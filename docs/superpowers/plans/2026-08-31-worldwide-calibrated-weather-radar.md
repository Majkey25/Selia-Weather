# Worldwide calibrated weather and radar implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every valid WGS84 coordinate the full weather detail, region-aware model routing, calibration-ready local blending, worldwide observed radar, and a clearly labelled 24-hour precipitation forecast.

**Architecture:** Android keeps Open-Meteo Best Match as the safe result and requests one independent series per eligible provider family. A checksum-verified artifact can replace the diagnostic median with variable-specific regional weights after a locked holdout passes. The map uses RainViewer for worldwide observed radar and the existing `PrecipitationField` calculation for the 24-hour forecast. The research package trains and verifies regional artifacts from issued model runs and independent observation sources.

**Tech stack:** Kotlin, Jetpack Compose, Android WebView, bundled Leaflet 1.9.4, JUnit 4, Python 3.12, NumPy, SciPy, pytest, Ruff, Pyright, GitHub Pages.

**Spec:** `docs/superpowers/specs/2026-08-30-global-regional-ensemble-design.md`

## Global constraints

- Keep `com.majkeylab.weatheraladin` and `minSdk = 29`.
- Keep the release build free of Ads SDK, Billing, Premium, UMP, and `AD_ID`.
- Do not add Android or Python dependencies.
- Never average wind directions as scalar degrees.
- Never average weather codes.
- Never convert missing weather, radar, station, or satellite data to zero.
- Label observed radar and model forecast separately.
- Use only live-verified provider identifiers and documented public endpoints.
- Keep unvalidated regional weights in diagnostic output. Do not ship them as accepted weights.
- Keep Best Match when an artifact, provider, parser, source-count, or observation gate fails.
- Run each task test-first and commit only the files listed for that task.

---

### Task 1: Cover all worldwide calibration domains

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/ForecastRegion.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/data/ForecastRegionTest.kt`

**Interfaces:**
- Consumes: `CzechLocation(latitude, longitude, countryCode)`.
- Produces: `forecastRegionFor(location: CzechLocation): ForecastRegion` and `forecastApiModelsFor(location: CzechLocation): List<String>`.

- [ ] **Step 1: Add failing worldwide routing tests**

Add cases for Moscow, Delhi, Lagos, Nairobi, São Paulo, a Pacific point, and a polar point. Assert that each location has a region, excludes CHMI outside Czechia, contains unique model IDs, and retains at least three global provider families.

```kotlin
@Test
fun routesEveryWorldwideDetailLocationWithoutLeakingChmi() {
    val locations = listOf(
        CzechLocation("Moscow", "Moscow", 55.7558, 37.6173, "RU"),
        CzechLocation("Delhi", "Delhi", 28.6139, 77.2090, "IN"),
        CzechLocation("Lagos", "Lagos", 6.5244, 3.3792, "NG"),
        CzechLocation("Nairobi", "Nairobi", -1.2921, 36.8219, "KE"),
        CzechLocation("São Paulo", "São Paulo", -23.5505, -46.6333, "BR"),
        CzechLocation("Pacific", REGION_WORLD, 0.0, -140.0, null),
        CzechLocation("Arctic", REGION_WORLD, 82.0, 20.0, null),
    )
    locations.forEach { location ->
        val models = forecastApiModelsFor(location)
        assertTrue(models.size >= 3)
        assertEquals(models.size, models.distinct().size)
        assertFalse("chmi_aladin_seamless" in models)
    }
}
```

- [ ] **Step 2: Run the test and confirm RED**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.ForecastRegionTest" --console=plain
```

Expected: FAIL because Africa, South America, South/Central Asia, and polar/global domains are not explicit.

- [ ] **Step 3: Extend `ForecastRegion` without duplicating provider families**

Add `SOUTH_CENTRAL_ASIA`, `AFRICA`, `SOUTH_AMERICA`, and `NORTHERN_ASIA`. Keep `GLOBAL_API_MODELS` as the common list. Add CHMI only for Czechia. Use country code first where it exists and coordinate bounds only for saved map pins without a country code.

- [ ] **Step 4: Run the focused test and confirm GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit the routing slice**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/data/ForecastRegion.kt app/src/test/java/cz/majkey/pocasicesko/data/ForecastRegionTest.kt
git commit -m "feat(weather): route worldwide forecast domains"
```

### Task 2: Parse a bounded calibration artifact

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/CalibrationArtifact.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/data/CalibrationArtifactTest.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/StaticForecastRepository.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/data/StaticForecastRepositoryTest.kt`

**Interfaces:**
- Produces: `parseCalibrationArtifact(json: String, nowEpochSeconds: Long): CalibrationArtifact`.
- Produces: `CalibrationArtifact.segment(region, variable, leadHours, month): CalibrationSegment?`.
- Produces: `CalibrationSegment.weights: Map<String, Double>`, `minimumContributors: Int`, `fallbackModelId: String`, and `truthClass: CalibrationTruthClass`.
- Produces: `StaticForecastRepository.fetchCalibrationArtifact(now: Instant): CalibrationArtifact`.

- [ ] **Step 1: Write failing parser and selector tests**

```kotlin
@Test
fun selectsExactRegionVariableLeadAndSeason() {
    val artifact = parseCalibrationArtifact(VALID_ARTIFACT, nowEpochSeconds = 1_788_000_000)
    val segment = requireNotNull(
        artifact.segment(ForecastRegion.AFRICA, "temperature_2m", 24, month = 8),
    )
    assertEquals(mapOf("gfs_seamless" to 0.4, "ecmwf_ifs025" to 0.6), segment.weights)
    assertEquals(2, segment.minimumContributors)
}

@Test
fun rejectsExpiredOrUnverifiedArtifacts() {
    assertThrows(JSONException::class.java) {
        parseCalibrationArtifact(EXPIRED_ARTIFACT, nowEpochSeconds = 1_788_000_000)
    }
}
```

The fixture schema is `2`. It includes `generated_at`, `expires_at`, `dataset_manifest_hash`, `model_contract_hash`, `accepted`, and `segments`. Each segment includes `region`, `variable`, `lead_hours`, `months`, `weights`, `minimum_contributors`, `fallback_model_id`, and `truth_class`.

- [ ] **Step 2: Run the test and confirm RED**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.CalibrationArtifactTest" --console=plain
```

Expected: compilation fails because the artifact types do not exist.

- [ ] **Step 3: Implement the minimum typed parser**

Require lowercase SHA-256 hashes, finite non-negative weights, a weight sum within `1e-6` of `1.0`, unique model IDs, `minimumContributors >= 2`, `accepted == true`, and `expiresAt > nowEpochSeconds`. Return `null` when no exact selector matches.

- [ ] **Step 4: Reuse the static-feed manifest and checksum path**

Fetch `calibration/ensemble_weights.json` from the existing `StaticForecastRepository.BASE_URL` only when `fetchUsableManifest(now)` returns a production manifest. Limit the payload to 2 MB. Verify its SHA-256 against `manifest.calibrationChecksum` before parsing it. Do not create another base URL, HTTP client, or manifest type.

- [ ] **Step 5: Run the focused tests and confirm GREEN**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.CalibrationArtifactTest" --tests "cz.majkey.pocasicesko.data.StaticForecastRepositoryTest" --console=plain
```

Expected: PASS.

- [ ] **Step 6: Commit the artifact contract**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/data/CalibrationArtifact.kt app/src/test/java/cz/majkey/pocasicesko/data/CalibrationArtifactTest.kt app/src/main/java/cz/majkey/pocasicesko/data/StaticForecastRepository.kt app/src/test/java/cz/majkey/pocasicesko/data/StaticForecastRepositoryTest.kt
git commit -m "feat(weather): validate calibration artifacts"
```

### Task 3: Apply accepted weights to point forecasts and provenance

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/ModelConsensus.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/ForecastCalculation.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/data/ModelConsensusTest.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/data/WeatherModelsTest.kt`

**Interfaces:**
- Consumes: `CalibrationArtifact?` and `CzechLocation`.
- Produces: `ForecastCalculationMode.CALIBRATED`, selected weights, artifact version, and truth class in `_selia_calculation`.
- Preserves: `DIAGNOSTIC_MEDIAN` and `BEST_MATCH` fallbacks.

- [ ] **Step 1: Add a failing weighted-blend test**

```kotlin
@Test
fun appliesOnlyAcceptedVariableSpecificWeights() {
    val result = blendModelForecast(BASE, MODELS, PRAGUE, acceptedArtifact())
    val current = JSONObject(result.json).getJSONObject("current")
    assertEquals(21.2, current.getDouble("temperature_2m"), 0.0001)
    assertEquals(ForecastCalculationMode.CALIBRATED, result.mode)
    assertEquals(listOf("a", "b"), result.contributorIds)
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.ModelConsensusTest" --console=plain
```

Expected: FAIL because `blendModelForecast` cannot accept an artifact and `CALIBRATED` does not exist.

- [ ] **Step 3: Implement weighted scalar and vector calculation**

Use a weighted sum for scalar values only when all required contributor values are finite and valid. Renormalize the accepted subset only when it still meets `minimumContributors`. Blend wind in east and north components with the same model weights. Use independent precipitation-occurrence and positive-amount segments. Derive weather code from calculated precipitation, cloud cover, and contributor severe-weather agreement.

`WeatherRepository` calls `StaticForecastRepository.fetchCalibrationArtifact(now)` once per refresh. Any `IOException`, checksum failure, expired artifact, or parse failure supplies `null` to `blendModelForecast` and preserves the existing diagnostic or Best Match result.

- [ ] **Step 4: Extend calculation JSON without breaking schema 1 caches**

Write schema `2`. Read both schema `1` and `2`. Schema `2` adds `artifact_version`, `truth_class`, and `weights`. Legacy schema `1` returns the existing provenance fields.

- [ ] **Step 5: Run focused tests and confirm GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.ModelConsensusTest" --tests "cz.majkey.pocasicesko.data.WeatherModelsTest" --console=plain
```

Expected: PASS.

- [ ] **Step 6: Commit the calculation slice**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/data/ModelConsensus.kt app/src/main/java/cz/majkey/pocasicesko/data/ForecastCalculation.kt app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt app/src/test/java/cz/majkey/pocasicesko/data/ModelConsensusTest.kt app/src/test/java/cz/majkey/pocasicesko/data/WeatherModelsTest.kt
git commit -m "feat(weather): apply accepted regional weights"
```

### Task 4: Use the same precipitation weights in the 24-hour field

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/PrecipitationFieldParser.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/PrecipitationFieldRepository.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/data/PrecipitationFieldParserTest.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/data/PrecipitationFieldRepositoryTest.kt`

**Interfaces:**
- Consumes: the same `CalibrationArtifact?` used by `WeatherRepository`.
- Produces: calibrated precipitation amount and occurrence probability for every 5-by-5 field cell.

- [ ] **Step 1: Add a failing weighted precipitation test**

```kotlin
@Test
fun usesThePointForecastPrecipitationSegmentForEveryCell() {
    val field = parsePrecipitationField(payload(), points, MODELS, LOCATION, acceptedArtifact())
    val centre = field.frames.first().cells[12]
    assertEquals(0.32, requireNotNull(centre.precipitationMm), 0.0001)
    assertEquals(80, centre.probabilityPercent)
}
```

- [ ] **Step 2: Run the parser tests and confirm RED**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.PrecipitationFieldParserTest" --console=plain
```

Expected: compilation fails because the parser has no location or artifact parameters.

- [ ] **Step 3: Reuse the artifact selector and keep the median fallback**

Do not create another weight parser. Select the precipitation occurrence and amount segments by the field centre, frame lead, and month. Keep `UNAVAILABLE` when fewer than three diagnostic values remain and no accepted segment can run.

- [ ] **Step 4: Run field tests and confirm GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.PrecipitationFieldParserTest" --tests "cz.majkey.pocasicesko.data.PrecipitationFieldRepositoryTest" --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit the shared precipitation calculation**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/data/PrecipitationFieldParser.kt app/src/main/java/cz/majkey/pocasicesko/data/PrecipitationFieldRepository.kt app/src/test/java/cz/majkey/pocasicesko/data/PrecipitationFieldParserTest.kt app/src/test/java/cz/majkey/pocasicesko/data/PrecipitationFieldRepositoryTest.kt
git commit -m "feat(weather): calibrate the 24 hour rain field"
```

### Task 5: Add fresh worldwide METAR current observations

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/MetarCurrentConditions.kt`
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/MetarCurrentConditionsRepository.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/data/MetarCurrentConditionsTest.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/CurrentConditions.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/data/CurrentConditionsTest.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/data/WeatherRepositoryTest.kt`

**Interfaces:**
- Consumes: `https://aviationweather.gov/api/data/metar?bbox=<south,west,north,east>&format=json&hours=2`.
- Produces: `fetch(location: CzechLocation, now: Instant): List<CurrentObservation>`.
- Reuses: `fuseCurrentConditions` and its freshness and distance checks.

- [ ] **Step 1: Write failing METAR parsing and selection tests**

```kotlin
@Test
fun selectsTheNearestFreshWorldwideMetar() {
    val observations = parseMetarCurrentConditions(METAR_JSON, now)
    val observation = observations.single()
    assertEquals("VIDP", observation.stationId)
    assertEquals(32.0, observation.temperature, 0.0)
    assertEquals(1012.0, observation.pressureHpa, 0.0)
}
```

- [ ] **Step 2: Run the test and confirm RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.MetarCurrentConditionsTest" --console=plain
```

Expected: compilation fails because the parser and repository do not exist.

- [ ] **Step 3: Implement the bounded adapter**

Use a 1.5-degree bounding box, a 10-second connect timeout, a 15-second read timeout, a 1-MB response limit, and the existing application user agent. Reject reports older than 90 minutes, more than 50 km away, missing coordinates, or non-finite values. Extend `CurrentStationObservation` with nullable dew point, pressure, visibility, and cloud-cover fields. Do not synthesize precipitation from METAR present-weather text.

- [ ] **Step 4: Fuse CHMI in Czechia and METAR elsewhere**

Keep CHMI as the Czech adapter. Outside Czechia, request METAR. If the adapter fails or no report passes, use the model current state unchanged.

- [ ] **Step 5: Run current-condition tests and confirm GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.MetarCurrentConditionsTest" --tests "cz.majkey.pocasicesko.data.CurrentConditionsTest" --tests "cz.majkey.pocasicesko.data.WeatherRepositoryTest" --console=plain
```

Expected: PASS.

- [ ] **Step 6: Commit the METAR slice**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/data/MetarCurrentConditions.kt app/src/main/java/cz/majkey/pocasicesko/data/MetarCurrentConditionsRepository.kt app/src/main/java/cz/majkey/pocasicesko/data/CurrentConditions.kt app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt app/src/test/java/cz/majkey/pocasicesko/data/MetarCurrentConditionsTest.kt app/src/test/java/cz/majkey/pocasicesko/data/CurrentConditionsTest.kt app/src/test/java/cz/majkey/pocasicesko/data/WeatherRepositoryTest.kt
git commit -m "feat(weather): correct current conditions worldwide"
```

### Task 6: Replace the Czech-only observed map with worldwide radar

**Files:**
- Modify: `app/src/main/assets/radar.html`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/RadarScreen.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/ui/RadarScreenTest.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: localized `app/src/main/res/values-*/strings.xml` files that contain radar strings

**Interfaces:**
- Consumes: RainViewer `https://api.rainviewer.com/public/weather-maps.json` and returned tile host/path values.
- Produces: `localizedRadarUrl(languageTag, latitude, longitude, isInCzechia)`.
- Preserves: the existing Compose `MapMode.OBSERVED` and `MapMode.FORECAST` data boundary.

- [ ] **Step 1: Replace Czech-only asset assertions with failing global-radar tests**

```kotlin
@Test
fun radarUrlCarriesTheSelectedWorldwideCoordinate() {
    assertEquals(
        "file:///android_asset/radar.html?lang=en&lat=-1.2921&lon=36.8219&chmi=0",
        localizedRadarUrl("en", -1.2921, 36.8219, false),
    )
}

@Test
fun radarAssetUsesOnlyCurrentRainViewerProducts() {
    val source = radarAsset.readText()
    assertTrue(source.contains("https://api.rainviewer.com/public/weather-maps.json"))
    assertTrue(source.contains("radar.past"))
    assertFalse(source.contains("radar.nowcast"))
    assertFalse(source.contains("satellite.infrared"))
}
```

- [ ] **Step 2: Run `RadarScreenTest` and confirm RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.ui.RadarScreenTest" --console=plain
```

Expected: FAIL because the URL has no coordinate and the asset is CHMI-only.

- [ ] **Step 3: Build the worldwide observed map from existing Leaflet assets**

Center the map on `lat` and `lon`. Load the RainViewer frame manifest once, render only `radar.past`, use the documented Universal Blue tiles, cap zoom at 7, display the coverage mask, and show `RainViewer` attribution. Keep a bounded 20-frame layer cache and remove each old Leaflet layer before adding the next frame.

- [ ] **Step 4: Keep a Czech high-resolution option without blocking the world map**

When `chmi=1`, expose a **ČHMÚ detail** layer. If the CHMI image fails, keep RainViewer visible. Remove the retired CHMI satellite and future-nowcast paths from the main timeline.

- [ ] **Step 5: Show one observed-to-forecast timeline band**

Replace the two equal mode buttons with one timeline band split at **Now**. The left segment is **Observed radar · past 2 h** and the right segment is **Model forecast · next 24 h**. Selecting a segment changes `MapMode` and keeps each source's frame control inside the same map card. Show the same location name in both modes. Do not merge observed and forecast values into one data series.

- [ ] **Step 6: Run radar tests and confirm GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 7: Live-verify radar endpoints**

Check the RainViewer manifest, one returned tile, the coverage mask, the latest CHMI image, and the existing 24-hour Open-Meteo field request. Require HTTP 200 or a documented coverage miss. Do not retry a retired RainViewer nowcast endpoint.

- [ ] **Step 8: Commit the worldwide radar slice**

```powershell
git add app/src/main/assets/radar.html app/src/main/java/cz/majkey/pocasicesko/ui/RadarScreen.kt app/src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt app/src/test/java/cz/majkey/pocasicesko/ui/RadarScreenTest.kt app/src/main/res
git commit -m "feat(radar): show worldwide observed precipitation"
```

### Task 7: Add global truth sources to the research pipeline

**Files:**
- Create: `research/src/aladin_ensemble/sources/noaa_isd.py`
- Create: `research/src/aladin_ensemble/sources/nasa_imerg.py`
- Create: `research/tests/test_noaa_isd.py`
- Create: `research/tests/test_nasa_imerg.py`
- Modify: `research/src/aladin_ensemble/run_backtest.py`
- Modify: `research/src/aladin_ensemble/types.py`
- Modify: `research/tests/test_run_backtest.py`

**Interfaces:**
- Produces: canonical hourly `Observation` rows from NOAA ISD/GHCNh temperature, dew point, pressure, visibility, wind, and precipitation fields.
- Produces: canonical half-hourly precipitation rows from NASA IMERG with `truth_class = "satellite_precipitation"`.
- Preserves: CHMI station and MERGE truth for Czechia.

- [ ] **Step 1: Add failing source-contract tests**

```python
def test_isd_rejects_failed_quality_flags() -> None:
    rows = parse_isd_rows(ISD_FIXTURE, station=STATION)
    assert [row.variable for row in rows] == ["temperature_2m", "wind_speed_10m"]


def test_imerg_keeps_native_grid_and_latency_metadata() -> None:
    row = parse_imerg_cell(IMERG_FIXTURE, latitude=6.5, longitude=3.4)
    assert row.variable == "precipitation"
    assert row.interval == timedelta(minutes=30)
    assert row.truth_class == "satellite_precipitation"
```

- [ ] **Step 2: Run the tests and confirm RED**

```powershell
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pytest research/tests/test_noaa_isd.py research/tests/test_nasa_imerg.py -q
```

Expected: import failures because both adapters are absent.

- [ ] **Step 3: Implement strict parsers before downloaders**

Parse fixture bytes into existing typed observations. Reject unknown units, failed quality flags, impossible values, missing station or grid identity, non-UTC timestamps, and inconsistent accumulation intervals. Keep downloader work separate from parsing.

- [ ] **Step 4: Add bounded cached downloads**

Reuse the SHA-256 addressed immutable cache pattern from `sources/open_meteo_runs.py`. Every manifest stores the public URL, request parameters, retrieval time, content hash, source licence URL, station or grid identity, and truth class. Require an explicit request budget before network execution.

- [ ] **Step 5: Add regional truth selection**

Use CHMI truth in Czechia, MRMS or ISD in the United States when available, ISD/GHCNh stations worldwide, and IMERG for global precipitation verification. Use ERA5 or IFS analysis only when the report labels the result as reanalysis-backed.

- [ ] **Step 6: Run research tests, Ruff, and Pyright**

```powershell
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pytest -q
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research ruff check .
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pyright
```

Expected: all tests pass, Ruff is clean, and Pyright reports zero errors.

- [ ] **Step 7: Commit the truth-source slice**

```powershell
git add research/src/aladin_ensemble/sources/noaa_isd.py research/src/aladin_ensemble/sources/nasa_imerg.py research/src/aladin_ensemble/run_backtest.py research/src/aladin_ensemble/types.py research/tests/test_noaa_isd.py research/tests/test_nasa_imerg.py research/tests/test_run_backtest.py
git commit -m "feat(research): add worldwide verification sources"
```

### Task 8: Train guarded regional artifacts and publish only accepted segments

**Files:**
- Modify: `research/src/aladin_ensemble/export.py`
- Modify: `research/src/aladin_ensemble/run_backtest.py`
- Modify: `research/tests/test_export.py`
- Modify: `research/tests/test_run_backtest.py`
- Modify: `.github/workflows/research.yml`
- Create: `docs/research/worldwide-ensemble-validation.md`

**Interfaces:**
- Consumes: fixed issued runs and truth rows from Task 7.
- Produces: schema-2 candidate and accepted artifact files.
- Produces: one explicit fallback for every rejected region, variable, and lead.

- [ ] **Step 1: Add failing export-gate tests**

```python
def test_global_artifact_exports_only_holdout_accepted_segments() -> None:
    artifact = build_backtest_artifact(TRAINING, EVALUATION, CONTRACTS)
    assert all(segment.accepted for segment in artifact.segments)
    assert artifact.dataset_manifest_hash == DATASET_HASH
    assert artifact.model_contract_hash == CONTRACT_HASH
```

- [ ] **Step 2: Run export tests and confirm RED**

```powershell
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pytest research/tests/test_export.py research/tests/test_run_backtest.py -q
```

Expected: FAIL because the artifact has no worldwide domain or truth-class contract.

- [ ] **Step 3: Extend selectors and keep the current acceptance gates**

Add worldwide domain and truth class to every fitted segment. Keep the 30-date locked holdout, positive bootstrap improvement, maximum 5-percent regional degradation, two improving training folds, missing-model fallback, complete registry, and fresh model-contract requirements.

- [ ] **Step 4: Run bounded regional backtests**

Use representative stations from Europe, Africa, South America, North America, South/Central Asia, East Asia, Oceania, northern Asia, and a reanalysis-only ocean domain. Each run writes an immutable dataset manifest and holdout lock before reading holdout scores.

- [ ] **Step 5: Write `worldwide-ensemble-validation.md` from the actual reports**

Record sample counts, truth class, fallback model, blend score, fallback score, confidence interval, regional degradation, and accepted status. Do not describe a rejected segment as improved.

- [ ] **Step 6: Publish only accepted artifacts**

Use the existing GitHub Pages static-data workflow. Upload the schema-2 artifact and its SHA-256 file only when every export validation passes. Candidate artifacts remain workflow artifacts and never enter the Pages directory.

- [ ] **Step 7: Run the full research gate and commit**

Run the Task 7 Step 6 commands. Then:

```powershell
git add research/src/aladin_ensemble/export.py research/src/aladin_ensemble/run_backtest.py research/tests/test_export.py research/tests/test_run_backtest.py .github/workflows/research.yml docs/research/worldwide-ensemble-validation.md
git commit -m "feat(research): guard worldwide calibration artifacts"
```

### Task 9: Show worldwide provenance and coverage in the app

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherDetailScreen.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/ui/WeatherDetailScreenTest.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/ui/RadarScreenTest.kt`
- Modify: localized `strings.xml` files

**Interfaces:**
- Consumes: `ForecastCalculation` schema 2 and radar coverage state.
- Produces: visible model contributors, mode, truth class, artifact age, fallback reason, and radar coverage.

- [ ] **Step 1: Add failing UI contract assertions**

Assert that the detail source contains localized labels for **Calibrated**, **Diagnostic median**, **Best Match fallback**, **Observed radar unavailable**, and **Model forecast**. Assert that no string says that every location has radar coverage.

- [ ] **Step 2: Run UI tests and confirm RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.ui.WeatherDetailScreenTest" --tests "cz.majkey.pocasicesko.ui.RadarScreenTest" --console=plain
```

- [ ] **Step 3: Implement compact provenance and coverage text**

Show one source block in the expanded detail. Keep contributor IDs behind an expansion affordance. On the map, keep the forecast visible when radar coverage is absent.

- [ ] **Step 4: Run UI tests and confirm GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit the provenance UI**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/ui/WeatherDetailScreen.kt app/src/main/java/cz/majkey/pocasicesko/ui/MapHubScreen.kt app/src/test/java/cz/majkey/pocasicesko/ui/WeatherDetailScreenTest.kt app/src/test/java/cz/majkey/pocasicesko/ui/RadarScreenTest.kt app/src/main/res
git commit -m "feat(ui): explain worldwide forecast sources"
```

### Task 10: Verify and publish beta.10, then publish accepted weight updates separately

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `CHANGELOG.md`
- Modify: `README.md`
- Modify: `fastlane/metadata/android/*/changelogs/11.txt`

**Interfaces:**
- Produces: versionCode `11`, versionName `0.2.0-beta.10`.
- Produces: GitHub prerelease and Google Play Alpha closed-test release.

- [ ] **Step 1: Update version and English-first release notes**

State that beta.10 adds worldwide observed radar, worldwide current observations where a fresh METAR exists, clearer radar coverage, calibration-ready local blending, and the existing 24-hour model precipitation forecast. Do not claim accepted worldwide weights unless Task 8 produced them.

- [ ] **Step 2: Run the complete Android gate**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Verify release contents and signing**

Require package `com.majkeylab.weatheraladin`, versionCode `11`, versionName `0.2.0-beta.10`, minSdk `29`, targetSdk `36`, a valid APK signature, and zero release references to Ads, UMP, Billing, Premium, or `AD_ID`.

- [ ] **Step 4: Live-test four worldwide scenarios**

Test Prague, New York, Nairobi, and Tokyo. For each location, verify the full detail, source provenance, observed-radar coverage state, 24-hour forecast, favorite persistence, and widget refresh. Also test an ocean point with no radar coverage and offline cached forecast recovery.

- [ ] **Step 5: Push the approved commits and require green CI**

```powershell
git push origin main
```

Require Android and Research workflows to succeed on the exact final commit.

- [ ] **Step 6: Publish the GitHub prerelease**

Attach the signed APK, AAB, and SHA-256 file. Target the exact final commit. Mark the release as prerelease.

- [ ] **Step 7: Publish version 11 to Google Play Alpha closed testing**

Upload the same signed AAB. Add localized release notes. Submit the release to review. Report **under review** until Play shows that it is available to selected testers.

- [ ] **Step 8: Publish later accepted calibration artifacts without an app binary when possible**

When Task 8 accepts new regional segments, update the checksum-verified GitHub Pages artifact. Create beta.11 only when the Android artifact schema or UI changes. Do not create an app release for data-only weight updates.

- [ ] **Step 9: Close test resources and clean temporary artifacts**

Close browser tabs and Android tools started for this work. Delete only exact ignored release copies after both remotes contain verified artifacts. Keep the repository, source, chat, and normal user files.
