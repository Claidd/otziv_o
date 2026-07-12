import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const { mobileViewportMode } = loadTsModule('src/app/core/mobile-viewport.helpers.ts');

test('keeps large and medium phones out of compact mode', () => {
  assert.equal(mobileViewportMode(430, 932).compactPhone, false);
  assert.equal(mobileViewportMode(430, 932).roomyPhone, true);
  assert.equal(mobileViewportMode(430, 932).shortPhone, false);
  assert.equal(mobileViewportMode(390, 844).compactPhone, false);
  assert.equal(mobileViewportMode(390, 844).roomyPhone, true);
  assert.equal(mobileViewportMode(390, 844).shortPhone, false);
});

test('treats Android WebView height with system bars as compact', () => {
  assert.equal(mobileViewportMode(412, 803).compactPhone, true);
  assert.equal(mobileViewportMode(412, 803).roomyPhone, false);
  assert.equal(mobileViewportMode(412, 803).shortPhone, false);
});

test('uses compact mode only for genuinely tight phones', () => {
  assert.equal(mobileViewportMode(360, 844).compactPhone, true);
  assert.equal(mobileViewportMode(360, 844).roomyPhone, false);
  assert.equal(mobileViewportMode(360, 844).shortPhone, false);
  assert.equal(mobileViewportMode(375, 667).compactPhone, true);
  assert.equal(mobileViewportMode(375, 667).roomyPhone, false);
  assert.equal(mobileViewportMode(375, 667).shortPhone, true);
});

test('keeps landscape or keyboard-shrunk viewports dense', () => {
  assert.equal(mobileViewportMode(430, 760).compactPhone, true);
  assert.equal(mobileViewportMode(430, 760).roomyPhone, false);
  assert.equal(mobileViewportMode(430, 760).shortPhone, true);
});

test('covers Galaxy S25 and the full supported phone width range', () => {
  const galaxyS25 = mobileViewportMode(384, 700);
  assert.equal(galaxyS25.compactPhone, true);
  assert.equal(galaxyS25.roomyPhone, false);
  assert.equal(galaxyS25.shortPhone, true);
  assert.equal(mobileViewportMode(320, 568).shortPhone, true);
  assert.equal(mobileViewportMode(414, 896).roomyPhone, true);
  assert.equal(mobileViewportMode(600, 960).roomyPhone, true);
  assert.equal(mobileViewportMode(800, 1000).roomyPhone, true);
});
