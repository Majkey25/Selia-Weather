from __future__ import annotations

import csv
import hashlib
from collections.abc import Callable, Iterator, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from math import asin, cos, isfinite, radians, sin, sqrt
from pathlib import Path
from typing import TextIO
from urllib.parse import urlencode

from aladin_ensemble.sources.chmi_download import ResearchTarget, SelectedStation
from aladin_ensemble.sources.chmi_station import Station
from aladin_ensemble.sources.official_runs import download_http_with_retry
from aladin_ensemble.types import Observation

ISD_SOURCE = "NOAA_ISD"
ISD_DATA_URL = "https://www.ncei.noaa.gov/access/services/data/v1"

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
_HISTORY_COLUMNS = {
    "USAF",
    "WBAN",
    "STATION NAME",
    "LAT",
    "LON",
    "ELEV(M)",
    "BEGIN",
    "END",
}
HttpGet = Callable[[str, float, int], bytes]


@dataclass(frozen=True, slots=True)
class IsdDataRequest:
    station_ids: tuple[str, ...]
    start_date: date
    end_date: date

    def __post_init__(self) -> None:
        if (
            not self.station_ids
            or tuple(sorted(set(self.station_ids))) != self.station_ids
            or any(
                len(station_id) != 11
                or not station_id.isascii()
                or not station_id.isalnum()
                for station_id in self.station_ids
            )
        ):
            raise ValueError("NOAA ISD station IDs must be sorted and unique")
        days = (self.end_date - self.start_date).days + 1
        if days not in range(1, 367):
            raise ValueError("NOAA ISD request must cover from 1 through 366 days")


@dataclass(frozen=True, slots=True)
class CachedIsdCsv:
    path: Path
    checksum_sha256: str
    source_url: str
    from_cache: bool


def build_isd_data_url(request: IsdDataRequest) -> str:
    parameters = {
        "dataset": "global-hourly",
        "endDate": request.end_date.isoformat(),
        "format": "csv",
        "includeAttributes": "true",
        "includeStationName": "true",
        "startDate": request.start_date.isoformat(),
        "stations": ",".join(request.station_ids),
    }
    return f"{ISD_DATA_URL}?{urlencode(parameters)}"


def download_isd_csv(
    request: IsdDataRequest,
    cache_root: Path,
    *,
    http_get: HttpGet | None = None,
    timeout: float = 30.0,
    max_bytes: int = 50_000_000,
) -> CachedIsdCsv:
    if timeout <= 0 or max_bytes <= 0:
        raise ValueError("NOAA ISD download limits must be positive")
    url = build_isd_data_url(request)
    key = hashlib.sha256(url.encode()).hexdigest()
    cache_root.mkdir(parents=True, exist_ok=True)
    path = cache_root / f"{key}.csv"
    checksum_path = cache_root / f"{key}.sha256"
    if path.exists() or checksum_path.exists():
        if not path.is_file() or not checksum_path.is_file():
            raise ValueError("NOAA ISD cache is incomplete")
        expected = checksum_path.read_text(encoding="ascii").strip()
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if expected != actual:
            raise ValueError("NOAA ISD cache checksum mismatch")
        return CachedIsdCsv(path, actual, url, True)
    getter = http_get or (
        lambda source_url, request_timeout, request_max_bytes: download_http_with_retry(
            source_url,
            timeout=request_timeout,
            max_bytes=request_max_bytes,
        )
    )
    payload = getter(url, timeout, max_bytes)
    if len(payload) > max_bytes:
        raise ValueError("NOAA ISD payload exceeds size limit")
    if not payload.startswith((b'"STATION"', b"STATION,")):
        raise ValueError("NOAA ISD payload is not the expected CSV")
    checksum = hashlib.sha256(payload).hexdigest()
    temporary_path = path.with_suffix(".csv.tmp")
    temporary_checksum = checksum_path.with_suffix(".sha256.tmp")
    temporary_path.write_bytes(payload)
    temporary_checksum.write_text(checksum + "\n", encoding="ascii")
    temporary_path.replace(path)
    temporary_checksum.replace(checksum_path)
    return CachedIsdCsv(path, checksum, url, False)


def parse_isd_station_history(
    source: TextIO,
    *,
    required_start: date,
    required_end: date,
) -> tuple[Station, ...]:
    if required_start > required_end:
        raise ValueError("NOAA ISD required period is invalid")
    reader = csv.DictReader(source)
    if reader.fieldnames is None or not _HISTORY_COLUMNS.issubset(reader.fieldnames):
        raise ValueError("NOAA ISD history columns are incomplete")
    stations: dict[str, Station] = {}
    for row in reader:
        usaf = _history_value(row, "USAF")
        wban = _history_value(row, "WBAN")
        if not (
            len(usaf) == 6
            and usaf.isascii()
            and usaf.isalnum()
            and len(wban) == 5
            and wban.isdigit()
        ):
            raise ValueError("NOAA ISD station ID is invalid")
        station_id = usaf + wban
        if set(station_id) == {"9"}:
            continue
        begin = _history_date(_history_value(row, "BEGIN"), "BEGIN")
        end = _history_date(_history_value(row, "END"), "END")
        if begin > required_start or end < required_end:
            continue
        coordinates = tuple(_history_value(row, name, required=False) for name in ("LAT", "LON"))
        elevation = _history_value(row, "ELEV(M)", required=False)
        if not all((*coordinates, elevation)):
            continue
        latitude = _number(coordinates[0], "latitude")
        longitude = _number(coordinates[1], "longitude")
        elevation_m = _number(elevation, "elevation")
        if not (
            -90 <= latitude <= 90
            and -180 <= longitude <= 180
            and -500 <= elevation_m <= 10_000
        ):
            continue
        station = Station(
            station_id,
            _history_value(row, "STATION NAME"),
            latitude,
            longitude,
            elevation_m,
            datetime.combine(begin, datetime.min.time(), UTC),
        )
        if station_id in stations:
            raise ValueError("NOAA ISD station IDs are duplicated")
        stations[station_id] = station
    if not stations:
        raise ValueError("NOAA ISD history contains no station for the required period")
    return tuple(stations[key] for key in sorted(stations))


def select_isd_station_cohort(
    targets: Sequence[ResearchTarget],
    stations: Sequence[Station],
    *,
    max_distance_km: float,
) -> tuple[SelectedStation, ...]:
    if not targets or len({target.target_id for target in targets}) != len(targets):
        raise ValueError("NOAA ISD targets must be non-empty and unique")
    if not stations or len({station.wigos_id for station in stations}) != len(stations):
        raise ValueError("NOAA ISD stations must be non-empty and unique")
    if not isfinite(max_distance_km) or max_distance_km <= 0:
        raise ValueError("NOAA ISD maximum distance must be positive")
    selected: list[SelectedStation] = []
    used: set[str] = set()
    for target in targets:
        candidates = tuple(
            station
            for station in stations
            if station.wigos_id not in used
            and _distance_km(
                target.latitude,
                target.longitude,
                station.latitude,
                station.longitude,
            ) <= max_distance_km
        )
        if not candidates:
            raise ValueError(f"NOAA ISD has no nearby station for {target.target_id}")
        station = min(
            candidates,
            key=lambda item: (
                _distance_km(
                    target.latitude,
                    target.longitude,
                    item.latitude,
                    item.longitude,
                ),
                item.wigos_id,
            ),
        )
        selected.append(SelectedStation(target, station))
        used.add(station.wigos_id)
    return tuple(selected)


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


def _history_value(row: dict[str, str], name: str, *, required: bool = True) -> str:
    value = row.get(name, "").strip()
    if required and not value:
        raise ValueError(f"NOAA ISD {name} is required")
    return value


def _history_date(value: str, name: str) -> date:
    try:
        return datetime.strptime(value, "%Y%m%d").date()
    except ValueError as error:
        raise ValueError(f"NOAA ISD {name} is invalid") from error


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


def _distance_km(
    latitude_a: float,
    longitude_a: float,
    latitude_b: float,
    longitude_b: float,
) -> float:
    latitude_delta = radians(latitude_b - latitude_a)
    longitude_delta = radians(longitude_b - longitude_a)
    value = sin(latitude_delta / 2) ** 2 + cos(radians(latitude_a)) * cos(
        radians(latitude_b)
    ) * sin(longitude_delta / 2) ** 2
    return 6_371.0088 * 2 * asin(sqrt(value))
