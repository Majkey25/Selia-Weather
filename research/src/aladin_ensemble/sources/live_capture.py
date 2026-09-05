"""Freeze prospective provider forecasts without inventing model initialization times."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections.abc import Callable, Mapping, Sequence
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from math import isfinite
from pathlib import Path
from typing import Literal, cast
from urllib.parse import parse_qs, urlencode, urlsplit

from aladin_ensemble.align import station_distance_km
from aladin_ensemble.metrics import circular_mean_absolute_error, mean_absolute_error
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.sources.chmi_station import STATION_SOURCE, uses_standard_measurement_height
from aladin_ensemble.sources.noaa_isd import ISD_SOURCE
from aladin_ensemble.sources.official_runs import download_http_with_retry
from aladin_ensemble.sources.open_meteo_runs import canonical_value
from aladin_ensemble.sources.probe import FORECAST_URL
from aladin_ensemble.types import ForecastPoint, Observation
from aladin_ensemble.worldwide import WORLD_VARIABLES

MAX_RESPONSE_BYTES = 2_000_000
MAX_HOURLY_ROWS = 72
# Tolerate rounded station catalog coordinates, not a nearby station or model grid cell.
MAX_STATION_COORDINATE_DISTANCE_KM = 1.0
HEIGHT_ELEMENTS = {
    "temperature": "T", "dew_point": "Td", "wind_speed": "F", "wind_direction": "D",
}


@dataclass(frozen=True, slots=True)
class CapturedValue:
    model_id: str
    variable: str
    valid_time: str
    value: float | None
    unit: str
    interval_seconds: int


@dataclass(frozen=True, slots=True)
class CaptureError:
    model_id: str
    variable: str
    valid_time: str
    capture_lead_seconds: float
    forecast_value: float | None
    observed_value: float | None
    absolute_error: float | None
    status: Literal["paired", "missing_truth", "missing_forecast", "incompatible_truth"]
    truth_checksum: str | None


def evaluate_capture(
    manifest_path: Path,
    observations: Sequence[Observation],
    truth_payloads: Mapping[str, bytes],
) -> tuple[CaptureError, ...]:
    """Pair parser-produced station observations; raw hashes prove linkage, not authenticity."""
    manifest_bytes = _read_bounded(manifest_path)
    if hashlib.sha256(manifest_bytes).hexdigest() != manifest_path.stem:
        raise ValueError("capture manifest checksum mismatch")
    manifest = _mapping(json.loads(manifest_bytes))
    if (
        manifest.get("schema_version") != 1
        or manifest.get("kind") != "prospective_forecast_capture"
        or manifest.get("lead_reference") != "capture_completed"
        or manifest.get("model_initialization_time") is not None
        or manifest.get("calibration_eligible") is not False
        or manifest.get("truth") is not None
    ):
        raise ValueError("capture provenance contract is incompatible")
    checksum = manifest.get("source_sha256")
    if not isinstance(checksum, str) or re.fullmatch(r"[0-9a-f]{64}", checksum) is None:
        raise ValueError("capture raw checksum is invalid")
    expected_raw_path = f"raw/{checksum}.json"
    if manifest.get("raw_path") != expected_raw_path:
        raise ValueError("capture raw path mismatch")
    raw = _read_bounded(manifest_path.parent.parent / expected_raw_path)
    if hashlib.sha256(raw).hexdigest() != checksum:
        raise ValueError("capture raw checksum mismatch")
    captured_at = datetime.fromisoformat(_text(manifest.get("captured_at")))
    started_at = datetime.fromisoformat(_text(manifest.get("request_started_at")))
    _utc(captured_at)
    _utc(started_at)
    if captured_at < started_at:
        raise ValueError("capture clock is invalid")
    source_url = _text(manifest.get("source_url"))
    if not source_url.startswith(f"{FORECAST_URL}?"):
        raise ValueError("capture source endpoint is invalid")
    query = parse_qs(urlsplit(source_url).query)
    models = tuple(query.get("models", [""])[0].split(","))
    records = future_values(raw, models, captured_at)
    if manifest.get("values") != [asdict(record) for record in records]:
        raise ValueError("capture values differ from immutable raw data")
    point = _mapping(manifest.get("requested_point"))
    station_id = _text(point.get("point_id"))
    latitude, longitude = point.get("latitude"), point.get("longitude")
    if any(
        isinstance(value, bool) or not isinstance(value, int | float)
        for value in (latitude, longitude)
    ):
        raise ValueError("capture requested coordinates are invalid")
    requested = ForecastPoint(station_id, cast(float, latitude), cast(float, longitude))
    for name in ("latitude", "longitude"):
        if query.get(name) != [str(point.get(name))]:
            raise ValueError("capture requested coordinates mismatch")

    checked: set[str] = set()
    indexed: dict[tuple[str, datetime, str], list[Observation]] = {}
    for observation in observations:
        source_checksum = observation.source_checksum
        if observation.source not in {STATION_SOURCE, ISD_SOURCE} or source_checksum is None:
            raise ValueError("independent station provenance is required")
        if source_checksum not in checked:
            body = truth_payloads.get(source_checksum)
            if body is None or hashlib.sha256(body).hexdigest() != source_checksum:
                raise ValueError("independent station payload checksum mismatch")
            checked.add(source_checksum)
        key = observation.station_id, observation.valid_time, _variable(observation.variable)
        indexed.setdefault(key, []).append(observation)

    errors: list[CaptureError] = []
    for record in records:
        valid_time = datetime.fromisoformat(record.valid_time)
        variable = _variable(record.variable)
        forecast = canonical_value(variable, record.value, record.unit)
        candidates = indexed.get((station_id, valid_time, variable), [])
        height_element = HEIGHT_ELEMENTS.get(variable)
        compatible = [observation for observation in candidates if (
            observation.accumulation == ("interval" if record.interval_seconds else "instant")
            and observation.interval == (
                timedelta(seconds=record.interval_seconds) if record.interval_seconds else None
            )
            and station_distance_km(
                requested.latitude, requested.longitude,
                observation.latitude, observation.longitude,
            ) <= MAX_STATION_COORDINATE_DISTANCE_KM
            and (
                height_element is None
                or uses_standard_measurement_height(
                    height_element, observation.measurement_height_m,
                )
            )
        )]
        if len(compatible) > 1:
            raise ValueError("duplicate compatible station truth")
        observation = compatible[0] if compatible else None
        observed = None if observation is None else canonical_value(
            variable, observation.value, observation.unit,
        )
        error = None
        status: Literal["paired", "missing_truth", "missing_forecast", "incompatible_truth"]
        if forecast is None:
            status = "missing_forecast"
        elif observation is None and candidates:
            status = "incompatible_truth"
        elif observed is None:
            status = "missing_truth"
        else:
            status = "paired"
            metric = (
                circular_mean_absolute_error
                if variable == "wind_direction" else mean_absolute_error
            )
            error = metric((forecast,), (observed,))
        errors.append(CaptureError(
            record.model_id, variable, record.valid_time,
            (valid_time - captured_at).total_seconds(), forecast, observed, error, status,
            None if observation is None else observation.source_checksum,
        ))
    return tuple(errors)


def _variable(name: str) -> str:
    return {
        "temperature_2m": "temperature", "dew_point_2m": "dew_point",
        "wind_speed_10m": "wind_speed", "wind_direction_10m": "wind_direction",
        "pressure_msl": "sea_level_pressure",
    }.get(name, name)


def _read_bounded(path: Path) -> bytes:
    with path.open("rb") as source:
        data = source.read(MAX_RESPONSE_BYTES + 1)
    if len(data) > MAX_RESPONSE_BYTES:
        raise ValueError("capture file exceeds size limit")
    return data


def _text(value: JsonValue) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError("capture metadata text is missing")
    return value


def future_values(
    raw: bytes, models: tuple[str, ...], captured_at: datetime,
) -> tuple[CapturedValue, ...]:
    _utc(captured_at)
    root = _mapping(json.loads(raw))
    hourly = _mapping(root.get("hourly"))
    units = _mapping(root.get("hourly_units"))
    times = hourly.get("time")
    if not isinstance(times, list) or not 1 <= len(times) <= MAX_HOURLY_ROWS:
        raise ValueError("capture requires a bounded hourly timeline")
    valid_times: list[datetime] = []
    for timestamp in times:
        if isinstance(timestamp, bool) or not isinstance(timestamp, int):
            raise ValueError("capture timestamps must be Unix seconds")
        valid_times.append(datetime.fromtimestamp(timestamp, UTC))
    if valid_times != sorted(set(valid_times)) or any(
        value.minute or value.second or value > captured_at + timedelta(hours=MAX_HOURLY_ROWS)
        for value in valid_times
    ) or any(
        following - preceding != timedelta(hours=1)
        for preceding, following in zip(valid_times, valid_times[1:], strict=False)
    ):
        raise ValueError("capture timestamps must be unique consecutive hours")
    records: list[CapturedValue] = []
    for model_id in models:
        for variable in WORLD_VARIABLES:
            field = f"{variable}_{model_id}"
            values = hourly.get(field)
            unit = units.get(field)
            if (
                not isinstance(values, list) or len(values) != len(times)
                or not isinstance(unit, str) or not unit
            ):
                raise ValueError(f"capture response is incomplete for {field}")
            interval = 3600 if variable == "precipitation" else 0
            for valid_time, value in zip(valid_times, values, strict=True):
                if value is not None and (
                    isinstance(value, bool) or not isinstance(value, int | float)
                    or not isfinite(value)
                ):
                    raise ValueError(f"capture value is invalid for {field}")
                if captured_at >= valid_time - timedelta(seconds=interval):
                    continue
                records.append(CapturedValue(
                    model_id, variable, valid_time.isoformat(),
                    None if value is None else float(value), unit, interval,
                ))
    if not records or not any(record.value is not None for record in records):
        raise ValueError("capture contains no usable genuinely future forecasts")
    return tuple(records)


def capture(
    point: ForecastPoint,
    models: tuple[str, ...],
    output: Path,
    *,
    fetch: Callable[[str], bytes] | None = None,
    now: Callable[[], datetime] = lambda: datetime.now(UTC),
) -> Path:
    if not 2 <= len(models) <= 12 or len(set(models)) != len(models) or any(
        re.fullmatch(r"[a-z0-9_]{1,64}", model) is None for model in models
    ):
        raise ValueError("capture needs two through twelve unique provider model IDs")
    parameters = {
        "latitude": str(point.latitude), "longitude": str(point.longitude),
        "models": ",".join(models), "hourly": ",".join(WORLD_VARIABLES),
        "forecast_days": "3", "timezone": "GMT", "timeformat": "unixtime",
    }
    url = f"{FORECAST_URL}?{urlencode(parameters)}"
    started_at = now()
    _utc(started_at)
    raw = fetch(url) if fetch is not None else download_http_with_retry(
        url, timeout=20, max_bytes=MAX_RESPONSE_BYTES, attempts=1,
    )
    captured_at = now()
    _utc(captured_at)
    if captured_at < started_at or len(raw) > MAX_RESPONSE_BYTES:
        raise ValueError("capture clock or response size is invalid")
    records = future_values(raw, models, captured_at)
    checksum = hashlib.sha256(raw).hexdigest()
    manifest: dict[str, JsonValue] = {
        "schema_version": 1, "kind": "prospective_forecast_capture",
        "requested_point": cast(dict[str, JsonValue], asdict(point)),
        "request_started_at": started_at.isoformat(), "captured_at": captured_at.isoformat(),
        "model_initialization_time": None, "lead_reference": "capture_completed",
        "source_url": url, "source_sha256": checksum,
        "raw_path": f"raw/{checksum}.json",
        "values": cast(list[JsonValue], [asdict(record) for record in records]),
        "truth": None, "calibration_eligible": False,
    }
    body = (json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n").encode()
    _write_immutable(output / "raw" / f"{checksum}.json", raw)
    path = output / "captures" / f"{hashlib.sha256(body).hexdigest()}.json"
    _write_immutable(path, body)
    return path


def _mapping(value: JsonValue) -> dict[str, JsonValue]:
    if not isinstance(value, dict):
        raise ValueError("capture requires JSON objects")
    return value


def _utc(value: datetime) -> None:
    if value.tzinfo is None or value.utcoffset() != UTC.utcoffset(value):
        raise ValueError("capture time must be timezone-aware UTC")


def _write_immutable(path: Path, body: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("xb") as target:
            target.write(body)
    except FileExistsError:
        if path.read_bytes() != body:
            raise ValueError("capture archive checksum mismatch") from None


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--station-id", required=True)
    parser.add_argument("--latitude", type=float, required=True)
    parser.add_argument("--longitude", type=float, required=True)
    parser.add_argument("--models", nargs="+", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    point = ForecastPoint(
        cast(str, args.station_id), cast(float, args.latitude), cast(float, args.longitude),
    )
    path = capture(point, tuple(cast(list[str], args.models)), cast(Path, args.output))
    print(path)


if __name__ == "__main__":
    main()
