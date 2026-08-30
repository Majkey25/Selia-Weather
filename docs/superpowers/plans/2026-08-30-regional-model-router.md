# Regional Model Router Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route worldwide coordinates into stable calibration regions while requesting one verified, non-duplicated global-capable model family list plus truly local additions such as CHMI ALADIN.

**Architecture:** `ForecastRegion.kt` remains the single model-routing boundary consumed by both the point forecast and the 24-hour precipitation field. Seamless provider IDs stay global because each provider selects its local high-resolution model only inside its domain; region selectors prepare later calibration and observation adapters without double-counting provider families.

**Tech Stack:** Kotlin, Android 10+ (`minSdk 29`), JUnit 4, Open-Meteo Forecast API.

**Spec:** `docs/superpowers/specs/2026-08-30-global-regional-ensemble-design.md`

## Global Constraints

- Keep `com.majkeylab.weatheraladin` and Android 10+ compatibility.
- Do not add dependencies.
- Do not average weather codes or wind directions in degrees.
- Do not claim improved worldwide accuracy before regional locked holdouts pass.
- Keep Open-Meteo Free requests out of monetised release behaviour.
- Missing providers fall back to the existing Best Match response.

---

### Task 1: Worldwide calibration regions and provider allowlist

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/ForecastRegion.kt`
- Test: `app/src/test/java/cz/majkey/pocasicesko/data/ForecastRegionTest.kt`

**Interfaces:**
- Consumes: `CzechLocation`, `CzechLocation.isInCzechia()`.
- Produces: `forecastRegionFor(location: CzechLocation): ForecastRegion` and `forecastApiModelsFor(location: CzechLocation): List<String>`.

- [x] **Step 1: Write failing region and allowlist tests**

Add literal expectations for Prague, Berlin, New York, Tokyo, Sydney, and an ocean point:

```kotlin
assertEquals(ForecastRegion.EAST_ASIA, forecastRegionFor(tokyo))
assertEquals(ForecastRegion.OCEANIA, forecastRegionFor(sydney))
assertEquals(ForecastRegion.GLOBAL, forecastRegionFor(ocean))

val pragueModels = forecastApiModelsFor(prague)
val globalModels = forecastApiModelsFor(newYork)
assertEquals(pragueModels.size, pragueModels.toSet().size)
assertEquals(globalModels.size, globalModels.toSet().size)
assertTrue("chmi_aladin_seamless" in pragueModels)
assertFalse("chmi_aladin_seamless" in globalModels)
assertFalse("kma_seamless" in pragueModels)
assertFalse("kma_seamless" in globalModels)
assertTrue("gfs_seamless" in globalModels)
assertTrue("jma_seamless" in globalModels)
assertTrue("bom_access_global" in globalModels)
```

- [x] **Step 2: Run the focused tests and confirm RED**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.ForecastRegionTest" --console=plain
```

Expected: failures because `EAST_ASIA` and `OCEANIA` do not exist and KMA is still requested.

- [x] **Step 3: Implement the minimum router change**

Extend `ForecastRegion` with `EAST_ASIA` and `OCEANIA`. Route in this order: Czechia, Europe,
North America, East Asia, Oceania, global. Keep the existing verified global provider list, remove
`kma_seamless`, and prepend `chmi_aladin_seamless` only for Czechia. Return a unique ordered list.

- [x] **Step 4: Run focused tests and confirm GREEN**

Run the Step 2 command. Expected: all `ForecastRegionTest` tests pass.

- [x] **Step 5: Commit the isolated router change**

```powershell
git add app/src/main/java/cz/majkey/pocasicesko/data/ForecastRegion.kt app/src/test/java/cz/majkey/pocasicesko/data/ForecastRegionTest.kt
git commit -m "feat(weather): route worldwide model regions"
```

### Task 2: Prove both forecast products share the router

**Files:**
- Test: `app/src/test/java/cz/majkey/pocasicesko/data/ModelConsensusTest.kt`
- Test: `app/src/test/java/cz/majkey/pocasicesko/data/PrecipitationFieldRepositoryTest.kt`

**Interfaces:**
- Consumes: `forecastApiModelsFor`, `WeatherRepository.modelForecastUrl`, `PrecipitationFieldRepository.url`.
- Produces: regression evidence that both URLs use the same ordered model IDs.

- [x] **Step 1: Add URL-contract tests**

For New York, parse the `models` query from both URLs and assert both equal
`forecastApiModelsFor(newYork).joinToString(",")`. Also assert CHMI and KMA are absent.

- [x] **Step 2: Run the two focused test classes**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest --tests "cz.majkey.pocasicesko.data.ModelConsensusTest" --tests "cz.majkey.pocasicesko.data.PrecipitationFieldRepositoryTest" --console=plain
```

Expected: PASS because both production paths already consume the shared router; any future split
fails this test.

- [x] **Step 3: Verify live provider acceptance**

Issue bounded live requests for Prague, New York, Tokyo, Sydney, and an ocean point using the exact
ordered model lists. Require HTTP 200, a non-empty hourly timeline, and at least three finite
temperature contributors. Record suspended or rejected model IDs in the spec, never substitute a
different unverified identifier.

- [x] **Step 4: Commit URL-contract tests**

```powershell
git add app/src/test/java/cz/majkey/pocasicesko/data/ModelConsensusTest.kt app/src/test/java/cz/majkey/pocasicesko/data/PrecipitationFieldRepositoryTest.kt
git commit -m "test(weather): lock regional model routing"
```

### Task 3: Documentation and complete verification

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/research/global-model-routing.md`

**Interfaces:**
- Consumes: verified model IDs, sample coordinates, HTTP results, and official provider docs.
- Produces: an English routing/limitations record without an accuracy claim.

- [x] **Step 1: Document routing and evidence**

Record each representative coordinate, region, requested IDs, response status, available
contributors, and the KMA operational exclusion. State that seamless models choose provider-local
grids automatically and that the current median remains diagnostic.

- [x] **Step 2: Run all Android and research gates**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --console=plain
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pytest -q
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research ruff check .
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pyright
```

Expected: Gradle `BUILD SUCCESSFUL`, pytest all passing, Ruff clean, Pyright zero errors.

- [x] **Step 3: Hostile diff review**

Verify no duplicate IDs, no KMA request, no CHMI request outside Czechia, no new dependency, no
accuracy claim, unchanged monetisation fail-closed flags, and no unrelated files.

- [x] **Step 4: Commit and push documentation**

```powershell
git add README.md CHANGELOG.md docs/research/global-model-routing.md docs/superpowers/specs/2026-08-30-global-regional-ensemble-design.md docs/superpowers/plans/2026-08-30-regional-model-router.md
git commit -m "docs(weather): document global model routing"
git push origin main
```

- [ ] **Step 5: Verify GitHub CI**

Require green Android CI and Research CI for the final HEAD. Do not create a GitHub release or Play
upload while calibration or commercial-data gates fail.
