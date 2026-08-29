from __future__ import annotations

import json
import math
from dataclasses import replace
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from urllib.request import Request

import pytest

from aladin_ensemble.align import DateRange
from aladin_ensemble.backtest import BacktestConfig, SegmentDataset, SegmentKey
from aladin_ensemble.baselines import ScalarForecastCase
from aladin_ensemble.evaluate import HoldoutLock
from aladin_ensemble.run_backtest import (
    build_previous_requests,
    download_previous_forecasts,
    fit_scalar_segment,
    fit_wind_vector_segment,
    load_registry_model_ids,
    monthly_truth_requests,
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
from aladin_ensemble.types import ForecastValue, Observation

TRAIN = DateRange(date(2026, 4, 2), date(2026, 6, 30))
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


def _lock() -> HoldoutLock:
    return HoldoutLock(TRAIN, HOLDOUT, "a" * 64, datetime(2026, 8, 1, tzinfo=UTC))


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

    assert len(requests) == 8
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
