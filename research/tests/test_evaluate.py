from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest

from aladin_ensemble.align import DateRange
from aladin_ensemble.evaluate import (
    EvaluationFailure,
    EvaluationSample,
    HoldoutLock,
    evaluate_segment,
    read_holdout_lock,
    write_holdout_lock,
)
from aladin_ensemble.fallback import SegmentSelector

DATASET_HASH = "0123456789abcdef" * 4
LOCKED_AT = datetime(2026, 6, 1, tzinfo=UTC)


def _lock() -> HoldoutLock:
    return HoldoutLock(
        train=DateRange(date(2026, 1, 1), date(2026, 4, 1)),
        holdout=DateRange(date(2026, 4, 2), date(2026, 5, 1)),
        dataset_manifest_hash=DATASET_HASH,
        locked_at=LOCKED_AT,
    )


def _selector() -> SegmentSelector:
    return SegmentSelector("temperature", "7-24h", "spring", "REGION_PRAGUE", "low")


def test_holdout_lock_round_trips_and_requires_90_plus_30_days(tmp_path: Path) -> None:
    path = tmp_path / "holdout-lock.json"

    write_holdout_lock(path, _lock())

    assert read_holdout_lock(path) == _lock()
    with pytest.raises(ValueError, match="90"):
        HoldoutLock(
            DateRange(date(2026, 1, 1), date(2026, 1, 31)),
            DateRange(date(2026, 2, 1), date(2026, 3, 2)),
            DATASET_HASH,
            LOCKED_AT,
        )
    with pytest.raises(ValueError, match="30"):
        HoldoutLock(
            DateRange(date(2026, 1, 1), date(2026, 4, 1)),
            DateRange(date(2026, 4, 2), date(2026, 4, 20)),
            DATASET_HASH,
            LOCKED_AT,
        )


def test_segment_is_accepted_only_when_every_ship_rule_passes() -> None:
    samples = tuple(
        EvaluationSample(
            date(2026, 4, 2) + timedelta(days=offset),
            "REGION_PRAGUE" if offset % 2 == 0 else "REGION_BRNO",
            1.0,
            2.0,
        )
        for offset in range(30)
    )

    result = evaluate_segment(
        _selector(),
        samples,
        metric="mae",
        fallback_model="model_a",
        fold_improvements=(0.8, 0.9),
        missing_fallback_ok=True,
        trained_at=datetime(2026, 5, 31, tzinfo=UTC),
        lock=_lock(),
        bootstrap_repetitions=100,
        seed=7,
    )

    assert result.accepted
    assert result.rejection_reasons == ()
    assert result.improvement.estimate == 1.0
    assert result.improvement.lower == 1.0
    assert result.maximum_region_degradation == -0.5
    assert result.sample_count == 30


def test_segment_records_every_failed_ship_rule() -> None:
    samples = tuple(
        EvaluationSample(
            date(2026, 4, 2) + timedelta(days=offset),
            "REGION_PRAGUE",
            1.2,
            1.0,
        )
        for offset in range(30)
    )

    result = evaluate_segment(
        _selector(),
        samples,
        metric="mae",
        fallback_model="model_a",
        fold_improvements=(0.1,),
        missing_fallback_ok=False,
        trained_at=datetime(2026, 5, 31, tzinfo=UTC),
        lock=_lock(),
        bootstrap_repetitions=50,
        seed=9,
    )

    assert not result.accepted
    assert set(result.rejection_reasons) == {
        EvaluationFailure.NO_SIGNIFICANT_IMPROVEMENT,
        EvaluationFailure.REGION_DEGRADATION,
        EvaluationFailure.UNSTABLE_FOLDS,
        EvaluationFailure.MISSING_FALLBACK,
    }


def test_segment_rejects_too_few_active_sources() -> None:
    samples = tuple(
        EvaluationSample(
            date(2026, 4, 2) + timedelta(days=offset),
            "REGION_PRAGUE",
            1.0,
            2.0,
        )
        for offset in range(30)
    )

    result = evaluate_segment(
        _selector(),
        samples,
        metric="mae",
        fallback_model="model_a",
        fold_improvements=(1.0, 1.0),
        missing_fallback_ok=True,
        minimum_sources_ok=False,
        trained_at=datetime(2026, 5, 31, tzinfo=UTC),
        lock=_lock(),
        bootstrap_repetitions=20,
    )

    assert not result.accepted
    assert EvaluationFailure.INSUFFICIENT_SOURCES in result.rejection_reasons


def test_segment_rejects_less_than_30_distinct_holdout_days() -> None:
    result = evaluate_segment(
        _selector(),
        (
            EvaluationSample(date(2026, 4, 2), "REGION_PRAGUE", 1.0, 2.0),
            EvaluationSample(date(2026, 4, 3), "REGION_PRAGUE", 1.0, 2.0),
        ),
        metric="mae",
        fallback_model="model_a",
        fold_improvements=(1.0, 1.0),
        missing_fallback_ok=True,
        trained_at=datetime(2026, 5, 31, tzinfo=UTC),
        lock=_lock(),
        bootstrap_repetitions=20,
    )

    assert not result.accepted
    assert EvaluationFailure.INSUFFICIENT_HOLDOUT in result.rejection_reasons


def test_evaluator_rejects_leakage_and_outside_holdout_samples() -> None:
    sample = (EvaluationSample(date(2026, 4, 2), "REGION_PRAGUE", 1.0, 2.0),)
    with pytest.raises(ValueError, match="trained after"):
        evaluate_segment(
            _selector(),
            sample,
            metric="mae",
            fallback_model="model_a",
            fold_improvements=(1.0, 1.0),
            missing_fallback_ok=True,
            trained_at=datetime(2026, 6, 2, tzinfo=UTC),
            lock=_lock(),
        )
    with pytest.raises(ValueError, match="holdout"):
        evaluate_segment(
            _selector(),
            (EvaluationSample(date(2026, 4, 1), "REGION_PRAGUE", 1.0, 2.0),),
            metric="mae",
            fallback_model="model_a",
            fold_improvements=(1.0, 1.0),
            missing_fallback_ok=True,
            trained_at=datetime(2026, 5, 31, tzinfo=UTC),
            lock=_lock(),
        )
