from __future__ import annotations

from collections.abc import Sequence
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from math import nan
from pathlib import Path
from typing import cast

import pytest

from aladin_ensemble.registry import (
    JsonValue,
    ModelRegistry,
    estimate_http_request_budget,
)
from aladin_ensemble.sources import probe as probe_module
from aladin_ensemble.sources.probe import (
    DefinitiveProbeError,
    ModelSeed,
    OperationalProbeError,
    ProbeResult,
    SamplePoint,
    probe_candidate,
    probe_registry,
    retrying_fetch_json,
    write_probe_registry,
)
from aladin_ensemble.types import (
    ForecastValue,
    ModelCandidate,
    Observation,
    ResponseMetadata,
    SourceManifest,
)


def hourly_payload(
    *, values: Sequence[float | None] | None = None, times: Sequence[str] | None = None
) -> JsonValue:
    hourly_values = values or [1.0] * 25
    hourly: dict[str, JsonValue] = {}
    timestamps: list[JsonValue] = []
    for timestamp in times or [
        (datetime(2026, 8, 25, tzinfo=UTC) + timedelta(hours=hour)).isoformat()
        for hour in range(25)
    ]:
        timestamps.append(timestamp)
    numeric_values: list[JsonValue] = []
    for value in hourly_values:
        numeric_values.append(value)
    hourly["time"] = timestamps
    hourly["temperature_2m"] = numeric_values
    hourly["precipitation"] = numeric_values
    hourly["wind_speed_10m"] = numeric_values
    return {
        "elevation": 250.0,
        "generationtime_ms": 1.0,
        "hourly": hourly,
        "latitude": 50.0,
        "longitude": 14.0,
        "timezone": "GMT",
    }


def candidate(
    model_id: str = "dwd_icon_eu",
    *,
    verified: bool = True,
    covered_points: int = 7,
    available_horizon_hours: int = 24,
    variables: frozenset[str] = frozenset(
        {"temperature_2m", "precipitation", "wind_speed_10m"}
    ),
    license_name: str = "Open-Meteo Free API",
) -> ModelCandidate:
    manifest = SourceManifest(
        provider="Open-Meteo",
        documentation_url="https://open-meteo.com/en/docs/dwd-api",
        license_name=license_name,
        license_url="https://open-meteo.com/en/terms",
        retrieved_at=datetime(2026, 8, 25, tzinfo=UTC),
        run_time=None,
    )
    return ModelCandidate(
        model_id=model_id,
        display_name="DWD ICON-EU",
        provider="DWD",
        required_variables=frozenset(
            {"temperature_2m", "precipitation", "wind_speed_10m"}
        ),
        returned_variables=variables,
        sample_points=7,
        covered_points=covered_points,
        required_horizon_hours=24,
        available_horizon_hours=available_horizon_hours,
        verified=verified,
        archive_verified=True,
        manifest=manifest,
    )


def test_registry_accepts_verified_eligible_candidate() -> None:
    registry = ModelRegistry()
    registry.add(candidate())
    assert registry.model_ids == ("dwd_icon_eu",)


def test_registry_rejects_unverified_or_duplicate_model() -> None:
    registry = ModelRegistry()
    registry.add(candidate("icon_eu", verified=True))
    with pytest.raises(ValueError, match="duplicate"):
        registry.add(candidate("icon_eu", verified=True))
    with pytest.raises(ValueError, match="unverified"):
        registry.add(candidate("unknown", verified=False))


@pytest.mark.parametrize(
    ("update", "message"),
    [
        ({"covered_points": 6}, "coverage"),
        ({"available_horizon_hours": 23}, "horizon"),
        ({"returned_variables": frozenset({"temperature_2m"})}, "variables"),
        ({"manifest": replace(candidate().manifest, license_name="")}, "license"),
        ({"archive_verified": False}, "archive"),
    ],
)
def test_registry_rejects_ineligible_candidate(
    update: dict[str, bool | int | frozenset[str] | SourceManifest], message: str
) -> None:
    registry = ModelRegistry()
    with pytest.raises(ValueError, match=message):
        registry.add(replace(candidate(), **update))


def test_registry_json_is_deterministic_and_sorted() -> None:
    first = ModelRegistry()
    first.add(candidate("z_model"))
    first.add(candidate("a_model"))
    second = ModelRegistry()
    second.add(candidate("a_model"))
    second.add(candidate("z_model"))
    assert first.to_json() == second.to_json()
    assert first.to_json().find('"a_model"') < first.to_json().find('"z_model"')


def test_request_budget_fails_closed_above_limit() -> None:
    budget = estimate_http_request_budget(
        candidate_count=17,
        location_count=7,
        run_count=180,
        variable_count=3,
        date_count=180,
        provider_limit=10_000,
    )
    with pytest.raises(ValueError, match="reaches"):
        budget.require_within_limit()


def test_request_budget_counts_probe_and_download_calls() -> None:
    budget = estimate_http_request_budget(
        candidate_count=2,
        location_count=7,
        run_count=3,
        variable_count=3,
        date_count=2,
        provider_limit=100,
    )
    assert budget.expected_http_requests == 16
    budget.require_within_limit()
    retried = estimate_http_request_budget(
        candidate_count=2,
        location_count=7,
        run_count=3,
        variable_count=3,
        date_count=2,
        provider_limit=100,
        probe_attempts=2,
    )
    assert retried.expected_http_requests == 20


def test_probe_rejects_invalid_hourly_data() -> None:
    seed = ModelSeed("dwd_icon_eu", "DWD ICON-EU", "DWD", "https://open-meteo.com/en/docs/dwd-api")
    point = SamplePoint("test", 50.0, 14.0)

    def invalid_fetch(_: str) -> JsonValue:
        return hourly_payload(times=["invalid"])

    with pytest.raises(OperationalProbeError, match="Invalid isoformat"):
        probe_candidate(seed, points=(point,), fetch_json=invalid_fetch)


def test_probe_excludes_unavailable_source() -> None:
    seed = ModelSeed("dwd_icon_eu", "DWD ICON-EU", "DWD", "https://open-meteo.com/en/docs/dwd-api")

    def unavailable_fetch(_: str) -> JsonValue:
        raise OSError("connection reset")

    result = probe_registry(seeds=(seed,), fetch_json=unavailable_fetch)
    assert result.registry.model_ids == ()
    assert result.excluded == ()
    assert result.operational_failures == (("dwd_icon_eu", "connection reset"),)
    assert not result.complete


def test_probe_registry_paces_candidates_without_sleeping_after_last() -> None:
    seeds = (
        ModelSeed("model_a", "Model A", "Provider", "https://example.com/a"),
        ModelSeed("model_b", "Model B", "Provider", "https://example.com/b"),
    )
    sleeps: list[float] = []

    result = probe_registry(
        seeds=seeds,
        points=(SamplePoint("test", 50.0, 14.0),),
        fetch_json=lambda _: hourly_payload(),
        pause_seconds=0.5,
        sleeper=sleeps.append,
    )

    assert result.complete
    assert result.registry.model_ids == ("model_a", "model_b")
    assert sleeps == [0.5]


def test_retrying_fetch_retries_only_operational_failures() -> None:
    calls = 0
    sleeps: list[float] = []

    def transient(_: str) -> JsonValue:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise OperationalProbeError("connection reset")
        return {"ok": True}

    assert retrying_fetch_json(
        "https://example.com",
        fetch_json=transient,
        attempts=2,
        retry_delay_seconds=1.0,
        sleeper=sleeps.append,
    ) == {"ok": True}
    assert calls == 2
    assert sleeps == [1.0]

    definitive_calls = 0

    def definitive(_: str) -> JsonValue:
        nonlocal definitive_calls
        definitive_calls += 1
        raise DefinitiveProbeError("HTTP 400")

    with pytest.raises(DefinitiveProbeError, match="400"):
        retrying_fetch_json(
            "https://example.com",
            fetch_json=definitive,
            attempts=2,
            sleeper=sleeps.append,
        )
    assert definitive_calls == 1


def test_probe_marks_empty_archive_response_incomplete() -> None:
    seed = ModelSeed("dwd_icon_eu", "DWD ICON-EU", "DWD", "https://open-meteo.com/en/docs/dwd-api")

    def empty_archive_fetch(url: str) -> JsonValue:
        return [] if "previous-runs" in url else hourly_payload()

    result = probe_registry(
        seeds=(seed,),
        points=(SamplePoint("test", 50.0, 14.0),),
        fetch_json=empty_archive_fetch,
    )
    assert result.registry.model_ids == ()
    assert result.excluded == ()
    assert result.operational_failures == (
        ("dwd_icon_eu", "response format failed: Open-Meteo payload has no response rows"),
    )
    assert not result.complete


def test_probe_excludes_incomplete_hourly_series() -> None:
    seed = ModelSeed("dwd_icon_eu", "DWD ICON-EU", "DWD", "https://open-meteo.com/en/docs/dwd-api")

    def incomplete_fetch(url: str) -> JsonValue:
        if "previous-runs" in url:
            return hourly_payload()
        return hourly_payload(values=[1.0] + [None] * 24)

    result = probe_registry(
        seeds=(seed,),
        points=(SamplePoint("test", 50.0, 14.0),),
        fetch_json=incomplete_fetch,
    )
    assert result.registry.model_ids == ()
    assert result.excluded == (("dwd_icon_eu", "coverage is below 90 percent"),)
    assert result.complete


def test_probe_registry_json_sorts_exclusions(tmp_path: Path) -> None:
    registry = ModelRegistry()
    registry.add(candidate())
    output = tmp_path / "model-registry.json"
    write_probe_registry(output, ProbeResult(registry, (("z_model", "z"), ("a_model", "a")), ()))
    text = output.read_text(encoding="utf-8")
    assert text.find('"a_model"') < text.find('"z_model"')


def test_main_writes_incomplete_registry_and_fails_on_operational_error(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    output = tmp_path / "model-registry.json"
    monkeypatch.setattr(
        probe_module,
        "probe_registry",
        lambda **_: ProbeResult(ModelRegistry(), (), (("dwd_icon_eu", "HTTP 503"),)),
    )
    monkeypatch.setattr("sys.argv", ["probe", "--output", str(output)])
    assert probe_module.main() == 1
    assert '"status":"incomplete"' in output.read_text(encoding="utf-8")
    summary = capsys.readouterr().out
    assert "locations: 7" in summary
    assert "variables: 3" in summary
    assert "expected HTTP requests: 85" in summary


def test_http_request_budget_rejects_free_limit_boundary() -> None:
    within_limit = estimate_http_request_budget(
        candidate_count=1,
        location_count=8,
        run_count=1,
        variable_count=4,
        date_count=9_997,
        provider_limit=10_000,
    )
    assert within_limit.expected_http_requests == 9_999
    within_limit.require_within_limit()
    limit = estimate_http_request_budget(
        candidate_count=1,
        location_count=8,
        run_count=1,
        variable_count=4,
        date_count=9_998,
        provider_limit=10_000,
    )
    assert limit.expected_http_requests == 10_000
    with pytest.raises(ValueError, match="reaches"):
        limit.require_within_limit()


def test_probe_requires_archive_horizon_and_records_provenance() -> None:
    seed = ModelSeed("dwd_icon_eu", "DWD ICON-EU", "DWD", "https://open-meteo.com/en/docs/dwd-api")
    point = SamplePoint("test", 50.0, 14.0)

    def short_archive_fetch(url: str) -> JsonValue:
        if "previous-runs" in url:
            return hourly_payload(
                times=[
                    (datetime(2026, 8, 25, tzinfo=UTC) + timedelta(hours=hour)).isoformat()
                    for hour in range(24)
                ]
            )
        return hourly_payload()

    result = probe_registry(seeds=(seed,), points=(point,), fetch_json=short_archive_fetch)
    assert result.excluded == (("dwd_icon_eu", "archive availability is unverified"),)
    candidate_result = probe_candidate(
        seed,
        points=(point,),
        fetch_json=lambda _: hourly_payload(),
        now=datetime(2026, 8, 25, tzinfo=UTC),
    )
    manifest = candidate_result.manifest
    assert manifest.requested_model_id == "dwd_icon_eu"
    assert manifest.request_endpoint == "https://api.open-meteo.com/v1/forecast"
    assert ("models", "dwd_icon_eu") in manifest.request_parameters
    assert manifest.provider_model_id is None
    assert manifest.run_time is None
    assert manifest.responses == (ResponseMetadata(50.0, 14.0, 250.0, "GMT", 1.0),)


def test_probe_excludes_nonfinite_coverage_as_definitive() -> None:
    seed = ModelSeed("dwd_icon_eu", "DWD ICON-EU", "DWD", "https://open-meteo.com/en/docs/dwd-api")

    def nonfinite_fetch(url: str) -> JsonValue:
        if "previous-runs" in url:
            return hourly_payload()
        return hourly_payload(values=[nan] * 25)

    result = probe_registry(
        seeds=(seed,),
        points=(SamplePoint("test", 50.0, 14.0),),
        fetch_json=nonfinite_fetch,
    )
    assert result.complete
    assert result.excluded == (("dwd_icon_eu", "coverage is below 90 percent"),)


def test_records_reject_naive_time_and_nonfinite_or_boolean_values() -> None:
    with pytest.raises(ValueError, match="run_time"):
        ForecastValue(
            "model",
            datetime(2026, 8, 25),
            datetime(2026, 8, 25, tzinfo=UTC),
            50.0,
            14.0,
            250.0,
            "temperature_2m",
            1.0,
            "°C",
        )
    with pytest.raises(ValueError, match="latitude"):
        Observation(
            "CHMI",
            "station",
            datetime(2026, 8, 25, tzinfo=UTC),
            nan,
            14.0,
            250.0,
            "temperature_2m",
            1.0,
            "°C",
        )
    with pytest.raises(ValueError, match="value"):
        Observation(
            "CHMI",
            "station",
            datetime(2026, 8, 25, tzinfo=UTC),
            50.0,
            14.0,
            250.0,
            "temperature_2m",
            cast(float, True),
            "°C",
        )
