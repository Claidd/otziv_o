import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  mobileManualTaskTargetEffect,
  mobileManualTaskTargetForSnapshot,
  mobileManualTaskTargetValid
} = loadTsModule('src/app/shared/manual-payment-task-target.ts');

const options = [
  { key: 'EXTERNAL_TASK', kind: 'EXTERNAL_TASK', label: 'Только задание', enabled: true },
  {
    key: 'MANAGER:7', kind: 'MANAGER', profileId: 7, label: 'Менеджер · Вика', enabled: true,
    projectedOverrunKopecks: 50000, overrunAcknowledgementRequired: true
  }
];

test('restores task target only by exact kind and profile id', () => {
  assert.equal(mobileManualTaskTargetForSnapshot(options, {
    accountingTargetKind: 'MANAGER', accountingTargetProfileId: 7
  }).key, 'MANAGER:7');
  assert.equal(mobileManualTaskTargetForSnapshot(options, {
    accountingTargetKind: 'MANAGER', accountingTargetProfileId: 8
  }), null);
});

test('requires acknowledgement for a server-projected target overrun', () => {
  assert.equal(mobileManualTaskTargetValid(options[1], false), false);
  assert.equal(mobileManualTaskTargetValid(options[1], true), true);
});

test('explains external-task accounting without implying owner credit', () => {
  assert.match(mobileManualTaskTargetEffect(options[0]), /не изменит лимиты владельца и сотрудников/i);
});
