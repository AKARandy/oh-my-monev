'use strict';

// Run with: node scripts/test-location-bridge.cjs
// This exercises the bridge script with fixture data. It does not test device GPS.

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const bridgePath = path.join(
  __dirname,
  '..',
  'app',
  'src',
  'main',
  'java',
  'id',
  'ohmymonev',
  'android',
  'engine',
  'LocationBridge.kt',
);
const kotlinSource = fs.readFileSync(bridgePath, 'utf8');
const scriptLiteral = kotlinSource.match(/val SCRIPT\s*=\s*"""([\s\S]*?)"""\.trimIndent\(\)/);
assert.ok(scriptLiteral, 'Could not find LocationBridge.SCRIPT');

function trimIndent(value) {
  const lines = value.replace(/^\r?\n/, '').replace(/\r?\n$/, '').split(/\r?\n/);
  const indent = Math.min(...lines.filter((line) => line.trim()).map((line) => line.match(/^\s*/)[0].length));
  return lines.map((line) => line.slice(indent)).join('\n');
}

const script = trimIndent(scriptLiteral[1]);

function createHarness({ iframe = false } = {}) {
  const messages = [];
  const timers = new Map();
  let nextTimerId = 1;
  const originalGetCurrentPosition = function originalGetCurrentPosition() {};
  const originalWatchPosition = function originalWatchPosition() {};
  const originalClearWatch = function originalClearWatch() {};
  const geolocation = {
    getCurrentPosition: originalGetCurrentPosition,
    watchPosition: originalWatchPosition,
    clearWatch: originalClearWatch,
    fixtureMarker: 'keep-geolocation-fields',
  };
  const navigator = { geolocation, userAgent: 'LocationBridge fixture' };
  const document = { title: 'Fixture page', body: { textContent: 'unchanged page text' } };
  const window = {
    document,
    location: { href: 'https://monev.example.test/form' },
    pageMarker: 'keep-window-fields',
    top: null,
  };
  window.top = iframe ? {} : window;
  const monevDeviceLocation = {
    onmessage: null,
    postMessage(data) {
      messages.push(JSON.parse(data));
    },
  };
  window.monevDeviceLocation = monevDeviceLocation;
  const pageSnapshot = {
    windowKeys: Object.keys(window).sort(),
    navigatorKeys: Object.keys(navigator).sort(),
    geolocationKeys: Object.keys(geolocation).sort(),
    location: JSON.stringify(window.location),
    document: JSON.stringify(document),
    userAgent: navigator.userAgent,
    geolocationMarker: geolocation.fixtureMarker,
  };

  function setTimeoutMock(callback, delay) {
    const id = nextTimerId++;
    timers.set(id, { callback, delay });
    return id;
  }

  function clearTimeoutMock(id) {
    timers.delete(id);
  }

  vm.runInNewContext(script, {
    window,
    navigator,
    document,
    monevDeviceLocation,
    setTimeout: setTimeoutMock,
    clearTimeout: clearTimeoutMock,
  });

  return {
    clearTimeoutMock,
    document,
    geolocation,
    messages,
    monevDeviceLocation,
    navigator,
    originalClearWatch,
    originalGetCurrentPosition,
    originalWatchPosition,
    pageSnapshot,
    setTimeoutMock,
    timers,
    window,
  };
}

function reply(harness, message) {
  harness.monevDeviceLocation.onmessage({ data: JSON.stringify(message) });
}

function testFixtureCoordinatesAndOptions() {
  const h = createHarness();
  const fixture = {
    timestamp: 1_794_300_000_000,
    coords: { latitude: -6.2088, longitude: 106.8456, accuracy: 12.5 },
  };
  let received;
  let errorReceived;

  h.navigator.geolocation.getCurrentPosition(
    (position) => { received = position; },
    (error) => { errorReceived = error; },
    { maximumAge: 1_250, timeout: 6_500 },
  );

  assert.deepEqual(h.messages[0], { id: '1', maximumAge: 1_250, timeout: 6_500 });
  const timer = [...h.timers.values()][0];
  assert.equal(timer.delay, 6_500);
  reply(h, { id: h.messages[0].id, position: fixture });

  assert.equal(received.timestamp, fixture.timestamp);
  assert.equal(received.coords.latitude, fixture.coords.latitude);
  assert.equal(received.coords.longitude, fixture.coords.longitude);
  assert.equal(received.coords.accuracy, fixture.coords.accuracy);
  assert.equal(errorReceived, undefined);
  assert.equal(h.timers.size, 0, 'successful reply clears its timeout');

  assert.equal(h.window.document, h.document);
  assert.equal(h.window.location.href, 'https://monev.example.test/form');
  assert.equal(h.window.pageMarker, 'keep-window-fields');
  assert.equal(h.document.title, 'Fixture page');
  assert.equal(h.document.body.textContent, 'unchanged page text');
  assert.equal(h.navigator.userAgent, 'LocationBridge fixture');
  assert.equal(h.geolocation.fixtureMarker, 'keep-geolocation-fields');
  assert.equal(h.geolocation.watchPosition, h.originalWatchPosition);
  assert.equal(h.geolocation.clearWatch, h.originalClearWatch);
  assert.deepEqual(Object.keys(h.window).sort(), h.pageSnapshot.windowKeys);
  assert.deepEqual(Object.keys(h.navigator).sort(), h.pageSnapshot.navigatorKeys);
  assert.deepEqual(Object.keys(h.geolocation).sort(), h.pageSnapshot.geolocationKeys);
  assert.equal(JSON.stringify(h.window.location), h.pageSnapshot.location);
  assert.equal(JSON.stringify(h.document), h.pageSnapshot.document);
  assert.equal(h.navigator.userAgent, h.pageSnapshot.userAgent);
  assert.equal(h.geolocation.fixtureMarker, h.pageSnapshot.geolocationMarker);
}

function testTransportErrorCallback() {
  const h = createHarness();
  let received;

  h.navigator.geolocation.getCurrentPosition(() => {}, (error) => { received = error; });
  reply(h, {
    id: h.messages[0].id,
    error: { code: 1, message: 'Location permission denied.' },
  });

  assert.equal(received.code, 1);
  assert.equal(received.message, 'Location permission denied.');
  assert.equal(h.timers.size, 0, 'error reply clears its timeout');
}

function testLateReplyAfterTimeoutIsIgnored() {
  const h = createHarness();
  let successes = 0;
  const errors = [];

  h.navigator.geolocation.getCurrentPosition(
    () => { successes += 1; },
    (error) => errors.push(error),
    { timeout: 25 },
  );
  const request = h.messages[0];
  const [timerId, timer] = [...h.timers.entries()][0];
  assert.equal(timer.delay, 25);
  h.timers.delete(timerId);
  timer.callback();

  reply(h, {
    id: request.id,
    position: { timestamp: 1_794_300_000_000, coords: { latitude: -6, longitude: 106 } },
  });

  assert.equal(successes, 0);
  assert.equal(errors.length, 1);
  assert.equal(errors[0].code, 3);
  assert.equal(errors[0].message, 'Lokasi belum ditemukan.');
}

function testIframeDoesNotInstallTheOverride() {
  const h = createHarness({ iframe: true });

  assert.equal(h.geolocation.getCurrentPosition, h.originalGetCurrentPosition);
  assert.equal(h.monevDeviceLocation.onmessage, null);
  assert.equal(h.messages.length, 0);
}

testFixtureCoordinatesAndOptions();
testTransportErrorCallback();
testLateReplyAfterTimeoutIsIgnored();
testIframeDoesNotInstallTheOverride();
console.log('LocationBridge SCRIPT fixture checks passed; no device GPS was tested.');
