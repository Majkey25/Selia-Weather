<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="96" alt="Selia Vetra icon">
</p>

<h1 align="center">Selia Vetra</h1>

<p align="center">A focused worldwide Android weather app with regional model routing, observed radar, and an adaptive widget.</p>

<p align="center">
  <a href="https://github.com/Majkey25/Selia-Weather/actions/workflows/android.yml"><img alt="Android CI" src="https://github.com/Majkey25/Selia-Weather/actions/workflows/android.yml/badge.svg"></a>
  <a href="https://github.com/Majkey25/Selia-Weather/releases"><img alt="GitHub release" src="https://img.shields.io/github/v/release/Majkey25/Selia-Weather?include_prereleases"></a>
  <img alt="Android 10 and later" src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white">
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-blue"></a>
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01-weather.png" width="240" alt="Selia Vetra forecast">
  &nbsp;&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03-radar.png" width="240" alt="Observed precipitation radar">
  &nbsp;&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05-widget-editor.png" width="240" alt="Weather widget editor">
</p>

## What it does

- Shows current and apparent temperature, dew point, wet-bulb temperature, precipitation, cloud layers, visibility, pressure, wind, sun, and Moon details.
- Adds UV, freezing-level, boundary-layer, atmospheric-water, instability, showers, and ground details.
- Shows worldwide observed precipitation through RainViewer for the available past two hours, including a radar-coverage mask. The separate 24-hour forecast samples a 5 by 5 field around the selected location and never labels model output as observed radar.
- Shows a horizontal 24-hour outlook, a 14-day forecast, and an hourly detail for each day. A complete day normally has 24 hours.
- Loads a bounded five-year NASA POWER archive on demand, calculates rainfall and climate summaries locally, shows every daily row, and exports one provenance-labelled CSV through Android's share sheet for analysis in ChatGPT or another app.
- Searches places worldwide, stores favourites, can use your optional current location, and can save an exact named point on an interactive world map or by coordinate.
- In Czechia, corrects current temperature, humidity, wind, precipitation, and sky condition from up to three nearby ČHMÚ automatic stations when their observations are fresh.
- Outside Czechia, uses nearby fresh worldwide METAR reports to correct available temperature, humidity, dew point, pressure, visibility, cloud-cover, and wind fields. A METAR report never invents a precipitation amount.
- Keeps the last successful forecast for offline display.
- Includes a resizable launcher widget with per-widget colours, transparency, gradient or custom-image backgrounds, text scale, alignment, custom label, and selectable weather fields.
- Supports Metric and Imperial display units in the app and widgets.

## Forecast data and accuracy

The base forecast uses Open-Meteo Best Match worldwide. The app also requests verified provider-family series and calculates a robust median on the device when at least three values are available. Provider seamless series use local high-resolution grids inside their domains and global output elsewhere. Czech locations additionally request CHMI ALADIN. Suspended providers are excluded.

The runtime accepts checksum-verified regional weights only after their exact provider-family inputs beat the training-selected fallback on a locked holdout. No worldwide segment currently passes that contract, so beta.11 uses the diagnostic median or Best Match. Weather details show the region, mode, contributors, fallback, and any accepted artifact evidence after cache reload.

The evidence and limits are recorded in [Global model routing](docs/research/global-model-routing.md) and [Worldwide ensemble validation](docs/research/worldwide-ensemble-validation.md).

- [Open-Meteo Forecast API](https://open-meteo.com/en/docs)
- [NASA POWER Daily API](https://power.larc.nasa.gov/docs/services/api/temporal/daily/)
- [NASA POWER referencing guide](https://power.larc.nasa.gov/docs/referencing/)
- [AviationWeather worldwide METAR API](https://aviationweather.gov/data/api/)
- [NOAA ISD](https://www.ncei.noaa.gov/products/land-based-station/integrated-surface-database)
- [NASA GPM IMERG](https://gpm.nasa.gov/data/imerg)
- [RainViewer Weather Maps API](https://www.rainviewer.com/api/weather-maps-api.html)
- [ČHMÚ current station data](https://opendata.chmi.cz/meteorology/climate/now/)
- [ČHMÚ open weather data](https://opendata.chmi.cz/meteorology/weather/)

The historical data was obtained from the NASA Langley Research Center POWER project funded through the NASA Earth Science Division. CSV exports include the POWER Daily API version and access time. Selia Vetra is not an official NASA, ČHMÚ, or Open-Meteo app.

## Language and requirements

Selia Vetra supports Android 10 and later. It follows the Android system language by default. English is the fallback for unsupported system languages. You can select English, Czech, German, Spanish, or French in the app.

The public application ID is `com.majkeylab.weatheraladin`. A network connection is required for fresh forecasts, search, and radar. Build locally with JDK 17 and Android SDK 36.

## Widget

One stable widget layout adapts to compact, standard, wide, and tall sizes. Resize it horizontally or vertically. Each widget stores its own configuration, so two widgets can use different colours, fields, labels, and backgrounds.

For a custom background, the editor asks Android to grant access to the selected image. The widget keeps only the image URI. It decodes a bounded copy when it renders. If the URI becomes unavailable, the widget uses its configured colour background instead of failing.

## Support

The settings screen includes an optional [Buy Me a Coffee](https://www.buymeacoffee.com/majkey) link. It opens in Android's external browser. Support does not unlock features, give priority, or change the app.

## Monetisation status

Release builds do not contain or initialise Ads, UMP, Play Billing, Premium, or `AD_ID`. Debug-only QA builds keep optional test integrations outside the release dependency graph. Monetisation can return only after the app uses a commercially licensed forecast path and the public disclosures are updated.

## Build

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Privacy

The app has no developer account or separate analytics SDK. It keeps the selected place, favourites, widget settings, forecast cache, and a bounded history cache in internal app storage. Current location is optional. Forecast coordinates are sent to Open-Meteo. Outside Czechia, a bounded coordinate box is sent to AviationWeather to find nearby METAR reports. The observed map loads visible OpenStreetMap and RainViewer tiles. After you select **Load archive**, the selected coordinates are sent to NASA POWER. A history CSV leaves the app only after you select **Ask ChatGPT** and choose a recipient in Android's share sheet. In Czechia, the app selects nearby ČHMÚ station IDs locally and requests their public observation files without sending the selected coordinates to ČHMÚ. Read the [privacy policy](https://majkey25.github.io/Selia-Weather/).

## Status

The app uses the product identity Selia Vetra and the short launcher label Vetra. It keeps the public package `com.majkeylab.weatheraladin`, so existing Play installations update normally. GitHub prereleases are for testing. Worldwide calibration remains diagnostic until the seamless-model holdout passes. Google Play uses a separate private upload key and Play App Signing.

## License

Source code is available under the [MIT License](LICENSE). Weather data and radar remain subject to their providers' terms.
