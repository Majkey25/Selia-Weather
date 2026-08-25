from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from math import isfinite
from typing import Literal


def _require_utc(value: datetime, field_name: str) -> None:
    if value.tzinfo is None or value.utcoffset() != UTC.utcoffset(value):
        raise ValueError(f"{field_name} must be timezone-aware UTC")


def _require_finite(value: object, field_name: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int | float) or not isfinite(value):
        raise ValueError(f"{field_name} must be finite")


def _require_coordinates(latitude: float, longitude: float, elevation_m: float) -> None:
    _require_finite(latitude, "latitude")
    _require_finite(longitude, "longitude")
    _require_finite(elevation_m, "elevation_m")
    if not -90 <= latitude <= 90:
        raise ValueError("latitude is outside WGS84 range")
    if not -180 <= longitude <= 180:
        raise ValueError("longitude is outside WGS84 range")


def _require_value(value: float | None) -> None:
    if value is not None:
        _require_finite(value, "value")


@dataclass(frozen=True, slots=True)
class ForecastValue:
    model_id: str
    run_time: datetime
    valid_time: datetime
    latitude: float
    longitude: float
    elevation_m: float
    variable: str
    value: float | None
    unit: str

    def __post_init__(self) -> None:
        _require_utc(self.run_time, "run_time")
        _require_utc(self.valid_time, "valid_time")
        _require_coordinates(self.latitude, self.longitude, self.elevation_m)
        _require_value(self.value)


@dataclass(frozen=True, slots=True)
class Observation:
    source: str
    station_id: str
    valid_time: datetime
    latitude: float
    longitude: float
    elevation_m: float
    variable: str
    value: float | None
    unit: str
    interval: timedelta | None = None
    accumulation: Literal["instant", "interval", "cumulative"] = "instant"
    flag: str | None = None
    quality: int | None = None

    def __post_init__(self) -> None:
        _require_utc(self.valid_time, "valid_time")
        _require_coordinates(self.latitude, self.longitude, self.elevation_m)
        _require_value(self.value)
        if self.interval is not None and self.interval.total_seconds() <= 0:
            raise ValueError("interval must be positive")
        if self.accumulation == "instant" and self.interval is not None:
            raise ValueError("instant observation cannot have an interval")
        if self.accumulation == "interval" and self.interval is None:
            raise ValueError("interval observation requires an interval")
        if self.flag is not None and not self.flag:
            raise ValueError("flag cannot be empty")
        if self.quality is not None and (
            isinstance(self.quality, bool) or not isinstance(self.quality, int) or self.quality < 0
        ):
            raise ValueError("quality must be a non-negative integer")


@dataclass(frozen=True, slots=True)
class ResponseMetadata:
    latitude: float
    longitude: float
    elevation_m: float
    timezone: str | None
    generationtime_ms: float | None

    def __post_init__(self) -> None:
        _require_coordinates(self.latitude, self.longitude, self.elevation_m)
        if self.generationtime_ms is not None:
            _require_finite(self.generationtime_ms, "generationtime_ms")


@dataclass(frozen=True, slots=True)
class SourceManifest:
    provider: str
    documentation_url: str
    license_name: str
    license_url: str
    retrieved_at: datetime
    run_time: datetime | None
    request_endpoint: str | None = None
    request_parameters: tuple[tuple[str, str], ...] = ()
    requested_model_id: str | None = None
    archive_endpoint: str | None = None
    archive_parameters: tuple[tuple[str, str], ...] = ()
    responses: tuple[ResponseMetadata, ...] = ()
    provider_model_id: str | None = None
    source_url: str | None = None
    checksum_sha256: str | None = None
    source_timestamp: datetime | None = None

    def __post_init__(self) -> None:
        _require_utc(self.retrieved_at, "retrieved_at")
        if self.run_time is not None:
            _require_utc(self.run_time, "run_time")
        if self.source_url is not None and not self.source_url:
            raise ValueError("source_url cannot be empty")
        if self.checksum_sha256 is not None and (
            len(self.checksum_sha256) != 64
            or any(character not in "0123456789abcdef" for character in self.checksum_sha256)
        ):
            raise ValueError("checksum_sha256 must be a lowercase SHA-256 digest")
        if self.source_timestamp is not None:
            _require_utc(self.source_timestamp, "source_timestamp")


@dataclass(frozen=True, slots=True)
class ModelCandidate:
    model_id: str
    display_name: str
    provider: str
    required_variables: frozenset[str]
    returned_variables: frozenset[str]
    sample_points: int
    covered_points: int
    required_horizon_hours: int
    available_horizon_hours: int
    verified: bool
    archive_verified: bool
    manifest: SourceManifest

    def __post_init__(self) -> None:
        if not self.model_id or not self.display_name or not self.provider:
            raise ValueError("model identity is required")
        if not self.required_variables:
            raise ValueError("required_variables cannot be empty")
        if self.sample_points <= 0 or not 0 <= self.covered_points <= self.sample_points:
            raise ValueError("invalid coverage counts")
        if self.required_horizon_hours <= 0 or self.available_horizon_hours < 0:
            raise ValueError("invalid horizon")

    def eligibility_error(self) -> str | None:
        if not self.verified:
            return "unverified model"
        if not self.archive_verified:
            return "archive availability is unverified"
        if self.covered_points * 100 < self.sample_points * 90:
            return "coverage is below 90 percent"
        if self.available_horizon_hours < self.required_horizon_hours:
            return "horizon is below requirement"
        if not self.required_variables.issubset(self.returned_variables):
            return "required variables are unavailable"
        if not self.manifest.license_name or not self.manifest.license_url:
            return "license is missing"
        return None
