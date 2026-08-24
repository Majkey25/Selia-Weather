# ALADIN weather adaptive release implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix radar layer composition and widget resize, add a 20-hour forecast and advanced per-widget customization, publish English project documentation, and release `v0.2.0-beta.2`.

**Architecture:** Keep weather data and stored values language-neutral. Compose, the radar asset, and `RemoteViews` resolve presentation at the UI boundary. The widget uses one stable view hierarchy, one validated per-widget settings model, bounded background bitmaps, and a safe fallback renderer.

**Tech Stack:** Kotlin 2.3.21, Android SDK 36 with minSdk 29, Jetpack Compose, Android `AppWidgetProvider`, `RemoteViews`, Storage Access Framework, local HTML and JavaScript, JUnit 4, Gradle, GitHub Actions, GitHub Pages, and fastlane metadata files.

**Spec:** `docs/superpowers/specs/2026-08-24-aladin-weather-adaptive-radar-widget-design.md`

## Global constraints

- Keep `applicationId = "com.majkeylab.weatheraladin"`, versionCode `3`, and versionName `0.2.0-beta.2`.
- Keep Android 10 support with `minSdk = 29`.
- Use no new dependency.
- Keep system language as the default. Keep English as the unsupported-locale fallback.
- Preserve the five explicit locales `en`, `cs`, `de`, `es`, and `fr`.
- Preserve current ČHMÚ and Open-Meteo source URLs and attribution.
- Keep the technical token `nowcast` lowercase in every locale.
- Bound every bitmap sent through `RemoteViews` to at most 512 by 256 pixels.
- Use only dedicated Android emulators. Never send ADB commands to a physical device.
- Do not submit a Play Console form or publish a Play release without action-time confirmation.
- Do not expose signing passwords, tokens, or account data.

## File structure

- `app/src/main/assets/radar.html` owns radar base-layer and lightning-overlay state.
- `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt` owns the 20-hour and 14-day presentation.
- `app/src/main/java/cz/majkey/pocasicesko/widget/WidgetSettings.kt` owns pure widget settings, size classes, color parsing, and preference keys.
- `app/src/main/java/cz/majkey/pocasicesko/widget/WidgetBackground.kt` owns bounded custom-image and gradient bitmap rendering.
- `app/src/main/java/cz/majkey/pocasicesko/widget/WeatherWidgetProvider.kt` owns `RemoteViews` rendering, error logging, and the safe fallback.
- `app/src/main/java/cz/majkey/pocasicesko/widget/WidgetConfigActivity.kt` owns the Android widget ID and document-picker lifecycle.
- `app/src/main/java/cz/majkey/pocasicesko/widget/WidgetEditorScreen.kt` owns the Compose editor and provider-aligned preview.
- `app/src/main/res/layout/widget_adaptive.xml` is the only hosted widget hierarchy.
- `app/src/main/java/cz/majkey/pocasicesko/ui/Support.kt` owns the published Buy Me a Coffee URL and external intent.
- `README.md`, `PRIVACY.md`, `CHANGELOG.md`, `docs/`, and `fastlane/metadata/android/` own the public English-first release content.

---

### Task 1: Keep lightning across radar base layers

**Files:**
- Modify: `app/src/main/assets/radar.html`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/ui/RadarScreenTest.kt`

**Interfaces:**
- Consumes: the existing `rain`, `clouds`, and `lightning` controls and ČHMÚ URL builders.
- Produces: independent `baseLayer: "rain" | "clouds"` and `lightningVisible: Boolean` state.

- [ ] **Step 1: Record the current failure**

Install the current debug APK on a dedicated API 29 emulator. Open radar, enable lightning, select clouds, and capture the UI tree, screenshot, and Logcat. The expected RED evidence is that the lightning control or overlay disappears.

- [ ] **Step 2: Add a deterministic layer-state self-test**

Add a pure JavaScript helper and cover both base layers:

```javascript
function layerVisibility(baseLayer, lightningVisible) {
  return {
    rain: baseLayer === 'rain',
    clouds: baseLayer === 'clouds',
    lightning: lightningVisible
  };
}

function layerStateSelfTest() {
  var clouds = layerVisibility('clouds', true);
  if (!clouds.clouds || !clouds.lightning || clouds.rain) {
    throw new Error('Radar layer-state self-test failed');
  }
}
```

Call `layerStateSelfTest()` beside the existing URL and dictionary self-tests.

- [ ] **Step 3: Render lightning for both base layers**

Keep the lightning button visible in `setLayer`. In `showFrame`, derive the lightning URL from the active frame date for both radar and cloud frames. Hide only the strike image when its load fails.

Do not change `radarUrl`, `satelliteUrl`, `nowcastUrl`, probe timing, or animation timing.

- [ ] **Step 4: Extend the Kotlin contract test**

Keep `localizedRadarUrl` coverage and add a source-contract assertion that reads the asset and confirms the independent cloud-plus-lightning state and the lowercase `nowcast` token.

Use the repository-relative file resolved from `System.getProperty("user.dir")`. Assert the file exists before reading it.

- [ ] **Step 5: Run focused and full checks**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cz.majkey.pocasicesko.ui.RadarScreenTest --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
git diff --check
```

- [ ] **Step 6: Verify live radar behavior**

On the dedicated API 29 emulator, verify measured rain with lightning, clouds with lightning, lightning off and on over clouds, return to rain, animation, and `nowcast +60 min`. Confirm that a missing strike image does not display a broken image marker.

- [ ] **Step 7: Commit**

```powershell
git add -- app/src/main/assets/radar.html app/src/test/java/cz/majkey/pocasicesko/ui/RadarScreenTest.kt
git commit -m "fix(radar): keep lightning across layers"
```

---

### Task 2: Show 20 future hours and expand daily rows

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/ui/ForecastDayTest.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-cs/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`

**Interfaces:**
- Produces: `internal fun upcomingHours(hourly: List<HourlyWeather>, currentHour: String, limit: Int = 20): List<HourlyWeather>`.
- Preserves: `hourlyForDay`, `formatDay`, `formatFullDay`, and the day-detail sheet.

- [ ] **Step 1: Write the failing hourly-selection test**

Add a test with 24 ordered hours and assert that `upcomingHours` skips past hours and returns exactly 20 items beginning at the requested hour.

```kotlin
@Test
fun selectsTwentyHoursFromCurrentHour() {
    val hours = (0..23).map { hour("2026-08-24T%02d:00".format(it)) }
    val selected = upcomingHours(hours, "2026-08-24T03", 20)
    assertEquals(20, selected.size)
    assertEquals("2026-08-24T03:00", selected.first().time)
    assertEquals("2026-08-24T22:00", selected.last().time)
}
```

- [ ] **Step 2: Run the test RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cz.majkey.pocasicesko.ui.ForecastDayTest --console=plain
```

Expected: Kotlin compilation fails because `upcomingHours` does not exist.

- [ ] **Step 3: Add the pure selector**

Implement:

```kotlin
internal fun upcomingHours(
    hourly: List<HourlyWeather>,
    currentHour: String,
    limit: Int = 20,
): List<HourlyWeather> = hourly.dropWhile { it.time.take(13) < currentHour }.take(limit)
```

- [ ] **Step 4: Replace the six-column panel**

Use one `rememberScrollState` for a fixed-width 20-hour content row. Each hour gets enough width for time, icon, temperature, and precipitation. Draw the existing temperature line across the same scrollable width.

Use `stringResource(R.string.next_hours, 20)` for the section title. Add this exact English fallback string:

```xml
<string name="next_hours">Next %1$d hours</string>
```

Add grammatically correct Czech, German, Spanish, and French values with the same `%1$d` argument.

- [ ] **Step 5: Expand each 14-day row**

Increase the row height and add the localized condition label, precipitation probability, and wind speed. Keep the entire row as one 48 dp or larger click target. Keep low and high temperatures aligned at the trailing edge.

- [ ] **Step 6: Run checks**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cz.majkey.pocasicesko.ui.ForecastDayTest --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
git diff --check
```

- [ ] **Step 7: Verify live forecast behavior**

On API 29 and API 35 emulators, count 20 hourly items, scroll to item 20, open the first, seventh, and fourteenth daily rows, and confirm that a complete day shows 24 hourly entries. Check English and Czech layouts.

- [ ] **Step 8: Commit**

```powershell
git add -- app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt app/src/test/java/cz/majkey/pocasicesko/ui/ForecastDayTest.kt app/src/main/res/values/strings.xml app/src/main/res/values-cs/strings.xml app/src/main/res/values-de/strings.xml app/src/main/res/values-es/strings.xml app/src/main/res/values-fr/strings.xml
git commit -m "feat(forecast): add 20-hour outlook"
```

---

### Task 3: Reproduce and fix hosted widget resize

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/widget/WidgetSettings.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/widget/WidgetSettingsTest.kt`
- Create: `app/src/main/res/layout/widget_adaptive.xml`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/widget/WeatherWidgetProvider.kt`
- Modify: `app/src/main/res/xml/weather_widget_info.xml`
- Modify: `app/src/main/res/xml-v31/weather_widget_info.xml`
- Delete: `app/src/main/res/layout/widget_compact.xml`
- Delete: `app/src/main/res/layout/widget_wide.xml`

**Interfaces:**
- Produces: `WidgetSize`, `widgetSize(minWidth: Int, minHeight: Int): WidgetSize`, and one stable layout with every provider view ID.
- Preserves: existing per-widget theme and visibility values.

- [ ] **Step 1: Reproduce the user-reported host failure before editing**

Create dedicated `ALADIN_API_29_QA` and `ALADIN_API_35_QA` AVDs from installed SDK images. Install the current APK, place a real widget through the launcher, and resize it narrow, wide, tall, full width, and narrow again.

Capture `AppWidgetHost`, `RemoteViews`, and application Logcat when the launcher shows `Error loading widget`. Record the failing widget ID, options bundle, chosen layout, and exception. Do not start implementation until the failure or a clearly documented non-reproduction is recorded.

- [ ] **Step 2: Write size-boundary tests**

```kotlin
@Test
fun classifiesEverySupportedWidgetSize() {
    assertEquals(WidgetSize.COMPACT, widgetSize(110, 40))
    assertEquals(WidgetSize.STANDARD, widgetSize(180, 80))
    assertEquals(WidgetSize.TALL, widgetSize(180, 120))
    assertEquals(WidgetSize.WIDE, widgetSize(320, 100))
}
```

- [ ] **Step 3: Run the focused test RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cz.majkey.pocasicesko.widget.WidgetSettingsTest --console=plain
```

Expected: compilation fails because the size contract does not exist.

- [ ] **Step 4: Create one adaptive hierarchy**

Create `widget_adaptive.xml` with every ID used by the provider. Put optional blocks in containers that can be set to `GONE`. Keep the root ID `widget_root` and include the city, temperature, clock, date, icon, condition, range, hourly, precipitation, wind, humidity, update time, and background image IDs.

Point both widget metadata files to `widget_adaptive`. Set `resizeMode="horizontal|vertical"`. Remove the API 31 `maxResizeHeight="100dp"` ceiling.

- [ ] **Step 5: Add guarded provider rendering**

Split `update` into a public guard and a private renderer:

```kotlin
fun update(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
    runCatching { render(context, manager, appWidgetId) }
        .onFailure { error ->
            Log.e("ALADINWidget", "Widget $appWidgetId render failed", error)
            manager.updateAppWidget(appWidgetId, fallbackViews(AppLocale.localized(context)))
        }
}
```

The fallback uses the same adaptive layout and touches only `widget_root`, `widget_temperature`, and `widget_city`.

- [ ] **Step 6: Apply size classes with visibility**

Read both `OPTION_APPWIDGET_MIN_WIDTH` and `OPTION_APPWIDGET_MIN_HEIGHT`. Use `widgetSize` to show or hide blocks without changing the layout resource.

- [ ] **Step 7: Run checks**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cz.majkey.pocasicesko.widget.WidgetSettingsTest --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
git diff --check
```

- [ ] **Step 8: Verify real hosted resize**

Repeat the exact pre-fix resize sequence on both dedicated emulators. The launcher must never show `Error loading widget`. Force-stop and restart both the app and launcher, then repeat one wide-to-compact resize.

- [ ] **Step 9: Commit**

```powershell
git add -- app/src/main/java/cz/majkey/pocasicesko/widget/WidgetSettings.kt app/src/test/java/cz/majkey/pocasicesko/widget/WidgetSettingsTest.kt app/src/main/res/layout/widget_adaptive.xml app/src/main/java/cz/majkey/pocasicesko/widget/WeatherWidgetProvider.kt app/src/main/res/xml/weather_widget_info.xml app/src/main/res/xml-v31/weather_widget_info.xml app/src/main/res/layout/widget_compact.xml app/src/main/res/layout/widget_wide.xml
git commit -m "fix(widget): stabilize resize rendering"
```

---

### Task 4: Add the customizable widget renderer

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/widget/WidgetBackground.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/widget/WidgetSettings.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/widget/WeatherWidgetProvider.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/widget/WidgetSettingsTest.kt`

**Interfaces:**
- Produces: `WidgetBackgroundMode`, `WidgetAlignment`, expanded `WidgetSettings`, validated hex colors, and bounded background rendering.
- Consumes: the stable adaptive hierarchy from Task 3.

- [ ] **Step 1: Write failing settings tests**

Cover defaults, per-widget isolation, invalid enum fallback, valid `#RRGGBB` and `#AARRGGBB`, invalid color fallback, text scale clamping, and size-bounded background dimensions.

Use these model defaults:

```kotlin
data class WidgetSettings(
    val backgroundMode: WidgetBackgroundMode = WidgetBackgroundMode.AUTOMATIC,
    val backgroundStart: String = "#0C1922",
    val backgroundEnd: String = "#28758D",
    val primaryColor: String = "#FFFFFFFF",
    val secondaryColor: String = "#CCFFFFFF",
    val accentColor: String = "#FF66C9DF",
    val opacity: Int = 100,
    val textScale: Int = 100,
    val alignment: WidgetAlignment = WidgetAlignment.LEFT,
    val customLabel: String = "",
    val imageUri: String = "",
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val showLocation: Boolean = true,
    val showTemperature: Boolean = true,
    val showIcon: Boolean = true,
    val showCondition: Boolean = true,
    val showRange: Boolean = true,
    val showHourly: Boolean = true,
    val showPrecipitation: Boolean = false,
    val showWind: Boolean = false,
    val showHumidity: Boolean = false,
    val showUpdatedAt: Boolean = false,
)
```

- [ ] **Step 2: Run the focused tests RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cz.majkey.pocasicesko.widget.WidgetSettingsTest --console=plain
```

- [ ] **Step 3: Implement validated persistence**

Keep ID-prefixed preference keys. Replace `WidgetTheme` with `WidgetBackgroundMode`. When the new background key is absent, migrate the existing `AUTOMATIC`, `LIGHT`, `DARK`, or `TRANSPARENT` theme value to its matching background mode. Normalize the custom label with `trim().take(40)`. Clamp opacity to 0 through 100 and text scale to 80 through 140. Accept only six-digit or eight-digit hexadecimal colors.

When `onDeleted` runs, remove every key for only the deleted widget ID.

- [ ] **Step 4: Store the required neutral weather metrics**

Extend the existing widget cache with current precipitation probability, wind speed, humidity, and update epoch. Store numbers only. Do not store formatted or translated strings.

- [ ] **Step 5: Render bounded backgrounds**

`WidgetBackground.kt` must:

- read a persisted content URI only when `backgroundMode == CUSTOM_IMAGE`;
- calculate an image sample size before decoding;
- crop or scale into a bitmap no larger than 512 by 256;
- apply configured opacity and a color overlay;
- recycle intermediate bitmaps when safe;
- return the solid or gradient fallback when the URI cannot be read.

- [ ] **Step 6: Apply all field settings**

The provider reads `WidgetSettings` once. Apply colors, text scale, alignment, label, and field visibility to the adaptive layout. A size class can hide a field that does not fit even when the user enabled it. Never show a field that the user disabled.

- [ ] **Step 7: Run checks**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cz.majkey.pocasicesko.widget.WidgetSettingsTest --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
git diff --check
```

- [ ] **Step 8: Commit**

```powershell
git add -- app/src/main/java/cz/majkey/pocasicesko/widget/WidgetBackground.kt app/src/main/java/cz/majkey/pocasicesko/widget/WidgetSettings.kt app/src/main/java/cz/majkey/pocasicesko/widget/WeatherWidgetProvider.kt app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt app/src/test/java/cz/majkey/pocasicesko/widget/WidgetSettingsTest.kt
git commit -m "feat(widget): add adaptive customization"
```

---

### Task 5: Build the widget editor and custom-image flow

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/widget/WidgetEditorScreen.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/widget/WidgetConfigActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-cs/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`

**Interfaces:**
- Consumes: `WidgetSettings`, color validation, and background decoding from Task 4.
- Produces: one scrollable editor with a provider-aligned preview and persisted document URI.

- [ ] **Step 1: Add the document picker lifecycle**

Register `ActivityResultContracts.OpenDocument()` for `image/*`. On result, call `takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)`. Save the URI string only in the current editor state. Commit it to preferences only when the user taps **Apply**.

- [ ] **Step 2: Move Compose editor code into `WidgetEditorScreen.kt`**

Use `LazyColumn` so all controls fit on small API 29 screens. Keep `WidgetConfigActivity` responsible for the widget ID, result contract, image picker, save, provider update, and finish.

- [ ] **Step 3: Add localized editor controls**

Add controls for background mode, two background colors, primary, secondary, and accent color, opacity, text scale, alignment, custom label, image select/remove, and every field toggle from `WidgetSettings`.

Every hexadecimal field shows a localized validation error before **Apply** becomes enabled.

- [ ] **Step 4: Share preview interpretation**

The preview consumes the same settings values, normalized colors, alignment, visibility rules, and custom image loader as the provider. Do not duplicate a second default table.

- [ ] **Step 5: Run checks**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
git diff --check
```

- [ ] **Step 6: Verify two independent widgets**

On API 29, create an English widget with a solid background, custom colors, centered text, and selected fields. On API 35, create a French widget with a custom image, 60 percent opacity, 120 percent text, right alignment, and different fields.

Resize both through all supported sizes. Restart the app and launcher. Confirm persistence and independence. Remove access to the French image and confirm the configured color fallback without `Error loading widget`.

- [ ] **Step 7: Commit**

```powershell
git add -- app/src/main/java/cz/majkey/pocasicesko/widget/WidgetEditorScreen.kt app/src/main/java/cz/majkey/pocasicesko/widget/WidgetConfigActivity.kt app/src/main/res/values/strings.xml app/src/main/res/values-cs/strings.xml app/src/main/res/values-de/strings.xml app/src/main/res/values-es/strings.xml app/src/main/res/values-fr/strings.xml
git commit -m "feat(widget): add custom editor"
```

---

### Task 6: Add optional Buy Me a Coffee support

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/ui/Support.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/ui/SupportTest.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/SettingsSheet.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherApp.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-cs/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`

**Interfaces:**
- Produces: `internal const val SUPPORT_URL = "https://www.buymeacoffee.com/majkey"`.
- Produces: `supportIntent(): Intent` with `ACTION_VIEW` and the exact HTTPS URI.

- [ ] **Step 1: Write the failing URL test**

```kotlin
@Test
fun usesPublishedBuyMeACoffeePage() {
    assertEquals("https://www.buymeacoffee.com/majkey", SUPPORT_URL)
}
```

- [ ] **Step 2: Run the focused test RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cz.majkey.pocasicesko.ui.SupportTest --console=plain
```

- [ ] **Step 3: Add the About section**

Place **About** after the language list. Add **Support this app -> Buy Me a Coffee** as a full-width 48 dp or larger row. Nearby localized copy states that support is optional and grants no feature or priority.

- [ ] **Step 4: Handle the external intent**

Pass `onSupport` from `WeatherApp`. Launch the external HTTPS intent. Catch `ActivityNotFoundException` and show the localized `support_unavailable` message inside the app.

- [ ] **Step 5: Run checks**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cz.majkey.pocasicesko.ui.SupportTest --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
git diff --check
```

- [ ] **Step 6: Verify external routing**

On the dedicated emulator, open English and Czech settings and activate the support row. Confirm from the resolved intent that Android receives exactly `https://www.buymeacoffee.com/majkey`. Do not accept browser consent. Disable available browser handlers in the disposable emulator and verify the localized failure path.

- [ ] **Step 7: Commit**

```powershell
git add -- app/src/main/java/cz/majkey/pocasicesko/ui/Support.kt app/src/test/java/cz/majkey/pocasicesko/ui/SupportTest.kt app/src/main/java/cz/majkey/pocasicesko/ui/SettingsSheet.kt app/src/main/java/cz/majkey/pocasicesko/ui/WeatherApp.kt app/src/main/res/values/strings.xml app/src/main/res/values-cs/strings.xml app/src/main/res/values-de/strings.xml app/src/main/res/values-es/strings.xml app/src/main/res/values-fr/strings.xml
git commit -m "feat(settings): add optional support link"
```

---

### Task 7: Publish English-first repository and Play metadata

**Files:**
- Modify: `README.md`
- Modify: `PRIVACY.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/index.html`
- Modify: `docs/google-play-submission.md`
- Modify: remaining public Markdown under `docs/`
- Create: `fastlane/metadata/android/en-US/`
- Create: `fastlane/metadata/android/de-DE/`
- Create: `fastlane/metadata/android/es-ES/`
- Create: `fastlane/metadata/android/fr-FR/`
- Modify: `fastlane/metadata/android/cs-CZ/`

**Interfaces:**
- Consumes: final app UI and screenshots from Tasks 1 through 6.
- Produces: English default repository content, five Play listings, and the GitHub Pages privacy URL.

- [ ] **Step 1: Replace stale identity and Czech repository prose**

Use `ALADIN weather`, `com.majkeylab.weatheraladin`, `Majkey25/ALADIN-weather`, and `https://majkey25.github.io/ALADIN-weather/` everywhere. Make every public GitHub and Pages sentence English. Keep localized Play listing files in their target languages.

- [ ] **Step 2: Document the final feature set and support link**

Document independent lightning, the 20-hour panel, 14 daily details, advanced per-widget settings, custom-image privacy, Android 10+, and the optional Buy Me a Coffee external link with no entitlement.

- [ ] **Step 3: Create five complete Play listings**

Each locale contains `title.txt`, `short_description.txt`, `full_description.txt`, the versionCode `3` changelog, icon, feature graphic, and final phone screenshots. Keep Play limits:

- title: at most 30 characters;
- short description: at most 80 characters;
- full description: at most 4,000 characters.

- [ ] **Step 4: Capture final emulator screenshots**

Capture English phone screenshots for the default listing. Capture translated screenshots only where visible text changes the store image. Do not include emulator chrome, accounts, notifications, or unrelated apps.

- [ ] **Step 5: Validate docs and metadata**

Run repository scans for `Pocasi-Cesko`, `Počasí Česko`, the old package, old Pages URL, and Czech prose in public English files. Validate the Play text character counts and verify every linked local image exists.

Run the full Android checks once after resource and screenshot updates:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
git diff --check
```

- [ ] **Step 6: Commit**

```powershell
git add -- README.md PRIVACY.md CHANGELOG.md docs fastlane/metadata/android
git commit -m "docs: publish ALADIN weather metadata"
```

---

### Task 8: Verify and publish `v0.2.0-beta.2`

**Files:**
- No production source changes.
- Local outputs: signed APK, signed AAB, checksums, screenshots, and QA reports.

**Interfaces:**
- Consumes: the reviewed commits from Tasks 1 through 7.
- Produces: PR, merged release commit, GitHub Pages, GitHub release, and a prepared Play Console app entry.

- [ ] **Step 1: Run final local gates**

Run:

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --console=plain
git diff --check
git status --short
```

Verify the APK and AAB package, versionCode, versionName, signatures, certificate fingerprint, and SHA-256 hashes without printing signing secrets.

- [ ] **Step 2: Run final dedicated-emulator acceptance**

On API 29 and API 35, verify forecast, all radar layers, English and Czech system-language behavior, search and favorites, day detail, support routing, two independently configured widgets, custom-image fallback, and every resize class.

- [ ] **Step 3: Run broad final code review**

Review the full feature-branch diff from merge base `9337fa9`. Fix every Critical and Important finding through the original task implementer and scoped re-review.

- [ ] **Step 4: Push the feature branch and open a PR**

Push `feat/localization/24-08-2026`, open an English PR against `main`, and include the specs, verification evidence, screenshots, package migration warning, and release checklist. Wait for GitHub Actions to pass.

- [ ] **Step 5: Merge and publish GitHub assets**

Merge only after green CI and final review. Publish `v0.2.0-beta.2` with signed APK and AAB assets plus SHA-256 checksums. Verify the public release page, fresh asset downloads, hashes, and signatures.

- [ ] **Step 6: Verify GitHub Pages**

Confirm that `https://majkey25.github.io/ALADIN-weather/` returns HTTP 200 and shows the English privacy policy, correct package, support disclosure, and current repository links.

- [ ] **Step 7: Prepare Play Console**

After action-time confirmation, create or complete the `ALADIN weather` Play app with package `com.majkeylab.weatheraladin`. Fill the app-access, ads, content rating, target audience, data safety, privacy policy, store settings, five listings, screenshots, and internal testing release fields. Stop before any unconfirmed final submission or rollout.

- [ ] **Step 8: Reclaim QA resources**

List active ADB targets and AVD processes. Stop only emulator serials. Never send a cleanup command to the physical device. Resolve and verify the exact absolute paths for `ALADIN_API_29_QA` and `ALADIN_API_35_QA`, then delete only those dedicated AVDs and their temporary captures. Remove an Android system image only if no remaining AVD uses it.

Keep the local project, git history, signing key, signed release artifacts, and this Codex task.

- [ ] **Step 9: Record the release handoff**

Record final URLs, commit and tag, CI run, hashes, certificate fingerprint, Pages state, Play Console state, emulator cleanup targets, and reclaimed bytes in the Task 8 report.
