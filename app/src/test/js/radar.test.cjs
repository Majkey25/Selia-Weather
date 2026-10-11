const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const html = readFileSync(join(__dirname, '../../main/assets/radar.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const NOW = Date.UTC(2026, 9, 11, 10, 15);
const manifest = (time = NOW / 1000) => ({ host: 'https://tilecache.rainviewer.com', radar: { past: [
  { time: time - 1200, path: '/v2/radar/514ebb4b4aef' },
  { time: time - 600, path: '/v2/radar/fda4d81e26c8' },
] } });

function forecastPayload(context, key = 'eu') {
  const run = Math.floor(context.Date.now() / 21600000) * 21600000;
  const iso = milliseconds => new Date(milliseconds).toISOString();
  const source = context.RadarForecast.sources[key];
  return JSON.stringify({
    Name: [{ text: source.layer }, { text: source.style }],
    Dimension: [
      { name: 'time', units: 'ISO8601', text: iso(run - 86400000) + '/' + iso(run + 3 * 86400000) + '/PT' + (key === 'eu' ? 1 : 3) + 'H' },
      { name: 'REFERENCE_TIME', units: 'ISO8601', default: iso(run), text: iso(run) },
    ],
    BoundingBox: [{ CRS: 'EPSG:4326', minx: key === 'eu' ? '29.46875' : '-90',
      miny: key === 'eu' ? '-23.53125' : '-180', maxx: key === 'eu' ? '70.53125' : '90',
      maxy: key === 'eu' ? '62.53125' : '180' }],
  });
}
const forecastResponse = (app, url) => new Response(forecastPayload(app.context, url.includes('Icon-eu_') ? 'eu' : 'global'));
const settled = async () => {
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
};

function runtime({ observed = manifest(), forecast = true, search = '?lat=50&lon=14', fetch: handler } = {}) {
  const nodes = new Map(), events = {}, layers = [], removed = [], calls = [];
  const timeouts = new Map(), timeoutDelays = new Map(), intervals = new Map();
  let timerId = 0, clock = NOW;
  function node() {
    return { style: {}, children: [], hidden: false,
      setAttribute(key, value) { this[key] = value; },
      appendChild(child) { this.children.push(child); },
      replaceChildren(...children) { this.children = children; } };
  }
  const document = {
    hidden: false,
    documentElement: { style: { setProperty(key, value) { this[key] = value; } } },
    body: { style: {}, classList: { toggle(name, enabled) { this[name] = enabled; } } },
    querySelector(selector) { return document.getElementById(selector === '.panel' ? 'panel' : selector); },
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); },
    addEventListener(name, callback) { events[name] = callback; },
    createElement: node,
  };
  document.getElementById('details').hidden = true;
  const map = {
    setView(center) { events.center = center; return this; }, setMaxZoom() {},
    removeLayer(layer) { removed.push(layer); layer.added = false; },
    attributionControl: { setPosition(position) { events.attributionPosition = position; },
      getContainer() { return document.getElementById('credits'); } },
    invalidateSize() { events.invalidations = (events.invalidations || 0) + 1; },
    on(name, callback) { events[name] = callback; return this; },
    getCenter() { return { lat: 50, lng: 14 }; }, getZoom() { return 6; },
    getBounds() { return { getEast: () => 17, getWest: () => 11, getNorth: () => 53, getSouth: () => 47 }; },
  };
  function layer(url, options) {
    const result = { url, options, events: {}, on(name, fn) { this.events[name] = fn; return this; },
      addTo() { this.added = true; return this; }, setOpacity(value) { this.opacity = value; return this; } };
    layers.push(result);
    return result;
  }
  layer.wms = layer;
  const context = vm.createContext({
    document, URLSearchParams, AbortController, TextDecoder, console,
    Date: class extends Date { static now() { return clock; } },
    // Browser XML parsing is covered by live browser QA; these are WMS DOM fixtures.
    DOMParser: class { parseFromString(value) {
      const fixture = JSON.parse(value);
      return { getElementsByTagNameNS(_namespace, name) {
        return (fixture[name] || []).map(item => ({ textContent: item.text || '',
          getAttribute: key => item[key] ?? null }));
      } };
    } },
    window: { location: { search }, innerHeight: 480,
      addEventListener(name, callback) { events[name] = callback; } },
    setTimeout(callback, delay) { timeouts.set(++timerId, callback); timeoutDelays.set(timerId, delay); return timerId; },
    clearTimeout(id) { timeouts.delete(id); timeoutDelays.delete(id); },
    setInterval(callback) { intervals.set(++timerId, callback); return timerId; },
    clearInterval(id) { intervals.delete(id); },
    requestAnimationFrame(callback) { callback(); },
    ResizeObserver: class { constructor(callback) { events.mapResize = callback; }
      observe(element) { events.observedMap = element; } },
    fetch: (url, options) => {
      calls.push({ url, options });
      if (handler) return handler(url, options, context);
      return Promise.resolve(url.includes('maps.dwd.de')
        ? forecast ? new Response(forecastPayload(context, url.includes('Icon-eu_') ? 'eu' : 'global')) : new Response('', { status: 503 })
        : new Response(JSON.stringify(observed)));
    },
    L: { map: () => map, tileLayer: layer, imageOverlay: layer, rectangle: layer, circleMarker: layer },
  });
  vm.runInContext(readFileSync(join(__dirname, '../../main/assets/radar-forecast.js'), 'utf8'), context);
  vm.runInContext(script, context);
  return { context, document, events, layers, removed, calls, timeouts, timeoutDelays, intervals,
    node: id => document.getElementById(id),
    advance(time) { clock = time; },
    fire(id) { const callback = timeouts.get(id); timeouts.delete(id); timeoutDelays.delete(id); assert.ok(callback); callback(); },
    commit() { assert.ok(context.pendingLayer); context.pendingLayer.events.load(); },
  };
}
const forecastIndex = app => app.context.frames.findIndex(frame => frame.type === 'forecast');
function scrub(app, values) {
  const input = html.match(/id="slider"[^>]*oninput="([^"]+)"/)[1];
  for (const value of values) {
    app.node('slider').value = value;
    vm.runInContext('(function() { ' + input + ' }).call(document.getElementById("slider"))', app.context);
  }
}
function asia(app) {
  app.context.map.getBounds = () => ({ getEast: () => 145, getWest: () => 135, getNorth: () => 40, getSouth: () => 30 });
}

test('both sources load independently into a bounded, ordered epoch timeline', async () => {
  const app = runtime();
  await settled();
  assert.equal(app.calls.length, 3);
  assert.ok(app.calls.every(call => call.options.cache === 'no-store'));
  assert.equal(app.context.observedCache.frames.length, 2);
  assert.ok(app.context.forecastModels.eu.frames.length <= 50);
  assert.ok(app.context.forecastModels.global.frames.length <= 50);
  assert.ok(app.context.frames.length <= 70);
  assert.ok(app.context.frames.every((frame, index, all) => !index || frame.time > all[index - 1].time));
  assert.equal(app.node('slider').min, app.context.frames[0].time);
  assert.equal(app.node('slider').max, app.context.frames.at(-1).time);
  assert.equal(app.context.pendingLayer.frame.type, 'observed');
  app.commit();
  assert.equal(app.context.radarLayer.frame.type, 'observed');
  assert.equal(Number(app.node('slider').value), manifest().radar.past[1].time);
  assert.equal(app.node('status').hidden, true);
});

test('a slower observation request still establishes the initial observed selection', async () => {
  let finish;
  const app = runtime({ fetch: (url, _options, context) => url.includes('maps.dwd.de')
    ? Promise.resolve(new Response(forecastPayload(context, url.includes('Icon-eu_') ? 'eu' : 'global')))
    : new Promise(resolve => { finish = () => resolve(new Response(JSON.stringify(manifest()))); }) });
  await settled();
  assert.equal(app.context.pendingLayer.frame.type, 'forecast');
  finish();
  await settled();
  assert.equal(app.context.pendingLayer.frame.type, 'observed');
});

test('a user selection made before observations arrive survives their arrival', async () => {
  let finish;
  const app = runtime({ fetch: (url, _options, context) => url.includes('maps.dwd.de')
    ? Promise.resolve(new Response(forecastPayload(context, url.includes('Icon-eu_') ? 'eu' : 'global')))
    : new Promise(resolve => { finish = () => resolve(new Response(JSON.stringify(manifest()))); }) });
  await settled();
  app.context.showFrame(3);
  const selected = app.context.selectedTime;
  finish();
  await settled();
  assert.equal(app.context.selectedTime, selected);
  assert.equal(app.context.pendingLayer.frame.time, selected);
});

test('forecast failure and timeout retain healthy observations and allow an explicit retry', async () => {
  const app = runtime({ forecast: false });
  await settled();
  app.commit();
  const displayed = app.context.radarLayer;
  assert.equal(app.context.frames.length, 2);
  assert.equal(app.node('play').disabled, false);
  assert.match(app.node('status').textContent, /Model forecast unavailable/);
  app.context.fetch = (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new Error('aborted')));
  });
  app.context.loadForecast(true);
  app.fire(app.context.forecastState.timeout);
  await settled();
  assert.equal(app.context.forecastState.request, null);
  assert.equal(app.context.radarLayer, displayed);
  app.context.fetch = async url => forecastResponse(app, url);
  app.context.loadForecast(true);
  await settled();
  assert.ok(app.context.frames.some(frame => frame.type === 'forecast'));
  assert.equal(app.context.radarLayer, displayed);
});

test('invalid or oversized observations preserve a successful forecast', async () => {
  const bad = [
    null, { ...manifest(), host: 'https://untrusted.example' },
    { ...manifest(), radar: { past: [{ time: NOW / 1000 - 7200, path: '/v2/radar/stale' }] } },
    { ...manifest(), radar: { past: [{ time: NOW / 1000, path: '/unexpected' }] } },
    { ...manifest(), radar: { past: [{ time: NOW / 1000, path: '/v2/radar/../../untrusted' }] } },
    { ...manifest(), radar: { past: [{ time: NOW / 1000 + 301, path: '/v2/radar/future' }] } },
    { ...manifest(), padding: 'x'.repeat(131073) },
  ];
  for (const observed of bad) {
    const app = runtime({ observed });
    await settled();
    assert.equal(app.context.observedCache, null);
    assert.ok(app.context.frames.length >= 13);
    assert.ok(app.context.frames.every(frame => frame.type === 'forecast'));
    assert.equal(app.node('play').disabled, false);
    app.commit();
    assert.equal(app.context.radarLayer.frame.type, 'forecast');
    assert.match(app.node('status').textContent, /Observed radar is unavailable/);
  }
});

test('invalid data from both sources disables the timeline and exits loading', async () => {
  const app = runtime({ observed: null, forecast: false });
  await settled();
  assert.equal(app.context.frames.length, 0);
  assert.equal(app.node('play').disabled, true);
  assert.equal(app.node('slider').disabled, true);
  assert.equal(app.node('time').textContent, '—');
  assert.match(app.node('status').textContent, /unavailable/);
  assert.doesNotMatch(app.node('status').textContent, /Loading/);
});

test('observed manifest size, chronology and two-hour range are bounded', async () => {
  const observed = manifest();
  observed.radar.past = Array.from({ length: 100 }, (_, index) => ({
    time: NOW / 1000 - (99 - index) * 600, path: '/v2/radar/frame_' + index,
  }));
  const app = runtime({ observed });
  await settled();
  assert.ok(app.context.observedCache.frames.length <= 20);
  assert.ok(app.context.observedCache.frames.every(frame => frame.time >= NOW / 1000 - 7200));
  const duplicate = runtime({ observed: { ...manifest(), radar: { past: [manifest().radar.past[0], manifest().radar.past[0]] } } });
  await settled();
  assert.equal(duplicate.context.observedCache, null);
});

test('epoch spacing and nearest-frame snap preserve actual gaps without synthetic frames', async () => {
  const app = runtime();
  await settled();
  app.commit();
  const observed = app.context.frames[1], future = app.context.frames[2], original = app.context.frames.length;
  const gap = observed.time + (future.time - observed.time) * 0.9;
  scrub(app, [gap]);
  assert.equal(Number(app.node('slider').value), future.time);
  app.fire(app.context.seekTimer);
  assert.equal(app.context.pendingLayer.frame.time, future.time);
  app.commit();
  assert.equal(app.context.frames.length, original);
  assert.equal(app.node('timeline-hint').textContent, 'Observed snapshots · Forecast intervals · Gaps have no data.');
  const marks = app.node('timeline-marks').children;
  assert.equal(marks.length, original + 1);
  assert.equal(parseFloat(marks[1].style.left),
    (observed.time - app.context.frames[0].time) / (app.context.frames.at(-1).time - app.context.frames[0].time) * 100);
});

test('rapid scrubbing renders only the final selection and ignores an obsolete load callback', async () => {
  const app = runtime();
  await settled();
  app.commit();
  const displayed = app.context.radarLayer;
  app.context.showFrame(0);
  const obsolete = app.context.pendingLayer, count = app.layers.length;
  const target = app.context.frames[5].time;
  scrub(app, [app.context.frames[2].time, target, app.context.frames[3].time, target]);
  assert.equal(app.layers.length, count);
  obsolete.events.load();
  assert.equal(app.context.radarLayer, displayed);
  assert.equal(Number(app.node('slider').value), target);
  assert.ok(app.timeoutDelays.get(app.context.seekTimer) <= 150);
  app.fire(app.context.seekTimer);
  assert.equal(app.context.pendingLayer.frame.time, target);
  app.commit();
  assert.equal(app.context.radarLayer.frame.time, target);
});

test('play starts at a queued selection, then walks only real frames', async () => {
  const app = runtime();
  await settled();
  app.commit();
  const target = app.context.frames[0].time;
  scrub(app, [target]);
  const queued = app.context.seekTimer;
  app.context.togglePlay();
  assert.equal(app.context.seekTimer, null);
  assert.equal(app.timeouts.has(queued), false);
  assert.equal(app.context.selectedTime, target);
  app.commit();
  [...app.intervals.values()][0]();
  assert.equal(app.context.pendingLayer.frame.time, app.context.frames[1].time);
  assert.equal(app.node('play')['aria-pressed'], 'true');
});

test('keyboard arrows and Home/End advance real frames rather than getting stuck at one-second epoch steps', async () => {
  const app = runtime();
  await settled(); app.commit();
  let prevented = 0;
  const key = value => app.context.timelineKey({ key: value, preventDefault() { prevented++; } });
  key('ArrowRight');
  assert.equal(Number(app.node('slider').value), app.context.frames[2].time);
  assert.match(app.node('slider')['aria-valuetext'], /^Forecast/);
  key('ArrowLeft');
  assert.equal(Number(app.node('slider').value), app.context.frames[1].time);
  key('Home');
  assert.equal(Number(app.node('slider').value), app.context.frames[0].time);
  key('ArrowDown');
  assert.equal(Number(app.node('slider').value), app.context.frames[0].time);
  key('End');
  assert.equal(Number(app.node('slider').value), app.context.frames.at(-1).time);
  key('Tab');
  assert.equal(prevented, 5);
  assert.equal(app.calls.length, 3);
});

test('same-source transitions crossfade; observation-to-forecast and model changes hard cut', async () => {
  const app = runtime();
  await settled();
  app.commit();
  const old = app.context.radarLayer;
  app.context.showFrame(0);
  assert.equal(app.context.radarLayer, old);
  app.commit();
  assert.equal(old.opacity, 0);
  assert.equal(app.context.retiredLayer, old);
  const observed = app.context.radarLayer;
  app.context.showFrame(forecastIndex(app));
  assert.equal(app.context.pendingLayer.options.className, '');
  app.commit();
  assert.ok(app.removed.includes(observed));
  assert.equal(app.context.retiredLayer, null);
  assert.equal(app.context.transitionTimer, null);
  const europe = app.context.radarLayer;
  asia(app); app.events.moveend();
  app.commit();
  assert.ok(app.removed.includes(europe));
  assert.equal(app.context.retiredLayer, null);
  assert.equal(app.context.radarLayer.frame.key, 'global');
});

test('snapshot and accumulated interval labels, legends and coverage match the displayed source', async () => {
  const app = runtime();
  await settled();
  app.commit();
  assert.equal(app.node('frame-kind').textContent, 'Observed');
  assert.match(app.node('legend').textContent, /frame generation time.*dBZ/);
  assert.equal(app.node('observed-scale').hidden, false);
  assert.equal(app.node('scale').hidden, true);
  assert.equal(app.context.coverageLayer.added, true);
  app.context.showFrame(forecastIndex(app));
  app.commit();
  assert.equal(app.node('frame-kind').textContent, 'Forecast');
  assert.match(app.node('frame-summary').textContent, /ICON-EU.*mm \/ 1 h/);
  assert.match(app.node('time').textContent, /\d\d:\d\d–\d\d:\d\d UTC/);
  assert.match(app.node('legend').textContent, /precipitation totals/);
  assert.match(app.node('scale').src, /GetLegendGraphic/);
  assert.equal(app.context.radarLayer.options.dim_reference_time, app.context.radarLayer.frame.reference);
  assert.equal(app.node('coverage').hidden, true);
  assert.equal(app.context.coverageLayer.added, false);
  asia(app); app.events.moveend(); app.commit();
  assert.match(app.node('frame-summary').textContent, /ICON.*mm \/ 6 h/);
  for (const mark of app.node('timeline-marks').children) {
    assert.ok(parseFloat(mark.style.left) >= 0 && parseFloat(mark.style.left) <= 100);
    if (mark.className === 'forecast-mark') assert.ok(parseFloat(mark.style.left) + parseFloat(mark.style.width) <= 100.000001);
  }
});

test('failed replacement and hung panned tiles retain truthful last-frame provenance', async () => {
  const app = runtime();
  await settled();
  app.commit();
  const displayed = app.context.radarLayer;
  app.context.showFrame(forecastIndex(app));
  app.context.pendingLayer.events.tileerror();
  assert.equal(app.context.radarLayer, displayed);
  assert.equal(app.node('frame-kind').textContent, 'Observed');
  assert.match(app.node('status').textContent, /Model forecast unavailable/);
  app.context.showFrame(0);
  app.fire(app.context.tileTimeout);
  assert.equal(app.context.radarLayer, displayed);
  displayed.events.loading();
  app.fire(app.context.panTimeout);
  displayed.events.load();
  assert.equal(displayed.failed, true);
  assert.match(app.node('status').textContent, /Observed radar is unavailable/);
});

test('failed tiles cannot become successful on later loading events; explicit tile retry can recover', async () => {
  const app = runtime();
  await settled();
  app.commit();
  const layer = app.context.radarLayer;
  layer.events.loading(); layer.events.tileerror(); layer.events.load();
  layer.events.loading(); layer.events.load();
  assert.equal(layer.failed, true);
  assert.equal(layer.ready, false);
  app.context.showFrame(app.context.frameIndex);
  app.commit();
  assert.equal(app.node('status').hidden, true);
  layer.events.tileerror();
  assert.equal(app.node('status').hidden, true);
});

test('selected timestamp survives observation refresh and newly appended observations', async () => {
  const app = runtime();
  await settled();
  app.context.showFrame(0); app.commit();
  const selected = app.context.selectedTime;
  const refreshed = manifest();
  refreshed.radar.past.push({ time: NOW / 1000, path: '/v2/radar/latest' });
  app.context.fetch = async () => new Response(JSON.stringify(refreshed));
  app.context.loadRadar(true);
  await settled();
  assert.equal(app.context.selectedTime, selected);
  assert.equal(app.context.pendingLayer.frame.time, selected);
  app.commit();
  assert.equal(app.context.radarLayer.frame.time, selected);
});

test('refresh and panning preserve a queued scrub timestamp before its render deadline', async () => {
  const app = runtime();
  await settled(); app.commit();
  const target = app.context.frames.at(-1).time;
  scrub(app, [target]);
  const queued = app.context.seekTimer;
  asia(app); app.events.moveend();
  assert.equal(app.context.selectedTime, target);
  assert.equal(Number(app.node('slider').value), target);
  app.context.refreshMap();
  assert.equal(app.timeouts.has(queued), false);
  assert.equal(app.context.pendingLayer.frame.time, app.context.frames[app.context.nearestFrame(target)].time);
  await settled();
  assert.equal(app.context.selectedTime, target);
});

test('failed direct refresh discards only expired caches and has no immediate automatic retry', async () => {
  const app = runtime();
  await settled(); app.commit();
  app.advance(NOW + 8 * 3600000 + 1);
  let calls = 0;
  app.context.fetch = async () => { calls++; return new Response('', { status: 503 }); };
  app.context.loadRadar(true); app.context.loadForecast(true);
  await settled();
  assert.equal(calls, 3);
  assert.equal(app.context.observedCache, null);
  assert.equal(app.context.forecastModels.eu, null);
  app.context.ensureSourcesFresh(); app.events.moveend();
  assert.equal(calls, 3);
});

test('panning uses cached regional/global grids and retains the requested epoch across both', async () => {
  const app = runtime();
  await settled();
  app.context.showFrame(app.context.frames.length - 1); app.commit();
  const selected = app.context.selectedTime, europe = app.context.forecastCache;
  const originalBounds = app.context.map.getBounds;
  asia(app); app.events.moveend();
  assert.equal(app.calls.length, 3);
  assert.equal(app.context.forecastCache.key, 'global');
  assert.equal(app.context.selectedTime, selected);
  assert.equal(app.context.pendingLayer.frame.time, app.context.frames[app.context.nearestFrame(selected)].time);
  app.commit();
  app.context.map.getBounds = originalBounds; app.events.moveend(); app.commit();
  assert.equal(app.context.forecastCache, europe);
  assert.equal(app.context.radarLayer.frame.time, selected);
  const displayed = app.context.radarLayer;
  app.events.moveend();
  assert.equal(app.context.radarLayer, displayed);
  assert.equal(app.context.pendingLayer, null);
});

test('missing global coverage preserves observations and returning to Europe restores its cached model', async () => {
  const app = runtime({ fetch: (url, _options, context) => Promise.resolve(url.includes('Icon_reg025_')
    ? new Response('', { status: 429 })
    : new Response(url.includes('maps.dwd.de') ? forecastPayload(context) : JSON.stringify(manifest()))) });
  await settled();
  app.context.showFrame(forecastIndex(app)); app.commit();
  const selected = app.context.selectedTime, bounds = app.context.map.getBounds;
  asia(app); app.events.moveend();
  assert.equal(app.context.frames.length, 2);
  assert.equal(app.context.forecastCache, null);
  assert.equal(app.node('play').disabled, false);
  assert.equal(app.context.pendingLayer.frame.type, 'observed');
  assert.equal(app.calls.length, 3);
  app.context.map.getBounds = bounds; app.events.moveend();
  assert.equal(app.context.forecastCache.key, 'eu');
  assert.equal(app.context.pendingLayer.frame.time, selected);
});

test('forecast expiration never erases a newer healthy observation segment or duplicates requests', async () => {
  const app = runtime();
  await settled();
  app.commit();
  app.advance(NOW + 300000);
  app.context.observedCache.loadedAt = NOW + 300000;
  app.advance(NOW + 600001);
  for (const model of Object.values(app.context.forecastModels)) model.run -= 12 * 3600;
  let calls = 0;
  app.context.fetch = async () => { calls++; return new Response('', { status: 503 }); };
  app.context.ensureSourcesFresh();
  assert.equal(app.context.forecastCache, null);
  assert.equal(app.context.frames.length, 2);
  assert.equal(app.node('play').disabled, false);
  assert.equal(calls, 2);
  app.context.ensureSourcesFresh();
  assert.equal(calls, 2);
  await settled();
  app.context.ensureSourcesFresh(); app.events.moveend();
  assert.equal(calls, 2, 'automatic failures must not create a retry loop');
  assert.match(app.node('status').textContent, /Model forecast unavailable/);
});

test('observation expiration never erases a newer healthy forecast segment', async () => {
  const app = runtime();
  await settled();
  app.commit();
  app.advance(NOW + 600001);
  for (const frame of app.context.observedCache.frames) frame.time -= 3600;
  for (const model of Object.values(app.context.forecastModels)) model.loadedAt = NOW + 300000;
  app.context.fetch = async () => new Response('', { status: 503 });
  app.context.ensureSourcesFresh();
  assert.equal(app.context.observedCache, null);
  assert.ok(app.context.frames.every(frame => frame.type === 'forecast'));
  assert.equal(app.node('slider').disabled, false);
  app.commit();
  await settled();
  assert.equal(app.context.radarLayer.frame.type, 'forecast');
  assert.match(app.node('status').textContent, /Observed radar is unavailable/);
});

test('forecast refresh is due at metadata TTL while hard usability depends on model age and horizon', async () => {
  const app = runtime();
  await settled();
  const cached = app.context.forecastCache;
  assert.equal(app.context.forecastIsUsable(cached), true);
  app.advance(cached.loadedAt + 599999);
  assert.equal(app.context.forecastIsUsable(cached), true);
  assert.equal(app.context.refreshDue(cached), false);
  app.advance(cached.loadedAt + 600000);
  assert.equal(app.context.forecastIsUsable(cached), true);
  assert.equal(app.context.refreshDue(cached), true);
  app.advance(NOW);
  cached.run = NOW / 1000 - 12 * 3600 - 1;
  assert.equal(app.context.forecastIsUsable(cached), false);
  cached.run = NOW / 1000;
  cached.frames.at(-1).time = NOW / 1000 + 12 * 3600 - 1;
  assert.equal(app.context.forecastIsUsable(cached), false);
});

for (const action of ['seek', 'play', 'tick', 'commit', 'idle', 'online', 'resume']) {
  test('expiry is checked by ' + action + ' without displaying expired tiles', async () => {
    const app = runtime();
    await settled();
    if (action !== 'commit') app.commit();
    if (action === 'tick') app.context.togglePlay();
    app.advance(NOW + 8 * 3600000 + 1);
    app.context.fetch = async () => new Response('', { status: 503 });
    if (action === 'seek') app.context.showFrame(1);
    if (action === 'play') app.context.togglePlay();
    if (action === 'tick') [...app.intervals.values()][0]();
    if (action === 'commit') app.context.pendingLayer.events.load();
    if (action === 'idle') app.fire(app.context.expiryTimer);
    if (action === 'online') app.events.online();
    if (action === 'resume') app.events.visibilitychange();
    await settled();
    assert.equal(app.context.frames.length, 0);
    assert.equal(app.context.radarLayer, null);
    assert.equal(app.node('play').disabled, true);
    assert.equal(app.node('time').textContent, '—');
    assert.doesNotMatch(app.node('status').textContent, /Loading/);
  });
}

test('a forecast loaded at hh:59 survives the next hour until its real age deadline', async () => {
  const app = runtime();
  await settled();
  app.advance(Date.UTC(2026, 9, 11, 10, 59));
  for (const model of Object.values(app.context.forecastModels)) model.loadedAt = app.context.Date.now();
  const cached = app.context.forecastCache;
  app.advance(Date.UTC(2026, 9, 11, 11, 0, 1));
  assert.equal(app.context.forecastIsUsable(cached), true);
});

test('hidden and native-inactive pages cancel requests and do not poll on seek, pan, expiry or online', async () => {
  for (const native of [false, true]) {
    const finishes = [], signals = [];
    const app = runtime({ fetch: (url, options, context) => {
      signals.push(options.signal);
      return new Promise(resolve => finishes.push(() => resolve(new Response(url.includes('maps.dwd.de')
        ? forecastPayload(context, url.includes('Icon-eu_') ? 'eu' : 'global') : JSON.stringify(manifest())))));
    } });
    if (native) app.context.window.setNativeActive(false);
    else { app.document.hidden = true; app.events.visibilitychange(); }
    assert.ok(signals.every(signal => signal.aborted));
    finishes.forEach(finish => finish());
    await settled();
    assert.equal(app.context.frames.length, 0, 'late canceled responses cannot install data');
    assert.equal(app.context.observedState.request, null);
    assert.equal(app.context.forecastState.request, null);
    assert.equal(app.timeouts.size, 0);
    app.events.online(); app.events.moveend(); app.context.ensureSourcesFresh(); app.context.refreshMap();
    assert.equal(app.calls.length, 3);
  }
});

for (const committed of [false, true]) {
  test('queued seek resumes after visibility cancellation, committed=' + committed, async () => {
    const app = runtime();
    await settled();
    if (committed) app.commit();
    const selected = app.context.frames[0].time;
    scrub(app, [selected]);
    const queued = app.context.seekTimer;
    app.document.hidden = true; app.events.visibilitychange();
    assert.equal(app.timeouts.has(queued), false);
    assert.equal(app.context.timer, null);
    app.document.hidden = false; app.events.visibilitychange();
    assert.equal(app.context.pendingLayer.frame.time, selected);
    app.commit();
    assert.equal(app.context.radarLayer.frame.time, selected);
    assert.equal(app.node('status').hidden, true);
  });
}

test('native resume refreshes expired sources once and preserves selected epoch', async () => {
  const app = runtime();
  await settled();
  app.commit();
  const selected = app.context.selectedTime;
  app.context.window.setNativeActive(false);
  app.advance(NOW + 600001);
  app.context.window.setNativeActive(true);
  await settled();
  assert.equal(app.context.selectedTime, selected);
  assert.equal(app.calls.filter(call => call.url.includes('maps.dwd.de')).length, 4);
  assert.equal(app.calls.filter(call => call.url.includes('rainviewer')).length, 2);
  app.context.window.setNativeActive(true);
  assert.equal(app.calls.length, 6);
});

test('native-inactive startup makes no requests until explicitly activated', async () => {
  const app = runtime({ search: '?nativeActive=0' });
  await settled();
  assert.equal(app.calls.length, 0);
  assert.equal(app.context.frames.length, 0);
  app.context.window.setNativeActive(true);
  await settled();
  assert.equal(app.calls.length, 3);
  assert.equal(app.context.pendingLayer.frame.type, 'observed');
});

test('model run age schedules expiration before the metadata TTL', async () => {
  const app = runtime({ observed: null });
  await settled();
  for (const model of Object.values(app.context.forecastModels)) model.run = NOW / 1000 - 12 * 3600 + 60;
  app.context.scheduleExpiry();
  assert.equal(app.timeoutDelays.get(app.context.expiryTimer), 60001);
});

test('due metadata refresh retains healthy caches, the displayed epoch and playback through a failed update', async () => {
  const app = runtime();
  await settled(); app.commit(); app.context.togglePlay();
  const displayed = app.context.radarLayer, selected = app.context.selectedTime, playing = app.context.timer;
  const observed = app.context.observedCache, europe = app.context.forecastCache, count = app.context.frames.length;
  const finishes = [];
  app.context.fetch = () => new Promise(resolve => finishes.push(() => resolve(new Response('', { status: 503 }))));
  app.advance(NOW + 600001);
  app.fire(app.context.expiryTimer);
  assert.equal(finishes.length, 3);
  assert.equal(app.context.radarLayer, displayed);
  assert.equal(app.context.selectedTime, selected);
  assert.equal(app.context.timer, playing);
  assert.equal(app.context.frames.length, count);
  assert.equal(app.node('play').disabled, false);
  finishes.forEach(finish => finish()); await settled();
  assert.equal(app.context.observedCache, observed);
  assert.equal(app.context.forecastCache, europe);
  assert.equal(app.context.radarLayer, displayed);
  assert.equal(app.context.timer, playing);
  assert.match(app.node('status').textContent, /previous snapshot.*previous forecast/);
  app.context.ensureSourcesFresh(); app.events.moveend();
  assert.equal(finishes.length, 3);
  assert.ok(app.timeoutDelays.get(app.context.expiryTimer) >= 599999);
});

test('hiding during a pan cancels its tile deadline and resumes with a healthy replacement', async () => {
  const app = runtime();
  await settled(); app.commit();
  const displayed = app.context.radarLayer;
  displayed.events.loading();
  const panDeadline = app.context.panTimeout;
  app.context.window.setNativeActive(false);
  assert.equal(app.context.panTimeout, null);
  assert.equal(app.timeouts.has(panDeadline), false);
  assert.equal(displayed.failed, false);
  displayed.events.loading();
  assert.equal(app.context.panTimeout, null, 'late hidden tile events cannot rearm the pan deadline');
  app.advance(NOW + 16000);
  app.context.window.setNativeActive(true);
  assert.ok(app.context.pendingLayer);
  assert.equal(app.calls.length, 3);
  app.commit();
  assert.equal(app.context.radarLayer.failed, false);
  assert.equal(app.node('status').hidden, true);
});

test('replacement timeout and tileerror restore the epoch and thumb to the actual displayed frame', async () => {
  for (const timeout of [false, true]) {
    const app = runtime();
    await settled(); app.commit();
    const displayed = app.context.radarLayer;
    app.context.showFrame(forecastIndex(app));
    assert.notEqual(app.context.selectedTime, displayed.frame.time);
    if (timeout) app.fire(app.context.tileTimeout);
    else app.context.pendingLayer.events.tileerror();
    assert.equal(app.context.selectedTime, displayed.frame.time);
    assert.equal(Number(app.node('slider').value), displayed.frame.time);
    assert.equal(app.context.frames[app.context.frameIndex].time, displayed.frame.time);
    assert.equal(app.context.radarLayer, displayed);
  }
});

test('an error on the displayed layer preserves a different pending selection through commit and refresh', async () => {
  const app = runtime();
  await settled(); app.commit();
  const displayed = app.context.radarLayer;
  displayed.events.loading();
  app.context.showFrame(forecastIndex(app));
  const selected = app.context.pendingLayer.frame.time;
  displayed.events.tileerror();
  assert.equal(app.context.selectedTime, selected);
  assert.equal(Number(app.node('slider').value), selected);
  app.commit();
  assert.equal(app.context.radarLayer.frame.time, selected);
  app.context.refreshMap(); await settled();
  assert.equal(app.context.selectedTime, selected);
  assert.equal(app.context.pendingLayer.frame.time, selected);
  app.commit();
  assert.equal(app.context.radarLayer.frame.time, selected);
});

test('canceling a background update clears loading and resume starts each request only once', async () => {
  const app = runtime();
  await settled(); app.commit();
  let calls = 0;
  app.context.fetch = (_url, options) => {
    calls++;
    return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted'))));
  };
  app.advance(NOW + 600001); app.context.ensureSourcesFresh();
  assert.equal(calls, 3);
  app.context.window.setNativeActive(false); await settled();
  assert.equal(app.context.observedState.message, '');
  assert.equal(app.context.forecastState.message, '');
  assert.doesNotMatch(app.node('status').textContent, /Loading/);
  app.context.window.setNativeActive(true);
  assert.equal(calls, 6);
  app.context.window.setNativeActive(true); app.events.visibilitychange();
  assert.equal(calls, 6);
});

test('canceling an allowed retry of failed sources permits one immediate retry on resume', async () => {
  const app = runtime({ observed: null, forecast: false });
  await settled();
  assert.equal(app.context.observedState.failed, true);
  assert.ok(Object.values(app.context.forecastStates).every(state => state.failed));
  const finishes = [];
  app.context.fetch = (_url, options) => new Promise((resolve, reject) => {
    finishes.push(() => resolve(new Response('', { status: 503 })));
    options.signal.addEventListener('abort', () => reject(new Error('aborted')));
  });
  app.advance(NOW + 600001); app.context.ensureSourcesFresh();
  assert.equal(finishes.length, 3);
  app.context.window.setNativeActive(false); await settled();
  app.context.window.setNativeActive(true);
  assert.equal(finishes.length, 6);
  app.context.window.setNativeActive(true); app.events.visibilitychange();
  assert.equal(finishes.length, 6);
  finishes.slice(3).forEach(finish => finish()); await settled();
  assert.equal(app.context.observedState.failed, true);
  assert.ok(Object.values(app.context.forecastStates).every(state => state.failed));
  app.context.ensureSourcesFresh();
  assert.equal(finishes.length, 6, 'a completed failed retry still enforces bounded automatic retries');
});

test('forecast seeking chooses the containing accumulated interval while observed times retain snapshots', async () => {
  const app = runtime();
  await settled(); asia(app); app.events.moveend();
  const first = app.context.forecastCache.frames[0], next = app.context.forecastCache.frames[1];
  const insideNext = first.time + 3600;
  assert.ok(insideNext - first.time < next.time - insideNext, 'nearest endpoint would choose the wrong total');
  assert.equal(app.context.frames[app.context.nearestFrame(insideNext)].time, next.time);
  assert.equal(app.context.frames[app.context.nearestFrame(first.time)].time, first.time);
  const observed = app.context.observedCache.frames.at(-1);
  assert.equal(app.context.frames[app.context.nearestFrame(observed.time)].type, 'observed');
  scrub(app, [insideNext]); app.fire(app.context.seekTimer);
  assert.equal(app.context.pendingLayer.frame.time, next.time);
});

test('DST-crossing interval labels include both timezone offsets instead of ambiguous repeated hours', () => {
  const app = runtime({ search: '?lang=en&tz=America%2FNew_York' });
  const autumn = Date.parse('2026-11-01T06:00:00Z') / 1000;
  const spring = Date.parse('2026-03-08T07:00:00Z') / 1000;
  assert.match(app.context.forecastIntervalLabel({ time: autumn, hours: 1 }), /01:00 EDT–01:00 EST/);
  assert.match(app.context.forecastIntervalLabel({ time: spring, hours: 1 }), /01:00 EST–03:00 EDT/);
});

test('a cached regional model cannot reset a failed global model during repeated panning', async () => {
  const app = runtime({ fetch: (url, _options, context) => Promise.resolve(url.includes('Icon_reg025_')
    ? new Response('', { status: 503 })
    : new Response(url.includes('maps.dwd.de') ? forecastPayload(context) : JSON.stringify(manifest()))) });
  await settled();
  const bounds = app.context.map.getBounds;
  assert.equal(app.context.forecastStates.global.failed, true);
  for (let turn = 0; turn < 3; turn++) {
    asia(app); app.events.moveend();
    app.context.map.getBounds = bounds; app.events.moveend();
  }
  assert.equal(app.context.forecastStates.global.failed, true);
  assert.equal(app.calls.length, 3);
  app.advance(NOW + 610000);
  app.context.ensureSourcesFresh();
  await settled();
  assert.equal(app.calls.filter(call => call.url.includes('Icon_reg025_')).length, 2);
});

test('429 backoff survives manual refresh, pan and online without clearing a healthy other source', async () => {
  for (const busy of ['observed', 'forecast']) {
    const app = runtime({ fetch: (url, _options, context) => {
      const model = url.includes('maps.dwd.de');
      return Promise.resolve((model === (busy === 'forecast'))
        ? new Response('', { status: 429 })
        : new Response(model ? forecastPayload(context, url.includes('Icon-eu_') ? 'eu' : 'global') : JSON.stringify(manifest())));
    } });
    await settled();
    const initialBusyCalls = app.calls.filter(call => call.url.includes(busy === 'forecast' ? 'maps.dwd.de' : 'api.rainviewer')).length;
    app.context.refreshMap(); app.events.moveend(); app.events.online();
    await settled();
    assert.equal(app.calls.filter(call => call.url.includes(busy === 'forecast' ? 'maps.dwd.de' : 'api.rainviewer')).length, initialBusyCalls);
    assert.ok(app.context.frames.length);
    assert.equal(app.node('play').disabled, false);
  }
});

test('superseded observation requests cannot replace later successful frames', async () => {
  const app = runtime();
  await settled();
  const finishes = [], signals = [];
  app.context.fetch = (_url, options) => {
    signals.push(options.signal);
    return new Promise(resolve => finishes.push(value => resolve(new Response(JSON.stringify(value)))));
  };
  app.context.loadRadar(true); app.context.loadRadar(true);
  const latest = manifest(); latest.radar.past[1].path = '/v2/radar/new';
  finishes[1](latest); await settled();
  finishes[0](manifest()); await settled();
  assert.equal(signals[0].aborted, true);
  assert.equal(app.context.observedCache.frames[1].path, '/v2/radar/new');
});

test('bounded metadata decoder accepts UTF-8 and rejects oversized or malformed bytes', async () => {
  const app = runtime();
  const read = app.context.RadarForecast.read;
  assert.equal(await read(new Response('Niederschläge')), 'Niederschläge');
  await assert.rejects(read(new Response('x'.repeat(131073))), /too large/);
  await assert.rejects(read(new Response(new Uint8Array([255]))), /encoded data/);
});

test('forecast provider uses official interval grids and full-viewport coverage selection', () => {
  const app = runtime();
  const provider = app.context.RadarForecast;
  assert.equal(provider.endpoint, 'https://maps.dwd.de/geoserver/wms');
  assert.equal(provider.sources.eu.hours, 1);
  assert.equal(provider.sources.global.hours, 6);
  assert.equal(provider.grid, undefined);
  assert.doesNotMatch(html, /api\.open-meteo\.com/);
  const europe = provider.parse(forecastPayload(app.context), 'eu', NOW / 1000).bounds;
  assert.equal(provider.sourceFor({ west: 11, east: 17, south: 47, north: 53 }, europe), 'eu');
  assert.equal(provider.sourceFor({ west: 371, east: 377, south: 47, north: 53 }, europe), 'eu');
  for (const bounds of [
    { west: 11, east: 70, south: 47, north: 53 },
    { west: -180, east: 180, south: 47, north: 53 },
    { west: 175, east: 190, south: 0, north: 10 },
  ]) assert.equal(provider.sourceFor(bounds, europe), 'global');
});

test('fresh main cycles supply hourly outlooks even when default reference is intermediate', () => {
  const app = runtime(), payload = JSON.parse(forecastPayload(app.context));
  const main = Date.parse(payload.Dimension[1].default), intermediate = main + 3 * 3600000;
  payload.Dimension[1].default = new Date(intermediate).toISOString();
  payload.Dimension[1].text += ',' + payload.Dimension[1].default;
  const parsed = app.context.RadarForecast.parse(JSON.stringify(payload), 'eu', intermediate / 1000 + 3600);
  assert.equal(parsed.run, main / 1000);
  assert.ok(parsed.frames.every(frame => frame.reference === new Date(main).toISOString()));
});

test('malformed/stale metadata and far-future ISO ranges fail closed with bounded traversal', () => {
  const app = runtime();
  for (const corrupt of [
    payload => { payload.Name[0].text = 'untrusted'; },
    payload => { payload.Dimension[0].units = 'hours'; },
    payload => { payload.Dimension[0].text = 'invalid'; },
    payload => { payload.Dimension[1].default = new Date(NOW - 86400000).toISOString(); },
    payload => { payload.Dimension[1].default = new Date(NOW + 3600000).toISOString(); },
    payload => { payload.Dimension[1].text = ''; },
    payload => { payload.Dimension[0].text = payload.Dimension[0].text.replace(/\/[^/]+\/PT/, '/' + new Date(NOW + 3600000).toISOString() + '/PT'); },
    payload => { payload.Dimension[0].text = '+200000-01-01T00:00:00Z/+200000-01-10T00:00:00Z/PT1H'; },
    payload => { payload.BoundingBox[0].minx = 'NaN'; },
    payload => { payload.BoundingBox[0].maxx = '-90'; },
    payload => { payload.parsererror = [{ text: 'Bad XML' }]; },
  ]) {
    const payload = JSON.parse(forecastPayload(app.context));
    corrupt(payload);
    assert.throws(() => app.context.RadarForecast.parse(JSON.stringify(payload), 'eu', NOW / 1000));
  }
  assert.throws(() => app.context.RadarForecast.parse('x'.repeat(131073), 'eu', NOW / 1000));
  assert.throws(() => app.context.RadarForecast.parse('<!DOCTYPE x>', 'eu', NOW / 1000));
});

test('model frames retain their true accumulation step and only promise available provider hours', () => {
  const app = runtime();
  for (const key of ['eu', 'global']) {
    const parsed = app.context.RadarForecast.parse(forecastPayload(app.context, key), key, NOW / 1000);
    const step = key === 'eu' ? 3600 : 21600;
    assert.ok(parsed.frames.length >= 9 && parsed.frames.length <= 50);
    assert.ok(parsed.frames[0].time > NOW / 1000 && parsed.frames[0].time <= NOW / 1000 + step);
    assert.ok(parsed.frames.at(-1).time >= NOW / 1000 + 48 * 3600 + 600);
    assert.ok(parsed.frames.every((frame, index) => frame.hours * 3600 === step &&
      frame.time % step === 0 && (!index || frame.time === parsed.frames[index - 1].time + step)));
  }
  const payload = JSON.parse(forecastPayload(app.context));
  payload.Dimension[0].text = payload.Dimension[0].text.replace(/\/[^/]+\/PT/, '/' + new Date(NOW + 24 * 3600000).toISOString() + '/PT');
  const partial = app.context.RadarForecast.parse(JSON.stringify(payload), 'eu', NOW / 1000);
  assert.ok(partial.frames.at(-1).time <= NOW / 1000 + 24 * 3600);
});

test('timeline and overnight accumulated intervals use the selected timezone with UTC fallback', () => {
  const local = runtime({ search: '?lang=en&tz=Europe%2FPrague' });
  assert.match(local.context.formatTime(Date.UTC(2026, 8, 19, 11) / 1000), /13:00/);
  const invalid = runtime({ search: '?tz=not-a-zone' });
  assert.match(invalid.context.formatTime(Date.UTC(2026, 8, 19, 11) / 1000), /11:00/);
  const app = runtime({ search: '?lang=en&tz=America%2FNew_York' });
  const overnight = Date.parse('2026-09-23T06:00:00Z') / 1000;
  assert.match(app.context.forecastIntervalLabel({ time: overnight, hours: 6 }), /Tue 20:00–Wed 02:00/);
  assert.match(app.context.forecastIntervalLabel({ time: overnight + 21600, hours: 6 }), /^Wed 02:00–08:00/);
});

test('appearance changes preserve map state and validate both native insets', async () => {
  const app = runtime();
  await settled();
  const count = app.layers.length, selected = app.context.selectedTime;
  app.context.window.setAppearance({ bg: '#FFFFFF', text: '#17222B', surface: 'url(https://bad.invalid/)', topInset: 88, bottomInset: 76 });
  assert.equal(app.document.documentElement.style['--bg'], '#FFFFFF');
  assert.equal(app.document.documentElement.style['--text'], '#17222B');
  assert.equal(app.document.documentElement.style['--surface'], undefined);
  assert.equal(app.document.documentElement.style['--header-inset'], '88px');
  assert.equal(app.document.documentElement.style['--bottom-inset'], '76px');
  assert.equal(app.layers.length, count);
  assert.equal(app.context.selectedTime, selected);
  app.context.window.setAppearance({ topInset: -1, bottomInset: 241 });
  assert.equal(app.document.documentElement.style['--header-inset'], '88px');
  assert.equal(app.document.documentElement.style['--bottom-inset'], '76px');
});

test('timezone-only appearance updates labels without fetching, recreating layers or changing a pending seek', async () => {
  const app = runtime();
  await settled(); app.commit();
  const displayed = app.context.radarLayer;
  const index = app.context.frameIndex;
  const before = app.node('time').textContent;
  const target = app.context.frames[5].time;
  scrub(app, [target]);
  const queued = app.context.seekTimer, count = app.layers.length;
  app.context.window.setAppearance({ timezone: 'Asia/Tokyo' });
  assert.equal(app.context.timezone, 'Asia/Tokyo');
  assert.notEqual(app.node('time').textContent, before);
  assert.equal(app.context.radarLayer, displayed);
  assert.equal(app.context.frameIndex, index);
  assert.equal(app.context.selectedTime, target);
  assert.equal(Number(app.node('slider').value), target);
  assert.equal(app.context.seekTimer, queued);
  assert.equal(app.layers.length, count);
  assert.equal(app.calls.length, 3);
  app.context.window.setAppearance({ timezone: 'not-a-zone' });
  assert.equal(app.context.timezone, 'Asia/Tokyo');
  app.context.window.setAppearance({ timezone: null });
  assert.equal(app.context.timezone, 'UTC');
});

test('short viewports bound scrollable sheets below toolbar and credits, then recover on resize', () => {
  const app = runtime();
  app.node('radar-tools').offsetHeight = 48;
  app.node('credits').offsetHeight = 36;
  app.context.window.setAppearance({ topInset: 88, bottomInset: 76 });
  app.context.window.innerHeight = 400; app.events.resize();
  assert.equal(app.document.body.classList.short, true);
  assert.equal(app.node('panel').style.maxHeight, '104px');
  assert.equal(app.document.body.classList['details-over-panel'], true);
  assert.equal(app.document.documentElement.style['--details-bottom'], '88px');
  assert.equal(app.document.documentElement.style['--details-height'], '108px');
  app.context.toggleDetails();
  assert.equal(app.document.body.classList['details-open'], true);
  app.node('.topline').offsetHeight = 80;
  app.context.window.innerHeight = 180; app.events.resize();
  assert.equal(app.node('panel').style.maxHeight, '94px');
  assert.match(html, /body\.short \.topline\s*\{[^}]*order:\s*-1[^}]*flex-shrink:\s*0/);
  app.context.window.innerHeight = 744; app.events.resize();
  assert.equal(app.document.body.classList.short, false);
  assert.equal(app.node('panel').style.maxHeight, '334.8px');
});

test('root sizing, details and layout changes invalidate maps without network requests', async () => {
  const app = runtime();
  await settled();
  assert.equal(app.document.documentElement.style.height, '480px');
  assert.equal(app.document.body.style.height, '480px');
  assert.equal(app.events.observedMap, app.node('map'));
  app.context.fetch = () => { throw new Error('Layout must not fetch'); };
  app.context.window.innerHeight = 720; app.events.resize();
  assert.equal(app.document.body.style.height, '720px');
  assert.equal(app.node('map').style.height, undefined);
  app.context.window.innerHeight = 0; app.events.resize();
  assert.equal(app.document.body.style.height, '720px');
  app.context.toggleDetails();
  assert.equal(app.node('details').hidden, false);
  assert.equal(app.node('details-toggle')['aria-expanded'], 'true');
  app.context.toggleDetails();
  assert.equal(app.node('details').hidden, true);
  assert.equal(app.node('details-toggle')['aria-expanded'], 'false');
  const before = app.events.invalidations;
  app.events.mapResize();
  assert.equal(app.events.invalidations, before + 1);
});

test('fullscreen controls keep 48px hit areas, separate details, attribution and strict provider CSP', () => {
  assert.match(html, /#map\s*\{[^}]*flex:\s*1[^}]*min-height:\s*0/);
  assert.match(html, /\.panel, \.details\s*\{[^}]*position:\s*absolute/);
  assert.match(html, /\.tools\s*\{[^}]*position:\s*absolute/);
  assert.match(html, /button\s*\{[^}]*min-width:\s*48px[^}]*min-height:\s*48px/);
  assert.match(html, /input\[type=range\]\s*\{[^}]*height:\s*48px/);
  assert.match(html, /\.status\[hidden\]\s*\{[^}]*display:\s*none/);
  assert.ok(html.indexOf('id="details"') > html.indexOf('id="status"'));
  assert.doesNotMatch(script, /function setMode|JavascriptInterface|getElementById\('map'\)\.style\.height\s*=/);
  assert.doesNotMatch(html, /id="observed"|id="forecast"/);
  assert.match(html, /connect-src https:\/\/api\.rainviewer\.com https:\/\/maps\.dwd\.de;/);
  assert.equal(runtime().events.attributionPosition, 'topright');
});

test('invalid coordinates and frame indexes preserve safe location and selection', async () => {
  const app = runtime({ search: '?lat=91&lon=999' });
  await settled();
  assert.deepEqual(Array.from(app.events.center), [50.0755, 14.4378]);
  assert.equal(app.context.coordinate(null, -90, 90, 50), 50);
  assert.equal(app.context.coordinate('0', -90, 90, 50), 0);
  const selected = app.context.selectedTime;
  app.context.showFrame(NaN); app.context.seekTime(Infinity);
  assert.equal(app.context.selectedTime, selected);
});
