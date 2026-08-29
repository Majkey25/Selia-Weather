# Czech calibrated ensemble and complete weather detail design

## Objective

Build a Czech-focused forecast system that combines eligible European and global numerical weather models only when backtesting proves that the combination improves forecast skill. Add a clearly separated 24-hour forecast-precipitation map, detailed atmospheric data, and offline Moon information without making the main forecast screen dense.

This work does not average weather codes or call a forecast image "radar." ČHMÚ radar remains an observation product. Every future precipitation frame is labelled **Forecast precipitation**.

## Approved assumptions

- `ALADIN weather` remains free to install and open source. The free tier may show limited consent-gated ads. A lifetime purchase or monthly Premium removes ads.
- Buy Me a Coffee remains an optional donation with no entitlement or priority.
- The Huawei device connected to this workspace is authorised for app installation and functional QA.
- Model candidates can receive a zero weight. "Use all models" means evaluate every eligible model that covers Czechia, not force every output into the result.
- The Android app does not download raw satellite scenes or every European station record. Operational numerical models already assimilate satellite, station, aircraft, buoy, and radar observations. The app downloads aligned model output for one selected coordinate and current Czech observations that can correct that point.
- The local model is deterministic Kotlin code with versioned calibration data. It does not use an LLM to invent weather values.
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

### Runtime horizon tiers

The app treats forecast horizons as separate products because model resolution and skill change with lead time.

- **Now through 6 hours:** Correct the latest model state with fresh ČHMÚ station observations. Use measured radar and a labelled short nowcast for precipitation where the product is available.
- **7 through 48 hours:** Blend every eligible high-resolution Czech or European model that covers the point and variable. Require at least five independent model families unless the recorded fallback applies.
- **Hour 49 through day 15:** Blend eligible European and global deterministic models. Require at least three independent model families unless the recorded fallback applies.
- **Day 16 through day 35:** Use extended ensemble means and member spread. Show daily ranges, precipitation risk, and trend confidence. Do not show precise hourly conditions or deterministic weather claims.

The 35-day tier is a probabilistic outlook. It cannot extend a short-range model by interpolation, repetition, or an LLM-generated value.

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

Open-Meteo Free API is limited to non-commercial use and fewer than 10,000 calls per day. An advertising or subscription release cannot use that free endpoint.

Before a public monetized Play rollout, satisfy one of these conditions:

1. use a paid commercial endpoint whose credentials are not embedded in the Android client;
2. self-host the required Open-Meteo services;
3. ingest commercially compatible provider open data directly.

Production ads and paid products remain disabled until this licence gate passes.

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

### On-device computation contract

The Android runtime performs the final point calculation. The research pipeline trains and validates calibration data, but it does not precompute a forecast for a user location.

For each validity time and variable, the runtime:

1. validates the model ID, run time, validity time, unit, and finite value;
2. rejects stale, missing, out-of-domain, and physically invalid values;
3. converts accepted values to canonical metric units;
4. selects the calibrated segment by variable, lead time, season, region, and elevation;
5. renormalises weights across available sources when the minimum source count remains satisfied;
6. calculates scalar values, wind vectors, and precipitation through their variable-specific methods;
7. returns the contributor count, model spread, source age, historical error, and fallback reason with the value.

The runtime does not average WMO codes. It derives the condition from the calculated temperature, cloud, precipitation, snow, visibility, and convective state.

The runtime stores no unbounded model history. It keeps only the latest valid run per model and location, the last valid blended forecast, and a bounded previous result for offline fallback.

## Acceptance for production weights

A calibrated segment ships only when:

- its locked holdout improves the selected primary score over the best single candidate with a 95 percent bootstrap confidence interval above zero;
- no Czech region degrades its primary score by more than five percent without an explicit fallback;
- calibration is stable across at least two time folds;
- missing-model fallback reproduces a valid result;
- the weight vector and training manifest are versioned and reproducible.

If a blend does not pass, ship the best eligible single model for that variable and lead bucket.

## Runtime forecast architecture

### Point forecast pipeline

The client requests only the selected WGS84 coordinate. It does not download European grids.

- The short and medium-range request returns separate time series for each eligible deterministic model.
- The extended request returns ensemble means and spread through day 35.
- The ČHMÚ request selects nearby station IDs locally and downloads their current public observations.
- The parser retains provider and model IDs. A combined provider response must never lose the source boundary.
- The blend runs after all bounded requests finish or time out. One failed provider does not cancel usable sources.
- The cache key includes the coordinate, model ID, model run, variable set, and unit schema.

The implementation reuses the existing blocking repository entry point because callers already run it away from the main thread. Network requests stay bounded by explicit connection, read, and overall refresh timeouts.

### Runtime result metadata

`WeatherSnapshot` gains one compact metadata object for the calculated forecast:

- calculation schema and calibration version;
- contributing model count by horizon;
- oldest and newest contributing run time;
- spread and confidence band;
- source state: calibrated blend, best-model fallback, cached fallback, or extended outlook;
- degraded reason when a source, variable, or calibration segment is unavailable.

The UI shows this metadata in forecast details. The home screen keeps one short source and confidence label.

### Exact saved points

Locations are first-class WGS84 coordinates, not only geocoded cities. The location flow adds
**Choose point on map** alongside search and device location. A user can place a pin, enter a
custom label such as a field name, preview latitude and longitude, then save and use the point.
Existing favourites remain compatible because they already persist name, region, latitude, and
longitude.

- Reject non-finite coordinates and points outside the supported Czech map bounds.
- Keep the current 12-location bound and coordinate-based duplicate matching.
- Preserve the selected WGS84 coordinate in requests and cache keys.
- Record the actual model grid point, elevation, and native resolution returned by each source.
- Show when a provider selects a nearby land/elevation grid cell instead of the literal pin.
- Do not claim field-scale or 100 m forecast precision from a 1–25 km model grid.
- For precipitation, expose calibrated probability, amount, spread, and the smallest historically
  skilful neighbourhood scale. A deterministic yes/no field-rain claim is not allowed.
- Use observed ČHMÚ radar/MERGE and short nowcast for the nearest available 1 km evidence; keep it
  visibly separate from later numerical-model precipitation.

The picker must always allow direct coordinate entry. An interactive tile map ships only with a
documented provider, attribution, caching, and acceptable mobile-use terms; otherwise the
coordinate entry plus the existing ČHMÚ georeferenced map is the fail-closed path.

### Measurement units

Settings offers two explicit display presets. **Metric** is the default and uses °C, km/h, mm,
hPa, and kilometres. **Imperial** uses °F, mph, inches, inHg, and miles. Raw API data, caches, research
inputs, ensemble weights, and calculations remain in canonical metric units. Conversion happens
only in the shared display formatter, including widgets, so changing units never changes model
inputs or invalidates calibration.

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
- The 0 through 48-hour runtime uses at least five independent model families when the validated segment and source availability permit it.
- The hour 49 through day 15 runtime uses at least three independent model families when the validated segment and source availability permit it.
- The day 16 through day 35 screen shows only probabilistic daily outlook data with ensemble spread and a clear low-confidence label.
- The Android app computes the same golden blend as the research exporter.
- Model failures renormalise or fall back without crashes or zero substitution.
- The UI shows source age, model count, spread, and confidence.
- Every calculated value can report its calibration version, contributors, and fallback state without exposing secrets.
- Observed radar and 1–24-hour forecast precipitation are visually and textually distinct.
- The complete detail screen exposes every listed common variable without cluttering home.
- Moon phase, illumination, rise/set, and orientation pass reference-date tests and render offline.
- New widget fields remain optional and old widget settings migrate.
- Licence and API-budget gates pass before public Play rollout.
- Final signed builds pass API 29, API 35, and authorised Huawei acceptance.

## Open questions

None. The user approved the calibrated ensemble direction on 25 August 2026 and the on-device 35-day extension on 29 August 2026. Source feasibility, licence, and accuracy are implementation gates with defined fail-closed behaviour.

## Primary technical references

- [ECMWF data assimilation](https://www.ecmwf.int/en/research/data-assimilation) explains how operational forecasts combine satellite and in-situ observations with a short-range model state.
- [Open-Meteo Forecast API](https://open-meteo.com/en/docs) documents explicit multi-model selection and a maximum 16-day deterministic forecast.
- [Open-Meteo Ensemble API](https://open-meteo.com/en/docs/ensemble-api) documents ensemble members and forecast horizons up to 36 days.
- [Open-Meteo Ensemble Mean API](https://open-meteo.com/en/docs/ensemble-mean-api) documents extended ensemble means up to 35 days.
