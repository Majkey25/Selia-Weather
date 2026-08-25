# Open-Meteo forecast fixture

`single-run-chmi-aladin-cz-20260824.json` is the exact 1-day, one-location JSON response from
the official Single Runs API, retrieved on 25 August 2026 UTC with one bounded request:

```text
https://single-runs-api.open-meteo.com/v1/forecast?latitude=50.0755&longitude=14.4378&hourly=temperature_2m,relative_humidity_2m,pressure_msl,wind_speed_10m,wind_direction_10m,precipitation&models=chmi_aladin_cz_1km&run=2026-08-24T00%3A00&forecast_days=1&timeformat=unixtime
```

It contains no credential and is retained only as a small actual-format parser fixture.
Use is subject to the Open-Meteo terms recorded at https://open-meteo.com/en/terms and the
upstream ČHMÚ attribution carried by the requested model data.
