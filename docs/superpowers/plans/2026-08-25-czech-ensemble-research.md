# Czech ensemble research implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reproducible Czech forecast backtest that evaluates eligible models, trains calibrated weights without leakage, and exports a reviewed Android weight artifact plus an English validation report.

**Architecture:** A separate Python 3.12 package downloads immutable source files into ignored storage, normalises forecasts and ČHMÚ observations into typed records, aligns issued runs by valid time and lead, evaluates locked baselines, and fits constrained variable-specific blends. SQLite stores derived rows; NumPy and SciPy handle fitting; `uv.lock` supplies exact dependency pins.

**Tech Stack:** Python 3.12, standard library HTTP/CSV/SQLite, NumPy, SciPy, pytest, Ruff, Pyright, uv, ČHMÚ open data, Open-Meteo archived forecast APIs.

**Spec:** `docs/superpowers/specs/2026-08-25-czech-calibrated-ensemble-design.md`

## Global constraints

- Do not change Android production code in this plan.
- Use independent ČHMÚ station/radar observations as primary truth.
- Never train on holdout dates.
- Never average weather codes or degree-valued wind directions.
- Every downloaded file records URL, retrieval time, checksum, licence, and source timestamp.
- Raw and derived data remain git-ignored. Only small fixtures, manifests, selected tables/plots, the report, and production weights are committed.
- Keep API traffic under current provider limits. Stop before a large download if the estimated request budget exceeds the documented free allowance.
- No API key or credential enters the repository, report, logs, or task state.
- A model without verified Czech coverage or sufficient archive is excluded, not guessed.

---

### Task 1: Create the locked research package and model registry

**Files:**
- Create: `research/pyproject.toml`
- Create: `research/uv.lock`
- Create: `research/README.md`
- Create: `research/.gitignore`
- Create: `research/src/aladin_ensemble/types.py`
- Create: `research/src/aladin_ensemble/registry.py`
- Create: `research/src/aladin_ensemble/sources/probe.py`
- Create: `research/tests/test_registry.py`

**Interfaces:**
- Produces: `ModelCandidate`, `ForecastValue`, `Observation`, `SourceManifest`, and `model-registry.json`.

- [ ] **Step 1: Create the environment**

Set Python to `>=3.12,<3.13`. Add NumPy and SciPy runtime dependencies and pytest, Ruff, and Pyright development dependencies. Generate and commit `uv.lock`; do not hand-edit resolved versions.

- [ ] **Step 2: Write RED registry tests**

```python
def test_registry_rejects_unverified_or_duplicate_model() -> None:
    registry = ModelRegistry()
    registry.add(candidate("icon_eu", verified=True))
    with pytest.raises(ValueError):
        registry.add(candidate("icon_eu", verified=True))
    with pytest.raises(ValueError):
        registry.add(candidate("unknown", verified=False))
```

Also test coverage threshold, horizon, variable set, licence field, and deterministic JSON ordering.

- [ ] **Step 3: Add typed records**

```python
@dataclass(frozen=True, slots=True)
class ForecastValue:
    model_id: str
    run_time: datetime
    valid_time: datetime
    latitude: float
    longitude: float
    elevation_m: float
    variable: str
    value: float | None
    unit: str
```

Use timezone-aware UTC datetimes only in research storage.

- [ ] **Step 4: Probe candidates**

Probe the documented model families at representative Czech low, middle, high, west, east, north, and south coordinates. Record exact API identifiers returned by successful calls. A candidate passes only when at least 90 percent of required sample points return the required variable and lead range.

The initial display-name pool comes from the spec. The probe writes exact identifiers; source code does not invent missing identifiers.

- [ ] **Step 5: Estimate request cost before downloading**

Print candidate count, locations, runs, variables, date range, expected calls, and provider limit. Exit non-zero when the configured limit is exceeded.

- [ ] **Step 6: Run checks**

```powershell
uv sync --project research --frozen
uv run --project research ruff check .
uv run --project research pyright
uv run --project research pytest research/tests/test_registry.py
```

- [ ] **Step 7: Commit**

`feat(research): add verified model registry`

---

### Task 2: Ingest ČHMÚ observation truth

**Files:**
- Create: `research/src/aladin_ensemble/sources/chmi_station.py`
- Create: `research/src/aladin_ensemble/sources/chmi_radar.py`
- Create: `research/src/aladin_ensemble/storage.py`
- Create: `research/tests/fixtures/chmi/`
- Create: `research/tests/test_chmi_sources.py`

**Interfaces:**
- Produces: canonical station and spatial precipitation `Observation` rows plus `source_manifest` records in SQLite.

- [ ] **Step 1: Add small licensed fixtures**

Store only short UTF-8 fixture excerpts and their source URLs. Include station metadata, hourly temperature/humidity/pressure/wind, hourly precipitation, and one radar/rain-gauge metadata record.

- [ ] **Step 2: Write parser RED tests**

Cover decimal format, missing flags, WIGOS ID, elevation, UTC conversion, cumulative versus interval precipitation, invalid values, and Czech characters.

- [ ] **Step 3: Implement streaming parsers**

Use `csv` and iterators. Do not load multi-year source files into memory. Convert documented missing values to `None`, never zero.

- [ ] **Step 4: Add deterministic SQLite storage**

Primary observation key:

```text
(source, station_id, valid_time, variable)
```

Use one transaction per source file. Reject duplicate rows with conflicting values.

- [ ] **Step 5: Add radar/rain-gauge contract**

Parse timestamps, bounding box, projection, interval, and file checksum. Keep raster files outside git. Do not implement image heuristics in this task.

- [ ] **Step 6: Run focused/full research checks and commit**

`feat(research): ingest Czech observations`

---

### Task 3: Download and align issued forecast runs

**Files:**
- Create: `research/src/aladin_ensemble/sources/open_meteo_runs.py`
- Create: `research/src/aladin_ensemble/align.py`
- Create: `research/tests/fixtures/forecast/`
- Create: `research/tests/test_forecast_runs.py`
- Create: `research/tests/test_alignment.py`

**Interfaces:**
- Produces: canonical `ForecastValue` rows keyed by model, run, valid time, point, and variable.

- [ ] **Step 1: Write RED run-integrity tests**

Test that a five-day lead comes from the original issued run, not a later stitched first hour. Reject naive timestamps, mismatched units, duplicate model/run/valid rows, and future truth leakage.

- [ ] **Step 2: Implement a cached downloader**

Use conditional HTTP requests where supported. Save response bytes by SHA-256. The manifest records request parameters but redacts any future credential.

- [ ] **Step 3: Align units and times**

Canonical units:

- temperature and dew point: °C;
- pressure: hPa;
- speed: km/h;
- direction: degrees clockwise from north;
- precipitation: mm per interval;
- probability and cloud/humidity: 0 through 1 internally.

- [ ] **Step 4: Join forecasts to truth**

Join station variables by station point and valid hour. Join precipitation rasters by valid interval and grid/sample point. Record distance, elevation difference, and truth-source type.

- [ ] **Step 5: Enforce train/holdout boundaries**

The alignment command refuses overlapping train and holdout dates. Write an explicit leakage regression test.

- [ ] **Step 6: Run checks and commit**

`feat(research): align issued forecast runs`

---

### Task 4: Implement metrics and locked baselines

**Files:**
- Create: `research/src/aladin_ensemble/metrics.py`
- Create: `research/src/aladin_ensemble/baselines.py`
- Create: `research/tests/test_metrics.py`
- Create: `research/tests/test_baselines.py`

**Interfaces:**
- Produces: per-variable/model/lead/region/elevation/season score tables.

- [ ] **Step 1: Add hand-calculated metric tests**

Cover MAE, RMSE, circular MAE, Brier score/decomposition, threshold contingency scores, weighted median, and Fractions Skill Score on tiny arrays.

- [ ] **Step 2: Implement baselines**

Evaluate every single model, Best Match, arithmetic mean, and median with the same missing-data mask. A baseline cannot receive a more favourable sample set than the candidate blend.

- [ ] **Step 3: Add bootstrap intervals**

Use deterministic block bootstrap seeds grouped by forecast date to retain temporal dependence. Report 95 percent intervals.

- [ ] **Step 4: Run checks and commit**

`feat(research): add forecast skill metrics`

---

### Task 5: Train constrained hierarchical blends

**Files:**
- Create: `research/src/aladin_ensemble/train.py`
- Create: `research/src/aladin_ensemble/fallback.py`
- Create: `research/tests/test_train.py`
- Create: `research/tests/test_fallback.py`

**Interfaces:**
- Produces: fitted segment weights, calibration parameters, fallback model, minimum-source count, and training diagnostics.

- [ ] **Step 1: Write RED constraint tests**

Assert weights are finite, non-negative, sum to one, deterministic, and never reference an ineligible model. Test sparse groups fall back through the exact hierarchy from the spec.

- [ ] **Step 2: Fit scalar and vector blends**

Use constrained regularised optimisation. Fit wind east/north components, then reconstruct speed/direction.

- [ ] **Step 3: Fit precipitation calibration**

Fit occurrence probability separately from positive amount. Keep regularisation selected only inside training folds.

- [ ] **Step 4: Record failure reasons**

Every excluded segment records insufficient sample, unstable fit, failed holdout, missing coverage, or licence failure. Do not silently omit it.

- [ ] **Step 5: Run checks and commit**

`feat(research): train calibrated Czech blends`

---

### Task 6: Evaluate holdout, publish report, and export Android weights

**Files:**
- Create: `research/src/aladin_ensemble/evaluate.py`
- Create: `research/src/aladin_ensemble/export.py`
- Create: `research/tests/test_evaluate.py`
- Create: `research/tests/test_export.py`
- Create: `docs/research/czech-ensemble-validation.md`
- Create: `app/src/main/assets/ensemble_weights.json`

**Interfaces:**
- Produces: schema-versioned Android artifact and English validation report.

- [ ] **Step 1: Lock the holdout before evaluation**

Write the train and holdout date ranges plus manifest hash before computing scores. The evaluator refuses an artifact trained after the lock file timestamp.

- [ ] **Step 2: Enforce ship rules**

For each segment, use the calibrated blend only when the bootstrap interval and regional degradation rule pass. Otherwise export the best eligible model.

- [ ] **Step 3: Export deterministic JSON**

Sort keys, use stable numeric rounding, include model/run-age contracts, minimum source count, scores, fallback, and manifest hash. Golden tests compare byte-for-byte output.

- [ ] **Step 4: Write the report**

Include source coverage, exclusions, sample counts, methods, leakage controls, baseline tables, region/elevation/lead results, confidence intervals, limitations, licences, and reproduction commands. Do not claim superiority for failed segments.

- [ ] **Step 5: Run every research check**

```powershell
uv run --project research ruff check .
uv run --project research pyright
uv run --project research pytest
```

- [ ] **Step 6: Commit**

`feat(research): publish Czech ensemble weights`
