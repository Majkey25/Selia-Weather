# Interface evolution record

The redesign work is complete. This record preserves the scope that shaped the current interface.

## Completed work

- Replaced a wide bottom bar with a compact Weather and Maps pill.
- Added weather-reactive gradients, a large current temperature, and a clearer place picker.
- Added a horizontal 24-hour outlook with temperature and precipitation information.
- Expanded the 14-day rows and kept the full hourly detail for each day.
- Added favourites, worldwide place search, optional current location, exact map points, offline cache, and the local ČHMÚ radar viewer.
- Added independent lightning over rain and cloud imagery.
- Reworked the launcher widget into one resize-safe hierarchy with per-widget customisation.
- Added system-language behaviour, English fallback, five explicit UI languages, and the optional Buy Me a Coffee external action.

## Verification standard

The project validates the Android unit suite, lint, debug build, release build, and dedicated Android 10 and Android 15 emulator flows. Runtime checks cover forecast, search, favourites, location denial and success, day detail, radar base layers and lightning, support routing, and widget resize and fallback behaviour.

## Boundaries

The app remains independent and does not claim official ČHMÚ or Open-Meteo affiliation. Google Mobile Ads, UMP, and Google Play Billing are present, but a monetised production rollout remains blocked while the app uses the Open-Meteo Free API.
