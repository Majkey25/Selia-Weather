# Interface evolution

The current interface takes inspiration from modern weather apps without copying their layouts. It uses one weather-reactive background, a strong temperature hierarchy, and direct navigation.

## Hierarchy

1. Place picker and refresh action.
2. Current condition, icon, temperature, apparent temperature, and daily range.
3. A 20-hour horizontal forecast with temperature line and precipitation probability.
4. Compact weather metrics.
5. A 14-day list with condition, precipitation probability, wind, and low-high range.
6. A compact **Weather** and **Maps** navigation pill.

## Radar and widget

The radar controls make the layers explicit. Rain and clouds are alternatives. Lightning remains available over both. The widget editor exposes reliable launcher settings rather than a free-position canvas, because Android `RemoteViews` does not support arbitrary interactive layouts.

Each widget can use an automatic, light, dark, transparent, solid, gradient, or custom-image background. It can change colours, opacity, text scale, alignment, custom label, and visible fields. The custom image path is resilient: an unreadable image falls back to the selected colour treatment.

## Accessibility

Interactive areas are at least 48 dp. Icons have text or content descriptions. The theme keeps readable contrast and supports Android font scaling. Localised text uses Android resources rather than translated values stored in weather data.
