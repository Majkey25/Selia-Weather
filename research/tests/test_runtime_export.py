from __future__ import annotations

import json
from dataclasses import replace
from datetime import UTC, datetime
from typing import cast

import pytest

from aladin_ensemble.evaluate import SegmentEvaluation
from aladin_ensemble.export import ModelContract
from aladin_ensemble.fallback import SegmentSelector
from aladin_ensemble.metrics import ConfidenceInterval
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.runtime_export import (
    RuntimeCalibrationSegment,
    RuntimeSelector,
    TruthClass,
    build_runtime_artifact,
    export_runtime_artifact,
)
from aladin_ensemble.train import WeightFit


def test_runtime_artifact_matches_android_schema_two() -> None:
    artifact = build_runtime_artifact(
        dataset_manifest_hash="a" * 64,
        model_contract_hash="b" * 64,
        generated_at=datetime(2026, 8, 31, 20, tzinfo=UTC),
        expires_at=datetime(2026, 9, 7, 20, tzinfo=UTC),
        models=MODELS,
        segments=(SEGMENT,),
    )

    payload = cast(dict[str, JsonValue], json.loads(export_runtime_artifact(artifact)))

    assert payload["schema_version"] == 2
    assert payload["dataset_manifest_hash"] == "a" * 64
    assert payload["model_contract_hash"] == "b" * 64
    segments = cast(list[dict[str, JsonValue]], payload["segments"])
    assert segments[0]["truth_class"] == "station"
    assert segments[0]["weights"] == {"ecmwf_ifs025": 0.6, "gfs_seamless": 0.4}
    assert segments[0]["holdout"] == {"accepted": True, "sample_count": 30}
    selector = cast(dict[str, JsonValue], segments[0]["selector"])
    assert selector == {
        "maximum_lead_hours": 24,
        "minimum_lead_hours": 0,
        "months": [6, 7, 8],
        "region": "AFRICA",
        "variable": "temperature_2m",
    }


def test_runtime_artifact_rejects_unaccepted_or_mismatched_training() -> None:
    with pytest.raises(ValueError, match="holdout"):
        replace(SEGMENT, evaluation=replace(EVALUATION, accepted=False))
    with pytest.raises(ValueError, match="truth class"):
        replace(SEGMENT, truth_class=cast(TruthClass, "invalid"))

    wrong_identity = replace(
        SEGMENT,
        fit=WeightFit({"ncep_gfs_global": 0.4, "ecmwf_ifs025": 0.6}, 100, 0.5),
    )
    with pytest.raises(ValueError, match="unknown model"):
        build_runtime_artifact(
            dataset_manifest_hash="a" * 64,
            model_contract_hash="b" * 64,
            generated_at=datetime(2026, 8, 31, 20, tzinfo=UTC),
            expires_at=datetime(2026, 9, 7, 20, tzinfo=UTC),
            models=MODELS,
            segments=(wrong_identity,),
        )


EVALUATION = SegmentEvaluation(
    selector=SegmentSelector("temperature", "7-24h", "summer", None, "low"),
    metric="mae",
    fallback_model="gfs_seamless",
    sample_count=30,
    blend_score=1.0,
    best_model_score=1.5,
    improvement=ConfidenceInterval(0.5, 0.2, 0.8),
    maximum_region_degradation=0.0,
    fold_improvements=(0.2, 0.3),
    accepted=True,
    rejection_reasons=(),
)
MODELS = (
    ModelContract("ecmwf_ifs025", 12, 25.0),
    ModelContract("gfs_seamless", 12, 13.0),
)
SEGMENT = RuntimeCalibrationSegment(
    selector=RuntimeSelector(
        region="AFRICA",
        variable="temperature_2m",
        minimum_lead_hours=0,
        maximum_lead_hours=24,
        months=(6, 7, 8),
    ),
    evaluation=EVALUATION,
    fit=WeightFit({"ecmwf_ifs025": 0.6, "gfs_seamless": 0.4}, 100, 0.5),
    minimum_source_count=2,
    truth_class="station",
)
