# Weather navigation and ads cleanup design

## Objective

Make the existing forecast UI easier to use without changing forecast data,
monetisation products, or the visual design system.

This phase removes banner ads, fixes Settings scrolling, expands the hourly
outlook to 24 hours, and adds day-to-day paging inside the open daily detail.

## Scope

### Ads

- Remove every banner-ad placement from the app UI.
- Delete the unused banner composable and banner ad-unit build configuration.
- Keep the existing Google Mobile Ads SDK, consent flow, Premium entitlement,
  and fullscreen interstitial implementation.
- Keep the existing interstitial frequency: one eligible display after every
  fourth completed Maps-to-Weather return, subject to consent, load state, and
  Premium suppression.
- Do not add a replacement banner, native ad, hidden ad, or new ad trigger.

### Settings scrolling

- Open Settings as a fully expanded modal bottom sheet.
- Bound its content to the available screen height.
- Keep one vertical scroll container for the settings content.
- Apply navigation-bar padding so the final support and legal rows remain
  reachable above system navigation.
- Preserve every existing setting, purchase action, link, and privacy action.

### 24-hour outlook

- Change the home hourly outlook from 20 to 24 consecutive future hours.
- Select hours from the current local hour, continuing across midnight.
- Show fewer than 24 entries only when the API payload contains fewer future
  hours.
- Keep the current chart design and hourly data model.

### Daily detail paging

- Opening a daily row selects its index in the existing forecast-day list.
- Render daily detail pages with Compose `HorizontalPager` inside the existing
  modal sheet.
- A finger swipe to the right advances to the next chronological day.
- A finger swipe to the left returns to the previous day.
- Stop at the first and last available day; do not wrap.
- Update the date, summary, and hourly rows together when the page changes.
- Keep vertical hourly scrolling and disable sheet drag gestures as today so
  horizontal and vertical content gestures remain distinct.

## Implementation boundaries

Use existing Compose and monetisation dependencies. Add no library.

Expected code changes are limited to:

- `WeatherApp.kt` for banner removal;
- deletion of `AdaptiveBanner.kt`;
- `AdsController.kt` only if its banner-only public state becomes unused;
- `SettingsSheet.kt` for bounded full-height scrolling;
- `ForecastScreen.kt` for the 24-hour limit and native day pager;
- existing unit tests covering forecast selection and pager boundaries;
- `app/build.gradle.kts` and `README.md` for banner configuration cleanup and
  accurate feature documentation.

Do not change app naming, billing product IDs, interstitial unit ID, forecast
API, weather calculations, radar layers, widgets, release version, or signing.

## Deferred work

The calibrated multi-model weather engine and local LLM are not part of this
phase. The current Android app still uses its existing ALADIN-based forecast
request. Satellite and radar imagery remain display/nowcast sources, not LLM
inputs.

Future implementation must keep deterministic weather calculation separate
from language generation: the calibrated engine produces structured forecasts
and uncertainty; an optional local model only explains those verified values.
The approved design remains
`docs/superpowers/specs/2026-08-25-czech-calibrated-ensemble-design.md`.

## Verification

Run repository-defined Android checks:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Unit verification must cover:

- 24 future hours selected across a date boundary;
- fewer than 24 remaining hours handled without fabricated entries;
- daily paging stops at both list boundaries;
- hourly filtering still returns only the selected local day, including the
  existing daylight-saving-time cases;
- existing monetisation policy tests still pass.

Runtime QA must verify:

1. Settings can scroll to and activate its final row.
2. The hourly panel labels and renders 24 future hours across midnight.
3. Daily detail swipes right to the next day and left to the previous day.
4. The first and last day do not wrap or crash.
5. No banner appears on Weather, Maps, Settings, or Premium screens.
6. Premium users never receive an interstitial.
7. An eligible non-Premium Maps-to-Weather flow can still show the existing
   interstitial without blocking navigation when no ad is loaded.

## Success criteria

- No banner ad code or banner ad-unit configuration remains.
- Settings content is fully reachable on supported screen sizes.
- The outlook uses up to 24 real future hourly entries.
- Daily detail paging follows the approved gesture direction and list bounds.
- Existing forecast, radar, billing, consent, and Premium behavior does not
  regress.
- Android unit tests, lint, and debug assembly pass.
