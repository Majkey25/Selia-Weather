# Czech ensemble validation status

## Result

The calibrated Czech ensemble is not ready for nationwide production. The repository deliberately
does not contain `app/src/main/assets/ensemble_weights.json`.

The first locked Previous Runs backtest completed on 26 August 2026. It used 90 training dates
(2 April through 30 June), a separate 30-date holdout (1 through 30 July), 15 Czech stations,
276 immutable Open-Meteo payloads, 120 ČHMÚ monthly truth payloads, and 496,800 parsed forecast
values. Every input is recorded by SHA-256 in the generated dataset manifest.

Six of 21 scalar lead segments passed every holdout rule:

- temperature at 24, 48, 72, and 120 hours;
- wind speed at 24 and 168 hours.

All seven precipitation blends failed. They were worse than or statistically indistinguishable
from the training-selected fallback and often degraded at least one region. They must not ship.
Temperature truth covered 10 selected stations and wind truth covered 5, while precipitation
covered all 15. The accepted temperature and wind results are therefore evidence for those station
cohorts, not proof of nationwide Czech accuracy. Wind direction was not evaluated by this scalar
run and still requires the existing vector fitter.

One archived CHMI ALADIN CZ value reported non-physical `-0.2 mm` precipitation at a 48-hour lead.
The Previous Runs parser records such values as missing; the live forecast parser remains strict
and rejects negative precipitation.

## Nationwide truth rerun status

The 28 August rerun fixed a station-selection error before fitting new weights. The old selector
required the correct ČHMÚ element names but ignored their measurement heights. The parser then
kept measurements at 1.99–2.06 m and 9.55–10.56 m under their exact-height names, so the backtest
discarded valid near-standard temperature and wind observations.

The corrected selector accepts measurements within 10 percent of the standard 2 m temperature
and 10 m wind heights. It rejects non-standard 15–21 m wind sensors and selects another regional
station. The same April through July truth audit now contains 172,800 observations. All 15 selected
stations contain 2,880 values for each of temperature, wind speed, wind direction, and hourly
precipitation. The previous audit contained 100,800 observations, with temperature at 10 stations
and wind at 5 stations.

The fixed-lead preflight completed all 112 immutable Open-Meteo requests after bounded
`Retry-After` backoff. The downloader resumes from verified cache without network calls or pacing
delays. The dataset contains 806,400 forecast values.

Five temperature segments passed the diagnostic gate. All precipitation segments failed. All wind
speed segments failed, mainly because at least one region degraded by more than 5 percent. These
results must not ship because the July holdout was already inspected before the station-selection
fix. A new untouched 30-day holdout is required.

### Nationwide diagnostic results

| Variable | Lead | Samples | Blend MAE | Training fallback | Fallback MAE | Gate |
|---|---:|---:|---:|---|---:|---|
| Precipitation | 24 h | 450 | 0.2268 mm | KNMI HARMONIE | 0.2313 mm | Reject |
| Precipitation | 48 h | 450 | 0.2382 mm | CMC GEM | 0.2222 mm | Reject |
| Precipitation | 72 h | 450 | 0.2373 mm | ICON Global | 0.2467 mm | Reject |
| Precipitation | 96 h | 450 | 0.2368 mm | Best Match | 0.1918 mm | Reject |
| Precipitation | 120 h | 450 | 0.2426 mm | CMC GEM | 0.2044 mm | Reject |
| Precipitation | 144 h | 450 | 0.2248 mm | GFS | 0.1980 mm | Reject |
| Precipitation | 168 h | 450 | 0.2207 mm | GFS | 0.1844 mm | Reject |
| Temperature | 24 h | 450 | 1.0633 °C | Best Match | 1.2489 °C | Pass |
| Temperature | 48 h | 450 | 1.2585 °C | ECMWF AIFS | 1.3380 °C | Reject |
| Temperature | 72 h | 450 | 1.3825 °C | ECMWF AIFS | 1.5356 °C | Pass |
| Temperature | 96 h | 450 | 1.5656 °C | ECMWF IFS | 1.7844 °C | Pass |
| Temperature | 120 h | 450 | 1.8991 °C | ECMWF IFS 0.25° | 2.1062 °C | Pass |
| Temperature | 144 h | 450 | 2.1985 °C | ECMWF IFS 0.25° | 2.6136 °C | Pass |
| Temperature | 168 h | 450 | 2.5304 °C | ECMWF IFS 0.25° | 2.8522 °C | Reject |
| Wind speed | 24 h | 450 | 3.8311 km/h | ICON-EU | 3.9748 km/h | Reject |
| Wind speed | 48 h | 450 | 3.9749 km/h | Best Match | 4.0871 km/h | Reject |
| Wind speed | 72 h | 450 | 3.9982 km/h | Best Match | 4.0812 km/h | Reject |
| Wind speed | 96 h | 450 | 4.1665 km/h | Best Match | 4.7272 km/h | Reject |
| Wind speed | 120 h | 450 | 4.1205 km/h | ECMWF AIFS | 4.3495 km/h | Reject |
| Wind speed | 144 h | 450 | 4.1552 km/h | ECMWF AIFS | 4.6274 km/h | Reject |
| Wind speed | 168 h | 450 | 4.4381 km/h | ECMWF AIFS | 4.6884 km/h | Reject |

The diagnostic artifacts have these SHA-256 hashes:

- dataset manifest: `f3a795b2ce759edcc30d1c1d82a2dc907ba99ec35aaedcb6c05324ef532573d0`.
- holdout lock: `ce3b49e958e521fee6979d0ccc95a5c0a7f584c1a050596579bfedd5ac59b15c`.
- report: `49ea6e49687b92963b7bd2fff177301e2564c20e3516e96a19421fbbda8de57f`.

The east/north wind-vector diagnostic rejected all seven leads. Every lead degraded at least one
region by more than 5 percent. The maximum regional degradation ranged from 5.12 percent at 48
hours to 18.95 percent at 168 hours. The wind-vector report SHA-256 is
`f9c1a8192eef72dda8e1b8d0bd93e5268a87df02cc4411b7ccad4493eca9cc54`.

### Historical partial-cohort results

The following table describes the 26 August run.

| Variable | Lead | Holdout samples | Blend MAE | Training-selected fallback | Fallback MAE | Ship |
|---|---:|---:|---:|---|---:|---|
| Precipitation | 24 h | 450 | 0.2234 mm | KNMI HARMONIE | 0.2033 mm | No |
| Precipitation | 48 h | 450 | 0.2218 mm | CMC GEM | 0.2216 mm | No |
| Precipitation | 72 h | 450 | 0.2293 mm | ARPEGE Europe | 0.2047 mm | No |
| Precipitation | 96 h | 450 | 0.1996 mm | Best Match | 0.1924 mm | No |
| Precipitation | 120 h | 450 | 0.2317 mm | GFS | 0.2042 mm | No |
| Precipitation | 144 h | 450 | 0.2170 mm | GFS | 0.1867 mm | No |
| Precipitation | 168 h | 450 | 0.2133 mm | GFS | 0.1760 mm | No |
| Temperature | 24 h | 300 | 1.0865 °C | Best Match | 1.2643 °C | Yes |
| Temperature | 48 h | 300 | 1.3492 °C | Best Match | 1.4910 °C | Yes |
| Temperature | 72 h | 300 | 1.5302 °C | ECMWF AIFS | 1.7243 °C | Yes |
| Temperature | 96 h | 300 | 1.6382 °C | ECMWF IFS | 1.8393 °C | No |
| Temperature | 120 h | 300 | 1.9010 °C | ECMWF IFS 0.25° | 2.1160 °C | Yes |
| Temperature | 144 h | 300 | 2.2245 °C | ECMWF AIFS | 2.1307 °C | No |
| Temperature | 168 h | 300 | 2.5329 °C | Best Match | 2.8673 °C | No |
| Wind speed | 24 h | 150 | 4.3190 km/h | ICON-EU | 4.6839 km/h | Yes |
| Wind speed | 48 h | 150 | 4.4903 km/h | ECMWF AIFS | 4.6443 km/h | No |
| Wind speed | 72 h | 150 | 4.4294 km/h | ECMWF AIFS | 4.6315 km/h | No |
| Wind speed | 96 h | 150 | 4.5102 km/h | ECMWF AIFS | 4.6573 km/h | No |
| Wind speed | 120 h | 150 | 4.4831 km/h | ECMWF AIFS | 4.5849 km/h | No |
| Wind speed | 144 h | 150 | 4.1529 km/h | ECMWF AIFS | 4.6715 km/h | No |
| Wind speed | 168 h | 150 | 4.4792 km/h | ECMWF AIFS | 4.8928 km/h | Yes |

The live model probe on 28 August 2026 checked 17 candidates at 7 Czech points for 3 required
variables. The final bounded retry budget was 85 HTTP requests, below the configured limit of
10,000.

- 15 candidates passed the coverage and archive checks.
- MeteoSwiss ICON-CH1 and ICON-CH2 returned HTTP 400 and were excluded from this Czech cohort.
- No candidate ended with an operational failure.
- The registry status is `complete`.

The first burst probe produced transient `WinError 10054` resets. A single-candidate check passed.
The final probe used a 0.5 second pause between candidates and one bounded retry only for
operational failures. The budget includes both attempts. Definitive HTTP 400 responses were not
retried.

## Available evidence

The research package now implements these checks:

- a provider model registry with coverage, horizon, licence, and request-budget gates;
- typed ČHMÚ station observations and the 1 km MERGE radar and rain-gauge precipitation product;
- immutable Open-Meteo Single Runs payloads that retain the original run time;
- UTC alignment between issued forecasts and independent ČHMÚ observations;
- MAE, RMSE, circular MAE, Brier decomposition, contingency scores, and Fractions Skill Score;
- single-model, Best Match, mean, and median baselines on one complete-case sample mask;
- constrained non-negative scalar weights and wind weights fitted in east and north components;
- separate logistic occurrence calibration and positive precipitation amount fitting;
- deterministic date-block bootstrap intervals;
- an immutable holdout lock with at least 90 training days and 30 holdout days;
- export rules that reject an incomplete registry and any segment that fails a ship rule.

The small issued-run fixture still proves only the parser contract. Long-term accuracy evidence now
comes from the separate 90-day training and 30-day holdout run above.

## Acceptance rules

A segment can export a blend only when all of these conditions pass:

- the locked holdout includes at least 30 distinct forecast dates;
- the 95 percent bootstrap interval for improvement over the best single model is above zero;
- no Czech region degrades by more than 5 percent;
- at least two training folds improve;
- the missing-model fallback produces a valid forecast;
- every referenced model exists in a complete registry.

If a segment fails a rule, the export contract selects its recorded best single model. The export
stores the rejection reasons, sample count, score interval, fallback model, minimum source count,
model resolution, and maximum run age.

## Work required before production

1. After 30 August is complete, lock an untouched August holdout and evaluate the corrected
   15-station cohort without changing the fitted method.
2. Keep the wind-vector method fixed for the untouched holdout. Run precipitation through the
   separate occurrence and positive-amount pipeline.
3. Lock a new untouched holdout before changing any failed model or segment selection.
4. Re-run the source licence gate before any release with advertising or paid features.
5. Export `ensemble_weights.json` only when nationwide coverage and every artifact rule pass.

Open-Meteo documents issued model runs in the
[Single Runs API](https://open-meteo.com/en/docs/single-runs-api) and fixed lead comparisons in the
[Previous Runs API](https://open-meteo.com/en/docs/previous-runs-api). Its Free API terms limit
commercial use, so advertising or subscriptions require a paid, self-hosted, or directly licensed
data path. See the [Open-Meteo terms](https://open-meteo.com/en/terms).

ČHMÚ documents the 1 km merged radar and rain-gauge truth product in the
[radar open-data specification](https://opendata.chmi.cz/meteorology/weather/radar/radar_description_en.pdf).
The statistical design follows Ensemble Model Output Statistics principles, which correct bias and
dispersion instead of treating a raw model mean as calibrated. See
[Gneiting et al. 2005](https://doi.org/10.1175/MWR2904.1).

## Reproduce the current checks

Run these commands from the repository root:

```powershell
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv sync --project research --frozen
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pytest -q
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research ruff check .
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pyright
```

The current verified result is 116 passing tests, Ruff clean, and zero repository-wide Pyright
errors.
