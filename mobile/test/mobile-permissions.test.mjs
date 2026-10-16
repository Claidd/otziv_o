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

test('makes audit training available to managers and management only', () => {
  assert.equal(canUseAction(['MANAGER'], MOBILE_SECTIONS.training, MOBILE_ACTIONS.view), true);
  assert.equal(canUseAction(['ADMIN'], MOBILE_SECTIONS.training, MOBILE_ACTIONS.view), true);
  assert.equal(canUseAction(['OWNER'], MOBILE_SECTIONS.training, MOBILE_ACTIONS.view), true);
  assert.equal(canUseAction(['WORKER'], MOBILE_SECTIONS.training, MOBILE_ACTIONS.view), false);
});

test('city statistics remains an owner/admin view', () => {
  assert.equal(canUseAction(['ADMIN'], MOBILE_SECTIONS.cityStats, MOBILE_ACTIONS.view), true);
  assert.equal(canUseAction(['OWNER'], MOBILE_SECTIONS.cityStats, MOBILE_ACTIONS.view), true);
  assert.equal(canUseAction(['MANAGER'], MOBILE_SECTIONS.cityStats, MOBILE_ACTIONS.view), false);
  assert.equal(canUseAction(['WORKER'], MOBILE_SECTIONS.cityStats, MOBILE_ACTIONS.view), false);
});

test('new management screens and performer board follow their web roles', () => {
  for (const section of [MOBILE_SECTIONS.clientOffers, MOBILE_SECTIONS.notificationMedia, MOBILE_SECTIONS.archiveAdmin, MOBILE_SECTIONS.reputationAi, MOBILE_SECTIONS.workloadShadow, MOBILE_SECTIONS.mobileUpdateAdmin]) {
    assert.equal(canUseAction(['ADMIN'], section, MOBILE_ACTIONS.view), true);
    assert.equal(canUseAction(['OWNER'], section, MOBILE_ACTIONS.view), true);
    assert.equal(canUseAction(['MANAGER'], section, MOBILE_ACTIONS.view), false);
  }
  assert.equal(canUseAction(['PERFORMER'], MOBILE_SECTIONS.performerBoard, MOBILE_ACTIONS.view), true);
  assert.equal(canUseAction(['PERFORMER'], MOBILE_SECTIONS.performerBoard, MOBILE_ACTIONS.edit), true);
  assert.equal(canUseAction(['WORKER'], MOBILE_SECTIONS.performerBoard, MOBILE_ACTIONS.view), false);
});
