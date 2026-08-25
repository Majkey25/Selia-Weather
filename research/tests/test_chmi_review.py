from __future__ import annotations

import json
from dataclasses import replace
from datetime import UTC, datetime
from io import StringIO
from pathlib import Path

import h5py
import pytest

from aladin_ensemble.sources.chmi_radar import iter_merge1h_observations, parse_merge1h_contract
from aladin_ensemble.sources.chmi_station import (
    ElementMetadata,
    Station,
    cumulative_precipitation_intervals,
    parse_element_metadata,
    parse_station_metadata,
    parse_station_observations,
)
from aladin_ensemble.storage import connect, ingest_source
from aladin_ensemble.types import SourceManifest

FIXTURES = Path(__file__).parent / "fixtures" / "chmi"
CHECKSUM = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"


class TinyChunks(StringIO):
    def read(self, size: int | None = -1) -> str:
        return super().read(7 if size is None or size < 0 else min(size, 7))


def _stations() -> dict[str, Station]:
    with (FIXTURES / "meta1.json").open(encoding="utf-8") as source:
        return {station.wigos_id: station for station in parse_station_metadata(source)}


def _metadata() -> dict[tuple[str, str, str], ElementMetadata]:
    with (FIXTURES / "meta2.json").open(encoding="utf-8") as source:
        return parse_element_metadata(source)


def _manifest(retrieved_at: datetime) -> SourceManifest:
    return SourceManifest(
        provider="ČHMÚ",
        documentation_url="https://opendata.chmi.cz/meteorology/weather/radar/radar_description_en.pdf",
        license_name="ČHMÚ Open Data CC BY 4.0",
        license_url="https://www.chmi.cz/-/jak-mohu-pou%C5%BE%C3%ADvat-otev%C5%99en%C3%A1-data-%C4%8Dhm%C3%BA-",
        retrieved_at=retrieved_at,
        run_time=None,
        source_url="https://example.invalid/one-source",
        checksum_sha256=CHECKSUM,
        source_timestamp=datetime(2026, 8, 25, 0, tzinfo=UTC),
    )


def test_metadata_height_prevents_false_wind_10m_label_and_keeps_dst_hours() -> None:
    with (FIXTURES / "ten-minute.json").open(encoding="utf-8") as source:
        observations = tuple(
            parse_station_observations(source, _stations(), _metadata(), "10M", CHECKSUM)
        )

    wind = observations[3]
    assert wind.variable == "wind_speed"
    assert wind.measurement_height_m == 10.56
    assert observations[3].valid_time != observations[4].valid_time


def test_metadata_rejects_duplicate_key_and_unknown_wigos(tmp_path: Path) -> None:
    duplicate = tmp_path / "duplicate.json"
    payload = json.loads((FIXTURES / "meta2.json").read_text(encoding="utf-8"))
    payload["data"]["data"]["values"].append(
        ["10M", "0-20000-0-11502", "F", "Rychlost větru", "m/s", 10.56, "10M"]
    )
    duplicate.write_text(json.dumps(payload), encoding="utf-8")
    unknown = tmp_path / "unknown.json"
    unknown.write_text(
        (FIXTURES / "ten-minute.json")
        .read_text(encoding="utf-8")
        .replace("0-20000-0-11502", "0-20000-0-99999", 1),
        encoding="utf-8",
    )

    with duplicate.open(encoding="utf-8") as source, pytest.raises(ValueError, match="duplicate"):
        parse_element_metadata(source)
    with unknown.open(encoding="utf-8") as source, pytest.raises(ValueError, match="unknown WIGOS"):
        tuple(parse_station_observations(source, _stations(), _metadata(), "10M", CHECKSUM))


def test_json_parser_handles_marker_and_row_boundaries() -> None:
    text = (FIXTURES / "meta1.json").read_text(encoding="utf-8")

    stations = tuple(parse_station_metadata(TinyChunks(text)))

    assert stations[0].wigos_id == "0-20000-0-11502"


def test_cumulative_precipitation_keeps_first_reset_and_missing_unknown() -> None:
    values = (
        (datetime(2026, 8, 25, 0, tzinfo=UTC), 2.0),
        (datetime(2026, 8, 25, 1, tzinfo=UTC), 3.5),
        (datetime(2026, 8, 25, 2, tzinfo=UTC), 0.2),
        (datetime(2026, 8, 25, 3, tzinfo=UTC), None),
    )

    assert tuple(cumulative_precipitation_intervals(values)) == (
        (values[0][0], None),
        (values[1][0], 1.5),
        (values[2][0], None),
        (values[3][0], None),
    )


def test_manifest_retry_updates_last_retrieval_and_observation_fk() -> None:
    connection = connect(":memory:")
    with (FIXTURES / "hourly.json").open(encoding="utf-8") as source:
        observations = tuple(
            parse_station_observations(source, _stations(), _metadata(), "1H", CHECKSUM)
        )

    ingest_source(connection, _manifest(datetime(2026, 8, 25, 1, tzinfo=UTC)), observations)
    ingest_source(connection, _manifest(datetime(2026, 8, 25, 2, tzinfo=UTC)), observations)

    assert connection.execute("SELECT last_retrieved_at FROM source_manifest").fetchone() == (
        "2026-08-25T02:00:00+00:00",
    )
    assert connection.execute("SELECT source_checksum FROM observation LIMIT 1").fetchone() == (
        CHECKSUM,
    )

    with pytest.raises(ValueError, match="conflicting source manifest"):
        ingest_source(
            connection,
            replace(
                _manifest(datetime(2026, 8, 25, 3, tzinfo=UTC)),
                source_url="https://example.invalid/conflict",
            ),
            (),
        )


def test_merge1h_reads_chunked_spatial_data_with_actual_odim_contract() -> None:
    path = FIXTURES / "T_PASV23_C_OKPR_20260825173000.hdf"
    contract = parse_merge1h_contract(path)
    observations = tuple(iter_merge1h_observations(path, CHECKSUM, block_rows=1))

    assert contract.valid_start == datetime(2026, 8, 25, 16, 30, 1, tzinfo=UTC)
    assert contract.valid_end == datetime(2026, 8, 25, 17, 30, tzinfo=UTC)
    assert contract.projdef.startswith("+proj=merc")
    assert contract.geographic_bounds == (11.266869, 48.047275, 19.623974, 51.458369)
    assert [observation.value for observation in observations] == [1.0, None, 2.0, None]
    assert observations[0].source_checksum == CHECKSUM


def test_merge1h_rejects_filename_end_time_projection_and_geographic_bounds(tmp_path: Path) -> None:
    invalid = tmp_path / "invalid.hdf"
    invalid.write_bytes((FIXTURES / "T_PASV23_C_OKPR_20260825173000.hdf").read_bytes())

    with pytest.raises(ValueError, match="filename"):
        parse_merge1h_contract(invalid)

    named = tmp_path / "T_PASV23_C_OKPR_20260825173000.hdf"
    named.write_bytes((FIXTURES / "T_PASV23_C_OKPR_20260825173000.hdf").read_bytes())
    with h5py.File(named, "r+") as source:
        source["where"].attrs["projdef"] = b""
    with pytest.raises(ValueError, match="projdef"):
        parse_merge1h_contract(named)

    named.write_bytes((FIXTURES / "T_PASV23_C_OKPR_20260825173000.hdf").read_bytes())
    with h5py.File(named, "r+") as source:
        source["where"].attrs["UR_lon"] = 11.0
    with pytest.raises(ValueError, match="bounds"):
        parse_merge1h_contract(named)

    named.write_bytes((FIXTURES / "T_PASV23_C_OKPR_20260825173000.hdf").read_bytes())
    with h5py.File(named, "r+") as source:
        source["dataset1/what"].attrs["endtime"] = b"172000"
    with pytest.raises(ValueError, match="end time"):
        parse_merge1h_contract(named)
