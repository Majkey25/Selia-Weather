from __future__ import annotations

import hashlib
import json
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from io import StringIO
from math import degrees, isclose
from pathlib import Path
from typing import cast

import pytest

from aladin_ensemble.registry import JsonValue
from aladin_ensemble.sources.live_capture import capture, evaluate_capture, future_values
from aladin_ensemble.sources.noaa_isd import parse_isd_observations
from aladin_ensemble.types import ForecastPoint, Observation
from aladin_ensemble.worldwide import WORLD_VARIABLES

CAPTURED = datetime(2026, 9, 5, 12, 30, tzinfo=UTC)
MODELS = ("icon_seamless", "gfs_seamless")
POINT = ForecastPoint("0-203-0-11775", 49.2, 17.7)


def payload() -> bytes:
    hourly: dict[str, JsonValue] = {
        "time": [int(datetime(2026, 9, 5, hour, tzinfo=UTC).timestamp()) for hour in range(12, 16)],
    }
    units: dict[str, JsonValue] = {}
    for model in MODELS:
        for variable in WORLD_VARIABLES:
            field = f"{variable}_{model}"
            hourly[field] = [1.0, 2.0, None, 4.0]
            units[field] = {
                "temperature_2m": "°C", "dew_point_2m": "°C", "pressure_msl": "hPa",
                "wind_speed_10m": "km/h", "wind_direction_10m": "°", "precipitation": "mm",
            }[variable]
    return json.dumps({"hourly": hourly, "hourly_units": units}).encode()


def test_capture_keeps_future_rows_and_excludes_already_started_rain_interval() -> None:
    values = future_values(payload(), MODELS, CAPTURED)
    temperature = [value for value in values if value.variable == "temperature_2m"]
    rain = [value for value in values if value.variable == "precipitation"]

    assert len(temperature) == 6
    assert min(value.valid_time for value in temperature) == "2026-09-05T13:00:00+00:00"
    assert len(rain) == 4
    assert min(value.valid_time for value in rain) == "2026-09-05T14:00:00+00:00"
    assert all(value.interval_seconds == 3600 for value in rain)
    assert any(value.value is None for value in rain)


def test_capture_records_real_receipt_time_without_fabricating_initialization(
    tmp_path: Path,
) -> None:
    raw = payload()
    times = iter((CAPTURED.replace(minute=29), CAPTURED))
    manifest_path = capture(POINT, MODELS, tmp_path, fetch=lambda _: raw, now=lambda: next(times))
    manifest = cast(dict[str, JsonValue], json.loads(manifest_path.read_bytes()))

    assert manifest["captured_at"] == CAPTURED.isoformat()
    assert manifest["model_initialization_time"] is None
    assert manifest["lead_reference"] == "capture_completed"
    assert manifest["truth"] is None
    assert manifest["calibration_eligible"] is False
    assert manifest["source_sha256"] == hashlib.sha256(raw).hexdigest()
    assert (tmp_path / str(manifest["raw_path"])).read_bytes() == raw


def test_capture_is_immutable_and_rejects_corrupt_archive(tmp_path: Path) -> None:
    raw = payload()
    first = capture(POINT, MODELS, tmp_path, fetch=lambda _: raw, now=lambda: CAPTURED)
    assert capture(POINT, MODELS, tmp_path, fetch=lambda _: raw, now=lambda: CAPTURED) == first
    raw_path = tmp_path / "raw" / f"{hashlib.sha256(raw).hexdigest()}.json"
    raw_path.write_bytes(b"corrupt")
    with pytest.raises(ValueError, match="checksum"):
        capture(POINT, MODELS, tmp_path, fetch=lambda _: raw, now=lambda: CAPTURED)
    assert raw_path.read_bytes() == b"corrupt"


def test_capture_rejects_only_past_data_without_creating_archive(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="future"):
        capture(
            POINT, MODELS, tmp_path, fetch=lambda _: payload(), now=lambda: CAPTURED.replace(day=6),
        )
    assert list(tmp_path.iterdir()) == []


def test_capture_rejects_backward_clock_and_naive_timestamp(tmp_path: Path) -> None:
    times = iter((CAPTURED, CAPTURED.replace(minute=29)))
    with pytest.raises(ValueError, match="clock"):
        capture(POINT, MODELS, tmp_path, fetch=lambda _: payload(), now=lambda: next(times))
    with pytest.raises(ValueError, match="UTC"):
        future_values(payload(), MODELS, CAPTURED.replace(tzinfo=None))


def test_capture_rejects_truncated_or_nonfinite_payload() -> None:
    root = json.loads(payload())
    root["hourly"]["precipitation_icon_seamless"] = [1.0]
    with pytest.raises(ValueError, match="incomplete"):
        future_values(json.dumps(root).encode(), MODELS, CAPTURED)
    root = json.loads(payload())
    root["hourly"]["temperature_2m_icon_seamless"][1] = "NaN"
    with pytest.raises(ValueError, match="invalid"):
        future_values(json.dumps(root).encode(), MODELS, CAPTURED)


def independent_truth() -> tuple[tuple[Observation, ...], dict[str, bytes]]:
    header = (
        '"STATION","DATE","LATITUDE","LONGITUDE","ELEVATION",'
        '"TMP","DEW","SLP","WND","VIS","AA1"\n'
    )
    rows = "".join(
        f'"10637099999","2026-09-05T{hour}:00:00","50.026","8.543","110.9",'
        '"+0010,1","+0000,1","10123,1","359,1,N,0034,1","010000,1,9,9","01,0012,9,1"\n'
        for hour in (13, 15)
    )
    raw = (header + rows).encode()
    checksum = hashlib.sha256(raw).hexdigest()
    observations = tuple(parse_isd_observations(StringIO(raw.decode()), checksum))
    return observations, {checksum: raw}


def test_pairs_independent_parser_truth_with_units_circular_wind_and_capture_lead(
    tmp_path: Path,
) -> None:
    point = ForecastPoint("10637099999", 50.026, 8.543)
    path = capture(point, MODELS, tmp_path, fetch=lambda _: payload(), now=lambda: CAPTURED)
    observations, truth = independent_truth()
    rows = evaluate_capture(path, observations, truth)
    first_hour = {
        row.variable: row for row in rows
        if row.model_id == MODELS[0] and row.valid_time == "2026-09-05T13:00:00+00:00"
    }

    assert first_hour["temperature"].absolute_error == 1.0
    assert first_hour["temperature"].capture_lead_seconds == 1800
    assert first_hour["temperature"].status == "paired"
    assert first_hour["temperature"].truth_checksum in truth
    observed_wind = first_hour["wind_speed"].observed_value
    assert observed_wind is not None and isclose(observed_wind, 12.24)
    assert first_hour["wind_direction"].absolute_error == 3.0
    rain = next(row for row in rows if row.variable == "precipitation" and "T15:" in row.valid_time)
    assert rain.absolute_error is not None and isclose(rain.absolute_error, 2.8)


def test_missing_or_wrong_station_time_truth_never_becomes_zero_error(tmp_path: Path) -> None:
    point = ForecastPoint("10637099999", 50.026, 8.543)
    path = capture(point, MODELS, tmp_path, fetch=lambda _: payload(), now=lambda: CAPTURED)
    observations, truth = independent_truth()
    wrong_station = tuple(replace(row, station_id="different") for row in observations)
    wrong_time = tuple(
        replace(row, valid_time=row.valid_time + timedelta(minutes=1)) for row in observations
    )
    for unavailable in ((), wrong_station, wrong_time):
        rows = evaluate_capture(path, unavailable, truth)
        assert all(row.absolute_error is None for row in rows)
        assert {row.status for row in rows} == {"missing_truth", "missing_forecast"}


def test_rejects_ten_minute_rain_as_hourly_truth(tmp_path: Path) -> None:
    point = ForecastPoint("10637099999", 50.026, 8.543)
    path = capture(point, MODELS, tmp_path, fetch=lambda _: payload(), now=lambda: CAPTURED)
    observations, truth = independent_truth()
    ten_minute = tuple(
        replace(row, interval=timedelta(minutes=10)) if row.variable == "precipitation" else row
        for row in observations
    )
    rain = next(
        row for row in evaluate_capture(path, ten_minute, truth)
        if row.variable == "precipitation" and "T15:" in row.valid_time
    )
    assert rain.status == "incompatible_truth"
    assert rain.absolute_error is None


def test_rejects_fused_truth_missing_provenance_and_corrupt_capture(tmp_path: Path) -> None:
    point = ForecastPoint("10637099999", 50.026, 8.543)
    path = capture(point, MODELS, tmp_path, fetch=lambda _: payload(), now=lambda: CAPTURED)
    observations, truth = independent_truth()
    with pytest.raises(ValueError, match="provenance"):
        evaluate_capture(path, (replace(observations[0], source="FUSED_MODEL"),), truth)
    with pytest.raises(ValueError, match="checksum"):
        evaluate_capture(path, observations, {})
    with pytest.raises(ValueError, match="checksum"):
        evaluate_capture(path, observations, {key: b"corrupt" for key in truth})
    with pytest.raises(ValueError, match="duplicate"):
        evaluate_capture(path, (*observations, observations[0]), truth)
    manifest = json.loads(path.read_bytes())
    raw_path = tmp_path / manifest["raw_path"]
    raw_path.write_bytes(b"corrupt")
    with pytest.raises(ValueError, match="raw checksum"):
        evaluate_capture(path, observations, truth)
    path.write_bytes(b"{}")
    with pytest.raises(ValueError, match="manifest checksum"):
        evaluate_capture(path, observations, truth)


def test_rejects_unsupported_truth_units(tmp_path: Path) -> None:
    point = ForecastPoint("10637099999", 50.026, 8.543)
    path = capture(point, MODELS, tmp_path, fetch=lambda _: payload(), now=lambda: CAPTURED)
    observations, truth = independent_truth()
    with pytest.raises(ValueError, match="unsupported unit"):
        evaluate_capture(path, (replace(observations[0], unit="unknown"),), truth)


def test_capture_values_must_match_raw_even_if_manifest_hash_is_recomputed(tmp_path: Path) -> None:
    path = capture(POINT, MODELS, tmp_path, fetch=lambda _: payload(), now=lambda: CAPTURED)
    manifest = json.loads(path.read_bytes())
    manifest["values"][0]["value"] = 999.0
    altered = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    forged = path.with_name(f"{hashlib.sha256(altered).hexdigest()}.json")
    forged.write_bytes(altered)
    with pytest.raises(ValueError, match="values differ"):
        evaluate_capture(forged, (), {})


def test_same_station_id_at_distant_coordinates_does_not_pair(tmp_path: Path) -> None:
    point = ForecastPoint("10637099999", 50.026, 8.543)
    path = capture(point, MODELS, tmp_path, fetch=lambda _: payload(), now=lambda: CAPTURED)
    observations, truth = independent_truth()
    distant = tuple(replace(row, latitude=-33.9, longitude=151.2) for row in observations)
    rows = evaluate_capture(path, distant, truth)
    assert all(row.absolute_error is None for row in rows)
    assert any(row.status == "incompatible_truth" for row in rows)


def test_station_coordinate_tolerance_admits_rounding_but_not_nearby_stations(
    tmp_path: Path,
) -> None:
    point = ForecastPoint("10637099999", 50.026, 8.543)
    path = capture(point, MODELS, tmp_path, fetch=lambda _: payload(), now=lambda: CAPTURED)
    observations, truth = independent_truth()
    for north_km, accepted in ((0.05, True), (0.999, True), (1.001, False)):
        shifted = tuple(
            replace(row, latitude=point.latitude + degrees(north_km / 6371.0088))
            for row in observations
        )
        rows = evaluate_capture(path, shifted, truth)
        assert any(row.status == "paired" for row in rows) == accepted
        if not accepted:
            assert all(row.absolute_error is None for row in rows)


def test_temperature_and_wind_require_standard_sensor_height(tmp_path: Path) -> None:
    point = ForecastPoint("10637099999", 50.026, 8.543)
    path = capture(point, MODELS, tmp_path, fetch=lambda _: payload(), now=lambda: CAPTURED)
    observations, truth = independent_truth()
    for variable, alias, nonstandard, standard in (
        ("temperature_2m", "temperature", 0.05, 2.06),
        ("dew_point_2m", "dew_point", 0.05, 2.06),
        ("wind_speed_10m", "wind_speed", 20.0, 10.56),
        ("wind_direction_10m", "wind_direction", 20.0, 9.55),
    ):
        original = next(row for row in observations if row.variable == variable)
        for height in (nonstandard, None):
            wrong = replace(original, variable=alias, measurement_height_m=height)
            rejected = [
                row for row in evaluate_capture(path, (wrong,), truth) if row.variable == alias
            ]
            assert all(row.absolute_error is None for row in rejected)
            assert any(row.status == "incompatible_truth" for row in rejected)
        normal = replace(original, variable=alias, measurement_height_m=standard)
        accepted = [
            row for row in evaluate_capture(path, (normal,), truth) if row.variable == alias
        ]
        assert any(row.status == "paired" for row in accepted)
