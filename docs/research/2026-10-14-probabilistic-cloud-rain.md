# Probabilistic cloud and rain confirmation

Decision: keep this correction research-only. Cloud results are promising on
independent observations, but coverage, rainfall and operational-provenance
requirements do not all pass. No Android estimator, confidence label or
production calibration artifact changes.

## Method

Fit a probability distribution on the individual deterministic model values,
not a scalar weighted average. Nonnegative weights sum to one. Minimize mean
continuous ranked probability score, CRPS, plus a squared-distance penalty
toward equal weights, scaled by the training mean absolute deviation from its
median. CRPS scores the full predicted distribution. A better
CRPS does not establish better rain detection or calibrated confidence.
The [ECMWF CRPS guide](https://www.ecmwf.int/en/elibrary/81707-hydrologists-guide-crps)
describes the score and its relation to absolute error and Brier score.

The empirical score is
`sum(w_i * abs(f_i - y)) - 0.5 * sum(w_i * w_j * abs(f_i - f_j))`.
The implementation checks finite inputs, probability mass and the computed
score. Numerical overflow must not become a perfect zero score. The fitter
uses the existing constrained optimizer and an analytic gradient. This is not
a full precipitation EMOS or cloud ordinal-regression implementation.

Use separate cloud/rain and nominal previous-day1/day2 fits. Initial training
is March-May 2025 at Praha Libuš, Holešov and Prostějov. Purge validity times
at or after the earliest nominal June validation issuance. Select the penalty
from 0.001, 0.01 and 0.1 using June 1-28 CRPS, breaking ties toward the larger
penalty. Refit through June 28, then freeze weights before confirmation.
Cloud training has 5,161 and 5,153 complete pairs over 120 days, respectively.

Truth is native ČHMÚ quality-0 whole-sky `N`, integer octas 0-8, and native
quality-0 `SRA1H` gauge accumulations. Exclude obscured sky code 9, unknown
quality, trace amounts and unsupported flags. Match exact station and UTC hour.
Rain forecasts and gauges both cover the preceding hour. Missing values are
not zero. The [official hourly archive](https://opendata.chmi.cz/meteorology/climate/historical_csv/data/1hour/)
separates `synop` cloud files from `precipitation` gauge files.

All six predetermined stations are 11520, 11774, 11747, 11457, 11659 and 11698.
The last three, Churáňov, Přibyslav and Kuchařovice, are spatial transfer sites.
Models are the app's eleven requested Czech model families plus Best Match.
UKMO fails training coverage. A seamless model ID does not establish the native
regional source or statistical independence of the members. In particular,
historical ALADIN Seamless must not be described as native ALADIN CZ 1 km.
The saved ALADIN and IFS025 cloud/rain arrays are identical over all 22,032
station-hours per variable/lead in March-July. Their labels are not two distinct
value streams; the upstream cause was not established. Preserve this limitation
rather than claiming independently validated regional ALADIN skill.

## Rejected July complete-case confirmation

The first frozen test requested July 1-31, 2025. BOM ACCESS disappears after
July 6 in the saved responses. Requiring every selected model leaves only
387 cloud and 717 rain pairs per lead over six dates. Official observations
cover the full month. Every per-site 20-day coverage requirement fails.

The seven-day bootstrap has only one block. Its degenerate interval is not
significance evidence, even though the prototype upper-bound check reports
true. Preserve the result and reject deployment. Rain daily intervals include
zero; some spatial cloud point-error guards also fail. Do not remove BOM and
relabel a rescored July cohort as untouched confirmation.

## New August missingness confirmation

Before scoring August, freeze the same weights and a separate missingness
policy. At each hour require at least three available positive-weight members
and 70% retained fitted mass. Renormalize those weights. Equal-CDF and raw
mean/median comparators use the identical active member set. Best Match uses
the identical case mask. Individual-model comparisons re-score all comparators
on that model's available subset. No weights, thresholds or point-extraction
rules are refitted on July or August errors.

August 1-31, 2025 gives 2,325 cloud pairs and 4,432 rain pairs per lead, including
995 cloud pairs at the three spatial transfer sites. Both lead cohorts cover
31 dates. Daily and seven-day resampling use 31 and 5 blocks, respectively.

| Cloud metric | Nominal day1 | Nominal day2 |
| --- | ---: | ---: |
| CRPS, learned / equal, fraction | 0.10557 / 0.12527 | 0.11678 / 0.13354 |
| Point MAE, learned / raw mean, percentage points | 14.02 / 16.33 | 15.21 / 17.38 |
| Point MAE, learned / raw median, percentage points | 14.02 / 17.84 | 15.21 / 19.25 |
| Point MAE, learned / Best Match, percentage points | 14.02 / 20.44 | 15.21 / 22.16 |
| False clear, learned / equal / Best Match | 4 / 21 / 25 | 8 / 46 / 12 |
| False overcast, learned / equal / Best Match | 3 / 0 / 25 | 1 / 1 / 54 |
| Central 80% interval observed coverage | 78.19% | 76.04% |

False clear means a point forecast below 20% when observed `N>=7/8`.
The learned-minus-equal CRPS 95% intervals are below zero for both block sizes.
Seven-day intervals are `[-0.02655, -0.01309]` and `[-0.02399, -0.01106]`.
At the unseen spatial sites, cloud point MAE is 14.22/15.03 percentage points,
versus 20.30/21.55 for the equal-CDF point summary. These are archived-cohort
results, not measured improvements to the installed app or next-hour forecast.

Cloud still fails the frozen all-site coverage requirement: Holešov has 126
valid pairs over 18 days, below 20. Do not relax that requirement after scoring.
The August protocol accidentally omitted the original per-site CRPS regression
guard. A stricter post-score audit checks it separately: all six sites pass
against Best Match at both leads. This audit is not falsely labelled pre-registered.

| Rain at least 0.1 mm | Nominal day1 | Nominal day2 |
| --- | ---: | ---: |
| Observed wet cases | 245 | 245 |
| Misses, learned / Best Match | 114 / 137 | 93 / 134 |
| False alarms, learned / Best Match | 201 / 172 | 322 / 254 |
| CRPS, learned / equal, mm | 0.06466 / 0.06624 | 0.07123 / 0.07154 |

Rain does not pass. Fewer misses come with more false alarms, and both daily
and seven-day CRPS intervals include zero. Report 1 mm and 5 mm event scores and
reliability bins in the immutable result too; overall error can hide heavy-rain
misses. Do not turn model agreement into a validated rain probability.
For nominal day1, the point median misses 73 of 85 events at least 1 mm and
all 15 events at least 5 mm. That is a substantive reason not to ship this rain fit.

## Evidence and limits

Local raw payloads, returned grids, request URLs, UTC receipt times, hashes,
frozen protocols and results remain under `.reference/tmp/`. They are not
bundled as repository data or silently replaced.

- Frozen fits SHA256: `f14594de60c0ac687a04bd908a948f9caa8e5c81952d446f1df8299bf2c6bbb5`.
- July result SHA256: `ccbc3f916e545847eda89139d8e562ec17f436f38b19d22b1ace4fd0ffe14686`.
- August protocol SHA256: `9daa3a81b6d40f0dd04fe1020e498ccb0a9c5509897006ccda13776094e24c50`.
- August result SHA256: `3e29dbafa3c0012685e0f512556e423c926cc4894a2998f20cb29f44159b59f2`.

Argon was consulted with Highest reasoning selected. Its warnings about
missingness, finite distribution tails and separate point-event evaluation
informed the review. Its proposed mandatory octa quantization was not adopted:
real-valued CRPS does not require rounding predictors to observed categories.
Statements about universally uncalibrated linear pools require the assumptions
of the [original combination theory](https://arxiv.org/abs/1106.1638), not a
claim that this experiment proves or disproves calibration.

Previous Runs does not independently establish historical public availability
or exact initialization of every member. Best Match is a diagnostic comparator,
not the app's full fusion kernel, whose weather codes were not in this cohort.
Six Czech stations, one month and day-offset leads cannot establish worldwide,
all-season or next-hour superiority. Operational export remains blocked.
