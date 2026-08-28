# Weather Navigation and Ads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove banner ads, make Settings fully scrollable, show 24 future hours, and page between daily details with the approved swipe direction.

**Architecture:** Keep the existing single-activity Compose structure. Delete the banner-only path, use one bounded scroll container for Settings, and use Compose Foundation `HorizontalPager` over the existing daily list. Forecast data and monetisation policy remain unchanged.

**Tech Stack:** Kotlin, Jetpack Compose Material 3/Foundation, JUnit 4, Gradle Android plugin.

**Spec:** `docs/superpowers/specs/2026-08-28-weather-navigation-ads-design.md`

## Global Constraints

- Android 10 minimum (`minSdk = 29`).
- Add no dependency.
- Keep interstitial frequency at four completed Maps-to-Weather returns.
- Keep Premium, consent, forecast API, radar, widgets, version, and signing unchanged.
- Do not commit, push, or open a PR without separate explicit approval.
- Preserve untracked `release-artifacts/`.

---

### Task 1: 24-hour forecast selection

**Files:**
- Modify: `app/src/test/java/cz/majkey/pocasicesko/ui/ForecastDayTest.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt`

**Interfaces:**
- Consumes: `List<HourlyWeather>` and the current local-hour prefix.
- Produces: `upcomingHours(hourly: List<HourlyWeather>, currentHour: String): List<HourlyWeather>` limited by one shared `HOURLY_OUTLOOK_COUNT = 24` constant.

- [ ] Replace the 20-hour unit case with a two-day fixture that expects 24 entries from `2026-08-24T03:00` through `2026-08-25T02:00`.
- [ ] Add a short-payload case expecting only the real remaining entries.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.ui.ForecastDayTest" --console=plain`; confirm the 24-hour case fails with actual size 20.
- [ ] Add `HOURLY_OUTLOOK_COUNT = 24`, use it in the section title and `take`, and remove the unused caller-controlled limit.
- [ ] Re-run the targeted test; require PASS.

### Task 2: Daily detail pager

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/ui/ForecastDayTest.kt`

**Interfaces:**
- Consumes: `snapshot.daily`, `snapshot.hourly`, selected daily-row index, and `WeatherUnitFormatter`.
- Produces: `dayForPage(days: List<DailyWeather>, page: Int): DailyWeather?` plus `DayDetailSheet(days, hourly, initialPage, units, onDismiss)` using `rememberPagerState(pageCount = { days.size })` and `HorizontalPager(reverseLayout = true)`.

- [ ] Add a unit case proving negative and past-end page indexes return `null` instead of wrapping; run it and confirm the missing `dayForPage` symbol fails compilation.
- [ ] Add the one-line `getOrNull` boundary helper and re-run the targeted test; require PASS.
- [ ] Store the selected daily index instead of a copied `DailyWeather` value.
- [ ] Change `DailyForecastPanel` to return the clicked index.
- [ ] Wrap the existing daily-detail `LazyColumn` in `HorizontalPager`, obtain the day through `dayForPage`, and derive `hourlyForDay` inside each page.
- [ ] Keep `sheetGesturesEnabled = false`, vertical page scrolling, navigation-bar padding, and native pager bounds.
- [ ] Run the targeted `ForecastDayTest`; require existing normal-day and DST filtering cases to remain PASS.
- [ ] Run `./gradlew.bat :app:compileDebugKotlin --console=plain`; require PASS so pager imports/API are verified against the pinned Compose BOM.

### Task 3: Banner deletion and interstitial preservation

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherApp.kt`
- Delete: `app/src/main/java/cz/majkey/pocasicesko/ui/AdaptiveBanner.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/monetization/AdsController.kt`
- Modify: `app/build.gradle.kts`
- Modify: `README.md`

**Interfaces:**
- Consumes: `AdsController.maybeShowInterstitial(entitlement, onContinue)`.
- Produces: no banner UI/configuration; unchanged interstitial and consent behavior.

- [ ] Remove banner rendering and banner-only state/imports from `WeatherApp`.
- [ ] Delete `AdaptiveBanner.kt`.
- [ ] Remove `ALADIN_BANNER_AD_UNIT_ID`, `BANNER_AD_UNIT_ID`, and the test banner ID from Gradle configuration; validate only app and interstitial IDs.
- [ ] Replace the banner-facing public ads-ready flow with private Boolean state inside `AdsController`; retain the privacy-options `StateFlow`.
- [ ] Update README from 20 to 24 hours and from banner-plus-interstitial to interstitial-only configuration.
- [ ] Run `MonetizationPolicyTest`; require the frequency, Premium suppression, and configuration policy cases to PASS.

### Task 4: Settings scroll and full verification

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/SettingsSheet.kt`

**Interfaces:**
- Consumes: existing Settings content and callbacks.
- Produces: fully expanded modal sheet with one `fillMaxHeight().verticalScroll(...).navigationBarsPadding()` content column.

- [ ] Create `rememberModalBottomSheetState(skipPartiallyExpanded = true)` and pass it to the sheet.
- [ ] Bound the existing content column with `fillMaxHeight` and add navigation-bar padding without changing settings content.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain`; require all gates PASS.
- [ ] Install the debug APK only on an explicitly authorised connected Huawei target.
- [ ] Live-check Settings final-row reachability, 24 hours across midnight, right-to-next/left-to-previous day paging, non-wrapping boundaries, no banner on any screen, and unchanged interstitial continuation.
- [ ] Review `git diff --check`, all changed files, and `git status`; fix scope leaks and leave changes uncommitted.
