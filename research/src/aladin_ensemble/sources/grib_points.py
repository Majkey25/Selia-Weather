from __future__ import annotations

import importlib
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from math import cos, isclose, isfinite, radians, sin
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


@dataclass(frozen=True, slots=True)
class IndexedPoint:
    latitude: float
    longitude: float
    source_latitude: float
    source_longitude: float
    distance_km: float
    index: int

    def __post_init__(self) -> None:
        if not all(
            isfinite(item)
            for item in (
                self.latitude,
                self.longitude,
                self.source_latitude,
                self.source_longitude,
                self.distance_km,
            )
        ) or self.distance_km < 0 or self.index < 0:
            raise ValueError("indexed GRIB point is invalid")


@dataclass(frozen=True, slots=True)
class GribPointIndex:
    grid_hash: str
    points: tuple[IndexedPoint, ...]

    def __post_init__(self) -> None:
        coordinates = tuple((point.latitude, point.longitude) for point in self.points)
        if not self.grid_hash or not self.points or len(set(coordinates)) != len(coordinates):
            raise ValueError("GRIB point index is invalid")


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

    def get_elements(
        self,
        handle: int,
        key: str,
        indexes: tuple[int, ...],
    ) -> Sequence[float]: ...

    def release(self, handle: int) -> None: ...


def decode_grib_points(
    path: Path,
    points: tuple[GeoPoint, ...],
    *,
    api: EccodesApi | None = None,
    point_index: GribPointIndex | None = None,
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
                values = _sample_values(
                    decoder,
                    handle,
                    points,
                    latitudes,
                    longitudes,
                    point_index,
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


def build_grib_point_index(
    path: Path,
    points: tuple[GeoPoint, ...],
    *,
    api: EccodesApi | None = None,
) -> GribPointIndex:
    if not path.is_file():
        raise ValueError("GRIB path is not a file")
    if not points or len(set(points)) != len(points):
        raise ValueError("GRIB points must be non-empty and unique")
    decoder = api or _DefaultEccodesApi()
    with path.open("rb") as source:
        handle = decoder.new_from_file(source)
        if handle is None:
            raise ValueError("GRIB file contains no messages")
        try:
            nearest = tuple(
                decoder.find_nearest_multiple(
                    handle,
                    False,
                    tuple(point.latitude for point in points),
                    tuple(point.longitude for point in points),
                )
            )
            if len(nearest) != len(points):
                raise ValueError("ecCodes returned the wrong point count")
            return GribPointIndex(
                grid_hash=_text(decoder.get(handle, "md5GridSection"), "md5GridSection"),
                points=tuple(
                    IndexedPoint(
                        latitude=point.latitude,
                        longitude=point.longitude,
                        source_latitude=result.lat,
                        source_longitude=result.lon,
                        distance_km=result.distance,
                        index=result.index,
                    )
                    for point, result in zip(points, nearest, strict=True)
                ),
            )
        finally:
            decoder.release(handle)


def _sample_values(
    decoder: EccodesApi,
    handle: int,
    points: tuple[GeoPoint, ...],
    latitudes: tuple[float, ...],
    longitudes: tuple[float, ...],
    point_index: GribPointIndex | None,
) -> tuple[SampledPoint, ...]:
    if point_index is None:
        nearest = tuple(decoder.find_nearest_multiple(handle, False, latitudes, longitudes))
        if len(nearest) != len(points):
            raise ValueError("ecCodes returned the wrong point count")
        return tuple(
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
    indexed_coordinates = tuple(
        GeoPoint(point.latitude, point.longitude) for point in point_index.points
    )
    if indexed_coordinates != points:
        raise ValueError("GRIB point index coordinates do not match")
    grid_hash = _text(decoder.get(handle, "md5GridSection"), "md5GridSection")
    if grid_hash != point_index.grid_hash:
        raise ValueError("GRIB point index grid hash does not match")
    values = tuple(
        decoder.get_elements(
            handle,
            "values",
            tuple(point.index for point in point_index.points),
        )
    )
    if len(values) != len(points):
        raise ValueError("ecCodes returned the wrong point count")
    return tuple(
        SampledPoint(
            latitude=indexed.latitude,
            longitude=indexed.longitude,
            source_latitude=indexed.source_latitude,
            source_longitude=indexed.source_longitude,
            distance_km=indexed.distance_km,
            value=value,
        )
        for indexed, value in zip(point_index.points, values, strict=True)
    )


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
    if variable == "precipitation":
        messages = _precipitation_intervals(messages, canonical_unit)
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


def _precipitation_intervals(
    messages: tuple[SampledMessage, ...],
    canonical_unit: str,
) -> tuple[SampledMessage, ...]:
    if canonical_unit != "mm":
        raise ValueError("precipitation canonical unit must be millimetres")
    ordered = tuple(sorted(messages, key=lambda message: message.end_step_hours))
    if any(message.step_type != "accum" for message in ordered):
        raise ValueError("precipitation GRIB messages must use accumulated steps")
    coordinates = tuple((point.latitude, point.longitude) for point in ordered[0].values)
    if any(
        message.run_time != ordered[0].run_time
        or message.unit != ordered[0].unit
        or tuple((point.latitude, point.longitude) for point in message.values) != coordinates
        for message in ordered
    ):
        raise ValueError("precipitation GRIB axes do not match")
    cumulative = all(message.start_step_hours == 0 for message in ordered)
    if not cumulative and any(
        current.start_step_hours != previous.end_step_hours
        for previous, current in zip(ordered, ordered[1:], strict=False)
    ):
        raise ValueError("precipitation GRIB intervals are not contiguous")

    previous_values = tuple(0.0 for _ in coordinates)
    previous_end = ordered[0].start_step_hours
    result: list[SampledMessage] = []
    if ordered[0].start_step_hours == 0 and ordered[0].end_step_hours > 0:
        result.append(
            replace(
                ordered[0],
                valid_time=ordered[0].run_time,
                unit=canonical_unit,
                start_step_hours=0,
                end_step_hours=0,
                values=tuple(replace(point, value=0.0) for point in ordered[0].values),
            )
        )
    for message in ordered:
        raw_values = tuple(
            convert_grib_unit(point.value, message.unit, canonical_unit)
            for point in message.values
        )
        interval_values = (
            tuple(
                current - previous
                for current, previous in zip(raw_values, previous_values, strict=True)
            )
            if cumulative
            else raw_values
        )
        if any(value < -PRECIPITATION_TOLERANCE_MM for value in interval_values):
            raise ValueError("cumulative precipitation decreased")
        result.append(
            replace(
                message,
                unit=canonical_unit,
                start_step_hours=previous_end if cumulative else message.start_step_hours,
                values=tuple(
                    replace(point, value=max(value, 0.0))
                    for point, value in zip(message.values, interval_values, strict=True)
                ),
            )
        )
        previous_values = raw_values
        previous_end = message.end_step_hours
    return tuple(result)


def elevation_by_point(message: SampledMessage) -> dict[GeoPoint, float]:
    if message.unit != "m":
        raise ValueError("elevation GRIB field must use metres")
    elevations = {
        GeoPoint(point.latitude, point.longitude): point.value for point in message.values
    }
    if len(elevations) != len(message.values):
        raise ValueError("elevation GRIB field contains duplicate points")
    return elevations


def to_wind_component_values(
    speed_messages: tuple[SampledMessage, ...],
    direction_messages: tuple[SampledMessage, ...],
    model_id: str,
    elevation_by_point: Mapping[GeoPoint, float],
) -> tuple[ForecastValue, ...]:
    if not speed_messages or not direction_messages or not model_id:
        raise ValueError("wind conversion metadata is required")
    speeds = sorted(speed_messages, key=lambda message: message.valid_time)
    directions = sorted(direction_messages, key=lambda message: message.valid_time)
    if len(speeds) != len(directions):
        raise ValueError("wind speed and direction axes do not match")
    rows: list[ForecastValue] = []
    for speed_message, direction_message in zip(speeds, directions, strict=True):
        if (
            speed_message.run_time != direction_message.run_time
            or speed_message.valid_time != direction_message.valid_time
            or len(speed_message.values) != len(direction_message.values)
        ):
            raise ValueError("wind speed and direction axes do not match")
        for speed_point, direction_point in zip(
            speed_message.values,
            direction_message.values,
            strict=True,
        ):
            coordinate = GeoPoint(speed_point.latitude, speed_point.longitude)
            if coordinate != GeoPoint(direction_point.latitude, direction_point.longitude):
                raise ValueError("wind speed and direction points do not match")
            elevation = elevation_by_point.get(coordinate)
            if elevation is None or not isfinite(elevation):
                raise ValueError(f"elevation is missing for point: {coordinate}")
            speed = convert_grib_unit(speed_point.value, speed_message.unit, "m/s")
            direction = normalize_wind_direction(
                convert_grib_unit(direction_point.value, direction_message.unit, "°")
            )
            if speed < 0:
                raise ValueError("wind speed is invalid")
            angle = radians(direction)
            for variable, value in (
                ("wind_u_10m", -speed * sin(angle)),
                ("wind_v_10m", -speed * cos(angle)),
            ):
                rows.append(
                    ForecastValue(
                        model_id=model_id,
                        run_time=speed_message.run_time,
                        valid_time=speed_message.valid_time,
                        latitude=coordinate.latitude,
                        longitude=coordinate.longitude,
                        elevation_m=elevation,
                        variable=variable,
                        value=value,
                        unit="m/s",
                    )
                )
    return tuple(rows)


def normalize_wind_direction(value: float) -> float:
    if (
        not isfinite(value)
        or value < -DIRECTION_TOLERANCE_DEGREES
        or value > 360 + DIRECTION_TOLERANCE_DEGREES
    ):
        raise ValueError("wind direction is invalid")
    return value % 360.0


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
    if conversion == ("m s**-1", "m/s"):
        return value
    if conversion == ("Pa", "hPa"):
        return value / 100.0
    if conversion in (("kg/m²", "mm"), ("kg m**-2", "mm")):
        return value
    if conversion == ("m", "mm"):
        return value * 1_000.0
    if canonical_unit == "°" and source_unit.lower() in ("deg", "degree true", "degrees"):
        return value
    raise ValueError(f"unsupported unit conversion: {source_unit} to {canonical_unit}")


DIRECTION_TOLERANCE_DEGREES = 0.5
PRECIPITATION_TOLERANCE_MM = 0.01


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

    def codes_get_elements(
        self,
        handle: int,
        key: str,
        indexes: tuple[int, ...],
    ) -> Sequence[float]: ...

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

    def get_elements(
        self,
        handle: int,
        key: str,
        indexes: tuple[int, ...],
    ) -> Sequence[float]:
        return self.module.codes_get_elements(handle, key, indexes)

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
