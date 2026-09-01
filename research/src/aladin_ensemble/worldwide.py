from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date
from pathlib import Path

from aladin_ensemble.registry import RequestBudget
from aladin_ensemble.sources.chmi_download import ResearchTarget, SelectedStation
from aladin_ensemble.sources.noaa_isd import (
    HttpGet,
    IsdDataRequest,
    download_isd_csv,
    parse_isd_observations,
)
from aladin_ensemble.sources.open_meteo_runs import (
    PreviousRunsRequest,
    estimate_previous_runs_budget,
)
from aladin_ensemble.types import ForecastPoint, Observation

WORLD_MODEL_IDS = (
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
WORLD_VARIABLES = (
    "temperature_2m",
    "dew_point_2m",
    "pressure_msl",
    "wind_speed_10m",
    "wind_direction_10m",
    "precipitation",
)
WORLD_TARGETS = (
    ResearchTarget("frankfurt", "EUROPE", 50.0379, 8.5622),
    ResearchTarget("new-york", "NORTH_AMERICA", 40.6413, -73.7781),
    ResearchTarget("sao-paulo", "SOUTH_AMERICA", -23.4356, -46.4731),
    ResearchTarget("nairobi", "AFRICA", -1.3192, 36.9278),
    ResearchTarget("delhi", "SOUTH_CENTRAL_ASIA", 28.5562, 77.1),
    ResearchTarget("tokyo", "EAST_ASIA", 35.5494, 139.7798),
    ResearchTarget("moscow", "NORTHERN_ASIA", 55.9726, 37.4146),
    ResearchTarget("sydney", "OCEANIA", -33.9399, 151.1753),
)
_LEAD_DAYS = tuple(range(1, 8))


def build_worldwide_previous_requests(
    stations: Sequence[SelectedStation],
    start_date: date,
    end_date: date,
    *,
    provider_limit: int,
) -> tuple[tuple[PreviousRunsRequest, ...], RequestBudget]:
    if not stations or len({item.station.wigos_id for item in stations}) != len(stations):
        raise ValueError("worldwide stations must be non-empty and unique")
    points = tuple(
        ForecastPoint(item.station.wigos_id, item.station.latitude, item.station.longitude)
        for item in stations
    )
    requests = tuple(
        PreviousRunsRequest(
            model_id,
            points,
            WORLD_VARIABLES,
            start_date,
            end_date,
            lead_days,
        )
        for model_id in (*WORLD_MODEL_IDS, "best_match")
        for lead_days in _LEAD_DAYS
    )
    return requests, estimate_previous_runs_budget(requests, provider_limit=provider_limit)


def download_worldwide_truth(
    stations: Sequence[SelectedStation],
    start_date: date,
    end_date: date,
    cache_root: Path,
    *,
    http_get: HttpGet | None = None,
) -> tuple[tuple[Observation, ...], Mapping[str, str]]:
    station_ids = tuple(sorted(item.station.wigos_id for item in stations))
    if not station_ids or len(set(station_ids)) != len(station_ids):
        raise ValueError("worldwide truth stations must be non-empty and unique")
    request = IsdDataRequest(station_ids, start_date, end_date)
    cached = download_isd_csv(request, cache_root, http_get=http_get)
    with cached.path.open(encoding="utf-8-sig") as source:
        observations = tuple(parse_isd_observations(source, cached.checksum_sha256))
    if any(not start_date <= item.valid_time.date() <= end_date for item in observations):
        raise ValueError("NOAA ISD truth is outside the requested period")
    observed_ids = {item.station_id for item in observations}
    missing = set(station_ids).difference(observed_ids)
    unexpected = observed_ids.difference(station_ids)
    if missing:
        raise ValueError(f"NOAA ISD truth is missing selected stations: {sorted(missing)}")
    if unexpected:
        raise ValueError(f"NOAA ISD truth contains unexpected stations: {sorted(unexpected)}")
    key = f"noaa-isd:{start_date.isoformat()}:{end_date.isoformat()}"
    return observations, {key: cached.checksum_sha256}
