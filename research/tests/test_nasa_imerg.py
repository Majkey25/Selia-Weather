from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from typing import Protocol, cast

import h5py
import numpy as np
import pytest
from numpy.typing import NDArray

from aladin_ensemble.sources.nasa_imerg import iter_imerg_observations


class _WritableGroup(Protocol):
    def create_group(self, name: str) -> _WritableGroup: ...

    def create_dataset(
        self,
        name: str,
        *,
        data: NDArray[np.float32],
    ) -> h5py.Dataset: ...


def test_imerg_parses_v07_grid_rate_as_half_hour_amount(tmp_path: Path) -> None:
    path = tmp_path / "imerg.h5"
    write_imerg(path)

    observations = tuple(
        iter_imerg_observations(path, "b" * 64, source_filename=SOURCE_FILENAME)
    )

    assert len(observations) == 4
    first = observations[0]
    assert first.valid_start == datetime(2026, 8, 31, 20, 0, tzinfo=UTC)
    assert first.valid_end == datetime(2026, 8, 31, 20, 30, tzinfo=UTC)
    assert first.variable == "precipitation"
    assert first.value == 1.0
    assert first.unit == "mm"
    assert first.projection == "EPSG:4326"
    assert first.row == 0
    assert first.column == 0
    nodata = next(observation for observation in observations if observation.flag == "nodata")
    assert nodata.row == 1
    assert nodata.column == 0
    assert nodata.value is None


def test_imerg_rejects_wrong_units_and_shape(tmp_path: Path) -> None:
    wrong_units = tmp_path / "wrong-units.h5"
    write_imerg(wrong_units, units="mm")
    with pytest.raises(ValueError, match="units"):
        tuple(
            iter_imerg_observations(
                wrong_units,
                "b" * 64,
                source_filename=SOURCE_FILENAME,
            )
        )

    wrong_shape = tmp_path / "wrong-shape.h5"
    write_imerg(wrong_shape, shape=(1, 2, 1))
    with pytest.raises(ValueError, match="shape"):
        tuple(
            iter_imerg_observations(
                wrong_shape,
                "b" * 64,
                source_filename=SOURCE_FILENAME,
            )
        )
    with pytest.raises(ValueError, match="block_longitudes"):
        tuple(
            iter_imerg_observations(
                wrong_shape,
                "b" * 64,
                source_filename=SOURCE_FILENAME,
                block_longitudes=0,
            )
        )


def write_imerg(
    path: Path,
    *,
    units: str = "mm/hr",
    shape: tuple[int, int, int] = (1, 2, 2),
) -> None:
    with h5py.File(path, "w") as raw_destination:
        destination = cast(_WritableGroup, raw_destination)
        grid = destination.create_group("Grid")
        grid.create_dataset("lat", data=np.array([-0.05, 0.05], dtype=np.float32))
        grid.create_dataset("lon", data=np.array([10.05, 10.15], dtype=np.float32))
        values = np.full(shape, 2.0, dtype=np.float32)
        if shape == (1, 2, 2):
            values = np.array([[[2.0, -9999.9], [4.0, 6.0]]], dtype=np.float32)
        precipitation = grid.create_dataset("precipitation", data=values)
        precipitation.attrs["units"] = units
        precipitation.attrs["_FillValue"] = np.float32(-9999.9)


SOURCE_FILENAME = "3B-HHR.MS.MRG.3IMERG.20260831-S200000-E202959.1200.V07B.HDF5"
