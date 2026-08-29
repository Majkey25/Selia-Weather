from __future__ import annotations

import importlib
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from math import isclose, isfinite
from numbers import Real
from pathlib import Path
from typing import BinaryIO, Protocol, cast

from aladin_ensemble.types import ForecastValue


@dataclass(frozen=True, slots=True)
class GeoPoint:
    latitude: float
    longitude: float

    def __post_init__(self) -> None:
        if not isfinite(self.latitude) or not -90 <= self.latitude <= 90:
            raise ValueError("latitude is invalid")
        if not isfinite(self.longitude) or not -180 <= self.longitude <= 180:
            raise ValueError("longitude is invalid")


@dataclass(frozen=True, slots=True)
class SampledPoint:
    latitude: float
    longitude: float
    source_latitude: float
    source_longitude: float
    distance_km: float
    value: float

    def __post_init__(self) -> None:
        if not all(
            isfinite(item)
            for item in (
                self.latitude,
                self.longitude,
                self.source_latitude,
                self.source_longitude,
                self.distance_km,
                self.value,
            )
        ) or self.distance_km < 0:
            raise ValueError("sampled GRIB point is invalid")


@dataclass(frozen=True, slots=True)
class SampledMessage:
    run_time: datetime
    valid_time: datetime
    unit: str
    step_type: str
    start_step_hours: int
    end_step_hours: int
    values: tuple[SampledPoint, ...]

    def __post_init__(self) -> None:
        if self.run_time.tzinfo is None or self.run_time.utcoffset() != UTC.utcoffset(
            self.run_time
        ):
            raise ValueError("run_time must be UTC")
        if self.valid_time.tzinfo is None or self.valid_time.utcoffset() != UTC.utcoffset(
            self.valid_time
        ):
            raise ValueError("valid_time must be UTC")
        if (
            self.valid_time < self.run_time
            or not self.unit
            or not self.step_type
            or self.start_step_hours < 0
            or self.end_step_hours < self.start_step_hours
            or not self.values
        ):
            raise ValueError("sampled GRIB message metadata is invalid")


class NearestPoint(Protocol):
    @property
    def lat(self) -> float: ...

    @property
    def lon(self) -> float: ...

    @property
    def value(self) -> float: ...

    @property
    def distance(self) -> float: ...

    @property
    def index(self) -> int: ...


class EccodesApi(Protocol):
    def new_from_file(self, source: BinaryIO) -> int | None: ...

    def get(self, handle: int, key: str) -> object: ...

    def find_nearest_multiple(
        self,
        handle: int,
        is_lsm: bool,
        latitudes: tuple[float, ...],
        longitudes: tuple[float, ...],
    ) -> Sequence[NearestPoint]: ...

    def release(self, handle: int) -> None: ...


def decode_grib_points(
    path: Path,
    points: tuple[GeoPoint, ...],
    *,
    api: EccodesApi | None = None,
) -> tuple[SampledMessage, ...]:
    if not path.is_file():
        raise ValueError("GRIB path is not a file")
    if not points or len(set(points)) != len(points):
        raise ValueError("GRIB points must be non-empty and unique")
    decoder = api or _DefaultEccodesApi()
    latitudes = tuple(point.latitude for point in points)
    longitudes = tuple(point.longitude for point in points)
    messages: list[SampledMessage] = []
    with path.open("rb") as source:
        while (handle := decoder.new_from_file(source)) is not None:
            try:
                run_time = _timestamp(
                    decoder.get(handle, "dataDate"),
                    decoder.get(handle, "dataTime"),
                )
                valid_time = _timestamp(
                    decoder.get(handle, "validityDate"),
                    decoder.get(handle, "validityTime"),
                )
                unit = _text(decoder.get(handle, "units"), "units")
                step_type = _text(decoder.get(handle, "stepType"), "stepType")
                start_step = _step_hours(decoder.get(handle, "startStep"), "startStep")
                end_step = _step_hours(decoder.get(handle, "endStep"), "endStep")
                nearest = tuple(
                    decoder.find_nearest_multiple(handle, False, latitudes, longitudes)
                )
                if len(nearest) != len(points):
                    raise ValueError("ecCodes returned the wrong point count")
                values = tuple(
                    SampledPoint(
                        latitude=point.latitude,
                        longitude=point.longitude,
                        source_latitude=result.lat,
                        source_longitude=result.lon,
                        distance_km=result.distance,
                        value=result.value,
                    )
                    for point, result in zip(points, nearest, strict=True)
                )
                messages.append(
                    SampledMessage(
                        run_time=run_time,
                        valid_time=valid_time,
                        unit=unit,
                        step_type=step_type,
                        start_step_hours=start_step,
                        end_step_hours=end_step,
                        values=values,
                    )
                )
            finally:
                decoder.release(handle)
    if not messages:
        raise ValueError("GRIB file contains no messages")
    return tuple(messages)


def regular_grid_points(
    south: float,
    north: float,
    west: float,
    east: float,
    step: float,
) -> tuple[GeoPoint, ...]:
    if not all(isfinite(value) for value in (south, north, west, east, step)):
        raise ValueError("grid values must be finite")
    if not -90 <= south < north <= 90 or not -180 <= west < east <= 180 or step <= 0:
        raise ValueError("grid bounds or step are invalid")
    latitude_steps = round((north - south) / step)
    longitude_steps = round((east - west) / step)
    if not isclose(south + latitude_steps * step, north, abs_tol=1e-9) or not isclose(
        west + longitude_steps * step,
        east,
        abs_tol=1e-9,
    ):
        raise ValueError("grid bounds must be divisible by step")
    return tuple(
        GeoPoint(round(south + latitude * step, 8), round(west + longitude * step, 8))
        for latitude in range(latitude_steps + 1)
        for longitude in range(longitude_steps + 1)
    )


def to_forecast_values(
    messages: tuple[SampledMessage, ...],
    *,
    model_id: str,
    variable: str,
    canonical_unit: str,
    elevation_by_point: Mapping[GeoPoint, float],
) -> tuple[ForecastValue, ...]:
    if not messages or not model_id or not variable or not canonical_unit:
        raise ValueError("forecast conversion metadata is required")
    rows: list[ForecastValue] = []
    for message in messages:
        for point in message.values:
            coordinate = GeoPoint(point.latitude, point.longitude)
            elevation = elevation_by_point.get(coordinate)
            if elevation is None or not isfinite(elevation):
                raise ValueError(f"elevation is missing for point: {coordinate}")
            rows.append(
                ForecastValue(
                    model_id=model_id,
                    run_time=message.run_time,
                    valid_time=message.valid_time,
                    latitude=point.latitude,
                    longitude=point.longitude,
                    elevation_m=elevation,
                    variable=variable,
                    value=convert_grib_unit(point.value, message.unit, canonical_unit),
                    unit=canonical_unit,
                )
            )
    return tuple(rows)


def convert_grib_unit(value: float, source_unit: str, canonical_unit: str) -> float:
    if not isfinite(value) or not source_unit or not canonical_unit:
        raise ValueError("GRIB unit conversion input is invalid")
    if source_unit == canonical_unit:
        return value
    conversion = (source_unit, canonical_unit)
    if conversion == ("K", "°C"):
        return value - 273.15
    if conversion in (("m/s", "km/h"), ("m s**-1", "km/h")):
        return value * 3.6
    if conversion == ("Pa", "hPa"):
        return value / 100.0
    if conversion in (("kg/m²", "mm"), ("kg m**-2", "mm")):
        return value
    if conversion == ("m", "mm"):
        return value * 1_000.0
    raise ValueError(f"unsupported unit conversion: {source_unit} to {canonical_unit}")


class _EccodesModule(Protocol):
    def codes_grib_new_from_file(self, source: BinaryIO) -> int | None: ...

    def codes_get(self, handle: int, key: str) -> object: ...

    def codes_grib_find_nearest_multiple(
        self,
        handle: int,
        is_lsm: bool,
        latitudes: tuple[float, ...],
        longitudes: tuple[float, ...],
    ) -> Sequence[NearestPoint]: ...

    def codes_release(self, handle: int) -> None: ...


class _DefaultEccodesApi:
    def __init__(self) -> None:
        self.module = cast(_EccodesModule, importlib.import_module("eccodes"))

    def new_from_file(self, source: BinaryIO) -> int | None:
        return self.module.codes_grib_new_from_file(source)

    def get(self, handle: int, key: str) -> object:
        return self.module.codes_get(handle, key)

    def find_nearest_multiple(
        self,
        handle: int,
        is_lsm: bool,
        latitudes: tuple[float, ...],
        longitudes: tuple[float, ...],
    ) -> Sequence[NearestPoint]:
        return self.module.codes_grib_find_nearest_multiple(
            handle,
            is_lsm,
            latitudes,
            longitudes,
        )

    def release(self, handle: int) -> None:
        self.module.codes_release(handle)


def _timestamp(date_value: object, time_value: object) -> datetime:
    date = _integer(date_value, "date")
    time = _integer(time_value, "time")
    text = f"{date:08d}{time:04d}"
    try:
        return datetime.strptime(text, "%Y%m%d%H%M").replace(tzinfo=UTC)
    except ValueError as error:
        raise ValueError("GRIB timestamp is invalid") from error


def _integer(value: object, name: str) -> int:
    if (
        isinstance(value, bool)
        or not isinstance(value, Real)
        or not isfinite(value)
        or not float(value).is_integer()
    ):
        raise ValueError(f"GRIB {name} must be an integer, got {value!r}")
    return int(float(value))


def _step_hours(value: object, name: str) -> int:
    if isinstance(value, str):
        match = re.fullmatch(r"(\d+)([hm]?)", value)
        if match is None:
            raise ValueError(f"GRIB {name} is invalid: {value!r}")
        amount = int(match.group(1))
        if match.group(2) == "m":
            if amount % 60:
                raise ValueError(f"GRIB {name} is not a whole hour: {value!r}")
            return amount // 60
        return amount
    return _integer(value, name)


def _text(value: object, name: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"GRIB {name} must be text")
    return value
