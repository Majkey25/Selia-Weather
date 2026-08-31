from __future__ import annotations

import argparse
import hashlib
import json
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from math import cos, hypot, isfinite, radians, sin
from pathlib import Path
from statistics import fmean
from time import sleep
from typing import Literal, cast

from aladin_ensemble.align import DateRange
from aladin_ensemble.backtest import (
    BacktestConfig,
    BacktestDataset,
    SegmentDataset,
    SegmentKey,
    build_backtest_dataset,
    write_dataset_manifest,
)
from aladin_ensemble.baselines import ScalarForecastCase
from aladin_ensemble.evaluate import (
    EvaluationSample,
    HoldoutLock,
    SegmentEvaluation,
    evaluate_segment,
    write_holdout_lock,
)
from aladin_ensemble.fallback import FitFailure, SegmentSelector
from aladin_ensemble.metrics import (
    BrierDecomposition,
    ContingencyScores,
    brier_decomposition,
    threshold_scores,
)
from aladin_ensemble.registry import JsonValue, RequestBudget
from aladin_ensemble.sources.chmi_download import (
    CZECH_TARGETS,
    ChmiMonthlyDownloader,
    ChmiMonthlyRequest,
    SelectedStation,
    select_station_cohort,
)
from aladin_ensemble.sources.chmi_station import (
    ElementMetadata,
    Station,
    parse_element_metadata,
    parse_station_metadata,
    parse_station_observations,
)
from aladin_ensemble.sources.open_meteo_runs import (
    CachedDownloader,
    ForecastPoint,
    PreviousRunsRequest,
    estimate_previous_runs_budget,
    parse_previous_run_values,
)
from aladin_ensemble.train import (
    OccurrenceCalibration,
    WeightFit,
    blend_positive_amount,
    blend_scalar,
    blend_wind,
    fit_occurrence_calibration,
    fit_positive_amount_weights,
    fit_scalar_weights,
    fit_wind_vector_weights,
    predict_occurrence,
)
from aladin_ensemble.types import ForecastValue, Observation

FORECAST_VARIABLES = (
    "temperature_2m",
    "wind_speed_10m",
    "wind_direction_10m",
    "precipitation",
)
FIXED_LEADS = tuple(range(1, 8))
PRECIPITATION_EVENT_THRESHOLD_MM = 0.1
PRECIPITATION_THRESHOLDS_MM = (0.1, 1.0, 5.0)
Sleeper = Callable[[float], None]
UtcClock = Callable[[], datetime]
PreflightStatus = Literal["ready", "blocked"]


@dataclass(frozen=True, slots=True)
class FittedSegment:
    fallback_model: str
    fit: WeightFit | None
    evaluation: SegmentEvaluation

    @property
    def export_fit(self) -> WeightFit | None:
        return self.fit if self.evaluation.accepted else None


@dataclass(frozen=True, slots=True)
class ScalarTraining:
    eligible_models: tuple[str, ...]
    fallback_model: str
    fit: WeightFit | None
    fold_improvements: tuple[float, ...]


@dataclass(frozen=True, slots=True)
class WindTraining:
    eligible_models: tuple[str, ...]
    fallback_model: str
    fit: WeightFit | None
    fold_improvements: tuple[float, ...]


@dataclass(frozen=True, slots=True)
class PrecipitationTraining:
    eligible_models: tuple[str, ...]
    fallback_model: str
    occurrence: OccurrenceCalibration | None
    occurrence_threshold: float
    amount: WeightFit | None
    occurrence_fold_improvements: tuple[float, ...]
    amount_fold_improvements: tuple[float, ...]

    def __post_init__(self) -> None:
        if not 0 <= self.occurrence_threshold <= 1:
            raise ValueError("occurrence_threshold must be from zero through one")


@dataclass(frozen=True, slots=True)
class FittedPrecipitation:
    fallback_model: str
    occurrence: OccurrenceCalibration | None
    occurrence_threshold: float
    amount: WeightFit | None
    occurrence_evaluation: SegmentEvaluation
    amount_evaluation: SegmentEvaluation
    brier: BrierDecomposition
    thresholds: tuple[tuple[float, ContingencyScores], ...]

    def __post_init__(self) -> None:
        if not 0 <= self.occurrence_threshold <= 1:
            raise ValueError("occurrence_threshold must be from zero through one")

    @property
    def accepted(self) -> bool:
        return self.occurrence_evaluation.accepted and self.amount_evaluation.accepted


@dataclass(frozen=True, slots=True)
class BacktestTraining:
    trained_at: datetime
    scalar: tuple[tuple[SegmentKey, ScalarTraining], ...]
    wind: tuple[tuple[int, WindTraining], ...]
    precipitation: tuple[tuple[int, PrecipitationTraining], ...]

    def __post_init__(self) -> None:
        _require_utc(self.trained_at, "trained_at")


@dataclass(frozen=True, slots=True)
class BacktestRun:
    training: BacktestTraining
    lock: HoldoutLock
    scalar: tuple[tuple[SegmentKey, FittedSegment], ...]
    wind: tuple[tuple[int, FittedSegment], ...]
    precipitation: tuple[tuple[int, FittedPrecipitation], ...]


@dataclass(frozen=True, slots=True)
class BacktestPreflight:
    status: PreflightStatus
    reason: str | None
    training_start: date
    training_end: date
    holdout_start: date
    holdout_end: date
    model_count: int
    station_count: int
    forecast_requests: int
    truth_requests: int

    def __post_init__(self) -> None:
        if self.status == "ready" and self.reason is not None:
            raise ValueError("ready preflight cannot have a blocking reason")
        if self.status == "blocked" and not self.reason:
            raise ValueError("blocked preflight requires a reason")
        if min(
            self.model_count,
            self.station_count,
            self.forecast_requests,
            self.truth_requests,
        ) <= 0:
            raise ValueError("preflight counts must be positive")


def build_backtest_preflight(
    config: BacktestConfig,
    stations: Sequence[SelectedStation],
    *,
    now: datetime,
    provider_limit: int,
) -> BacktestPreflight:
    if now.tzinfo is None or now.utcoffset() != UTC.utcoffset(now):
        raise ValueError("now must be timezone-aware UTC")
    requests, budget = build_previous_requests(
        config.model_ids,
        stations,
        config.train.start,
        config.holdout.end,
        provider_limit=provider_limit,
    )
    budget.require_within_limit()
    truth = monthly_truth_requests(stations, config.train.start, config.holdout.end)
    complete_before = date(now.year, now.month, 1)
    reason = None if config.holdout.end < complete_before else "holdout_month_incomplete"
    return BacktestPreflight(
        status="ready" if reason is None else "blocked",
        reason=reason,
        training_start=config.train.start,
        training_end=config.train.end,
        holdout_start=config.holdout.start,
        holdout_end=config.holdout.end,
        model_count=len(config.model_ids),
        station_count=len(stations),
        forecast_requests=len(requests),
        truth_requests=len(truth),
    )


def preflight_payload(preflight: BacktestPreflight) -> dict[str, JsonValue]:
    return {
        "forecast_requests": preflight.forecast_requests,
        "holdout": {
            "end": preflight.holdout_end.isoformat(),
            "start": preflight.holdout_start.isoformat(),
        },
        "model_count": preflight.model_count,
        "reason": preflight.reason,
        "station_count": preflight.station_count,
        "status": preflight.status,
        "training": {
            "end": preflight.training_end.isoformat(),
            "start": preflight.training_start.isoformat(),
        },
        "truth_requests": preflight.truth_requests,
    }


def lock_backtest_dataset(
    dataset: BacktestDataset,
    *,
    registry_hash: str,
    source_hashes: Mapping[str, str],
    output_dir: Path,
    locked_at: datetime,
) -> HoldoutLock:
    if output_dir.exists():
        raise ValueError("output_dir already exists")
    output_dir.mkdir(parents=True)
    manifest_hash = write_dataset_manifest(
        output_dir / "dataset-manifest.json",
        dataset,
        registry_hash,
        source_hashes,
    )
    lock = HoldoutLock(dataset.config.train, dataset.config.holdout, manifest_hash, locked_at)
    write_holdout_lock(output_dir / "holdout-lock.json", lock)
    return lock


def run_locked_backtest(
    dataset: BacktestDataset,
    *,
    registry_hash: str,
    source_hashes: Mapping[str, str],
    output_dir: Path,
    clock: UtcClock = lambda: datetime.now(UTC),
    bootstrap_repetitions: int = 1_000,
) -> BacktestRun:
    if output_dir.exists():
        raise ValueError("output_dir already exists")
    training = fit_backtest_training(dataset, clock=clock)
    locked_at = clock()
    _require_utc(locked_at, "locked_at")
    if locked_at < training.trained_at:
        raise ValueError("holdout lock precedes fitted training artifacts")
    lock = lock_backtest_dataset(
        dataset,
        registry_hash=registry_hash,
        source_hashes=source_hashes,
        output_dir=output_dir,
        locked_at=locked_at,
    )
    result = evaluate_backtest_training(
        dataset,
        training,
        lock,
        bootstrap_repetitions=bootstrap_repetitions,
    )
    write_backtest_report(output_dir / "report.json", result)
    return result


def fit_backtest_training(
    dataset: BacktestDataset,
    *,
    clock: UtcClock = lambda: datetime.now(UTC),
) -> BacktestTraining:
    scalar: list[tuple[SegmentKey, ScalarTraining]] = []
    precipitation: list[tuple[int, PrecipitationTraining]] = []
    for key, segment in sorted(dataset.segments.items()):
        if key.variable == "precipitation":
            precipitation.append((key.lead_hours, fit_precipitation_training(segment)))
        elif key.variable not in {"wind_speed", "wind_direction"}:
            scalar.append((key, fit_scalar_training(segment)))
    speed_leads = {
        key.lead_hours for key in dataset.segments if key.variable == "wind_speed"
    }
    direction_leads = {
        key.lead_hours for key in dataset.segments if key.variable == "wind_direction"
    }
    if speed_leads != direction_leads:
        raise ValueError("wind speed and direction lead sets do not match")
    wind = tuple(
        (
            lead_hours,
            fit_wind_vector_training(
                dataset.segments[SegmentKey("wind_speed", lead_hours)],
                dataset.segments[SegmentKey("wind_direction", lead_hours)],
            ),
        )
        for lead_hours in sorted(speed_leads)
    )
    return BacktestTraining(clock(), tuple(scalar), wind, tuple(precipitation))


def evaluate_backtest_training(
    dataset: BacktestDataset,
    training: BacktestTraining,
    lock: HoldoutLock,
    *,
    bootstrap_repetitions: int = 1_000,
) -> BacktestRun:
    if lock.train != dataset.config.train or lock.holdout != dataset.config.holdout:
        raise ValueError("holdout lock ranges do not match dataset")
    scalar = tuple(
        (
            key,
            evaluate_scalar_training(
                dataset.segments[key],
                fitted,
                lock,
                trained_at=training.trained_at,
                bootstrap_repetitions=bootstrap_repetitions,
            ),
        )
        for key, fitted in training.scalar
    )
    wind = tuple(
        (
            lead_hours,
            evaluate_wind_vector_training(
                dataset.segments[SegmentKey("wind_speed", lead_hours)],
                dataset.segments[SegmentKey("wind_direction", lead_hours)],
                fitted,
                lock,
                trained_at=training.trained_at,
                bootstrap_repetitions=bootstrap_repetitions,
            ),
        )
        for lead_hours, fitted in training.wind
    )
    precipitation = tuple(
        (
            lead_hours,
            evaluate_precipitation_training(
                dataset.segments[SegmentKey("precipitation", lead_hours)],
                fitted,
                lock,
                trained_at=training.trained_at,
                bootstrap_repetitions=bootstrap_repetitions,
            ),
        )
        for lead_hours, fitted in training.precipitation
    )
    return BacktestRun(training, lock, scalar, wind, precipitation)


def write_backtest_report(path: Path, result: BacktestRun) -> None:
    segments: list[JsonValue] = []
    for key, fitted in result.scalar:
        segments.append(
            {
                "evaluation": _evaluation_payload(fitted.evaluation),
                "kind": "scalar",
                "lead_hours": key.lead_hours,
                "variable": key.variable,
                "weights": _weight_payload(fitted.fit),
            }
        )
    for lead_hours, fitted in result.wind:
        segments.append(
            {
                "evaluation": _evaluation_payload(fitted.evaluation),
                "kind": "wind_vector",
                "lead_hours": lead_hours,
                "variable": "wind_vector",
                "weights": _weight_payload(fitted.fit),
            }
        )
    for lead_hours, fitted in result.precipitation:
        segments.append(
            {
                "amount_evaluation": _evaluation_payload(fitted.amount_evaluation),
                "amount_weights": _weight_payload(fitted.amount),
                "brier": {
                    "reliability": fitted.brier.reliability,
                    "resolution": fitted.brier.resolution,
                    "score": fitted.brier.score,
                    "uncertainty": fitted.brier.uncertainty,
                },
                "kind": "precipitation",
                "lead_hours": lead_hours,
                "occurrence": _occurrence_payload(fitted),
                "occurrence_evaluation": _evaluation_payload(
                    fitted.occurrence_evaluation
                ),
                "thresholds": [
                    {
                        "correct_negatives": score.correct_negatives,
                        "critical_success_index": score.critical_success_index,
                        "false_alarm_ratio": score.false_alarm_ratio,
                        "false_alarms": score.false_alarms,
                        "frequency_bias": score.frequency_bias,
                        "hits": score.hits,
                        "misses": score.misses,
                        "probability_of_detection": score.probability_of_detection,
                        "threshold_mm": threshold,
                    }
                    for threshold, score in fitted.thresholds
                ],
                "variable": "precipitation",
            }
        )
    payload: dict[str, JsonValue] = {
        "dataset_manifest_hash": result.lock.dataset_manifest_hash,
        "exported": False,
        "locked_at": result.lock.locked_at.isoformat().replace("+00:00", "Z"),
        "segments": segments,
        "status": "diagnostic",
        "trained_at": result.training.trained_at.isoformat().replace("+00:00", "Z"),
    }
    path.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n",
        encoding="utf-8",
    )


def download_and_run_backtest(
    config: BacktestConfig,
    selected: tuple[SelectedStation, ...],
    stations: tuple[Station, ...],
    metadata: Mapping[tuple[str, str, str], ElementMetadata],
    *,
    registry_path: Path,
    forecast_cache: Path,
    truth_cache: Path,
    output_dir: Path,
    provider_limit: int,
    pause_seconds: float,
) -> BacktestRun:
    if output_dir.exists():
        raise ValueError("output_dir already exists")
    requests, budget = build_previous_requests(
        config.model_ids,
        selected,
        config.train.start,
        config.holdout.end,
        provider_limit=provider_limit,
    )
    budget.require_within_limit()
    forecasts, forecast_hashes = download_previous_forecasts(
        requests,
        CachedDownloader(forecast_cache),
        pause_seconds=pause_seconds,
    )
    observations, truth_hashes = download_truth_observations(
        config,
        selected,
        stations,
        metadata,
        ChmiMonthlyDownloader(truth_cache),
    )
    duplicate_hash_keys = forecast_hashes.keys() & truth_hashes.keys()
    if duplicate_hash_keys:
        raise ValueError(f"duplicate source hash key: {sorted(duplicate_hash_keys)}")
    dataset = build_backtest_dataset(config, forecasts, observations, selected)
    return run_locked_backtest(
        dataset,
        registry_hash=hashlib.sha256(registry_path.read_bytes()).hexdigest(),
        source_hashes={**forecast_hashes, **truth_hashes},
        output_dir=output_dir,
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Preflight a locked Czech forecast backtest.")
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--station-metadata", type=Path, required=True)
    parser.add_argument("--element-metadata", type=Path, required=True)
    parser.add_argument("--train-start", type=date.fromisoformat, required=True)
    parser.add_argument("--train-end", type=date.fromisoformat, required=True)
    parser.add_argument("--holdout-start", type=date.fromisoformat, required=True)
    parser.add_argument("--holdout-end", type=date.fromisoformat, required=True)
    parser.add_argument("--provider-limit", type=int, default=10_000)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--forecast-cache", type=Path)
    parser.add_argument("--truth-cache", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--pause-seconds", type=float, default=0.5)
    arguments = parser.parse_args(argv)
    registry = cast(Path, arguments.registry)
    station_metadata = cast(Path, arguments.station_metadata)
    element_metadata = cast(Path, arguments.element_metadata)
    with station_metadata.open(encoding="utf-8") as source:
        stations = tuple(parse_station_metadata(source))
    with element_metadata.open(encoding="utf-8") as source:
        metadata = parse_element_metadata(source)
    selected = select_station_cohort(CZECH_TARGETS, stations, metadata)
    config = BacktestConfig(
        DateRange(cast(date, arguments.train_start), cast(date, arguments.train_end)),
        DateRange(cast(date, arguments.holdout_start), cast(date, arguments.holdout_end)),
        load_registry_model_ids(registry),
    )
    preflight = build_backtest_preflight(
        config,
        selected,
        now=datetime.now(UTC),
        provider_limit=cast(int, arguments.provider_limit),
    )
    print(json.dumps(preflight_payload(preflight), sort_keys=True, separators=(",", ":")))
    if preflight.status != "ready":
        return 2
    if not cast(bool, arguments.execute):
        return 0
    output_dir = cast(Path | None, arguments.output_dir)
    if output_dir is None:
        raise ValueError("--output-dir is required with --execute")
    forecast_cache = cast(Path | None, arguments.forecast_cache)
    truth_cache = cast(Path | None, arguments.truth_cache)
    result = download_and_run_backtest(
        config,
        selected,
        stations,
        metadata,
        registry_path=registry,
        forecast_cache=forecast_cache or registry.parent / "data/raw/open-meteo",
        truth_cache=truth_cache or registry.parent / "data/raw/chmi-climate",
        output_dir=output_dir,
        provider_limit=cast(int, arguments.provider_limit),
        pause_seconds=cast(float, arguments.pause_seconds),
    )
    print(
        json.dumps(
            {
                "dataset_manifest_hash": result.lock.dataset_manifest_hash,
                "report": str(output_dir / "report.json"),
                "status": "completed_diagnostic",
            },
            sort_keys=True,
            separators=(",", ":"),
        )
    )
    return 0


def load_registry_model_ids(path: Path) -> tuple[str, ...]:
    value = _json_value(json.loads(path.read_text(encoding="utf-8")))
    if not isinstance(value, dict) or value.get("status") != "complete":
        raise ValueError("model registry must be complete")
    models = value.get("models")
    if not isinstance(models, list):
        raise ValueError("model registry models must be a list")
    model_ids: list[str] = []
    for item in models:
        if not isinstance(item, dict) or item.get("verified") is not True:
            raise ValueError("model registry contains an unverified model")
        model_id = item.get("model_id")
        if not isinstance(model_id, str) or not model_id:
            raise ValueError("model registry contains an invalid model ID")
        model_ids.append(model_id)
    if not model_ids or len(set(model_ids)) != len(model_ids):
        raise ValueError("model registry IDs must be non-empty and unique")
    return tuple(sorted(model_ids))


def build_previous_requests(
    model_ids: Sequence[str],
    stations: Sequence[SelectedStation],
    start_date: date,
    end_date: date,
    *,
    provider_limit: int,
) -> tuple[tuple[PreviousRunsRequest, ...], RequestBudget]:
    if not model_ids or len(set(model_ids)) != len(model_ids):
        raise ValueError("model_ids must be non-empty and unique")
    if not stations:
        raise ValueError("stations must be non-empty")
    points = tuple(
        ForecastPoint(item.station.wigos_id, item.station.latitude, item.station.longitude)
        for item in stations
    )
    requests = tuple(
        PreviousRunsRequest(
            model_id,
            points,
            FORECAST_VARIABLES,
            start_date,
            end_date,
            lead_days,
        )
        for model_id in (*model_ids, "best_match")
        for lead_days in FIXED_LEADS
    )
    return requests, estimate_previous_runs_budget(requests, provider_limit=provider_limit)


def monthly_truth_requests(
    stations: Sequence[SelectedStation], start_date: date, end_date: date
) -> tuple[ChmiMonthlyRequest, ...]:
    if not stations or start_date > end_date:
        raise ValueError("stations and an ordered date range are required")
    months: list[tuple[int, int]] = []
    year, month = start_date.year, start_date.month
    while (year, month) <= (end_date.year, end_date.month):
        months.append((year, month))
        year, month = (year + 1, 1) if month == 12 else (year, month + 1)
    return tuple(
        ChmiMonthlyRequest(station.station.wigos_id, year, month, cadence)
        for station in stations
        for year, month in months
        for cadence in ("10min", "1hour")
    )


def sample_forecast_hours(
    values: Sequence[ForecastValue], sample_hours: Sequence[int]
) -> tuple[ForecastValue, ...]:
    if (
        not sample_hours
        or len(set(sample_hours)) != len(sample_hours)
        or any(hour not in range(24) for hour in sample_hours)
    ):
        raise ValueError("sample_hours must be non-empty, unique UTC hours")
    selected = frozenset(sample_hours)
    return tuple(value for value in values if value.valid_time.hour in selected)


def usable_truth_observation(
    observation: Observation,
    cadence: str,
    config: BacktestConfig,
) -> bool:
    if observation.value is None or not (
        config.train.contains(observation.valid_time.date())
        or config.holdout.contains(observation.valid_time.date())
    ):
        return False
    if cadence == "10min":
        return (
            observation.valid_time.minute == 0
            and observation.valid_time.second == 0
            and observation.accumulation == "instant"
            and observation.variable
            in {"temperature_2m", "wind_speed_10m", "wind_direction_10m"}
        )
    if cadence == "1hour":
        return (
            observation.valid_time.minute == 0
            and observation.variable == "precipitation"
            and observation.accumulation == "interval"
            and observation.interval == timedelta(hours=1)
        )
    raise ValueError("unsupported ČHMÚ cadence")


def download_truth_observations(
    config: BacktestConfig,
    selected: Sequence[SelectedStation],
    stations: Sequence[Station],
    metadata: Mapping[tuple[str, str, str], ElementMetadata],
    downloader: ChmiMonthlyDownloader,
) -> tuple[tuple[Observation, ...], Mapping[str, str]]:
    station_map = {station.wigos_id: station for station in stations}
    observations: list[Observation] = []
    source_hashes: dict[str, str] = {}
    for request in monthly_truth_requests(selected, config.train.start, config.holdout.end):
        cached = downloader.download(request)
        observation_type = "10M" if request.cadence == "10min" else "1H"
        with cached.path.open(encoding="utf-8") as source:
            parsed = parse_station_observations(
                source,
                station_map,
                metadata,
                observation_type,
                cached.checksum_sha256,
            )
            observations.extend(
                observation
                for observation in parsed
                if usable_truth_observation(observation, request.cadence, config)
            )
        source_hashes[
            f"{request.cadence}:{request.station_id}:{request.year}{request.month:02d}"
        ] = cached.checksum_sha256
    return tuple(observations), source_hashes


def download_previous_forecasts(
    requests: Sequence[PreviousRunsRequest],
    downloader: CachedDownloader,
    *,
    sample_hours: Sequence[int] = (12,),
    pause_seconds: float = 0.5,
    sleeper: Sleeper = sleep,
) -> tuple[tuple[ForecastValue, ...], Mapping[str, str]]:
    if not isfinite(pause_seconds) or pause_seconds < 0:
        raise ValueError("pause_seconds must be finite and non-negative")
    forecasts: list[ForecastValue] = []
    source_hashes: dict[str, str] = {}
    for request in requests:
        cached = downloader.cached_previous(request)
        if cached is None:
            cached = downloader.download_previous(request)
            sleeper(pause_seconds)
        parsed = parse_previous_run_values(
            cached.path.read_bytes(),
            request,
            sample_hours=sample_hours,
        )
        forecasts.extend(parsed)
        source_hashes[
            f"{request.model_id}:{request.lead_days}:"
            f"{request.start_date.isoformat()}:{request.end_date.isoformat()}"
        ] = cached.checksum_sha256
    return tuple(forecasts), source_hashes


def select_training_fallback(segment: SegmentDataset) -> str:
    cases = _complete_cases(segment.training, segment.eligible_models)
    if not cases:
        raise ValueError("segment has no complete training cases")
    candidates = segment.eligible_models
    if all(case.best_match is not None for case in cases):
        candidates = (*candidates, "best_match")
    scores = {
        model_id: fmean(
            abs(_model_value(case, model_id) - case.observation) for case in cases
        )
        for model_id in candidates
    }
    return min(scores, key=lambda model_id: (scores[model_id], model_id))


def fit_scalar_segment(
    segment: SegmentDataset,
    lock: HoldoutLock,
    *,
    trained_at: datetime,
    bootstrap_repetitions: int = 1_000,
) -> FittedSegment:
    training = fit_scalar_training(segment)
    return evaluate_scalar_training(
        segment,
        training,
        lock,
        trained_at=trained_at,
        bootstrap_repetitions=bootstrap_repetitions,
    )


def fit_scalar_training(segment: SegmentDataset) -> ScalarTraining:
    training = _complete_cases(segment.training, segment.eligible_models)
    if not training:
        raise ValueError("segment requires complete training cases")
    fallback_model = select_training_fallback(segment)
    fit_result = fit_scalar_weights(
        segment.eligible_models,
        tuple(_model_row(case, segment.eligible_models) for case in training),
        tuple(case.observation for case in training),
        eligible_models=frozenset(segment.eligible_models),
        minimum_samples=30,
    )
    fit = None if isinstance(fit_result, FitFailure) else fit_result
    return ScalarTraining(
        segment.eligible_models,
        fallback_model,
        fit,
        _fold_improvements(training, fit, fallback_model),
    )


def evaluate_scalar_training(
    segment: SegmentDataset,
    training: ScalarTraining,
    lock: HoldoutLock,
    *,
    trained_at: datetime,
    bootstrap_repetitions: int = 1_000,
) -> FittedSegment:
    if training.eligible_models != segment.eligible_models:
        raise ValueError("scalar training models do not match evaluation segment")
    holdout = _complete_cases(segment.holdout, segment.eligible_models)
    if training.fallback_model == "best_match":
        holdout = tuple(case for case in holdout if case.best_match is not None)
    if not holdout:
        raise ValueError("segment requires complete holdout cases")
    samples = tuple(
        EvaluationSample(
            case.forecast_date,
            case.region,
            abs(
                _blend_or_fallback(case, training.fit, training.fallback_model)
                - case.observation
            ),
            abs(_model_value(case, training.fallback_model) - case.observation),
        )
        for case in holdout
    )
    selector = SegmentSelector(
        segment.key.variable,
        f"{segment.key.lead_hours}h",
        None,
        None,
        None,
    )
    evaluation = evaluate_segment(
        selector,
        samples,
        metric="mae",
        fallback_model=training.fallback_model,
        fold_improvements=training.fold_improvements,
        missing_fallback_ok=True,
        trained_at=trained_at,
        lock=lock,
        bootstrap_repetitions=bootstrap_repetitions,
    )
    return FittedSegment(training.fallback_model, training.fit, evaluation)


def fit_precipitation_training(segment: SegmentDataset) -> PrecipitationTraining:
    _require_precipitation_segment(segment)
    cases = _complete_cases(segment.training, segment.eligible_models)
    _require_non_negative_precipitation(cases, segment.eligible_models)
    fallback_model = select_training_fallback(segment)
    occurrence_result = fit_occurrence_calibration(
        segment.eligible_models,
        tuple(_precipitation_event_row(case, segment.eligible_models) for case in cases),
        tuple(case.observation >= PRECIPITATION_EVENT_THRESHOLD_MM for case in cases),
        fold_ids=tuple(case.forecast_date.year * 12 + case.forecast_date.month for case in cases),
        regularization_candidates=(0.01, 0.1, 1.0),
        minimum_samples=60,
    )
    occurrence = None if isinstance(occurrence_result, FitFailure) else occurrence_result
    occurrence_threshold = _select_precipitation_threshold(
        cases,
        occurrence,
        fallback_model,
    )
    amount_result = fit_positive_amount_weights(
        segment.eligible_models,
        tuple(_model_row(case, segment.eligible_models) for case in cases),
        tuple(case.observation for case in cases),
        eligible_models=frozenset(segment.eligible_models),
        minimum_positive_samples=30,
    )
    amount = None if isinstance(amount_result, FitFailure) else amount_result
    occurrence_folds, amount_folds = _precipitation_fold_improvements(
        cases,
        occurrence,
        amount,
        fallback_model,
        occurrence_threshold,
    )
    return PrecipitationTraining(
        segment.eligible_models,
        fallback_model,
        occurrence,
        occurrence_threshold,
        amount,
        occurrence_folds,
        amount_folds,
    )


def evaluate_precipitation_training(
    segment: SegmentDataset,
    training: PrecipitationTraining,
    lock: HoldoutLock,
    *,
    trained_at: datetime,
    bootstrap_repetitions: int = 1_000,
) -> FittedPrecipitation:
    _require_precipitation_segment(segment)
    if training.eligible_models != segment.eligible_models:
        raise ValueError("precipitation training models do not match evaluation segment")
    holdout = _complete_cases(segment.holdout, segment.eligible_models)
    if training.fallback_model == "best_match":
        holdout = tuple(case for case in holdout if case.best_match is not None)
    _require_non_negative_precipitation(holdout, segment.eligible_models)
    probabilities = tuple(
        _precipitation_probability(case, training.occurrence, training.fallback_model)
        for case in holdout
    )
    amounts = tuple(
        _precipitation_amount(
            case,
            training.occurrence,
            training.occurrence_threshold,
            training.amount,
            training.fallback_model,
        )
        for case in holdout
    )
    events = tuple(case.observation >= PRECIPITATION_EVENT_THRESHOLD_MM for case in holdout)
    occurrence_evaluation = evaluate_segment(
        SegmentSelector(
            "precipitation_occurrence",
            f"{segment.key.lead_hours}h",
            None,
            None,
            None,
        ),
        tuple(
            EvaluationSample(
                case.forecast_date,
                case.region,
                (probability - float(event)) ** 2,
                (
                    float(
                        _model_value(case, training.fallback_model)
                        >= PRECIPITATION_EVENT_THRESHOLD_MM
                    )
                    - float(event)
                )
                ** 2,
            )
            for case, probability, event in zip(holdout, probabilities, events, strict=True)
        ),
        metric="brier",
        fallback_model=training.fallback_model,
        fold_improvements=training.occurrence_fold_improvements,
        missing_fallback_ok=True,
        trained_at=trained_at,
        lock=lock,
        bootstrap_repetitions=bootstrap_repetitions,
    )
    amount_evaluation = evaluate_segment(
        SegmentSelector(
            "precipitation_amount",
            f"{segment.key.lead_hours}h",
            None,
            None,
            None,
        ),
        tuple(
            EvaluationSample(
                case.forecast_date,
                case.region,
                abs(amount - case.observation),
                abs(_model_value(case, training.fallback_model) - case.observation),
            )
            for case, amount in zip(holdout, amounts, strict=True)
        ),
        metric="mae",
        fallback_model=training.fallback_model,
        fold_improvements=training.amount_fold_improvements,
        missing_fallback_ok=True,
        trained_at=trained_at,
        lock=lock,
        bootstrap_repetitions=bootstrap_repetitions,
    )
    observed_amounts = tuple(case.observation for case in holdout)
    return FittedPrecipitation(
        training.fallback_model,
        training.occurrence,
        training.occurrence_threshold,
        training.amount,
        occurrence_evaluation,
        amount_evaluation,
        brier_decomposition(probabilities, events),
        tuple(
            (threshold, threshold_scores(amounts, observed_amounts, threshold))
            for threshold in PRECIPITATION_THRESHOLDS_MM
        ),
    )


def fit_wind_vector_segment(
    speed_segment: SegmentDataset,
    direction_segment: SegmentDataset,
    lock: HoldoutLock,
    *,
    trained_at: datetime,
    bootstrap_repetitions: int = 1_000,
) -> FittedSegment:
    training = fit_wind_vector_training(speed_segment, direction_segment)
    return evaluate_wind_vector_training(
        speed_segment,
        direction_segment,
        training,
        lock,
        trained_at=trained_at,
        bootstrap_repetitions=bootstrap_repetitions,
    )


def fit_wind_vector_training(
    speed_segment: SegmentDataset,
    direction_segment: SegmentDataset,
) -> WindTraining:
    eligible_models = _wind_eligible_models(speed_segment, direction_segment)
    training = _paired_wind_cases(
        speed_segment.training,
        direction_segment.training,
        eligible_models,
    )
    fallback_model = _select_wind_fallback(training, eligible_models)
    fit_result = fit_wind_vector_weights(
        eligible_models,
        speed_rows=tuple(_model_row(pair[0], eligible_models) for pair in training),
        direction_rows=tuple(_model_row(pair[1], eligible_models) for pair in training),
        observed_speeds=tuple(pair[0].observation for pair in training),
        observed_directions=tuple(pair[1].observation for pair in training),
        eligible_models=frozenset(eligible_models),
        minimum_samples=30,
    )
    fit = None if isinstance(fit_result, FitFailure) else fit_result
    return WindTraining(
        eligible_models,
        fallback_model,
        fit,
        _wind_fold_improvements(training, fit, fallback_model),
    )


def evaluate_wind_vector_training(
    speed_segment: SegmentDataset,
    direction_segment: SegmentDataset,
    training: WindTraining,
    lock: HoldoutLock,
    *,
    trained_at: datetime,
    bootstrap_repetitions: int = 1_000,
) -> FittedSegment:
    if training.eligible_models != _wind_eligible_models(speed_segment, direction_segment):
        raise ValueError("wind training models do not match evaluation segments")
    holdout = _paired_wind_cases(
        speed_segment.holdout,
        direction_segment.holdout,
        training.eligible_models,
    )
    if training.fallback_model == "best_match":
        holdout = tuple(
            pair
            for pair in holdout
            if pair[0].best_match is not None and pair[1].best_match is not None
        )
    if not holdout:
        raise ValueError("paired wind segments require complete holdout cases")
    samples = tuple(
        EvaluationSample(
            speed.forecast_date,
            speed.region,
            _wind_pair_loss(speed, direction, training.fit, training.fallback_model),
            _wind_pair_loss(speed, direction, None, training.fallback_model),
        )
        for speed, direction in holdout
    )
    evaluation = evaluate_segment(
        SegmentSelector(
            "wind_vector",
            f"{speed_segment.key.lead_hours}h",
            None,
            None,
            None,
        ),
        samples,
        metric="vector_mae",
        fallback_model=training.fallback_model,
        fold_improvements=training.fold_improvements,
        missing_fallback_ok=True,
        trained_at=trained_at,
        lock=lock,
        bootstrap_repetitions=bootstrap_repetitions,
    )
    return FittedSegment(training.fallback_model, training.fit, evaluation)


def _wind_eligible_models(
    speed_segment: SegmentDataset,
    direction_segment: SegmentDataset,
) -> tuple[str, ...]:
    if (
        speed_segment.key.variable != "wind_speed"
        or direction_segment.key.variable != "wind_direction"
        or speed_segment.key.lead_hours != direction_segment.key.lead_hours
    ):
        raise ValueError("paired wind speed and direction segments are required")
    eligible_models = tuple(
        model_id
        for model_id in speed_segment.eligible_models
        if model_id in direction_segment.eligible_models
    )
    if not eligible_models:
        raise ValueError("paired wind segments have no eligible model")
    return eligible_models


def _paired_wind_cases(
    speeds: Sequence[ScalarForecastCase],
    directions: Sequence[ScalarForecastCase],
    model_ids: Sequence[str],
) -> tuple[tuple[ScalarForecastCase, ScalarForecastCase], ...]:
    def index(
        cases: Sequence[ScalarForecastCase],
    ) -> dict[tuple[date, str, str, str], ScalarForecastCase]:
        result: dict[tuple[date, str, str, str], ScalarForecastCase] = {}
        for case in cases:
            key = (case.forecast_date, case.region, case.elevation_band, case.season)
            if key in result:
                raise ValueError("paired wind cases contain a duplicate")
            result[key] = case
        return result

    speed_by_key = index(speeds)
    direction_by_key = index(directions)
    if speed_by_key.keys() != direction_by_key.keys():
        raise ValueError("paired wind cases do not match")
    return tuple(
        (speed_by_key[key], direction_by_key[key])
        for key in sorted(speed_by_key)
        if all(
            speed_by_key[key].model_values.get(model_id) is not None
            and direction_by_key[key].model_values.get(model_id) is not None
            for model_id in model_ids
        )
    )


def _select_wind_fallback(
    pairs: Sequence[tuple[ScalarForecastCase, ScalarForecastCase]],
    model_ids: Sequence[str],
) -> str:
    if not pairs:
        raise ValueError("paired wind segments require complete training cases")
    candidates = tuple(model_ids)
    if all(
        speed.best_match is not None and direction.best_match is not None
        for speed, direction in pairs
    ):
        candidates = (*candidates, "best_match")
    scores = {
        model_id: fmean(
            _wind_pair_loss(speed, direction, None, model_id)
            for speed, direction in pairs
        )
        for model_id in candidates
    }
    return min(scores, key=lambda model_id: (scores[model_id], model_id))


def _wind_pair_loss(
    speed: ScalarForecastCase,
    direction: ScalarForecastCase,
    fit: WeightFit | None,
    fallback_model: str,
) -> float:
    if fit is None:
        predicted_speed = _model_value(speed, fallback_model)
        predicted_direction = _model_value(direction, fallback_model)
    else:
        predicted_speed, predicted_direction = blend_wind(
            fit,
            {
                model_id: (_model_value(speed, model_id), _model_value(direction, model_id))
                for model_id in fit.weights
            },
        )
    predicted_angle = radians(predicted_direction)
    observed_angle = radians(direction.observation)
    return hypot(
        predicted_speed * sin(predicted_angle) - speed.observation * sin(observed_angle),
        predicted_speed * cos(predicted_angle) - speed.observation * cos(observed_angle),
    )


def _wind_fold_improvements(
    pairs: Sequence[tuple[ScalarForecastCase, ScalarForecastCase]],
    fit: WeightFit | None,
    fallback_model: str,
) -> tuple[float, ...]:
    if fit is None:
        return ()
    folds: dict[tuple[int, int], list[tuple[ScalarForecastCase, ScalarForecastCase]]] = {}
    for pair in pairs:
        folds.setdefault((pair[0].forecast_date.year, pair[0].forecast_date.month), []).append(pair)
    return tuple(
        fmean(
            _wind_pair_loss(speed, direction, None, fallback_model)
            - _wind_pair_loss(speed, direction, fit, fallback_model)
            for speed, direction in fold
        )
        for _, fold in sorted(folds.items())
    )


def _require_precipitation_segment(segment: SegmentDataset) -> None:
    if segment.key.variable != "precipitation":
        raise ValueError("precipitation segment is required")


def _require_non_negative_precipitation(
    cases: Sequence[ScalarForecastCase], model_ids: Sequence[str]
) -> None:
    if not cases:
        raise ValueError("precipitation cases are required")
    for case in cases:
        if case.observation < 0 or any(_model_value(case, model_id) < 0 for model_id in model_ids):
            raise ValueError("precipitation values must be non-negative")
        if case.best_match is not None and case.best_match < 0:
            raise ValueError("precipitation values must be non-negative")


def _precipitation_event_row(
    case: ScalarForecastCase, model_ids: Sequence[str]
) -> tuple[float, ...]:
    return tuple(
        float(_model_value(case, model_id) >= PRECIPITATION_EVENT_THRESHOLD_MM)
        for model_id in model_ids
    )


def _precipitation_probability(
    case: ScalarForecastCase,
    occurrence: OccurrenceCalibration | None,
    fallback_model: str,
) -> float:
    if occurrence is None:
        return float(
            _model_value(case, fallback_model) >= PRECIPITATION_EVENT_THRESHOLD_MM
        )
    return predict_occurrence(
        occurrence,
        {
            model_id: float(
                _model_value(case, model_id) >= PRECIPITATION_EVENT_THRESHOLD_MM
            )
            for model_id in occurrence.coefficients
        },
    )


def _select_precipitation_threshold(
    cases: Sequence[ScalarForecastCase],
    occurrence: OccurrenceCalibration | None,
    fallback_model: str,
) -> float:
    if occurrence is None:
        return 0.5
    probabilities = tuple(
        _precipitation_probability(case, occurrence, fallback_model) for case in cases
    )
    events = tuple(case.observation >= PRECIPITATION_EVENT_THRESHOLD_MM for case in cases)
    return min(
        set(probabilities),
        key=lambda threshold: (
            sum(
                (probability >= threshold) != event
                for probability, event in zip(probabilities, events, strict=True)
            ),
            abs(threshold - 0.5),
            threshold,
        ),
    )


def _precipitation_amount(
    case: ScalarForecastCase,
    occurrence: OccurrenceCalibration | None,
    occurrence_threshold: float,
    amount: WeightFit | None,
    fallback_model: str,
) -> float:
    if occurrence is None or amount is None:
        return _model_value(case, fallback_model)
    if _precipitation_probability(case, occurrence, fallback_model) < occurrence_threshold:
        return 0.0
    return blend_positive_amount(
        amount,
        {model_id: _model_value(case, model_id) for model_id in amount.weights},
    )


def _precipitation_fold_improvements(
    cases: Sequence[ScalarForecastCase],
    occurrence: OccurrenceCalibration | None,
    amount: WeightFit | None,
    fallback_model: str,
    occurrence_threshold: float,
) -> tuple[tuple[float, ...], tuple[float, ...]]:
    folds: dict[tuple[int, int], list[ScalarForecastCase]] = {}
    for case in cases:
        folds.setdefault((case.forecast_date.year, case.forecast_date.month), []).append(case)
    occurrence_improvements = (
        ()
        if occurrence is None
        else tuple(
            fmean(
                (
                    float(
                        _model_value(case, fallback_model)
                        >= PRECIPITATION_EVENT_THRESHOLD_MM
                    )
                    - float(case.observation >= PRECIPITATION_EVENT_THRESHOLD_MM)
                )
                ** 2
                - (
                    _precipitation_probability(case, occurrence, fallback_model)
                    - float(case.observation >= PRECIPITATION_EVENT_THRESHOLD_MM)
                )
                ** 2
                for case in fold
            )
            for _, fold in sorted(folds.items())
        )
    )
    amount_improvements = (
        ()
        if occurrence is None or amount is None
        else tuple(
            fmean(
                abs(_model_value(case, fallback_model) - case.observation)
                - abs(
                    _precipitation_amount(
                        case,
                        occurrence,
                        occurrence_threshold,
                        amount,
                        fallback_model,
                    )
                    - case.observation
                )
                for case in fold
            )
            for _, fold in sorted(folds.items())
        )
    )
    return occurrence_improvements, amount_improvements


def _complete_cases(
    cases: Sequence[ScalarForecastCase], model_ids: Sequence[str]
) -> tuple[ScalarForecastCase, ...]:
    return tuple(
        case
        for case in cases
        if all(case.model_values.get(model_id) is not None for model_id in model_ids)
    )


def _model_row(case: ScalarForecastCase, model_ids: Sequence[str]) -> tuple[float, ...]:
    return tuple(_model_value(case, model_id) for model_id in model_ids)


def _model_value(case: ScalarForecastCase, model_id: str) -> float:
    value = case.best_match if model_id == "best_match" else case.model_values.get(model_id)
    if value is None or not isfinite(value):
        raise ValueError(f"case has no finite value for {model_id}")
    return value


def _blend_or_fallback(
    case: ScalarForecastCase, fit: WeightFit | None, fallback_model: str
) -> float:
    if fit is None:
        return _model_value(case, fallback_model)
    values = {model_id: _model_value(case, model_id) for model_id in fit.weights}
    return blend_scalar(fit, values)


def _fold_improvements(
    cases: Sequence[ScalarForecastCase], fit: WeightFit | None, fallback_model: str
) -> tuple[float, ...]:
    if fit is None:
        return ()
    folds: dict[tuple[int, int], list[ScalarForecastCase]] = {}
    for case in cases:
        folds.setdefault((case.forecast_date.year, case.forecast_date.month), []).append(case)
    return tuple(
        fmean(
            abs(_model_value(case, fallback_model) - case.observation)
            - abs(_blend_or_fallback(case, fit, fallback_model) - case.observation)
            for case in fold
        )
        for _, fold in sorted(folds.items())
    )


def _evaluation_payload(evaluation: SegmentEvaluation) -> dict[str, JsonValue]:
    return {
        "accepted": evaluation.accepted,
        "best_model_score": evaluation.best_model_score,
        "blend_score": evaluation.blend_score,
        "fallback_model": evaluation.fallback_model,
        "fold_improvements": list(evaluation.fold_improvements),
        "improvement": {
            "estimate": evaluation.improvement.estimate,
            "lower": evaluation.improvement.lower,
            "upper": evaluation.improvement.upper,
        },
        "maximum_region_degradation": evaluation.maximum_region_degradation,
        "metric": evaluation.metric,
        "rejection_reasons": [reason.value for reason in evaluation.rejection_reasons],
        "sample_count": evaluation.sample_count,
    }


def _weight_payload(fit: WeightFit | None) -> JsonValue:
    if fit is None:
        return None
    return {
        "objective": fit.objective,
        "sample_count": fit.sample_count,
        "weights": dict(sorted(fit.weights.items())),
    }


def _occurrence_payload(fitted: FittedPrecipitation) -> JsonValue:
    if fitted.occurrence is None:
        return None
    return {
        "coefficients": dict(sorted(fitted.occurrence.coefficients.items())),
        "intercept": fitted.occurrence.intercept,
        "regularization": fitted.occurrence.regularization,
        "sample_count": fitted.occurrence.sample_count,
        "threshold": fitted.occurrence_threshold,
    }


def _require_utc(value: datetime, name: str) -> None:
    if value.tzinfo is None or value.utcoffset() != UTC.utcoffset(value):
        raise ValueError(f"{name} must be timezone-aware UTC")


def _json_value(value: object) -> JsonValue:
    if value is None or isinstance(value, str | int | float | bool):
        return value
    if isinstance(value, list):
        return [_json_value(item) for item in cast(list[object], value)]
    if isinstance(value, dict):
        result: dict[str, JsonValue] = {}
        for key, item in cast(dict[object, object], value).items():
            if not isinstance(key, str):
                raise ValueError("registry JSON object key must be text")
            result[key] = _json_value(item)
        return result
    raise ValueError("invalid registry JSON value")


if __name__ == "__main__":
    raise SystemExit(main())
