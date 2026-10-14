# Changelog

## [0.7.10] - 2026-10-14

- Show the source's light, moderate or heavy rain, drizzle, freezing precipitation and shower descriptions throughout the app and widgets, in all five supported languages. Do not equate a small hourly total with drizzle.
- Preserve exact precipitation codes supported by a strict model majority. When intensities differ within a family, retain the strongest intensity supported by a strict majority instead of always falling back to light rain.
- Stop counting ordinary and freezing precipitation as full condition agreement. Keep confidence thresholds and precipitation probabilities unchanged.
- Measure pending radar loading time from the last successful tile progress. Retry a pending forecast image-decoding failure once without HTTP cache; retain the last valid raster and timestamp if recovery fails. Keep normal tile caching, source limits and observation/forecast separation.
- The frozen August/September kernel replay changes intensity codes, not cloud percentages or rainfall amounts. Learned forecast weights remain disabled; this release does not establish a new cloud/rain accuracy gain.

## [0.7.9] - 2026-10-14

- Check the original provider's wet or missing precipitation before selecting regional dry-sky conditions. A small positive multi-model rainfall blend must not erase that safeguard and incorrectly replace a cloudy forecast with clear sky.
- Preserve rainfall amounts, hazard safeguards and confidence thresholds. This fixes an order-dependent sky-selection defect; it does not enable learned weights or establish worldwide forecast superiority.

## [0.7.8] - 2026-10-13

- Validate total cloud cover before integer conversion, matching the cloud-layer checks. Invalid optional hourly totals remain unknown; an invalid mandatory current total is rejected explicitly. Valid fractional values retain their existing display conversion.
- Forecast weights and confidence thresholds are unchanged. This boundary fix does not establish a meteorological accuracy gain.

## [0.7.7] - 2026-10-13

- Reject cloud-layer percentages outside 0–100 before integer conversion. Invalid layer readings remain unknown without discarding valid forecast hours or days.
- Correct regional metadata for European Russia and Iran. Keep the available API model list unchanged, including Russian locations across the date line.
- Keep the radar playback button's localized accessible name constant while its pressed state identifies active playback.
- Allow applying automatic widget text colours after an invalid manual HEX draft is replaced by valid rendered colours. Keep validation for accents, backgrounds and manual colours.
- Show the actual morning-briefing enablement state when background-coordinate consent is absent, without changing that consent.
- Keep a successfully downloaded weather archive available when optional local cache storage fails. Preserve response limits and the offline fallback for failed downloads.
- Reject mismatched forecast units before comparison with station observations in the research pipeline. The frozen September cloud replay remains unchanged; this release does not enable learned forecast weights or claim a measured cloud/rain accuracy gain.

## [0.7.6] - 2026-10-13

- Compare current conditions with hourly model agreement only when their valid times match. Show both times in the model comparison when a newer quarter-hour condition is displayed. Genuine model disagreement and confidence thresholds are unchanged.
- Stop treating a nearby station's ten-minute rainfall total as proof that rain is falling now. Preserve its measured amount and timestamp; explicit METAR rain and drizzle reports still correct current conditions.
- Exclude negative model wind speeds from agreement metadata as well as the calculated speed. An invalid extra source no longer hides the comparison of valid models.
- Average nearby stations' scalar wind speeds without cancelling opposing directions into false calm. Preserve the existing station selection, distance weights and circular direction calculation.
- Skip only the malformed METAR report when its timestamp is outside the supported range, retaining other stations and an earlier valid report instead of failing the forecast refresh.
- Apply regional radar metadata as soon as it arrives instead of waiting for an unrelated global request. Keep valid forecast playback running when an inactive observations cache expires, and retain a still-valid raster while a replacement model run loads.
- Preserve pasted RGB/ARGB widget colours with surrounding whitespace. Refresh cached widget displays after the device time or timezone changes without fetching weather again.
- Keep a delayed morning forecast preparation from rescheduling today's notification to tomorrow. Preparation refreshes data without replacing the independently scheduled delivery alarm.

## [0.7.5] - 2026-10-13

- Keep instantaneous weather conditions separate from preceding-hour rainfall totals. Preserve a strict liquid-precipitation majority when models disagree between drizzle, rain and showers instead of turning it into dry sky.
- Reject model-response time joins with incompatible UTC offsets. Keep the provider forecast when the timestamps cannot be safely matched.
- Show uncertainty in the next-rain outlook when a minority model predicts trace precipitation, rather than promising a rain-free next 24 hours. Preserve the provider's precipitation probability; model vote counts are not calibrated rain probabilities.
- Calculate hourly wind speed from the median of valid model speeds instead of cancelling opposing model directions. Keep circular direction handling, minimum-source safeguards and confidence thresholds unchanged. On the September six-station comparison, wind-speed MAE fell by 5.4% at nominal 24 hours and 7.1% at 48 hours. This is not a worldwide or all-weather accuracy claim.
- Recognize documented modern NOAA station sources in the independent observation validation pipeline. Keep unknown sources and failed quality checks excluded.
- Use the trained linear estimator for research precipitation amounts instead of applying linear weights to a different, weighted-median estimator. No learned production weights are enabled by this correction.
- Remove numerical-zero optimizer weights before counting fitted sources. Floating-point residue must not pass the research minimum-source gate as another model.

## [0.7.3] - 2026-10-12

- Explain hourly confidence with named model predictions, cloud cover, wind, temperature and rainfall for the displayed forecast interval. Keep confidence thresholds unchanged and include the condition actually shown for the current hour.
- Use an explicit Czech ALADIN seamless source for eligible short-range sky conditions instead of an unvalidated global dry-sky vote. Preserve wet forecasts, fog and hazards, reject incomplete intervals, and retain sky-source information through the cache. This is a regional baseline, not a demonstrated accuracy improvement.
- Compact radar controls on small screens and put secondary controls in the details drawer. Preserve the last valid raster on loading failures and avoid healthy-frame status messages shifting the map.

## [0.7.2] - 2026-10-12

- Preserve a strict majority of the models' dry-sky weather categories instead of replacing their classification with generic cloud-percentage thresholds. Keep precipitation and hazard safeguards.
- Include the provider's documented heavy-thunderstorm code in model comparisons instead of excluding those reports.
- Calculate wind disagreement from actual model pairs, not artificial corners formed by combining unrelated extremes. Keep confidence thresholds unchanged.
- Restore weather-coloured hourly icons while retaining the light/dark gradients, bordered panels and saved appearance settings. Drizzle uses dotted drops; rain uses streaks.
- Capture cloud cover and weather categories for future forecast validation. Replay legacy captures unchanged and keep categorical codes out of scalar error scores. No new learned forecast weights are enabled without validation.

## [0.6.2] - 2026-10-12

- Keep daily wind summaries valid when model inputs are incomplete. Do not display a provider's wet duration for a daily rainfall total recalculated from different model inputs.

## [0.6.1] - 2026-10-12

- Preserve unsaved custom appearance colours, text size and corner settings when Android restores the screen.

## [0.6.0] - 2026-10-11

- Join observed radar and DWD precipitation forecasts in one timeline. Use actual snapshots and accumulation intervals, retain the last valid frame on failures, and stop hidden-map requests. Improve fullscreen controls, keyboard navigation, timezone updates and short-screen layout.
- Simplify the weather overview and navigation. Keep four forecast days initially, basic hourly metrics visible, and technical fields under Advanced details. Clarify daily confidence and next-rain summaries.
- Offer installed, compatible ChatGPT, Gemini, Claude and Grok apps after archive-sharing consent. Share a CSV and an app-language question through Android; the recipient controls attachment support. Keep missing archive days distinct from zero rainfall.
- Improve widget contrast across gradients and light backgrounds, resized clock/date headers and bounded custom-image decoding. Match Material theme previews to rendered colors and label the full-row automatic-text switch.
- Reject outdated morning-briefing deliveries after the configured time changes. Show Android-blocked notification categories without changing saved preferences.
- Keep location hardware optional so devices using manually selected places are not excluded by Google Play hardware filtering.
- Keep cloud observations from the same report and invalidate incompatible model cloud layers. Preserve the provider's apparent-temperature estimate instead of shifting it by a station temperature difference.
- Avoid unnecessary forecast parsing on resume and defer Czech station metadata until needed. Reuse the parsed forecast after current-condition correction, defer collapsed moon details, and bound retained radar layers during scrubbing.
- Correct daily precipitation aggregation, station/time identity in wind backtests and exact-hour NOAA rain alignment. Purge observations dated at or after forecast issuance and refit validation folds using past-only coverage. The corrected diagnostic replay accepts 15 of 28 evaluations; no unvalidated forecast weights are enabled.

## [0.5.1] - 2026-10-11

- Refine the gradient forecast layout with a compact location picker, hourly graph, navigation and model-agreement indicators for every day and hour. Keep current-condition icons and accessibility descriptions consistent.
- Use predominant daytime conditions for the daily icon. Retain whole-day precipitation and storm warnings; distinguish drizzle from rain in the app and widgets.
- Extend the DWD precipitation map to a 48-hour target, with an honest available-horizon label, hourly European and six-hour global totals. Keep the map full-canvas with floating controls, including a visible fullscreen timeline and font-scaled header spacing.
- Combine rainy-day clothing and umbrella advice into one sentence in English, Czech, German, Spanish and French.
- Confidence remains a model-agreement estimate, not a validated accuracy probability. Forecast values and learned-weight validation gates are unchanged.

## [0.5.0] - 2026-10-07

- Redesign the forecast with softer light/dark weather gradients, outlined weather icons and a compact hourly chart. Keep radar controls, details and app-style widgets consistent with the selected theme.
- Add System, Light and Dark appearance modes, independent light/dark presets, AMOLED, custom hex colours, font styles, text size and corner controls. Block custom palettes with unreadable text.
- Show confidence for each forecast day and hour. Tap an hour for details; tap its confidence chip for the compared model ranges and limitations.
- Base confidence on complete hourly model inputs, temperature/wind/cloud/rain disagreement, forecast lead time and download age. Missing or partial inputs remain explicit. This is an agreement estimate, not a validated accuracy probability; forecast values and calibration gates are unchanged.

## [0.4.4] - 2026-10-05

- Fetch the three nearest ČHMÚ station observations concurrently, retaining their selected order, per-source fallback and timeouts.

## [0.4.3] - 2026-10-05

- Ask before background alerts and morning briefings reuse saved coordinates, including after an update on Android 10. Keep previous alert settings and provide an off switch in Notifications.
- Explain location recipients before GPS permission and clarify local data deletion in the privacy policy.

## [0.4.2] - 2026-10-04

- Choose a daily briefing time in Notifications → Morning briefing. Prepare its forecast beforehand and deliver from the cache at the chosen time.
- Expire missed briefings after 30 minutes and suppress duplicate delivery for the same day.
- Set a separate rain lead time and probability threshold. Default to the next hour and avoid alerts from a single weak model signal alone.
- Schedule cached rain checks through Android alarms alongside background forecast refresh. Add the Android Alarms & reminders shortcut for timely delivery on Android 12+.

## [0.4.1] - 2026-10-01

- Restore rain and official-warning background checks after reboot or app updates even when morning briefing is off.
- Keep the valid forecast when a daily rain probability is missing. Show the missing probability as unavailable instead of zero, and avoid unsupported no-umbrella advice.
- Add Background delivery guidance and a shortcut to Android app settings, including Huawei/Honor launch restrictions.

## [0.4.0] - 2026-09-24

- Group settings into Notifications, Official warnings, Appearance, Units, Language, Widgets, and About. Keep thresholds in focused notification submenus and return to the parent menu with Back.
- Swipe between Weather, Radar, and Ask AI. Keep scroll positions and the radar WebView, cancel superseded tab animations, and leave map drags and fullscreen gestures to the radar.
- Add weather-reactive colours, Ocean/Sunset/Forest gradients, and Material/Minimal styles. App-style widgets follow the choice; explicit widget backgrounds remain unchanged.
- Revalidate the retained AI archive on return and app resume without clearing the question or date range. Clear keyboard focus when leaving the tab.

## [0.3.1] - 2026-09-24

- Coalesce rapid radar timeline changes instead of starting tile loads for every crossed position. Keep the selected frame through background/resume and prevent superseded frames from moving the slider.
- Move Weather warnings directly below View all weather details, before Weather history.
- Give the widget temperature more room, use accurate preview sizes, and render the editor preview through the same native RemoteViews as the widget. New widgets start with the app-style gradient; saved customizations remain unchanged.
- Refresh Play screenshot artwork using real app screens and public Prague history data.
- Quarantine invalid cumulative rainfall from an affected model/run without discarding valid fields from other sources. Keep missing rainfall distinct from zero and retain all production calibration gates.
- Add a research-only forward-validation comparison of location-specific and regional model weights. It is not a new untouched accuracy validation and does not activate runtime weights.

## [0.3.0] - 2026-09-23

- Replace sampled future-map interpolation with official DWD precipitation grids: hourly ICON-EU in Europe and six-hour ICON elsewhere. Keep observed radar separate, preload animation frames, refresh expired runs, and show explicit tile failures.
- Show four days initially, with an option to expand the full forecast. Move weather details and history below the daily overview.
- Use shorter hourly summaries for rain, snow, wind, visibility and UV. Keep technical model disagreement inside the precipitation explanation. Add total cloud cover and preserve unavailable feels-like values.
- Add configurable rain, cold, temperature-drop, heat, wind and UV advice with separate Android notification channels. Keep morning briefing optional.
- Add official ČHMÚ warnings matched through ČÚZK administrative areas and NWS point warnings in the US. Unsupported sources remain explicit; the app is not an emergency-alert service.
- Fix offset timestamp parsing on Android 10 for station observations, warning feeds and calibration data. Preserve forecast validation gates; no new accuracy claim or unvalidated model weights.

## [0.2.0-beta.24] - 2026-09-12

- Add inner spacing and wrapping to highlighted hourly rows, keeping text away from rounded edges and allowing the time column to grow with text size.
- Show precipitation for the hour beginning at each displayed timestamp in daily details and the 24-hour chart. Read amounts, probability, components and model spread from the matching interval-ending source record; do not shift instantaneous temperature or conditions.
- Keep missing, duplicate or invalid interval endpoints unavailable. Preserve source timestamps and cached forecast data.

## [0.2.0-beta.23] - 2026-09-08

- Preserve a strict majority of valid model weather codes when cloud or precipitation amounts are missing. Reject fractional and unsupported codes before counting; do not change precipitation amounts or probabilities.
- Keep explicit local METAR weather alongside recognized vicinity or recent-weather groups. Nonlocal groups alone never become local rain, and unknown or mixed-phase groups still fail closed.
- Keep fresh ČHMÚ temperature and rain-gauge observations when humidity is unavailable. Retain the gauge's original timestamp and do not substitute ten-minute totals into the forecast's fifteen-minute interval.

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
