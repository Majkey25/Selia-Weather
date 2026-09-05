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
  let timerId = 0;
  const document = {
    hidden: false,
    documentElement: {},
    getElementById(id) {
      if (!nodes.has(id)) nodes.set(id, { style: {}, setAttribute(key, value) { this[key] = value; } });
      return nodes.get(id);
    },
    addEventListener(name, callback) { events[name] = callback; },
  };
  const map = { setView() { return this; }, removeLayer() {}, invalidateSize() {} };
  const context = vm.createContext({
    document, URLSearchParams, AbortController, Date, console,
    window: { location: { search: '?lat=50&lon=14' }, innerHeight: 480,
      addEventListener(name, callback) { events[name] = callback; } },
    setTimeout(callback) { timeouts.set(++timerId, callback); return timerId; },
    clearTimeout(id) { timeouts.delete(id); },
    setInterval() { return ++timerId; }, clearInterval() {},
    fetch: async () => ({ ok: true, json: async () => response }),
    L: {
      map: () => map,
      tileLayer(url) {
        const layer = { url, events: {}, on(name, fn) { this.events[name] = fn; }, addTo() { return this; } };
        layers.push(layer);
        return layer;
      },
    },
  });
  vm.runInContext(script, context);
  return { context, document, events, layers, timeouts, node: id => document.getElementById(id) };
}
const settled = () => new Promise(resolve => setImmediate(resolve));

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
  app.layers.at(-1).events.load();
  layer.events.tileerror();
  assert.equal(app.node('status').hidden, true, 'retired layer cannot change current status');
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
