from __future__ import annotations

import argparse
import json
from collections.abc import Sequence
from datetime import date
from pathlib import Path
from typing import cast

from aladin_ensemble.align import DateRange
from aladin_ensemble.backtest import BacktestConfig
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.sources.noaa_isd import (
    parse_isd_station_history,
    select_isd_station_cohort,
)
from aladin_ensemble.worldwide import (
    WORLD_MODEL_IDS,
    WORLD_TARGETS,
    build_worldwide_previous_requests,
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
    requests, budget = build_worldwide_previous_requests(
        selected,
        config.train.start,
        config.holdout.end,
        provider_limit=cast(int, arguments.provider_limit),
    )
    budget.require_within_limit()
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
        "truth_requests": 1,
    }
    print(json.dumps(payload, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
