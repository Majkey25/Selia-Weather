from __future__ import annotations

import sqlite3
from collections.abc import Iterable
from pathlib import Path

from aladin_ensemble.types import Observation, SourceManifest

_SCHEMA = """
CREATE TABLE IF NOT EXISTS source_manifest (
    source_url TEXT PRIMARY KEY,
    provider TEXT NOT NULL,
    documentation_url TEXT NOT NULL,
    license_name TEXT NOT NULL,
    license_url TEXT NOT NULL,
    retrieved_at TEXT NOT NULL,
    source_timestamp TEXT NOT NULL,
    checksum_sha256 TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS observation (
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
    PRIMARY KEY (source, station_id, valid_time, variable)
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
) -> None:
    manifest_row = _manifest_row(manifest)
    with connection:
        _insert_manifest(connection, manifest_row)
        for observation in observations:
            _insert_observation(connection, _observation_row(observation))


def _manifest_row(manifest: SourceManifest) -> tuple[str, str, str, str, str, str, str, str]:
    if (
        manifest.source_url is None
        or manifest.checksum_sha256 is None
        or manifest.source_timestamp is None
    ):
        raise ValueError("source manifest requires URL, checksum, and source timestamp")
    return (
        manifest.source_url,
        manifest.provider,
        manifest.documentation_url,
        manifest.license_name,
        manifest.license_url,
        manifest.retrieved_at.isoformat(),
        manifest.source_timestamp.isoformat(),
        manifest.checksum_sha256,
    )


def _observation_row(
    observation: Observation,
) -> tuple[
    str,
    str,
    str,
    str,
    float,
    float,
    float,
    float | None,
    str,
    int | None,
    str,
    str | None,
    int | None,
]:
    return (
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
    )


def _insert_manifest(
    connection: sqlite3.Connection, row: tuple[str, str, str, str, str, str, str, str]
) -> None:
    try:
        connection.execute(
            "INSERT INTO source_manifest "
            "(source_url, provider, documentation_url, license_name, license_url, retrieved_at, "
            "source_timestamp, checksum_sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            row,
        )
    except sqlite3.IntegrityError as error:
        existing = connection.execute(
            "SELECT source_url, provider, documentation_url, license_name, license_url, "
            "retrieved_at, "
            "source_timestamp, checksum_sha256 FROM source_manifest WHERE source_url = ?",
            row[:1],
        ).fetchone()
        if existing != row:
            raise ValueError("conflicting source manifest") from error


def _insert_observation(
    connection: sqlite3.Connection,
    row: tuple[
        str,
        str,
        str,
        str,
        float,
        float,
        float,
        float | None,
        str,
        int | None,
        str,
        str | None,
        int | None,
    ],
) -> None:
    try:
        connection.execute(
            "INSERT INTO observation "
            "(source, station_id, valid_time, variable, latitude, longitude, elevation_m, value, "
            "unit, interval_seconds, accumulation, flag, quality) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            row,
        )
    except sqlite3.IntegrityError as error:
        existing = connection.execute(
            "SELECT source, station_id, valid_time, variable, latitude, longitude, elevation_m, "
            "value, "
            "unit, interval_seconds, accumulation, flag, quality FROM observation "
            "WHERE source = ? AND station_id = ? AND valid_time = ? AND variable = ?",
            row[:4],
        ).fetchone()
        if existing != row:
            raise ValueError("conflicting observation") from error
