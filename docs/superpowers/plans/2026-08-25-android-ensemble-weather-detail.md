# Android ensemble, Moon, and weather detail implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consume reviewed Czech ensemble weights on Android, expose complete weather and Moon details, and extend widgets while preserving the minimal home screen and Android 10 support.

**Architecture:** The network layer returns typed model series with run/valid timestamps. A pure Kotlin ensemble engine validates the research artifact and blends only aligned eligible values. Detailed data appears in a separate Material screen. `commons-suncalc:3.11` supplies offline astronomy; a Compose Canvas renders the Moon orientation.

**Tech Stack:** Kotlin 2.3.21, Android SDK 36/minSdk 29, Compose Material 3, `org.shredzone.commons:commons-suncalc:3.11`, JUnit 4, existing JSON/network stack.

**Spec:** `docs/superpowers/specs/2026-08-25-czech-calibrated-ensemble-design.md`

**Consumes:** `app/src/main/assets/ensemble_weights.json` and `docs/research/czech-ensemble-validation.md` from the research plan.

## Global constraints

- Do not start this plan until the production weight artifact passes research review.
- Keep the current single-model path as fail-closed fallback.
- No API secret in the app.
- All network and blend work stays off the main thread.
- Bound raw-model cache by saved location count and expiry.
- Preserve system/EN/CS/DE/ES/FR localisation and bundle language delivery.
- Keep the home screen visually restrained.
- Missing variables display unavailable; never zero-fill.
- Do not label model precipitation as radar.

---

### Task 0: Add exact saved map points

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherApp.kt`
- Modify or create only when required: the existing map WebView asset/screen
- Modify: five `strings.xml` catalogs
- Modify: `app/src/test/java/cz/majkey/pocasicesko/data/LocationFavoritesCodecTest.kt`
- Create only when coordinate conversion is not trivial in the UI: one focused mapping test

- [ ] Reuse `CzechLocation` and the existing favourites codec; do not add a second location model.
- [ ] Add **Choose point on map**, direct latitude/longitude entry, and a bounded custom label.
- [ ] Validate finite WGS84 values and the supported Czech map bounds before selection or storage.
- [ ] Save by coordinate identity and keep the existing 12-location bound.
- [ ] Preserve exact request/cache coordinates; show the source grid point/resolution separately.
- [ ] Keep map attribution visible and fail closed when an interactive tile source is unavailable.
- [ ] Test valid field, map-boundary, malformed/non-finite, duplicate, and old-favourite compatibility.
- [ ] Run focused/full Android checks. Do not commit without explicit user approval.

---

### Task 0b: Add Metric and Imperial display units

- [ ] Keep Metric units as the persisted default and add the selector only to Settings.
- [ ] Keep network/cache/research values canonical metric; convert only at display boundaries.
- [ ] Cover temperature, wind/gusts, precipitation/snow, pressure, visibility, and distance.
- [ ] Apply the same preset to forecast details and every widget/preview.
- [ ] Test conversion constants, malformed stored values, restart persistence, and widget refresh.
- [ ] Run focused/full Android checks. Do not commit without explicit user approval.

---

### Task 1: Add the ensemble schema and validator

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/ensemble/EnsembleModels.kt`
- Create: `app/src/main/java/cz/majkey/pocasicesko/ensemble/EnsembleWeights.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/ensemble/EnsembleWeightsTest.kt`
- Modify: `app/proguard-rules.pro`

**Interfaces:**
- Produces: `EnsembleWeightSet.load(InputStream)`, typed segment/model/score records, and `WeightValidationResult`.

- [ ] Write RED fixtures for valid, malformed, negative, non-summing, duplicate, unknown-schema, and missing-fallback artifacts.
- [ ] Validate every field and reject non-finite numbers.
- [ ] Verify the embedded production artifact manifest hash and schema.
- [ ] Add only the R8 rules proven necessary by a minified parse test.
- [ ] Run focused/full Android checks and commit `feat(ensemble): validate forecast weights`.

---

### Task 2: Fetch aligned multi-model payloads

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/ModelForecastRepository.kt`
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/ModelForecastParser.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/data/ModelForecastParserTest.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`

**Interfaces:**
- Produces: `ModelForecast(modelId, runTime, validTime, hourlyValues, sourceAge)`.

- [ ] Write RED JSON fixtures for aligned, missing, stale, mixed-unit, and partial-model responses.
- [ ] Request only model/variable/horizon combinations present in the weight artifact.
- [ ] Parse run and validity timestamps. Reject incompatible grids/times instead of positional zipping.
- [ ] Cache raw payloads by location/model/run with an expiry and fixed location bound.
- [ ] Preserve the current fallback request when the weight set or minimum source count fails.
- [ ] Run checks and commit `feat(ensemble): fetch aligned model forecasts`.

---

### Task 3: Implement the on-device blend engine

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/ensemble/EnsembleEngine.kt`
- Create: `app/src/main/java/cz/majkey/pocasicesko/ensemble/ForecastConfidence.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/ensemble/EnsembleEngineTest.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`

**Interfaces:**
- Produces: blended scalar/vector/precipitation values, source count, spread, confidence, degraded state, and fallback reason.

- [ ] Create golden tests from the Python exporter.
- [ ] Test scalar weights, U/V wind, probability calibration, positive precipitation amount, missing renormalisation, stale exclusion, minimum source count, and best-model fallback.
- [ ] Reconstruct weather condition from blended fields, never codes.
- [ ] Compare Kotlin golden output byte-for-byte/numerically with research fixtures.
- [ ] Keep all calculation pure and deterministic.
- [ ] Run checks and commit `feat(ensemble): blend Czech model forecasts`.

---

### Task 4: Expand the weather data contract

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherModels.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherParser.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`
- Modify: `app/src/test/java/cz/majkey/pocasicesko/data/WeatherParserTest.kt`

**Interfaces:**
- Adds nullable typed dew point, wet-bulb temperature, surface pressure, visibility, cloud layers, rain/showers/snow, snow depth, freezing level, CAPE, lifted index, inhibition, UV, sunshine/radiation, gusts, and ensemble metadata.

- [ ] Add RED complete/partial fixtures.
- [ ] Keep values nullable by source availability.
- [ ] Store canonical units only.
- [ ] Migrate cached JSON by parser compatibility; invalid old cache falls back to refresh.
- [ ] Run checks and commit `feat(weather): add complete forecast details`.

---

### Task 5: Add offline Moon calculations

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/cz/majkey/pocasicesko/astro/MoonCalculator.kt`
- Create: `app/src/main/java/cz/majkey/pocasicesko/astro/MoonModels.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/astro/MoonCalculatorTest.kt`
- Modify: `NOTICE.md` or create it when absent.

**Interfaces:**
- Produces: phase key, illuminated fraction, waxing/waning, rise/set, altitude, azimuth, limb orientation, and next new/full Moon.

- [ ] Verify Maven Central checksum and Apache-2.0 licence for `commons-suncalc:3.11` before adding it.
- [ ] Write reference tests for known new/full/quarter dates, Prague coordinates, polar/no-rise cases, DST, and orientation range.
- [ ] Wrap library output in immutable app types; no library type reaches UI.
- [ ] Document expected common-use accuracy and avoid astronomical-precision claims.
- [ ] Run minified build/tests and commit `feat(astro): add offline Moon details`.

---

### Task 6: Build the complete Material weather detail

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherDetailScreen.kt`
- Create: `app/src/main/java/cz/majkey/pocasicesko/ui/MoonPhaseCanvas.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt`
- Modify: five `strings.xml` catalogs

**Interfaces:**
- Produces: dedicated Now, Precipitation, Wind, Sun, Moon, and Model confidence sections.

- [ ] Use one scroll owner and clear section rhythm; no nested scroll/card grid.
- [ ] Keep home unchanged except one obvious detail action.
- [ ] Render missing values as localised unavailable text.
- [ ] Draw Moon illumination/orientation from calculated geometry; no remote image.
- [ ] Show model count, run age, spread, confidence, and degraded/fallback label.
- [ ] Add content descriptions, 48dp targets, contrast, large-font and narrow-screen checks.
- [ ] Run API29/API35/Huawei visual QA and commit `feat(ui): add complete weather detail`.

---

### Task 7: Add optional advanced widget fields

**Files:**
- Modify: `WeatherRepository.kt`, `WidgetSettings.kt`, `WeatherWidgetProvider.kt`, `WidgetEditorScreen.kt`, `widget_adaptive.xml`, five catalogs, and widget tests.

- [ ] Add dew point, pressure, visibility, UV, gust, Moon phase/illumination, confidence, and source-age toggles defaulted off.
- [ ] Persist neutral numeric/enum values only.
- [ ] Apply existing size gates, bounded worker, and bounded background rules.
- [ ] Keep existing widget settings binary-compatible.
- [ ] Verify two independent widgets, resize, locale, restart, and image fallback on API29/API35/Huawei.
- [ ] Commit `feat(widget): add advanced weather fields`.

---

### Task 8: Final Android integration and attribution

**Files:**
- Modify: README, PRIVACY, NOTICE, CHANGELOG, Pages, Play metadata, screenshots, and tests as required.

- [ ] Document models, calibration version, limitations, confidence, observation/forecast separation, Moon source, licences, and API posture.
- [ ] Re-run Data Safety against the new endpoints.
- [ ] Run clean signed APK/AAB gates and split-equivalent language install.
- [ ] Complete signed API29/API35/Huawei acceptance.
- [ ] Commit `docs: document Czech ensemble forecast`.
