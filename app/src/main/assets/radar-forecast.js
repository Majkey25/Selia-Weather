/* Official DWD WMS precipitation totals. Values describe the preceding 1 / 6 hours.
 * https://www.dwd.de/geodienste · https://www.dwd.de/copyright */
var RadarForecast = (function() {
  var endpoint = 'https://maps.dwd.de/geoserver/wms';
  var sources = {
    eu: { layer: 'Icon-eu_reg00625_fd_sl_TOTPREC01H', model: 'ICON-EU', hours: 1,
      style: 'icon-eu_reg00625_fd_sl_totprec01h_lawa' },
    global: { layer: 'Icon_reg025_fd_sl_TOTPREC06H', model: 'ICON', hours: 6,
      style: 'icon_reg025_fd_sl_totprec06h_wmc_isoarea' }
  };

  function capabilitiesUrl(key) {
    return 'https://maps.dwd.de/geoserver/dwd/' + sources[key].layer +
      '/wms?service=WMS&version=1.3.0&request=GetCapabilities';
  }

  function read(response) {
    var reader = response.body.getReader(), decoder = new TextDecoder('utf-8', { fatal: true });
    var size = 0, text = '';
    function next() {
      return reader.read().then(function(chunk) {
        if (chunk.done) return text + decoder.decode();
        size += chunk.value.byteLength;
        if (size > 131072) return reader.cancel().then(function() { throw new Error('Forecast metadata too large'); });
        text += decoder.decode(chunk.value, { stream: true });
        return next();
      });
    }
    return next();
  }

  function parse(xml, key, now) {
    if (typeof xml !== 'string' || xml.length > 131072 || /<!DOCTYPE|<!ENTITY/i.test(xml)) {
      throw new Error('Invalid forecast metadata');
    }
    var source = sources[key], doc = new DOMParser().parseFromString(xml, 'application/xml');
    function tags(name) { return Array.from(doc.getElementsByTagNameNS('*', name)); }
    if (tags('parsererror').length || !tags('Name').some(function(node) {
      return node.textContent === source.layer || node.textContent === 'dwd:' + source.layer;
    }) || !tags('Name').some(function(node) { return node.textContent === source.style; })) {
      throw new Error('Unexpected forecast layer');
    }
    function dimension(name) {
      var nodes = tags('Dimension').filter(function(node) { return node.getAttribute('name') === name; });
      if (nodes.length !== 1 || nodes[0].getAttribute('units') !== 'ISO8601') {
        throw new Error('Invalid forecast dimension');
      }
      return nodes[0];
    }
    var reference = dimension('REFERENCE_TIME');
    var run = Date.parse(reference.getAttribute('default')) / 1000;
    if (!Number.isFinite(run) || run > now || now - run > 12 * 3600 ||
        !reference.textContent.split(',').some(function(value) { return Date.parse(value) / 1000 === run; })) {
      throw new Error('Stale forecast run');
    }
    var range = dimension('time').textContent.trim().split('/');
    var start = Date.parse(range[0]) / 1000, end = Date.parse(range[1]) / 1000;
    var period = /^PT([1-6])H$/.exec(range[2]);
    if (range.length !== 3 || !period || !Number.isFinite(start) || !Number.isFinite(end) ||
        end <= start || end - start > 14 * 86400 || source.hours % Number(period[1]) !== 0) {
      throw new Error('Invalid forecast time range');
    }
    var box = tags('BoundingBox').find(function(node) { return node.getAttribute('CRS') === 'EPSG:4326'; });
    if (!box || ['minx', 'miny', 'maxx', 'maxy'].some(function(name) {
      return box.getAttribute(name) === null || !box.getAttribute(name).trim();
    })) throw new Error('Missing forecast bounds');
    var bounds = { south: Number(box.getAttribute('minx')), west: Number(box.getAttribute('miny')),
      north: Number(box.getAttribute('maxx')), east: Number(box.getAttribute('maxy')) };
    if (!Object.values(bounds).every(Number.isFinite) || bounds.south >= bounds.north ||
        bounds.west >= bounds.east || bounds.south < -90 || bounds.north > 90 ||
        bounds.west < -180 || bounds.east > 180) throw new Error('Invalid forecast bounds');

    // Global capabilities merge runs at PT3H, but a selected run serves 6-hour totals at 00/06/12/18 UTC.
    var step = source.hours * 3600, frames = [];
    for (var time = Math.ceil(now / step) * step; time <= end && frames.length < 15; time += step) {
      if (time <= now || time < start || time < run + step || (time - start) % (Number(period[1]) * 3600)) continue;
      frames.push({ type: 'forecast', time: time, hours: source.hours, key: key,
        reference: new Date(run * 1000).toISOString() });
      if (time >= now + 12 * 3600 + 600) break;
    }
    if (frames.length < 3 || frames[0].time > now + step || frames[frames.length - 1].time < now + 12 * 3600 + 600) {
      throw new Error('Forecast does not cover the next 12 hours');
    }
    return { key: key, bounds: bounds, frames: frames, run: run };
  }

  function sourceFor(bounds, europe) {
    var width = bounds.east - bounds.west;
    var west = ((bounds.west + 180) % 360 + 360) % 360 - 180;
    return europe && Object.values(bounds).every(Number.isFinite) && width > 0 && width < 360 &&
      bounds.south >= europe.south && bounds.north <= europe.north &&
      west >= europe.west && west + width <= europe.east ? 'eu' : 'global';
  }

  function legendUrl(key) {
    return endpoint + '?' + new URLSearchParams({ service: 'WMS', version: '1.1.1',
      request: 'GetLegendGraphic', format: 'image/png', layer: 'dwd:' + sources[key].layer,
      style: sources[key].style }).toString();
  }

  return { endpoint: endpoint, sources: sources, capabilitiesUrl: capabilitiesUrl, read: read,
    parse: parse, sourceFor: sourceFor, legendUrl: legendUrl };
})();
