from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import UTC, date, datetime
from hashlib import sha256
from io import StringIO
from pathlib import Path
from threading import Barrier

import pytest

from aladin_ensemble.sources.chmi_station import Station
from aladin_ensemble.sources.noaa_ghcnh import (
    SOURCE,
    CachedGhcnh,
    download_ghcnh_year,
    match_ghcnh_station,
    parse_ghcnh_stations,
    parse_ghcnh_temperatures,
)
from aladin_ensemble.types import Observation

STATION = Station("GMI0000EDDF", "Frankfurt", 50.0264, 8.5431, 110.9)
START, END = date(2025, 9, 1), date(2025, 12, 29)
HEADER = (
    "STATION|DATE|LATITUDE|LONGITUDE|ELEVATION|temperature|temperature_Measurement_Code|"
    "temperature_Quality_Code|temperature_Source_Code|temperature_Source_Station_ID\n"
)
ROW = "GMI0000EDDF|2025-11-01T00:20:00|50.0264|8.5431|110.9|6.0||1|223|ICAO-EDDF\n"


def parse(text: str = HEADER + ROW) -> tuple[Observation, ...]:
    return tuple(parse_ghcnh_temperatures(
        StringIO(text), STATION, sha256(text.encode()).hexdigest(), start=START, end=END,
    ))


def test_successor_mapping_requires_unique_icao_and_colocation() -> None:
    catalog = (
        "GHCN_ID,LATITUDE,LONGITUDE,ELEVATION,NAME,ICAO,WMO_ID\n"
        "GMI0000EDDF,50.0264,8.5431,110.9,Frankfurt,EDDF,\n"
        "JAI0000RJTT,35.553,139.78,10.7,Tokyo,RJTT,\n"
    )
    entries = parse_ghcnh_stations(StringIO(catalog), frozenset({"EDDF"}))
    old = Station("10637099999", "Original Frankfurt", 50.026, 8.543, 111.0)
    mapped = match_ghcnh_station(old, "EDDF", entries)
    assert mapped.station.wigos_id == "GMI0000EDDF"
    assert mapped.station.elevation_m == 110.9
    assert old.wigos_id == "10637099999"
    with pytest.raises(ValueError, match="one colocated"):
        match_ghcnh_station(old, "EDDF", (mapped, mapped))
    with pytest.raises(ValueError, match="one colocated"):
        match_ghcnh_station(replace(old, latitude=-33.9, longitude=151.2), "EDDF", entries)


def test_psv_preserves_celsius_utc_minute_station_id_and_actual_elevation() -> None:
    observations = parse((HEADER + ROW).replace("110.9|6.0", "111.2|6.0"))
    assert len(observations) == 1
    item = observations[0]
    assert item.source == SOURCE
    assert item.station_id == "GMI0000EDDF"
    assert item.valid_time == datetime(2025, 11, 1, 0, 20, tzinfo=UTC)
    assert item.value == 6.0
    assert item.unit == "°C"
    assert item.elevation_m == 111.2
    assert item.measurement_height_m is None
    assert item.source_checksum is not None


@pytest.mark.parametrize("quality,source,accepted", [
    ("", "83", True), ("1", "223", True), ("4", "223", False),
    ("0", "223", False), ("3", "223", False), ("n", "83", False),
    ("4", "313", True), ("9", "313", True), ("1", "unknown", False),
    ("", "999", False), ("", "ERA5", False),
])
def test_quality_codes_are_source_specific(quality: str, source: str, accepted: bool) -> None:
    text = (HEADER + ROW).replace("|1|223|", f"|{quality}|{source}|")
    assert bool(parse(text)) == accepted


def test_missing_or_estimated_temperature_does_not_become_zero() -> None:
    assert parse((HEADER + ROW).replace("|6.0||", "|||")) == ()
    assert parse((HEADER + ROW).replace("|6.0||", "|6.0|E|")) == ()


@pytest.mark.parametrize("old,new,error", [
    ("|6.0||", "|NaN||", "temperature is invalid"),
    ("|6.0||", "|Infinity||", "temperature is invalid"),
    ("50.0264|8.5431", "-33.9|151.2", "coordinates differ"),
    ("GMI0000EDDF|", "JAI0000RJTT|", "row station"),
    ("T00:20:00|", "T00:20:00+01:00|", "must be UTC"),
    ("|ICAO-EDDF", "|", "provenance is missing"),
])
def test_invalid_station_time_value_or_provenance_fails(old: str, new: str, error: str) -> None:
    with pytest.raises(ValueError, match=error):
        parse((HEADER + ROW).replace(old, new))


def test_duplicate_observations_are_not_double_counted() -> None:
    with pytest.raises(ValueError, match="duplicate"):
        parse(HEADER + ROW + ROW)


def test_year_download_reuses_verified_cache_and_rejects_corruption(tmp_path: Path) -> None:
    requested: list[str] = []

    def fetch(url: str, timeout: float, max_bytes: int) -> bytes:
        requested.append(url)
        assert timeout > 0 and max_bytes > 0
        return (HEADER + ROW).encode()

    first = download_ghcnh_year(STATION.wigos_id, 2025, tmp_path, fetch=fetch)
    second = download_ghcnh_year(STATION.wigos_id, 2025, tmp_path, fetch=fetch)
    assert not first.from_cache and second.from_cache
    assert len(requested) == 1
    assert requested[0].endswith("/by-year/2025/psv/GHCNh_GMI0000EDDF_2025.psv")
    first.path.write_bytes(b"corrupt")
    with pytest.raises(ValueError, match="checksum"):
        download_ghcnh_year(STATION.wigos_id, 2025, tmp_path, fetch=fetch)
    assert len(requested) == 1


def test_concurrent_cache_writers_leave_a_complete_checksum_linked_payload(tmp_path: Path) -> None:
    barrier = Barrier(2)

    def load(value: int) -> CachedGhcnh:
        def fetch(url: str, timeout: float, max_bytes: int) -> bytes:
            barrier.wait(timeout=5)
            return (HEADER + ROW).replace("|6.0|", f"|{value}.0|").encode()
        return download_ghcnh_year(STATION.wigos_id, 2025, tmp_path, fetch=fetch)

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = tuple(executor.map(load, (6, 7)))
    for result in results:
        assert sha256(result.path.read_bytes()).hexdigest() == result.checksum_sha256
    final = download_ghcnh_year(STATION.wigos_id, 2025, tmp_path)
    assert final.from_cache
    assert final.checksum_sha256 in {result.checksum_sha256 for result in results}
