import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  MOBILE_ACTIONS,
  MOBILE_SECTIONS,
  canUseAction,
  rolesForAction
} = loadTsModule('src/app/core/mobile-permissions.ts');

test('keeps public mobile home available only after authentication guard handles login', () => {
  assert.deepEqual(Array.from(rolesForAction(MOBILE_SECTIONS.home, MOBILE_ACTIONS.view)), []);
  assert.equal(canUseAction([], MOBILE_SECTIONS.home, MOBILE_ACTIONS.view), true);
});

test('keeps finance actions available to owner/admin and out of manager-only controls', () => {
  assert.equal(canUseAction(['ADMIN'], MOBILE_SECTIONS.tbank, MOBILE_ACTIONS.view), true);
  assert.equal(canUseAction(['OWNER'], MOBILE_SECTIONS.tbank, MOBILE_ACTIONS.view), true);
  assert.equal(canUseAction(['MANAGER'], MOBILE_SECTIONS.tbank, MOBILE_ACTIONS.view), false);
  assert.equal(canUseAction(['MANAGER'], MOBILE_SECTIONS.leads, MOBILE_ACTIONS.import), false);
  assert.equal(canUseAction(['OWNER'], MOBILE_SECTIONS.leads, MOBILE_ACTIONS.assign), true);
  assert.equal(canUseAction(['MANAGER'], MOBILE_SECTIONS.leads, MOBILE_ACTIONS.assign), false);
});

test('allows the same functional sections as the backend role model', () => {
  assert.equal(canUseAction(['MARKETOLOG'], MOBILE_SECTIONS.leads, MOBILE_ACTIONS.view), true);
  assert.equal(canUseAction(['OPERATOR'], MOBILE_SECTIONS.leads, MOBILE_ACTIONS.view), false);
  assert.equal(canUseAction(['WORKER'], MOBILE_SECTIONS.botBrowser, MOBILE_ACTIONS.view), true);
});
