# ALADIN weather localization implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebrand the Android app as `ALADIN weather`, change its Play package to `com.majkeylab.weatheraladin`, and add a persisted system-aware language picker for English, Czech, German, Spanish, and French.

**Architecture:** Android resources provide all visible text, with English as the fallback. `AppLocale` applies a persisted language override through framework APIs and wraps contexts on Android 10 through 12. Compose, widgets, and the local radar viewer resolve text at their display boundary instead of storing translated strings in weather data.

**Tech Stack:** Kotlin 2.3.21, Jetpack Compose Material 3, Android SDK 36, minSdk 29, Android framework locale APIs, `SharedPreferences`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-24-aladin-weather-localization-design.md`

## Global constraints

- Public Play package: `com.majkeylab.weatheraladin`.
- Visible name in every locale: `ALADIN weather`.
- Version: `versionCode = 3`, `versionName = "0.2.0-beta.2"`.
- Supported locales: system default, `en`, `cs`, `de`, `es`, and `fr`.
- Unsupported system locales use the English `values/strings.xml` fallback.
- Add no dependency.
- Keep `minSdk = 29` and `targetSdk = 36`.
- Keep the Kotlin namespace `cz.majkey.pocasicesko`.
- Use only emulators for runtime QA.
- Keep `.signing/` ignored and never print its contents.

---

### Task 1: Application identity and locale contract

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/locale/AppLocale.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/locale/AppLocaleTest.kt`
- Create: `app/src/main/res/xml/locales_config.xml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `SupportedLanguage`, `normalizeLanguageTag(String?): String`, `AppLocale.wrap(Context): Context`, `AppLocale.set(Activity, String)`, and `AppLocale.localized(Context): Context`.
- Persists: preference file `app_locale`, key `language_tag`, where an empty string means System.

- [ ] **Step 1: Write the failing locale contract test**

```kotlin
class AppLocaleTest {
    @Test
    fun acceptsSupportedTagsAndResetsInvalidTagsToSystem() {
        assertEquals("en", normalizeLanguageTag("en-US"))
        assertEquals("cs", normalizeLanguageTag("cs-CZ"))
        assertEquals("de", normalizeLanguageTag("de"))
        assertEquals("es", normalizeLanguageTag("es-MX"))
        assertEquals("fr", normalizeLanguageTag("fr-FR"))
        assertEquals("", normalizeLanguageTag("pl"))
        assertEquals("", normalizeLanguageTag(null))
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests cz.majkey.pocasicesko.locale.AppLocaleTest --console=plain`

Expected: compilation fails because `normalizeLanguageTag` does not exist.

- [ ] **Step 3: Implement the minimal locale contract**

```kotlin
enum class SupportedLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    CZECH("cs"),
    GERMAN("de"),
    SPANISH("es"),
    FRENCH("fr"),
}

internal fun normalizeLanguageTag(tag: String?): String {
    val language = tag.orEmpty().substringBefore('-').lowercase(Locale.ROOT)
    return SupportedLanguage.entries.firstOrNull { it.tag == language }?.tag.orEmpty()
}
```

`AppLocale.set` stores the normalized tag. On API 33 and newer it assigns `LocaleManager.applicationLocales`. On API 29 through 32 it recreates the activity after persistence. `AppLocale.wrap` returns a configuration context with the persisted locale. `AppLocale.localized` applies the same rule for widgets.

- [ ] **Step 4: Add the Android locale declaration**

`locales_config.xml` contains exactly:

```xml
<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
    <locale android:name="cs" />
    <locale android:name="de" />
    <locale android:name="es" />
    <locale android:name="fr" />
</locale-config>
```

Set `android:localeConfig="@xml/locales_config"` on `<application>`.

- [ ] **Step 5: Change the public app identity**

Set these exact Gradle values:

```kotlin
applicationId = "com.majkeylab.weatheraladin"
versionCode = 3
versionName = "0.2.0-beta.2"
```

- [ ] **Step 6: Run the test and build**

Run: `./gradlew :app:testDebugUnitTest --tests cz.majkey.pocasicesko.locale.AppLocaleTest :app:assembleDebug --console=plain`

Expected: PASS and a debug APK whose package name is `com.majkeylab.weatheraladin`.

- [ ] **Step 7: Commit the foundation**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res/xml/locales_config.xml app/src/main/java/cz/majkey/pocasicesko/locale/AppLocale.kt app/src/test/java/cz/majkey/pocasicesko/locale/AppLocaleTest.kt
git commit -m "feat(locale): add app locale contract"
```

### Task 2: Complete Android string catalogs

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values-cs/strings.xml`
- Create: `app/src/main/res/values-de/strings.xml`
- Create: `app/src/main/res/values-es/strings.xml`
- Create: `app/src/main/res/values-fr/strings.xml`

**Interfaces:**
- Produces: complete string resources for app navigation, forecast, locations, errors, settings, weather conditions, radar, and widgets.
- Consumes: language tags from Task 1.

- [ ] **Step 1: Replace the default catalog with English fallback text**

Keep `app_name` as `ALADIN weather`. Define these resource groups with formatting arguments where values vary:

```text
Navigation: nav_weather, nav_maps, open_settings
Forecast: refresh_forecast, forecast_unavailable, retry, cached_data, feels_like_temperature, next_six_hours, now, current_details, days_14, today, open_hourly_detail, whole_day_hours
Metrics: precipitation, wind, humidity, pressure, sun, relative, sea_level, precipitation_value, wind_value
Locations: locations, use_my_location, location_purpose, current_location, favorites, search_in_czechia, city_or_municipality, no_czech_result, toggle_favorite, add_favorite, remove_favorite
Errors: forecast_load_failed, location_permission_required, location_lookup_failed, search_failed, location_outside_czechia, enable_system_location, favorite_limit, server_error
Settings: settings, language, language_system, language_english, language_czech, language_german, language_spanish, language_french
Conditions: condition_clear_day, condition_clear_night, condition_partly_cloudy, condition_cloudy, condition_fog, condition_drizzle, condition_rain, condition_snow, condition_showers, condition_snow_showers, condition_storm, condition_unknown
Radar: radar_title, radar_subtitle, radar_footer, radar_retry, radar_rain, radar_clouds, radar_lightning, radar_play, radar_timeline, radar_loading, radar_unavailable, radar_nowcast_loading, radar_nowcast_unavailable
Widget: existing widget keys plus widget_title, widget_preview_description, widget_appearance, widget_theme_auto, widget_theme_light, widget_theme_dark, widget_theme_transparent, widget_clock_title, widget_clock_description, widget_icon_title, widget_icon_description, widget_details_title, widget_details_description, widget_apply
```

- [ ] **Step 2: Add exact Czech, German, Spanish, and French catalogs**

Each translated file contains every key from the English catalog. Provider names and technical terms remain unchanged: `ALADIN`, `ECMWF`, `ČHMÚ`, `Open-Meteo`, `nowcast`, `UTC`, `km/h`, `hPa`, and `mm`.

Translate the language picker names into each current locale. Keep the visible app name `ALADIN weather` in all five files.

- [ ] **Step 3: Validate catalog parity**

Run: `./gradlew :app:lintDebug --console=plain`

Expected: no missing translation errors, malformed formatting arguments, or resource compilation errors.

- [ ] **Step 4: Commit the catalogs**

```bash
git add app/src/main/res/values app/src/main/res/values-cs app/src/main/res/values-de app/src/main/res/values-es app/src/main/res/values-fr
git commit -m "feat(locale): add translated string catalogs"
```

## Checkpoint: Locale foundation

- [ ] Locale contract tests pass.
- [ ] All five catalogs compile.
- [ ] Debug APK uses package `com.majkeylab.weatheraladin`.
- [ ] Review the diff before UI migration.

### Task 3: Localized weather state and forecast UI

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherText.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherModels.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/data/WeatherParserTest.kt`

**Interfaces:**
- Produces: `WeatherConditionKey`, typed `WeatherCondition`, `conditionFor(code: Int, isDay: Boolean): WeatherCondition`, and `WeatherCondition.labelResource(): Int`.
- Removes: translated `WeatherCondition.label` from the data layer and cached widget condition strings.

- [ ] **Step 1: Change the existing condition test to expect typed state only**

```kotlin
@Test
fun mapsWeatherCodesToTypedConditions() {
    assertEquals(WeatherConditionKey.CLEAR_DAY, conditionFor(0, true).key)
    assertEquals(WeatherConditionKey.DRIZZLE, conditionFor(51, true).key)
    assertEquals(WeatherConditionKey.SHOWERS, conditionFor(80, true).key)
    assertEquals(WeatherConditionKey.STORM, conditionFor(95, true).key)
    assertEquals(WeatherConditionKey.UNKNOWN, conditionFor(999, true).key)
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests cz.majkey.pocasicesko.data.WeatherParserTest --console=plain`

Expected: compilation fails because `WeatherConditionKey` does not exist.

- [ ] **Step 3: Return `WeatherKind` and map labels at the display boundary**

```kotlin
@StringRes
fun WeatherCondition.labelResource(): Int = when (key) {
    WeatherConditionKey.CLEAR_DAY -> R.string.condition_clear_day
    WeatherConditionKey.CLEAR_NIGHT -> R.string.condition_clear_night
    WeatherConditionKey.PARTLY_CLOUDY -> R.string.condition_partly_cloudy
    WeatherConditionKey.CLOUDY -> R.string.condition_cloudy
    WeatherConditionKey.FOG -> R.string.condition_fog
    WeatherConditionKey.DRIZZLE -> R.string.condition_drizzle
    WeatherConditionKey.RAIN -> R.string.condition_rain
    WeatherConditionKey.SNOW -> R.string.condition_snow
    WeatherConditionKey.SHOWERS -> R.string.condition_showers
    WeatherConditionKey.SNOW_SHOWERS -> R.string.condition_snow_showers
    WeatherConditionKey.STORM -> R.string.condition_storm
    WeatherConditionKey.UNKNOWN -> R.string.condition_unknown
}
```

Use `stringResource(condition.labelResource())` in Compose. Add `KEY_WIDGET_CONDITION_KEY` and persist `condition.key.name`. Keep the old `KEY_WIDGET_CONDITION` constant until the widget task switches consumers, but stop writing translated text to it.

- [ ] **Step 4: Replace every visible string in `ForecastScreen.kt`**

Use `stringResource` for headings, metrics, content descriptions, cache state, day detail, and formatted strings. Use `Locale.current.platformLocale` for day and timestamp formatters so weekday and month names follow the selected language.

- [ ] **Step 5: Run focused and full unit tests**

Run: `./gradlew :app:testDebugUnitTest --console=plain`

Expected: all parser, favorites, locale, and day-detail tests pass.

- [ ] **Step 6: Commit the weather slice**

```bash
git add app/src/main/java/cz/majkey/pocasicesko/data/WeatherModels.kt app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt app/src/main/java/cz/majkey/pocasicesko/ui/WeatherText.kt app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt app/src/test/java/cz/majkey/pocasicesko/data/WeatherParserTest.kt
git commit -m "feat(locale): localize forecast content"
```

### Task 4: Language settings and location UI

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/ui/SettingsSheet.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/MainActivity.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherApp.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/DeviceLocationRepository.kt`

**Interfaces:**
- Consumes: `SupportedLanguage` and `AppLocale.set` from Task 1.
- Produces: `SettingsSheet(selectedTag: String, onLanguage: (String) -> Unit, onDismiss: () -> Unit)`.

- [ ] **Step 1: Wrap `MainActivity` before resource access**

```kotlin
override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(AppLocale.wrap(newBase))
}
```

- [ ] **Step 2: Add the language settings sheet**

The sheet lists System first, followed by English, Czech, German, Spanish, and French. Mark the active row with a check icon. `AppLocale.set(activity, tag)` persists the tag and recreates the activity.

- [ ] **Step 3: Add a settings action to the forecast header**

Pass `onSettings` from `WeatherApp` to `ForecastScreen` and `LocationHeader`. Place the settings icon opposite refresh. Keep both touch targets at least 48 dp.

- [ ] **Step 4: Localize navigation, location search, errors, and accessibility text**

Replace hard-coded Czech strings in `WeatherApp.kt`. Map repository and location failures to the generic localized messages from Task 2. Preserve specific validation facts such as the 12-favorite limit and outside-Czechia result.

- [ ] **Step 5: Build and run Android 10 locale smoke checks**

Run: `./gradlew :app:assembleDebug --console=plain`

On an API 29 emulator:

1. Start with the emulator in Czech and confirm Czech UI.
2. Select English and confirm navigation, forecast, locations, and day detail change immediately.
3. Force-stop and restart. Confirm English persists.
4. Select System. Confirm the UI returns to Czech.

- [ ] **Step 6: Commit the settings slice**

```bash
git add app/src/main/java/cz/majkey/pocasicesko/MainActivity.kt app/src/main/java/cz/majkey/pocasicesko/data/DeviceLocationRepository.kt app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt app/src/main/java/cz/majkey/pocasicesko/ui/SettingsSheet.kt app/src/main/java/cz/majkey/pocasicesko/ui/WeatherApp.kt
git commit -m "feat(locale): add in-app language settings"
```

### Task 5: Radar localization

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/RadarScreen.kt`
- Modify: `app/src/main/assets/radar.html`

**Interfaces:**
- Consumes: the active Compose locale.
- Produces: radar URL query `lang=en|cs|de|es|fr` and localized radar output.

- [ ] **Step 1: Pass the active language to the local radar**

Build `RADAR_APP_URL?lang=<tag>` from the active locale. Wrap `AndroidView` in `key(url)` so a language change reloads the trusted local asset.

- [ ] **Step 2: Add a fixed radar dictionary**

`radar.html` contains dictionaries for `en`, `cs`, `de`, `es`, and `fr`. Translate layer buttons, loading states, unavailable states, play label, timeline label, and image alternatives. Reject every other query value and use `en`.

- [ ] **Step 3: Verify radar on Android 10 and 15**

Confirm English, Czech, and French layer labels. Verify measured rain, clouds, lightning, and +60-minute nowcast in each selected language.

- [ ] **Step 4: Commit the radar slice**

```bash
git add app/src/main/assets/radar.html app/src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt app/src/main/java/cz/majkey/pocasicesko/ui/RadarScreen.kt
git commit -m "feat(locale): localize radar controls"
```

### Task 6: Widget localization

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/widget/WeatherWidgetProvider.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/widget/WidgetConfigActivity.kt`

**Interfaces:**
- Consumes: `AppLocale.localized`, `KEY_WIDGET_CONDITION_KEY`, and localized condition resources.
- Removes: legacy `KEY_WIDGET_CONDITION` after both widget consumers stop reading it.

- [ ] **Step 1: Localize widget rendering**

Use `AppLocale.localized(context)` before `RemoteViews` construction. Resolve city placeholders, condition labels, range placeholders, and content descriptions from resources. Keep actual city names and weather numbers unchanged.

- [ ] **Step 2: Localize and wrap `WidgetConfigActivity`**

Override `attachBaseContext` with `AppLocale.wrap`. Replace visible configuration text and theme labels with resources. Resolve the preview condition from `WeatherConditionKey`.

- [ ] **Step 3: Remove the legacy translated cache key**

Delete `KEY_WIDGET_CONDITION` only after `WeatherWidgetProvider` and `WidgetConfigActivity` read `KEY_WIDGET_CONDITION_KEY`. Keep `KEY_WIDGET_KIND` for icons and weather-dependent backgrounds.

- [ ] **Step 4: Verify widgets on Android 10 and 15**

Open widget configuration in English and French. Confirm the preview, placeholder text, theme chips, switches, and actual widget use the selected language.

- [ ] **Step 5: Commit the widget slice**

```bash
git add app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt app/src/main/java/cz/majkey/pocasicesko/widget/WeatherWidgetProvider.kt app/src/main/java/cz/majkey/pocasicesko/widget/WidgetConfigActivity.kt
git commit -m "feat(locale): localize widgets"
```

## Checkpoint: End-to-end localization

- [ ] English fallback is complete.
- [ ] Czech, German, Spanish, and French have no missing resources.
- [ ] System reset and a manual override work on API 29 and API 35.
- [ ] Forecast, locations, radar, and widget behavior still works.

### Task 7: Rebrand docs and Play metadata

**Files:**
- Modify: `README.md`, `PRIVACY.md`, `CHANGELOG.md`, and `docs/index.html`
- Modify: `docs/google-play-submission.md`
- Create: `fastlane/metadata/android/en-US/*`
- Modify: `fastlane/metadata/android/cs-CZ/*`
- Create: `fastlane/metadata/android/de-DE/*`, `es-ES/*`, and `fr-FR/*`

**Interfaces:**
- Consumes: final identity and translated product copy.
- Produces: five complete Play listings and beta.2 release notes.

- [ ] **Step 1: Rebrand repository documentation**

Replace the visible product name with `ALADIN weather`. State package `com.majkeylab.weatheraladin`, Android 10 support, supported languages, system-language default, and privacy behavior. Keep the repository URL unchanged.

- [ ] **Step 2: Update the privacy policy**

Change the product name and package reference. Do not change the existing disclosures for location, Open-Meteo, Android Geocoder, ČHMÚ, local storage, or HTTPS.

- [ ] **Step 3: Add five localized store listings**

Each locale contains `title.txt`, `short_description.txt`, `full_description.txt`, and `changelogs/3.txt`. Every title is `ALADIN weather`. Keep the English title under 30 characters, each short description under 80 characters, each full description under 4,000 characters, and each changelog under 500 characters.

Reuse the verified icon, feature graphic, and phone screenshots in each locale only when Play requires locale-local copies. Do not duplicate image files in Git when Play accepts the default listing assets.

- [ ] **Step 4: Validate text limits and links**

Check file lengths. Verify `https://majkey25.github.io/Pocasi-Cesko/` returns HTTP 200 and contains the `ALADIN weather` privacy title after merge.

- [ ] **Step 5: Commit docs and listings**

```bash
git add README.md PRIVACY.md CHANGELOG.md docs fastlane
git commit -m "docs: rebrand ALADIN weather release"
```

### Task 8: Release, Play handoff, and cleanup

**Files:**
- No production source changes.
- Local only: `.signing/`, release artifacts, emulator state, and `.reference/tmp`.

**Interfaces:**
- Produces: PR, signed beta.2 artifacts, verified GitHub release, prepared Play Console form, and cleanup report.

- [ ] **Step 1: Run the final quality gate**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --console=plain
```

Expected: all tests pass, lint has zero errors, and signed APK and AAB exist.

- [ ] **Step 2: Verify signatures and hashes**

Use `apksigner verify --verbose --print-certs` for the APK and `jarsigner -verify` for the AAB. Generate `SHA256SUMS.txt` from the exact staged artifacts.

- [ ] **Step 3: Run release emulator QA**

On API 29 and API 35, test the final signed release package. Cover system locale, English, Czech, one additional locale, persistence, forecast, favorites, location permission denial, current location, day detail, radar, clouds, nowcast, launcher icon, and widget configuration.

- [ ] **Step 4: Open a PR and require green CI**

Push `feat/localization/24-08-2026`, open a PR against `main`, document the package change, and merge only after the Android CI build succeeds.

- [ ] **Step 5: Publish `v0.2.0-beta.2`**

Create the prerelease from the merge commit. Upload the signed APK, signed AAB, and checksum manifest. Download the published assets and verify hashes and signatures again.

- [ ] **Step 6: Prepare the Play Console form**

Set app name `ALADIN weather`, package `com.majkeylab.weatheraladin`, default listing language English, type App, and price Free. Stop immediately before the action that creates the Play app and request action-time confirmation.

- [ ] **Step 7: Complete Play setup after confirmation**

After the user confirms, create the Play app. Add the privacy URL, five listings, store assets, declarations, data safety answers, app access, target audience, and the signed AAB to an internal test release. Request action-time confirmation before each form submission that communicates data or creates a release.

- [ ] **Step 8: Stop emulators and reclaim QA storage**

List emulator serials and AVD names first. Stop every running emulator with an explicit `adb -s emulator-<port> emu kill` command. Delete only the `PocasiCesko_API_29_QA` AVD and QA-only temporary artifacts after verifying their exact paths. Remove the Android 29 system image only if no remaining AVD uses it. Keep the repo, `.signing/`, final release artifacts, and local project.

- [ ] **Step 9: Report final state**

Report PR, merge commit, release URL, Pages URL, Play track state, artifact hashes, retained files, deleted QA data, reclaimed disk space, and any Play Console step that still needs user action.

## Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Android 10 cannot use `LocaleManager` | High | Persist the tag and wrap both activities before resource access. |
| Translated strings remain in data or preferences | High | Store `WeatherKind` and resolve labels at the UI or widget boundary. |
| Format arguments differ between locales | High | Keep identical resource names and placeholders. Run Lint after catalog changes. |
| WebView keeps stale language text | Medium | Include `lang` in the local URL and key `AndroidView` by URL. |
| Package change breaks old installs | Expected | Treat beta.2 as a new app identity. Document that beta.1 does not upgrade in place. |
| Cleanup removes a shared Android image | High | Inspect every remaining AVD before removing a system image. |

## Open questions

None. The approved spec defines every product and release choice.
