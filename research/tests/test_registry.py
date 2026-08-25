from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from aladin_ensemble.registry import (
    JsonValue,
    ModelRegistry,
    estimate_request_budget,
)
from aladin_ensemble.sources.probe import (
    ModelSeed,
    SamplePoint,
    probe_candidate,
    probe_registry,
    write_probe_registry,
)
from aladin_ensemble.types import ModelCandidate, SourceManifest


def hourly_payload(
    *, values: list[float | None] | None = None, times: list[str] | None = None
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
        "hourly": hourly,
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
    budget = estimate_request_budget(
        candidate_count=17,
        location_count=7,
        run_count=180,
        variable_count=3,
        date_count=180,
        provider_limit=10_000,
    )
    with pytest.raises(ValueError, match="exceeds"):
        budget.require_within_limit()


def test_request_budget_counts_probe_and_download_calls() -> None:
    budget = estimate_request_budget(
        candidate_count=2,
        location_count=7,
        run_count=3,
        variable_count=3,
        date_count=2,
        provider_limit=100,
    )
    assert budget.expected_calls == 16
    budget.require_within_limit()


def test_probe_rejects_invalid_hourly_data() -> None:
    seed = ModelSeed("dwd_icon_eu", "DWD ICON-EU", "DWD", "https://open-meteo.com/en/docs/dwd-api")
    point = SamplePoint("test", 50.0, 14.0)

    def invalid_fetch(_: str) -> JsonValue:
        return hourly_payload(times=["invalid"])

    with pytest.raises(ValueError, match="Invalid isoformat"):
        probe_candidate(seed, points=(point,), fetch_json=invalid_fetch)


def test_probe_excludes_unavailable_source() -> None:
    seed = ModelSeed("dwd_icon_eu", "DWD ICON-EU", "DWD", "https://open-meteo.com/en/docs/dwd-api")

    def unavailable_fetch(_: str) -> JsonValue:
        raise OSError("connection reset")

    registry, excluded = probe_registry(seeds=(seed,), fetch_json=unavailable_fetch)
    assert registry.model_ids == ()
    assert excluded == (("dwd_icon_eu", "probe failed: connection reset"),)


def test_probe_excludes_incomplete_hourly_series() -> None:
    seed = ModelSeed("dwd_icon_eu", "DWD ICON-EU", "DWD", "https://open-meteo.com/en/docs/dwd-api")

    def incomplete_fetch(url: str) -> JsonValue:
        if "previous-runs" in url:
            return hourly_payload()
        return hourly_payload(values=[1.0] + [None] * 24)

    registry, excluded = probe_registry(
        seeds=(seed,),
        points=(SamplePoint("test", 50.0, 14.0),),
        fetch_json=incomplete_fetch,
    )
    assert registry.model_ids == ()
    assert excluded == (("dwd_icon_eu", "probe failed: coverage is below 90 percent"),)


def test_probe_registry_json_sorts_exclusions(tmp_path: Path) -> None:
    registry = ModelRegistry()
    registry.add(candidate())
    output = tmp_path / "model-registry.json"
    write_probe_registry(output, registry, (("z_model", "z"), ("a_model", "a")))
    text = output.read_text(encoding="utf-8")
    assert text.find('"a_model"') < text.find('"z_model"')
