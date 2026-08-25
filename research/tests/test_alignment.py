from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import pytest

from aladin_ensemble.align import (
    DateRange,
    align_station_forecasts,
    split_train_holdout,
    validate_spatial_precipitation,
)
from aladin_ensemble.types import ForecastValue, Observation, SpatialObservation


def _forecast(
    *,
    run_time: datetime = datetime(2026, 10, 24, tzinfo=UTC),
    valid_time: datetime = datetime(2026, 10, 25, tzinfo=UTC),
    variable: str = "wind_speed",
    value: float | None = 36.0,
) -> ForecastValue:
    return ForecastValue(
        "chmi_aladin_cz_1km",
        run_time,
        valid_time,
        50.0,
        14.0,
        250.0,
        variable,
        value,
        "km/h" if variable == "wind_speed" else "mm",
    )


def _observation(
    *,
    valid_time: datetime = datetime(2026, 10, 25, tzinfo=UTC),
    variable: str = "wind_speed",
    value: float | None = 10.0,
) -> Observation:
    return Observation(
        "CHMI_STATION",
        "0-20000-0-11502",
        valid_time,
        50.1,
        14.1,
        300.0,
        variable,
        value,
        "m/s" if variable == "wind_speed" else "mm",
    )


def test_aligns_station_truth_by_valid_utc_hour_and_converts_units() -> None:
    aligned = align_station_forecasts((_forecast(),), (_observation(),))

    assert len(aligned) == 1
    assert aligned[0].truth_value == 36.0
    assert aligned[0].unit == "km/h"
    assert aligned[0].station_id == "0-20000-0-11502"
    assert aligned[0].truth_type == "station"
    assert aligned[0].distance_km > 0
    assert aligned[0].elevation_difference_m == 50.0


def test_alignment_rejects_future_run_duplicate_forecast_and_dst_ambiguity() -> None:
    with pytest.raises(ValueError, match="future"):
        align_station_forecasts(
            (_forecast(run_time=datetime(2026, 10, 25, 1, tzinfo=UTC)),), (_observation(),)
        )

    with pytest.raises(ValueError, match="duplicate forecast"):
        align_station_forecasts((_forecast(), _forecast()), (_observation(),))

    first = _forecast(valid_time=datetime(2026, 10, 25, 0, tzinfo=UTC))
    second = _forecast(valid_time=datetime(2026, 10, 25, 1, tzinfo=UTC))
    observed_first = _observation(valid_time=first.valid_time)
    observed_second = _observation(valid_time=second.valid_time)
    assert len(align_station_forecasts((first, second), (observed_first, observed_second))) == 2


def test_train_holdout_do_not_overlap_and_keep_only_original_issued_run() -> None:
    original = _forecast(valid_time=datetime(2026, 10, 29, tzinfo=UTC))
    later = _forecast(
        run_time=datetime(2026, 10, 29, tzinfo=UTC), valid_time=original.valid_time
    )
    truth = _observation(valid_time=original.valid_time)
    aligned = align_station_forecasts((original,), (truth,))

    train, holdout = split_train_holdout(
        aligned,
        DateRange(date(2026, 10, 1), date(2026, 10, 28)),
        DateRange(date(2026, 10, 29), date(2026, 10, 31)),
    )
    assert train == ()
    assert holdout[0].forecast.run_time == original.run_time
    assert later.run_time != holdout[0].forecast.run_time

    with pytest.raises(ValueError, match="overlap"):
        split_train_holdout(
            aligned,
            DateRange(date(2026, 10, 1), date(2026, 10, 29)),
            DateRange(date(2026, 10, 29), date(2026, 10, 31)),
        )


def test_spatial_precipitation_contract_requires_exact_interval_end() -> None:
    forecast = _forecast(variable="precipitation", value=1.0)
    truth = SpatialObservation(
        "CHMI_MERGE1H",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        forecast.valid_time - timedelta(hours=1),
        forecast.valid_time,
        "precipitation",
        1.0,
        "mm",
        "EPSG:3857",
        (11.0, 48.0, 19.0, 51.0),
        0,
        0,
        1_000.0,
        1_000.0,
    )

    matched = validate_spatial_precipitation(forecast, truth)
    assert matched.truth_type == "spatial_precipitation"
    assert matched.truth.valid_end == forecast.valid_time


def test_station_precipitation_uses_hourly_interval_not_ten_minute_value() -> None:
    forecast = _forecast(variable="precipitation", value=3.0)
    ten_minute = Observation(
        "CHMI_STATION",
        "0-20000-0-11502",
        forecast.valid_time,
        50.1,
        14.1,
        300.0,
        "precipitation",
        0.5,
        "mm",
        interval=timedelta(minutes=10),
        accumulation="interval",
    )
    hourly = Observation(
        "CHMI_STATION",
        "0-20000-0-11502",
        forecast.valid_time,
        50.1,
        14.1,
        300.0,
        "precipitation",
        3.0,
        "mm",
        interval=timedelta(hours=1),
        accumulation="interval",
    )

    aligned = align_station_forecasts((forecast,), (ten_minute, hourly))

    assert len(aligned) == 1
    assert aligned[0].observation.interval == timedelta(hours=1)
    assert aligned[0].truth_value == 3.0
