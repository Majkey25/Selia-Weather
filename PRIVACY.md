# Privacy policy

Effective date: 31 August 2026

Selia Vetra, package `com.majkeylab.weatheraladin`, does not require a developer account. The developer does not sell personal data or run a separate analytics service. Release builds do not contain or initialise Ads, UMP, Play Billing, Premium, or `AD_ID`. Debug-only QA builds can use Google test integrations outside the release dependency graph.

## Data on your device

The app stores the last selected place, favourite places, the last successful forecast, language preference, and widget settings in internal app storage. A history archive is cached for up to 12 selected coordinates. The cache is refreshed at most once per 24 hours. Android backup and device transfer are disabled for this data. Clearing app data or uninstalling the app deletes it.

After you select **Ask ChatGPT**, the app creates one CSV file in its private cache. The file contains the selected location name and coordinates, daily weather estimates, data-source version, and access time. Android shares the file only with the app you choose in the system share sheet. A later export replaces the previous file.

Each widget stores its settings under its Android widget ID. If you select a custom image, the app stores only the selected Android content URI and retains Android read permission for that URI. The widget reads a bounded image copy only when it renders. Removing the widget removes its settings and releases its retained image permission when no other widget uses the URI.

## Location

Approximate and precise location permissions are optional. After you select **Use my location**, the app reads coordinates and sends them to Open-Meteo over HTTPS to request a forecast. Android's system Geocoder can also receive the coordinates to name the place. The app does not track location in the background.

## Network communication

- Open-Meteo receives place search terms and forecast coordinates for every forecast request. This includes default Prague, a searched place, a favourite, and current location. Current location remains optional and starts only after you select **Use my location**. Open-Meteo provides geocoding and forecasts. The developer does not retain this data off-device. [Open-Meteo states](https://open-meteo.com/en/terms) that free API server logs may contain coordinates and are deleted after 90 days.
- When you open **Weather details** or select **Next 24h** in Map, the Local rain field sends 25 forecast coordinates inside a 20 km radius around the selected location to Open-Meteo. The app keeps the returned spatial field only while that view is active. The field is a model forecast, not measured radar.
- After you select **Load archive**, NASA POWER receives the selected coordinates and requested five-year date range. It returns daily model and satellite grid estimates. The app stores the response only in its bounded on-device cache. NASA POWER data are not local-station measurements. See the [NASA POWER Daily API](https://power.larc.nasa.gov/docs/services/api/temporal/daily/) and [referencing guide](https://power.larc.nasa.gov/docs/referencing/).
- In Czechia, ČHMÚ provides public automatic-station observations. The app selects nearby station IDs on the device. It does not send the selected coordinates to ČHMÚ. ČHMÚ servers process normal HTTPS technical data such as your IP address and requested public file names.
- Outside Czechia, AviationWeather receives a bounded coordinate box around the selected location to find recent worldwide METAR observations. A request can reveal the approximate selected area and normal HTTPS technical data such as your IP address. The app does not send a name, account, or device identifier. See the [AviationWeather Data API](https://aviationweather.gov/data/api/).
- OpenStreetMap receives requests for visible tiles while the point picker or observed radar map is open. RainViewer receives requests for the radar manifest, visible radar tiles, and coverage-mask tiles while the observed radar map is open. Tile requests reveal the approximate visible map area and normal HTTPS technical data such as your IP address. See the [RainViewer Weather Maps API](https://www.rainviewer.com/api/weather-maps-api.html).
- Release builds do not contact Google Mobile Ads, UMP, or Play Billing and do not expose Premium purchases.
- Buy Me a Coffee receives data only if you choose the optional support action. The app opens the external HTTPS page `https://www.buymeacoffee.com/majkey` through Android.

All app network communication uses HTTPS. The app does not send contacts, messages, photos, audio, or the contents of a selected widget image. A weather-history CSV leaves the app only after you select **Ask ChatGPT** and then choose a recipient in Android's share sheet. That recipient processes the file under its own terms.

## Retention and choices

The developer does not operate a server that stores app data. Data on your device remains until the cache replaces it, you clear app data, or you uninstall the app. You can remove location permission in Android settings at any time. You can remove image access by deleting the widget, changing its image, or managing the selected document in Android. Open-Meteo, NASA POWER, ČHMÚ, AviationWeather, OpenStreetMap, RainViewer, Buy Me a Coffee, and a share-sheet recipient process data under their own terms when you contact their services.

## Contact

For privacy questions, open the [project support form](https://github.com/Majkey25/Selia-Weather/issues/new).
