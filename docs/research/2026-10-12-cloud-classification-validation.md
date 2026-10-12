# Sky classification and confidence: 12 October 2026

## Changes

The hourly blend previously replaced dry model weather categories with a category derived
from median cloud cover. Provider categories do not necessarily use those same percentage
thresholds. The blend now keeps a strict majority of at least three valid dry categories.
Ties and missing categories retain the existing cloud fallback. Precipitation and hazard
guards remain unchanged. This fixes a classification inconsistency; it is not a measured
increase in forecast accuracy.

Wind disagreement now uses the largest distance between two actual model wind vectors.
Previously it used the diagonal of their bounding box. Four 10 km/h winds from the cardinal
directions produced 28.284 km/h, although the largest actual pair difference is 20 km/h.
That could incorrectly cross the 25 km/h disagreement threshold. Thresholds are unchanged.

The current-condition observation path is separate from hourly model blending. These changes
do not turn an hourly forecast into a local observation.

## Frozen Zlín replay

The existing forecast captured on 5 September was replayed against the station records for
6–7 September, using `research/scripts/replay_capture_20260905.py`. Its report remained
byte-identical after the capture extension, SHA-256
`aab13714c7dc96dc43eae6cebef856fc6aa57ba41c668c9962656d39e98ac001`.

Temperature mean absolute error on the same 48 provisional QC5 hours:

| Forecast | Error, °C |
| --- | ---: |
| ICON | 0.815 |
| Arithmetic mean | 0.858 |
| Median | 0.931 |
| ALADIN | 1.071 |
| GFS | 1.217 |
| ECMWF IFS | 1.563 |

Only two temperature hours had checked QC0 observations. All 48 provisional precipitation
hours were dry. Best Match and cloud forecasts were not in that capture. This sample cannot
validate rain detection, cloud classification, a general advantage for blending, or a
location-specific dominance of ALADIN. No weights were fitted or exported.

## New measurements and limits

New prospective captures request total cloud cover and weather categories alongside the
existing scalar variables. A real ALADIN + ICON collection for the public Zlín station
completed at `2026-10-12T12:59:54.645141Z`; only future valid times are eligible. Raw responses
and manifests remain local, immutable and checksum-checked. Legacy captures replay exactly
the fields declared in their saved request URL.

Weather codes are categories, not a numerical error scale. They remain unscored. The existing
ČHMÚ and NOAA ISD adapters do not supply independently verified total cloud cover, so the new
capture does not yet establish cloud skill. Future weighting needs compatible independent
observations, a training-only fit, and an untouched holdout for each variable and lead range.

The UI confidence indicator describes model agreement, not the probability of a correct
forecast. A next-hour forecast can have low agreement when cloud predictions span 0–100%.
A short lead time does not remove that disagreement. Long-range caps and missing-data guards
were not relaxed to make the label appear more certain.
