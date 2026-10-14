# Rain intensity, phase agreement and radar recovery

## Forecast defects

The app grouped WMO weather codes into broad conditions before producing labels.
It displayed code 51 (light drizzle), 55 (dense drizzle), 61 (light rain) and 65
(heavy rain) without their available intensity information. The shared label
mapper now preserves those distinctions, including freezing precipitation and
showers. All five app languages and the persisted widget condition use it.
Old widget caches without a raw code retain their generic description.

Drizzle is not inferred from a small hourly accumulation. Weather-code type and
intensity come from the source classification, not an invented mm/h threshold.
See the [Open-Meteo code table](https://open-meteo.com/en/docs#weathervariables)
and [WMO observation guidance](https://www.weather.gov/media/epz/mesonet/CWOP-WMO8.pdf).

The multi-model categorical selector also discarded supported intensities. An
exact strict majority now retains its code. With a strict family majority but
different intensities, the output is the strongest intensity supported at or
above that level by a strict majority of all available codes. For example,
`[63, 63, 65, 65]` produces moderate rain, not light rain. Mixed rain/showers are
mapped to corresponding rain intensities before that calculation. Existing
hazard guards and mixed-liquid fallback remain in place.

Condition agreement previously treated ordinary and freezing rain as the same
category. Their counts are now separate. Cached evidence with raw model rows is
recounted too; legacy evidence without rows retains its stored coarse count until
refresh. This can lower an overstated confidence rating. No confidence threshold,
probability or learned weight was raised.

## Frozen kernel replay

The actual compiled JVM kernel processed 4,320 September and 4,464 August two-hour
windows, each in three existing regional/global ablation scenarios: 26,352 kernel
calls. Inputs and baseline outputs are the retained, previously inspected nominal-
lead reconstruction from the [earlier diagnosis](2026-10-13-cloud-rain-diagnosis.md).

Compared with 0.7.9, the production-regional scenario changed 12 first-hour weather
codes: seven light-to-moderate drizzle and five light-to-moderate rain. The global-
only scenario changed 22 codes. The no-regional-override scenario matched the
production-regional change counts. Cloud percentages, precipitation amounts and
selected sky-source identifiers were unchanged in every compared window.

The old outputs contain only first-hour comparison fields. All emitted agreement
rows in this cohort were missing, so this replay does not exercise the new phase-
agreement calculation. Separate deterministic regressions cover that path.

This is not a new holdout, an as-issued archive, independent confirmation of the
reported Březnice sky, or evidence of worldwide superiority. The existing cloud/
rain calibration NO-GO remains. More model votes do not make a calibrated rain
probability, and a nearby airport is not automatically local ground truth.

Retained local evidence, SHA-256:

| Artifact | SHA-256 |
| --- | --- |
| Replay report | `9dc0ca7ee673456d87de57ffafe92f3245fd93b6f209a2b27f5d0ed10008747a` |
| September kernel output | `c76411eb8b4a3f3207483674ebedd6c89f0b75a6f770e5f807e3e06d65bec1fb` |
| August kernel output | `cd44fa8b3596dbe3c1e34b8b07ef371162de043a899fa32531a1cc4fc1eb985c` |

## Radar failure

On Huawei Android 10, a real DWD forecast raster failed with MapLibre's
`The source image could not be decoded.` error. Other tiles had loaded. The app
correctly retained the earlier frame, but subsequent test polling looked like a
timeout. This confirms the image-decoding failure stage, not its upstream cause:
the bad response body was not captured. Later successful runs do not erase that
failure.

Pending forecast decode failures now get one replacement source with HTTP cache
reload. A second error, other HTTP failures, observation errors and stalled loads
remain explicit failures. There is no blind commit on map idle: MapLibre can mark
an errored source loaded without having a complete valid raster. A successful tile
event restarts the inactivity watchdog; arbitrary metadata events do not.

Normal caching remains enabled outside the pending retry. Retained weather sources
remain bounded at four. Failed replacements preserve the last loaded raster and
its actual timestamp. Device verification includes an intentionally invalid PNG
followed by real network tiles; injected fault recovery is distinct from proving
that DWD will never return a bad response again.

## Independent checks

GPT-6 Astra at xhigh reviewed the forecast path and replayed the compiled kernel.
Opus 5.5 at high identified an unsupported light-intensity fallback; the correction
now requires majority support for the emitted intensity. Its radar review also
tightened cache reload to the requested frame and required the device test to
prove that the new retry source, not the retained old source, was committed.
Gemini 3.8 Flash at high reviewed the confidence methodology. Its proposed
quarter-hour/hour alignment change was not adopted because it would conflate
different valid times. No helper recommendation was treated as measured accuracy.

The stricter device assertion initially failed. The test was reading the previous
asynchronous WebView snapshot and starting fault injection before an already
queued slider seek had finished. State reads now await their own callback, and
pending work includes the deferred seek. With that test race removed, a focused
Huawei run recovered from the single injected PNG error, loaded European and
global forecasts through at least +48 hours, and rendered/wrapped/paused both
forecast modes and observations. Sydney, Nairobi and Lima first/last frames also
loaded. The run took 50.164 seconds; its largest sampled transition gap was
24.6 ms, not a universal frame-rate guarantee.

The subsequent complete Huawei run finished in 66.127 seconds: 70 tests executed
successfully, with four existing opt-in capture/background probes assumption-
skipped (the runner reports 74 total). It repeated the injected decode recovery,
worldwide radar seek/play/pause checks, hourly layouts, archive-sharing intents,
notification recovery and widget resizing. Precise widget labels fit without
ellipsis or vertical clipping in all five supported languages. The 523 host tests,
52 JavaScript tests, both Android lint variants and signed APK/AAB builds passed.
Actual AI recipient attachment handling and worldwide meteorological superiority
were not established by these app tests.
