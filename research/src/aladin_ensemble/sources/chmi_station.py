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
    begin_time: datetime | None = None

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
        if self.begin_time is not None and (
            self.begin_time.tzinfo is None
            or self.begin_time.utcoffset() != UTC.utcoffset(self.begin_time)
        ):
            raise ValueError("station begin_time must be timezone-aware UTC")


@dataclass(frozen=True, slots=True)
class ElementMetadata:
    observation_type: str
    wigos_id: str
    element: str
    unit: str
    height_m: float | None
    schedule: str


@dataclass(frozen=True, slots=True)
class _Element:
    variable: str
    unit: str
    minimum: float
    maximum: float
    declared_units: frozenset[str]
    interval: timedelta | None = None
    accumulation: Literal["instant", "interval", "cumulative"] = "instant"


ELEMENTS = {
    "T": _Element("temperature", "°C", -90.0, 60.0, frozenset({"°C"})),
    "Td": _Element("dew_point", "°C", -100.0, 60.0, frozenset({"°C"})),
    "H": _Element("relative_humidity", "%", 0.0, 100.0, frozenset({"%"})),
    "P": _Element("surface_pressure", "hPa", 800.0, 1100.0, frozenset({"hPa"})),
    "D": _Element("wind_direction", "°", 0.0, 360.0, frozenset({"stupně"})),
    "F": _Element("wind_speed", "m/s", 0.0, 120.0, frozenset({"m/s"})),
    "SRA10M": _Element(
        "precipitation", "mm", 0.0, 1_000.0, frozenset({"mm"}), timedelta(minutes=10), "interval"
    ),
    "SRA1H": _Element(
        "precipitation", "mm", 0.0, 1_000.0, frozenset({"mm"}), timedelta(hours=1), "interval"
    ),
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
            self._buffer = self._buffer.lstrip(" \t\r\n:,")
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
            while not self._buffer and not self._ended:
                self._read()
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


def _metadata_number(value: object, field_name: str) -> float | None:
    if value == "" or value is None:
        return None
    if isinstance(value, str):
        try:
            value = float(value)
        except ValueError as error:
            raise ValueError(f"{field_name} must be finite") from error
    return _number(value, field_name)


def _required_metadata_number(value: object, field_name: str) -> float:
    parsed = _metadata_number(value, field_name)
    if parsed is None:
        raise ValueError(f"{field_name} must be finite")
    return parsed


def _row_values(row: list[object], fields: tuple[str, ...]) -> dict[str, object]:
    if len(row) != len(fields):
        raise ValueError("ČHMÚ JSON row length does not match header")
    return dict(zip(fields, row, strict=True))


def parse_station_metadata(source: TextIO) -> Iterator[Station]:
    _, fields, rows = _collection(source)
    required = ("WSI", "FULL_NAME", "GEOGR1", "GEOGR2", "ELEVATION")
    if any(field not in fields for field in required):
        raise ValueError("ČHMÚ station metadata columns are incomplete")
    latest: dict[str, Station] = {}
    for row in rows:
        values = _row_values(row, fields)
        if any(values[field] in ("", None) for field in ("GEOGR1", "GEOGR2", "ELEVATION")):
            continue
        wigos_id = values["WSI"]
        name = values["FULL_NAME"]
        if not isinstance(wigos_id, str) or not isinstance(name, str):
            raise ValueError("ČHMÚ station identity is invalid")
        begin_time = (
            _timestamp(values["BEGIN_DATE"], "BEGIN_DATE")
            if "BEGIN_DATE" in values and values["BEGIN_DATE"] not in ("", None)
            else None
        )
        station = Station(
            wigos_id=wigos_id,
            name=name,
            latitude=_required_metadata_number(values["GEOGR2"], "GEOGR2"),
            longitude=_required_metadata_number(values["GEOGR1"], "GEOGR1"),
            elevation_m=_required_metadata_number(values["ELEVATION"], "ELEVATION"),
            begin_time=begin_time,
        )
        previous = latest.get(wigos_id)
        previous_start = (
            previous.begin_time
            if previous and previous.begin_time
            else datetime.min.replace(tzinfo=UTC)
        )
        station_start = begin_time or datetime.min.replace(tzinfo=UTC)
        if previous is None or station_start > previous_start:
            latest[wigos_id] = station
        elif station_start == previous_start and station != previous:
            raise ValueError(f"conflicting ČHMÚ station metadata {wigos_id}")
    yield from (latest[wigos_id] for wigos_id in sorted(latest))


def parse_element_metadata(source: TextIO) -> dict[tuple[str, str, str], ElementMetadata]:
    _, fields, rows = _collection(source)
    required = ("OBS_TYPE", "WSI", "EG_EL_ABBREVIATION", "UN_DESCRIPTION", "HEIGHT", "SCHEDULE")
    if any(field not in fields for field in required):
        raise ValueError("ČHMÚ element metadata columns are incomplete")
    metadata: dict[tuple[str, str, str], ElementMetadata] = {}
    for row in rows:
        values = _row_values(row, fields)
        observation_type = values["OBS_TYPE"]
        wigos_id = values["WSI"]
        element = values["EG_EL_ABBREVIATION"]
        unit = values["UN_DESCRIPTION"]
        schedule = values["SCHEDULE"]
        if not (
            isinstance(observation_type, str)
            and observation_type
            and isinstance(wigos_id, str)
            and wigos_id
            and isinstance(element, str)
            and element
            and isinstance(unit, str)
            and unit
            and isinstance(schedule, str)
            and schedule
        ):
            raise ValueError("ČHMÚ element metadata identity is invalid")
        key = (observation_type, wigos_id, element)
        if key in metadata:
            raise ValueError(f"duplicate ČHMÚ element metadata {key}")
        metadata[key] = ElementMetadata(
            observation_type=observation_type,
            wigos_id=wigos_id,
            element=element,
            unit=unit,
            height_m=_metadata_number(values["HEIGHT"], "HEIGHT"),
            schedule=schedule,
        )
    return metadata


def parse_station_observations(
    source: TextIO,
    stations: Mapping[str, Station],
    element_metadata: Mapping[tuple[str, str, str], ElementMetadata],
    observation_type: str,
    source_checksum: str,
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
        metadata = element_metadata.get((observation_type, station_id, element_name))
        if metadata is None:
            raise ValueError(f"ČHMÚ observation has no metadata for {element_name}")
        if metadata.unit not in element.declared_units:
            raise ValueError(f"ČHMÚ metadata unit is invalid for {element_name}")
        if element_name in {"T", "Td", "D", "F"} and metadata.height_m is None:
            raise ValueError(f"ČHMÚ metadata height is missing for {element_name}")
        value = _optional_value(values["VAL"], element)
        flag = _optional_flag(values["FLAG"])
        yield Observation(
            source=STATION_SOURCE,
            station_id=station.wigos_id,
            valid_time=_timestamp(values["DT"], "DT"),
            latitude=station.latitude,
            longitude=station.longitude,
            elevation_m=station.elevation_m,
            variable=_variable(element_name, element.variable, metadata.height_m),
            value=value,
            unit=element.unit,
            interval=element.interval,
            accumulation=element.accumulation,
            flag=flag,
            quality=_quality(values["QUALITY"]),
            measurement_height_m=metadata.height_m,
            source_checksum=source_checksum,
        )


def _variable(element_name: str, variable: str, height_m: float | None) -> str:
    if element_name in {"D", "F"} and height_m == 10.0:
        return f"{variable}_10m"
    if element_name in {"T", "Td"} and height_m == 2.0:
        return f"{variable}_2m"
    return variable


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


def cumulative_precipitation_intervals(
    values: Iterator[tuple[datetime, float | None]] | tuple[tuple[datetime, float | None], ...],
) -> Iterator[tuple[datetime, float | None]]:
    previous: float | None = None
    for valid_time, value in values:
        if valid_time.tzinfo is None or valid_time.utcoffset() != UTC.utcoffset(valid_time):
            raise ValueError("cumulative precipitation time must be UTC")
        if value is not None and (not isfinite(value) or value < 0):
            raise ValueError("cumulative precipitation must be finite and non-negative")
        interval = value - previous if value is not None and previous is not None else None
        yield valid_time, interval if interval is not None and interval >= 0 else None
        previous = value


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
