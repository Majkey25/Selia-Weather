# Global regional ensemble design

## Objective

Make Selia Vetra a worldwide weather app whose deterministic point forecast uses the best
available model families for every valid WGS84 coordinate. The app must not treat every provider
as equally useful everywhere and must not claim improved accuracy until an untouched regional
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
- The full weather-detail screen is available at every valid coordinate. Region selection changes
  model eligibility, calibration, and observation coverage. It never removes weather variables or
  disables the detail screen.
- Satellite products and radar observations verify or correct a forecast only through a documented
  variable contract. The runtime never averages raw satellite pixels with point forecasts.

## Worldwide detail contract

The app returns the same typed weather detail in Europe, Africa, Asia, Russia, North America,
South America, Oceania, polar regions, and open ocean. The contract includes temperature,
apparent temperature, humidity, dew point, precipitation, cloud layers, pressure, visibility,
wind speed, wind direction, gusts, solar and UV values, soil values when a provider exposes them,
and calculation provenance.

Missing regional observations do not become zero and do not block the forecast. The app keeps the
global model result, labels the observation layer as unavailable, and reports the fallback reason.

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
| South and Central Asia | use verified global families; add a local provider only after live contract and holdout checks | same verified global-capable families |
| Africa | use verified global families; use global satellite precipitation as verification where stations are sparse | same verified global-capable families |
| South America | use verified global families; add national models only after live contract and holdout checks | same verified global-capable families |
| Oceania | BOM ACCESS remains available | same verified global-capable families |
| Russia and northern Asia | use verified global families; use European local grids only inside their documented domain | same verified global-capable families |
| Polar, ocean, and other global | every seamless provider uses its global fallback | same verified global-capable families |

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
5. load a versioned calibration artifact whose checksum, model contracts, region, variable, lead,
   season, maximum age, and minimum source count match;
6. otherwise use the robust-median diagnostic baseline when at least three independent values are
   present;
7. otherwise retain Best Match;
8. calculate the accepted weighted blend locally on Android;
9. derive wind from vector components and weather condition from continuous calculated values;
10. retain contributor IDs, weights, count, spread, calculation mode, artifact version, run age,
    truth class, and fallback reason.

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
Failure exports a fallback, never unvalidated weights. Each variable has independent weights.
Temperature weights cannot be reused for wind or precipitation.

The truth hierarchy is:

1. quality-controlled national stations, gauges, and radar composites with a verified licence;
2. worldwide METAR observations for fresh temperature, dew point, pressure, visibility, cloud,
   and wind checks;
3. NOAA ISD or its successor for historical worldwide station verification;
4. NASA GPM IMERG for global half-hourly precipitation verification, including areas with sparse
   gauge or radar coverage;
5. ECMWF IFS analysis or ERA5 only as a labelled reanalysis fallback.

Reanalysis is not independent station truth. A segment trained only against reanalysis cannot
claim better real-world accuracy than its source models.

## Observation and archive layers

- Czech current correction continues to use nearby ČHMÚ automatic stations.
- Worldwide current correction may use the nearest fresh METAR only when the report includes a
  station identity, timestamp, coordinates, units, quality flags, and a bounded distance. No nearby
  report means no current correction.
- Historical worldwide calibration may use NOAA ISD station data. Regional adapters such as
  NOAA MRMS require a source licence, timestamp, unit, grid contract, and independent holdout before
  they can correct a forecast.
- NASA GPM IMERG is precipitation verification, not a 24-hour forecast. Its native half-hourly
  0.1-degree grid and latency remain part of the truth metadata.
- Satellite/radar products remain separate observation layers. They can verify or nowcast
  precipitation but do not become generic point-forecast values without a documented conversion.
- The worldwide history archive uses five years of NASA POWER daily grid estimates for the exact
  selected coordinate. It includes daily precipitation, temperature, humidity, wind, and solar
  energy, but is labelled as satellite/model grid data rather than local station truth.
- **Ask ChatGPT with CSV** exports every archived day, location coordinates, source metadata, and a
  prompt that requests calculations from the rows. No OpenAI API key or paid LLM call is embedded.

## Worldwide radar and precipitation map

The map opens at every selected coordinate. It separates observed precipitation from forecast
precipitation instead of presenting both as one radar product.

- The observed timeline uses RainViewer global composite tiles for the available past frames and
  displays its required attribution. The client honours the documented zoom and request limits.
- Czechia may use the higher-resolution ČHMÚ layer. The United States may use NOAA MRMS. These
  adapters override the observed layer only inside their verified coverage domains.
- The future timeline uses the same calibrated precipitation calculation as the point forecast.
  It renders a 24-hour local precipitation field and labels every frame **Forecast**.
- The transition between observed and forecast frames is visible on the timeline.
- Missing radar coverage displays a coverage message. It does not display a blank frame as dry
  weather.
- RainViewer removed its free future nowcast and satellite layer in 2026. The app must not request
  those retired products or label model output as RainViewer radar.
- The first worldwide implementation does not add a live cloud-satellite mosaic. Numerical models
  already assimilate satellite observations. A future live satellite layer requires a stable tile
  service, licence, timestamp contract, and global coverage check.

## Calibration artifact delivery

GitHub Pages hosts bounded static calibration artifacts, not per-user compute. Each artifact has a
schema version, generation timestamp, expiry, dataset-manifest hash, model-contract hash, region,
truth class, variables, lead buckets, seasons, weights, fallback model, and SHA-256 checksum.

Android keeps the last valid bounded artifact and a packaged safe fallback. Invalid, expired,
unknown, or partially downloaded artifacts fail closed to the diagnostic median or Best Match.

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

1. Keep one verified global-capable provider list. Add only domain-valid local candidates.
2. Generalise the calibration artifact from Czech regions to worldwide domains and variables.
3. Apply accepted scalar, wind-vector, precipitation-occurrence, and precipitation-amount weights
   locally in both the point forecast and the 24-hour precipitation field.
4. Add worldwide METAR current observations with strict freshness and distance limits.
5. Replace the Czech-only radar page with the worldwide observed and forecast map. Keep ČHMÚ and
   MRMS as regional high-resolution observed adapters.
6. Extend the research truth pipeline with NOAA ISD and NASA GPM IMERG. Keep every regional segment
   diagnostic until its untouched holdout passes.
7. Publish only checksum-verified calibration artifacts to GitHub Pages.
8. Run Android API 29+, Huawei, archive/share, widget, map, offline, and release-gate acceptance.

## Acceptance criteria

- Prague includes CHMI plus every verified global-capable provider family.
- Berlin, Moscow, Lagos, Nairobi, Delhi, Tokyo, Sydney, São Paulo, New York, an unmatched ocean
  point, and a polar point exclude CHMI and retain every eligible global-capable provider family.
- Every listed location opens the same complete detail screen and returns explicit provenance.
- New York includes NOAA `gfs_seamless`; Tokyo includes `jma_seamless`; Sydney includes
  `bom_access_global`.
- Suspended KMA output is not requested.
- All returned model IDs are unique and every routing list contains at least three families.
- The main point forecast and 24-hour precipitation field use the same router.
- The point forecast and precipitation field use the same accepted calibration artifact.
- Best Match survives provider, parse, minimum-source, and calibration failures.
- Observed precipitation and forecast precipitation use different labels and timeline styling.
- A location without radar or station coverage still receives a forecast and a coverage message.
- No UI calls a 24-hour model frame radar.
- No UI or documentation says the worldwide diagnostic median is more accurate than its sources.
- Five-year archive and CSV export continue to work for any valid WGS84 coordinate.
- Android tests, lint, release APK/AAB, Research pytest, Ruff, Pyright, and GitHub CI pass.
- GitHub/Play release remains blocked until the existing calibration and commercial-data gates pass.

## Source contracts

- [Open-Meteo Forecast API](https://open-meteo.com/en/docs)
- [Open-Meteo Previous Runs API](https://open-meteo.com/en/docs/previous-runs-api)
- [Open-Meteo terms](https://open-meteo.com/en/terms)
- [AviationWeather worldwide METAR API](https://aviationweather.gov/data/api/)
- [NOAA Integrated Surface Database](https://www.ncei.noaa.gov/products/land-based-station/integrated-surface-database)
- [NASA GPM IMERG](https://gpm.nasa.gov/data/imerg)
- [NOAA MRMS services](https://opengeo.ncep.noaa.gov/geoserver/www/index.html)
- [RainViewer Weather Maps API](https://www.rainviewer.com/api/weather-maps-api.html)
- [RainViewer API transition and current limits](https://www.rainviewer.com/api/transition-faq.html)
