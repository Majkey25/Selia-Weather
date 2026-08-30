from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path

from .sources.grib_points import (
    GeoPoint,
    GribPointIndex,
    SampledMessage,
    build_grib_point_index,
    decode_grib_points,
    elevation_by_point,
    regular_grid_points,
    to_forecast_values,
    to_wind_component_values,
)
from .sources.official_runs import (
    CachedGrib,
    ChmiAladinRequest,
    DwdIconRequest,
    EcmwfOpenRequest,
    NoaaGefsRequest,
    NoaaGfsRequest,
    download_chmi_aladin,
    download_dwd_icon,
    download_ecmwf,
    download_noaa_gefs,
    download_noaa_gfs,
)
from .static_feed import FeedGrid
from .types import ForecastValue

LEAD_HOURS = (0, 6, 12, 18, 24)
CANONICAL_FIELDS = {
    "precipitation": "mm",
    "temperature_2m": "°C",
    "wind_u_10m": "m/s",
    "wind_v_10m": "m/s",
}
MODEL_IDS = frozenset(
    {
        "chmi_aladin_cz_1km",
        "dwd_icon_eu",
        "ecmwf_aifs_open",
        "ecmwf_ifs_open",
        "noaa_gefs",
        "noaa_gfs",
    }
)
FieldDownload = Callable[[str, int], CachedGrib]


def latest_complete_cycle(now: datetime) -> datetime:
    if now.tzinfo is None or now.utcoffset() != UTC.utcoffset(now):
        raise ValueError("now must be timezone-aware UTC")
    conservative = now - timedelta(hours=7)
    return conservative.replace(
        hour=conservative.hour // 6 * 6,
        minute=0,
        second=0,
        microsecond=0,
    )


def select_lead_messages(
    messages: tuple[SampledMessage, ...],
    run_time: datetime,
    lead_hours: tuple[int, ...],
) -> tuple[SampledMessage, ...]:
    if run_time.tzinfo is None or run_time.utcoffset() != UTC.utcoffset(run_time):
        raise ValueError("run_time must be timezone-aware UTC")
    if not lead_hours or lead_hours != tuple(sorted(set(lead_hours))) or lead_hours[0] < 0:
        raise ValueError("lead_hours must be non-empty, sorted, non-negative, and unique")
    by_lead: dict[int, SampledMessage] = {}
    for message in messages:
        if message.run_time != run_time:
            continue
        seconds = (message.valid_time - run_time).total_seconds()
        if seconds < 0 or seconds % 3_600:
            raise ValueError("forecast validity is not a whole-hour lead")
        lead = int(seconds // 3_600)
        if lead in by_lead:
            raise ValueError(f"duplicate forecast lead: {lead}")
        by_lead[lead] = message
    missing = tuple(lead for lead in lead_hours if lead not in by_lead)
    if missing:
        raise ValueError(f"missing required lead: {missing}")
    return tuple(by_lead[lead] for lead in lead_hours)


def load_operational_values(
    run_time: datetime,
    cache_root: Path,
    *,
    grid: FeedGrid | None = None,
    lead_hours: tuple[int, ...] = LEAD_HOURS,
) -> tuple[ForecastValue, ...]:
    active_grid = grid or FeedGrid()
    points = regular_grid_points(
        active_grid.south,
        active_grid.north,
        active_grid.west,
        active_grid.east,
        active_grid.step,
    )
    elevation_day = run_time + timedelta(hours=6) if run_time.hour == 18 else run_time
    elevation_run = elevation_day.replace(hour=0)
    elevation_file = download_dwd_icon(
        DwdIconRequest(elevation_run, 0, "surface_elevation"),
        cache_root / "dwd-icon-eu",
    )
    dwd_index = build_grib_point_index(elevation_file.path, points)
    elevation_message = decode_grib_points(
        elevation_file.path,
        points,
        point_index=dwd_index,
    )
    if len(elevation_message) != 1:
        raise ValueError("DWD elevation field must contain one message")
    elevations = elevation_by_point(elevation_message[0])
    chmi_points = operational_points_for_model("chmi_aladin_cz_1km", points)

    rows: list[ForecastValue] = []
    rows.extend(
        load_sampled_model_values(
            "dwd_icon_eu",
            run_time,
            points,
            elevations,
            lead_hours,
            lambda variable, lead: download_dwd_icon(
                DwdIconRequest(run_time, lead, variable),
                cache_root / "dwd-icon-eu",
            ),
            point_index=dwd_index,
        )
    )
    rows.extend(
        _load_chmi(run_time, chmi_points, elevations, lead_hours, cache_root / "chmi")
    )
    rows.extend(
        load_sampled_model_values(
            "noaa_gfs",
            run_time,
            points,
            elevations,
            lead_hours,
            lambda variable, lead: download_noaa_gfs(
                NoaaGfsRequest(run_time, lead, variable),
                cache_root / "noaa-gfs",
            ),
        )
    )
    rows.extend(
        load_sampled_model_values(
            "noaa_gefs",
            run_time,
            points,
            elevations,
            lead_hours,
            lambda variable, lead: download_noaa_gefs(
                NoaaGefsRequest("mean", run_time, lead, variable),
                cache_root / "noaa-gefs",
            ),
        )
    )
    for model_id, model in (("ecmwf_ifs_open", "ifs"), ("ecmwf_aifs_open", "aifs-single")):
        rows.extend(
            load_sampled_model_values(
                model_id,
                run_time,
                points,
                elevations,
                lead_hours,
                lambda variable, lead, model=model: download_ecmwf(
                    EcmwfOpenRequest(model, run_time, lead, variable),
                    cache_root / model,
                ),
            )
        )
    result = tuple(rows)
    validate_operational_values(
        result,
        points,
        lead_hours,
        model_point_counts={"chmi_aladin_cz_1km": len(chmi_points)},
    )
    return result


def operational_points_for_model(
    model_id: str,
    points: tuple[GeoPoint, ...],
) -> tuple[GeoPoint, ...]:
    if model_id == "chmi_aladin_cz_1km":
        return tuple(
            point
            for point in points
            if CHMI_LATITUDE[0] <= point.latitude <= CHMI_LATITUDE[1]
            and CHMI_LONGITUDE[0] <= point.longitude <= CHMI_LONGITUDE[1]
        )
    return points


def load_sampled_model_values(
    model_id: str,
    run_time: datetime,
    points: tuple[GeoPoint, ...],
    elevations: Mapping[GeoPoint, float],
    lead_hours: tuple[int, ...],
    download: FieldDownload,
    *,
    point_index: GribPointIndex | None = None,
) -> tuple[ForecastValue, ...]:
    rows: list[ForecastValue] = []
    active_index = point_index
    for variable, unit in CANONICAL_FIELDS.items():
        messages: list[SampledMessage] = []
        variable_leads = tuple(
            lead for lead in lead_hours if variable != "precipitation" or lead > 0
        )
        if not variable_leads:
            raise ValueError(f"{variable} requires a positive forecast lead")
        for lead in variable_leads:
            field = download(variable, lead)
            if active_index is None:
                active_index = build_grib_point_index(field.path, points)
            decoded = decode_grib_points(field.path, points, point_index=active_index)
            messages.extend(select_lead_messages(decoded, run_time, (lead,)))
        rows.extend(
            to_forecast_values(
                tuple(messages),
                model_id=model_id,
                variable=variable,
                canonical_unit=unit,
                elevation_by_point=elevations,
            )
        )
    return tuple(rows)


def _load_chmi(
    run_time: datetime,
    points: tuple[GeoPoint, ...],
    elevations: Mapping[GeoPoint, float],
    lead_hours: tuple[int, ...],
    cache_root: Path,
) -> tuple[ForecastValue, ...]:
    temperature_file = download_chmi_aladin(
        ChmiAladinRequest(run_time, "temperature_2m"),
        cache_root,
    )
    point_index = build_grib_point_index(temperature_file.path, points)
    temperature = select_lead_messages(
        decode_grib_points(temperature_file.path, points, point_index=point_index),
        run_time,
        lead_hours,
    )
    precipitation_file = download_chmi_aladin(
        ChmiAladinRequest(run_time, "precipitation"),
        cache_root,
    )
    precipitation_leads = tuple(lead for lead in lead_hours if lead > 0)
    if not precipitation_leads:
        raise ValueError("CHMI precipitation requires a positive forecast lead")
    precipitation = normalize_chmi_precipitation_messages(
        select_lead_messages(
            decode_grib_points(precipitation_file.path, points, point_index=point_index),
            run_time,
            precipitation_leads,
        ),
    )
    speed_file = download_chmi_aladin(ChmiAladinRequest(run_time, "wind_speed_10m"), cache_root)
    direction_file = download_chmi_aladin(
        ChmiAladinRequest(run_time, "wind_direction_10m"),
        cache_root,
    )
    speed = select_lead_messages(
        decode_grib_points(speed_file.path, points, point_index=point_index),
        run_time,
        lead_hours,
    )
    direction = select_lead_messages(
        decode_grib_points(direction_file.path, points, point_index=point_index),
        run_time,
        lead_hours,
    )
    return to_forecast_values(
        temperature,
        model_id="chmi_aladin_cz_1km",
        variable="temperature_2m",
        canonical_unit="°C",
        elevation_by_point=elevations,
    ) + to_forecast_values(
        precipitation,
        model_id="chmi_aladin_cz_1km",
        variable="precipitation",
        canonical_unit="mm",
        elevation_by_point=elevations,
    ) + to_wind_component_values(
        speed,
        direction,
        "chmi_aladin_cz_1km",
        elevations,
    )


def normalize_chmi_precipitation_messages(
    messages: tuple[SampledMessage, ...],
) -> tuple[SampledMessage, ...]:
    if not messages:
        raise ValueError("CHMI precipitation messages are required")
    ordered = tuple(sorted(messages, key=lambda message: message.end_step_hours))
    first = ordered[0]
    coordinates = tuple((point.latitude, point.longitude) for point in first.values)
    if any(
        message.run_time != first.run_time
        or message.valid_time != message.run_time + timedelta(hours=message.end_step_hours)
        or message.unit != "unknown"
        or message.step_type != "instant"
        or message.start_step_hours != message.end_step_hours
        or message.end_step_hours <= 0
        or tuple((point.latitude, point.longitude) for point in message.values) != coordinates
        for message in ordered
    ) or len({message.end_step_hours for message in ordered}) != len(ordered):
        raise ValueError("CHMI precipitation metadata is invalid")
    return tuple(
        replace(message, unit="kg/m²", step_type="accum", start_step_hours=0)
        for message in ordered
    )


def validate_operational_values(
    values: tuple[ForecastValue, ...],
    points: tuple[GeoPoint, ...],
    lead_hours: tuple[int, ...],
    *,
    model_point_counts: Mapping[str, int] | None = None,
) -> None:
    counts = {model_id: 0 for model_id in MODEL_IDS}
    for value in values:
        if value.model_id not in counts or value.variable not in CANONICAL_FIELDS:
            raise ValueError("operational feed contains an unexpected model or variable")
        counts[value.model_id] += 1
    expected = {
        model_id: (model_point_counts or {}).get(model_id, len(points))
        * len(lead_hours)
        * len(CANONICAL_FIELDS)
        for model_id in MODEL_IDS
    }
    incomplete = {
        model_id: count
        for model_id, count in counts.items()
        if count != expected[model_id]
    }
    if incomplete:
        raise ValueError(f"operational feed is incomplete: {incomplete}")


CHMI_LATITUDE = (48.551, 51.056)
CHMI_LONGITUDE = (12.09, 18.86)
