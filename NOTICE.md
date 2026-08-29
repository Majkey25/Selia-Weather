# Third-party notices

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
