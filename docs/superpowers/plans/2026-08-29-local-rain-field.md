# Local rain field implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a worldwide 24-hour spatial precipitation field and the missing high-value weather
details to Selia Vetra.

**Architecture:** `PrecipitationFieldRepository` requests 25 surrounding coordinates only when
the weather detail opens. A strict parser validates the multi-location response and calculates one
model consensus per cell and hour. `WeatherRepository` exposes one suspend method, and Compose
receives that method as a lambda. The main forecast request supplies the added point details.

**Tech stack:** Kotlin, Jetpack Compose Material 3, `HttpURLConnection`, `org.json`, Java time,
JUnit 4, Android SDK 36, and minSdk 29.

**Spec:** `docs/superpowers/specs/2026-08-29-local-rain-field-design.md`

## Global constraints

- Keep Android 10 as the minimum version.
- Add no dependency.
- Keep all calculations in canonical Metric units.
- Require at least three valid model values per spatial cell and hour.
- Do not average weather codes or precipitation types.
- Do not call forecast precipitation radar.
- Do not use Meteoblue code, assets, APIs, names, or its 7 by 7 format.
- Keep Open-Meteo Free limited to non-commercial development.
- Localise every new string in English, Czech, German, Spanish, and French.
- Write the failing test before each production change.
- Do not publish a GitHub release or Google Play build until the existing calibration and
  commercial-data gates pass.

---

### Task 1: Add the missing point forecast values

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherModels.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherParser.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`
- Test: `app/src/test/java/cz/majkey/pocasicesko/data/WeatherParserTest.kt`
- Test: `app/src/test/java/cz/majkey/pocasicesko/data/WeatherRepositoryTest.kt`

**Interfaces:**
- Produces optional properties on `CurrentWeather`, `HourlyWeather`, and `DailyWeather`.
- Keeps `WeatherParser.parseForecast(json, updatedAtEpochMillis)` unchanged.

- [ ] **Step 1: Write the failing request and parser tests**

Add assertions that `WeatherRepository.forecastUrl()` requests these exact hourly fields:

```kotlin
listOf(
    "uv_index",
    "freezing_level_height",
    "boundary_layer_height",
    "total_column_integrated_water_vapour",
    "lifted_index",
    "convective_inhibition",
    "soil_temperature_0cm",
    "soil_moisture_0_to_1cm",
    "showers",
).forEach { assertTrue(url.contains(it)) }
assertTrue(url.contains("uv_index_max"))
```

Extend the existing complete forecast fixture and assert the parsed values:

```kotlin
assertEquals(3.4, snapshot.current.uvIndex)
assertEquals(2_450.0, snapshot.current.freezingLevelHeightMeters)
assertEquals(820.0, snapshot.current.boundaryLayerHeightMeters)
assertEquals(18.2, snapshot.current.integratedWaterVapour)
assertEquals(-1.5, snapshot.current.liftedIndex)
assertEquals(42.0, snapshot.current.convectiveInhibition)
assertEquals(21.3, snapshot.current.soilTemperature0Cm)
assertEquals(0.24, snapshot.current.soilMoisture0To1Cm)
assertEquals(0.7, snapshot.current.showers)
assertEquals(5.2, snapshot.daily.first().uvIndexMax)
```

Add one fixture with `null` optional values and one with `"uv_index":"NaN"`. The first must parse
to `null`. The second must throw `JSONException`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "cz.majkey.pocasicesko.data.WeatherParserTest" --tests "cz.majkey.pocasicesko.data.WeatherRepositoryTest"
```

Expected: compilation fails because the typed properties do not exist, or the URL assertions fail.

- [ ] **Step 3: Add the optional typed properties**

Use these names and types:

```kotlin
val uvIndex: Double? = null
val freezingLevelHeightMeters: Double? = null
val boundaryLayerHeightMeters: Double? = null
val integratedWaterVapour: Double? = null
val liftedIndex: Double? = null
val convectiveInhibition: Double? = null
val soilTemperature0Cm: Double? = null
val soilMoisture0To1Cm: Double? = null
val showers: Double? = null
```

Add the same fields to `HourlyWeather`. Add `val uvIndexMax: Double? = null` to `DailyWeather`.

- [ ] **Step 4: Extend the request and parser**

Append the verified hourly identifiers to `CURRENT_VARIABLES`. Append `uv_index_max` to
`DAILY_VARIABLES`. Parse each value with the existing optional helpers. Update those helpers to
reject a present non-finite number:

```kotlin
private fun JSONObject.optionalFiniteDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return getDouble(name).also { value ->
        if (!value.isFinite()) throw JSONException("Hodnota $name není konečná.")
    }
}
```

Use the matching array helper for hourly and daily fields.

- [ ] **Step 5: Run focused and full unit tests**

Run the focused command from Step 2, then:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: both commands pass.

- [ ] **Step 6: Commit**

```text
feat(weather): add atmosphere and soil details
```

---

### Task 2: Define the spatial field geometry and models

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/PrecipitationFieldModels.kt`
- Test: `app/src/test/java/cz/majkey/pocasicesko/data/PrecipitationFieldModelsTest.kt`

**Interfaces:**
- Produces `PrecipitationFieldPoint`, `PrecipitationFieldCell`, `PrecipitationFieldFrame`,
  `PrecipitationField`, `PrecipitationKind`, and `precipitationFieldPoints(location)`.

- [ ] **Step 1: Write failing geometry and invariant tests**

Use this contract:

```kotlin
val points = precipitationFieldPoints(
    CzechLocation("Point", REGION_WORLD, 0.0, 0.0),
)

assertEquals(25, points.size)
assertEquals((0..4).flatMap { row -> (0..4).map { column -> row to column } },
    points.map { it.row to it.column })
assertEquals(0.0, points[12].offsetEastKm, 0.0)
assertEquals(0.0, points[12].offsetNorthKm, 0.0)
assertTrue(points.all { hypot(it.offsetEastKm, it.offsetNorthKm) <= 20.0 })
```

Repeat the call at longitude `179.99`, latitude `89.9`, and latitude `-89.9`. Assert finite
coordinates within valid WGS84 bounds and stable row and column order. Assert that a non-finite
centre throws `IllegalArgumentException`.

Add constructor tests that reject an invalid row, column, probability, agreement, contributor
count, negative amount, unsorted frames, or a frame with other than 25 cells.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "cz.majkey.pocasicesko.data.PrecipitationFieldModelsTest"
```

Expected: compilation fails because the spatial types do not exist.

- [ ] **Step 3: Add the immutable models**

Use this public shape inside the package:

```kotlin
internal enum class PrecipitationKind { DRY, RAIN, SNOW, MIXED, UNAVAILABLE }

internal data class PrecipitationFieldPoint(
    val row: Int,
    val column: Int,
    val latitude: Double,
    val longitude: Double,
    val offsetEastKm: Double,
    val offsetNorthKm: Double,
)

internal data class PrecipitationFieldCell(
    val point: PrecipitationFieldPoint,
    val precipitationMm: Double?,
    val rainMm: Double?,
    val showersMm: Double?,
    val snowfallCm: Double?,
    val probabilityPercent: Int?,
    val agreementPercent: Int?,
    val contributorCount: Int,
    val minimumMm: Double?,
    val maximumMm: Double?,
    val kind: PrecipitationKind,
)

internal data class PrecipitationFieldFrame(
    val validTime: Instant,
    val cells: List<PrecipitationFieldCell>,
)

internal data class PrecipitationField(val frames: List<PrecipitationFieldFrame>)
```

Put validation in `init` blocks. A valid `UNAVAILABLE` cell has null amounts, null percentages,
and zero contributors. Every other cell has finite non-negative amounts and at least three
contributors.

- [ ] **Step 4: Implement spherical point generation**

Use `GRID_OFFSETS_KM = doubleArrayOf(-14.0, -7.0, 0.0, 7.0, 14.0)` and
`EARTH_RADIUS_KM = 6_371.0088`. For each offset pair, compute distance with `hypot`, bearing with
`atan2(east, north)`, and the destination with the standard great-circle formula. Normalize
longitude to `[-180, 180]`.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 6: Commit**

```text
feat(weather): define local rain field
```

---

### Task 3: Parse and calculate the multi-model field

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/PrecipitationFieldParser.kt`
- Test: `app/src/test/java/cz/majkey/pocasicesko/data/PrecipitationFieldParserTest.kt`

**Interfaces:**
- Consumes `List<PrecipitationFieldPoint>` and `List<String>` model IDs.
- Produces `parsePrecipitationField(json, points, modelIds): PrecipitationField`.

- [ ] **Step 1: Write the failing golden test**

Generate a 25-item JSON list in the test. Give every item the same two epoch timestamps and these
model suffixes: `a`, `b`, `c`, and `missing`. For the centre cell at the first hour, use
precipitation `0.0`, `0.2`, `0.4`, and `null`.

Assert:

```kotlin
val field = parsePrecipitationField(json, points, listOf("a", "b", "c", "missing"))
val centre = field.frames.first().cells[12]

assertEquals(0.2, centre.precipitationMm)
assertEquals(67, centre.probabilityPercent)
assertEquals(67, centre.agreementPercent)
assertEquals(3, centre.contributorCount)
assertEquals(0.0, centre.minimumMm)
assertEquals(0.4, centre.maximumMm)
assertEquals(PrecipitationKind.RAIN, centre.kind)
```

Add one test for snow, mixed precipitation, all dry, fewer than three models, a negative value,
wrong units, a missing location, an incorrect `location_id`, mismatched timestamps, and more than
24 frames.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "cz.majkey.pocasicesko.data.PrecipitationFieldParserTest"
```

Expected: compilation fails because the parser does not exist.

- [ ] **Step 3: Implement strict response validation**

Implement:

```kotlin
internal fun parsePrecipitationField(
    json: String,
    points: List<PrecipitationFieldPoint>,
    modelIds: List<String>,
): PrecipitationField
```

Require exactly 25 response objects. Require `location_id == index` when the field is present and
require it for every index after zero. Require units `mm` for precipitation, rain, and showers,
`cm` for snowfall, and `unixtime` for time. Require identical sorted timestamps and 1 to 24 frames.

- [ ] **Step 4: Calculate one consensus per cell and hour**

Reuse the median rule from `ModelConsensus.kt`. Keep the new helper local unless extraction removes
real duplicate production code. Calculate wet probability, majority agreement, minimum, maximum,
and type exactly as the spec defines. Produce an `UNAVAILABLE` cell when fewer than three model
precipitation values remain.

- [ ] **Step 5: Run focused and full tests**

Run the command from Step 2, then `testDebugUnitTest`. Expected: PASS.

- [ ] **Step 6: Commit**

```text
feat(weather): parse local rain field
```

---

### Task 4: Fetch the field on demand

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/PrecipitationFieldRepository.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`
- Test: `app/src/test/java/cz/majkey/pocasicesko/data/PrecipitationFieldRepositoryTest.kt`

**Interfaces:**
- Produces `PrecipitationFieldRepository.fetch(location): PrecipitationField`.
- Produces `WeatherRepository.fetchPrecipitationField(location): PrecipitationField` as a suspend
  function.

- [ ] **Step 1: Write failing URL, success, and failure tests**

Inject `fetchText: (String) -> String` into the repository. Capture the URL and assert one request
with 25 comma-separated latitudes, 25 comma-separated longitudes, `forecast_hours=24`,
`timeformat=unixtime`, `timezone=GMT`, the four hourly variables, and the location-specific models.

Assert that a valid response returns 24 frames. Assert that an HTTP-style `IOException` and a
malformed response propagate to the caller. Assert that the bounded reader rejects a response
above `5_000_000` bytes.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "cz.majkey.pocasicesko.data.PrecipitationFieldRepositoryTest"
```

Expected: compilation fails because the repository does not exist.

- [ ] **Step 3: Implement the repository**

Use the existing `forecastApiModelsFor(location)` and `readLimited()`. Configure a 10-second
connect timeout, a 15-second read timeout, a 5 MB response limit, `Accept: application/json`, and
the existing Selia Vetra user-agent form. Always disconnect in `finally`.

Expose:

```kotlin
internal suspend fun fetch(location: CzechLocation): PrecipitationField =
    withContext(Dispatchers.IO) {
        val points = precipitationFieldPoints(location)
        val models = forecastApiModelsFor(location)
        parsePrecipitationField(fetchText(url(location, points, models)), points, models)
    }
```

- [ ] **Step 4: Wire `WeatherRepository`**

Create one private `PrecipitationFieldRepository` property and delegate through:

```kotlin
suspend fun fetchPrecipitationField(location: CzechLocation): PrecipitationField =
    precipitationFieldRepository.fetch(location)
```

Do not fetch the field from `fetchForecastBlocking()`.

- [ ] **Step 5: Run focused and full tests**

Run the command from Step 2, then `testDebugUnitTest`. Expected: PASS.

- [ ] **Step 6: Commit**

```text
feat(weather): fetch local rain field
```

---

### Task 5: Build the Local rain field UI

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/ui/LocalRainField.kt`
- Test: `app/src/test/java/cz/majkey/pocasicesko/ui/LocalRainFieldTest.kt`

**Interfaces:**
- Produces `LocalRainField(field, timezone, units, modifier)`.
- Produces pure `rainFieldColor(cell)` and `rainFieldCellDescription(...)` helpers.

- [ ] **Step 1: Write failing colour and description tests**

Assert the exact colour categories for unavailable, dry, `0.4`, `0.5`, `1.9`, `2.0`, `4.9`, and
`5.0 mm`. Assert that rain, snow, mixed, and centre descriptions include type, direction, distance,
probability, agreement, contributors, and selected interval.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "cz.majkey.pocasicesko.ui.LocalRainFieldTest"
```

Expected: compilation fails because the UI helpers do not exist.

- [ ] **Step 3: Implement the field without card spam**

Use a `Box` with a background `Canvas` for two rings and compass labels. Place the cells in a
five-column `Column` and `Row` grid above the canvas. Each cell uses `Modifier.sizeIn(minWidth =
44.dp, minHeight = 44.dp)`, a rounded square, a border, `selectable`, and its own semantics.

Use one `LazyRow` for the 24-hour selector. Default to the first frame. Keep selected cell state
inside the composable and default it to the centre cell. Show a snowflake text mark for snow and
mixed cells.

- [ ] **Step 4: Add the selected-cell summary and legend**

Use one compact text block, then one horizontal colour legend. Convert precipitation with
`WeatherUnitFormatter`. Keep the internal thresholds in millimetres.

- [ ] **Step 5: Run focused and full tests**

Run the command from Step 2, then `testDebugUnitTest`. Expected: PASS.

- [ ] **Step 6: Commit**

```text
feat(ui): add local rain field
```

---

### Task 6: Integrate loading and dense detail

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherApp.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherDetailScreen.kt`
- Modify: all five `app/src/main/res/values*/strings.xml` files
- Test: `app/src/test/java/cz/majkey/pocasicesko/ui/WeatherDetailScreenTest.kt`
- Test: `app/src/test/java/cz/majkey/pocasicesko/locale/AppLocaleTest.kt`

**Interfaces:**
- Passes `loadPrecipitationField: suspend (CzechLocation) -> PrecipitationField` from
  `WeatherRepository` to `WeatherDetailSheet`.

- [ ] **Step 1: Write failing hierarchy and localization tests**

Assert that `WeatherDetailScreen.kt` orders `AtAGlanceSection`, `LocalRainFieldSection`, current,
precipitation, wind, atmosphere, ground, sun, and Moon. Assert that every locale contains the new
keys. Test pure next-rain and maximum-probability helpers with wet, dry, and missing data.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "cz.majkey.pocasicesko.ui.WeatherDetailScreenTest" --tests "cz.majkey.pocasicesko.locale.AppLocaleTest"
```

Expected: the new symbols or strings are absent.

- [ ] **Step 3: Pass the loader lambda**

Add the lambda to `WeatherDestination`, `ForecastScreen`, and `WeatherDetailSheet`. Pass
`repository::fetchPrecipitationField` from `WeatherApp`. Do not pass the repository object into a
composable.

- [ ] **Step 4: Load only while the detail is open**

Use `produceState` keyed by location coordinates. Model loading, content, and error as a sealed
private state in `WeatherDetailScreen.kt`. Catch only `IOException` and `JSONException`. Add a
retry counter key. Coroutine cancellation must stop publishing stale results after dismissal.

- [ ] **Step 5: Add the information hierarchy**

Implement the four-value **At a glance** block. Add the field loading, content, partial, and error
states. Move the new atmosphere values into an **Atmosphere** section and the soil values into a
**Ground** section. Keep existing rows and dividers.

- [ ] **Step 6: Add all localized strings**

Add translations for the section titles, state messages, compass directions, rain types, selected
cell summary, model agreement, contributor count, UV, freezing level, boundary layer, integrated
water vapour, lifted index, convective inhibition, soil temperature, soil moisture, and showers.

- [ ] **Step 7: Run focused and full Android gates**

Run the focused command from Step 2, then:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Expected: PASS.

- [ ] **Step 8: Commit**

```text
feat(ui): integrate dense weather detail
```

---

### Task 7: Update disclosure and verify on hardware

**Files:**
- Modify: `README.md`
- Modify: `PRIVACY.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/index.html`
- Modify: `docs/google-play-submission.md`

**Interfaces:**
- Documents the 25-coordinate on-demand request and the non-commercial release boundary.

- [ ] **Step 1: Update the English public documentation**

State that opening **Weather details** sends a 5 by 5 set of nearby coordinates to the configured
weather service. State that the field is a model forecast, not radar. Keep the Open-Meteo
attribution and licence link.

- [ ] **Step 2: Run every local quality gate**

Run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease
research\.venv\Scripts\python.exe -m pytest -q
research\.venv\Scripts\python.exe -m ruff check research\src research\tests
```

Run Pyright from `research/`:

```powershell
.\.venv\Scripts\python.exe -m pyright
```

Expected: Android build success, 208 or more research tests passing, Ruff clean, and zero Pyright
errors.

- [ ] **Step 3: Install on the authorised Huawei**

Use only serial `BQLDU19927002646`:

```powershell
adb -s BQLDU19927002646 install -r app\build\outputs\apk\debug\app-debug.apk
```

Verify the seven live scenarios from the spec. Capture screenshots, UI trees, and package-scoped
logcat. Keep every ADB command pinned to the serial.

- [ ] **Step 4: Review the diff**

Check correctness, readability, architecture, security, payload bounds, accessibility, and
performance. Remove dead helpers, duplicate parsing, unused strings, and swallowed exceptions.
Run `git diff --check` after all fixes.

- [ ] **Step 5: Commit and push documentation**

```text
docs(weather): document local rain field
```

Push `main`. Wait for Android CI and verify the public head SHA.

- [ ] **Step 6: Apply release gates**

Do not create a tag, GitHub release, or Play upload if either condition remains true:

- the forecast calibration artifact has not passed the untouched holdout;
- the release calls the Open-Meteo Free API while ads or paid products are enabled.

If both gates pass, bump the version, rebuild signed APK and AAB files, create checksums, publish a
GitHub release, upload the AAB to the existing Google Play closed-test track, and verify the Play
status. Otherwise, report the exact blockers and keep the existing release unchanged.

- [ ] **Step 7: Clean resources**

Stop Gradle daemons and the debug app process. Remove only the temporary screenshots, UI trees,
and probe payloads created by this plan. Keep the repository, build outputs, research cache,
installed app, and chat state.
