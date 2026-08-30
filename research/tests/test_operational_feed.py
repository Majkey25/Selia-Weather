from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from aladin_ensemble import operational_feed
from aladin_ensemble.operational_feed import (
    MODEL_IDS,
    latest_complete_cycle,
    operational_points_for_model,
    select_lead_messages,
    validate_operational_values,
)
from aladin_ensemble.sources.grib_points import (
    GeoPoint,
    GribPointIndex,
    IndexedPoint,
    SampledMessage,
    SampledPoint,
)
from aladin_ensemble.sources.official_runs import CachedGrib
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
            ("precipitation", "mm"),
            ("temperature_2m", "°C"),
            ("wind_u_10m", "m/s"),
            ("wind_v_10m", "m/s"),
        )
    )

    validate_operational_values(values, (point,), (0,))
    with pytest.raises(ValueError, match="incomplete"):
        validate_operational_values(values[:-1], (point,), (0,))


def test_operational_model_outputs_interval_precipitation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    run_time = datetime(2026, 8, 29, 0, tzinfo=UTC)
    point = GeoPoint(50.0, 14.0)
    leads = (0, 6, 12)

    def download(variable: str, lead: int) -> CachedGrib:
        return CachedGrib(Path(f"{variable}-{lead}.grib2"), "a" * 64, "https://example.com", False)

    def decode(
        path: Path,
        points: tuple[GeoPoint, ...],
        *,
        point_index: GribPointIndex | None = None,
    ) -> tuple[SampledMessage, ...]:
        del points, point_index
        variable, lead_text = path.stem.rsplit("-", 1)
        lead = int(lead_text)
        precipitation = {0: 0.0, 6: 3.0, 12: 5.0}
        return (
            SampledMessage(
                run_time=run_time,
                valid_time=run_time + timedelta(hours=lead),
                unit={
                    "precipitation": "kg/m²",
                    "temperature_2m": "K",
                    "wind_u_10m": "m/s",
                    "wind_v_10m": "m/s",
                }[variable],
                step_type="accum" if variable == "precipitation" else "instant",
                start_step_hours=0 if variable == "precipitation" else lead,
                end_step_hours=lead,
                values=(
                    SampledPoint(
                        point.latitude,
                        point.longitude,
                        point.latitude,
                        point.longitude,
                        0.0,
                        precipitation[lead] if variable == "precipitation" else 280.0,
                    ),
                ),
            ),
        )

    monkeypatch.setattr(operational_feed, "decode_grib_points", decode)
    values = operational_feed.load_sampled_model_values(
        "noaa_gfs",
        run_time,
        (point,),
        {point: 250.0},
        leads,
        download,
        point_index=GribPointIndex(
            "grid",
            (IndexedPoint(50.0, 14.0, 50.0, 14.0, 0.0, 0),),
        ),
    )

    precipitation = [value.value for value in values if value.variable == "precipitation"]
    assert precipitation == [0.0, 3.0, 2.0]


def test_normalizes_chmi_total_precipitation_metadata() -> None:
    run_time = datetime(2026, 8, 29, 0, tzinfo=UTC)
    point = GeoPoint(50.0, 14.0)
    messages = tuple(
        SampledMessage(
            run_time=run_time,
            valid_time=run_time + timedelta(hours=lead),
            unit="unknown",
            step_type="instant",
            start_step_hours=lead,
            end_step_hours=lead,
            values=(SampledPoint(50.0, 14.0, 50.0, 14.0, 0.0, amount),),
        )
        for lead, amount in ((6, 3.0), (12, 5.0))
    )

    normalized = operational_feed.normalize_chmi_precipitation_messages(messages)
    values = operational_feed.to_forecast_values(
        normalized,
        model_id="chmi_aladin_cz_1km",
        variable="precipitation",
        canonical_unit="mm",
        elevation_by_point={point: 250.0},
    )

    assert [message.end_step_hours for message in normalized] == [0, 6, 12]
    assert [value.value for value in values] == [0.0, 3.0, 2.0]
    with pytest.raises(ValueError, match="metadata"):
        operational_feed.normalize_chmi_precipitation_messages(
            (replace(messages[0], unit="mm"),),
        )


def test_aladin_uses_only_its_official_domain_without_shrinking_other_models() -> None:
    points = (
        GeoPoint(48.45, 11.9),
        GeoPoint(48.6, 12.1),
        GeoPoint(51.05, 18.85),
        GeoPoint(51.2, 19.0),
    )

    assert operational_points_for_model("chmi_aladin_cz_1km", points) == points[1:3]
    assert operational_points_for_model("noaa_gfs", points) == points
