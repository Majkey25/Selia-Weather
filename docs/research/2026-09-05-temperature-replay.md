# Temperature replay, 5 September 2026

An offline replay compared three global model families and their mean and median against
NOAA ISD station temperatures. The median improved pooled MAE over provider Best Match,
but it did not beat every individual model at every station. No weights were trained or exported.

This is a diagnostic replay of previously inspected dates, not a new untouched holdout.

## Scope and results

- Evaluation dates: 11 July through 24 August 2025, inclusive, 45 days.
- Valid time: exactly 12:00:00 UTC. Reports at other times were excluded without rounding.
- Variable: temperature at 2 m, in degrees Celsius.
- Inputs: `icon_seamless`, `ecmwf_ifs025`, `gfs_seamless`, plus `best_match` as a baseline.
- Horizon: Open-Meteo Previous Runs `previous_day1`, a nominal 24-hour horizon.
- Sample mask: all three models, Best Match, and valid station truth on the same station/date.
- Samples: 313 matched cases and 313 complete cases. No network requests.

| Forecast | Pooled MAE, °C | Pooled RMSE, °C |
|---|---:|---:|
| Best Match | 1.723323 | 2.273911 |
| Three-model median | 1.278275 | 1.645645 |
| Three-model arithmetic mean | 1.208520 | 1.589875 |

The paired difference in absolute error, Best Match minus median, was 0.445048°C.
The date-block bootstrap interval was 0.375563 to 0.538801°C at 95% confidence,
using 200 resamples and seed `20260825`. Each resampled date includes all its station cases.
The interval describes this sample and does not establish worldwide forecast superiority.

## Station results

Each region has one station. These are station results, not region-wide accuracy estimates.
All errors below are MAE in degrees Celsius.

| Station | NOAA ISD ID | Cases | Best Match | Median | Mean | Lowest individual-model MAE in this replay |
|---|---|---:|---:|---:|---:|---|
| Frankfurt | `10637099999` | 1 | 2.10000 | 1.70000 | 0.66667 | ECMWF 1.70000 |
| New York | `74486094789` | 45 | 1.17778 | 0.92889 | 0.88519 | ECMWF 0.93778 |
| São Paulo | `83075099999` | 45 | 1.32222 | 1.32000 | 1.34593 | ECMWF 1.26222 |
| Nairobi | `63740099999` | 42 | 1.97143 | 1.20714 | 1.02460 | ECMWF 1.27857 |
| Delhi | `42181099999` | 45 | 1.77556 | 1.45111 | 1.43926 | ECMWF 1.65111 |
| Tokyo | `47671099999` | 45 | 0.87556 | 0.77111 | 0.72815 | GFS 0.79778 |
| Moscow | `27515599999` | 45 | 1.39556 | 1.05111 | 0.96889 | ECMWF 1.16889 |
| Sydney | `94767099999` | 45 | 3.55333 | 2.20444 | 2.06741 | GFS 1.00444 |

Sydney is a counterexample to universal improvement. The median error was more than twice
the GFS error. The lowest individual-model column is descriptive selection on the evaluated
dates, not a training-selected fallback. Frankfurt's single case supports no accuracy conclusion.

## Source identity

The cohort is recorded locally in
`research/output/worldwide-2025-rainshift-20260901/worldwide-input-registry.json`.
Its SHA-256 is `83d3b3373971bc2ef134b546a7619053ae037e2ea80bedaa8f868c9b2b67a93f`.
The original dataset and date split are recorded in
`research/output/worldwide-2025-rainshift-20260901/dataset-manifest.json`.
That manifest belongs to the earlier larger diagnostic run, not this temperature-only sample.

Forecast requests cover 13 March through 24 August 2025 and use the six `WORLD_VARIABLES`
already cached by the worldwide research pipeline. This replay selects only temperature,
the later evaluation dates, and 12 UTC. The cached request manifests are under
`research/data/raw/open-meteo-worldwide/manifests`; raw payloads are addressed by checksum
under the adjacent `raw` directory. `CachedDownloader.cached_previous()` verifies their hashes.

| Source payload | Verified SHA-256 |
|---|---|
| ICON | `cc07211916ccee823ec26eb7d501b8fdd22e6768651410afaa9cb9add76fa5b4` |
| ECMWF IFS | `6027e32fb238cc14d2b9b9841eb591032a1425f70d4b27f59cfbc4d49d393b95` |
| GFS | `85455006cb4f98dc6a11bbdd3bdf6ef3bd29bc05f14c6a23a1976c7c3b1a20a0` |
| Best Match | `5b4d8962a560124d075c98e1196e461864da2378c8aef827566ff776ee9d2868` |
| NOAA ISD observations | `b9176015b2155bf7e4dddfb8deb8b44c79d1084d67f595987e5c3fb27bc84637` |
| NOAA station history | `1994747ab4af1b97e63adb434b4d0d022f2daee76f0c144ea9ab46be2d906604` |

The local NOAA observation file is
`research/data/raw/noaa-isd-worldwide/2215ba1811b9974dc8695f07048ba8620c2ad80aaf0bab4f00d28cc9f0c45adc.csv`.
Its name hashes the request URL, not its content. Its `.sha256` sidecar matches the content
hash above. Station metadata is `research/data/raw/noaa-isd-history/isd-history-20250828.csv`.

## Reproduction method

The replay uses existing research functions. It does not fit parameters or change runtime behavior.

1. Parse the station history with `parse_isd_station_history`, requiring coverage from
   `2025-03-13` through `2025-08-24`. Select the eight named `WORLD_TARGETS` with
   `select_isd_station_cohort(max_distance_km=250.0)`, preserving target order.
2. For each of the four input model IDs, construct `PreviousRunsRequest` with those station
   coordinates, `WORLD_VARIABLES`, the same source dates, and `lead_days=1`.
   Read only `CachedDownloader.cached_previous`; missing cache is an error and triggers no download.
3. Parse with `parse_previous_run_values(sample_hours=(12,))`. Keep canonical `temperature`
   rows within the evaluation dates. Check that nominal run-to-valid duration is 86,400 seconds.
4. Verify the NOAA CSV content hash. Keep rows in the evaluation dates whose timestamp ends
   exactly with `T12:00:00`. Use `parse_isd_observations` for quality checks and unit conversion,
   retaining `temperature_2m` observations.
5. Use `align_station_forecasts` to match station identity and exact UTC valid time.
   Group by station and date, reject duplicate model rows, and apply one common complete-case mask.
6. Use `evaluate_scalar_baselines` for source, mean, and median station scores. Compute pooled
   MAE and RMSE on the same 313 cases with `mean_absolute_error` and `root_mean_square_error`.
   Bootstrap paired absolute-error differences with `block_bootstrap_mean_interval`,
   `repetitions=200`, `seed=20260825`, and `confidence=0.95`.

The bounded offline runner is `research/scripts/temperature_replay_2025.py`.
It requires the cached raw files, request manifests, station metadata, and cohort registry listed
above. These historical inputs are not distributed with the repository. Missing or mismatched
inputs stop the replay. The runner never downloads replacements.

From the repository root, with the documented research environment:

```powershell
uv run --project research python research/scripts/temperature_replay_2025.py
```

The runner verifies the station metadata and cohort registry hashes as well as forecast and
observation payload hashes. It is an offline research replay, not an application service.

## Limits and release decision

- These dates were inspected in earlier research. This replay cannot approve a new calibrated fit.
- The three-model blend is a subset diagnostic, not a replay of every regional source requested by Android.
- No Czech station or ALADIN input is included. This result does not prove Czech forecast accuracy.
- Only temperature at one UTC hour was evaluated. Rain, wind, other hours, and longer leads remain untested here.
- Previous Runs parsing assigns a nominal run time from the requested horizon. Actual model
  initialization and availability timestamps were not independently verified.
- The largest matched model-grid-to-station distance was 22.151 km. Results do not demonstrate
  field-scale or 100-metre accuracy.
- The existing `summer` grouping means calendar months June through August. For southern
  hemisphere stations, it does not describe the local meteorological season.

Runtime calibration stays disabled. A change to learned weights requires adequate regional
observation coverage, verified issuance metadata, and a later untouched holdout.
