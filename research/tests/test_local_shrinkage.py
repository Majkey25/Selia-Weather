from dataclasses import replace
from datetime import date, timedelta

import pytest

from aladin_ensemble.align import DateRange
from aladin_ensemble.baselines import ScalarForecastCase
from aladin_ensemble.local_shrinkage import fit_local_shrinkage


def test_forward_selection_is_local_and_cannot_use_later_labels() -> None:
    start = date(2025, 8, 1)
    training = {
        location: tuple(
            ScalarForecastCase(
                start + timedelta(days=day),
                "temperature",
                24,
                "EUROPE",
                "low",
                "autumn",
                observed,
                {"a": 0.0, "b": 10.0},
                5.0,
            )
            for day in range(130)
        )
        for location, observed in (("north", 0.0), ("south", 10.0))
    }
    windows = tuple(
        DateRange(start + timedelta(days=first), start + timedelta(days=last))
        for first, last in ((90, 104), (105, 119))
    )
    selected = fit_local_shrinkage(training, ("a", "b"), windows)
    assert selected["north"].local_fraction == selected["south"].local_fraction == 1.0
    assert selected["north"].fit.weights["a"] > 0.99
    assert selected["south"].fit.weights["b"] > 0.99
    assert selected["north"].validation_dates == 30

    changed = {
        location: tuple(
            replace(case, observation=10.0 - case.observation)
            if case.forecast_date > windows[-1].end
            else case
            for case in cases
        )
        for location, cases in training.items()
    }
    later = fit_local_shrinkage(changed, ("a", "b"), windows)
    for location in selected:
        assert later[location].validation_mae == selected[location].validation_mae
        assert later[location].local_fraction == selected[location].local_fraction
        assert later[location].fit.weights != selected[location].fit.weights

    mixed = dict(training)
    mixed["north"] = (replace(training["north"][0], lead_hours=48), *training["north"][1:])
    with pytest.raises(ValueError, match="one variable, lead and region"):
        fit_local_shrinkage(mixed, ("a", "b"), windows)
    with pytest.raises(ValueError, match="90 earlier"):
        fit_local_shrinkage(
            training,
            ("a", "b"),
            (
                DateRange(start + timedelta(days=89), start + timedelta(days=104)),
                windows[1],
            ),
        )
    with pytest.raises(ValueError, match="ordered and non-overlapping"):
        fit_local_shrinkage(training, ("a", "b"), (windows[0], windows[0]))
    missing = dict(training)
    missing["north"] = (
        replace(training["north"][0], model_values={"a": None, "b": 10.0}),
        *training["north"][1:],
    )
    with pytest.raises(ValueError, match="complete matched predictors"):
        fit_local_shrinkage(missing, ("a", "b"), windows)
