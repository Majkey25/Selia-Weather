from __future__ import annotations

import hashlib
import json
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime
from math import asin, cos, isfinite, radians, sin, sqrt
from pathlib import Path
from time import sleep
from typing import Literal, cast
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from aladin_ensemble.registry import JsonValue
from aladin_ensemble.sources.chmi_station import (
    ElementMetadata,
    Station,
    uses_standard_measurement_height,
)

CHMI_CLIMATE_ROOT = "https://opendata.chmi.cz/meteorology/climate/recent/data"
DEFAULT_CACHE_ROOT = Path("data/raw/chmi-climate")
Cadence = Literal["10min", "1hour"]
ElevationBand = Literal["low", "middle", "high"]
REQUIRED_ELEMENTS = frozenset(
    {("10M", "T"), ("10M", "F"), ("10M", "D"), ("1H", "SRA1H")}
)
CZECH_BOUNDS = (11.2, 48.0, 19.7, 51.5)


@dataclass(frozen=True, slots=True)
class ResearchTarget:
    target_id: str
    region: str
    latitude: float
    longitude: float

    def __post_init__(self) -> None:
        if not self.target_id or not self.region:
            raise ValueError("target identity is required")
        if not all(isfinite(value) for value in (self.latitude, self.longitude)):
            raise ValueError("target coordinates must be finite")
        if not -90 <= self.latitude <= 90 or not -180 <= self.longitude <= 180:
            raise ValueError("target coordinates are outside WGS84 range")


@dataclass(frozen=True, slots=True)
class CzechTarget(ResearchTarget):
    def __post_init__(self) -> None:
        ResearchTarget.__post_init__(self)
        west, south, east, north = CZECH_BOUNDS
        if not west <= self.longitude <= east or not south <= self.latitude <= north:
            raise ValueError("target is outside the Czech research bounds")


CZECH_TARGETS = (
    CzechTarget("prague", "REGION_PRAGUE", 50.0755, 14.4378),
    CzechTarget("kladno", "REGION_CENTRAL_BOHEMIA", 50.1473, 14.1029),
    CzechTarget("ceske-budejovice", "REGION_SOUTH_BOHEMIAN", 48.9745, 14.4743),
    CzechTarget("plzen", "REGION_PLZEN", 49.7475, 13.3776),
    CzechTarget("karlovy-vary", "REGION_KARLOVY_VARY", 50.2319, 12.8710),
    CzechTarget("usti-nad-labem", "REGION_USTI_NAD_LABEM", 50.6611, 14.0531),
    CzechTarget("liberec", "REGION_LIBEREC", 50.7671, 15.0562),
    CzechTarget("hradec-kralove", "REGION_HRADEC_KRALOVE", 50.2092, 15.8328),
    CzechTarget("pardubice", "REGION_PARDUBICE", 50.0343, 15.7812),
    CzechTarget("jihlava", "REGION_VYSOCINA", 49.3961, 15.5912),
    CzechTarget("brno", "REGION_SOUTH_MORAVIAN", 49.1951, 16.6068),
    CzechTarget("olomouc", "REGION_OLOMOUC", 49.5938, 17.2509),
    CzechTarget("zlin", "REGION_ZLIN", 49.2244, 17.6628),
    CzechTarget("ostrava", "REGION_MORAVIAN_SILESIAN", 49.8209, 18.2625),
)


@dataclass(frozen=True, slots=True)
class SelectedStation:
    target: ResearchTarget
    station: Station

    @property
    def elevation_band(self) -> ElevationBand:
        return elevation_band(self.station.elevation_m)


def elevation_band(elevation_m: float) -> ElevationBand:
    if elevation_m < 300:
        return "low"
    if elevation_m <= 700:
        return "middle"
    return "high"


def select_station_cohort(
    targets: Sequence[CzechTarget],
    stations: Sequence[Station],
    metadata: Mapping[tuple[str, str, str], ElementMetadata],
) -> tuple[SelectedStation, ...]:
    if not targets or len({target.target_id for target in targets}) != len(targets):
        raise ValueError("targets must be non-empty and unique")
    if len({target.region for target in targets}) != len(targets):
        raise ValueError("target regions must be unique")
    if not stations or len({station.wigos_id for station in stations}) != len(stations):
        raise ValueError("stations must be non-empty and unique")
    available: dict[str, set[tuple[str, str]]] = {}
    for (observation_type, station_id, element), details in metadata.items():
        if element not in {"T", "F", "D"} or uses_standard_measurement_height(
            element, details.height_m
        ):
            available.setdefault(station_id, set()).add((observation_type, element))
    eligible = tuple(
        station
        for station in stations
        if REQUIRED_ELEMENTS.issubset(available.get(station.wigos_id, set()))
    )
    if not eligible:
        raise ValueError("no station has the required ČHMÚ elements")

    selected: list[SelectedStation] = []
    used: set[str] = set()
    for target in targets:
        candidates = tuple(station for station in eligible if station.wigos_id not in used)
        if not candidates:
            raise ValueError(f"no unique station is available for {target.region}")
        station = min(
            candidates,
            key=lambda item: (
                _distance_km(target.latitude, target.longitude, item.latitude, item.longitude),
                item.wigos_id,
            ),
        )
        selected.append(SelectedStation(target, station))
        used.add(station.wigos_id)

    for band in ("low", "middle", "high"):
        if any(item.elevation_band == band for item in selected):
            continue
        candidates = tuple(
            station
            for station in eligible
            if station.wigos_id not in used and elevation_band(station.elevation_m) == band
        )
        if not candidates:
            raise ValueError(f"{band} elevation station is unavailable")
        station = min(
            candidates,
            key=lambda item: (
                _distance_km(49.8, 15.5, item.latitude, item.longitude),
                item.wigos_id,
            ),
        )
        selected.append(
            SelectedStation(
                CzechTarget(
                    f"elevation-{band}",
                    "REGION_CZECHIA",
                    station.latitude,
                    station.longitude,
                ),
                station,
            )
        )
        used.add(station.wigos_id)
    return tuple(selected)


@dataclass(frozen=True, slots=True)
class ChmiMonthlyRequest:
    station_id: str
    year: int
    month: int
    cadence: Cadence

    def __post_init__(self) -> None:
        if not self.station_id:
            raise ValueError("station_id is required")
        if self.year < 2018 or not 1 <= self.month <= 12:
            raise ValueError("ČHMÚ monthly date is invalid")
        if self.cadence not in {"10min", "1hour"}:
            raise ValueError("unsupported ČHMÚ cadence")

    @property
    def url(self) -> str:
        prefix = "10m" if self.cadence == "10min" else "1h"
        return (
            f"{CHMI_CLIMATE_ROOT}/{self.cadence}/{self.month:02d}/"
            f"{prefix}-{self.station_id}-{self.year}{self.month:02d}.json"
        )


@dataclass(frozen=True, slots=True)
class ChmiHttpResponse:
    status: int
    headers: Mapping[str, str]
    body: bytes


@dataclass(frozen=True, slots=True)
class ChmiCachedResponse:
    path: Path
    manifest_path: Path
    checksum_sha256: str


Fetch = Callable[[Request], ChmiHttpResponse]
Clock = Callable[[], datetime]
Sleeper = Callable[[float], None]


class ChmiMonthlyDownloader:
    def __init__(
        self,
        root: Path = DEFAULT_CACHE_ROOT,
        *,
        fetch: Fetch | None = None,
        now: Clock | None = None,
        retry_attempts: int = 2,
        retry_delay_seconds: float = 1.0,
        sleeper: Sleeper | None = None,
    ) -> None:
        if retry_attempts <= 0:
            raise ValueError("retry_attempts must be positive")
        if not isfinite(retry_delay_seconds) or retry_delay_seconds < 0:
            raise ValueError("retry_delay_seconds must be finite and non-negative")
        self._root = root
        self._fetch = fetch or _fetch
        self._now = now or (lambda: datetime.now(UTC))
        self._retry_attempts = retry_attempts
        self._retry_delay_seconds = float(retry_delay_seconds)
        self._sleep = sleeper or sleep

    def download(self, request: ChmiMonthlyRequest) -> ChmiCachedResponse:
        now = self._now()
        if now.tzinfo is None or now.utcoffset() != UTC.utcoffset(now):
            raise ValueError("now must return timezone-aware UTC")
        if date(request.year, request.month, 1) >= date(now.year, now.month, 1):
            raise ValueError("ČHMÚ backtest requires a complete month")
        manifest_name = (
            f"{request.cadence}-{request.station_id}-{request.year}{request.month:02d}.json"
        )
        manifest_path = self._root / "manifests" / manifest_name
        cached = self._cached_manifest(manifest_path)
        headers = {"User-Agent": "aladin-ensemble-research/0.1"}
        if cached is not None:
            etag = _optional_text(cached.get("etag"), "etag")
            modified = _optional_text(cached.get("last_modified"), "last_modified")
            if etag is not None:
                headers["If-None-Match"] = etag
            if modified is not None:
                headers["If-Modified-Since"] = modified
        response = self._request(Request(request.url, headers=headers))
        if response.status == 304:
            if cached is None:
                raise RuntimeError("HTTP 304 without cached response")
            return self._from_cached(manifest_path, cached)
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}")
        checksum = hashlib.sha256(response.body).hexdigest()
        raw_path = self._root / "raw" / f"{checksum}.json"
        self._write_immutable(raw_path, response.body)
        payload: dict[str, JsonValue] = {
            "checksum_sha256": checksum,
            "etag": _header(response.headers, "etag"),
            "last_modified": _header(response.headers, "last-modified"),
            "retrieved_at": now.isoformat(),
            "source_url": request.url,
        }
        self._write_manifest(manifest_path, payload)
        return ChmiCachedResponse(raw_path, manifest_path, checksum)

    def _request(self, request: Request) -> ChmiHttpResponse:
        for attempt in range(self._retry_attempts):
            try:
                response = self._fetch(request)
            except RuntimeError:
                if attempt + 1 == self._retry_attempts:
                    raise
            else:
                if response.status < 500 or attempt + 1 == self._retry_attempts:
                    return response
            self._sleep(self._retry_delay_seconds)
        raise RuntimeError("request retry loop ended unexpectedly")

    def _cached_manifest(self, path: Path) -> Mapping[str, JsonValue] | None:
        if not path.exists():
            return None
        value = _json_value(json.loads(path.read_text(encoding="utf-8")))
        if not isinstance(value, dict):
            raise ValueError("cached manifest must be an object")
        return value

    def _from_cached(
        self, manifest_path: Path, payload: Mapping[str, JsonValue]
    ) -> ChmiCachedResponse:
        checksum = _text(payload.get("checksum_sha256"), "checksum_sha256")
        raw_path = self._root / "raw" / f"{checksum}.json"
        if not raw_path.is_file() or hashlib.sha256(raw_path.read_bytes()).hexdigest() != checksum:
            raise ValueError("cached response checksum is invalid")
        return ChmiCachedResponse(raw_path, manifest_path, checksum)

    @staticmethod
    def _write_immutable(path: Path, body: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            if path.read_bytes() != body:
                raise ValueError("cache collision for response checksum")
            return
        path.write_bytes(body)

    @staticmethod
    def _write_manifest(path: Path, payload: Mapping[str, JsonValue]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )


def _distance_km(
    latitude_a: float, longitude_a: float, latitude_b: float, longitude_b: float
) -> float:
    latitude_delta = radians(latitude_b - latitude_a)
    longitude_delta = radians(longitude_b - longitude_a)
    value = sin(latitude_delta / 2) ** 2 + cos(radians(latitude_a)) * cos(
        radians(latitude_b)
    ) * sin(longitude_delta / 2) ** 2
    return 6_371.0088 * 2 * asin(sqrt(value))


def _fetch(request: Request) -> ChmiHttpResponse:
    try:
        with urlopen(request, timeout=30) as response:
            return ChmiHttpResponse(
                response.status,
                dict(response.headers.items()),
                response.read(),
            )
    except HTTPError as error:
        return ChmiHttpResponse(error.code, dict(error.headers.items()), error.read())
    except (OSError, TimeoutError) as error:
        raise RuntimeError(f"request failed: {error}") from error


def _header(headers: Mapping[str, str], name: str) -> str | None:
    for key, value in headers.items():
        if key.casefold() == name:
            return value
    return None


def _optional_text(value: JsonValue | None, field_name: str) -> str | None:
    if value is None:
        return None
    return _text(value, field_name)


def _text(value: JsonValue | None, field_name: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{field_name} must be text")
    return value


def _json_value(value: object) -> JsonValue:
    if value is None or isinstance(value, str | int | float | bool):
        return value
    if isinstance(value, list):
        return [_json_value(item) for item in cast(list[object], value)]
    if isinstance(value, dict):
        result: dict[str, JsonValue] = {}
        for key, item in cast(dict[object, object], value).items():
            if not isinstance(key, str):
                raise ValueError("JSON object key must be text")
            result[key] = _json_value(item)
        return result
    raise ValueError("invalid JSON value")
