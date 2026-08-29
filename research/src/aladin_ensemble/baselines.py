from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from math import isfinite
from statistics import fmean, median
from types import MappingProxyType

from aladin_ensemble.metrics import (
    ConfidenceInterval,
    block_bootstrap_mean_interval,
    mean_absolute_error,
    root_mean_square_error,
)


@dataclass(frozen=True, order=True, slots=True)
class BaselineGroup:
    variable: str
    lead_hours: int
    region: str
    elevation_band: str
    season: str


@dataclass(frozen=True, slots=True)
class ScalarForecastCase:
    forecast_date: date
    variable: str
    lead_hours: int
    region: str
    elevation_band: str
    season: str
    observation: float
    model_values: Mapping[str, float | None]
    best_match: float | None

    def __post_init__(self) -> None:
        if not all((self.variable, self.region, self.elevation_band, self.season)):
            raise ValueError("case grouping fields are required")
        if self.lead_hours < 0:
            raise ValueError("lead_hours must be non-negative")
        _finite(self.observation, "observation")
        if self.best_match is not None:
            _finite(self.best_match, "best_match")
        copied = dict(self.model_values)
        if not copied or any(not model_id for model_id in copied):
            raise ValueError("model_values must be non-empty and named")
        for model_id, value in copied.items():
            if value is not None:
                _finite(value, model_id)
        object.__setattr__(self, "model_values", MappingProxyType(copied))

    @property
    def group(self) -> BaselineGroup:
        return BaselineGroup(
            self.variable,
            self.lead_hours,
            self.region,
            self.elevation_band,
            self.season,
        )

    def values_for(self, model_ids: Sequence[str]) -> tuple[tuple[float, ...], float] | None:
        if not model_ids or len(set(model_ids)) != len(model_ids):
            raise ValueError("model_ids must be non-empty and unique")
        for model_id in model_ids:
            if model_id not in self.model_values:
                raise ValueError(f"missing model value for {model_id}")
        values = tuple(self.model_values[model_id] for model_id in model_ids)
        if self.best_match is None or any(value is None for value in values):
            return None
        return tuple(value for value in values if value is not None), self.best_match


@dataclass(frozen=True, slots=True)
class ScalarBaselineScore:
    group: BaselineGroup
    baseline: str
    sample_count: int
    mae: float
    rmse: float
    mae_interval: ConfidenceInterval


def evaluate_scalar_baselines(
    cases: Sequence[ScalarForecastCase],
    model_ids: Sequence[str],
    *,
    bootstrap_repetitions: int = 1_000,
    seed: int = 20_260_825,
) -> tuple[ScalarBaselineScore, ...]:
    if not cases:
        raise ValueError("cases must be non-empty")
    if not model_ids or len(set(model_ids)) != len(model_ids):
        raise ValueError("model_ids must be non-empty and unique")
    groups: dict[BaselineGroup, list[ScalarForecastCase]] = {}
    for case in cases:
        groups.setdefault(case.group, []).append(case)
    rows: list[ScalarBaselineScore] = []
    for group in sorted(groups):
        complete: list[tuple[ScalarForecastCase, tuple[float, ...], float]] = []
        for case in groups[group]:
            values = case.values_for(model_ids)
            if values is not None:
                complete.append((case, values[0], values[1]))
        if not complete:
            raise ValueError(f"group {group} has no common complete-case samples")
        observations = tuple(case.observation for case, _, _ in complete)
        predictions: dict[str, tuple[float, ...]] = {
            model_id: tuple(values[index] for _, values, _ in complete)
            for index, model_id in enumerate(model_ids)
        }
        predictions["best_match"] = tuple(best_match for _, _, best_match in complete)
        predictions["mean"] = tuple(fmean(values) for _, values, _ in complete)
        predictions["median"] = tuple(median(values) for _, values, _ in complete)
        for baseline in (*model_ids, "best_match", "mean", "median"):
            forecast = predictions[baseline]
            absolute_errors = tuple(
                (case.forecast_date, abs(predicted - case.observation))
                for (case, _, _), predicted in zip(complete, forecast, strict=True)
            )
            rows.append(
                ScalarBaselineScore(
                    group,
                    baseline,
                    len(complete),
                    mean_absolute_error(forecast, observations),
                    root_mean_square_error(forecast, observations),
                    block_bootstrap_mean_interval(
                        absolute_errors,
                        repetitions=bootstrap_repetitions,
                        seed=seed,
                    ),
                )
            )
    return tuple(rows)


def _finite(value: object, field_name: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int | float) or not isfinite(value):
        raise ValueError(f"{field_name} must be finite")
