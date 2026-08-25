from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from aladin_ensemble.types import ModelCandidate

type JsonScalar = str | int | float | bool | None
type JsonValue = JsonScalar | list[JsonValue] | dict[str, JsonValue]


@dataclass(frozen=True, slots=True)
class RequestBudget:
    candidate_count: int
    location_count: int
    run_count: int
    variable_count: int
    date_count: int
    expected_calls: int
    provider_limit: int

    def __post_init__(self) -> None:
        if min(
            self.candidate_count,
            self.location_count,
            self.run_count,
            self.variable_count,
            self.date_count,
            self.expected_calls,
            self.provider_limit,
        ) < 0:
            raise ValueError("request budget values cannot be negative")

    def require_within_limit(self) -> None:
        if self.expected_calls > self.provider_limit:
            raise ValueError(
                f"expected calls {self.expected_calls} exceeds provider limit {self.provider_limit}"
            )

    def summary(self) -> str:
        return "\n".join(
            (
                f"candidates: {self.candidate_count}",
                f"locations: {self.location_count}",
                f"runs: {self.run_count}",
                f"variables: {self.variable_count}",
                f"dates: {self.date_count}",
                f"expected calls: {self.expected_calls}",
                f"provider limit: {self.provider_limit}",
            )
        )


def estimate_request_budget(
    *,
    candidate_count: int,
    location_count: int,
    run_count: int,
    variable_count: int,
    date_count: int,
    provider_limit: int,
) -> RequestBudget:
    if min(candidate_count, location_count, run_count, variable_count, date_count) < 1:
        raise ValueError("candidate, location, run, variable, and date counts must be positive")
    return RequestBudget(
        candidate_count=candidate_count,
        location_count=location_count,
        run_count=run_count,
        variable_count=variable_count,
        date_count=date_count,
        expected_calls=candidate_count * (date_count * run_count + 2),
        provider_limit=provider_limit,
    )


class ModelRegistry:
    def __init__(self) -> None:
        self._candidates: dict[str, ModelCandidate] = {}

    @property
    def model_ids(self) -> tuple[str, ...]:
        return tuple(sorted(self._candidates))

    def add(self, candidate: ModelCandidate) -> None:
        if candidate.model_id in self._candidates:
            raise ValueError(f"duplicate model: {candidate.model_id}")
        if error := candidate.eligibility_error():
            raise ValueError(error)
        self._candidates[candidate.model_id] = candidate

    def to_json(self) -> str:
        return json.dumps(self.payload(), sort_keys=True, separators=(",", ":")) + "\n"

    def write(self, path: Path) -> None:
        path.write_text(self.to_json(), encoding="utf-8")

    def payload(self) -> dict[str, JsonValue]:
        return {"models": [self._record(self._candidates[model_id]) for model_id in self.model_ids]}

    @staticmethod
    def _record(candidate: ModelCandidate) -> dict[str, JsonValue]:
        manifest = candidate.manifest
        required_variables: list[JsonValue] = []
        returned_variables: list[JsonValue] = []
        for variable in sorted(candidate.required_variables):
            required_variables.append(variable)
        for variable in sorted(candidate.returned_variables):
            returned_variables.append(variable)
        return {
            "archive_verified": candidate.archive_verified,
            "available_horizon_hours": candidate.available_horizon_hours,
            "covered_points": candidate.covered_points,
            "display_name": candidate.display_name,
            "manifest": {
                "documentation_url": manifest.documentation_url,
                "license_name": manifest.license_name,
                "license_url": manifest.license_url,
                "provider": manifest.provider,
                "retrieved_at": manifest.retrieved_at.isoformat(),
                "run_time": manifest.run_time.isoformat() if manifest.run_time else None,
            },
            "model_id": candidate.model_id,
            "provider": candidate.provider,
            "required_horizon_hours": candidate.required_horizon_hours,
            "required_variables": required_variables,
            "returned_variables": returned_variables,
            "sample_points": candidate.sample_points,
            "verified": candidate.verified,
        }
