# Worldwide ensemble validation status

## Current result

No worldwide calibration segment is approved for production. Selia Vetra keeps Open-Meteo Best
Match and the diagnostic median outside an accepted artifact selector.

The Android runtime requests provider-family identifiers such as `gfs_seamless`, `icon_seamless`,
and `jma_seamless`. The existing Czech issued-run dataset uses underlying model identifiers such
as `ncep_gfs_global` and `dwd_icon_eu`. Those weights cannot be renamed or combined after training.
Doing so would double-count some providers and would apply scores to a different input series.

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

## Work required before accepted weights

1. Build a separate issued-run registry with the exact seamless provider-family IDs used by
   Android.
2. Select representative stations for Europe, North America, South America, Africa, South and
   Central Asia, East Asia, northern Asia, and Oceania.
3. Download immutable NOAA ISD and model-run inputs under a bounded request manifest.
4. Obtain authorized NASA Earthdata or PPS access before downloading IMERG files. Do not store
   credentials in the repository or GitHub Pages.
5. Fit each variable and lead range on training dates only.
6. Lock at least 30 later dates before evaluating the holdout.
7. Publish schema 2 only when every existing bootstrap, region-degradation, coverage, licence, and
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
