from __future__ import annotations

import sqlite3
from collections.abc import Iterable
from pathlib import Path

from aladin_ensemble.types import Observation, SourceManifest, SpatialObservation

_SCHEMA = """
CREATE TABLE IF NOT EXISTS source_manifest (
    checksum_sha256 TEXT PRIMARY KEY,
    source_url TEXT NOT NULL,
    provider TEXT NOT NULL,
    documentation_url TEXT NOT NULL,
    license_name TEXT NOT NULL,
    license_url TEXT NOT NULL,
    first_retrieved_at TEXT NOT NULL,
    last_retrieved_at TEXT NOT NULL,
    source_timestamp TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS observation (
    source_checksum TEXT NOT NULL REFERENCES source_manifest(checksum_sha256),
    source TEXT NOT NULL,
    station_id TEXT NOT NULL,
    valid_time TEXT NOT NULL,
    variable TEXT NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    elevation_m REAL NOT NULL,
    value REAL,
    unit TEXT NOT NULL,
    interval_seconds INTEGER,
    accumulation TEXT NOT NULL,
    flag TEXT,
    quality INTEGER,
    measurement_height_m REAL,
    PRIMARY KEY (source_checksum, source, station_id, valid_time, variable)
);
CREATE TABLE IF NOT EXISTS spatial_observation (
    source_checksum TEXT NOT NULL REFERENCES source_manifest(checksum_sha256),
    source TEXT NOT NULL,
    valid_start TEXT NOT NULL,
    valid_end TEXT NOT NULL,
    variable TEXT NOT NULL,
    row_index INTEGER NOT NULL,
    column_index INTEGER NOT NULL,
    value REAL,
    unit TEXT NOT NULL,
    projection TEXT NOT NULL,
    west REAL NOT NULL,
    south REAL NOT NULL,
    east REAL NOT NULL,
    north REAL NOT NULL,
    xscale_m REAL NOT NULL,
    yscale_m REAL NOT NULL,
    flag TEXT,
    PRIMARY KEY (source_checksum, source, valid_end, variable, row_index, column_index)
);
"""


def connect(path: str | Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA foreign_keys = ON")
    connection.executescript(_SCHEMA)
    return connection


def ingest_source(
    connection: sqlite3.Connection,
    manifest: SourceManifest,
    observations: Iterable[Observation],
    spatial_observations: Iterable[SpatialObservation] = (),
) -> None:
    checksum = _manifest_checksum(manifest)
    with connection:
        _insert_manifest(connection, manifest, checksum)
        for observation in observations:
            _insert_observation(connection, observation, checksum)
        for observation in spatial_observations:
            _insert_spatial_observation(connection, observation, checksum)


def _manifest_checksum(manifest: SourceManifest) -> str:
    if (
        manifest.source_url is None
        or manifest.checksum_sha256 is None
        or manifest.source_timestamp is None
    ):
        raise ValueError("source manifest requires URL, checksum, and source timestamp")
    return manifest.checksum_sha256


def _insert_manifest(
    connection: sqlite3.Connection, manifest: SourceManifest, checksum: str
) -> None:
    assert manifest.source_url is not None
    assert manifest.source_timestamp is not None
    row = (
        checksum,
        manifest.source_url,
        manifest.provider,
        manifest.documentation_url,
        manifest.license_name,
        manifest.license_url,
        manifest.retrieved_at.isoformat(),
        manifest.retrieved_at.isoformat(),
        manifest.source_timestamp.isoformat(),
    )
    try:
        connection.execute(
            "INSERT INTO source_manifest "
            "(checksum_sha256, source_url, provider, documentation_url, license_name, license_url, "
            "first_retrieved_at, last_retrieved_at, source_timestamp) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            row,
        )
    except sqlite3.IntegrityError as error:
        existing = connection.execute(
            "SELECT source_url, provider, documentation_url, license_name, license_url, "
            "source_timestamp "
            "FROM source_manifest WHERE checksum_sha256 = ?",
            (checksum,),
        ).fetchone()
        if existing != (row[1], row[2], row[3], row[4], row[5], row[8]):
            raise ValueError("conflicting source manifest") from error
        connection.execute(
            "UPDATE source_manifest SET last_retrieved_at = ? WHERE checksum_sha256 = ?",
            (manifest.retrieved_at.isoformat(), checksum),
        )


def _insert_observation(
    connection: sqlite3.Connection, observation: Observation, checksum: str
) -> None:
    if observation.source_checksum != checksum:
        raise ValueError("observation source checksum does not match manifest")
    row = (
        checksum,
        observation.source,
        observation.station_id,
        observation.valid_time.isoformat(),
        observation.variable,
        observation.latitude,
        observation.longitude,
        observation.elevation_m,
        observation.value,
        observation.unit,
        int(observation.interval.total_seconds()) if observation.interval is not None else None,
        observation.accumulation,
        observation.flag,
        observation.quality,
        observation.measurement_height_m,
    )
    _insert_or_require(
        connection,
        "observation",
        "source_checksum, source, station_id, valid_time, variable, latitude, longitude, "
        "elevation_m, "
        "value, unit, interval_seconds, accumulation, flag, quality, measurement_height_m",
        "source_checksum = ? AND source = ? AND station_id = ? AND valid_time = ? AND variable = ?",
        row,
        5,
    )


def _insert_spatial_observation(
    connection: sqlite3.Connection, observation: SpatialObservation, checksum: str
) -> None:
    if observation.source_checksum != checksum:
        raise ValueError("spatial observation source checksum does not match manifest")
    west, south, east, north = observation.geographic_bounds
    row = (
        checksum,
        observation.source,
        observation.valid_start.isoformat(),
        observation.valid_end.isoformat(),
        observation.variable,
        observation.row,
        observation.column,
        observation.value,
        observation.unit,
        observation.projection,
        west,
        south,
        east,
        north,
        observation.xscale_m,
        observation.yscale_m,
        observation.flag,
    )
    _insert_or_require(
        connection,
        "spatial_observation",
        "source_checksum, source, valid_start, valid_end, variable, row_index, column_index, "
        "value, unit, "
        "projection, west, south, east, north, xscale_m, yscale_m, flag",
        "source_checksum = ? AND source = ? AND valid_end = ? AND variable = ? "
        "AND row_index = ? AND column_index = ?",
        row,
        6,
    )


def _insert_or_require(
    connection: sqlite3.Connection,
    table: str,
    columns: str,
    key: str,
    row: tuple[object, ...],
    key_size: int,
) -> None:
    placeholders = ", ".join("?" for _ in row)
    try:
        connection.execute(f"INSERT INTO {table} ({columns}) VALUES ({placeholders})", row)
    except sqlite3.IntegrityError as error:
        existing = connection.execute(
            f"SELECT {columns} FROM {table} WHERE {key}", row[:key_size]
        ).fetchone()
        if existing != row:
            raise ValueError(f"conflicting {table.replace('_', ' ')}") from error
