# Selia Weather store artwork

Warm white posters, a black phone frame, one headline and one explanatory line.
The compositor preserves Android capture proportions and does not draw app
controls, weather values or archive totals.

## Order

| Order | File | Screen | Source |
| --- | --- | --- | --- |
| 1 | `01-weather.png` | Current weather and forecast | Actual app, public Prague forecast |
| 2 | `02-widget.png` | Widget customization | Actual widget editor, isolated demo weather |
| 3 | `03-forecast-map.png` | Unified precipitation timeline | Actual WebView, live model forecast layer |
| 4 | `04-hourly.png` | Expanded hourly forecast | Actual day detail, public Prague forecast |
| 5 | `05-history.png` | Archive and coverage | Actual history screen, public Prague NASA POWER data |
| 6 | `06-ask-ai.png` | Archive sharing confirmation | Actual consent dialog; no external AI conversation |

Phone posters: 1080 × 1920 PNG. Feature graphic: 1024 × 500 PNG.
Contact sheets: 1080 × 1280 PNG. Source captures: 1080 × 2232 PNG after
Android system-bar cropping. App pixels are unchanged.

Raw captures: `docs/store/captures/2026-10-11/<locale>/`.
Upload candidates: `fastlane/metadata/android/<locale>/images/`.
This manifest is not evidence of Play Console upload or publication.

## Provenance

The approved Huawei test phone captures the actual Android app. The opt-in
`StoreScreenshotsTest` uses public Prague coordinates 50.0755, 14.4378 and separate
preferences/cache. No GPS, personal favourites, user photos or AI conversations
are included. The AI consent dialog is not accepted during store capture.

The five-year archive contains 1,826 daily records, 2021-10-10 through 2026-10-09,
from NASA POWER v2.10.0. These are grid estimates, not measurements on a field.
The map capture selects a real available model interval. Future precipitation
is a model forecast, not an observation or future radar measurement. Widget
editor weather is isolated demo data and is not advertised as live weather.

Each capture set includes `provenance.txt` with provider fetch times and archive
coverage. Other locales inherit default English artwork.

## Reproduction

Build and install the debug and Android test APKs on the approved device, then
explicitly opt into capture:

```text
adb -s <approved-serial> shell am instrument -w -e class cz.majkey.pocasicesko.ui.StoreScreenshotsTest -e captureStore true -e storeLocale en-US com.majkeylab.weatheraladin.debug.test/androidx.test.runner.AndroidJUnitRunner
java "-Dfile.encoding=UTF-8" tools/RenderStoreScreenshots.java --check-copy
java "-Dfile.encoding=UTF-8" tools/RenderStoreScreenshots.java en-US
java "-Dfile.encoding=UTF-8" tools/RenderStoreScreenshots.java cs-CZ
```

Use `storeLocale=cs-CZ` for Czech capture. The test changes only its activity
resources and restores them afterward; it does not change system or app language.

## Checks

English and Czech physical capture passed both tests on 2026-10-11. The final
contact sheets were visually checked; hourly touch feedback is absent. Widget editor
captures were refreshed by the passing native widget suite. The renderer
passed headline/subtitle bounds checks for both languages and verified output
PNG dimensions. Listing upload and publication are tracked separately in release evidence.
