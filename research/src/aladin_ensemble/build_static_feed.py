from __future__ import annotations

import gzip
import hashlib
import json
from collections import defaultdict
from datetime import UTC, datetime, timedelta
from math import floor, isclose, isfinite
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import cast

from .static_feed import (
    FeedGrid,
    FeedManifest,
    FeedRun,
    FeedSource,
    FeedState,
    decode_manifest,
    decode_source_registry,
    encode_manifest,
    encode_source_registry,
    validate_source_registry,
)
from .types import ForecastValue


def build_static_feed(
    *,
    values: tuple[ForecastValue, ...],
    sources: tuple[FeedSource, ...],
    grid: FeedGrid,
    run_id: str,
    generated_at: datetime,
    expires_at: datetime,
    state: FeedState,
    calibration: bytes | None,
    dataset_manifest_hash: str | None,
    output_dir: Path,
    max_bytes: int = 800_000_000,
) -> FeedManifest:
    if output_dir.exists():
        raise ValueError("output_dir already exists")
    if max_bytes <= 0:
        raise ValueError("max_bytes must be positive")
    run = FeedRun(run_id, generated_at, expires_at, state)
    if state == "production" and calibration is None:
        raise ValueError("production feed requires calibration")
    validate_source_registry(sources, production=state == "production")
    source_by_model = {source.model_id: source for source in sources if source.enabled}
    if len(source_by_model) != sum(source.enabled for source in sources):
        raise ValueError("enabled model IDs must be unique")
    calibration_dataset_hash = None
    if calibration is not None:
        calibration_dataset_hash = _validate_calibration(calibration, frozenset(source_by_model))
    if state == "production" and calibration_dataset_hash != dataset_manifest_hash:
        raise ValueError("calibration dataset manifest hash does not match the feed dataset")

    tiles: dict[tuple[int, int], list[tuple[FeedSource, ForecastValue]]] = defaultdict(list)
    identities: set[tuple[object, ...]] = set()
    used_sources: dict[str, FeedSource] = {}
    units: dict[str, str] = {}
    axes: dict[tuple[str, str, float, float], set[datetime]] = defaultdict(set)
    for forecast in values:
        identity = (
            forecast.model_id,
            forecast.run_time,
            forecast.valid_time,
            forecast.latitude,
            forecast.longitude,
            forecast.variable,
        )
        if identity in identities:
            raise ValueError("duplicate forecast value")
        identities.add(identity)
        source = source_by_model.get(forecast.model_id)
        if source is None:
            raise ValueError(f"forecast model is not enabled: {forecast.model_id}")
        if forecast.variable not in source.variables:
            raise ValueError(f"source does not provide variable: {forecast.variable}")
        if not forecast.unit:
            raise ValueError("forecast unit is required")
        previous_unit = units.setdefault(forecast.variable, forecast.unit)
        if previous_unit != forecast.unit:
            raise ValueError(f"mixed units for variable: {forecast.variable}")
        lead_seconds = (forecast.valid_time - forecast.run_time).total_seconds()
        horizon = source.forecast_horizon_hours
        if horizon is None or lead_seconds < 0 or lead_seconds > horizon * 3_600:
            raise ValueError("forecast lead is outside source horizon")
        if forecast.run_time > generated_at:
            raise ValueError("forecast run cannot follow feed generation")
        tile = _tile_for(grid, forecast.latitude, forecast.longitude)
        tiles[tile].append((source, forecast))
        axes[(forecast.model_id, forecast.variable, forecast.latitude, forecast.longitude)].add(
            forecast.valid_time
        )
        used_sources[source.source_id] = source
    if not tiles:
        raise ValueError("feed requires at least one forecast value")
    _validate_axes(axes)

    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=output_dir.parent, prefix=".feed-") as temporary:
        staging = Path(temporary) / "site"
        staging.mkdir()
        (staging / "licences.json").write_text(
            encode_source_registry(tuple(used_sources.values())),
            encoding="utf-8",
        )
        calibration_checksum = None
        if calibration is not None:
            calibration_target = staging / "calibration" / "ensemble_weights.json"
            calibration_target.parent.mkdir()
            calibration_target.write_bytes(calibration)
            calibration_checksum = hashlib.sha256(calibration).hexdigest()
        checksums: list[tuple[str, str]] = []
        for (tile_y, tile_x), tile_values in sorted(tiles.items()):
            relative = Path("tiles") / run.run_id / str(tile_y) / f"{tile_x}.json.gz"
            payload = _encode_tile(run.run_id, tile_y, tile_x, tile_values)
            target = staging / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)
            checksums.append((relative.as_posix(), hashlib.sha256(payload).hexdigest()))
        manifest = FeedManifest(
            schema_version=1,
            grid=grid,
            run=run,
            sources=tuple(sorted(used_sources.values(), key=lambda source: source.source_id)),
            tile_checksums=tuple(checksums),
            calibration_checksum=calibration_checksum,
            dataset_manifest_hash=calibration_dataset_hash,
        )
        (staging / "manifest.json").write_text(encode_manifest(manifest), encoding="utf-8")
        total_bytes = sum(path.stat().st_size for path in staging.rglob("*") if path.is_file())
        if total_bytes > max_bytes:
            raise ValueError("feed exceeds size limit")
        staging.replace(output_dir)
    return manifest


def verify_static_feed(output_dir: Path) -> FeedManifest:
    manifest_path = output_dir / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError("feed manifest is missing")
    manifest = decode_manifest(manifest_path.read_text(encoding="utf-8"))
    licences_path = output_dir / "licences.json"
    if not licences_path.is_file():
        raise ValueError("feed licences are missing")
    licences = decode_source_registry(licences_path.read_text(encoding="utf-8"))
    if tuple(sorted(licences, key=lambda source: source.source_id)) != manifest.sources:
        raise ValueError("feed licences do not match manifest sources")
    for relative, expected in manifest.tile_checksums:
        path = output_dir / relative
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f"tile checksum mismatch: {relative}")
    if manifest.calibration_checksum is not None:
        calibration = output_dir / "calibration" / "ensemble_weights.json"
        if (
            not calibration.is_file()
            or hashlib.sha256(calibration.read_bytes()).hexdigest()
            != manifest.calibration_checksum
        ):
            raise ValueError("calibration checksum mismatch")
    return manifest


def _validate_axes(axes: dict[tuple[str, str, float, float], set[datetime]]) -> None:
    expected: dict[tuple[str, str], set[datetime]] = {}
    for (model_id, variable, _, _), validity_times in axes.items():
        key = (model_id, variable)
        model_axis = expected.setdefault(key, validity_times)
        if model_axis != validity_times:
            raise ValueError(f"mixed validity axis for {model_id}/{variable}")


def _validate_calibration(value: bytes, eligible_models: frozenset[str]) -> str:
    try:
        root = _json_object(cast(object, json.loads(value.decode("utf-8"))), "calibration")
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("calibration is not valid UTF-8 JSON") from error
    expected = {
        "dataset_manifest_hash",
        "expires_at",
        "generated_at",
        "model_contract_hash",
        "models",
        "schema_version",
        "segments",
    }
    if set(root) != expected or root["schema_version"] != 2:
        raise ValueError("calibration schema is invalid")
    dataset_hash = root["dataset_manifest_hash"]
    _checksum(dataset_hash, "dataset_manifest_hash")
    _checksum(root["model_contract_hash"], "model_contract_hash")
    generated_at = _iso_time(root["generated_at"], "generated_at")
    expires_at = _iso_time(root["expires_at"], "expires_at")
    if expires_at <= generated_at or expires_at - generated_at > timedelta(days=90):
        raise ValueError("calibration validity window is invalid")
    models = root["models"]
    segments = root["segments"]
    if not isinstance(models, list) or not models or not isinstance(segments, list) or not segments:
        raise ValueError("calibration models and segments are required")
    model_ids: set[str] = set()
    for item in cast(list[object], models):
        model = _json_object(item, "calibration model")
        if set(model) != {"maximum_run_age_hours", "model_id", "resolution_km"}:
            raise ValueError("calibration model contract schema is invalid")
        model_id = model.get("model_id")
        if not isinstance(model_id, str) or not model_id:
            raise ValueError("calibration model_id is invalid")
        maximum_age = model["maximum_run_age_hours"]
        resolution = model["resolution_km"]
        if not _positive_integer(maximum_age) or (
            isinstance(resolution, bool)
            or not isinstance(resolution, int | float)
            or not isfinite(resolution)
            or resolution <= 0
        ):
            raise ValueError("calibration model contract is invalid")
        if model_id in model_ids:
            raise ValueError("calibration model_id is duplicated")
        model_ids.add(model_id)
    unknown = model_ids.difference(eligible_models)
    if unknown:
        raise ValueError(f"unknown calibration model: {sorted(unknown)}")
    selectors: set[tuple[object, ...]] = set()
    for item in cast(list[object], segments):
        selector = _validate_runtime_segment(item, model_ids)
        if selector in selectors:
            raise ValueError("calibration segment selector is duplicated")
        selectors.add(selector)
    return cast(str, dataset_hash)


def _validate_runtime_segment(
    value: object,
    model_ids: set[str],
) -> tuple[object, ...]:
    segment = _json_object(value, "calibration segment")
    expected = {
        "fallback_model",
        "holdout",
        "minimum_source_count",
        "mode",
        "selector",
        "truth_class",
        "weights",
    }
    if set(segment) != expected or segment["mode"] != "blend":
        raise ValueError("calibration segment schema is invalid")
    holdout = _json_object(segment["holdout"], "calibration holdout")
    if holdout.get("accepted") is not True or not _positive_integer(holdout.get("sample_count")):
        raise ValueError("calibration segment holdout is invalid")
    if cast(int, holdout["sample_count"]) < 30:
        raise ValueError("calibration segment holdout is too small")
    truth_class = segment["truth_class"]
    if truth_class not in {"station", "radar_gauge", "satellite_precipitation", "reanalysis"}:
        raise ValueError("calibration truth class is invalid")
    weights = _json_object(segment["weights"], "calibration weights")
    if not weights or not set(weights).issubset(model_ids):
        raise ValueError("calibration weights reference an unknown model")
    numeric_weights: list[float] = []
    for weight in weights.values():
        if isinstance(weight, bool) or not isinstance(weight, int | float) or not isfinite(weight):
            raise ValueError("calibration weight is invalid")
        numeric_weights.append(float(weight))
    if any(weight < 0 for weight in numeric_weights) or not isclose(
        sum(numeric_weights), 1.0, abs_tol=1e-8
    ):
        raise ValueError("calibration weights are not normalized")
    minimum = segment["minimum_source_count"]
    if not _positive_integer(minimum) or not 2 <= cast(int, minimum) <= sum(
        weight > 0 for weight in numeric_weights
    ):
        raise ValueError("calibration minimum source count is invalid")
    fallback = segment["fallback_model"]
    if fallback not in model_ids | {"best_match"}:
        raise ValueError("calibration fallback model is unknown")
    selector = _json_object(segment["selector"], "calibration selector")
    expected_selector = {
        "maximum_lead_hours",
        "minimum_lead_hours",
        "months",
        "region",
        "variable",
    }
    if set(selector) != expected_selector:
        raise ValueError("calibration selector schema is invalid")
    minimum_lead = selector["minimum_lead_hours"]
    maximum_lead = selector["maximum_lead_hours"]
    if (
        not isinstance(minimum_lead, int)
        or isinstance(minimum_lead, bool)
        or not isinstance(maximum_lead, int)
        or isinstance(maximum_lead, bool)
        or minimum_lead < 0
        or maximum_lead < minimum_lead
    ):
        raise ValueError("calibration lead range is invalid")
    months = selector["months"]
    if not isinstance(months, list):
        raise ValueError("calibration months are invalid")
    typed_months = cast(list[object], months)
    if not typed_months or any(
        not isinstance(month, int)
        or isinstance(month, bool)
        or month not in range(1, 13)
        for month in typed_months
    ):
        raise ValueError("calibration months are invalid")
    integer_months = cast(list[int], typed_months)
    if integer_months != sorted(set(integer_months)):
        raise ValueError("calibration months are invalid")
    if selector["region"] not in _CALIBRATION_REGIONS:
        raise ValueError("calibration region is invalid")
    variable = selector["variable"]
    if not isinstance(variable, str) or not variable:
        raise ValueError("calibration variable is invalid")
    return selector["region"], variable, minimum_lead, maximum_lead, tuple(integer_months)


def _checksum(value: object, name: str) -> None:
    if not isinstance(value, str) or len(value) != 64 or any(
        character not in "0123456789abcdef" for character in value
    ):
        raise ValueError(f"calibration {name} is invalid")


def _iso_time(value: object, name: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError(f"calibration {name} is invalid")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"calibration {name} is invalid") from error
    if parsed.tzinfo is None or parsed.utcoffset() != UTC.utcoffset(parsed):
        raise ValueError(f"calibration {name} must be UTC")
    return parsed


def _positive_integer(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


_CALIBRATION_REGIONS = {
    "CZECHIA",
    "EUROPE",
    "NORTH_AMERICA",
    "SOUTH_AMERICA",
    "AFRICA",
    "SOUTH_CENTRAL_ASIA",
    "EAST_ASIA",
    "NORTHERN_ASIA",
    "OCEANIA",
    "GLOBAL",
}


def _json_object(value: object, name: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError(f"{name} must be an object")
    mapping = cast(dict[object, object], value)
    if not all(isinstance(key, str) for key in mapping):
        raise ValueError(f"{name} must use string keys")
    return cast(dict[str, object], mapping)


def _tile_for(grid: FeedGrid, latitude: float, longitude: float) -> tuple[int, int]:
    if not grid.south <= latitude <= grid.north or not grid.west <= longitude <= grid.east:
        raise ValueError("forecast point is outside feed grid")
    latitude_index = (latitude - grid.south) / grid.step
    longitude_index = (longitude - grid.west) / grid.step
    if not isclose(latitude_index, round(latitude_index), abs_tol=1e-7) or not isclose(
        longitude_index, round(longitude_index), abs_tol=1e-7
    ):
        raise ValueError("forecast point is not aligned to feed grid")
    return (
        floor((latitude - grid.south) / grid.tile_step),
        floor((longitude - grid.west) / grid.tile_step),
    )


def _encode_tile(
    run_id: str,
    tile_y: int,
    tile_x: int,
    values: list[tuple[FeedSource, ForecastValue]],
) -> bytes:
    rows = [
        {
            "elevation_m": forecast.elevation_m,
            "latitude": forecast.latitude,
            "longitude": forecast.longitude,
            "model_id": forecast.model_id,
            "run_time": _format_time(forecast.run_time),
            "source_id": source.source_id,
            "unit": forecast.unit,
            "valid_time": _format_time(forecast.valid_time),
            "value": forecast.value,
            "variable": forecast.variable,
        }
        for source, forecast in sorted(values, key=_value_key)
    ]
    document = {
        "run_id": run_id,
        "schema_version": 1,
        "tile_x": tile_x,
        "tile_y": tile_y,
        "values": rows,
    }
    text = json.dumps(document, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
    return gzip.compress(text.encode("utf-8"), compresslevel=9, mtime=0)


def _value_key(value: tuple[FeedSource, ForecastValue]) -> tuple[object, ...]:
    source, forecast = value
    return (
        source.source_id,
        forecast.variable,
        forecast.valid_time,
        forecast.latitude,
        forecast.longitude,
        forecast.run_time,
    )


def _format_time(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")
