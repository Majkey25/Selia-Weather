"""Reproduce the frozen January 2026 regional versus station temperature comparison offline."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import UTC, date, datetime
from hashlib import sha256
from math import isclose
from pathlib import Path
from typing import cast

from aladin_ensemble.align import DateRange
from aladin_ensemble.backtest import BacktestConfig, build_backtest_dataset
from aladin_ensemble.baselines import ScalarForecastCase
from aladin_ensemble.metrics import (
    block_bootstrap_mean_interval,
    mean_absolute_error,
    root_mean_square_error,
)
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.run_backtest import (
    ScalarTraining,
    evaluate_backtest_training,
    fit_backtest_training,
    lock_backtest_dataset,
    write_backtest_report,
)
from aladin_ensemble.sources.chmi_download import SelectedStation
from aladin_ensemble.sources.noaa_ghcnh import (
    download_ghcnh_year,
    parse_ghcnh_stations,
    parse_ghcnh_temperatures,
)
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
START, TRAIN_END = date(2025, 8, 28), date(2025, 12, 31)
HOLDOUT_START, END = date(2026, 1, 1), date(2026, 1, 30)
MODELS = ("icon_seamless", "ecmwf_ifs025", "gfs_seamless")
ICAOS = {"tokyo": "RJTT", "seoul": "RKSI", "shanghai": "ZSPD"}
TRUTH_SHA = {
    ("JAI0000RJTT", 2025): "48b6a05f00138d1403b8080b5fcffd1101b5ea2e4295774595aa4bb0035facea",
    ("KSI0000RKSI", 2025): "a8dd4459b51af0cea58a2205822e87bd2eaee25a74658517a56b6f99705739fe",
    ("CHI0000ZSPD", 2025): "227b3c09d203cc4883762e6f9eac5072ae074e8311ca9dcf5692dfe794381c97",
    ("JAI0000RJTT", 2026): "d9becf3d90195b42a8ba9969c220866fea1f7015d772816eae49551901099c96",
    ("KSI0000RKSI", 2026): "9d51ddf8afa3b3e9bbefd6a071631a102df56347351fbff25728fea9f7b58cb6",
    ("CHI0000ZSPD", 2026): "dc3caaabd155da00d6798aea2e2f3a64754e697c7a87593247591dd7909936c0",
}
FORECAST_SHA = (
    {
        "icon_seamless": "0b05afeb42064917bf76ddf72d351fa659a851856321483ab1e4e1bca3797371",
        "ecmwf_ifs025": "cef7bad6f55fcab208dbaf2c8f542a15624833964e8d9426bd859d4155b0132c",
        "gfs_seamless": "3ef3604a47f60539d14a080646b547e8685afb87153f4b796167183063efd0b8",
        "best_match": "f7818f58b4d8b7d0516940ed04fb265d44ad001bb9cf97f96d64531f46d0ee2a",
    },
    {
        "icon_seamless": "2ea6cb1c9a8403fd1afc6691fedf21b9e5990f2553cffacb3eac0b15df223886",
        "ecmwf_ifs025": "a80e9cc73af25d6e8aec4256a8a83014cec2ff37e8d559559a0512b9af78b7b5",
        "gfs_seamless": "ad0aa7d110c55198d6350d2a34579efa2dcb0037ce1eb911581b92c0aa64ddd3",
        "best_match": "89fb2f6908140ef929d0b3e30dd2fb635e59628bce85062bfca579d79aea6b15",
    },
)


def deny_download(url: str, timeout: float, max_bytes: int) -> bytes:
    raise ValueError(f"Frozen local cache missing; downloads are disabled: {url}")


def load_inputs() -> tuple[
    tuple[SelectedStation, ...], tuple[Observation, ...], tuple[ForecastValue, ...], dict[str, str],
]:
    catalog = ROOT / "research/data/raw/noaa-ghcnh-meta/ghcnh-station-list.csv"
    catalog_sha = sha256(catalog.read_bytes()).hexdigest()
    if catalog_sha != "812ea50bd5efd1b9cc09d5800c55825b3d43b3c5aa75ab17b2848f43b1bfaaef":
        raise ValueError("Station catalog differs from the frozen study")
    with catalog.open(encoding="utf-8-sig") as source:
        station_by_icao = {item.icao: item.station for item in parse_ghcnh_stations(
            source, frozenset(ICAOS.values()),
        )}
    selected = tuple(SelectedStation(target, station_by_icao[ICAOS[target.target_id]])
                     for target in WORLD_TARGETS if target.target_id in ICAOS)
    if len(selected) != 3:
        raise ValueError("Frozen cohort requires exactly three airport sites")
    hashes = {"ghcnh_catalog": catalog_sha}
    truth: list[Observation] = []
    for item in selected:
        rows: list[Observation] = []
        for year in (2025, 2026):
            cached = download_ghcnh_year(
                item.station.wigos_id, year, ROOT / "research/data/raw/noaa-ghcnh",
                fetch=deny_download,
            )
            if cached.checksum_sha256 != TRUTH_SHA[item.station.wigos_id, year]:
                raise ValueError("Observation vintage differs from frozen study")
            hashes[f"{item.station.wigos_id}:{year}"] = cached.checksum_sha256
            with cached.path.open(encoding="utf-8-sig") as source:
                rows.extend(parse_ghcnh_temperatures(
                    source, item.station, cached.checksum_sha256, start=START, end=END,
                ))
        daily = select_daily_synoptic_observations(tuple(
            row for row in rows if not any((row.valid_time.minute, row.valid_time.second,
                                           row.valid_time.microsecond))
        ))
        training_days = {
            row.valid_time.date() for row in daily if row.valid_time.date() <= TRAIN_END
        }
        holdout_days = {
            row.valid_time.date() for row in daily if row.valid_time.date() >= HOLDOUT_START
        }
        if len(training_days) != 123 or len(holdout_days) != 30:
            raise ValueError("Frozen station requires 123 training and 30 holdout dates")
        truth.extend(daily)
    points = tuple(ForecastPoint(
        item.station.wigos_id, item.station.latitude, item.station.longitude,
    ) for item in selected)
    cache = CachedDownloader(ROOT / "research/data/raw/open-meteo-worldwide", retry_attempts=1)
    forecasts: list[ForecastValue] = []
    for index, (start, end) in enumerate((
        (START, date(2025, 12, 29)), (date(2025, 12, 30), END),
    )):
        for model in (*MODELS, "best_match"):
            request = PreviousRunsRequest(model, points, ("temperature_2m",), start, end, 1)
            cached_forecast = cache.cached_previous(request)
            if cached_forecast is None:
                raise ValueError(f"Frozen forecast cache missing for {model}:{start}")
            if cached_forecast.checksum_sha256 != FORECAST_SHA[index][model]:
                raise ValueError("Forecast vintage differs from frozen study")
            hashes[f"{model}:{start}"] = cached_forecast.checksum_sha256
            forecasts.extend(parse_previous_run_values(
                cached_forecast.path.read_bytes(), request, sample_hours=WORLD_SYNOPTIC_HOURS,
            ))
    return selected, tuple(truth), tuple(forecasts), hashes


def model_value(case: ScalarForecastCase, model: str) -> float:
    value = case.best_match if model == "best_match" else case.model_values.get(model)
    if value is None:
        raise ValueError("Frozen comparison requires complete matched predictors")
    return value


def prediction(case: ScalarForecastCase, training: ScalarTraining) -> float:
    if training.fit is None or case.region in training.fallback_regions:
        return model_value(case, training.fallback_model)
    return blend_scalar(training.fit, {model: model_value(case, model) for model in MODELS})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    output = cast(Path, parser.parse_args().output_dir)
    if output.exists():
        raise ValueError("Use a new output directory; prior evidence must remain immutable")
    selected, truth, forecasts, hashes = load_inputs()
    config = BacktestConfig(DateRange(START, TRAIN_END), DateRange(HOLDOUT_START, END), MODELS)
    datasets = {"regional": build_backtest_dataset(config, forecasts, truth, selected)}
    for item in selected:
        station_id = item.station.wigos_id
        datasets[item.target.target_id] = build_backtest_dataset(
            config, tuple(row for row in forecasts if row.requested_point_id == station_id),
            tuple(row for row in truth if row.station_id == station_id), (item,),
        )
    # Freeze every fit and fallback before evaluating any January label.
    trained = {name: fit_backtest_training(dataset) for name, dataset in datasets.items()}
    registry_hash = sha256(json.dumps({
        "models": MODELS, "stations": [item.station.wigos_id for item in selected],
        "comparison": "regional versus independently fitted station weights",
    }, sort_keys=True).encode()).hexdigest()
    locks = {name: lock_backtest_dataset(
        dataset, registry_hash=registry_hash, source_hashes=hashes,
        output_dir=output / name, locked_at=datetime.now(UTC),
    ) for name, dataset in datasets.items()}
    results = {name: evaluate_backtest_training(dataset, trained[name], locks[name])
               for name, dataset in datasets.items()}
    for name, result in results.items():
        write_backtest_report(output / name / "report.json", result)
    summaries: dict[str, JsonValue] = {}
    pooled_training = trained["regional"].scalar[0][1]
    for name, dataset in datasets.items():
        segment = next(iter(dataset.segments.values()))
        fitted = trained[name].scalar[0][1]
        evaluation = results[name].scalar[0][1].evaluation
        station_prediction = tuple(prediction(case, fitted) for case in segment.holdout)
        regional_prediction = tuple(prediction(case, pooled_training) for case in segment.holdout)
        actual = tuple(case.observation for case in segment.holdout)
        station_ids = {item.station.wigos_id for item in dataset.stations}
        if evaluation.sample_count != len(actual) or evaluation.blend_score is None or not isclose(
            evaluation.blend_score, mean_absolute_error(station_prediction, actual), abs_tol=1e-12,
        ):
            raise ValueError("Descriptive comparison differs from the existing evaluator's cases")
        local_vs_regional = block_bootstrap_mean_interval(tuple(
            (case.forecast_date, abs(regional - case.observation) - abs(local - case.observation))
            for case, local, regional in zip(
                segment.holdout, station_prediction, regional_prediction, strict=True,
            )
        ))
        record: dict[str, JsonValue] = {
            "training_cases": len(segment.training), "holdout_cases": len(segment.holdout),
            "holdout_hours_utc": {str(hour): count for hour, count in sorted(Counter(
                row.valid_time.hour for row in truth
                if row.station_id in station_ids and row.valid_time.date() >= HOLDOUT_START
            ).items())},
            "training_month_counts": {month: count for month, count in sorted(Counter(
                str(case.forecast_date)[:7] for case in segment.training
            ).items())},
            "weights": None if fitted.fit is None else {
                model: weight for model, weight in fitted.fit.weights.items()
            },
            "training_selected_fallback": fitted.fallback_model,
            "holdout_training_selected_fallback_mae": evaluation.best_model_score,
            "training_fallback_regions": [region for region in sorted(fitted.fallback_regions)],
            "in_sample_monthly_improvements": [value for value in fitted.fold_improvements],
            "accepted_by_existing_gates": evaluation.accepted,
            "rejections": [reason.value for reason in evaluation.rejection_reasons],
            "holdout_individual_mae": {
                model: mean_absolute_error(tuple(model_value(case, model)
                                                 for case in segment.holdout), actual)
                for model in (*MODELS, "best_match")
            },
            "holdout_candidate_mae": mean_absolute_error(station_prediction, actual),
            "holdout_candidate_rmse": root_mean_square_error(station_prediction, actual),
            "holdout_regional_mae": mean_absolute_error(regional_prediction, actual),
            "holdout_regional_rmse": root_mean_square_error(regional_prediction, actual),
            "local_vs_regional_mae_improvement": {
                "estimate": local_vs_regional.estimate, "lower": local_vs_regional.lower,
                "upper": local_vs_regional.upper,
            },
        }
        summaries[name] = record
    summary: dict[str, JsonValue] = {
        "status": "frozen January 2026 temperature comparison", "exported": False,
        "lead_contract": "previous_day1 nominal 24h; exact original initialization unverified",
        "fits": summaries, "source_hashes": {name: checksum for name, checksum in hashes.items()},
    }
    with (output / "comparison-summary.json").open("x", encoding="utf-8") as target:
        json.dump(summary, target, indent=2, sort_keys=True)
    print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
