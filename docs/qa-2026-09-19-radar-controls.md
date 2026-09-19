# Radar and Ask AI, version 0.2.1

## Changes

- Load radar assets through `WebViewAssetLoader` on an HTTPS origin. File access remains disabled. The previous `file://` origin was incompatible with the fetch-based radar and forecast feeds on Android WebView. See [Android local-content guidance](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content).
- Separate mode selection, map and timeline. Use vector playback controls, labeled time endpoints, the selected location's timezone and a location pin. Fullscreen keeps the same map.
- Observe map-container size changes, including wrapped status text and forecast summaries. This prevents Leaflet from retaining stale map dimensions.
- Distinguish missing forecast amounts from amounts below 0.1 mm at the sampled points. Sampling does not increase the underlying model resolution.
- Give Ask AI an explicit AI speech-bubble icon, its own heading, archive-loading text and a clear app-selection action. Move source explanations into a disclosure. Sharing still requires confirmation.

## Verification

- `:app:testDebugUnitTest`: 368 tests passed.
- `:app:lintDebug` and `:app:lintRelease`: passed with existing warnings.
- Debug APK, instrumentation APK, signed release APK and AAB built.
- `node --test app/src/test/js/radar.test.cjs app/src/test/js/location-picker.test.cjs`: 29 tests passed. New timeline, missing-data, timezone and map-resize regressions failed before their fixes.
- Huawei Android 10, `BQLDU19927002646`: all 11 instrumented tests passed on the final debug build. The new smoke test uses the real WebView and live RainViewer/Open-Meteo responses. It verifies the HTTPS asset origin, disabled file access, observed frames and forecast frames.
- Manual Huawei checks: radar rendering, fullscreen entry, system Back returning to embedded radar, Ask AI opening a five-year archive with 1,826 daily records, confirmation dialog and Android share sheet offering ChatGPT. No question was sent to an AI provider.
- Desktop Chromium: live observed/forecast loading, playback, keyboard seeking to the final next-day frame, details toggle and 320px layout. Leaflet/client heights matched at 341px observed, 315px forecast and 179px with details expanded. No horizontal overflow in these states.
- Phone test window released at 15:04 CEST. Removed only the instrumentation package and two temporary QA files. Production app and data were untouched.

## Limits

The future layer remains a sampled Open-Meteo model forecast, not observed radar or a validated new prediction model. This release does not change forecast blending or claim improved meteorological accuracy. AI recipient upload and answers were not tested. The signed release artifacts were built and signature-checked; physical execution used the corresponding debug build.
