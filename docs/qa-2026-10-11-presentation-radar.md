# Forecast presentation and radar verification, 0.5.1

Version code 38. Checked on Huawei YAL-L21, Android 10, on 11 October 2026. Only `com.majkeylab.weatheraladin.debug` and its test APK were installed. The Play-installed app and its data were not replaced or removed. Device windows were agreed with the active SeliaScan task and released after testing.

## Changes

- The gradient forecast has a compact location selector, a thinner 24-hour chart and qualitative confidence bars for each day and hour. The confidence dialog retains the underlying comparison evidence and its limitations.
- Daily display icons describe predominant daytime conditions. Whole-day precipitation totals and the original provider condition remain available; a separate storm label prevents the representative icon from hiding a storm. Incomplete days retain the provider icon.
- Drizzle uses cloud-and-dot artwork; rain uses cloud-and-streak artwork, including native widgets.
- Radar fills the canvas with floating controls and targets 48 hours of model precipitation. The displayed horizon comes from available frames. Europe uses hourly totals from a fresh main ICON-EU cycle; global ICON uses six-hour totals. These are model forecasts, not future radar observations.
- Clothing and umbrella advice form one sentence in English, Czech, German, Spanish and French. Scheduling and notification consent were not changed.

## Checks

- 436 JVM tests passed, with no failures, errors or skipped tests.
- `node --test app/src/test/js/radar.test.cjs app/src/test/js/location-picker.test.cjs`: 45 passed, none failed or skipped.
- Debug and release lint, debug/test/release APK builds and the signed release AAB build passed. The final asset/test rebuild passed after the last layout correction.
- Final Huawei regression run: 27 passed in 25.49 seconds. Coverage includes confidence disclosure, appearance persistence, narrow/large-font layouts, day gestures, bottom navigation, widget resizing and localized notification text.
- Final real-WebView radar run at 400 dp height and 200% font scale passed in 5.364 seconds. It checked controls against actual native viewport geometry, fullscreen exit/re-entry, observed frames, Prague ICON-EU and Tokyo global ICON. Both regions loaded their final +48-hour forecast tiles without a tile-error status.
- Final public-Prague fullscreen capture passed in 4.852 seconds. Visual inspection confirmed that playback, time, slider and start/end range are onscreen. Captures use public coordinates, not the user's location.
- Claude Opus 5.5 with high effort provided design guidance and focused read-only review. Suggestions were checked against the existing requirements; no competitor assets or proprietary code were copied.

## Reproduced failures

The Android fullscreen WebView initially measured its floating panel at top −20 px and bottom −8 px. Its absolutely positioned panel used a zero-height containing block. Giving the body a positioned, explicitly measured viewport fixed the native regression. Actual viewport height now determines compact mode; measured header, mode controls and attribution heights bound the panel. Resize observations keep those bounds current when labels wrap.

Large-font navigation initially clipped the selected label. Its line height and large-font width were corrected, and the native regression passed. One earlier public-map capture timed out waiting for the provider; an isolated retry and the final capture passed. Provider availability is therefore not claimed to be unconditional.

## Limits and delivery

Confidence measures model agreement and input coverage, not a calibrated probability of accuracy. Forecast numbers and learned-weight validation gates were not changed. These checks do not establish improved meteorological accuracy or cover every Android device.

Google Play upload was attempted through the existing Brave browser connection. Tab navigation repeatedly failed with `Debugger unattached` or a navigation timeout. No Play upload, save or submission occurred during these attempts; the signed version-38 AAB is ready for the existing closed track when the browser connection is restored.

References: [Gradient Weather](https://play.google.com/store/apps/details?id=com.subtlesignals.gradientweather), [Open-Meteo weather-code semantics](https://open-meteo.com/en/docs), [DWD WMS usage](https://www.dwd.de/DE/leistungen/geodienste/help/nutzung_geodienste.html?lsbId=621762), [DWD ICON model database](https://www.dwd.de/DWD/forschung/nwv/icon_database_main.pdf).
