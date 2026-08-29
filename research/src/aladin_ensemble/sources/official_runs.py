from __future__ import annotations

import bz2
import hashlib
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

from ..types import ForecastValue

HttpGet = Callable[[str, float, int], bytes]
PayloadLoader = Callable[[], bytes]


@dataclass(frozen=True, slots=True)
class CachedGrib:
    path: Path
    checksum: str
    source_url: str
    from_cache: bool



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
class NoaaGfsRequest:
    run_time: datetime
    lead_hour: int
    variable: str

    def __post_init__(self) -> None:
        _require_utc(self.run_time, "run_time")
        if self.run_time.minute or self.run_time.second or self.run_time.microsecond:
            raise ValueError("NOAA run_time must use a whole hour")
        if self.run_time.hour not in (0, 6, 12, 18):
            raise ValueError("NOAA GFS run hour must be 00, 06, 12, or 18 UTC")
        if self.lead_hour not in range(385):
            raise ValueError("NOAA GFS lead_hour must be between 0 and 384")
        if self.variable not in NOAA_GFS_VARIABLES:
            raise ValueError(f"unsupported NOAA GFS variable: {self.variable}")


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


def build_noaa_gfs_url(request: NoaaGfsRequest) -> str:
    variable, level = NOAA_GFS_VARIABLES[request.variable]
    run = request.run_time
    parameters = (
        ("file", f"gfs.t{run:%H}z.pgrb2.0p25.f{request.lead_hour:03d}"),
        (variable, "on"),
        (level, "on"),
        ("subregion", ""),
        ("leftlon", "11.9"),
        ("rightlon", "19"),
        ("toplat", "51.2"),
        ("bottomlat", "48.45"),
        ("dir", f"/gfs.{run:%Y%m%d}/{run:%H}/atmos"),
    )
    return f"{NOAA_GFS_FILTER}?{urlencode(parameters)}"


def download_dwd_icon(
    request: DwdIconRequest,
    cache_root: Path,
    *,
    http_get: HttpGet | None = None,
    timeout: float = 15.0,
    max_compressed_bytes: int = 2_000_000,
    max_decompressed_bytes: int = 20_000_000,
) -> CachedGrib:
    if timeout <= 0 or max_compressed_bytes <= 0 or max_decompressed_bytes <= 0:
        raise ValueError("download limits must be positive")
    url = build_dwd_icon_url(request)
    getter = http_get or _http_get

    def load() -> bytes:
        compressed = getter(url, timeout, max_compressed_bytes)
        if len(compressed) > max_compressed_bytes:
            raise ValueError("DWD compressed payload exceeds size limit")
        return _require_grib(_decompress_bz2(compressed, max_decompressed_bytes))

    return _cached_grib(url, cache_root, load)


def download_noaa_gfs(
    request: NoaaGfsRequest,
    cache_root: Path,
    *,
    http_get: HttpGet | None = None,
    timeout: float = 15.0,
    max_bytes: int = 5_000_000,
) -> CachedGrib:
    if timeout <= 0 or max_bytes <= 0:
        raise ValueError("download limits must be positive")
    url = build_noaa_gfs_url(request)
    getter = http_get or _http_get

    def load() -> bytes:
        payload = getter(url, timeout, max_bytes)
        if len(payload) > max_bytes:
            raise ValueError("NOAA payload exceeds size limit")
        return _require_grib(payload)

    return _cached_grib(url, cache_root, load)


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


def _http_get(url: str, timeout: float, max_bytes: int) -> bytes:
    with urlopen(url, timeout=timeout) as response:
        payload = response.read(max_bytes + 1)
    if len(payload) > max_bytes:
        raise ValueError("HTTP payload exceeds size limit")
    return payload


def _cached_grib(url: str, cache_root: Path, load: PayloadLoader) -> CachedGrib:
    key = hashlib.sha256(url.encode()).hexdigest()
    cache_root.mkdir(parents=True, exist_ok=True)
    path = cache_root / f"{key}.grib2"
    checksum_path = cache_root / f"{key}.sha256"
    if path.exists() or checksum_path.exists():
        if not path.is_file() or not checksum_path.is_file():
            raise ValueError("GRIB cache is incomplete")
        expected = checksum_path.read_text(encoding="ascii").strip()
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if expected != actual:
            raise ValueError("GRIB cache checksum mismatch")
        return CachedGrib(path, actual, url, True)
    payload = load()
    checksum = hashlib.sha256(payload).hexdigest()
    temporary_path = path.with_suffix(".grib2.tmp")
    temporary_checksum = checksum_path.with_suffix(".sha256.tmp")
    temporary_path.write_bytes(payload)
    temporary_checksum.write_text(checksum + "\n", encoding="ascii")
    temporary_path.replace(path)
    temporary_checksum.replace(checksum_path)
    return CachedGrib(path, checksum, url, False)


def _require_grib(value: bytes) -> bytes:
    if not value.startswith(b"GRIB") or not value.endswith(b"7777"):
        raise ValueError("payload is not GRIB")
    return value


def _decompress_bz2(value: bytes, max_bytes: int) -> bytes:
    decoder = bz2.BZ2Decompressor()
    output = bytearray()
    try:
        for offset in range(0, len(value), 64 * 1024):
            chunk = value[offset : offset + 64 * 1024]
            while (chunk or not decoder.needs_input) and not decoder.eof:
                decoded = decoder.decompress(chunk, max_length=max_bytes - len(output) + 1)
                chunk = b""
                output.extend(decoded)
                if len(output) > max_bytes:
                    raise ValueError("DWD decompressed size exceeds limit")
        if not decoder.eof:
            raise ValueError("DWD payload is incomplete bzip2 data")
    except OSError as error:
        raise ValueError("DWD payload is invalid bzip2 data") from error
    return bytes(output)


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
NOAA_GFS_FILTER = "https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl"
NOAA_GFS_VARIABLES = {
    "precipitation": ("var_APCP", "lev_surface"),
    "pressure_msl": ("var_PRMSL", "lev_mean_sea_level"),
    "relative_humidity_2m": ("var_RH", "lev_2_m_above_ground"),
    "temperature_2m": ("var_TMP", "lev_2_m_above_ground"),
    "wind_u_10m": ("var_UGRD", "lev_10_m_above_ground"),
    "wind_v_10m": ("var_VGRD", "lev_10_m_above_ground"),
}
