from __future__ import annotations

import csv
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from math import isfinite
from typing import TextIO

from aladin_ensemble.types import Observation

ISD_SOURCE = "NOAA_ISD"

_REQUIRED_COLUMNS = {
    "STATION",
    "DATE",
    "LATITUDE",
    "LONGITUDE",
    "ELEVATION",
    "TMP",
    "DEW",
    "SLP",
    "WND",
    "VIS",
    "AA1",
}
_ACCEPTED_QUALITY = frozenset("01459")


def parse_isd_observations(source: TextIO, source_checksum: str) -> Iterator[Observation]:
    reader = csv.DictReader(source)
    if reader.fieldnames is None or not _REQUIRED_COLUMNS.issubset(reader.fieldnames):
        raise ValueError("NOAA ISD columns are incomplete")
    selected: dict[tuple[str, datetime, str], Observation] = {}
    for row in reader:
        station_id = _required(row, "STATION")
        valid_time = _timestamp(_required(row, "DATE"))
        latitude = _number(_required(row, "LATITUDE"), "latitude")
        longitude = _number(_required(row, "LONGITUDE"), "longitude")
        elevation = _number(_required(row, "ELEVATION"), "elevation")
        if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
            raise ValueError("NOAA ISD station coordinates are invalid")
        values = _row_observations(
            row,
            station_id=station_id,
            valid_time=valid_time,
            latitude=latitude,
            longitude=longitude,
            elevation=elevation,
            source_checksum=source_checksum,
        )
        for observation in values:
            key = observation.station_id, observation.valid_time, observation.variable
            previous = selected.get(key)
            if previous is None or _quality_rank(observation.quality) < _quality_rank(
                previous.quality
            ):
                selected[key] = observation
    yield from (selected[key] for key in sorted(selected))


def _row_observations(
    row: dict[str, str],
    *,
    station_id: str,
    valid_time: datetime,
    latitude: float,
    longitude: float,
    elevation: float,
    source_checksum: str,
) -> tuple[Observation, ...]:
    observations: list[Observation] = []

    def add(
        variable: str,
        parsed: tuple[float, int] | None,
        unit: str,
        *,
        measurement_height_m: float | None = None,
        interval: timedelta | None = None,
    ) -> None:
        if parsed is None:
            return
        value, quality = parsed
        observations.append(
            Observation(
                source=ISD_SOURCE,
                station_id=station_id,
                valid_time=valid_time,
                latitude=latitude,
                longitude=longitude,
                elevation_m=elevation,
                variable=variable,
                value=value,
                unit=unit,
                interval=interval,
                accumulation="interval" if interval is not None else "instant",
                quality=quality,
                measurement_height_m=measurement_height_m,
                source_checksum=source_checksum,
            )
        )

    add(
        "temperature_2m",
        _scaled(row["TMP"], 10.0, "+9999", -100.0, 70.0),
        "°C",
        measurement_height_m=2.0,
    )
    add(
        "dew_point_2m",
        _scaled(row["DEW"], 10.0, "+9999", -120.0, 70.0),
        "°C",
        measurement_height_m=2.0,
    )
    add("sea_level_pressure", _scaled(row["SLP"], 10.0, "99999", 800.0, 1100.0), "hPa")
    wind = _parts(row["WND"], "WND", 5)
    add(
        "wind_direction_10m",
        _component(wind[0], wind[1], "999", 1.0, 0.0, 360.0),
        "°",
        measurement_height_m=10.0,
    )
    add(
        "wind_speed_10m",
        _component(wind[3], wind[4], "9999", 10.0, 0.0, 150.0),
        "m/s",
        measurement_height_m=10.0,
    )
    visibility = _parts(row["VIS"], "VIS", 4)
    add("visibility", _component(visibility[0], visibility[1], "999999", 1.0, 0.0, 160_000.0), "m")
    precipitation = _precipitation(row["AA1"])
    if precipitation is not None:
        value, quality, interval = precipitation
        add("precipitation", (value, quality), "mm", interval=interval)
    return tuple(observations)


def _scaled(
    text: str,
    scale: float,
    missing: str,
    minimum: float,
    maximum: float,
) -> tuple[float, int] | None:
    values = _parts(text, "scaled field", 2)
    return _component(values[0], values[1], missing, scale, minimum, maximum)


def _component(
    raw: str,
    quality: str,
    missing: str,
    scale: float,
    minimum: float,
    maximum: float,
) -> tuple[float, int] | None:
    if raw == missing or quality not in _ACCEPTED_QUALITY:
        return None
    try:
        value = int(raw) / scale
    except ValueError as error:
        raise ValueError("NOAA ISD numeric component is invalid") from error
    if not minimum <= value <= maximum:
        raise ValueError("NOAA ISD value is outside its valid range")
    return value, int(quality)


def _precipitation(text: str) -> tuple[float, int, timedelta] | None:
    if not text:
        return None
    values = _parts(text, "AA1", 4)
    if values[0] == "99" or values[1] == "9999" or values[3] not in _ACCEPTED_QUALITY:
        return None
    try:
        hours = int(values[0])
        amount = int(values[1]) / 10.0
    except ValueError as error:
        raise ValueError("NOAA ISD precipitation is invalid") from error
    if hours <= 0 or amount < 0 or amount > 1_000:
        raise ValueError("NOAA ISD precipitation is outside its valid range")
    return amount, int(values[3]), timedelta(hours=hours)


def _parts(text: str, field_name: str, expected: int) -> tuple[str, ...]:
    values = tuple(text.split(","))
    if len(values) != expected:
        raise ValueError(f"NOAA ISD {field_name} component count is invalid")
    return values


def _required(row: dict[str, str], name: str) -> str:
    value = row.get(name, "")
    if not value:
        raise ValueError(f"NOAA ISD {name} is required")
    return value


def _timestamp(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed.replace(tzinfo=UTC) if parsed.tzinfo is None else parsed.astimezone(UTC)


def _number(value: str, field_name: str) -> float:
    try:
        parsed = float(value)
    except ValueError as error:
        raise ValueError(f"NOAA ISD {field_name} must be finite") from error
    if not isfinite(parsed):
        raise ValueError(f"NOAA ISD {field_name} must be finite")
    return parsed


def _quality_rank(value: int | None) -> int:
    if value in {1, 5}:
        return 0
    if value in {0, 4}:
        return 1
    return 2
