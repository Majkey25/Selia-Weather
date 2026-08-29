from __future__ import annotations

import gzip
import json
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from aladin_ensemble.build_static_feed import build_static_feed, verify_static_feed
from aladin_ensemble.static_feed import FeedGrid, FeedSource, FeedState, encode_manifest
from aladin_ensemble.types import ForecastValue


def feed_source(source_id: str, model_id: str) -> FeedSource:
    return FeedSource(
        source_id=source_id,
        provider=source_id,
        model_id=model_id,
        source_kind="forecast",
        data_url="https://example.com/data/",
        documentation_url="https://example.com/docs/",
        native_resolution_km=9.0,
        forecast_horizon_hours=360,
        update_interval_minutes=360,
        variables=("precipitation", "temperature_2m", "wind_speed_10m"),
        licence_name="CC BY 4.0",
        licence_url="https://example.com/licence/",
        attribution=f"Source: {source_id}",
        commercial_redistribution=True,
        enabled=True,
    )


def value(
    model_id: str,
    latitude: float,
    longitude: float,
    valid_time: datetime,
    variable: str,
    amount: float | None,
) -> ForecastValue:
    return ForecastValue(
        model_id=model_id,
        run_time=datetime(2026, 8, 29, 12, tzinfo=UTC),
        valid_time=valid_time,
        latitude=latitude,
        longitude=longitude,
        elevation_m=250.0,
        variable=variable,
        value=amount,
        unit={"precipitation": "mm", "temperature_2m": "°C", "wind_speed_10m": "km/h"}[
            variable
        ],
    )


def fixture_values() -> tuple[ForecastValue, ...]:
    times = (
        datetime(2026, 8, 29, 12, tzinfo=UTC),
        datetime(2026, 8, 29, 13, tzinfo=UTC),
        datetime(2026, 8, 29, 14, tzinfo=UTC),
    )
    points = ((49.0, 14.0), (49.0, 14.05), (49.05, 14.0), (49.05, 14.05))
    return tuple(
        value(model_id, latitude, longitude, valid_time, variable, 20.0 + index)
        for model_id in ("model_a", "model_b")
        for latitude, longitude in points
        for valid_time in times
        for index, variable in enumerate(("precipitation", "temperature_2m", "wind_speed_10m"))
    )


def calibration(*model_ids: str) -> bytes:
    document = {
        "dataset_manifest_hash": "a" * 64,
        "generated_at": "2026-08-29T12:00:00Z",
        "models": [
            {"maximum_run_age_hours": 6, "model_id": model_id, "resolution_km": 9.0}
            for model_id in model_ids
        ],
        "schema_version": 1,
        "segments": [{"mode": "fallback"}],
    }
    return (json.dumps(document, separators=(",", ":"), sort_keys=True) + "\n").encode()


def build(
    output: Path,
    values: tuple[ForecastValue, ...],
    *,
    state: FeedState = "diagnostic",
    calibration: bytes | None = None,
    dataset_manifest_hash: str | None = None,
    max_bytes: int = 800_000_000,
):
    generated_at = datetime(2026, 8, 29, 12, tzinfo=UTC)
    return build_static_feed(
        values=values,
        sources=(feed_source("source-a", "model_a"), feed_source("source-b", "model_b")),
        grid=FeedGrid(south=49.0, north=49.1, west=14.0, east=14.1, step=0.05, tile_step=0.1),
        run_id="20260829T120000Z",
        generated_at=generated_at,
        expires_at=generated_at + timedelta(hours=8),
        state=state,
        calibration=calibration,
        dataset_manifest_hash=dataset_manifest_hash,
        output_dir=output,
        max_bytes=max_bytes,
    )


def test_builder_writes_deterministic_source_separated_tile(tmp_path: Path) -> None:
    first = tmp_path / "first"
    second = tmp_path / "second"

    first_manifest = build(first, fixture_values())
    second_manifest = build(second, tuple(reversed(fixture_values())))

    assert encode_manifest(first_manifest) == encode_manifest(second_manifest)
    assert first_manifest.tile_checksums == second_manifest.tile_checksums
    assert (first / "manifest.json").read_bytes() == (second / "manifest.json").read_bytes()
    tile = Path(first_manifest.tile_checksums[0][0])
    assert (first / tile).read_bytes() == (second / tile).read_bytes()
    text = gzip.decompress((first / tile).read_bytes()).decode("utf-8")
    assert '"source_id":"source-a"' in text
    assert '"source_id":"source-b"' in text
    assert (first / "licences.json").is_file()


def test_builder_compresses_tiles_losslessly_and_deterministically(tmp_path: Path) -> None:
    output = tmp_path / "feed"

    manifest = build(output, fixture_values())
    relative = manifest.tile_checksums[0][0]
    compressed = (output / relative).read_bytes()

    assert relative.endswith(".json.gz")
    decoded = gzip.decompress(compressed).decode("utf-8")
    assert '"source_id":"source-a"' in decoded
    assert len(compressed) < len(decoded.encode("utf-8"))


def test_builder_refuses_production_without_calibration(tmp_path: Path) -> None:
    generated_at = datetime(2026, 8, 29, 12, tzinfo=UTC)

    with pytest.raises(ValueError, match="calibration"):
        build_static_feed(
            values=fixture_values(),
            sources=(feed_source("source-a", "model_a"), feed_source("source-b", "model_b")),
            grid=FeedGrid(south=49.0, north=49.1, west=14.0, east=14.1, step=0.05, tile_step=0.1),
            run_id="20260829T120000Z",
            generated_at=generated_at,
            expires_at=generated_at + timedelta(hours=8),
            state="production",
            calibration=None,
            dataset_manifest_hash="a" * 64,
            output_dir=tmp_path / "feed",
        )


def test_builder_rejects_duplicate_forecast_identity(tmp_path: Path) -> None:
    values = fixture_values()

    with pytest.raises(ValueError, match="duplicate forecast value"):
        build(tmp_path / "feed", values + (values[0],))


def test_builder_rejects_point_outside_grid(tmp_path: Path) -> None:
    values = fixture_values()
    invalid = value(
        "model_a",
        50.0,
        14.0,
        datetime(2026, 8, 29, 12, tzinfo=UTC),
        "temperature_2m",
        20.0,
    )

    with pytest.raises(ValueError, match="outside feed grid"):
        build(tmp_path / "feed", values + (invalid,))


def test_builder_leaves_no_output_when_size_limit_fails(tmp_path: Path) -> None:
    output = tmp_path / "feed"

    with pytest.raises(ValueError, match="size limit"):
        build(output, fixture_values(), max_bytes=1)

    assert not output.exists()


def test_builder_rejects_mixed_units(tmp_path: Path) -> None:
    values = fixture_values()
    mixed = (replace(values[0], unit="K"),) + values[1:]

    with pytest.raises(ValueError, match="mixed units"):
        build(tmp_path / "feed", mixed)


def test_builder_rejects_mixed_validity_axes(tmp_path: Path) -> None:
    values = fixture_values()
    shortened = tuple(
        item
        for item in values
        if not (
            item.model_id == "model_a"
            and item.latitude == 49.0
            and item.longitude == 14.0
            and item.variable == "temperature_2m"
            and item.valid_time.hour == 14
        )
    )

    with pytest.raises(ValueError, match="validity axis"):
        build(tmp_path / "feed", shortened)


def test_verifier_rejects_corrupt_tile(tmp_path: Path) -> None:
    output = tmp_path / "feed"
    manifest = build(output, fixture_values())
    tile = output / manifest.tile_checksums[0][0]
    tile.write_text("corrupt", encoding="utf-8")

    with pytest.raises(ValueError, match="checksum"):
        verify_static_feed(output)


def test_production_feed_writes_calibration_artifact(tmp_path: Path) -> None:
    output = tmp_path / "feed"

    manifest = build(
        output,
        fixture_values(),
        state="production",
        calibration=calibration("model_a", "model_b"),
        dataset_manifest_hash="a" * 64,
    )

    calibration_path = output / "calibration" / "ensemble_weights.json"
    assert calibration_path.is_file()
    assert manifest.calibration_checksum is not None
    verify_static_feed(output)


def test_production_feed_rejects_unknown_calibration_model(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="unknown calibration model"):
        build(
            tmp_path / "feed",
            fixture_values(),
            state="production",
            calibration=calibration("model_a", "unlicensed"),
            dataset_manifest_hash="a" * 64,
        )


def test_production_feed_rejects_calibration_for_another_dataset(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="dataset manifest hash"):
        build(
            tmp_path / "feed",
            fixture_values(),
            state="production",
            calibration=calibration("model_a", "model_b"),
            dataset_manifest_hash="b" * 64,
        )
