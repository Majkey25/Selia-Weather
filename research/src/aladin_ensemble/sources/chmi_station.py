from __future__ import annotations

import csv
import hashlib
import json
from collections.abc import Iterator, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from math import isfinite
from pathlib import Path
from typing import Literal, TextIO

from aladin_ensemble.types import Observation, SourceManifest

CHMI_PROVIDER = "ČHMÚ"
STATION_SOURCE = "CHMI_STATION"


@dataclass(frozen=True, slots=True)
class Station:
    wigos_id: str
    name: str
    latitude: float
    longitude: float
    elevation_m: float

    def __post_init__(self) -> None:
        if not self.wigos_id or not self.name:
            raise ValueError("station WIGOS ID and name are required")
        for value, field_name in (
            (self.latitude, "latitude"),
            (self.longitude, "longitude"),
            (self.elevation_m, "elevation_m"),
        ):
            if not isfinite(value):
                raise ValueError(f"station {field_name} must be finite")
        if not -90 <= self.latitude <= 90 or not -180 <= self.longitude <= 180:
            raise ValueError("station coordinates are outside WGS84 range")
        if not -500 <= self.elevation_m <= 10_000:
            raise ValueError("station elevation is outside supported range")


@dataclass(frozen=True, slots=True)
class _Element:
    variable: str
    unit: str
    minimum: float
    maximum: float
    interval: timedelta | None = None
    accumulation: Literal["instant", "interval", "cumulative"] = "instant"


ELEMENTS = {
    "T": _Element("temperature_2m", "°C", -90.0, 60.0),
    "Td": _Element("dew_point_2m", "°C", -100.0, 60.0),
    "H": _Element("relative_humidity", "%", 0.0, 100.0),
    "P": _Element("surface_pressure", "hPa", 800.0, 1100.0),
    "D": _Element("wind_direction_10m", "°", 0.0, 360.0),
    "F": _Element("wind_speed_10m", "m/s", 0.0, 120.0),
    "SRA10M": _Element("precipitation", "mm", 0.0, 1_000.0, timedelta(minutes=10), "interval"),
    "SRA1H": _Element("precipitation", "mm", 0.0, 1_000.0, timedelta(hours=1), "interval"),
}


class _JsonStream:
    def __init__(self, source: TextIO) -> None:
        self._source = source
        self._buffer = ""
        self._ended = False
        self._decoder = json.JSONDecoder()

    def find(self, marker: str) -> None:
        keep = len(marker) - 1
        while marker not in self._buffer:
            chunk = self._source.read(8192)
            if not chunk:
                raise ValueError(f"ČHMÚ JSON has no {marker}")
            self._buffer = self._buffer[-keep:] + chunk
        _, _, self._buffer = self._buffer.partition(marker)

    def value(self) -> object:
        while True:
            self._buffer = self._buffer.lstrip(" \t\r\n:")
            try:
                value, end = self._decoder.raw_decode(self._buffer)
            except json.JSONDecodeError:
                if self._ended:
                    raise ValueError("ČHMÚ JSON is truncated") from None
                self._read()
                continue
            self._buffer = self._buffer[end:]
            return value

    def rows(self) -> Iterator[list[object]]:
        self._buffer = self._buffer.lstrip(" \t\r\n:")
        while not self._buffer.startswith("["):
            if self._ended:
                raise ValueError("ČHMÚ JSON values are not an array")
            self._read()
            self._buffer = self._buffer.lstrip(" \t\r\n:")
        self._buffer = self._buffer[1:]
        while True:
            self._buffer = self._buffer.lstrip(" \t\r\n,")
            if self._buffer.startswith("]"):
                return
            row = self.value()
            if not isinstance(row, list):
                raise ValueError("ČHMÚ JSON value row is not an array")
            yield row

    def _read(self) -> None:
        chunk = self._source.read(8192)
        if chunk:
            self._buffer += chunk
        else:
            self._ended = True


def _collection(source: TextIO) -> tuple[datetime, tuple[str, ...], Iterator[list[object]]]:
    stream = _JsonStream(source)
    stream.find('"datumVytvoreni"')
    timestamp = _timestamp(stream.value(), "datumVytvoreni")
    stream.find('"header"')
    header = stream.value()
    if not isinstance(header, str):
        raise ValueError("ČHMÚ JSON header is not text")
    fields = tuple(next(csv.reader((header,)), ()))
    if not fields:
        raise ValueError("ČHMÚ JSON header is empty")
    stream.find('"values"')
    return timestamp, fields, stream.rows()


def _timestamp(value: object, field_name: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError(f"{field_name} must be an ISO timestamp")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError(f"{field_name} must include timezone")
    return parsed.astimezone(UTC)


def _number(value: object, field_name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float) or not isfinite(value):
        raise ValueError(f"{field_name} must be finite")
    return float(value)


def _row_values(row: list[object], fields: tuple[str, ...]) -> dict[str, object]:
    if len(row) != len(fields):
        raise ValueError("ČHMÚ JSON row length does not match header")
    return dict(zip(fields, row, strict=True))


def parse_station_metadata(source: TextIO) -> Iterator[Station]:
    _, fields, rows = _collection(source)
    required = ("WSI", "FULL_NAME", "GEOGR1", "GEOGR2", "ELEVATION")
    if any(field not in fields for field in required):
        raise ValueError("ČHMÚ station metadata columns are incomplete")
    for row in rows:
        values = _row_values(row, fields)
        wigos_id = values["WSI"]
        name = values["FULL_NAME"]
        if not isinstance(wigos_id, str) or not isinstance(name, str):
            raise ValueError("ČHMÚ station identity is invalid")
        yield Station(
            wigos_id=wigos_id,
            name=name,
            latitude=_number(values["GEOGR2"], "GEOGR2"),
            longitude=_number(values["GEOGR1"], "GEOGR1"),
            elevation_m=_number(values["ELEVATION"], "ELEVATION"),
        )


def parse_station_observations(
    source: TextIO, stations: Mapping[str, Station]
) -> Iterator[Observation]:
    _, fields, rows = _collection(source)
    required = ("STATION", "ELEMENT", "DT", "VAL", "FLAG", "QUALITY")
    if any(field not in fields for field in required):
        raise ValueError("ČHMÚ observation columns are incomplete")
    for row in rows:
        values = _row_values(row, fields)
        station_id = values["STATION"]
        element_name = values["ELEMENT"]
        if not isinstance(station_id, str) or not isinstance(element_name, str):
            raise ValueError("ČHMÚ observation identity is invalid")
        element = ELEMENTS.get(element_name)
        if element is None:
            continue
        station = stations.get(station_id)
        if station is None:
            raise ValueError(f"ČHMÚ observation has unknown WIGOS station {station_id}")
        value = _optional_value(values["VAL"], element)
        flag = _optional_flag(values["FLAG"])
        yield Observation(
            source=STATION_SOURCE,
            station_id=station.wigos_id,
            valid_time=_timestamp(values["DT"], "DT"),
            latitude=station.latitude,
            longitude=station.longitude,
            elevation_m=station.elevation_m,
            variable=element.variable,
            value=value,
            unit=element.unit,
            interval=element.interval,
            accumulation=element.accumulation,
            flag=flag,
            quality=_quality(values["QUALITY"]),
        )


def _optional_value(value: object, element: _Element) -> float | None:
    if value == "" or value is None:
        return None
    numeric = _number(value, element.variable)
    if not element.minimum <= numeric <= element.maximum:
        raise ValueError(f"{element.variable} is outside valid range")
    return numeric


def _optional_flag(value: object) -> str | None:
    if value == "" or value is None:
        return None
    if not isinstance(value, str):
        raise ValueError("ČHMÚ FLAG must be text")
    return value


def _quality(value: object) -> int | None:
    if value == "" or value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int | float) or int(value) != value:
        raise ValueError("ČHMÚ QUALITY must be an integer")
    quality = int(value)
    if quality < 0:
        raise ValueError("ČHMÚ QUALITY must be non-negative")
    return quality


def build_source_manifest(
    path: Path,
    *,
    source_url: str,
    retrieved_at: datetime,
    documentation_url: str,
    license_name: str,
    license_url: str,
) -> SourceManifest:
    with path.open(encoding="utf-8") as source:
        stream = _JsonStream(source)
        stream.find('"datumVytvoreni"')
        source_timestamp = _timestamp(stream.value(), "datumVytvoreni")
    return SourceManifest(
        provider=CHMI_PROVIDER,
        documentation_url=documentation_url,
        license_name=license_name,
        license_url=license_url,
        retrieved_at=retrieved_at,
        run_time=None,
        source_url=source_url,
        checksum_sha256=_sha256(path),
        source_timestamp=source_timestamp,
    )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(65_536):
            digest.update(chunk)
    return digest.hexdigest()
