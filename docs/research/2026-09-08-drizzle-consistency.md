# Drizzle and time-support corrections

A user reported light drizzle on 7 September 2026 around 15:18 while the app's
15:00 row displayed Clear, 3% and 0.0 mm. The screenshot also showed 25°C against
a 23°C daily maximum. It did not identify the location or original model runs.
This report is a case to investigate, not a calibration label with verified coordinates.

## Reproduced code paths

- Current conditions were copied into the matching hourly row after its daily summary
  had been calculated. The copy excluded rain amounts, mixing different time supports.
  The correction now leaves the whole hourly forecast unchanged.
- Station sunshine was used to invent total/layer cloud cover and erase a rain code.
  Sunshine duration does not measure cloud fraction; that inference was removed.
- A strict majority of drizzle codes could become Clear below the 0.1 mm amount
  threshold. Source-supported drizzle, including majority intensity, is now preserved.
- Cloud-only blends could change cloud cover without updating a benign sky code when
  precipitation contributors were missing. The sky code now follows the supported
  cloud result without overwriting provider rain or hazard codes.
- Display rounding hid positive trace amounts. Metric and Imperial formatting now
  distinguishes trace precipitation from zero without changing the underlying values.

Nearby point-weather corrections use a narrower 10 km / 30 minute support window;
these are conservative limits, not fitted accuracy weights. A ten-minute station
accumulation does not replace a fifteen-minute model total or an hourly forecast.
Station sunshine availability no longer displaces a nearer station in source selection.
Provider precipitation probabilities remain unchanged.

## Evidence limits

The associated CurrentConditions, ModelConsensus and WeatherUnitFormatter regression
tests reproduce structural failures. They do not establish a percentage improvement
in forecast skill. The exact September case still needs the selected coordinates,
original issued forecasts and independent station/radar observations.

Layer and total cloud products can have different definitions; they must not simply
be summed or forced to their maximum. See the provider's
[cloud calculations](https://github.com/open-meteo/open-meteo/blob/main/Sources/App/Helper/Meteorology.swift).
[Open-Meteo documents](https://open-meteo.com/en/docs) current conditions as
15-minute model data and hourly precipitation as a preceding-hour accumulation.

No failed research weights were activated, and no claim of worldwide or field-level
superiority follows from these consistency fixes.
