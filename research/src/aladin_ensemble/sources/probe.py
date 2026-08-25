from __future__ import annotations

import argparse
import json
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import cast
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from aladin_ensemble.registry import JsonValue, ModelRegistry, estimate_request_budget
from aladin_ensemble.types import ModelCandidate, SourceManifest

FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
PREVIOUS_RUNS_URL = "https://previous-runs-api.open-meteo.com/v1/forecast"
TERMS_URL = "https://open-meteo.com/en/terms"
FREE_DAILY_LIMIT = 10_000
REQUIRED_VARIABLES = frozenset({"temperature_2m", "precipitation", "wind_speed_10m"})
REQUIRED_HORIZON_HOURS = 24


@dataclass(frozen=True, slots=True)
class SamplePoint:
    name: str
    latitude: float
    longitude: float


@dataclass(frozen=True, slots=True)
class ModelSeed:
    model_id: str
    display_name: str
    provider: str
    documentation_url: str


# IDs are copied from current official Open-Meteo documentation controls and confirmed by probes.
OFFICIAL_MODEL_SEEDS = (
    ModelSeed("chmi_aladin_central_europe_2km", "CHMI Aladin Central Europe 2km", "CHMI", "https://open-meteo.com/en/docs/chmi-api"),
    ModelSeed("chmi_aladin_cz_1km", "CHMI Aladin CZ 1km", "CHMI", "https://open-meteo.com/en/docs/chmi-api"),
    ModelSeed("dwd_icon_d2", "DWD ICON-D2", "DWD", "https://open-meteo.com/en/docs/dwd-api"),
    ModelSeed("dwd_icon_eu", "DWD ICON-EU", "DWD", "https://open-meteo.com/en/docs/dwd-api"),
    ModelSeed("dwd_icon_global", "DWD ICON Global", "DWD", "https://open-meteo.com/en/docs/dwd-api"),
    ModelSeed("meteoswiss_icon_ch1", "MeteoSwiss ICON CH1", "MeteoSwiss", "https://open-meteo.com/en/docs/meteoswiss-api"),
    ModelSeed("meteoswiss_icon_ch2", "MeteoSwiss ICON CH2", "MeteoSwiss", "https://open-meteo.com/en/docs/meteoswiss-api"),
    ModelSeed("geosphere_arome_austria", "GeoSphere AROME Austria", "GeoSphere", "https://open-meteo.com/en/docs/geosphere-austria-api"),
    ModelSeed("dmi_harmonie_arome_europe", "DMI HARMONIE AROME Europe", "DMI", "https://open-meteo.com/en/docs/dmi-api"),
    ModelSeed("knmi_harmonie_arome_europe", "KNMI HARMONIE AROME Europe", "KNMI", "https://open-meteo.com/en/docs/knmi-api"),
    ModelSeed("meteofrance_arpege_europe", "Meteo-France ARPEGE Europe", "Meteo-France", "https://open-meteo.com/en/docs/meteofrance-api"),
    ModelSeed("ecmwf_ifs", "ECMWF IFS HRES", "ECMWF", "https://open-meteo.com/en/docs/ecmwf-api"),
    ModelSeed("ecmwf_ifs025", "ECMWF IFS 0.25°", "ECMWF", "https://open-meteo.com/en/docs/ecmwf-api"),
    ModelSeed("ecmwf_aifs025_single", "ECMWF AIFS 0.25° Single", "ECMWF", "https://open-meteo.com/en/docs/ecmwf-api"),
    ModelSeed("ukmo_global_deterministic_10km", "UKMO Global 10km", "UKMO", "https://open-meteo.com/en/docs/ukmo-api"),
    ModelSeed("ncep_gfs_global", "NCEP GFS Global", "NOAA", "https://open-meteo.com/en/docs/gfs-api"),
    ModelSeed("cmc_gem_gdps", "CMC GEM GDPS", "Canadian Weather Service", "https://open-meteo.com/en/docs/gem-api"),
)

CZECH_SAMPLE_POINTS = (
    SamplePoint("west-low", 49.7475, 13.3776),
    SamplePoint("central-middle", 50.0755, 14.4378),
    SamplePoint("north", 50.7671, 15.0562),
    SamplePoint("east", 49.8209, 18.2625),
    SamplePoint("south", 48.9745, 14.4743),
    SamplePoint("high", 50.0833, 17.2300),
    SamplePoint("border-middle", 49.1951, 16.6068),
)

FetchJson = Callable[[str], JsonValue]


def _json_value(value: object) -> JsonValue:
    if value is None or isinstance(value, str | int | float | bool):
        return value
    if isinstance(value, list):
        return [_json_value(item) for item in cast(list[object], value)]
    if isinstance(value, dict):
        result: dict[str, JsonValue] = {}
        for key, item in cast(dict[object, object], value).items():
            if not isinstance(key, str):
                raise ValueError("Open-Meteo payload has an invalid object key")
            result[key] = _json_value(item)
        return result
    raise ValueError("Open-Meteo payload has an invalid JSON value")


def _fetch_json(url: str) -> JsonValue:
    request = Request(url, headers={"User-Agent": "aladin-ensemble-research/0.1"})
    with urlopen(request, timeout=30) as response:
        return _json_value(json.loads(response.read().decode("utf-8")))


def _url(base_url: str, model_id: str, points: Sequence[SamplePoint], forecast_hours: int) -> str:
    return f"{base_url}?{urlencode({
        'latitude': ','.join(str(point.latitude) for point in points),
        'longitude': ','.join(str(point.longitude) for point in points),
        'hourly': ','.join(sorted(REQUIRED_VARIABLES)),
        'forecast_hours': str(forecast_hours),
        'models': model_id,
        'timezone': 'GMT',
    })}"


def _rows(payload: JsonValue) -> tuple[Mapping[str, JsonValue], ...]:
    values = payload if isinstance(payload, list) else [payload]
    rows: list[Mapping[str, JsonValue]] = []
    for value in values:
        if not isinstance(value, dict):
            raise ValueError("Open-Meteo payload must contain objects")
        rows.append(value)
    return tuple(rows)


def _hourly(row: Mapping[str, JsonValue]) -> Mapping[str, JsonValue]:
    hourly = row.get("hourly")
    if not isinstance(hourly, dict):
        raise ValueError("Open-Meteo payload has no hourly data")
    return hourly


def _timestamps(hourly: Mapping[str, JsonValue]) -> tuple[datetime, ...]:
    values = hourly.get("time")
    if not isinstance(values, list) or not values:
        raise ValueError("Open-Meteo payload has invalid hourly times")
    timestamps: list[datetime] = []
    for value in values:
        if not isinstance(value, str):
            raise ValueError("Open-Meteo payload has invalid hourly times")
        timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
        timestamps.append(
            timestamp.replace(tzinfo=UTC) if timestamp.tzinfo is None else timestamp.astimezone(UTC)
        )
    if any(
        later - earlier != timedelta(hours=1)
        for earlier, later in zip(timestamps, timestamps[1:], strict=False)
    ):
        raise ValueError("Open-Meteo payload has non-hourly times")
    return tuple(timestamps)


def _coverage_row(row: Mapping[str, JsonValue]) -> tuple[frozenset[str], int]:
    hourly = _hourly(row)
    timestamps = _timestamps(hourly)
    returned: set[str] = set()
    for variable in REQUIRED_VARIABLES:
        values = hourly.get(variable)
        if (
            isinstance(values, list)
            and len(values) == len(timestamps)
            and all(
                isinstance(value, int | float) and not isinstance(value, bool) for value in values
            )
        ):
            returned.add(variable)
    horizon = int((timestamps[-1] - timestamps[0]).total_seconds() // 3600)
    return frozenset(returned), horizon


def _archive_available(payload: JsonValue) -> bool:
    return all(REQUIRED_VARIABLES.issubset(_coverage_row(row)[0]) for row in _rows(payload))


def probe_candidate(
    seed: ModelSeed,
    *,
    points: Sequence[SamplePoint] = CZECH_SAMPLE_POINTS,
    fetch_json: FetchJson = _fetch_json,
    now: datetime | None = None,
) -> ModelCandidate:
    if not points:
        raise ValueError("at least one sample point is required")
    retrieved_at = now or datetime.now(UTC)
    if retrieved_at.tzinfo is None or retrieved_at.utcoffset() != UTC.utcoffset(retrieved_at):
        raise ValueError("now must be timezone-aware UTC")
    rows = _rows(fetch_json(_url(FORECAST_URL, seed.model_id, points, REQUIRED_HORIZON_HOURS + 1)))
    if len(rows) != len(points):
        raise ValueError("Open-Meteo returned an unexpected number of locations")
    coverage = tuple(_coverage_row(row) for row in rows)
    returned: set[str] = set()
    for variables, _ in coverage:
        returned.update(variables)
    covered_points = sum(
        REQUIRED_VARIABLES.issubset(variables) and horizon >= REQUIRED_HORIZON_HOURS
        for variables, horizon in coverage
    )
    archive_payload = fetch_json(_url(PREVIOUS_RUNS_URL, seed.model_id, points[:1], 25))
    return ModelCandidate(
        model_id=seed.model_id,
        display_name=seed.display_name,
        provider=seed.provider,
        required_variables=REQUIRED_VARIABLES,
        returned_variables=frozenset(returned),
        sample_points=len(points),
        covered_points=covered_points,
        required_horizon_hours=REQUIRED_HORIZON_HOURS,
        available_horizon_hours=min(horizon for _, horizon in coverage),
        verified=True,
        archive_verified=_archive_available(archive_payload),
        manifest=SourceManifest(
            provider="Open-Meteo",
            documentation_url=seed.documentation_url,
            license_name="Open-Meteo Free API (non-commercial; under 10,000 calls/day)",
            license_url=TERMS_URL,
            retrieved_at=retrieved_at,
            run_time=None,
        ),
    )


def probe_registry(
    *,
    seeds: Sequence[ModelSeed] = OFFICIAL_MODEL_SEEDS,
    points: Sequence[SamplePoint] = CZECH_SAMPLE_POINTS,
    fetch_json: FetchJson = _fetch_json,
    now: datetime | None = None,
) -> tuple[ModelRegistry, tuple[tuple[str, str], ...]]:
    registry = ModelRegistry()
    excluded: list[tuple[str, str]] = []
    for seed in seeds:
        try:
            candidate = probe_candidate(seed, points=points, fetch_json=fetch_json, now=now)
            registry.add(candidate)
        except (OSError, ValueError) as error:
            excluded.append((seed.model_id, f"probe failed: {error}"))
    return registry, tuple(excluded)


def write_probe_registry(
    path: Path, registry: ModelRegistry, excluded: Sequence[tuple[str, str]]
) -> None:
    payload = registry.payload()
    excluded_records: list[JsonValue] = []
    for model_id, reason in sorted(excluded):
        excluded_records.append({"model_id": model_id, "reason": reason})
    payload["excluded"] = excluded_records
    path.write_text(
        json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8"
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Probe verified Open-Meteo model coverage for Czechia."
    )
    parser.add_argument("--output", type=Path, default=Path("model-registry.json"))
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--dates", type=int, default=1)
    parser.add_argument("--provider-limit", type=int, default=FREE_DAILY_LIMIT)
    args = parser.parse_args()
    budget = estimate_request_budget(
        candidate_count=len(OFFICIAL_MODEL_SEEDS),
        location_count=len(CZECH_SAMPLE_POINTS),
        run_count=args.runs,
        variable_count=len(REQUIRED_VARIABLES),
        date_count=args.dates,
        provider_limit=args.provider_limit,
    )
    print(budget.summary())
    budget.require_within_limit()
    registry, excluded = probe_registry()
    write_probe_registry(args.output, registry, excluded)
    print(f"eligible: {len(registry.model_ids)}")
    print(f"excluded: {len(excluded)}")
    for model_id, reason in excluded:
        print(f"excluded {model_id}: {reason}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
