# External cloud check and observed precipitation

## Cloud correction rejected

Freeze the corrected August 1-28, 2026 monotone cloud maps before requesting
October 1-31, 2025 at Libuš, Holešov and Prostějov. This is an external temporal
falsification in a different weather period, not causal forward validation:
the fit data postdate the test data. Nominal previous-day labels also do not
establish exact operational issue or receipt times.

Each lead has 1,359 matched QC0 whole-sky SYNOP N hours across 31 calendar days.
Station counts are 463/153/743; accepted observation days are 31/22/31. Before
pairing, 868 N records fail QC, three are unobservable code 9 and two hourly
records are absent. No missing observation or model value becomes zero.

| Nominal lead | Candidate MAE | Raw median MAE | Raw mean MAE |
| --- | ---: | ---: | ---: |
| Day1 | 15.1686 pp | 15.2965 pp | 15.4607 pp |
| Day2 | 16.3673 pp | 16.6196 pp | 17.3309 pp |

Paired daily and seven-day bootstrap intervals include zero against both raw
aggregates. Holešov day2 MAE rises from 12.9281 to 15.2958 pp (+18.31%), exceeding
the existing 5% degradation limit. Both leads fail the frozen checks. Five
weekly blocks are descriptive, not strong statistical confirmation.

BoM is wholly missing and CMA mostly missing; the actual median has 8-10 usable
members, not eleven everywhere. ALADIN Seamless in 2025 can contain a global
fallback; do not call that benchmark native ALADIN. Do not deploy the maps or
increase confidence. Their 28-day fit still fails the 90-day training gate.

Local report SHA256: `b59a38e2845d1724a7db4c0e5f87c563dea4a77999f48554398022e119d0bee6`.
Map SHA256: `ccb5ebfc4c64dbf810c5cd2932778e3a20d74eacaafbb5e3fc317167acae006f`.
Protocol SHA256: `ed3be6e5dadcd3918b881333f50cd96de5a9bdd0f545a58c87e76a25ea990b5c`.
The fixed knots permit independent scoring; the original temporary fit script
was removed after verification and is not a retained runnable fitting pipeline.

## Confirmed observation-ingestion defect

The METAR exact-token whitelist rejected valid `DZRA`/`RADZ` and unsigned/light
`TSRAGR` groups. A nearby fresh report could therefore lose its precipitation
phenomenon while retaining only cloud cover or the modeled clear condition.

Use combined WMO 4677 codes 58/59 for light/moderate-or-heavy drizzle with rain.
Do not recast it as pure rain or infer mm. Keep the existing conservative 96
hail-thunderstorm mapping for all supported `TSRAGR` intensity variants. Unknown,
nonlocal and mixed freezing/snow combinations still cannot supply local weather.

Primary contracts: [WMO weather groups](https://codes.wmo.int/49-2/_AerodromePresentOrForecastWeather),
[WMO Manual 306, FM15 §15.8.6 and table 4677](https://www.mgm.gov.tr/FILES/resmi-istatistikler/WMO_NO_306.pdf),
[NOAA present-weather table](https://emc.ncep.noaa.gov/mmb/data_processing/table_20.htm).
The correction applies to current observations, not learned future predictions.
It does not establish the cause of the reported Březnice miss.

## Independent advice is not evidence

Browser Gemini explicitly selected `4 Argon` with `Nejvyšší` thinking. Its initial
answer incorrectly described CHMI QC0 as unvalidated and proposed a quorum that
could erase a preserved wet provider. These claims were checked, rejected and
explicitly corrected. Its revised preservation-safe 75th-percentile rain rule
was tested on the already-inspected September cohort, not a new holdout. It
keeps every existing production wet amount and can only raise dry amounts.
The scalar reconstruction matches all saved compiled-kernel amounts.

Among 2,159 station-hours and 143 wet hours per lead, misses fall 36→15 and
41→22, but false alarms rise 130→206 and 138→212. MAE rises from 0.055535 to
0.060769 mm and 0.059194 to 0.063363 mm; both paired daily and weekly difference
intervals are entirely positive. CSI falls 0.39194→0.36676 and 0.36299→0.34085.
Reject the candidate: catching more rain alone does not establish a better
forecast. It is not integrated and these are exploratory, not operational scores.

Astra independently reproduced the external cloud scores
from the raw snapshots and checked the METAR contract. Neither model agreement
nor a parser regression test proves forecast accuracy.
