# Precipitation source rejection and frozen validation, 24 September 2026

The operational feed now marks a model's precipitation series as missing when its
otherwise valid cumulative source decreases beyond the existing 0.01 mm tolerance.
It retains the same model's temperature/wind and other models' valid precipitation.
It logs the rejected source, point, cycle, lead interval and decrease. The research
conversion remains strict by default. No calibration gate or weight was changed.

## Exact IFS source evidence

The failure from cycle 2026-09-22 12 UTC was reproduced using official ECMWF Open
Data, ecCodes 2.48.0 and the production nearest-point decoder at 49.15 N, 16.4 E.
The two messages have the same grid hash `265781b4edc06425746b46a5775244eb`,
`stepType=accum`, `startStep=0`, `shortName=tp`, `units=m`, `packingType=grid_ccsds`,
`bitsPerValue=16`, `decimalScaleFactor=0` and `referenceValue=0`.

| Lead | Decoded total, mm | ecCodes packingError, mm | binaryScaleFactor |
|---|---:|---:|---:|
| 12 h | 0.3509521484375 | 0.003814697265625 | -17 |
| 18 h | 0.335693359375 | 0.00762939453125 | -16 |

The decrease is 0.0152587890625 mm. The sum of the two final-message packing
error bounds is only 0.011444091796875 mm. Packing becomes coarser, but the final
GRIB metadata does not establish that the complete decrease is encoding noise.
Upstream processing or earlier packing would need additional source evidence.
The correction therefore does not widen the tolerance or relabel this value as dry.

ECMWF documents how changing discretization can create spurious positive and
negative deaccumulations. That general mechanism does not prove a particular
decrease falls inside the bounds of these two delivered messages.
[ECMWF accumulation/packing explanation](https://confluence.ecmwf.int/pages/viewpage.action?pageId=208501579).

The source request identities are:

- [IFS 12 h](https://data.ecmwf.int/forecasts/?date=20260922&model=ifs&param=tp&step=12&stream=oper&time=12&type=fc), SHA-256 `3c48dfc49f07ff33edcbc418693ad307e13fda75fa5e9788bed6081d538ef020`.
- [IFS 18 h](https://data.ecmwf.int/forecasts/?date=20260922&model=ifs&param=tp&step=18&stream=oper&time=12&type=fc), SHA-256 `441790cab1b674c484315d098a4a29ef1683d9b10eda92a3640f3bd9163562d3`.

Eight bounded fields were collected: IFS and AIFS at 6, 12, 18 and 24 hours,
5,234,891 bytes total. Raw GRIBs, checksum sidecars and `metadata.json` remain in
the ignored local cache `research/data/raw/ecmwf-packing-20260924/`.
ECMWF source attribution: ECMWF, CC BY 4.0. The public source archive rotates;
future reproduction needs those frozen bytes, not a later forecast run.

## Missingness policy and verification

Only the typed numeric decrease error can activate operational quarantine.
Mixed runs/units/point axes, duplicate leads, inconsistent valid times,
non-accumulated fields and incompatible interval boundaries still fail explicitly.
Large resets are rejected too; quarantine never repairs their numeric values.
The whole affected model/run precipitation series is null, including its initial
lead, because subsequent increments cannot safely restore a compromised series.
This deliberately sacrifices unaffected points in that model field. Narrower
quarantine requires provenance for the affected source values.

An actual nine-point, four-lead decode around the failure produced 45 IFS rain rows
including the initial lead, all null after logged rejection. All 45 AIFS rows remained
numeric. At the reported point AIFS intervals remained `[0, 0, 0, 0, 0.09375]` mm.
The regression suite checks same-model non-rain retention, null JSON serialization,
unchanged other-model rainfall and complete validity axes. Existing missing-source
blend checks enforce the contributor minimum and exclude null values from scoring.
Full-grid operational publication was not run locally; this verification is bounded
source decoding/conversion plus feed serialization, not deployment acceptance.

The complete test run also exposed an existing Windows concurrent cache-index
replacement race (`WinError 5`) in the GHCNh downloader. Cache publication now
atomically links a completed temporary file only if the immutable destination does
not exist. The first concurrent writer wins; existing cache bytes are not replaced.
The existing concurrent-writer/checksum regression passes with this correction.

## Reproduced independent-observation comparison

The already frozen East Asia temperature study was rerun without network access:

```powershell
research/.venv/Scripts/python.exe research/scripts/ghcnh_temperature_validation_2025.py --output-dir research/output/ghcnh-temperature-reproduction-20260924
```

This is a reproduction of the September 6 study, not a new untouched holdout.
The script verifies pinned raw checksums and rejects an existing output directory.
Use a new directory when rerunning. The original 273 training cases and 90 holdout
cases use quality-filtered NOAA GHCNh station observations independent of the three
forecast inputs. The holdout spans November 30 through December 29, 2025, with
30 daily cases each at Tokyo Haneda, Seoul Incheon and Shanghai Pudong.

| Forecast | Pooled holdout MAE, C |
|---|---:|
| ICON Seamless | 1.394444 |
| ECMWF IFS 0.25 | 1.538889 |
| GFS Seamless | 1.370000 |
| Training-selected Best Match fallback | 1.404444 |
| Learned candidate | 1.054757 |

The candidate's paired MAE improvement over Best Match is 0.349688 C, with the
same date-block bootstrap 95% interval 0.172289 to 0.533534 C. It still fails
`unstable_folds`: the August in-sample stability group has six cases and improvement
-0.638302 C. No requirement was relaxed and `exported=false` remains in the report.
Tokyo's GFS MAE (1.066667 C) still beats the candidate (1.366884 C); Shanghai's
Best Match (0.713333 C) still beats the candidate (0.732303 C).

The runtime schema can restrict accepted segments by region, variable, lead range
and month, and validates exact model IDs and minimum contributors. The existing
study does not qualify station-specific weights, other regions, rain probabilities,
14-day forecasts or global superiority. Its nominal `previous_day1` lead is not a
verified issued-run initialization. The existing `fold_improvements` are monthly
in-sample checks, not rolling cross-validation. Retuning against this observed
holdout would require a new untouched validation period before activation.

Reproduction artifact SHA-256:

| Artifact | SHA-256 |
|---|---|
| dataset-manifest.json | `0a32637b4bd84a24b2c3c9b5234f504aba5eae36bd1f53c9ac9a538f06e53539` |
| holdout-lock.json | `f0b1ba31eba78661be74f94f7e592898165bd425a41bdc1b1986754266aa0377` |
| report.json | `654ca75c35eb17b47b706f269434416047067a34adfe13c72ce8d9218aab638d` |
| reproduction-summary.json | `b841306e7ece7624602321231553034b39108ae7206ac12343fbfafb068dd68e` |

See the [original study and source contracts](2026-09-06-ghcnh-temperature-validation.md)
for its frozen input hashes, sampling rules and station scope. Existing worldwide
production gates remain blocked; no weights or probabilities were exported.
