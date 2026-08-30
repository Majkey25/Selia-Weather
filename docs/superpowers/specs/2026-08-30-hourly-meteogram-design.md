# Hourly meteogram design

## Product goal

Selia Vetra should answer one question without opening another screen: what changes during the next
24 hours? The existing hourly panel shows time, condition, temperature, and precipitation
probability. It hides precipitation amount, wind, and the day-to-night transition.

The target user scans the panel before travel, outdoor work, or sleep. The main action remains
horizontal scrolling. The design must not add another navigation destination or require another
weather request.

## Competitor findings

Meteoblue combines hourly temperature, precipitation amount and probability, cloud context, wind
speed, wind gusts, and direction on one time axis. It also separates daylight from night. Samsung
Weather keeps the hourly forecast in one focus block with large, readable values. Selia Vetra will
use those information-design principles without copying either visual system, icon set, or layout.

## Chosen approach

Extend the existing `HourlyGraphPanel` into one compact 24-hour meteogram. Keep its current
horizontal scroll and 68 dp hourly columns. Replace the temperature-only canvas with a combined
chart and add one wind row.

Alternatives were rejected:

- A separate full-screen meteogram duplicates navigation and hides the fastest forecast scan.
- Separate temperature, precipitation, and wind cards create a tall stack and repeat the time axis.

## Visual system

The panel remains one dark focus block with the existing 28 dp radius and border. It uses the app's
current type scale and weather colors.

- A pale cyan line shows temperature.
- Cyan precipitation bars grow from the chart baseline. Their opacity follows precipitation
  probability. Dry hours have no bar.
- A low-contrast warm tint marks daylight. A blue-black tint marks night.
- Temperature labels remain white.
- Precipitation labels remain cyan.
- Wind arrows and speeds use white at reduced opacity.

The chart uses no gradient fog, glass effect, extra cards, chart library, or animation.

## Layout and behavior

Each hourly column shows these elements in order:

1. Local time, with **Now** for the first hour.
2. The weather condition icon.
3. The combined temperature and precipitation chart.
4. Temperature.
5. Precipitation probability and amount. A dry hour shows an en dash.
6. A wind-direction arrow and wind speed.

The panel always requests 24 existing hourly records through `upcomingHours`. It does not fabricate
missing records. The graph scales temperature within the visible 24-hour data set. Precipitation
bars use the largest finite precipitation amount in that set and keep a nonzero minimum scale so a
dry forecast does not divide by zero.

The complete panel scrolls as one unit. It keeps the current bottom navigation overlay clearance.
The chart height grows only enough to show both the temperature line and precipitation bars.

## Component boundaries

`HourlyMeteogram.kt` owns pure chart geometry and the combined Canvas. `ForecastScreen.kt` owns the
hourly labels, scrolling, weather icons, unit formatting, and section placement.

The pure geometry function accepts finite hourly values and chart dimensions. It returns normalized
temperature points and precipitation bar fractions. The function rejects non-positive dimensions
and clamps weather values to safe display ranges. Compose draws the returned geometry and does not
repeat the calculations.

## Accessibility

Each hourly column exposes one description containing local time, condition, temperature,
precipitation probability, precipitation amount, wind speed, and wind direction. Decorative chart
marks are hidden from accessibility services. Text contrast remains at least as strong as the
existing panel. The implementation does not depend on color alone because every value also appears
as text.

## Error handling

An empty hourly list keeps the existing panel hidden. One hour renders labels without a temperature
line. Missing optional values omit only the affected mark. Invalid chart dimensions fail in the pure
geometry boundary during tests and never come from Compose layout.

## Verification

Unit tests cover 24-hour geometry, constant temperature, dry precipitation, mixed day and night,
and invalid dimensions. Android tests verify all five locales still contain the required labels.
The full Android unit suite, Lint, debug APK, minified release APK, and release AAB must pass.

Huawei QA covers horizontal scrolling to hour 24, daylight and nighttime sections, rain and dry
hours, Metric and Imperial units, English and Czech, large text, and TalkBack descriptions.

## Non-goals

This slice does not add a new data provider, model-accuracy claim, cloud-height profile, seven-day
meteogram, radar layer, alert, or navigation destination.
