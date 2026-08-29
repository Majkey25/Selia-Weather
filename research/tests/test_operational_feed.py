from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from aladin_ensemble.operational_feed import (
    MODEL_IDS,
    latest_complete_cycle,
    select_lead_messages,
    validate_operational_values,
)
from aladin_ensemble.sources.grib_points import GeoPoint, SampledMessage, SampledPoint
from aladin_ensemble.types import ForecastValue


def message(run_time: datetime, lead_hour: int) -> SampledMessage:
    return SampledMessage(
        run_time=run_time,
        valid_time=run_time + timedelta(hours=lead_hour),
        unit="K",
        step_type="instant",
        start_step_hours=lead_hour,
        end_step_hours=lead_hour,
        values=(SampledPoint(50.0, 14.0, 50.0, 14.0, 0.0, 280.0),),
    )


def test_selects_conservative_complete_cycle() -> None:
    assert latest_complete_cycle(datetime(2026, 8, 29, 15, 1, tzinfo=UTC)) == datetime(
        2026,
        8,
        29,
        6,
        tzinfo=UTC,
    )
    assert latest_complete_cycle(datetime(2026, 8, 29, 5, 59, tzinfo=UTC)) == datetime(
        2026,
        8,
        28,
        18,
        tzinfo=UTC,
    )


def test_selects_every_required_lead_and_rejects_partial_axis() -> None:
    run_time = datetime(2026, 8, 29, 6, tzinfo=UTC)
    messages = tuple(message(run_time, lead) for lead in (0, 3, 6, 12, 18, 24))

    selected = select_lead_messages(messages, run_time, (0, 6, 12, 18, 24))

    assert tuple(item.end_step_hours for item in selected) == (0, 6, 12, 18, 24)
    with pytest.raises(ValueError, match="missing required lead"):
        select_lead_messages(messages[:-1], run_time, (0, 6, 12, 18, 24))


def test_operational_validator_rejects_any_partial_model() -> None:
    run_time = datetime(2026, 8, 29, 6, tzinfo=UTC)
    point = GeoPoint(50.0, 14.0)
    values = tuple(
        ForecastValue(
            model_id=model_id,
            run_time=run_time,
            valid_time=run_time,
            latitude=point.latitude,
            longitude=point.longitude,
            elevation_m=250.0,
            variable=variable,
            value=1.0,
            unit=unit,
        )
        for model_id in MODEL_IDS
        for variable, unit in (
            ("temperature_2m", "°C"),
            ("wind_u_10m", "m/s"),
            ("wind_v_10m", "m/s"),
        )
    )

    validate_operational_values(values, (point,), (0,))
    with pytest.raises(ValueError, match="incomplete"):
        validate_operational_values(values[:-1], (point,), (0,))
