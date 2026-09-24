"""Audit one training-selected shrinkage candidate on the already explored January corpus."""

from __future__ import annotations

import argparse
import json
from datetime import UTC, date, datetime
from hashlib import sha256
from pathlib import Path
from statistics import fmean
from typing import cast

from station_temperature_validation_2026 import (
    END,
    HOLDOUT_START,
    MODELS,
    ROOT,
    START,
    TRAIN_END,
    load_inputs,
    model_value,
    prediction,
)

from aladin_ensemble.align import DateRange
from aladin_ensemble.backtest import BacktestConfig, SegmentDataset, build_backtest_dataset
from aladin_ensemble.local_shrinkage import fit_local_shrinkage
from aladin_ensemble.metrics import mean_absolute_error
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.run_backtest import fit_scalar_training
from aladin_ensemble.train import blend_scalar

WINDOWS = (
    DateRange(date(2025, 12, 1), date(2025, 12, 15)),
    DateRange(date(2025, 12, 16), date(2025, 12, 31)),
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    output = cast(Path, parser.parse_args().output_dir)
    if output.exists():
        raise ValueError("Use a new output directory; prior evidence is immutable")
    selected, truth, forecasts, hashes = load_inputs()
    protocol: dict[str, JsonValue] = {
        "status": "exploratory candidate, already explored January evaluation",
        "frozen_at": datetime.now(UTC).isoformat(),
        "exported": False,
        "local_fractions": [0, 0.5, 1],
        "selection_metric": "forward validation MAE",
        "tie_break": "smaller local fraction",
        "minimum_prefix_dates": 90,
        "validation_windows": [
            [window.start.isoformat(), window.end.isoformat()] for window in WINDOWS
        ],
        "training": [START.isoformat(), TRAIN_END.isoformat()],
        "evaluation": [HOLDOUT_START.isoformat(), END.isoformat()],
        "source_hashes": dict(hashes),
        "code_hashes": {
            name: sha256((ROOT / name).read_bytes()).hexdigest()
            for name in (
                "research/src/aladin_ensemble/local_shrinkage.py",
                "research/scripts/local_shrinkage_validation_2026.py",
            )
        },
    }
    output.mkdir(parents=True)
    with (output / "protocol.json").open("x", encoding="utf-8") as target:
        json.dump(protocol, target, indent=2, sort_keys=True)
    config = BacktestConfig(DateRange(START, TRAIN_END), DateRange(HOLDOUT_START, END), MODELS)
    pooled = build_backtest_dataset(config, forecasts, truth, selected)
    regional = fit_scalar_training(next(iter(pooled.segments.values())))
    segments: dict[str, SegmentDataset] = {}
    for item in selected:
        station = item.station.wigos_id
        dataset = build_backtest_dataset(
            config,
            tuple(row for row in forecasts if row.requested_point_id == station),
            tuple(row for row in truth if row.station_id == station),
            (item,),
        )
        segments[item.target.target_id] = next(iter(dataset.segments.values()))
    candidates = fit_local_shrinkage(
        {location: segment.training for location, segment in segments.items()},
        MODELS,
        WINDOWS,
    )
    selection: dict[str, JsonValue] = {
        location: {
            "local_fraction": candidate.local_fraction,
            "forward_validation_dates": candidate.validation_dates,
            "forward_validation_mae": {
                str(fraction): score for fraction, score in candidate.validation_mae.items()
            },
            "final_weights": dict(candidate.fit.weights),
        }
        for location, candidate in candidates.items()
    }
    with (output / "training-selection.json").open("x", encoding="utf-8") as target:
        json.dump(selection, target, indent=2, sort_keys=True)
    scores: dict[str, JsonValue] = {}
    aggregate: dict[str, list[float]] = {}
    for location, segment in segments.items():
        local = fit_scalar_training(segment)
        actual = tuple(case.observation for case in segment.holdout)
        predictions = {
            "regional": tuple(prediction(case, regional) for case in segment.holdout),
            "local": tuple(prediction(case, local) for case in segment.holdout),
            "candidate": tuple(
                blend_scalar(
                    candidates[location].fit, {model: model_value(case, model) for model in MODELS}
                )
                for case in segment.holdout
            ),
            "training_selected_fallback": tuple(
                model_value(case, local.fallback_model) for case in segment.holdout
            ),
        }
        scores[location] = {
            "sample_count": len(actual),
            "fallback_model": local.fallback_model,
            "mae": {
                name: mean_absolute_error(values, actual) for name, values in predictions.items()
            },
        }
        for name, values in predictions.items():
            aggregate.setdefault(name, []).extend(
                abs(value - observation) for value, observation in zip(values, actual, strict=True)
            )
    report: dict[str, JsonValue] = {
        "status": "exploratory audit on reused evaluation data",
        "exported": False,
        "production_eligible": False,
        "new_untouched_holdout": False,
        "blockers": [
            "January outcomes were already explored",
            "previous_day1 nominal 24h; original initialization unverified",
            "three airport sites; no unseen-location or other-lead validation",
            "runtime schema has regional selectors, no station-specific applicability",
        ],
        "selection": selection,
        "stations": scores,
        "pooled_mae": {name: fmean(values) for name, values in aggregate.items()},
        "pooled_case_count": len(aggregate["candidate"]),
    }
    with (output / "comparison.json").open("x", encoding="utf-8") as target:
        json.dump(report, target, indent=2, sort_keys=True)
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
