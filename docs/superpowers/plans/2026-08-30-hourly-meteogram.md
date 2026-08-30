# Hourly meteogram implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the temperature-only 24-hour graph with one accessible meteogram that also shows precipitation, day and night, and wind.

**Architecture:** Add one pure geometry module with no Android state or network access. `ForecastScreen.kt` keeps ownership of scrolling, labels, localization, and unit formatting. Compose Canvas draws the geometry with platform primitives and no new dependency.

**Tech stack:** Kotlin, Jetpack Compose Canvas, Material 3, JUnit 4, Android Gradle Plugin.

---

### Task 1: Define deterministic chart geometry

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/ui/HourlyMeteogram.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/ui/HourlyMeteogramTest.kt`

- [ ] **Step 1: Write the failing geometry tests**

Add tests for 24 positions, constant temperature, dry precipitation, probability alpha, day/night state, one hour, and invalid dimensions. Use real `HourlyWeather` records.

```kotlin
@Test
fun mapsTwentyFourHoursToColumnCentres() {
    val geometry = calculateHourlyMeteogram(
        hours = (0 until 24).map(::hour),
        width = 1_632f,
        height = 112f,
        columnWidth = 68f,
    )

    assertEquals(24, geometry.hours.size)
    assertEquals(34f, geometry.hours.first().centerX, 0.001f)
    assertEquals(1_598f, geometry.hours.last().centerX, 0.001f)
}

@Test
fun keepsConstantTemperatureFiniteAndDryBarsAtZero() {
    val geometry = calculateHourlyMeteogram(
        hours = listOf(hour(0), hour(1)),
        width = 136f,
        height = 112f,
        columnWidth = 68f,
    )

    assertTrue(geometry.hours.all { it.temperatureY.isFinite() })
    assertTrue(geometry.hours.all { it.precipitationHeight == 0f })
}

@Test
fun rejectsInvalidDimensions() {
    assertThrows(IllegalArgumentException::class.java) {
        calculateHourlyMeteogram(listOf(hour(0)), 0f, 112f, 68f)
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.ui.HourlyMeteogramTest" --console=plain
```

Expected: compilation fails because `calculateHourlyMeteogram` and its immutable geometry types do not exist.

- [ ] **Step 3: Implement the minimum pure geometry**

Define immutable output records and one function:

```kotlin
internal data class MeteogramHourGeometry(
    val centerX: Float,
    val temperatureY: Float,
    val precipitationHeight: Float,
    val precipitationAlpha: Float,
    val isDay: Boolean,
)

internal data class HourlyMeteogramGeometry(
    val hours: List<MeteogramHourGeometry>,
)

internal fun calculateHourlyMeteogram(
    hours: List<HourlyWeather>,
    width: Float,
    height: Float,
    columnWidth: Float,
): HourlyMeteogramGeometry {
    require(width > 0f && height > 0f && columnWidth > 0f)
    if (hours.isEmpty()) return HourlyMeteogramGeometry(emptyList())
    val temperatures = hours.map(HourlyWeather::temperature)
    val minimum = temperatures.min()
    val range = (temperatures.max() - minimum).coerceAtLeast(1.0)
    val maximumRain = hours.maxOf(HourlyWeather::precipitation).coerceAtLeast(0.1)
    return HourlyMeteogramGeometry(
        hours.mapIndexed { index, hour ->
            MeteogramHourGeometry(
                centerX = columnWidth * index + columnWidth / 2f,
                temperatureY = height * (0.54f - ((hour.temperature - minimum) / range).toFloat() * 0.42f),
                precipitationHeight = height * 0.30f * (hour.precipitation / maximumRain).toFloat().coerceIn(0f, 1f),
                precipitationAlpha = (0.30f + hour.precipitationProbability / 100f * 0.70f).coerceIn(0.30f, 1f),
                isDay = hour.isDay,
            )
        },
    )
}
```

Keep all values finite. Do not add chart configuration objects or interfaces.

- [ ] **Step 4: Run focused tests and static checks**

Run the focused JUnit command from Step 2. Then run:

```powershell
.\gradlew.bat :app:lintDebug --console=plain
```

Expected: focused tests and Lint pass.

- [ ] **Step 5: Commit the geometry slice**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/ui/HourlyMeteogram.kt app/src/test/java/cz/majkey/pocasicesko/ui/HourlyMeteogramTest.kt
git commit -m "feat(ui): add meteogram geometry"
```

### Task 2: Draw the combined meteogram

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/HourlyMeteogram.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/ui/HourlyMeteogramTest.kt`

- [ ] **Step 1: Write RED source-contract tests**

Add a narrow test that verifies the panel uses the new component and keeps 24 hours:

```kotlin
@Test
fun forecastPanelUsesCombinedMeteogram() {
    val source = File(
        System.getProperty("user.dir"),
        "src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt",
    ).readText()

    assertTrue(source.contains("HourlyMeteogram("))
    assertFalse(source.contains("HourlyTemperatureLine("))
}
```

Run the focused test and verify that it fails before editing `ForecastScreen.kt`.

- [ ] **Step 2: Add the Canvas component**

In `HourlyMeteogram.kt`, add one composable. It must:

- draw one low-alpha rectangle for each day or night column;
- draw precipitation bars from the lower chart baseline;
- draw one temperature path and small point markers;
- call `calculateHourlyMeteogram` once per draw;
- use `clearAndSetSemantics { }` because text rows expose the data;
- return without drawing a line when fewer than two temperatures exist.

Use `drawRect`, `drawLine`, `drawPath`, and `drawCircle`. Do not add animation or gesture state.

- [ ] **Step 3: Replace the temperature-only Canvas**

In `HourlyGraphPanel`:

- replace `HourlyTemperatureLine` with `HourlyMeteogram(hours, itemWidth, Modifier.height(112.dp))`;
- remove `HourlyTemperatureLine`;
- keep the time, weather icon, temperature, and precipitation probability rows;
- show precipitation amount beside probability only when `hour.precipitation > 0`;
- add a wind row with a north arrow rotated by `(hour.windDirection + 180) % 360` and `units.windSpeed(hour.windSpeed)`;
- keep every hourly column at 68 dp and the complete content at `itemWidth * hours.size`.

The wind arrow points where the air moves. The direction label remains available through the semantic description.

- [ ] **Step 4: Add one accessible description per displayed hour**

Create a pure formatter that receives already-localized labels and formatted unit strings:

```kotlin
internal fun hourlyAccessibilityDescription(
    time: String,
    condition: String,
    temperature: String,
    precipitationProbability: Int,
    precipitation: String,
    wind: String,
    direction: String,
): String = "$time, $condition, $temperature, $precipitationProbability%, $precipitation, $wind, $direction"
```

Place the visual rows and a same-size semantic overlay inside one `Box`. Clear semantics from the
visual rows. The overlay is a `Row` of 68 dp boxes, and each box exposes the corresponding hourly
description through `clearAndSetSemantics`. Decorative icons and Canvas marks must not add duplicate
announcements.

- [ ] **Step 5: Verify focused UI behavior**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.ui.HourlyMeteogramTest" --tests "cz.majkey.pocasicesko.ui.ForecastDayTest" --console=plain
.\gradlew.bat :app:lintDebug :app:assembleDebug --console=plain
```

Expected: all commands pass and `app-debug.apk` builds.

- [ ] **Step 6: Commit the UI slice**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/ui/HourlyMeteogram.kt app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt app/src/test/java/cz/majkey/pocasicesko/ui/HourlyMeteogramTest.kt
git commit -m "feat(ui): add 24-hour meteogram"
```

### Task 3: Verify the real phone and release build

**Files:**
- Modify only if QA exposes a reproduced defect.

- [ ] **Step 1: Run the full Android gate**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install only on the authorised Huawei**

```powershell
adb -s BQLDU19927002646 install -r app\build\outputs\apk\debug\app-debug.apk
```

Verify the installed package is `com.majkeylab.weatheraladin.debug` before launch.

- [ ] **Step 3: Test four live scenarios**

On the Huawei, verify:

1. Prague in English and Metric. Scroll from **Now** through the 24th hour.
2. A forecast containing rain. Confirm bars, probability, and amount agree with the text values.
3. Czech and Imperial. Confirm translated labels, Fahrenheit, inches, and mph.
4. Offline cached forecast. Confirm the meteogram still renders without a crash.

Capture one top screenshot, one late-hour screenshot, the UI hierarchy, and filtered app logcat.

- [ ] **Step 4: Run hostile diff review**

Check correctness, readability, architecture, security, performance, contrast, 48 dp touch targets,
large text clipping, duplicate TalkBack announcements, dead imports, and unrelated changes. Fix every
required finding through a new failing test.

- [ ] **Step 5: Push and verify CI**

```powershell
git diff --check
git status --short
git push origin main
gh run watch --exit-status
```

Do not create a GitHub release or Google Play upload. The existing calibration and commercial-data
gates remain independent blockers.

- [ ] **Step 6: Clean test resources**

Stop the debug app and Gradle daemon. Remove only screenshots and UI trees created by this plan.
Keep the installed app, repository, build outputs, research cache, and user data.
