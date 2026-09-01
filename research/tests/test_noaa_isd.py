from __future__ import annotations

from dataclasses import replace
from datetime import date
from io import StringIO
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import pytest

from aladin_ensemble.sources.chmi_download import ResearchTarget
from aladin_ensemble.sources.noaa_isd import (
    IsdDataRequest,
    build_isd_data_url,
    download_isd_csv,
    parse_isd_observations,
    parse_isd_station_history,
    select_isd_station_cohort,
)


def test_isd_parses_documented_scaling_units_and_quality() -> None:
    observations = tuple(parse_isd_observations(StringIO(ISD_CSV), "a" * 64))
    by_variable = {observation.variable: observation for observation in observations}

    assert by_variable["temperature_2m"].value == 23.4
    assert by_variable["dew_point_2m"].value == 12.3
    assert by_variable["sea_level_pressure"].value == 1012.3
    assert by_variable["wind_direction_10m"].value == 90.0
    assert by_variable["wind_speed_10m"].value == 3.4
    assert by_variable["visibility"].value == 10_000.0
    precipitation = by_variable["precipitation"]
    assert precipitation.value == 1.2
    assert precipitation.interval is not None
    assert precipitation.interval.total_seconds() == 3600
    assert precipitation.accumulation == "interval"
    assert all(observation.source_checksum == "a" * 64 for observation in observations)


def test_isd_rejects_failed_quality_without_discarding_valid_fields() -> None:
    observations = tuple(
        parse_isd_observations(
            StringIO(ISD_CSV.replace('"+0234,1"', '"+0234,3"')),
            "a" * 64,
        )
    )

    assert "temperature_2m" not in {observation.variable for observation in observations}
    assert "wind_speed_10m" in {observation.variable for observation in observations}


def test_isd_rejects_incomplete_or_invalid_station_rows() -> None:
    with pytest.raises(ValueError, match="columns"):
        tuple(parse_isd_observations(StringIO("STATION,DATE\nA,2026-08-31T20:00:00\n"), "a" * 64))
    with pytest.raises(ValueError, match="coordinates"):
        tuple(
            parse_isd_observations(
                StringIO(ISD_CSV.replace('"35.33346"', '"135.33346"')),
                "a" * 64,
            )
        )


def test_isd_history_selects_unique_nearby_stations_with_full_period() -> None:
    stations = parse_isd_station_history(
        StringIO(ISD_HISTORY),
        required_start=date(2026, 1, 1),
        required_end=date(2026, 8, 31),
    )
    targets = (
        ResearchTarget("new-york", "NORTH_AMERICA", 40.7, -74.0),
        ResearchTarget("tokyo", "EAST_ASIA", 35.6, 139.7),
    )

    selected = select_isd_station_cohort(targets, stations, max_distance_km=200.0)

    assert "A0000253928" in {station.wigos_id for station in stations}
    assert "99999899999" not in {station.wigos_id for station in stations}
    assert tuple(item.target.target_id for item in selected) == ("new-york", "tokyo")
    assert tuple(item.station.wigos_id for item in selected) == (
        "72505394728",
        "47671099999",
    )


def test_isd_history_rejects_incomplete_columns_and_missing_nearby_station() -> None:
    with pytest.raises(ValueError, match="columns"):
        parse_isd_station_history(
            StringIO("USAF,WBAN\n725053,94728\n"),
            required_start=date(2026, 1, 1),
            required_end=date(2026, 8, 31),
        )
    stations = parse_isd_station_history(
        StringIO(ISD_HISTORY),
        required_start=date(2026, 1, 1),
        required_end=date(2026, 8, 31),
    )
    with pytest.raises(ValueError, match="nearby station"):
        select_isd_station_cohort(
            (ResearchTarget("ocean", "GLOBAL", 0.0, -140.0),),
            stations,
            max_distance_km=50.0,
        )


def test_isd_download_uses_bounded_request_and_verified_cache(tmp_path: Path) -> None:
    request = IsdDataRequest(
        ("72505394728", "A0000253928"),
        date(2025, 4, 27),
        date(2025, 8, 24),
    )
    query = parse_qs(urlsplit(build_isd_data_url(request)).query)
    assert query == {
        "dataset": ["global-hourly"],
        "endDate": ["2025-08-24"],
        "format": ["csv"],
        "includeAttributes": ["true"],
        "includeStationName": ["true"],
        "startDate": ["2025-04-27"],
        "stations": ["72505394728,A0000253928"],
    }
    calls = 0

    def http_get(url: str, timeout: float, max_bytes: int) -> bytes:
        nonlocal calls
        calls += 1
        assert url == build_isd_data_url(request)
        assert timeout == 30.0
        assert max_bytes == 50_000_000
        return ISD_CSV.encode()

    first = download_isd_csv(request, tmp_path, http_get=http_get)
    second = download_isd_csv(request, tmp_path, http_get=http_get)

    assert second == replace(first, from_cache=True)
    assert calls == 1
    first.path.write_bytes(b"corrupt")
    with pytest.raises(ValueError, match="checksum"):
        download_isd_csv(request, tmp_path, http_get=http_get)


ISD_CSV = """\
"STATION","DATE","LATITUDE","LONGITUDE","ELEVATION","TMP","DEW","SLP","WND","VIS","AA1"
"72344013964","2026-08-31T20:00:00","35.33346","-94.36526","136.7","+0234,1","+0123,1","10123,1","090,1,N,0034,1","010000,1,9,9","01,0012,9,1"
"""

ISD_HISTORY = (
    '"USAF","WBAN","STATION NAME","CTRY","STATE","ICAO","LAT","LON",'
    '"ELEV(M)","BEGIN","END"\n'
    '"725053","94728","NEW YORK CENTRAL PARK","US","NY","","+40.779",'
    '"-073.969","+47.5","19480101","20261231"\n'
    '"476710","99999","TOKYO INTL","JP","","RJTT","+35.552","+139.780",'
    '"+6.4","19510101","20261231"\n'
    '"A00002","53928","BRENHAM MUNICIPAL AIRPORT","US","TX","","+30.219",'
    '"-096.374","+93.0","20130701","20261231"\n'
    '"999998","99999","BOGUS ICELAND","IS","","","+99.999","+999.999",'
    '"-0999.0","20200101","20261231"\n'
    '"999999","99999","OLD STATION","US","NY","","+40.700","-074.000",'
    '"+10.0","19510101","20251231"\n'
)
