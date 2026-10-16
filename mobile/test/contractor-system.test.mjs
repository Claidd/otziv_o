import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  ACTIVATION_CONFIRMATION, ROUTING_ENABLE_CONFIRMATION, ROUTING_PAUSE_CONFIRMATION,
  contractorBusinessDate, canActivateContractorSystem, canChangeContractorRouting,
  contractorSystemReason
} = loadTsModule('src/app/shared/contractor-system.ts');

const legacy = {
  mode: 'LEGACY', systemEnabled: false, irreversible: false,
  activationAvailable: true, legacyBehavior: true, routingRequested: false
};

test('uses the Irkutsk business date across the UTC boundary and exact server confirmations', () => {
  assert.equal(contractorBusinessDate(new Date('2026-10-15T16:30:00Z')), '2026-10-16');
  assert.equal(ACTIVATION_CONFIRMATION, 'ВКЛЮЧИТЬ НОВУЮ СИСТЕМУ');
  assert.equal(ROUTING_ENABLE_CONFIRMATION, 'ВКЛЮЧИТЬ РЕКВИЗИТЫ');
  assert.equal(ROUTING_PAUSE_CONFIRMATION, 'ПРИОСТАНОВИТЬ РЕКВИЗИТЫ');
});

test('activation is owner-only, requires server readiness, and cannot recur', () => {
  assert.equal(canActivateContractorSystem(legacy, true), true);
  assert.equal(canActivateContractorSystem(legacy, false), false);
  assert.equal(canActivateContractorSystem({ ...legacy, activationAvailable: false }, true), false);
  assert.equal(canActivateContractorSystem({ ...legacy, irreversible: true }, true), false);
  assert.equal(canActivateContractorSystem({ ...legacy, systemEnabled: true }, true), false);
});

test('global routing is separate from individual profiles and requires owner and active system', () => {
  assert.equal(canChangeContractorRouting(legacy, true), false);
  assert.equal(canChangeContractorRouting({ ...legacy, mode: 'ROUTING_PAUSED', systemEnabled: true, legacyBehavior: false }, true), true);
  assert.equal(canChangeContractorRouting({ ...legacy, mode: 'ROUTING_PAUSED', systemEnabled: true, legacyBehavior: false }, false), false);
  assert.equal(contractorSystemReason('  сверка очереди  '), 'сверка очереди');
  assert.equal(contractorSystemReason(' '.repeat(10)), null);
  assert.equal(contractorSystemReason('x'.repeat(501)), null);
});
