# Implementation reference

This document records the shipped structure of Selia Weather. It is not a release checklist.

## App foundation

The Android app has one module. The public application ID is `com.majkeylab.weatheraladin`. The app supports Android 10 and later, uses Kotlin, Compose Material 3, Android framework widgets, `HttpURLConnection`, `org.json`, and JUnit 4.

## Forecast and places

`WeatherRepository` owns HTTP, JSON validation, cached weather, and typed weather data. It requests 14 forecast days, seven past days, and the selected location's timezone. Place search and exact coordinates work worldwide. The consensus path combines explicit provider series on-device only when at least three values exist and falls back to Best Match. Current conditions combine fresh nearby ČHMÚ and METAR observations in Czechia and use METAR elsewhere. Compose resolves localised text at the UI boundary.

## Radar

`radar.html` is a local, trusted WebView asset. It allows the existing ČHMÚ HTTPS sources only. Rain and clouds are exclusive base layers. Lightning is a separate overlay whose failures hide only the lightning image. The URL builders, nowcast timing, and animation timing keep their ČHMÚ sources.

## Widget

`WeatherWidgetProvider` uses one adaptive `RemoteViews` hierarchy. Size changes show or hide fields instead of replacing a widget layout. `WidgetSettings` stores validated per-widget preferences. `WidgetBackground` bounds a selected image to 512 by 256 pixels before it reaches `RemoteViews`. `WidgetEditorScreen` shares settings interpretation with the provider.

## Language and privacy

The app follows the Android system language by default. English is the resource fallback. The settings screen offers English, Czech, German, Spanish, and French. The widget uses the same locale setting.

The app stores settings and cached weather locally. A custom widget image remains at its selected Android content URI. See the [privacy policy](https://majkey25.github.io/Selia-Weather/) for network and permission details.
