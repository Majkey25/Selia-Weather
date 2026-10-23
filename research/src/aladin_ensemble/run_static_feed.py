from __future__ import annotations

import argparse
import os
import tarfile
from datetime import UTC, datetime, timedelta
from pathlib import Path, PurePosixPath
from shutil import copyfileobj, copytree
from tempfile import TemporaryDirectory
from typing import cast

from .build_static_feed import build_static_feed, verify_static_feed
from .operational_feed import latest_complete_cycle, load_operational_values
from .static_feed import (
    FeedGrid,
    FeedManifest,
    FeedRun,
    decode_manifest,
    decode_source_registry,
    encode_manifest,
    encode_source_registry,
)
from .types import ForecastValue


def build_pages_site(
    docs_root: Path,
    output_dir: Path,
    source_registry: Path,
    now: datetime,
    max_bytes: int = 800_000_000,
    values: tuple[ForecastValue, ...] = (),
    previous_artifact: Path | None = None,
    published_manifest: Path | None = None,
) -> FeedManifest:
    if now.tzinfo is None or now.utcoffset() != UTC.utcoffset(now):
        raise ValueError("now must be timezone-aware UTC")
    if max_bytes <= 0:
        raise ValueError("max_bytes must be positive")
    if output_dir.exists():
        raise ValueError("output_dir already exists")
    if not (docs_root / "index.html").is_file():
        raise ValueError("docs_root must contain index.html")
    if (previous_artifact is None) != (published_manifest is None):
        raise ValueError("previous_artifact and published_manifest are required together")
    if previous_artifact is not None and values:
        raise ValueError("cannot replace forecasts while preserving a published feed")
    sources = decode_source_registry(source_registry.read_text(encoding="utf-8"))
    generated_at = now.replace(microsecond=0)
    run = FeedRun(
        run_id=generated_at.strftime("%Y%m%dT%H%M%SZ"),
        generated_at=generated_at,
        expires_at=generated_at + timedelta(hours=6),
        state="diagnostic",
    )
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=output_dir.parent, prefix=".pages-") as temporary:
        staging = Path(temporary) / "site"
        copytree(docs_root, staging)
        data = staging / "data" / "v1"
        if previous_artifact is not None and published_manifest is not None:
            manifest = _restore_published_feed(
                previous_artifact, published_manifest, data, now, max_bytes
            )
        elif values:
            manifest = build_static_feed(
                values=values,
                sources=sources,
                grid=FeedGrid(),
                run_id=run.run_id,
                generated_at=run.generated_at,
                expires_at=run.expires_at,
                state="diagnostic",
                calibration=None,
                dataset_manifest_hash=None,
                output_dir=data,
                max_bytes=max_bytes,
            )
            verify_static_feed(data)
        else:
            manifest = FeedManifest(
                schema_version=1,
                grid=FeedGrid(),
                run=run,
                sources=tuple(
                    sorted(
                        (source for source in sources if source.enabled),
                        key=lambda item: item.source_id,
                    )
                ),
                tile_checksums=(),
            )
            data.mkdir(parents=True)
            (data / "manifest.json").write_text(encode_manifest(manifest), encoding="utf-8")
            (data / "licences.json").write_text(
                encode_source_registry(sources),
                encoding="utf-8",
            )
        total_bytes = sum(path.stat().st_size for path in staging.rglob("*") if path.is_file())
        if total_bytes > max_bytes:
            raise ValueError("Pages site exceeds size limit")
        staging.replace(output_dir)
    return manifest


def _restore_published_feed(
    artifact: Path, published_manifest: Path, output: Path, now: datetime, max_bytes: int
) -> FeedManifest:
    if artifact.is_symlink() or not artifact.is_file() or artifact.stat().st_size > max_bytes:
        raise ValueError("previous artifact must be a regular file within the size limit")
    if (
        published_manifest.is_symlink()
        or not published_manifest.is_file()
        or published_manifest.stat().st_size > 1_000_000
    ):
        raise ValueError("published manifest must be a regular file within the size limit")
    published = published_manifest.read_bytes()
    manifest = decode_manifest(published.decode("utf-8"))
    if not manifest.tile_checksums or not (
        manifest.run.generated_at <= now < manifest.run.expires_at
    ):
        raise ValueError("published feed must contain tiles and be currently valid")
    expected = {"manifest.json", "licences.json", *(path for path, _ in manifest.tile_checksums)}
    if manifest.calibration_checksum is not None:
        expected.add("calibration/ensemble_weights.json")
    with tarfile.open(artifact, mode="r:") as archive:
        seen: set[str] = set()
        files: dict[str, tarfile.TarInfo] = {}
        total_bytes = 0
        for member in archive:
            name = member.name.removeprefix("./")
            path = PurePosixPath(name)
            if (
                name in seen
                or len(seen) >= 10_000
                or path.is_absolute()
                or ".." in path.parts
                or name != path.as_posix()
                or "\\" in name
                or ":" in name
                or not (member.isdir() or member.isreg())
                or member.issparse()
                or (name == "." and not member.isdir())
            ):
                raise ValueError("previous artifact contains unsafe or duplicate entries")
            seen.add(name)
            total_bytes += member.size
            if member.size < 0 or total_bytes > max_bytes:
                raise ValueError("previous artifact exceeds size limit")
            if member.isreg() and path.parts[:2] == ("data", "v1"):
                files[path.relative_to("data/v1").as_posix()] = member
        if set(files) != expected:
            raise ValueError("previous artifact does not contain exactly the published feed files")
        if files["manifest.json"].size != len(published):
            raise ValueError("previous artifact manifest differs from the published feed")
        output.mkdir(parents=True)
        for relative, member in files.items():
            target = output / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            source = archive.extractfile(member)
            if source is None:
                raise ValueError("previous artifact file is missing")
            with source, target.open("xb") as destination:
                copyfileobj(source, destination)
            os.utime(target, (member.mtime, member.mtime))
        if (output / "manifest.json").read_bytes() != published:
            raise ValueError("previous artifact manifest differs from the published feed")
        return verify_static_feed(output)


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the static Selia Weather Pages site.")
    parser.add_argument("--docs-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-registry", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--operational", action="store_true")
    mode.add_argument("--previous-artifact", type=Path)
    parser.add_argument("--published-manifest", type=Path)
    parser.add_argument("--cache-root", type=Path)
    arguments = parser.parse_args()
    now = datetime.now(UTC)
    values: tuple[ForecastValue, ...] = ()
    if cast(bool, arguments.operational):
        cache_root = cast(Path | None, arguments.cache_root)
        if cache_root is None:
            parser.error("--cache-root is required with --operational")
        values = load_operational_values(latest_complete_cycle(now), cache_root)
    manifest = build_pages_site(
        docs_root=cast(Path, arguments.docs_root),
        output_dir=cast(Path, arguments.output),
        source_registry=cast(Path, arguments.source_registry),
        now=now,
        values=values,
        previous_artifact=cast(Path | None, arguments.previous_artifact),
        published_manifest=cast(Path | None, arguments.published_manifest),
    )
    print(
        f"state={manifest.run.state} run={manifest.run.run_id} "
        f"sources={len(manifest.sources)} tiles={len(manifest.tile_checksums)}"
    )


if __name__ == "__main__":
    main()
