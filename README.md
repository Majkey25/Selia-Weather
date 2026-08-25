<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="96" alt="ALADIN weather icon">
</p>

<h1 align="center">ALADIN weather</h1>

<p align="center">A focused Android weather app for Czechia with ALADIN forecasts, ČHMÚ radar, and an adaptive widget.</p>

<p align="center">
  <a href="https://github.com/Majkey25/ALADIN-weather/actions/workflows/android.yml"><img alt="Android CI" src="https://github.com/Majkey25/ALADIN-weather/actions/workflows/android.yml/badge.svg"></a>
  <a href="https://github.com/Majkey25/ALADIN-weather/releases"><img alt="GitHub release" src="https://img.shields.io/github/v/release/Majkey25/ALADIN-weather?include_prereleases"></a>
  <img alt="Android 10 and later" src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white">
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-blue"></a>
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01-weather.png" width="240" alt="ALADIN weather forecast">
  &nbsp;&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03-radar.png" width="240" alt="ČHMÚ radar with controls">
  &nbsp;&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05-widget-editor.png" width="240" alt="Weather widget editor">
</p>

## What it does

- Shows current and apparent temperature, dew point, wet-bulb temperature, precipitation, cloud layers, visibility, pressure, wind, sun, and Moon details.
- Shows a horizontal 20-hour outlook, a 14-day forecast, and an hourly detail for each day. A complete day normally has 24 hours.
- Searches places in Czechia, stores favourites, can use your optional current location, and can save an exact named map point or coordinate.
- Includes ČHMÚ rain radar, satellite clouds, nowcast, and lightning. Rain and clouds are base layers. Lightning is an independent overlay on either layer.
- Keeps the last successful forecast for offline display.
- Includes a resizable launcher widget with per-widget colours, transparency, gradient or custom-image backgrounds, text scale, alignment, custom label, and selectable weather fields.
- Supports Metric and Imperial display units in the app and widgets.
- The free build can show a restrained adaptive banner and an occasional interstitial after a completed map visit. Google UMP controls consent. Either Premium purchase removes ads.

## Forecast data and accuracy

The first three days use Open-Meteo `chmi_aladin_seamless`. In Czechia, this provides hourly ALADIN CZ model data at 1 km resolution, updated every six hours. Open-Meteo continues the 14-day outlook with ECMWF IFS HRES data at 9 km. The later days carry more uncertainty.

- [Open-Meteo CHMI Forecast API](https://open-meteo.com/en/docs/chmi-api)
- [ČHMÚ open weather data](https://opendata.chmi.cz/meteorology/weather/)
- [ČHMÚ radar](https://produkty.chmi.cz/radar/)
- [ČHMÚ satellite data](https://opendata.chmi.cz/meteorology/weather/satellite/geo/vis-ir/)

ALADIN weather is not an official ČHMÚ or Open-Meteo app. Each provider remains identified in the app.

## Language and requirements

ALADIN weather supports Android 10 and later. It follows the Android system language by default. English is the fallback for unsupported system languages. You can select English, Czech, German, Spanish, or French in the app.

The public application ID is `com.majkeylab.weatheraladin`. A network connection is required for fresh forecasts, search, and radar. Build locally with JDK 17 and Android SDK 36.

## Widget

One stable widget layout adapts to compact, standard, wide, and tall sizes. Resize it horizontally or vertically. Each widget stores its own configuration, so two widgets can use different colours, fields, labels, and backgrounds.

For a custom background, the editor asks Android to grant access to the selected image. The widget keeps only the image URI. It decodes a bounded copy when it renders. If the URI becomes unavailable, the widget uses its configured colour background instead of failing.

## Support

The settings screen includes an optional [Buy Me a Coffee](https://www.buymeacoffee.com/majkey) link. It opens in Android's external browser. Support does not unlock features, give priority, or change the app.

## Ads and Premium

Google Play offers `remove_ads_lifetime` as a one-time purchase and `premium_monthly` as an auto-renewing subscription. Either option removes ads. The app checks active purchases whenever Play Billing connects or the app resumes. A pending or unknown entitlement never enables ads.

The debug build uses Google's published test ad IDs. Production ad IDs are supplied as Gradle properties `ALADIN_ADMOB_APP_ID`, `ALADIN_BANNER_AD_UNIT_ID`, and `ALADIN_INTERSTITIAL_AD_UNIT_ID`. A release built without all three stays ad-disabled.

## Build

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Privacy

The app has no developer account or separate analytics SDK. It keeps the selected place, favourites, widget settings, and forecast cache in internal app storage. Current location is optional. Its coordinates are sent to Open-Meteo over HTTPS to request the forecast. The free build uses Google Mobile Ads and UMP; purchases use Google Play Billing. Read the [privacy policy](https://majkey25.github.io/ALADIN-weather/).

## Status

`v0.2.0-beta.2` is the rebranded public identity. It uses `com.majkeylab.weatheraladin`, so it does not update installations of earlier beta packages. GitHub prereleases are for testing. Google Play uses a separate private upload key and Play App Signing.

## License

Source code is available under the [MIT License](LICENSE). Weather data and radar remain subject to their providers' terms.
