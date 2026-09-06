"""Reproduce the fixed, rejected East Asia temperature study from frozen local caches only."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import date
from hashlib import sha256
from pathlib import Path
from statistics import fmean
from typing import cast

from aladin_ensemble.align import DateRange, align_station_forecasts, station_distance_km
from aladin_ensemble.backtest import BacktestConfig, build_backtest_dataset
from aladin_ensemble.metrics import mean_absolute_error
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.run_backtest import run_locked_backtest
from aladin_ensemble.sources.chmi_download import SelectedStation
from aladin_ensemble.sources.noaa_ghcnh import (
    download_ghcnh_year,
    match_ghcnh_station,
    parse_ghcnh_stations,
    parse_ghcnh_temperatures,
)
from aladin_ensemble.sources.noaa_isd import parse_isd_station_history, select_isd_station_cohort
from aladin_ensemble.sources.open_meteo_runs import (
    CachedDownloader,
    PreviousRunsRequest,
    parse_previous_run_values,
)
from aladin_ensemble.train import blend_scalar
from aladin_ensemble.types import ForecastPoint, ForecastValue, Observation
from aladin_ensemble.worldwide import (
    WORLD_SYNOPTIC_HOURS,
    WORLD_TARGETS,
    select_daily_synoptic_observations,
)

ROOT = Path(__file__).resolve().parents[2]
START, TRAIN_END = date(2025, 8, 28), date(2025, 11, 29)
HOLDOUT_START, END = date(2025, 11, 30), date(2025, 12, 29)
MODELS = ("icon_seamless", "ecmwf_ifs025", "gfs_seamless")
ICAOS = {"tokyo": "RJTT", "seoul": "RKSI", "shanghai": "ZSPD"}
EXPECTED = {
    "JAI0000RJTT": "48b6a05f00138d1403b8080b5fcffd1101b5ea2e4295774595aa4bb0035facea",
    "KSI0000RKSI": "a8dd4459b51af0cea58a2205822e87bd2eaee25a74658517a56b6f99705739fe",
    "CHI0000ZSPD": "227b3c09d203cc4883762e6f9eac5072ae074e8311ca9dcf5692dfe794381c97",
    "icon_seamless": "0b05afeb42064917bf76ddf72d351fa659a851856321483ab1e4e1bca3797371",
    "ecmwf_ifs025": "cef7bad6f55fcab208dbaf2c8f542a15624833964e8d9426bd859d4155b0132c",
    "gfs_seamless": "3ef3604a47f60539d14a080646b547e8685afb87153f4b796167183063efd0b8",
    "best_match": "f7818f58b4d8b7d0516940ed04fb265d44ad001bb9cf97f96d64531f46d0ee2a",
}


def deny_download(url: str, timeout: float, max_bytes: int) -> bytes:
    raise ValueError(f"Frozen local cache missing; this reproducer cannot download {url}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    output = cast(Path, args.output_dir)
    if output.exists():
        raise ValueError("Use a new output directory; existing study evidence is immutable")
    history = ROOT / "research/data/raw/noaa-isd-history/isd-history-20250828.csv"
    catalog_path = ROOT / "research/data/raw/noaa-ghcnh-meta/ghcnh-station-list.csv"
    if sha256(history.read_bytes()).hexdigest() != (
        "1994747ab4af1b97e63adb434b4d0d022f2daee76f0c144ea9ab46be2d906604"
    ) or sha256(catalog_path.read_bytes()).hexdigest() != (
        "812ea50bd5efd1b9cc09d5800c55825b3d43b3c5aa75ab17b2848f43b1bfaaef"
    ):
        raise ValueError("Station metadata differs from the frozen study")
    with history.open(encoding="utf-8-sig") as source:
        old_stations = parse_isd_station_history(
            source, required_start=date(2025, 3, 13), required_end=date(2025, 8, 24),
        )
    old = select_isd_station_cohort(
        tuple(target for target in WORLD_TARGETS if target.region == "EAST_ASIA"),
        old_stations, max_distance_km=250,
    )
    with catalog_path.open(encoding="utf-8-sig") as source:
        catalog = parse_ghcnh_stations(source, frozenset(ICAOS.values()))
    selected: list[SelectedStation] = []
    truth: list[Observation] = []
    hashes: dict[str, str] = {}
    crosswalk: list[dict[str, JsonValue]] = []
    for item in old:
        matched = match_ghcnh_station(item.station, ICAOS[item.target.target_id], catalog)
        selected.append(SelectedStation(item.target, matched.station))
        crosswalk.append({
            "target": item.target.target_id, "isd_id": item.station.wigos_id,
            "ghcnh_id": matched.station.wigos_id, "icao": matched.icao,
            "latitude": matched.station.latitude, "longitude": matched.station.longitude,
            "elevation_m": matched.station.elevation_m,
            "distance_km": station_distance_km(
                item.station.latitude, item.station.longitude,
                matched.station.latitude, matched.station.longitude,
            ),
        })
        cached = download_ghcnh_year(
            matched.station.wigos_id, 2025, ROOT / "research/data/raw/noaa-ghcnh",
            fetch=deny_download,
        )
        if cached.checksum_sha256 != EXPECTED[matched.station.wigos_id]:
            raise ValueError("Observation vintage differs from the frozen study")
        hashes[matched.station.wigos_id] = cached.checksum_sha256
        with cached.path.open(encoding="utf-8-sig") as source:
            rows = tuple(parse_ghcnh_temperatures(
                source, matched.station, cached.checksum_sha256, start=START, end=END,
            ))
        daily = select_daily_synoptic_observations(tuple(
            row for row in rows if row.valid_time.minute == 0 and row.valid_time.second == 0
        ))
        if len({row.valid_time.date() for row in daily if row.valid_time.date() <= TRAIN_END}) < 90:
            raise ValueError("Station has fewer than 90 independent training days")
        holdout_days = {
            row.valid_time.date() for row in daily if row.valid_time.date() >= HOLDOUT_START
        }
        if len(holdout_days) < 30:
            raise ValueError("Station has fewer than 30 holdout days")
        truth.extend(daily)
    points = tuple(ForecastPoint(
        item.station.wigos_id, item.station.latitude, item.station.longitude,
    ) for item in selected)
    cache = CachedDownloader(ROOT / "research/data/raw/open-meteo-worldwide", retry_attempts=1)
    forecasts: list[ForecastValue] = []
    for model in (*MODELS, "best_match"):
        request = PreviousRunsRequest(model, points, ("temperature_2m",), START, END, 1)
        cached_forecast = cache.cached_previous(request)
        if cached_forecast is None:
            raise ValueError(f"Frozen forecast cache missing for {model}; downloads are disabled")
        if cached_forecast.checksum_sha256 != EXPECTED[model]:
            raise ValueError("Forecast vintage differs from the frozen study")
        hashes[model] = cached_forecast.checksum_sha256
        forecasts.extend(parse_previous_run_values(
            cached_forecast.path.read_bytes(), request, sample_hours=WORLD_SYNOPTIC_HOURS,
        ))
    config = BacktestConfig(DateRange(START, TRAIN_END), DateRange(HOLDOUT_START, END), MODELS)
    dataset = build_backtest_dataset(config, tuple(forecasts), tuple(truth), tuple(selected))
    registry = json.dumps(
        {"region": "EAST_ASIA", "crosswalk": crosswalk, "models": MODELS}, sort_keys=True,
    )
    result = run_locked_backtest(
        dataset, registry_hash=sha256(registry.encode()).hexdigest(), source_hashes=hashes,
        output_dir=output, bootstrap_repetitions=1000,
    )
    key, fitted = result.scalar[0]
    fit = fitted.fit
    if fit is None:
        raise ValueError("Fixed study did not reproduce its candidate fit")
    aligned = align_station_forecasts(tuple(forecasts), tuple(truth))
    cases: dict[tuple[str, str], dict[str, float]] = {}
    observed: dict[tuple[str, str], float] = {}
    for item in aligned:
        if item.forecast.valid_time.date() < HOLDOUT_START or item.truth_value is None:
            continue
        if item.forecast.value is None:
            raise ValueError("Frozen holdout unexpectedly has a missing predictor")
        identity = item.station_id, item.forecast.valid_time.isoformat()
        cases.setdefault(identity, {})[item.forecast.model_id] = item.forecast.value
        observed[identity] = item.truth_value
    station_scores: dict[str, dict[str, float]] = {}
    for station in selected:
        identities = sorted(
            identity for identity in cases if identity[0] == station.station.wigos_id
        )
        if len(identities) != 30:
            raise ValueError("Frozen per-station holdout must have 30 cases")
        station_truth = tuple(observed[identity] for identity in identities)
        station_scores[station.target.target_id] = {
            model: mean_absolute_error(
                tuple(cases[identity][model] for identity in identities), station_truth,
            )
            for model in (*MODELS, "best_match")
        }
        station_scores[station.target.target_id]["candidate_blend"] = mean_absolute_error(
            tuple(blend_scalar(fit, cases[identity]) for identity in identities), station_truth,
        )
    summary = {
        "status": "reproduction_of_rejected_frozen_study", "exported": False,
        "region": "EAST_ASIA", "variable": key.variable, "lead_hours": key.lead_hours,
        "lead_contract": "previous_day1 nominal offset; original initialization unverified",
        "accepted": fitted.evaluation.accepted, "blend_mae": fitted.evaluation.blend_score,
        "fallback_mae": fitted.evaluation.best_model_score, "fallback_model": fitted.fallback_model,
        "rejections": [reason.value for reason in fitted.evaluation.rejection_reasons],
        "in_sample_monthly_improvements": fitted.evaluation.fold_improvements,
        "training_cases_by_month": dict(sorted(Counter(
            str(case.forecast_date)[:7] for case in dataset.segments[key].training
        ).items())),
        "holdout_station_mae": station_scores,
        "holdout_aggregate_mae": {
            model: fmean(values[model] for values in station_scores.values())
            for model in (*MODELS, "best_match", "candidate_blend")
        },
        "crosswalk": crosswalk, "source_hashes": hashes,
    }
    with (output / "reproduction-summary.json").open("x", encoding="utf-8") as target:
        json.dump(summary, target, indent=2, sort_keys=True)
    print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
