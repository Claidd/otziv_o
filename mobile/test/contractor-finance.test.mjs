import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  contractorProfileDraft, contractorProfileRequest, contractorSettlementRequest,
  newContractorSettlementDraft, parseKopecks
} = loadTsModule('src/app/shared/contractor-finance.ts');

const profile = {
  id: 27, userId: 14, role: 'SPECIALIST', rowVersion: 7,
  enabled: true, liveEnabled: false, recipientName: 'Мария Р.',
  paymentPhone: '2202 2082 3839 6676', bankName: 'Сбер', paymentComment: '',
  openingBalanceKopecks: 10_000
};

test('profile save sends full optimistic-lock request and normalizes card without float rounding', () => {
  const draft = contractorProfileDraft(profile);
  draft.liveEnabled = true;
  draft.openingBalanceRubles = '120,05';
  draft.openingBalanceReason = 'Сверка начального остатка';
  const result = contractorProfileRequest(profile, draft);
  assert.equal(result.error, null);
  assert.deepEqual(JSON.parse(JSON.stringify(result.request)), {
    role: 'SPECIALIST', expectedVersion: 7, enabled: true, liveEnabled: true,
    recipientName: 'Мария Р.', paymentPhone: '2202208238396676', bankName: 'Сбер',
    paymentComment: '', openingBalanceKopecks: 12_005,
    openingBalanceReason: 'Сверка начального остатка'
  });
});

test('profile validation requires reason for a changed opening balance and valid destination for activation', () => {
  const draft = contractorProfileDraft(profile);
  draft.openingBalanceRubles = '101,01';
  assert.match(contractorProfileRequest(profile, draft).error, /причину/i);
  draft.openingBalanceReason = 'Сверка';
  draft.paymentPhone = 'бумажка';
  assert.match(contractorProfileRequest(profile, draft).error, /телефон/i);
  draft.paymentPhone = '';
  assert.match(contractorProfileRequest(profile, draft).error, /ФИО получателя/i);
});

test('turning LIVE off preserves an unchanged malformed historical destination for safe disable', () => {
  const legacy = { ...profile, liveEnabled: true, paymentPhone: 'old-invalid' };
  const draft = contractorProfileDraft(legacy);
  draft.liveEnabled = false;
  const result = contractorProfileRequest(legacy, draft);
  assert.equal(result.error, null);
  assert.equal(result.request.paymentPhone, 'old-invalid');
  assert.equal(result.request.liveEnabled, false);
});

test('direct settlement request preserves exact amount, mode and operation key', () => {
  const draft = {
    ...newContractorSettlementDraft(), amountRubles: '340,07',
    effectiveAt: '2026-10-15T12:30', reason: 'Перевод по акту',
    evidenceReference: 'bank-proof-17', idempotencyKey: 'retry-key'
  };
  const result = contractorSettlementRequest('LIVE', draft, '2026-10-16T12:30');
  assert.equal(result.error, null);
  assert.deepEqual(JSON.parse(JSON.stringify(result.request)), {
    expectedMode: 'LIVE', amountKopecks: 34_007, effectiveAt: '2026-10-15T12:30',
    reason: 'Перевод по акту', evidenceReference: 'bank-proof-17', idempotencyKey: 'retry-key'
  });
  assert.equal(parseKopecks('340,07'), 34_007);
  assert.equal(parseKopecks('340,077'), null);
});

test('direct settlement rejects future date, missing proof and excessive amount', () => {
  const baseline = {
    ...newContractorSettlementDraft(), amountRubles: '1,00',
    effectiveAt: '2026-10-15T12:30', reason: 'Перевод', evidenceReference: 'bank-1'
  };
  assert.match(contractorSettlementRequest('LIVE', { ...baseline, effectiveAt: '2026-10-17T12:30' }, '2026-10-16T12:30').error, /дату/i);
  assert.match(contractorSettlementRequest('LIVE', { ...baseline, effectiveAt: '2026-02-31T12:30' }, '2026-10-16T12:30').error, /дату/i);
  assert.match(contractorSettlementRequest('LIVE', { ...baseline, evidenceReference: '' }, '2026-10-16T12:30').error, /документа/i);
  assert.match(contractorSettlementRequest('LIVE', { ...baseline, amountRubles: '1000000001' }, '2026-10-16T12:30').error, /сумму/i);
});
