from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import cast

import pytest

import aladin_ensemble.run_worldwide_backtest as runner
from aladin_ensemble.registry import JsonValue
from aladin_ensemble.types import ForecastValue, Observation
from aladin_ensemble.worldwide import WORLD_MODEL_IDS, WORLD_TARGETS


def test_worldwide_preflight_is_network_free_and_bounded(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    history = tmp_path / "isd-history.csv"
    history.write_text(_history_fixture(), encoding="utf-8")

    result = runner.main(
        [
            "--station-history",
            str(history),
            "--train-start",
            "2025-01-01",
            "--train-end",
            "2025-03-31",
            "--holdout-start",
            "2025-04-01",
            "--holdout-end",
            "2025-04-30",
            "--provider-limit",
            "10000",
        ]
    )

    assert result == 0
    payload = cast(dict[str, JsonValue], json.loads(capsys.readouterr().out))
    assert payload == {
        "forecast_requests": 77,
        "holdout": {"end": "2025-04-30", "start": "2025-04-01"},
        "model_count": len(WORLD_MODEL_IDS),
        "station_count": len(WORLD_TARGETS),
        "status": "ready",
        "training": {"end": "2025-03-31", "start": "2025-01-01"},
        "truth_requests": 3,
    }
    assert {path.name for path in tmp_path.iterdir()} == {"isd-history.csv"}


def test_worldwide_execute_writes_locked_diagnostic_report(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    history = tmp_path / "isd-history.csv"
    history.write_text(_history_fixture(), encoding="utf-8")
    forecasts, observations = _backtest_rows()
    sampled_hours: list[tuple[int, ...]] = []
    downloader_options: list[dict[str, object]] = []

    def fake_forecasts(*_args: object, **kwargs: object) -> tuple[object, object]:
        sampled_hours.append(cast(tuple[int, ...], kwargs["sample_hours"]))
        return forecasts, {"forecast": "a" * 64}

    def fake_downloader(_root: object, **kwargs: object) -> object:
        downloader_options.append(kwargs)
        return object()

    def fake_truth(
        *_args: object,
        **_kwargs: object,
    ) -> tuple[tuple[Observation, ...], dict[str, str]]:
        return observations, {"truth": "b" * 64}

    monkeypatch.setattr(
        runner,
        "download_previous_forecasts",
        fake_forecasts,
    )
    monkeypatch.setattr(
        runner,
        "CachedDownloader",
        fake_downloader,
    )
    monkeypatch.setattr(
        runner,
        "download_worldwide_truth",
        fake_truth,
    )
    output = tmp_path / "output"

    result = runner.main(
        [
            "--station-history",
            str(history),
            "--train-start",
            "2025-01-01",
            "--train-end",
            "2025-03-31",
            "--holdout-start",
            "2025-04-01",
            "--holdout-end",
            "2025-04-30",
            "--execute",
            "--forecast-cache",
            str(tmp_path / "forecast-cache"),
            "--truth-cache",
            str(tmp_path / "truth-cache"),
            "--output-dir",
            str(output),
            "--region",
            "EUROPE",
            "--pause-seconds",
            "0",
            "--bootstrap-repetitions",
            "20",
        ]
    )

    assert result == 0
    lines = capsys.readouterr().out.splitlines()
    assert cast(dict[str, JsonValue], json.loads(lines[-1]))["status"] == (
        "completed_diagnostic"
    )
    assert (output / "dataset-manifest.json").is_file()
    assert (output / "holdout-lock.json").is_file()
    assert (output / "report.json").is_file()
    assert (output / "worldwide-input-registry.json").is_file()
    manifest = cast(
        dict[str, JsonValue],
        json.loads((output / "dataset-manifest.json").read_text(encoding="utf-8")),
    )
    assert len(cast(list[JsonValue], manifest["stations"])) == 3
    assert sampled_hours == [tuple(range(24))]
    assert downloader_options == [
        {"retry_attempts": 5, "retry_delay_seconds": 30.0}
    ]


def _history_fixture() -> str:
    header = (
        '"USAF","WBAN","STATION NAME","CTRY","STATE","ICAO","LAT","LON",'
        '"ELEV(M)","BEGIN","END"'
    )
    rows = [header]
    for index, target in enumerate(WORLD_TARGETS):
        rows.append(
            f'"{index + 100000:06d}","{index:05d}","{target.target_id}","",'
            f'"","","{target.latitude}","{target.longitude}","100.0",'
            '"20000101","20251231"'
        )
    return "\n".join(rows) + "\n"


def _backtest_rows() -> tuple[tuple[ForecastValue, ...], tuple[Observation, ...]]:
    forecasts: list[ForecastValue] = []
    observations: list[Observation] = []
    for day_offset in range(120):
        valid_time = datetime(2025, 1, 1, 12, tzinfo=UTC) + timedelta(days=day_offset)
        for station_index, target in enumerate(WORLD_TARGETS):
            station_id = f"{station_index + 100000:06d}{station_index:05d}"
            truth = 10.0 + station_index * 0.2 + day_offset * 0.01
            observations.append(
                Observation(
                    "NOAA_ISD",
                    station_id,
                    valid_time,
                    target.latitude,
                    target.longitude,
                    100.0,
                    "temperature_2m",
                    truth,
                    "°C",
                    source_checksum="b" * 64,
                )
            )
            for model_index, model_id in enumerate((*WORLD_MODEL_IDS, "best_match")):
                forecasts.append(
                    ForecastValue(
                        model_id,
                        valid_time - timedelta(hours=24),
                        valid_time,
                        target.latitude,
                        target.longitude,
                        100.0,
                        "temperature",
                        truth + (model_index - 5) * 0.1,
                        "°C",
                        station_id,
                    )
                )
    return tuple(forecasts), tuple(observations)
