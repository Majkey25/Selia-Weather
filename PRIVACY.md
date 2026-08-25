# Privacy policy

Effective date: 25 August 2026

ALADIN weather, package `com.majkeylab.weatheraladin`, does not require an account. The app has no analytics, advertising, or advertising SDK. The developer does not sell personal data or use it for profiling.

## Data on your device

The app stores the last selected place, favourite places, the last successful forecast, language preference, and widget settings in internal app storage. Android backup and device transfer are disabled for this data. Clearing app data or uninstalling the app deletes it.

Each widget stores its settings under its Android widget ID. If you select a custom image, the app stores only the selected Android content URI and retains Android read permission for that URI. The widget reads a bounded image copy only when it renders. Removing the widget removes its settings and releases its retained image permission when no other widget uses the URI.

## Location

Approximate and precise location permissions are optional. After you select **Use my location**, the app reads coordinates, checks that they are in Czechia, and sends them to Open-Meteo over HTTPS to request a forecast. Android's system Geocoder can also receive the coordinates to name the place. The app does not track location in the background.

## Network communication

- Open-Meteo receives place search terms and selected coordinates. It provides geocoding and forecasts. The developer does not retain this data off-device. [Open-Meteo states](https://open-meteo.com/en/terms) that free API server logs may contain coordinates and are deleted after 90 days.
- ČHMÚ provides radar, nowcast, lightning, and satellite images. Its servers process normal HTTPS technical data such as your IP address and request data.
- Buy Me a Coffee receives data only if you choose the optional support action. The app opens the external HTTPS page `https://www.buymeacoffee.com/majkey` through Android.

All app network communication uses HTTPS. The app does not send your name, email address, advertising ID, contacts, or device content.

## Retention and choices

The developer does not operate a server that stores app data. Data on your device remains until you clear app data or uninstall the app. You can remove location permission in Android settings at any time. You can remove image access by deleting the widget, changing its image, or managing the selected document in Android. Open-Meteo, ČHMÚ, and Buy Me a Coffee process data under their own terms when you contact their services.

## Contact

For privacy questions, open the [project support form](https://github.com/Majkey25/ALADIN-weather/issues/new).
