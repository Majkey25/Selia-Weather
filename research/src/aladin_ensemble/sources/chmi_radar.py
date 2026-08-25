from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from math import isfinite
from pathlib import Path

_MERGE1H_NAME = re.compile(r"^T_PASV23_C_OKPR_(\d{14})\.hdf$")


@dataclass(frozen=True, slots=True)
class Merge1hContract:
    valid_time: datetime
    interval: timedelta
    projection: str
    bounds: tuple[float, float, float, float]
    checksum_sha256: str


def parse_merge1h_contract(path: Path) -> Merge1hContract:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("MERGE1h metadata must be an object")
    filename = payload.get("filename")
    projection = payload.get("projection")
    interval_minutes = payload.get("interval_minutes")
    checksum = payload.get("checksum_sha256")
    bounds = payload.get("bounds")
    if not isinstance(filename, str):
        raise ValueError("MERGE1h filename is required")
    match = _MERGE1H_NAME.fullmatch(filename)
    if match is None:
        raise ValueError("MERGE1h filename is invalid")
    if projection != "EPSG:3857":
        raise ValueError("MERGE1h projection must be EPSG:3857")
    if isinstance(interval_minutes, bool) or interval_minutes != 60:
        raise ValueError("MERGE1h interval must be 60 minutes")
    if not isinstance(checksum, str) or len(checksum) != 64 or any(
        character not in "0123456789abcdef" for character in checksum
    ):
        raise ValueError("MERGE1h checksum must be a lowercase SHA-256 digest")
    if not isinstance(bounds, list) or len(bounds) != 4:
        raise ValueError("MERGE1h bounds are invalid")
    numeric_bounds = (
        _number(bounds[0]),
        _number(bounds[1]),
        _number(bounds[2]),
        _number(bounds[3]),
    )
    west, south, east, north = numeric_bounds
    if not (-180 <= west < east <= 180 and -90 <= south < north <= 90):
        raise ValueError("MERGE1h bounds are outside WGS84 range")
    return Merge1hContract(
        valid_time=datetime.strptime(match.group(1), "%Y%m%d%H%M%S").replace(tzinfo=UTC),
        interval=timedelta(hours=1),
        projection=projection,
        bounds=numeric_bounds,
        checksum_sha256=checksum,
    )


def _number(value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float) or not isfinite(value):
        raise ValueError("MERGE1h bounds must be finite")
    return float(value)
