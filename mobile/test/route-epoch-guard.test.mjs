import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTsModule } from './load-ts-module.mjs';

const { RouteEpochGuard } = loadTsModule('src/app/core/route-epoch.guard.ts');

test('route epoch accepts only the current routed resource', () => {
  const guard = new RouteEpochGuard();

  assert.equal(guard.change('order:1'), true);
  const first = guard.capture();
  assert.ok(first);
  assert.equal(guard.accepts(first), true);

  assert.equal(guard.change('order:2'), true);
  assert.equal(guard.accepts(first), false);
  const second = guard.capture();
  assert.ok(second);
  assert.equal(guard.accepts(second), true);
});

test('route epoch rejects an old response after A to B to A navigation', () => {
  const guard = new RouteEpochGuard();
  guard.change('pay:A');
  const abandoned = guard.capture();
  assert.ok(abandoned);

  guard.change('pay:B');
  guard.change('pay:A');

  assert.equal(guard.accepts(abandoned), false);
  assert.ok(guard.capture());
});

test('duplicate route emissions preserve the current epoch and destroy rejects it', () => {
  const guard = new RouteEpochGuard();
  guard.change('same');
  const current = guard.capture();
  assert.ok(current);

  assert.equal(guard.change('same'), false);
  assert.equal(guard.accepts(current), true);

  guard.destroy();
  assert.equal(guard.accepts(current), false);
  assert.equal(guard.capture(), null);
  assert.equal(guard.change('later'), false);
});
