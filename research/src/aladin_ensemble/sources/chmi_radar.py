from __future__ import annotations

import hashlib
import re
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import UTC, datetime
from math import isfinite
from numbers import Real
from pathlib import Path

import h5py

from aladin_ensemble.types import SourceManifest, SpatialObservation

CHMI_PROVIDER = "ČHMÚ"
MERGE1H_SOURCE = "CHMI_MERGE1H"
_MERGE1H_NAME = re.compile(r"^T_PASV23_C_OKPR_(\d{14})\.hdf$")


@dataclass(frozen=True, slots=True)
class Merge1hContract:
    valid_start: datetime
    valid_end: datetime
    projdef: str
    geographic_bounds: tuple[float, float, float, float]
    xsize: int
    ysize: int
    xscale_m: float
    yscale_m: float
    gain: float
    offset: float
    nodata: float
    undetect: float


def parse_merge1h_contract(path: Path, source_filename: str | None = None) -> Merge1hContract:
    filename = source_filename or path.name
    match = _MERGE1H_NAME.fullmatch(filename)
    if match is None:
        raise ValueError("MERGE1h filename is invalid")
    with h5py.File(path, "r") as source:
        contract = _contract(source)
    filename_end = datetime.strptime(match.group(1), "%Y%m%d%H%M%S").replace(tzinfo=UTC)
    if filename_end != contract.valid_end:
        raise ValueError("MERGE1h filename end time does not match ODIM metadata")
    return contract


def iter_merge1h_observations(
    path: Path, source_checksum: str, *, block_rows: int = 64
) -> Iterator[SpatialObservation]:
    if block_rows <= 0:
        raise ValueError("block_rows must be positive")
    contract = parse_merge1h_contract(path)
    with h5py.File(path, "r") as source:
        dataset = source["dataset1/data1/data"]
        if not isinstance(dataset, h5py.Dataset) or dataset.shape != (
            contract.ysize,
            contract.xsize,
        ):
            raise ValueError("MERGE1h data shape does not match ODIM metadata")
        for first_row in range(0, contract.ysize, block_rows):
            block = dataset[first_row : first_row + block_rows, :]
            for block_row, values in enumerate(block):
                for column, raw in enumerate(values):
                    value, flag = _precipitation(float(raw), contract)
                    yield SpatialObservation(
                        source=MERGE1H_SOURCE,
                        source_checksum=source_checksum,
                        valid_start=contract.valid_start,
                        valid_end=contract.valid_end,
                        variable="precipitation",
                        value=value,
                        unit="mm",
                        projection=contract.projdef,
                        geographic_bounds=contract.geographic_bounds,
                        row=first_row + block_row,
                        column=column,
                        xscale_m=contract.xscale_m,
                        yscale_m=contract.yscale_m,
                        flag=flag,
                    )


def build_merge1h_manifest(
    path: Path,
    *,
    source_url: str,
    retrieved_at: datetime,
    documentation_url: str,
    license_name: str,
    license_url: str,
) -> SourceManifest:
    contract = parse_merge1h_contract(path)
    return SourceManifest(
        provider=CHMI_PROVIDER,
        documentation_url=documentation_url,
        license_name=license_name,
        license_url=license_url,
        retrieved_at=retrieved_at,
        run_time=None,
        source_url=source_url,
        checksum_sha256=_sha256(path),
        source_timestamp=contract.valid_end,
    )


def _contract(source: h5py.File) -> Merge1hContract:
    where = _group(source, "where")
    dataset = _group(source, "dataset1")
    what = _group(dataset, "what")
    data_what = _group(_group(dataset, "data1"), "what")
    valid_start = _odim_time(
        _text(what.attrs.get("startdate"), "startdate"),
        _text(what.attrs.get("starttime"), "starttime"),
    )
    valid_end = _odim_time(
        _text(what.attrs.get("enddate"), "enddate"), _text(what.attrs.get("endtime"), "endtime")
    )
    west = _number(where.attrs.get("LL_lon"), "LL_lon")
    south = _number(where.attrs.get("LL_lat"), "LL_lat")
    east = _number(where.attrs.get("UR_lon"), "UR_lon")
    north = _number(where.attrs.get("UR_lat"), "UR_lat")
    if not (-180 <= west < east <= 180 and -90 <= south < north <= 90):
        raise ValueError("MERGE1h geographic bounds are invalid")
    projdef = _text(where.attrs.get("projdef"), "projdef")
    if not projdef:
        raise ValueError("MERGE1h projdef is empty")
    return Merge1hContract(
        valid_start=valid_start,
        valid_end=valid_end,
        projdef=projdef,
        geographic_bounds=(west, south, east, north),
        xsize=_integer(where.attrs.get("xsize"), "xsize"),
        ysize=_integer(where.attrs.get("ysize"), "ysize"),
        xscale_m=_positive(where.attrs.get("xscale"), "xscale"),
        yscale_m=_positive(where.attrs.get("yscale"), "yscale"),
        gain=_number(data_what.attrs.get("gain"), "gain"),
        offset=_number(data_what.attrs.get("offset"), "offset"),
        nodata=_number(data_what.attrs.get("nodata"), "nodata"),
        undetect=_number(data_what.attrs.get("undetect"), "undetect"),
    )


def _group(parent: h5py.File | h5py.Group, name: str) -> h5py.Group:
    value = parent[name]
    if not isinstance(value, h5py.Group):
        raise ValueError(f"MERGE1h {name} group is missing")
    return value


def _odim_time(date: str, time: str) -> datetime:
    if len(date) != 8 or len(time) != 6 or not date.isdigit() or not time.isdigit():
        raise ValueError("MERGE1h ODIM time is invalid")
    return datetime.strptime(f"{date}{time}", "%Y%m%d%H%M%S").replace(tzinfo=UTC)


def _text(value: object, field_name: str) -> str:
    if isinstance(value, bytes):
        return value.decode("ascii")
    if isinstance(value, str):
        return value
    raise ValueError(f"MERGE1h {field_name} is invalid")


def _number(value: object, field_name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, Real) or not isfinite(value):
        raise ValueError(f"MERGE1h {field_name} must be finite")
    return float(value)


def _positive(value: object, field_name: str) -> float:
    numeric = _number(value, field_name)
    if numeric <= 0:
        raise ValueError(f"MERGE1h {field_name} must be positive")
    return numeric


def _integer(value: object, field_name: str) -> int:
    numeric = _number(value, field_name)
    if not numeric.is_integer() or numeric <= 0:
        raise ValueError(f"MERGE1h {field_name} must be a positive integer")
    return int(numeric)


def _precipitation(raw: float, contract: Merge1hContract) -> tuple[float | None, str | None]:
    if raw == contract.nodata:
        return None, "nodata"
    if raw == contract.undetect:
        return None, "undetect"
    value = raw * contract.gain + contract.offset
    if value < 0 or not isfinite(value):
        raise ValueError("MERGE1h precipitation value is invalid")
    return value, None


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(65_536):
            digest.update(chunk)
    return digest.hexdigest()
