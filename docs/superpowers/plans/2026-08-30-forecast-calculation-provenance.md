# Forecast Calculation Provenance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist and display truthful calculation provenance for every fresh or cached forecast: region, calculation mode, requested providers, current-hour contributors, and fallback reason.

**Architecture:** `ModelConsensus` returns values plus typed calculation evidence. `WeatherRepository` combines that evidence with the location route and stores it inside the cached forecast JSON. `WeatherParser` validates the optional app-owned metadata object, and Weather details renders it without adding content to the home screen.

**Tech Stack:** Kotlin, `org.json`, Android SharedPreferences cache, Jetpack Compose, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-30-global-regional-ensemble-design.md`

## Global Constraints

- No new dependency or telemetry.
- Metadata describes the current-hour calculation; it does not claim calibrated accuracy.
- A diagnostic median requires at least three finite aligned contributors.
- Best Match always records an explicit fallback reason.
- Contributor IDs must be unique and contained in the requested provider list.
- Cached JSON must preserve provenance across app restarts.
- Unknown, malformed, or unsupported metadata fails closed instead of inventing values.

---

### Task 1: Typed provenance and JSON contract

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/ForecastCalculation.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherModels.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherParser.kt`
- Test: `app/src/test/java/cz/majkey/pocasicesko/data/WeatherParserTest.kt`

**Interfaces:**
- Produces: `ForecastCalculationMode`, `ForecastFallbackReason`, `ForecastCalculation`, `JSONObject.putForecastCalculation`, and `JSONObject.forecastCalculationOrNull`.
- `WeatherSnapshot` gains `calculation: ForecastCalculation? = null`.

- [x] **Step 1: Write failing parser tests**

Add a valid `_selia_calculation` object to the forecast fixture and assert all typed fields. Add
negative fixtures for a contributor outside the requested list and diagnostic mode with fewer than
three contributors; both must throw `JSONException`.

- [x] **Step 2: Run parser tests and confirm RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.WeatherParserTest" --console=plain
```

Expected: compile failure because the provenance types/property do not exist.

- [x] **Step 3: Implement the typed contract**

Use schema version `1`. Store enum names, ordered requested/contributor arrays, and nullable
fallback reason. Validate finite bounded list sizes, uniqueness, contributor subset, and mode/reason
invariants. Return `null` only when `_selia_calculation` is absent.

- [x] **Step 4: Run parser tests and confirm GREEN**

Run the Step 2 command. Expected: all `WeatherParserTest` tests pass.

### Task 2: Blend evidence and repository persistence

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/ModelConsensus.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`
- Test: `app/src/test/java/cz/majkey/pocasicesko/data/ModelConsensusTest.kt`

**Interfaces:**
- Produces: `ModelBlendResult(json: String, mode: ForecastCalculationMode, contributorIds: List<String>, fallbackReason: ForecastFallbackReason?)`.
- Consumes: `forecastRegionFor(location)` and `forecastApiModelsFor(location)`.

- [x] **Step 1: Write failing blend-result tests**

The three-model fixture must return `DIAGNOSTIC_MEDIAN` with `a`, `b`, and `c`. The one-model
fixture must return unchanged Best Match with `INSUFFICIENT_CONTRIBUTORS`. Existing value assertions
must use `result.json`.

- [x] **Step 2: Run model tests and confirm RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.ModelConsensusTest" --console=plain
```

Expected: compile failure because `blendModelForecast` still returns `String`.

- [x] **Step 3: Return current-hour evidence**

Identify the explicit-model index matching the Best Match current hour. Keep only finite valid
`temperature_2m` values at that index as current-hour contributors. Return diagnostic mode only
with at least three. Continue blending other hourly values under existing per-field minimum rules.

- [x] **Step 4: Attach and persist metadata**

`WeatherRepository` builds `ForecastCalculation` from the route, requested IDs, and blend result,
writes it into the forecast JSON, then parses and persists that JSON. Network or JSON failure keeps
Best Match with `PROVIDER_UNAVAILABLE` and an empty contributor list. Cached parsing restores the
same metadata.

- [x] **Step 5: Run model/parser tests**

Run both focused test classes. Expected: PASS.

- [x] **Step 6: Commit the data slice**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/data app/src/test/java/cz/majkey/pocasicesko/data
git commit -m "feat(weather): persist calculation provenance"
```

### Task 3: Weather-details presentation and complete gates

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherDetailScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-cs/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `WeatherSnapshot.calculation`.
- Produces: one compact Weather-details section; home remains unchanged.

- [x] **Step 1: Render provenance**

When metadata exists, show calculation mode, calibration region, `contributors / requested`, raw
contributor IDs, and fallback reason. Hide the section for legacy cached JSON without metadata.

- [x] **Step 2: Add all five localisations**

Translate labels for diagnostic median, Best Match, provider unavailable, insufficient
contributors, six regions, and model-count text. Keep model IDs untranslated.

- [x] **Step 3: Run complete gates**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --console=plain
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pytest -q
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research ruff check .
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pyright
```

- [ ] **Step 4: Verify live on Huawei when the device is free**

Install the debug APK on `BQLDU19927002646`, load one Czech and one worldwide location, and verify
the details section matches the live contributor evidence. Do not interrupt another active task.

- [ ] **Step 5: Commit, push, and verify CI**

Use Conventional Commits, push the already-approved `main`, require Android and Research CI green,
and do not tag or upload to Play while calibration/commercial-data gates fail.
