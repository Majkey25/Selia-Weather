# Google Play submission reference

- Store name: `Selia Weather: Weather Radar`
- Launcher name: `Weather`
- Package: `com.majkeylab.weatheraladin`
- Default language: English (`en-US`)
- Category: Weather
- App or game: App
- Free or paid: Free
- Contains ads: No for the current closed-test release. Change this only after commercially licensed forecasts and release monetisation are enabled.
- Privacy policy: `https://majkey25.github.io/Selia-Weather/`
- App access: All functionality is available without login.
- Target age groups: 13 to 15, 16 to 17, and 18 or older.
- News app: No
- Government app: No
- Health features: No

## Data safety

- Data is encrypted in transit: Yes.
- Account creation: No.
- Approximate location and precise location: collected and shared with Open-Meteo for every forecast request. The request sends the selected forecast coordinates, including default Prague, a search result, a favourite, or current location. The current-location permission is optional. The data supports app functionality and is encrypted in transit. Widgets and enabled notifications can refresh the last selected place in the background without reading a new device location.
- After the user selects **Load archive**, the selected coordinates are shared with NASA POWER together with a five-year date range. This user-triggered transfer provides app functionality and is encrypted in transit. The response is cached on device for at most 12 coordinates and refreshed at most once per 24 hours.
- In-app search history: place search terms are collected and shared with Open-Meteo for geocoding. This data is optional, used for app functionality, and encrypted in transit.
- Ephemeral processing: No. The app does not retain this data off-device, but [Open-Meteo states](https://open-meteo.com/en/terms) that free API server logs may contain coordinates and are deleted after 90 days.
- Users can delete local app data in Android settings or by uninstalling the app.
- AviationWeather receives a bounded coordinate box around the selected location to find recent METAR reports, including in Czechia. This transfer is used for app functionality and is encrypted in transit.
- OpenStreetMap receives requests for visible tiles while the point picker or observed radar map is open. RainViewer receives requests for the radar manifest, visible radar tiles, and coverage mask. Tile requests reveal the approximate visible map area and IP-derived location.
- DWD receives forecast-map metadata, legend and visible-area tile requests. Forecast-map requests no longer send an interpolated coordinate grid to Open-Meteo.
- Official warnings send selected Czech coordinates to ČÚZK for administrative-area matching and US coordinates to NWS for point warnings. ČHMÚ receives national CAP bulletin requests without the coordinates. Coordinates without a country label can be sent to ČÚZK or NWS to verify coverage. These requests support app functionality over HTTPS; enabled notifications can repeat them in the background.
- Release builds do not contain or contact Google Mobile Ads, UMP, Play Billing, Premium, or `AD_ID`.
- The app has no separate analytics SDK and does not collect health, contacts, messages, photos, files, audio, or payment-card data.
- A selected widget image stays on the device. The app retains read access to the Android document URI only while a configured widget uses it.
- The optional Buy Me a Coffee action opens an external HTTPS page. It grants no app feature, entitlement, or priority.
- **Ask AI with CSV** confirms the disclosure, then creates a separate local CSV with the selected location name and coordinates, source metadata, and the full available five-year daily archive. The file is shared only with the recipient the user chooses in Android's share sheet. New exports do not overwrite earlier files; the bounded cache keeps at most 12 exports and removes files older than seven days on export.

## Monetization

- Current release builds contain no advertising or purchase payload.
- Products can remain configured in Play Console but are unavailable in the current app.
- Monetization requires a commercially licensed forecast path, a new Data safety review, updated public disclosures, and a separately verified release.

The location disclosure covers Open-Meteo forecasts, NASA POWER archives, AviationWeather observations, ČÚZK/NWS warnings and visible map areas requested from OpenStreetMap, RainViewer and DWD. Android Geocoder can receive coordinates only after the user selects **Use my location**. In Czechia, the app selects nearby ČHMÚ station IDs locally and requests public station files over HTTPS. Selected coordinates are not sent to ČHMÚ. The public privacy page describes these data flows; this reference is not a claim that every Play Console field has been freshly verified.

## Assets

`en-US` owns the default icon, feature graphic, and six phone screenshots. `cs-CZ` has its own Czech feature graphic and six phone screenshots. The `de-DE`, `es-ES`, and `fr-FR` listings omit image directories and inherit the default English assets. The fresh 2026-09-24 poster order, capture provenance, and verification are recorded in [the store artwork manifest](store/2026-09-24/manifest.md).
