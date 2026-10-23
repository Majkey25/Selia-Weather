from __future__ import annotations

import io
import tarfile
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from aladin_ensemble.run_static_feed import build_pages_site
from aladin_ensemble.static_feed import decode_manifest, decode_source_registry, encode_manifest
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


def archive_site(site: Path, artifact: Path) -> None:
    with tarfile.open(artifact, "w") as archive:
        archive.add(site, arcname=".")


@pytest.fixture
def previous_site(tmp_path: Path) -> tuple[Path, Path, Path, datetime]:
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "index.html").write_text("old privacy policy", encoding="utf-8")
    now = datetime(2026, 8, 29, 12, tzinfo=UTC)
    site = tmp_path / "previous-site"
    build_pages_site(
        docs, site, registry_path(), now,
        values=(ForecastValue(
            model_id="noaa_gfs", run_time=now, valid_time=now + timedelta(hours=6),
            latitude=50.0, longitude=14.0, elevation_m=250.0,
            variable="temperature_2m", value=20.0, unit="°C",
        ),),
    )
    artifact = tmp_path / "artifact.tar"
    archive_site(site, artifact)
    (docs / "index.html").write_text("new privacy policy", encoding="utf-8")
    return docs, site, artifact, now + timedelta(hours=1)


def test_docs_publication_preserves_exact_feed_and_uses_new_html(
    tmp_path: Path, previous_site: tuple[Path, Path, Path, datetime],
) -> None:
    docs, previous, artifact, now = previous_site
    output = tmp_path / "site"
    published = previous / "data/v1/manifest.json"

    manifest = build_pages_site(
        docs, output, registry_path(), now,
        previous_artifact=artifact, published_manifest=published,
    )

    assert (output / "index.html").read_text(encoding="utf-8") == "new privacy policy"
    assert manifest == decode_manifest(published.read_text(encoding="utf-8"))
    assert manifest.run.generated_at == now - timedelta(hours=1)
    assert manifest.run.expires_at == now + timedelta(hours=5)
    for source in (previous / "data/v1").rglob("*"):
        if source.is_file():
            restored = output / source.relative_to(previous)
            assert restored.read_bytes() == source.read_bytes()
            assert abs(restored.stat().st_mtime - source.stat().st_mtime) < 0.000001


@pytest.mark.parametrize(
    "failure", ["expired", "future", "empty", "missing", "corrupt", "different", "extra"],
)
def test_preserving_invalid_or_unpublished_feed_fails_without_output(
    tmp_path: Path, previous_site: tuple[Path, Path, Path, datetime], failure: str,
) -> None:
    docs, previous, artifact, now = previous_site
    data = previous / "data/v1"
    published = data / "manifest.json"
    manifest = decode_manifest(published.read_text(encoding="utf-8"))
    if failure == "expired":
        now = manifest.run.expires_at
    elif failure == "future":
        now = manifest.run.generated_at - timedelta(seconds=1)
    elif failure == "empty":
        published.write_text(
            encode_manifest(replace(manifest, tile_checksums=())), encoding="utf-8"
        )
    elif failure == "missing":
        (data / "licences.json").unlink()
    elif failure == "corrupt":
        (data / manifest.tile_checksums[0][0]).write_bytes(b"corrupt tile")
    elif failure == "extra":
        (data / "index.html").write_text("old HTML must not be restored", encoding="utf-8")
    if failure != "different":
        archive_site(previous, artifact)
    else:
        published.write_bytes(published.read_bytes() + b"\n")

    output = tmp_path / "site"
    with pytest.raises(ValueError):
        build_pages_site(
            docs, output, registry_path(), now,
            previous_artifact=artifact, published_manifest=published,
        )
    assert not output.exists()


@pytest.mark.parametrize(("name", "kind"), [
    ("/outside", tarfile.REGTYPE),
    ("./../outside", tarfile.REGTYPE),
    ("./data//v1/outside", tarfile.REGTYPE),
    ("./data\\v1\\outside", tarfile.REGTYPE),
    ("C:/outside", tarfile.REGTYPE),
    ("./data/v1/manifest.json", tarfile.REGTYPE),
    ("./link", tarfile.SYMTYPE),
    ("./link", tarfile.LNKTYPE),
    ("./pipe", tarfile.FIFOTYPE),
])
def test_previous_archive_rejects_unsafe_entries_before_copying(
    tmp_path: Path, previous_site: tuple[Path, Path, Path, datetime], name: str, kind: bytes,
) -> None:
    docs, previous, artifact, now = previous_site
    with tarfile.open(artifact, "a") as archive:
        member = tarfile.TarInfo(name)
        member.type = kind
        if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
            member.linkname = "data/v1/manifest.json"
        archive.addfile(member, io.BytesIO())
    output = tmp_path / "site"

    with pytest.raises(ValueError, match="unsafe or duplicate"):
        build_pages_site(
            docs, output, registry_path(), now,
            previous_artifact=artifact, published_manifest=previous / "data/v1/manifest.json",
        )
    assert not output.exists()
    assert not (tmp_path / "outside").exists()


@pytest.mark.parametrize("oversized_header", [False, True])
def test_previous_archive_size_is_bounded_before_copying(
    tmp_path: Path, previous_site: tuple[Path, Path, Path, datetime], oversized_header: bool,
) -> None:
    docs, previous, artifact, now = previous_site
    limit = artifact.stat().st_size - 1
    if oversized_header:
        member = tarfile.TarInfo("data/v1/oversized")
        member.size = 1_000_000
        artifact.write_bytes(member.tobuf() + b"\0" * 1024)
    output = tmp_path / "site"

    with pytest.raises(ValueError, match="size limit"):
        build_pages_site(
            docs, output, registry_path(), now, max_bytes=limit,
            previous_artifact=artifact, published_manifest=previous / "data/v1/manifest.json",
        )
    assert not output.exists()


def test_forecast_workflow_builds_and_deploys_pages_site() -> None:
    workflow = Path(__file__).parents[2] / ".github" / "workflows" / "forecast-data.yml"

    source = workflow.read_text(encoding="utf-8")

    assert 'cron: "17 */6 * * *"' in source
    assert "pages: write" in source
    assert "actions: read" in source
    assert "id-token: write" in source
    assert "libeccodes-tools" in source
    assert "python -m aladin_ensemble.run_static_feed" in source
    assert "--operational" in source
    assert "actions/deploy-pages@v4" in source
    assert "include_forecasts:" in source
    assert "default: true" in source
    assert "if: github.event_name != 'workflow_dispatch' || inputs.include_forecasts" in source
    assert "if: github.event_name == 'workflow_dispatch' && !inputs.include_forecasts" in source
    assert "--previous-artifact" in source
    assert "--published-manifest" in source
    assert "digest-mismatch: error" in source
