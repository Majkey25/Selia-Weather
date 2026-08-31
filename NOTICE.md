# Third-party notices

## Leaflet 1.9.4

The worldwide point picker bundles Leaflet 1.9.4.

- Copyright 2010–2023 Vladimir Agafonkin and contributors.
- Software licence: BSD 2-Clause License.
- Source: <https://github.com/Leaflet/Leaflet/tree/v1.9.4>
- JavaScript SHA-256: `db49d009c841f5ca34a888c96511ae936fd9f5533e90d8b2c4d57596f4e5641a`.
- Bundled CSS SHA-256: `337bfca5cabd03b39815b2700febe2b3b7edf55921c59cd49f88ecb328212303`.

Map tiles are loaded interactively from OpenStreetMap only while the point picker or observed
radar is open and retain the required OpenStreetMap attribution.

## RainViewer Weather Maps API

The observed map loads the available past radar frames and coverage mask from RainViewer. The
screen displays RainViewer attribution. The public API is used only in the non-commercial build
and has no availability guarantee.

- Documentation: <https://www.rainviewer.com/api/weather-maps-api.html>
- Current public API limits: <https://www.rainviewer.com/api/transition-faq.html>

## AviationWeather Data API

Outside Czechia, the app requests recent worldwide METAR reports from the U.S. National Weather
Service Aviation Weather Center. Requests use a bounded coordinate box and follow the documented
100-request-per-minute limit.

- Documentation: <https://aviationweather.gov/data/api/>

## NOAA ISD and NASA GPM IMERG

The offline research pipeline can parse NOAA Integrated Surface Database station observations and
NASA GPM IMERG V07 satellite precipitation. These products verify forecasts and are not embedded
as live user-facing model output.

- NOAA ISD: <https://www.ncei.noaa.gov/products/land-based-station/integrated-surface-database>
- NASA GPM IMERG: <https://gpm.nasa.gov/data/imerg>

## Czech Hydrometeorological Institute open data

The forecast research and runtime pipeline uses the following ČHMÚ datasets under Creative
Commons Attribution 4.0 International:

- ALADIN CZ 1 km numerical forecast data;
- current meteorological station observations;
- MERGE1h radar and rain-gauge precipitation estimates.

Source: Czech Hydrometeorological Institute (ČHMÚ). The application is not an official ČHMÚ
product. Dataset identities, download URLs, licence links, and metadata records are pinned in
`research/static-source-registry.json`.

## ecCodes Python 2.48.0

The research pipeline uses `eccodes==2.48.0` for bounded batch sampling of official GRIB fields.

- Copyright 2017–2026 ECMWF.
- Software licence: Apache License 2.0.
- Source: <https://github.com/ecmwf/eccodes-python>
- Windows CPython 3.12 wheel SHA-256:
  `ae555c99abe13331d5f4a5dd4b4b6ca2ac39aefdc68037b7ebb623df16963778`.
- Platform-neutral wheel SHA-256:
  `470eed1c5ba0aa9062a345ac0806cb887e5395263243b2d93c88e45002a035aa`.

## ecmwf-opendata 0.3.34

The research pipeline uses `ecmwf-opendata==0.3.34` to retrieve selected IFS and AIFS fields from
ECMWF Open Data.

- Copyright European Centre for Medium-Range Weather Forecasts.
- Software licence: Apache License 2.0.
- Source: <https://github.com/ecmwf/ecmwf-opendata/tree/0.3.34>
- PyPI wheel SHA-256: `2ed33e30af73ed7180da9e21552f3d28bd284376cce93286c01b1fa436a8df49`.

ECMWF Open Data uses Creative Commons Attribution 4.0. Generated products must retain the ECMWF
attribution recorded in `research/static-source-registry.json`.

## commons-suncalc 3.11

ALADIN weather uses `org.shredzone.commons:commons-suncalc:3.11` for offline Sun and Moon
calculations.

- Copyright 2017 Richard "Shred" Körber and contributors.
- Licence: Apache License 2.0.
- Source: <https://github.com/shred/commons-suncalc>
- Maven Central: <https://central.sonatype.com/artifact/org.shredzone.commons/commons-suncalc/3.11>
- Verified JAR SHA-256: `08ae8c1b90468ec45ef9eae93624dd2d9a7e7f8ffa5478fb24d03cecd62d5ba5`

The library targets common-use astronomical calculations. Its upstream documentation reports
roughly minute-level timing accuracy and does not claim observatory precision.
