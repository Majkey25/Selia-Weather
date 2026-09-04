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
- Approximate location and precise location: collected and shared with Open-Meteo for every forecast request. The request sends the selected forecast coordinates, including default Prague, a search result, a favourite, or current location. Opening **Weather details** or selecting **Next 24h** in Map also sends 25 forecast coordinates inside a 20 km radius around the selected location for the Local rain field. The current-location permission is optional. The data supports app functionality and is encrypted in transit.
- After the user selects **Load archive**, the selected coordinates are shared with NASA POWER together with a five-year date range. This user-triggered transfer provides app functionality and is encrypted in transit. The response is cached on device for at most 12 coordinates and refreshed at most once per 24 hours.
- In-app search history: place search terms are collected and shared with Open-Meteo for geocoding. This data is optional, used for app functionality, and encrypted in transit.
- Ephemeral processing: No. The app does not retain this data off-device, but [Open-Meteo states](https://open-meteo.com/en/terms) that free API server logs may contain coordinates and are deleted after 90 days.
- Users can delete local app data in Android settings or by uninstalling the app.
- Outside Czechia, AviationWeather receives a bounded coordinate box around the selected location to find recent METAR reports. This transfer is used for app functionality and is encrypted in transit.
- OpenStreetMap receives requests for visible tiles while the point picker or observed radar map is open. RainViewer receives requests for the radar manifest, visible radar tiles, and coverage mask. Tile requests reveal the approximate visible map area and IP-derived location.
- Release builds do not contain or contact Google Mobile Ads, UMP, Play Billing, Premium, or `AD_ID`.
- The app has no separate analytics SDK and does not collect health, contacts, messages, photos, files, audio, or payment-card data.
- A selected widget image stays on the device. The app retains read access to the Android document URI only while a configured widget uses it.
- The optional Buy Me a Coffee action opens an external HTTPS page. It grants no app feature, entitlement, or priority.
- **Ask ChatGPT** creates one local CSV with the selected location name and coordinates, source metadata, and up to five years of daily rows. The file is shared only with the recipient the user chooses in Android's share sheet. A later export replaces it.

## Monetization

- Current release builds contain no advertising or purchase payload.
- Products can remain configured in Play Console but are unavailable in the current app.
- Monetization requires a commercially licensed forecast path, a new Data safety review, updated public disclosures, and a separately verified release.

The location disclosure covers forecast coordinates sent to Open-Meteo, archive coordinates sent to NASA POWER after **Load archive**, a bounded coordinate box sent to AviationWeather outside Czechia, and approximate visible map areas requested from OpenStreetMap and RainViewer. Android Geocoder can receive coordinates only after the user selects **Use my location**. In Czechia, the app selects nearby ČHMÚ station IDs locally and requests public station files over HTTPS. Selected coordinates are not sent to ČHMÚ.

## Assets

`en-US` owns the icon, feature graphic, and phone screenshots. The `cs-CZ`, `de-DE`, `es-ES`, and `fr-FR` listings contain localised text only and omit image directories. Google Play inherits the default English assets for those listings, which avoids mismatched screenshots or feature graphics.
