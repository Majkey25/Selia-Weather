# Native radar verification | 2 November 2026

The radar now uses MapLibre Native 13.7.1 with OpenFreeMap vector styles.
The location picker remains separate. The former radar WebView, JavaScript
renderer and their obsolete tests were removed after native device checks.

## Data contract

* Forecast: ECCC GDPS, `GDPS_15km_Precip-Accum6h`, six-hour totals in mm.
  Only advertised timestamps from one explicit model run enter the timeline.
  Live metadata and real first/last images supported a horizon through
  12 November 00:00 UTC from the 2 November 00:00 UTC run.
* GeoMet requires whole-second timestamps. A real request with `.000Z`
  returned a date-format error; the corrected request returned PNG data.
  DWD retains its required millisecond spelling. Regression tests cover both.
* DWD ICON-EU/ICON supplies bounded fallback on GDPS failure. No model images
  are extrapolated or relabelled as observed future radar.
* Observed: available RainViewer past frames, with its coverage mask.
  A shaded area is missing coverage, not evidence of dry weather.
* Metadata has a 20-second total deadline, at most four image probes and
  three cached source snapshots. The renderer retains six forecast bitmaps
  and prepares one next frame. Cancelled selections cancel obsolete prefetch.
* GeoMet interpolation smooths the rendered grid. It adds no spatial detail
  or forecast accuracy. Point forecasts and confidence calculations are unchanged.

Primary sources:
[GDPS](https://eccc-msc.github.io/open-data/msc-data/nwp_gdps/readme_gdps-geomet_en/),
[GeoMet time handling](https://eccc-msc.github.io/open-data/msc-geomet/wms_en/),
[ECCC licence](https://eccc-msc.github.io/open-data/licence/readme_en/),
[RainViewer](https://www.rainviewer.com/api/weather-maps-api.html),
[OpenFreeMap](https://openfreemap.org/quick_start/).

## Physical checks

Huawei YAL-L21, Android 10, with separate debug and minified QA packages.
The installed production package and device accounts were untouched.

* 34 device regression checks passed: native forecast/observed playback,
  final forecast frame, selected-mode re-click, light/dark recreation,
  double text scale in a compact viewport, map panning, fullscreen/back,
  rapid navigation, weather layout and keyboard reachability.
* A separate native performance probe passed. The first loaded forecast
  frame took 5,870 ms in one network run. Playback advanced from 2 November
  19:00 CET to 4 November 01:00 CET during the probe.
* MapLibre's FPS callback recorded 200 samples with median 59.99.
  These are instantaneous renderer samples, not guaranteed presented FPS.
  Static holds and network waits are intentionally not continuous animation.
* The minified QA build rendered the real GDPS map and advanced through
  future frames. One playback memory snapshot reported 199,720 KiB PSS,
  including 49,924 KiB graphics, and zero WebViews.
* These are single-device measurements. No equivalent old-build baseline
  was measured, so no percentage speed or memory improvement is claimed.

## Build checks and limits

* 567 JVM tests passed, including 22 data-contract tests and two date-line
  geometry tests. Three location-picker JavaScript tests passed.
* Debug/release build and lint passed without errors. Lint still reports
  warnings; this is not a claim of a warning-free project.
* R8 shrinking, optimization and obfuscation are enabled in the actual AAB.
* Both 64-bit MapLibre ELF binaries have `0x4000` load alignment. The signed
  release APK passed `zipalign -c -P 16 -v 4`. No 16-KiB-page device was available.
* Open-Meteo returned HTTP 503, `The service is overloaded`, during a fresh
  QA launch and an independent simple request. The native map still worked.
  This release does not resolve that external forecast-service failure.
* Gradient Weather installation through ADB was rejected by automatic
  approval review. Design references were its public site and screenshots;
  no proprietary code or assets were copied.
