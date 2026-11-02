# Native radar verification | 2 November 2026

The radar now uses MapLibre Native OpenGL 13.7.1 with OpenFreeMap vector styles.
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
* Transient connection failures and HTTP 500/502/503/504 get one retry.
  Both attempts share a 15-second deadline. Cancellation, oversized responses,
  TLS failures, HTTP 404 and rate limits are not retried.
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
* A separate OpenGL performance probe passed. The first loaded forecast
  frame took 4,605 ms in the final network run. Playback advanced from 2 November
  19:00 GMT+01:00 to 3 November 19:00 GMT+01:00 during the probe.
* MapLibre's FPS callback recorded a median of 60.18 in that run.
  These are instantaneous renderer samples, not guaranteed presented FPS.
  Static holds and network waits are intentionally not continuous animation.
* The minified OpenGL QA build rendered the real GDPS map and advanced through
  future frames. One playback memory snapshot reported 154,181 KiB PSS,
  including 49,652 KiB graphics, and zero WebViews.
* One initial performance run and a later minified playback encountered a
  real GDPS socket timeout. These failures prompted the bounded retry above;
  they are not evidence that the external service is always available.
* These are single-device measurements. No equivalent old-build baseline
  was measured, so no percentage speed or memory improvement is claimed.
* Final focused runs passed after the retry and layout changes. The compact
  test scrolls both endpoint labels into view and verifies that their bounds
  do not overlap at double text scale. The selected mode uses an explicit
  contrasting fill. The minified QA run preceded only that fill adjustment.

## Build checks and limits

* 570 JVM tests passed, including 25 data-contract tests and two date-line
  geometry tests. Three location-picker JavaScript tests passed.
* Debug/release build and lint passed without errors. Lint still reports
  warnings; this is not a claim of a warning-free project.
* R8 shrinking, optimization and obfuscation are enabled in the actual AAB.
* Both 64-bit MapLibre ELF binaries have `0x4000` load alignment. The signed
  release APK passed `zipalign -c -P 16 -v 4`. No 16-KiB-page device was available.
* A draft using the default Vulkan SDK excluded 1,471 phones and 579 tablets
  in Play Console. That build was not published. Build 71 uses the OpenGL
  artifact and its manifest does not require Vulkan.
* Open-Meteo returned HTTP 503, `The service is overloaded`, during a fresh
  QA launch and an independent simple request. The native map still worked.
  This release does not resolve that external forecast-service failure.
* Gradient Weather installation through ADB was rejected by automatic
  approval review. Design references were its public site and screenshots;
  no proprietary code or assets were copied.
