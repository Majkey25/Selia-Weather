from __future__ import annotations

from io import StringIO

import pytest

from aladin_ensemble.sources.noaa_isd import parse_isd_observations


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


ISD_CSV = """\
"STATION","DATE","LATITUDE","LONGITUDE","ELEVATION","TMP","DEW","SLP","WND","VIS","AA1"
"72344013964","2026-08-31T20:00:00","35.33346","-94.36526","136.7","+0234,1","+0123,1","10123,1","090,1,N,0034,1","010000,1,9,9","01,0012,9,1"
"""
