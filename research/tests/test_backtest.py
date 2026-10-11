from __future__ import annotations

from dataclasses import replace
from datetime import UTC, date, datetime, timedelta
from math import isclose
from pathlib import Path

import pytest

from aladin_ensemble.align import DateRange
from aladin_ensemble.backtest import (
    BacktestConfig,
    SegmentKey,
    build_backtest_dataset,
    write_dataset_manifest,
)
from aladin_ensemble.run_backtest import fit_wind_vector_training
from aladin_ensemble.sources.chmi_download import CZECH_TARGETS, SelectedStation
from aladin_ensemble.sources.chmi_station import Station
from aladin_ensemble.types import ForecastValue, Observation

TRAIN = DateRange(date(2026, 4, 1), date(2026, 6, 30))
HOLDOUT = DateRange(date(2026, 7, 1), date(2026, 7, 30))
STATION = Station("0-20000-0-11519", "Praha, Karlov", 50.07, 14.43, 260.5)
SELECTED = (SelectedStation(CZECH_TARGETS[0], STATION),)


def _rows(lead_hours: int = 24) -> tuple[tuple[ForecastValue, ...], tuple[Observation, ...]]:
    forecasts: list[ForecastValue] = []
    observations: list[Observation] = []
    start = datetime(2026, 4, 2, tzinfo=UTC) - timedelta(hours=lead_hours)
    for offset in range((HOLDOUT.end - start.date()).days + 1):
        valid_time = start + timedelta(days=offset)
        truth = 10.0 + offset / 100.0
        observations.append(
            Observation(
                "CHMI_STATION",
                STATION.wigos_id,
                valid_time,
                STATION.latitude,
                STATION.longitude,
                STATION.elevation_m,
                "temperature_2m",
                truth,
                "°C",
                quality=0,
            )
        )
        for model_id, value in (
            ("model_a", truth + 1.0),
            ("model_b", None if offset < 20 else truth - 0.5),
            ("best_match", truth + 2.0),
        ):
            forecasts.append(
                ForecastValue(
                    model_id,
                    valid_time - timedelta(hours=lead_hours),
                    valid_time,
                    STATION.latitude,
                    STATION.longitude,
                    STATION.elevation_m,
                    "temperature",
                    value,
                    "°C",
                    STATION.wigos_id,
                )
            )
    return tuple(forecasts), tuple(observations)


def test_backtest_config_requires_locked_90_plus_30_day_ranges() -> None:
    config = BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b"))

    assert config.training_days == 91
    assert config.holdout_days == 30
    with pytest.raises(ValueError, match="90"):
        BacktestConfig(DateRange(date(2026, 4, 3), TRAIN.end), HOLDOUT, ("model_a",))
    with pytest.raises(ValueError, match="30"):
        BacktestConfig(TRAIN, DateRange(HOLDOUT.start, date(2026, 7, 29)), ("model_a",))


@pytest.mark.parametrize("lead_hours", [24, 168])
def test_dataset_requires_90_truth_days_before_first_holdout_issuance(lead_hours: int) -> None:
    forecasts, observations = _rows()
    training = DateRange(TRAIN.end - timedelta(days=89), TRAIN.end)
    forecasts = tuple(
        replace(row, run_time=row.valid_time - timedelta(hours=lead_hours))
        for row in forecasts
        if training.contains(row.valid_time.date()) or HOLDOUT.contains(row.valid_time.date())
    )

    with pytest.raises(ValueError, match="90 distinct safe training"):
        build_backtest_dataset(
            BacktestConfig(training, HOLDOUT, ("model_a", "model_b")),
            forecasts,
            observations,
            SELECTED,
        )


@pytest.mark.parametrize("lead_hours", [24, 168])
def test_dataset_purges_unavailable_truth_before_model_coverage(lead_hours: int) -> None:
    forecasts, observations = _rows(lead_hours)
    training = DateRange(forecasts[0].valid_time.date(), TRAIN.end)
    issued_at = datetime(2026, 7, 1, tzinfo=UTC) - timedelta(hours=lead_hours)
    changed = tuple(
        replace(row, value=None)
        if training.contains(row.valid_time.date()) and row.valid_time >= issued_at
        else row
        for row in forecasts
    )
    config = BacktestConfig(training, HOLDOUT, ("model_a", "model_b"))
    original = build_backtest_dataset(config, forecasts, observations, SELECTED)
    perturbed = build_backtest_dataset(config, changed, observations, SELECTED)
    segment = original.segments[SegmentKey("temperature", lead_hours)]

    assert len(segment.training) == 90
    assert segment.training == perturbed.segments[segment.key].training
    assert segment.coverage == perturbed.segments[segment.key].coverage
    assert all(
        case.observation_id is not None and case.observation_id[1] < issued_at
        for case in segment.training
    )


def test_dataset_splits_locked_dates_and_excludes_low_coverage_model() -> None:
    forecasts, observations = _rows()

    dataset = build_backtest_dataset(
        BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b")),
        forecasts,
        observations,
        SELECTED,
    )

    segment = dataset.segments[SegmentKey("temperature", 24)]
    assert len(segment.training) == 90
    assert len(segment.holdout) == 30
    assert segment.eligible_models == ("model_a",)
    assert segment.coverage["model_a"] == 1.0
    assert isclose(segment.coverage["model_b"], 70 / 90)
    assert segment.training[0].best_match == 12.0
    assert segment.holdout[0].forecast_date == date(2026, 7, 1)


def test_dataset_wind_pairing_preserves_station_and_original_utc_time() -> None:
    base_forecasts, base_observations = _rows()
    second_station = replace(STATION, wigos_id="second_station", longitude=14.44)
    samples = ((STATION, 0), (second_station, 0), (STATION, 12))
    forecasts = tuple(
        replace(
            row,
            requested_point_id=station.wigos_id,
            longitude=station.longitude,
            run_time=row.run_time + timedelta(hours=hour),
            valid_time=row.valid_time + timedelta(hours=hour),
            variable=variable,
            unit=unit,
        )
        for station, hour in samples
        for variable, unit in (("wind_speed", "km/h"), ("wind_direction", "°"))
        for row in base_forecasts
    )
    observations = tuple(
        replace(
            row,
            station_id=station.wigos_id,
            longitude=station.longitude,
            valid_time=row.valid_time + timedelta(hours=hour),
            variable=variable,
            unit=unit,
        )
        for station, hour in samples
        for variable, unit in (("wind_speed_10m", "km/h"), ("wind_direction_10m", "°"))
        for row in base_observations
    )
    dataset = build_backtest_dataset(
        BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b"), coverage_threshold=0.7),
        forecasts,
        observations,
        (*SELECTED, SelectedStation(CZECH_TARGETS[0], second_station)),
    )
    speed = dataset.segments[SegmentKey("wind_speed", 24)]
    direction = dataset.segments[SegmentKey("wind_direction", 24)]

    training = fit_wind_vector_training(
        speed, replace(direction, training=tuple(reversed(direction.training)))
    )

    assert len(speed.training) == 270
    assert speed.coverage_threshold == 0.7
    assert training.fit is not None
    assert training.fit.sample_count == 210
    assert {case.observation_id for case in speed.training} == {
        case.observation_id for case in direction.training
    }
    assert {case.observation_id for case in speed.training} == {
        (station.wigos_id, row.valid_time + timedelta(hours=hour))
        for station, hour in samples
        for row in base_observations
        if TRAIN.contains(row.valid_time.date())
        and row.valid_time.date() < HOLDOUT.start - timedelta(days=1)
    }


def test_dataset_manifest_is_deterministic_and_records_quality(tmp_path: Path) -> None:
    forecasts, observations = _rows()
    dataset = build_backtest_dataset(
        BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b")),
        forecasts,
        observations,
        SELECTED,
    )
    first = tmp_path / "first.json"
    second = tmp_path / "second.json"
    source_hashes = {"chmi": "a" * 64, "previous_runs": "b" * 64}

    first_hash = write_dataset_manifest(first, dataset, "c" * 64, source_hashes)
    second_hash = write_dataset_manifest(second, dataset, "c" * 64, source_hashes)

    assert first.read_bytes() == second.read_bytes()
    assert first_hash == second_hash
    text = first.read_text(encoding="utf-8")
    assert '"schema_version":2' in text
    assert '"coverage_threshold":0.9' in text
    assert '"eligible_models":["model_a"]' in text
    assert '"training_case_count":90' in text


def test_dataset_rejects_unknown_point_duplicate_model_and_missing_holdout_day() -> None:
    forecasts, observations = _rows()
    unknown = (replace(forecasts[0], requested_point_id="unknown"),)
    with pytest.raises(ValueError, match="unknown requested point"):
        build_backtest_dataset(
            BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b")),
            unknown,
            observations,
            SELECTED,
        )

    with pytest.raises(ValueError, match="duplicate forecast row"):
        build_backtest_dataset(
            BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b")),
            (*forecasts, forecasts[0]),
            observations,
            SELECTED,
        )

    incomplete_forecasts = tuple(
        item for item in forecasts if item.valid_time.date() != HOLDOUT.end
    )
    with pytest.raises(ValueError, match="30 distinct holdout"):
        build_backtest_dataset(
            BacktestConfig(TRAIN, HOLDOUT, ("model_a", "model_b")),
            incomplete_forecasts,
            observations,
            SELECTED,
        )
