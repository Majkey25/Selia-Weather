"""Research-only local/regional shrinkage selected with forward training folds."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from statistics import fmean

from .align import DateRange
from .baselines import ScalarForecastCase
from .train import WeightFit, blend_scalar, fit_scalar_weights


@dataclass(frozen=True, slots=True)
class LocalShrinkage:
    local_fraction: float
    fit: WeightFit
    validation_mae: Mapping[float, float]
    validation_dates: int


def fit_local_shrinkage(
    training: Mapping[str, Sequence[ScalarForecastCase]],
    model_ids: tuple[str, ...],
    validation_windows: tuple[DateRange, ...],
) -> dict[str, LocalShrinkage]:
    """Receive training cases only; fit every validation prefix strictly before its window."""
    pooled = tuple(case for cases in training.values() for case in cases)
    if not training or any(not location or not cases for location, cases in training.items()):
        raise ValueError("named locations with training cases are required")
    if len({(case.variable, case.lead_hours, case.region) for case in pooled}) != 1:
        raise ValueError("shrinkage requires one variable, lead and region")
    if any(
        len({case.forecast_date for case in cases}) != len(cases) for cases in training.values()
    ):
        raise ValueError("shrinkage requires one case per location/date")
    if len(validation_windows) < 2 or any(
        previous.end >= current.start
        for previous, current in zip(validation_windows, validation_windows[1:], strict=False)
    ):
        raise ValueError("forward validation windows must be ordered and non-overlapping")
    losses: dict[str, dict[float, list[float]]] = {
        location: {fraction: [] for fraction in (0.0, 0.5, 1.0)} for location in training
    }
    for window in validation_windows:
        regional = _fit(
            tuple(case for case in pooled if case.forecast_date < window.start), model_ids
        )
        for location, cases in training.items():
            local = _fit(
                tuple(case for case in cases if case.forecast_date < window.start), model_ids
            )
            validation = tuple(case for case in cases if window.contains(case.forecast_date))
            if not validation:
                raise ValueError("each location requires cases in every validation window")
            for case in validation:
                values = _values(case, model_ids)
                regional_value, local_value = (
                    blend_scalar(regional, values),
                    blend_scalar(local, values),
                )
                for fraction in losses[location]:
                    prediction = (1 - fraction) * regional_value + fraction * local_value
                    losses[location][fraction].append(abs(prediction - case.observation))
    regional = _fit(pooled, model_ids)
    results: dict[str, LocalShrinkage] = {}
    for location, cases in training.items():
        if len(losses[location][0.0]) < 30:
            raise ValueError("each location requires at least 30 forward validation dates")
        scores = {fraction: fmean(errors) for fraction, errors in losses[location].items()}
        fraction = min(scores, key=lambda value: (scores[value], value))
        local = _fit(cases, model_ids)
        weights = {
            model: (1 - fraction) * regional.weights[model] + fraction * local.weights[model]
            for model in model_ids
        }
        mse = fmean(
            (
                sum(weights[model] * value for model, value in _values(case, model_ids).items())
                - case.observation
            )
            ** 2
            for case in cases
        )
        results[location] = LocalShrinkage(
            fraction, WeightFit(weights, len(cases), mse), scores, len(losses[location][0.0])
        )
    return results


def _values(case: ScalarForecastCase, model_ids: tuple[str, ...]) -> dict[str, float]:
    values = {model: case.model_values.get(model) for model in model_ids}
    if any(value is None for value in values.values()):
        raise ValueError("shrinkage comparison requires complete matched predictors")
    return {model: value for model, value in values.items() if value is not None}


def _fit(cases: Sequence[ScalarForecastCase], model_ids: tuple[str, ...]) -> WeightFit:
    if len({case.forecast_date for case in cases}) < 90:
        raise ValueError("each fit requires at least 90 earlier observed dates")
    fit = fit_scalar_weights(
        model_ids,
        tuple(tuple(_values(case, model_ids).values()) for case in cases),
        tuple(case.observation for case in cases),
        eligible_models=frozenset(model_ids),
    )
    if not isinstance(fit, WeightFit):
        raise ValueError(f"shrinkage prefix fit failed: {fit}")
    return fit
