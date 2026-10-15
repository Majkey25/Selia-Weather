# Current-sky fallback and rejected rain alternatives

## Corrected ingestion defect

The current-sky path selected a regional `current` object by timestamp before
checking its required weather code and cloud amount. Missing or invalid fields
then returned the original forecast, even when `minutely_15` already contained a
complete sample for exactly the same valid time and UTC offset.

Validate the preferred sample before selecting it. Otherwise try the existing
unique, exactly time-matched quarter-hour slot. A recognized rain, fog, snow or
storm code in the preferred sample still vetoes dry fallback, even when cloud
amount is missing. Do not change model weights, thresholds, hourly forecasts,
rain amounts, probabilities, confidence or observation support limits.

The regression reproduces the old rejection before the change. It covers null,
string, out-of-range and fractional required fields; separate tests retain the
phenomenon veto. The physical-device regression also exercises Android JSON's
non-finite number handling. Android rejects non-finite object values while
parsing the entire response; that malformed response safely retains the base
forecast rather than claiming a recovered slot. Non-finite array values remain
rejected by the existing finite-value guard. The first device run caught an
incorrect test expectation for this parser difference; the explicit boundary
assertion was corrected without changing production parsing. This proves ingestion behavior, not a measured
meteorological gain. No retained live response has yet established this defect
as the cause of the user's Březnice sky error.

## Rain exploration, not a new confirmation cohort

Reuse the already studied July 2026 six-station bytes and the actual compiled
0.7.15 replay from the [preceding study](2026-10-14-hourly-sky-override.md).
This is explicitly exploratory: July is no longer an untouched holdout.
Compare exact UTC preceding-hour SRA1H with QC0 and accepted numeric gauge flags.
Keep 4,459 common cases, including 240 wet hours at least 0.1 mm, for each nominal
Previous Runs day1/day2 lead. These archives do not establish original public
availability or operational next-hour skill.

| Candidate, day1 / day2 | Missed wet hours | False alarms | Amount MAE, mm |
| --- | ---: | ---: | ---: |
| Production | 112 / 114 | 369 / 455 | 0.10261 / 0.10455 |
| Provider alone | 174 / 147 | 192 / 374 | 0.14806 / 0.13270 |
| Raw member mean | 72 / 76 | 542 / 643 | 0.12765 / 0.12729 |
| Argon temporal hurdle | 83 / 89 | 576 / 645 | 0.10997 / 0.11075 |

The bounded Argon proposal promotes an existing zero to the wet-member 25th
percentile, at least 0.1 mm, only when at least three members are wet at the target
hour and at least five distinct members are wet in its forecast t−1/t/t+1 window.
Other production amounts remain unchanged. Forecast neighbors are predictors,
not future observed outcomes. This standalone rain counterfactual does not test
the separate, unsupported dew-point/cloud-floor proposal. No candidate was
implemented: extra detection did not justify the increased false alarms and
amount error. Provider-only produced fewer false alarms, but missed more rain
and had larger amount error. These are descriptive results, not significance
tests. At the first missing neighbor boundary, absent forecasts add no wet vote.

Argon was explicitly set to Highest. Its numerical advice was checked against
the retained data. Its causal explanations, fixed publication delays and claim
that the proposed hurdle prevents false-alarm growth were not established by
the supplied evidence and are not adopted. Advice and model agreement are not
empirical proof.

## Prospective observation availability

At 20:49 UTC, fetch fresh native hourly files for Libuš, Holešov and Prostějov
from the [official CHMI current-data directory](https://opendata.chmi.cz/meteorology/climate/now/data/).
The earlier forecasts were captured at about 14:28 UTC, before their scored
target times. Preserve receipt timestamps, source bytes and SHA256 locally.

For 15:00–20:00 UTC, available cloud values at Libuš and Prostějov are QC5
(provisional); Holešov cloud values are missing, QC4. Rain values are QC5 except
one Libuš QC3 value. There are no QC0 cloud/rain outcomes in these inspected
future hours. The frozen protocol therefore supplies no accepted new validation
pairs; do not silently admit provisional data, report a new accuracy gain or
activate learned weights. Local evidence remains under
`.reference/tmp/prospective-cloud-rain-20261014/` and
`.reference/tmp/kernel-july-20261014/provider-rain-exploratory.json`.

## Release boundary

On Huawei YAL-L21 / Android 10 at 07:10 UTC on October 15, the live Březnice
refresh completed Best Match, regional sky, multi-model blend, METAR and CHMI
stages. The current model-derived sky was code 3, 92% cloud, valid at 09:00 local.
This is a runtime value, not an independent outdoor observation.

The 10:00 local forecast had ten complete members with cloud amounts from
0% (GEM) to 100% (GFS and ICON); ALADIN reported 76%, AIFS 88%, CMA 22%.
That 100-point spread, not an arbitrary next-hour cap, triggers the existing
low-agreement rating. All ten members reported zero rain for that interval end.
This does not prove that the future hour will be dry or justify calling the
overall weather highly certain. Do not delete disagreeing members merely to
increase the displayed rating.

The full JVM suite passed 532 tests. Thirteen targeted physical-device tests
passed, covering current sky, model consensus, parser behavior, named model
evidence, minority rain and the forward hourly interval. Preserve the original
device failure and subsequent passing receipt rather than reporting only the
successful retry. Test APKs and screen captures remain local.

This is a beta ingestion fix, not calibrated high confidence or a solution to
worldwide precipitation. Keep the pending production review intact. Publish a
GitHub prerelease and prepare a Google Play testing release without restarting
production review. No schedule or automatic production promotion is created.
