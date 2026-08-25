# ALADIN weather adaptive radar and widget design

## Objective

Extend the approved `ALADIN weather` beta with four release-blocking improvements:

1. Keep lightning visible as an independent overlay when the user selects cloud imagery.
2. Show at least 20 future hours on the main forecast and give each of the 14 daily rows more useful space.
3. Fix the hosted widget resize failure and add practical per-widget customization without exceeding Android `RemoteViews` limits.
4. Add an optional Buy Me a Coffee link and make the public GitHub project English-first.

The app remains focused on Czech weather data. The default UI language follows the Android system language. English remains the resource fallback for unsupported system languages.

## Approved product decisions

### Radar layers

Rain and clouds remain mutually exclusive base layers because they use different frame sets and legends. Lightning becomes an independent overlay that remains available on both base layers.

- The **Lightning** control never disappears when the user selects **Clouds**.
- A cloud frame derives its lightning image URL from the active cloud timestamp.
- A missing lightning image hides only the lightning overlay. It does not hide the cloud or rain image and does not show a broken image marker.
- The user can turn lightning off and on without changing the selected base layer or timeline position.
- Rain history, cloud history, playback, and `nowcast` keep their current source URLs and timing.

### Forecast density

The main hourly panel shows 20 consecutive hours starting at the current forecast hour. It uses a horizontally scrollable strip so that phones do not compress 20 columns into one screen.

Each hourly item shows the time, weather icon, temperature, and precipitation probability. The temperature line remains visible across the scrollable content.

The 14-day section keeps all 14 days. Each row becomes taller and shows:

- the localized weekday and date;
- the localized weather condition;
- the weather icon;
- precipitation probability and wind speed;
- the daily low and high temperature.

The whole row opens the existing day sheet. The sheet continues to show every available hour for that calendar day, normally 24 hours.

### Adaptive widget

The widget uses one stable `RemoteViews` hierarchy for every size. `onAppWidgetOptionsChanged` changes view visibility and content instead of switching between layouts with different view IDs. This removes stale actions as a cause of launcher-side `Error loading widget` failures.

The provider uses these size classes:

- Compact: time, temperature, and one optional icon.
- Standard: compact content plus location, condition, and daily range.
- Wide: standard content plus the short hourly forecast.
- Tall: the selected metrics can use a second content row.

The provider supports horizontal and vertical resize. Every update catches a rendering failure, records it in Logcat, and applies a minimal safe fallback layout for that widget ID.

Each widget stores its own settings under its Android widget ID. Removing a widget removes only that widget's settings and retained image URI.

### Widget customization

The configuration screen exposes the maximum reliable customization supported by Android `RemoteViews`. It is not a free-position drag-and-drop canvas.

Each widget can configure:

- automatic, light, dark, transparent, solid-color, gradient, or custom-image background;
- background start color, background end color, primary text color, secondary text color, and accent color as validated hexadecimal values;
- background opacity;
- text scale from 80 to 140 percent;
- left, center, or right content alignment;
- an optional custom label;
- visibility of time, date, location, current temperature, weather icon, condition, daily range, hourly forecast, precipitation, wind, humidity, and last-update time.

The custom-image picker uses Android's Storage Access Framework with `ACTION_OPEN_DOCUMENT`. The app retains read permission for the selected URI. The provider decodes the image at a bounded size and renders at most a 512 by 256 ARGB bitmap into `RemoteViews`. If the URI is missing or unreadable, the widget uses its configured color background and continues to render.

The preview uses the same `WidgetSettings` and style functions as the provider. It must not maintain a second interpretation of colors, visibility, or alignment.

### Support link

The settings sheet adds an **About** section below the language list. It contains a localized **Support this app -> Buy Me a Coffee** action.

The action opens `https://www.buymeacoffee.com/majkey` through Android's external HTTPS handler. The link grants no feature, entitlement, badge, membership, or support priority. If Android cannot open the URI, the app shows a localized error and stays open.

### English-first repository

Public repository content uses English:

- `README.md`;
- `PRIVACY.md`;
- `CHANGELOG.md`;
- `docs/index.html`;
- contribution, release, and Google Play submission documentation;
- badge URLs, repository links, screenshots, and image alternative text.

Localized Google Play listings remain localized. The default listing is English. Czech remains available as `cs-CZ` metadata and as an in-app language.

## Tech stack

- Kotlin and Jetpack Compose for app and configuration UI.
- Android `AppWidgetProvider` and `RemoteViews` for the launcher widget.
- Android Storage Access Framework for the custom image URI.
- Local HTML, CSS, and JavaScript for the trusted ČHMÚ radar WebView.
- Existing Android and Java standard libraries only. No new dependency is required.

## Commands

Run the repository checks from the linked worktree:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleRelease :app:bundleRelease --console=plain
```

Use `adb -s <emulator-serial>` for runtime QA. Never send an ADB command to the attached physical device.

## Project structure

- `app/src/main/assets/radar.html`: radar layer state, image URLs, timeline, and radar localization.
- `app/src/main/java/cz/majkey/pocasicesko/ui/ForecastScreen.kt`: 20-hour panel, 14-day rows, and 24-hour day detail.
- `app/src/main/java/cz/majkey/pocasicesko/widget/WeatherWidgetProvider.kt`: size classes, rendering, fallback, and per-widget settings.
- `app/src/main/java/cz/majkey/pocasicesko/widget/WidgetConfigActivity.kt`: widget editor, image picker, and preview.
- `app/src/main/res/layout/`: one adaptive widget hierarchy and launcher preview resources.
- `app/src/main/java/cz/majkey/pocasicesko/ui/SettingsSheet.kt`: language and About actions.
- `app/src/main/res/values*/strings.xml`: all app and widget text in English, Czech, German, Spanish, and French.
- `README.md`, `PRIVACY.md`, `CHANGELOG.md`, `docs/`, and `fastlane/metadata/android/`: public documentation and Play metadata.

The repository graph tool does not index Kotlin in this project. Source inspection and tests remain the authority for Kotlin call paths.

## Code style

Use immutable settings and one explicit size classifier. Keep Android boundary failures local.

```kotlin
internal fun widgetSize(minWidth: Int, minHeight: Int): WidgetSize = when {
    minWidth >= 320 -> WidgetSize.WIDE
    minHeight >= 120 -> WidgetSize.TALL
    minWidth >= 180 -> WidgetSize.STANDARD
    else -> WidgetSize.COMPACT
}
```

Do not add a renderer interface, factory, or dependency. The provider and the Compose preview share pure settings and color helpers.

## Testing strategy

### Unit and static checks

- Test radar base-layer and lightning-overlay state independently.
- Test that the hourly selector returns 20 future hours when 20 hours are available.
- Test widget size boundaries, invalid stored enum values, invalid colors, and unreadable image fallback.
- Test per-widget settings isolation and cleanup.
- Test the exact Buy Me a Coffee HTTPS URL.
- Run the full unit suite, Android Lint, the debug build, `git diff --check`, and release builds.

### Emulator checks

Use dedicated API 29 and API 35 emulators. Do not reuse an AVD controlled by another QA task.

Radar checks:

1. Show measured rain with lightning on.
2. Select clouds and confirm that lightning remains visible and independently controllable.
3. Return to rain and confirm that the timeline position and `nowcast` still work.

Forecast checks:

1. Count at least 20 hourly items from the current hour.
2. Scroll through the full hourly strip.
3. Open the first, a middle, and the last available daily row.
4. Confirm that a normal complete day shows 24 hourly entries.

Widget checks:

1. Place a real widget through the launcher host.
2. Resize compact to standard, wide, tall, full width, and back to compact.
3. Confirm that no size displays `Error loading widget`.
4. Configure English and French widgets with different settings and confirm isolation.
5. Apply a custom image, custom colors, opacity, text scale, alignment, and field visibility.
6. Restart the launcher and the app, then confirm that settings persist.
7. Remove the custom image permission or source and confirm the safe color fallback.

Support checks:

1. Open the support action from English and Czech settings.
2. Confirm that Android receives the exact HTTPS URI.
3. Confirm that a missing external handler produces a localized in-app error.

## Boundaries

Always:

- Keep Android 10 support.
- Keep the default language tied to the Android system language.
- Keep English as the unsupported-locale fallback.
- Preserve ČHMÚ attribution and current radar source URLs.
- Bound every bitmap sent through `RemoteViews`.
- Validate every stored enum, URI, and hexadecimal color.
- Use only emulators for Android QA.

Ask before:

- Adding a third-party dependency.
- Changing the weather provider or radar host.
- Adding any support reward or digital entitlement.
- Submitting a final Play Console form or publishing a Play release.

Never:

- Store a custom image bitmap or translated weather label in preferences.
- Send an unbounded bitmap through `RemoteViews`.
- Open a support URL inside the trusted radar WebView.
- Add ads, analytics, accounts, tracking, or payment SDKs.
- Send ADB commands to a physical device.

## Success criteria

- Selecting clouds does not remove or disable lightning.
- The main forecast shows at least 20 future hourly items.
- Every one of the 14 daily rows has the expanded content and opens its hourly detail.
- A hosted widget survives every tested horizontal and vertical resize without `Error loading widget`.
- Each widget can use an independent image, colors, opacity, text scale, alignment, label, and visible data fields.
- Invalid or missing widget image data falls back without breaking the host.
- The settings sheet opens the verified optional Buy Me a Coffee URL.
- All public GitHub and Pages content is English.
- The app follows the system language unless the user selects English, Czech, German, Spanish, or French.
- Unit tests, Lint, debug and release builds, API 29 QA, API 35 QA, CI, release signature checks, and public link checks pass before publication.

## Open questions

None. The user approved the practical adaptive-template approach on 24 August 2026.
