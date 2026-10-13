# Wind-speed computation and independent observation coverage

## Decision

Use the median of available model speeds for the app's diagnostic hourly wind speed.
Keep the existing vector calculation for direction, the three-paired-source minimum and
confidence thresholds. Do not turn disagreement into calm wind. No learned weights are exported.

The untuned September comparison supports a pooled wind-speed improvement on six stations at
nominal 24-hour and 48-hour leads. It does not establish better cloud, rain or temperature
forecasts, worldwide superiority, or performance at other lead times and seasons.

## Defect and candidate

Previously, the app displayed the magnitude of the mean east/north wind vector. Opposing
directions reduced this number even when all models predicted a substantial wind speed.
Four 10 km/h winds from north, east, south and west produced approximately 0 km/h. The new
scalar estimator produces 10 km/h. Direction still expresses the existing circular consensus;
it is poorly determined when directions cancel. Model disagreement remains visible.

This is an empirical speed estimator, not a calibrated probability or a claim that the vector
mean is mathematically invalid. A vector mean optimizes a different quantity from scalar speed.

## Frozen comparison

- Period: 1–30 September 2026. No parameters fitted to this period.
- Candidate: median speed of exactly the same contributors used by the old vector estimator.
- Inputs: at least three finite speed/direction pairs, provider Best Match and independent truth.
- Forecast grid must be within 50 km of the requested station. No second elevation correction.
- Truth: NOAA GHCNh station sources 412/413, blank harmonized QC and speed measurement codes
  blank, C, N or V. Other measurement codes and QC failures are excluded.
- GHCNh speed is in m/s, converted once to km/h. Use native exact UTC hours, with calm and
  variable-wind speeds retained. Do not round off-hour METAR observations onto forecast hours.
- Compare Previous Runs `previous_day1` and `previous_day2` separately. These are nominal lead
  contracts; actual model initialization and public availability were not independently verified.
- Predeclared acceptance: both leads have a negative upper 95% paired error-difference bound
  under daily and consecutive seven-day blocks, no station MAE increase above 5%, and at least
  six stations covering at least 20 days. Bootstrap: 2,000 resamples, fixed seed 20260825.
- Seven stations were requested. New York had no accepted exact-hour modern-source wind truth;
  keep it as missing coverage, not a dry/calm value. No Oceania station enters this confirmation.

Actual runtime model IDs, without substituting the old AIFS alias:

```
icon_seamless, ecmwf_ifs025, ecmwf_aifs025_single, gfs_seamless,
gem_seamless, meteofrance_seamless, ukmo_seamless, cma_grapes_global,
jma_seamless, bom_access_global
```

These are numerical/AI forecast products distributed by Open-Meteo, not ten directly ingested
satellites. Best Match is a separate benchmark and is never counted as a blend contributor.

## Results

All six covered stations have 30 distinct UTC days. The same 4,170 observations support each
lead, so the two lead tests are dependent, not 8,340 independent observations.

| Station | GHCNh ID | Cases per lead | 24 h old MAE | 24 h new MAE | 48 h old MAE | 48 h new MAE |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Frankfurt | GMI0000EDDF | 680 | 4.479 | 4.072 | 4.843 | 4.387 |
| São Paulo | BRI0000SBGR | 720 | 4.581 | 4.282 | 4.636 | 4.253 |
| Nairobi | KEI0000HKJK | 610 | 3.425 | 3.383 | 3.398 | 3.338 |
| Delhi | INI0000VIDP | 720 | 4.185 | 3.726 | 4.491 | 3.866 |
| Tokyo Haneda | JAI0000RJTT | 720 | 6.861 | 6.909 | 7.452 | 7.283 |
| Vladivostok | RSI0000UHWW | 720 | 3.770 | 3.463 | 3.923 | 3.599 |
| Pooled | | 4,170 | 4.581 | 4.333 | 4.827 | 4.485 |

MAE is mean absolute speed error, in km/h. Pooled reductions are 5.4% and 7.1%.
Tokyo's 24-hour error increases by 0.047 km/h, about 0.7%; it is not a universal station win.
Best Match pooled MAE is 5.371 km/h at 24 hours and 5.527 km/h at 48 hours.

| Lead | New minus old MAE | Daily-block 95% interval | Seven-day-block 95% interval |
| --- | ---: | --- | --- |
| Nominal 24 h | -0.247951 | -0.311944 to -0.189763 | -0.347147 to -0.184419 |
| Nominal 48 h | -0.342189 | -0.414774 to -0.272574 | -0.411338 to -0.277307 |

An earlier cache-only diagnostic replay used explored July–August 2025 data and the older
worldwide model set. It found pooled MAE reductions of 0.184 km/h at 24 hours and 0.277 km/h at
48 hours across 6,574 cases per lead. That replay guided the new confirmation; it is not another
untouched holdout and has only two Frankfurt cases.

## Observation coverage correction

The existing GHCNh parser rejected modern source codes 412/413 even though NOAA documents them
as decoded surface-station SYNOP/BUFR and METAR observations. Add these two explicit source IDs.
Blank harmonized QC means no recorded failure flag; it does not prove perfect measurements.
Do not interpret a numeric legacy quality code as accepted quality for a modern source.

The corrected temperature parser recovered 708 exact synoptic-hour matches in a separate
1–30 August 2026 comparison. Mean MAE was 1.101773 °C, existing median MAE 1.101554 °C and Best
Match MAE 1.421186 °C. Mean-minus-median superiority was not established: the paired gain
interval includes zero. Keep the existing temperature estimator. New York contributed one case
and the requested Sydney annual file returned 404; no regional temperature weights are promoted.

## Precipitation training consistency

The positive-amount fitter minimizes error of a linear weighted sum. Prediction incorrectly
used those weights in a weighted median. With model amounts 1 and 9 mm, observation 3 mm and
weights 0.75/0.25, the fitted sum is 3 mm but the old prediction is 1 mm. Prediction now uses
the same linear estimator. Keep non-negative validation, positive-event sample minimums and
the separate occurrence gate. The regression fails before the fix and passes afterwards.
This is a training/prediction contract correction, not measured rain-forecast improvement.

## Provenance and limits

NOAA source contracts: [GHCNh source list, page 15](https://www.ncei.noaa.gov/oa/global-historical-climatology-network/hourly/doc/ghcnh-source-list.pdf)
and [GHCNh v1.1.0 documentation, wind units and QC](https://www.ncei.noaa.gov/oa/global-historical-climatology-network/hourly/doc/ghcnh_DOCUMENTATION.pdf).
Forecast contract: [Open-Meteo Previous Runs API](https://open-meteo.com/en/docs/previous-runs-api).

Requests use station-catalog coordinates, UTC Unix timestamps, the September start/end dates,
the model IDs above, and `wind_speed_10m_previous_day1/2,wind_direction_10m_previous_day1/2`.
Truth files follow `access/by-year/2026/psv/GHCNh_<station ID>_2026.psv` under NOAA's hourly base.
Raw inputs remain checksum-addressed in the local research cache. A malformed UKMO 48-hour
response was rejected, its raw bytes preserved, and its request repeated before scoring.

Evidence SHA-256:

```
September protocol: dfabcd1c97d7db45ed1d230adb86728166ee2a6bf7219376e3c0fbda75303b05
24-hour report: c2f1ee769c80fe49cf5d29bb251dbcc10ecc210e3535c07baaaa85215df14526
48-hour report: fb21c7ee863a0da45b64ecac4bff573946b017f7ba2d1354f63bde9875778b1e
```

The reports contain every observation/forecast payload hash. Airport exposure, unverified
individual wind-mast heights, one station per covered region, seasonal coverage and provider
run timing limit the conclusion. Czech ALADIN and sub-24-hour leads were not in this comparison.
No cloud/rain observational skill claim, regional learned-weight export or artificial confidence
increase follows from this study.
