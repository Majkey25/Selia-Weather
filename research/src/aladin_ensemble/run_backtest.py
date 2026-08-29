from __future__ import annotations

import json
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from math import cos, hypot, isfinite, radians, sin
from pathlib import Path
from statistics import fmean
from time import sleep
from typing import cast

from aladin_ensemble.backtest import BacktestConfig, SegmentDataset
from aladin_ensemble.baselines import ScalarForecastCase
from aladin_ensemble.evaluate import (
    EvaluationSample,
    HoldoutLock,
    SegmentEvaluation,
    evaluate_segment,
)
from aladin_ensemble.fallback import FitFailure, SegmentSelector
from aladin_ensemble.registry import JsonValue, RequestBudget
from aladin_ensemble.sources.chmi_download import (
    ChmiMonthlyDownloader,
    ChmiMonthlyRequest,
    SelectedStation,
)
from aladin_ensemble.sources.chmi_station import (
    ElementMetadata,
    Station,
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
    WeightFit,
    blend_scalar,
    blend_wind,
    fit_scalar_weights,
    fit_wind_vector_weights,
)
from aladin_ensemble.types import ForecastValue, Observation

FORECAST_VARIABLES = (
    "temperature_2m",
    "wind_speed_10m",
    "wind_direction_10m",
    "precipitation",
)
FIXED_LEADS = tuple(range(1, 8))
Sleeper = Callable[[float], None]


@dataclass(frozen=True, slots=True)
class FittedSegment:
    fallback_model: str
    fit: WeightFit | None
    evaluation: SegmentEvaluation

    @property
    def export_fit(self) -> WeightFit | None:
        return self.fit if self.evaluation.accepted else None


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
    training = _complete_cases(segment.training, segment.eligible_models)
    if not training:
        raise ValueError("segment requires complete training cases")
    fallback_model = select_training_fallback(segment)
    holdout = _complete_cases(segment.holdout, segment.eligible_models)
    if fallback_model == "best_match":
        holdout = tuple(case for case in holdout if case.best_match is not None)
    if not holdout:
        raise ValueError("segment requires complete holdout cases")
    fit_result = fit_scalar_weights(
        segment.eligible_models,
        tuple(_model_row(case, segment.eligible_models) for case in training),
        tuple(case.observation for case in training),
        eligible_models=frozenset(segment.eligible_models),
        minimum_samples=30,
    )
    fit = None if isinstance(fit_result, FitFailure) else fit_result
    samples = tuple(
        EvaluationSample(
            case.forecast_date,
            case.region,
            abs(_blend_or_fallback(case, fit, fallback_model) - case.observation),
            abs(_model_value(case, fallback_model) - case.observation),
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
        fallback_model=fallback_model,
        fold_improvements=_fold_improvements(training, fit, fallback_model),
        missing_fallback_ok=True,
        trained_at=trained_at,
        lock=lock,
        bootstrap_repetitions=bootstrap_repetitions,
    )
    return FittedSegment(fallback_model, fit, evaluation)


def fit_wind_vector_segment(
    speed_segment: SegmentDataset,
    direction_segment: SegmentDataset,
    lock: HoldoutLock,
    *,
    trained_at: datetime,
    bootstrap_repetitions: int = 1_000,
) -> FittedSegment:
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
    training = _paired_wind_cases(
        speed_segment.training,
        direction_segment.training,
        eligible_models,
    )
    holdout = _paired_wind_cases(
        speed_segment.holdout,
        direction_segment.holdout,
        eligible_models,
    )
    fallback_model = _select_wind_fallback(training, eligible_models)
    if fallback_model == "best_match":
        holdout = tuple(
            pair
            for pair in holdout
            if pair[0].best_match is not None and pair[1].best_match is not None
        )
    if not holdout:
        raise ValueError("paired wind segments require complete holdout cases")
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
    samples = tuple(
        EvaluationSample(
            speed.forecast_date,
            speed.region,
            _wind_pair_loss(speed, direction, fit, fallback_model),
            _wind_pair_loss(speed, direction, None, fallback_model),
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
        fallback_model=fallback_model,
        fold_improvements=_wind_fold_improvements(training, fit, fallback_model),
        missing_fallback_ok=True,
        trained_at=trained_at,
        lock=lock,
        bootstrap_repetitions=bootstrap_repetitions,
    )
    return FittedSegment(fallback_model, fit, evaluation)


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
