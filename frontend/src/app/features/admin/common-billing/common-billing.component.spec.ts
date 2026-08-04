import { describe, expect, it } from 'vitest';
import {
  commonInvoicePaymentEvidence,
  commonInvoicePaymentEvidenceSnapshot,
  isIncompletePartiallyPaidInvoice,
  isMigrationPaymentRegistryError,
  isPaymentInitManualCheckError
} from './common-billing.component';
import type { CommonInvoiceDetailsResponse } from '../../../core/common-billing.api';

describe('isIncompletePartiallyPaidInvoice', () => {
  it('recognizes a partially paid invoice that is still being collected', () => {
    expect(isIncompletePartiallyPaidInvoice({
      status: 'PARTIALLY_PAID',
      readyOrders: 7,
      totalOrders: 8
    })).toBe(true);
  });

  it('does not hide the warning after every order is ready', () => {
    expect(isIncompletePartiallyPaidInvoice({
      status: 'PARTIALLY_PAID',
      readyOrders: 8,
      totalOrders: 8
    })).toBe(false);
  });

  it('does not affect other invoice statuses', () => {
    expect(isIncompletePartiallyPaidInvoice({
      status: 'INVOICED',
      readyOrders: 7,
      totalOrders: 8
    })).toBe(false);
  });
});

describe('commonInvoicePaymentEvidence', () => {
  it('uses the persisted T-Bank amount and terminal identity instead of the current invoice amount', () => {
    const evidence = commonInvoicePaymentEvidence({
      tbankOrderId: 'invoice-order',
      tbankPaymentId: 'invoice-payment',
      tbankPaymentAmountKopecks: 475_000,
      tbankTerminalLabel: 'Основной терминал',
      tbankTerminalKey: 'terminal-key'
    }, [{
      id: 17,
      status: 'INIT_CONFLICT',
      orderId: 'ref-order',
      paymentId: null,
      amountKopecks: 200_000,
      reason: 'init_exception_before_response',
      terminalLabel: 'Резервный терминал',
      terminalKey: 'reserve-key'
    }]);

    expect(evidence).toEqual([
      {
        key: 'invoice',
        label: 'Счёт',
        orderId: 'invoice-order',
        paymentId: 'invoice-payment',
        amountKopecks: 475_000,
        status: '',
        reason: '',
        terminalLabel: 'Основной терминал',
        terminalKey: 'terminal-key'
      },
      {
        key: 'ref-17',
        label: 'Реестр #17',
        orderId: 'ref-order',
        paymentId: 'не сохранён',
        amountKopecks: 200_000,
        status: 'INIT_CONFLICT',
        reason: 'init_exception_before_response',
        terminalLabel: 'Резервный терминал',
        terminalKey: 'reserve-key'
      }
    ]);
  });

  it('does not substitute the current invoice total when persisted payment evidence is absent', () => {
    expect(commonInvoicePaymentEvidence({
      tbankOrderId: undefined,
      tbankPaymentId: undefined,
      tbankPaymentAmountKopecks: undefined,
      tbankTerminalLabel: undefined,
      tbankTerminalKey: undefined
    }, [], true)[0]?.amountKopecks).toBeNull();
  });
});

describe('commonInvoicePaymentEvidenceSnapshot', () => {
  const details = (overrides: Partial<CommonInvoiceDetailsResponse> = {}): CommonInvoiceDetailsResponse => ({
    summary: {
      id: 97,
      accountId: 10,
      accountName: 'Аккаунт',
      title: 'Счёт',
      token: 'public-token',
      publicUrl: 'https://example.test/pay',
      status: 'NEEDS_ATTENTION',
      totalOrders: 1,
      readyOrders: 1,
      paidOrders: 0,
      amount: 4750,
      paid: 0,
      remaining: 4750,
      amountKopecks: 475_000,
      paidKopecks: 0,
      remainingKopecks: 475_000,
      lastError: 'payment_init_conflict: duplicate request',
      tbankOrderId: 'invoice-order',
      tbankPaymentId: 'invoice-payment',
      tbankPaymentAmountKopecks: 475_000,
      tbankTerminalLabel: 'Основной терминал',
      tbankTerminalKey: 'terminal-key'
    },
    orders: [],
    orderCards: [],
    nextCycleOrders: [],
    paymentRefs: [{
      id: 17,
      status: 'INIT_CONFLICT',
      orderId: 'ref-order',
      paymentId: null,
      amountKopecks: 200_000,
      reason: 'init_exception_before_response',
      terminalLabel: 'Резервный терминал',
      terminalKey: 'reserve-key'
    }],
    paymentEvidenceToken: 'evidence-token-1',
    ...overrides
  });

  it('captures token and complete evidence only for the expected fully loaded invoice', () => {
    const snapshot = commonInvoicePaymentEvidenceSnapshot(details(), 97);

    expect(snapshot?.invoiceId).toBe(97);
    expect(snapshot?.evidenceToken).toBe('evidence-token-1');
    expect(snapshot?.evidence.map(item => item.orderId)).toEqual(['invoice-order', 'ref-order']);
  });

  it('rejects absent details, a different invoice and a blank token', () => {
    expect(commonInvoicePaymentEvidenceSnapshot(null, 97)).toBeNull();
    expect(commonInvoicePaymentEvidenceSnapshot(details(), 98)).toBeNull();
    expect(commonInvoicePaymentEvidenceSnapshot(details({ paymentEvidenceToken: '  ' }), 97)).toBeNull();
  });
});

describe('payment-init attention policy', () => {
  it.each([
    'payment_init_stale: timeout',
    'payment_init_conflict: duplicate request',
    'payment_init_exception: PKIX path building failed',
    'payment_init_response_mismatch: OrderId mismatch',
    'payment_init_response_collision: PaymentId collision',
    'payment_init_invalid_url: unsafe URL',
    'payment_cached_invalid_url: missing URL',
    'tbank_init_failed: rejected',
    'migration_common_payment_registry:nonterminal_or_unknown_payment_ref_on_invoice; manual reconciliation required'
  ])('requires an explicit bank check for %s', (error) => {
    expect(isPaymentInitManualCheckError(error)).toBe(true);
  });

  it('does not classify ordinary close failures as payment-init checks', () => {
    expect(isPaymentInitManualCheckError('close_failed: order 42')).toBe(false);
  });

  it('identifies only the migration registry quarantine', () => {
    expect(isMigrationPaymentRegistryError('migration_common_payment_registry: invoice=97')).toBe(true);
    expect(isMigrationPaymentRegistryError('payment_init_exception: PKIX')).toBe(false);
  });

  it('keeps unsupported migration conflicts in manual quarantine without enabling confirmation', () => {
    const error = 'migration_common_payment_registry:provider_identity_cross_invoice_collision';
    expect(isMigrationPaymentRegistryError(error)).toBe(true);
    expect(isPaymentInitManualCheckError(error)).toBe(false);
  });
});
