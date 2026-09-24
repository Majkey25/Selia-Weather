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

function runtime(response = manifest(), search = '?lat=50&lon=14') {
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
  function layer(url, options) {
    const result = { url, options, events: {}, on(name, fn) { this.events[name] = fn; return this; },
      addTo() { return this; }, setOpacity(value) { this.opacity = value; return this; } };
    layers.push(result);
    return result;
  }
  layer.wms = layer;
  const context = vm.createContext({
    document, URLSearchParams, AbortController, TextDecoder, Date, console,
    // Browser XML parsing is covered by live browser QA; these are WMS DOM fixtures.
    DOMParser: class { parseFromString(value) {
      const fixture = JSON.parse(value);
      return { getElementsByTagNameNS(_namespace, name) {
        return (fixture[name] || []).map(node => ({ textContent: node.text || '',
          getAttribute: key => node[key] ?? null }));
      } };
    } },
    window: { location: { search }, innerHeight: 480,
      addEventListener(name, callback) { events[name] = callback; } },
    setTimeout(callback, delay) { timeouts.set(++timerId, callback); timeoutDelays.set(timerId, delay); return timerId; },
    clearTimeout(id) { timeouts.delete(id); timeoutDelays.delete(id); },
    setInterval(callback) { intervals.set(++timerId, callback); return timerId; },
    clearInterval(id) { intervals.delete(id); },
    requestAnimationFrame(callback) { callback(); },
    ResizeObserver: class { constructor(callback) { events.mapResize = callback; } observe(element) { events.observedMap = element; } },
    fetch: async () => ({ ok: true, json: async () => response }),
    L: {
      map: () => map,
      tileLayer: layer, imageOverlay: layer, rectangle: layer, circleMarker: layer,
    },
  });
  vm.runInContext(readFileSync(join(__dirname, '../../main/assets/radar-forecast.js'), 'utf8'), context);
  vm.runInContext(script, context);
  return { context, document, events, layers, timeouts, timeoutDelays, intervals, removed, node: id => document.getElementById(id) };
}
const settled = () => new Promise(resolve => setImmediate(resolve));

function scrubTimeline(app, values) {
  const input = html.match(/id="slider"[^>]*oninput="([^"]+)"/)[1];
  for (const value of values) {
    app.node('slider').value = value;
    vm.runInContext(`(function() { ${input} }).call(document.getElementById('slider'))`, app.context);
  }
}

test('rapid timeline input loads only the final selection and retains the displayed frame', async () => {
  const app = runtime();
  await settled();
  const displayed = app.context.pendingLayer;
  displayed.events.load();
  const count = app.layers.length;
  scrubTimeline(app, [0, 1, 0, 1, 0]);
  assert.equal(app.layers.length, count, 'dragging must not create discarded tile layers');
  assert.equal(app.context.radarLayer, displayed);
  assert.ok(app.timeoutDelays.get(app.context.seekTimer) <= 150);
  app.timeouts.get(app.context.seekTimer)();
  assert.equal(app.context.pendingLayer.frameIndex, 0);
  assert.equal(app.context.seekTimer, null);
  app.context.pendingLayer.events.load();
  assert.equal(app.context.frameIndex, 0);
});

test('backgrounding and mode changes cancel queued timeline work', async () => {
  const app = runtime();
  await settled();
  scrubTimeline(app, [0]);
  const hiddenTimer = app.context.seekTimer;
  app.document.hidden = true;
  app.events.visibilitychange();
  assert.equal(app.timeouts.has(hiddenTimer), false);
  assert.equal(app.context.seekTimer, null);
  app.document.hidden = false;
  scrubTimeline(app, [1]);
  const modeTimer = app.context.seekTimer;
  app.context.setMode('forecast');
  assert.equal(app.timeouts.has(modeTimer), false);
  assert.equal(app.context.seekTimer, null);
});

test('a superseded loading frame cannot snap the slider back during a new seek', async () => {
  const app = runtime();
  await settled();
  const displayed = app.context.pendingLayer;
  displayed.events.load();
  app.context.showFrame(0);
  const superseded = app.context.pendingLayer;
  scrubTimeline(app, [1]);
  superseded.events.load();
  assert.equal(app.context.radarLayer, displayed);
  assert.equal(Number(app.node('slider').value), 1);
  assert.ok(app.removed.includes(superseded));
});

for (const committed of [false, true]) {
  test(`a queued seek resumes after backgrounding with initial frame committed=${committed}`, async () => {
    const app = runtime();
    await settled();
    if (committed) app.context.pendingLayer.events.load();
    scrubTimeline(app, [0]);
    app.document.hidden = true;
    app.events.visibilitychange();
    app.document.hidden = false;
    app.events.visibilitychange();
    assert.ok(app.context.pendingLayer, 'the canceled render must restart on resume');
    assert.equal(app.context.pendingLayer.frameIndex, 0);
    app.context.pendingLayer.events.load();
    assert.equal(app.context.frameIndex, 0);
    assert.equal(Number(app.node('slider').value), 0);
    assert.equal(app.node('status').hidden, true);
  });
}

test('play starts from the queued slider selection without an old seek firing later', async () => {
  const app = runtime();
  await settled();
  app.context.pendingLayer.events.load();
  app.context.preloadLayer.events.load();
  scrubTimeline(app, [0]);
  const queued = app.context.seekTimer;
  app.context.togglePlay();
  assert.equal(app.context.frameIndex, 0);
  assert.equal(app.timeouts.has(queued), false);
  assert.equal(app.context.seekTimer, null);
  assert.equal(app.node('play')['aria-pressed'], 'true');
});

test('forecast intervals identify both days when an accumulation crosses midnight', () => {
  const app = runtime(manifest(), '?lang=en&tz=America%2FNew_York');
  const overnight = Date.parse('2026-09-23T06:00:00Z') / 1000;
  assert.match(app.context.forecastIntervalLabel({ time: overnight, hours: 6 }), /Tue 20:00–Wed 02:00/);
  assert.match(app.context.forecastIntervalLabel({ time: overnight + 21600, hours: 6 }), /^Wed 02:00–08:00/);
});

test('forecast provider uses official WMS grids instead of sampled point interpolation', () => {
  const app = runtime();
  assert.equal(app.context.RadarForecast.endpoint, 'https://maps.dwd.de/geoserver/wms');
  assert.equal(app.context.RadarForecast.sources.eu.hours, 1);
  assert.equal(app.context.RadarForecast.sources.global.hours, 6);
  assert.equal(app.context.RadarForecast.grid, undefined);
  assert.doesNotMatch(html, /api\.open-meteo\.com/);
});

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

function forecastPayload(app, key = 'eu') {
  const run = Math.floor(app.context.Date.now() / 21600000) * 21600000;
  const iso = milliseconds => new Date(milliseconds).toISOString();
  const source = app.context.RadarForecast.sources[key];
  return JSON.stringify({
    Name: [{ text: source.layer }, { text: source.style }],
    Dimension: [
      { name: 'time', units: 'ISO8601', text: `${iso(run - 86400000)}/${iso(run + 3 * 86400000)}/PT${key === 'eu' ? 1 : 3}H` },
      { name: 'REFERENCE_TIME', units: 'ISO8601', default: iso(run), text: iso(run) },
    ],
    BoundingBox: [{ CRS: 'EPSG:4326', minx: key === 'eu' ? '29.46875' : '-90',
      miny: key === 'eu' ? '-23.53125' : '-180', maxx: key === 'eu' ? '70.53125' : '90',
      maxy: key === 'eu' ? '62.53125' : '180' }],
  });
}

function forecastResponse(app, url) {
  return new Response(forecastPayload(app, url.includes('Icon-eu_') ? 'eu' : 'global'));
}

test('forecast metadata downloads stop above 128 KiB and decode valid UTF-8', async () => {
  const app = runtime();
  const read = app.context.RadarForecast.read;
  assert.equal(typeof read, 'function');
  assert.equal(await read(new Response('Niederschläge')), 'Niederschläge');
  await assert.rejects(read(new Response('x'.repeat(131073))), /too large/);
});

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

test('model forecast loads two metadata documents and reuses bounded caches on mode switch', async () => {
  const app = runtime();
  await settled();
  let modelCalls = 0;
  app.context.fetch = async url => {
    if (url.includes('maps.dwd.de')) {
      assert.equal(new URL(url).searchParams.get('request'), 'GetCapabilities');
      modelCalls++; return forecastResponse(app, url);
    }
    return { ok: true, json: async () => manifest() };
  };
  app.context.setMode('forecast');
  await settled();
  assert.equal(modelCalls, 2);
  assert.ok(app.context.frames.length >= 13);
  assert.ok(app.context.frames.every(frame => frame.type === 'forecast'));
  app.context.pendingLayer.events.load();
  assert.match(app.node('time').textContent, /\d\d:\d\d–\d\d:\d\d UTC/);
  assert.match(app.node('legend').textContent, /precipitation totals/);
  assert.match(app.node('scale').src, /GetLegendGraphic/);
  assert.equal(app.context.radarLayer.options.layers, 'dwd:Icon-eu_reg00625_fd_sl_TOTPREC01H');
  assert.equal(app.context.radarLayer.options.dim_reference_time, app.context.frames[0].reference);
  assert.match(app.node('frame-summary').textContent, /ICON-EU.*mm \/ 1 h/);
  assert.equal(app.node('coverage').hidden, true);
  app.context.setMode('observed');
  await settled();
  app.context.setMode('forecast');
  await settled();
  assert.equal(modelCalls, 2);
});

test('forecast cancellation ignores late responses after switching back to observed', async () => {
  const app = runtime();
  await settled();
  const finishes = [], signals = [];
  app.context.fetch = (url, options) => {
    if (!url.includes('maps.dwd.de')) return Promise.resolve({ ok: true, json: async () => manifest() });
    signals.push(options.signal);
    return new Promise(resolve => { finishes.push(() => resolve(forecastResponse(app, url))); });
  };
  app.context.setMode('forecast');
  app.context.setMode('observed');
  finishes.forEach(finish => finish());
  await settled();
  assert.ok(signals.every(signal => signal.aborted));
  assert.equal(app.context.mode, 'observed');
  assert.ok(app.context.frames.every(frame => !frame.type));
  assert.match(app.node('source').textContent, /RainViewer/);
});

test('429 forecast requests do not retry on refresh or mode switches', async () => {
  const app = runtime();
  await settled();
  let modelCalls = 0;
  app.context.fetch = async url => {
    if (!url.includes('maps.dwd.de')) return { ok: true, json: async () => manifest() };
    modelCalls++; return { ok: false, status: 429 };
  };
  app.context.setMode('forecast');
  await settled();
  assert.equal(app.node('play').disabled, true);
  app.context.refreshMap();
  assert.equal(modelCalls, 2);
  const later = Date.now() + 61000;
  app.context.Date = class extends Date { static now() { return later; } };
  app.context.setMode('observed');
  await settled();
  app.context.setMode('forecast');
  assert.equal(modelCalls, 2);
  assert.equal(app.context.frames.length, 0);
  assert.equal(app.node('slider').disabled, true);
});

test('stale runs, invalid layers, dimensions, bounds and short horizons fail closed', () => {
  const app = runtime();
  for (const corrupt of [
    payload => { payload.Name[0].text = 'untrusted'; },
    payload => { payload.Dimension[0].units = 'hours'; },
    payload => { payload.Dimension[0].text = 'invalid'; },
    payload => { payload.Dimension[1].default = new Date((now - 86400) * 1000).toISOString(); },
    payload => { payload.Dimension[1].default = new Date((now + 3600) * 1000).toISOString(); },
    payload => { payload.Dimension[1].text = ''; },
    payload => { payload.Dimension[0].text = payload.Dimension[0].text.replace(/\/[^/]+\/PT/, `/${new Date((now + 3600) * 1000).toISOString()}/PT`); },
    payload => { payload.BoundingBox[0].minx = 'NaN'; },
    payload => { payload.BoundingBox[0].maxx = '-90'; },
    payload => { payload.parsererror = [{ text: 'Bad XML' }]; },
  ]) {
    const payload = JSON.parse(forecastPayload(app));
    corrupt(payload);
    assert.throws(() => app.context.RadarForecast.parse(JSON.stringify(payload), 'eu', now));
  }
  assert.throws(() => app.context.RadarForecast.parse('x'.repeat(131073), 'eu', now));
  assert.throws(() => app.context.RadarForecast.parse('<!DOCTYPE x>', 'eu', now));
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
  app.context.fetch = async url => forecastResponse(app, url);
  app.context.refreshMap();
  await settled();
  assert.equal(app.node('play').disabled, false);
  assert.ok(app.context.frames.length >= 13);
});

test('panning switches Europe to global and back immediately without requesting point forecasts', async () => {
  const app = runtime();
  await settled();
  let calls = 0;
  app.context.fetch = async url => { calls++; return forecastResponse(app, url); };
  app.context.setMode('forecast');
  await settled();
  app.context.pendingLayer.events.load();
  const europe = app.context.forecastCache;
  const originalBounds = app.context.map.getBounds;
  app.context.map.getBounds = () => ({ getEast: () => 145, getWest: () => 135, getNorth: () => 40, getSouth: () => 30 });
  app.events.resize();
  app.events.moveend();
  assert.equal(calls, 2);
  assert.equal(app.context.forecastCache.key, 'global');
  assert.equal(app.context.pendingLayer.options.layers, 'dwd:Icon_reg025_fd_sl_TOTPREC06H');
  app.context.pendingLayer.events.load();
  assert.match(app.node('frame-summary').textContent, /ICON.*mm \/ 6 h/);
  app.context.map.getBounds = originalBounds;
  app.events.moveend();
  assert.equal(app.context.forecastCache, europe);
  app.context.pendingLayer.events.load();
  const displayed = app.context.radarLayer;
  app.events.moveend();
  assert.equal(app.context.radarLayer, displayed, 'panning within the same model retains selected time');
  assert.equal(app.node('status').hidden, true);
  assert.equal(calls, 2);
  app.context.refreshMap();
  assert.notEqual(app.context.pendingLayer, displayed, 'manual refresh retries tiles immediately');
  assert.equal(calls, 2);
});

test('regional selection uses the full viewport and wraps equivalent longitudes', async () => {
  const app = runtime();
  await settled();
  const provider = app.context.RadarForecast;
  const europe = provider.parse(forecastPayload(app), 'eu', now).bounds;
  assert.equal(provider.sourceFor({ west: 11, east: 17, south: 47, north: 53 }, europe), 'eu');
  assert.equal(provider.sourceFor({ west: 371, east: 377, south: 47, north: 53 }, europe), 'eu');
  assert.equal(provider.sourceFor({ west: 11, east: 70, south: 47, north: 53 }, europe), 'global');
  assert.equal(provider.sourceFor({ west: -180, east: 180, south: 47, north: 53 }, europe), 'global');
  assert.equal(provider.sourceFor({ west: 175, east: 190, south: 0, north: 10 }, europe), 'global');
});

test('panning outside available regional coverage fails closed during provider backoff', async () => {
  const app = runtime();
  await settled();
  app.context.fetch = async url => url.includes('Icon-eu_') ? forecastResponse(app, url) : { ok: false, status: 429 };
  app.context.setMode('forecast');
  await settled();
  app.context.pendingLayer.events.load();
  const originalBounds = app.context.map.getBounds;
  app.context.map.getBounds = () => ({ getEast: () => 145, getWest: () => 135, getNorth: () => 40, getSouth: () => 30 });
  app.events.moveend();
  assert.equal(app.context.frames.length, 0);
  assert.equal(app.context.radarLayer, null);
  assert.equal(app.node('play').disabled, true);
  assert.equal(app.node('time').textContent, '—');
  assert.equal(app.node('status').textContent, app.context.FORECAST_TEXT[8]);
  app.context.map.getBounds = originalBounds;
  app.events.moveend();
  assert.equal(app.context.forecastCache.key, 'eu');
  assert.notEqual(app.context.pendingLayer, null);
});

test('visible expired forecast refreshes once on resume, online, play, seek, tick, commit and idle expiry', async () => {
  for (const action of ['resume', 'online', 'play', 'seek', 'tick', 'commit', 'idle']) {
    const app = runtime();
    await settled();
    let calls = 0;
    app.context.fetch = async url => { calls++; return forecastResponse(app, url); };
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
    assert.equal(app.node('status').textContent, app.context.FORECAST_TEXT[2], action);
    assert.equal(calls, 4, 'expiration starts exactly one two-document refresh');
    app.context.ensureForecastFresh();
    assert.equal(calls, 4, 'pending refresh is not duplicated');
    await settled();
    app.context.pendingLayer.events.load();
    assert.equal(app.node('status').textContent, '', action);
    assert.notEqual(app.context.radarLayer, null, action);
  }
});

test('insufficient remaining horizon expires even a recently loaded forecast and failed refresh stays empty', async () => {
  const app = runtime();
  await settled();
  app.context.fetch = async url => forecastResponse(app, url);
  app.context.setMode('forecast');
  await settled();
  const later = app.context.frames.at(-1).time * 1000 - 12 * 3600000 + 1;
  app.context.Date = class extends Date { static now() { return later; } };
  app.context.forecastCache.loadedAt = later;
  let calls = 0;
  app.context.fetch = async () => { calls++; return { ok: false, status: 503 }; };
  app.context.showFrame(1);
  assert.equal(app.context.frames.length, 0);
  assert.equal(app.node('play').disabled, true);
  await settled();
  assert.equal(app.context.frames.length, 0);
  assert.equal(app.node('play').disabled, true);
  assert.equal(app.node('status').textContent, app.context.FORECAST_TEXT[3]);
  app.context.ensureForecastFresh();
  app.events.online();
  app.events.moveend();
  assert.equal(calls, 2, 'a failed automatic refresh has no immediate retry loop');
  assert.equal(app.context.forecastExpiryTimer, null);
  app.context.fetch = async url => forecastResponse(app, url);
  app.context.refreshMap();
  await settled();
  app.context.pendingLayer.events.load();
  assert.equal(app.node('status').textContent, '', 'explicit refresh can recover without a cooldown');
});

test('forecast loaded at hh:59 survives the next hour boundary until its normal age expiry', async () => {
  const app = runtime();
  await settled();
  const loadedAt = Date.UTC(2026, 8, 9, 9, 59);
  let clock = loadedAt, calls = 0;
  app.context.Date = class extends Date { static now() { return clock; } };
  app.context.fetch = async url => {
    calls++;
    return forecastResponse(app, url);
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
  assert.equal(calls, 4, 'the hour boundary keeps the cache; age expiry refreshes it once');
  await settled();
  assert.notEqual(app.context.pendingLayer, null);
});

test('hidden forecast expiry defers its single automatic refresh until visible again', async () => {
  const app = runtime();
  await settled();
  let calls = 0;
  app.context.fetch = async url => { calls++; return forecastResponse(app, url); };
  app.context.setMode('forecast');
  await settled();
  app.context.pendingLayer.events.load();
  app.document.hidden = true;
  app.events.visibilitychange();
  const expiredAt = Date.now() + 600001;
  app.context.Date = class extends Date { static now() { return expiredAt; } };
  app.timeouts.get(app.context.forecastExpiryTimer)();
  assert.equal(app.context.frames.length, 0);
  assert.equal(app.context.forecastExpiryTimer, null);
  app.events.online();
  assert.equal(calls, 2, 'no provider polling while hidden');
  app.document.hidden = false;
  app.events.visibilitychange();
  assert.equal(calls, 4);
  await settled();
  app.context.pendingLayer.events.load();
  assert.equal(app.node('status').textContent, '');
});

test('model run age schedules expiry before the metadata cache TTL', async () => {
  const app = runtime();
  await settled();
  let clock = Date.UTC(2026, 8, 22, 23, 59), calls = 0;
  app.context.Date = class extends Date { static now() { return clock; } };
  app.context.fetch = async url => {
    calls++;
    const payload = JSON.parse(forecastPayload(app, url.includes('Icon-eu_') ? 'eu' : 'global'));
    payload.Dimension[1].default = payload.Dimension[1].text = '2026-09-22T12:00:00.000Z';
    return new Response(JSON.stringify(payload));
  };
  app.context.setMode('forecast');
  await settled();
  app.context.pendingLayer.events.load();
  assert.equal(app.timeoutDelays.get(app.context.forecastExpiryTimer), 60001);
  clock += 60001;
  app.timeouts.get(app.context.forecastExpiryTimer)();
  await settled();
  assert.equal(calls, 4);
  assert.equal(app.context.frames.length, 0, 'a still-old provider run fails closed after refresh');
  assert.equal(app.context.forecastExpiryTimer, null);
  assert.equal(app.node('status').textContent, app.context.FORECAST_TEXT[3]);
});

test('loading new tiles cannot erase a retained tile error on the same layer', async () => {
  const app = runtime();
  await settled();
  const layer = app.context.pendingLayer;
  layer.events.load();
  layer.events.loading();
  layer.events.tileerror();
  layer.events.load();
  assert.equal(layer.failed, true);
  layer.events.loading();
  layer.events.load();
  assert.equal(layer.failed, true);
  assert.equal(layer.ready, false);
  assert.equal(app.node('status').textContent, app.context.TEXT.unavailable);
  app.context.showFrame(app.context.frameIndex);
  app.context.pendingLayer.events.load();
  assert.equal(app.node('status').textContent, '');
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

test('global frames use six-hour totals and every model covers at least the next 12 hours', () => {
  const app = runtime();
  for (const key of ['eu', 'global']) {
    const parsed = app.context.RadarForecast.parse(forecastPayload(app, key), key, now);
    const step = key === 'eu' ? 3600 : 21600;
    assert.ok(parsed.frames.length >= 3 && parsed.frames.length <= 15);
    assert.ok(parsed.frames[0].time > now && parsed.frames[0].time <= now + step);
    assert.ok(parsed.frames.at(-1).time >= now + 12 * 3600 + 600);
    assert.ok(parsed.frames.every((frame, index) => frame.hours * 3600 === step &&
      frame.time % step === 0 && (!index || frame.time === parsed.frames[index - 1].time + step)));
  }
});

test('forecast timeline exposes its whole range and mode changes clear the old range', async () => {
  const app = runtime();
  await settled();
  app.context.fetch = async url => forecastResponse(app, url);
  app.context.setMode('forecast');
  await settled();
  app.context.pendingLayer.events.load();
  assert.notEqual(app.node('range-start').textContent, undefined);
  assert.notEqual(app.node('range-end').textContent, undefined);
  assert.notEqual(app.node('range-start').textContent, app.node('range-end').textContent);
  app.context.fetch = async () => ({ ok: false, status: 503 });
  app.context.setMode('observed');
  await settled();
  assert.equal(app.node('range-start').textContent, '—');
  assert.equal(app.node('range-end').textContent, '—');
});

test('successful model metadata never reports tile failures as a dry forecast', async () => {
  const app = runtime();
  await settled();
  app.context.fetch = async url => forecastResponse(app, url);
  app.context.setMode('forecast');
  await settled();
  const layer = app.context.pendingLayer;
  layer.events.tileerror();
  layer.events.load();
  assert.equal(app.context.radarLayer, null);
  assert.equal(app.node('status').textContent, app.context.FORECAST_TEXT[3]);
  assert.equal(app.node('time').textContent, '—');
});

test('a displayed forecast that hangs while panning leaves loading with an explicit failure', async () => {
  const app = runtime();
  await settled();
  app.context.fetch = async url => forecastResponse(app, url);
  app.context.setMode('forecast');
  await settled();
  const layer = app.context.pendingLayer;
  layer.events.load();
  layer.events.loading();
  const timeout = [...app.timeouts].find(([id]) => app.timeoutDelays.get(id) === app.context.REQUEST_TIMEOUT_MS);
  assert.ok(timeout, 'visible panned tiles need a deadline');
  timeout[1]();
  layer.events.load();
  assert.equal(app.node('status').textContent, app.context.FORECAST_TEXT[3]);
  app.context.refreshMap();
  assert.notEqual(app.context.pendingLayer, layer);
});

test('radar timeline uses the selected location timezone and invalid zones fall back to UTC', () => {
  const local = runtime(manifest(), '?lang=en&tz=Europe%2FPrague');
  assert.match(local.context.formatTime(Date.UTC(2026, 8, 19, 11) / 1000), /13:00/);
  const invalid = runtime(manifest(), '?tz=not-a-zone');
  assert.match(invalid.context.formatTime(Date.UTC(2026, 8, 19, 11) / 1000), /11:00/);
});

test('panel layout changes invalidate the Leaflet viewport without requesting weather again', async () => {
  const app = runtime();
  await settled();
  assert.equal(app.events.observedMap, app.node('map'));
  const before = app.events.invalidations;
  app.context.fetch = () => { throw new Error('Layout must not request weather'); };
  app.events.mapResize();
  assert.equal(app.events.invalidations, before + 1);
});
