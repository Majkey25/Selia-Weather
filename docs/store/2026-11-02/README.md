# Store graphics | 2 November 2026

English and Czech artwork uses actual app captures from Huawei YAL-L21,
Android 10, running the 0.7.26 debug source. Production was not overwritten.
The graphic renderer adds headlines, backgrounds and generic phone frames.
It does not replace, relabel or retouch the app screen content.

## Capture provenance

* Forecast and native radar use public Prague coordinates, not a device fix.
* Weather and archive capture receipts are in
  `../captures/2026-11-02/{en-US,cs-CZ}/provenance.txt`.
* The archive contains 1,826 daily estimates from 1 November 2021 through
  31 October 2026, supplied by NASA POWER v2.10.1. These are grid estimates,
  not a complete local station record. The screen shows missing-data counts.
* Map receipts are in each capture directory's `map-provenance.txt`.
  The native forecast view shows real GDPS six-hour precipitation totals.
  Data Source: Environment and Climate Change Canada. Basemap: OpenFreeMap,
  © OpenMapTiles and © OpenStreetMap contributors. These credits also appear in exported
  artwork that contains the forecast map. Provider terms remain separate
  from the app's MIT licence.
* Widget editor captures use the existing, explicitly simulated 22-degree
  preview. They demonstrate appearance settings, not current weather.
* AI images show the app's confirmation dialog. No external AI recipient was
  opened and no weather file was shared during capture.
* System bars were cropped by the capture helper. The renderer scales the
  remaining screen into the phone frame and leaves its content intact.

## Exports

* Play phone screenshots: six 1080 × 1920 RGB PNG files per locale under
  `fastlane/metadata/android/{en-US,cs-CZ}/images/phoneScreenshots`.
* Suggested upload order: `01-weather.png`, `03-forecast-map.png`,
  `02-widget.png`, `04-hourly.png`, `05-history.png`, `06-ask-ai.png`.
* Play feature graphic: 1024 × 500 RGB PNG per locale.
* Social cover: 1600 × 1200 RGB PNG per locale in this directory.
* Contact sheets are review aids. Do not upload them as phone screenshots.
* `manifest.json` records final dimensions and SHA-256 checksums.

## Reproduce

Use the opt-in `StoreScreenshotsTest` with `captureStore=true` and
`storeLocale=en-US` or `cs-CZ`. Its native map capture now uses the Compose
timeline instead of the removed radar WebView. Run the two existing widget
editor capture methods separately, then copy the resulting captures into the
dated directory. All six capture checks passed on the physical phone.

```powershell
& 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot/bin/java.exe' '-Dfile.encoding=UTF-8' tools/RenderStoreScreenshots.java --check-copy
& 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot/bin/java.exe' '-Dfile.encoding=UTF-8' tools/RenderStoreScreenshots.java en-US
& 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot/bin/java.exe' '-Dfile.encoding=UTF-8' tools/RenderStoreScreenshots.java cs-CZ
```

The app icon is the project's existing artwork. Fonts use the installed
Segoe UI family. No competitor artwork or device-brand assets are included.
Provider references: [OpenFreeMap](https://openfreemap.org/quick_start/),
[ECCC](https://eccc-msc.github.io/open-data/licence/readme_en/).
