const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const html = readFileSync(join(__dirname, '../../main/assets/radar.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const now = Math.floor(Date.now() / 1000);
const manifest = () => ({ host: 'https://tilecache.rainviewer.com', radar: { past: [
  { time: now - 1200, path: '/v2/radar/514ebb4b4aef' },
  { time: now - 600, path: '/v2/radar/fda4d81e26c8' },
] } });

function runtime(response = manifest()) {
  const nodes = new Map();
  const events = {};
  const layers = [];
  const timeouts = new Map();
  const timeoutDelays = new Map();
  const intervals = new Map();
  let timerId = 0;
  const document = {
    hidden: false,
    documentElement: { style: {} },
    body: { style: {} },
    getElementById(id) {
      if (!nodes.has(id)) nodes.set(id, { style: {}, setAttribute(key, value) { this[key] = value; } });
      return nodes.get(id);
    },
    addEventListener(name, callback) { events[name] = callback; },
    createElement() { return { getContext() { return {
      createImageData: (width, height) => ({ data: new Uint8ClampedArray(width * height * 4) }),
      putImageData() {},
    }; }, toDataURL() { return 'data:image/png;base64,placeholder'; } }; },
  };
  const removed = [];
  const map = { setView() { return this; }, setMaxZoom() {}, removeLayer(layer) { removed.push(layer); },
    invalidateSize() { events.invalidations = (events.invalidations || 0) + 1; },
    on(name, callback) { events[name] = callback; return this; },
    getCenter() { return { lat: 50, lng: 14 }; }, getZoom() { return 6; },
    getBounds() { return { getEast: () => 17, getWest: () => 11, getNorth: () => 53, getSouth: () => 47 }; } };
  function layer(url) {
    const result = { url, events: {}, on(name, fn) { this.events[name] = fn; return this; },
      addTo() { return this; }, setOpacity(value) { this.opacity = value; return this; } };
    layers.push(result);
    return result;
  }
  const context = vm.createContext({
    document, URLSearchParams, AbortController, Date, console,
    window: { location: { search: '?lat=50&lon=14' }, innerHeight: 480,
      addEventListener(name, callback) { events[name] = callback; } },
    setTimeout(callback, delay) { timeouts.set(++timerId, callback); timeoutDelays.set(timerId, delay); return timerId; },
    clearTimeout(id) { timeouts.delete(id); timeoutDelays.delete(id); },
    setInterval(callback) { intervals.set(++timerId, callback); return timerId; },
    clearInterval(id) { intervals.delete(id); },
    requestAnimationFrame(callback) { callback(); },
    fetch: async () => ({ ok: true, json: async () => response }),
    L: {
      map: () => map,
      tileLayer: layer, imageOverlay: layer, rectangle: layer,
    },
  });
  vm.runInContext(readFileSync(join(__dirname, '../../main/assets/radar-forecast.js'), 'utf8'), context);
  vm.runInContext(script, context);
  return { context, document, events, layers, timeouts, timeoutDelays, intervals, removed, node: id => document.getElementById(id) };
}
const settled = () => new Promise(resolve => setImmediate(resolve));

test('radar controls are outside the flex map with stable status space and 48px hit areas', () => {
  assert.match(html, /#map\s*\{[^}]*flex:\s*1[^}]*min-height:\s*0/);
  assert.doesNotMatch(html, /\.panel\s*\{[^}]*position:\s*fixed/);
  assert.doesNotMatch(html, /\.status\s*\{[^}]*position:\s*fixed/);
  assert.match(html, /button\s*\{[^}]*min-width:\s*48px[^}]*min-height:\s*48px/);
  assert.match(html, /\.status\[hidden\]\s*\{[^}]*visibility:\s*hidden/);
  assert.ok(html.indexOf('id="status"') > html.indexOf('class="panel"'));
  assert.doesNotMatch(script, /getElementById\('map'\)\.style\.height\s*=/);
});

test('Android viewport sizing gives the root height without forcing map height or fetching data', async () => {
  const app = runtime();
  await settled();
  assert.equal(app.document.documentElement.style.height, '480px');
  assert.equal(app.document.body.style.height, '480px');
  app.context.fetch = () => { throw new Error('Resize must not fetch'); };
  app.context.window.innerHeight = 720;
  app.events.resize();
  assert.equal(app.document.body.style.height, '720px');
  assert.equal(app.node('map').style.height, undefined);
  app.context.window.innerHeight = 0;
  app.events.resize();
  assert.equal(app.document.body.style.height, '720px');
});

test('details toggle resizes the map explicitly without any weather request', async () => {
  const app = runtime();
  await settled();
  app.node('details').hidden = true;
  let calls = 0;
  app.context.fetch = async () => { calls++; throw new Error('Unexpected request'); };
  app.context.toggleDetails();
  assert.equal(app.node('details').hidden, false);
  assert.equal(app.node('details-toggle')['aria-expanded'], 'true');
  app.context.toggleDetails();
  assert.equal(app.node('details').hidden, true);
  assert.equal(app.node('details-toggle')['aria-expanded'], 'false');
  assert.equal(app.events.invalidations, 3);
  assert.equal(calls, 0);
});

test('live manifest enables timeline and a failed tile stays visibly failed after load', async () => {
  const app = runtime();
  await settled();
  assert.equal(app.node('slider').disabled, false);
  assert.equal(app.node('slider').max, 1);
  const layer = app.layers.at(-1);
  layer.events.loading();
  layer.events.tileerror();
  layer.events.load();
  assert.equal(app.node('status').hidden, false);
  assert.equal(app.node('status').textContent, app.context.TEXT.unavailable);
  app.context.showFrame(0);
  app.context.pendingLayer.events.load();
  layer.events.tileerror();
  assert.equal(app.node('status').hidden, true, 'retired layer cannot change current status');
});

function forecastPayload(app, value = 1) {
  const first = Math.floor(app.context.Date.now() / 3600000) * 3600;
  return app.context.RadarForecast.grid(50, 14, 6, 6).points.map((point, location_id) => ({
    location_id, latitude: point.lat, longitude: point.lon, utc_offset_seconds: 0,
    hourly_units: { time: 'unixtime', precipitation: 'mm' },
    hourly: { time: Array.from({ length: 15 }, (_, i) => first + i * 3600),
      precipitation: Array(15).fill(value) },
  }));
}

test('observed transition keeps the old frame until replacement tiles load', async () => {
  const app = runtime();
  await settled();
  const old = app.context.pendingLayer;
  old.events.load();
  app.context.showFrame(0);
  const pending = app.context.pendingLayer;
  assert.equal(app.context.radarLayer, old);
  assert.equal(old.opacity, 0.78);
  assert.equal(app.removed.includes(old), false);
  pending.events.load();
  assert.equal(app.context.radarLayer, pending);
  assert.equal(old.opacity, 0);
  assert.equal(pending.opacity, 0.78);
  assert.equal(app.context.preloadLayer.frameIndex, 1);
});

test('failed replacement and hung tiles retain the last successful frame', async () => {
  const app = runtime();
  await settled();
  const old = app.context.pendingLayer;
  old.events.load();
  app.context.showFrame(0);
  app.context.pendingLayer.events.tileerror();
  assert.equal(app.context.radarLayer, old);
  assert.equal(old.opacity, 0.78);
  assert.equal(app.node('status').hidden, false);
  app.context.showFrame(0);
  for (const callback of [...app.timeouts.values()]) callback();
  assert.equal(app.context.radarLayer, old);
  assert.equal(app.context.pendingLayer, null);
});

test('model forecast loads one batch and reuses its bounded cache on mode switch', async () => {
  const app = runtime();
  await settled();
  let modelCalls = 0;
  app.context.fetch = async url => {
    if (url.includes('open-meteo')) {
      assert.equal(new URL(url).searchParams.get('forecast_hours'), '15');
      modelCalls++; return { ok: true, json: async () => forecastPayload(app) };
    }
    return { ok: true, json: async () => manifest() };
  };
  app.context.setMode('forecast');
  await settled();
  assert.equal(modelCalls, 1);
  assert.equal(app.context.frames.length, 14);
  assert.ok(app.context.frames.every(frame => frame.type === 'forecast'));
  app.context.pendingLayer.events.load();
  assert.match(app.node('time').textContent, /\d\d:\d\d–\d\d:\d\d UTC/);
  assert.match(app.node('legend').textContent, /Sampled\/interpolated/);
  assert.equal(app.node('coverage').hidden, true);
  app.context.setMode('observed');
  await settled();
  app.context.setMode('forecast');
  await settled();
  assert.equal(modelCalls, 1);
});

test('forecast cancellation ignores late responses after switching back to observed', async () => {
  const app = runtime();
  await settled();
  let finish, signal;
  app.context.fetch = (url, options) => {
    if (!url.includes('open-meteo')) return Promise.resolve({ ok: true, json: async () => manifest() });
    signal = options.signal;
    return new Promise(resolve => { finish = resolve; });
  };
  app.context.setMode('forecast');
  app.context.setMode('observed');
  finish({ ok: true, json: async () => forecastPayload(app) });
  await settled();
  assert.equal(signal.aborted, true);
  assert.equal(app.context.mode, 'observed');
  assert.ok(app.context.frames.every(frame => !frame.type));
  assert.match(app.node('source').textContent, /RainViewer/);
});

test('429 forecast requests do not retry on refresh or mode switches', async () => {
  const app = runtime();
  await settled();
  let modelCalls = 0;
  app.context.fetch = async url => {
    if (!url.includes('open-meteo')) return { ok: true, json: async () => manifest() };
    modelCalls++; return { ok: false, status: 429 };
  };
  app.context.setMode('forecast');
  await settled();
  assert.equal(app.node('play').disabled, true);
  app.context.refreshMap();
  assert.equal(modelCalls, 1);
  const later = Date.now() + 61000;
  app.context.Date = class extends Date { static now() { return later; } };
  app.context.setMode('observed');
  await settled();
  app.context.setMode('forecast');
  assert.equal(modelCalls, 1);
  assert.equal(app.context.frames.length, 0);
  assert.equal(app.node('slider').disabled, true);
});

test('stale, reordered, negative and wrong-timezone forecast payloads fail closed', () => {
  const app = runtime();
  const area = app.context.RadarForecast.grid(50, 14, 6, 6);
  for (const corrupt of [
    payload => { payload[0].hourly.time[1] += 60; },
    payload => { payload[0].location_id = 2; },
    payload => { payload[0].hourly.precipitation[1] = -1; },
    payload => { payload[0].utc_offset_seconds = 7200; },
    payload => payload.forEach(point => { point.hourly.time.pop(); point.hourly.precipitation.pop(); }),
    payload => payload.forEach(point => {
      point.hourly.time.push(point.hourly.time.at(-1) + 3600); point.hourly.precipitation.push(1);
    }),
    payload => payload.forEach(point => { point.hourly.time = point.hourly.time.map(time => time - 86400); }),
  ]) {
    const payload = forecastPayload(app);
    corrupt(payload);
    assert.throws(() => app.context.RadarForecast.parse(payload, area, now));
  }
});

test('forecast timeout fails closed and one later explicit retry can recover', async () => {
  const app = runtime();
  await settled();
  app.context.fetch = (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new Error('aborted')));
  });
  app.context.setMode('forecast');
  for (const callback of [...app.timeouts.values()]) callback();
  await settled();
  assert.equal(app.context.activeRequest, null);
  assert.equal(app.node('play').disabled, true);
  assert.equal(app.node('status').textContent, app.context.FORECAST_TEXT[3]);
  const later = Date.now() + 61000;
  app.context.Date = class extends Date { static now() { return later; } };
  app.context.fetch = async () => ({ ok: true, json: async () => forecastPayload(app) });
  app.context.refreshMap();
  await settled();
  assert.equal(app.node('play').disabled, false);
  assert.equal(app.context.frames.length, 14);
});

test('resize and panning do not fetch a new model area or misplace a cached area', async () => {
  const app = runtime();
  await settled();
  let calls = 0;
  app.context.fetch = async () => { calls++; return { ok: true, json: async () => forecastPayload(app) }; };
  app.context.setMode('forecast');
  await settled();
  const original = app.context.forecastArea;
  app.context.map.getCenter = () => ({ lat: 35, lng: 140 });
  app.events.resize();
  assert.equal(calls, 1);
  assert.equal(app.context.forecastArea, original);
  app.context.refreshMap();
  assert.equal(calls, 1, 'explicit area refresh is throttled for 60 seconds');
  assert.equal(app.context.forecastArea, original);
});

test('unloaded forecast area warning survives playback and clears only inside loaded bounds', async () => {
  const app = runtime();
  await settled();
  let calls = 0;
  app.context.fetch = async () => { calls++; return { ok: true, json: async () => forecastPayload(app) }; };
  app.context.setMode('forecast');
  await settled();
  app.context.pendingLayer.events.load();
  app.context.map.getCenter = () => ({ lat: 35, lng: 140 });
  app.events.moveend();
  const warning = app.context.FORECAST_AREA_UNAVAILABLE[app.context.language];
  assert.equal(app.node('status').textContent, warning);
  assert.equal(app.node('status').hidden, false);
  app.context.togglePlay();
  [...app.intervals.values()][0]();
  app.context.pendingLayer.events.load();
  assert.equal(app.node('status').textContent, warning);
  assert.equal(calls, 1);
  app.context.map.getCenter = () => ({ lat: 50, lng: 14 });
  app.events.moveend();
  assert.equal(app.node('status').hidden, true);
  assert.equal(calls, 1);
});

test('loaded forecast bounds recognize equivalent longitudes across the antimeridian', async () => {
  const app = runtime();
  await settled();
  app.context.fetch = async () => ({ ok: true, json: async () => forecastPayload(app) });
  app.context.setMode('forecast');
  await settled();
  app.context.pendingLayer.events.load();
  app.context.forecastArea = app.context.RadarForecast.grid(10, 179, 6, 6);
  for (const longitude of [179, -179, 181, 541]) {
    app.context.map.getCenter = () => ({ lat: 10, lng: longitude });
    app.events.moveend();
    assert.equal(app.node('status').hidden, true, String(longitude));
  }
  for (const center of [{ lat: 10, lng: -170 }, { lat: 20, lng: 179 }]) {
    app.context.map.getCenter = () => center;
    app.events.moveend();
    assert.equal(app.node('status').hidden, false);
  }
});

test('expired forecast stops on resume, online, play, seek, playback tick, pending commit and idle expiry', async () => {
  for (const action of ['resume', 'online', 'play', 'seek', 'tick', 'commit', 'idle']) {
    const app = runtime();
    await settled();
    let calls = 0;
    app.context.fetch = async () => { calls++; return { ok: true, json: async () => forecastPayload(app) }; };
    app.context.setMode('forecast');
    await settled();
    if (action !== 'commit') app.context.pendingLayer.events.load();
    if (action === 'tick') app.context.togglePlay();
    const expiredAt = Date.now() + 600001;
    app.context.Date = class extends Date { static now() { return expiredAt; } };
    if (action === 'resume') app.events.visibilitychange();
    if (action === 'online') app.events.online();
    if (action === 'play') app.context.togglePlay();
    if (action === 'seek') app.context.showFrame(1);
    if (action === 'tick') [...app.intervals.values()][0]();
    if (action === 'commit') app.context.pendingLayer.events.load();
    if (action === 'idle') app.timeouts.get(app.context.forecastExpiryTimer)();
    assert.equal(app.context.timer, null, action);
    assert.equal(app.context.frames.length, 0, action);
    assert.equal(app.context.radarLayer, null, action);
    assert.equal(app.node('play').disabled, true, action);
    assert.equal(app.node('slider').disabled, true, action);
    assert.equal(app.node('time').textContent, '—', action);
    assert.equal(app.node('status').textContent, app.context.FORECAST_EXPIRED[app.context.language], action);
    assert.equal(calls, 1, 'expiration never reloads a panned area implicitly');
  }
});

test('insufficient remaining horizon expires even a recently loaded forecast and failed refresh stays empty', async () => {
  const app = runtime();
  await settled();
  app.context.fetch = async () => ({ ok: true, json: async () => forecastPayload(app) });
  app.context.setMode('forecast');
  await settled();
  const later = app.context.frames.at(-1).time * 1000 - 12 * 3600000 + 1;
  app.context.Date = class extends Date { static now() { return later; } };
  app.context.forecastCache.loadedAt = later;
  app.context.showFrame(1);
  assert.equal(app.context.frames.length, 0);
  assert.equal(app.node('play').disabled, true);
  app.context.fetch = async () => ({ ok: false, status: 503 });
  app.context.lastForecastRequest = later - 60001;
  app.context.refreshMap();
  await settled();
  assert.equal(app.context.frames.length, 0);
  assert.equal(app.node('play').disabled, true);
  assert.equal(app.node('status').textContent, app.context.FORECAST_TEXT[3]);
});

test('forecast loaded at hh:59 survives the next hour boundary until its normal age expiry', async () => {
  const app = runtime();
  await settled();
  const loadedAt = Date.UTC(2026, 8, 9, 9, 59);
  let clock = loadedAt, calls = 0;
  app.context.Date = class extends Date { static now() { return clock; } };
  app.context.fetch = async () => {
    calls++;
    return { ok: true, json: async () => forecastPayload(app) };
  };
  app.context.setMode('forecast');
  await settled();
  app.context.pendingLayer.events.load();
  const cached = app.context.forecastCache;
  assert.equal(app.timeoutDelays.get(app.context.forecastExpiryTimer), 600001);
  clock = Date.UTC(2026, 8, 9, 10, 0, 1);
  app.context.showFrame(1);
  assert.equal(app.context.forecastCache === cached, true);
  assert.equal(app.node('play').disabled, false);
  app.context.pendingLayer.events.load();
  clock = loadedAt + 599999;
  assert.equal(app.context.ensureForecastFresh(), true);
  clock = loadedAt + 600001;
  app.timeouts.get(app.context.forecastExpiryTimer)();
  assert.equal(app.context.frames.length, 0);
  assert.equal(app.node('play').disabled, true);
  assert.equal(calls, 1, 'crossing an hour and age expiry must not request extra data');
});

test('bad host, stale frames and malformed paths never enable playback', async () => {
  for (const payload of [
    { ...manifest(), host: 'https://untrusted.example' },
    { ...manifest(), radar: { past: [{ time: now - 7200, path: '/v2/radar/' + (now - 7200) }] } },
    { ...manifest(), radar: { past: [{ time: now, path: '/unexpected' }] } },
    { ...manifest(), radar: { past: [{ time: now, path: '/v2/radar/../../untrusted' }] } },
  ]) {
    const app = runtime(payload);
    await settled();
    assert.equal(app.node('play').disabled, true);
    assert.equal(app.node('status').textContent, app.context.TEXT.unavailable);
    assert.equal(app.context.frames.length, 0);
  }
});

test('failed fetch can retry successfully and backgrounding stops playback', async () => {
  const app = runtime(null);
  await settled();
  app.context.fetch = async () => ({ ok: true, json: async () => manifest() });
  app.context.loadRadar();
  await settled();
  assert.equal(app.node('play').disabled, false);
  app.context.pendingLayer.events.load();
  app.context.togglePlay();
  assert.notEqual(app.context.timer, null);
  app.document.hidden = true;
  app.events.visibilitychange();
  assert.equal(app.context.timer, null);
  assert.equal(app.timeouts.size, 0);
  assert.equal(app.context.coordinate(null, -90, 90, 50), 50);
  assert.equal(app.context.coordinate('0', -90, 90, 50), 0);
  app.context.showFrame(NaN);
  assert.equal(app.context.frameIndex, 1);
});

test('hung request aborts and exits loading with disabled controls', async () => {
  const app = runtime();
  await settled();
  app.context.fetch = (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new Error('aborted')));
  });
  app.context.loadRadar();
  for (const callback of app.timeouts.values()) callback();
  await settled();
  assert.equal(app.node('play').disabled, true);
  assert.equal(app.node('status').textContent, app.context.TEXT.unavailable);
  assert.equal(app.timeouts.size, 0);
});

test('forecast grid crosses the dateline locally and spaces rows in Web Mercator', () => {
  const app = runtime();
  const grid = app.context.RadarForecast.grid(70, 179, 12, 12);
  assert.equal(grid.points.length, 49);
  assert.ok(grid.points.every(point => point.lon >= -180 && point.lon <= 180));
  assert.ok(grid.east - grid.west <= 12);
  const middle = grid.points[24].lat;
  assert.ok(middle > (grid.south + grid.north) / 2);
  assert.ok(grid.north <= 85);
});

test('forecast parser preserves hourly nulls and provides at least 12 hours ahead', () => {
  const app = runtime();
  const grid = app.context.RadarForecast.grid(50, 14, 6, 6);
  const first = Math.floor(now / 3600) * 3600;
  const payload = grid.points.map(point => ({
    latitude: point.lat, longitude: point.lon, utc_offset_seconds: 0,
    hourly_units: { time: 'unixtime', precipitation: 'mm' },
    hourly: { time: Array.from({ length: 15 }, (_, i) => first + i * 3600),
      precipitation: [0, null, ...Array(13).fill(1)] },
  }));
  const frames = app.context.RadarForecast.parse(payload, grid, now);
  assert.equal(frames.length, 14);
  assert.equal(frames[0].values[0], null);
  assert.ok(frames.at(-1).time >= now + 12 * 3600);
  payload[0].hourly_units.precipitation = 'inch';
  assert.throws(() => app.context.RadarForecast.parse(payload, grid, now));
});
