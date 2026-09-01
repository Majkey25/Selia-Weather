# Worldwide ensemble validation status

## Current result

No regional worldwide calibration selector is approved for production. Selia Vetra keeps
Open-Meteo Best Match and the diagnostic median outside an accepted artifact selector.

The Android runtime requests provider-family identifiers such as `gfs_seamless`, `icon_seamless`,
and `jma_seamless`. The existing Czech issued-run dataset uses underlying model identifiers such
as `ncep_gfs_global` and `dwd_icon_eu`. Those weights cannot be renamed or combined after training.
Doing so would double-count some providers and would apply scores to a different input series.

## Locked worldwide diagnostic run

The run on 1 September 2026 used the exact ten provider-family IDs that Android requests. The
dataset covered eight NOAA ISD stations, one in each populated calibration region. It used 120
training days and a later 45-day holdout. The downloader made 77 Open-Meteo Previous Runs
requests and one NOAA Global Hourly request. Each source payload has a SHA-256 entry in the
dataset manifest.

The station selector chose Frankfurt, New York, São Paulo, Nairobi, Delhi, Tokyo, Moscow, and
Sydney. Continuous fields use one exact synoptic report per station and day. One-hour rain reports
use a separate daily selection. The rain selector accepts a report only when its end time is within
ten minutes of a UTC hour, then records the comparison at that nearest hour.

The locked report contains 42 evaluations:

- 19 accepted aggregate evaluations;
- 9 rejected temperature, dew-point, pressure, or wind evaluations;
- 14 rejected precipitation occurrence or amount evaluations.

The accepted aggregate evaluations were four dew-point leads, four pressure leads, four
temperature leads, and seven wind-vector leads. All precipitation evaluations remained rejected.
At 24, 48, and 72 hours, precipitation could evaluate only the deployed fallback because no
holdout row contained the full trained predictor set. At 96 hours, neither the blend nor the
training-selected fallback had a holdout sample. The report records null scores for that unavailable
evaluation instead of numeric zero.

These aggregate results are not runtime regional weights. A runtime selector needs a separate
locked evaluation for its region. The report also rejects any lead that fails significance, fold
stability, source-count, fallback, or maximum regional-degradation rules.

The locked artifacts have these SHA-256 hashes:

- dataset manifest: `33716b5e1570e3ff6438a7a525ca349f02ee477560345e463208fd60a84099d2`;
- holdout lock: `0f273b79e67658e2ebb9cde671dd2b41260313b2a169c03a5d4c9ba4f5d33bf3`;
- diagnostic report: `e03dd790f2a666c84170e01567155a5e802288d61278fe250c4f951f9527858d`;
- input registry: `83d3b3373971bc2ef134b546a7619053ae037e2ea80bedaa8f868c9b2b67a93f`.

## Implemented gates

- Android accepts only calibration schema 2.
- The Pages builder accepts only schema 2 for a production feed.
- Every runtime segment requires an accepted locked holdout with at least 30 samples.
- Runtime weights must reference the exact model IDs returned to Android.
- Model weights must be finite, non-negative, normalized, and supported by a fresh model contract.
- Each selector includes a worldwide region, variable, lead range, month set, and truth class.
- An expired artifact, invalid checksum, unknown model, failed holdout, or missing contributor falls
  back to the diagnostic median or Best Match.

## Worldwide truth sources

The research package now parses two additional independent observation products:

- NOAA ISD global hourly station CSV for temperature, dew point, pressure, wind, visibility, and
  interval precipitation. The parser applies NOAA scaling and rejects suspect or erroneous quality
  codes.
- NASA GPM IMERG V07 half-hourly HDF5 precipitation. The parser preserves the native 0.1-degree
  grid, converts precipitation rate to a 30-minute amount, records nodata, and reads bounded
  longitude blocks.

ČHMÚ station and MERGE1h radar-gauge truth remain the preferred Czech sources.

## Completed input work

The research package now:

- uses the exact seamless provider-family IDs that Android requests;
- selects one unique NOAA ISD station for each populated calibration region;
- bounds Open-Meteo and NOAA response sizes;
- stores immutable source payloads and request manifests by checksum;
- supports a network-free preflight and an exact-lock resume after an evaluation failure;
- evaluates missing scalar and wind contributors with the same minimum-source and weight
  renormalization rules as Android.

## Work required before accepted weights

1. Fit and lock each runtime region separately. Do not copy the aggregate diagnostic weights into
   regional selectors.
2. Add more representative stations per region, then repeat the coverage and degradation gates.
3. Obtain authorized NASA Earthdata or PPS access before downloading IMERG files. Do not store
   credentials in the repository or GitHub Pages.
4. Use IMERG, MRMS, or regional radar-gauge truth for precipitation. NOAA ISD alone did not
   provide enough simultaneous predictors for an accepted rain blend.
5. Audit current runtime model contracts for every exact seamless ID.
6. Confirm any candidate weights on a later untouched holdout.
7. Publish schema 2 only when every bootstrap, region-degradation, coverage, licence, and
   reproducibility gate passes.

## Release posture

The worldwide radar, model routing, METAR current correction, and calibration-ready runtime can
ship in a non-commercial closed-test build. Release notes must describe the current result as
diagnostic. A Pages production calibration artifact remains blocked until the steps above produce
accepted segments.

## Verification

The implementation check on 31 August 2026 produced:

- 236 passing research tests;
- Ruff with no findings;
- Pyright with zero errors and zero warnings.

## Source contracts

- [NOAA ISD](https://www.ncei.noaa.gov/products/land-based-station/integrated-surface-database)
- [NOAA ISD format](https://www.ncei.noaa.gov/pub/data/noaa/isd-format-document.pdf)
- [NASA GPM IMERG](https://gpm.nasa.gov/data/imerg)
- [Open-Meteo Previous Runs API](https://open-meteo.com/en/docs/previous-runs-api)
