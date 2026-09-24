# Selia Weather store artwork

Poster design follows the supplied SeliaScan reference: warm white background,
small app icon and brand, one bold headline, one explanatory line, black phone frame.
Android captures keep their original aspect ratio. No app controls or weather values
are drawn by the poster compositor.

## Output order

| Order | File | Screen | Capture source |
| --- | --- | --- | --- |
| 1 | `01-weather.png` | Current weather and forecast | Actual `WeatherApp`, public Prague forecast |
| 2 | `02-widget.png` | Widget customization | Actual `WidgetEditorScreen`, isolated demo weather |
| 3 | `03-forecast-map.png` | Future precipitation map | Actual Android WebView, live DWD model layer |
| 4 | `04-hourly.png` | Expanded hourly forecast | Actual `DayDetailSheet`, public Prague forecast |
| 5 | `05-history.png` | Archive data and coverage | Actual `WeatherDetailSheet`, public Prague NASA POWER archive |
| 6 | `06-ask-ai.png` | CSV sharing confirmation | Actual in-app confirmation, no external AI recipient opened |

Phone posters: 1080 × 1920 PNG. Feature graphic: 1024 × 500 PNG.
Contact sheets: 1080 × 1280 PNG, three columns by two rows.
Both `en-US` and `cs-CZ` contain six posters and one feature graphic.
All source screen captures are 1080 × 2232 PNG after system-bar cropping.

Raw captures live in `docs/store/captures/2026-09-24/<locale>/`.
Published-file candidates live in `fastlane/metadata/android/<locale>/images/`.
This manifest does not confirm Play Console upload or publication.

## Provenance and reproduction

The opt-in `StoreScreenshotsTest` uses public Prague coordinates 50.0755, 14.4378.
It calls the production `WeatherRepository` and uses separate capture preferences
and cache. The archive values are NASA POWER grid estimates, not local observations.
The captured map is a model forecast, not observed future radar. Widget captures
come from `WidgetRenderTest` and use isolated demo data.

Final captures used the approved Huawei test phone on 2026-09-24. Public NASA POWER
v2.10.0 supplied 1,826 daily records, 2021-09-23 through 2026-09-22. The forecast
map shows the real DWD 16:00–17:00 interval, three steps after the first available
frame. App pixels were not retouched; only Android system bars were cropped.

`provenance.txt` beside each capture set records source fetch times, NASA POWER
version and archive range. No GPS, personal favourites, selected photos or AI
conversations belong in these assets. The AI confirmation is never accepted.

Run the inspected Gradle tasks before installing the debug and test APKs on the
approved device. Explicitly opt in to capture, since routine device test runs skip it:

```text
adb -s <approved-serial> shell am instrument -w -e class cz.majkey.pocasicesko.ui.StoreScreenshotsTest -e captureStore true -e storeLocale en-US com.majkeylab.weatheraladin.debug.test/androidx.test.runner.AndroidJUnitRunner
java "-Dfile.encoding=UTF-8" tools/RenderStoreScreenshots.java --check-copy
java "-Dfile.encoding=UTF-8" tools/RenderStoreScreenshots.java en-US
java "-Dfile.encoding=UTF-8" tools/RenderStoreScreenshots.java cs-CZ
```

Use `storeLocale=cs-CZ` for the Czech source capture run. The Java renderer needs
only Java 17 and the existing store icon. Other locales retain default English
image inheritance by keeping their image directories absent.
The capture test temporarily localizes its activity resources for Android dialogs
and restores them after each test. It does not change system or app language settings.

## Verification status

Renderer headline/subtitle bounds check passed for all twelve English/Czech
posters. Debug app and Android test APK build passed on 2026-09-24.
Final physical-device capture runs passed 2/2 tests in English and 2/2 in Czech.
The renderer re-read every output PNG and verified dimensions. Both complete
contact sheets, both feature graphics, widget previews, the expanded hourly
detail and the settled AI confirmation were visually checked. No provider
fallback data or fabricated archive totals were used. No AI app was opened.

Four superseded default screenshots (`02-day-detail.png`, `03-radar.png`,
`04-clouds.png`, `05-widget-editor.png`) were removed from the upload directory.
Their earlier versions remain recoverable from Git history. Each locale's
upload directory now contains only the six ordered posters listed above.
