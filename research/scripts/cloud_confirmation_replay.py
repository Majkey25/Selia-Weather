"""Reconstruct the inspected September cloud study from frozen raw inputs, offline only."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from io import StringIO
from pathlib import Path
from statistics import fmean, median
from typing import TypedDict, cast

from aladin_ensemble.align import align_station_forecasts
from aladin_ensemble.metrics import (
    block_bootstrap_mean_interval,
    mean_absolute_error,
    root_mean_square_error,
)
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.sources.chmi_station import Station, parse_cloud_observations
from aladin_ensemble.sources.open_meteo_runs import (
    PREVIOUS_RUNS_URL,
    CachedDownloader,
    PreviousRunsRequest,
    build_previous_runs_batch_url,
    parse_previous_run_values,
)
from aladin_ensemble.types import ForecastPoint, ForecastValue, Observation

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_STUDY = ROOT / ".reference/tmp/cloud-validation-20261013"
REPORT_SHA256 = "2630af5c603458e60445bddcaee41b0e33566a4b69890d37b70c73cf635f1660"
START, END = date(2026, 9, 1), date(2026, 9, 30)
GLOBAL_MODELS = (
    "icon_seamless", "ecmwf_ifs025", "ecmwf_aifs025_single", "gfs_seamless",
    "gem_seamless", "meteofrance_seamless", "ukmo_seamless", "cma_grapes_global",
    "jma_seamless", "bom_access_global",
)
REGIONAL_MODELS = ("chmi_aladin_cz_1km", "chmi_aladin_seamless")
BENCHMARKS = ("best_match", "global_median", "native_aladin", "seamless_aladin")
STATIONS = (
    Station("0-20000-0-11520", "Praha Libuš", 50.007778, 14.446944, 302.04),
    Station("0-20000-0-11774", "Holešov", 49.320556, 17.57, 222.25),
    Station("0-20000-0-11747", "Prostějov", 49.4525, 17.134722, 214.75),
)
TRUTH_FILES = (
    "chmi-recent-libus-202609.json", "chmi-recent-holesov-202609.json",
    "chmi-recent-prostejov-202609.json",
)


@dataclass(frozen=True, slots=True)
class CloudCase:
    station_id: str
    valid_time: datetime
    lead_days: int
    observed: float
    predictions: dict[str, float]


class Score(TypedDict):
    cases: int
    days: int
    mae_pp: float
    rmse_pp: float
    bias_pp: float
    false_clear: int
    station_mae_pp: dict[str, float]


class Difference(TypedDict):
    difference_pp: float
    daily_lower: float
    daily_upper: float
    weekly_lower: float
    weekly_upper: float


def _object(value: JsonValue) -> dict[str, JsonValue]:
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON object")
    return value


def _records(value: JsonValue) -> tuple[dict[str, JsonValue], ...]:
    if not isinstance(value, list):
        raise ValueError("Expected a JSON array")
    return tuple(_object(row) for row in value)


def _verified_bytes(path: Path, checksum: JsonValue) -> bytes:
    raw = path.read_bytes()
    if not isinstance(checksum, str) or hashlib.sha256(raw).hexdigest() != checksum:
        raise ValueError(f"Frozen source checksum mismatch: {path}")
    return raw


def _forecasts(study: Path, original: dict[str, JsonValue]) -> tuple[ForecastValue, ...]:
    records = _records(original["request_manifests"])
    if len(records) != 26:
        raise ValueError("Frozen study must contain exactly 26 forecast requests")
    cache = CachedDownloader(study / "confirmation-forecasts")
    points = tuple(ForecastPoint(s.wigos_id, s.latitude, s.longitude) for s in STATIONS)
    expected_times = tuple(
        datetime(2026, 9, 1, tzinfo=UTC) + timedelta(hours=hour) for hour in range(720)
    )
    forecasts: list[ForecastValue] = []
    for model in (*GLOBAL_MODELS, "best_match", *REGIONAL_MODELS):
        for lead in (1, 2):
            matching = [r for r in records if r["model_id"] == model and r["lead_days"] == lead]
            if len(matching) != 1:
                raise ValueError(f"Missing or duplicate frozen request: {model}:{lead}")
            record = matching[0]
            request = PreviousRunsRequest(model, points, ("cloud_cover",), START, END, lead)
            url, parameters = build_previous_runs_batch_url(request)
            if record["source_url"] != url or record["failure"] is not None:
                raise ValueError(f"Frozen request identity mismatch: {model}:{lead}")
            cached = cache.cached_previous(request)
            if cached is None:
                raise ValueError(f"Offline cache missing: {model}:{lead}; no download permitted")
            manifest = _object(cast(JsonValue, json.loads(cached.manifest_path.read_bytes())))
            if (
                manifest.get("request_endpoint") != PREVIOUS_RUNS_URL
                or manifest.get("request_parameters") != [list(pair) for pair in parameters]
                or cached.checksum_sha256 != record["checksum_sha256"]
            ):
                raise ValueError(f"Cache provenance mismatch: {model}:{lead}")
            raw = _verified_bytes(cached.path, record["checksum_sha256"])
            response = _records(cast(JsonValue, json.loads(raw)))
            field = f"cloud_cover_previous_day{lead}"
            if any(
                row.get("utc_offset_seconds") != 0
                or _object(row["hourly_units"]).get(field) != "%" for row in response
            ):
                raise ValueError(f"Forecast UTC/percent contract mismatch: {model}:{lead}")
            # The pinned September bytes contain no invalid cells; no masking is needed.
            if record["invalid_cloud_cells"] != []:
                raise ValueError("This replay requires the frozen September cloud-cell contract")
            parsed = parse_previous_run_values(raw, request)
            grids: list[dict[str, JsonValue]] = []
            coverage: dict[str, JsonValue] = {}
            for point in points:
                values = tuple(item for item in parsed if item.requested_point_id == point.point_id)
                if tuple(item.valid_time for item in values) != expected_times or any(
                    item.model_id != model or item.variable != "cloud_cover" or item.unit != "1"
                    or item.valid_time - item.run_time != timedelta(days=lead) for item in values
                ):
                    raise ValueError(f"Forecast point/time/lead identity mismatch: {model}:{lead}")
                first = values[0]
                grids.append({
                    "station_id": point.point_id, "latitude": first.latitude,
                    "longitude": first.longitude, "elevation": first.elevation_m,
                })
                count = sum(item.value is not None for item in values)
                if count:
                    coverage[point.point_id] = count
            if (
                len(parsed) != len(points) * len(expected_times)
                or grids != record["returned_grids"]
                or coverage != _object(original["forecast_coverage"])[f"{model}:{lead}"]
            ):
                raise ValueError(f"Forecast grid/coverage mismatch: {model}:{lead}")
            forecasts.extend(parsed)
    return tuple(forecasts)


def _observations(study: Path, original: dict[str, JsonValue]) -> tuple[Observation, ...]:
    records = _records(original["truth_coverage"])
    if len(records) != len(STATIONS):
        raise ValueError("Frozen study must contain exactly three truth sources")
    observations: list[Observation] = []
    for station, filename in zip(STATIONS, TRUTH_FILES, strict=True):
        matching = [row for row in records if row["station_id"] == station.wigos_id]
        if len(matching) != 1:
            raise ValueError(f"Missing or duplicate truth source: {station.wigos_id}")
        record = matching[0]
        expected_url = (
            "https://opendata.chmi.cz/meteorology/climate/recent/data/1hour/09/"
            f"1h-{station.wigos_id}-202609.json"
        )
        if record["source_url"] != expected_url:
            raise ValueError(f"Truth source identity mismatch: {station.wigos_id}")
        checksum = record["checksum_sha256"]
        raw = _verified_bytes(study / filename, checksum)
        if not isinstance(checksum, str):
            raise ValueError("Truth checksum must be text")
        parsed = parse_cloud_observations(StringIO(raw.decode("utf-8")), station, checksum)
        if (
            any(not START <= item.valid_time.date() <= END for item in parsed)
            or len(parsed) != record["accepted_rows"]
            or len({item.valid_time.date() for item in parsed}) != record["accepted_days"]
        ):
            raise ValueError(f"Truth date/coverage mismatch: {station.wigos_id}")
        observations.extend(parsed)
    return tuple(observations)


def _cases(
    forecasts: tuple[ForecastValue, ...], observations: tuple[Observation, ...],
) -> tuple[CloudCase, ...]:
    grouped: dict[tuple[str, datetime, int], dict[str, float | None]] = {}
    truth: dict[tuple[str, datetime, int], float] = {}
    for item in align_station_forecasts(forecasts, observations):
        if item.truth_value is None:
            raise ValueError("Cloud truth cannot be missing after QC")
        lead = (item.forecast.valid_time - item.forecast.run_time).days
        key = item.station_id, item.forecast.valid_time, lead
        values = grouped.setdefault(key, {})
        if item.forecast.model_id in values or (key in truth and truth[key] != item.truth_value):
            raise ValueError("Duplicate model or conflicting cloud truth")
        values[item.forecast.model_id] = item.forecast.value
        truth[key] = item.truth_value
    cases: list[CloudCase] = []
    for key, values in sorted(grouped.items()):
        contributors = tuple(
            value for model in GLOBAL_MODELS for value in (values.get(model),) if value is not None
        )
        if len(contributors) < 3:
            continue
        predictions = {"global_mean": fmean(contributors), "global_median": median(contributors)}
        for name, model in zip(
            ("best_match", "native_aladin", "seamless_aladin"),
            ("best_match", *REGIONAL_MODELS), strict=True,
        ):
            value = values.get(model)
            if value is None:
                raise ValueError(f"Required benchmark missing: {model} at {key}")
            predictions[name] = value
        cases.append(CloudCase(*key, truth[key], predictions))
    return tuple(cases)


def _score(cases: Sequence[CloudCase], name: str) -> Score:
    predicted = tuple(case.predictions[name] for case in cases)
    observed = tuple(case.observed for case in cases)
    return {
        "cases": len(cases), "days": len({case.valid_time.date() for case in cases}),
        "mae_pp": mean_absolute_error(predicted, observed) * 100,
        "rmse_pp": root_mean_square_error(predicted, observed) * 100,
        "bias_pp": fmean(p - o for p, o in zip(predicted, observed, strict=True)) * 100,
        "false_clear": sum(
            p < 0.2 and o >= 0.875 for p, o in zip(predicted, observed, strict=True)
        ),
        "station_mae_pp": {
            station.wigos_id: mean_absolute_error(
                tuple(
                    case.predictions[name] for case in cases if case.station_id == station.wigos_id
                ),
                tuple(case.observed for case in cases if case.station_id == station.wigos_id),
            ) * 100 for station in STATIONS
        },
    }


def _difference(cases: Sequence[CloudCase], benchmark: str) -> Difference:
    dated = tuple((
        case.valid_time.date(),
        (abs(case.predictions["global_mean"] - case.observed)
         - abs(case.predictions[benchmark] - case.observed)) * 100,
    ) for case in cases)
    daily = block_bootstrap_mean_interval(dated, repetitions=2000, seed=20260825)
    weekly = block_bootstrap_mean_interval(tuple(
        (START + timedelta(days=((day - START).days // 7) * 7), value) for day, value in dated
    ), repetitions=2000, seed=20260825)
    return {
        "difference_pp": daily.estimate, "daily_lower": daily.lower, "daily_upper": daily.upper,
        "weekly_lower": weekly.lower, "weekly_upper": weekly.upper,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--study-dir", type=Path, default=DEFAULT_STUDY)
    parser.add_argument(
        "--output", type=Path, help="New JSON file; existing files are never replaced",
    )
    args = parser.parse_args()
    study, output = cast(Path, args.study_dir), cast(Path | None, args.output)
    if output is not None and output.exists():
        raise ValueError(f"Output already exists: {output}")
    original = _object(cast(JsonValue, json.loads(
        _verified_bytes(study / "confirmation-results.json", REPORT_SHA256)
    )))
    _verified_bytes(study / "confirmation-gate.md", original["gate_sha256"])
    # Resolve and validate every frozen input before calculating any score.
    forecasts, observations = _forecasts(study, original), _observations(study, original)
    cases = _cases(forecasts, observations)
    paired_counts = {
        f"{station.wigos_id}:{lead}": {
            "cases": len(selected), "days": len({case.valid_time.date() for case in selected}),
        }
        for lead in (1, 2) for station in STATIONS
        for selected in [tuple(
            case for case in cases if case.lead_days == lead and case.station_id == station.wigos_id
        )]
    }
    if len(cases) != original["paired_cases"] or paired_counts != original["paired_counts"]:
        raise ValueError("Reconstructed paired cohort differs from the frozen study")
    metrics: dict[str, dict[str, Score]] = {}
    differences: dict[str, dict[str, Difference]] = {}
    regional_differences: dict[str, int] = {}
    reasons: list[str] = []
    for lead in (1, 2):
        selected = tuple(case for case in cases if case.lead_days == lead)
        metrics[str(lead)] = scores = {
            name: _score(selected, name) for name in ("global_mean", *BENCHMARKS)
        }
        differences[str(lead)] = comparisons = {
            name: _difference(selected, name) for name in BENCHMARKS
        }
        regional = [{
            (item.requested_point_id, item.valid_time): item.value for item in forecasts
            if item.model_id == model and item.valid_time - item.run_time == timedelta(days=lead)
        } for model in REGIONAL_MODELS]
        if regional[0].keys() != regional[1].keys():
            raise ValueError("Native/seamless coverage differs")
        regional_differences[str(lead)] = sum(
            value != regional[1][key] for key, value in regional[0].items()
        )
        if len({case.station_id for case in selected}) < 3 or any(
            paired_counts[f"{station.wigos_id}:{lead}"]["cases"] < 100
            or paired_counts[f"{station.wigos_id}:{lead}"]["days"] < 20 for station in STATIONS
        ):
            reasons.append(f"lead{lead}: station coverage gate failed")
        candidate = scores["global_mean"]
        for name in BENCHMARKS:
            if comparisons[name]["daily_upper"] >= 0 or comparisons[name]["weekly_upper"] >= 0:
                reasons.append(f"lead{lead}: {name} confidence gate failed")
            if candidate["false_clear"] > scores[name]["false_clear"]:
                reasons.append(f"lead{lead}: {name} false-clear gate failed")
            for station in STATIONS:
                station_id = station.wigos_id
                if candidate["station_mae_pp"][station_id] > (
                    scores[name]["station_mae_pp"][station_id] * 1.05
                ):
                    reasons.append(f"lead{lead}: {station_id} {name} station MAE gate failed")
    result = {
        "status": "reconstructed_offline_replay_not_new_holdout", "network_requests": 0,
        "original_scripts_available": False, "original_report_sha256": REPORT_SHA256,
        "original_gate": original["gate"], "gate_sha256": original["gate_sha256"],
        "lead_contract": "Nominal Previous Runs day1/day2; issue time not independently verified",
        "truth_contract": (
            "CHMI monthly Oct2 snapshots, N0–8, QC0, blank flag; not annual finalized"
        ),
        "date_start": str(START), "date_end": str(END),
        "request_manifests": original["request_manifests"],
        "truth_sources": original["truth_coverage"],
        "paired_cases": len(cases), "paired_counts": paired_counts,
        "metrics_by_lead": metrics, "mean_minus_benchmark_by_lead": differences,
        "native_seamless_different_values": regional_differences,
        "gate": {"passed": not reasons, "reasons": reasons},
    }
    encoded = json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False)
    if output is None:
        print(encoded)
    else:
        with output.open("x", encoding="utf-8") as destination:
            destination.write(encoded + "\n")


if __name__ == "__main__":
    main()
