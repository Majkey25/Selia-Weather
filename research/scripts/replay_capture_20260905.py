"""Replay one pre-existing Zlin capture against frozen, independently fetched station data."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from datetime import UTC, datetime
from io import StringIO
from pathlib import Path
from typing import cast

from aladin_ensemble.registry import JsonValue
from aladin_ensemble.sources.chmi_station import (
    parse_element_metadata,
    parse_station_metadata,
    parse_station_observations,
)
from aladin_ensemble.sources.live_capture import (
    compare_capture_models,
    evaluate_capture,
    write_immutable,
)
from aladin_ensemble.sources.official_runs import download_http_with_retry
from aladin_ensemble.types import Observation

ROOT = Path(__file__).resolve().parents[2]
CAPTURE = ROOT / "research/data/raw/captured-live/captures" / (
    "1fad12ae7f36d27a5185e816f0880cc9e3213703fdbbb575a433f2fb8f86bfe1.json"
)
STATION_ID = "0-203-0-11775"
CACHE = ROOT / "research/data/raw/capture-replay-20260908"
SOURCE_ROOT = "https://opendata.chmi.cz/meteorology/climate/now"
URLS = {
    **{f"meta{i}": f"{SOURCE_ROOT}/metadata/meta{i}-20260908.json" for i in range(1, 5)},
    **{f"{prefix}-{day}": f"{SOURCE_ROOT}/data/{prefix}-{STATION_ID}-{day}.json"
       for prefix in ("10m", "1h") for day in ("20260906", "20260907")},
}


def freeze_inputs() -> None:
    manifest = CACHE / "inputs.json"
    if manifest.exists():
        raise ValueError("Frozen inputs already exist; rerun without --download")
    sources: dict[str, JsonValue] = {}
    for name, url in URLS.items():
        body = download_http_with_retry(url, timeout=20, max_bytes=2_000_000, attempts=1)
        json.loads(body)
        checksum = hashlib.sha256(body).hexdigest()
        write_immutable(CACHE / f"{checksum}.json", body)
        sources[name] = {
            "url": url, "sha256": checksum, "received_at": datetime.now(UTC).isoformat(),
        }
    write_immutable(manifest, json.dumps(sources, indent=2, sort_keys=True).encode())


def inputs() -> tuple[dict[str, bytes], dict[str, JsonValue]]:
    records = cast(dict[str, dict[str, str]], json.loads((CACHE / "inputs.json").read_bytes()))
    if set(records) != set(URLS):
        raise ValueError("Frozen input set differs")
    bodies: dict[str, bytes] = {}
    for name, record in records.items():
        checksum = record["sha256"]
        if len(checksum) != 64 or any(char not in "0123456789abcdef" for char in checksum):
            raise ValueError("Invalid frozen checksum")
        body = (CACHE / f"{checksum}.json").read_bytes()
        if hashlib.sha256(body).hexdigest() != checksum or record["url"] != URLS[name]:
            raise ValueError("Frozen source differs")
        bodies[name] = body
    return bodies, cast(dict[str, JsonValue], records)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = cast(Path, args.output)
    if output.exists():
        raise ValueError("Use a new output file; prior results stay immutable")
    if args.download:
        freeze_inputs()
    bodies, sources = inputs()
    stations = {station.wigos_id: station for station in parse_station_metadata(
        StringIO(bodies["meta1"].decode()),
    )}
    metadata = parse_element_metadata(StringIO(bodies["meta2"].decode()))
    observations: list[Observation] = []
    truth_payloads: dict[str, bytes] = {}
    for name, body in bodies.items():
        if name.startswith("meta"):
            continue
        checksum = hashlib.sha256(body).hexdigest()
        truth_payloads[checksum] = body
        observations.extend(parse_station_observations(
            StringIO(body.decode()), stations, metadata,
            "10M" if name.startswith("10m") else "1H", checksum,
        ))
    strict = evaluate_capture(CAPTURE, observations, truth_payloads)
    provisional = evaluate_capture(
        CAPTURE, observations, truth_payloads, allow_provisional_chmi=True,
    )
    station = stations[STATION_ID]
    result: dict[str, JsonValue] = {
        "capture_manifest_sha256": CAPTURE.stem,
        "sources": sources,
        "station": {
            "id": station.wigos_id, "name": station.name, "latitude": station.latitude,
            "longitude": station.longitude, "elevation_m": station.elevation_m,
        },
        "quality_counts": dict(Counter(str(row.quality) for row in observations)),
        "flag_counts": dict(Counter(str(row.flag) for row in observations)),
        "strict_status_counts": dict(Counter(row.status for row in strict)),
        "strict_comparison": compare_capture_models(strict),
        "provisional_status_counts": dict(Counter(row.status for row in provisional)),
        "provisional_comparison": compare_capture_models(provisional),
        "calibration_eligible": False,
        "caveat": (
            "One captured forecast, one station, two days. Provisional QC5 is not validated truth. "
            "No weights fitted or exported. Best Match was not captured."
        ),
    }
    write_immutable(output, json.dumps(result, indent=2, sort_keys=True).encode())
    print(json.dumps({key: value for key, value in result.items() if key != "sources"}, indent=2))


if __name__ == "__main__":
    main()
