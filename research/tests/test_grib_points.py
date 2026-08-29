from __future__ import annotations

from dataclasses import dataclass
from math import isclose
from pathlib import Path
from typing import BinaryIO

import pytest

from aladin_ensemble.sources.grib_points import (
    GeoPoint,
    decode_grib_points,
    regular_grid_points,
    to_forecast_values,
)


@dataclass(frozen=True, slots=True)
class Nearest:
    lat: float
    lon: float
    value: float
    distance: float
    index: int


class FakeEccodes:
    def __init__(self, *, bad_count: bool = False) -> None:
        self.handles: list[int | None] = [1, 2, None]
        self.released: list[int] = []
        self.bad_count = bad_count

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
        return values[(handle, key)]

    def find_nearest_multiple(
        self,
        handle: int,
        is_lsm: bool,
        latitudes: tuple[float, ...],
        longitudes: tuple[float, ...],
    ) -> tuple[Nearest, ...]:
        values = tuple(
            Nearest(latitude + 0.01, longitude + 0.01, 280.0 + handle + index, 1.5, index)
            for index, (latitude, longitude) in enumerate(
                zip(latitudes, longitudes, strict=True)
            )
        )
        return values[:-1] if self.bad_count else values

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
