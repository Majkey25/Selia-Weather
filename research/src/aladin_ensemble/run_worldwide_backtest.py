from __future__ import annotations

import argparse
import hashlib
import json
from collections.abc import Sequence
from datetime import date
from pathlib import Path
from typing import cast

from aladin_ensemble.align import DateRange
from aladin_ensemble.backtest import BacktestConfig, build_backtest_dataset
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.run_backtest import (
    download_previous_forecasts,
    resume_locked_backtest,
    run_locked_backtest,
)
from aladin_ensemble.sources.chmi_download import SelectedStation
from aladin_ensemble.sources.noaa_isd import (
    parse_isd_station_history,
    select_isd_station_cohort,
)
from aladin_ensemble.sources.open_meteo_runs import CachedDownloader
from aladin_ensemble.worldwide import (
    WORLD_MODEL_IDS,
    WORLD_SAMPLE_HOURS,
    WORLD_TARGETS,
    build_worldwide_previous_requests,
    build_worldwide_truth_requests,
    download_worldwide_truth,
)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Preflight a locked worldwide forecast backtest.")
    parser.add_argument("--station-history", type=Path, required=True)
    parser.add_argument("--train-start", type=date.fromisoformat, required=True)
    parser.add_argument("--train-end", type=date.fromisoformat, required=True)
    parser.add_argument("--holdout-start", type=date.fromisoformat, required=True)
    parser.add_argument("--holdout-end", type=date.fromisoformat, required=True)
    parser.add_argument("--provider-limit", type=int, default=10_000)
    parser.add_argument("--max-station-distance-km", type=float, default=250.0)
    parser.add_argument(
        "--region",
        choices=sorted({target.region for target in WORLD_TARGETS}),
    )
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--forecast-cache", type=Path)
    parser.add_argument("--truth-cache", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--pause-seconds", type=float, default=0.5)
    parser.add_argument("--retry-attempts", type=int, default=5)
    parser.add_argument("--retry-delay-seconds", type=float, default=30.0)
    parser.add_argument("--bootstrap-repetitions", type=int, default=1_000)
    arguments = parser.parse_args(argv)
    config = BacktestConfig(
        DateRange(cast(date, arguments.train_start), cast(date, arguments.train_end)),
        DateRange(cast(date, arguments.holdout_start), cast(date, arguments.holdout_end)),
        WORLD_MODEL_IDS,
    )
    station_history = cast(Path, arguments.station_history)
    with station_history.open(encoding="utf-8-sig") as source:
        stations = parse_isd_station_history(
            source,
            required_start=config.train.start,
            required_end=config.holdout.end,
        )
    selected = select_isd_station_cohort(
        WORLD_TARGETS,
        stations,
        max_distance_km=cast(float, arguments.max_station_distance_km),
    )
    region = cast(str | None, arguments.region)
    evaluation_stations = tuple(
        item for item in selected if region is None or item.target.region == region
    )
    requests, budget = build_worldwide_previous_requests(
        selected,
        config.train.start,
        config.holdout.end,
        provider_limit=cast(int, arguments.provider_limit),
    )
    budget.require_within_limit()
    truth_requests = build_worldwide_truth_requests(
        selected,
        config.train.start,
        config.holdout.end,
    )
    payload: dict[str, JsonValue] = {
        "forecast_requests": len(requests),
        "holdout": {
            "end": config.holdout.end.isoformat(),
            "start": config.holdout.start.isoformat(),
        },
        "model_count": len(config.model_ids),
        "station_count": len(selected),
        "status": "ready",
        "training": {
            "end": config.train.end.isoformat(),
            "start": config.train.start.isoformat(),
        },
        "truth_requests": len(truth_requests),
    }
    print(json.dumps(payload, sort_keys=True, separators=(",", ":")))
    if not cast(bool, arguments.execute):
        return 0
    output_dir = cast(Path | None, arguments.output_dir)
    if output_dir is None:
        raise ValueError("--output-dir is required with --execute")
    resume = cast(bool, arguments.resume)
    if output_dir.exists() and not resume:
        raise ValueError("output_dir already exists")
    bootstrap_repetitions = cast(int, arguments.bootstrap_repetitions)
    if bootstrap_repetitions <= 0:
        raise ValueError("bootstrap_repetitions must be positive")
    forecast_cache = cast(Path | None, arguments.forecast_cache)
    truth_cache = cast(Path | None, arguments.truth_cache)
    forecasts, forecast_hashes = download_previous_forecasts(
        requests,
        CachedDownloader(
            forecast_cache or station_history.parent / "data/raw/open-meteo-worldwide",
            retry_attempts=cast(int, arguments.retry_attempts),
            retry_delay_seconds=cast(float, arguments.retry_delay_seconds),
        ),
        sample_hours=WORLD_SAMPLE_HOURS,
        pause_seconds=cast(float, arguments.pause_seconds),
    )
    observations, truth_hashes = download_worldwide_truth(
        selected,
        config.train.start,
        config.holdout.end,
        truth_cache or station_history.parent / "data/raw/noaa-isd",
    )
    source_hashes = {
        "noaa-isd-history": hashlib.sha256(station_history.read_bytes()).hexdigest(),
        **forecast_hashes,
        **truth_hashes,
    }
    if len(source_hashes) != 1 + len(forecast_hashes) + len(truth_hashes):
        raise ValueError("worldwide source hash keys are duplicated")
    evaluation_station_ids = {
        item.station.wigos_id for item in evaluation_stations
    }
    evaluation_forecasts = tuple(
        item for item in forecasts if item.requested_point_id in evaluation_station_ids
    )
    evaluation_observations = tuple(
        item for item in observations if item.station_id in evaluation_station_ids
    )
    registry = _registry_bytes(evaluation_stations)
    dataset = build_backtest_dataset(
        config,
        evaluation_forecasts,
        evaluation_observations,
        evaluation_stations,
    )
    run = resume_locked_backtest if resume else run_locked_backtest
    result = run(
        dataset,
        registry_hash=hashlib.sha256(registry).hexdigest(),
        source_hashes=source_hashes,
        output_dir=output_dir,
        bootstrap_repetitions=bootstrap_repetitions,
    )
    (output_dir / "worldwide-input-registry.json").write_bytes(registry)
    print(
        json.dumps(
            {
                "dataset_manifest_hash": result.lock.dataset_manifest_hash,
                "report": str(output_dir / "report.json"),
                "status": "completed_diagnostic",
            },
            sort_keys=True,
            separators=(",", ":"),
        )
    )
    return 0


def _registry_bytes(stations: Sequence[SelectedStation]) -> bytes:
    payload: dict[str, JsonValue] = {
        "models": list(WORLD_MODEL_IDS),
        "schema_version": 1,
        "stations": [
            {
                "region": item.target.region,
                "station_id": item.station.wigos_id,
                "target_id": item.target.target_id,
            }
            for item in stations
        ],
        "status": "configured",
    }
    serialized = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return (serialized + "\n").encode()


if __name__ == "__main__":
    raise SystemExit(main())
