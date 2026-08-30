# Expandable hourly details design

## Goal

The full-day screen lists every available hour but hides most of the hourly data already stored in
`HourlyWeather`. A user should be able to inspect one hour without leaving the day or losing the
current day page.

## Chosen interaction

Tapping an hourly row expands that row. Tapping it again collapses it. Tapping another hour closes
the first row and opens the selected row.

Two alternatives were rejected:

- A nested bottom sheet conflicts with the existing full-day bottom sheet and horizontal day pager.
- Permanently visible metrics make a 24-hour day several screens longer and slow down scanning.

## Layout

The collapsed row keeps the current 78 dp presentation: time, condition, temperature,
precipitation probability, wind speed, and direction. A chevron at the right edge shows that the row
can expand.

The expanded area sits below the summary row and above its divider. It uses a two-column grid with
no card backgrounds. Each metric has a small muted label and one higher-contrast value. The block
uses the existing 8 dp spacing rhythm and cyan accent only for data, not decoration.

The expanded block may show these values:

- apparent temperature;
- precipitation amount;
- humidity;
- wind gusts;
- pressure;
- low, middle, and high cloud cover;
- UV index;
- visibility.

Optional values are omitted. Required values remain visible even when their value is zero.

## State

Each day page owns one nullable expanded-hour key. The key uses the complete hourly timestamp, not a
list index. Paging to another day does not carry the expanded row into that day.

`toggleExpandedHour(current, clicked)` is a pure state transition. It returns `null` when the same
hour is tapped and returns `clicked` for every other tap.

## Accessibility

The whole collapsed row is one 48 dp or larger target. Its state description reports either
**Collapsed** or **Expanded** in the selected app language. The chevron is decorative. The expanded
metric labels and values remain available to TalkBack in reading order.

## Data and formatting

The screen uses the existing `HourlyWeather` record and `WeatherUnitFormatter`. It performs no
network request and changes no forecast value. Pressure, visibility, precipitation, temperature,
and wind follow the selected Metric or Imperial setting.

## Error handling

If an hour has no optional detail, the row still expands to show precipitation amount, humidity,
and pressure. Wind remains in the summary row. Invalid or missing optional data never produces
placeholder numbers. The day list keeps its existing 23-hour and 25-hour daylight-saving behavior.

## Verification

Unit tests cover opening, closing, switching hours, and day isolation. Locale tests require the two
new state labels in all five catalogs. The full Android unit, Lint, debug, release APK, and release
AAB gates must pass.

Huawei QA covers one normal hour, one rainy hour, Metric and Imperial units, English and Czech,
horizontal day paging, vertical scrolling, and TalkBack state descriptions.

## Non-goals

This slice does not add another screen, data provider, hourly model comparison, animation, chart,
alert, or forecast-accuracy claim.
