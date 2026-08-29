from __future__ import annotations

import hashlib
from dataclasses import replace
from datetime import UTC, datetime
from pathlib import Path
from urllib.request import Request

import pytest

from aladin_ensemble.sources.chmi_download import (
    CZECH_TARGETS,
    ChmiHttpResponse,
    ChmiMonthlyDownloader,
    ChmiMonthlyRequest,
    select_station_cohort,
)
from aladin_ensemble.sources.chmi_station import ElementMetadata, Station

REGIONS = {
    "REGION_PRAGUE",
    "REGION_CENTRAL_BOHEMIA",
    "REGION_SOUTH_BOHEMIAN",
    "REGION_PLZEN",
    "REGION_KARLOVY_VARY",
    "REGION_USTI_NAD_LABEM",
    "REGION_LIBEREC",
    "REGION_HRADEC_KRALOVE",
    "REGION_PARDUBICE",
    "REGION_VYSOCINA",
    "REGION_SOUTH_MORAVIAN",
    "REGION_OLOMOUC",
    "REGION_ZLIN",
    "REGION_MORAVIAN_SILESIAN",
}


def _metadata(stations: tuple[Station, ...]) -> dict[tuple[str, str, str], ElementMetadata]:
    result: dict[tuple[str, str, str], ElementMetadata] = {}
    for station in stations:
        for observation_type, element, unit, height in (
            ("10M", "T", "°C", 2.0),
            ("10M", "F", "m/s", 10.0),
            ("10M", "D", "stupně", 10.0),
            ("1H", "SRA1H", "mm", 0.0),
        ):
            key = (observation_type, station.wigos_id, element)
            result[key] = ElementMetadata(
                observation_type,
                station.wigos_id,
                element,
                unit,
                height,
                "continuous",
            )
    return result


def _station(index: int, *, elevation: float = 250.0) -> Station:
    target = CZECH_TARGETS[index]
    return Station(
        wigos_id=f"0-20000-0-{11_500 + index}",
        name=f"Station {index}",
        latitude=target.latitude,
        longitude=target.longitude,
        elevation_m=elevation,
    )


def test_selects_every_czech_region_and_adds_missing_elevation_band() -> None:
    regional = tuple(
        _station(index, elevation=150.0 if index < 7 else 450.0)
        for index in range(len(CZECH_TARGETS))
    )
    high = Station("0-20000-0-11999", "Mountain", 50.73, 15.74, 1_315.0)
    stations = (*regional, high)

    selected = select_station_cohort(CZECH_TARGETS, stations, _metadata(stations))

    assert len(CZECH_TARGETS) == 14
    selected_regions = {
        item.target.region for item in selected if item.target.region != "REGION_CZECHIA"
    }
    assert selected_regions == REGIONS
    assert len({item.station.wigos_id for item in selected}) == len(selected)
    assert {item.elevation_band for item in selected} == {"low", "middle", "high"}
    assert any(item.station.wigos_id == high.wigos_id for item in selected)


def test_station_selection_skips_incomplete_station_and_rejects_missing_band() -> None:
    targets = (CZECH_TARGETS[0],)
    incomplete = _station(0, elevation=100.0)
    complete = Station("0-20000-0-11888", "Complete", 50.08, 14.44, 120.0)
    middle = Station("0-20000-0-11889", "Middle", 50.1, 14.5, 500.0)
    high = Station("0-20000-0-11890", "High", 50.2, 14.6, 900.0)
    metadata = _metadata((incomplete, complete, middle, high))
    metadata.pop(("1H", incomplete.wigos_id, "SRA1H"))

    selected = select_station_cohort(targets, (incomplete, complete, middle, high), metadata)

    assert selected[0].station.wigos_id == complete.wigos_id
    with pytest.raises(ValueError, match="high elevation"):
        select_station_cohort(targets, (complete, middle), _metadata((complete, middle)))


def test_station_selection_requires_near_standard_comparison_heights() -> None:
    targets = (CZECH_TARGETS[0],)
    near = _station(0, elevation=100.0)
    standard = Station("0-20000-0-11888", "Standard", 50.08, 14.46, 120.0)
    middle = Station("0-20000-0-11889", "Middle", 50.1, 14.5, 500.0)
    high = Station("0-20000-0-11890", "High", 50.2, 14.6, 900.0)
    metadata = _metadata((near, standard, middle, high))
    for element in ("F", "D"):
        key = ("10M", near.wigos_id, element)
        metadata[key] = replace(metadata[key], height_m=15.0)
    for station in (standard, middle, high):
        for element in ("F", "D"):
            key = ("10M", station.wigos_id, element)
            metadata[key] = replace(metadata[key], height_m=10.5)
        key = ("10M", station.wigos_id, "T")
        metadata[key] = replace(metadata[key], height_m=2.1)

    selected = select_station_cohort(targets, (near, standard, middle, high), metadata)

    assert selected[0].station.wigos_id == standard.wigos_id


def test_monthly_urls_separate_instant_and_hourly_precipitation_truth() -> None:
    ten_minute = ChmiMonthlyRequest("0-20000-0-11502", 2026, 4, "10min")
    hourly = ChmiMonthlyRequest("0-20000-0-11502", 2026, 4, "1hour")

    assert ten_minute.url.endswith("/10min/04/10m-0-20000-0-11502-202604.json")
    assert hourly.url.endswith("/1hour/04/1h-0-20000-0-11502-202604.json")


def test_monthly_downloader_caches_bytes_and_uses_conditional_request(tmp_path: Path) -> None:
    body = b'{"datumVytvoreni":"2026-05-01T00:00:00Z"}'
    calls: list[Request] = []

    def fetch(request: Request) -> ChmiHttpResponse:
        calls.append(request)
        if len(calls) == 1:
            return ChmiHttpResponse(200, {"ETag": '"april"'}, body)
        return ChmiHttpResponse(304, {}, b"")

    downloader = ChmiMonthlyDownloader(
        tmp_path,
        fetch=fetch,
        now=lambda: datetime(2026, 8, 26, tzinfo=UTC),
    )
    request = ChmiMonthlyRequest("0-20000-0-11502", 2026, 4, "10min")

    first = downloader.download(request)
    second = downloader.download(request)

    assert first.checksum_sha256 == hashlib.sha256(body).hexdigest()
    assert second.path == first.path
    assert calls[1].get_header("If-none-match") == '"april"'
    assert first.path.read_bytes() == body


def test_monthly_downloader_rejects_partial_month_http_error_and_collision(tmp_path: Path) -> None:
    def now() -> datetime:
        return datetime(2026, 8, 26, tzinfo=UTC)

    downloader = ChmiMonthlyDownloader(
        tmp_path,
        fetch=lambda _: ChmiHttpResponse(404, {}, b"missing"),
        now=now,
    )
    with pytest.raises(ValueError, match="complete month"):
        downloader.download(ChmiMonthlyRequest("0-20000-0-11502", 2026, 8, "10min"))
    with pytest.raises(RuntimeError, match="HTTP 404"):
        downloader.download(ChmiMonthlyRequest("0-20000-0-11502", 2026, 4, "10min"))

    body = b'{"datumVytvoreni":"2026-05-01T00:00:00Z"}'
    checksum = hashlib.sha256(body).hexdigest()
    raw = tmp_path / "collision" / "raw"
    raw.mkdir(parents=True)
    (raw / f"{checksum}.json").write_bytes(b"wrong")
    collision = ChmiMonthlyDownloader(
        tmp_path / "collision",
        fetch=lambda _: ChmiHttpResponse(200, {}, body),
        now=now,
    )
    with pytest.raises(ValueError, match="cache collision"):
        collision.download(ChmiMonthlyRequest("0-20000-0-11502", 2026, 4, "10min"))


def test_monthly_downloader_retries_operational_failure(tmp_path: Path) -> None:
    body = b'{"datumVytvoreni":"2026-05-01T00:00:00Z"}'
    calls = 0

    def transient(_: Request) -> ChmiHttpResponse:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise RuntimeError("request failed: reset")
        return ChmiHttpResponse(200, {}, body)

    downloader = ChmiMonthlyDownloader(
        tmp_path,
        fetch=transient,
        now=lambda: datetime(2026, 8, 26, tzinfo=UTC),
        retry_delay_seconds=0,
    )

    response = downloader.download(
        ChmiMonthlyRequest("0-20000-0-11502", 2026, 4, "10min")
    )

    assert response.path.is_file()
    assert calls == 2
