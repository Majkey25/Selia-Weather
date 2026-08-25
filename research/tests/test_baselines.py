from __future__ import annotations

from datetime import date
from math import sqrt

import pytest

from aladin_ensemble.baselines import ScalarForecastCase, evaluate_scalar_baselines


def _case(
    day: int,
    observed: float,
    model_a: float | None,
    model_b: float | None,
    best_match: float | None,
    *,
    lead_hours: int = 12,
) -> ScalarForecastCase:
    return ScalarForecastCase(
        forecast_date=date(2026, 8, day),
        variable="temperature",
        lead_hours=lead_hours,
        region="REGION_PRAGUE",
        elevation_band="low",
        season="summer",
        observation=observed,
        model_values={"model_a": model_a, "model_b": model_b},
        best_match=best_match,
    )


def test_baselines_share_one_complete_case_mask() -> None:
    rows = evaluate_scalar_baselines(
        (
            _case(1, 0.0, 0.0, 2.0, 1.0),
            _case(2, 2.0, 2.0, None, 2.0),
            _case(3, 4.0, 6.0, 4.0, 5.0),
        ),
        ("model_a", "model_b"),
        bootstrap_repetitions=100,
        seed=7,
    )
    by_name = {row.baseline: row for row in rows}

    assert set(by_name) == {"model_a", "model_b", "best_match", "mean", "median"}
    assert {row.sample_count for row in rows} == {2}
    assert by_name["model_a"].mae == 1.0
    assert by_name["model_a"].rmse == pytest.approx(sqrt(2.0))
    assert by_name["best_match"].mae == 1.0
    assert by_name["best_match"].rmse == 1.0
    assert by_name["mean"].mae == 1.0
    assert by_name["median"].mae == 1.0


def test_baseline_groups_and_bootstrap_are_deterministic() -> None:
    cases = (
        _case(1, 0.0, 0.0, 2.0, 1.0),
        _case(2, 2.0, 2.0, 4.0, 3.0, lead_hours=36),
    )

    first = evaluate_scalar_baselines(
        cases, ("model_a", "model_b"), bootstrap_repetitions=80, seed=11
    )
    second = evaluate_scalar_baselines(
        cases, ("model_a", "model_b"), bootstrap_repetitions=80, seed=11
    )

    assert first == second
    assert {row.group.lead_hours for row in first} == {12, 36}
    assert len(first) == 10


def test_baselines_reject_unknown_models_and_empty_common_mask() -> None:
    with pytest.raises(ValueError, match="model_ids"):
        evaluate_scalar_baselines((_case(1, 0.0, 0.0, 1.0, 0.5),), (), seed=1)
    with pytest.raises(ValueError, match="model_b"):
        ScalarForecastCase(
            forecast_date=date(2026, 8, 1),
            variable="temperature",
            lead_hours=12,
            region="REGION_PRAGUE",
            elevation_band="low",
            season="summer",
            observation=0.0,
            model_values={"model_a": 0.0},
            best_match=0.0,
        ).values_for(("model_a", "model_b"))
    with pytest.raises(ValueError, match="common complete-case"):
        evaluate_scalar_baselines(
            (_case(1, 0.0, 0.0, None, 0.0),),
            ("model_a", "model_b"),
            seed=1,
        )
