from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from math import isclose
from pathlib import Path
from typing import BinaryIO

import pytest

from aladin_ensemble.sources.grib_points import (
    GeoPoint,
    SampledMessage,
    SampledPoint,
    build_grib_point_index,
    convert_grib_unit,
    decode_grib_points,
    elevation_by_point,
    normalize_wind_direction,
    regular_grid_points,
    to_forecast_values,
    to_wind_component_values,
)


@dataclass(frozen=True, slots=True)
class Nearest:
    lat: float
    lon: float
    value: float
    distance: float
    index: int


class FakeEccodes:
    def __init__(self, *, bad_count: bool = False, grid_hash: str = "grid-a") -> None:
        self.handles: list[int | None] = [1, 2, None]
        self.released: list[int] = []
        self.bad_count = bad_count
        self.grid_hash = grid_hash
        self.nearest_calls = 0
        self.element_calls = 0

    def new_from_file(self, source: BinaryIO) -> int | None:
        return self.handles.pop(0)

    def get(self, handle: int, key: str) -> object:
        values: dict[tuple[int, str], object] = {
            (1, "dataDate"): 20260829,
            (1, "dataTime"): 1200,
            (1, "validityDate"): 20260829,
            (1, "validityTime"): 1300,
            (1, "units"): "K",
            (1, "stepType"): "instant",
            (1, "startStep"): "60m",
            (1, "endStep"): 1,
            (2, "dataDate"): 20260829,
            (2, "dataTime"): 1200,
            (2, "validityDate"): 20260829,
            (2, "validityTime"): 1400,
            (2, "units"): "K",
            (2, "stepType"): "instant",
            (2, "startStep"): 2,
            (2, "endStep"): 2,
        }
        if key == "md5GridSection":
            return self.grid_hash
        return values[(handle, key)]

    def find_nearest_multiple(
        self,
        handle: int,
        is_lsm: bool,
        latitudes: tuple[float, ...],
        longitudes: tuple[float, ...],
    ) -> tuple[Nearest, ...]:
        self.nearest_calls += 1
        values = tuple(
            Nearest(latitude + 0.01, longitude + 0.01, 280.0 + handle + index, 1.5, index)
            for index, (latitude, longitude) in enumerate(
                zip(latitudes, longitudes, strict=True)
            )
        )
        return values[:-1] if self.bad_count else values

    def get_elements(
        self,
        handle: int,
        key: str,
        indexes: tuple[int, ...],
    ) -> tuple[float, ...]:
        self.element_calls += 1
        assert key == "values"
        return tuple(280.0 + handle + index for index in indexes)

    def release(self, handle: int) -> None:
        self.released.append(handle)


def test_decodes_all_messages_and_keeps_requested_coordinates(tmp_path: Path) -> None:
    path = tmp_path / "field.grib2"
    path.write_bytes(b"GRIB-test-7777")
    api = FakeEccodes()

    messages = decode_grib_points(
        path,
        (GeoPoint(49.0, 14.0), GeoPoint(50.0, 15.0)),
        api=api,
    )

    assert len(messages) == 2
    assert messages[0].run_time.isoformat() == "2026-08-29T12:00:00+00:00"
    assert messages[0].valid_time.isoformat() == "2026-08-29T13:00:00+00:00"
    assert messages[0].unit == "K"
    assert messages[0].step_type == "instant"
    assert messages[0].start_step_hours == 1
    assert messages[0].end_step_hours == 1
    assert messages[0].values[0].latitude == 49.0
    assert messages[0].values[0].source_latitude == 49.01
    assert messages[1].values[1].value == 283.0
    assert api.released == [1, 2]


def test_releases_handle_when_result_count_is_wrong(tmp_path: Path) -> None:
    path = tmp_path / "field.grib2"
    path.write_bytes(b"GRIB-test-7777")
    api = FakeEccodes(bad_count=True)

    with pytest.raises(ValueError, match="point count"):
        decode_grib_points(path, (GeoPoint(49.0, 14.0), GeoPoint(50.0, 15.0)), api=api)

    assert api.released == [1]


def test_builds_complete_deterministic_czech_grid() -> None:
    points = regular_grid_points(48.45, 51.2, 11.9, 19.0, 0.05)

    assert len(points) == 8_008
    assert points[0] == GeoPoint(48.45, 11.9)
    assert points[-1] == GeoPoint(51.2, 19.0)
    assert len(set(points)) == len(points)


def test_converts_sampled_messages_to_canonical_forecast_values(tmp_path: Path) -> None:
    path = tmp_path / "field.grib2"
    path.write_bytes(b"GRIB-test-7777")
    points = (GeoPoint(49.0, 14.0), GeoPoint(50.0, 15.0))
    messages = decode_grib_points(path, points, api=FakeEccodes())

    values = to_forecast_values(
        messages,
        model_id="noaa_gfs",
        variable="temperature_2m",
        canonical_unit="°C",
        elevation_by_point={points[0]: 250.0, points[1]: 300.0},
    )

    assert len(values) == 4
    assert values[0].model_id == "noaa_gfs"
    assert values[0].elevation_m == 250.0
    assert values[0].unit == "°C"
    assert values[0].value is not None
    assert isclose(values[0].value, 7.85)
    assert convert_grib_unit(5.0, "m s**-1", "m/s") == 5.0
    assert convert_grib_unit(90.0, "Degree true", "°") == 90.0
    assert isclose(normalize_wind_direction(360.023_885_7), 0.023_885_7)
    with pytest.raises(ValueError, match="direction"):
        normalize_wind_direction(361.0)


def test_converts_cumulative_precipitation_to_intervals() -> None:
    run_time = datetime(2026, 8, 29, 0, tzinfo=UTC)
    point = GeoPoint(50.0, 14.0)
    messages = tuple(
        SampledMessage(
            run_time=run_time,
            valid_time=run_time.replace(hour=lead),
            unit="kg/m²",
            step_type="accum",
            start_step_hours=0,
            end_step_hours=lead,
            values=(SampledPoint(50.0, 14.0, 50.0, 14.0, 0.0, amount),),
        )
        for lead, amount in ((0, 0.0), (6, 3.0), (12, 5.0), (18, 4.9915))
    )

    values = to_forecast_values(
        messages,
        model_id="ecmwf_ifs_open",
        variable="precipitation",
        canonical_unit="mm",
        elevation_by_point={point: 250.0},
    )

    assert [value.value for value in values] == [0.0, 3.0, 2.0, 0.0]


def test_preserves_contiguous_interval_precipitation_and_rejects_decrease() -> None:
    run_time = datetime(2026, 8, 29, 0, tzinfo=UTC)
    point = GeoPoint(50.0, 14.0)

    def message(start: int, end: int, amount: float) -> SampledMessage:
        return SampledMessage(
            run_time=run_time,
            valid_time=run_time.replace(hour=end),
            unit="kg/m²",
            step_type="accum",
            start_step_hours=start,
            end_step_hours=end,
            values=(SampledPoint(50.0, 14.0, 50.0, 14.0, 0.0, amount),),
        )

    interval = to_forecast_values(
        (message(0, 6, 3.0), message(6, 12, 2.0)),
        model_id="noaa_gfs",
        variable="precipitation",
        canonical_unit="mm",
        elevation_by_point={point: 250.0},
    )

    assert [value.value for value in interval] == [3.0, 2.0]
    with pytest.raises(ValueError, match="decreased"):
        to_forecast_values(
            (message(0, 6, 3.0), message(0, 12, 2.0)),
            model_id="noaa_gfs",
            variable="precipitation",
            canonical_unit="mm",
            elevation_by_point={point: 250.0},
        )


def test_reuses_verified_grid_indexes_for_later_fields(tmp_path: Path) -> None:
    path = tmp_path / "field.grib2"
    path.write_bytes(b"GRIB-test-7777")
    points = (GeoPoint(49.0, 14.0), GeoPoint(50.0, 15.0))
    index_api = FakeEccodes()

    point_index = build_grib_point_index(path, points, api=index_api)
    decode_api = FakeEccodes()
    messages = decode_grib_points(path, points, api=decode_api, point_index=point_index)

    assert index_api.nearest_calls == 1
    assert decode_api.nearest_calls == 0
    assert decode_api.element_calls == 2
    assert messages[1].values[1].value == 283.0
    assert decode_api.released == [1, 2]


def test_rejects_index_from_another_grid_and_releases_handle(tmp_path: Path) -> None:
    path = tmp_path / "field.grib2"
    path.write_bytes(b"GRIB-test-7777")
    points = (GeoPoint(49.0, 14.0),)
    point_index = build_grib_point_index(path, points, api=FakeEccodes(grid_hash="grid-a"))
    decode_api = FakeEccodes(grid_hash="grid-b")

    with pytest.raises(ValueError, match="grid hash"):
        decode_grib_points(path, points, api=decode_api, point_index=point_index)

    assert decode_api.released == [1]


def test_builds_elevation_map_and_converts_meteorological_wind() -> None:
    run_time = datetime(2026, 8, 29, 12, tzinfo=UTC)
    elevation_points = (
        SampledPoint(49.0, 14.0, 49.0, 14.0, 0.0, 250.0),
        SampledPoint(50.0, 15.0, 50.0, 15.0, 0.0, 300.0),
    )
    elevation = SampledMessage(run_time, run_time, "m", "instant", 0, 0, elevation_points)
    speed = SampledMessage(
        run_time,
        run_time,
        "m/s",
        "instant",
        0,
        0,
        (
            SampledPoint(49.0, 14.0, 49.0, 14.0, 0.0, 10.0),
            SampledPoint(50.0, 15.0, 50.0, 15.0, 0.0, 10.0),
        ),
    )
    direction = SampledMessage(
        run_time,
        run_time,
        "°",
        "instant",
        0,
        0,
        (
            SampledPoint(49.0, 14.0, 49.0, 14.0, 0.0, 0.0),
            SampledPoint(50.0, 15.0, 50.0, 15.0, 0.0, 90.0),
        ),
    )

    elevations = elevation_by_point(elevation)
    values = to_wind_component_values((speed,), (direction,), "chmi_aladin_cz_1km", elevations)

    assert elevations == {GeoPoint(49.0, 14.0): 250.0, GeoPoint(50.0, 15.0): 300.0}
    by_variable = {(value.variable, value.latitude): value.value for value in values}
    assert isclose(require_float(by_variable[("wind_u_10m", 49.0)]), 0.0, abs_tol=1e-9)
    assert isclose(require_float(by_variable[("wind_v_10m", 49.0)]), -10.0)
    assert isclose(require_float(by_variable[("wind_u_10m", 50.0)]), -10.0)
    assert isclose(require_float(by_variable[("wind_v_10m", 50.0)]), 0.0, abs_tol=1e-9)


def require_float(value: float | None) -> float:
    assert value is not None
    return value
