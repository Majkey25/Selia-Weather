# Static Czech ensemble feed implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a licence-safe Czech multi-model forecast feed on GitHub Actions and Pages, calculate the final point forecast locally on Android, and add an honest 35-day probabilistic outlook.

**Architecture:** A Python pipeline ingests commercially reusable official model data, aligns it on a bounded Czech grid, and publishes source-separated tiles plus a signed-by-checksum manifest. Android downloads one tile, verifies it, interpolates the selected coordinate, applies validated calibration weights, and corrects current conditions with nearby ČHMÚ observations. Open-Meteo Free API remains available only to non-monetised debug builds and research.

**Tech stack:** Python 3.12, existing `aladin_ensemble` package, standard library JSON/hash/HTTP/subprocess, ECMWF ecCodes command-line tools on CI, pytest, Ruff, Pyright, GitHub Actions, GitHub Pages, Kotlin, Android SDK 36/minSdk 29, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-25-czech-calibrated-ensemble-design.md`

## Global constraints

- Never use the Open-Meteo Free API in the monetised release or its GitHub workflow.
- Add a production source only after its exact product licence permits commercial redistribution.
- Never publish a calibrated segment before an untouched holdout passes every ship gate.
- Keep provider/model/run/valid-time boundaries through the full pipeline.
- Never average WMO codes or degree-valued wind directions.
- Never replace a missing value with zero.
- GitHub Pages serves static files only. Android must keep a bounded last-valid cache.
- The generated Pages site must fail above 800 MB.
- No raw forecast binary enters git history or a GitHub release.
- No credential enters source, logs, manifests, tiles, screenshots, or build artifacts.
- Do not publish a new Play release until the production build makes no Open-Meteo Free API request.

---

### Task 1: Define and validate the static feed contract

**Files:**
- Create: `research/src/aladin_ensemble/static_feed.py`
- Create: `research/tests/test_static_feed.py`
- Create: `research/static-source-registry.json`

**Interfaces:**
- Produces: `FeedSource`, `FeedGrid`, `FeedRun`, `FeedManifest`, `FeedTile`, `validate_source_registry()`, `encode_manifest()`, and `decode_manifest()`.

- [ ] **Step 1: Write the failing source-licence test**

```python
def test_source_registry_rejects_noncommercial_production_source() -> None:
    source = source_record(commercial_redistribution=False)

    with pytest.raises(ValueError, match="commercial redistribution"):
        validate_source_registry((source,), production=True)
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
research\.venv\Scripts\python.exe -m pytest research/tests/test_static_feed.py -q
```

Expected: collection or import failure because `static_feed` does not exist.

- [ ] **Step 3: Add the minimum typed contract**

```python
@dataclass(frozen=True, slots=True)
class FeedGrid:
    south: float = 48.45
    north: float = 51.20
    west: float = 11.90
    east: float = 19.00
    step: float = 0.05
    tile_step: float = 0.50


@dataclass(frozen=True, slots=True)
class FeedSource:
    source_id: str
    provider: str
    model_id: str
    licence_name: str
    licence_url: str
    attribution: str
    commercial_redistribution: bool
```

Validate finite bounds, exact divisibility by the grid step, non-empty identities, HTTPS licence URLs, unique source/model IDs, lowercase SHA-256 digests, UTC timestamps, schema version, run expiry, and production licence state.

- [ ] **Step 4: Add deterministic JSON tests**

Cover round-trip encoding, sorted keys, duplicate sources, bad hashes, stale runs, invalid coordinates, unsupported schema, source ordering, and non-finite values.

- [ ] **Step 5: Add the production source registry**

Store exact product URLs, licence URLs, attribution, redistribution permission, native resolution, forecast horizon, variables, and update schedule. Begin with verified ČHMÚ, DWD, ECMWF, and NOAA products. Keep a source disabled until every field has evidence.

- [ ] **Step 6: Run focused and full research gates**

```powershell
research\.venv\Scripts\python.exe -m pytest research/tests/test_static_feed.py -q
research\.venv\Scripts\python.exe -m ruff check research/src research/tests
research\.venv\Scripts\python.exe -m pyright research/src/aladin_ensemble/static_feed.py research/tests/test_static_feed.py
```

- [ ] **Step 7: Commit**

```text
feat(research): define static forecast feed
```

---

### Task 2: Build deterministic source-separated forecast tiles

**Files:**
- Create: `research/src/aladin_ensemble/build_static_feed.py`
- Create: `research/tests/test_build_static_feed.py`
- Modify: `research/src/aladin_ensemble/types.py`

**Interfaces:**
- Consumes: canonical `ForecastValue` rows, a complete source registry, and an optional validated calibration artifact.
- Produces: `data/v1/manifest.json`, `data/v1/tiles/{run_id}/{tile_y}/{tile_x}.json`, and `data/v1/licences.json`.

- [ ] Write a failing golden test with two models, four grid points, three validity hours, and temperature/wind/precipitation values.
- [ ] Verify RED because no tile builder exists.
- [ ] Group values by tile, source, variable, validity time, and grid point. Reject duplicates, mixed units, mixed validity axes, missing run metadata, and points outside the declared grid.
- [ ] Keep source series separate. Do not pre-average values in the feed.
- [ ] Quantise only after a round-trip error test proves these maximum errors: temperature `0.05 °C`, speed `0.1 km/h`, pressure `0.1 hPa`, humidity/cloud/probability `1%`, and precipitation `0.01 mm`.
- [ ] Hash every tile byte sequence. Write the manifest only after every tile and size check passes.
- [ ] Refuse `state=production` when calibration is absent, diagnostic, references an unlicensed source, or fails its manifest hash.
- [ ] Test deterministic bytes, one corrupted tile, partial generation cleanup, 800 MB size refusal, and diagnostic-to-production refusal.
- [ ] Run pytest, Ruff, and focused Pyright.
- [ ] Commit `feat(research): build forecast feed tiles`.

---

### Task 3: Ingest direct official operational model runs

**Files:**
- Create: `research/src/aladin_ensemble/sources/official_runs.py`
- Create: `research/tests/test_official_runs.py`
- Add small licensed fixtures under: `research/tests/fixtures/official/`
- Modify: `research/pyproject.toml`
- Modify: `research/uv.lock`

**Interfaces:**
- Produces canonical `ForecastValue` rows and `SourceManifest` records from ČHMÚ ALADIN, DWD ICON-EU, ECMWF IFS/AIFS Open Data, and NOAA GFS/GEFS products.

- [ ] Write failing command-builder and parser tests for one tiny fixture from each provider.
- [ ] Verify RED before adding the adapter.
- [ ] Use ECMWF ecCodes tools for GRIB decoding. Do not write a GRIB decoder.
- [ ] Restrict every download to the Czech bounding box, required variables, required pressure/surface levels, and supported forecast steps when the provider supports subsetting.
- [ ] Validate provider run time, forecast step, grid coordinates, missing markers, units, checksum, licence record, and output dimensions.
- [ ] Keep deterministic and ensemble products separate.
- [ ] Add bounded retry, `Retry-After`, conditional download, SHA-256 cache collision, stale run, missing field, and malformed GRIB tests.
- [ ] Run one real source smoke request per provider. Record byte count and duration. Stop before any unbounded download.
- [ ] Run pytest, Ruff, Pyright, and a cached replay with zero network requests.
- [ ] Commit `feat(research): ingest official model runs`.

---

### Task 4: Publish the verified feed through GitHub Pages

**Files:**
- Create: `.github/workflows/forecast-data.yml`
- Create: `research/src/aladin_ensemble/run_static_feed.py`
- Create: `research/tests/test_run_static_feed.py`
- Modify: `docs/index.html`

**Interfaces:**
- Scheduled run: `17 */6 * * *` plus `workflow_dispatch`.
- Pages root keeps the privacy site and adds `/data/v1/`.

- [ ] Write a failing orchestration test that refuses incomplete sources, stale runs, missing calibration, and oversized output.
- [ ] Verify RED before implementation.
- [ ] Implement one command that downloads bounded inputs, builds into a temporary directory, verifies every manifest/tile hash, copies the existing `docs/` site, and emits a Pages artifact only on success.
- [ ] Give the workflow only `contents: read`, `pages: write`, and `id-token: write` permissions. Set concurrency to cancel only an older in-progress feed build.
- [ ] Install pinned Python dependencies from `research/uv.lock` and the Ubuntu ecCodes package. Never print environment variables.
- [ ] Add a small English status section to `docs/index.html` with schema, generation time, expiry, model count, attribution, and limitations.
- [ ] Run the command locally against fixtures. Verify the generated artifact and corrupt-tile failure.
- [ ] Run the workflow manually. Verify a successful Pages deployment and fetch the public manifest and one tile over HTTPS.
- [ ] Commit `ci: publish verified forecast data`.

---

### Task 5: Add the Android Pages client and local interpolation

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/StaticForecastModels.kt`
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/StaticForecastParser.kt`
- Create: `app/src/main/java/cz/majkey/pocasicesko/data/StaticForecastRepository.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/data/StaticForecastParserTest.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`

**Interfaces:**
- Production base URL: `https://majkey25.github.io/Selia-Weather/data/v1/`.
- Produces aligned per-model values for the exact selected coordinate plus source/run/freshness metadata.

- [ ] Write RED fixtures for valid, stale, corrupt, partial, out-of-grid, unsupported-schema, and non-finite feeds.
- [ ] Verify RED before implementation.
- [ ] Parse into immutable typed records. Validate schema, bounds, tile identity, SHA-256, run/valid time, units, source IDs, dimensions, and expiry.
- [ ] Select one tile from the coordinate. Interpolate each model independently from the four surrounding grid points. Never interpolate codes.
- [ ] Cache only the current manifest, current tile per saved location, and last valid snapshot. Expire raw feed data after its manifest expiry.
- [ ] Keep Open-Meteo direct forecast and geocoding calls in debug only. Production uses the static feed and system/bundled Czech place lookup.
- [ ] Test online success, unchanged ETag, corrupt download, stale network with valid cache, no valid cache, and coordinate boundary.
- [ ] Run focused/full Android tests, lint, debug, and minified release build.
- [ ] Commit `feat(android): consume static forecast feed`.

---

### Task 6: Compute the calibrated point forecast on Android

**Files:**
- Create: `app/src/main/java/cz/majkey/pocasicesko/ensemble/EnsembleModels.kt`
- Create: `app/src/main/java/cz/majkey/pocasicesko/ensemble/EnsembleWeights.kt`
- Create: `app/src/main/java/cz/majkey/pocasicesko/ensemble/EnsembleEngine.kt`
- Create: `app/src/test/java/cz/majkey/pocasicesko/ensemble/EnsembleEngineTest.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherModels.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`

- [ ] Write Python/Kotlin golden cases for scalar weights, U/V wind, precipitation occurrence/amount, missing-source renormalisation, minimum-source fallback, and confidence metadata.
- [ ] Verify Kotlin RED because the engine does not exist.
- [ ] Validate the weight artifact before use. Reject unknown schema, hash mismatch, bad model IDs, negative/non-summing weights, missing fallback, and diagnostic state.
- [ ] Blend scalar variables with the exported constrained weights, wind in east/north components, and precipitation through occurrence plus positive amount.
- [ ] Derive weather conditions from calculated continuous variables. Never blend WMO codes.
- [ ] Apply current ČHMÚ station correction after the forecast blend.
- [ ] Return contributor count, spread, source age, historical error, confidence band, and fallback reason.
- [ ] Run all golden, Android, lint, and minified build gates.
- [ ] Commit `feat(ensemble): calculate Czech point forecast`.

---

### Task 7: Add the 35-day probabilistic outlook

**Files:**
- Modify: `app/src/main/java/cz/majkey/pocasicesko/data/WeatherModels.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt`
- Modify: `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherDetailScreen.kt`
- Modify: five `strings.xml` catalogs
- Create: `app/src/test/java/cz/majkey/pocasicesko/ui/ExtendedOutlookTest.kt`

- [ ] Write RED tests for days 16 through 35, missing spread, low confidence, and deterministic-hour refusal.
- [ ] Add immutable daily outlook values: median, lower/upper range, precipitation probability, contributor count, and confidence.
- [ ] Keep the existing detailed hourly/day pager through day 15.
- [ ] Add one separate **Extended outlook** section for days 16 through 35. Show ranges and uncertainty. Do not expose hourly detail.
- [ ] Localise every label and unavailable/degraded state.
- [ ] Verify narrow screen, large font, dark/light system mode, API 29, and Huawei rendering.
- [ ] Run full Android gates and commit `feat(ui): add probabilistic extended outlook`.

---

### Task 8: Remove commercial data violations and release

**Files:**
- Modify: `README.md`, `PRIVACY.md`, `NOTICE.md`, `CHANGELOG.md`, `docs/index.html`, `docs/google-play-submission.md`
- Modify: Play metadata and screenshots only when the verified UI differs.

- [ ] Prove the release manifest and code contain no `api.open-meteo.com`, `geocoding-api.open-meteo.com`, customer credential, or non-commercial source.
- [ ] Document every operational provider, licence, attribution, grid resolution, calibration version, confidence meaning, and GitHub availability limit.
- [ ] Run research pytest/Ruff/Pyright and Android tests/lint/signed APK/AAB builds.
- [ ] Install the signed Play-equivalent build on API 29, API 35, and the authorised Huawei. Test happy, stale-cache, corrupt-tile, missing-source, location, radar, 35-day outlook, and widget flows.
- [ ] Verify GitHub Pages manifest/tile freshness and GitHub Actions status.
- [ ] Bump version code/name, create checksums, commit, push, wait for CI, create the GitHub release, and submit Google Play production only after every gate passes.
- [ ] Stop Gradle, test app processes, and temporary services. Preserve the repo, signed artifacts, research cache, and installed Play app.
