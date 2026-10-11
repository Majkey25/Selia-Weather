from __future__ import annotations

import hashlib
import json
import math
from dataclasses import replace
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import cast
from urllib.request import Request

import pytest

from aladin_ensemble.align import DateRange
from aladin_ensemble.backtest import BacktestConfig, BacktestDataset, SegmentDataset, SegmentKey
from aladin_ensemble.baselines import ScalarForecastCase
from aladin_ensemble.evaluate import EvaluationFailure, HoldoutLock
from aladin_ensemble.export import ModelContract, export_artifact
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.run_backtest import (
    ScalarTraining,
    WindTraining,
    build_backtest_artifact,
    build_backtest_preflight,
    build_previous_requests,
    download_previous_forecasts,
    evaluate_precipitation_training,
    evaluate_scalar_training,
    evaluate_wind_vector_training,
    fit_precipitation_training,
    fit_scalar_segment,
    fit_scalar_training,
    fit_wind_vector_segment,
    fit_wind_vector_training,
    load_registry_model_ids,
    lock_backtest_dataset,
    monthly_truth_requests,
    preflight_payload,
    resume_locked_backtest,
    run_locked_backtest,
    sample_forecast_hours,
    select_training_fallback,
    usable_truth_observation,
)
from aladin_ensemble.sources.chmi_download import CZECH_TARGETS, SelectedStation
from aladin_ensemble.sources.chmi_station import Station
from aladin_ensemble.sources.open_meteo_runs import (
    CachedDownloader,
    ForecastPoint,
    HttpResponse,
    PreviousRunsRequest,
)
from aladin_ensemble.train import WeightFit
from aladin_ensemble.types import ForecastValue, Observation

TRAIN = DateRange(date(2026, 3, 31), date(2026, 6, 30))
HOLDOUT = DateRange(date(2026, 7, 1), date(2026, 7, 30))


def _case(day: date, truth: float, model_a: float, model_b: float) -> ScalarForecastCase:
    return ScalarForecastCase(
        day,
        "temperature",
        24,
        "REGION_PRAGUE",
        "low",
        "spring" if day.month < 6 else "summer",
        truth,
        {"model_a": model_a, "model_b": model_b},
        truth + 3.0,
    )


def _segment(*, degraded_holdout: bool = False) -> SegmentDataset:
    training = tuple(
        _case(TRAIN.start + timedelta(days=offset), 10.0, 11.0, 9.0)
        for offset in range(90)
    )
    holdout = tuple(
        _case(
            HOLDOUT.start + timedelta(days=offset),
            10.0,
            11.0,
            13.0 if degraded_holdout else 9.0,
        )
        for offset in range(30)
    )
    return SegmentDataset(
        SegmentKey("temperature", 24),
        training,
        holdout,
        ("model_a", "model_b"),
        {"model_a": 1.0, "model_b": 1.0},
    )


def _precipitation_segment(*, incomplete_holdout: bool = False) -> SegmentDataset:
    def precipitation_case(day: date, offset: int, station: int = 0) -> ScalarForecastCase:
        phase = offset % 3
        return ScalarForecastCase(
            day,
            "precipitation",
            24,
            "REGION_PRAGUE",
            "low",
            "spring" if day.month < 6 else "summer",
            2.0 if phase == 0 else 0.0,
            {
                "model_a": 2.0 if phase in {0, 1} else 0.0,
                "model_b": 2.0 if phase in {0, 2} else 0.0,
            },
            4.0,
            (f"station-{station}", datetime.combine(day, datetime.min.time(), UTC)),
        )

    training = tuple(
        precipitation_case(TRAIN.start + timedelta(days=offset), offset, station)
        for offset in range(90)
        for station in range(3)
    )
    holdout = tuple(
        precipitation_case(HOLDOUT.start + timedelta(days=offset), offset)
        for offset in range(30)
    )
    if incomplete_holdout:
        holdout = holdout[:-1]
    return SegmentDataset(
        SegmentKey("precipitation", 24),
        training,
        holdout,
        ("model_a", "model_b"),
        {"model_a": 1.0, "model_b": 1.0},
    )


def test_scalar_forward_score_does_not_learn_from_later_training() -> None:
    segment = _segment()
    original = fit_scalar_training(segment)
    changed = fit_scalar_training(
        replace(
            segment,
            training=tuple(
                replace(case, observation=15.0)
                if case.forecast_date >= date(2026, 6, 2)
                else case
                for case in segment.training
            ),
        )
    )

    assert original.fit != changed.fit
    assert math.isclose(original.fold_improvements[0], 1.0, abs_tol=1e-6)
    assert math.isclose(changed.fold_improvements[0], original.fold_improvements[0], abs_tol=1e-6)


def test_scalar_forward_models_do_not_depend_on_later_coverage() -> None:
    segment = _segment()
    original = fit_scalar_training(segment)
    changed = fit_scalar_training(
        replace(
            segment,
            eligible_models=("model_a",),
            coverage={"model_a": 1.0, "model_b": 2 / 3},
            training=tuple(
                replace(case, model_values={"model_a": 11.0, "model_b": None})
                if case.forecast_date >= date(2026, 6, 2)
                else case
                for case in segment.training
            ),
        )
    )

    assert original.fit != changed.fit
    assert math.isclose(changed.fold_improvements[0], original.fold_improvements[0], abs_tol=1e-6)


def test_scalar_forward_models_respect_configured_coverage_threshold() -> None:
    segment = _segment()
    training = fit_scalar_training(
        replace(
            segment,
            coverage_threshold=0.7,
            training=tuple(
                replace(case, model_values={"model_a": 11.0, "model_b": None})
                if case.forecast_date < TRAIN.start + timedelta(days=7)
                else case
                for case in segment.training
            ),
        )
    )

    assert math.isclose(training.fold_improvements[1], 1.0, abs_tol=1e-6)


def test_scalar_forward_validation_uses_missing_model_renormalization() -> None:
    segment = _segment()
    model_ids = ("model_a", "model_b", "model_c", "model_d")
    training = fit_scalar_training(
        replace(
            segment,
            eligible_models=model_ids,
            coverage=dict.fromkeys(model_ids, 1.0),
            training=tuple(
                replace(
                    case,
                    model_values={
                        "model_a": 9.0, "model_b": 9.0, "model_c": 11.0,
                        "model_d": None
                        if case.forecast_date == TRAIN.start + timedelta(days=31)
                        else 11.0,
                    },
                )
                for case in segment.training
            ),
        )
    )

    assert math.isclose(training.fold_improvements[0], 89 / 90, abs_tol=1e-6)


@pytest.mark.parametrize("lead_hours", [24, 168])
def test_scalar_forward_fit_purges_truth_not_available_at_validation_issue(
    lead_hours: int,
) -> None:
    segment = _segment()
    segment = replace(
        segment,
        key=SegmentKey("temperature", lead_hours),
        training=tuple(
            replace(
                case,
                lead_hours=lead_hours,
                observation_id=(
                    "station",
                    datetime.combine(case.forecast_date, datetime.min.time(), UTC),
                ),
            )
            for case in segment.training
        ),
    )
    original = fit_scalar_training(segment)
    unavailable_start = TRAIN.start + timedelta(days=30)
    validation_start = unavailable_start + timedelta(hours=lead_hours)
    changed = fit_scalar_training(
        replace(
            segment,
            training=tuple(
                replace(case, observation=15.0)
                if unavailable_start <= case.forecast_date < validation_start
                else case
                for case in segment.training
            ),
        )
    )

    assert original.fit != changed.fit
    assert math.isclose(changed.fold_improvements[0], original.fold_improvements[0], abs_tol=1e-6)


@pytest.mark.parametrize(("truth_hour", "available"), [(11, True), (12, False), (13, False)])
def test_forward_fit_uses_original_utc_truth_time(truth_hour: int, available: bool) -> None:
    segment = _segment()
    boundary_day = date(2026, 4, 30)
    segment = replace(
        segment,
        training=tuple(
            replace(
                case,
                observation_id=(
                    "station",
                    datetime(
                        case.forecast_date.year,
                        case.forecast_date.month,
                        case.forecast_date.day,
                        truth_hour if case.forecast_date == boundary_day else 12,
                        tzinfo=UTC,
                    ),
                ),
            )
            for case in segment.training
        ),
    )
    original = fit_scalar_training(segment)
    changed = fit_scalar_training(
        replace(
            segment,
            training=tuple(
                replace(case, observation=15.0) if case.forecast_date == boundary_day else case
                for case in segment.training
            ),
        )
    )

    # The first validation forecast is valid May 1 at noon, issued April 30 at noon.
    unchanged = math.isclose(
        changed.fold_improvements[0], original.fold_improvements[0], abs_tol=1e-6
    )
    assert unchanged is not available


def test_precipitation_forward_scores_do_not_learn_from_later_training() -> None:
    segment = _precipitation_segment()
    original = fit_precipitation_training(segment)
    changed = fit_precipitation_training(
        replace(
            segment,
            training=tuple(
                replace(case, observation=2.0)
                if case.forecast_date >= date(2026, 6, 2)
                else case
                for case in segment.training
            ),
        )
    )

    assert original.occurrence != changed.occurrence
    assert math.isclose(
        changed.occurrence_fold_improvements[0],
        original.occurrence_fold_improvements[0],
        abs_tol=1e-6,
    )
    assert math.isclose(
        changed.amount_fold_improvements[0], original.amount_fold_improvements[0], abs_tol=1e-6,
    )


def test_precipitation_forward_models_do_not_depend_on_later_coverage() -> None:
    segment = _precipitation_segment()
    original = fit_precipitation_training(segment)
    changed = fit_precipitation_training(
        replace(
            segment,
            eligible_models=("model_a",),
            coverage={"model_a": 1.0, "model_b": 2 / 3},
            training=tuple(
                replace(
                    case, model_values={"model_a": case.model_values["model_a"], "model_b": None}
                )
                if case.forecast_date >= date(2026, 6, 2)
                else case
                for case in segment.training
            ),
        )
    )

    assert math.isclose(
        changed.occurrence_fold_improvements[0], original.occurrence_fold_improvements[0],
        abs_tol=1e-6,
    )
    assert math.isclose(
        changed.amount_fold_improvements[0], original.amount_fold_improvements[0], abs_tol=1e-6,
    )


def test_precipitation_insufficient_prior_wet_truth_does_not_prove_stability() -> None:
    segment = _precipitation_segment()
    segment = replace(
        segment,
        training=tuple(
            case
            for case in segment.training
            if case.observation_id is not None and case.observation_id[0] == "station-0"
        ),
    )
    training = fit_precipitation_training(segment)

    assert training.amount is not None
    assert training.amount.sample_count == 30
    assert len(training.occurrence_fold_improvements) < 2
    assert training.amount_fold_improvements == ()
    fitted = evaluate_precipitation_training(
        segment,
        training,
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=200,
    )
    assert EvaluationFailure.UNSTABLE_FOLDS in fitted.amount_evaluation.rejection_reasons


def test_forward_fold_missing_past_selected_fallback_fails_closed() -> None:
    segment = _segment()
    segment = replace(
        segment,
        training=tuple(
            replace(
                case,
                best_match=case.observation if case.forecast_date < date(2026, 5, 2) else None,
            )
            for case in segment.training
        ),
    )
    training = fit_scalar_training(segment)

    assert training.fit is not None
    assert training.fallback_model == "model_a"
    assert training.fold_improvements[0] == 0.0
    fitted = evaluate_scalar_training(
        segment,
        training,
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=200,
    )
    assert EvaluationFailure.UNSTABLE_FOLDS in fitted.evaluation.rejection_reasons


def _lock() -> HoldoutLock:
    return HoldoutLock(TRAIN, HOLDOUT, "a" * 64, datetime(2026, 8, 1, tzinfo=UTC))


def _complete_dataset() -> BacktestDataset:
    scalar = _segment()
    precipitation = _precipitation_segment()
    wind_speed = _wind_segment("wind_speed", 10.0, 10.0, 10.0)
    wind_direction = _wind_segment("wind_direction", 350.0, 10.0, 0.0)
    station = Station("0-20000-0-11519", "Praha", 50.07, 14.43, 260.5)
    return BacktestDataset(
        BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b")),
        (SelectedStation(CZECH_TARGETS[0], station),),
        {
            scalar.key: scalar,
            precipitation.key: precipitation,
            wind_speed.key: wind_speed,
            wind_direction.key: wind_direction,
        },
    )


def test_direct_dataset_construction_rejects_unavailable_training_truth() -> None:
    segment = _segment()
    segment = replace(
        segment, training=(*segment.training, _case(TRAIN.end, 10.0, 11.0, 9.0))
    )
    station = Station("0-20000-0-11519", "Praha", 50.07, 14.43, 260.5)

    with pytest.raises(ValueError, match="holdout issuance"):
        BacktestDataset(
            BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b")),
            (SelectedStation(CZECH_TARGETS[0], station),),
            {segment.key: segment},
        )


def test_registry_loader_requires_complete_verified_models(tmp_path: Path) -> None:
    registry = tmp_path / "registry.json"
    registry.write_text(
        json.dumps(
            {
                "status": "complete",
                "models": [
                    {"model_id": "dwd_icon_eu", "verified": True},
                    {"model_id": "chmi_aladin_cz_1km", "verified": True},
                ],
            }
        ),
        encoding="utf-8",
    )

    assert load_registry_model_ids(registry) == (
        "chmi_aladin_cz_1km",
        "dwd_icon_eu",
    )
    registry.write_text('{"status":"incomplete","models":[]}', encoding="utf-8")
    with pytest.raises(ValueError, match="complete"):
        load_registry_model_ids(registry)


def test_previous_request_plan_batches_points_and_all_fixed_leads() -> None:
    station = Station("0-20000-0-11519", "Praha", 50.07, 14.43, 260.5)
    selected = (SelectedStation(CZECH_TARGETS[0], station),)

    requests, budget = build_previous_requests(
        ("chmi_aladin_cz_1km", "dwd_icon_eu"),
        selected,
        TRAIN.start,
        HOLDOUT.end,
        provider_limit=10_000,
    )

    assert len(requests) == 21
    assert {request.model_id for request in requests} == {
        "best_match",
        "chmi_aladin_cz_1km",
        "dwd_icon_eu",
    }
    assert {request.lead_days for request in requests} == set(range(1, 8))
    assert requests[0].points[0].point_id == station.wigos_id
    assert budget.expected_http_requests == 21
    budget.require_within_limit()


def test_preflight_blocks_while_holdout_month_can_still_change() -> None:
    station = Station("0-20000-0-11519", "Praha", 50.07, 14.43, 260.5)
    selected = (SelectedStation(CZECH_TARGETS[0], station),)
    config = BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b"))

    result = build_backtest_preflight(
        config,
        selected,
        now=datetime(2026, 7, 31, 12, tzinfo=UTC),
        provider_limit=10_000,
    )

    assert result.status == "blocked"
    assert result.reason == "holdout_month_incomplete"


def test_preflight_is_ready_after_month_boundary_with_deterministic_counts() -> None:
    station = Station("0-20000-0-11519", "Praha", 50.07, 14.43, 260.5)
    selected = (SelectedStation(CZECH_TARGETS[0], station),)
    config = BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b"))

    result = build_backtest_preflight(
        config,
        selected,
        now=datetime(2026, 8, 1, tzinfo=UTC),
        provider_limit=10_000,
    )

    assert preflight_payload(result) == {
        "forecast_requests": 21,
        "holdout": {"end": "2026-07-30", "start": "2026-07-01"},
        "model_count": 2,
        "reason": None,
        "station_count": 1,
        "status": "ready",
        "training": {"end": "2026-06-30", "start": "2026-03-31"},
        "truth_requests": 10,
    }


def test_dataset_lock_records_manifest_hash_and_refuses_overwrite(tmp_path: Path) -> None:
    segment = _segment()
    station = Station("0-20000-0-11519", "Praha", 50.07, 14.43, 260.5)
    selected = (SelectedStation(CZECH_TARGETS[0], station),)
    dataset = BacktestDataset(
        BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b")),
        selected,
        {segment.key: segment},
    )
    output = tmp_path / "locked"
    locked_at = datetime(2026, 8, 1, tzinfo=UTC)

    lock = lock_backtest_dataset(
        dataset,
        registry_hash="a" * 64,
        source_hashes={"forecast": "b" * 64, "truth": "c" * 64},
        output_dir=output,
        locked_at=locked_at,
    )

    assert lock.locked_at == locked_at
    assert lock.dataset_manifest_hash == hashlib.sha256(
        (output / "dataset-manifest.json").read_bytes()
    ).hexdigest()
    assert (output / "holdout-lock.json").is_file()
    with pytest.raises(ValueError, match="output_dir"):
        lock_backtest_dataset(
            dataset,
            registry_hash="a" * 64,
            source_hashes={"forecast": "b" * 64},
            output_dir=output,
            locked_at=locked_at,
        )


def test_locked_run_fits_before_lock_and_writes_diagnostic_report(tmp_path: Path) -> None:
    timestamps = iter(
        (
            datetime(2026, 7, 31, 23, 59, tzinfo=UTC),
            datetime(2026, 8, 1, tzinfo=UTC),
        )
    )

    result = run_locked_backtest(
        _complete_dataset(),
        registry_hash="a" * 64,
        source_hashes={"forecast": "b" * 64, "truth": "c" * 64},
        output_dir=tmp_path / "run",
        clock=lambda: next(timestamps),
        bootstrap_repetitions=50,
    )

    assert result.training.trained_at < result.lock.locked_at
    assert len(result.scalar) == 1
    assert len(result.wind) == 1
    assert len(result.precipitation) == 1
    report = json.loads((tmp_path / "run" / "report.json").read_text(encoding="utf-8"))
    assert report["status"] == "diagnostic"
    assert report["exported"] is False
    assert report["evaluation_summary"] == {
        "accepted": 4,
        "rejected": 0,
        "total": 4,
    }
    assert {row["kind"] for row in report["segments"]} == {
        "precipitation",
        "scalar",
        "wind_vector",
    }
    artifact = build_backtest_artifact(
        result,
        models=(ModelContract("model_a", 6, 1.0), ModelContract("model_b", 6, 1.0)),
        registry_status="complete",
        generated_at=datetime(2026, 8, 1, 0, 1, tzinfo=UTC),
    )
    assert len(artifact.segments) == 3
    assert b'"method":"zero_inflated"' in export_artifact(artifact)


def test_resume_keeps_existing_holdout_lock_and_writes_report(tmp_path: Path) -> None:
    dataset = _complete_dataset()
    output = tmp_path / "locked-run"
    lock_backtest_dataset(
        dataset,
        registry_hash="a" * 64,
        source_hashes={"forecast": "b" * 64, "truth": "c" * 64},
        output_dir=output,
        locked_at=datetime(2026, 8, 1, tzinfo=UTC),
    )
    lock_bytes = (output / "holdout-lock.json").read_bytes()

    result = resume_locked_backtest(
        dataset,
        registry_hash="a" * 64,
        source_hashes={"forecast": "b" * 64, "truth": "c" * 64},
        output_dir=output,
        bootstrap_repetitions=20,
    )

    assert result.lock.locked_at == datetime(2026, 8, 1, tzinfo=UTC)
    assert (output / "holdout-lock.json").read_bytes() == lock_bytes
    assert (output / "report.json").is_file()


def test_resume_rejects_changed_threshold_with_same_models_and_coverage(tmp_path: Path) -> None:
    original = _segment()
    segment = replace(
        original,
        coverage={"model_a": 1.0, "model_b": 83 / 90},
        training=tuple(
            replace(case, model_values={"model_a": 11.0, "model_b": None})
            if case.forecast_date < TRAIN.start + timedelta(days=7)
            else case
            for case in original.training
        ),
    )
    changed_segment = replace(segment, coverage_threshold=0.7)
    dataset = replace(_complete_dataset(), segments={segment.key: segment})
    changed = replace(dataset, segments={segment.key: changed_segment})
    assert segment.eligible_models == changed_segment.eligible_models
    assert segment.coverage == changed_segment.coverage
    assert (
        fit_scalar_training(segment).fold_improvements
        != fit_scalar_training(changed_segment).fold_improvements
    )
    output = tmp_path / "locked-run"
    lock_backtest_dataset(
        dataset,
        registry_hash="a" * 64,
        source_hashes={"forecast": "b" * 64, "truth": "c" * 64},
        output_dir=output,
        locked_at=datetime(2026, 8, 1, tzinfo=UTC),
    )
    manifest_bytes = (output / "dataset-manifest.json").read_bytes()
    lock_bytes = (output / "holdout-lock.json").read_bytes()

    with pytest.raises(ValueError, match="resume dataset does not match"):
        resume_locked_backtest(
            changed,
            registry_hash="a" * 64,
            source_hashes={"forecast": "b" * 64, "truth": "c" * 64},
            output_dir=output,
            bootstrap_repetitions=20,
        )

    assert (output / "dataset-manifest.json").read_bytes() == manifest_bytes
    assert (output / "holdout-lock.json").read_bytes() == lock_bytes
    assert not (output / "report.json").exists()


def test_resume_rejects_legacy_manifest_without_pinned_thresholds(tmp_path: Path) -> None:
    dataset = _complete_dataset()
    output = tmp_path / "legacy-run"
    lock_backtest_dataset(
        dataset,
        registry_hash="a" * 64,
        source_hashes={"forecast": "b" * 64, "truth": "c" * 64},
        output_dir=output,
        locked_at=datetime(2026, 8, 1, tzinfo=UTC),
    )
    manifest_path = output / "dataset-manifest.json"
    manifest = cast(dict[str, JsonValue], json.loads(manifest_path.read_text(encoding="utf-8")))
    manifest["schema_version"] = 1
    segments = manifest["segments"]
    assert isinstance(segments, list)
    for segment in segments:
        assert isinstance(segment, dict)
        segment.pop("coverage_threshold", None)
    manifest_path.write_bytes(
        (json.dumps(manifest, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")
    )
    lock_path = output / "holdout-lock.json"
    lock = cast(dict[str, JsonValue], json.loads(lock_path.read_text(encoding="utf-8")))
    lock["dataset_manifest_hash"] = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    lock_path.write_text(json.dumps(lock), encoding="utf-8")
    manifest_bytes = manifest_path.read_bytes()
    lock_bytes = lock_path.read_bytes()

    with pytest.raises(ValueError, match="legacy manifests cannot be resumed"):
        resume_locked_backtest(
            dataset,
            registry_hash="a" * 64,
            source_hashes={"forecast": "b" * 64, "truth": "c" * 64},
            output_dir=output,
            bootstrap_repetitions=20,
        )

    assert manifest_path.read_bytes() == manifest_bytes
    assert lock_path.read_bytes() == lock_bytes
    assert not (output / "report.json").exists()


def _wind_segment(variable: str, model_a: float, model_b: float, truth: float) -> SegmentDataset:
    def wind_case(day: date) -> ScalarForecastCase:
        return ScalarForecastCase(
            day,
            variable,
            24,
            "REGION_PRAGUE",
            "low",
            "spring" if day.month < 6 else "summer",
            truth,
            {"model_a": model_a, "model_b": model_b},
            None,
            observation_id=("station", datetime(day.year, day.month, day.day, tzinfo=UTC)),
        )

    training = tuple(wind_case(TRAIN.start + timedelta(days=offset)) for offset in range(90))
    holdout = tuple(wind_case(HOLDOUT.start + timedelta(days=offset)) for offset in range(30))
    return SegmentDataset(
        SegmentKey(variable, 24),
        training,
        holdout,
        ("model_a", "model_b"),
        {"model_a": 1.0, "model_b": 1.0},
    )


def test_previous_forecast_download_reuses_verified_cache(tmp_path: Path) -> None:
    request = PreviousRunsRequest(
        "model_a",
        (ForecastPoint("station", 50.07, 14.43),),
        ("temperature_2m",),
        date(2026, 7, 1),
        date(2026, 7, 1),
        1,
    )
    valid_time = int(datetime(2026, 7, 1, 12, tzinfo=UTC).timestamp())
    body = json.dumps(
        [
            {
                "latitude": 50.07,
                "longitude": 14.43,
                "elevation": 260.0,
                "hourly_units": {
                    "time": "unixtime",
                    "temperature_2m_previous_day1": "°C",
                },
                "hourly": {
                    "time": [valid_time],
                    "temperature_2m_previous_day1": [20.0],
                },
            }
        ]
    ).encode()
    calls: list[Request] = []

    def fetch(http_request: Request) -> HttpResponse:
        calls.append(http_request)
        return HttpResponse(200, {}, body)

    downloader = CachedDownloader(
        tmp_path,
        fetch=fetch,
        now=lambda: datetime(2026, 8, 28, tzinfo=UTC),
    )
    downloader.download_previous(request)
    delays: list[float] = []

    forecasts, hashes = download_previous_forecasts(
        (request,),
        downloader,
        pause_seconds=0.5,
        sleeper=delays.append,
    )

    assert len(calls) == 1
    assert delays == []
    assert len(forecasts) == 1
    assert len(hashes) == 1


def test_fallback_is_selected_from_training_even_when_holdout_prefers_other_model() -> None:
    training = tuple(
        _case(TRAIN.start + timedelta(days=offset), 10.0, 10.5, 12.0)
        for offset in range(90)
    )
    holdout = tuple(
        _case(HOLDOUT.start + timedelta(days=offset), 10.0, 11.0, 10.0)
        for offset in range(30)
    )
    segment = SegmentDataset(
        SegmentKey("temperature", 24),
        training,
        holdout,
        ("model_a", "model_b"),
        {"model_a": 1.0, "model_b": 1.0},
    )

    assert select_training_fallback(segment) == "model_a"


def test_fallback_includes_best_match_when_it_wins_on_training() -> None:
    training = tuple(
        replace(
            _case(TRAIN.start + timedelta(days=offset), 10.0, 10.5, 12.0),
            best_match=10.1,
        )
        for offset in range(90)
    )
    segment = SegmentDataset(
        SegmentKey("temperature", 24),
        training,
        training[:30],
        ("model_a", "model_b"),
        {"model_a": 1.0, "model_b": 1.0},
    )

    assert select_training_fallback(segment) == "best_match"


def test_scalar_fit_accepts_real_improvement_and_rejects_holdout_degradation() -> None:
    accepted = fit_scalar_segment(
        _segment(),
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )
    rejected = fit_scalar_segment(
        _segment(degraded_holdout=True),
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )

    assert accepted.evaluation.accepted
    assert accepted.fit is not None
    assert math.isclose(accepted.fit.weights["model_a"], 0.5, abs_tol=0.01)
    assert math.isclose(accepted.fit.weights["model_b"], 0.5, abs_tol=0.01)
    assert not rejected.evaluation.accepted
    assert rejected.export_fit is None


def test_scalar_training_does_not_read_holdout() -> None:
    assert fit_scalar_training(_segment()) == fit_scalar_training(
        _segment(degraded_holdout=True)
    )


def test_scalar_evaluation_matches_runtime_missing_model_renormalization() -> None:
    model_ids = ("model_a", "model_b", "model_c", "model_d")

    def case(day: date, *, missing_d: bool) -> ScalarForecastCase:
        return ScalarForecastCase(
            day,
            "temperature",
            24,
            "REGION_PRAGUE",
            "low",
            "summer",
            10.0,
            {
                "model_a": 9.0,
                "model_b": 10.0,
                "model_c": 11.0,
                "model_d": None if missing_d else 10.0,
            },
            12.0,
        )

    segment = SegmentDataset(
        SegmentKey("temperature", 24),
        tuple(case(TRAIN.start + timedelta(days=offset), missing_d=False) for offset in range(90)),
        tuple(case(HOLDOUT.start + timedelta(days=offset), missing_d=True) for offset in range(30)),
        model_ids,
        dict.fromkeys(model_ids, 1.0),
    )
    training = ScalarTraining(
        model_ids,
        "model_a",
        WeightFit(dict.fromkeys(model_ids, 0.25), 90, 0.1),
        frozenset(),
        (0.1, 0.1),
    )

    fitted = evaluate_scalar_training(
        segment,
        training,
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )

    assert fitted.evaluation.accepted
    assert fitted.evaluation.sample_count == 30


def test_scalar_training_falls_back_only_in_harmful_regions() -> None:
    def regional_cases(start: date, days: int) -> tuple[ScalarForecastCase, ...]:
        return tuple(
            case
            for offset in range(days)
            for case in (
                replace(
                    _case(start + timedelta(days=offset), 10.0, 11.0, 9.0),
                    region="REGION_GOOD",
                ),
                replace(
                    _case(start + timedelta(days=offset), 10.0, 10.0, 20.0),
                    region="REGION_HARMFUL",
                ),
            )
        )

    segment = SegmentDataset(
        SegmentKey("temperature", 24),
        regional_cases(TRAIN.start, 90),
        regional_cases(HOLDOUT.start, 30),
        ("model_a", "model_b"),
        {"model_a": 1.0, "model_b": 1.0},
    )
    training = fit_scalar_training(segment)

    assert training.fallback_regions == frozenset({"REGION_HARMFUL"})
    fitted = fit_scalar_segment(
        segment,
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )
    assert fitted.evaluation.accepted
    assert fitted.evaluation.maximum_region_degradation == 0.0


def test_precipitation_training_is_holdout_blind_and_evaluates_both_parts() -> None:
    training = fit_precipitation_training(_precipitation_segment())

    assert training == fit_precipitation_training(
        _precipitation_segment(incomplete_holdout=True)
    )
    fitted = evaluate_precipitation_training(
        _precipitation_segment(),
        training,
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )
    assert fitted.occurrence_evaluation.accepted
    assert fitted.amount_evaluation.accepted
    assert fitted.brier is not None
    assert fitted.occurrence_evaluation.best_model_score is not None
    assert fitted.brier.score < fitted.occurrence_evaluation.best_model_score
    assert fitted.thresholds[0][0] == 0.1
    assert fitted.thresholds[0][1].misses == 0
    assert fitted.thresholds[0][1].false_alarms == 0


def test_precipitation_training_rejects_negative_amounts() -> None:
    segment = _precipitation_segment()
    invalid_case = replace(segment.training[0], observation=-0.1)

    with pytest.raises(ValueError, match="non-negative"):
        fit_precipitation_training(
            replace(segment, training=(invalid_case, *segment.training[1:]))
        )


def test_precipitation_evaluation_rejects_blend_when_only_fallback_is_available() -> None:
    segment = _precipitation_segment()
    sparse_holdout = tuple(
        replace(case, model_values={"model_a": case.model_values["model_a"], "model_b": None})
        for case in segment.holdout
    )
    sparse = replace(segment, holdout=sparse_holdout)
    training = fit_precipitation_training(segment)

    fitted = evaluate_precipitation_training(
        sparse,
        training,
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )

    assert not fitted.accepted
    assert fitted.occurrence_evaluation.sample_count == 30
    assert fitted.occurrence_evaluation.blend_score == (
        fitted.occurrence_evaluation.best_model_score
    )
    assert EvaluationFailure.INSUFFICIENT_SOURCES in (
        fitted.occurrence_evaluation.rejection_reasons
    )


def test_precipitation_model_gaps_do_not_fake_missing_fallback() -> None:
    segment = _precipitation_segment()
    mixed = replace(
        segment,
        holdout=tuple(
            replace(
                case,
                model_values={
                    "model_a": case.model_values["model_a"],
                    "model_b": None if index % 2 else case.model_values["model_b"],
                },
            )
            for index, case in enumerate(segment.holdout)
        ),
    )
    training = fit_precipitation_training(segment)

    fitted = evaluate_precipitation_training(
        mixed,
        training,
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )

    assert EvaluationFailure.MISSING_FALLBACK not in (
        fitted.occurrence_evaluation.rejection_reasons
    )


def test_precipitation_evaluation_reports_unavailable_without_fake_scores() -> None:
    segment = _precipitation_segment()
    unavailable = replace(
        segment,
        holdout=tuple(
            replace(
                case,
                model_values={"model_a": None, "model_b": None},
                best_match=None,
            )
            for case in segment.holdout
        ),
    )
    training = fit_precipitation_training(segment)

    fitted = evaluate_precipitation_training(
        unavailable,
        training,
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )

    assert fitted.occurrence_evaluation.sample_count == 0
    assert fitted.occurrence_evaluation.blend_score is None
    assert fitted.occurrence_evaluation.best_model_score is None
    assert fitted.brier is None
    assert set(fitted.occurrence_evaluation.rejection_reasons) == {
        EvaluationFailure.INSUFFICIENT_HOLDOUT,
        EvaluationFailure.MISSING_FALLBACK,
        EvaluationFailure.INSUFFICIENT_SOURCES,
    }


def test_precipitation_training_protects_harmful_regions() -> None:
    base = _precipitation_segment()

    def model_a(case: ScalarForecastCase) -> float:
        value = case.model_values["model_a"]
        assert value is not None
        return value

    def regional(cases: tuple[ScalarForecastCase, ...]) -> tuple[ScalarForecastCase, ...]:
        return tuple(
            item
            for case in cases
            for item in (
                replace(case, region="REGION_GOOD"),
                replace(
                    case,
                    region="REGION_HARMFUL",
                    observation=model_a(case),
                ),
            )
        )

    segment = replace(
        base,
        training=regional(base.training),
        holdout=regional(base.holdout),
    )
    training = fit_precipitation_training(segment)

    assert "REGION_HARMFUL" in (
        training.occurrence_fallback_regions | training.amount_fallback_regions
    )
    fitted = evaluate_precipitation_training(
        segment,
        training,
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )
    occurrence_degradation = fitted.occurrence_evaluation.maximum_region_degradation
    amount_degradation = fitted.amount_evaluation.maximum_region_degradation
    assert occurrence_degradation is not None and occurrence_degradation <= 0.05
    assert amount_degradation is not None and amount_degradation <= 0.05


def test_wind_vector_segment_blends_across_north_and_passes_holdout() -> None:
    fitted = fit_wind_vector_segment(
        _wind_segment("wind_speed", 10.0, 10.0, 10.0),
        _wind_segment("wind_direction", 350.0, 10.0, 0.0),
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )

    assert fitted.fit is not None
    assert math.isclose(fitted.fit.weights["model_a"], 0.5, abs_tol=0.01)
    assert math.isclose(fitted.fit.weights["model_b"], 0.5, abs_tol=0.01)
    assert fitted.evaluation.accepted
    assert fitted.evaluation.metric == "vector_mae"


def test_wind_vector_segment_rejects_unpaired_speed_and_direction_cases() -> None:
    speed = _wind_segment("wind_speed", 10.0, 10.0, 10.0)
    direction = _wind_segment("wind_direction", 350.0, 10.0, 0.0)
    direction = replace(direction, holdout=direction.holdout[:-1])

    with pytest.raises(ValueError, match="paired"):
        fit_wind_vector_segment(
            speed,
            direction,
            _lock(),
            trained_at=datetime(2026, 7, 31, tzinfo=UTC),
            bootstrap_repetitions=50,
        )


def test_wind_pairing_rejects_unknown_case_identity() -> None:
    speed = _wind_segment("wind_speed", 10.0, 10.0, 10.0)
    direction = _wind_segment("wind_direction", 350.0, 10.0, 0.0)
    unknown = replace(speed.training[0], observation_id=None)

    with pytest.raises(ValueError, match="identity"):
        fit_wind_vector_training(replace(speed, training=(unknown, *speed.training[1:])), direction)


@pytest.mark.parametrize(
    ("station_id", "hour", "lead_hours"),
    (("other_station", 0, 24), ("station", 12, 24), ("station", 0, 12)),
)
def test_wind_pairing_rejects_different_station_time_or_lead(
    station_id: str, hour: int, lead_hours: int
) -> None:
    speed = _wind_segment("wind_speed", 10.0, 10.0, 10.0)
    direction = _wind_segment("wind_direction", 350.0, 10.0, 0.0)
    changed = replace(
        direction.training[0],
        observation_id=(station_id, datetime(2026, 3, 31, hour, tzinfo=UTC)),
        lead_hours=lead_hours,
    )

    with pytest.raises(ValueError, match="do not match"):
        fit_wind_vector_training(
            speed, replace(direction, training=(changed, *direction.training[1:]))
        )


def test_wind_pairing_rejects_duplicate_observation_identity() -> None:
    speed = _wind_segment("wind_speed", 10.0, 10.0, 10.0)
    direction = _wind_segment("wind_direction", 350.0, 10.0, 0.0)

    with pytest.raises(ValueError, match="duplicate"):
        fit_wind_vector_training(
            replace(speed, training=(*speed.training, speed.training[0])), direction
        )


def test_wind_training_requires_both_components_for_the_common_model_mask() -> None:
    speed = _wind_segment("wind_speed", 10.0, 10.0, 10.0)
    direction = _wind_segment("wind_direction", 350.0, 10.0, 0.0)
    incomplete = replace(direction.training[0], model_values={"model_a": 350.0, "model_b": None})

    training = fit_wind_vector_training(
        speed, replace(direction, training=(incomplete, *direction.training[1:]))
    )

    assert training.fit is not None
    assert training.fit.sample_count == 89


def test_wind_evaluation_matches_runtime_missing_model_renormalization() -> None:
    model_ids = ("model_a", "model_b", "model_c", "model_d")

    def segment(variable: str) -> SegmentDataset:
        def case(day: date, *, missing_d: bool) -> ScalarForecastCase:
            direction = variable == "wind_direction"
            return ScalarForecastCase(
                day,
                variable,
                24,
                "REGION_PRAGUE",
                "low",
                "summer",
                0.0 if direction else 10.0,
                {
                    "model_a": 350.0 if direction else 10.0,
                    "model_b": 0.0 if direction else 10.0,
                    "model_c": 10.0 if direction else 10.0,
                    "model_d": None if missing_d else (0.0 if direction else 10.0),
                },
                None,
                observation_id=("station", datetime(day.year, day.month, day.day, tzinfo=UTC)),
            )

        return SegmentDataset(
            SegmentKey(variable, 24),
            tuple(
                case(TRAIN.start + timedelta(days=offset), missing_d=False)
                for offset in range(90)
            ),
            tuple(
                case(HOLDOUT.start + timedelta(days=offset), missing_d=True)
                for offset in range(30)
            ),
            model_ids,
            dict.fromkeys(model_ids, 1.0),
        )

    training = WindTraining(
        model_ids,
        "model_a",
        WeightFit(dict.fromkeys(model_ids, 0.25), 90, 0.1),
        frozenset(),
        (0.1, 0.1),
    )

    fitted = evaluate_wind_vector_training(
        segment("wind_speed"),
        segment("wind_direction"),
        training,
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )

    assert fitted.evaluation.accepted
    assert fitted.evaluation.sample_count == 30


def test_wind_forward_score_does_not_learn_from_later_training() -> None:
    speed = _wind_segment("wind_speed", 10.0, 10.0, 10.0)
    direction = _wind_segment("wind_direction", 350.0, 10.0, 0.0)
    original = fit_wind_vector_training(speed, direction)
    changed = fit_wind_vector_training(
        speed,
        replace(
            direction,
            training=tuple(
                replace(case, observation=350.0)
                if case.forecast_date >= date(2026, 6, 2)
                else case
                for case in direction.training
            ),
        ),
    )

    assert original.fit != changed.fit
    assert math.isclose(changed.fold_improvements[0], original.fold_improvements[0], abs_tol=1e-6)


def test_wind_forward_models_do_not_depend_on_later_coverage() -> None:
    speed = _wind_segment("wind_speed", 10.0, 10.0, 10.0)
    direction = _wind_segment("wind_direction", 350.0, 10.0, 0.0)
    original = fit_wind_vector_training(speed, direction)
    changed = fit_wind_vector_training(
        speed,
        replace(
            direction,
            eligible_models=("model_a",),
            coverage={"model_a": 1.0, "model_b": 2 / 3},
            training=tuple(
                replace(case, model_values={"model_a": 350.0, "model_b": None})
                if case.forecast_date >= date(2026, 6, 2)
                else case
                for case in direction.training
            ),
        ),
    )

    assert original.fit != changed.fit
    assert math.isclose(changed.fold_improvements[0], original.fold_improvements[0], abs_tol=1e-6)


def test_wind_training_does_not_read_holdout() -> None:
    speed = _wind_segment("wind_speed", 10.0, 10.0, 10.0)
    direction = _wind_segment("wind_direction", 350.0, 10.0, 0.0)
    incomplete_holdout = replace(direction, holdout=direction.holdout[:-1])

    assert fit_wind_vector_training(speed, direction) == fit_wind_vector_training(
        speed, incomplete_holdout
    )


def test_wind_training_falls_back_only_in_harmful_regions() -> None:
    def regional_wind(variable: str, start: date, days: int) -> tuple[ScalarForecastCase, ...]:
        rows: list[ScalarForecastCase] = []
        for offset in range(days):
            day = start + timedelta(days=offset)
            if variable == "wind_speed":
                truth, good_values, harmful_values = 10.0, (10.0, 10.0), (10.0, 10.0)
            else:
                truth, good_values, harmful_values = 0.0, (350.0, 10.0), (0.0, 180.0)
            rows.extend(
                (
                    ScalarForecastCase(
                        day,
                        variable,
                        24,
                        "REGION_GOOD",
                        "low",
                        "summer",
                        truth,
                        {"model_a": good_values[0], "model_b": good_values[1]},
                        None,
                        observation_id=(
                            "good_station",
                            datetime(day.year, day.month, day.day, tzinfo=UTC),
                        ),
                    ),
                    ScalarForecastCase(
                        day,
                        variable,
                        24,
                        "REGION_HARMFUL",
                        "low",
                        "summer",
                        truth,
                        {"model_a": harmful_values[0], "model_b": harmful_values[1]},
                        None,
                        observation_id=(
                            "harmful_station",
                            datetime(day.year, day.month, day.day, tzinfo=UTC),
                        ),
                    ),
                )
            )
        return tuple(rows)

    def segment(variable: str) -> SegmentDataset:
        return SegmentDataset(
            SegmentKey(variable, 24),
            regional_wind(variable, TRAIN.start, 90),
            regional_wind(variable, HOLDOUT.start, 30),
            ("model_a", "model_b"),
            {"model_a": 1.0, "model_b": 1.0},
        )

    speed = segment("wind_speed")
    direction = segment("wind_direction")
    training = fit_wind_vector_training(speed, direction)

    assert training.fallback_regions == frozenset({"REGION_HARMFUL"})
    fitted = fit_wind_vector_segment(
        speed,
        direction,
        _lock(),
        trained_at=datetime(2026, 7, 31, tzinfo=UTC),
        bootstrap_repetitions=50,
    )
    assert fitted.evaluation.accepted
    assert fitted.evaluation.maximum_region_degradation == 0.0


def test_monthly_truth_plan_and_forecast_hour_sampling_are_bounded() -> None:
    station = Station("0-20000-0-11519", "Praha", 50.07, 14.43, 260.5)
    selected = (SelectedStation(CZECH_TARGETS[0], station),)
    requests = monthly_truth_requests(selected, TRAIN.start, HOLDOUT.end)
    values = tuple(
        ForecastValue(
            "model_a",
            datetime(2026, 6, 29, hour, tzinfo=UTC),
            datetime(2026, 7, 1, hour, tzinfo=UTC),
            station.latitude,
            station.longitude,
            station.elevation_m,
            "temperature",
            20.0,
            "°C",
            station.wigos_id,
        )
        for hour in (0, 6, 12, 18)
    )

    assert len(requests) == 10
    assert {request.cadence for request in requests} == {"10min", "1hour"}
    assert tuple(value.valid_time.hour for value in sample_forecast_hours(values, (0, 12))) == (
        0,
        12,
    )
    with pytest.raises(ValueError, match="sample_hours"):
        sample_forecast_hours(values, ())


def test_truth_filter_keeps_whole_hour_instants_and_hourly_precipitation_only() -> None:
    config = BacktestConfig(TRAIN, HOLDOUT, ("model_a",))
    instant = Observation(
        "CHMI_STATION",
        "station",
        datetime(2026, 4, 2, 12, tzinfo=UTC),
        50.0,
        14.0,
        250.0,
        "temperature_2m",
        15.0,
        "°C",
        quality=0,
    )
    ten_minute_rain = Observation(
        "CHMI_STATION",
        "station",
        instant.valid_time,
        50.0,
        14.0,
        250.0,
        "precipitation",
        0.2,
        "mm",
        interval=timedelta(minutes=10),
        accumulation="interval",
        quality=0,
    )
    hourly_rain = replace(ten_minute_rain, interval=timedelta(hours=1))

    assert usable_truth_observation(instant, "10min", config)
    assert not usable_truth_observation(
        replace(instant, valid_time=instant.valid_time + timedelta(minutes=10)),
        "10min",
        config,
    )
    assert not usable_truth_observation(ten_minute_rain, "10min", config)
    assert usable_truth_observation(hourly_rain, "1hour", config)
