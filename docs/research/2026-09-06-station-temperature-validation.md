# Regional versus station temperature fits

Independent station fits reduced January temperature error versus the pooled East Asia fit
at Tokyo Haneda and Seoul Incheon, but increased it at Shanghai Pudong. None passed all
existing acceptance gates. No weights were exported or activated.

## Frozen experiment

The protocol was frozen at `2026-09-06T10:50:05Z`, before downloading 2026 observations
or scoring January forecasts. Training covered 28 August through 31 December 2025.
The untouched holdout covered 1 through 30 January 2026. The earlier November/December
holdout became training data in this new experiment; it was not presented as fresh evidence.

The cohort remained three airport sites, not whole cities or the East Asia region:

| Site | ICAO | Native GHCNh station | Latitude, longitude | Elevation |
|---|---|---|---|---:|
| Tokyo Haneda | RJTT | `JAI0000RJTT` | 35.5523, 139.7797 | 10.7 m |
| Seoul Incheon | RKSI | `KSI0000RKSI` | 37.4691, 126.4505 | 7.0 m |
| Shanghai Pudong | ZSPD | `CHI0000ZSPD` | 31.1434, 121.8052 | 4.0 m |

Each site supplied 123 training dates and all 30 holdout dates. The grain was one native,
exact-hour UTC synoptic temperature observation per station per day, chosen by the existing
daily reducer. No timestamps were rounded and no missing observations were replaced with zero.
The pooled fit had 369 training cases; each station fit had 123. Every comparison used the
same 30 January cases at its station.
All 90 selected January observations were at 12 UTC. This experiment does not validate
the other hours of the day.

Truth came from NOAA GHCNh, with the existing source-specific quality policy and native
Celsius units. Individual measured sensor height remains unknown, rather than fabricated
as exactly 2 m. The adapter and station crosswalk are described in the
[preceding GHCNh validation](2026-09-06-ghcnh-temperature-validation.md).
[NOAA GHCNh documentation](https://www.ncei.noaa.gov/oa/global-historical-climatology-network/hourly/doc/ghcnh_DOCUMENTATION.pdf).

All fits used `icon_seamless`, `ecmwf_ifs025`, and `gfs_seamless`, with Best Match also
eligible for training-only fallback selection. The existing constrained fitter, regularization,
fallback selection, and acceptance gates were unchanged. Every fit and fallback was frozen
before any January score was computed. No station was dropped after inspecting its result.

## Matched holdout errors

MAE is in degrees Celsius; lower is better. Each row contains 30 station/date cases.

| Site | Pooled regional fit | Station fit | Training-selected fallback | Fallback MAE |
|---|---:|---:|---|---:|
| Tokyo Haneda | 1.120903 | 0.904383 | GFS | 0.893333 |
| Seoul Incheon | 1.315453 | 0.953477 | ECMWF IFS | 1.193333 |
| Shanghai Pudong | 0.879350 | 0.932513 | ECMWF IFS | 0.956667 |

The station fit was better than the pooled fit at Tokyo, but slightly worse than the GFS
fallback selected from training. Shanghai's station fit was worse than the pooled fit.
Choosing only the successful stations after viewing January would require new untouched
validation; these results were not used to create a post-hoc deployment rule.

The paired station-versus-regional MAE improvements use 1,000 UTC-date bootstrap resamples,
seed `20260825`. Positive values favor the station fit.

| Site | MAE improvement | 95% bootstrap interval |
|---|---:|---|
| Tokyo Haneda | 0.216520°C | 0.068160 to 0.361165°C |
| Seoul Incheon | 0.361976°C | 0.133625 to 0.582365°C |
| Shanghai Pudong | -0.053163°C | -0.119944 to 0.017972°C |

These are pointwise intervals, not multiple-comparison-adjusted guarantees. Resampling whole
UTC dates groups sites measured on the same date, but does not model serial dependence across
consecutive dates. Thirty dates do not establish annual or all-weather reliability.

For reference, individual model errors on exactly the same cases were:

| Site | ICON | ECMWF IFS | GFS | Best Match |
|---|---:|---:|---:|---:|
| Tokyo Haneda | 2.040000 | 2.073333 | 0.893333 | 1.390000 |
| Seoul Incheon | 1.370000 | 1.193333 | 2.570000 | 1.950000 |
| Shanghai Pudong | 1.200000 | 0.956667 | 0.893333 | 0.890000 |

Across the 90 cases, the pooled fit's MAE was 1.105235°C versus its training-selected
Best Match fallback at 1.410000°C. A favorable pooled average was not treated as proof
of station-specific superiority.

## Acceptance and limitations

| Fit | Existing gate result |
|---|---|
| Pooled East Asia | Rejected, `unstable_folds` |
| Tokyo Haneda | Rejected, `no_significant_improvement` over training-selected GFS |
| Seoul Incheon | Rejected, `unstable_folds` |
| Shanghai Pudong | Rejected, `no_significant_improvement` and `unstable_folds` |

The existing `fold_improvements` are in-sample calendar-month checks using the same fit
trained on the full training period. They are not rolling validation or independent folds.

| Fit | August | September | October | November | December |
|---|---:|---:|---:|---:|---:|
| Pooled East Asia | -0.691808 | 0.343678 | 0.159750 | 0.290659 | 0.348050 |
| Tokyo Haneda | 0.009870 | 0.222964 | 0.106335 | 0.298914 | 0.059296 |
| Seoul Incheon | -0.112102 | 0.318401 | 0.243464 | 0.231005 | 0.308209 |
| Shanghai Pudong | -0.266416 | 0.006528 | -0.037662 | 0.096447 | 0.038664 |

Per-site month counts were 2, 29, 31, 30, and 31; pooled counts were three times those values.
The small August group remains visible. The existing gate was not waived or retuned.

Fitted weights also differed by location:

| Fit | ICON | ECMWF IFS | GFS |
|---|---:|---:|---:|
| Pooled East Asia | 2.11 × 10⁻¹⁸ | 0.483050 | 0.516950 |
| Tokyo Haneda | 0.111894 | 0.177694 | 0.710413 |
| Seoul Incheon | 0.298091 | 0.584348 | 0.117561 |
| Shanghai Pudong | 0.161161 | 0.641495 | 0.197345 |

The pooled ICON weight is numerical residue, not a meaningful third contributor. The existing
positive-weight source-count check counts it; this frozen run did not change that check.
The pooled fit is effectively an IFS/GFS combination and remains rejected for instability.

The forecast source is Open-Meteo `previous_day1`, a nominal 24-hour offset. The parser's
`valid_time - 24h` timestamp is a surrogate, not verified original issuance. These weights
cannot be relabeled as exact-issued-run calibration or applied to arbitrary live forecasts.
This study covers airport temperature at sampled hours, not rural or inland locations,
precipitation, wind, radar, other horizons, or worldwide forecast accuracy.

## Reproduction and preserved evidence

From the repository root:

```powershell
uv run --project research python research/scripts/station_temperature_validation_2026.py --output-dir research/output/station-temperature-reproduction
```

The runner is cache-only. It pins the station catalog and every raw forecast/observation
checksum, rejects missing or changed inputs, and requires a new output directory.
Frozen raw caches are not distributed in Git; a clean checkout cannot bootstrap this archive.
Reruns reproduce the fixed comparison and are not new untouched holdout experiments.

The original evidence is under
`research/output/station-temperature-20260101-20260130-20260906/`:

| Artifact | SHA-256 |
|---|---|
| `frozen-plan.json` | `2952d71b97b5a921c587bb7d12c12229b345fa000eb2cb44bdc5acf77ec5eb69` |
| `truth-coverage.json` | `cc090b8715dc69843951e2665f96959844364d5eb1c48b8412cdfe76cdc1daa2` |
| `forecast-sources.json` | `814e585805ca2e182afef21016d31d423d555dc609de8f06d54fcc62975d3501` |
| `evaluation/comparison-summary.json` | `25245c8bbfad49df84e6215b4bbd3d7534c26845421b949221a34a0b260202e9` |

The `evaluation/` subdirectories contain the pooled and three station-specific dataset
manifests, fit timestamps, holdout locks, and full reports. Reproduction timestamps differ;
the original evidence is not overwritten.

Collection reused the frozen 2025 data, downloaded three bounded 2026 GHCNh station files,
and requested only the uncovered 32 forecast days. Four forecast payloads cost an estimated
12 provider quota units. One GFS TLS connection reset occurred before an HTTP response;
one manual transport resume was recorded in `transport-resume.json`. No HTTP 429 occurred,
no rate-limit retry was used, and no experiment dates or fitting rules changed.
