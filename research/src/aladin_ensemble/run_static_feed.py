from __future__ import annotations

import argparse
from datetime import UTC, datetime, timedelta
from pathlib import Path
from shutil import copytree
from tempfile import TemporaryDirectory
from typing import cast

from .static_feed import (
    FeedGrid,
    FeedManifest,
    FeedRun,
    decode_source_registry,
    encode_manifest,
    encode_source_registry,
)


def build_pages_site(
    docs_root: Path,
    output_dir: Path,
    source_registry: Path,
    now: datetime,
    max_bytes: int = 800_000_000,
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
    manifest = FeedManifest(
        schema_version=1,
        grid=FeedGrid(),
        run=FeedRun(
            run_id=generated_at.strftime("%Y%m%dT%H%M%SZ"),
            generated_at=generated_at,
            expires_at=generated_at + timedelta(hours=6),
            state="diagnostic",
        ),
        sources=tuple(
            sorted(
                (source for source in sources if source.enabled),
                key=lambda item: item.source_id,
            )
        ),
        tile_checksums=(),
    )
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=output_dir.parent, prefix=".pages-") as temporary:
        staging = Path(temporary) / "site"
        copytree(docs_root, staging)
        data = staging / "data" / "v1"
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
    parser = argparse.ArgumentParser(description="Build the static Selia Vetra Pages site.")
    parser.add_argument("--docs-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-registry", type=Path, required=True)
    arguments = parser.parse_args()
    manifest = build_pages_site(
        docs_root=cast(Path, arguments.docs_root),
        output_dir=cast(Path, arguments.output),
        source_registry=cast(Path, arguments.source_registry),
        now=datetime.now(UTC),
    )
    print(
        f"state={manifest.run.state} run={manifest.run.run_id} "
        f"sources={len(manifest.sources)} tiles={len(manifest.tile_checksums)}"
    )


if __name__ == "__main__":
    main()
