# Reliability and device checks

## Scope

Version 0.7.6 corrects forecast-time comparisons, observation parsing, scalar station
wind speed, radar refresh ownership and widget input/display handling. These fixes do
not establish a worldwide cloud or rainfall accuracy gain. The September cloud and
rain validation decisions remain unchanged; no new learned weights are enabled.

## Confirmed regressions

- A newer quarter-hour current condition could be compared with the category of an
  earlier hourly forecast, adding a condition conflict between different valid times.
  Compare categories only at the same valid time and show both times in the model detail.
- A ten-minute station rainfall total could invent rain at the current instant, including
  overriding a newer dry METAR. Keep measured totals and timestamps without converting
  them into instantaneous weather phenomena.
- A negative wind value from an otherwise unusable model could hide the agreement
  metadata of valid sources, although the calculated wind speed was already filtered.
  Apply the same boundary validation to the metadata inputs.
- Opposing nearby station winds could cancel the displayed scalar speed into calm.
  Average scalar speeds over the same paired observations and distance weights. The
  existing circular direction calculation remains; direction is not established when
  vectors cancel.
- An out-of-range METAR timestamp could throw outside the record's validation handler
  and abort the whole forecast refresh. Reject that record only, preserving other
  stations and an earlier valid report.
- Regional radar metadata waited for an unrelated global request. Inactive observation
  expiry could stop valid forecast playback; a replacement run could remove a usable
  raster before its successor loaded. Keep request ownership, expiry and source limits
  explicit.
- Leading whitespace could truncate a pasted widget ARGB colour. Trim before applying
  the existing length bound. Device time/timezone changes now rerender cached widget
  data without another weather request.
- A delayed morning forecast preparation could replace today's delivery alarm with
  tomorrow's. Preparation now refreshes data without rescheduling the independent
  delivery alarm; delivery and boot/time recovery still schedule the next briefing.

Each changed logic path has a focused regression. New regressions failed before their
fixes; the wind-speed and METAR timestamp cases were independently reproduced from the
existing computation/parser, not inferred from model votes.

## Completed checks

The full local Android unit suite passed 510 tests with no failures or skipped tests.
The radar and location-picker JavaScript suites passed 47 tests. Debug/test APK builds,
debug/release lint and signed release APK/AAB builds passed. APK signing verification
confirmed the existing RSA-4096 certificate, package `com.majkeylab.weatheraladin`,
version code 48, minimum API 29 and target API 36.

Huawei Android 10 checks on the pending 0.7.6 build included:

- Seven widget rendering tests, including compact/expanded sizes, large fonts, colour
  previews and image fallback. These fixtures do not prove midnight or time-broadcast
  delivery.
- Twenty-three forecast layout tests covering current-hour changes, day swipes,
  collapsed/expanded forecasts, narrow layouts and basic/advanced hourly details.
- Two real-network radar runs: normal height and 300 dp height with font scale 2.0.
  Both rendered changing European/global forecast rasters and observed snapshots,
  verified source/time identity, playback/pause and fullscreen control bounds. Retained
  sources peaked at three, below the four-source limit. Measured crossfade frame gaps
  stayed below 21 ms in these runs; this is not a guarantee of production frame rate.

The final scalar-wind, malformed-timestamp and notification fixes passed the full
510-test host run and signed builds. The final Huawei run passed twelve notification
and model-consensus tests, followed by 31 notification and forecast-layout tests.
The delayed-preparation regression first failed on the phone at the unwanted alarm
reschedule, then passed after the one-line receiver fix. Its delayed holding job
prevents the regression from starting an unrelated immediate weather refresh.

An earlier opt-in probe advanced the delivery-day marker, but retained the process
and did not verify a visible notification. It was not cold-process proof.

A subsequent 13:50–14:00 UTC Huawei window verified the natural scheduled path.
Through the app's actual Notifications UI, background delivery was enabled and the
briefing set to 15:57 local time. After Home and a scoped `am kill` of the debug app,
`pidof` was empty. AlarmManager retained the exact 15:57 delivery alarm. After the
alarm, a new process and an active notification record (ID 7001, channel
`daily_weather_briefing`) appeared without opening the app. The notification shade
visibly displayed Březnice's briefing and clothing/umbrella advice. This verifies
delivery, not the forecast's meteorological accuracy or delivery under every OEM
power-saving mode. Original debug preferences and disabled background consent were
restored exactly; no production settings, accounts or system services were changed.

## Archive and AI sharing

The real app fetched 1,826 daily archive rows for 12 October 2021 through 11 October
2026. It retained the missing day and per-variable coverage: precipitation and
temperature covered 1,825 days, solar radiation 1,754. These are NASA grid estimates,
not measurements at the user's field.

After explicit sharing consent, the installed-app chooser offered ChatGPT. Its actual
composer displayed the CSV attachment and the user's question with archive coverage,
units, missing-data cautions and the app-language instruction. The Send button was
not pressed. This verifies attachment/prompt handoff, not a cloud upload or an AI
answer. Gemini, Claude and Grok were not installed on this test phone, so their receiver
behaviour was not tested.

## Review limits

The initial 0.7.6 Antigravity review phase used a 100-task matrix, not 100 completed
audits. Sixteen usable Gemini/Opus responses were retained before provider quota errors.
Every proposed change was checked against callers and tests; unsupported regional-code,
empty-median and 360-degree-wind claims were rejected. Review counts and agreement
ratings are not evidence of meteorological accuracy.

See [cloud and precipitation diagnosis](2026-10-13-cloud-rain-diagnosis.md) for the
actual source disagreements, station support limits and unchanged validation gates.

## 0.7.7 follow-up

The continued Antigravity run retained 80 usable bounded responses before the Opus
and Gemini High quotas stopped further calls. Source packets were capped at 8,000
characters and explicitly marked when truncated. These are advisory reviews, not
80 full-repository audits. Full-caller checks and independent Astra reviews rejected
unsupported cache, radar, HEX-fallback and notification claims before implementation.

Confirmed fixes:

- Cloud layers accepted invalid percentages, including negative fractions truncated
  to zero. Validate the raw number before conversion; preserve unknown values and
  the remaining forecast.
- European Russian locations were assigned to northern Asia, and Iran fell inside
  an overlapping African bounding box. Correct regional metadata without changing
  the live API model list or assigning new forecast weights.
- Station comparisons did not reject noncanonical forecast units. Require the
  existing canonical unit before pairing. Normal provider parsers already converted
  both value and unit; no corrupted frozen scores were demonstrated.
- The radar mixed a changing Play/Pause accessible name with a toggle's pressed
  state. Keep the localized name constant and the pressed state explicit.
- Switching from invalid manual widget HEX to automatic colours left a hidden draft
  blocking Apply. Validate the colours actually displayed; retain other validation.
- Morning-briefing UI could show ON while background-coordinate consent was OFF and
  the scheduler was disabled. Read back the scheduler state without changing consent.
- A cache-write failure could discard a successfully downloaded archive. Treat the
  write as optional after validating the response, retaining atomic storage, limits,
  cancellation and offline fallback behaviour.

Local checks passed 514 Android unit tests, 48 JavaScript tests, 423 research tests,
Ruff and strict Pyright. Debug/test APK builds, debug/release lint and signed release
APK/AAB builds passed for version 0.7.7, code 49. Existing lint warnings remain.

The 18:30–18:40 UTC Huawei window passed 45 native tests: Android JSON parsing,
model-consensus regressions, real-network radar, eight widget tests, 23 forecast
layout tests, notification recovery and blocked-channel UI. The new widget test
first failed at Apply's disabled state, then passed after the two-line fix. Radar
rendered European/global future rasters and observed snapshots, held pause for
2,200 ms, preserved source/time identity and kept at most four weather sources.
The largest measured transition frame gap in this run was 28.5 ms; no universal
frame-rate guarantee follows from this measurement.

The actual app also reproduced Morning ON with background consent OFF. After the
fix, tapping Morning kept both UI switches OFF and the stored consent false.
Original debug briefing/alert preferences were restored and byte-compared before
temporary backup removal; the app was reopened and left at Home. All ADB access
was explicitly released at 18:38 UTC. Production data and system services were
not changed. Android 13's permission-result branch was reviewed, not exercised on
this Android 10 device.

After the unit-boundary fix, the frozen September cloud replay produced the same
SHA-256 `05440970e5b79ed857b7485d9f8dcdfb6f5f540c73a7e0c9ecf1aa7513805d92`.
Its validation gate still fails. These fixes do not establish a cloud/rain accuracy
gain or enable learned production weights.

## 0.7.8 total-cloud boundary follow-up

The total-cloud fields still converted values to integers before validation. Raw
`-0.1` and `100.1` could therefore become believable zero and 100 percent. The
mandatory current total also had no range check. Validate the raw total first:
invalid optional hourly data remains unknown, while invalid mandatory current data
raises the existing JSON error. Valid fractional display conversion is unchanged.

The new host regression failed before the fix. The complete host suite then passed
515 tests with no failures, errors or skips. Debug/test APKs, both lint variants and
signed APK/AAB builds passed. A focused independent review found no concrete blocker.

Huawei Android 10 passed six native tests on the code-50 debug build: both total/layer
cloud boundaries, model-consensus regressions and real-network radar. Forecast and
observed playback each rendered two loaded rasters, wrapped their timeline and held
pause for 2,200 ms. The largest measured transition gap was 21.9 ms in this run, not
a universal frame-rate guarantee. The earlier 0.7.7 widget/layout checks were not
rerun for this two-field parser change.

The main phone installation was still 0.5.0 before these release checks; new debug
builds were a separate package. Direct-distribution signing could not update the
Google Play installation. A Console-downloaded Play-signed APK installed in place,
but its automatic protection required a legitimate Play installation. After 0.7.7
became available, the actual Play Store's Update from Play flow restored code 49,
installer `com.android.vending` and a normal app launch with Březnice retained.
No uninstall, data wipe or protection bypass was used. All ADB was released at
19:29 UTC with Home delivered and no pending commands.

These are software and release-verification results, not a measured improvement in
cloud or rainfall prediction. Frozen validation decisions and production weights
remain unchanged.
