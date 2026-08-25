from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from aladin_ensemble.train import WeightFit


class ExclusionReason(str, Enum):
    INSUFFICIENT_SAMPLES = "insufficient_samples"
    UNSTABLE_FIT = "unstable_fit"
    HOLDOUT_FAILED = "holdout_failed"
    MISSING_COVERAGE = "missing_coverage"
    LICENCE_FAILURE = "licence_failure"


@dataclass(frozen=True, slots=True)
class FitFailure:
    reason: ExclusionReason
    detail: str

    def __post_init__(self) -> None:
        if not self.detail:
            raise ValueError("failure detail is required")


@dataclass(frozen=True, order=True, slots=True)
class SegmentSelector:
    variable: str
    lead_bucket: str | None
    season: str | None
    region: str | None
    elevation_band: str | None

    def __post_init__(self) -> None:
        if not self.variable:
            raise ValueError("segment variable is required")
        if any(value == "" for value in self.optional_values):
            raise ValueError("optional segment fields cannot be empty")

    @property
    def optional_values(self) -> tuple[str | None, ...]:
        return self.lead_bucket, self.season, self.region, self.elevation_band


@dataclass(frozen=True, slots=True)
class ExclusionRecord:
    selector: SegmentSelector
    reason: ExclusionReason
    detail: str

    def __post_init__(self) -> None:
        if not self.detail:
            raise ValueError("exclusion detail is required")


@dataclass(frozen=True, slots=True)
class FallbackDecision:
    selector: SegmentSelector | None
    fit: WeightFit | None
    best_model: str
    fallback_level: int

    def __post_init__(self) -> None:
        if not self.best_model:
            raise ValueError("best_model is required")
        if self.fallback_level < 0:
            raise ValueError("fallback_level must be non-negative")


def fallback_chain(selector: SegmentSelector) -> tuple[SegmentSelector, ...]:
    candidates = (
        selector,
        SegmentSelector(
            selector.variable,
            selector.lead_bucket,
            selector.season,
            None,
            selector.elevation_band,
        ),
        SegmentSelector(selector.variable, selector.lead_bucket, None, None, None),
        SegmentSelector(selector.variable, None, None, None, None),
    )
    return tuple(dict.fromkeys(candidates))


def resolve_fallback(
    selector: SegmentSelector,
    available: Mapping[SegmentSelector, WeightFit],
    *,
    best_model: str,
) -> FallbackDecision:
    if not best_model:
        raise ValueError("best_model is required")
    chain = fallback_chain(selector)
    for level, candidate in enumerate(chain):
        fit = available.get(candidate)
        if fit is not None:
            return FallbackDecision(candidate, fit, best_model, level)
    return FallbackDecision(None, None, best_model, len(chain))
