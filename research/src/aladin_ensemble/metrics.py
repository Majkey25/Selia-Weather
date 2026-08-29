from __future__ import annotations

import random
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from math import fsum, isfinite, sqrt
from statistics import fmean


@dataclass(frozen=True, slots=True)
class BrierDecomposition:
    score: float
    reliability: float
    resolution: float
    uncertainty: float


@dataclass(frozen=True, slots=True)
class ContingencyScores:
    hits: int
    misses: int
    false_alarms: int
    correct_negatives: int
    probability_of_detection: float | None
    false_alarm_ratio: float | None
    critical_success_index: float | None
    frequency_bias: float | None


@dataclass(frozen=True, slots=True)
class ConfidenceInterval:
    estimate: float
    lower: float
    upper: float


def mean_absolute_error(forecast: Sequence[float], observed: Sequence[float]) -> float:
    pairs = _pairs(forecast, observed)
    return fmean(abs(predicted - truth) for predicted, truth in pairs)


def root_mean_square_error(forecast: Sequence[float], observed: Sequence[float]) -> float:
    pairs = _pairs(forecast, observed)
    return sqrt(fmean((predicted - truth) ** 2 for predicted, truth in pairs))


def circular_mean_absolute_error(
    forecast_degrees: Sequence[float], observed_degrees: Sequence[float]
) -> float:
    pairs = _pairs(forecast_degrees, observed_degrees)
    return fmean(abs((predicted - truth + 180.0) % 360.0 - 180.0) for predicted, truth in pairs)


def brier_score(probabilities: Sequence[float], events: Sequence[bool]) -> float:
    pairs = _probability_pairs(probabilities, events)
    return fmean((probability - float(event)) ** 2 for probability, event in pairs)


def brier_decomposition(
    probabilities: Sequence[float], events: Sequence[bool], *, bin_count: int = 10
) -> BrierDecomposition:
    pairs = _probability_pairs(probabilities, events)
    if bin_count <= 0:
        raise ValueError("bin_count must be positive")
    bins: list[list[tuple[float, bool]]] = [[] for _ in range(bin_count)]
    for probability, event in pairs:
        bins[min(int(probability * bin_count), bin_count - 1)].append((probability, event))
    event_rate = fmean(float(event) for _, event in pairs)
    total = len(pairs)
    reliability = 0.0
    resolution = 0.0
    for values in bins:
        if not values:
            continue
        weight = len(values) / total
        forecast_rate = fmean(probability for probability, _ in values)
        observed_rate = fmean(float(event) for _, event in values)
        reliability += weight * (forecast_rate - observed_rate) ** 2
        resolution += weight * (observed_rate - event_rate) ** 2
    return BrierDecomposition(
        brier_score(probabilities, events),
        reliability,
        resolution,
        event_rate * (1.0 - event_rate),
    )


def threshold_scores(
    forecast: Sequence[float], observed: Sequence[float], threshold: float
) -> ContingencyScores:
    if not isfinite(threshold):
        raise ValueError("threshold must be finite")
    pairs = _pairs(forecast, observed)
    hits = misses = false_alarms = correct_negatives = 0
    for predicted, truth in pairs:
        predicted_event = predicted >= threshold
        observed_event = truth >= threshold
        if predicted_event and observed_event:
            hits += 1
        elif observed_event:
            misses += 1
        elif predicted_event:
            false_alarms += 1
        else:
            correct_negatives += 1
    return ContingencyScores(
        hits,
        misses,
        false_alarms,
        correct_negatives,
        _ratio(hits, hits + misses),
        _ratio(false_alarms, hits + false_alarms),
        _ratio(hits, hits + misses + false_alarms),
        _ratio(hits + false_alarms, hits + misses),
    )


def weighted_median(values: Sequence[float], weights: Sequence[float]) -> float:
    pairs = _pairs(values, weights)
    if any(weight < 0 for _, weight in pairs):
        raise ValueError("weight must be non-negative")
    total = fsum(weight for _, weight in pairs)
    if total <= 0:
        raise ValueError("weight sum must be positive")
    cumulative = 0.0
    for value, weight in sorted(pairs):
        cumulative += weight
        if cumulative >= total / 2:
            return value
    raise AssertionError("unreachable weighted median")


def fractions_skill_score(
    forecast: Sequence[Sequence[float]],
    observed: Sequence[Sequence[float]],
    *,
    threshold: float,
    neighbourhood: int,
) -> float:
    forecast_grid = _grid(forecast, "forecast")
    observed_grid = _grid(observed, "observed")
    if len(forecast_grid) != len(observed_grid) or len(forecast_grid[0]) != len(observed_grid[0]):
        raise ValueError("forecast and observed grid shape must match")
    if not isfinite(threshold):
        raise ValueError("threshold must be finite")
    if neighbourhood <= 0:
        raise ValueError("neighbourhood must be positive")
    rows = len(forecast_grid)
    columns = len(forecast_grid[0])
    forecast_fractions: list[float] = []
    observed_fractions: list[float] = []
    for row in range(rows):
        row_start, row_end = _window(row, rows, neighbourhood)
        for column in range(columns):
            column_start, column_end = _window(column, columns, neighbourhood)
            count = (row_end - row_start) * (column_end - column_start)
            forecast_fractions.append(
                sum(
                    forecast_grid[y][x] >= threshold
                    for y in range(row_start, row_end)
                    for x in range(column_start, column_end)
                )
                / count
            )
            observed_fractions.append(
                sum(
                    observed_grid[y][x] >= threshold
                    for y in range(row_start, row_end)
                    for x in range(column_start, column_end)
                )
                / count
            )
    fraction_brier = fmean(
        (predicted - truth) ** 2
        for predicted, truth in zip(forecast_fractions, observed_fractions, strict=True)
    )
    worst = fmean(
        predicted**2 + truth**2
        for predicted, truth in zip(forecast_fractions, observed_fractions, strict=True)
    )
    return 1.0 if worst == 0 else 1.0 - fraction_brier / worst


def block_bootstrap_mean_interval(
    dated_values: Sequence[tuple[date, float]],
    *,
    repetitions: int = 1_000,
    seed: int = 20_260_825,
    confidence: float = 0.95,
) -> ConfidenceInterval:
    if not dated_values:
        raise ValueError("dated values must be non-empty")
    if repetitions <= 0:
        raise ValueError("repetitions must be positive")
    if not 0 < confidence < 1:
        raise ValueError("confidence must be between 0 and 1")
    blocks: dict[date, list[float]] = {}
    for forecast_date, value in dated_values:
        _finite(value)
        blocks.setdefault(forecast_date, []).append(value)
    dates = sorted(blocks)
    generator = random.Random(seed)
    estimates: list[float] = []
    for _ in range(repetitions):
        sample = [value for _ in dates for value in blocks[generator.choice(dates)]]
        estimates.append(fmean(sample))
    estimates.sort()
    tail = (1.0 - confidence) / 2.0
    lower = estimates[min(int(tail * repetitions), repetitions - 1)]
    upper = estimates[min(int((1.0 - tail) * repetitions), repetitions - 1)]
    return ConfidenceInterval(fmean(value for _, value in dated_values), lower, upper)


def _pairs(left: Sequence[float], right: Sequence[float]) -> tuple[tuple[float, float], ...]:
    if not left or not right:
        raise ValueError("inputs must be non-empty")
    if len(left) != len(right):
        raise ValueError("inputs must have the same length")
    pairs = tuple(zip(left, right, strict=True))
    for first, second in pairs:
        _finite(first)
        _finite(second)
    return pairs


def _probability_pairs(
    probabilities: Sequence[float], events: Sequence[bool]
) -> tuple[tuple[float, bool], ...]:
    if not probabilities or not events:
        raise ValueError("inputs must be non-empty")
    if len(probabilities) != len(events):
        raise ValueError("inputs must have the same length")
    pairs: list[tuple[float, bool]] = []
    for probability, event in zip(probabilities, events, strict=True):
        _finite(probability)
        if not 0 <= probability <= 1:
            raise ValueError("probability must be from 0 through 1")
        pairs.append((probability, _boolean(event)))
    return tuple(pairs)


def _grid(values: Sequence[Sequence[float]], name: str) -> tuple[tuple[float, ...], ...]:
    if not values or not values[0]:
        raise ValueError(f"{name} grid must be non-empty")
    width = len(values[0])
    rows = tuple(tuple(row) for row in values)
    if any(len(row) != width for row in rows):
        raise ValueError(f"{name} grid shape is invalid")
    for row in rows:
        for value in row:
            _finite(value)
    return rows


def _window(index: int, length: int, neighbourhood: int) -> tuple[int, int]:
    width = min(neighbourhood, length)
    start = min(max(0, index - (width - 1) // 2), length - width)
    return start, start + width


def _ratio(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def _finite(value: object) -> None:
    if isinstance(value, bool) or not isinstance(value, int | float) or not isfinite(value):
        raise ValueError("values must be finite")


def _boolean(value: object) -> bool:
    if not isinstance(value, bool):
        raise ValueError("event must be boolean")
    return value
