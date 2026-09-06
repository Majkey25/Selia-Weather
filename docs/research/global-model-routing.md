# Global model routing

## Current result

Selia Weather selects a calibration region from the requested coordinates and country. Regions
cover Czechia, Europe, North America, South America, Africa, south/central Asia, east Asia,
northern Asia, Oceania, and global fallback. The selector does not discard independent global
model families.

Open-Meteo documents that a provider's `seamless` series combines its global and local models. For
example, NOAA GFS seamless uses HRRR where its North American domain is available, and JMA
seamless can use MSM around Japan. The app therefore requests one series per provider family and
does not request the provider's global and regional variants as separate independent models.

The common requested provider IDs are:

- `icon_seamless`;
- `ecmwf_ifs025`;
- `ecmwf_aifs025`;
- `gfs_seamless`;
- `gem_seamless`;
- `meteofrance_seamless`;
- `ukmo_seamless`;
- `cma_grapes_global`;
- `jma_seamless`;
- `bom_access_global`.

Czech coordinates prepend `chmi_aladin_seamless`. `kma_seamless` is excluded while Open-Meteo
reports that KMA updates are suspended during its KIM source migration.

## Live sample

The following bounded checks ran on 30 August 2026 at 20:48 UTC. Each request asked for one hourly
temperature value and the exact production model list. Every request returned HTTP 200.

| Location | Coordinate | Route | Requested | Finite first-hour contributors |
| --- | --- | --- | ---: | ---: |
| Prague | 50.0755, 14.4378 | Czechia | 11 | 9 |
| New York | 40.7128, -74.0060 | North America | 10 | 8 |
| Tokyo | 35.6762, 139.6503 | East Asia | 10 | 8 |
| Sydney | -33.8688, 151.2093 | Oceania | 10 | 8 |
| Pacific Ocean | 0.0000, -140.0000 | Global | 10 | 8 |

Prague returned CHMI ALADIN plus ICON, IFS, GFS, GEM, Météo-France, UKMO, CMA, and JMA. The other
samples returned the same set without CHMI. AIFS and BOM did not expose a finite value for that
first requested hour. The runtime correctly omitted them instead of converting missing values to
zero. A later valid timestamp can contain a different contributor count.

## Calculation state

The current worldwide calculation is an outlier-resistant median baseline. It runs only with at
least three finite aligned model values, blends wind as east/north vectors, and derives the weather
condition from calculated continuous fields. It preserves provider precipitation probability;
deterministic wet-model agreement is not a calibrated probability. Otherwise it retains Open-Meteo
Best Match.

This is diagnostic routing, not evidence that the median is more accurate than every contributing
model. Production weights require independent regional observations, fixed issued model runs,
training-only model selection, and an untouched chronological holdout under the global regional
ensemble design.

## Learned-weight delivery

The runtime now checks the existing Pages manifest instead of permanently passing `calibration =
null`. A diagnostic, expired, missing, corrupt, or out-of-area feed leaves the live multi-model
calculation available. No diagnostic tiles become production forecasts.

The accepted feed must link the artifact and tiles to the same manifest. Each learned value must
match its location, exact model ID, variable, unit, actual model run time, and forecast validity.
Lead time is measured from the model run, not the current array index. ISO timestamps use the
response's explicit UTC offset, as specified by Open-Meteo's JSON writer, rather than possibly old
Android timezone rules. Legacy responses without that offset cannot calibrate ambiguous
daylight-saving hours. Stale runs, future run times, expired weights, and inadequate contributor
coverage cannot apply learned weights. Selectors cannot overlap, and the artifact must contain an accepted
holdout with at least 30 samples. That parser check does not replace the research acceptance gates.

This path supports instantaneous temperature, dew point, and pressure. It does not substitute
six-hour rainfall totals into hourly rainfall or treat wind direction as a scalar. Other values
retain their existing live multi-model or provider calculation.

Weather details distinguish the first calibrated hourly value from current conditions and show
the number of calibrated hourly values. Displayed weights describe that first sample, not every
hour or variable. Typed provenance remains in the bounded forecast cache. Older cache entries
remain readable.

The public feed still has no accepted production artifact. The integration makes accepted weights
deliverable; it does not establish that any blend is more accurate worldwide.

## Sources

- [Open-Meteo Forecast API](https://open-meteo.com/en/docs)
- [Open-Meteo JSON timestamp writer](https://github.com/open-meteo/open-meteo/blob/main/Sources/App/Helper/Writer/JsonWriter.swift)
- [Open-Meteo GFS and HRRR API](https://open-meteo.com/en/docs/gfs-api)
- [Open-Meteo ECMWF API](https://open-meteo.com/en/docs/ecmwf-api)
- [Open-Meteo JMA API](https://open-meteo.com/en/docs/jma-api)
- [Open-Meteo KMA status](https://open-meteo.com/en/docs/kma-api)
- [Global regional ensemble design](../superpowers/specs/2026-08-30-global-regional-ensemble-design.md)
