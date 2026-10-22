from collections.abc import Iterator
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from threading import Thread

import pytest

from aladin_ensemble.sources import chmi_download, probe
from aladin_ensemble.sources.chmi_download import ChmiMonthlyDownloader, ChmiMonthlyRequest
from aladin_ensemble.sources.chmi_station import Station


@pytest.fixture
def provider() -> Iterator[str]:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            self.send_response(404 if self.path.startswith("/error") else 200)
            self.end_headers()
            self.wfile.write(b'{"ok":true}' if self.path.startswith("/valid") else b"x" * 65)

        def log_message(self, format: str, *args: object) -> None:
            pass

    with HTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}"
        finally:
            server.shutdown()
            thread.join(timeout=5)


def test_probe_rejects_oversized_http_body(provider: str, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(probe, "MAX_RESPONSE_BYTES", 64)
    with pytest.raises(probe.OperationalProbeError, match="exceeds size limit"):
        probe.retrying_fetch_json(f"{provider}/large", attempts=1)
    assert probe.retrying_fetch_json(f"{provider}/valid", attempts=1) == {"ok": True}


@pytest.mark.parametrize("path", ["large", "error"])
def test_monthly_download_bounds_success_and_error_bodies(
    provider: str, monkeypatch: pytest.MonkeyPatch, path: str, tmp_path: Path,
) -> None:
    monkeypatch.setattr(chmi_download, "MAX_RESPONSE_BYTES", 64)
    monkeypatch.setattr(chmi_download, "CHMI_CLIMATE_ROOT", f"{provider}/{path}")
    downloader = ChmiMonthlyDownloader(
        tmp_path, now=lambda: datetime(2026, 8, 26, tzinfo=UTC), retry_attempts=1,
    )
    request = ChmiMonthlyRequest("0-20000-0-11502", 2026, 4, "10min")
    with pytest.raises(ValueError, match="exceeds size limit"):
        downloader.download(request)
    assert list(tmp_path.iterdir()) == []
    monkeypatch.setattr(chmi_download, "CHMI_CLIMATE_ROOT", f"{provider}/valid")
    assert downloader.download(request).path.read_bytes() == b'{"ok":true}'


@pytest.mark.parametrize("value", [
    "../outside", "a/../../outside", r"a\outside", "C:outside", "a?query", "a#fragment",
    "a%2foutside", "CON", "NUL.txt", "trailing.", "x" * 129,
])
def test_station_ids_cannot_escape_cache_components(value: str) -> None:
    with pytest.raises(ValueError, match="safe path component"):
        Station(value, "Test", 50.0, 14.0, 200.0)
    with pytest.raises(ValueError, match="safe path component"):
        ChmiMonthlyRequest(value, 2026, 4, "10min")


@pytest.mark.parametrize("value", ["0-20000-0-11502", "72505394728", "GMI0000EDDF"])
def test_supported_station_id_formats_remain_valid(value: str) -> None:
    assert Station(value, "Test", 50.0, 14.0, 200.0).wigos_id == value
