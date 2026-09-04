from __future__ import annotations

import argparse
from datetime import UTC, datetime, timedelta
from pathlib import Path
from shutil import copytree
from tempfile import TemporaryDirectory
from typing import cast

from .build_static_feed import build_static_feed, verify_static_feed
from .operational_feed import latest_complete_cycle, load_operational_values
from .static_feed import (
    FeedGrid,
    FeedManifest,
    FeedRun,
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
) -> FeedManifest:
    if now.tzinfo is None or now.utcoffset() != UTC.utcoffset(now):
        raise ValueError("now must be timezone-aware UTC")
    if max_bytes <= 0:
        raise ValueError("max_bytes must be positive")
    if output_dir.exists():
        raise ValueError("output_dir already exists")
    if not (docs_root / "index.html").is_file():
        raise ValueError("docs_root must contain index.html")
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
        if values:
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


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the static Selia Weather Pages site.")
    parser.add_argument("--docs-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-registry", type=Path, required=True)
    parser.add_argument("--operational", action="store_true")
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
    )
    print(
        f"state={manifest.run.state} run={manifest.run.run_id} "
        f"sources={len(manifest.sources)} tiles={len(manifest.tile_checksums)}"
    )


if __name__ == "__main__":
    main()
