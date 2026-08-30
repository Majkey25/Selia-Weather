# Expandable hourly details implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user expand one hour in the full-day list and inspect all available hourly metrics without opening another screen.

**Architecture:** `ForecastScreen.kt` keeps the selected-hour state. A new `HourlyDetails.kt` file owns the pure toggle and the compact two-column metric block. The feature reuses `HourlyWeather`, `WeatherUnitFormatter`, existing translated metric labels, and Material icons.

**Tech stack:** Kotlin, Jetpack Compose Material 3, JUnit 4, Android resources.

---

### Task 1: Define expansion state and localized labels

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/ui/HourlyDetails.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/ui/HourlyDetailsTest.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-cs/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`

- [ ] **Step 1: Write RED state tests**

```kotlin
@Test
fun opensClosesAndSwitchesHours() {
    assertEquals("2026-08-30T12:00", toggleExpandedHour(null, "2026-08-30T12:00"))
    assertNull(toggleExpandedHour("2026-08-30T12:00", "2026-08-30T12:00"))
    assertEquals(
        "2026-08-30T13:00",
        toggleExpandedHour("2026-08-30T12:00", "2026-08-30T13:00"),
    )
}
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.ui.HourlyDetailsTest" --console=plain
```

Expected: compilation fails because `toggleExpandedHour` does not exist.

- [ ] **Step 2: Implement the pure state transition**

```kotlin
internal fun toggleExpandedHour(current: String?, clicked: String): String? {
    require(clicked.isNotBlank())
    return if (current == clicked) null else clicked
}
```

- [ ] **Step 3: Add state labels to every locale**

Add `hour_expanded` and `hour_collapsed` to all five catalogs. Use these values:

```xml
<!-- English -->
<string name="hour_expanded">Expanded</string>
<string name="hour_collapsed">Collapsed</string>

<!-- Czech -->
<string name="hour_expanded">Rozbaleno</string>
<string name="hour_collapsed">Sbaleno</string>

<!-- German -->
<string name="hour_expanded">Ausgeklappt</string>
<string name="hour_collapsed">Eingeklappt</string>

<!-- Spanish -->
<string name="hour_expanded">Expandido</string>
<string name="hour_collapsed">Contraído</string>

<!-- French -->
<string name="hour_expanded">Développé</string>
<string name="hour_collapsed">Réduit</string>
```

- [ ] **Step 4: Verify state and locale completeness**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.ui.HourlyDetailsTest" --tests "cz.majkey.pocasicesko.locale.AppLocaleTest" --console=plain
```

Expected: both test classes pass.

- [ ] **Step 5: Commit the state slice**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/ui/HourlyDetails.kt app/src/test/java/cz/majkey/pocasicesko/ui/HourlyDetailsTest.kt app/src/main/res/values*/strings.xml
git commit -m "feat(ui): add hourly detail state"
```

### Task 2: Render the compact metric block

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/HourlyDetails.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/ui/HourlyDetailsTest.kt`

- [ ] **Step 1: Write RED integration tests**

Add source-contract checks that require `ExpandedHourDetails`, `stateDescription`, and remove the fixed
78 dp height from the hourly row. Add a pure availability test for optional values:

```kotlin
@Test
fun optionalMetricsAppearOnlyWhenPresent() {
    val kinds = availableHourMetricKinds(hour(apparentTemperature = 18.0, uvIndex = null))

    assertTrue(HourMetricKind.FEELS_LIKE in kinds)
    assertFalse(HourMetricKind.UV in kinds)
}
```

Run the focused test and verify RED before editing production code.

- [ ] **Step 2: Add metric availability types**

```kotlin
internal enum class HourMetricKind {
    FEELS_LIKE,
    PRECIPITATION,
    HUMIDITY,
    WIND_GUSTS,
    PRESSURE,
    LOW_CLOUDS,
    MIDDLE_CLOUDS,
    HIGH_CLOUDS,
    UV,
    VISIBILITY,
}

internal fun availableHourMetricKinds(hour: HourlyWeather): Set<HourMetricKind> = buildSet {
    if (hour.apparentTemperature != null) add(HourMetricKind.FEELS_LIKE)
    add(HourMetricKind.PRECIPITATION)
    add(HourMetricKind.HUMIDITY)
    if (hour.windGusts != null) add(HourMetricKind.WIND_GUSTS)
    add(HourMetricKind.PRESSURE)
    if (hour.cloudCoverLow != null) add(HourMetricKind.LOW_CLOUDS)
    if (hour.cloudCoverMid != null) add(HourMetricKind.MIDDLE_CLOUDS)
    if (hour.cloudCoverHigh != null) add(HourMetricKind.HIGH_CLOUDS)
    if (hour.uvIndex != null) add(HourMetricKind.UV)
    if (hour.visibilityMeters != null) add(HourMetricKind.VISIBILITY)
}
```

- [ ] **Step 3: Implement `ExpandedHourDetails`**

Build a `listOfNotNull` of localized label-value pairs. Always include precipitation amount, humidity,
and pressure. Use optional values only when present. Render `metrics.chunked(2)` as rows. Each metric
uses `Modifier.weight(1f)`, a 10 sp muted label, a 13 sp medium-weight value, and 8 dp vertical spacing.

Use these formatters:

```kotlin
units.temperature(value)
units.precipitation(value)
"${hour.humidity} %"
units.windSpeed(value)
units.pressure(value)
"$value %"
String.format(locale, "%.1f", value)
units.visibility(value)
```

Do not add a `Surface`, border, animation, or nested scroll container.

- [ ] **Step 4: Integrate expansion into each day page**

Inside the `HorizontalPager` page:

```kotlin
var expandedHourTime by remember(day.date) { mutableStateOf<String?>(null) }
```

For each hour, compute `expanded = expandedHourTime == hour.time`. Replace `.height(78.dp)` with
`.heightIn(min = 78.dp)`, add the existing ripple `clickable`, and set the localized
`stateDescription`. Add a decorative `ExpandMore` or `ExpandLess` icon after the temperature.

Below the summary text:

```kotlin
if (expanded) {
    ExpandedHourDetails(
        hour = hour,
        units = units,
        locale = locale,
        modifier = Modifier.padding(start = 81.dp, top = 12.dp, bottom = 8.dp),
    )
}
```

- [ ] **Step 5: Verify focused behavior**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.ui.HourlyDetailsTest" --tests "cz.majkey.pocasicesko.ui.ForecastDayTest" --console=plain
.\gradlew.bat :app:lintDebug :app:assembleDebug --console=plain
```

Expected: tests, Lint, and debug build pass.

- [ ] **Step 6: Commit the UI slice**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/ui/HourlyDetails.kt app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt app/src/test/java/cz/majkey/pocasicesko/ui/HourlyDetailsTest.kt
git commit -m "feat(ui): expand hourly weather details"
```

### Task 3: Run live and release verification

**Files:**
- Modify only when a reproduced QA defect requires a fix.

- [ ] **Step 1: Run the full Android gate**

```powershell
.\gradlew.bat --no-daemon --max-workers=4 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install on the authorised Huawei**

Install only `com.majkeylab.weatheraladin.debug` with serial `BQLDU19927002646`. Preserve app data.

- [ ] **Step 3: Verify four live scenarios**

1. Open and close one dry hour.
2. Switch directly from that hour to a rainy hour.
3. Page to the next day and confirm no hour remains expanded.
4. Verify English and Czech plus Metric and Imperial values.

Capture UI hierarchy, screenshots, and `AndroidRuntime` errors for the debug process.

- [ ] **Step 4: Review, document, and push**

Run hostile review, update `CHANGELOG.md`, verify `git diff --check`, push `main`, and wait for Android
CI. Do not create a release or Play upload while the existing data gates remain blocked.

- [ ] **Step 5: Clean resources**

Restore English and Metric. Stop the debug app and Gradle services. Remove only this plan's test
artifacts when filesystem policy permits. Keep the installed app, repository, build outputs, and
user data.
