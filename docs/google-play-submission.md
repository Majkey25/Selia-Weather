# Google Play submission reference

- Store name: `Selia Vetra: Czech Forecast`
- Launcher name: `Vetra`
- Package: `com.majkeylab.weatheraladin`
- Default language: English (`en-US`)
- Category: Weather
- App or game: App
- Free or paid: Free
- Contains ads: Yes
- Privacy policy: `https://majkey25.github.io/Selia-Weather/`
- App access: All functionality is available without login.
- Target age groups: 13 to 15, 16 to 17, and 18 or older.
- News app: No
- Government app: No
- Health features: No

## Data safety

- Data is encrypted in transit: Yes.
- Account creation: No.
- Approximate location and precise location: collected and shared with Open-Meteo for every forecast request. The request sends the selected forecast coordinates, including default Prague, a search result, a favourite, or current location. Opening **Weather details** also sends 25 forecast coordinates inside a 20 km radius around the selected location for the Local rain field. The current-location permission is optional. The data supports app functionality and is encrypted in transit.
- In-app search history: place search terms are collected and shared with Open-Meteo for geocoding. This data is optional, used for app functionality, and encrypted in transit.
- Ephemeral processing: No. The app does not retain this data off-device, but [Open-Meteo states](https://open-meteo.com/en/terms) that free API server logs may contain coordinates and are deleted after 90 days.
- Users can delete local app data in Android settings or by uninstalling the app.
- Google Mobile Ads 25.4.0 collects and shares IP-derived approximate location, app interactions, diagnostics, and device or other identifiers for advertising, analytics, and fraud prevention. Transport is encrypted. UMP consent and privacy choices apply where required.
- OpenStreetMap receives requests for the visible map tiles only while the worldwide point picker is open. Tile requests reveal the approximate visible map area and IP-derived location. The exact selected coordinate remains in the app.
- Google Play Billing accesses purchase history for app functionality. Payment-card details remain with Google Play and are not received by the app or developer.
- The app has no separate analytics SDK and does not collect health, contacts, messages, photos, files, audio, or payment-card data.
- A selected widget image stays on the device. The app retains read access to the Android document URI only while a configured widget uses it.
- The optional Buy Me a Coffee action opens an external HTTPS page. It grants no app feature, entitlement, or priority.

## Monetization

- One-time product: `remove_ads_lifetime` — permanently removes ads.
- Subscription: `premium_monthly` — monthly auto-renewing Premium that removes ads.
- Either active product removes all interstitial requests.
- Pending or unknown entitlement hides ads until Google Play returns a conclusive state.
- AdMob app and interstitial IDs must replace the debug test IDs before production upload.
- Do not enable ads, paid products, or a production rollout while the app calls the Open-Meteo Free API. A licensed customer endpoint behind a secret-safe backend, self-hosted service, or direct commercially reusable feed is required first.
- A server-side Play Developer API verifier is recommended before public rollout; the current client-only beta rechecks active purchases on every Billing connection and resume.

The location disclosure covers forecast coordinates sent to Open-Meteo for every forecast request and approximate map areas requested from OpenStreetMap while the point picker is open. Android Geocoder can receive coordinates only after the user selects **Use my location**. In Czechia, the app selects nearby ČHMÚ station IDs locally and requests public station, radar, and satellite files over HTTPS. Selected coordinates are not sent to ČHMÚ. Do not claim a service-provider or user-action exception for the Open-Meteo transfer.

## Assets

`en-US` owns the icon, feature graphic, and phone screenshots. The `cs-CZ`, `de-DE`, `es-ES`, and `fr-FR` listings contain localised text only and omit image directories. Google Play inherits the default English assets for those listings, which avoids mismatched screenshots or feature graphics.
