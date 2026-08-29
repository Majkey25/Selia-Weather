from __future__ import annotations

import bz2
from dataclasses import replace
from datetime import UTC, datetime
from email.message import Message
from pathlib import Path
from urllib.error import HTTPError

import pytest

from aladin_ensemble.sources.official_runs import (
    ChmiAladinRequest,
    DwdGlobalRequest,
    DwdIconRequest,
    EcmwfOpenRequest,
    GribField,
    NoaaGefsRequest,
    NoaaGfsRequest,
    build_chmi_aladin_url,
    build_dwd_global_url,
    build_dwd_icon_url,
    build_ecmwf_request,
    build_grib_get_data_command,
    build_noaa_gefs_url,
    build_noaa_gfs_url,
    download_chmi_aladin,
    download_dwd_icon,
    download_ecmwf,
    download_http_with_retry,
    download_noaa_gefs,
    download_noaa_gfs,
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


def test_dwd_surface_elevation_uses_static_hsurf_field() -> None:
    request = DwdIconRequest(
        run_time=datetime(2026, 8, 29, 6, tzinfo=UTC),
        lead_hour=0,
        variable="surface_elevation",
    )

    assert build_dwd_icon_url(request) == (
        "https://opendata.dwd.de/weather/nwp/icon-eu/grib/00/hsurf/"
        "icon-eu_europe_regular-lat-lon_time-invariant_2026082900_HSURF.grib2.bz2"
    )


def test_dwd_global_url_uses_verified_icosahedral_filename() -> None:
    request = DwdGlobalRequest(
        run_time=datetime(2026, 8, 29, 0, tzinfo=UTC),
        lead_hour=24,
        variable="temperature_2m",
    )

    assert build_dwd_global_url(request) == (
        "https://opendata.dwd.de/weather/nwp/icon/grib/00/t_2m/"
        "icon_global_icosahedral_single-level_2026082900_024_T_2M.grib2.bz2"
    )

def test_chmi_aladin_url_uses_verified_operational_filename() -> None:
    request = ChmiAladinRequest(
        run_time=datetime(2026, 8, 28, 0, tzinfo=UTC),
        variable="temperature_2m",
    )

    assert build_chmi_aladin_url(request) == (
        "https://opendata.chmi.cz/meteorology/weather/nwp_aladin/CZ_1km/00/"
        "ALADCZ1K4opendata_2026082800_CLSTEMPERATURE.grb.bz2"
    )


@pytest.mark.parametrize(
    "run_hour, variable",
    ((7, "temperature_2m"), (0, "unknown")),
)
def test_chmi_aladin_request_rejects_unsupported_contract(
    run_hour: int,
    variable: str,
) -> None:
    with pytest.raises(ValueError):
        ChmiAladinRequest(datetime(2026, 8, 28, run_hour, tzinfo=UTC), variable)


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


def test_noaa_gfs_url_uses_czech_subregion_filter() -> None:
    request = NoaaGfsRequest(
        run_time=datetime(2026, 8, 29, 6, tzinfo=UTC),
        lead_hour=3,
        variable="temperature_2m",
    )

    assert build_noaa_gfs_url(request) == (
        "https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl?"
        "file=gfs.t06z.pgrb2.0p25.f003&var_TMP=on&lev_2_m_above_ground=on&"
        "subregion=&leftlon=11.4&rightlon=19.5&toplat=51.7&bottomlat=47.95&"
        "dir=%2Fgfs.20260829%2F06%2Fatmos"
    )


@pytest.mark.parametrize(
    "run_time, lead_hour, variable",
    (
        (datetime(2026, 8, 29, 7, tzinfo=UTC), 3, "temperature_2m"),
        (datetime(2026, 8, 29, 6, tzinfo=UTC), 385, "temperature_2m"),
        (datetime(2026, 8, 29, 6, tzinfo=UTC), 3, "unknown"),
    ),
)
def test_noaa_request_rejects_unsupported_contract(
    run_time: datetime,
    lead_hour: int,
    variable: str,
) -> None:
    with pytest.raises(ValueError):
        NoaaGfsRequest(run_time, lead_hour, variable)


def test_noaa_gefs_url_uses_extended_ensemble_mean() -> None:
    request = NoaaGefsRequest(
        statistic="mean",
        run_time=datetime(2026, 8, 29, 0, tzinfo=UTC),
        lead_hour=840,
        variable="temperature_2m",
    )

    assert build_noaa_gefs_url(request) == (
        "https://nomads.ncep.noaa.gov/cgi-bin/filter_gefs_atmos_0p50a.pl?"
        "file=geavg.t00z.pgrb2a.0p50.f840&var_TMP=on&lev_2_m_above_ground=on&"
        "subregion=&leftlon=11.4&rightlon=19.5&toplat=51.7&bottomlat=47.95&"
        "dir=%2Fgefs.20260829%2F00%2Fatmos%2Fpgrb2ap5"
    )


def test_noaa_gefs_url_uses_ensemble_spread() -> None:
    request = NoaaGefsRequest(
        statistic="spread",
        run_time=datetime(2026, 8, 29, 0, tzinfo=UTC),
        lead_hour=24,
        variable="temperature_2m",
    )

    assert "file=gespr.t00z.pgrb2a.0p50.f024" in build_noaa_gefs_url(request)


@pytest.mark.parametrize(
    "statistic, run_hour, lead_hour",
    (("unknown", 0, 24), ("mean", 6, 840), ("mean", 0, 841), ("mean", 0, 25)),
)
def test_noaa_gefs_rejects_unsupported_contract(
    statistic: str,
    run_hour: int,
    lead_hour: int,
) -> None:
    with pytest.raises(ValueError):
        NoaaGefsRequest(
            statistic,
            datetime(2026, 8, 29, run_hour, tzinfo=UTC),
            lead_hour,
            "temperature_2m",
        )


def test_ecmwf_request_preserves_model_run_and_field() -> None:
    request = EcmwfOpenRequest(
        model="ifs",
        run_time=datetime(2026, 8, 29, 6, tzinfo=UTC),
        lead_hour=3,
        variable="temperature_2m",
    )

    assert build_ecmwf_request(request) == {
        "date": "20260829",
        "param": "2t",
        "step": 3,
        "stream": "oper",
        "time": 6,
        "type": "fc",
    }


@pytest.mark.parametrize(
    "model, lead_hour",
    (("ifs", 2), ("aifs-single", 3), ("unknown", 6), ("ifs", 363)),
)
def test_ecmwf_request_rejects_unsupported_contract(model: str, lead_hour: int) -> None:
    with pytest.raises(ValueError):
        EcmwfOpenRequest(
            model=model,
            run_time=datetime(2026, 8, 29, 6, tzinfo=UTC),
            lead_hour=lead_hour,
            variable="temperature_2m",
        )


def test_ecmwf_download_reuses_verified_cache(tmp_path: Path) -> None:
    ecmwf_request = EcmwfOpenRequest(
        "ifs",
        datetime(2026, 8, 29, 6, tzinfo=UTC),
        3,
        "temperature_2m",
    )
    calls: list[tuple[str, dict[str, object]]] = []

    class Client:
        def retrieve(self, request: dict[str, object], target: str) -> object:
            calls.append((ecmwf_request.model, request))
            Path(target).write_bytes(b"GRIBfixture7777")
            return None

    first = download_ecmwf(ecmwf_request, tmp_path, client_factory=lambda _model: Client())
    second = download_ecmwf(ecmwf_request, tmp_path, client_factory=lambda _model: Client())

    assert first.path.read_bytes() == b"GRIBfixture7777"
    assert second == replace(first, from_cache=True)
    assert calls == [("ifs", build_ecmwf_request(ecmwf_request))]


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


def test_dwd_download_decompresses_and_reuses_verified_cache(tmp_path: Path) -> None:
    request = DwdIconRequest(datetime(2026, 8, 29, 6, tzinfo=UTC), 0, "temperature_2m")
    compressed = bz2.compress(b"GRIBfixture7777")
    calls = 0

    def http_get(url: str, timeout: float, max_bytes: int) -> bytes:
        nonlocal calls
        assert url == build_dwd_icon_url(request)
        assert timeout == 15.0
        assert max_bytes > len(compressed)
        calls += 1
        return compressed

    first = download_dwd_icon(request, tmp_path, http_get=http_get)
    second = download_dwd_icon(request, tmp_path, http_get=http_get)

    assert first.path.read_bytes() == b"GRIBfixture7777"
    assert second == replace(first, from_cache=True)
    assert calls == 1


def test_dwd_download_rejects_corrupt_cache(tmp_path: Path) -> None:
    request = DwdIconRequest(datetime(2026, 8, 29, 6, tzinfo=UTC), 0, "temperature_2m")
    result = download_dwd_icon(
        request,
        tmp_path,
        http_get=lambda _url, _timeout, _max_bytes: bz2.compress(b"GRIBfixture7777"),
    )
    result.path.write_bytes(b"corrupt")

    with pytest.raises(ValueError, match="cache checksum"):
        download_dwd_icon(
            request,
            tmp_path,
            http_get=lambda _url, _timeout, _max_bytes: b"should not download",
        )


def test_dwd_download_rejects_invalid_or_oversized_payload(tmp_path: Path) -> None:
    request = DwdIconRequest(datetime(2026, 8, 29, 6, tzinfo=UTC), 0, "temperature_2m")

    with pytest.raises(ValueError, match="bzip2"):
        download_dwd_icon(
            request,
            tmp_path / "invalid",
            http_get=lambda _url, _timeout, _max_bytes: b"not bzip2",
        )
    with pytest.raises(ValueError, match="decompressed size"):
        download_dwd_icon(
            request,
            tmp_path / "large",
            http_get=lambda _url, _timeout, _max_bytes: bz2.compress(b"GRIB" + b"x" * 100),
            max_decompressed_bytes=32,
        )


def test_chmi_aladin_download_reuses_verified_cache(tmp_path: Path) -> None:
    request = ChmiAladinRequest(datetime(2026, 8, 28, 0, tzinfo=UTC), "temperature_2m")
    calls = 0

    def http_get(url: str, _timeout: float, _max_bytes: int) -> bytes:
        nonlocal calls
        assert url == build_chmi_aladin_url(request)
        calls += 1
        return bz2.compress(b"GRIBfixture7777")

    first = download_chmi_aladin(request, tmp_path, http_get=http_get)
    second = download_chmi_aladin(request, tmp_path, http_get=http_get)

    assert second == replace(first, from_cache=True)
    assert calls == 1


def test_noaa_download_reuses_verified_cache(tmp_path: Path) -> None:
    request = NoaaGfsRequest(datetime(2026, 8, 29, 6, tzinfo=UTC), 3, "temperature_2m")
    calls = 0

    def http_get(url: str, timeout: float, max_bytes: int) -> bytes:
        nonlocal calls
        assert url == build_noaa_gfs_url(request)
        assert timeout == 15.0
        assert max_bytes >= 459
        calls += 1
        return b"GRIBfixture7777"

    first = download_noaa_gfs(request, tmp_path, http_get=http_get)
    second = download_noaa_gfs(request, tmp_path, http_get=http_get)

    assert first.path.read_bytes() == b"GRIBfixture7777"
    assert second == replace(first, from_cache=True)
    assert calls == 1


def test_noaa_gefs_download_reuses_verified_cache(tmp_path: Path) -> None:
    request = NoaaGefsRequest(
        "mean",
        datetime(2026, 8, 29, 0, tzinfo=UTC),
        24,
        "temperature_2m",
    )
    calls = 0

    def http_get(url: str, _timeout: float, _max_bytes: int) -> bytes:
        nonlocal calls
        assert url == build_noaa_gefs_url(request)
        calls += 1
        return b"GRIBfixture7777"

    first = download_noaa_gefs(request, tmp_path, http_get=http_get)
    second = download_noaa_gefs(request, tmp_path, http_get=http_get)

    assert second == replace(first, from_cache=True)
    assert calls == 1


def test_http_download_honours_retry_after() -> None:
    calls = 0
    delays: list[float] = []
    headers = Message()
    headers["Retry-After"] = "2"

    class Response:
        def __enter__(self) -> Response:
            return self

        def __exit__(self, *_args: object) -> None:
            return None

        def read(self, _limit: int) -> bytes:
            return b"GRIBfixture7777"

    def open_url(url: str, _timeout: float) -> Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise HTTPError(url, 429, "rate limited", headers, None)
        return Response()

    payload = download_http_with_retry(
        "https://example.com/field.grib2",
        timeout=10.0,
        max_bytes=1_000,
        open_url=open_url,
        sleeper=delays.append,
        attempts=2,
    )

    assert payload == b"GRIBfixture7777"
    assert calls == 2
    assert delays == [2.0]
