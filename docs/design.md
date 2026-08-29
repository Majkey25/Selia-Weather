# Product design

## Purpose

Selia Vetra answers a practical question worldwide: what will the weather do at this exact place today and over the next days? The app keeps the first screen focused on current conditions, the 24-hour outlook, and the 14-day forecast. Search, favourites, current location, exact coordinates, maps, and the widget remain one action away.

## Data

- Open-Meteo Best Match supplies the complete fallback forecast worldwide.
- The unreleased on-device prototype requests explicit global provider series, adds CHMI ALADIN inside Czechia, and uses a robust median only when at least three values are available.
- The prototype is not a production calibration or an accuracy claim. The separate direct official-data feed remains diagnostic until a locked holdout passes.
- Search, current location, map points, and coordinates work worldwide with local timezones.
- ČHMÚ provides rain radar, nowcast, lightning, and satellite cloud imagery.
- Nearby ČHMÚ automatic stations correct only current Czech conditions when observations are fresh.
- The app stores the last successful forecast locally for offline display and widgets.

Selia Weather identifies these providers but is not affiliated with them.

## Information structure

The bottom navigation has **Weather** and **Maps**. Weather shows current conditions, a horizontally scrollable 24-hour strip, metrics, a 14-day list, and a full 24-hour sheet for a selected day. Maps use rain and clouds as base layers in the Czech radar view. Lightning remains an independent overlay on either base layer.

The adaptive widget is one stable launcher layout. It changes visible fields by size and lets each widget keep its own background, colours, transparency, text scale, alignment, label, image URI, and field selection.

## Visual rules

- Material 3 with edge-to-edge layout and Android 10 support.
- A restrained weather-reactive gradient carries the state. It is not decorative filler.
- The current temperature leads. The place and condition follow. Time-based information scrolls horizontally. The day list scrolls vertically.
- A 4 dp grid, 20 dp side padding, and at least 48 dp touch targets keep the UI usable.
- System typography is preferred. Numeric values use tabular figures where available.
- Only controls use rounded surfaces. The UI avoids stacks of unrelated cards and unnecessary shadows.

## States and failures

Loading keeps the page structure and uses one indicator. A network failure leaves the last cached forecast visible with its update time. Without a cache, the app shows the error and a retry action. An empty search sends no request. Invalid or non-finite coordinates are rejected before use.

Widget rendering has a safe fallback. If Android cannot read a custom image or a launcher resize causes rendering trouble, the widget uses its colour background and remains updateable.
