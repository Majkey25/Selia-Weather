from __future__ import annotations

import json
import os
import tempfile
from dataclasses import dataclass
from datetime import UTC, date, datetime
from enum import Enum
from math import isfinite
from pathlib import Path
from statistics import fmean
from typing import cast

from aladin_ensemble.align import DateRange
from aladin_ensemble.fallback import SegmentSelector
from aladin_ensemble.metrics import ConfidenceInterval, block_bootstrap_mean_interval
from aladin_ensemble.registry import JsonValue


class EvaluationFailure(str, Enum):
    INSUFFICIENT_HOLDOUT = "insufficient_holdout"
    NO_SIGNIFICANT_IMPROVEMENT = "no_significant_improvement"
    REGION_DEGRADATION = "region_degradation"
    UNSTABLE_FOLDS = "unstable_folds"
    MISSING_FALLBACK = "missing_fallback"
    INSUFFICIENT_SOURCES = "insufficient_sources"


@dataclass(frozen=True, slots=True)
class HoldoutLock:
    train: DateRange
    holdout: DateRange
    dataset_manifest_hash: str
    locked_at: datetime

    def __post_init__(self) -> None:
        train_days = (self.train.end - self.train.start).days + 1
        holdout_days = (self.holdout.end - self.holdout.start).days + 1
        if train_days < 90:
            raise ValueError("training range must contain at least 90 days")
        if holdout_days < 30:
            raise ValueError("holdout range must contain at least 30 days")
        if self.train.end >= self.holdout.start:
            raise ValueError("training and holdout ranges overlap")
        _checksum(self.dataset_manifest_hash)
        _utc(self.locked_at, "locked_at")


@dataclass(frozen=True, slots=True)
class EvaluationSample:
    forecast_date: date
    region: str
    blend_loss: float
    best_model_loss: float

    def __post_init__(self) -> None:
        if not self.region:
            raise ValueError("sample region is required")
        for value, name in (
            (self.blend_loss, "blend_loss"),
            (self.best_model_loss, "best_model_loss"),
        ):
            if not isfinite(value) or value < 0:
                raise ValueError(f"{name} must be finite and non-negative")


@dataclass(frozen=True, slots=True)
class SegmentEvaluation:
    selector: SegmentSelector
    metric: str
    fallback_model: str
    sample_count: int
    blend_score: float
    best_model_score: float
    improvement: ConfidenceInterval
    maximum_region_degradation: float | None
    fold_improvements: tuple[float, ...]
    accepted: bool
    rejection_reasons: tuple[EvaluationFailure, ...]


def evaluate_segment(
    selector: SegmentSelector,
    samples: tuple[EvaluationSample, ...],
    *,
    metric: str,
    fallback_model: str,
    fold_improvements: tuple[float, ...],
    missing_fallback_ok: bool,
    minimum_sources_ok: bool = True,
    trained_at: datetime,
    lock: HoldoutLock,
    bootstrap_repetitions: int = 1_000,
    seed: int = 20_260_825,
) -> SegmentEvaluation:
    if not metric or not fallback_model:
        raise ValueError("metric and fallback_model are required")
    _utc(trained_at, "trained_at")
    if trained_at > lock.locked_at:
        raise ValueError("artifact was trained after the holdout lock")
    if not samples:
        raise ValueError("evaluation samples must be non-empty")
    if any(not lock.holdout.contains(sample.forecast_date) for sample in samples):
        raise ValueError("evaluation sample is outside the locked holdout")
    if any(not isfinite(value) for value in fold_improvements):
        raise ValueError("fold improvements must be finite")

    differences = tuple(
        (sample.forecast_date, sample.best_model_loss - sample.blend_loss)
        for sample in samples
    )
    improvement = block_bootstrap_mean_interval(
        differences,
        repetitions=bootstrap_repetitions,
        seed=seed,
    )
    blend_score = fmean(sample.blend_loss for sample in samples)
    best_score = fmean(sample.best_model_loss for sample in samples)
    maximum_degradation = _maximum_region_degradation(samples)
    reasons: list[EvaluationFailure] = []
    if len({sample.forecast_date for sample in samples}) < 30:
        reasons.append(EvaluationFailure.INSUFFICIENT_HOLDOUT)
    if improvement.lower <= 0:
        reasons.append(EvaluationFailure.NO_SIGNIFICANT_IMPROVEMENT)
    if maximum_degradation is None or maximum_degradation > 0.05:
        reasons.append(EvaluationFailure.REGION_DEGRADATION)
    if len(fold_improvements) < 2 or any(value <= 0 for value in fold_improvements):
        reasons.append(EvaluationFailure.UNSTABLE_FOLDS)
    if not missing_fallback_ok:
        reasons.append(EvaluationFailure.MISSING_FALLBACK)
    if not minimum_sources_ok:
        reasons.append(EvaluationFailure.INSUFFICIENT_SOURCES)
    return SegmentEvaluation(
        selector,
        metric,
        fallback_model,
        len(samples),
        blend_score,
        best_score,
        improvement,
        maximum_degradation,
        fold_improvements,
        not reasons,
        tuple(reasons),
    )


def write_holdout_lock(path: Path, lock: HoldoutLock) -> None:
    payload = _lock_bytes(lock)
    if path.exists():
        if path.read_bytes() != payload:
            raise ValueError("holdout lock already exists with different content")
        return
    _atomic_write(path, payload)


def read_holdout_lock(path: Path) -> HoldoutLock:
    value = _json_value(cast(object, json.loads(path.read_text(encoding="utf-8"))))
    if not isinstance(value, dict):
        raise ValueError("holdout lock must be a JSON object")
    train = _range(value.get("train"), "train")
    holdout = _range(value.get("holdout"), "holdout")
    manifest_hash = _text(value.get("dataset_manifest_hash"), "dataset_manifest_hash")
    locked_at = _timestamp(value.get("locked_at"), "locked_at")
    return HoldoutLock(train, holdout, manifest_hash, locked_at)


def _maximum_region_degradation(samples: tuple[EvaluationSample, ...]) -> float | None:
    groups: dict[str, list[EvaluationSample]] = {}
    for sample in samples:
        groups.setdefault(sample.region, []).append(sample)
    degradations: list[float] = []
    for region_samples in groups.values():
        blend = fmean(sample.blend_loss for sample in region_samples)
        best = fmean(sample.best_model_loss for sample in region_samples)
        if best == 0:
            if blend != 0:
                return None
            degradations.append(0.0)
        else:
            degradations.append((blend - best) / best)
    return max(degradations)


def _lock_bytes(lock: HoldoutLock) -> bytes:
    payload: dict[str, JsonValue] = {
        "dataset_manifest_hash": lock.dataset_manifest_hash,
        "holdout": {"end": lock.holdout.end.isoformat(), "start": lock.holdout.start.isoformat()},
        "locked_at": lock.locked_at.isoformat().replace("+00:00", "Z"),
        "train": {"end": lock.train.end.isoformat(), "start": lock.train.start.isoformat()},
    }
    serialized = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return (serialized + "\n").encode()


def _atomic_write(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temporary:
            temporary.write(payload)
            temporary.flush()
            os.fsync(temporary.fileno())
            temporary_path = Path(temporary.name)
        temporary_path.replace(path)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def _range(value: JsonValue | None, name: str) -> DateRange:
    if not isinstance(value, dict):
        raise ValueError(f"{name} must be an object")
    return DateRange(
        _date(value.get("start"), f"{name}.start"),
        _date(value.get("end"), f"{name}.end"),
    )


def _date(value: JsonValue | None, name: str) -> date:
    text = _text(value, name)
    try:
        return date.fromisoformat(text)
    except ValueError as error:
        raise ValueError(f"{name} must be an ISO date") from error


def _timestamp(value: JsonValue | None, name: str) -> datetime:
    text = _text(value, name)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{name} must be an ISO timestamp") from error
    _utc(parsed, name)
    return parsed


def _text(value: JsonValue | None, name: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{name} must be text")
    return value


def _json_value(value: object) -> JsonValue:
    if value is None or isinstance(value, str | int | float | bool):
        return value
    if isinstance(value, list):
        return [_json_value(item) for item in cast(list[object], value)]
    if isinstance(value, dict):
        result: dict[str, JsonValue] = {}
        for key, item in cast(dict[object, object], value).items():
            if not isinstance(key, str):
                raise ValueError("JSON object key must be text")
            result[key] = _json_value(item)
        return result
    raise ValueError("invalid JSON value")


def _checksum(value: str) -> None:
    if len(value) != 64 or any(character not in "0123456789abcdef" for character in value):
        raise ValueError("dataset_manifest_hash must be a lowercase SHA-256 digest")


def _utc(value: datetime, name: str) -> None:
    if value.tzinfo is None or value.utcoffset() != UTC.utcoffset(value):
        raise ValueError(f"{name} must be timezone-aware UTC")
