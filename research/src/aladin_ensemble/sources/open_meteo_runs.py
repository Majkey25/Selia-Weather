from __future__ import annotations

import hashlib
import json
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta
from math import isfinite
from pathlib import Path
from time import sleep
from typing import cast
from urllib.error import HTTPError
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from urllib.request import Request, urlopen

from aladin_ensemble.registry import JsonValue, RequestBudget
from aladin_ensemble.types import ForecastPoint, ForecastValue, SourceManifest

SINGLE_RUNS_URL = "https://single-runs-api.open-meteo.com/v1/forecast"
PREVIOUS_RUNS_URL = "https://previous-runs-api.open-meteo.com/v1/forecast"
TERMS_URL = "https://open-meteo.com/en/terms"
DEFAULT_CACHE_ROOT = Path("data/raw/open-meteo")
_SECRET_PARAMETERS = frozenset({"api_key", "apikey", "authorization", "key", "token"})
_VARIABLES = {
    "temperature_2m": ("temperature", "°C"),
    "dew_point_2m": ("dew_point", "°C"),
    "pressure_msl": ("sea_level_pressure", "hPa"),
    "surface_pressure": ("surface_pressure", "hPa"),
    "wind_speed_10m": ("wind_speed", "km/h"),
    "wind_direction_10m": ("wind_direction", "°"),
    "precipitation": ("precipitation", "mm"),
    "precipitation_probability": ("precipitation_probability", "1"),
    "relative_humidity_2m": ("relative_humidity", "1"),
    "cloud_cover": ("cloud_cover", "1"),
}


@dataclass(frozen=True, slots=True)
class IssuedRunRequest:
    model_id: str
    run_time: datetime
    latitude: float
    longitude: float
    variables: tuple[str, ...]
    forecast_days: int

    def __post_init__(self) -> None:
        if not self.model_id:
            raise ValueError("model_id is required")
        run_time = cast(object, self.run_time)
        if not isinstance(run_time, datetime) or (
            run_time.tzinfo is None or run_time.utcoffset() != UTC.utcoffset(run_time)
        ):
            raise ValueError("run_time must be timezone-aware UTC")
        if self.run_time.minute or self.run_time.second or self.run_time.microsecond:
            raise ValueError("run_time must be aligned to a UTC hour")
        if not all(isfinite(value) for value in (self.latitude, self.longitude)):
            raise ValueError("coordinates must be finite")
        if not -90 <= self.latitude <= 90 or not -180 <= self.longitude <= 180:
            raise ValueError("coordinates are outside WGS84 range")
        if not self.variables or len(set(self.variables)) != len(self.variables):
            raise ValueError("variables must be non-empty and unique")
        if any(variable not in _VARIABLES for variable in self.variables):
            raise ValueError("unsupported Open-Meteo variable")
        if not 1 <= self.forecast_days <= 16:
            raise ValueError("forecast_days must be from 1 through 16")


@dataclass(frozen=True, slots=True)
class PreviousRunsRequest:
    model_id: str
    points: tuple[ForecastPoint, ...]
    variables: tuple[str, ...]
    start_date: date
    end_date: date
    lead_days: int

    def __post_init__(self) -> None:
        if not self.model_id:
            raise ValueError("model_id is required")
        if not self.points:
            raise ValueError("points must be non-empty")
        point_ids = tuple(point.point_id for point in self.points)
        if len(set(point_ids)) != len(point_ids):
            raise ValueError("point IDs must be unique")
        if not self.variables or len(set(self.variables)) != len(self.variables):
            raise ValueError("variables must be non-empty and unique")
        if any(variable not in _VARIABLES for variable in self.variables):
            raise ValueError("unsupported Open-Meteo variable")
        if self.start_date > self.end_date:
            raise ValueError("start_date must not follow end_date")
        if not 1 <= self.lead_days <= 7:
            raise ValueError("lead_days must be from 1 through 7")


@dataclass(frozen=True, slots=True)
class HttpResponse:
    status: int
    headers: Mapping[str, str]
    body: bytes


@dataclass(frozen=True, slots=True)
class CachedResponse:
    path: Path
    manifest_path: Path
    checksum_sha256: str
    manifest: SourceManifest


Fetch = Callable[[Request], HttpResponse]
Clock = Callable[[], datetime]
Sleeper = Callable[[float], None]


def build_single_run_url(request: IssuedRunRequest) -> tuple[str, tuple[tuple[str, str], ...]]:
    parameters = _parameters(request)
    return f"{SINGLE_RUNS_URL}?{urlencode(parameters)}", parameters


def build_previous_runs_url(
    request: IssuedRunRequest, *, start_date: str, end_date: str, lead_days: int
) -> tuple[str, tuple[tuple[str, str], ...]]:
    batch = PreviousRunsRequest(
        request.model_id,
        (ForecastPoint("requested", request.latitude, request.longitude),),
        request.variables,
        _date(start_date, "start_date"),
        _date(end_date, "end_date"),
        lead_days,
    )
    return build_previous_runs_batch_url(batch)


def build_previous_runs_batch_url(
    request: PreviousRunsRequest,
) -> tuple[str, tuple[tuple[str, str], ...]]:
    parameters = tuple(
        sorted(
            {
                "end_date": request.end_date.isoformat(),
                "hourly": ",".join(
                    f"{variable}_previous_day{request.lead_days}"
                    for variable in request.variables
                ),
                "latitude": ",".join(str(point.latitude) for point in request.points),
                "longitude": ",".join(str(point.longitude) for point in request.points),
                "models": request.model_id,
                "start_date": request.start_date.isoformat(),
                "timeformat": "unixtime",
            }.items()
        )
    )
    return f"{PREVIOUS_RUNS_URL}?{urlencode(parameters)}", parameters


def estimate_download_budget(
    requests: Sequence[IssuedRunRequest], *, provider_limit: int
) -> RequestBudget:
    if not requests:
        raise ValueError("at least one issued run request is required")
    return RequestBudget(
        candidate_count=len({request.model_id for request in requests}),
        location_count=len({(request.latitude, request.longitude) for request in requests}),
        run_count=len({(request.model_id, request.run_time) for request in requests}),
        variable_count=len({variable for request in requests for variable in request.variables}),
        date_count=len({request.run_time.date() for request in requests}),
        expected_http_requests=len(requests),
        provider_limit=provider_limit,
    )


def estimate_previous_runs_budget(
    requests: Sequence[PreviousRunsRequest], *, provider_limit: int
) -> RequestBudget:
    if not requests:
        raise ValueError("at least one previous-runs request is required")
    dates = {
        request.start_date + timedelta(days=offset)
        for request in requests
        for offset in range((request.end_date - request.start_date).days + 1)
    }
    return RequestBudget(
        candidate_count=len({request.model_id for request in requests}),
        location_count=len(
            {
                (point.latitude, point.longitude)
                for request in requests
                for point in request.points
            }
        ),
        run_count=len({(request.model_id, request.lead_days) for request in requests}),
        variable_count=len({variable for request in requests for variable in request.variables}),
        date_count=len(dates),
        expected_http_requests=len(requests),
        provider_limit=provider_limit,
    )


def sanitize_request_parameters(
    parameters: Sequence[tuple[str, str]],
) -> tuple[tuple[str, str], ...]:
    return tuple(
        sorted(
            (key, "[redacted]" if key.casefold() in _SECRET_PARAMETERS else value)
            for key, value in parameters
        )
    )


class CachedDownloader:
    def __init__(
        self,
        root: Path = DEFAULT_CACHE_ROOT,
        *,
        fetch: Fetch | None = None,
        now: Clock | None = None,
        retry_attempts: int = 2,
        retry_delay_seconds: float = 1.0,
        sleeper: Sleeper | None = None,
    ) -> None:
        if retry_attempts <= 0:
            raise ValueError("retry_attempts must be positive")
        if not isfinite(retry_delay_seconds) or retry_delay_seconds < 0:
            raise ValueError("retry_delay_seconds must be finite and non-negative")
        self._root = root
        self._fetch = fetch or _fetch
        self._now = now or (lambda: datetime.now(UTC))
        self._retry_attempts = retry_attempts
        self._retry_delay_seconds = retry_delay_seconds
        self._sleep = sleeper or sleep

    def download(self, request: IssuedRunRequest) -> CachedResponse:
        url, parameters = build_single_run_url(request)
        safe_parameters = sanitize_request_parameters(parameters)
        manifest_path = self._manifest_path(SINGLE_RUNS_URL, safe_parameters)
        cached = self._cached_manifest(manifest_path)
        headers = {"User-Agent": "aladin-ensemble-research/0.1"}
        if cached is not None:
            etag = _text(cached.get("etag"), "etag", allow_none=True)
            modified = _text(cached.get("last_modified"), "last_modified", allow_none=True)
            if etag is not None:
                headers["If-None-Match"] = etag
            if modified is not None:
                headers["If-Modified-Since"] = modified
        response = self._request(Request(url, headers=headers))
        if response.status == 304:
            if cached is None:
                raise RuntimeError("HTTP 304 without cached response")
            return self._from_cached(manifest_path, cached, request, safe_parameters)
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}")
        checksum = hashlib.sha256(response.body).hexdigest()
        raw_path = self._raw_path(checksum)
        self._write_immutable(raw_path, response.body)
        retrieved_at = self._now()
        if retrieved_at.tzinfo is None or retrieved_at.utcoffset() != UTC.utcoffset(retrieved_at):
            raise ValueError("now must return timezone-aware UTC")
        payload: dict[str, JsonValue] = {
            "checksum_sha256": checksum,
            "etag": _header(response.headers, "etag"),
            "last_modified": _header(response.headers, "last-modified"),
            "request_endpoint": SINGLE_RUNS_URL,
            "request_parameters": [list(item) for item in safe_parameters],
            "retrieved_at": retrieved_at.isoformat(),
        }
        self._write_manifest(manifest_path, payload)
        return CachedResponse(
            raw_path,
            manifest_path,
            checksum,
            _source_manifest(request, _sanitized_url(url), safe_parameters, checksum, retrieved_at),
        )

    def download_previous(self, request: PreviousRunsRequest) -> CachedResponse:
        url, parameters = build_previous_runs_batch_url(request)
        safe_parameters = sanitize_request_parameters(parameters)
        manifest_path = self._manifest_path(PREVIOUS_RUNS_URL, safe_parameters)
        cached = self._cached_manifest(manifest_path)
        headers = {"User-Agent": "aladin-ensemble-research/0.1"}
        if cached is not None:
            etag = _text(cached.get("etag"), "etag", allow_none=True)
            modified = _text(cached.get("last_modified"), "last_modified", allow_none=True)
            if etag is not None:
                headers["If-None-Match"] = etag
            if modified is not None:
                headers["If-Modified-Since"] = modified
        response = self._request(Request(url, headers=headers))
        if response.status == 304:
            if cached is None:
                raise RuntimeError("HTTP 304 without cached response")
            return self._previous_from_cached(
                request,
                _sanitized_url(url),
                safe_parameters,
                manifest_path,
                cached,
            )
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}")
        checksum = hashlib.sha256(response.body).hexdigest()
        raw_path = self._raw_path(checksum)
        self._write_immutable(raw_path, response.body)
        retrieved_at = self._now()
        if retrieved_at.tzinfo is None or retrieved_at.utcoffset() != UTC.utcoffset(retrieved_at):
            raise ValueError("now must return timezone-aware UTC")
        payload: dict[str, JsonValue] = {
            "checksum_sha256": checksum,
            "etag": _header(response.headers, "etag"),
            "last_modified": _header(response.headers, "last-modified"),
            "request_endpoint": PREVIOUS_RUNS_URL,
            "request_parameters": [list(item) for item in safe_parameters],
            "retrieved_at": retrieved_at.isoformat(),
        }
        self._write_manifest(manifest_path, payload)
        return CachedResponse(
            raw_path,
            manifest_path,
            checksum,
            _previous_source_manifest(
                request,
                _sanitized_url(url),
                safe_parameters,
                checksum,
                retrieved_at,
            ),
        )

    def cached_previous(self, request: PreviousRunsRequest) -> CachedResponse | None:
        url, parameters = build_previous_runs_batch_url(request)
        safe_parameters = sanitize_request_parameters(parameters)
        manifest_path = self._manifest_path(PREVIOUS_RUNS_URL, safe_parameters)
        cached = self._cached_manifest(manifest_path)
        if cached is None:
            return None
        return self._previous_from_cached(
            request,
            _sanitized_url(url),
            safe_parameters,
            manifest_path,
            cached,
        )

    def _previous_from_cached(
        self,
        request: PreviousRunsRequest,
        source_url: str,
        parameters: Sequence[tuple[str, str]],
        manifest_path: Path,
        payload: Mapping[str, JsonValue],
    ) -> CachedResponse:
        checksum = _text(payload.get("checksum_sha256"), "checksum_sha256")
        assert checksum is not None
        raw_path = self._raw_path(checksum)
        if (
            not raw_path.is_file()
            or hashlib.sha256(raw_path.read_bytes()).hexdigest() != checksum
        ):
            raise ValueError("cached response checksum is invalid")
        retrieved_at = _timestamp(payload.get("retrieved_at"), "retrieved_at")
        return CachedResponse(
            raw_path,
            manifest_path,
            checksum,
            _previous_source_manifest(
                request,
                source_url,
                parameters,
                checksum,
                retrieved_at,
            ),
        )

    def _manifest_path(
        self, endpoint: str, parameters: Sequence[tuple[str, str]]
    ) -> Path:
        encoded = json.dumps(
            {"endpoint": endpoint, "parameters": parameters}, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        return self._root / "manifests" / f"{hashlib.sha256(encoded).hexdigest()}.json"

    def _request(self, request: Request) -> HttpResponse:
        for attempt in range(self._retry_attempts):
            try:
                response = self._fetch(request)
            except RuntimeError:
                if attempt + 1 == self._retry_attempts:
                    raise
            else:
                if response.status < 500 or attempt + 1 == self._retry_attempts:
                    return response
            self._sleep(self._retry_delay_seconds)
        raise RuntimeError("request retry loop ended unexpectedly")

    def _raw_path(self, checksum: str) -> Path:
        return self._root / "raw" / f"{checksum}.json"

    def _cached_manifest(self, path: Path) -> Mapping[str, JsonValue] | None:
        if not path.exists():
            return None
        value = _json_value(json.loads(path.read_text(encoding="utf-8")))
        if not isinstance(value, dict):
            raise ValueError("cached manifest must be an object")
        return value

    def _from_cached(
        self,
        manifest_path: Path,
        payload: Mapping[str, JsonValue],
        request: IssuedRunRequest,
        parameters: Sequence[tuple[str, str]],
    ) -> CachedResponse:
        checksum = _text(payload.get("checksum_sha256"), "checksum_sha256")
        assert checksum is not None
        raw_path = self._raw_path(checksum)
        if not raw_path.is_file() or hashlib.sha256(raw_path.read_bytes()).hexdigest() != checksum:
            raise ValueError("cached response checksum is invalid")
        retrieved_at = _timestamp(payload.get("retrieved_at"), "retrieved_at")
        return CachedResponse(
            raw_path,
            manifest_path,
            checksum,
            _source_manifest(
                request,
                _sanitized_url(build_single_run_url(request)[0]),
                parameters,
                checksum,
                retrieved_at,
            ),
        )

    def _write_immutable(self, path: Path, body: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            if path.read_bytes() != body:
                raise ValueError("cache collision for response checksum")
            return
        path.write_bytes(body)

    def _write_manifest(self, path: Path, payload: Mapping[str, JsonValue]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8"
        )


def parse_forecast_values(raw: bytes, request: IssuedRunRequest) -> tuple[ForecastValue, ...]:
    value = _json_value(json.loads(raw.decode("utf-8")))
    rows = value if isinstance(value, list) else [value]
    if not rows or any(not isinstance(row, dict) for row in rows):
        raise ValueError("Open-Meteo response must contain objects")
    parsed: list[ForecastValue] = []
    seen: set[tuple[str, datetime, datetime, float, float, str]] = set()
    expected_units: dict[str, str] = {}
    horizon_end = datetime.combine(
        request.run_time.date() + timedelta(days=request.forecast_days), time.min, UTC
    )
    for row in rows:
        assert isinstance(row, dict)
        latitude = _number(row.get("latitude"), "latitude")
        longitude = _number(row.get("longitude"), "longitude")
        elevation = _number(row.get("elevation"), "elevation")
        hourly = _mapping(row.get("hourly"), "hourly")
        units = _mapping(row.get("hourly_units"), "hourly_units")
        timestamps = _timestamps(hourly, units)
        for requested in request.variables:
            raw_unit = _text(units.get(requested), f"hourly_units.{requested}")
            assert raw_unit is not None
            previous_unit = expected_units.setdefault(requested, raw_unit)
            if previous_unit != raw_unit:
                raise ValueError(f"mixed unit for {requested}")
            values = hourly.get(requested)
            if not isinstance(values, list) or len(values) != len(timestamps):
                raise ValueError(f"Open-Meteo response is partial for {requested}")
            variable, unit = _VARIABLES[requested]
            for valid_time, raw_value in zip(timestamps, values, strict=True):
                if valid_time < request.run_time:
                    if raw_value is not None:
                        raise ValueError("forecast valid time precedes issued run")
                    continue
                if valid_time >= horizon_end:
                    raise ValueError("forecast valid time exceeds requested horizon")
                forecast = ForecastValue(
                    request.model_id,
                    request.run_time,
                    valid_time,
                    latitude,
                    longitude,
                    elevation,
                    variable,
                    canonical_value(variable, _optional_number(raw_value, requested), raw_unit),
                    unit,
                )
                key = (
                    forecast.model_id,
                    forecast.run_time,
                    forecast.valid_time,
                    forecast.latitude,
                    forecast.longitude,
                    forecast.variable,
                )
                if key in seen:
                    raise ValueError("duplicate model/run/valid forecast row")
                seen.add(key)
                parsed.append(forecast)
    return tuple(parsed)


def parse_previous_run_values(
    raw: bytes,
    request: PreviousRunsRequest,
    *,
    sample_hours: Sequence[int] | None = None,
) -> tuple[ForecastValue, ...]:
    if sample_hours is not None and (
        not sample_hours
        or len(set(sample_hours)) != len(sample_hours)
        or any(hour not in range(24) for hour in sample_hours)
    ):
        raise ValueError("sample_hours must be non-empty, unique UTC hours")
    selected_hours = None if sample_hours is None else frozenset(sample_hours)
    value = _json_value(json.loads(raw.decode("utf-8")))
    rows = value if isinstance(value, list) else [value]
    if any(not isinstance(row, dict) for row in rows):
        raise ValueError("Open-Meteo response must contain objects")
    if len(rows) != len(request.points):
        raise ValueError("Open-Meteo response row count does not match requested points")
    parsed: list[ForecastValue] = []
    seen: set[tuple[str, str, datetime, str]] = set()
    expected_units: dict[str, str] = {}
    for point, row in zip(request.points, rows, strict=True):
        assert isinstance(row, dict)
        latitude = _number(row.get("latitude"), "latitude")
        longitude = _number(row.get("longitude"), "longitude")
        elevation = _number(row.get("elevation"), "elevation")
        hourly = _mapping(row.get("hourly"), "hourly")
        units = _mapping(row.get("hourly_units"), "hourly_units")
        timestamps = _timestamps(hourly, units)
        if any(
            not request.start_date <= timestamp.date() <= request.end_date
            for timestamp in timestamps
        ):
            raise ValueError("Open-Meteo timestamp is outside the requested date range")
        for requested in request.variables:
            response_name = f"{requested}_previous_day{request.lead_days}"
            raw_unit = _text(units.get(response_name), f"hourly_units.{response_name}")
            assert raw_unit is not None
            previous_unit = expected_units.setdefault(requested, raw_unit)
            if previous_unit != raw_unit:
                raise ValueError(f"mixed unit for {requested}")
            values = hourly.get(response_name)
            if not isinstance(values, list) or len(values) != len(timestamps):
                raise ValueError(f"Open-Meteo response is partial for {response_name}")
            variable, unit = _VARIABLES[requested]
            for valid_time, raw_value in zip(timestamps, values, strict=True):
                if selected_hours is not None and valid_time.hour not in selected_hours:
                    continue
                if (
                    variable == "precipitation"
                    and not isinstance(raw_value, bool)
                    and isinstance(raw_value, int | float)
                    and isfinite(raw_value)
                    and raw_value < 0
                ):
                    raw_value = None
                forecast = ForecastValue(
                    request.model_id,
                    valid_time - timedelta(days=request.lead_days),
                    valid_time,
                    latitude,
                    longitude,
                    elevation,
                    variable,
                    canonical_value(
                        variable,
                        _optional_number(raw_value, response_name),
                        raw_unit,
                    ),
                    unit,
                    point.point_id,
                )
                key = (
                    point.point_id,
                    forecast.model_id,
                    forecast.valid_time,
                    forecast.variable,
                )
                if key in seen:
                    raise ValueError("duplicate point/model/valid forecast row")
                seen.add(key)
                parsed.append(forecast)
    return tuple(parsed)


def canonical_value(variable: str, value: float | None, unit: str) -> float | None:
    if value is None:
        return None
    canonical = _canonical_unit(variable)
    if unit == canonical:
        converted = value
    elif variable in {"temperature", "dew_point"} and unit == "°F":
        converted = (value - 32.0) * 5.0 / 9.0
    elif variable in {"sea_level_pressure", "surface_pressure"} and unit == "Pa":
        converted = value / 100.0
    elif variable == "wind_speed" and unit == "m/s":
        converted = value * 3.6
    elif variable == "wind_speed" and unit == "mph":
        converted = value * 1.609344
    elif variable == "wind_direction" and unit == "stupně":
        converted = value
    elif variable == "precipitation" and unit == "inch":
        converted = value * 25.4
    elif (
        variable in {"precipitation_probability", "relative_humidity", "cloud_cover"}
        and unit == "%"
    ):
        converted = value / 100.0
    else:
        raise ValueError(f"unsupported unit {unit!r} for {variable}")
    return _validated_value(variable, converted)


def _parameters(request: IssuedRunRequest) -> tuple[tuple[str, str], ...]:
    return tuple(
        sorted(
            {
                "forecast_days": str(request.forecast_days),
                "hourly": ",".join(request.variables),
                "latitude": str(request.latitude),
                "longitude": str(request.longitude),
                "models": request.model_id,
                "run": request.run_time.strftime("%Y-%m-%dT%H:%M"),
                "timeformat": "unixtime",
            }.items()
        )
    )


def _fetch(request: Request) -> HttpResponse:
    try:
        with urlopen(request, timeout=30) as response:
            return HttpResponse(response.status, dict(response.headers.items()), response.read())
    except HTTPError as error:
        return HttpResponse(error.code, dict(error.headers.items()), error.read())
    except (OSError, TimeoutError) as error:
        raise RuntimeError(f"request failed: {error}") from error


def _source_manifest(
    request: IssuedRunRequest,
    source_url: str,
    parameters: Sequence[tuple[str, str]],
    checksum: str,
    retrieved_at: datetime,
) -> SourceManifest:
    return SourceManifest(
        provider="Open-Meteo",
        documentation_url="https://open-meteo.com/en/docs/single-runs-api",
        license_name="Open-Meteo Free API (non-commercial; under 10,000 calls/day)",
        license_url=TERMS_URL,
        retrieved_at=retrieved_at,
        run_time=request.run_time,
        request_endpoint=SINGLE_RUNS_URL,
        request_parameters=tuple(parameters),
        requested_model_id=request.model_id,
        source_url=source_url,
        checksum_sha256=checksum,
        source_timestamp=request.run_time,
    )


def _previous_source_manifest(
    request: PreviousRunsRequest,
    source_url: str,
    parameters: Sequence[tuple[str, str]],
    checksum: str,
    retrieved_at: datetime,
) -> SourceManifest:
    return SourceManifest(
        provider="Open-Meteo",
        documentation_url="https://open-meteo.com/en/docs/previous-runs-api",
        license_name="Open-Meteo Free API (non-commercial; under 10,000 calls/day)",
        license_url=TERMS_URL,
        retrieved_at=retrieved_at,
        run_time=None,
        request_endpoint=PREVIOUS_RUNS_URL,
        request_parameters=tuple(parameters),
        requested_model_id=request.model_id,
        source_url=source_url,
        checksum_sha256=checksum,
    )


def _timestamps(
    hourly: Mapping[str, JsonValue], units: Mapping[str, JsonValue]
) -> tuple[datetime, ...]:
    if _text(units.get("time"), "hourly_units.time") != "unixtime":
        raise ValueError("Open-Meteo hourly time must be unixtime UTC")
    raw_times = hourly.get("time")
    if not isinstance(raw_times, list):
        raise ValueError("Open-Meteo response has no hourly times")
    timestamps: list[datetime] = []
    for raw in raw_times:
        seconds = _number(raw, "hourly.time")
        if not seconds.is_integer():
            raise ValueError("Open-Meteo hourly time must be a whole second")
        timestamps.append(datetime.fromtimestamp(seconds, UTC))
    if not timestamps:
        raise ValueError("Open-Meteo response has no hourly times")
    if any(
        later - earlier != timedelta(hours=1)
        for earlier, later in zip(timestamps, timestamps[1:], strict=False)
    ):
        raise ValueError("Open-Meteo hourly times are non-hourly, duplicate, or unordered")
    return tuple(timestamps)


def _canonical_unit(variable: str) -> str:
    if variable in {"temperature", "dew_point"}:
        return "°C"
    if variable in {"sea_level_pressure", "surface_pressure"}:
        return "hPa"
    if variable == "wind_speed":
        return "km/h"
    if variable == "wind_direction":
        return "°"
    if variable == "precipitation":
        return "mm"
    if variable in {"precipitation_probability", "relative_humidity", "cloud_cover"}:
        return "1"
    raise ValueError(f"unsupported canonical variable {variable}")


def _validated_value(variable: str, value: float) -> float:
    if (
        variable in {"precipitation_probability", "relative_humidity", "cloud_cover"}
        and not 0 <= value <= 1
    ):
        raise ValueError(f"{variable} must be from 0 through 1")
    if variable in {"precipitation", "wind_speed"} and value < 0:
        raise ValueError(f"{variable} must be non-negative")
    if variable == "wind_direction" and not 0 <= value <= 360:
        raise ValueError("wind_direction must be from 0 through 360")
    if variable in {"sea_level_pressure", "surface_pressure"} and value <= 0:
        raise ValueError(f"{variable} must be positive")
    return value


def _json_value(value: object) -> JsonValue:
    if value is None or isinstance(value, str | int | float | bool):
        return value
    if isinstance(value, list):
        return [_json_value(item) for item in cast(list[object], value)]
    if isinstance(value, dict):
        result: dict[str, JsonValue] = {}
        for key, item in cast(dict[object, object], value).items():
            if not isinstance(key, str):
                raise ValueError("Open-Meteo JSON object key is invalid")
            result[key] = _json_value(item)
        return result
    raise ValueError("Open-Meteo JSON value is invalid")


def _mapping(value: JsonValue | None, field_name: str) -> Mapping[str, JsonValue]:
    if not isinstance(value, dict):
        raise ValueError(f"Open-Meteo {field_name} must be an object")
    return value


def _number(value: JsonValue | None, field_name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float) or not isfinite(value):
        raise ValueError(f"Open-Meteo {field_name} must be finite")
    return float(value)


def _optional_number(value: JsonValue, field_name: str) -> float | None:
    if value is None:
        return None
    return _number(value, field_name)


def _text(value: JsonValue | None, field_name: str, *, allow_none: bool = False) -> str | None:
    if value is None and allow_none:
        return None
    if not isinstance(value, str) or not value:
        raise ValueError(f"Open-Meteo {field_name} must be text")
    return value


def _timestamp(value: JsonValue | None, field_name: str) -> datetime:
    text = _text(value, field_name)
    assert text is not None
    parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() != UTC.utcoffset(parsed):
        raise ValueError(f"Open-Meteo {field_name} must be UTC")
    return parsed


def _date(value: str, field_name: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as error:
        raise ValueError(f"{field_name} must be an ISO date") from error


def _header(headers: Mapping[str, str], name: str) -> str | None:
    for key, value in headers.items():
        if key.casefold() == name:
            return value
    return None


def _sanitized_url(url: str) -> str:
    parsed = urlsplit(url)
    parameters = sanitize_request_parameters(parse_qsl(parsed.query, keep_blank_values=True))
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, urlencode(parameters), ""))
