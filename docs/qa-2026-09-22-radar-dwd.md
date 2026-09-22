# Forecast map source verification, 22 September 2026

The future layer now uses DWD's rendered model grids. It replaces the 49-point Open-Meteo sample grid and its viewport refresh restriction. RainViewer remains the observed-radar provider. Model forecasts are not radar observations.

## Verified services

| Product | WMS layer | Amount represented | Coverage |
| --- | --- | --- | --- |
| ICON-EU | `dwd:Icon-eu_reg00625_fd_sl_TOTPREC01H` | Preceding hour, mm | 29.46875–70.53125° N, 23.53125° W–62.53125° E |
| ICON | `dwd:Icon_reg025_fd_sl_TOTPREC06H` | Preceding six hours, mm | Global |

These names, styles, bounds, run times and valid times were checked against the live [ICON-EU capabilities](https://maps.dwd.de/geoserver/dwd/Icon-eu_reg00625_fd_sl_TOTPREC01H/wms?service=WMS&version=1.3.0&request=GetCapabilities) and [ICON capabilities](https://maps.dwd.de/geoserver/dwd/Icon_reg025_fd_sl_TOTPREC06H/wms?service=WMS&version=1.3.0&request=GetCapabilities). Both advertise EPSG:3857 and `Fees=none`. HTTPS capabilities, map images and legends return `Access-Control-Allow-Origin: *`; no key or account was used.

At 21:00 UTC, the current run was 2026-09-22 18:00 UTC. ICON-EU advertised hourly times through September 25 18:00 UTC. Global ICON advertised times through September 30 00:00 UTC. Its combined time range said `PT3H`, but 09:00 UTC for the selected run returned `InvalidDimensionValue`. Actual 00:00, 06:00 and 12:00 UTC requests returned PNGs. The client therefore selects six-hour UTC boundaries for that product and labels the six-hour accumulation explicitly. It does not interpolate hourly images from these totals.

Live HTTP checks returned PNGs for ICON-EU in EPSG:4326 and EPSG:3857, including the next morning at 06:00, 09:00 and 10:00 UTC. A 512×512 Europe request at 09:00 returned 10,748 bytes. Global 256×128 requests at 00:00, 06:00 and 12:00 returned 8,765, 8,687 and 8,901 bytes. Both `GetLegendGraphic` requests also returned PNGs, 5,093 bytes for ICON-EU and 4,772 for ICON. One earlier whole-world request timed out; subsequent requests succeeded. HTTP status alone is insufficient because WMS errors can return HTTP 200 with XML.

## Licence and operation

[DWD copyright terms](https://www.dwd.de/copyright) permit reuse of public geodata and services under CC BY 4.0 with source attribution. The map shows Deutscher Wetterdienst and the licence link; details show the selected model. [Attribution instructions](https://www.dwd.de/DE/service/rechtliche_hinweise/vorlagen_quellenangabe.html) allow a text source credit directly beside the information. [Service guidance](https://www.dwd.de/DE/leistungen/geodienste/help/nutzung_geodienste.html?lsbId=621762) documents public WMS use and possible outages. No numeric public request limit was found in the checked guidance; that is not a guarantee of unlimited capacity.

The client holds only two metadata records, expires them after ten minutes, bounds each download at 128 KiB, uses 15-second metadata/tile deadlines, and backs off after HTTP 429. It requires a run no older than twelve hours and a remaining forecast horizon of at least twelve hours. Panning switches between regional and global coverage using the advertised bounds. Tile buffers remain bounded and only the next animation frame is preloaded. Failed requests show an unavailable state, not a dry-weather claim.

Expiry uses the earliest of metadata age, model-run age and remaining horizon. Visible expiry refreshes metadata once automatically. Hidden expiry clears stale frames and defers refresh until the page becomes visible. A failed refresh stops automatic requests, including layout-triggered map movement; explicit refresh can recover. Tile errors stay attached to that layer until it is replaced, because Leaflet may retain failed tiles across later loading events.

Windy's embed was rejected: [its current terms](https://account.windy.com/agreements/windy-terms-of-use), section 8.6.3, prohibit embeddable widgets in weather apps. No private tiles or proprietary assets are used.

## Repeatable checks

Run existing JS checks from the repository root:

```text
node --test app/src/test/js/radar.test.cjs app/src/test/js/location-picker.test.cjs
```

Result: 35 passed. Coverage includes observed playback, transition failures, automatic visible expiry refresh, deferred hidden refresh, model-run age cutoff, failed refresh recovery, cancellation, provider backoff, metadata limits, regional/global panning, cross-midnight forecast intervals and retained or hung tile failures. Node uses WMS DOM fixtures; native XML parsing and visual output require the actual browser/WebView check.

Fetch fresh metadata before repeating image checks, because old model runs are removed:

```text
curl.exe -sS --max-time 15 "https://maps.dwd.de/geoserver/dwd/Icon-eu_reg00625_fd_sl_TOTPREC01H/wms?service=WMS&version=1.3.0&request=GetCapabilities"
curl.exe -sS --max-time 15 "https://maps.dwd.de/geoserver/dwd/Icon_reg025_fd_sl_TOTPREC06H/wms?service=WMS&version=1.3.0&request=GetCapabilities"
```

Use the advertised reference run and a future valid time in a `GetMap` request. For global ICON, use 00/06/12/18 UTC. Check `Content-Type: image/png`, not only HTTP 200. Then load the app's local radar page and verify the real XML parser, timeline, rain areas, dry areas, legend, viewport panning across Europe/global bounds, switching back to observations, and unavailable states. Browser and physical-device results are separate from the HTTP and Node checks above.
