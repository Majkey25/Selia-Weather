# Removing unconditional regional hourly sky dominance

The frozen July 2026 comparison supports removing the Czech-only hourly sky
override. It does not establish worldwide, all-season or next-hour superiority.
The separate rainfall candidate failed and is not included in Android.

## Cause and smallest correction

The hourly kernel normally uses a multi-model cloud median and preserves its
selected sources' cloud layers. A Czech-only branch instead selected ALADIN
Seamless alone for benign, apparently dry hours through 48 h. It replaced both
the cloud amount and benign sky code, even when other models disagreed.
Regional resolution was being used as a substitute for demonstrated local skill.

Remove that exception. ALADIN remains in the median and categorical comparison;
it is not removed from requests. Preserve the provider's precipitation phenomena,
minority wet-provider safeguard, missing-value handling and minimum contributor
count. Keep current quarter-hour regional sky and station/METAR fusion unchanged.
Keep historical provenance decoding and confidence thresholds unchanged.

## Independent temporal check

Freeze the two candidates, sites, metrics and rejection rules before obtaining
July 2026 outcomes. This month was not scored in the preceding cloud/rain studies;
the stations and other periods were already studied. No coefficient was fitted.

Stations: Praha Libuš 11520, Holešov 11774, Prostějov 11747, Churáňov 11457,
Přibyslav 11659 and Kuchařovice 11698, using official station coordinates.
Truth is the native [CHMI July hourly archive](https://opendata.chmi.cz/meteorology/climate/recent/data/1hour/07/):
whole-sky N, integer 0–8 octas, blank flag, QC0; code 9 and missing values excluded.
Native SRA1H uses QC0 and documented numeric gauge flags; trace is not zero.
Monthly snapshots are not represented as immutable final annual observations.

Request eleven actual Czech app model families and Best Match, six fields,
nominal Previous Runs day1/day2, July 1–August 1. August 1 midnight supplies only
following-hour veto context, not July truth. Retain source bytes, URLs, units,
returned grids, receipt times and SHA256. Best Match is outside the member pool.

Replay the actual compiled 0.7.14 Kotlin kernel, not a Python transcription
(source commit `0407731d11d0bd02eb836e3ca7459867664c5e81`, checked clean before
collection and replay). The baseline class hash was recorded during scoring
before the production source edit/rebuild, not embedded by the replay runner.
For each valid hour include both accumulation boundaries. The original Czech
path is the baseline; the same kernel with the non-Czech hourly path is the
predeclared no-override candidate. No calibrated artifact is supplied.
After implementing the deletion, replay the new compiled Czech path and require
exact output parity on all 8,928 point/hour/lead records. Precipitation is unchanged.

The [Previous Runs contract](https://open-meteo.com/en/docs/previous-runs-api)
does not independently prove original public availability or exact run receipt.
These are nominal archived leads, not verified operational 1 h/24 h/48 h skill.
No learned artifact is exported on this evidence.

## Cloud results

Each lead has 2,395 accepted observed station-hours. Coverage is 144–744 cases
and 22–31 dates per site. Both candidates use exactly the same accepted rows.
All six sites improve MAE at both nominal leads.

| Measure, original → no override | Day1 | Day2 |
| --- | ---: | ---: |
| Cloud MAE, percentage points | 21.497 → 18.865 | 22.962 → 19.658 |
| Mean bias, percentage points | −11.423 → −12.407 | −8.339 → −11.408 |
| False clear: forecast <20%, observed ≥87.5% | 50 → 16 | 52 → 35 |
| False overcast: forecast ≥87.5%, observed <20% | 4 → 2 | 17 → 1 |
| Daily bootstrap MAE difference, 95% interval | [−3.786, −1.588] | [−4.812, −1.760] |
| Seven-day block interval | [−3.843, −1.708] | [−4.640, −1.915] |

Use 2,000 paired resamples, seed 20260825, resampling common UTC date blocks
across sites. Seven-day sensitivity has five consecutive blocks, including a
short final block; it is not many independent weather regimes. Differences
span all 31 dates, all six sites and all five blocks. The frozen cloud gates pass.
The mean bias becomes more negative despite lower absolute error: cloud amount
is still under-forecast on average, and this correction does not solve that bias.

Example: Libuš, July 31 at 20:00 UTC, observed N=8. Original hourly output is
19% cloud and clear; the candidate is 89% and overcast, with rain unchanged at 0 mm.
This is station evidence, not a direct observation at the user's Březnice location.

Additional **post-hoc** icon-code audit is mixed: clear/mainly-clear codes 0/1
against N≥7 decrease 141→123 at day1 but increase 151→153 at day2. These codes
and percentage thresholds are different tests. Do not claim every false-sun
icon is fixed or silently change the frozen percentage gate. The existing
categorical aggregation still needs a separately frozen improvement study.

## Rain rejection

Candidate B would additionally preserve provider amounts ≥0.1 mm when the raw
median is positive but below 0.1 mm, while retaining the existing zero-median
trace safeguard. Reconstruct and assert every existing amount against compiled
kernel output before applying this exact numerical counterfactual. It was
rejected before implementation; no compiled candidate-parity claim is made.

Each lead has 4,459 gauge pairs and 240 wet observations ≥0.1 mm.

| Measure, existing → candidate B | Day1 | Day2 |
| --- | ---: | ---: |
| Missed wet hours | 112 → 106 | 114 → 106 |
| False alarms | 369 → 389 | 455 → 498 |
| All-hour MAE, mm | 0.10261 → 0.10636 | 0.10455 → 0.10921 |

More recovered rain does not justify more false alarms and worse amount error.
The frozen rain gates fail. Rainfall amounts, probabilities and confidence
remain unchanged in this release. Heavy-rain misses remain a material limitation.

## Independent advice and evidence

Astra identified trace-preservation and interval/cross-effect checks. Opus 5.5
review requested implemented-kernel parity and explicit bias/icon disclosures;
those checks and disclosures are included. Argon,
explicitly Highest, requested active-difference support and Best Match exclusion
checks; both were verified. Advice is not empirical proof. Their warnings do
not turn a failed rain hypothesis into an accepted result.

Local immutable evidence remains under `.reference/tmp/kernel-july-20261014/`.

- Frozen protocol SHA256: `de9fee6937647df7cdf7d96dcecb7b3ceb6ca31981e3e6a8fdd51236c19387f5`.
- Original replay SHA256: `ea4bf934a8d488d9b5650a357172adda2f53cbd33ef9ba0f07d205cb298ca35a`.
- Implemented replay SHA256: `93ca3c0e009b740034a2df63550cb3ad73f9cba590ad1527a85c7aceff9fc70e`.

The correction improves this hourly cloud cohort, not every weather variable.
No claim of calibrated high confidence, world-leading accuracy or a solved
current-condition display follows from this experiment.
