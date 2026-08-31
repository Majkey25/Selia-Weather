# Model runtime contracts

`research/model-contracts.json` records the native grid resolution and update cadence for every
model in `research/model-registry.json`. The audit expires after 90 days. The export loader rejects
a stale audit, an unknown model, a missing model, a duplicate model, or an unsupported schema.

The source pages publish update cadence, but they do not publish a Selia Vetra run-age limit. The
project therefore applies one explicit operational policy:

```text
maximum_run_age_hours = max(6, 2 * update_frequency_hours)
```

This policy allows two expected update cycles and keeps a six-hour minimum for hourly and
three-hourly regional models. It is a project safety limit, not a provider accuracy claim.

## Audited values

| Model ID | Resolution | Update cadence | Maximum run age | Source |
| --- | ---: | ---: | ---: | --- |
| `chmi_aladin_central_europe_2km` | 2.3 km | 6 h | 12 h | [CHMI API](https://open-meteo.com/en/docs/chmi-api) |
| `chmi_aladin_cz_1km` | 1 km | 6 h | 12 h | [CHMI API](https://open-meteo.com/en/docs/chmi-api) |
| `cmc_gem_gdps` | 15 km | 12 h | 24 h | [GEM API](https://open-meteo.com/en/docs/gem-api) |
| `dmi_harmonie_arome_europe` | 2 km | 3 h | 6 h | [DMI API](https://open-meteo.com/en/docs/dmi-api) |
| `dwd_icon_d2` | 2 km | 3 h | 6 h | [DWD API](https://open-meteo.com/en/docs/dwd-api) |
| `dwd_icon_eu` | 7 km | 3 h | 6 h | [DWD API](https://open-meteo.com/en/docs/dwd-api) |
| `dwd_icon_global` | 11 km | 6 h | 12 h | [DWD API](https://open-meteo.com/en/docs/dwd-api) |
| `ecmwf_aifs025_single` | 28 km | 6 h | 12 h | [ECMWF API](https://open-meteo.com/en/docs/ecmwf-api) |
| `ecmwf_ifs` | 9 km | 6 h | 12 h | [ECMWF API](https://open-meteo.com/en/docs/ecmwf-api) |
| `ecmwf_ifs025` | 25 km | 6 h | 12 h | [ECMWF API](https://open-meteo.com/en/docs/ecmwf-api) |
| `geosphere_arome_austria` | 2.5 km | 3 h | 6 h | [GeoSphere API](https://open-meteo.com/en/docs/geosphere-austria-api) |
| `knmi_harmonie_arome_europe` | 5.5 km | 1 h | 6 h | [KNMI API](https://open-meteo.com/en/docs/knmi-api) |
| `meteofrance_arpege_europe` | 11 km | 6 h | 12 h | [Météo-France API](https://open-meteo.com/en/docs/meteofrance-api) |
| `ncep_gfs_global` | 13 km | 6 h | 12 h | [GFS API](https://open-meteo.com/en/docs/gfs-api) |
| `ukmo_global_deterministic_10km` | 10 km | 6 h | 12 h | [UKMO API](https://open-meteo.com/en/docs/ukmo-api) |

The audit does not clear the release licence gate. The current research registry still uses the
Open-Meteo Free API, whose terms limit commercial use. Ads and purchases must remain disabled
until the app uses a paid, self-hosted, or directly licensed production data path.
