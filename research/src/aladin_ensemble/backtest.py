from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import datetime
from math import isfinite
from pathlib import Path
from types import MappingProxyType

from aladin_ensemble.align import DateRange, align_station_forecasts
from aladin_ensemble.baselines import ScalarForecastCase
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.sources.chmi_download import SelectedStation
from aladin_ensemble.types import ForecastValue, Observation


@dataclass(frozen=True, slots=True)
class BacktestConfig:
    train: DateRange
    holdout: DateRange
    model_ids: tuple[str, ...]
    coverage_threshold: float = 0.9

    def __post_init__(self) -> None:
        if self.training_days < 90:
            raise ValueError("training range must contain at least 90 days")
        if self.holdout_days < 30:
            raise ValueError("holdout range must contain at least 30 days")
        if self.train.end >= self.holdout.start:
            raise ValueError("training and holdout ranges overlap")
        if not self.model_ids or len(set(self.model_ids)) != len(self.model_ids):
            raise ValueError("model_ids must be non-empty and unique")
        if any(not model_id for model_id in self.model_ids):
            raise ValueError("model_ids must be named")
        if not isfinite(self.coverage_threshold) or not 0 < self.coverage_threshold <= 1:
            raise ValueError("coverage_threshold must be from zero through one")

    @property
    def training_days(self) -> int:
        return (self.train.end - self.train.start).days + 1

    @property
    def holdout_days(self) -> int:
        return (self.holdout.end - self.holdout.start).days + 1


@dataclass(frozen=True, order=True, slots=True)
class SegmentKey:
    variable: str
    lead_hours: int

    def __post_init__(self) -> None:
        if not self.variable or self.lead_hours <= 0:
            raise ValueError("segment variable and positive lead are required")


@dataclass(frozen=True, slots=True)
class SegmentDataset:
    key: SegmentKey
    training: tuple[ScalarForecastCase, ...]
    holdout: tuple[ScalarForecastCase, ...]
    eligible_models: tuple[str, ...]
    coverage: Mapping[str, float]

    def __post_init__(self) -> None:
        if not self.training or not self.holdout or not self.eligible_models:
            raise ValueError("segment data and eligible models are required")
        copied = dict(self.coverage)
        if any(not 0 <= value <= 1 for value in copied.values()):
            raise ValueError("model coverage must be from zero through one")
        object.__setattr__(self, "coverage", MappingProxyType(copied))


@dataclass(frozen=True, slots=True)
class BacktestDataset:
    config: BacktestConfig
    stations: tuple[SelectedStation, ...]
    segments: Mapping[SegmentKey, SegmentDataset]

    def __post_init__(self) -> None:
        if not self.stations or not self.segments:
            raise ValueError("backtest stations and segments are required")
        object.__setattr__(self, "segments", MappingProxyType(dict(self.segments)))


@dataclass(slots=True)
class _CaseBuilder:
    valid_time: datetime
    variable: str
    lead_hours: int
    station: SelectedStation
    observation: float
    model_values: dict[str, float | None] = field(default_factory=dict[str, float | None])
    best_match: float | None = None


def build_backtest_dataset(
    config: BacktestConfig,
    forecasts: tuple[ForecastValue, ...],
    observations: tuple[Observation, ...],
    stations: Sequence[SelectedStation],
) -> BacktestDataset:
    station_by_id = {item.station.wigos_id: item for item in stations}
    if len(station_by_id) != len(stations):
        raise ValueError("selected stations must be unique")
    allowed_models = {*config.model_ids, "best_match"}
    for forecast in forecasts:
        if forecast.requested_point_id not in station_by_id:
            raise ValueError("forecast has unknown requested point")
        if forecast.model_id not in allowed_models:
            raise ValueError(f"forecast model is not configured: {forecast.model_id}")

    aligned = align_station_forecasts(forecasts, observations)
    builders: dict[tuple[str, datetime, str, int], _CaseBuilder] = {}
    for item in aligned:
        point_id = item.forecast.requested_point_id
        assert point_id is not None
        truth = item.truth_value
        if truth is None:
            continue
        lead_seconds = (item.forecast.valid_time - item.forecast.run_time).total_seconds()
        if lead_seconds <= 0 or lead_seconds % 3_600:
            raise ValueError("forecast lead must be a positive whole hour")
        lead_hours = int(lead_seconds // 3_600)
        key = (point_id, item.forecast.valid_time, item.forecast.variable, lead_hours)
        builder = builders.get(key)
        if builder is None:
            builder = _CaseBuilder(
                item.forecast.valid_time,
                item.forecast.variable,
                lead_hours,
                station_by_id[point_id],
                truth,
            )
            builders[key] = builder
        elif builder.observation != truth:
            raise ValueError("aligned truth differs across models")
        if item.forecast.model_id == "best_match":
            if builder.best_match is not None:
                raise ValueError("duplicate best-match value")
            builder.best_match = item.forecast.value
        else:
            if item.forecast.model_id in builder.model_values:
                raise ValueError("duplicate model value")
            builder.model_values[item.forecast.model_id] = item.forecast.value

    grouped: dict[SegmentKey, list[ScalarForecastCase]] = {}
    for key in sorted(builders):
        builder = builders[key]
        forecast_date = builder.valid_time.date()
        if not config.train.contains(forecast_date) and not config.holdout.contains(forecast_date):
            raise ValueError("forecast date is outside the configured ranges")
        case = ScalarForecastCase(
            forecast_date,
            builder.variable,
            builder.lead_hours,
            builder.station.target.region,
            builder.station.elevation_band,
            _season(builder.valid_time.month),
            builder.observation,
            {model_id: builder.model_values.get(model_id) for model_id in config.model_ids},
            builder.best_match,
        )
        grouped.setdefault(SegmentKey(case.variable, case.lead_hours), []).append(case)

    segments: dict[SegmentKey, SegmentDataset] = {}
    for key, cases in sorted(grouped.items()):
        training = tuple(case for case in cases if config.train.contains(case.forecast_date))
        holdout = tuple(case for case in cases if config.holdout.contains(case.forecast_date))
        if len({case.forecast_date for case in training}) < 90:
            raise ValueError(f"segment {key} has fewer than 90 distinct training days")
        if len({case.forecast_date for case in holdout}) < 30:
            raise ValueError(f"segment {key} has fewer than 30 distinct holdout days")
        coverage = {
            model_id: sum(case.model_values[model_id] is not None for case in training)
            / len(training)
            for model_id in config.model_ids
        }
        eligible = tuple(
            model_id
            for model_id in config.model_ids
            if coverage[model_id] >= config.coverage_threshold
        )
        if not eligible:
            raise ValueError(f"segment {key} has no model above the coverage threshold")
        segments[key] = SegmentDataset(key, training, holdout, eligible, coverage)
    return BacktestDataset(config, tuple(stations), segments)


def write_dataset_manifest(
    path: Path,
    dataset: BacktestDataset,
    registry_hash: str,
    source_hashes: Mapping[str, str],
) -> str:
    _checksum(registry_hash, "registry_hash")
    if not source_hashes:
        raise ValueError("source_hashes must be non-empty")
    for name, checksum in source_hashes.items():
        if not name:
            raise ValueError("source hash name is required")
        _checksum(checksum, name)
    payload: dict[str, JsonValue] = {
        "holdout": {
            "end": dataset.config.holdout.end.isoformat(),
            "start": dataset.config.holdout.start.isoformat(),
        },
        "models": list(dataset.config.model_ids),
        "registry_hash": registry_hash,
        "schema_version": 1,
        "segments": [
            {
                "coverage": {
                    model_id: round(segment.coverage[model_id], 12)
                    for model_id in dataset.config.model_ids
                },
                "eligible_models": list(segment.eligible_models),
                "holdout_case_count": len(segment.holdout),
                "lead_hours": key.lead_hours,
                "training_case_count": len(segment.training),
                "variable": key.variable,
            }
            for key, segment in sorted(dataset.segments.items())
        ],
        "source_hashes": dict(sorted(source_hashes.items())),
        "stations": [
            {
                "elevation_m": item.station.elevation_m,
                "region": item.target.region,
                "target_id": item.target.target_id,
                "wigos_id": item.station.wigos_id,
            }
            for item in sorted(dataset.stations, key=lambda value: value.target.target_id)
        ],
        "training": {
            "end": dataset.config.train.end.isoformat(),
            "start": dataset.config.train.start.isoformat(),
        },
    }
    content = (
        json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
    ).encode()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return hashlib.sha256(content).hexdigest()


def _season(month: int) -> str:
    if month in {12, 1, 2}:
        return "winter"
    if month in {3, 4, 5}:
        return "spring"
    if month in {6, 7, 8}:
        return "summer"
    return "autumn"


def _checksum(value: str, name: str) -> None:
    if len(value) != 64 or any(character not in "0123456789abcdef" for character in value):
        raise ValueError(f"{name} must be a lowercase SHA-256 digest")
