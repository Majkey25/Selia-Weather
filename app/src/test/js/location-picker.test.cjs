const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const html = readFileSync(join(__dirname, '../../main/assets/location_picker.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function runtime(search = '?lat=50&lon=14') {
  const events = {};
  const selections = [];
  const views = [];
  const map = {
    setView(point) { views.push([...point]); return this; },
    panTo(point) { views.push([...point]); },
    invalidateSize() {},
    on(name, callback) { events[name] = callback; },
  };
  const pin = {
    addTo() { return this; },
    setLatLng(point) { this.point = [...point]; },
    getLatLng() { return { lat: this.point[0], lng: this.point[1] }; },
  };
  const context = vm.createContext({
    URLSearchParams, location: { search },
    document: { getElementById() { return {}; } },
    ResizeObserver: class { observe() {} }, requestAnimationFrame(callback) { callback(); },
    window: { LocationBridge: { onLocationSelected(...point) { selections.push(point); } } },
    L: {
      map: () => map,
      tileLayer: () => ({ addTo() {} }),
      circleMarker(point) { pin.point = [...point]; return pin; },
    },
  });
  vm.runInContext(script, context);
  return { context, pin, events, selections, views };
}

test('typed coordinates move the existing pin without notifying native fields again', () => {
  const app = runtime();
  app.context.window.updateLocationPin(-33.8688, 151.2093);
  assert.deepEqual(app.pin.point, [-33.8688, 151.2093]);
  assert.deepEqual(app.views.at(-1), [-33.8688, 151.2093]);
  assert.deepEqual(app.selections, []);
  app.context.window.updateLocationPin(-33.8688, 151.2093);
  assert.equal(app.views.length, 2, 'same point must not recenter a map the user panned');
});

test('map clicks wrap longitude and a rounded native echo does not pan the map', () => {
  const app = runtime();
  app.events.click({ latlng: { lat: 12.12345678, lng: 190 } });
  assert.deepEqual(app.pin.point, [12.12345678, -170]);
  assert.deepEqual(app.selections, [[12.12345678, -170]]);
  app.context.window.updateLocationPin(12.123457, -170);
  assert.equal(app.views.length, 1);
});

test('invalid native coordinates do not move the pin and missing URL values keep defaults', () => {
  const app = runtime('?lat=&lon=');
  assert.deepEqual(app.pin.point, [50.0755, 14.4378]);
  for (const point of [[NaN, 14], [91, 14], [50, 181], [null, null], ['50', '14']]) {
    app.context.window.updateLocationPin(...point);
    assert.deepEqual(app.pin.point, [50.0755, 14.4378]);
  }
});
