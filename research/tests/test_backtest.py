from __future__ import annotations

from dataclasses import replace
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest

from aladin_ensemble.align import DateRange
from aladin_ensemble.backtest import (
    BacktestConfig,
    SegmentKey,
    build_backtest_dataset,
    write_dataset_manifest,
)
from aladin_ensemble.sources.chmi_download import CZECH_TARGETS, SelectedStation
from aladin_ensemble.sources.chmi_station import Station
from aladin_ensemble.types import ForecastValue, Observation

TRAIN = DateRange(date(2026, 4, 2), date(2026, 6, 30))
HOLDOUT = DateRange(date(2026, 7, 1), date(2026, 7, 30))
STATION = Station("0-20000-0-11519", "Praha, Karlov", 50.07, 14.43, 260.5)
SELECTED = (SelectedStation(CZECH_TARGETS[0], STATION),)


def _rows() -> tuple[tuple[ForecastValue, ...], tuple[Observation, ...]]:
    forecasts: list[ForecastValue] = []
    observations: list[Observation] = []
    for offset in range(120):
        valid_time = datetime(2026, 4, 2, tzinfo=UTC) + timedelta(days=offset)
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
                    valid_time - timedelta(days=1),
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

    assert config.training_days == 90
    assert config.holdout_days == 30
    with pytest.raises(ValueError, match="90"):
        BacktestConfig(DateRange(date(2026, 4, 3), TRAIN.end), HOLDOUT, ("model_a",))
    with pytest.raises(ValueError, match="30"):
        BacktestConfig(TRAIN, DateRange(HOLDOUT.start, date(2026, 7, 29)), ("model_a",))


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
    assert segment.coverage["model_b"] == pytest.approx(70 / 90)
    assert segment.training[0].best_match == 12.0
    assert segment.holdout[0].forecast_date == date(2026, 7, 1)


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
