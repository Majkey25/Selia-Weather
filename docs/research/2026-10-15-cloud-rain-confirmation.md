# November cloud and rain confirmation

Decision: retain the fitted correction in research. New independent station
outcomes show several improvements over Best Match, but all four variable/lead
cohorts fail overall acceptance. No learned runtime weights or confidence
thresholds changed. Google Play review was not touched.

## Frozen test

Test November 1–30, 2025 at Praha Libuš, Holešov, Prostějov, Churáňov,
Přibyslav and Kuchařovice. These are the same six previously studied sites,
not six unseen locations. The final three were outside training. November
cloud/rain outcomes had not been inspected in this experiment before freezing
the protocol. The existing March–June empirical CRPS fits remain unchanged.

Use native ČHMÚ QC0 whole-sky `N`, integer octas 0–8 with blank flags,
and native QC0 `SRA1H` gauge totals with the existing accepted flags. Exclude
unknown sky code 9, missing values and censored trace amounts. Match station
and exact UTC hour. Rain totals cover the preceding hour. Sources are the
[official SYNOP archive](https://opendata.chmi.cz/meteorology/climate/historical_csv/data/1hour/synop/2025/)
and [gauge archive](https://opendata.chmi.cz/meteorology/climate/historical_csv/data/1hour/precipitation/2025/).

Keep at least three available positive-weight model labels and 70% fitted mass,
then renormalize. This is not a guarantee of independent models. Historical
ALADIN Seamless and IFS streams previously coincided; their frozen weights
were not altered. All comparators share identical station-hour masks and
available members. Candidate point estimates remain weighted medians.

The 22 six-point model/lead requests and 12 native observation files are retained
locally with hashes and receipts. Six GFS cloud cells were outside 0–100%,
two at -1% and four at 101%. The first parser attempt stopped before opening
truth or calculating errors. A documented pre-score repair treats only these
invalid cells as missing for every comparator, including Best Match, without
clipping. Immutable raw bytes, rejected cells and derivative hashes remain.
No weights, event thresholds or coverage requirements were changed.

## Cloud results

Each nominal lead has 2,123 matched station-hours over 30 dates. These are
paired cases, not independent samples per hour. Bootstrap UTC dates/weeks
together across all sites.

| Nominal lead | Candidate MAE | Equal-pool point MAE | Best Match MAE | False-clear candidate/equal/Best Match |
| --- | ---: | ---: | ---: | ---: |
| Day 1 | 12.223 pp | 13.060 pp | 16.004 pp | 9/30/60 |
| Day 2 | 13.062 pp | 13.947 pp | 17.347 pp | 11/26/62 |

False clear means forecast below 20% against observed `N>=7/8`. Raw mean
has fewer false-clear cases than the candidate, 8/5 at day 1/day 2, despite
worse MAE of 14.656/16.378 pp. No estimator wins every relevant metric.

Holešov has 133 accepted cases over 19 days at each lead, below the frozen
20-day requirement. Its day 1 point MAE rises from Best Match's 11.827 pp
to 13.316 pp, a 12.59% regression exceeding the 5% site limit.

The frozen 2,000-draw daily and weekly CRPS intervals do not establish improvement
at day 1. Day 2's reported candidate-minus-equal CRPS intervals are negative:
daily `[-0.01578773,-0.00067518]`, weekly `[-0.01865794,-0.00009567]`,
in cloud fraction units. A separate post-score sensitivity audit enumerated
all 3,125 resamples of the five weekly blocks. Its day 2 interval is
`[-0.01943625,+0.00003365]`, crossing zero. Preserve the frozen report;
this is Monte Carlo sensitivity, not robust evidence of weekly significance.

## Rain results

Each lead has 4,300 accepted station-hours, including 511 totals at least 0.1 mm.
All six sites have 30 accepted dates. At nominal day 1 and 0.1 mm:

| Metric | Candidate | Equal pool | Best Match |
| --- | ---: | ---: | ---: |
| Misses | 122 | 73 | 138 |
| False alarms | 230 | 312 | 267 |
| CSI | 0.52497 | 0.53220 | 0.47943 |
| CRPS, mm | 0.0435658 | 0.0427264 | 0.0736279 |

Candidate point-event detection improves over Best Match, but equal pooling
has better CRPS and CSI. The candidate-minus-equal seven-day CRPS interval
is entirely positive, `[+0.00030156,+0.00142013]mm`, indicating worse scores
in that block analysis. Daily resampling includes zero. At 1 mm the candidate
misses 71 events versus Best Match's 64. Both miss the two 5 mm events.
At day 2, candidate false alarms increase to 321 versus Best Match's 286.
Lower average error does not establish better protection against heavier rain.

## Current-observation defect fixed separately

A real [AWC CYKL report](https://aviationweather.gov/api/data/metar?ids=CYKL&format=json&hours=2)
at 2026-10-15 09:33 UTC contains raw `-SNRA`, normalized as `-SN -RA`.
The parser previously discarded this mixture. A synthetic dry model fused
with that report became only cloudy, without precipitation evidence.
This reproduces a real input-handling defect, not an observed CYKL forecast miss.

The correction preserves light/non-light mixed rain or drizzle with snow as
codes 68/69, matching the [WMO present-weather table](https://emc.ncep.noaa.gov/mmb/data_processing/table_20.htm).
Split-group intensity uses the non-light category if any component is non-light;
this coarse aggregation does not infer an amount or exact combined intensity.
Exact supported tokens and split groups retain storm/hail priority, unknown
and freezing safeguards, freshness/distance limits and original amounts.
Code-specific labels, mixed icons in app/widget, hourly highlights, cache
round trips and phase-aware confidence groups retain the mixture. No METAR
phenomenon is converted into an accumulated amount or numerical probability.

## Obscured-sky observation contract

AWC distinguishes [VV/OVX vertical visibility](https://aviationweather.gov/gfa/help/)
from a cloud amount. Its [METAR documentation](https://aviationweather.gov/help/data/)
defines vertical visibility into a surface-based obscuration, not a measured
whole-sky cloud percentage or proof of fog.

The retained global AWC snapshot received at 2026-10-15 10:26:46 UTC contains
5,188 reports, including 45 with OVX/VV. Its SHA256 is
`c89698ad74615fb237ae0fe7a79794522852ce8d8c033543627681690d78bb0f`.
Separate JSON responses retain actual reports from Indonesia, France, Chile,
Iceland and the United States. WIOO/WIMM report smoke with an obscured sky;
LFHP reports obscuration without a present-weather group. SCVD reports heavy
rain, BITN mixed snow/rain, and KLAR light snow with mist.

Previously, OVX with no recognized phenomenon could leave a synthetic clear
model unchanged; VV instead invented 100% cloud cover. The shared correction
preserves an explicit obscured-sky state without inventing a cloud amount or
fog. Known rain/snow/fog and modeled storm safeguards remain in force. Regression
replays use the actual observation fields but deliberately synthetic model
conditions: they prove the input contract, not an observed forecast-error gain.

The internal obscuration code survives native JSON caching and app/widget
labels. Current details do not present retained model cloud amounts as an
observed amount for that state. Unknown sky is not a confirmed categorical
disagreement and cannot receive high confidence; genuine model conflicts remain.
Rain amounts, probabilities, fitted weights and confidence thresholds do not
change. This bounded fix does not establish improved future forecasts or
worldwide non-regression.

## Evidence and limits

- Frozen fits SHA256: `f14594de60c0ac687a04bd908a948f9caa8e5c81952d446f1df8299bf2c6bbb5`.
- November protocol SHA256: `b3043a4cce250d2c75f0e1ca02d4031018cc5fa25714d3174f92183ca73cb1c0`.
- Revised pre-score runner SHA256: `49683c05c50b660bd7a9e1d7457e04f284bab71decbe16720d43f57caac65b00`.
- Frozen score SHA256: `e5160e150f3c2ea68b783e0cf59a5e55546ce1aea6ce85caf42a1404010247c5`.

Raw inputs, score details and post-score sensitivity audit are local under
`.reference/tmp/november-confirmation-20261015`, not bundled repository data.
One-off verification scripts were removed after use. Three Astra helpers at
maximum reasoning audited source handling, rain and methodology. Independent
raw reconstruction reproduced consequential metrics and intervals; the primary
agent also reproduced the exhaustive weekly sensitivity.

Fresh checks of all three October 14 prospective truth endpoints still contain no
QC0 `N/SRA1H` outcomes. Provisional observations were not promoted to calibration
truth. Nominal Previous Runs fields do not independently establish historical
public availability or exact native initialization. This is archived seasonal
validation, not next-hour, worldwide or installed-app superiority. None of the
results authorizes higher UI confidence or production export.
