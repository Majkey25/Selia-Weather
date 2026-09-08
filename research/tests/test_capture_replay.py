from __future__ import annotations

from dataclasses import replace

import pytest

from aladin_ensemble.sources.live_capture import CaptureError
from scripts.replay_capture_20260905 import comparison


def test_replay_baselines_share_the_complete_hour_mask() -> None:
    first = CaptureError(
        "a", "temperature", "2026-09-06T00:00:00Z", 3600, 10, 12, 2, "paired", None,
    )
    second = replace(first, model_id="b", forecast_value=20, absolute_error=8)
    incomplete = replace(first, valid_time="2026-09-06T01:00:00Z", absolute_error=100)
    assert comparison((first, second, incomplete)) == {"temperature": {
        "matched_hours": 1, "nonzero_observation_hours": 1, "observed_sum": None,
        "mae": {"a": 2, "b": 8, "arithmetic_mean": 3, "median": 3},
    }}


def test_replay_cannot_hide_disagreement_about_the_matched_truth() -> None:
    first = CaptureError(
        "a", "temperature", "2026-09-06T00:00:00Z", 3600, 10, 12, 2, "paired", None,
    )
    with pytest.raises(ValueError, match="disagree"):
        comparison((first, replace(first, model_id="b", observed_value=13)))


def test_replay_does_not_compute_a_linear_average_of_directions() -> None:
    first = CaptureError(
        "a", "wind_direction", "2026-09-06T00:00:00Z", 3600, 359, 1, 2, "paired", None,
    )
    assert comparison((first,)) == {"wind_direction": {
        "matched_hours": 1, "nonzero_observation_hours": 1, "observed_sum": None, "mae": {"a": 2},
    }}
