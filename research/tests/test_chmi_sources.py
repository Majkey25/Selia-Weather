from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from aladin_ensemble.sources.chmi_radar import parse_merge1h_contract
from aladin_ensemble.sources.chmi_station import (
    ElementMetadata,
    Station,
    build_source_manifest,
    parse_element_metadata,
    parse_station_metadata,
    parse_station_observations,
)
from aladin_ensemble.storage import connect, ingest_source
from aladin_ensemble.types import Observation, SourceManifest

FIXTURES = Path(__file__).parent / "fixtures" / "chmi"
DOCUMENTATION_URL = "https://opendata.chmi.cz/meteorology/climate/Klimatologicka_data_popis.pdf"
LICENSE_URL = (
    "https://www.chmi.cz/-/jak-mohu-pou%C5%BE%C3%ADvat-otev%C5%99en%C3%A1-data-%C4%8Dhm%C3%BA-"
)


def _stations() -> dict[str, Station]:
    with (FIXTURES / "meta1.json").open(encoding="utf-8") as source:
        return {station.wigos_id: station for station in parse_station_metadata(source)}


def _observations() -> tuple[Observation, ...]:
    checksum = _manifest().checksum_sha256
    assert checksum is not None
    with (FIXTURES / "hourly.json").open(encoding="utf-8") as source:
        return tuple(parse_station_observations(source, _stations(), _metadata(), "1H", checksum))


def _metadata() -> dict[tuple[str, str, str], ElementMetadata]:
    with (FIXTURES / "meta2.json").open(encoding="utf-8") as source:
        return parse_element_metadata(source)


def _manifest() -> SourceManifest:
    return build_source_manifest(
        FIXTURES / "hourly.json",
        source_url="https://opendata.chmi.cz/meteorology/climate/now/data/1h-0-20000-0-11502-20260825.json",
        retrieved_at=datetime(2026, 8, 25, 2, tzinfo=UTC),
        documentation_url=DOCUMENTATION_URL,
        license_name="ČHMÚ Open Data",
        license_url=LICENSE_URL,
    )


def test_parses_utf8_station_and_hourly_observations() -> None:
    stations = _stations()
    observations = _observations()
    station = stations["0-20000-0-11502"]
    pressure = observations[0]
    precipitation = observations[1]

    assert station.name == "Ústí nad Labem, Kočkov"
    assert pressure.variable == "surface_pressure"
    assert pressure.value == 1007.1
    assert pressure.valid_time == datetime(2026, 10, 25, tzinfo=UTC)
    assert precipitation.unit == "mm"
    assert precipitation.interval == timedelta(hours=1)
    assert precipitation.accumulation == "interval"


def test_preserves_missing_flag_and_quality_without_zero_substitution() -> None:
    missing = _observations()[2]

    assert missing.value is None
    assert missing.flag == "M"
    assert missing.quality == 4


def test_rejects_invalid_station_observation_range(tmp_path: Path) -> None:
    invalid = tmp_path / "invalid.json"
    invalid.write_text(
        """{"datumVytvoreni":"2026-08-25T01:02:03Z","data":{"data":{"header":"STATION,ELEMENT,DT,VAL,FLAG,QUALITY","values":[["0-20000-0-11502","H","2026-08-25T00:00:00Z",101,"",5]]}}}""",
        encoding="utf-8",
    )

    with (
        invalid.open(encoding="utf-8") as source,
        pytest.raises(ValueError, match="relative_humidity"),
    ):
        tuple(
            parse_station_observations(
                source, _stations(), _metadata(), "10M", _manifest().checksum_sha256 or ""
            )
        )


def test_manifest_records_checksum_and_source_timestamp() -> None:
    manifest = _manifest()

    assert manifest.source_url is not None
    assert manifest.checksum_sha256 is not None
    assert len(manifest.checksum_sha256) == 64
    assert manifest.source_timestamp == datetime(2026, 8, 25, 1, 2, 3, tzinfo=UTC)


def test_ingest_rejects_conflicting_duplicate_and_rolls_back() -> None:
    connection = connect(":memory:")
    observations = _observations()
    manifest = _manifest()
    conflicting = replace(observations[0], value=13.0)

    with pytest.raises(ValueError, match="conflicting observation"):
        ingest_source(connection, manifest, (*observations, conflicting))

    assert connection.execute("SELECT count(*) FROM observation").fetchone() == (0,)
    assert connection.execute("SELECT count(*) FROM source_manifest").fetchone() == (0,)


def test_ingest_is_idempotent_and_persists_manifest() -> None:
    connection = connect(":memory:")
    observations = _observations()
    manifest = _manifest()

    ingest_source(connection, manifest, observations)
    ingest_source(connection, manifest, observations)

    assert connection.execute("SELECT count(*) FROM observation").fetchone() == (len(observations),)
    assert connection.execute(
        "SELECT source_url, checksum_sha256 FROM source_manifest"
    ).fetchone() == (manifest.source_url, manifest.checksum_sha256)


def test_merge1h_contract_parses_utc_interval_projection_and_bounds() -> None:
    contract = parse_merge1h_contract(FIXTURES / "T_PASV23_C_OKPR_20260825173000.hdf")

    assert contract.valid_end == datetime(2026, 8, 25, 17, 30, tzinfo=UTC)
    assert contract.valid_start == datetime(2026, 8, 25, 16, 30, 1, tzinfo=UTC)
    assert contract.projdef.startswith("+proj=merc")
    assert contract.geographic_bounds == (11.266869, 48.047275, 19.623974, 51.458369)


def test_merge1h_contract_rejects_bad_filename(tmp_path: Path) -> None:
    invalid = tmp_path / "radar.hdf"
    invalid.write_bytes((FIXTURES / "T_PASV23_C_OKPR_20260825173000.hdf").read_bytes())

    with pytest.raises(ValueError, match="filename"):
        parse_merge1h_contract(invalid)
