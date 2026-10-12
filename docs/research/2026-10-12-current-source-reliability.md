# Current sky sources: reliability fixes, 12 October 2026

## Confirmed defects

The separately fetched ALADIN current request was discarded when the hourly multi-model
request failed, returned too few contributors, or lacked an aligned hourly series. Its use also
depended on ALADIN appearing among temperature contributors. Those conditions do not determine
whether a separately fetched current sky value is usable.

Regional current sky is now applied after the hourly blend or its fallback. Both the original
and regional weather categories must remain dry sky categories, with valid numerical cloud
cover and precipitation below 0.1 mm. Rain, fog, snow and storm indications are not erased.
Hourly and daily values, fallback reasons and contributor diagnostics remain unchanged.

Two sequential current requests can straddle a quarter-hour boundary. The regional request now
also retrieves four past and four forecast 15-minute slots. When its current timestamp differs,
only a unique slot exactly equal to the base current timestamp can be used. Missing, duplicate,
malformed or wet slots do not qualify. UTC offsets must also match, including during a daylight
saving transition. Mismatched array lengths and non-finite numbers are rejected without
aborting the base refresh. Timestamps are never rounded to an hour or another slot.
Live requests at the saved Březnice point and Prague confirmed matching `current` and
`minutely_15` values, including nonzero total and layered clouds in Prague. These remain model
values, not observations: [Open-Meteo's time and variable contract](https://open-meteo.com/en/docs).

The global METAR query used a fixed latitude/longitude box. It could miss stations inside the
supported 50 km radius at high latitudes and across the antimeridian. It now uses spherical
bounds with outward rounding, two requests only when crossing the antimeridian, and full
longitude only for a circle reaching a pole. A successful half-query survives failure of the
other; station duplicates retain the latest report. The existing 10 km/30-minute sky support
limit and 50 km scalar support limit are unchanged. Live API smoke checks covered Svalbard and
both antimeridian query halves, against the [AviationWeather contract](https://aviationweather.gov/data/schema/openapi.yaml).

The main weather hero now shows the valid date/time when its cached current value is not in
the actual current hour. It does not replace a failed refresh with fabricated current weather.

## Březnice: what the evidence establishes

The reported location was Březnice in the Zlín Region. Investigation of a matched earlier
provider response found Best Match already reporting clear sky; this was not an icon rendering
error. The explicit ALADIN response differed. The nearest captured METAR was about 22.7 km
away, outside the unchanged point-sky support radius.

Two EUMETView point queries resolved to different actual observation slots: cloudy at
14:30 UTC and clear land at 15:15 UTC. The server's nearest-time warning, not its response
generation timestamp, identified those slots. This does not prove that an earlier cloudy report
was wrong, nor that a later clear forecast was correct at a different point or instant.
A satellite mask pixel is a cloud/no-cloud classification, not hemispheric total-cloud percent.
It is therefore not copied into the app as 0% or 100% sky cover.

The fixes above improve source availability and time handling. They do not establish an
out-of-sample accuracy gain, worldwide model dominance, or superiority of ALADIN for every
Czech variable and lead time. Confidence remains a model-agreement diagnostic, not a verified
probability of a correct forecast.

## Global cloud truth: source audit

NOAA ISD `GF1` was investigated as an independent cloud source. Its total-coverage field and
quality codes are documented in [the NOAA specification, pages 63–64](https://www.ncei.noaa.gov/pub/data/noaa/isd-format-document.pdf).
However, code 00 includes both SKC and CLR. Automated ASOS CLR means no detected cloud below
12,000 ft, not a completely cloudless sky. Absence of AUTO only means an observer signed on;
it does not establish a manual full-sky measurement ([NOAA ASOS appendix C](https://www.weather.gov/media/asos/appen.pdf)).
Laser sampling also describes clouds passing above the sensor rather than the whole visible
sky ([Met Office measurement guidance](https://weather.metoffice.gov.uk/guides/observations/how-we-measure-cloud)).

Unmodified original NOAA CSVs for 10–11 October 2024 were inspected:

| Station | Numeric GF1 00–08 with accepted QC | Measurement limitation |
| --- | ---: | --- |
| Heathrow | 94 | All 36 numeric FM-12 records were automatic SYNOP; 58 were METAR. |
| Tokyo | 0 | Missing total 99 despite a populated lowest layer. |
| Fort Smith | 49 | 48 METAR records; one staffed SYNOP candidate shared nearby automated airport meteorology. |

Payload SHA-256:

- Heathrow: `79a2f6ddca4452d293ef490d88f468d0901fffaf418b4aa3911c706e6fb39292`
- Tokyo: `40f74010c46240b074ff6ab7ac84376f9006963bb0fa5e43fe72e5b6b13f6be0`
- Fort Smith: `b58f7951ea00ef46c3c3203f72323c2706116d331989398d45479acd13160dd9`

FM-12 denotes a SYNOP land-station report, not a manual sensor contract. SYNOP can be staffed
or automatic ([MANOBS section 15.3.3.2](https://www.canada.ca/en/environment-climate-change/services/weather-manuals-documentation/manobs-surface-observations.html)).
None of these files established independently verified manual total-sky truth. The experimental
GF1 extraction was therefore removed before release. Raw values remain available for inspection,
but they are not emitted as `cloud_cover` observations and cannot affect existing scalar sample
selection or cloud calibration. A regression checks that GF1 values, including malformed optional
fields, neither invent cloud truth nor discard supported scalar observations. No weights were
fitted, enabled or exported here.

## Verification and remaining work

The regional-current outage regression failed before the fix and passed afterward. A second
regression reproduced the quarter-hour boundary rejection, then passed with exact-slot selection.
Offset and array-length regressions also failed before their guards and passed afterward.

The complete research suite passed 407 tests; Ruff and strict Pyright passed. The Huawei
forecast-layout suite initially found clipping of the new timestamp at 2x text scale; after
the width fix, all 23 layout tests passed. Android's JSON implementation then reproduced a
`Forbidden numeric value: Infinity` crash from an optional regional array value; the finite-value
guard fixed it. The combined Huawei suite passed all 24 tests. Live debug refresh persisted
ALADIN as the current sky source.
Separate transient HTTP 503 and timeout attempts preserved and labelled the existing cache.

Production accuracy calibration still requires immutable forecasts captured before their valid
times, compatible independent truth, training-only fitting, and untouched holdouts across
regions, seasons, variables and lead ranges. These parser and reliability results are not a
cloud or rain skill score. Raw inputs and device evidence remain local; this report contains no
personal device dump or account data.
