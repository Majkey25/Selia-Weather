# Changelog

## Unreleased

## [0.2.0-beta.12] - 2026-09-01

- Removed precipitation target and bullseye visuals from daily and hourly weather UI.
- Kept feels-like temperature visible in every hourly row and daily summary.
- Expanded each opened hour with a plain-language rain explanation and every available temperature, precipitation, wind, cloud, atmosphere, visibility, and ground metric.
- Added an opt-in 07:00 local morning briefing with clothing, umbrella, and sun-protection advice.
- Fixed a zero-height Leaflet map on older Android WebView versions while retaining live OSM and RainViewer tiles.
- Kept release Ads, UMP, Billing, Premium, and `AD_ID` payloads disabled and absent.

## [0.2.0-beta.11] - 2026-09-01

- Fixed current-condition fusion so each metric uses the nearest stations that actually report it.
- Combined fresh ČHMÚ and METAR observations in Czechia while retaining METAR coverage worldwide.
- Expanded the worldwide research cohort to three stations per region and added separate locked regional evaluations. No new calibration weights are published while the source corpus remains incomplete.
- Kept release Ads, UMP, Billing, Premium, and `AD_ID` payloads disabled and absent.

## [0.2.0-beta.10] - 2026-08-31

- Replaced the Czech-only radar page with a worldwide RainViewer map centred on the selected coordinate, a coverage mask, and the available two-hour observed timeline.
- Kept the 24-hour local precipitation field clearly labelled as a model forecast rather than radar.
- Added worldwide METAR correction for fresh temperature, humidity, dew point, pressure, visibility, cloud cover, and wind observations without inventing precipitation totals.
- Added worldwide forecast domains for Africa, South America, South and Central Asia, Russia and northern Asia, polar locations, and open ocean.
- Added checksum-verified schema-2 regional calibration support with local weighted calculation and fail-closed Best Match or diagnostic-median fallback.
- Added strict NOAA ISD and NASA GPM IMERG research parsers and production gates. No worldwide calibration weights are published until a matching seamless-model holdout passes.
- Kept release Ads, UMP, Billing, Premium, and `AD_ID` payloads disabled and absent.

## [0.2.0-beta.9] - 2026-08-31

- Added worldwide place search, current location, exact coordinates, local timezone handling, and an interactive world map picker.
- Kept ČHMÚ station correction and radar scoped to Czechia while routing other locations to models that cover them.
- Clarified mainly-clear and partly-cloudy icons with a visible sun or Moon behind the cloud.
- Added a verified six-model operational diagnostic feed pipeline with compressed, checksummed tiles.
- Added an unreleased on-device model-consensus prototype. It uses a robust median only when at least three model values are present, derives rain probability and sky condition locally, and falls back to Best Match. It is not a production calibration or an accuracy claim.
- Fixed the radar card aspect ratio so rain and cloud imagery stays undistorted without large letterbox gaps.
- Added an on-demand 24-hour Local rain field with 25 surrounding forecast points, model agreement, precipitation type, and Metric or Imperial details.
- Added a five-year NASA POWER archive with local summaries, daily rows, and CSV sharing through Android to ChatGPT or another selected app.
- Split Maps into observed Czech radar and a worldwide 24-hour multi-model precipitation forecast around the selected location.
- Added UV, freezing-level, boundary-layer, integrated-water-vapour, instability, showers, and ground values to Weather details.
- Replaced the temperature-only hourly strip with a 24-hour meteogram for temperature, precipitation, day or night, wind direction, and wind speed.
- Kept the smaller mainly-clear cloud blue and rebuilt the adaptive launcher foreground with a centred padded canvas for OEM icon masks.
- Added expandable hourly rows with precipitation amount, feels-like temperature, humidity, gusts, pressure, cloud layers, UV, and visibility.
- Forced release ads and purchases off while forecasts use the non-commercial Open-Meteo Free API. Debug builds retain test monetisation for QA.
- Added a native Settings action that asks the launcher to pin and configure the weather widget.
- Added worldwide calibration regions, kept verified global-capable provider families at every coordinate, limited CHMI ALADIN to Czechia, and excluded suspended KMA output.
- Added cached calculation provenance to Weather details: region, diagnostic or fallback mode, contributor count and IDs, and fallback reason.
- Added a reproducible Czech backtest preflight that validates dates, station/model cohorts, request budgets, and the immutable-month gate before downloading or locking a holdout.

## [0.2.0-beta.8] - 2026-08-29

- Renamed the app to Selia Vetra and shortened the launcher label to Vetra.
- Corrected current conditions with fresh observations from nearby ČHMÚ stations.
- Added recent-day navigation and updated the daily detail presentation.
- Reduced the launcher mark size and removed its background on launchers that support transparency.
- Added typed static-feed research tools with bounded cache and freshness checks.

## [0.2.0-beta.7] - 2026-08-28

- Enabled the production AdMob interstitial while keeping banner ads removed.
- Activated both one-time and monthly Google Play Premium products.

## [0.2.0-beta.6] - 2026-08-28

- Expanded the home forecast to the next 24 hours across midnight.
- Added bounded left/right paging between full-day hourly forecasts.
- Fixed Settings scrolling so every language, Premium, privacy, and support control stays reachable.
- Removed banner ads and retained only the fourth-return Maps interstitial for free users.

## [0.2.0-beta.5] - 2026-08-26

- Makes the Lightning radar toggle visibly active while strikes remain layered over rain or clouds.
- Adds accessible pressed-state semantics to the Lightning control.

## [0.2.0-beta.4] - 2026-08-26

- Renamed the Play listing to Selia Weather and shortened the launcher label to Selia Wx.
- Always show separate one-time and monthly Premium buttons, even while Google Play pricing is loading.
- Decoupled Play Billing from AdMob configuration while keeping advertising disabled without production IDs.

## [0.2.0-beta.3] - 2026-08-26

- Kept the complete launcher artwork inside the Android adaptive-icon safe zone on OEM launchers.
- Fixed approximate and precise location fallback across enabled network and GPS providers.
- Fixed widget host resizing and added Minimal, Material, Pixel, and Cupertino presets with selectable font styles.
- Completed a locked 90-day training and 30-day holdout model backtest; rejected every unsafe precipitation blend and kept production weights disabled until nationwide truth coverage passes.

## [0.2.0-beta.2] - 2026-08-25

- Rebranded the app as ALADIN weather with public package `com.majkeylab.weatheraladin`.
- Added system-aware English, Czech, German, Spanish, and French UI support.
- Kept lightning available as an independent overlay on rain and cloud radar layers.
- Added a horizontal 20-hour forecast, expanded 14-day rows, and preserved full-day hourly details.
- Rebuilt the widget around one resize-safe layout and added per-widget colours, opacity, gradient or image backgrounds, text scale, alignment, label, and data visibility.
- Added an optional Buy Me a Coffee link that grants no entitlement.
- Added exact saved map points, Metric and Imperial display units, full weather and Moon details, and advanced forecast variables.
- Added consent-gated Google test ads in debug plus Google Play lifetime and monthly ad-removal products. Production ads stay disabled until real AdMob IDs are supplied.

## [0.2.0-beta.1] - 2026-08-24

- Introduced the weather-reactive minimal interface, favourites, place search, optional current location, radar, satellite imagery, and a resizable widget.
- Added Android 10 and later support.

## [0.1.0-beta.1] - 2026-08-24

- Added ALADIN forecasts for Czech places with a 14-day continuation, current conditions, hourly and daily forecasts, search, offline cache, ČHMÚ radar, and a configurable widget.
