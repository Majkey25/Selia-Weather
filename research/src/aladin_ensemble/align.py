from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from math import asin, cos, radians, sin, sqrt
from typing import Literal

from aladin_ensemble.sources.open_meteo_runs import canonical_value
from aladin_ensemble.types import ForecastValue, Observation, SpatialObservation


@dataclass(frozen=True, slots=True)
class DateRange:
    start: date
    end: date

    def __post_init__(self) -> None:
        if self.end < self.start:
            raise ValueError("date range end must not precede start")

    def contains(self, value: date) -> bool:
        return self.start <= value <= self.end


@dataclass(frozen=True, slots=True)
class AlignedForecast:
    forecast: ForecastValue
    observation: Observation
    truth_value: float | None
    unit: str
    station_id: str
    distance_km: float
    elevation_difference_m: float
    truth_type: Literal["station"] = "station"


@dataclass(frozen=True, slots=True)
class SpatialPrecipitationAlignment:
    forecast: ForecastValue
    truth: SpatialObservation
    truth_type: Literal["spatial_precipitation"] = "spatial_precipitation"


def align_station_forecasts(
    forecasts: tuple[ForecastValue, ...], observations: tuple[Observation, ...]
) -> tuple[AlignedForecast, ...]:
    _validate_forecasts(forecasts)
    indexed = _index_observations(observations)
    aligned: list[AlignedForecast] = []
    for forecast in forecasts:
        matches = tuple(
            observation
            for variable in _station_variables(forecast.variable)
            for observation in indexed.get((forecast.valid_time, variable), ())
        )
        matches = tuple(
            observation
            for observation in matches
            if _compatible_observation(forecast.variable, observation)
        )
        if forecast.requested_point_id is not None:
            matches = tuple(
                observation
                for observation in matches
                if observation.station_id == forecast.requested_point_id
            )
        if not matches:
            continue
        observation = min(
            matches,
            key=lambda candidate: (
                station_distance_km(
                    forecast.latitude, forecast.longitude, candidate.latitude, candidate.longitude
                ),
                candidate.station_id,
            ),
        )
        if forecast.run_time > observation.valid_time:
            raise ValueError("future forecast run would leak truth")
        truth_value = canonical_value(
            forecast.variable, observation.value, observation.unit
        )
        aligned.append(
            AlignedForecast(
                forecast,
                observation,
                truth_value,
                forecast.unit,
                observation.station_id,
                station_distance_km(
                    forecast.latitude,
                    forecast.longitude,
                    observation.latitude,
                    observation.longitude,
                ),
                abs(forecast.elevation_m - observation.elevation_m),
            )
        )
    return tuple(aligned)


def split_train_holdout(
    aligned: tuple[AlignedForecast, ...], train: DateRange, holdout: DateRange
) -> tuple[tuple[AlignedForecast, ...], tuple[AlignedForecast, ...]]:
    if train.end >= holdout.start:
        raise ValueError("train and holdout date ranges overlap")
    training = tuple(
        item for item in aligned if train.contains(item.observation.valid_time.date())
    )
    testing = tuple(
        item for item in aligned if holdout.contains(item.observation.valid_time.date())
    )
    return training, testing


def validate_spatial_precipitation(
    forecast: ForecastValue, truth: SpatialObservation
) -> SpatialPrecipitationAlignment:
    if forecast.variable != "precipitation" or truth.variable != "precipitation":
        raise ValueError("spatial contract requires precipitation")
    if forecast.unit != "mm" or truth.unit != "mm":
        raise ValueError("spatial precipitation unit must be mm")
    if forecast.valid_time != truth.valid_end:
        raise ValueError("spatial precipitation valid end must match forecast valid time")
    if forecast.run_time > truth.valid_end:
        raise ValueError("forecast run is later than spatial truth")
    return SpatialPrecipitationAlignment(forecast, truth)


def _validate_forecasts(forecasts: tuple[ForecastValue, ...]) -> None:
    seen: set[tuple[str, datetime, datetime, float, float, str]] = set()
    for forecast in forecasts:
        if any(
            (
                forecast.valid_time.minute,
                forecast.valid_time.second,
                forecast.valid_time.microsecond,
            )
        ):
            raise ValueError("forecast valid time must be a UTC hour")
        key = (
            forecast.model_id,
            forecast.run_time,
            forecast.valid_time,
            forecast.latitude,
            forecast.longitude,
            forecast.variable,
        )
        if key in seen:
            raise ValueError("duplicate forecast row")
        seen.add(key)


def _index_observations(
    observations: tuple[Observation, ...]
) -> dict[tuple[datetime, str], tuple[Observation, ...]]:
    indexed: dict[tuple[datetime, str], list[Observation]] = {}
    seen: set[tuple[str, str, datetime, str, timedelta | None, str]] = set()
    for observation in observations:
        if any(
            (
                observation.valid_time.minute,
                observation.valid_time.second,
                observation.valid_time.microsecond,
            )
        ):
            raise ValueError("observation valid time must be a UTC hour")
        identity = (
            observation.source,
            observation.station_id,
            observation.valid_time,
            observation.variable,
            observation.interval,
            observation.accumulation,
        )
        if identity in seen:
            raise ValueError("duplicate station observation")
        seen.add(identity)
        key = (observation.valid_time, observation.variable)
        indexed.setdefault(key, []).append(observation)
    return {key: tuple(value) for key, value in indexed.items()}


def _compatible_observation(variable: str, observation: Observation) -> bool:
    if variable == "precipitation":
        return observation.accumulation == "interval" and observation.interval == timedelta(hours=1)
    return observation.accumulation == "instant" and observation.interval is None


def _station_variables(variable: str) -> tuple[str, ...]:
    station_variable = {
        "temperature": "temperature_2m",
        "dew_point": "dew_point_2m",
        "wind_speed": "wind_speed_10m",
        "wind_direction": "wind_direction_10m",
    }.get(variable, variable)
    return (station_variable,) if station_variable == variable else (station_variable, variable)


def station_distance_km(
    latitude_a: float, longitude_a: float, latitude_b: float, longitude_b: float
) -> float:
    latitude_delta = radians(latitude_b - latitude_a)
    longitude_delta = radians(longitude_b - longitude_a)
    a = sin(latitude_delta / 2) ** 2 + cos(radians(latitude_a)) * cos(radians(latitude_b)) * sin(
        longitude_delta / 2
    ) ** 2
    return 6_371.0088 * 2 * asin(sqrt(a))
