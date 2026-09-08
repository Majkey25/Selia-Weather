"""GHCNh v1.1.0 station metadata and independent temperature observations."""

from __future__ import annotations

import csv
import hashlib
import json
import re
from collections.abc import Callable, Iterator, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from math import isfinite
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import TextIO

from aladin_ensemble.align import station_distance_km
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.sources.chmi_station import Station
from aladin_ensemble.sources.official_runs import download_http_with_retry
from aladin_ensemble.types import Observation

SOURCE = "NOAA_GHCNH"
BASE_URL = "https://www.ncei.noaa.gov/oa/global-historical-climatology-network/hourly"
STATION_LIST_URL = f"{BASE_URL}/doc/ghcnh-station-list.csv"
DOCUMENTATION_URL = f"{BASE_URL}/doc/ghcnh_DOCUMENTATION.pdf"
MAX_STATION_DISTANCE_KM = 1.0
STATION_ID = re.compile(r"[A-Z]{2}[A-Z0-9-]{9}")
LEGACY_ISD_SOURCES = frozenset({"313", "314", "315", "322", "335", "343", "344", "346"})
LEGACY_METAR_SOURCES = frozenset({"220", "221", "222", "223", "347", "348"})
# NCEI ghcnh-source-list.pdf identifies these as surface station observations, not reanalysis.
DOCUMENTED_STATION_SOURCES = LEGACY_ISD_SOURCES | LEGACY_METAR_SOURCES | {"83"}


@dataclass(frozen=True, slots=True)
class GhcnhStation:
    station: Station
    icao: str
    wmo_id: str


@dataclass(frozen=True, slots=True)
class CachedGhcnh:
    path: Path
    checksum_sha256: str
    source_url: str
    from_cache: bool


def parse_ghcnh_stations(source: TextIO, icao_codes: frozenset[str]) -> tuple[GhcnhStation, ...]:
    if not icao_codes or any(re.fullmatch(r"[A-Z0-9]{4}", code) is None for code in icao_codes):
        raise ValueError("explicit ICAO station codes are required")
    reader = csv.DictReader(source)
    required = {"GHCN_ID", "LATITUDE", "LONGITUDE", "ELEVATION", "NAME", "ICAO", "WMO_ID"}
    if reader.fieldnames is None or not required.issubset(reader.fieldnames):
        raise ValueError("GHCNh station catalog columns are incomplete")
    stations: list[GhcnhStation] = []
    for row in reader:
        if row["ICAO"] not in icao_codes:
            continue
        station_id = row["GHCN_ID"]
        if STATION_ID.fullmatch(station_id) is None:
            raise ValueError("GHCNh station ID is invalid")
        stations.append(GhcnhStation(
            Station(
                station_id, row["NAME"], float(row["LATITUDE"]), float(row["LONGITUDE"]),
                float(row["ELEVATION"]),
            ),
            row["ICAO"], row["WMO_ID"],
        ))
    if len({item.station.wigos_id for item in stations}) != len(stations):
        raise ValueError("duplicate GHCNh station catalog ID")
    return tuple(stations)


def match_ghcnh_station(
    original: Station, icao: str, candidates: Sequence[GhcnhStation],
) -> GhcnhStation:
    matches = tuple(item for item in candidates if item.icao == icao and station_distance_km(
        original.latitude, original.longitude, item.station.latitude, item.station.longitude,
    ) <= MAX_STATION_DISTANCE_KM)
    if len(matches) != 1:
        raise ValueError(f"GHCNh requires one colocated ICAO match for {original.wigos_id}: {icao}")
    return matches[0]


def download_ghcnh_year(
    station_id: str,
    year: int,
    cache_root: Path,
    *,
    fetch: Callable[[str, float, int], bytes] | None = None,
    timeout: float = 30,
    max_bytes: int = 50_000_000,
) -> CachedGhcnh:
    if (
        STATION_ID.fullmatch(station_id) is None or isinstance(year, bool)
        or not 1800 <= year <= 2100
    ):
        raise ValueError("GHCNh station ID or year is invalid")
    if not isfinite(timeout) or timeout <= 0 or max_bytes <= 0:
        raise ValueError("GHCNh download limits must be positive")
    name = f"GHCNh_{station_id}_{year}.psv"
    url = f"{BASE_URL}/access/by-year/{year}/psv/{name}"
    path = cache_root / name
    checksum_path = path.with_suffix(".sha256")
    index_path = path.with_suffix(".json")
    if index_path.is_file():
        if index_path.stat().st_size > 4096:
            raise ValueError("GHCNh cache index exceeds size limit")
        record: JsonValue = json.loads(index_path.read_bytes())
        if not isinstance(record, dict) or record.get("source_url") != url:
            raise ValueError("GHCNh cache index source mismatch")
        checksum = record.get("sha256")
        if not isinstance(checksum, str) or re.fullmatch(r"[0-9a-f]{64}", checksum) is None:
            raise ValueError("GHCNh cache index checksum is invalid")
        blob = cache_root / "raw" / f"{checksum}.psv"
        if not blob.is_file() or blob.stat().st_size > max_bytes:
            raise ValueError("GHCNh cache payload is missing or oversized")
        if hashlib.sha256(blob.read_bytes()).hexdigest() != checksum:
            raise ValueError("GHCNh cache checksum mismatch")
        return CachedGhcnh(blob, checksum, url, True)
    # Read previous two-file caches without modifying their frozen data.
    if path.exists() or checksum_path.exists():
        if not path.is_file() or not checksum_path.is_file() or path.stat().st_size > max_bytes:
            raise ValueError("GHCNh cache is incomplete or exceeds size limit")
        checksum = hashlib.sha256(path.read_bytes()).hexdigest()
        if checksum != checksum_path.read_text(encoding="ascii").strip():
            raise ValueError("GHCNh cache checksum mismatch")
        return CachedGhcnh(path, checksum, url, True)
    raw = fetch(url, timeout, max_bytes) if fetch is not None else download_http_with_retry(
        url, timeout=timeout, max_bytes=max_bytes, attempts=1,
    )
    if len(raw) > max_bytes or not raw.removeprefix(b"\xef\xbb\xbf").startswith(b"STATION|"):
        raise ValueError("GHCNh response is oversized or not station PSV data")
    checksum = hashlib.sha256(raw).hexdigest()
    blob = cache_root / "raw" / f"{checksum}.psv"
    if blob.exists():
        if hashlib.sha256(blob.read_bytes()).hexdigest() != checksum:
            raise ValueError("GHCNh cache checksum mismatch")
    else:
        _atomic_write(blob, raw)
    index = json.dumps({"source_url": url, "sha256": checksum}, sort_keys=True).encode()
    _atomic_write(index_path, index)
    return CachedGhcnh(blob, checksum, url, False)


def parse_ghcnh_temperatures(
    source: TextIO,
    station: Station,
    source_checksum: str,
    *,
    start: date,
    end: date,
) -> Iterator[Observation]:
    if start > end or re.fullmatch(r"[0-9a-f]{64}", source_checksum) is None:
        raise ValueError("GHCNh period or source checksum is invalid")
    reader = csv.DictReader(source, delimiter="|")
    required = {
        "STATION", "DATE", "LATITUDE", "LONGITUDE", "ELEVATION", "temperature",
        "temperature_Measurement_Code", "temperature_Quality_Code", "temperature_Source_Code",
        "temperature_Source_Station_ID",
    }
    if reader.fieldnames is None or not required.issubset(reader.fieldnames):
        raise ValueError("GHCNh v1.1.0 temperature columns are incomplete")
    seen: set[datetime] = set()
    for row in reader:
        if None in row or any(row.get(field) is None for field in required):
            raise ValueError("GHCNh temperature row columns are incomplete")
        if row["STATION"] != station.wigos_id:
            raise ValueError("GHCNh row station does not match requested station")
        valid_time = datetime.fromisoformat(row["DATE"])
        if valid_time.tzinfo is None:
            # GHCNh documents naive DATE values as UTC; do not round observation times.
            valid_time = valid_time.replace(tzinfo=UTC)
        if valid_time.utcoffset() != timedelta(0):
            raise ValueError("GHCNh observation time must be UTC")
        if not start <= valid_time.date() <= end or not row["temperature"]:
            continue
        quality, source_code = row["temperature_Quality_Code"], row["temperature_Source_Code"]
        if row["temperature_Measurement_Code"] or not _accepted_quality(quality, source_code):
            continue
        if not source_code.isdigit() or not row["temperature_Source_Station_ID"]:
            raise ValueError("GHCNh temperature source provenance is missing")
        value = float(row["temperature"])
        if not isfinite(value) or not -100 <= value <= 70:
            raise ValueError("GHCNh temperature is invalid")
        latitude, longitude, elevation = (
            float(row["LATITUDE"]), float(row["LONGITUDE"]), float(row["ELEVATION"]),
        )
        row_station = Station(station.wigos_id, station.name, latitude, longitude, elevation)
        if station_distance_km(
            station.latitude, station.longitude, row_station.latitude, row_station.longitude,
        ) > MAX_STATION_DISTANCE_KM:
            raise ValueError("GHCNh observation coordinates differ from station catalog")
        if valid_time in seen:
            raise ValueError("duplicate GHCNh temperature observation")
        seen.add(valid_time)
        yield Observation(
            SOURCE, station.wigos_id, valid_time, latitude, longitude, elevation,
            "temperature_2m", value, "°C", quality=int(quality) if quality else None,
            # The element is nominally circa 2 m; individual measured sensor height is absent.
            measurement_height_m=None, source_checksum=source_checksum,
        )


def _accepted_quality(quality: str, source_code: str) -> bool:
    if source_code not in DOCUMENTED_STATION_SOURCES:
        return False
    if not quality:
        return True  # No GHCNh harmonized QC failure flag.
    if source_code in LEGACY_ISD_SOURCES:
        return quality in {"0", "1", "4", "5", "9"}
    if source_code in LEGACY_METAR_SOURCES:
        return quality == "1"  # Exclude suspect, calculated, removed, or unchecked values.
    return False


def _atomic_write(path: Path, body: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with NamedTemporaryFile(dir=path.parent, prefix=f".{path.name}.", delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        temporary_path.write_bytes(body)
        temporary_path.replace(path)
    finally:
        temporary_path.unlink(missing_ok=True)
