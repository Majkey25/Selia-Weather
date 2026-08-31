from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest

from aladin_ensemble.align import DateRange
from aladin_ensemble.evaluate import (
    EvaluationSample,
    HoldoutLock,
    SegmentEvaluation,
    evaluate_segment,
)
from aladin_ensemble.export import (
    ExportSegment,
    ModelContract,
    build_artifact,
    export_artifact,
    load_model_contracts,
    write_artifact,
)
from aladin_ensemble.fallback import SegmentSelector
from aladin_ensemble.run_backtest import load_registry_model_ids
from aladin_ensemble.train import WeightFit

DATASET_HASH = "0123456789abcdef" * 4
LOCKED_AT = datetime(2026, 6, 1, tzinfo=UTC)


def _lock() -> HoldoutLock:
    return HoldoutLock(
        DateRange(date(2026, 1, 1), date(2026, 4, 1)),
        DateRange(date(2026, 4, 2), date(2026, 5, 1)),
        DATASET_HASH,
        LOCKED_AT,
    )


def _evaluation(*, accepted: bool = True) -> SegmentEvaluation:
    lead_bucket = "7-24h" if accepted else "25-72h"
    selector = SegmentSelector("temperature", lead_bucket, "spring", None, "low")
    if accepted:
        samples = tuple(
            EvaluationSample(
                date(2026, 4, 2) + timedelta(days=offset),
                "REGION_PRAGUE",
                1.0,
                2.0,
            )
            for offset in range(30)
        )
        folds = (1.0, 1.0)
    else:
        samples = tuple(
            EvaluationSample(
                date(2026, 4, 2) + timedelta(days=offset),
                "REGION_PRAGUE",
                2.0,
                1.0,
            )
            for offset in range(30)
        )
        folds = (-1.0, -1.0)
    return evaluate_segment(
        selector,
        samples,
        metric="mae",
        fallback_model="model_a",
        fold_improvements=folds,
        missing_fallback_ok=True,
        trained_at=datetime(2026, 5, 31, tzinfo=UTC),
        lock=_lock(),
        bootstrap_repetitions=20,
        seed=3,
    )


def test_export_is_deterministic_and_uses_fallback_for_rejected_segment() -> None:
    accepted = ExportSegment(
        _evaluation(),
        WeightFit({"model_b": 0.25, "model_a": 0.75}, 100, 0.5),
        minimum_source_count=2,
    )
    rejected = ExportSegment(_evaluation(accepted=False), None, minimum_source_count=1)
    artifact = build_artifact(
        lock=_lock(),
        generated_at=datetime(2026, 6, 2, tzinfo=UTC),
        registry_status="complete",
        models=(ModelContract("model_b", 6, 2.0), ModelContract("model_a", 3, 1.0)),
        segments=(rejected, accepted),
    )

    payload = export_artifact(artifact)

    assert payload == export_artifact(artifact)
    assert b'"mode":"blend"' in payload
    assert b'"mode":"fallback"' in payload
    assert b'"weights":{"model_a":0.75,"model_b":0.25}' in payload
    assert payload.endswith(b"\n")


def test_artifact_write_round_trips_exact_bytes(tmp_path: Path) -> None:
    artifact = build_artifact(
        lock=_lock(),
        generated_at=datetime(2026, 6, 2, tzinfo=UTC),
        registry_status="complete",
        models=(ModelContract("model_a", 3, 1.0),),
        segments=(ExportSegment(_evaluation(accepted=False), None, 1),),
    )
    path = tmp_path / "ensemble_weights.json"

    write_artifact(path, artifact)

    expected = (
        b'{"dataset_manifest_hash":"0123456789abcdef0123456789abcdef0123456789abcdef'
        b'0123456789abcdef","generated_at":"2026-06-02T00:00:00Z","models":'
        b'[{"maximum_run_age_hours":3,"model_id":"model_a","resolution_km":1.0}],'
        b'"schema_version":1,"segments":[{"fallback_model":"model_a","holdout":'
        b'{"accepted":false,"best_model_score":1.0,"blend_score":2.0,"improvement":'
        b'{"estimate":-1.0,"lower":-1.0,"upper":-1.0},"maximum_region_degradation":1.0,'
        b'"metric":"mae","rejection_reasons":["no_significant_improvement",'
        b'"region_degradation","unstable_folds"],"sample_count":30},'
        b'"minimum_source_count":1,"mode":"fallback","selector":{"elevation_band":'
        b'"low","lead_bucket":"25-72h","region":null,"season":"spring","variable":'
        b'"temperature"},"weights":{"model_a":1.0}}]}\n'
    )
    assert export_artifact(artifact) == expected
    assert path.read_bytes() == expected


def test_export_fails_closed_for_incomplete_registry_and_unknown_models() -> None:
    with pytest.raises(ValueError, match="registry"):
        build_artifact(
            lock=_lock(),
            generated_at=datetime(2026, 6, 2, tzinfo=UTC),
            registry_status="incomplete",
            models=(ModelContract("model_a", 3, 1.0),),
            segments=(ExportSegment(_evaluation(accepted=False), None, 1),),
        )
    with pytest.raises(ValueError, match="unknown model"):
        build_artifact(
            lock=_lock(),
            generated_at=datetime(2026, 6, 2, tzinfo=UTC),
            registry_status="complete",
            models=(ModelContract("model_a", 3, 1.0),),
            segments=(
                ExportSegment(
                    _evaluation(),
                    WeightFit({"model_b": 1.0}, 100, 0.5),
                    1,
                ),
            ),
        )


def test_segment_minimum_source_count_uses_only_positive_weights() -> None:
    with pytest.raises(ValueError, match="minimum_source_count"):
        ExportSegment(
            _evaluation(),
            WeightFit({"model_a": 1.0, "model_b": 0.0}, 100, 0.5),
            2,
        )
    with pytest.raises(ValueError, match="fallback"):
        ExportSegment(_evaluation(accepted=False), None, 2)


def test_model_contract_loader_requires_fresh_exact_audit(tmp_path: Path) -> None:
    path = tmp_path / "model-contracts.json"
    path.write_text(
        '{"checked_at":"2026-08-31","contracts":['
        '{"documentation_url":"https://open-meteo.com/en/docs/dwd-api",'
        '"model_id":"model_a","resolution_km":7.0,"update_frequency_hours":3}],'
        '"maximum_run_age_policy":"max(6,2*update_frequency_hours)",'
        '"schema_version":1,"status":"verified"}\n',
        encoding="utf-8",
    )

    assert load_model_contracts(
        path,
        expected_model_ids=("model_a",),
        today=date(2026, 8, 31),
    ) == (ModelContract("model_a", 6, 7.0),)
    with pytest.raises(ValueError, match="model IDs"):
        load_model_contracts(
            path,
            expected_model_ids=("model_b",),
            today=date(2026, 8, 31),
        )
    with pytest.raises(ValueError, match="stale"):
        load_model_contracts(
            path,
            expected_model_ids=("model_a",),
            today=date(2026, 12, 1),
        )


def test_checked_in_model_contracts_match_registry() -> None:
    research_root = Path(__file__).parents[1]
    model_ids = load_registry_model_ids(research_root / "model-registry.json")

    contracts = load_model_contracts(
        research_root / "model-contracts.json",
        expected_model_ids=model_ids,
        today=date(2026, 8, 31),
    )

    assert tuple(contract.model_id for contract in contracts) == model_ids
