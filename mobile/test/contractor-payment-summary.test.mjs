import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  contractorCoverageStartLabel,
  contractorPaymentMetricDescriptionId,
  contractorPaymentMetrics,
  contractorPaymentModeClass,
  contractorPaymentModeLabel,
  shouldShowLegacyContractorMetrics
} = loadTsModule('src/app/shared/contractor-payment-summary.ts');

function summary(overrides = {}) {
  return {
    profileId: 17,
    userId: 5,
    role: 'MANAGER',
    profileEnabled: true,
    liveEnabled: false,
    accruedMonthKopecks: 1_090_600,
    accruedTotalKopecks: 1_090_600,
    reservedKopecks: 520_000,
    clientReportedKopecks: 0,
    partiallyConfirmedOutstandingKopecks: 0,
    grossConfirmedMonthKopecks: 525_000,
    grossConfirmedTotalKopecks: 525_000,
    returnedMonthKopecks: 0,
    returnedTotalKopecks: 0,
    closedWithoutPaymentMonthKopecks: 200_000,
    closedWithoutPaymentTotalKopecks: 200_000,
    netReceivedMonthKopecks: 525_000,
    netReceivedTotalKopecks: 525_000,
    availableKopecks: 45_600,
    creditKopecks: 0,
    exposureOverrunKopecks: 0,
    reportingLive: false,
    shadowMode: true,
    liveRouting: false,
    trackingStartedAt: '2026-08-07T12:00:00Z',
    currentMonthCoverageComplete: false,
    ...overrides
  };
}

test('shows only the four manager-facing payment metrics', () => {
  const metrics = contractorPaymentMetrics(summary({
    reservedKopecks: 520_000,
    clientReportedKopecks: 30_000,
    partiallyConfirmedOutstandingKopecks: 80_000
  }));
  const byKey = Object.fromEntries(metrics.map((metric) => [metric.key, metric]));

  assert.equal(JSON.stringify(metrics.map((metric) => metric.key)), JSON.stringify(['accrued', 'reserved', 'paid', 'due']));
  assert.equal(byKey.accrued.label, 'Начислено');
  assert.equal(byKey.reserved.label, 'Зарезервировано');
  assert.equal(byKey.paid.label, 'Оплачено');
  assert.equal(byKey.due.label, 'Ожидает / к доплате');
  assert.equal(byKey.reserved.totalKopecks, 630_000);
  assert.equal(byKey.paid.totalKopecks, 525_000);
  assert.equal(byKey.due.totalKopecks, 45_600);
  assert.equal(byKey.accrued.monthKopecks, 1_090_600);
});

test('does not expose internal receipt, return, and reconciliation states as manager cards', () => {
  const keys = contractorPaymentMetrics(summary({
    creditKopecks: 12_300,
    exposureOverrunKopecks: 4_500
  })).map((metric) => metric.key);

  assert.ok(!keys.includes('client-reported'));
  assert.ok(!keys.includes('partially-confirmed'));
  assert.ok(!keys.includes('returned'));
  assert.ok(!keys.includes('net-received'));
  assert.ok(!keys.includes('credit'));
  assert.ok(!keys.includes('exposure-overrun'));
});

test('explains paid money differently for test and live accounting without changing the card label', () => {
  const simulatedPaid = contractorPaymentMetrics(summary()).find((metric) => metric.key === 'paid');
  assert.equal(simulatedPaid.label, 'Оплачено');
  assert.match(simulatedPaid.description, /не сумма подтверждённых реальных переводов/i);

  const livePaid = contractorPaymentMetrics(summary({ reportingLive: true })).find((metric) => metric.key === 'paid');
  assert.equal(livePaid.label, 'Оплачено');
  assert.match(livePaid.description, /Реально подтверждённые поступления/i);
});
test('keeps mode and coverage wording in parity with web', () => {
  assert.equal(contractorPaymentModeLabel(summary()), 'Реквизиты не участвуют в новых счетах');
  assert.equal(contractorPaymentModeClass(summary()), 'disabled');
  assert.equal(contractorPaymentModeLabel(summary({ liveEnabled: true, liveRouting: true })), 'Реквизиты участвуют в новых счетах');
  assert.equal(contractorPaymentModeClass(summary({ liveEnabled: true, liveRouting: true })), 'live');
  assert.match(contractorCoverageStartLabel(summary().trackingStartedAt), /7 августа 2026/);
  assert.equal(contractorCoverageStartLabel('bad-date'), 'даты подключения');
});

test('builds unique accessible help ids from profile and metric', () => {
  const first = contractorPaymentMetricDescriptionId(17, 'reserved');
  const secondProfile = contractorPaymentMetricDescriptionId(18, 'reserved');
  const secondMetric = contractorPaymentMetricDescriptionId(17, 'due');

  assert.equal(first, 'mobile-contractor-payment-help-17-reserved');
  assert.notEqual(first, secondProfile);
  assert.notEqual(first, secondMetric);
});

test('keeps legacy reward cards when summary is missing, failed, or incomplete', () => {
  assert.equal(shouldShowLegacyContractorMetrics(true, null, []), true);
  assert.equal(shouldShowLegacyContractorMetrics(true, '403', [summary({ currentMonthCoverageComplete: true })]), true);
  assert.equal(shouldShowLegacyContractorMetrics(true, null, [summary({ currentMonthCoverageComplete: false })]), true);
  assert.equal(shouldShowLegacyContractorMetrics(true, null, [summary({ currentMonthCoverageComplete: true })]), false);
});

test('mobile component exposes real buttons, linked explanations, and 44px tap targets', () => {
  const helpSource = fs.readFileSync(
    new URL('../src/app/shared/mobile-contractor-payment-metric-help.component.ts', import.meta.url),
    'utf8'
  );
  const summarySource = fs.readFileSync(
    new URL('../src/app/shared/mobile-contractor-payment-summary.component.ts', import.meta.url),
    'utf8'
  );

  assert.match(helpSource, /<button[\s\S]*\[attr\.aria-expanded\]="expanded\(\)"/);
  assert.match(helpSource, /\[attr\.aria-controls\]="descriptionId"/);
  assert.match(helpSource, /\[hidden\]="!expanded\(\)"/);
  assert.match(helpSource, /min-height:\s*44px/);
  assert.match(helpSource, /expanded\.update\(\(value\) => !value\)/);
  assert.match(summarySource, /descriptionId\(summary\.profileId, metric\.key\)/);
  assert.match(summarySource, /@media \(max-width: 390px\)[\s\S]*grid-template-columns: minmax\(0, 1fr\)/);
});

test('home loads the self summary separately and keeps a retryable fail-soft state', () => {
  const homeSource = fs.readFileSync(new URL('../src/app/features/home.page.ts', import.meta.url), 'utf8');
  const apiSource = fs.readFileSync(new URL('../src/app/core/api.service.ts', import.meta.url), 'utf8');

  assert.match(apiSource, /getMyContractorPaymentSummaries\(\)/);
  assert.match(apiSource, /\/api\/contractor-payments\/me/);
  assert.match(homeSource, /app-mobile-contractor-payment-summary/);
  assert.match(homeSource, /contractorPaymentsError/);
  assert.match(homeSource, /loadContractorPayments/);
  assert.match(homeSource, /Расчёты по вознаграждениям временно недоступны/);
  assert.match(homeSource, /reloadRequestId !== this\.reloadEpoch/);
});
