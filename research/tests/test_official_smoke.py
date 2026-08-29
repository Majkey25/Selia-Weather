from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import pytest

from aladin_ensemble.sources.official_smoke import SmokeConfig, validate_decode_output


@pytest.mark.parametrize(
    "source, lead_hour",
    (
        ("aifs", 6),
        ("chmi", 0),
        ("dwd", 0),
        ("gefs-mean", 840),
        ("ifs", 3),
        ("noaa", 3),
    ),
)
def test_smoke_config_accepts_supported_source(source: str, lead_hour: int) -> None:
    config = SmokeConfig(
        source=source,
        run_time=datetime(2026, 8, 29, 0, tzinfo=UTC),
        lead_hour=lead_hour,
        variable="temperature_2m",
    )

    assert config.source == source


def test_smoke_config_rejects_unknown_source() -> None:
    with pytest.raises(ValueError, match="source"):
        SmokeConfig(
            source="unknown",
            run_time=datetime(2026, 8, 29, 0, tzinfo=UTC),
            lead_hour=0,
            variable="temperature_2m",
        )


def test_decode_output_requires_real_grid_values() -> None:
    assert validate_decode_output("Latitude Longitude Value\n49.0 14.0 293.15\n") == 1

    with pytest.raises(ValueError, match="header"):
        validate_decode_output("not grib output\n")
    with pytest.raises(ValueError, match="values"):
        validate_decode_output("Latitude Longitude Value\n")


def test_decode_output_accepts_repeated_header_for_multimessage_grib() -> None:
    output = (
        "Latitude Longitude Value\n49.0 14.0 293.15\n"
        "Latitude Longitude Value\n49.0 14.0 294.15\n"
    )

    assert validate_decode_output(output) == 2


def test_smoke_workflow_installs_eccodes_and_runs_every_source() -> None:
    workflow = Path(__file__).parents[2] / ".github" / "workflows" / "official-data-smoke.yml"

    source = workflow.read_text(encoding="utf-8")

    assert "libeccodes-tools" in source
    assert "python -m aladin_ensemble.sources.official_smoke" in source
    for name in ("aifs", "chmi", "dwd", "gefs-mean", "ifs", "noaa"):
        assert name in source
