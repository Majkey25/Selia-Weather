# Forecast time alignment and cloud quantization

## Corrected paths

Open-Meteo hourly precipitation amounts and probabilities describe the preceding
hour. Weather codes and cloud cover describe the timestamp itself. These fields
must not share one implicit time shift. See the
[provider's variable definitions](https://open-meteo.com/en/docs#hourly-parameter-definition).

- At 10:45, the widget must use the 11:00 probability for 10:00–11:00, while its
  labelled 10:00 temperature remains an instantaneous value. The old persistence
  path selected the 10:00 probability, covering 09:00–10:00. Exact boundary lookup
  now leaves missing or duplicate rows unknown; it does not take the next available
  hour. Midnight rollover preserves the date.
- A code-only drizzle prediction at 12:00 describes 12:00, not 11:00. Alert
  evaluation and scheduling now distinguish this instant from an accumulation's
  preceding-hour start. Mixed evidence retains the earliest eligible event.
- Provider timestamps use the response's fixed UTC offset. On 25 October 2026,
  `03:00` with offset `7200` means `01:00Z`, not `02:00Z`. Prefer that offset when
  decoding forecast rows; use the named local zone when formatting notification
  text. Legacy responses without an offset still reject ambiguous local hours.

The widget regression exercises real Android preferences and RemoteViews through
the existing persistence path. Its first valid RED run read 0% instead of 80%.
After correction, exact-hour, within-hour, reordered rows, missing probability,
missing boundary, duplicate boundary and midnight cases passed on Huawei Android 10.
The fixture's application context also preserves its isolated preference namespace.

Notification regressions failed before the correction on exact event time,
configured lead time and daylight-saving cases. These establish temporal
correctness, not improved meteorological prediction skill or guaranteed background
delivery on every Android device.

## Cloud percentage consistency

The kernel rounds cloud cover before its fallback sky classification. The parser
previously truncated it for display: a 19.5% result could use the 20% category but
show 19%. Both current and hourly total cloud cover now use nearest-integer
rounding after the unchanged finite-value and 0–100 validation. Cloud layers,
model selection, raw blended values and kernel weather-code decisions are unchanged.
Later current-observation fusion can classify the rounded integer at a boundary.

A diagnostic reused the already examined August/September inputs and actual
compiled-kernel outputs from the intensity replay. Exact station/time/nominal-lead
keys were matched to QC0 CHMI whole-sky N/8 observations. Missing, invalid and
unknown observations were not zero-filled. No forecast algorithm was transcribed
or refitted. The primary agent independently recomputed the paired errors from
the hash-verified saved outputs and observation pairs.

| Existing cohort | Pairs | Truncated MAE | Rounded MAE |
| --- | ---: | ---: | ---: |
| August 2026 | 2,656 | 20.114458 | 20.103539 |
| September 2026 | 2,614 | 20.171385 | 20.151109 |
| Combined | 5,270 | 20.142694 | 20.127135 |

Errors are percentage points. Rounding changes 442 values: 259 errors improve,
177 worsen and six changed cases tie. Holešov in August worsens by 0.0375 points.
Displayed percentages below 20% against observations of at least 87.5% decrease
from 96 to 95, but the number of clear weather codes against overcast observations
stays 95. This does not fix the reported Březnice sky miss or establish a significant
forecast accuracy gain.

For example, Prostějov on 21 September at 14:00 UTC, nominal day1, has a raw 19.5%
result and code 1 against QC0 37.5%. The parser now shows 20%, not 19%.
The local report SHA-256 is
`9bdc7da621397a2a059b400cf8ef5382ad27d8b14dd0e9f914afe50551ebbd9f`.
Raw source identities, hashes, paired cases and both improvement and worsening
examples remain in the local research evidence. These examined months are not a
fresh holdout. The study does not evaluate cloud-layer rounding, live current
fusion, verified as-issued leads, other seasons or worldwide accuracy.

## Independent advice and limits

Astra and Opus 5.5 identified and reviewed the temporal and rounding defects.
Gemini 3.8 Flash and Argon were consulted through the authenticated Gemini UI at
its highest advertised thinking level. Their output was treated as advice, not
observations. Argon's proposed fixed weights, asymmetric thresholds, weather-code
time reinterpretation and broader station nudging were not adopted: they lack
validated support here, and some contradict the existing provider contract.

No learned cloud/rain weights or confidence thresholds change. The earlier mean
and regional-override ablations still have their frozen NO-GO decisions. A future
candidate must use genuine independent observations, declared training coverage,
unexamined confirmation data and the existing safeguards. Neither model agreement
nor a language-model recommendation supplies calibrated forecast accuracy.
