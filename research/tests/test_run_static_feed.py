from __future__ import annotations

from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from aladin_ensemble.run_static_feed import build_pages_site
from aladin_ensemble.static_feed import decode_manifest, decode_source_registry
from aladin_ensemble.types import ForecastValue


def registry_path() -> Path:
    return Path(__file__).parents[1] / "static-source-registry.json"


def test_pages_site_preserves_docs_and_publishes_diagnostic_contract(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "index.html").write_text("<h1>Privacy</h1>\n", encoding="utf-8")
    output = tmp_path / "site"

    manifest = build_pages_site(
        docs_root=docs,
        output_dir=output,
        source_registry=registry_path(),
        now=datetime(2026, 8, 29, 12, tzinfo=UTC),
    )

    assert (output / "index.html").read_text(encoding="utf-8") == "<h1>Privacy</h1>\n"
    assert manifest.run.state == "diagnostic"
    assert manifest.tile_checksums == ()
    assert decode_manifest(
        (output / "data/v1/manifest.json").read_text(encoding="utf-8")
    ) == manifest
    licences = decode_source_registry(
        (output / "data/v1/licences.json").read_text(encoding="utf-8")
    )
    assert tuple(source for source in licences if source.enabled) == manifest.sources
    assert len(licences) > len(manifest.sources)
    assert all(source.enabled and source.commercial_redistribution for source in manifest.sources)


def test_pages_site_is_deterministic_for_same_time(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "index.html").write_text("ok\n", encoding="utf-8")
    now = datetime(2026, 8, 29, 12, tzinfo=UTC)

    build_pages_site(docs, tmp_path / "first", registry_path(), now)
    build_pages_site(docs, tmp_path / "second", registry_path(), now)

    assert (tmp_path / "first/data/v1/manifest.json").read_bytes() == (
        tmp_path / "second/data/v1/manifest.json"
    ).read_bytes()


def test_pages_site_leaves_no_output_when_size_limit_fails(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "index.html").write_text("too large", encoding="utf-8")
    output = tmp_path / "site"

    with pytest.raises(ValueError, match="size limit"):
        build_pages_site(
            docs_root=docs,
            output_dir=output,
            source_registry=registry_path(),
            now=datetime(2026, 8, 29, 12, tzinfo=UTC),
            max_bytes=1,
        )

    assert not output.exists()


def test_pages_site_publishes_verified_operational_tiles(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "index.html").write_text("ok\n", encoding="utf-8")
    now = datetime(2026, 8, 29, 12, tzinfo=UTC)
    value = ForecastValue(
        model_id="noaa_gfs",
        run_time=now,
        valid_time=now + timedelta(hours=6),
        latitude=50.0,
        longitude=14.0,
        elevation_m=250.0,
        variable="temperature_2m",
        value=20.0,
        unit="°C",
    )

    manifest = build_pages_site(
        docs,
        tmp_path / "site",
        registry_path(),
        now,
        values=(value,),
    )

    assert manifest.run.state == "diagnostic"
    assert len(manifest.tile_checksums) == 1
    assert manifest.tile_checksums[0][0].endswith(".json.gz")


def test_forecast_workflow_builds_and_deploys_pages_site() -> None:
    workflow = Path(__file__).parents[2] / ".github" / "workflows" / "forecast-data.yml"

    source = workflow.read_text(encoding="utf-8")

    assert 'cron: "17 */6 * * *"' in source
    assert "pages: write" in source
    assert "id-token: write" in source
    assert "libeccodes-tools" in source
    assert "python -m aladin_ensemble.run_static_feed" in source
    assert "--operational" in source
    assert "actions/deploy-pages@v4" in source
    assert "include_forecasts:" in source
    assert "default: true" in source
    assert "if: github.event_name != 'workflow_dispatch' || inputs.include_forecasts" in source
    assert "if: github.event_name == 'workflow_dispatch' && !inputs.include_forecasts" in source
