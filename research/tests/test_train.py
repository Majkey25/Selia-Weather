from __future__ import annotations

from math import isfinite

import pytest

from aladin_ensemble.fallback import ExclusionReason, FitFailure
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


def test_scalar_fit_is_constrained_deterministic_and_eligible_only() -> None:
    predictors = ((1.0, 4.0), (2.0, 0.0), (3.0, 5.0), (4.0, 0.0))
    observations = (1.0, 2.0, 3.0, 4.0)

    first = fit_scalar_weights(
        ("accurate", "bad"),
        predictors,
        observations,
        eligible_models=frozenset({"accurate", "bad"}),
        regularization=0.0,
        minimum_samples=3,
    )
    second = fit_scalar_weights(
        ("accurate", "bad"),
        predictors,
        observations,
        eligible_models=frozenset({"accurate", "bad"}),
        regularization=0.0,
        minimum_samples=3,
    )

    assert isinstance(first, WeightFit)
    assert first == second
    assert set(first.weights) == {"accurate", "bad"}
    assert all(isfinite(value) and value >= 0 for value in first.weights.values())
    assert sum(first.weights.values()) == pytest.approx(1.0)
    assert first.weights["accurate"] > 0.99
    assert blend_scalar(first, {"accurate": 8.0, "bad": 80.0}) == pytest.approx(8.0)

    eligible_only = fit_scalar_weights(
        ("accurate", "bad"),
        predictors,
        observations,
        eligible_models=frozenset({"accurate"}),
        minimum_samples=3,
    )
    assert isinstance(eligible_only, WeightFit)
    assert eligible_only.weights == {"accurate": 1.0}


def test_scalar_fit_reports_insufficient_samples_and_rejects_bad_boundary_data() -> None:
    failure = fit_scalar_weights(
        ("a",),
        ((1.0,),),
        (1.0,),
        eligible_models=frozenset({"a"}),
        minimum_samples=2,
    )
    assert failure == FitFailure(ExclusionReason.INSUFFICIENT_SAMPLES, "1 < 2")

    with pytest.raises(ValueError, match="eligible"):
        fit_scalar_weights(
            ("a",),
            ((1.0,), (2.0,)),
            (1.0, 2.0),
            eligible_models=frozenset({"unknown"}),
        )
    with pytest.raises(ValueError, match="finite"):
        fit_scalar_weights(
            ("a",),
            ((float("nan"),), (2.0,)),
            (1.0, 2.0),
            eligible_models=frozenset({"a"}),
        )


def test_wind_fit_uses_components_instead_of_averaging_degrees() -> None:
    fit = fit_wind_vector_weights(
        ("accurate", "opposite"),
        speed_rows=((10.0, 10.0), (10.0, 10.0), (10.0, 10.0), (10.0, 10.0)),
        direction_rows=((350.0, 170.0), (10.0, 190.0), (0.0, 180.0), (5.0, 185.0)),
        observed_speeds=(10.0, 10.0, 10.0, 10.0),
        observed_directions=(350.0, 10.0, 0.0, 5.0),
        eligible_models=frozenset({"accurate", "opposite"}),
        regularization=0.0,
        minimum_samples=3,
    )

    assert isinstance(fit, WeightFit)
    assert fit.weights["accurate"] > 0.99
    speed, direction = blend_wind(fit, {"accurate": (10.0, 350.0), "opposite": (10.0, 170.0)})
    assert speed == pytest.approx(10.0)
    assert direction == pytest.approx(350.0)


def test_occurrence_calibration_selects_regularization_inside_training_folds() -> None:
    rows = (
        (0.05, 0.95),
        (0.10, 0.90),
        (0.80, 0.20),
        (0.90, 0.10),
    ) * 3
    events = (False, False, True, True) * 3
    folds = (0, 1, 0, 1) * 3

    first = fit_occurrence_calibration(
        ("useful", "wrong"),
        rows,
        events,
        fold_ids=folds,
        regularization_candidates=(0.01, 1.0),
        minimum_samples=8,
    )
    second = fit_occurrence_calibration(
        ("useful", "wrong"),
        rows,
        events,
        fold_ids=folds,
        regularization_candidates=(0.01, 1.0),
        minimum_samples=8,
    )

    assert isinstance(first, OccurrenceCalibration)
    assert first == second
    assert first.coefficients["useful"] > first.coefficients["wrong"]
    assert first.regularization in {0.01, 1.0}
    assert predict_occurrence(first, {"useful": 0.9, "wrong": 0.1}) > 0.5
    assert 0 <= predict_occurrence(first, {"useful": 0.1, "wrong": 0.9}) <= 1


def test_positive_amount_fit_ignores_zero_events_and_blends_weighted_median() -> None:
    fit = fit_positive_amount_weights(
        ("accurate", "wet"),
        ((0.0, 0.0), (1.0, 5.0), (2.0, 8.0), (3.0, 9.0)),
        (0.0, 1.0, 2.0, 3.0),
        eligible_models=frozenset({"accurate", "wet"}),
        regularization=0.0,
        minimum_positive_samples=3,
    )

    assert isinstance(fit, WeightFit)
    assert fit.sample_count == 3
    assert fit.weights["accurate"] > 0.99
    assert blend_positive_amount(fit, {"accurate": 2.0, "wet": 10.0}) == 2.0

    no_rain = fit_positive_amount_weights(
        ("accurate", "wet"),
        ((0.0, 0.0), (0.0, 0.0)),
        (0.0, 0.0),
        eligible_models=frozenset({"accurate", "wet"}),
        minimum_positive_samples=1,
    )
    assert no_rain == FitFailure(ExclusionReason.INSUFFICIENT_SAMPLES, "0 < 1")
