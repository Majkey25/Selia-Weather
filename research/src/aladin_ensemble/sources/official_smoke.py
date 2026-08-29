from __future__ import annotations

import argparse
import subprocess
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import cast

from .official_runs import (
    ChmiAladinRequest,
    DwdIconRequest,
    EcmwfOpenRequest,
    NoaaGefsRequest,
    NoaaGfsRequest,
    build_grib_get_data_command,
    download_chmi_aladin,
    download_dwd_icon,
    download_ecmwf,
    download_noaa_gefs,
    download_noaa_gfs,
)


@dataclass(frozen=True, slots=True)
class SmokeConfig:
    source: str
    run_time: datetime
    lead_hour: int
    variable: str

    def __post_init__(self) -> None:
        if self.source not in SOURCES:
            raise ValueError(f"unsupported smoke source: {self.source}")
        if self.run_time.tzinfo is None or self.run_time.utcoffset() != UTC.utcoffset(
            self.run_time
        ):
            raise ValueError("run_time must be timezone-aware UTC")
        if not self.variable:
            raise ValueError("variable is required")


def run_smoke(config: SmokeConfig, cache_root: Path) -> int:
    source_cache = cache_root / config.source
    if config.source == "chmi":
        downloaded = download_chmi_aladin(
            ChmiAladinRequest(config.run_time, config.variable),
            source_cache,
        )
    elif config.source == "dwd":
        downloaded = download_dwd_icon(
            DwdIconRequest(config.run_time, config.lead_hour, config.variable),
            source_cache,
        )
    elif config.source == "noaa":
        downloaded = download_noaa_gfs(
            NoaaGfsRequest(config.run_time, config.lead_hour, config.variable),
            source_cache,
        )
    elif config.source == "gefs-mean":
        downloaded = download_noaa_gefs(
            NoaaGefsRequest("mean", config.run_time, config.lead_hour, config.variable),
            source_cache,
        )
    else:
        model = "ifs" if config.source == "ifs" else "aifs-single"
        downloaded = download_ecmwf(
            EcmwfOpenRequest(model, config.run_time, config.lead_hour, config.variable),
            source_cache,
        )
    result = subprocess.run(
        build_grib_get_data_command(downloaded.path),
        check=True,
        capture_output=True,
        text=True,
    )
    count = validate_decode_output(result.stdout)
    print(
        f"source={config.source} bytes={downloaded.path.stat().st_size} "
        f"sha256={downloaded.checksum} values={count} cache={downloaded.from_cache}"
    )
    return count


def validate_decode_output(value: str) -> int:
    lines = [line.strip() for line in value.splitlines() if line.strip()]
    if not lines or lines[0].split() != ["Latitude", "Longitude", "Value"]:
        raise ValueError("ecCodes output header is invalid")
    rows = lines[1:]
    if not rows:
        raise ValueError("ecCodes output contains no values")
    for row in rows:
        columns = row.split()
        if len(columns) != 3:
            raise ValueError("ecCodes output row must have three columns")
        float(columns[0])
        float(columns[1])
        if columns[2] != "MISSING":
            float(columns[2])
    return len(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description="Download and decode one official GRIB field.")
    parser.add_argument("--source", choices=sorted(SOURCES), required=True)
    parser.add_argument("--date", required=True, help="Run date in YYYYMMDD format.")
    parser.add_argument("--hour", type=int, required=True)
    parser.add_argument("--lead", type=int, required=True)
    parser.add_argument("--variable", default="temperature_2m")
    parser.add_argument("--cache-root", type=Path, required=True)
    arguments = parser.parse_args()
    run_time = datetime.strptime(cast(str, arguments.date), "%Y%m%d").replace(
        hour=cast(int, arguments.hour),
        tzinfo=UTC,
    )
    run_smoke(
        SmokeConfig(
            source=cast(str, arguments.source),
            run_time=run_time,
            lead_hour=cast(int, arguments.lead),
            variable=cast(str, arguments.variable),
        ),
        cast(Path, arguments.cache_root),
    )


SOURCES = frozenset({"aifs", "chmi", "dwd", "gefs-mean", "ifs", "noaa"})


if __name__ == "__main__":
    main()
