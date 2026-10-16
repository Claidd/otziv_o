import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  currentContractorAccrualMonth, newContractorAccrualDraft,
  newContractorAccrualOperationKey, contractorAccrualRequest
} = loadTsModule('src/app/shared/contractor-accrual-adjustment.ts');

function draft(overrides = {}) {
  return {
    ...newContractorAccrualDraft(), kind: 'BONUS', amountRubles: '1 000',
    reason: 'Помощь команде', referenceMonth: '2026-09',
    evidenceReference: 'акт-1', idempotencyKey: 'same-key', ...overrides
  };
}

test('uses the Irkutsk business month around a UTC month boundary', () => {
  assert.equal(currentContractorAccrualMonth(new Date('2026-09-30T15:59:00Z')), '2026-09');
  assert.equal(currentContractorAccrualMonth(new Date('2026-09-30T16:01:00Z')), '2026-10');
});

test('converts rubles to exact kopecks and keeps the operation key stable in the request', () => {
  const result = contractorAccrualRequest(draft({ amountRubles: '1234,05' }), '2026-10');
  assert.equal(result.error, null);
  assert.deepEqual(JSON.parse(JSON.stringify(result.request)), {
    kind: 'BONUS', amountKopecks: 123405, reason: 'Помощь команде',
    referenceMonth: '2026-09', evidenceReference: 'акт-1', idempotencyKey: 'same-key'
  });
  assert.notEqual(newContractorAccrualOperationKey(), newContractorAccrualOperationKey());
});

test('rejects invalid amounts, empty reason, and future reference months before a financial write', () => {
  for (const amountRubles of ['0', '1.001', '-1', '1000000001', '1,2,3']) {
    assert.equal(contractorAccrualRequest(draft({ amountRubles }), '2026-10').request, null, amountRubles);
  }
  assert.equal(contractorAccrualRequest(draft({ amountRubles: '2.00', reason: ' ' }), '2026-10').request, null);
  assert.equal(contractorAccrualRequest(draft({ amountRubles: '2.00', referenceMonth: '2026-11' }), '2026-10').request, null);
  assert.equal(contractorAccrualRequest(draft({ amountRubles: '2.00', evidenceReference: 'x'.repeat(161) }), '2026-10').request, null);
});
