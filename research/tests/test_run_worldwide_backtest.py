from __future__ import annotations

import json
from pathlib import Path
from typing import cast

import pytest

from aladin_ensemble.registry import JsonValue
from aladin_ensemble.run_worldwide_backtest import main
from aladin_ensemble.worldwide import WORLD_MODEL_IDS, WORLD_TARGETS


def test_worldwide_preflight_is_network_free_and_bounded(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    history = tmp_path / "isd-history.csv"
    history.write_text(_history_fixture(), encoding="utf-8")

    result = main(
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
        "truth_requests": 1,
    }
    assert {path.name for path in tmp_path.iterdir()} == {"isd-history.csv"}


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
