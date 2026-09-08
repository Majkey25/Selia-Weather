/* Open-Meteo hourly precipitation is the preceding-hour sum, not observed radar.
 * https://open-meteo.com/en/docs. Sampling/interpolation does not add model resolution. */
var RadarForecast = (function() {
  var SIZE = 7;
  function mercator(lat) { return Math.log(Math.tan(Math.PI / 4 + lat * Math.PI / 360)); }
  function latitude(y) { return (2 * Math.atan(Math.exp(y)) - Math.PI / 2) * 180 / Math.PI; }
  function longitude(lon) { return ((lon + 180) % 360 + 360) % 360 - 180; }

  function grid(lat, lon, width, height) {
    if (![lat, lon, width, height].every(Number.isFinite) || width <= 0 || height <= 0) {
      throw new Error('Invalid forecast area');
    }
    lat = Math.max(-80, Math.min(80, lat));
    width = Math.max(1, Math.min(12, width));
    height = Math.max(1, Math.min(12, height));
    var south = Math.max(-85, lat - height / 2), north = Math.min(85, lat + height / 2);
    var west = lon - width / 2, east = lon + width / 2;
    var top = mercator(north), bottom = mercator(south);
    var points = [];
    for (var row = 0; row < SIZE; row++) {
      for (var column = 0; column < SIZE; column++) {
        points.push({ lat: latitude(top + (bottom - top) * row / (SIZE - 1)),
          lon: longitude(west + width * column / (SIZE - 1)) });
      }
    }
    return { points: points, south: south, north: north, west: west, east: east,
      spacingKm: Math.round(Math.max(height * 111, width * 111 * Math.cos(lat * Math.PI / 180)) / (SIZE - 1)) };
  }

  function url(area) {
    return 'https://api.open-meteo.com/v1/forecast?' + new URLSearchParams({
      latitude: area.points.map(function(point) { return point.lat.toFixed(5); }).join(','),
      longitude: area.points.map(function(point) { return point.lon.toFixed(5); }).join(','),
      hourly: 'precipitation', forecast_hours: '15', timezone: 'GMT', timeformat: 'unixtime',
      precipitation_unit: 'mm', cell_selection: 'nearest'
    }).toString();
  }

  function parse(payload, area, now) {
    if (!Array.isArray(payload) || payload.length !== SIZE * SIZE) throw new Error('Incomplete forecast area');
    var times = payload[0] && payload[0].hourly && payload[0].hourly.time;
    if (!Array.isArray(times) || times.length !== 15 || !times.every(function(time, index) {
      return Number.isInteger(time) && time % 3600 === 0 && (!index || time === times[index - 1] + 3600);
    }) || Math.abs(times[0] - Math.floor(now / 3600) * 3600) > 3600) throw new Error('Invalid forecast times');
    payload.forEach(function(point, index) {
      var requested = area.points[index];
      if (!point || point.utc_offset_seconds !== 0 || !point.hourly_units ||
          point.hourly_units.precipitation !== 'mm' || point.hourly_units.time !== 'unixtime' ||
          !Number.isFinite(point.latitude) || !Number.isFinite(point.longitude) ||
          Math.abs(point.latitude - requested.lat) > 1 ||
          Math.abs(longitude(point.longitude - requested.lon)) * Math.cos(requested.lat * Math.PI / 180) > 1 ||
          (point.location_id !== undefined && point.location_id !== index) || !point.hourly ||
          !Array.isArray(point.hourly.time) || point.hourly.time.length !== times.length ||
          !point.hourly.time.every(function(time, i) { return time === times[i]; }) ||
          !Array.isArray(point.hourly.precipitation) || point.hourly.precipitation.length !== times.length ||
          !point.hourly.precipitation.every(function(value) {
            return value === null || (Number.isFinite(value) && value >= 0 && value <= 1000);
          })) throw new Error('Invalid forecast point');
    });
    var frames = [];
    times.forEach(function(time, index) {
      if (time > now) frames.push({ type: 'forecast', time: time,
        values: payload.map(function(point) { return point.hourly.precipitation[index]; }) });
    });
    if (frames.length < 13 || frames[frames.length - 1].time < now + 12 * 3600) {
      throw new Error('Forecast does not cover the next 12 hours');
    }
    return frames;
  }

  function image(frame) {
    var canvas = document.createElement('canvas');
    canvas.width = canvas.height = 128;
    var context = canvas.getContext('2d'), pixels = context.createImageData(128, 128);
    for (var y = 0; y < 128; y++) {
      for (var x = 0; x < 128; x++) {
        var gx = x / 127 * (SIZE - 1), gy = y / 127 * (SIZE - 1);
        var left = Math.min(SIZE - 2, Math.floor(gx)), top = Math.min(SIZE - 2, Math.floor(gy));
        var fx = gx - left, fy = gy - top;
        var values = [frame.values[top * SIZE + left], frame.values[top * SIZE + left + 1],
          frame.values[(top + 1) * SIZE + left], frame.values[(top + 1) * SIZE + left + 1]];
        var i = (y * 128 + x) * 4;
        if (values.some(function(value) { return value === null; })) {
          pixels.data[i] = pixels.data[i + 1] = pixels.data[i + 2] = 160;
          pixels.data[i + 3] = (x + y) % 12 < 3 ? 160 : 50;
        } else {
          var rain = values[0] * (1 - fx) * (1 - fy) + values[1] * fx * (1 - fy) +
            values[2] * (1 - fx) * fy + values[3] * fx * fy;
          var color = rain >= 10 ? [218, 80, 146] : rain >= 5 ? [161, 104, 227] :
            rain >= 2 ? [65, 105, 225] : rain >= 0.5 ? [45, 157, 221] : [87, 215, 224];
          pixels.data[i] = color[0]; pixels.data[i + 1] = color[1]; pixels.data[i + 2] = color[2];
          pixels.data[i + 3] = rain < 0.1 ? 0 : Math.min(220, 70 + Math.log1p(rain) * 55);
        }
      }
    }
    context.putImageData(pixels, 0, 0);
    return canvas.toDataURL('image/png');
  }
  return { grid: grid, url: url, parse: parse, image: image };
})();
