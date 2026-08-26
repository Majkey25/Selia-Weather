# Product design

## Purpose

Selia Weather answers a practical question for people in Czechia: what will the weather do in my place today and over the next days? The app keeps the first screen focused on current conditions, the 20-hour outlook, and the 14-day forecast. Search, favourites, current location, radar, and the widget remain one action away.

## Data

- Open-Meteo CHMI Forecast API uses `chmi_aladin_seamless` for the first three days.
- ALADIN CZ provides the local model data. Open-Meteo continues the forecast with ECMWF IFS HRES data for up to 14 days.
- Search is restricted to Czech places.
- ČHMÚ provides rain radar, nowcast, lightning, and satellite cloud imagery.
- The app stores the last successful forecast locally for offline display and widgets.

Selia Weather identifies these providers but is not affiliated with them.

## Information structure

The bottom navigation has **Weather** and **Maps**. Weather shows current conditions, a horizontally scrollable 20-hour strip, metrics, a 14-day list, and a full hourly sheet for a selected day. Maps use rain and clouds as base layers. Lightning remains an independent overlay on either base layer.

The adaptive widget is one stable launcher layout. It changes visible fields by size and lets each widget keep its own background, colours, transparency, text scale, alignment, label, image URI, and field selection.

## Visual rules

- Material 3 with edge-to-edge layout and Android 10 support.
- A restrained weather-reactive gradient carries the state. It is not decorative filler.
- The current temperature leads. The place and condition follow. Time-based information scrolls horizontally. The day list scrolls vertically.
- A 4 dp grid, 20 dp side padding, and at least 48 dp touch targets keep the UI usable.
- System typography is preferred. Numeric values use tabular figures where available.
- Only controls use rounded surfaces. The UI avoids stacks of unrelated cards and unnecessary shadows.

## States and failures

Loading keeps the page structure and uses one indicator. A network failure leaves the last cached forecast visible with its update time. Without a cache, the app shows the error and a retry action. An empty search sends no request. A search result outside Czechia is not shown.

Widget rendering has a safe fallback. If Android cannot read a custom image or a launcher resize causes rendering trouble, the widget uses its colour background and remains updateable.
