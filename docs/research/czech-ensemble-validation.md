# Czech ensemble validation status

## Result

The calibrated Czech ensemble is not ready for production. The repository does not contain
`app/src/main/assets/ensemble_weights.json`.

The live model probe on 25 August 2026 checked 17 candidates at 7 Czech points for 3 required
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

The current issued-run fixture contains one CHMI ALADIN CZ run for one location and one day. This
fixture proves the parser contract. It cannot measure long-term accuracy.

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

1. Download at least 90 complete training days and lock the newest 30 complete days for holdout.
2. Re-run the source licence gate before any release with advertising or paid features.
3. Fit each variable and lead segment without reading the holdout.
4. Evaluate the locked holdout once. Do not tune a failed segment against the holdout.
5. Export `ensemble_weights.json` only when the dataset and every artifact validation pass.

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

The current verified result is 75 passing tests, Ruff clean, and zero Pyright errors.
