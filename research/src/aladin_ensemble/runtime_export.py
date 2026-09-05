from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from math import isfinite
from pathlib import Path
from typing import Literal, cast

from aladin_ensemble.evaluate import SegmentEvaluation
from aladin_ensemble.export import ModelContract
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.train import WeightFit

TruthClass = Literal["station", "radar_gauge", "satellite_precipitation", "reanalysis"]


@dataclass(frozen=True, slots=True)
class RuntimeSelector:
    region: str
    variable: str
    minimum_lead_hours: int
    maximum_lead_hours: int
    months: tuple[int, ...]

    def __post_init__(self) -> None:
        if self.region not in _REGIONS:
            raise ValueError("runtime region is invalid")
        if self.variable not in _RUNTIME_VARIABLES.values():
            raise ValueError("runtime variable is invalid")
        if self.minimum_lead_hours < 0 or self.maximum_lead_hours < self.minimum_lead_hours:
            raise ValueError("runtime lead range is invalid")
        if (
            not self.months
            or tuple(sorted(set(self.months))) != self.months
            or any(month not in range(1, 13) for month in self.months)
        ):
            raise ValueError("runtime months are invalid")


@dataclass(frozen=True, slots=True)
class RuntimeCalibrationSegment:
    selector: RuntimeSelector
    evaluation: SegmentEvaluation
    fit: WeightFit
    minimum_source_count: int
    truth_class: TruthClass

    def __post_init__(self) -> None:
        expected_variable = _RUNTIME_VARIABLES.get(self.evaluation.selector.variable)
        if expected_variable != self.selector.variable:
            raise ValueError("runtime selector does not match its evaluation")
        if not self.evaluation.accepted:
            raise ValueError("runtime segment failed its holdout")
        evaluated = self.evaluation.selector
        if evaluated.elevation_band is not None:
            raise ValueError("runtime schema cannot represent evaluated elevation scope")
        if evaluated.region != self.selector.region:
            raise ValueError("runtime region exceeds evaluated scope")
        if not set(self.selector.months).issubset(
            _SEASON_MONTHS.get(evaluated.season or "", ())
        ):
            raise ValueError("runtime months exceed evaluated season scope")
        lead = re.fullmatch(r"([0-9]+)(?:-([0-9]+))?h", evaluated.lead_bucket or "")
        if lead is None or not (
            int(lead[1]) <= self.selector.minimum_lead_hours
            <= self.selector.maximum_lead_hours <= int(lead[2] or lead[1])
        ):
            raise ValueError("runtime lead range exceeds evaluated scope")
        if self.truth_class not in _TRUTH_CLASSES:
            raise ValueError("runtime truth class is invalid")
        positive_sources = sum(weight > 0 for weight in self.fit.weights.values())
        if self.minimum_source_count < 2 or self.minimum_source_count > positive_sources:
            raise ValueError("runtime minimum source count is invalid")


@dataclass(frozen=True, slots=True)
class RuntimeArtifact:
    schema_version: int
    dataset_manifest_hash: str
    model_contract_hash: str
    generated_at: datetime
    expires_at: datetime
    models: tuple[ModelContract, ...]
    segments: tuple[RuntimeCalibrationSegment, ...]


def build_runtime_artifact(
    *,
    dataset_manifest_hash: str,
    model_contract_hash: str,
    generated_at: datetime,
    expires_at: datetime,
    models: tuple[ModelContract, ...],
    segments: tuple[RuntimeCalibrationSegment, ...],
) -> RuntimeArtifact:
    _checksum(dataset_manifest_hash, "dataset_manifest_hash")
    _checksum(model_contract_hash, "model_contract_hash")
    _utc(generated_at, "generated_at")
    _utc(expires_at, "expires_at")
    validity = expires_at - generated_at
    if validity <= timedelta(0) or validity > MAX_ARTIFACT_VALIDITY:
        raise ValueError("runtime artifact validity is invalid")
    if not models or not segments:
        raise ValueError("runtime models and segments are required")
    model_ids = {model.model_id for model in models}
    if len(model_ids) != len(models):
        raise ValueError("runtime model IDs are duplicated")
    selector_keys = {_selector_key(segment.selector) for segment in segments}
    if len(selector_keys) != len(segments):
        raise ValueError("runtime segment selectors are duplicated")
    for segment in segments:
        unknown = set(segment.fit.weights).difference(model_ids)
        if unknown:
            raise ValueError(f"runtime segment references unknown model: {sorted(unknown)}")
        if segment.evaluation.fallback_model not in model_ids | {"best_match"}:
            raise ValueError("runtime fallback model is unknown")
    return RuntimeArtifact(
        schema_version=2,
        dataset_manifest_hash=dataset_manifest_hash,
        model_contract_hash=model_contract_hash,
        generated_at=generated_at,
        expires_at=expires_at,
        models=tuple(sorted(models, key=lambda model: model.model_id)),
        segments=tuple(sorted(segments, key=lambda segment: _selector_key(segment.selector))),
    )


def export_runtime_artifact(artifact: RuntimeArtifact) -> bytes:
    payload: dict[str, JsonValue] = {
        "dataset_manifest_hash": artifact.dataset_manifest_hash,
        "expires_at": _iso(artifact.expires_at),
        "generated_at": _iso(artifact.generated_at),
        "model_contract_hash": artifact.model_contract_hash,
        "models": cast(
            list[JsonValue],
            [
                {
                    "maximum_run_age_hours": model.maximum_run_age_hours,
                    "model_id": model.model_id,
                    "resolution_km": _number(model.resolution_km),
                }
                for model in artifact.models
            ],
        ),
        "schema_version": artifact.schema_version,
        "segments": cast(
            list[JsonValue],
            [_segment_payload(segment) for segment in artifact.segments],
        ),
    }
    serialized = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return (serialized + "\n").encode()


def write_runtime_artifact(path: Path, artifact: RuntimeArtifact) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(export_runtime_artifact(artifact))


def _segment_payload(segment: RuntimeCalibrationSegment) -> dict[str, JsonValue]:
    selector = segment.selector
    return {
        "fallback_model": segment.evaluation.fallback_model,
        "holdout": {
            "accepted": True,
            "sample_count": segment.evaluation.sample_count,
        },
        "minimum_source_count": segment.minimum_source_count,
        "mode": "blend",
        "selector": {
            "maximum_lead_hours": selector.maximum_lead_hours,
            "minimum_lead_hours": selector.minimum_lead_hours,
            "months": cast(list[JsonValue], list(selector.months)),
            "region": selector.region,
            "variable": selector.variable,
        },
        "truth_class": segment.truth_class,
        "weights": {
            model_id: _number(weight)
            for model_id, weight in sorted(segment.fit.weights.items())
        },
    }


def _selector_key(selector: RuntimeSelector) -> tuple[str, str, int, int, tuple[int, ...]]:
    return (
        selector.region,
        selector.variable,
        selector.minimum_lead_hours,
        selector.maximum_lead_hours,
        selector.months,
    )


def _checksum(value: str, name: str) -> None:
    if len(value) != 64 or any(character not in "0123456789abcdef" for character in value):
        raise ValueError(f"{name} must be a lowercase SHA-256 digest")


def _utc(value: datetime, name: str) -> None:
    if value.tzinfo is None or value.utcoffset() != UTC.utcoffset(value):
        raise ValueError(f"{name} must be timezone-aware UTC")


def _iso(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def _number(value: float) -> int | float:
    if not isfinite(value):
        raise ValueError("runtime artifact number must be finite")
    return int(value) if value.is_integer() else value


MAX_ARTIFACT_VALIDITY = timedelta(days=90)
_SEASON_MONTHS = {
    "winter": (12, 1, 2),
    "spring": (3, 4, 5),
    "summer": (6, 7, 8),
    "autumn": (9, 10, 11),
}
_REGIONS = frozenset(
    {
        "CZECHIA",
        "EUROPE",
        "NORTH_AMERICA",
        "SOUTH_AMERICA",
        "AFRICA",
        "SOUTH_CENTRAL_ASIA",
        "EAST_ASIA",
        "NORTHERN_ASIA",
        "OCEANIA",
        "GLOBAL",
    }
)
_TRUTH_CLASSES = frozenset(
    {"station", "radar_gauge", "satellite_precipitation", "reanalysis"}
)
_RUNTIME_VARIABLES = {
    "temperature": "temperature_2m",
    "dew_point": "dew_point_2m",
    "sea_level_pressure": "pressure_msl",
    "wind_speed": "wind_speed_10m",
    "wind_direction": "wind_direction_10m",
    "precipitation": "precipitation",
    "cloud_cover": "cloud_cover",
}
