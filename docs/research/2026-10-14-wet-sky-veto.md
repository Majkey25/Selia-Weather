# Preserve the provider wet-sky veto

## Confirmed defect

The hourly regional sky selector requires dry, known precipitation at the displayed
time and the following hourly boundary. It checks the provider, regional model and
multi-model median independently. Previously, `blendPrecipitation` mutated the
provider array before this check. A provider value of 0.3 mm could become a positive
median of 0.05 mm, unintentionally removing the provider's wet veto. Missing provider
data at the displayed time could similarly become known. The following boundary
still used the original value, making equivalent inputs depend on array position.

Select the regional sky before mutating precipitation. The existing precipitation
calculation still runs before deriving weather conditions and daily totals. No
threshold, weight, amount estimator, current-condition override or observation
selection changed.

The host regression failed before the change and passed afterwards. Host and native
fixtures cover either boundary, exactly 0.1 mm, 0.3 mm and missing provider values,
with cloudy comparison models and a clear regional model. They verify that the
regional override is withheld while the calculated 0.05 mm amount is preserved.

## Frozen actual-kernel replay

The compiled production JVM kernel processed the same 4,320 September and 4,464
August two-hour windows used in the [previous diagnosis](2026-10-13-cloud-rain-diagnosis.md).
No new network data or refitted weights were used. Rainfall amounts were unchanged
in every window. Available nonregional ablation outputs were also unchanged.

September had no changed outputs. August had two changed sky selections, both at
nominal 48-hour lead:

| Station / UTC valid time | Before | After | Effect against QC0 observation |
| --- | --- | --- | --- |
| Praha Libuš, 20 August 16:00 | 97%, cloudy | 97.5%, cloudy | Absolute cloud error increased by 0.5 percentage points. |
| Prostějov, 1 August 22:00 | 1%, clear | 58.5%, partly cloudy | Removed a clear classification against observed overcast; still underestimated cloud cover. |

On the 1,328 matched August 48-hour cases, cloud MAE changed from 21.162651 to
21.119729 percentage points. False-clear cases (forecast cloud below 20%, observed
cloud at least 87.5%) changed from 34 to 33. August 24-hour and both September
cohorts were unchanged. One changed case improved and one slightly worsened.

This is a reproducible software-defect correction, not a fresh holdout, evidence of
worldwide superiority or a demonstrated solution to the Březnice current-condition
miss. The earlier calibration and sky-policy NO-GO decisions remain in effect.
Current observation fusion, model issuance and historical public availability were
not validated by this nominal-lead replay.

## Local evidence hashes

SHA-256, retained outside Git with the raw diagnostic inputs:

| Period / artifact | SHA-256 |
| --- | --- |
| September kernel input | `49dee51e4294b715f1ee4526db14ce67030f838737f954d9f379c05b9c0a0586` |
| September corrected output | `5ee14bee4a38667a4a2c2d9d5712882b370af6724d6fb92479f255419d88e588` |
| September comparison report | `2f9c3020eda0c4ceeca1b77b795bcc9a1d4141b2350670b96b1fa6a2c52fbbf9` |
| August kernel input | `dbc64f9c677dae9fb152366a3b8b0e4d1d372f0fc3d6e96a8ce29c6dffa06123` |
| August corrected output | `82cb1fb01482a5873049b6166974c3c6e602eb7a78faf10f3640b4f908154566` |
| August comparison report | `d3bd09d940793905d2769c42a3519725079fc0a6b31d8fa7a8edace297ad7824` |

## Release verification

The complete local suite passed 516 host tests, 48 JavaScript tests and 423 research
tests. Ruff and strict Pyright passed. Debug/test APKs, both lint variants and signed
release APK/AAB builds passed. Existing lint warnings remain. Independent read-only
review found no concrete defect in the mutation-order correction.

Huawei Android 10, debug code 51, passed a complete 58-test native run on 14 October
at 09:22 UTC. It covered the new wet/missing-sky veto, Android JSON boundaries,
widgets and resizing/HEX colours, hourly layouts and highlights, model explanations,
navigation, settings, notification recovery and archive-sharing intents.

The initial native run passed 57/58: seeking the last Tokyo/global forecast frame
timed out. Two focused runs reproduced the failure. The app retained its loaded
earlier raster and restored its timestamp, reporting the forecast unavailable. A
direct official WMS request returned a valid rain PNG for the requested target and
reference time. A subsequent focused run and the complete 58-test run both passed
without changing production radar logic. The test now includes a bounded MapLibre
error buffer and the last-frame state on failure; its timeouts and assertions were
not weakened. The underlying intermittent tile-load/readiness cause is unresolved.

The successful complete run rendered two loaded raster frames, wrapped and held
pause for 2,200 ms in European forecast, global forecast and observed modes. The
largest measured transition gap was 24.5 ms, not a universal frame-rate guarantee.
Manual Ask AI UI also loaded a five-year archive: 1,825 available daily records out
of 1,826 dates. Native sharing tests checked the CSV, app-language question and
read-only URI grant without launching an external AI recipient. Attachment handling
inside ChatGPT, Gemini, Claude or Grok was not verified in this release window.

All ADB access ended at 09:23 UTC with Home delivered and no instrumentation pending.
No emulator, uninstall, data wipe or production-package sideload was used.
