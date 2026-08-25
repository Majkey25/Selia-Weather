from __future__ import annotations

from aladin_ensemble.fallback import (
    ExclusionReason,
    ExclusionRecord,
    FallbackDecision,
    SegmentSelector,
    fallback_chain,
    resolve_fallback,
)
from aladin_ensemble.train import WeightFit


def _selector() -> SegmentSelector:
    return SegmentSelector(
        variable="temperature",
        lead_bucket="7-24h",
        season="summer",
        region="REGION_PRAGUE",
        elevation_band="low",
    )


def test_fallback_chain_matches_approved_hierarchy() -> None:
    requested = _selector()

    assert fallback_chain(requested) == (
        requested,
        SegmentSelector("temperature", "7-24h", "summer", None, "low"),
        SegmentSelector("temperature", "7-24h", None, None, None),
        SegmentSelector("temperature", None, None, None, None),
    )


def test_resolve_fallback_uses_first_available_segment_then_best_model() -> None:
    requested = _selector()
    broad = SegmentSelector("temperature", "7-24h", None, None, None)
    fit = WeightFit({"model_a": 1.0}, sample_count=100, objective=1.2)

    decision = resolve_fallback(requested, {broad: fit}, best_model="model_b")

    assert decision == FallbackDecision(broad, fit, "model_b", fallback_level=2)
    assert resolve_fallback(requested, {}, best_model="model_b") == FallbackDecision(
        None,
        None,
        "model_b",
        fallback_level=4,
    )


def test_exclusion_record_keeps_explicit_reason() -> None:
    record = ExclusionRecord(
        _selector(),
        ExclusionReason.LICENCE_FAILURE,
        "commercial use is not allowed",
    )

    assert record.reason is ExclusionReason.LICENCE_FAILURE
    assert record.detail == "commercial use is not allowed"
