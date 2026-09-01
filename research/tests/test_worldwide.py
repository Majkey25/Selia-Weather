from __future__ import annotations

from datetime import date
from pathlib import Path

import pytest

from aladin_ensemble.sources.chmi_download import ResearchTarget, SelectedStation
from aladin_ensemble.sources.chmi_station import Station
from aladin_ensemble.worldwide import (
    WORLD_MODEL_IDS,
    WORLD_TARGETS,
    build_worldwide_previous_requests,
    download_worldwide_truth,
)


def test_worldwide_plan_uses_exact_runtime_models_and_bounded_requests() -> None:
    stations = tuple(
        SelectedStation(
            target,
            Station(
                f"station-{index}",
                target.target_id,
                target.latitude,
                target.longitude,
                100.0,
            ),
        )
        for index, target in enumerate(WORLD_TARGETS)
    )

    requests, budget = build_worldwide_previous_requests(
        stations,
        date(2026, 1, 1),
        date(2026, 4, 30),
        provider_limit=10_000,
    )

    assert WORLD_MODEL_IDS == (
        "icon_seamless",
        "ecmwf_ifs025",
        "ecmwf_aifs025",
        "gfs_seamless",
        "gem_seamless",
        "meteofrance_seamless",
        "ukmo_seamless",
        "cma_grapes_global",
        "jma_seamless",
        "bom_access_global",
    )
    assert {request.model_id for request in requests} == {*WORLD_MODEL_IDS, "best_match"}
    assert len(requests) == 77
    assert all(len(request.points) == len(WORLD_TARGETS) for request in requests)
    assert budget.expected_http_requests == 77
    assert budget.provider_limit == 10_000


def test_world_targets_cover_each_runtime_calibration_region_once() -> None:
    assert len(WORLD_TARGETS) == len({target.target_id for target in WORLD_TARGETS})
    assert {target.region for target in WORLD_TARGETS} == {
        "EUROPE",
        "NORTH_AMERICA",
        "SOUTH_AMERICA",
        "AFRICA",
        "SOUTH_CENTRAL_ASIA",
        "EAST_ASIA",
        "NORTHERN_ASIA",
        "OCEANIA",
    }
    ResearchTarget("ocean", "GLOBAL", 0.0, -140.0)


def test_worldwide_truth_download_requires_every_selected_station(tmp_path: Path) -> None:
    stations = (
        SelectedStation(
            ResearchTarget("new-york", "NORTH_AMERICA", 40.7, -74.0),
            Station("72505394728", "New York", 40.7, -74.0, 10.0),
        ),
        SelectedStation(
            ResearchTarget("tokyo", "EAST_ASIA", 35.6, 139.7),
            Station("47671099999", "Tokyo", 35.6, 139.7, 6.0),
        ),
    )

    observations, hashes = download_worldwide_truth(
        stations,
        date(2025, 8, 24),
        date(2025, 8, 24),
        tmp_path / "complete",
        http_get=lambda _url, _timeout, _max_bytes: WORLD_ISD_CSV.encode(),
    )

    assert {item.station_id for item in observations} == {"72505394728", "47671099999"}
    assert all(item.valid_time.minute == 0 for item in observations)
    assert {
        item.valid_time.hour for item in observations if item.variable != "precipitation"
    } == {12}
    tokyo_rain = [
        item
        for item in observations
        if item.station_id == "47671099999" and item.variable == "precipitation"
    ]
    assert len(tokyo_rain) == 1
    assert tokyo_rain[0].valid_time.hour == 6
    assert tokyo_rain[0].valid_time.minute == 0
    assert tokyo_rain[0].interval is not None
    assert tokyo_rain[0].interval.total_seconds() == 3_600
    tokyo_variables = {
        item.variable for item in observations if item.station_id == "47671099999"
    }
    assert "wind_speed_10m" not in tokyo_variables
    assert "wind_direction_10m" not in tokyo_variables
    assert tuple(hashes) == ("noaa-isd:2025-08-24:2025-08-24",)

    partial_csv = "\n".join(WORLD_ISD_CSV.splitlines()[:2]) + "\n"
    with pytest.raises(ValueError, match="missing selected stations"):
        download_worldwide_truth(
            stations,
            date(2025, 8, 24),
            date(2025, 8, 24),
            tmp_path / "partial",
            http_get=lambda _url, _timeout, _max_bytes: partial_csv.encode(),
        )


WORLD_ISD_CSV = (
    '"STATION","DATE","LATITUDE","LONGITUDE","ELEVATION","TMP","DEW","SLP",'
    '"WND","VIS","AA1"\n'
    '"72505394728","2025-08-24T12:00:00","40.7","-74.0","10.0","+0234,1",'
    '"+0123,1","10123,1","090,1,N,0034,1","010000,1,9,9","01,0012,9,1"\n'
    '"47671099999","2025-08-24T12:00:00","35.6","139.7","6.0","+0240,1",'
    '"+0130,1","10110,1","100,1,N,9999,9","010000,1,9,9","06,0010,9,1"\n'
    '"47671099999","2025-08-24T12:30:00","35.6","139.7","6.0","+0241,1",'
    '"+0131,1","10111,1","100,1,N,0021,1","010000,1,9,9","01,0000,9,1"\n'
    '"47671099999","2025-08-24T06:00:00","35.6","139.7","6.0","+0230,1",'
    '"+0120,1","10120,1","090,1,N,9999,9","010000,1,9,9",""\n'
    '"47671099999","2025-08-24T05:53:00","35.6","139.7","6.0","+0220,1",'
    '"+0110,1","10130,1","080,1,N,0010,1","010000,1,9,9","01,0000,9,1"\n'
)
