from __future__ import annotations

import re
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from math import cos, isclose, isfinite, radians
from numbers import Real
from pathlib import Path
from typing import Protocol, cast

import h5py
import numpy as np
from numpy.typing import NDArray

from aladin_ensemble.types import SpatialObservation

IMERG_SOURCE = "NASA_GPM_IMERG"
_FILENAME = re.compile(
    r"^3B-HHR[^.]*\.MS\.MRG\.3IMERG\.(\d{8})-S(\d{6})-E(\d{6})\.\d{4}\.V07[A-Z]\.HDF5$"
)


class _Dataset(Protocol):
    shape: tuple[int, ...]
    attrs: h5py.AttributeManager

    def __getitem__(self, key: object) -> object: ...


def iter_imerg_observations(
    path: Path,
    source_checksum: str,
    *,
    source_filename: str | None = None,
    block_longitudes: int = 64,
) -> Iterator[SpatialObservation]:
    if block_longitudes <= 0:
        raise ValueError("block_longitudes must be positive")
    filename = source_filename or path.name
    valid_start, valid_end = _validity(filename)
    with h5py.File(path, "r") as source:
        grid = source.get("Grid")
        if not isinstance(grid, h5py.Group):
            raise ValueError("IMERG Grid group is missing")
        latitudes = _axis(grid, "lat")
        longitudes = _axis(grid, "lon")
        latitude_step = _step(latitudes, "latitude")
        longitude_step = _step(longitudes, "longitude")
        precipitation = grid.get("precipitation")
        if not isinstance(precipitation, h5py.Dataset):
            raise ValueError("IMERG precipitation dataset is missing")
        dataset = cast(_Dataset, precipitation)
        if dataset.shape != (1, len(longitudes), len(latitudes)):
            raise ValueError("IMERG precipitation shape is invalid")
        if _text(dataset.attrs.get("units")) != "mm/hr":
            raise ValueError("IMERG precipitation units are invalid")
        fill_value = _number(dataset.attrs.get("_FillValue"), "_FillValue")
        bounds = (
            float(longitudes[0] - longitude_step / 2),
            float(latitudes[0] - latitude_step / 2),
            float(longitudes[-1] + longitude_step / 2),
            float(latitudes[-1] + latitude_step / 2),
        )
        for first_column in range(0, len(longitudes), block_longitudes):
            values = cast(
                NDArray[np.float32],
                dataset[0, first_column : first_column + block_longitudes, :],
            )
            for block_column, longitude_values in enumerate(values):
                column = first_column + block_column
                for row, raw_value in enumerate(longitude_values):
                    latitude = float(latitudes[row])
                    yscale = DEGREE_METRES * latitude_step
                    xscale = max(
                        1.0,
                        DEGREE_METRES * longitude_step * cos(radians(latitude)),
                    )
                    raw = float(raw_value)
                    missing = raw < 0 or isclose(raw, fill_value, abs_tol=1e-5)
                    yield SpatialObservation(
                        source=IMERG_SOURCE,
                        source_checksum=source_checksum,
                        valid_start=valid_start,
                        valid_end=valid_end,
                        variable="precipitation",
                        value=None if missing else raw * HALF_HOUR,
                        unit="mm",
                        projection="EPSG:4326",
                        geographic_bounds=bounds,
                        row=row,
                        column=column,
                        xscale_m=xscale,
                        yscale_m=yscale,
                        flag="nodata" if missing else None,
                    )


def _validity(filename: str) -> tuple[datetime, datetime]:
    match = _FILENAME.fullmatch(filename)
    if match is None:
        raise ValueError("IMERG filename is invalid")
    start = datetime.strptime(match.group(1) + match.group(2), "%Y%m%d%H%M%S").replace(
        tzinfo=UTC
    )
    reported_end = datetime.strptime(match.group(1) + match.group(3), "%Y%m%d%H%M%S").replace(
        tzinfo=UTC
    )
    end = start + timedelta(minutes=30)
    if reported_end != end - timedelta(seconds=1):
        raise ValueError("IMERG filename interval is invalid")
    return start, end


def _axis(grid: h5py.Group, name: str) -> NDArray[np.float32]:
    value = grid.get(name)
    if not isinstance(value, h5py.Dataset) or len(value.shape) != 1 or value.shape[0] < 2:
        raise ValueError(f"IMERG {name} axis is invalid")
    axis = cast(NDArray[np.float32], value[:])
    if not np.isfinite(axis).all() or np.any(np.diff(axis) <= 0):
        raise ValueError(f"IMERG {name} axis is invalid")
    return axis


def _step(values: NDArray[np.float32], name: str) -> float:
    steps = np.diff(values.astype(np.float64))
    step = float(steps[0])
    if not np.allclose(steps, step, rtol=0, atol=1e-5) or not isclose(
        step, 0.1, rel_tol=0, abs_tol=1e-4
    ):
        raise ValueError(f"IMERG {name} step is invalid")
    return step


def _text(value: object) -> str:
    if isinstance(value, bytes):
        return value.decode("ascii")
    if isinstance(value, str):
        return value
    raise ValueError("IMERG text attribute is invalid")


def _number(value: object, field_name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, Real) or not isfinite(value):
        raise ValueError(f"IMERG {field_name} must be finite")
    return float(value)


DEGREE_METRES = 111_320.0
HALF_HOUR = 0.5
