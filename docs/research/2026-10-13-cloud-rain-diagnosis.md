# Cloud and precipitation diagnosis

## Březnice reproduction

The reproduced forecast is for Březnice, Zlín region, near 49.19, 17.67.
Do not substitute the town near Příbram. A read-only cache capture at 09:58:13 UTC on
13 October 2026 had been updated at 09:46:45.447 UTC: about 11 minutes old.
The API response uses Europe/Prague and a fixed UTC offset of 7,200 seconds.

At 11:00 local time, ten models supplied these total-cloud predictions:

| Model | Cloud cover (%) |
| --- | ---: |
| CHMI ALADIN Seamless | 1 |
| CMA GRAPES Global | 1 |
| ECMWF AIFS | 41 |
| ECMWF IFS | 40 |
| GEM Seamless | 0 |
| GFS Seamless | 76 |
| ICON Seamless | 0 |
| JMA Seamless | 0 |
| Météo-France Seamless | 3 |
| UKMO Seamless | 73 |

BOM had no usable cloud value; it is not a zero or a vote. All ten available models
reported zero precipitation for that hour. The Czech short-range sky baseline selected
ALADIN's Clear category. The unweighted cloud median also falls in the clear range, so
removing the regional baseline would not resolve this particular example.

The displayed LOW confidence comes from the actual 76-percentage-point cloud range,
which exceeds the existing 60-point conflict threshold. It is not caused by the lack of
learned cloud weights, an old download, or the forecast being far in the future. At 13:00,
ALADIN instead reports Cloudy with 82% cloud; the displayed condition also disagrees with
the models' modal category. Confidence is an agreement indicator, not a measured chance
that the forecast is correct. No confidence threshold is raised in this release.

This capture does not establish the observed sky over the user's exact location. The
nearest available Kunovice METAR was about 23 km away and is excluded by the existing
10 km sky-observation limit. Nearby station rain gauges reporting zero cannot rule out
local drizzle. Current CHMI cloud records with unknown quality or stale measurement
times are not promoted to validated local truth. A user's report of overcast conditions
is useful evidence of a miss, but not sufficient to fit regional weights by itself.

## Implemented defects

- Hourly precipitation is an accumulation over the preceding hour. The old condition
  calculation could turn this amount into rain at the displayed instant even when the
  instantaneous codes indicated dry conditions. Remove that amount-to-condition shortcut;
  retain provider phenomena and strict model-code majorities.
- Drizzle, rain and showers could together form a strict liquid-precipitation majority
  without any individual category reaching the threshold. Preserve that liquid majority
  using the most frequent actually reported liquid code. Keep freezing precipitation,
  snow, storms, minority and tie safeguards separate.
- Matching local timestamp strings across model responses with different UTC offsets
  could associate different instants. Reject an incompatible or malformed source offset
  and retain the provider forecast.
- The overview could promise no rain in the next 24 hours despite a minority model
  predicting trace precipitation. Show uncertainty without inventing an arrival time or
  changing the provider probability. A fraction of deterministic models is not a calibrated
  precipitation probability.

These are reproducible computation and presentation defects. They do not establish a
measured cloud or rainfall accuracy gain across locations. Separate observation validation
must decide whether a numerical estimator or learned weighting is safe to deploy.

## September precipitation comparison

A separate run used 15 Czech stations, exact 12:00 UTC observations and the unexamined
1–30 September 2026 period. Training used 20 May through 31 August, with the existing
forecast-issuance purge. Each lead has 440 accepted pairs: ten observations failed QC.
There were 29 events at least 0.1 mm, seven at least 1 mm and one at least 5 mm.

| Nominal lead | Brier blend / fallback | Amount MAE blend / fallback (mm) |
| --- | --- | --- |
| 24 h | 0.06319 / 0.07955 | 0.05205 / 0.05727 |
| 48 h | 0.05633 / 0.10682 | 0.06123 / 0.07023 |
| 72 h | 0.05662 / 0.10227 | 0.05761 / 0.07977 |
| 96 h | 0.05934 / 0.09773 | 0.05114 / 0.05795 |
| 120 h | 0.05991 / 0.12727 | 0.06517 / 0.09636 |
| 144 h | 0.06091 / 0.21136 | 0.06068 / 0.09864 |
| 168 h | 0.06139 / 0.19318 | 0.05818 / 0.08545 |

Lower scores are better, but they are not sufficient to release these weights. The
training-selected fallback was Best Match through 96 hours and GFS afterwards. At 144 hours,
the blend detected only one of 29 wet cases and missed all seven events at least 1 mm.
Some shorter-lead scores also failed the existing regional-degradation and significance gates.
ALADIN CZ 1 km did not meet 90% training coverage: its archive began on 24 June, so this
comparison cannot establish an ALADIN advantage or disadvantage.

The 144-hour amount fit was effectively one IFS source, but tiny optimizer residues
(`2.78e-17` and `4.86e-17`) falsely satisfied the three-source check. The fitter now sets
weights no larger than its existing `1e-12` solver tolerance to exactly zero before
normalization. No new meteorological tuning threshold is fitted to September. Preserve the
original report as a pre-correction diagnostic, not an exportable calibration artifact.

A cache-only correction reanalysis of the same 192 raw inputs rejects the 144-hour amount
segment with `insufficient_sources`; its fitted weights are now IFS 1, GFS 0 and UKMO 0.
All seven amount segments are rejected. Only the occurrence gates at 96, 144 and 168 hours
remain accepted. Every amount MAE and occurrence evaluation is unchanged. The corrected
report SHA-256 is `f5a3ba3071bbd97540a19332cfe1c9602eaea1740f92962e441a3b5b1c7bdc60`.
This is a code-correction reanalysis, not another untouched validation period, and no
production artifact is exported.

The frozen dataset manifest is
`25046f61d202308cbd6e81e4738fb157bd6df239c2bcd181961493f6a8c979f3`;
the original report SHA-256 is
`18507e94d400190e3bd9573c28fb7c4c7aada0e307682df9536c3b791b178d20`.
The comparison uses provider Previous Runs lead labels; exact initialization and public
availability were not independently established. It covers station-noon cases, not every
hour, local drizzle below the event threshold, the whole world or long-term seasons.
September is now examined data and cannot be reused as a new untouched holdout.

## September cloud-cover comparison

Development used August 2025 observations. Before examining confirmation errors, freeze an
unweighted mean of the app's ten global model candidates and compare it with the existing
global median, Best Match, native ALADIN CZ 1 km and ALADIN Seamless. The confirmation period
is 1–30 September 2026. The older seamless archive is not assumed to be native ALADIN: a
seamless product can contain a global fallback outside its primary model's coverage.

CHMI whole-sky observations use exact UTC hours, quality code 0 and valid octas 0–8,
converted once to percent. Unknown sky code 9 and missing-quality records are excluded.
Each nominal lead has 1,307 accepted pairs: Libuš 450 over 30 days, Holešov 144 over 21 days
and Prostějov 713 over 30 days. Missing BOM values do not enter the mean or median.

| Nominal lead | Mean MAE | Median MAE | Best Match MAE | Native / seamless ALADIN MAE |
| --- | ---: | ---: | ---: | ---: |
| 24 h | 15.912 | 16.579 | 22.650 | 19.707 |
| 48 h | 17.089 | 17.852 | 22.497 | 21.946 |

Errors are percentage points, not rain probabilities. Native and seamless ALADIN agree on
all 2,160 available grid-hour values per lead in this confirmation. Predicted cloud cover
below 20% against observed cover at least 87.5% occurred 2 / 8 / 6 / 29 times at 24 hours
and 1 / 8 / 17 / 32 at 48 hours, in the table's estimator order.

The frozen gate requires superiority to every benchmark under both daily and consecutive
seven-day paired bootstrap blocks, with 2,000 resamples and seed 20260825, adequate site/day
coverage, no site MAE degradation above 5%, and no increase in those false-clear counts.
The candidate fails the 24-hour mean-minus-median daily 95% interval:
`-1.3826785 to +0.0132234` percentage points. Other gates pass, but the decision remains
NO-GO. Do not weaken the gate or promote the mean after viewing September. No runtime
cloud estimator or confidence threshold changes follow from this result.

This study measures hourly cloud-percent error at nominal 24/48-hour provider lead labels.
It does not validate current 15-minute WMO conditions, sub-hour forecasts, all seasons or
worldwide performance. These three stations cannot establish the sky at Březnice today.
Exact model initialization and public availability were not independently verified.

Evidence SHA-256:

```
Frozen gate: 0d12ebebb9c53342dd5175af78b35502668c98dbffa928fedb05795250639c23
Confirmation report: 2630af5c603458e60445bddcaee41b0e33566a4b69890d37b70c73cf635f1660
Paired cases: bded7d059945c4ac277fb9ce43325abe41ed431081e58d056d54c5685104c412
```

Raw payloads and their hashes remain in the local research cache. Seven transport-reset
requests were retried with unchanged parameters before scoring; successful bodies were
reused. September is now examined data, not a future untouched validation period.

The cache-only [replay script](../../research/scripts/cloud_confirmation_replay.py)
reconstructs this inspected study from the verified raw responses, not the paired CSV
or saved scores. It reproduced all 2,614 paired cases, the mean/median false-clear
counts of 2/8 and 1/8, and the exact positive daily interval bound above. The original
scripts are unavailable; this is a tested reconstruction, not recovery of those
scripts or another independent validation. No weights or Android assets are exported.

## Verification contract

Regression tests cover dry instantaneous codes with positive preceding-hour amounts,
mixed `[51, 51, 61, 80]` liquid codes with both zero and positive amounts, provider hazards,
minority/tie behavior, malformed UTC offsets and a trace-rain overview. Device tests use
the real Android JSON implementation. Confidence conflict evidence remains unchanged.

The Huawei Android 10 device passed all 27 targeted instrumented tests after correcting
a new fixture's assumption that hourly consensus should replace provider current sky.
Current sky remains at the provider's own validity time. The live current-hour and
forecast-help workflows were also checked: model names, cloud percentages, the specific
cloud-range conflict and basic hourly metrics were visible and scrollable. These checks
verify computation and presentation, not the actual sky at the forecast location.

Observation comparisons use exact UTC timestamps, explicit station coordinates and quality
filters. Model archives are forecasts, not observed weather. Development data cannot be
relabeled as untouched validation. No learned production cloud/rain weights are enabled by
these fixes.

Sources: [Open-Meteo variable definitions](https://open-meteo.com/en/docs#hourly-parameter-definition),
[Previous Runs API](https://open-meteo.com/en/docs/previous-runs-api),
[CHMI observation data and quality definitions](https://opendata.chmi.cz/meteorology/climate/Klimatologicka_data_popis.pdf).
