# Spec: ALADIN weather identity and localization

## Assumptions

1. The public Google Play package is exactly `com.majkeylab.weatheraladin`.
2. The visible app name is exactly `ALADIN weather` in every language.
3. The existing Kotlin namespace remains `cz.majkey.pocasicesko`. Only `applicationId` defines the public Play identity.
4. The app follows the device language until the user selects a language in the app.
5. Supported languages are Czech, English, German, Spanish, and French. Unsupported system languages fall back to English.
6. The default Google Play listing language is English. Czech, German, Spanish, and French listings are added as localizations.
7. The corrected release is `v0.2.0-beta.2` with `versionCode = 3`.
8. The GitHub repository is `Majkey25/ALADIN-weather`.

## Objective

Rebrand Počasí Česko as `ALADIN weather` before its Play Console app entry is created. Replace hard-coded Czech UI text with localized resources and add an in-app language setting without changing forecast, radar, location, favorite, or widget behavior.

The work succeeds when the release uses package `com.majkeylab.weatheraladin`, starts in the device language, lets the user override that language, persists the override across restart, and renders the main forecast, location flow, radar, errors, and widget configuration in all five languages.

## Architecture

- `values/strings.xml` is the English fallback.
- `values-cs`, `values-de`, `values-es`, and `values-fr` contain complete translations.
- A small `AppLocale` helper owns the supported language list, the persisted override, and locale application.
- Android 13 and newer use `LocaleManager.applicationLocales`.
- Android 10 through 12 use a configuration context and recreate the active activity after a language change.
- The settings sheet opens from the forecast header and offers System, English, Czech, German, Spanish, and French.
- Weather condition data stores `WeatherConditionKey` and `WeatherKind`. UI and widgets resolve the visible condition label from resources.
- The local radar HTML receives the active language tag and selects labels from a fixed five-language dictionary. It never executes remote scripts.
- The widget configuration activity and widget provider use the same persisted locale override.

No new dependency is added. The project uses Android framework locale APIs, Compose `stringResource`, and existing `SharedPreferences`.

## Tech stack

- Kotlin 2.3.21
- Jetpack Compose Material 3
- Android SDK 36, minSdk 29
- Android framework `LocaleManager`, `LocaleList`, and `Configuration`
- JUnit 4

## Commands

- Unit tests: `./gradlew :app:testDebugUnitTest --console=plain`
- Lint: `./gradlew :app:lintDebug --console=plain`
- Debug APK: `./gradlew :app:assembleDebug --console=plain`
- Signed release: `./gradlew :app:assembleRelease :app:bundleRelease --console=plain`
- Runtime QA: `adb -s <emulator-serial> ...`. Never use a physical device.

## Project structure

- `app/build.gradle.kts`: application identity and version.
- `app/src/main/AndroidManifest.xml`: locale configuration and app label.
- `app/src/main/res/values*`: localized Android strings.
- `app/src/main/res/xml/locales_config.xml`: supported per-app locales.
- `app/src/main/java/.../locale/AppLocale.kt`: locale persistence and application.
- `app/src/main/java/.../ui`: localized forecast, location, settings, and map UI.
- `app/src/main/java/.../widget`: localized widget configuration and placeholder text.
- `app/src/main/assets/radar.html`: five-language radar labels selected from a trusted language tag.
- `fastlane/metadata/android/<locale>`: localized Play listings.
- `docs`: rebranded README, privacy policy, screenshots, and release notes.

## Code style

Use resource identifiers for visible text and keep formatting arguments explicit:

```kotlin
Text(stringResource(R.string.feels_like_temperature, temperature.roundToInt()))
```

Do not pass translated strings through the data layer. Return typed state and resolve text at the UI or widget boundary.

## Testing strategy

- Write locale unit tests before `AppLocale` implementation.
- Test supported tags, system-language reset, invalid-tag fallback, and persistence format.
- Run all existing parser, favorites, and day-detail tests after every localization slice.
- Run Android Lint to catch missing or malformed translated resources.
- On Android 10, verify system default, English override, persistence after force-stop, Czech override, radar labels, and widget configuration.
- On Android 15, verify Android per-app locale integration and reset to System.
- Re-run forecast, current location, favorites, radar, nowcast, clouds, and signed release smoke tests.

## Boundaries

- Always: preserve Android 10 support, keep translations UTF-8, validate language tags, run tests before commits, and use only emulators.
- Ask first: add a dependency, add another language, change the Play package again, or publish to production instead of a test track.
- Never: commit signing secrets, touch a physical device, translate provider names, claim that ALADIN covers data it does not provide, or submit the Play app creation form without action-time confirmation.

## Success criteria

- `applicationId` is `com.majkeylab.weatheraladin`.
- App label and store name are `ALADIN weather`.
- `versionCode` is `3`. `versionName` is `0.2.0-beta.2`.
- System language is the default and English is the fallback.
- The settings UI lists System, English, Czech, German, Spanish, and French.
- A selected language survives process death and app restart.
- Main UI, location search, errors, day detail, radar controls, widget configuration, and widget placeholders have complete translations.
- Play metadata exists for `en-US`, `cs-CZ`, `de-DE`, `es-ES`, and `fr-FR`.
- Unit tests, lint, debug build, signed APK, and signed AAB pass.
- Release QA passes on Android 10 and Android 15 emulators.
- A PR merges through green CI and `v0.2.0-beta.2` is published with verified hashes.
- GitHub links and Pages use `https://github.com/Majkey25/ALADIN-weather` and `https://majkey25.github.io/ALADIN-weather/`.
- The Play Console form uses the final name and package before the user confirms creation.
- After Play work finishes, emulators stop and large QA-only AVD/system-image data is removed. The repo, signing key, release artifacts, and local app project remain.

## Open questions

None. The assumptions above are the approved defaults unless the user changes them.
