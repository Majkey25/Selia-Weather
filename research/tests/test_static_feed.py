from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from aladin_ensemble.static_feed import (
    FeedGrid,
    FeedManifest,
    FeedRun,
    FeedSource,
    decode_manifest,
    decode_source_registry,
    encode_manifest,
    validate_source_registry,
)


def source(
    source_id: str = "ecmwf-ifs-open",
    *,
    model_id: str = "ecmwf_ifs_open",
    variables: tuple[str, ...] = ("temperature_2m",),
    enabled: bool = True,
    commercial_redistribution: bool = True,
) -> FeedSource:
    return FeedSource(
        source_id=source_id,
        provider="ECMWF",
        model_id=model_id,
        source_kind="forecast",
        data_url="https://data.ecmwf.int/forecasts/",
        documentation_url="https://www.ecmwf.int/en/forecasts/datasets/open-data",
        native_resolution_km=9.0,
        forecast_horizon_hours=360,
        update_interval_minutes=360,
        variables=variables,
        licence_name="CC BY 4.0",
        licence_url="https://apps.ecmwf.int/datasets/licences/general/",
        attribution="Contains modified ECMWF data © ECMWF 2026",
        commercial_redistribution=commercial_redistribution,
        enabled=enabled,
    )


def manifest() -> FeedManifest:
    generated_at = datetime(2026, 8, 29, 12, tzinfo=UTC)
    return FeedManifest(
        schema_version=1,
        grid=FeedGrid(),
        run=FeedRun(
            run_id="20260829T120000Z",
            generated_at=generated_at,
            expires_at=generated_at + timedelta(hours=8),
            state="diagnostic",
        ),
        sources=(source(),),
        tile_checksums=(("tiles/20260829T120000Z/0/0.json", "a" * 64),),
    )


def test_source_registry_rejects_noncommercial_production_source() -> None:
    with pytest.raises(ValueError, match="commercial redistribution"):
        validate_source_registry(
            (source(commercial_redistribution=False),),
            production=True,
        )


def test_source_registry_ignores_disabled_source_in_production_gate() -> None:
    validate_source_registry(
        (
            source(),
            source(
                "chmi-current-stations",
                model_id="chmi_current_stations",
                enabled=False,
                commercial_redistribution=False,
            ),
        ),
        production=True,
    )


def test_source_registry_rejects_duplicate_identity() -> None:
    with pytest.raises(ValueError, match="duplicate source_id"):
        validate_source_registry((source(), source()), production=False)


def test_source_registry_rejects_duplicate_model_id() -> None:
    with pytest.raises(ValueError, match="duplicate model_id"):
        validate_source_registry((source(), source("ecmwf-second-source")), production=False)


def test_source_rejects_invalid_operational_contract() -> None:
    with pytest.raises(ValueError, match="native_resolution_km"):
        replace(source(), native_resolution_km=0.0)
    with pytest.raises(ValueError, match="forecast_horizon_hours"):
        replace(source(), forecast_horizon_hours=0)
    with pytest.raises(ValueError, match="variables"):
        replace(source(), variables=("temperature_2m", "temperature_2m"))


def test_bundled_source_registry_is_production_safe() -> None:
    registry = Path(__file__).parents[1] / "static-source-registry.json"

    sources = decode_source_registry(registry.read_text(encoding="utf-8"))

    validate_source_registry(sources, production=True)
    assert {item.source_id for item in sources if item.enabled} == {
        "dwd-icon-eu",
        "ecmwf-aifs-open",
        "ecmwf-ifs-open",
        "noaa-gefs",
        "noaa-gfs",
    }
    assert any(item.source_id == "chmi-current-stations" and not item.enabled for item in sources)
    assert any(item.source_id == "chmi-aladin-cz-1km" and not item.enabled for item in sources)


def test_manifest_json_round_trip_is_deterministic() -> None:
    encoded = encode_manifest(manifest())

    assert encoded == encode_manifest(decode_manifest(encoded))
    assert encoded.endswith("\n")


def test_production_manifest_requires_tiles() -> None:
    value = manifest()

    with pytest.raises(ValueError, match="production manifest requires tiles"):
        replace(value, run=replace(value.run, state="production"), tile_checksums=())


def test_manifest_rejects_tile_from_another_run() -> None:
    with pytest.raises(ValueError, match="current run"):
        replace(
            manifest(),
            tile_checksums=(("tiles/20260829T060000Z/0/0.json", "a" * 64),),
        )


@pytest.mark.parametrize(
    "value",
    (
        "{}",
        '{"schema_version":2}',
        encode_manifest(manifest()).replace("a" * 64, "z" * 64),
    ),
)
def test_manifest_rejects_invalid_document(value: str) -> None:
    with pytest.raises(ValueError):
        decode_manifest(value)


def test_feed_run_rejects_stale_or_naive_time() -> None:
    generated_at = datetime(2026, 8, 29, 12, tzinfo=UTC)

    with pytest.raises(ValueError, match="follow generated_at"):
        FeedRun("20260829T120000Z", generated_at, generated_at, "diagnostic")
    with pytest.raises(ValueError, match="UTC"):
        FeedRun(
            "20260829T120000Z",
            generated_at.replace(tzinfo=None),
            generated_at + timedelta(hours=1),
            "diagnostic",
        )


def test_grid_rejects_non_divisible_tile_step() -> None:
    with pytest.raises(ValueError, match="tile_step"):
        FeedGrid(step=0.06, tile_step=0.50)
