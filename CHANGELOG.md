# Changelog

## [0.2.0-beta.22] - 2026-09-08

- Add per-widget system, numeric, padded, ISO, readable and custom date formats, plus system, 12-hour and 24-hour clock formats. Validate custom patterns before saving and preserve existing widget defaults.
- Keep hourly cloud layers with the model contributors that determine the total-cloud median. Do not mix independent layer medians or unrelated Best Match fallback values.
- Preserve missing layers as unknown and retain the complete provider cloud group when there are too few valid total-cloud contributors.
- Preserve provider-specific cloud-layer definitions, current sky observations, precipitation and probabilities. No additional data requests or newly learned weights.

## [0.2.0-beta.21] - 2026-09-08

- Show how many contributing models predict a nonzero precipitation amount and their amount range in expanded hourly details, including trace amounts.
- Compute this optional information from the already downloaded model data. Keep forecasts, provider probabilities, component blending and advice unchanged.
- Preserve old caches and ignore malformed or misaligned optional spread metadata. Model agreement is descriptive, not a calibrated probability or accuracy guarantee.

## [0.2.0-beta.20] - 2026-09-08

- Keep the model precipitation map usable across an hour boundary by retaining one additional forecast sample. The ten-minute cache limit and minimum twelve-hour horizon remain enforced.
- Explain that precipitation amounts and probabilities use separate model guidance and can disagree.
- Record bounded, coordinate-free fetch diagnostics to investigate source timeouts. Check station freshness again after network requests finish.

## [0.2.0-beta.19] - 2026-09-08

- Keep drizzle, trace rainfall and snowfall visible in hourly summaries, the next-precipitation summary and optional morning advice. Do not derive a higher rain probability from an amount or condition code.
- Blend rain, showers and snowfall from the same model contributors as the total precipitation. Preserve unavailable components instead of borrowing another model's zero.
- Label hourly precipitation intervals explicitly and explain the 0.1 mm probability threshold. Restrict the next-precipitation summary to the following 24 hours.
- Reject suspect, missing, estimated and malformed ČHMÚ current measurements; retain usable provisional readings and their original timestamps. Variable wind reports are not fixed directions.
- Require checked ČHMÚ observations for calibration and exclude censored trace amounts from numeric rainfall truth. Keep provisional replay separate from validated evidence.

## [0.2.0-beta.18] - 2026-09-08

- Keep current conditions separate from the hourly forecast and daily summary. Remove sunshine-to-clear-sky guesses and prevent ten-minute station amounts from replacing fifteen-minute model totals.
- Preserve a strict majority of model drizzle reports even below 0.1 mm. Keep sky labels consistent with supported cloud-only blends and show trace precipitation as less than 0.1 mm (or 0.01 in), not zero. Provider rain probabilities are not inflated.
- Read explicit nearby METAR drizzle/rain reports, distinguish sea-level pressure from altimeter pressure, and stop treating partial-height CLR/CAVOK reports as zero total cloud. Reject invalid station humidity instead of converting it to zero.
- Add a 12-hour sampled model precipitation map alongside observed radar, load-before-swap animation, missing/stale-area warnings, controls below the map, and fullscreen mode.
- Share the full five-year archive with a custom question and selected UTC focus period. Confirm the disclosure before Android recipient selection; request answers in the app language. Preserve missing data and per-variable coverage across 15 archive metrics.
- Keep Ask AI compact, pin sheet back controls, group detailed statistics, and mark the actual current day/hour in the selected location's time.
- Add in-app links to privacy, terms, refunds and cookie pages, with accessible navigation and verified developer contact details. Include the complete Leaflet licence in the app.

- Connect validated, location-scoped learned weights to hourly forecasts through the checksum-linked Pages feed. Use actual model run times and response UTC offsets; reject stale, mismatched, or unvalidated inputs.
- Show the first calibrated hourly sample and its weights separately from current conditions. Keep live multi-model forecasts available when calibration is unavailable.
- Require nonoverlapping calibration selectors and sufficient holdout samples. Keep rainfall intervals and wind outside scalar calibration until their contracts are validated.
- Add NOAA GHCNh temperature observations and a frozen, reproducible East Asia comparison. Preserve the rejected candidate and per-station results; do not activate weights from pooled scores alone.

## [0.2.0-beta.17] - 2026-09-06

- Add a widget color picker with HEX input, swatches, RGB channels, and opacity. HEX input accepts values with or without a leading hash.
- Keep text readable on Light and opaque custom backgrounds with automatic contrast. Preserve manual colors, saved preferences, and unfinished HEX edits across recreation.
- Preserve current sky conditions and prevent older hourly values from replacing newer current readings. Independent station corrections remain active.
- Require sufficient source coverage and a strict majority for hazard consensus, retain freezing-rain/drizzle types, and classify hourly rain from its blended amount. Provider rain probabilities remain unchanged.
- Recalculate daily rain and snowfall totals from complete hourly data, retaining provider totals when coverage is incomplete. These are consistency fixes, not a validated forecast-accuracy claim.

## [0.2.0-beta.16] - 2026-09-05

- Open the five-year archive directly from the forecast screen. Select the last 30 days, 365 days, all data, or a custom inclusive date range to calculate rainfall totals and coverage.
- Share the full archive through the new Ask AI action with any installed app that accepts CSV attachments. Daily archive periods explicitly use UTC.
- Keep typed coordinates, map pins, and saved location drafts synchronized across rotation.
- Reject stale sunshine samples and invalid numeric forecast values. Validate device-location age with a monotonic clock.
- Read forecast/cache metadata consistently during concurrent updates. Replace archive caches atomically and preserve the previous archive when refresh is cancelled or cannot be saved.
- Match widget previews to selected fonts, alignment, and size. Improve selected-option accessibility and hourly data wrapping.
- Add an App style widget preset with the forecast's weather-aware gradient, adjustable corners, and content spacing.
- Fit widget temperatures to the available space at large text sizes and use matching portrait or landscape dimensions.
- Publish a reproducible 313-case temperature comparison with regional limitations. No new calibration weights or worldwide accuracy guarantee.
- Capture future model forecasts for research and compare them with independently sourced station observations. Missing observations remain unscored. WeatherNext 3 access and usage constraints are documented; it is not an active provider.

## [0.2.0-beta.15] - 2026-09-05

- Restore native downward dismissal of day details while preserving list scrolling and sideways day navigation.
- Use one continuous background for the 24-hour forecast and temperature graph.
- Show archive data coverage for daily totals, solar energy, humidity, and wind. Include missing-data guidance with ChatGPT exports.
- Keep shared CSV files independent so exporting another location never overwrites an earlier share. Retain up to 12 exports, pruning files older than seven days when a new export is created.

## [0.2.0-beta.14] - 2026-09-05

- Preserve provider rain probabilities and current precipitation intervals; incomplete hourly data no longer becomes a zero daily total.
- Prevent cancelled or superseded location requests from overwriting the forecast cache.
- Refresh stale forecasts when returning to the app and retain navigation and hourly detail across recreation.
- Add radar retry, request timeout, stale-frame validation, localized controls, and background playback pause. Keep tile failures visible.
- Release replaced WebViews and handle missing browsers safely.
- Explain snow, mixed precipitation, freezing rain, wind, visibility, feels-like temperature, and UV in hourly highlights. Allow long statistics to wrap.
- Preserve configured widget statistics when widening a tall widget and use correct night and partly-cloudy icons.
- Fix Android 10 widget loading by applying font and alignment in XML instead of unsupported RemoteViews methods. Make content toggle labels clickable and accessible.
- Let users customize existing widgets from Settings, including on launchers without a reconfiguration action.
- Run widget and morning-briefing network refresh through a bounded Android job with offline retry.
- Reject calibration exports outside explicitly evaluated regions, seasons, and lead times. Keep live calibration disabled until source run timestamps are available.
- Extend CI to release lint, R8, APK, AAB, and radar recovery checks. Release builds remain free of ads and Premium.

## [0.2.0-beta.13] - 2026-09-04

- Renamed the product to Selia Weather with the short Weather launcher label while preserving the existing package and app data.
- Replaced the spatial target views with one worldwide observed RainViewer radar with zoom, pan, animation, timeline, and coverage controls.
- Added a full-width highlighted plain-language summary to every expanded hour while retaining all available detailed metrics.
- Added accessible labels to both floating navigation buttons.
- Kept release Ads, UMP, Billing, Premium, and `AD_ID` payloads disabled and absent.

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
