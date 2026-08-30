# Global regional ensemble design

## Objective

Make Selia Vetra a worldwide weather app whose deterministic point forecast uses the best
available model families for the selected coordinate. The app must not treat every provider as
equally useful everywhere and must not claim improved accuracy until an untouched regional
holdout proves it.

This design extends the existing Czech calibrated-ensemble design. Czechia remains the first
high-resolution calibrated region. Other regions start with an explicit, auditable diagnostic
blend and fall back to Open-Meteo Best Match until their own calibration passes.

## Definitions and limits

- Numerical weather prediction models already assimilate satellite, station, aircraft, buoy,
  radar, and other observations. Android consumes model output; it does not average raw satellite
  pixels.
- Current observations may correct the current state only when an independently licensed regional
  observation adapter is available and fresh.
- A robust median is an outlier-resistant diagnostic baseline, not a calibrated forecast.
- No model is forced into a result. Out-of-domain, stale, missing, invalid, or historically harmful
  sources receive no contribution.
- Forecast resolution is the native model/grid resolution, not the precision of the saved GPS
  coordinate.

## Region routing

`ForecastRegion` selects calibration and observation domains by coordinate. Every location retains
the verified global-capable provider families. A provider's `seamless` model uses its global model
outside the regional domain and automatically selects its higher-resolution local model inside the
domain. The app must not request both forms and double-count one provider family.

| Region | Regional behaviour | Global-capable provider families |
| --- | --- | --- |
| Czechia | add CHMI ALADIN; seamless DWD/Météo-France/UKMO can select local grids | ECMWF IFS/AIFS, NOAA GFS, DWD ICON, GEM, Météo-France, UKMO, CMA, JMA, BOM |
| Europe | seamless DWD/Météo-France/UKMO can select local grids | same verified global-capable families |
| North America | NOAA seamless selects HRRR/GFS where available; GEM can select Canadian grids | same verified global-capable families |
| East Asia | JMA seamless can select MSM; CMA remains available | same verified global-capable families |
| Oceania | BOM ACCESS remains available | same verified global-capable families |
| Other/global | every seamless provider uses its global fallback | same verified global-capable families |

The first implementation uses only model identifiers verified against the live provider API.
Provider operational status can exclude a source without changing the region contract. KMA is
excluded while its documented data migration suspends updates. CHMI is requested only inside
Czechia because it has no global fallback.

## Runtime calculation

For each selected coordinate:

1. request Open-Meteo Best Match as the safe global fallback;
2. request only the explicit model families selected for the coordinate;
3. align values by validity timestamp and canonical variable;
4. reject missing, stale, non-finite, out-of-range, and out-of-domain values;
5. use a versioned calibrated segment when its region, variable, lead, season, and minimum source
   count match;
6. otherwise use the robust-median diagnostic baseline when at least three independent values are
   present;
7. otherwise retain Best Match;
8. derive wind from vector components and weather condition from continuous calculated values;
9. retain contributor IDs, count, spread, calculation mode, run age, and fallback reason.

The precipitation field uses the same region router. It remains labelled forecast, never radar.

## Calibration hierarchy

Calibration is trained independently per variable and lead bucket. Sparse selectors fall back in
this order:

1. country or validated local domain, season, terrain, and lead;
2. continental region, season, and lead;
3. continental region and lead;
4. global variable and lead;
5. training-selected best eligible single model.

Every shipped blend must beat its training-selected single-model fallback on a locked chronological
holdout with the existing bootstrap, regional-degradation, coverage, and reproducibility gates.
Failure exports a fallback, never unvalidated weights.

## Observation and archive layers

- Czech current correction continues to use nearby ČHMÚ automatic stations.
- Future regional adapters may use open official sources such as NOAA/MRMS and METAR/ISD in the
  United States. Each adapter requires a source licence, timestamp, unit, station identity, quality
  flag, distance bound, and independent holdout before it can correct a forecast.
- Satellite/radar products remain separate observation layers. They can verify or nowcast
  precipitation but do not become generic point-forecast values without a documented conversion.
- The worldwide history archive uses five years of NASA POWER daily grid estimates for the exact
  selected coordinate. It includes daily precipitation, temperature, humidity, wind, and solar
  energy, but is labelled as satellite/model grid data rather than local station truth.
- **Ask ChatGPT with CSV** exports every archived day, location coordinates, source metadata, and a
  prompt that requests calculations from the rows. No OpenAI API key or paid LLM call is embedded.

## Production data posture

The Open-Meteo Free API remains a non-commercial debug/research source. Release ads and purchases
stay disabled while it is used. A monetised release must use a paid endpoint, self-hosted service,
or the existing direct-official-data pipeline with compatible licences.

GitHub Pages can publish bounded static official-data tiles. It cannot perform per-user compute or
serve as a free proxy for a non-commercial API. Static manifests remain diagnostic until their
calibration checksum and dataset manifest hash pass the release gate.

## Failure behaviour

- One failed model does not cancel the forecast.
- Fewer than three valid diagnostic contributors keeps Best Match.
- Invalid or missing calibration keeps diagnostic/fallback mode.
- No observation adapter means no current-condition correction outside supported regions.
- Offline mode keeps the last bounded valid forecast and reports its age.
- Missing data is never converted to zero.

## Implementation slices

1. Record tested worldwide calibration regions, keep one global-capable provider list, remove
   suspended providers, and add only truly local candidates such as CHMI by domain.
2. Verify every routed model at representative worldwide coordinates and document exclusions.
3. Add compact calculation provenance to `WeatherSnapshot` and Weather details.
4. Generalise the calibration artifact selectors from Czech regions to continental/local domains.
5. Add one independently licensed United States observation/truth adapter and backtest before any
   live correction.
6. Extend the direct official-data static pipeline region by region; keep each new grid diagnostic
   until its untouched holdout passes.
7. Run Android API 29+, Huawei, archive/share, widget, map, and release-gate acceptance.

## Acceptance criteria

- Prague includes CHMI plus every verified global-capable provider family.
- Berlin, New York, Tokyo, Sydney, and an unmatched ocean point exclude CHMI and retain every
  verified global-capable provider family.
- New York includes NOAA `gfs_seamless`; Tokyo includes `jma_seamless`; Sydney includes
  `bom_access_global`.
- Suspended KMA output is not requested.
- All returned model IDs are unique and every routing list contains at least three families.
- The main point forecast and 24-hour precipitation field use the same router.
- Best Match survives provider, parse, minimum-source, and calibration failures.
- No UI or documentation says the worldwide diagnostic median is more accurate than its sources.
- Five-year archive and CSV export continue to work for any valid WGS84 coordinate.
- Android tests, lint, release APK/AAB, Research pytest, Ruff, Pyright, and GitHub CI pass.
- GitHub/Play release remains blocked until the existing calibration and commercial-data gates pass.
