from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from ..types import ForecastValue


@dataclass(frozen=True, slots=True)
class DwdIconRequest:
    run_time: datetime
    lead_hour: int
    variable: str

    def __post_init__(self) -> None:
        _require_utc(self.run_time, "run_time")
        if self.run_time.minute or self.run_time.second or self.run_time.microsecond:
            raise ValueError("DWD run_time must use a whole hour")
        if self.run_time.hour % 3:
            raise ValueError("DWD ICON-EU run hour must be divisible by three")
        if self.lead_hour not in range(121):
            raise ValueError("DWD ICON-EU lead_hour must be between 0 and 120")
        if self.variable not in DWD_VARIABLES:
            raise ValueError(f"unsupported DWD variable: {self.variable}")


@dataclass(frozen=True, slots=True)
class GribField:
    source_id: str
    model_id: str
    run_time: datetime
    valid_time: datetime
    variable: str
    source_unit: str
    canonical_unit: str

    def __post_init__(self) -> None:
        for name, value in (
            ("source_id", self.source_id),
            ("model_id", self.model_id),
            ("variable", self.variable),
            ("source_unit", self.source_unit),
            ("canonical_unit", self.canonical_unit),
        ):
            if not value:
                raise ValueError(f"{name} is required")
        _require_utc(self.run_time, "run_time")
        _require_utc(self.valid_time, "valid_time")
        if self.valid_time < self.run_time:
            raise ValueError("valid_time cannot precede run_time")


def build_grib_get_data_command(path: Path) -> tuple[str, ...]:
    return (
        "grib_get_data",
        "-L%.6f %.6f",
        "-F%.8g",
        "-m",
        "MISSING",
        str(path),
    )


def build_dwd_icon_url(request: DwdIconRequest) -> str:
    folder, code = DWD_VARIABLES[request.variable]
    run = request.run_time
    filename = (
        f"icon-eu_europe_regular-lat-lon_single-level_{run:%Y%m%d%H}_"
        f"{request.lead_hour:03d}_{code}.grib2.bz2"
    )
    return f"{DWD_ICON_ROOT}/{run:%H}/{folder}/{filename}"


def parse_grib_get_data(
    output: str,
    field: GribField,
    *,
    elevation_by_point: Mapping[tuple[float, float], float],
) -> tuple[ForecastValue, ...]:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines or lines[0].split() != ["Latitude", "Longitude", "Value"]:
        raise ValueError("grib_get_data header is invalid")
    values: list[ForecastValue] = []
    coordinates: set[tuple[float, float]] = set()
    for line in lines[1:]:
        columns = line.split()
        if len(columns) != 3:
            raise ValueError("grib_get_data row must have three columns")
        try:
            latitude = float(columns[0])
            longitude = _normalise_longitude(float(columns[1]))
        except ValueError as error:
            raise ValueError("grib_get_data coordinates are invalid") from error
        point = (round(latitude, 6), round(longitude, 6))
        if point in coordinates:
            raise ValueError("grib_get_data contains duplicate coordinates")
        coordinates.add(point)
        elevation = elevation_by_point.get(point)
        if elevation is None:
            raise ValueError(f"elevation is missing for point: {point}")
        raw = columns[2]
        if raw == "MISSING":
            converted = None
        else:
            try:
                converted = _convert(float(raw), field.source_unit, field.canonical_unit)
            except ValueError as error:
                raise ValueError("grib_get_data value is invalid") from error
        values.append(
            ForecastValue(
                model_id=field.model_id,
                run_time=field.run_time,
                valid_time=field.valid_time,
                latitude=point[0],
                longitude=point[1],
                elevation_m=elevation,
                variable=field.variable,
                value=converted,
                unit=field.canonical_unit,
            )
        )
    if not values:
        raise ValueError("grib_get_data contains no values")
    return tuple(sorted(values, key=lambda value: (value.latitude, value.longitude)))


def _convert(value: float, source_unit: str, canonical_unit: str) -> float:
    if source_unit == canonical_unit:
        return value
    conversion = (source_unit, canonical_unit)
    if conversion == ("K", "°C"):
        return value - 273.15
    if conversion == ("m/s", "km/h"):
        return value * 3.6
    if conversion == ("Pa", "hPa"):
        return value / 100.0
    if conversion == ("kg/m²", "mm"):
        return value
    raise ValueError(f"unsupported unit conversion: {source_unit} to {canonical_unit}")


def _normalise_longitude(value: float) -> float:
    return (value + 180.0) % 360.0 - 180.0


def _require_utc(value: datetime, name: str) -> None:
    if value.tzinfo is None or value.utcoffset() != UTC.utcoffset(value):
        raise ValueError(f"{name} must be timezone-aware UTC")


DWD_ICON_ROOT = "https://opendata.dwd.de/weather/nwp/icon-eu/grib"
DWD_VARIABLES = {
    "precipitation": ("tot_prec", "TOT_PREC"),
    "temperature_2m": ("t_2m", "T_2M"),
    "wind_u_10m": ("u_10m", "U_10M"),
    "wind_v_10m": ("v_10m", "V_10M"),
}
