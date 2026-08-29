from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime
from math import isclose, isfinite
from typing import Literal, cast
from urllib.parse import urlparse

FeedState = Literal["diagnostic", "production"]
SourceKind = Literal["forecast", "observation"]


@dataclass(frozen=True, slots=True)
class FeedGrid:
    south: float = 48.45
    north: float = 51.20
    west: float = 11.90
    east: float = 19.00
    step: float = 0.05
    tile_step: float = 0.50

    def __post_init__(self) -> None:
        values = (self.south, self.north, self.west, self.east, self.step, self.tile_step)
        if not all(isfinite(value) for value in values):
            raise ValueError("grid values must be finite")
        if not (-90 <= self.south < self.north <= 90):
            raise ValueError("latitude bounds are invalid")
        if not (-180 <= self.west < self.east <= 180):
            raise ValueError("longitude bounds are invalid")
        if self.step <= 0 or self.tile_step <= 0:
            raise ValueError("grid steps must be positive")
        ratio = self.tile_step / self.step
        if not isclose(ratio, round(ratio), abs_tol=1e-9):
            raise ValueError("tile_step must be divisible by step")


@dataclass(frozen=True, slots=True)
class FeedSource:
    source_id: str
    provider: str
    model_id: str
    source_kind: SourceKind
    data_url: str
    documentation_url: str
    native_resolution_km: float | None
    forecast_horizon_hours: int | None
    update_interval_minutes: int
    variables: tuple[str, ...]
    licence_name: str
    licence_url: str
    attribution: str
    commercial_redistribution: bool
    enabled: bool

    def __post_init__(self) -> None:
        if not SOURCE_ID.fullmatch(self.source_id):
            raise ValueError("source_id must use lowercase ASCII letters, digits, and hyphens")
        for name, value in (
            ("provider", self.provider),
            ("model_id", self.model_id),
            ("licence_name", self.licence_name),
            ("attribution", self.attribution),
        ):
            if not value.strip():
                raise ValueError(f"{name} is required")
        if self.source_kind not in ("forecast", "observation"):
            raise ValueError("unsupported source_kind")
        for name, value in (
            ("data_url", self.data_url),
            ("documentation_url", self.documentation_url),
            ("licence_url", self.licence_url),
        ):
            parsed_url = urlparse(value)
            if parsed_url.scheme != "https" or not parsed_url.netloc:
                raise ValueError(f"{name} must be an absolute HTTPS URL")
        if self.native_resolution_km is not None and (
            not isfinite(self.native_resolution_km) or self.native_resolution_km <= 0
        ):
            raise ValueError("native_resolution_km must be finite and positive")
        if self.forecast_horizon_hours is not None and self.forecast_horizon_hours <= 0:
            raise ValueError("forecast_horizon_hours must be positive")
        if self.source_kind == "forecast" and (
            self.native_resolution_km is None or self.forecast_horizon_hours is None
        ):
            raise ValueError("forecast source requires resolution and horizon")
        if self.update_interval_minutes <= 0:
            raise ValueError("update_interval_minutes must be positive")
        if (
            not self.variables
            or len(set(self.variables)) != len(self.variables)
            or tuple(sorted(self.variables)) != self.variables
            or any(not VARIABLE.fullmatch(variable) for variable in self.variables)
        ):
            raise ValueError("variables must be unique, sorted canonical identifiers")


@dataclass(frozen=True, slots=True)
class FeedRun:
    run_id: str
    generated_at: datetime
    expires_at: datetime
    state: FeedState

    def __post_init__(self) -> None:
        if not RUN_ID.fullmatch(self.run_id):
            raise ValueError("run_id must use YYYYMMDDTHHMMSSZ")
        _require_utc(self.generated_at, "generated_at")
        _require_utc(self.expires_at, "expires_at")
        if self.expires_at <= self.generated_at:
            raise ValueError("expires_at must follow generated_at")
        if self.state not in ("diagnostic", "production"):
            raise ValueError("unsupported feed state")


@dataclass(frozen=True, slots=True)
class FeedManifest:
    schema_version: int
    grid: FeedGrid
    run: FeedRun
    sources: tuple[FeedSource, ...]
    tile_checksums: tuple[tuple[str, str], ...]
    calibration_checksum: str | None = None
    dataset_manifest_hash: str | None = None

    def __post_init__(self) -> None:
        if self.schema_version != SCHEMA_VERSION:
            raise ValueError("unsupported schema_version")
        validate_source_registry(self.sources, production=self.run.state == "production")
        if self.run.state == "production" and not self.tile_checksums:
            raise ValueError("production manifest requires tiles")
        if self.run.state == "production" and self.calibration_checksum is None:
            raise ValueError("production manifest requires calibration")
        if self.run.state == "production" and self.dataset_manifest_hash is None:
            raise ValueError("production manifest requires dataset manifest hash")
        if self.calibration_checksum is not None:
            _require_checksum(self.calibration_checksum)
        if self.dataset_manifest_hash is not None:
            _require_checksum(self.dataset_manifest_hash)
        paths: set[str] = set()
        for path, checksum in self.tile_checksums:
            if not TILE_PATH.fullmatch(path) or ".." in path:
                raise ValueError("tile path is invalid")
            if not path.startswith(f"tiles/{self.run.run_id}/"):
                raise ValueError("tile path must reference the current run")
            if path in paths:
                raise ValueError("duplicate tile path")
            paths.add(path)
            _require_checksum(checksum)


def validate_source_registry(
    sources: tuple[FeedSource, ...],
    *,
    production: bool,
) -> None:
    if not sources:
        raise ValueError("source registry cannot be empty")
    source_ids: set[str] = set()
    model_ids: set[str] = set()
    for source in sources:
        if source.source_id in source_ids:
            raise ValueError(f"duplicate source_id: {source.source_id}")
        source_ids.add(source.source_id)
        if source.model_id in model_ids:
            raise ValueError(f"duplicate model_id: {source.model_id}")
        model_ids.add(source.model_id)
        if production and source.enabled and not source.commercial_redistribution:
            raise ValueError(f"{source.source_id} lacks commercial redistribution permission")
    if production and not any(source.enabled for source in sources):
        raise ValueError("production feed requires an enabled source")


def decode_source_registry(value: str) -> tuple[FeedSource, ...]:
    try:
        root = _object(cast(object, json.loads(value)), "source registry")
    except json.JSONDecodeError as error:
        raise ValueError("source registry is not valid JSON") from error
    _require_fields(root, {"schema_version", "sources"})
    if _integer(root["schema_version"], "schema_version") != SCHEMA_VERSION:
        raise ValueError("unsupported source registry schema_version")
    sources = tuple(_decode_source(item) for item in _array(root["sources"], "sources"))
    validate_source_registry(sources, production=False)
    return sources


def encode_manifest(manifest: FeedManifest) -> str:
    document = {
        "calibration_checksum": manifest.calibration_checksum,
        "dataset_manifest_hash": manifest.dataset_manifest_hash,
        "grid": {
            "east": manifest.grid.east,
            "north": manifest.grid.north,
            "south": manifest.grid.south,
            "step": manifest.grid.step,
            "tile_step": manifest.grid.tile_step,
            "west": manifest.grid.west,
        },
        "run": {
            "expires_at": _format_time(manifest.run.expires_at),
            "generated_at": _format_time(manifest.run.generated_at),
            "run_id": manifest.run.run_id,
            "state": manifest.run.state,
        },
        "schema_version": manifest.schema_version,
        "sources": [_source_document(source) for source in _sorted_sources(manifest.sources)],
        "tile_checksums": dict(sorted(manifest.tile_checksums)),
    }
    return json.dumps(document, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"


def encode_source_registry(sources: tuple[FeedSource, ...]) -> str:
    validate_source_registry(sources, production=False)
    document = {
        "schema_version": SCHEMA_VERSION,
        "sources": [_source_document(source) for source in _sorted_sources(sources)],
    }
    return json.dumps(document, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"


def _sorted_sources(sources: tuple[FeedSource, ...]) -> tuple[FeedSource, ...]:
    return tuple(sorted(sources, key=lambda value: value.source_id))


def _source_document(source: FeedSource) -> dict[str, object]:
    return {
        "attribution": source.attribution,
        "commercial_redistribution": source.commercial_redistribution,
        "data_url": source.data_url,
        "documentation_url": source.documentation_url,
        "enabled": source.enabled,
        "forecast_horizon_hours": source.forecast_horizon_hours,
        "licence_name": source.licence_name,
        "licence_url": source.licence_url,
        "model_id": source.model_id,
        "native_resolution_km": source.native_resolution_km,
        "provider": source.provider,
        "source_kind": source.source_kind,
        "source_id": source.source_id,
        "update_interval_minutes": source.update_interval_minutes,
        "variables": list(source.variables),
    }


def decode_manifest(value: str) -> FeedManifest:
    try:
        root = _object(cast(object, json.loads(value)), "manifest")
    except json.JSONDecodeError as error:
        raise ValueError("manifest is not valid JSON") from error
    _require_fields(
        root,
        {
            "calibration_checksum",
            "dataset_manifest_hash",
            "grid",
            "run",
            "schema_version",
            "sources",
            "tile_checksums",
        },
    )
    grid = _object(root["grid"], "grid")
    _require_fields(grid, {"east", "north", "south", "step", "tile_step", "west"})
    run = _object(root["run"], "run")
    _require_fields(run, {"expires_at", "generated_at", "run_id", "state"})
    sources = _array(root["sources"], "sources")
    checksums = _object(root["tile_checksums"], "tile_checksums")
    return FeedManifest(
        schema_version=_integer(root["schema_version"], "schema_version"),
        grid=FeedGrid(
            south=_number(grid["south"], "south"),
            north=_number(grid["north"], "north"),
            west=_number(grid["west"], "west"),
            east=_number(grid["east"], "east"),
            step=_number(grid["step"], "step"),
            tile_step=_number(grid["tile_step"], "tile_step"),
        ),
        run=FeedRun(
            run_id=_text(run["run_id"], "run_id"),
            generated_at=_time(run["generated_at"], "generated_at"),
            expires_at=_time(run["expires_at"], "expires_at"),
            state=_state(run["state"]),
        ),
        sources=tuple(_decode_source(item) for item in sources),
        tile_checksums=tuple(
            sorted(
                (_text(path, "tile path"), _text(checksum, "checksum"))
                for path, checksum in checksums.items()
            )
        ),
        calibration_checksum=_optional_text(
            root["calibration_checksum"], "calibration_checksum"
        ),
        dataset_manifest_hash=_optional_text(
            root["dataset_manifest_hash"], "dataset_manifest_hash"
        ),
    )


def _decode_source(value: object) -> FeedSource:
    source = _object(value, "source")
    _require_fields(
        source,
        {
            "attribution",
            "commercial_redistribution",
            "data_url",
            "documentation_url",
            "enabled",
            "forecast_horizon_hours",
            "licence_name",
            "licence_url",
            "model_id",
            "native_resolution_km",
            "provider",
            "source_kind",
            "source_id",
            "update_interval_minutes",
            "variables",
        },
    )
    return FeedSource(
        source_id=_text(source["source_id"], "source_id"),
        provider=_text(source["provider"], "provider"),
        model_id=_text(source["model_id"], "model_id"),
        source_kind=_source_kind(source["source_kind"]),
        data_url=_text(source["data_url"], "data_url"),
        documentation_url=_text(source["documentation_url"], "documentation_url"),
        native_resolution_km=_optional_number(
            source["native_resolution_km"], "native_resolution_km"
        ),
        forecast_horizon_hours=_optional_integer(
            source["forecast_horizon_hours"], "forecast_horizon_hours"
        ),
        update_interval_minutes=_integer(
            source["update_interval_minutes"], "update_interval_minutes"
        ),
        variables=tuple(
            _text(item, "variable")
            for item in _array(source["variables"], "variables")
        ),
        licence_name=_text(source["licence_name"], "licence_name"),
        licence_url=_text(source["licence_url"], "licence_url"),
        attribution=_text(source["attribution"], "attribution"),
        commercial_redistribution=_boolean(
            source["commercial_redistribution"], "commercial_redistribution"
        ),
        enabled=_boolean(source["enabled"], "enabled"),
    )


def _object(value: object, name: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError(f"{name} must be an object")
    mapping = cast(dict[object, object], value)
    if not all(isinstance(key, str) for key in mapping):
        raise ValueError(f"{name} must use string keys")
    return cast(dict[str, object], mapping)


def _array(value: object, name: str) -> list[object]:
    if not isinstance(value, list):
        raise ValueError(f"{name} must be an array")
    return cast(list[object], value)


def _require_fields(value: dict[str, object], expected: set[str]) -> None:
    if set(value) != expected:
        raise ValueError("document fields do not match the schema")


def _text(value: object, name: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{name} must be a non-empty string")
    return value


def _boolean(value: object, name: str) -> bool:
    if not isinstance(value, bool):
        raise ValueError(f"{name} must be a boolean")
    return value


def _optional_text(value: object, name: str) -> str | None:
    return None if value is None else _text(value, name)


def _number(value: object, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float) or not isfinite(value):
        raise ValueError(f"{name} must be finite")
    return float(value)


def _integer(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")
    return value


def _optional_number(value: object, name: str) -> float | None:
    return None if value is None else _number(value, name)


def _optional_integer(value: object, name: str) -> int | None:
    return None if value is None else _integer(value, name)


def _source_kind(value: object) -> SourceKind:
    source_kind = _text(value, "source_kind")
    if source_kind not in ("forecast", "observation"):
        raise ValueError("unsupported source_kind")
    return source_kind


def _state(value: object) -> FeedState:
    state = _text(value, "state")
    if state not in ("diagnostic", "production"):
        raise ValueError("unsupported feed state")
    return state


def _time(value: object, name: str) -> datetime:
    text = _text(value, name)
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{name} must be an ISO-8601 timestamp") from error


def _format_time(value: datetime) -> str:
    _require_utc(value, "timestamp")
    return value.isoformat().replace("+00:00", "Z")


def _require_utc(value: datetime, name: str) -> None:
    if value.tzinfo is None or value.utcoffset() != UTC.utcoffset(value):
        raise ValueError(f"{name} must be timezone-aware UTC")


def _require_checksum(value: str) -> None:
    if not CHECKSUM.fullmatch(value):
        raise ValueError("checksum must be a lowercase SHA-256 digest")


SCHEMA_VERSION = 1
SOURCE_ID = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
VARIABLE = re.compile(r"[a-z][a-z0-9_]*")
RUN_ID = re.compile(r"[0-9]{8}T[0-9]{6}Z")
TILE_PATH = re.compile(r"tiles/[0-9]{8}T[0-9]{6}Z/[0-9]+/[0-9]+\.json\.gz")
CHECKSUM = re.compile(r"[0-9a-f]{64}")
