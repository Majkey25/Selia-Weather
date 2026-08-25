# Czech calibrated ensemble and complete weather detail design

## Objective

Build a Czech-focused forecast system that combines eligible European and global numerical weather models only when backtesting proves that the combination improves forecast skill. Add a clearly separated 24-hour forecast-precipitation map, detailed atmospheric data, and offline Moon information without making the main forecast screen dense.

This work does not average weather codes or call a forecast image "radar." ČHMÚ radar remains an observation product. Every future precipitation frame is labelled **Forecast precipitation**.

## Approved assumptions

- `ALADIN weather` remains free, open source, without subscriptions or advertising.
- Buy Me a Coffee remains an optional donation with no entitlement or priority.
- The Huawei device connected to this workspace is authorised for app installation and functional QA.
- Model candidates can receive a zero weight. "Use all models" means evaluate every eligible model that covers Czechia, not force every output into the result.
- The validation report is a reproducible technical report. It is not presented as peer-reviewed research.
- A simple arithmetic mean is only a benchmark. It is not the production method.
- The production app does not claim "most accurate" unless the locked holdout results support that claim.

## Definitions

- **Observation:** a station or radar measurement.
- **Deterministic model:** one numerical forecast run, such as ALADIN CZ or IFS HRES.
- **Ensemble member:** one perturbed run from an ensemble prediction system.
- **Calibrated blend:** a constrained combination trained against independent observations.
- **Radar:** ČHMÚ measured reflectivity or derived measured precipitation.
- **Forecast precipitation:** future precipitation estimated from model data.
- **Lead time:** elapsed time between model initialisation and forecast validity.

## Data sources

### Forecast candidates

The research registry probes every candidate at Czech sample coordinates and records exact API identifiers, domain coverage, run age, variables, resolution, and horizon.

The initial candidate families are:

- ČHMÚ ALADIN CZ 1 km and ALADIN Central Europe 2.3 km;
- DWD ICON-D2, ICON-EU, and ICON global;
- MeteoSwiss ICON-CH1 and ICON-CH2 where the published domain covers the sample point;
- GeoSphere AROME Austria where coverage exists;
- DMI and KNMI HARMONIE AROME Europe where coverage exists;
- Météo-France ARPEGE Europe;
- ECMWF IFS HRES, IFS 0.25°, and AIFS 0.25°;
- UK Met Office global;
- NOAA GFS;
- GEM global.

The registry can add a model only after a coverage probe and an archived-run availability check pass. A model with less than 90 percent coverage for its intended Czech segment is excluded from that segment.

ALADIN contributes only inside its published horizon. It does not receive synthetic values after its data ends.

### Independent truth

Use ČHMÚ data as the primary truth source:

- hourly and ten-minute automated station observations;
- station metadata with coordinates and elevation;
- ČHMÚ hourly merged radar and rain-gauge precipitation products;
- ČHMÚ radar composites for spatial precipitation verification.

Do not use a stitched model analysis as the only truth source for a model-accuracy claim. Model analyses can fill documented gaps, but the report must separate those samples.

### Forecast archives

Use full issued runs or fixed previous-run lead times. Do not evaluate a five-day forecast against the first hours stitched from later runs.

- Single Runs API: original run and full horizon.
- Previous Runs API: fixed lead-time evaluation.
- Historical Forecast API: availability checks and secondary comparison only.

## Licence and service gate

Open-Meteo Free API is limited to non-commercial use and fewer than 10,000 calls per day. The release can use it only while the project qualifies under those terms and remains within all limits.

Before a public Play rollout with the new ensemble, satisfy one of these conditions:

1. obtain written confirmation that this free, ad-free, subscription-free app with an optional no-benefit donation qualifies as non-commercial;
2. use a paid commercial endpoint;
3. self-host the required Open-Meteo services;
4. ingest commercially compatible provider open data directly.

The research downloader records attribution and source licence for every dataset. A missing or incompatible licence excludes the source.

No API key enters source control, Android resources, logs, screenshots, reports, or release assets.

## Backtest design

### Geography

Sample Czechia across:

- all 14 administrative regions;
- low elevation below 300 m;
- middle elevation from 300 through 700 m;
- high elevation above 700 m;
- urban, rural, mountain, and border locations.

Station samples are joined by WIGOS identifier, coordinates, elevation, and observation timestamp.

### Time split

- Use at least 90 complete days for any trained segment.
- Prefer 180 days when all candidate archives permit it.
- Keep the newest 30 complete days as a locked chronological holdout.
- Never fit weights on the holdout.
- Record model-cycle changes and do not silently join incompatible cycles.

### Lead buckets

- 0 through 6 hours;
- 7 through 24 hours;
- 25 through 72 hours;
- day 4 through day 7;
- day 8 through day 14.

### Seasonal and terrain grouping

Fit a segment only when it has enough independent samples. Otherwise shrink to the next broader level:

1. variable, lead, season, region, elevation;
2. variable, lead, season, elevation;
3. variable and lead;
4. global variable weights;
5. best eligible single model.

The fallback chain prevents sparse regional weights from overfitting.

## Calibration methods

### Continuous scalar values

Temperature, dew point, pressure, humidity, visibility, cloud cover, wind speed, and gusts use non-negative regularised least squares with weights constrained to sum to one.

Compare against:

- each eligible single model;
- Open-Meteo Best Match;
- the unweighted mean;
- the unweighted median.

### Wind direction

Convert direction and speed to eastward and northward components. Blend components, then convert back to speed and circular direction. Never average degree values directly.

### Precipitation probability

Fit a regularised logistic calibration using model probabilities and recent radar evidence only for lead times where radar extrapolation is valid. Evaluate with Brier score, reliability, resolution, and event-frequency calibration.

### Precipitation amount

Use a zero-inflated method:

1. estimate occurrence probability;
2. combine positive amounts with a constrained weighted median or quantile blend;
3. calibrate accumulation bias by lead bucket and season.

Evaluate station precipitation with MAE and threshold scores. Evaluate spatial precipitation with Fractions Skill Score against ČHMÚ radar/rain-gauge composites.

### Weather condition

Derive the weather condition from blended continuous variables and calibrated precipitation state. Do not average WMO weather codes.

### Confidence

Expose confidence from model spread, source count, run age, missing-data fraction, and historical segment error. Confidence is not a probability of "correct weather."

## Acceptance for production weights

A calibrated segment ships only when:

- its locked holdout improves the selected primary score over the best single candidate with a 95 percent bootstrap confidence interval above zero;
- no Czech region degrades its primary score by more than five percent without an explicit fallback;
- calibration is stable across at least two time folds;
- missing-model fallback reproduces a valid result;
- the weight vector and training manifest are versioned and reproducible.

If a blend does not pass, ship the best eligible single model for that variable and lead bucket.

## Runtime forecast architecture

### Model payload

The Android app requests aligned hourly series for eligible models. Every model value includes:

- provider and model ID;
- run initialisation time;
- validity time;
- grid resolution;
- value and unit;
- missing/stale state.

Do not blend values from incompatible validity times. A stale run is excluded according to its model update schedule.

### Weight artifact

`app/src/main/assets/ensemble_weights.json` contains:

- schema version;
- research dataset manifest hash;
- generated timestamp;
- variables and lead buckets;
- segment selectors;
- eligible model IDs and weights;
- holdout scores and fallback model;
- minimum source count.

The app validates the artifact before use. Invalid weights fall back to the existing supported forecast path and report a local, secret-free diagnostic.

### Missing data

- Exclude missing and stale values.
- Renormalise remaining weights.
- Require the segment's minimum model count.
- Fall back to the recorded best model when the minimum is not met.
- Never replace a missing value with zero.

### Caching and refresh

- Cache raw model payloads with run timestamps.
- Recompute the blend when a newer eligible run arrives.
- Keep the last valid forecast for offline display.
- Show source age and degraded-data state.
- Bound all caches by location count and expiry.

## Precipitation visualisation

### Observed tab

Keep the existing ČHMÚ radar and short nowcast. Display measured and nowcast frames with their current labels.

### Forecast tab

Add **Forecast precipitation · 1–24 h**. It uses calibrated model precipitation and never uses the word radar.

The map consumes a static rolling tile contract:

- timestamped manifest;
- Czech bounding box and zoom levels;
- model/weight version;
- 24 hourly frames;
- WebP or PNG tiles;
- source age and confidence metadata.

A public-repository GitHub Actions proof-of-concept may generate and deploy rolling static tiles without committing forecast binaries to git history. Before production, verify action time, bandwidth, API-call count, licence, update latency, and uptime. If the static service gate fails, the app must not fake a national 24-hour map. It can still show the selected-location 24-hour precipitation timeline.

## Complete weather detail

The home screen remains restrained: current summary, 20-hour strip, 14-day rows, and clear navigation.

The detailed screen groups values by meaning:

### Now

- actual and apparent temperature;
- weather condition;
- humidity and dew point;
- sea-level and surface pressure;
- visibility;
- cloud cover total, low, middle, and high.

### Precipitation and convection

- probability and amount;
- rain, showers, snowfall, and snow depth when available;
- freezing level;
- CAPE, lifted index, convective inhibition, and lightning potential when available;
- ensemble spread and confidence.

### Wind

- speed, direction, and gusts;
- localised compass label;
- vector arrow;
- hourly wind timeline and daily maximum.

### Sun and radiation

- sunrise, sunset, daylight, and sunshine duration;
- UV index and clear-sky UV;
- shortwave, direct, diffuse, and terrestrial radiation when available.

### Moon

- phase name;
- illuminated percentage;
- waxing or waning state;
- moonrise and moonset;
- altitude and azimuth;
- bright-limb/parallactic orientation rendered on a custom Canvas;
- next new moon and full moon.

Use offline astronomy calculations. `commons-suncalc` is the preferred candidate because it is Apache-2.0, Android-compatible from API 26, and has no runtime dependencies. Pin the exact version after API and licence verification.

The Moon graphic is a calculated vector/canvas visual. Do not use remote Moon images.

### Availability

Show **Unavailable for this model/horizon** when a source does not provide a variable. Do not substitute zero or hide missingness behind a generic weather code.

## Widget changes

Keep the existing Material, minimal, per-widget editor. Add optional fields only:

- dew point;
- pressure;
- visibility;
- UV index;
- gusts;
- Moon phase and illumination;
- ensemble confidence;
- forecast source age.

Existing widgets retain their settings. New fields default off. The widget renderer keeps its bounded worker, bounded bitmap, and adaptive-size rules.

## Research project structure

- `research/pyproject.toml`: isolated exact Python dependencies and quality tools.
- `research/src/aladin_ensemble/sources/`: forecast and observation adapters.
- `research/src/aladin_ensemble/align.py`: run/valid-time/station alignment.
- `research/src/aladin_ensemble/metrics.py`: scalar, circular, probability, and spatial scores.
- `research/src/aladin_ensemble/train.py`: constrained calibration and fallback hierarchy.
- `research/src/aladin_ensemble/evaluate.py`: locked holdout evaluation and bootstrap intervals.
- `research/src/aladin_ensemble/export.py`: deterministic Android weight artifact.
- `research/src/aladin_ensemble/tiles.py`: forecast precipitation tile proof-of-concept.
- `research/tests/`: parser, alignment, leakage, metrics, fitting, fallback, and export tests.
- `research/data/raw/`: ignored downloaded inputs.
- `research/data/derived/`: ignored intermediate datasets.
- `research/output/`: reproducible tables and plots, ignored unless selected for the report.
- `docs/research/czech-ensemble-validation.md`: English technical report.
- `app/src/main/assets/ensemble_weights.json`: reviewed production artifact.

## Commands

Android checks:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleRelease :app:bundleRelease --console=plain
```

Research checks, after the research package is created:

```powershell
uv sync --project research --frozen
uv run --project research ruff check .
uv run --project research pyright
uv run --project research pytest
```

The plan defines the exact downloader, training, evaluation, and export CLI arguments after the source feasibility audit establishes available dates and identifiers.

## Code style

Use explicit typed records at every source boundary. Keep units and validity times attached to values.

```python
@dataclass(frozen=True, slots=True)
class ForecastValue:
    model_id: str
    run_time: datetime
    valid_time: datetime
    variable: str
    value: float | None
    unit: str
```

Python code follows `research/pyproject.toml`, Ruff, and Pyright. Android code follows the existing Kotlin and Compose style. Do not create factories, managers, or interfaces with one implementation.

## Testing strategy

### Research

- Parser fixtures for every provider format.
- Timestamp, timezone, DST, and unit alignment tests.
- No-leakage tests for train and holdout ranges.
- Metric tests with hand-calculated results.
- Weight constraints and deterministic seed tests.
- Missing-model and stale-run fallback tests.
- Golden weight artifact and report-table tests.
- A small end-to-end fixture before any large download.

### Android

- Weight schema and fallback tests.
- Model alignment and unit tests.
- Forecast blending tests per variable class.
- Confidence and missing-data tests.
- Moon reference-date, phase, orientation, and rise/set tests.
- Compose/runtime tests for detail groups and unavailable values.
- Widget migration and optional-field tests.
- Huawei and emulator QA for the final signed release.

### Operational

- Source freshness and coverage monitor.
- Tile manifest and frame-completeness validation.
- API-call budget and licence check.
- Attribution and source-age display.

## Boundaries

Always:

- label observation, nowcast, and forecast separately;
- validate against independent Czech observations;
- retain run and validity timestamps;
- keep weights non-negative and reproducible;
- show missing/degraded data honestly;
- keep model/source attribution;
- keep Android 10 support;
- keep the home UI minimal;
- use only authorised devices for QA.

Ask before:

- paying for an API or hosting service;
- deploying a persistent backend;
- changing the provider licence posture;
- collecting telemetry from app users;
- claiming the ensemble is the most accurate Czech forecast.

Never:

- average weather codes;
- average wind directions as degrees;
- call a 24-hour model map radar;
- train and evaluate on the same dates;
- use model output as the sole truth for the same model;
- hide missing models by writing zero;
- publish secrets or API keys;
- exceed a provider's free-use or licence terms.

## Success criteria

- The research pipeline reproduces from a locked dependency set and manifest.
- All eligible Czech model candidates are probed, scored, and either weighted or explicitly excluded.
- The locked holdout report compares every shipped blend with every candidate, Best Match, mean, and median.
- A shipped segment meets the production-weight acceptance rules or falls back to the best single model.
- The Android app computes the same golden blend as the research exporter.
- Model failures renormalise or fall back without crashes or zero substitution.
- The UI shows source age, model count, spread, and confidence.
- Observed radar and 1–24-hour forecast precipitation are visually and textually distinct.
- The complete detail screen exposes every listed common variable without cluttering home.
- Moon phase, illumination, rise/set, and orientation pass reference-date tests and render offline.
- New widget fields remain optional and old widget settings migrate.
- Licence and API-budget gates pass before public Play rollout.
- Final signed builds pass API 29, API 35, and authorised Huawei acceptance.

## Open questions

None. The user approved this design direction on 25 August 2026. Source feasibility, licence, and accuracy are implementation gates with defined fail-closed behaviour.
