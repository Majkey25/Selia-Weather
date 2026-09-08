# GHCNh temperature validation, 6 September 2026

The frozen East Asia candidate improved heldout temperature MAE from 1.404444°C to
1.054757°C against the training-selected Best Match baseline. It still failed the existing
`unstable_folds` gate. The result remains rejected and no weights were exported or activated.

## Why the input source and training dates changed

The initial plan froze 1 September through 29 November 2025 for training, and 30 November
through 29 December for holdout. The old NOAA ISD pipeline could not cover that period.
NCEI ended ISD updates on 29 August 2025 and replaced it with GHCNh.
[NCEI retirement notice](https://www.ncei.noaa.gov/operating-system-upgrade-outage).

The GHCNh successor check found 89 usable training dates at each of Tokyo, Seoul, and Shanghai;
1 September was missing at all three. The original plan was preserved as blocked. An
availability-only amendment extended training back to 28 August, leaving holdout dates unchanged.
The amendment was frozen at `2026-09-06T10:06:06.237993+00:00`, before any fit or holdout score.
The model families, fitting defaults, acceptance rules, and 1,000 bootstrap resamples stayed fixed.
The amendment scoped the first evaluation to three uniquely matched East Asia stations based
on metadata and exact-time data availability, before inspecting forecast errors.

The amended 94-calendar-day training period contains 91 independent observation dates per
station, 273 training cases total. Holdout contains 30 dates per station, 90 cases total.
No minimum sample gate was reduced. Subsequent reruns reproduce this fixed study; they are
not new untouched validation experiments.

## Station identity and observation contract

The successor crosswalk requires a matching ICAO identifier and coordinates within 1 km.
That tolerance covers catalog rounding, not arbitrary nearby-station substitution. GHCNh IDs
remain distinct from the old ISD IDs. Observation coordinates and elevation come from native
PSV rows; they are not replaced with old station metadata.
The sample consists of three airport sites: Tokyo Haneda, Seoul Incheon, and Shanghai Pudong.
It does not represent every location in those cities or throughout East Asia.

| Location | ICAO | Old ISD ID | GHCNh ID | Native latitude, longitude | Elevation | Crosswalk distance |
|---|---|---|---|---|---:|---:|
| Tokyo | RJTT | `47671099999` | `JAI0000RJTT` | 35.5523, 139.7797 | 10.7 m | 43.0 m |
| Seoul | RKSI | `47113199999` | `KSI0000RKSI` | 37.4691, 126.4505 | 7.0 m | 45.5 m |
| Shanghai | ZSPD | `58321199999` | `CHI0000ZSPD` | 31.1434, 121.8052 | 4.0 m | 48.4 m |

GHCNh v1.1.0 PSV temperature is already decimal degrees Celsius; the parser does not divide
it by ten. The dataset defines temperature at approximately 2 m. Individual measured sensor
height is absent, so `measurement_height_m` remains `None`. This is a nominal variable
definition, not evidence of an exact sensor height.
[GHCNh documentation](https://www.ncei.noaa.gov/oa/global-historical-climatology-network/hourly/doc/ghcnh_DOCUMENTATION.pdf).

The parser accepts only reviewed surface-observation source codes. Quality interpretation is
source-specific: source 223 code `4` means calculated and is excluded; legacy ISD-source code
`4` means a passed gross-limits check and is accepted under its documented policy. Unknown
sources, including unknown sources with blank quality flags, are excluded. Original source codes
and source-station IDs remain in checksum-linked raw PSV files; the `Observation` type does not
export those two attributes as separate fields.
[NCEI source-code table](https://www.ncei.noaa.gov/oa/global-historical-climatology-network/hourly/doc/ghcnh-source-list.pdf).

The sampling grain is one chosen exact UTC synoptic observation per station per day. The
existing reducer prefers 12 UTC, then 00, 06, and 18 when temperature availability is tied.
Native reports at other minutes retain their timestamps and are excluded from this hourly
comparison. No observation timestamps were rounded or interpolated. Frankfurt had no usable
exact-hour holdout dates under this contract and was not included in the East Asia result.

## Heldout results

These are errors on the same 90 station/date cases. The three input families are
`icon_seamless`, `ecmwf_ifs025`, and `gfs_seamless`. Best Match was selected as fallback
from training data, before holdout scoring.

| Forecast | Holdout MAE, °C |
|---|---:|
| ICON Seamless | 1.394444 |
| ECMWF IFS 0.25° | 1.538889 |
| GFS Seamless | 1.370000 |
| Best Match, training-selected fallback | 1.404444 |
| Learned candidate blend | 1.054757 |

The date-block bootstrap interval for the paired MAE improvement over Best Match is
0.172289 to 0.533534°C, with estimate 0.349688°C, 95% confidence, 1,000 resamples,
and seed `20260825`.

| Station, 30 cases each | ICON | ECMWF | GFS | Best Match | Candidate blend |
|---|---:|---:|---:|---:|---:|
| Tokyo | 1.913333 | 2.620000 | 1.066667 | 1.476667 | 1.366884 |
| Seoul | 1.230000 | 1.206667 | 2.233333 | 2.023333 | 1.065083 |
| Shanghai | 1.040000 | 0.790000 | 0.810000 | 0.713333 | 0.732303 |

The blend was worse than GFS at Tokyo and slightly worse than Best Match at Shanghai.
The report's `maximum_region_degradation` refers to the pooled `EAST_ASIA` group, not
three separate station stability guarantees. These station results do not establish superiority
throughout East Asia or at other forecast hours. The candidate is not qualified for rural,
inland, or other untested locations and times.

## Why the candidate was rejected

The existing code names its stability values `fold_improvements`, but computes them by
calendar month on **training data using the same all-training fit**. These are in-sample monthly
stability checks, not rolling cross-validation, independent folds, or holdout subperiods.

| Training month | Cases | MAE improvement over training-selected fallback, °C |
|---|---:|---:|
| August | 6 | -0.638302 |
| September | 87 | 0.363423 |
| October | 93 | 0.166484 |
| November | 87 | 0.270068 |

The negative August result triggers `unstable_folds`. Its small group size is reported explicitly;
the gate was not waived, retuned, or removed after seeing the result. The fitted candidate weights
were ICON `0.0697267991`, ECMWF `0.4738520192`, and GFS `0.4564211817`. They remain
research evidence only.

## Reproduction and immutable evidence

Run the bounded reproduction from the repository root:

```powershell
uv run --project research python research/scripts/ghcnh_temperature_validation_2025.py --output-dir research/output/ghcnh-temperature-reproduction
```

The script is cache-only and rejects an existing output directory. It requires the frozen
station metadata and raw forecast/observation caches, which are not distributed in Git.
Expected input hashes are pinned in the script. Missing inputs fail without a network request.
Fresh provider responses can differ in generation metadata and do not replace a frozen byte
vintage. A clean checkout alone cannot bootstrap this archive. There is no scheduler or download
option in the reproducer.

The script reuses `parse_ghcnh_temperatures`, `select_daily_synoptic_observations`,
`parse_previous_run_values`, `build_backtest_dataset`, and `run_locked_backtest`. It reproduces
scores and the rejection; new lock timestamps and expanded crosswalk metadata mean that
reproduction reports are not byte-identical to the original artifacts.

Original local evidence is under
`research/output/fresh-temperature-20250901-20251229-20260906/`:

| Artifact | SHA-256 |
|---|---|
| `frozen-plan.json` | `6e4dac2ad1a86f8005c20133182d8af4ee458d1d7ca2de2ae9c163312842bb2b` |
| `amendment-east-asia.json` | `f16fbcb2cda8d15d6cce13aa667a6d0bdf34f0496e09b1d91612ebb14e045090` |
| `east-asia-amended/dataset-manifest.json` | `6702c570e19b73b027dfd3e48a056727408508697307e1de596d33e4f0351b40` |
| `east-asia-amended/holdout-lock.json` | `f1699c5e7f6f93fd8b7f8e2a09c8afefafa5c902426e0dd42f522aed0d1fd78c` |
| `east-asia-amended/report.json` | `ba36b785ed965b3ae9fdfbbb187e5826cc99c40762254229f090dd72a44a96da` |

The forecast contract is Open-Meteo `previous_day1`, a nominal 24-hour offset. The archive
parser's synthetic `valid_time - 24h` value is not a verified original model initialization time.
Neither these weights nor their nominal lead may be applied to an arbitrary live response or
relabeled as an exact-issued-run calibration. No runtime activation or weight export occurred.
