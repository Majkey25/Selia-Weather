# A prospective Zlín forecast replay

The frozen four-model forecast did not establish that a blend beats every member.
On 48 provisionally observed temperature hours, ICON had MAE 0.815°C, arithmetic mean
0.858°C and median 0.931°C. Only two temperature hours had checked quality. All 48
rainfall observations were provisional zeros, so this sample cannot test rain detection.

## What was compared

The capture completed on 5 September 2026 at 16:18:15.638147 UTC, before every eligible
forecast interval. The requested point was the ČHMÚ station Zlín, WIGOS
`0-203-0-11775`, 49.236667°N, 17.643333°E, elevation 283 m. The raw forecast and
manifest are unchanged. This is not a reconstruction from a later model run.

On 8 September, eight bounded requests froze station metadata and the 6–7 September
10-minute and hourly observation files. Exact UTC hours and the same station coordinates
were required. Ten-minute rainfall never substituted for an hourly total. Temperature
required a documented 2 m sensor. Wind, dew point and sea-level pressure lacked matching
observations at this station, and the missing values stayed unscored.

The source's [metadata definitions](https://opendata.chmi.cz/meteorology/climate/Klimatologicka_data_popis.pdf)
identify separate quality and measurement flags. Frozen metadata 4 defines quality 0
as good and 5 as unknown; 1–4 are suspect, poor, estimated or missing. Metadata 3 identifies
trace rain with `T`, variable direction with `V`, and alternative rain-gauge sources.

The evaluator previously retained these fields without checking them. The correction
uses checked quality for calibration and an explicit provisional-only mode for descriptive
replay. Censored trace amounts cannot silently become numeric zero truth. The Android
current-conditions parser may still use provisional quality 5 for current display, but
rejects explicitly bad measurements. Current display is not a training-label source.

## Matched results

MAE is in °C. Every member and blend uses the same 48 temperature hours in the provisional
column and the same two checked hours in the strict column.

| Forecast | Provisional, 48 hours | Checked, 2 hours |
|---|---:|---:|
| CHMI ALADIN seamless | 1.071 | 1.500 |
| ICON seamless | 0.815 | 1.300 |
| ECMWF IFS 0.25° | 1.563 | 1.550 |
| GFS seamless | 1.217 | 1.250 |
| Arithmetic mean | 0.858 | 1.275 |
| Median | 0.931 | 1.300 |

Of 912 parsed station records, 12 had quality 0 and 900 had quality 5. The strict replay
paired eight model/temperature rows, corresponding to two independent hourly measurements.
It rejected 376 model/truth pairs and left 932 rows without compatible observations.
The provisional replay paired 384 model/variable rows, not 384 independent weather cases.

All four models and both blends predicted zero rainfall on the same 48 provisional hourly
gauge records. Their zero numeric error is not evidence of useful wet-weather skill.
Best Match was not included in the original capture and was not added retrospectively.

## Reproduction and limits

Run from the repository root after the frozen local cache exists:

```powershell
research/.venv/Scripts/python.exe research/scripts/replay_capture_20260905.py --output research/output/capture-replay-copy/report.json
```

The runner verifies every raw checksum and refuses to overwrite prior results. The explicit
`--download` option was used once to collect eight observation/metadata files, not new
forecasts. Source URLs, receipt times and SHA-256 values are in the local cache's
`research/data/raw/capture-replay-20260908/inputs.json`. The original report is
`research/output/capture-replay-20260908/report.json`. Raw caches are not distributed in Git;
the provider's daily files rotate, so a clean checkout may not be able to recreate collection.

This is one forecast capture, one station and two dates. It does not locate or validate the
user's September 7 drizzle report. It does not establish worldwide skill, optimal local
weights, or 14-day accuracy. No weights were fitted or exported. Older unchecked ČHMÚ
backtests require a new quality-filtered replay before they can support deployment.
