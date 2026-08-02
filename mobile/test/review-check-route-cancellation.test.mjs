import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { loadTsModule } from './load-ts-module.mjs';

const {
  ReviewCheckLoadGuard,
  ReviewCheckRouteGuard
} = loadTsModule('src/app/features/review-check-load.guard.ts');
const pageSource = readFileSync(
  new URL('../src/app/features/review-check.page.ts', import.meta.url),
  'utf8'
);

const legacyKey = {
  orderDetailId: '11111111-1111-1111-1111-111111111111',
  capabilityToken: null
};
const nextLegacyKey = {
  orderDetailId: '22222222-2222-2222-2222-222222222222',
  capabilityToken: null
};
const capabilityKey = {
  orderDetailId: 'secure-capability',
  capabilityToken: `rc1_${'A'.repeat(43)}`
};
const nextCapabilityKey = {
  orderDetailId: 'secure-capability',
  capabilityToken: `rc1_${'B'.repeat(43)}`
};

test('a route change rejects a late review-check GET response', () => {
  const guard = new ReviewCheckLoadGuard();
  const oldLoad = guard.begin(legacyKey);

  assert.equal(guard.accepts(oldLoad, legacyKey), true);
  guard.invalidate();
  const currentLoad = guard.begin(nextLegacyKey);

  assert.equal(guard.accepts(oldLoad, legacyKey), false);
  assert.equal(guard.accepts(oldLoad, nextLegacyKey), false);
  assert.equal(guard.accepts(currentLoad, nextLegacyKey), true);
});

test('ion leave rejects late delivery while a later view can start a fresh GET', () => {
  const guard = new ReviewCheckLoadGuard();
  const abandonedLoad = guard.begin(legacyKey);

  guard.leave();
  assert.equal(guard.canStart(), false);
  assert.equal(guard.accepts(abandonedLoad, legacyKey), false);

  guard.activate();
  const reenteredLoad = guard.begin(legacyKey);
  assert.equal(guard.canStart(), true);
  assert.equal(guard.accepts(reenteredLoad, legacyKey), true);
});

test('destroy permanently rejects late review-check GET delivery', () => {
  const guard = new ReviewCheckLoadGuard();
  const abandonedLoad = guard.begin(legacyKey);

  guard.destroy();
  guard.activate();

  assert.equal(guard.canStart(), false);
  assert.equal(guard.accepts(abandonedLoad, legacyKey), false);
});

test('route epoch rejects a late mutation after route change and ABA navigation', () => {
  const guard = new ReviewCheckRouteGuard();

  assert.equal(guard.change(legacyKey), true);
  const abandonedMutation = guard.capture(legacyKey);
  assert.ok(abandonedMutation);

  assert.equal(guard.change(nextLegacyKey), true);
  assert.equal(guard.accepts(abandonedMutation, nextLegacyKey), false);

  assert.equal(guard.change(legacyKey), true);
  assert.equal(guard.accepts(abandonedMutation, legacyKey), false);
  const currentMutation = guard.capture(legacyKey);
  assert.ok(currentMutation);
  assert.equal(guard.accepts(currentMutation, legacyKey), true);
});

test('duplicate route emissions do not reload but a changed capability token creates a new epoch', () => {
  const guard = new ReviewCheckRouteGuard();

  assert.equal(guard.change(capabilityKey), true);
  const firstTokenMutation = guard.capture(capabilityKey);
  assert.ok(firstTokenMutation);
  assert.equal(guard.change({ ...capabilityKey }), false);
  assert.equal(guard.accepts(firstTokenMutation, capabilityKey), true);

  assert.equal(guard.change(nextCapabilityKey), true);
  assert.equal(guard.accepts(firstTokenMutation, nextCapabilityKey), false);
});

test('destroy rejects every pending mutation delivery', () => {
  const guard = new ReviewCheckRouteGuard();
  guard.change(legacyKey);
  const pendingMutation = guard.capture(legacyKey);
  assert.ok(pendingMutation);

  guard.destroy();

  assert.equal(guard.accepts(pendingMutation, legacyKey), false);
  assert.equal(guard.capture(legacyKey), null);
});

test('page cancels only the active GET on route, leave and destroy boundaries', () => {
  assert.match(pageSource, /activateReviewCheckRoute\([\s\S]*?reviewCheckRouteGuard\.change\(routeKey\)[\s\S]*?cancelActiveReviewCheckLoad\(\)/);
  assert.match(pageSource, /ionViewWillLeave\(\): void \{[\s\S]*?reviewCheckLoadGuard\.leave\(\)[\s\S]*?cancelActiveReviewCheckLoad\(false\)/);
  assert.match(pageSource, /ngOnDestroy\(\): void \{[\s\S]*?reviewCheckLoadGuard\.destroy\(\)[\s\S]*?cancelActiveReviewCheckLoad\(false\)/);
  assert.match(pageSource, /reviewCheckLoadSubscription[\s\S]*?subscription\?\.unsubscribe\(\)/);
});

test('route changes clear stale payload drafts permissions and busy state before loading', () => {
  const transitionStart = pageSource.indexOf('private activateReviewCheckRoute(');
  const clearStart = pageSource.indexOf('private clearReviewCheckRouteState()', transitionStart);
  const clearEnd = pageSource.indexOf('private isCapabilityRoute()', clearStart);
  const transitionBlock = pageSource.slice(transitionStart, clearStart);
  const clearBlock = pageSource.slice(clearStart, clearEnd);

  assert.ok(transitionStart >= 0 && clearStart > transitionStart && clearEnd > clearStart);
  assert.match(transitionBlock, /clearReviewCheckRouteState\(\)[\s\S]*?orderDetailId\.set\(orderDetailId\)[\s\S]*?loadReviewCheck\(\)/);
  assert.match(clearBlock, /details\.set\(null\)/);
  assert.match(clearBlock, /drafts\.set\(\{\}\)/);
  assert.match(clearBlock, /mutationKey\.set\(null\)/);
  assert.match(clearBlock, /editingFieldKey\.set\(null\)/);
});

test('reused capability page reacts to fragment changes without trusting raw fragment text', () => {
  assert.match(pageSource, /this\.route\.fragment\.subscribe\(\(\) => \{[\s\S]*?syncReviewCheckRoute\(null\)/);
  assert.match(pageSource, /const capabilityToken = capabilityRoute\s*\? reviewCapabilityToken\(\)\s*:\s*null/);
  assert.doesNotMatch(pageSource, /fragment\.subscribe\(\((?!\))/);
});

test('an unresolved GET is restarted when Ionic re-enters a cached page', () => {
  assert.match(
    pageSource,
    /ionViewWillEnter\(\): void \{[\s\S]*?reviewCheckLoadGuard\.activate\(\)[\s\S]*?!this\.reviewCheckRouteLoadResolved[\s\S]*?this\.loadReviewCheck\(\)/
  );
  assert.match(pageSource, /this\.reviewCheckRouteLoadResolved = true;\s*this\.applyDetails\(details\)/);
});

test('public legacy and opaque GET contracts remain and mutations are not lifecycle-cancelled', () => {
  assert.match(pageSource, /this\.api\.getReviewCheck\(orderDetailId, token\)/);
  assert.match(pageSource, /this\.api\.getReviewCheck\(orderDetailId\)/);

  const runActionStart = pageSource.indexOf('private runAction(');
  const buildRequestStart = pageSource.indexOf('private buildRequest()', runActionStart);
  const mutationBlock = pageSource.slice(runActionStart, buildRequestStart);
  assert.ok(runActionStart >= 0 && buildRequestStart > runActionStart);
  assert.doesNotMatch(mutationBlock, /reviewCheckLoadSubscription|cancelActiveReviewCheckLoad|unsubscribe\(/);
});

test('all mutation response shapes suppress stale UI delivery without cancelling writes', () => {
  const fieldStart = pageSource.indexOf('\n  saveReviewField(');
  const fieldEnd = pageSource.indexOf('\n  isReviewFieldEditing(', fieldStart);
  const notesStart = pageSource.indexOf('\n  async saveReviewNotes(');
  const notesEnd = pageSource.indexOf('\n  canEditNotes(', notesStart);
  const actionStart = pageSource.indexOf('private runAction(');
  const actionEnd = pageSource.indexOf('private buildRequest()', actionStart);

  for (const block of [
    pageSource.slice(fieldStart, fieldEnd),
    pageSource.slice(notesStart, notesEnd),
    pageSource.slice(actionStart, actionEnd)
  ]) {
    assert.match(block, /captureReviewCheckRoute\(\)/);
    assert.match(block, /acceptsReviewCheckRoute\(routeTicket\)/);
    assert.doesNotMatch(block, /unsubscribe\(/);
  }
});
