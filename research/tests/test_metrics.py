from __future__ import annotations

from datetime import date
from math import isclose, sqrt

import pytest

from aladin_ensemble.metrics import (
    block_bootstrap_mean_interval,
    brier_decomposition,
    brier_score,
    circular_mean_absolute_error,
    fractions_skill_score,
    mean_absolute_error,
    root_mean_square_error,
    threshold_scores,
    weighted_median,
)


def test_scalar_and_circular_errors_match_hand_calculation() -> None:
    assert mean_absolute_error((1.0, 4.0), (2.0, 2.0)) == 1.5
    assert isclose(root_mean_square_error((1.0, 4.0), (2.0, 2.0)), sqrt(2.5))
    assert circular_mean_absolute_error((350.0, 10.0), (10.0, 350.0)) == 20.0


def test_brier_score_and_decomposition_recompose_exactly() -> None:
    probabilities = (0.0, 0.0, 1.0, 1.0)
    events = (False, True, True, True)

    decomposition = brier_decomposition(probabilities, events, bin_count=10)

    assert brier_score(probabilities, events) == 0.25
    assert decomposition.score == 0.25
    assert decomposition.reliability == 0.125
    assert decomposition.resolution == 0.0625
    assert decomposition.uncertainty == 0.1875
    assert decomposition.reliability - decomposition.resolution + decomposition.uncertainty == 0.25


def test_threshold_scores_and_weighted_median_match_hand_calculation() -> None:
    scores = threshold_scores((0.0, 2.0, 2.0, 0.0), (0.0, 2.0, 0.0, 2.0), 1.0)

    assert (scores.hits, scores.misses, scores.false_alarms, scores.correct_negatives) == (
        1,
        1,
        1,
        1,
    )
    assert scores.probability_of_detection == 0.5
    assert scores.false_alarm_ratio == 0.5
    assert scores.critical_success_index is not None
    assert isclose(scores.critical_success_index, 1 / 3)
    assert scores.frequency_bias == 1.0
    assert weighted_median((1.0, 5.0, 9.0), (0.2, 0.6, 0.2)) == 5.0


def test_fractions_skill_score_rewards_correct_neighbourhood() -> None:
    forecast = ((1.0, 0.0), (0.0, 0.0))
    observed = ((0.0, 1.0), (0.0, 0.0))

    assert fractions_skill_score(forecast, observed, threshold=0.5, neighbourhood=1) == 0.0
    assert fractions_skill_score(forecast, observed, threshold=0.5, neighbourhood=2) == 1.0


def test_date_block_bootstrap_is_deterministic() -> None:
    values = (
        (date(2026, 8, 1), 1.0),
        (date(2026, 8, 1), 3.0),
        (date(2026, 8, 2), 5.0),
    )

    first = block_bootstrap_mean_interval(values, repetitions=200, seed=42)
    second = block_bootstrap_mean_interval(values, repetitions=200, seed=42)

    assert first == second
    assert first.lower <= first.estimate <= first.upper
    assert first.estimate == 3.0


def test_metrics_reject_empty_mismatched_nonfinite_and_invalid_inputs() -> None:
    with pytest.raises(ValueError, match="non-empty"):
        mean_absolute_error((), ())
    with pytest.raises(ValueError, match="same length"):
        root_mean_square_error((1.0,), (1.0, 2.0))
    with pytest.raises(ValueError, match="finite"):
        brier_score((float("nan"),), (True,))
    with pytest.raises(ValueError, match="probability"):
        brier_score((1.1,), (True,))
    with pytest.raises(ValueError, match="weight"):
        weighted_median((1.0,), (-1.0,))
    with pytest.raises(ValueError, match="shape"):
        fractions_skill_score(((1.0,),), ((1.0, 0.0),), threshold=0.5, neighbourhood=1)
