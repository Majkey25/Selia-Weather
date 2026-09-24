# Training-selected local shrinkage candidate

A research-only selector now chooses how much to retain local model weights versus
pooled regional weights using forward validation confined to the training period.
The existing constrained fitter and MAE scorer are reused. No production selector,
calibration gate, source priority or rain-probability calculation changed.

The single tested candidate is
`weights = (1 - local_fraction) * regional_weights + local_fraction * local_weights`.
The fixed candidate fractions are 0, 0.5 and 1. Selection minimizes each station's
forward-validation MAE; ties favor the smaller local fraction. Each call requires
one variable, lead and region, complete matched predictors and one case per site/date.
It cannot pool scores across forecast leads. No provider receives special trust.

## Frozen-data exploratory audit

The existing January study supplies 123 training dates per airport, August 28 through
December 31, 2025. Two forward folds evaluate December 1–15 and December 16–31.
Each regional and station fit uses only earlier dates, with at least 90 distinct
observed dates in its prefix. Each station has 31 validation dates. The regional
fit uses all three stations' earlier cases. The final selected weights are refitted
on all training cases and written before January metrics are computed.

This analysis reuses January outcomes already examined on September 6. The preceding
November/December study is also already explored. Parameter selection in this script
uses December only, but the study design follows prior inspection; these results are
**not new untouched holdout evidence**. No further candidate or parameter grid was tried.

These are two different frozen populations. Direct inspection of selected native
observation timestamps confirmed the following, identically at all three stations:

| Study | Training cases, pooled | Actual evaluation timestamps, UTC | Evaluation cases |
|---|---:|---|---:|
| Earlier GHCNh study | 273, through November 29, 2025 | November 30, 2025 12:00 through December 29, 2025 12:00 | 90 |
| January corpus used here | 369, through December 31, 2025 | January 1, 2026 12:00 through January 30, 2026 12:00 | 90 |

The January training records actually run from August 28, 2025 12:00 through
December 31, 2025 12:00, with 123 dates per station. Its pooled baseline is refitted
on those additional training dates and scored on different January weather. That is
why its MAE is 1.105235 C, whereas the earlier study's pooled candidate scored
1.054757 C. Those two numbers must not be interpreted as a paired accuracy change.
The shrinkage candidate's 0.912403 C is compared only against January predictions
and January truth on matching station/date cases.

Compared with the earlier corpus, this loader additionally uses 2026 GHCNh files
and December 30–January 30 forecast payloads. Their pinned SHA-256 values are:

| Additional raw source | SHA-256 |
|---|---|
| Tokyo GHCNh 2026 | `d9becf3d90195b42a8ba9969c220866fea1f7015d772816eae49551901099c96` |
| Seoul GHCNh 2026 | `9d51ddf8afa3b3e9bbefd6a071631a102df56347351fbff25728fea9f7b58cb6` |
| Shanghai GHCNh 2026 | `dc3caaabd155da00d6798aea2e2f3a64754e697c7a87593247591dd7909936c0` |
| ICON December 30–January 30 | `2ea6cb1c9a8403fd1afc6691fedf21b9e5990f2553cffacb3eac0b15df223886` |
| IFS December 30–January 30 | `a80e9cc73af25d6e8aec4256a8a83014cec2ff37e8d559559a0512b9af78b7b5` |
| GFS December 30–January 30 | `ad0aa7d110c55198d6350d2a34579efa2dcb0037ce1eb911581b92c0aa64ddd3` |
| Best Match December 30–January 30 | `89fb2f6908140ef929d0b3e30dd2fb635e59628bce85062bfca579d79aea6b15` |

| Site | Regional validation MAE | Half-local validation MAE | Local validation MAE | Selected local fraction |
|---|---:|---:|---:|---:|
| Tokyo Haneda | 1.359324 | 1.128975 | 1.000676 | 1 |
| Seoul Incheon | 1.107517 | 0.968240 | 0.965384 | 1 |
| Shanghai Pudong | 0.676858 | 0.683892 | 0.721966 | 0 |

All metrics below are temperature MAE in degrees Celsius on the same 90 already
explored January cases, 30 per airport. They use independent quality-filtered
NOAA GHCNh observations; model outputs are not treated as truth.

| Site | Regional fit | Independent local fit | Selected candidate | Training-selected single fallback |
|---|---:|---:|---:|---:|
| Tokyo | 1.120903 | 0.904383 | 0.904383 | 0.893333, GFS |
| Seoul | 1.315453 | 0.953477 | 0.953477 | 1.193333, IFS |
| Shanghai | 0.879350 | 0.932513 | 0.879350 | 0.956667, IFS |
| Pooled | 1.105235 | 0.930124 | 0.912403 | 1.014444 |

The candidate improves this retrospective regional/local comparison, but Tokyo's
training-selected GFS still beats it. Seoul's validation advantage over half-local
is only 0.002855 C, so its fraction is not evidence of a stable exact optimum.
No new significance or acceptance claim is made from this reused evaluation set.

## Reproduction

```powershell
research/.venv/Scripts/python.exe research/scripts/local_shrinkage_validation_2026.py --output-dir research/output/local-shrinkage-candidate-20260924-final
```

The runner is network-free and reuses the checksum-pinned January input loader.
It refuses an existing output directory. `protocol.json` freezes the candidate grid,
date windows and code/source hashes before fitting; `training-selection.json` records
selection before scoring; `comparison.json` marks `new_untouched_holdout=false`,
`production_eligible=false`, and `exported=false`. For another reproduction, use a
new output directory. Original frozen inputs are required and not distributed in Git.

Regression checks verify station-specific selection, reject mixed leads, incomplete
predictors and insufficient training dates, and verify that changing labels after
the forward-validation windows cannot change their scores or chosen fractions.
Verification: 335 research tests passed; Ruff passed; strict Pyright over `src`,
`tests` and the candidate runner reported zero errors and warnings.

Final reproduction artifact SHA-256:

| Artifact | SHA-256 |
|---|---|
| protocol.json | `709baeda40e7494c923b3d941860e2f1dfd4a7812af34b110fd30bab3fe1a923` |
| training-selection.json | `09cf2c58921ee322a30397290eeb96577a55020b5e4e44dc4309c286eee3896e` |
| comparison.json | `712ec085cb3e30d5f6513f65db0c4e816982a5e895f66500bbf32a76c9521248` |

## Why this remains a candidate

The forecast source is still `previous_day1`, a nominal 24-hour offset with unverified
original model initialization. All January cases occur at 12 UTC at three coastal
East Asia airports. Other leads, seasons, inland/rural sites, unseen locations and
precipitation are unvalidated. The two locally available prospective Czech captures
also have null model initialization and `calibration_eligible=false`; the checked
observation sample is insufficient for a 90-day training/30-day untouched evaluation.

Runtime schema 2 addresses regions, variables, lead ranges and months, not station
applicability. Station weights must not be relabeled as whole-region weights. A
production change therefore needs both exact source/lead provenance and a declared,
validated geographical applicability rule. Existing failed gates remain in force.

Before deployment, freeze this one candidate, gather at least 90 observed training
dates plus 30 later untouched dates at each intended location/lead, retain exact
issued-run IDs and independent quality-filtered observations, and compare on matched
cases against individual models and the training-selected fallback. Report all sites,
including degradations; passing at these three airports cannot qualify worldwide use.
This is a collection requirement, not a scheduled downloader or an active automation.

See the [original station comparison](2026-09-06-station-temperature-validation.md)
for source hashes, observation quality, sampling and the original rejection reasons.
