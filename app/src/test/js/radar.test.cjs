const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');
const assets = join(__dirname, '../../main/assets');
const html = readFileSync(join(assets, 'radar.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const NOW = Date.UTC(2026, 9, 11, 10, 15);
const manifest = (time = NOW / 1000) => ({ host: 'https://tilecache.rainviewer.com', radar: { past: [
  { time: time - 1200, path: '/v2/radar/514ebb4b4aef' }, { time: time - 600, path: '/v2/radar/fda4d81e26c8' },
] } });
function forecastPayload(context, key = 'eu') {
  const run = Math.floor(context.Date.now() / 21600000) * 21600000, iso = ms => new Date(ms).toISOString();
  const source = context.RadarForecast.sources[key];
  return JSON.stringify({ Name: [{ text: source.layer }, { text: source.style }], Dimension: [
    { name: 'time', units: 'ISO8601', text: iso(run - 86400000) + '/' + iso(run + 3 * 86400000) + '/PT' + (key === 'eu' ? 1 : 3) + 'H' },
    { name: 'REFERENCE_TIME', units: 'ISO8601', default: iso(run), text: iso(run) },
  ], BoundingBox: [{ CRS: 'EPSG:4326', minx: key === 'eu' ? '29.46875' : '-90', miny: key === 'eu' ? '-23.53125' : '-180',
    maxx: key === 'eu' ? '70.53125' : '90', maxy: key === 'eu' ? '62.53125' : '180' }] });
}
const settled = async () => { await new Promise(resolve => setImmediate(resolve)); await new Promise(resolve => setImmediate(resolve)); };
function runtime({ observed = manifest(), forecast = true, search = '?lat=50&lon=14', fetch: handler, loaded = true } = {}) {
  const nodes = new Map(), events = {}, sources = new Map(), layers = new Map(), calls = [], removed = [];
  const timeouts = new Map(), delays = new Map(), intervals = new Map(); let timerId = 0, clock = NOW;
  function node() {
    return { style: {}, children: [], hidden: false, classList: { toggle(name, enabled) { this[name] = enabled; } },
      focus() { events.focus = this; }, setAttribute(key, value) { this[key] = value; },
      appendChild(child) { this.children.push(child); }, replaceChildren(...children) { this.children = children; } };
  }
  const document = { hidden: false, documentElement: { style: { setProperty(key, value) { this[key] = value; } } },
    body: { style: {}, classList: { toggle(name, enabled) { this[name] = enabled; } } },
    querySelector(selector) { return document.getElementById(selector === '.panel' ? 'panel' : selector); },
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); },
    addEventListener(name, callback) { events[name] = callback; }, createElement: node };
  document.getElementById('details').hidden = true;
  const map = { touchZoomRotate: { disableRotation() {} }, addControl(_control, position) { events.attributionPosition = position; },
    addSource(id, options) { assert.ok(!sources.has(id)); sources.set(id, options); }, getSource(id) { return sources.get(id); },
    removeSource(id) { assert.ok(!layers.has(id)); removed.push(id); sources.delete(id); },
    addLayer(layer) { assert.ok(sources.has(layer.source)); layers.set(layer.id, layer); }, getLayer(id) { return layers.get(id); },
    removeLayer(id) { layers.delete(id); }, setPaintProperty(id, key, value) { layers.get(id).paint[key] = value; },
    getStyle() { return { sources: Object.fromEntries(sources), layers: [...layers.values()] }; },
    resize() { events.resizes = (events.resizes || 0) + 1; }, on(name, callback) { events[name] = callback; return this; },
    getBounds() { return { getEast: () => 17, getWest: () => 11, getNorth: () => 53, getSouth: () => 47 }; } };
  const context = vm.createContext({ document, URLSearchParams, AbortController, TextDecoder, console,
    Date: class extends Date { static now() { return clock; } },
    // XML parsing and WebGL tile decoding require real browser/device verification.
    DOMParser: class { parseFromString(value) {
      const fixture = JSON.parse(value); return { getElementsByTagNameNS(_namespace, name) {
        return (fixture[name] || []).map(item => ({ textContent: item.text || '', getAttribute: key => item[key] ?? null }));
      } };
    } },
    window: { location: { search }, innerHeight: 480, addEventListener(name, callback) { events[name] = callback; } },
    setTimeout(callback, delay) { timeouts.set(++timerId, callback); delays.set(timerId, delay); return timerId; },
    clearTimeout(id) { timeouts.delete(id); delays.delete(id); }, setInterval(callback) { intervals.set(++timerId, callback); return timerId; },
    clearInterval(id) { intervals.delete(id); }, requestAnimationFrame(callback) { callback(); },
    ResizeObserver: class { constructor(callback) { events.mapResize = callback; } observe(element) { events.observedMap = element; } },
    fetch: (url, options) => { calls.push({ url, options }); if (handler) return handler(url, options, context);
      return Promise.resolve(url.includes('maps.dwd.de') ? forecast ? new Response(forecastPayload(context, url.includes('Icon-eu_') ? 'eu' : 'global'))
        : new Response('', { status: 503 }) : new Response(JSON.stringify(observed))); } });
  vm.runInContext(readFileSync(join(assets, 'radar-forecast.js'), 'utf8'), context); vm.runInContext(script, context);
  context.startRadar({ Map: function(options) { events.options = options; return map; }, AttributionControl: function() {} });
  if (loaded) events['style.load']();
  return { context, document, events, sources, layers, calls, removed, timeouts, delays, intervals,
    node: id => document.getElementById(id), advance(time) { clock = time; },
    fire(id) { const callback = timeouts.get(id); timeouts.delete(id); delays.delete(id); assert.ok(callback); callback(); },
    load(layer = context.pendingLayer) { assert.ok(layer); events.sourcedata({ sourceId: layer.id, tile: {}, isSourceLoaded: true }); },
    fail(layer = context.pendingLayer) { assert.ok(layer); events.error({ sourceId: layer.id, error: new Error('tile') }); },
    loading(layer = context.radarLayer) { events.sourcedataloading({ sourceId: layer.id }); } };
}
function asia(app) { app.context.map.getBounds = () => ({ getEast: () => 145, getWest: () => 135, getNorth: () => 40, getSouth: () => 30 }); }

test('style and native readiness gate all requests and raster sources', async () => {
  const app = runtime({ loaded: false, search: '?nativeActive=0' }); await settled(); assert.equal(app.calls.length, 0); assert.equal(app.sources.size, 0);
  app.events['style.load'](); await settled(); assert.equal(app.calls.length, 0); assert.equal(app.sources.size, 1);
  app.context.window.setNativeActive(true); await settled(); assert.equal(app.calls.length, 3); assert.equal(app.context.pendingLayer.frame.type, 'forecast');
});
test('rejected bundle parsing reports unsupported WebView and persists through mode changes', async () => {
  const app = runtime({ loaded: false, search: '?lang=cs' });
  const bootstrap = Array.from(html.matchAll(/<script>([\s\S]*?)<\/script>/g))[1][1];
  vm.runInContext(bootstrap.replace(/import\('[^']+'\)/, 'Promise.reject(new SyntaxError("unsupported bundle syntax"))'), app.context);
  await settled();
  assert.equal(app.context.mapUnavailable, true);
  assert.match(app.node('status').textContent, /Aktualizujte Android System WebView.*WebGL 2/);
  assert.doesNotMatch(app.node('status').textContent, /Loading|Načítám/);
  app.context.setMode('observed');
  assert.equal(app.node('status').hidden, false);
  assert.match(app.node('status').textContent, /Android System WebView/);
  assert.equal(app.calls.length, 0);
  assert.equal(app.events.options.locale['Map.Title'], 'Mapa srážek');
});
test('future forecast default survives slow observation arrival and user selection', async () => {
  let finish; const app = runtime({ fetch: (url, _options, context) => url.includes('maps.dwd.de')
    ? Promise.resolve(new Response(forecastPayload(context, url.includes('Icon-eu_') ? 'eu' : 'global')))
    : new Promise(resolve => { finish = () => resolve(new Response(JSON.stringify(manifest()))); }) });
  await settled(); assert.equal(app.context.mode, 'forecast'); assert.equal(app.context.selectedTime, app.context.frames[0].time);
  assert.ok(app.context.frames.every(frame => frame.type === 'forecast' && frame.time > NOW / 1000));
  app.context.showFrame(3); const selected = app.context.selectedTime; finish(); await settled(); assert.equal(app.context.selectedTime, selected);
  assert.equal(app.context.pendingLayer.frame.type, 'forecast'); assert.ok(app.calls.every(call => call.options.cache === 'no-store'));
});
test('healthy regional forecast renders before unrelated global metadata arrives', async () => {
  let finishGlobal;
  const app = runtime({ fetch: (url, _options, context) => url.includes('Icon_reg025_')
    ? new Promise(resolve => { finishGlobal = () => resolve(new Response(forecastPayload(context, 'global'))); })
    : Promise.resolve(new Response(url.includes('maps.dwd.de') ? forecastPayload(context) : JSON.stringify(manifest()))) });
  await settled();
  assert.equal(app.context.pendingLayer?.frame.key, 'eu');
  app.load(); const displayed = app.context.radarLayer;
  assert.equal(displayed.frame.key, 'eu');
  finishGlobal(); await settled();
  assert.equal(app.context.radarLayer, displayed);
  assert.equal(app.context.pendingLayer, null);
  assert.ok(app.context.forecastModels.global);
});
test('global fallback survives cancellation of a slower regional response', async () => {
  let finishRegional, regionalSignal;
  const app = runtime({ fetch: (url, options, context) => url.includes('Icon-eu_')
    ? new Promise(resolve => { regionalSignal = options.signal; finishRegional = () => resolve(new Response(forecastPayload(context))); })
    : Promise.resolve(new Response(url.includes('maps.dwd.de') ? forecastPayload(context, 'global') : JSON.stringify(manifest()))) });
  await settled(); assert.equal(app.context.pendingLayer.frame.key, 'global'); app.load();
  const global = app.context.forecastModels.global, selected = app.context.selectedTime;
  app.context.window.setNativeActive(false);
  assert.equal(regionalSignal.aborted, true);
  finishRegional(); await settled();
  assert.equal(app.context.forecastModels.eu, null);
  assert.equal(app.context.forecastModels.global, global);
  assert.equal(app.context.radarLayer, null);
  app.context.fetch = async url => new Response(forecastPayload(app.context, url.includes('Icon-eu_') ? 'eu' : 'global'));
  app.context.window.setNativeActive(true); await settled(); app.load();
  assert.equal(app.context.radarLayer.frame.key, 'eu');
  assert.equal(app.context.radarLayer.frame.time, selected);
});
test('modes preserve separate epochs, timelines, legends and coverage', async () => {
  const app = runtime(); await settled(); app.load(); app.context.showFrame(4); app.load(); const future = app.context.selectedTime;
  assert.equal(app.node('coverage').hidden, true); assert.equal(app.node('scale').hidden, false); assert.match(app.node('frame-summary').textContent, /ICON-EU.*mm \/ 1 h/);
  app.context.setMode('observed'); assert.equal(app.context.radarLayer, null); assert.equal(app.context.frames.length, 2);
  assert.equal(app.context.selectedTime, manifest().radar.past.at(-1).time); app.load(); assert.ok(app.sources.has('coverage'));
  assert.equal(app.node('observed-scale').hidden, false); assert.equal(app.node('scale').hidden, true); assert.match(app.node('legend').textContent, /dBZ/);
  app.context.showFrame(0); app.load(); const past = app.context.selectedTime; app.context.setMode('forecast'); assert.equal(app.context.selectedTime, future); app.load();
  assert.ok(!app.sources.has('coverage')); app.context.setMode('observed'); assert.equal(app.context.selectedTime, past);
  assert.equal(app.node('observed')['aria-pressed'], 'true'); assert.equal(app.node('forecast')['aria-pressed'], 'false');
});
test('unavailable forecast never substitutes observations; inactive error remains in details', async () => {
  const app = runtime({ forecast: false }); await settled(); assert.equal(app.context.frames.length, 0); assert.equal(app.context.pendingLayer, null);
  assert.equal(app.node('play').disabled, true); assert.match(app.node('status').textContent, /Model forecast unavailable/);
  app.context.setMode('observed'); app.load(); assert.equal(app.node('status').hidden, true); assert.match(app.node('forecast-status').textContent, /unavailable/);
  app.context.fetch = async url => new Response(forecastPayload(app.context, url.includes('Icon-eu_') ? 'eu' : 'global'));
  app.context.loadForecast(true); await settled(); assert.equal(app.context.radarLayer.frame.type, 'observed');
  app.context.setMode('forecast'); app.load(); assert.equal(app.node('status').hidden, true);
});
test('invalid/oversized observation manifests fail closed independently', async () => {
  for (const observed of [null, { ...manifest(), host: 'https://bad.example' }, { ...manifest(), radar: { past: [{ time: NOW / 1000 - 7200, path: '/v2/radar/stale' }] } },
    { ...manifest(), radar: { past: [{ time: NOW / 1000, path: '/v2/radar/../../bad' }] } }, { ...manifest(), radar: { past: [{ time: NOW / 1000 + 301, path: '/v2/radar/future' }] } },
    { ...manifest(), padding: 'x'.repeat(131073) }]) {
    const app = runtime({ observed }); await settled(); app.load(); assert.equal(app.context.observedCache, null); assert.equal(app.node('status').hidden, true);
    assert.match(app.node('observed-status').textContent, /unavailable/); app.context.setMode('observed'); assert.equal(app.context.frames.length, 0);
  }
});
test('observations remain bounded, recent and strictly chronological', async () => {
  const observed = manifest(); observed.radar.past = Array.from({ length: 100 }, (_, index) => ({ time: NOW / 1000 - (99 - index) * 600, path: '/v2/radar/frame_' + index }));
  const app = runtime({ observed }); await settled(); assert.ok(app.context.observedCache.frames.length <= 20); assert.ok(app.context.observedCache.frames.every(frame => frame.time >= NOW / 1000 - 7200));
  const duplicate = runtime({ observed: { ...manifest(), radar: { past: [manifest().radar.past[0], manifest().radar.past[0]] } } }); await settled(); assert.equal(duplicate.context.observedCache, null);
});
test('MapLibre metadata cannot commit; incomplete tiles wait; loaded transparent tiles succeed', async () => {
  const app = runtime(); await settled(); const layer = app.context.pendingLayer;
  for (const sourceDataType of ['metadata', 'content', 'idle']) app.events.sourcedata({ sourceId: layer.id, sourceDataType, isSourceLoaded: true });
  assert.equal(app.context.radarLayer, null); app.events.sourcedata({ sourceId: layer.id, tile: {}, isSourceLoaded: false }); assert.equal(app.context.radarLayer, null);
  app.events.sourcedata({ sourceId: layer.id, sourceDataType: 'idle', isSourceLoaded: true }); assert.equal(app.context.radarLayer, layer); assert.equal(layer.ready, true);
});
test('failed replacement and timeout restore actual provenance; new-source retry recovers', async () => {
  for (const timeout of [false, true]) {
    const app = runtime(); await settled(); app.load(); const displayed = app.context.radarLayer; app.context.showFrame(4); const failed = app.context.pendingLayer;
    if (timeout) app.fire(app.context.tileTimeout); else app.fail(); app.load(failed); assert.equal(app.context.radarLayer, displayed);
    assert.equal(app.context.selectedTime, displayed.frame.time); assert.equal(Number(app.node('slider').value), displayed.frame.time); assert.match(app.node('status').textContent, /unavailable/);
    app.context.showFrame(4); assert.notEqual(app.context.pendingLayer, failed); app.load(); assert.equal(app.node('status').hidden, true);
  }
});

test('pending raster timeout measures stalled loading, not time spent making tile progress', async () => {
  const app = runtime(); await settled(); app.load();
  const displayed = app.context.radarLayer;
  app.context.showFrame(4);
  const pending = app.context.pendingLayer, deadline = app.context.tileTimeout;
  app.events.sourcedata({ sourceId: pending.id, tile: {}, isSourceLoaded: false });
  assert.equal(app.timeouts.has(deadline), false);
  assert.notEqual(app.context.tileTimeout, deadline);
  assert.equal(app.context.pendingLayer, pending);
  assert.equal(app.context.radarLayer, displayed);
  app.fire(app.context.tileTimeout);
  assert.equal(app.context.pendingLayer, null);
  assert.equal(app.context.radarLayer, displayed);
  assert.match(app.node('status').textContent, /unavailable/);
});

test('forecast image decode failure retries once without cache, then preserves the last valid frame', async () => {
  const app = runtime(); await settled(); app.load();
  const displayed = app.context.radarLayer;
  app.context.showFrame(4);
  const failed = app.context.pendingLayer;
  app.events.error({ sourceId: failed.id, error: new Error('The source image could not be decoded.') });
  const retry = app.context.pendingLayer;
  assert.ok(retry && retry !== failed);
  assert.equal(retry.frame, failed.frame);
  const tile = app.context.forecastTileUrl(retry.frame).replace('{bbox-epsg-3857}', '0,0,1,1');
  assert.equal(app.events.options.transformRequest(tile, 'Tile').cache, 'reload');
  assert.ok(!app.sources.has(failed.id));
  app.events.error({ sourceId: retry.id, error: new Error('The source image could not be decoded.') });
  assert.equal(app.context.pendingLayer, null);
  assert.equal(app.context.radarLayer, displayed);
  assert.match(app.node('status').textContent, /unavailable/);
  app.context.showFrame(4); app.load();
  assert.equal(app.context.radarLayer.frame, failed.frame);
  assert.equal(app.node('status').hidden, true);
});

function advertisedOlderRun(url, context) {
  const key = url.includes('Icon-eu_') ? 'eu' : 'global';
  const payload = JSON.parse(forecastPayload(context, key));
  const run = Date.parse(payload.Dimension[1].default);
  payload.Dimension[1].text += ',' + new Date(run - 21600000).toISOString();
  return new Response(JSON.stringify(payload));
}

for (const key of ['eu', 'global']) test('incomplete forecast run recovers once at the same future epoch: ' + key, async () => {
  const app = runtime({ fetch: (url, _options, context) => Promise.resolve(url.includes('maps.dwd.de')
    ? advertisedOlderRun(url, context) : new Response(JSON.stringify(manifest()))) });
  await settled();
  if (key === 'global') { asia(app); app.events.moveend(); }
  app.load();
  const displayed = app.context.radarLayer, cached = app.context.forecastCache;
  const lastTime = app.context.frames.at(-1).time;
  app.context.showFrame(app.context.frames.length - 1);
  const first = app.context.pendingLayer;
  const decodeError = layer => app.events.error({ sourceId: layer.id, error: new Error('The source image could not be decoded.') });
  decodeError(first);
  const retry = app.context.pendingLayer;
  assert.equal(retry.frame.reference, first.frame.reference);
  decodeError(retry);
  const fallback = app.context.pendingLayer;
  assert.ok(fallback, 'An advertised fresh previous run must retry the requested future frame');
  assert.notEqual(app.context.forecastCache, cached);
  assert.equal(app.context.forecastCache.run, cached.run - 21600);
  assert.equal(app.context.forecastCache.loadedAt, cached.loadedAt);
  assert.equal(fallback.frame.time, lastTime);
  assert.equal(app.context.frames.at(-1).time, lastTime);
  assert.equal(app.context.selectedTime, lastTime);
  assert.ok(app.context.frames.every(frame => frame.reference === new Date((cached.run - 21600) * 1000).toISOString()));
  assert.equal(app.context.radarLayer, displayed);
  assert.equal(displayed.frame.reference, new Date(cached.run * 1000).toISOString());
  app.load(retry);
  assert.equal(app.context.pendingLayer, fallback);
  app.load();
  assert.equal(app.context.radarLayer, fallback);
  assert.equal(app.node('status').hidden, true);
  assert.equal(new URL(app.sources.get(fallback.id).tiles[0]).searchParams.get('dim_reference_time'), fallback.frame.reference);
  app.context.showFrame(1);
  decodeError(app.context.pendingLayer);
  decodeError(app.context.pendingLayer);
  assert.equal(app.context.pendingLayer, null);
  assert.equal(app.context.radarLayer, fallback);
  assert.match(app.node('status').textContent, /unavailable/);
  if (key === 'global') {
    app.advance((cached.run - 21600 + 12 * 3600) * 1000 + 1);
    app.context.fetch = async () => new Response('', { status: 503 });
    app.fire(app.context.expiryTimer); await settled();
    assert.equal(app.context.forecastModels.global, null);
    assert.equal(app.context.radarLayer, null);
    assert.equal(app.context.frames.length, 0);
  }
});

test('expired or superseded forecast metadata cannot trigger an older-run downgrade', async () => {
  for (const expired of [true, false]) {
    const app = runtime({ fetch: (url, _options, context) => Promise.resolve(url.includes('maps.dwd.de')
      ? advertisedOlderRun(url, context) : new Response(JSON.stringify(manifest()))) });
    await settled(); app.load(); app.context.showFrame(4);
    const cached = app.context.forecastCache;
    if (expired) app.advance(NOW + 3 * 3600000);
    else app.context.forecastModels.eu = { ...cached };
    app.events.error({ sourceId: app.context.pendingLayer.id, error: new Error('The source image could not be decoded.') });
    app.events.error({ sourceId: app.context.pendingLayer.id, error: new Error('The source image could not be decoded.') });
    assert.equal(app.context.pendingLayer, null);
    assert.equal(app.context.forecastModels.eu.run, cached.run);
    assert.match(app.node('status').textContent, /unavailable/);
  }
});

test('transient forecast decode failure recovers during playback without disabling normal tile caching', async () => {
  const app = runtime(); await settled(); app.load();
  const displayedTile = app.context.forecastTileUrl(app.context.radarLayer.frame).replace('{bbox-epsg-3857}', '0,0,1,1');
  assert.equal(app.events.options.transformRequest(displayedTile, 'Tile').cache, 'default');
  app.context.togglePlay();
  [...app.intervals.values()][0]();
  const failed = app.context.pendingLayer;
  const tile = app.context.forecastTileUrl(failed.frame).replace('{bbox-epsg-3857}', '0,0,1,1');
  app.events.error({ sourceId: failed.id, error: new Error('The source image could not be decoded.') });
  assert.equal(app.events.options.transformRequest(tile, 'Tile').cache, 'reload');
  assert.equal(app.events.options.transformRequest(displayedTile, 'Tile').cache, 'default');
  assert.equal(app.events.options.transformRequest(tile, 'Image').cache, 'default');
  assert.equal(app.events.options.transformRequest('https://tile.openstreetmap.org/6/30/20.png', 'Tile').cache, 'default');
  app.load(failed);
  assert.notEqual(app.context.radarLayer, failed);
  app.load();
  assert.equal(app.context.radarLayer.frame, failed.frame);
  assert.equal(app.node('status').hidden, true);
  assert.equal(app.node('play')['aria-pressed'], 'true');
  assert.equal(app.events.options.transformRequest(tile, 'Tile').cache, 'default');
  assert.ok(app.sources.size <= 6); // Four weather sources, base and location.
});

test('HTTP failures and observed image errors do not trigger the forecast decode retry', async () => {
  const app = runtime(); await settled(); app.load();
  app.context.showFrame(4);
  const sequence = app.context.sourceSequence;
  app.events.error({ sourceId: app.context.pendingLayer.id, error: { status: 429, message: 'Too Many Requests' } });
  assert.equal(app.context.pendingLayer, null);
  assert.equal(app.context.sourceSequence, sequence);
  app.context.setMode('observed');
  app.events.error({ sourceId: app.context.pendingLayer.id, error: new Error('The source image could not be decoded.') });
  assert.equal(app.context.pendingLayer, null);
  assert.match(app.node('status').textContent, /Observed radar is unavailable/);
});
test('displayed source remains failed on later data; panned timeout and retry work', async () => {
  const app = runtime(); await settled(); app.load(); const old = app.context.radarLayer; app.loading(); app.fail(old); app.load(old); app.loading(); app.load(old);
  assert.equal(old.failed, true); assert.equal(old.ready, false); app.context.showFrame(app.context.frameIndex); app.load(); assert.equal(app.node('status').hidden, true);
  const current = app.context.radarLayer; app.loading(); app.fire(app.context.panTimeout); app.load(current); assert.equal(current.failed, true);
});
test('pending playback stays quiet with a visible raster and still reports failures', async () => {
  const app = runtime(); await settled(); app.load();
  const current = app.context.radarLayer;
  app.loading();
  assert.equal(current.ready, false);
  assert.equal(app.node('status').hidden, true);
  assert.equal(app.delays.get(app.context.panTimeout), 15000);
  app.load(current);
  assert.equal(app.context.panTimeout, null);
  app.context.showFrame(4);
  assert.equal(app.node('status').hidden, true);
  assert.equal(app.node('status').textContent, '');
  assert.equal(app.node('slider')['aria-busy'], 'true');
  app.loading(current); app.load(current);
  assert.equal(app.node('status').hidden, true);
  assert.equal(app.node('slider')['aria-busy'], 'true');
  app.load();
  assert.equal(app.node('slider')['aria-busy'], 'false');
  app.loading(); app.fire(app.context.panTimeout);
  assert.match(app.node('status').textContent, /unavailable/);
  app.context.showFrame(5);
  assert.match(app.node('status').textContent, /Loading model forecast/);
  app.fail();
  assert.match(app.node('status').textContent, /unavailable/);
  assert.equal(app.node('slider')['aria-busy'], 'false');
});
test('scrubbing coalesces final epoch and ignores obsolete source callbacks', async () => {
  const app = runtime(); await settled(); app.load(); const displayed = app.context.radarLayer; app.context.showFrame(4); const obsolete = app.context.pendingLayer;
  const created = app.context.sourceSequence, target = app.context.frames[8].time;
  for (const time of [app.context.frames[5].time, target, app.context.frames[6].time, target]) app.context.seekTime(time);
  assert.equal(app.context.sourceSequence, created); app.load(obsolete); assert.equal(app.context.radarLayer, displayed);
  assert.equal(Number(app.node('slider').value), target); assert.equal(app.delays.get(app.context.seekTimer), 120); app.fire(app.context.seekTimer); app.load(); assert.equal(app.context.radarLayer.frame.time, target);
});
test('play and keyboard walk real frames and mode change cancels both', async () => {
  const app = runtime(); await settled(); app.load(); app.context.seekTime(app.context.frames[3].time); const queued = app.context.seekTimer;
  app.context.togglePlay(); assert.equal(app.timeouts.has(queued), false); app.load(); [...app.intervals.values()][0](); assert.equal(app.context.pendingLayer.frame.time, app.context.frames[4].time); app.load();
  let prevented = 0; const key = value => app.context.timelineKey({ key: value, preventDefault() { prevented++; } }); key('Home'); key('ArrowRight');
  assert.equal(Number(app.node('slider').value), app.context.frames[1].time); key('End'); assert.equal(Number(app.node('slider').value), app.context.frames.at(-1).time); key('Tab'); assert.equal(prevented, 3);
  app.context.setMode('observed'); assert.equal(app.context.timer, null); assert.equal(app.context.seekTimer, null);
});
test('play toggle keeps its localized accessible label constant', async () => {
  const app = runtime({ search: '?lang=cs' }); await settled(); app.load();
  const play = app.node('play');
  const assertState = pressed => {
    assert.equal(play['aria-label'], 'Přehrát');
    assert.equal(play['aria-pressed'], String(pressed));
  };
  assertState(false);
  app.context.togglePlay(); assertState(true);
  app.context.togglePlay(); assertState(false);
  app.context.togglePlay(); app.context.setMode('observed'); assertState(false);
  app.context.togglePlay(); assertState(true);
  app.fail(); assertState(false);
});
test('four-frame bound, preloading and hard mode/model transitions preserve source provenance', async () => {
  const app = runtime(); await settled(); app.load();
  for (let index = 1; index < 12; index++) { app.context.showFrame(index); app.load(); assert.ok([...app.sources.keys()].filter(id => id.startsWith('frame-')).length <= 4); }
  const old = app.context.radarLayer; app.context.setMode('observed'); assert.ok(!app.sources.has(old.id)); app.load(); assert.equal(app.context.retiredLayer, null);
  app.context.setMode('forecast'); app.load(); const europe = app.context.radarLayer; asia(app); app.events.moveend(); app.load(); assert.ok(!app.sources.has(europe.id)); assert.equal(app.context.radarLayer.frame.key, 'global');
});
for (const action of ['commit', 'frame expiry', 'run expiry', 'foreign coverage', 'mode change']) test('new run retains usable raster until ' + action, async () => {
  const app = runtime(); await settled(); app.load(); app.context.showFrame(action === 'frame expiry' ? 6 : 8); app.load();
  const displayed = app.context.radarLayer;
  app.advance(NOW + 6 * 3600000); app.context.loadForecast(true); await settled();
  assert.equal(app.context.radarLayer, displayed);
  assert.ok(app.sources.has(displayed.id));
  const replacement = app.context.pendingLayer;
  assert.notEqual(replacement.frame.reference, displayed.frame.reference);
  assert.equal(replacement.frame.key, displayed.frame.key);
  assert.equal(Number(app.node('slider').value), displayed.frame.time);
  if (action === 'commit') {
    app.load(); assert.equal(app.context.radarLayer, replacement); assert.equal(app.context.retiredLayer, null);
    assert.equal(app.layers.get(replacement.id).paint['raster-opacity-transition'].duration, 0);
    assert.equal(app.layers.get(replacement.id).paint['raster-opacity'], 0.78);
  } else if (action === 'foreign coverage') {
    asia(app); app.events.moveend(); assert.equal(app.context.radarLayer, null); assert.equal(app.context.pendingLayer.frame.key, 'global');
  } else if (action === 'mode change') {
    app.context.setMode('observed'); assert.equal(app.context.radarLayer, null);
  } else {
    app.advance(action === 'frame expiry' ? displayed.frame.time * 1000 + 1 : Date.parse(displayed.frame.reference) + 12 * 3600000 + 1);
    app.context.fetch = async () => new Response('', { status: 503 });
    app.fire(app.context.expiryTimer); await settled();
    assert.ok(app.context.forecastIsUsable(app.context.forecastCache));
    assert.equal(app.context.radarLayer, null);
  }
  assert.ok(!app.sources.has(displayed.id));
  assert.ok([...app.sources.keys()].filter(id => id.startsWith('frame-')).length <= 4);
});
test('play wraps loaded frames, crossfades retained rasters and pause cancels future steps', async () => {
  const app = runtime(); await settled(); app.load();
  app.context.showFrame(app.context.frames.length - 1); app.load();
  const old = app.context.radarLayer, next = app.context.preloadLayer;
  app.load(next); app.context.togglePlay(); [...app.intervals.values()][0]();
  assert.equal(app.context.frameIndex, 0); assert.equal(app.context.radarLayer, next);
  assert.equal(app.context.retiredLayer, old);
  assert.equal(app.layers.get(next.id).paint['raster-opacity'], 0.78);
  assert.equal(app.layers.get(old.id).paint['raster-opacity'], 0);
  assert.equal(app.layers.get(next.id).paint['raster-opacity-transition'].duration, 350);
  assert.equal(app.delays.get(app.context.transitionTimer), 350);
  app.fire(app.context.transitionTimer); assert.ok(!app.sources.has(old.id));
  app.context.togglePlay(); assert.equal(app.intervals.size, 0);
  assert.equal(app.node('play')['aria-pressed'], 'false'); assert.equal(app.context.radarLayer, next);
});

test('seeking during a crossfade restores displayed opacity before retiring its predecessor', async () => {
  const app = runtime(); await settled(); app.load();
  const first = app.context.radarLayer;
  assert.equal(app.layers.get(first.id).paint['raster-opacity-transition'].duration, 0);
  app.context.showFrame(1); app.load();
  const displayed = app.context.radarLayer, transition = app.context.transitionTimer;
  assert.equal(app.context.retiredLayer, first);
  app.context.showFrame(2);
  assert.equal(app.context.radarLayer, displayed);
  assert.equal(app.layers.get(displayed.id).paint['raster-opacity-transition'].duration, 0);
  assert.equal(app.layers.get(displayed.id).paint['raster-opacity'], 0.78);
  assert.equal(app.timeouts.has(transition), false);
  assert.equal(app.context.retiredLayer, null);
  assert.ok(!app.sources.has(first.id));
  app.load();
  assert.equal(app.context.retiredLayer, displayed);
  assert.equal(app.layers.get(displayed.id).paint['raster-opacity-transition'].duration, 350);
});
test('pause during tile loading keeps the visible frame and rejects the late replacement', async () => {
  const app = runtime(); await settled(); app.load();
  const displayed = app.context.radarLayer;
  app.context.togglePlay(); [...app.intervals.values()][0]();
  const pending = app.context.pendingLayer; assert.ok(pending);
  app.context.togglePlay();
  assert.equal(app.context.pendingLayer, null); assert.equal(app.context.radarLayer, displayed);
  assert.equal(app.context.tileTimeout, null);
  assert.equal(app.node('slider')['aria-busy'], 'false');
  assert.equal(Number(app.node('slider').value), displayed.frame.time);
  assert.equal(app.node('status').hidden, true);
  assert.equal(app.intervals.size, 0); app.load(pending);
  assert.equal(app.context.radarLayer, displayed); assert.ok(!app.sources.has(pending.id));
  app.context.togglePlay(); [...app.intervals.values()][0](); app.load();
  assert.notEqual(app.context.radarLayer, displayed);
});
test('pause allows the first frame to load but expiry cannot turn pause into play', async () => {
  const app = runtime(); await settled(); const first = app.context.pendingLayer;
  app.context.togglePlay(); app.context.togglePlay();
  assert.equal(app.intervals.size, 0); assert.equal(app.context.pendingLayer, first);
  app.load(); assert.equal(app.context.radarLayer, first);
  app.context.togglePlay(); app.advance(NOW + 8 * 3600000 + 1);
  app.context.fetch = async () => new Response('', { status: 503 });
  app.context.togglePlay(); await settled();
  assert.equal(app.intervals.size, 0); assert.equal(app.node('play')['aria-pressed'], 'false');
  assert.equal(app.context.radarLayer, null);
});
test('raster requests preserve correct projected WMS bbox, reference, epoch and tile sizes', async () => {
  const app = runtime(); await settled(); const layer = app.context.pendingLayer, source = app.sources.get(layer.id), url = new URL(source.tiles[0]);
  assert.equal(source.tileSize, 256); assert.equal(url.searchParams.get('srs'), 'EPSG:3857'); assert.equal(url.searchParams.get('bbox'), '{bbox-epsg-3857}');
  assert.equal(url.searchParams.get('dim_reference_time'), layer.frame.reference); assert.equal(url.searchParams.get('time'), new Date(layer.frame.time * 1000).toISOString());
  app.context.setMode('observed'); assert.equal(app.sources.get(app.context.pendingLayer.id).tileSize, 512); assert.match(app.sources.get(app.context.pendingLayer.id).tiles[0], /\/512\/\{z\}\/\{x\}\/\{y\}/);
});
test('missing global model leaves forecast unavailable and return to region preserves epoch', async () => {
  const app = runtime({ fetch: (url, _options, context) => Promise.resolve(url.includes('Icon_reg025_') ? new Response('', { status: 429 })
    : new Response(url.includes('maps.dwd.de') ? forecastPayload(context) : JSON.stringify(manifest()))) });
  await settled(); app.context.showFrame(8); app.load(); const selected = app.context.selectedTime, bounds = app.context.map.getBounds;
  asia(app); app.events.moveend(); assert.equal(app.context.frames.length, 0); assert.equal(app.context.radarLayer, null); assert.match(app.node('status').textContent, /busy/);
  app.context.map.getBounds = bounds; app.events.moveend(); assert.equal(app.context.pendingLayer.frame.time, selected); assert.equal(app.calls.length, 3);
});
test('containing accumulation intervals, real epoch spacing and marks stay within geometry', async () => {
  const app = runtime(); await settled(); asia(app); app.events.moveend(); const first = app.context.frames[0], next = app.context.frames[1];
  assert.equal(app.context.frames[app.context.nearestFrame(first.time + 3600)].time, next.time); assert.equal(app.node('slider').min, first.time - first.hours * 3600); assert.equal(app.node('slider').max, app.context.frames.at(-1).time);
  for (const mark of app.node('timeline-marks').children) { assert.ok(parseFloat(mark.style.left) >= 0 && parseFloat(mark.style.left) <= 100);
    if (mark.className === 'forecast-mark') assert.ok(parseFloat(mark.style.left) + parseFloat(mark.style.width) <= 100.000001); }
});
test('metadata TTL, hard model age and future horizon remain distinct', async () => {
  const app = runtime(); await settled(); const cached = app.context.forecastCache; app.advance(NOW + 599999); assert.equal(app.context.forecastIsUsable(cached), true); assert.equal(app.context.refreshDue(cached), false);
  app.advance(NOW + 600000); assert.equal(app.context.refreshDue(cached), true); app.advance(NOW); cached.run = NOW / 1000 - 12 * 3600 - 1; assert.equal(app.context.forecastIsUsable(cached), false);
  cached.run = NOW / 1000; cached.frames.at(-1).time = NOW / 1000 + 12 * 3600 - 1; assert.equal(app.context.forecastIsUsable(cached), false);
});
for (const action of ['seek', 'play', 'tick', 'commit', 'idle', 'online', 'resume']) test('expired tiles removed on ' + action, async () => {
  const app = runtime(); await settled(); if (action !== 'commit') app.load(); if (action === 'tick') app.context.togglePlay(); const pending = app.context.pendingLayer;
  app.advance(NOW + 8 * 3600000 + 1); app.context.fetch = async () => new Response('', { status: 503 });
  if (action === 'seek') app.context.showFrame(1); if (action === 'play') app.context.togglePlay(); if (action === 'tick') [...app.intervals.values()][0](); if (action === 'commit') app.load(pending);
  if (action === 'idle') app.fire(app.context.expiryTimer); if (action === 'online') app.events.online(); if (action === 'resume') app.events.visibilitychange(); await settled();
  assert.equal(app.context.frames.length, 0); assert.equal(app.context.radarLayer, null); assert.equal(app.node('play').disabled, true); assert.equal(app.node('time').textContent, '—'); assert.doesNotMatch(app.node('status').textContent, /Loading/);
});
test('hidden pages detach network sources, cancel deadlines and resume queued epoch', async () => {
  const app = runtime(); await settled(); app.load(); const target = app.context.frames[5].time; app.context.seekTime(target); const queued = app.context.seekTimer;
  app.context.window.setNativeActive(false); assert.equal(app.timeouts.has(queued), false); assert.equal(app.timeouts.size, 0); assert.equal(app.sources.size, 1);
  app.events.online(); app.events.moveend(); app.context.refreshMap(); assert.equal(app.calls.length, 3); app.context.window.setNativeActive(true); assert.equal(app.context.pendingLayer.frame.time, target); app.load(); assert.equal(app.context.radarLayer.frame.time, target);
  const signals = [], finishes = []; const waiting = runtime({ fetch: (url, options, context) => { signals.push(options.signal); return new Promise(resolve => finishes.push(() => resolve(new Response(url.includes('maps.dwd.de') ? forecastPayload(context, url.includes('Icon-eu_') ? 'eu' : 'global') : JSON.stringify(manifest()))))); } });
  waiting.document.hidden = true; waiting.events.visibilitychange(); assert.ok(signals.every(signal => signal.aborted)); finishes.forEach(finish => finish()); await settled(); assert.equal(waiting.context.frames.length, 0); assert.equal(waiting.timeouts.size, 0);
});
test('failed due refresh retains healthy forecast and enforces automatic retry delay', async () => {
  const app = runtime(); await settled(); app.load(); const displayed = app.context.radarLayer; app.advance(NOW + 600001); let calls = 0;
  app.context.fetch = async () => { calls++; return new Response('', { status: 503 }); }; app.context.ensureSourcesFresh(); await settled(); assert.equal(calls, 3); assert.equal(app.context.radarLayer, displayed);
  assert.match(app.node('status').textContent, /previous forecast/); assert.doesNotMatch(app.node('status').textContent, /previous snapshot/); app.context.ensureSourcesFresh(); app.events.moveend(); assert.equal(calls, 3);
});
test('expired inactive observations do not stop usable forecast playback', async () => {
  const app = runtime(); await settled(); app.load(); app.context.showFrame(8); app.load();
  const displayed = app.context.radarLayer;
  app.context.togglePlay(); app.advance(NOW + 3600000);
  app.context.fetch = async url => url.includes('maps.dwd.de')
    ? new Response(forecastPayload(app.context, url.includes('Icon-eu_') ? 'eu' : 'global')) : new Response('', { status: 503 });
  app.context.ensureSourcesFresh(); await settled();
  assert.equal(app.context.observedCache, null);
  assert.equal(app.context.radarLayer, displayed);
  assert.ok(app.context.forecastIsUsable(app.context.forecastCache));
  assert.equal(app.intervals.size, 1);
  const nextTime = app.context.frames[app.context.frameIndex + 1].time;
  [...app.intervals.values()][0](); app.load();
  assert.equal(app.context.radarLayer.frame.time, nextTime);
  assert.equal(app.node('status').hidden, true);
});
test('429 cooldown survives explicit refresh and online while healthy observation remains selectable', async () => {
  const app = runtime({ fetch: (url, _options, context) => Promise.resolve(url.includes('maps.dwd.de') ? new Response('', { status: 429 }) : new Response(JSON.stringify(manifest()))) });
  await settled(); app.context.refreshMap(); app.events.online(); app.events.moveend(); await settled(); assert.equal(app.calls.filter(call => call.url.includes('maps.dwd.de')).length, 2); app.context.setMode('observed'); app.load(); assert.equal(app.node('status').hidden, true);
});
test('superseded observations and request timeouts cannot install late frames', async () => {
  const app = runtime(); await settled(); app.load(); const finishes = [], signals = []; app.context.fetch = (_url, options) => { signals.push(options.signal); return new Promise(resolve => finishes.push(value => resolve(new Response(JSON.stringify(value))))); };
  app.context.loadRadar(true); app.context.loadRadar(true); const latest = manifest(); latest.radar.past[1].path = '/v2/radar/new'; finishes[1](latest); await settled(); finishes[0](manifest()); await settled(); assert.equal(signals[0].aborted, true); assert.equal(app.context.observedCache.frames[1].path, '/v2/radar/new');
  app.advance(NOW + 600001); app.context.fetch = (_url, options) => new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted')))); app.context.loadForecast(true); app.fire(app.context.forecastState.timeout); await settled(); assert.equal(app.context.forecastState.request, null); assert.match(app.node('forecast-status').textContent, /previous forecast/);
});
test('bounded decoder and metadata reject malformed UTF-8, source, run, geometry and horizon', async () => {
  const app = runtime(), read = app.context.RadarForecast.read; assert.equal(await read(new Response('Niederschläge')), 'Niederschläge'); await assert.rejects(read(new Response('x'.repeat(131073))), /too large/); await assert.rejects(read(new Response(new Uint8Array([255]))), /encoded data/);
  for (const corrupt of [p => { p.Name[0].text = 'untrusted'; }, p => { p.Dimension[0].units = 'hours'; }, p => { p.Dimension[0].text = 'invalid'; }, p => { p.Dimension[1].default = new Date(NOW - 86400000).toISOString(); }, p => { p.Dimension[1].default = new Date(NOW + 3600000).toISOString(); }, p => { p.Dimension[1].text = ''; }, p => { p.Dimension[0].text = '+200000-01-01T00:00:00Z/+200000-01-10T00:00:00Z/PT1H'; }, p => { p.BoundingBox[0].minx = 'NaN'; }, p => { p.BoundingBox[0].maxx = '-90'; }, p => { p.parsererror = [{ text: 'Bad XML' }]; }]) {
    const payload = JSON.parse(forecastPayload(app.context)); corrupt(payload); assert.throws(() => app.context.RadarForecast.parse(JSON.stringify(payload), 'eu', NOW / 1000));
  }
  assert.throws(() => app.context.RadarForecast.parse('<!DOCTYPE x>', 'eu', NOW / 1000)); assert.throws(() => app.context.RadarForecast.parse('x'.repeat(131073), 'eu', NOW / 1000));
});
test('provider retains true accumulation steps, main cycles and full viewport coverage', () => {
  const app = runtime(), provider = app.context.RadarForecast;
  for (const key of ['eu', 'global']) { const parsed = provider.parse(forecastPayload(app.context, key), key, NOW / 1000), step = key === 'eu' ? 3600 : 21600;
    assert.ok(parsed.frames.length >= 9 && parsed.frames.length <= 50); assert.ok(parsed.frames[0].time > NOW / 1000 && parsed.frames[0].time <= NOW / 1000 + step); assert.ok(parsed.frames.at(-1).time >= NOW / 1000 + 48 * 3600 + 600);
    assert.ok(parsed.frames.every((frame, index) => frame.hours * 3600 === step && frame.time % step === 0 && (!index || frame.time === parsed.frames[index - 1].time + step))); }
  const bounds = provider.parse(forecastPayload(app.context), 'eu', NOW / 1000).bounds; assert.equal(provider.sourceFor({ west: 371, east: 377, south: 47, north: 53 }, bounds), 'eu');
  for (const region of [{ west: 11, east: 70, south: 47, north: 53 }, { west: -180, east: 180, south: 47, north: 53 }, { west: 175, east: 190, south: 0, north: 10 }]) assert.equal(provider.sourceFor(region, bounds), 'global');
  const payload = JSON.parse(forecastPayload(app.context)), main = Date.parse(payload.Dimension[1].default), intermediate = main + 10800000; payload.Dimension[1].default = new Date(intermediate).toISOString(); payload.Dimension[1].text += ',' + payload.Dimension[1].default; assert.equal(provider.parse(JSON.stringify(payload), 'eu', intermediate / 1000 + 3600).run, main / 1000);
});
test('timezone changes, DST, appearance and invalid coordinates preserve pending selection', async () => {
  const app = runtime({ search: '?lat=91&lon=999&lang=en&tz=America%2FNew_York' }); await settled(); app.load(); assert.deepEqual(Array.from(app.events.options.center), [14.4378, 50.0755]);
  assert.match(app.context.forecastIntervalLabel({ time: Date.parse('2026-11-01T06:00:00Z') / 1000, hours: 1 }), /01:00 EDT–01:00 EST/); assert.match(app.context.forecastIntervalLabel({ time: Date.parse('2026-03-08T07:00:00Z') / 1000, hours: 1 }), /01:00 EST–03:00 EDT/);
  const displayed = app.context.radarLayer; app.context.seekTime(app.context.frames[5].time); const queued = app.context.seekTimer; app.context.window.setAppearance({ timezone: 'Asia/Tokyo', bg: '#FFFFFF', surface: 'url(bad)', topInset: 0, bottomInset: 76 });
  assert.equal(app.context.radarLayer, displayed); assert.equal(app.context.seekTimer, queued); assert.equal(app.calls.length, 3); assert.equal(app.document.documentElement.style['--surface'], undefined); assert.equal(app.document.documentElement.style['--header-inset'], '0px'); assert.equal(app.document.documentElement.style['--bottom-inset'], '76px');
  app.context.window.setAppearance({ timezone: 'bad-zone', topInset: -1, bottomInset: 241 }); assert.equal(app.context.timezone, 'Asia/Tokyo'); app.context.showFrame(NaN); app.context.seekTime(Infinity); app.context.window.setAppearance({ timezone: null }); assert.equal(app.context.timezone, 'UTC');
});
test('in-flow geometry reserves insets, 48px controls and explicit close/Escape restores focus', async () => {
  const app = runtime(); await settled(); assert.match(html, /body\s*\{[^}]*padding-top: var\(--header-inset\)[^}]*padding-bottom: var\(--bottom-inset\)/); assert.match(html, /#map\s*\{[^}]*flex:\s*1[^}]*min-height:\s*0/); assert.match(html, /\.panel, \.details\s*\{[^}]*flex:\s*0 1 auto/);
  assert.doesNotMatch(html, /\.tools\s*\{[^}]*position:\s*absolute|\.panel, \.details\s*\{[^}]*position:\s*absolute/); assert.match(html, /button\s*\{[^}]*min-width:\s*48px[^}]*min-height:\s*48px/); assert.match(html, /worker-src 'self';/); assert.doesNotMatch(html, /leaflet|JavascriptInterface/); assert.match(html, /connect-src[^;]*https:\/\/tilecache\.rainviewer\.com/); assert.equal(app.events.attributionPosition, 'bottom-left');
  app.context.toggleDetails(); assert.equal(app.events.focus, app.node('details-close')); app.events.keydown({ key: 'Escape', preventDefault() {} }); assert.equal(app.node('details').hidden, true); assert.equal(app.events.focus, app.node('details-toggle'));
  app.context.window.innerHeight = 320; app.events.resize(); assert.equal(app.document.body.style.height, '320px'); app.context.window.innerHeight = 0; app.events.resize(); assert.equal(app.document.body.style.height, '320px'); const resizes = app.events.resizes; app.events.mapResize(); assert.equal(app.events.resizes, resizes + 1);
  assert.match(html, /body\.short \.panel\s*\{[^}]*flex:\s*0 0 auto[^}]*max-height:\s*none[^}]*padding:\s*0 12px/);
  assert.match(html, /body\.short \.topline\s*\{[^}]*grid-template-columns:\s*48px auto minmax\(0, 1fr\) 48px/);
  assert.match(html, /body\.short \.modes \.mode-label\s*\{\s*display:\s*none/);
  assert.match(html, /body\.short \.mode-short\s*\{[^}]*display:\s*block/);
  assert.match(html, /\.mode-label, \.mode-short\s*\{[^}]*display:\s*block[^}]*overflow:\s*hidden[^}]*text-overflow:\s*ellipsis[^}]*white-space:\s*nowrap/);
  assert.equal(app.document.querySelector('#forecast .mode-short').textContent, 'Future');
  assert.equal(app.document.querySelector('#observed .mode-short').textContent, 'Radar');
  assert.equal(app.node('forecast')['aria-label'], 'Forecast');
  assert.equal(app.node('observed')['aria-label'], 'Observed');
  assert.doesNotMatch(html.match(/<div class="panel">([\s\S]*?)<div id="details"/)[1], /timeline-hint|frame-kind/);
  assert.match(html, /<div class="detail-controls">\s*<button id="center"/);
  assert.match(html, /\.details-header\s*\{[^}]*position:\s*sticky/);
  assert.match(html, /\.details-header h2\s*\{[^}]*min-width:\s*0[^}]*text-overflow:\s*ellipsis[^}]*white-space:\s*nowrap/);
  assert.doesNotMatch(html, /body\.short \.topline\s*\{[^}]*order:\s*-1/);
});
