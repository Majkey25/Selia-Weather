"""Offline diagnostic replay of previously inspected temperature data; no weight export."""

from __future__ import annotations

import csv
import hashlib
import io
import json
from dataclasses import asdict
from datetime import date
from pathlib import Path
from statistics import mean, median

from aladin_ensemble.align import align_station_forecasts
from aladin_ensemble.baselines import ScalarForecastCase, evaluate_scalar_baselines
from aladin_ensemble.metrics import (
    block_bootstrap_mean_interval,
    mean_absolute_error,
    root_mean_square_error,
)
from aladin_ensemble.sources.noaa_isd import (
    parse_isd_observations,
    parse_isd_station_history,
    select_isd_station_cohort,
)
from aladin_ensemble.sources.open_meteo_runs import (
    CachedDownloader,
    ForecastPoint,
    PreviousRunsRequest,
    parse_previous_run_values,
)
from aladin_ensemble.types import ForecastValue
from aladin_ensemble.worldwide import WORLD_TARGETS, WORLD_VARIABLES

ROOT = Path(__file__).resolve().parents[2]
SOURCE_START = date(2025, 3, 13)
START = date(2025, 7, 11)
END = date(2025, 8, 24)
MODEL_IDS = ("icon_seamless", "ecmwf_ifs025", "gfs_seamless")
TARGET_IDS = {
    "frankfurt", "new-york", "sao-paulo", "nairobi", "delhi", "tokyo", "moscow", "sydney",
}


def main() -> None:
    history_path = ROOT / "research/data/raw/noaa-isd-history/isd-history-20250828.csv"
    history_checksum = hashlib.sha256(history_path.read_bytes()).hexdigest()
    assert history_checksum == "1994747ab4af1b97e63adb434b4d0d022f2daee76f0c144ea9ab46be2d906604"
    cohort_path = ROOT / (
        "research/output/worldwide-2025-rainshift-20260901/worldwide-input-registry.json"
    )
    cohort_checksum = hashlib.sha256(cohort_path.read_bytes()).hexdigest()
    assert cohort_checksum == "83d3b3373971bc2ef134b546a7619053ae037e2ea80bedaa8f868c9b2b67a93f"
    with history_path.open(encoding="utf-8-sig") as source:
        stations = parse_isd_station_history(source, required_start=SOURCE_START, required_end=END)
    selected = select_isd_station_cohort(
        tuple(target for target in WORLD_TARGETS if target.target_id in TARGET_IDS),
        stations, max_distance_km=250.0,
    )
    assert len(selected) == 8
    points = tuple(
        ForecastPoint(item.station.wigos_id, item.station.latitude, item.station.longitude)
        for item in selected
    )
    cache = CachedDownloader(ROOT / "research/data/raw/open-meteo-worldwide")
    forecasts: list[ForecastValue] = []
    hashes: dict[str, str] = {
        "NOAA_station_history": history_checksum,
        "cohort_registry": cohort_checksum,
    }
    for model_id in (*MODEL_IDS, "best_match"):
        request = PreviousRunsRequest(model_id, points, WORLD_VARIABLES, SOURCE_START, END, 1)
        cached = cache.cached_previous(request)
        if cached is None:
            raise ValueError(f"Offline cache missing for {model_id}; no download permitted")
        hashes[model_id] = cached.checksum_sha256
        forecasts.extend(
            item for item in parse_previous_run_values(
                cached.path.read_bytes(), request, sample_hours=(12,),
            ) if item.variable == "temperature" and START <= item.valid_time.date() <= END
        )

    truth_path = ROOT / (
        "research/data/raw/noaa-isd-worldwide/"
        "2215ba1811b9974dc8695f07048ba8620c2ad80aaf0bab4f00d28cc9f0c45adc.csv"
    )
    checksum = hashlib.sha256(truth_path.read_bytes()).hexdigest()
    assert checksum == truth_path.with_suffix(".sha256").read_text(encoding="ascii").strip()
    hashes["NOAA_ISD"] = checksum
    filtered = io.StringIO()
    with truth_path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        assert reader.fieldnames is not None
        writer = csv.DictWriter(filtered, fieldnames=reader.fieldnames)
        writer.writeheader()
        for row in reader:
            timestamp = row["DATE"]
            if (
                START.isoformat() <= timestamp[:10] <= END.isoformat()
                and timestamp[11:] == "12:00:00"
            ):
                writer.writerow(row)
    filtered.seek(0)
    observations = tuple(
        item for item in parse_isd_observations(filtered, checksum)
        if item.variable == "temperature_2m"
    )
    aligned = align_station_forecasts(tuple(forecasts), observations)
    by_station = {item.station.wigos_id: item for item in selected}
    grouped: dict[tuple[str, date], dict[str, float | None]] = {}
    truth: dict[tuple[str, date], float] = {}
    for item in aligned:
        assert item.forecast.valid_time > item.forecast.run_time
        assert (item.forecast.valid_time - item.forecast.run_time).total_seconds() == 86400
        if item.truth_value is None:
            continue
        key = item.station_id, item.forecast.valid_time.date()
        values = grouped.setdefault(key, {})
        assert item.forecast.model_id not in values
        values[item.forecast.model_id] = item.forecast.value
        assert key not in truth or truth[key] == item.truth_value
        truth[key] = item.truth_value
    cases = tuple(
        ScalarForecastCase(
            observed_date, "temperature", 24, by_station[station_id].target.region,
            by_station[station_id].elevation_band, "summer", truth[(station_id, observed_date)],
            {model_id: values.get(model_id) for model_id in MODEL_IDS}, values.get("best_match"),
        ) for (station_id, observed_date), values in sorted(grouped.items())
    )
    complete = tuple(case for case in cases if case.values_for(MODEL_IDS) is not None)
    assert 0 < len(complete) <= 360
    scores = evaluate_scalar_baselines(complete, MODEL_IDS, bootstrap_repetitions=200)
    pairs = tuple(case.values_for(MODEL_IDS) for case in complete)
    assert all(pair is not None for pair in pairs)
    predictions = {
        "best_match": tuple(pair[1] for pair in pairs if pair is not None),
        "median": tuple(median(pair[0]) for pair in pairs if pair is not None),
        "mean": tuple(mean(pair[0]) for pair in pairs if pair is not None),
    }
    observed = tuple(case.observation for case in complete)
    improvement = block_bootstrap_mean_interval(tuple(
        (case.forecast_date, abs(best - case.observation) - abs(blend - case.observation))
        for case, best, blend in zip(
            complete, predictions["best_match"], predictions["median"], strict=True,
        )
    ), repetitions=200)
    print(json.dumps({
        "status": "diagnostic_replay_not_new_holdout",
        "date_start": str(START), "date_end": str(END), "sample_utc_hour": 12,
        "lead_contract": (
            "Previous Runs previous_day1 nominal horizon; issue time not independently verified"
        ),
        "network_requests": 0, "matched_cases": len(cases), "complete_cases": len(complete),
        "maximum_grid_station_distance_km": max(item.distance_km for item in aligned),
        "source_sha256": hashes,
        "pooled_scores": {name: {
            "mae_c": mean_absolute_error(values, observed),
            "rmse_c": root_mean_square_error(values, observed),
        } for name, values in predictions.items()},
        "median_mae_improvement_vs_best_match_paired_date_bootstrap": asdict(improvement),
        "station_scores": [{
            "region": score.group.region, "n": score.sample_count,
            "baseline": score.baseline, "mae_c": round(score.mae, 5),
        } for score in scores],
    }, sort_keys=True, indent=2))


if __name__ == "__main__":
    main()
