from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from math import atan2, cos, degrees, exp, hypot, isfinite, radians, sin
from types import MappingProxyType

import numpy as np
from numpy.typing import NDArray
from scipy.optimize import OptimizeResult, minimize

from aladin_ensemble.fallback import ExclusionReason, FitFailure
from aladin_ensemble.metrics import weighted_median


@dataclass(frozen=True, slots=True)
class WeightFit:
    weights: Mapping[str, float]
    sample_count: int
    objective: float

    def __post_init__(self) -> None:
        copied = dict(self.weights)
        if not copied or any(not model_id for model_id in copied):
            raise ValueError("weights must be non-empty and named")
        if any(not isfinite(value) or value < 0 for value in copied.values()):
            raise ValueError("weights must be finite and non-negative")
        if abs(sum(copied.values()) - 1.0) > 1e-8:
            raise ValueError("weights must sum to one")
        if self.sample_count <= 0:
            raise ValueError("sample_count must be positive")
        if not isfinite(self.objective) or self.objective < 0:
            raise ValueError("objective must be finite and non-negative")
        object.__setattr__(self, "weights", MappingProxyType(copied))


@dataclass(frozen=True, slots=True)
class OccurrenceCalibration:
    intercept: float
    coefficients: Mapping[str, float]
    regularization: float
    sample_count: int

    def __post_init__(self) -> None:
        copied = dict(self.coefficients)
        if not copied or any(not model_id for model_id in copied):
            raise ValueError("coefficients must be non-empty and named")
        if not isfinite(self.intercept) or any(
            not isfinite(value) or value < 0 for value in copied.values()
        ):
            raise ValueError("occurrence coefficients must be finite and non-negative")
        if not isfinite(self.regularization) or self.regularization < 0:
            raise ValueError("regularization must be finite and non-negative")
        if self.sample_count <= 0:
            raise ValueError("sample_count must be positive")
        object.__setattr__(self, "coefficients", MappingProxyType(copied))


def fit_scalar_weights(
    model_ids: Sequence[str],
    predictor_rows: Sequence[Sequence[float]],
    observations: Sequence[float],
    *,
    eligible_models: frozenset[str],
    regularization: float = 0.01,
    minimum_samples: int = 30,
) -> WeightFit | FitFailure:
    predictors, targets = _matrix(model_ids, predictor_rows, observations)
    eligible_ids, indices = _eligible(model_ids, eligible_models)
    return _fit_constrained(
        eligible_ids,
        predictors[:, indices],
        targets,
        regularization=regularization,
        minimum_samples=minimum_samples,
        reported_sample_count=len(targets),
    )


def fit_wind_vector_weights(
    model_ids: Sequence[str],
    *,
    speed_rows: Sequence[Sequence[float]],
    direction_rows: Sequence[Sequence[float]],
    observed_speeds: Sequence[float],
    observed_directions: Sequence[float],
    eligible_models: frozenset[str],
    regularization: float = 0.01,
    minimum_samples: int = 30,
) -> WeightFit | FitFailure:
    speeds, observed_speed = _matrix(model_ids, speed_rows, observed_speeds)
    directions, observed_direction = _matrix(model_ids, direction_rows, observed_directions)
    if speeds.shape != directions.shape:
        raise ValueError("wind speed and direction matrix shape must match")
    if np.any(speeds < 0) or np.any(observed_speed < 0):
        raise ValueError("wind speed must be non-negative")
    if np.any((directions < 0) | (directions > 360)) or np.any(
        (observed_direction < 0) | (observed_direction > 360)
    ):
        raise ValueError("wind direction must be from 0 through 360")
    if len(observed_speed) < minimum_samples:
        return FitFailure(
            ExclusionReason.INSUFFICIENT_SAMPLES,
            f"{len(observed_speed)} < {minimum_samples}",
        )
    eligible_ids, indices = _eligible(model_ids, eligible_models)
    direction_radians = np.radians(directions[:, indices])
    observed_radians = np.radians(observed_direction)
    east = speeds[:, indices] * np.sin(direction_radians)
    north = speeds[:, indices] * np.cos(direction_radians)
    observed_east = observed_speed * np.sin(observed_radians)
    observed_north = observed_speed * np.cos(observed_radians)
    fit = _fit_constrained(
        eligible_ids,
        np.vstack((east, north)),
        np.concatenate((observed_east, observed_north)),
        regularization=regularization,
        minimum_samples=minimum_samples * 2,
        reported_sample_count=len(observed_speed),
    )
    return fit


def fit_positive_amount_weights(
    model_ids: Sequence[str],
    predictor_rows: Sequence[Sequence[float]],
    observations: Sequence[float],
    *,
    eligible_models: frozenset[str],
    regularization: float = 0.01,
    minimum_positive_samples: int = 30,
) -> WeightFit | FitFailure:
    predictors, targets = _matrix(model_ids, predictor_rows, observations)
    if np.any(predictors < 0) or np.any(targets < 0):
        raise ValueError("precipitation amount must be non-negative")
    if minimum_positive_samples <= 0:
        raise ValueError("minimum_positive_samples must be positive")
    _eligible(model_ids, eligible_models)
    positive = targets > 0
    positive_count = int(np.count_nonzero(positive))
    if positive_count < minimum_positive_samples:
        return FitFailure(
            ExclusionReason.INSUFFICIENT_SAMPLES,
            f"{positive_count} < {minimum_positive_samples}",
        )
    return fit_scalar_weights(
        model_ids,
        predictors[positive].tolist(),
        targets[positive].tolist(),
        eligible_models=eligible_models,
        regularization=regularization,
        minimum_samples=minimum_positive_samples,
    )


def fit_occurrence_calibration(
    model_ids: Sequence[str],
    probability_rows: Sequence[Sequence[float]],
    events: Sequence[bool],
    *,
    fold_ids: Sequence[int],
    regularization_candidates: Sequence[float],
    minimum_samples: int = 60,
) -> OccurrenceCalibration | FitFailure:
    probabilities = _predictor_matrix(model_ids, probability_rows)
    if len(events) != len(probabilities) or len(fold_ids) != len(probabilities):
        raise ValueError("probabilities, events, and fold_ids must have the same length")
    if any(not isinstance(event, bool) for event in events):
        raise ValueError("events must be boolean")
    if np.any((probabilities < 0) | (probabilities > 1)):
        raise ValueError("probabilities must be from 0 through 1")
    if len(events) < minimum_samples:
        return FitFailure(
            ExclusionReason.INSUFFICIENT_SAMPLES,
            f"{len(events)} < {minimum_samples}",
        )
    candidates = tuple(sorted(set(regularization_candidates)))
    if not candidates or any(not isfinite(value) or value < 0 for value in candidates):
        raise ValueError("regularization candidates must be finite and non-negative")
    unique_folds = sorted(set(fold_ids))
    if len(unique_folds) < 2:
        raise ValueError("at least two training folds are required")
    targets = np.asarray(events, dtype=np.float64)
    scores: list[tuple[float, float]] = []
    for regularization in candidates:
        losses: list[float] = []
        for fold in unique_folds:
            training = np.asarray([value != fold for value in fold_ids], dtype=np.bool_)
            validation = ~training
            result = _fit_logistic(probabilities[training], targets[training], regularization)
            if not result.success:
                return FitFailure(ExclusionReason.UNSTABLE_FIT, str(result.message))
            losses.append(_log_loss(probabilities[validation], targets[validation], result.x))
        scores.append((sum(losses) / len(losses), regularization))
    selected = min(scores)[1]
    result = _fit_logistic(probabilities, targets, selected)
    if not result.success:
        return FitFailure(ExclusionReason.UNSTABLE_FIT, str(result.message))
    coefficients = np.asarray(result.x, dtype=np.float64)
    return OccurrenceCalibration(
        float(coefficients[0]),
        {model_id: float(coefficients[index + 1]) for index, model_id in enumerate(model_ids)},
        selected,
        len(events),
    )


def blend_scalar(fit: WeightFit, values: Mapping[str, float]) -> float:
    return sum(weight * _model_value(values, model_id) for model_id, weight in fit.weights.items())


def blend_wind(
    fit: WeightFit, values: Mapping[str, tuple[float, float]]
) -> tuple[float, float]:
    east = 0.0
    north = 0.0
    for model_id, weight in fit.weights.items():
        speed, direction = values.get(model_id, (float("nan"), float("nan")))
        if not isfinite(speed) or speed < 0 or not isfinite(direction) or not 0 <= direction <= 360:
            raise ValueError(f"invalid wind value for {model_id}")
        angle = radians(direction)
        east += weight * speed * sin(angle)
        north += weight * speed * cos(angle)
    return hypot(east, north), degrees(atan2(east, north)) % 360.0


def blend_positive_amount(fit: WeightFit, values: Mapping[str, float]) -> float:
    model_values = tuple(_model_value(values, model_id) for model_id in fit.weights)
    if any(value < 0 for value in model_values):
        raise ValueError("precipitation amount must be non-negative")
    return weighted_median(model_values, tuple(fit.weights.values()))


def predict_occurrence(calibration: OccurrenceCalibration, values: Mapping[str, float]) -> float:
    score = calibration.intercept + sum(
        coefficient * _probability(values, model_id)
        for model_id, coefficient in calibration.coefficients.items()
    )
    return _sigmoid(score)


def _fit_constrained(
    model_ids: Sequence[str],
    predictors: NDArray[np.float64],
    targets: NDArray[np.float64],
    *,
    regularization: float,
    minimum_samples: int,
    reported_sample_count: int,
) -> WeightFit | FitFailure:
    if not isfinite(regularization) or regularization < 0:
        raise ValueError("regularization must be finite and non-negative")
    if minimum_samples <= 0:
        raise ValueError("minimum_samples must be positive")
    if len(targets) < minimum_samples:
        return FitFailure(
            ExclusionReason.INSUFFICIENT_SAMPLES,
            f"{len(targets)} < {minimum_samples}",
        )
    if len(model_ids) == 1:
        residual = predictors[:, 0] - targets
        return WeightFit(
            {model_ids[0]: 1.0},
            reported_sample_count,
            float(np.mean(residual**2)),
        )
    initial = np.full(len(model_ids), 1.0 / len(model_ids), dtype=np.float64)

    def objective(weights: NDArray[np.float64]) -> float:
        residual = predictors @ weights - targets
        penalty = weights - initial
        return float(np.mean(residual**2) + regularization * np.dot(penalty, penalty))

    def sum_constraint(weights: NDArray[np.float64]) -> float:
        return float(np.sum(weights) - 1.0)

    result: OptimizeResult = minimize(
        objective,
        initial,
        method="SLSQP",
        bounds=[(0.0, 1.0)] * len(model_ids),
        constraints={"type": "eq", "fun": sum_constraint},
        options={"ftol": 1e-12, "maxiter": 1_000},
    )
    if not result.success:
        return FitFailure(ExclusionReason.UNSTABLE_FIT, str(result.message))
    weights = np.clip(np.asarray(result.x, dtype=np.float64), 0.0, 1.0)
    weights /= np.sum(weights)
    return WeightFit(
        {model_id: float(weights[index]) for index, model_id in enumerate(model_ids)},
        reported_sample_count,
        objective(weights),
    )


def _fit_logistic(
    predictors: NDArray[np.float64], targets: NDArray[np.float64], regularization: float
) -> OptimizeResult:
    event_rate = float(np.clip(np.mean(targets), 1e-6, 1 - 1e-6))
    initial = np.zeros(predictors.shape[1] + 1, dtype=np.float64)
    initial[0] = np.log(event_rate / (1 - event_rate))

    def objective(coefficients: NDArray[np.float64]) -> float:
        return _log_loss(predictors, targets, coefficients) + regularization * float(
            np.dot(coefficients[1:], coefficients[1:])
        )

    return minimize(
        objective,
        initial,
        method="L-BFGS-B",
        bounds=[(None, None), *([(0.0, None)] * predictors.shape[1])],
        options={"ftol": 1e-12, "maxiter": 1_000},
    )


def _log_loss(
    predictors: NDArray[np.float64],
    targets: NDArray[np.float64],
    coefficients: NDArray[np.float64],
) -> float:
    scores = coefficients[0] + predictors @ coefficients[1:]
    probabilities = np.clip(1.0 / (1.0 + np.exp(-scores)), 1e-12, 1 - 1e-12)
    return float(
        -np.mean(
            targets * np.log(probabilities) + (1 - targets) * np.log(1 - probabilities)
        )
    )


def _matrix(
    model_ids: Sequence[str],
    rows: Sequence[Sequence[float]],
    targets: Sequence[float],
) -> tuple[NDArray[np.float64], NDArray[np.float64]]:
    predictors = _predictor_matrix(model_ids, rows)
    if len(targets) != len(predictors):
        raise ValueError("predictors and observations must have the same length")
    observations = np.asarray(targets, dtype=np.float64)
    if not np.all(np.isfinite(observations)):
        raise ValueError("observations must be finite")
    return predictors, observations


def _predictor_matrix(
    model_ids: Sequence[str], rows: Sequence[Sequence[float]]
) -> NDArray[np.float64]:
    if (
        not model_ids
        or len(set(model_ids)) != len(model_ids)
        or any(not value for value in model_ids)
    ):
        raise ValueError("model_ids must be non-empty, named, and unique")
    if not rows or any(len(row) != len(model_ids) for row in rows):
        raise ValueError("predictor matrix shape is invalid")
    predictors = np.asarray(rows, dtype=np.float64)
    if not np.all(np.isfinite(predictors)):
        raise ValueError("predictors must be finite")
    return predictors


def _eligible(
    model_ids: Sequence[str], eligible_models: frozenset[str]
) -> tuple[tuple[str, ...], tuple[int, ...]]:
    unknown = eligible_models.difference(model_ids)
    if unknown:
        raise ValueError(f"eligible models are unknown: {sorted(unknown)}")
    selected = tuple(
        (model_id, index) for index, model_id in enumerate(model_ids) if model_id in eligible_models
    )
    if not selected:
        raise ValueError("eligible model set is empty")
    return tuple(value[0] for value in selected), tuple(value[1] for value in selected)


def _model_value(values: Mapping[str, float], model_id: str) -> float:
    value = values.get(model_id)
    if value is None or not isfinite(value):
        raise ValueError(f"missing or invalid value for {model_id}")
    return value


def _probability(values: Mapping[str, float], model_id: str) -> float:
    value = _model_value(values, model_id)
    if not 0 <= value <= 1:
        raise ValueError(f"invalid probability for {model_id}")
    return value


def _sigmoid(value: float) -> float:
    if value >= 0:
        return 1.0 / (1.0 + exp(-value))
    numerator = exp(value)
    return numerator / (1.0 + numerator)
