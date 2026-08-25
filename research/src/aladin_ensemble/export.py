from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from math import isfinite
from pathlib import Path

from aladin_ensemble.evaluate import HoldoutLock, SegmentEvaluation
from aladin_ensemble.fallback import SegmentSelector
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.train import WeightFit


@dataclass(frozen=True, slots=True)
class ModelContract:
    model_id: str
    maximum_run_age_hours: int
    resolution_km: float

    def __post_init__(self) -> None:
        if not self.model_id:
            raise ValueError("model_id is required")
        if self.maximum_run_age_hours <= 0:
            raise ValueError("maximum_run_age_hours must be positive")
        if not isfinite(self.resolution_km) or self.resolution_km <= 0:
            raise ValueError("resolution_km must be finite and positive")


@dataclass(frozen=True, slots=True)
class ExportSegment:
    evaluation: SegmentEvaluation
    fit: WeightFit | None
    minimum_source_count: int

    def __post_init__(self) -> None:
        if self.minimum_source_count <= 0:
            raise ValueError("minimum_source_count must be positive")
        if self.evaluation.accepted and self.fit is None:
            raise ValueError("accepted segment requires a fitted blend")
        if not self.evaluation.accepted and self.fit is not None:
            raise ValueError("rejected segment must export only its fallback")
        if self.fit is not None:
            positive_sources = sum(weight > 0 for weight in self.fit.weights.values())
            if self.minimum_source_count > positive_sources:
                raise ValueError("minimum_source_count exceeds positive fitted source count")
        elif self.minimum_source_count != 1:
            raise ValueError("fallback segment requires minimum_source_count of one")


@dataclass(frozen=True, slots=True)
class EnsembleArtifact:
    schema_version: int
    dataset_manifest_hash: str
    generated_at: datetime
    models: tuple[ModelContract, ...]
    segments: tuple[ExportSegment, ...]


def build_artifact(
    *,
    lock: HoldoutLock,
    generated_at: datetime,
    registry_status: str,
    models: tuple[ModelContract, ...],
    segments: tuple[ExportSegment, ...],
) -> EnsembleArtifact:
    if registry_status != "complete":
        raise ValueError("model registry is not complete")
    _utc(generated_at, "generated_at")
    if generated_at < lock.locked_at:
        raise ValueError("generated_at precedes the holdout lock")
    if not models or not segments:
        raise ValueError("models and segments must be non-empty")
    model_ids = {model.model_id for model in models}
    if len(model_ids) != len(models):
        raise ValueError("model contracts contain duplicates")
    selectors = [segment.evaluation.selector for segment in segments]
    if len(set(selectors)) != len(selectors):
        raise ValueError("segment selectors contain duplicates")
    for segment in segments:
        referenced = {segment.evaluation.fallback_model}
        if segment.fit is not None:
            referenced.update(segment.fit.weights)
        unknown = referenced.difference(model_ids)
        if unknown:
            raise ValueError(f"segment references unknown model: {sorted(unknown)}")
    return EnsembleArtifact(
        1,
        lock.dataset_manifest_hash,
        generated_at,
        tuple(sorted(models, key=lambda model: model.model_id)),
        tuple(sorted(segments, key=lambda segment: _selector_key(segment.evaluation.selector))),
    )


def export_artifact(artifact: EnsembleArtifact) -> bytes:
    payload: dict[str, JsonValue] = {
        "dataset_manifest_hash": artifact.dataset_manifest_hash,
        "generated_at": artifact.generated_at.isoformat().replace("+00:00", "Z"),
        "models": [
            {
                "maximum_run_age_hours": model.maximum_run_age_hours,
                "model_id": model.model_id,
                "resolution_km": _number(model.resolution_km),
            }
            for model in artifact.models
        ],
        "schema_version": artifact.schema_version,
        "segments": [_segment_payload(segment) for segment in artifact.segments],
    }
    serialized = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return (serialized + "\n").encode()


def write_artifact(path: Path, artifact: EnsembleArtifact) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(export_artifact(artifact))


def _segment_payload(segment: ExportSegment) -> dict[str, JsonValue]:
    evaluation = segment.evaluation
    if evaluation.accepted:
        assert segment.fit is not None
        mode = "blend"
        weights = {model_id: _number(weight) for model_id, weight in segment.fit.weights.items()}
    else:
        mode = "fallback"
        weights = {evaluation.fallback_model: 1.0}
    return {
        "fallback_model": evaluation.fallback_model,
        "holdout": {
            "accepted": evaluation.accepted,
            "best_model_score": _number(evaluation.best_model_score),
            "blend_score": _number(evaluation.blend_score),
            "improvement": {
                "estimate": _number(evaluation.improvement.estimate),
                "lower": _number(evaluation.improvement.lower),
                "upper": _number(evaluation.improvement.upper),
            },
            "maximum_region_degradation": (
                None
                if evaluation.maximum_region_degradation is None
                else _number(evaluation.maximum_region_degradation)
            ),
            "metric": evaluation.metric,
            "rejection_reasons": [reason.value for reason in evaluation.rejection_reasons],
            "sample_count": evaluation.sample_count,
        },
        "minimum_source_count": segment.minimum_source_count,
        "mode": mode,
        "selector": {
            "elevation_band": evaluation.selector.elevation_band,
            "lead_bucket": evaluation.selector.lead_bucket,
            "region": evaluation.selector.region,
            "season": evaluation.selector.season,
            "variable": evaluation.selector.variable,
        },
        "weights": dict(sorted(weights.items())),
    }


def _selector_key(selector: SegmentSelector) -> tuple[str, str, str, str, str]:
    return (
        selector.variable,
        selector.lead_bucket or "",
        selector.season or "",
        selector.region or "",
        selector.elevation_band or "",
    )


def _number(value: float) -> float:
    if not isfinite(value):
        raise ValueError("artifact numbers must be finite")
    rounded = round(value, 8)
    return 0.0 if rounded == 0 else rounded


def _utc(value: datetime, name: str) -> None:
    if value.tzinfo is None or value.utcoffset() != UTC.utcoffset(value):
        raise ValueError(f"{name} must be timezone-aware UTC")
