from __future__ import annotations

import hashlib
import json
from datetime import UTC, date, datetime
from pathlib import Path
from typing import cast
from urllib.request import Request

import pytest

from aladin_ensemble.sources.open_meteo_runs import (
    SINGLE_RUNS_URL,
    CachedDownloader,
    ForecastPoint,
    HttpResponse,
    IssuedRunRequest,
    PreviousRunsRequest,
    build_previous_runs_batch_url,
    build_previous_runs_url,
    build_single_run_url,
    estimate_download_budget,
    estimate_previous_runs_budget,
    parse_forecast_values,
    parse_previous_run_values,
    sanitize_request_parameters,
)

FIXTURES = Path(__file__).parent / "fixtures" / "forecast"
FIXTURE = (FIXTURES / "single-run-chmi-aladin-cz-20260824.json").read_bytes()
RUN_TIME = datetime(2026, 8, 24, tzinfo=UTC)


def _request(*, forecast_days: int = 1) -> IssuedRunRequest:
    return IssuedRunRequest(
        model_id="chmi_aladin_cz_1km",
        run_time=RUN_TIME,
        latitude=50.0755,
        longitude=14.4378,
        variables=(
            "temperature_2m",
            "relative_humidity_2m",
            "pressure_msl",
            "wind_speed_10m",
            "wind_direction_10m",
            "precipitation",
        ),
        forecast_days=forecast_days,
    )


def _payload() -> dict[str, object]:
    payload: object = json.loads(FIXTURE)
    return _mapping(payload)


def _mapping(value: object) -> dict[str, object]:
    assert isinstance(value, dict)
    return cast(dict[str, object], value)


def _list(value: object) -> list[object]:
    assert isinstance(value, list)
    return cast(list[object], value)


def test_parses_actual_single_run_with_original_run_time() -> None:
    five_day = _payload()
    hourly = _mapping(five_day["hourly"])
    raw_times = _list(hourly["time"])
    hourly["time"] = [cast(int, item) + 5 * 86_400 for item in raw_times]
    values = parse_forecast_values(json.dumps(five_day).encode(), _request(forecast_days=6))

    assert len(values) == 144
    assert values[0].run_time == RUN_TIME
    assert values[0].valid_time == datetime(2026, 8, 29, tzinfo=UTC)
    assert values[0].variable == "temperature"
    assert values[0].unit == "°C"
    assert values[0].value == 13.0


def test_urls_use_documented_single_and_previous_run_contracts() -> None:
    single_url, single_parameters = build_single_run_url(_request())
    previous_url, previous_parameters = build_previous_runs_url(
        _request(), start_date="2026-08-24", end_date="2026-08-25", lead_days=5
    )

    assert single_url.startswith(SINGLE_RUNS_URL)
    assert ("run", "2026-08-24T00:00") in single_parameters
    assert ("models", "chmi_aladin_cz_1km") in single_parameters
    assert "temperature_2m_previous_day5" in previous_url
    assert ("start_date", "2026-08-24") in previous_parameters


def test_parser_rejects_naive_malformed_duplicate_and_mixed_unit_rows() -> None:
    with pytest.raises(ValueError, match="run_time"):
        IssuedRunRequest(
            "chmi_aladin_cz_1km",
            datetime(2026, 8, 24),
            50.0,
            14.0,
            ("temperature_2m",),
            1,
        )

    with pytest.raises(ValueError, match="run_time"):
        IssuedRunRequest(
            "chmi_aladin_cz_1km",
            datetime(2026, 8, 24, 0, 30, tzinfo=UTC),
            50.0,
            14.0,
            ("temperature_2m",),
            1,
        )

    with pytest.raises(ValueError, match="run_time"):
        IssuedRunRequest(
            "chmi_aladin_cz_1km",
            cast(datetime, None),
            50.0,
            14.0,
            ("temperature_2m",),
            1,
        )

    duplicate = _payload()
    hourly = _mapping(duplicate["hourly"])
    times = _list(hourly["time"])
    times[1] = times[0]
    with pytest.raises(ValueError, match="duplicate"):
        parse_forecast_values(json.dumps(duplicate).encode(), _request())

    non_hourly = _payload()
    non_hourly_hourly = _mapping(non_hourly["hourly"])
    non_hourly_times = _list(non_hourly_hourly["time"])
    non_hourly_times[1] = cast(int, non_hourly_times[0]) + 5_400
    with pytest.raises(ValueError, match="non-hourly"):
        parse_forecast_values(json.dumps(non_hourly).encode(), _request())

    mixed = [_payload(), _payload()]
    units = _mapping(mixed[1]["hourly_units"])
    units["temperature_2m"] = "°F"
    with pytest.raises(ValueError, match="mixed"):
        parse_forecast_values(json.dumps(mixed).encode(), _request())

    malformed = _payload()
    malformed_hourly = _mapping(malformed["hourly"])
    malformed_hourly.pop("temperature_2m")
    with pytest.raises(ValueError, match="temperature_2m"):
        parse_forecast_values(json.dumps(malformed).encode(), _request())

    beyond_requested_horizon = _payload()
    beyond_hourly = _mapping(beyond_requested_horizon["hourly"])
    beyond_times = _list(beyond_hourly["time"])
    beyond_hourly["time"] = [cast(int, item) + 86_400 for item in beyond_times]
    with pytest.raises(ValueError, match="horizon"):
        parse_forecast_values(json.dumps(beyond_requested_horizon).encode(), _request())


def test_parser_rejects_invalid_canonical_ranges() -> None:
    invalid_humidity = _payload()
    humidity_hourly = _mapping(invalid_humidity["hourly"])
    humidity = _list(humidity_hourly["relative_humidity_2m"])
    humidity[0] = 101
    with pytest.raises(ValueError, match="relative_humidity"):
        parse_forecast_values(json.dumps(invalid_humidity).encode(), _request())

    negative_rain = _payload()
    rain_hourly = _mapping(negative_rain["hourly"])
    rain = _list(rain_hourly["precipitation"])
    rain[1] = -0.1
    with pytest.raises(ValueError, match="precipitation"):
        parse_forecast_values(json.dumps(negative_rain).encode(), _request())


def test_downloader_caches_immutable_bytes_and_uses_conditional_request(tmp_path: Path) -> None:
    calls: list[Request] = []

    def fetch(request: Request) -> HttpResponse:
        calls.append(request)
        if len(calls) == 1:
            return HttpResponse(200, {"ETag": '"fixture"'}, FIXTURE)
        return HttpResponse(304, {}, b"")

    downloader = CachedDownloader(tmp_path, fetch=fetch, now=lambda: RUN_TIME)
    first = downloader.download(_request())
    second = downloader.download(_request())

    assert first.checksum_sha256 == hashlib.sha256(FIXTURE).hexdigest()
    assert second.path == first.path
    assert first.path.read_bytes() == FIXTURE
    assert calls[1].get_header("If-none-match") == '"fixture"'
    assert "token" not in second.manifest_path.read_text(encoding="utf-8")


def test_downloader_rejects_cache_collision_http_error_and_budget_boundary(tmp_path: Path) -> None:
    checksum = hashlib.sha256(FIXTURE).hexdigest()
    raw = tmp_path / "raw"
    raw.mkdir()
    (raw / f"{checksum}.json").write_bytes(b"wrong bytes")

    downloader = CachedDownloader(
        tmp_path,
        fetch=lambda _: HttpResponse(200, {}, FIXTURE),
        now=lambda: RUN_TIME,
    )
    with pytest.raises(ValueError, match="cache collision"):
        downloader.download(_request())

    failing = CachedDownloader(
        tmp_path / "failure",
        fetch=lambda _: HttpResponse(503, {}, b"offline"),
        now=lambda: RUN_TIME,
    )
    with pytest.raises(RuntimeError, match="HTTP 503"):
        failing.download(_request())

    budget = estimate_download_budget((_request(),), provider_limit=2)
    assert budget.expected_http_requests == 1
    budget.require_within_limit()
    with pytest.raises(ValueError, match="reaches"):
        estimate_download_budget((_request(),), provider_limit=1).require_within_limit()


def test_manifest_parameters_redact_future_credentials() -> None:
    assert sanitize_request_parameters(
        (("latitude", "50.0"), ("token", "secret"), ("API_KEY", "secret"))
    ) == (("API_KEY", "[redacted]"), ("latitude", "50.0"), ("token", "[redacted]"))


def _previous_request(*, lead_days: int = 2) -> PreviousRunsRequest:
    return PreviousRunsRequest(
        model_id="dwd_icon_eu",
        points=(
            ForecastPoint("prague", 50.0755, 14.4378),
            ForecastPoint("brno", 49.1951, 16.6068),
        ),
        variables=("temperature_2m", "precipitation"),
        start_date=date(2026, 7, 1),
        end_date=date(2026, 7, 1),
        lead_days=lead_days,
    )


def _previous_payload() -> bytes:
    times = [
        int(datetime(2026, 7, 1, 0, tzinfo=UTC).timestamp()),
        int(datetime(2026, 7, 1, 1, tzinfo=UTC).timestamp()),
    ]
    rows: list[dict[str, object]] = []
    for latitude, longitude, elevation, temperatures, precipitation in (
        (50.08, 14.44, 240.0, [18.0, 19.0], [0.0, 0.2]),
        (49.20, 16.61, 250.0, [17.0, 18.0], [0.1, None]),
    ):
        rows.append(
            {
                "latitude": latitude,
                "longitude": longitude,
                "elevation": elevation,
                "hourly_units": {
                    "time": "unixtime",
                    "temperature_2m_previous_day2": "°C",
                    "precipitation_previous_day2": "mm",
                },
                "hourly": {
                    "time": times,
                    "temperature_2m_previous_day2": temperatures,
                    "precipitation_previous_day2": precipitation,
                },
            }
        )
    return json.dumps(rows).encode()


def test_previous_runs_batches_czech_points_and_preserves_fixed_lead() -> None:
    request = _previous_request()

    url, parameters = build_previous_runs_batch_url(request)
    values = parse_previous_run_values(_previous_payload(), request)

    assert "latitude=50.0755%2C49.1951" in url
    assert "longitude=14.4378%2C16.6068" in url
    assert ("models", "dwd_icon_eu") in parameters
    assert ("hourly", "temperature_2m_previous_day2,precipitation_previous_day2") in parameters
    assert len(values) == 8
    assert values[0].requested_point_id == "prague"
    assert values[0].valid_time == datetime(2026, 7, 1, 0, tzinfo=UTC)
    assert values[0].run_time == datetime(2026, 6, 29, 0, tzinfo=UTC)
    assert values[-1].requested_point_id == "brno"
    assert values[-1].value is None


def test_previous_runs_treats_nonphysical_negative_precipitation_as_missing() -> None:
    raw_payload: object = json.loads(_previous_payload())
    payload = cast(list[dict[str, object]], _list(raw_payload))
    hourly = _mapping(payload[0]["hourly"])
    hourly["precipitation_previous_day2"] = [-0.2, 0.2]

    values = parse_previous_run_values(json.dumps(payload).encode(), _previous_request())
    rain = [
        value
        for value in values
        if value.requested_point_id == "prague" and value.variable == "precipitation"
    ]

    assert [value.value for value in rain] == [None, 0.2]


def test_previous_runs_rejects_invalid_boundaries_and_response_shape() -> None:
    with pytest.raises(ValueError, match="unique"):
        PreviousRunsRequest(
            "dwd_icon_eu",
            (ForecastPoint("prague", 50.0, 14.0), ForecastPoint("prague", 49.0, 16.0)),
            ("temperature_2m",),
            date(2026, 7, 1),
            date(2026, 7, 2),
            1,
        )
    with pytest.raises(ValueError, match="lead_days"):
        _previous_request(lead_days=0)
    with pytest.raises(ValueError, match="row count"):
        raw_payload: object = json.loads(_previous_payload())
        parse_previous_run_values(json.dumps(_list(raw_payload)[:1]).encode(), _previous_request())

    raw_outside: object = json.loads(_previous_payload())
    outside = cast(list[dict[str, object]], _list(raw_outside))
    hourly = _mapping(outside[0]["hourly"])
    hourly["time"] = [
        int(datetime(2026, 6, 30, 23, tzinfo=UTC).timestamp()),
        int(datetime(2026, 7, 1, 0, tzinfo=UTC).timestamp()),
    ]
    with pytest.raises(ValueError, match="date range"):
        parse_previous_run_values(json.dumps(outside).encode(), _previous_request())


def test_previous_parser_filters_hours_before_materializing_rows() -> None:
    values = parse_previous_run_values(
        _previous_payload(),
        _previous_request(),
        sample_hours=(1,),
    )

    assert len(values) == 4
    assert {value.valid_time.hour for value in values} == {1}
    with pytest.raises(ValueError, match="sample_hours"):
        parse_previous_run_values(_previous_payload(), _previous_request(), sample_hours=())


def test_previous_runs_budget_counts_batched_requests_not_points() -> None:
    requests = (_previous_request(lead_days=1), _previous_request(lead_days=2))

    budget = estimate_previous_runs_budget(requests, provider_limit=10)

    assert budget.candidate_count == 1
    assert budget.location_count == 2
    assert budget.expected_http_requests == 2
    budget.require_within_limit()
    with pytest.raises(ValueError, match="reaches"):
        estimate_previous_runs_budget(requests, provider_limit=2).require_within_limit()


def test_downloader_caches_batched_previous_runs(tmp_path: Path) -> None:
    body = _previous_payload()
    calls: list[Request] = []

    def fetch(request: Request) -> HttpResponse:
        calls.append(request)
        if len(calls) == 1:
            return HttpResponse(200, {"ETag": '"previous"'}, body)
        return HttpResponse(304, {}, b"")

    downloader = CachedDownloader(tmp_path, fetch=fetch, now=lambda: RUN_TIME)

    first = downloader.download_previous(_previous_request())
    second = downloader.download_previous(_previous_request())

    assert first.path.read_bytes() == body
    assert second.path == first.path
    assert first.manifest.run_time is None
    assert first.manifest.request_endpoint is not None
    assert first.manifest.request_endpoint.startswith("https://previous-runs-api.open-meteo.com")
    assert calls[1].get_header("If-none-match") == '"previous"'


def test_previous_downloader_rejects_oversized_response_before_cache(tmp_path: Path) -> None:
    downloader = CachedDownloader(
        tmp_path,
        fetch=lambda _: HttpResponse(200, {}, b"x" * 33),
        max_response_bytes=32,
    )

    with pytest.raises(ValueError, match="size limit"):
        downloader.download_previous(_previous_request())

    assert not (tmp_path / "raw").exists()


def test_previous_downloader_retries_only_operational_failure(tmp_path: Path) -> None:
    calls = 0

    def transient(_: Request) -> HttpResponse:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise RuntimeError("request failed: reset")
        return HttpResponse(200, {}, _previous_payload())

    downloader = CachedDownloader(
        tmp_path,
        fetch=transient,
        now=lambda: RUN_TIME,
        retry_delay_seconds=0,
    )

    response = downloader.download_previous(_previous_request())

    assert response.path.is_file()
    assert calls == 2


@pytest.mark.parametrize(
    ("retry_after", "expected_delay"),
    (("3", 3.0), ("100000", 60.0), ("invalid", 1.0), ("-1", 1.0)),
)
def test_previous_downloader_retries_rate_limit_using_retry_after(
    tmp_path: Path,
    retry_after: str,
    expected_delay: float,
) -> None:
    calls = 0
    delays: list[float] = []

    def rate_limited(_: Request) -> HttpResponse:
        nonlocal calls
        calls += 1
        if calls == 1:
            return HttpResponse(429, {"Retry-After": retry_after}, b"")
        return HttpResponse(200, {}, _previous_payload())

    downloader = CachedDownloader(
        tmp_path,
        fetch=rate_limited,
        now=lambda: RUN_TIME,
        retry_delay_seconds=1,
        sleeper=delays.append,
    )

    response = downloader.download_previous(_previous_request())

    assert response.path.is_file()
    assert calls == 2
    assert delays == [expected_delay]


def test_previous_downloader_reads_verified_archive_cache_offline(tmp_path: Path) -> None:
    request = _previous_request()
    downloader = CachedDownloader(
        tmp_path,
        fetch=lambda _: HttpResponse(200, {}, _previous_payload()),
        now=lambda: RUN_TIME,
    )
    downloaded = downloader.download_previous(request)
    offline = CachedDownloader(
        tmp_path,
        fetch=lambda _: (_ for _ in ()).throw(RuntimeError("network must not run")),
        now=lambda: RUN_TIME,
    )

    cached = offline.cached_previous(request)

    assert cached is not None
    assert cached.path == downloaded.path
    assert cached.checksum_sha256 == downloaded.checksum_sha256
