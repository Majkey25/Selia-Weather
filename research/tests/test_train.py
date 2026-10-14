from __future__ import annotations

from math import isclose, isfinite
from types import SimpleNamespace
from unittest.mock import Mock

import numpy as np
import pytest

from aladin_ensemble.fallback import ExclusionReason, FitFailure
from aladin_ensemble.train import (
    OccurrenceCalibration,
    WeightFit,
    blend_positive_amount,
    blend_scalar,
    blend_scalar_available,
    blend_wind,
    blend_wind_available,
    fit_empirical_pool_weights,
    fit_occurrence_calibration,
    fit_positive_amount_weights,
    fit_scalar_weights,
    fit_wind_vector_weights,
    predict_occurrence,
)


def test_empirical_pool_fit_learns_a_distribution_not_a_blended_point() -> None:
    fit = fit_empirical_pool_weights(
        ("dry", "wet", "bad"),
        ((0.0, 2.0, 100.0),) * 4,
        (0.0, 0.0, 0.0, 2.0),
        eligible_models=frozenset({"dry", "wet", "bad"}),
        regularization=0.0,
        minimum_samples=4,
    )
    assert isinstance(fit, WeightFit)
    assert isclose(fit.weights["dry"], 0.75, abs_tol=1e-5)
    assert isclose(fit.weights["wet"], 0.25, abs_tol=1e-5)
    assert fit.weights["bad"] == 0.0
    assert isclose(fit.objective, 0.375, abs_tol=1e-8)


def test_empirical_pool_fit_keeps_eligibility_and_insufficient_sample_contract() -> None:
    result = fit_empirical_pool_weights(
        ("a", "b"),
        ((0.0, 5.0),) * 3,
        (1.0,) * 3,
        eligible_models=frozenset({"a"}),
        minimum_samples=4,
    )
    assert result == FitFailure(ExclusionReason.INSUFFICIENT_SAMPLES, "3 < 4")
    fit = fit_empirical_pool_weights(
        ("a", "b"),
        ((0.0, 5.0),) * 3,
        (1.0,) * 3,
        eligible_models=frozenset({"a"}),
        minimum_samples=3,
    )
    assert isinstance(fit, WeightFit)
    assert fit.weights == {"a": 1.0}
    assert fit.objective == 1.0
    with pytest.raises(ValueError, match="regularization"):
        fit_empirical_pool_weights(
            ("a",),
            ((0.0,),),
            (1.0,),
            eligible_models=frozenset({"a"}),
            regularization=-1,
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
    assert isclose(sum(first.weights.values()), 1.0)
    assert first.weights["accurate"] > 0.99
    assert isclose(blend_scalar(first, {"accurate": 8.0, "bad": 80.0}), 8.0)

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


@pytest.mark.parametrize(("third_weight", "has_third_source"), (
    (2.78e-17, False), (1e-12, False), (1e-9, True),
))
def test_solver_dust_cannot_supply_a_third_source(
    third_weight: float, has_third_source: bool, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr("aladin_ensemble.train._minimize", Mock(return_value=SimpleNamespace(
        success=True, message="",
        x=np.array([0.75 - third_weight, 0.25, third_weight, 4.86e-17]),
    )))
    fit = fit_positive_amount_weights(
        ("a", "b", "c", "d"),
        ((1.0, 5.0, 9.0, 12.0),) * 3,
        (2.0,) * 3,
        eligible_models=frozenset({"a", "b", "c", "d"}),
        regularization=0.0,
        minimum_positive_samples=3,
    )

    assert isinstance(fit, WeightFit)
    assert fit.weights["d"] == 0.0
    assert isclose(sum(fit.weights.values()), 1.0)
    assert isclose(fit.weights["a"], 0.75 - third_weight)
    assert isclose(fit.weights["b"], 0.25)
    prediction = blend_scalar_available(fit, {"a": 1.0, "b": 5.0, "c": 9.0, "d": 12.0}, 3)
    if has_third_source:
        assert isclose(fit.weights["c"], third_weight)
        assert prediction is not None
    else:
        assert fit.weights["c"] == 0.0
        assert prediction is None


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
    assert isclose(speed, 10.0)
    assert isclose(direction, 350.0)


def test_available_blends_renormalize_and_enforce_minimum_sources() -> None:
    fit = WeightFit({"a": 0.5, "b": 0.3, "c": 0.2}, 100, 1.0)

    scalar = blend_scalar_available(fit, {"a": 10.0, "b": None, "c": 20.0}, 2)
    assert scalar is not None
    assert isclose(scalar, (0.5 * 10.0 + 0.2 * 20.0) / 0.7)
    assert blend_scalar_available(fit, {"a": 10.0, "b": None, "c": 20.0}, 3) is None

    wind = blend_wind_available(
        fit,
        {"a": (10.0, 0.0), "b": (None, None), "c": (10.0, 90.0)},
        2,
    )
    assert wind is not None
    speed, direction = wind
    assert isclose(speed, 10.0 * (0.5**2 + 0.2**2) ** 0.5 / 0.7)
    assert isclose(direction, 21.80140948635181)
    assert blend_wind_available(
        fit,
        {"a": (10.0, 0.0), "b": (None, None), "c": (10.0, 90.0)},
        3,
    ) is None


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


def test_positive_amount_fit_ignores_zero_events_and_uses_trained_blend() -> None:
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
    assert isclose(blend_positive_amount(fit, {"accurate": 2.0, "wet": 10.0}), 2.0, abs_tol=1e-6)

    no_rain = fit_positive_amount_weights(
        ("accurate", "wet"),
        ((0.0, 0.0), (0.0, 0.0)),
        (0.0, 0.0),
        eligible_models=frozenset({"accurate", "wet"}),
        minimum_positive_samples=1,
    )
    assert no_rain == FitFailure(ExclusionReason.INSUFFICIENT_SAMPLES, "0 < 1")


def test_positive_amount_prediction_matches_fitted_linear_objective() -> None:
    fit = fit_positive_amount_weights(
        ("dry", "wet"),
        ((1.0, 9.0),) * 3,
        (3.0,) * 3,
        eligible_models=frozenset({"dry", "wet"}),
        regularization=0.0,
        minimum_positive_samples=3,
    )

    assert isinstance(fit, WeightFit)
    assert fit.objective < 1e-10
    assert isclose(blend_positive_amount(fit, {"dry": 1.0, "wet": 9.0}), 3.0, abs_tol=1e-6)
    with pytest.raises(ValueError, match="non-negative"):
        blend_positive_amount(fit, {"dry": -1.0, "wet": 9.0})
