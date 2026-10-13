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

A separate opt-in background probe used the real cached forecast. With the activity
left behind Home, the delivery-day marker advanced and the pending briefing was
cleared. The process was still present, so this is not cold-process proof. No active
notification record or notification-shade rendering was verified; the UI dump
returned a null root. Visible delivery remains unconfirmed. The probe's preferences
and original disabled background consent were restored before releasing the phone.

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

The Antigravity review matrix contained 100 bounded tasks, not 100 proven completed
audits. Sixteen usable Gemini/Opus responses were retained before provider quota errors.
Every proposed change was checked against callers and tests; unsupported regional-code,
empty-median and 360-degree-wind claims were rejected. Review counts and agreement
ratings are not evidence of meteorological accuracy.

See [cloud and precipitation diagnosis](2026-10-13-cloud-rain-diagnosis.md) for the
actual source disagreements, station support limits and unchanged validation gates.
