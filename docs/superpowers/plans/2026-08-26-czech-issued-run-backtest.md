# Czech issued-run backtest implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure every eligible European and global model over Czechia against independent ČHMÚ observations, fit Czech lead-specific blends without holdout leakage, and export only segments that beat the training-selected best single model on a locked 30-day holdout.

**Architecture:** Use the Open-Meteo Previous Runs API for a low-cost fixed-lead preflight and the Single Runs archive for final run-preserving training. Batch Czech station coordinates per model and run so the 120-day research job remains below the documented request limit. Keep raw responses immutable, derive one complete-case dataset per variable and lead, and fall back to the best training model whenever a blend fails a ship rule.

**Tech stack:** Python 3.12, stdlib HTTP/JSON/SQLite, NumPy, SciPy, pytest, Ruff, Pyright, existing `aladin_ensemble` package.

**Spec:** `docs/superpowers/specs/2026-08-25-czech-calibrated-ensemble-design.md`

## Global constraints

- Evaluate every verified registry model that covers the Czech station cohort; a model may receive zero weight.
- Use exact issued runs for production weights. Previous Runs data is a preflight and fixed-lead benchmark only.
- Training covers at least 90 complete forecast dates. The newest 30 complete dates are a locked holdout.
- Select fallback models using training data only. Never choose a model from holdout performance.
- Use ČHMÚ station observations as independent scalar truth and MERGE1h only for spatial precipitation verification.
- Keep research API use below 10,000 HTTP requests per day and record the estimate before downloading.
- Do not export Android weights unless every existing ship rule passes.
- Keep production ads and purchases disabled until the weather-data licence permits commercial use.
- Do not commit raw forecast responses, station archives, database files, weights, or generated reports before review.

---

### Task 1: Add batched fixed-lead forecast ingestion

**Files:**
- Modify: `research/src/aladin_ensemble/sources/open_meteo_runs.py`
- Modify: `research/src/aladin_ensemble/types.py`
- Modify: `research/tests/test_forecast_runs.py`

**Interfaces:**
- Produces: `ForecastPoint`, `PreviousRunsRequest`, `build_previous_runs_batch_url()`, and `parse_previous_run_values()`.
- Preserves: existing `IssuedRunRequest`, `CachedDownloader`, and parser behavior.

- [ ] Write a failing test proving one request batches multiple Czech coordinates for one model and one fixed lead.
- [ ] Run `pytest research/tests/test_forecast_runs.py -q` and verify failure because the batch contract does not exist.
- [ ] Add immutable typed point and request records with finite-coordinate, unique-point, variable, date-range, model, and lead validation.
- [ ] Build a sorted, credential-free URL using comma-separated latitudes and longitudes and `_previous_dayN` variable names.
- [ ] Parse list responses into canonical `ForecastValue` rows whose `run_time` is `valid_time - lead_days`, rejecting wrong units, wrong row counts, duplicate identities, and timestamps outside the request range.
- [ ] Add happy-path, missing-value, malformed-response, duplicate-row, invalid-coordinate, invalid-lead, and request-budget tests.
- [ ] Run focused pytest, Ruff, and Pyright.

### Task 2: Add deterministic Czech station selection and monthly truth downloads

**Files:**
- Create: `research/src/aladin_ensemble/sources/chmi_download.py`
- Create: `research/tests/test_chmi_download.py`
- Modify: `research/src/aladin_ensemble/sources/chmi_station.py`

**Interfaces:**
- Consumes: `Station`, `ElementMetadata`, `parse_station_metadata()`, `parse_element_metadata()`, and `parse_station_observations()`.
- Produces: `CzechTarget`, `SelectedStation`, `select_station_cohort()`, `ChmiMonthlyRequest`, and `download_chmi_month()`.

- [ ] Write failing tests with literal station fixtures for all 14 Czech regional targets plus low, middle, and high elevation coverage.
- [ ] Verify the tests fail because station-cohort selection and monthly downloading do not exist.
- [ ] Select the nearest station with required temperature, wind, and hourly precipitation elements for every regional target; add separated high-elevation stations only when they are not already selected.
- [ ] Reject duplicate WIGOS IDs, missing required elements, non-Czech coordinates, and cohorts missing an elevation band.
- [ ] Download immutable monthly `recent/data/10min/MM/10m-{WIGOS}-{YYYYMM}.json` files for temperature and wind plus `recent/data/1hour/MM/1h-{WIGOS}-{YYYYMM}.json` for `SRA1H`, with SHA-256 manifests, conditional headers, bounded timeout, and no secret-bearing URLs.
- [ ] Keep only exact whole-hour instants from 10-minute temperature and wind observations. Never aggregate or substitute ten-minute precipitation for `SRA1H`.
- [ ] Add cache-hit, checksum-collision, HTTP-error, incomplete-month, and current-partial-month tests.
- [ ] Run focused pytest, Ruff, and Pyright.

### Task 3: Build the 90-day training and 30-day holdout dataset

**Files:**
- Create: `research/src/aladin_ensemble/backtest.py`
- Create: `research/tests/test_backtest.py`
- Modify: `research/src/aladin_ensemble/baselines.py`
- Modify: `research/src/aladin_ensemble/align.py`

**Interfaces:**
- Consumes: complete registry, batched forecasts, ČHMÚ observations, `ScalarForecastCase`, and `DateRange`.
- Produces: `BacktestConfig`, `BacktestDataset`, `SegmentDataset`, `build_backtest_dataset()`, and `write_dataset_manifest()`.

- [ ] Write failing tests for the exact April 2 through June 30 training range and July 1 through July 30 holdout range.
- [ ] Verify failure because no dataset orchestration exists.
- [ ] Build complete hourly cases for temperature, wind speed, and precipitation grouped by lead, Czech region target, elevation band, and season.
- [ ] Calculate candidate coverage per variable and lead; keep only models with at least 90 percent coverage for that segment.
- [ ] Use one shared complete-case mask for every candidate, mean, median, Best Match, and blend comparison in a segment.
- [ ] Select the best single fallback from training MAE only and store that choice before the holdout lock is written.
- [ ] Write a deterministic dataset manifest containing source hashes, registry hash, dates, stations, model IDs, variables, request estimate, row counts, missingness rates, and exclusion reasons.
- [ ] Add tests for leakage, later-run stitching, incomplete truth, mixed units, duplicate observations, model dropout, and deterministic manifests.
- [ ] Run focused pytest, full research pytest, Ruff, and Pyright.

### Task 4: Fit, evaluate, report, and fail closed

**Files:**
- Create: `research/src/aladin_ensemble/run_backtest.py`
- Create: `research/tests/test_run_backtest.py`
- Modify: `research/src/aladin_ensemble/evaluate.py`
- Modify: `research/src/aladin_ensemble/export.py`
- Modify: `docs/research/czech-ensemble-validation.md`

**Interfaces:**
- Consumes: `BacktestDataset`, `fit_scalar_weights()`, `fit_wind_vector_weights()`, `fit_positive_amount_weights()`, `fit_occurrence_calibration()`, and `evaluate_segment()`.
- Produces: `BacktestReport`, deterministic Markdown/JSON reports, a locked holdout, and optional `app/src/main/assets/ensemble_weights.json`.

- [ ] Write failing tests proving the fallback is chosen on training data and a rejected blend cannot export as active.
- [ ] Verify failure before implementation.
- [ ] Fit scalar weights by variable and lead; fit wind in east/north components; fit precipitation occurrence from deterministic event indicators and positive amount separately.
- [ ] Evaluate holdout MAE/RMSE, circular wind error, precipitation Brier and threshold scores, date-block bootstrap intervals, regional degradation, fold stability, coverage, and missing-model fallback.
- [ ] Write one compact report table per variable and lead with sample count, best training model, holdout scores, confidence interval, accepted/rejected status, and reasons.
- [ ] Refuse weight export if the registry is incomplete, the dataset has fewer than 90 plus 30 days, any referenced model is absent, or a segment lacks a valid fallback.
- [ ] Run the bounded preflight first. Print the request estimate and stop before a run above the provider limit.
- [ ] Run the real 120-day job only after the preflight shows source coverage and expected storage size.
- [ ] Run full pytest, Ruff, Pyright, and a clean rerun from cached raw data.

### Task 5: Handoff validated weights to Android

**Files:**
- Follow-up plan: `docs/superpowers/plans/2026-08-25-android-ensemble-weather-detail.md`
- Follow-up plan: `docs/superpowers/plans/2026-08-25-forecast-precipitation-tiles.md`

**Interfaces:**
- Consumes: only a validated `ensemble_weights.json` from Task 4.
- Produces: Android runtime blend/fallback selection and a separately labelled 1–24 hour forecast-precipitation map.

- [ ] Do not start Android weight consumption until Task 4 produces a validated artifact.
- [ ] Keep current `chmi_aladin_seamless` behavior as the safe fallback until that gate passes.
- [ ] Execute the existing Android and forecast-tile plans after the gate.

### Task 6: Add widget style presets after forecast correctness

**Files:**
- Create follow-up plan: `docs/superpowers/plans/2026-08-26-widget-style-presets.md`

**Interfaces:**
- Consumes: current safe `RemoteViews` renderer and per-widget settings.
- Produces: Minimal, Material, Pixel, and Cupertino presets plus manual customization.

- [ ] Preserve every existing per-widget color, opacity, image, content, alignment, and resize option.
- [ ] Use Android text appearances for launcher-safe font styling; do not load arbitrary font files into `RemoteViews`.
- [ ] Verify preview/provider parity and compact, standard, tall, and wide resizing on Android 10 and the Huawei device.
