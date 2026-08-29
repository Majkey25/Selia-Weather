# Local rain field and dense weather detail design

## Objective

Add a worldwide spatial precipitation view to Selia Vetra. The view shows where rain or snow is
forecast around the selected location for each of the next 24 hours. Expand the existing weather
detail with useful atmospheric and ground values that the current forecast endpoint already
provides.

The feature must remain readable on a phone. It must not copy Meteoblue's 7 by 7 rainSPOT layout,
name, assets, or visual treatment.

## Approved scope

The work has two connected parts:

- **Local rain field:** an original 5 by 5 precipitation target for a 20 km radius around the
  selected location;
- **Dense weather detail:** a compact summary plus the missing UV, atmosphere, and ground values.

Both parts use the selected location and its local timezone. Android 10 remains the minimum
version. English remains the fallback language. Czech, German, Spanish, and French receive every
new user-facing string.

## Product rules

- Call the feature **Local rain field** in English. Translate the name in each locale.
- Label every cell as a forecast. Do not call the field radar or an observation.
- Keep the measured ČHMÚ radar in the existing **Maps** destination.
- Keep the weather screen restrained. Put the new feature in **Weather details**, not on the home
  forecast screen.
- Show the source interval. An hourly cell contains precipitation accumulated during the preceding
  hour and ending at the selected time.
- Show uncertainty. A dry or wet value without a contributor count is incomplete.
- Never average weather codes, precipitation types, or degree-valued wind directions.
- Do not claim higher accuracy until the locked forecast holdout passes.

## Spatial field

### Geometry

The field contains 25 points in a 5 by 5 grid. The selected location is the centre point. Grid
offsets are `-14`, `-7`, `0`, `7`, and `14` km on both axes. The corner points are 19.8 km from the
centre, so every point remains inside the labelled 20 km radius.

Generate each coordinate with a spherical destination calculation. Use the centre coordinate, the
offset distance, the offset bearing, and the existing Earth radius constant. The calculation must
handle the date line, both poles, and non-finite input. Do not use a flat longitude divisor near a
pole.

Store the row and column with each requested point. API coordinates can snap to a provider grid,
so response coordinates do not define the display order.

### Request

Request the 25 coordinates in one Open-Meteo multi-location call. Use these parameters:

- `forecast_hours=24`;
- `timeformat=unixtime`;
- `timezone=GMT`;
- `hourly=precipitation,rain,showers,snowfall`;
- the explicit models from `forecastApiModelsFor(location)`.

Open-Meteo returns a JSON list in request order. Items after the first include `location_id`. The
parser must require 25 items, matching hourly timestamps, expected units, and a bounded payload.
The parser must reject a duplicate, missing, reordered, non-finite, or malformed item.

The request uses UTC timestamps to keep every surrounding point on one time axis. The UI formats
those timestamps in `snapshot.timezone`.

### Point calculation

For each point and hour, keep model values separate until validation finishes.

- Reject negative precipitation, rain, showers, or snowfall.
- Require at least three valid model values for the point and hour.
- Use the median precipitation, rain, showers, and snowfall value.
- Define a wet model as precipitation greater than or equal to `0.1 mm`.
- Set precipitation probability to the percentage of valid models that are wet.
- Set model agreement to the percentage of valid models that match the majority wet or dry state.
- Record the minimum, median, and maximum precipitation.
- Classify the type as dry, rain, snow, or mixed. Treat at least `0.1 mm` of median rain plus
  showers as liquid precipitation. Treat at least `0.1 cm` of median snowfall as snow.

If fewer than three models remain, mark the cell unavailable. Do not replace a missing value with
zero or copy the centre point into nearby cells.

### Runtime state

Fetch the field only when the user opens **Weather details**. Keep one result for the active
location in the sheet state. Cancel the request when the sheet closes or the location changes.

Do not add a process-wide cache. One bounded result avoids unbounded state and keeps the first
weather screen fast. The existing forecast cache remains unchanged.

## Dense weather detail

### Added values

Add these verified Open-Meteo hourly fields to the main forecast request and typed models:

- `uv_index`;
- `freezing_level_height`;
- `boundary_layer_height`;
- `total_column_integrated_water_vapour`;
- `lifted_index`;
- `convective_inhibition`;
- `soil_temperature_0cm`;
- `soil_moisture_0_to_1cm`;
- `showers`.

Add `uv_index_max` to the daily request. Keep all values in canonical Metric units. Convert only
at the UI boundary where a Metric or Imperial equivalent exists. Keep indices and percentages in
their standard unitless form.

The JSON parser must accept a missing optional field. It must reject a present non-finite value.
Do not drop an otherwise complete hourly row because one optional detail is absent.

### Information hierarchy

Keep the existing full-height **Weather details** sheet. Use this order:

1. Header and location.
2. **At a glance** summary.
3. **Local rain field**.
4. Current conditions.
5. Precipitation and clouds.
6. Wind.
7. Atmosphere.
8. Ground.
9. Sun.
10. Moon.

The **At a glance** summary contains four compact values without individual cards:

- next forecast wet hour;
- maximum precipitation probability in the next 24 hours;
- daily maximum UV index;
- freezing-level height.

Calculate the first two values from the next 24 entries in `snapshot.hourly`. Read UV and the
freezing level from the main typed forecast. The summary must not wait for the spatial request.

Use aligned columns, one divider, and the existing cyan accent. Do not add glass effects, nested
cards, or a new gradient.

### Local rain field UI

Draw two thin concentric rings behind the 5 by 5 grid. Mark the centre cell with a small location
dot. Label north, east, south, and west outside the grid. Show `20 km` beside the outer ring.

Each cell is a rounded square with a minimum 44 dp touch target. Colour intensity follows fixed
Metric thresholds:

- dry: transparent with a faint border;
- less than `0.5 mm`: cyan;
- `0.5` to less than `2 mm`: blue;
- `2` to less than `5 mm`: violet;
- `5 mm` or more: magenta.

Show a small snow mark for snow or mixed precipitation. Do not use colour as the only type signal.

Place a horizontally scrollable 24-hour selector below the field. The selected hour uses a solid
accent background. Format times in the selected location's timezone. Do not autoplay the field.

When the user taps a cell, show one compact line below the selector. The line contains direction,
distance, precipitation, type, probability, model agreement, and contributor count.

### Accessibility

Expose every cell as a separate semantic node. Its description contains the direction, distance,
selected interval, precipitation amount, type, probability, agreement, and availability. The
centre cell description says that it is the selected location.

Keep contrast at WCAG AA for text and controls. Support large system text without clipping the
time selector or the selected-cell line. Respect reduced motion because the field has no required
animation.

## Loading and errors

Show a small progress indicator inside the **Local rain field** section. Keep the rest of the
weather detail usable while the field loads.

If the field request fails, show **Spatial precipitation unavailable** and a **Retry** action. Do
not replace the whole weather sheet with an error. If some cells are unavailable, render those
cells with the dry outline and a diagonal unavailable mark.

If all 25 cells are dry, keep the grid visible. A dry field is useful information, not an empty
state.

## Privacy, licence, and release gate

The multi-location request sends 25 nearby coordinates to the configured weather service when the
user opens **Weather details**. Update the privacy policy and Google Play data disclosure before a
release that contains the feature.

Open-Meteo Free remains limited to non-commercial development. Do not enable production ads,
paid products, or a public Play rollout while this request uses the Free endpoint. Production
requires a licensed backend, self-hosted service, or the direct official-data feed.

Do not send a Meteoblue API request. Do not include Meteoblue assets, names, or screenshots in the
application.

## Files

Create these focused files:

- `app/src/main/java/cz/majkey/pocasicesko/data/PrecipitationFieldModels.kt`;
- `app/src/main/java/cz/majkey/pocasicesko/data/PrecipitationFieldParser.kt`;
- `app/src/main/java/cz/majkey/pocasicesko/data/PrecipitationFieldRepository.kt`;
- `app/src/main/java/cz/majkey/pocasicesko/ui/LocalRainField.kt`.

Modify these existing files:

- `app/src/main/java/cz/majkey/pocasicesko/data/WeatherModels.kt`;
- `app/src/main/java/cz/majkey/pocasicesko/data/WeatherParser.kt`;
- `app/src/main/java/cz/majkey/pocasicesko/data/WeatherRepository.kt`;
- `app/src/main/java/cz/majkey/pocasicesko/ui/WeatherDetailScreen.kt`;
- all five `strings.xml` files;
- the privacy, Play submission, README, and changelog documents.

Add one focused test file per new production file. Extend existing parser and locale tests instead
of creating duplicate test helpers.

## Verification

Follow red, green, and refactor for every production change. Required automated cases are:

- 25 deterministic coordinates at the equator, the date line, and near both poles;
- exact 5 by 5 row and column order;
- multi-location response validation;
- mismatched timestamps and units;
- negative, missing, and non-finite values;
- three-model minimum and missing-model fallback;
- median, probability, agreement, spread, and precipitation type;
- dry, unavailable, rain, snow, and mixed colour states;
- Metric and Imperial formatting;
- complete localization keys.

Run the full Android unit suite, lint, debug APK, minified release APK, and release AAB. Install the
debug build on the authorised Huawei. Verify these live scenarios:

1. A dry 24-hour field.
2. A local shower that misses the centre point.
3. Widespread rain that covers the centre point.
4. A snow or mixed field when the source data provides one.
5. One unavailable model and one unavailable cell.
6. A failed field request with a successful central forecast.
7. Large text, English, Czech, Metric, and Imperial display.

## Release boundary

This feature can merge as an unreleased, non-commercial development path after tests and Huawei
QA pass. It cannot trigger a GitHub release or Google Play upload until the forecast calibration
and commercial data-service gates pass.

## References

- [Meteoblue rainSPOT explanation](https://content.meteoblue.com/en/private-customers/website-help/7-day-weather/rainspot)
- [Meteoblue weather variable reference](https://docs.meteoblue.com/en/meteo/variables/weather-variables)
- [Open-Meteo forecast API](https://open-meteo.com/en/docs)
- [Open-Meteo terms](https://open-meteo.com/en/terms)
