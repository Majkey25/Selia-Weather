from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import pytest

from aladin_ensemble.sources.official_runs import (
    DwdIconRequest,
    GribField,
    build_dwd_icon_url,
    build_grib_get_data_command,
    parse_grib_get_data,
)


def field(variable: str = "temperature_2m", unit: str = "K") -> GribField:
    return GribField(
        source_id="dwd-icon-eu",
        model_id="dwd_icon_eu",
        run_time=datetime(2026, 8, 29, 6, tzinfo=UTC),
        valid_time=datetime(2026, 8, 29, 9, tzinfo=UTC),
        variable=variable,
        source_unit=unit,
        canonical_unit="°C" if variable == "temperature_2m" else "km/h",
    )


def test_grib_command_is_explicit_and_machine_readable(tmp_path: Path) -> None:
    path = tmp_path / "field.grib2"

    command = build_grib_get_data_command(path)

    assert command == (
        "grib_get_data",
        "-L%.6f %.6f",
        "-F%.8g",
        "-m",
        "MISSING",
        str(path),
    )


def test_dwd_icon_url_uses_verified_operational_filename() -> None:
    request = DwdIconRequest(
        run_time=datetime(2026, 8, 29, 6, tzinfo=UTC),
        lead_hour=3,
        variable="temperature_2m",
    )

    assert build_dwd_icon_url(request) == (
        "https://opendata.dwd.de/weather/nwp/icon-eu/grib/06/t_2m/"
        "icon-eu_europe_regular-lat-lon_single-level_2026082906_003_T_2M.grib2.bz2"
    )


@pytest.mark.parametrize(
    "run_time, lead_hour, variable",
    (
        (datetime(2026, 8, 29, 7, tzinfo=UTC), 3, "temperature_2m"),
        (datetime(2026, 8, 29, 6, tzinfo=UTC), 121, "temperature_2m"),
        (datetime(2026, 8, 29, 6, tzinfo=UTC), 3, "unknown"),
    ),
)
def test_dwd_request_rejects_unsupported_contract(
    run_time: datetime,
    lead_hour: int,
    variable: str,
) -> None:
    with pytest.raises(ValueError):
        DwdIconRequest(run_time, lead_hour, variable)


def test_parser_converts_kelvin_and_keeps_missing_values() -> None:
    output = """Latitude Longitude Value
49.000000 14.000000 293.15
49.000000 14.050000 MISSING
"""

    values = parse_grib_get_data(
        output,
        field(),
        elevation_by_point={(49.0, 14.0): 250.0, (49.0, 14.05): 251.0},
    )

    assert [value.value for value in values] == [20.0, None]
    assert all(value.unit == "°C" for value in values)


def test_parser_normalises_longitude_and_converts_wind_speed() -> None:
    output = """Latitude Longitude Value
49.000000 374.000000 5
"""

    values = parse_grib_get_data(
        output,
        field("wind_speed_10m", "m/s"),
        elevation_by_point={(49.0, 14.0): 250.0},
    )

    assert values[0].longitude == 14.0
    assert values[0].value == 18.0
    assert values[0].unit == "km/h"


@pytest.mark.parametrize(
    "output, message",
    (
        ("bad header\n49 14 293\n", "header"),
        ("Latitude Longitude Value\n49 14 nope\n", "value"),
        ("Latitude Longitude Value\n49 14 293\n49 14 294\n", "duplicate"),
        ("Latitude Longitude Value\n49 14 293 extra\n", "columns"),
    ),
)
def test_parser_rejects_malformed_output(output: str, message: str) -> None:
    with pytest.raises(ValueError, match=message):
        parse_grib_get_data(output, field(), elevation_by_point={(49.0, 14.0): 250.0})


def test_parser_requires_matching_orography() -> None:
    with pytest.raises(ValueError, match="elevation"):
        parse_grib_get_data(
            "Latitude Longitude Value\n49 14 293.15\n",
            field(),
            elevation_by_point={},
        )
