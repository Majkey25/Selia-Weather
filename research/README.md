# Czech ensemble research

Locked research package for the verified Czech weather-model registry.

As of 8 September 2026, ČHMÚ numerical calibration truth requires provider quality 0.
Suspect, poor, estimated, missing and unknown-quality values cannot qualify a fit.
Trace-gauge flags are censored quantities, not zero rainfall. Earlier ČHMÚ reports predate
this check and must be rerun before any export. The prospective replay can explicitly
include provisional quality 5 for a separately labelled descriptive comparison only.
See [the frozen September replay](../docs/research/2026-09-08-prospective-replay.md).

As of 11 October 2026, wind-vector cases retain the original station ID, exact UTC observation
time and forecast lead. NOAA hourly rainfall is accepted only when the native one-hour interval
ends exactly on the forecast hour; nearby observation times are not rounded. Rerun every
worldwide backtest in a new output directory, and rerun Czech wind-vector segments before using
their scores. Record the source commit alongside the report: an unchanged input manifest alone
does not identify the evaluation code. These fixes do not activate learned weights. Temperature-only
studies that bypass the worldwide rain selection are unaffected.

Backtests also purge observations at or after the earliest holdout forecast issuance before
model coverage selection. Each expanding-window validation fold refits on past-only coverage.
The same inspected eight-station holdout now has 15 diagnostic passes out of 28 evaluations,
with no eligible exact-hour rain truth. See [the corrected worldwide diagnostic](../docs/research/worldwide-ensemble-validation.md).
This replay does not authorize regional production weights or establish new independent skill.
New dataset manifests pin segment coverage thresholds in schema 2. Incomplete legacy schema 1
runs cannot be resumed; create a new output directory using the verified cached inputs instead.
Existing completed reports are not rewritten.

## Prospective forecast capture

The manual collector freezes raw provider forecasts before their valid time. Each manifest records
the actual UTC response-receipt time, requested station coordinates, source URL, payload SHA-256,
provider units, and missing values. Model initialization time stays `null` because this endpoint
does not provide it. Forecast lead is explicitly relative to capture completion.

```powershell
uv run --project research python -m aladin_ensemble.sources.live_capture --station-id 0-203-0-11775 --latitude 49.236667 --longitude 17.643333 --models chmi_aladin_seamless icon_seamless ecmwf_ifs025 gfs_seamless --output research/data/raw/captured-live
```

This command makes one public Open-Meteo request, with a 20-second timeout and a 2 MB response
limit. It requests three days for one station and between two and twelve model IDs. It does not
schedule future requests. Raw responses and capture manifests are content-addressed and never
overwritten. A changed or corrupt existing file causes an error.

New captures also request total `cloud_cover` in percent and the provider's `weather_code`,
validated against the [Open-Meteo field units and WMO categories](https://open-meteo.com/en/docs).
`--variables` can restrict the requested fields. Replay uses the exact `hourly` variables saved
in the source URL, so legacy six-variable schema-1 captures retain their original records.
Cloud fractions are converted with the existing unit adapter. Weather codes remain unscored
categories, never MAE or arithmetic blend inputs. The separate ČHMÚ monthly cloud parser accepts
quality-0 whole-sky `N` observations with a blank flag and integer octas 0–8; code 9 is excluded.
This does not make current app conditions or the NOAA ISD layer calibration truth.
ISD `GF1` can encode height-limited automated airport reports; a numeric total or
`FM-12` report type alone does not establish full-sky measurement provenance. It stays in the
original raw payload, not calibration observations. See
[the source audit](../docs/research/2026-10-12-current-source-reliability.md).

Only timestamps after capture are eligible records. For hourly precipitation, the entire
accumulation interval must start after capture. The collector never copies current or fused app
conditions into an observation. Captures contain `truth: null` and `calibration_eligible: false`.

`evaluate_capture(manifest_path, observations, truth_payloads)` pairs a saved capture with typed
observations from the existing ČHMÚ or NOAA ISD station parsers. `truth_payloads` maps SHA-256
hashes to original observation bytes. The evaluator verifies capture and raw hashes, reconstructs
forecast records from the raw response, and requires matching observation source hashes. It matches
the exact station ID and UTC valid time, requires station coordinates within 1 km to tolerate
catalog rounding, and converts supported units with the existing source adapter.
Temperature and dew-point sensors must satisfy the existing 2 m height policy; wind sensors
must satisfy the 10 m policy. Both allow the existing 10% measurement-height tolerance.
It does not substitute nearby stations or model grid coordinates for the named station,
and accepts only one-hour precipitation intervals for hourly rainfall. Wind direction errors use
circular distance. Model or fused observation source IDs, duplicate truth, and invalid units fail.

Each result retains model, variable, valid time, actual capture-relative lead in seconds, and paired
error. Missing observations or missing forecasts produce an explicit status and `None` error,
never zero. The hashes establish integrity and linkage, not publisher authenticity. Observations
must come from the independent source parsers, not caller-fabricated measurements carrying a
station label. Learning, radar-truth pairing, and production weight export remain unimplemented.
Existing issue-time calibration cannot consume capture-relative records without a separately
validated contract and untouched holdout.

On 5 September 2026, one live Zlín capture saved 1,316 future records from the four models above.
Capture completion was `2026-09-05T16:18:15.638147+00:00`. The raw response SHA-256 was
`6a4177d0428dc9df2f0a7312437dcf7b254edf942097b513dd8b096ae5ac836d`.
This is prospective input evidence, not an accuracy result.

A single independent ČHMÚ check at `2026-09-05T17:18:46.060215+00:00` returned observations
only through `15:00 UTC`. The first captured forecast at `17:00 UTC` therefore still had missing
truth and no error score. That response is preserved by SHA-256
`6b674a7e671611d6863a986ebee7f8ff4bfaaba8ab290272c76f5c6e16513e81` under the local capture
archive's `truth` directory. No retry or scheduled check was created.

## Frozen September cloud replay

From the repository root, with the existing research environment:

```powershell
uv run --project research python research/scripts/cloud_confirmation_replay.py --study-dir .reference/tmp/cloud-validation-20261013 --output .reference/tmp/cloud-validation-20261013/new-replay.json
```

The script reconstructs the already-inspected September study from its 26 cached forecast
responses and three ČHMÚ monthly files. It verifies the pinned report, source hashes, request
identities, UTC hours, units, returned grids and paired coverage before recomputing scores.
The original report is a provenance/cohort contract, not a source of computed scores. These
local raw inputs are not bundled; missing or changed inputs fail without a network fallback.
An existing output is never replaced.

The reconstructed 2,614 pairs retain the original NO-GO: the 24-hour mean-minus-median daily
95% interval reaches `+0.013223424351708845` percentage points. This is a reconstruction,
not another untouched holdout or permission to export weights. See
[cloud and precipitation diagnosis](../docs/research/2026-10-13-cloud-rain-diagnosis.md).

## Research environment

The [probabilistic cloud/rain confirmation](../docs/research/2026-10-14-probabilistic-cloud-rain.md)
tests `fit_empirical_pool_weights` and `empirical_crps` against independent native
observations. Cloud scores improve in the archived August cohort, but coverage,
rainfall and operational-provenance gates still block deployment. These probability
masses must not be exported as scalar blend weights or used to raise UI confidence.

```powershell
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv sync --project research --frozen
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pytest
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research ruff check .
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pyright
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research python -m aladin_ensemble.sources.probe --output research/model-registry.json
```

The forecast coverage probe batches all configured Czech locations and required variables. Archive
verification intentionally uses the first representative point. Planned date/run requests follow
the documented downloader topology: one request per candidate/date/run. The worst-case HTTP
formula is `candidates × (2 × probe attempts + dates × runs)`. The CLI uses two probe attempts,
one second retry backoff, and a 0.5 second pause between candidates. Locations and variables remain
printed for audit. The guard
requires HTTP requests to be strictly below `10,000`; `10,000` itself fails closed. Provider quota
can use fractional request units, so this HTTP count does not replace checking the provider's
current quota terms before a production run.

Successful probes record the endpoint, sorted non-secret request parameters, requested and
provider model IDs, and per-location response metadata. Definitive model exclusions are kept
separately from operational failures. The output is `complete` only when every attempted probe
finished without an operational failure; otherwise it is `incomplete` and the CLI exits nonzero.

Issued-run research uses `sources.open_meteo_runs` rather than a stitched forecast series. Raw
responses are SHA-256 addressed, request manifests redact credential-like parameters, and every
forecast row keeps its original UTC run and validity time. Canonical forecast variable names match
the ČHMÚ observation layer. Station precipitation alignment accepts only matching one-hour
interval truth; ten-minute values cannot silently replace hourly accumulations.

`metrics` provides hand-verified scalar, circular, probability, contingency, weighted-median, and
spatial FSS calculations. `baselines` evaluates every candidate model, Best Match, arithmetic
mean, and median on one shared complete-case mask grouped by variable, lead, region, elevation,
and season. Confidence intervals use deterministic date-block bootstrap resampling. These tools do
not claim a winning blend before a locked holdout passes.

`train` fits deterministic constrained scalar weights and wind-vector weights in east/north
components. Precipitation occurrence uses non-negative logistic calibration whose regularisation
is selected only through supplied training folds; positive amounts use separately fitted weights
and the same linear blend optimized during training. `fallback` implements the approved
sparse-segment hierarchy and records an explicit reason for every excluded fit. Holdout acceptance
is still a separate gate.

The first 90-day training plus 30-day holdout run is complete. Six scalar lead segments passed, but
nationwide temperature/wind truth coverage and every precipitation segment did not. No production
weights were exported. The exact production gate and results are recorded in
[`docs/research/czech-ensemble-validation.md`](../docs/research/czech-ensemble-validation.md).
Historical precipitation amount scores used the pre-0.7.5 weighted median and need a rerun
with the corrected linear predictor before supporting a skill claim.
The current model registry is complete with 15 eligible candidates and 2 definitive exclusions.
The export code still refuses any future registry whose status is not `complete`.
[`model-contracts.json`](model-contracts.json) records the checked grid resolution and update
cadence for all 15 models. The loader requires an exact model-ID match and a contract audit no
older than 90 days. See [Model runtime contracts](../docs/research/model-contracts.md).

Worldwide preparation adds strict NOAA ISD and NASA GPM IMERG parsers plus a separate schema-2
runtime exporter. The exporter accepts only holdout-approved segments trained on the exact
provider-family IDs returned to Android. No worldwide segment currently satisfies that contract,
so no production runtime artifact is checked in or published. See
[Worldwide ensemble validation status](../docs/research/worldwide-ensemble-validation.md).

`sources.noaa_ghcnh` reads the NOAA successor to the retired ISD archive. It preserves native UTC
observation times and source-linked raw files, with source-specific quality checks. The frozen
East Asia temperature study has a cache-only reproducer in
`scripts/ghcnh_temperature_validation_2025.py`. Its pooled holdout improved, but the existing
stability gate failed and individual stations favored different models. No weights were exported.
See [GHCNh temperature validation](../docs/research/2026-09-06-ghcnh-temperature-validation.md)
for exact sampling, scores, limitations, and reproduction requirements.

Previous-runs budgets report HTTP requests and weighted Open-Meteo quota units separately.
The quota estimate uses each request's locations, variables, and inclusive date span. A planned
77-request batch with 24 locations, 165 days, and six variables costs 13,068 units before cache
reuse, so it cannot pass a 10,000-unit limit. This is a conservative plan estimate, not an account
usage meter or a guarantee that the shared daily quota remains available.

Run the locked-backtest preflight before any download:

```powershell
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research python -m aladin_ensemble.run_backtest --registry research/model-registry.json --station-metadata research/data/raw/chmi-meta/meta1-20260828.json --element-metadata research/data/raw/chmi-meta/meta2-20260828.json --train-start 2026-05-03 --train-end 2026-07-31 --holdout-start 2026-08-01 --holdout-end 2026-08-30 --provider-limit 10000
```

The command is network-free. It validates the complete registry, corrected station cohort, date
split, Open-Meteo request budget, ČHMÚ monthly request count, and immutable-month boundary. Exit
code `0` means ready. Exit code `2` prints a machine-readable blocking reason.

After the preflight prints `"status":"ready"`, run the same command with these arguments:

```powershell
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research python -m aladin_ensemble.run_backtest --registry research/model-registry.json --station-metadata research/data/raw/chmi-meta/meta1-20260828.json --element-metadata research/data/raw/chmi-meta/meta2-20260828.json --train-start 2026-05-03 --train-end 2026-07-31 --holdout-start 2026-08-01 --holdout-end 2026-08-30 --provider-limit 10000 --execute --output-dir research/output/august-holdout-20260901
```

`--execute` downloads or reuses cached inputs, builds the dataset, and fits only the training
range. The command then writes `dataset-manifest.json` and `holdout-lock.json` before it reads the
holdout for evaluation. `report.json` records scalar, wind-vector, precipitation-occurrence, and
positive-amount results. It also records the evaluation counts and every training-selected region
fallback. The artifact builder preserves those region guards and serializes precipitation as
separate occurrence and positive-amount fits. The CLI does not write that artifact automatically.
The report remains diagnostic and sets `exported` to `false`. A separate release gate must validate
model runtime contracts and source licences before weight export.

To write a review-only calibration candidate after a completed run, add
`--write-candidate --model-contracts research/model-contracts.json`. The command writes
`candidate-ensemble-weights.json` inside the new output directory. Do not copy this candidate into
Android assets or a production Pages feed while `report.json` remains diagnostic or the source
licence gate is blocked.
