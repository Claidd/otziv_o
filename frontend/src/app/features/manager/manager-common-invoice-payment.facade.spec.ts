import { signal } from '@angular/core';
import type { Observable } from 'rxjs';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import type {
  CommonInvoiceDetailsResponse,
  ManualPaymentConfirmationRequest
} from '../../core/common-billing.api';
import type { CommonManualPaymentAttributionModeResponse } from '../../core/common-manual-payment-attribution.api';
import type { OrderCardItem } from '../../core/manager.api';
import {
  ManagerCommonInvoicePaymentFacade,
  type ManagerCommonInvoicePaymentFacadeDeps,
  type ManagerCommonManualPaymentContext
} from './manager-common-invoice-payment.facade';

const paidDetails = { summary: { status: 'PAID' } } as CommonInvoiceDetailsResponse;
const evidence: ManualPaymentConfirmationRequest = {
  comment: 'Сверено менеджером',
  receiptUrl: ''
};

function order(overrides: Partial<OrderCardItem> = {}): OrderCardItem {
  return {
    id: -77,
    companyId: 12,
    companyTitle: 'Компания',
    status: 'Напоминание',
    commonInvoice: true,
    commonInvoiceId: 77,
    ...overrides
  } as OrderCardItem;
}

function setup(
  modeResult: Observable<CommonManualPaymentAttributionModeResponse>,
  legacyEvidence: ManualPaymentConfirmationRequest | null = evidence
) {
  const mutationKey = signal<string | null>('order--77-Оплачено');
  const contexts: ManagerCommonManualPaymentContext[] = [];
  const completed: Array<{ details: CommonInvoiceDetailsResponse; order: OrderCardItem }> = [];
  const failures: Array<{ title: string; message: string }> = [];
  const mode = vi.fn((_invoiceId: number) => modeResult);
  const markPaid = vi.fn((_invoiceId: number, _request: ManualPaymentConfirmationRequest) => of(paidDetails));
  const requestLegacyEvidence = vi.fn((_invoiceId: number) => legacyEvidence);
  const deps: ManagerCommonInvoicePaymentFacadeDeps = {
    attributionApi: { mode },
    commonBillingApi: { markPaid },
    mutationKey,
    requestLegacyEvidence,
    openAttribution: (context) => contexts.push(context),
    completed: (details, targetOrder) => completed.push({ details, order: targetOrder }),
    failed: (title, message) => failures.push({ title, message }),
    errorMessage: (_error, fallback) => fallback
  };

  return {
    facade: new ManagerCommonInvoicePaymentFacade(deps),
    mutationKey,
    contexts,
    completed,
    failures,
    mode,
    markPaid,
    requestLegacyEvidence
  };
}

describe('ManagerCommonInvoicePaymentFacade', () => {
  it('opens the typed STANDARD flow when attribution is required', () => {
    const state = setup(of({ attributionRequired: true }));
    const target = order();

    state.facade.start(target, 77);

    expect(state.mode).toHaveBeenCalledWith(77);
    expect(state.contexts).toEqual([{ invoiceId: 77, order: target, mode: 'STANDARD' }]);
    expect(state.requestLegacyEvidence).not.toHaveBeenCalled();
    expect(state.markPaid).not.toHaveBeenCalled();
    expect(state.mutationKey()).toBe('order--77-Оплачено');
  });

  it('opens the typed TBANK_FALLBACK flow for a standalone-route conflict', () => {
    const state = setup(of({ attributionRequired: true }));
    const target = order({
      commonInvoiceStatus: 'NEEDS_ATTENTION',
      commonInvoiceLastError: ' standalone_payment_route_conflict: активная ссылка '
    });

    state.facade.start(target, 77);

    expect(state.contexts).toEqual([{ invoiceId: 77, order: target, mode: 'TBANK_FALLBACK' }]);
    expect(state.markPaid).not.toHaveBeenCalled();
  });

  it('preserves the legacy evidence flow only for an explicit false mode', () => {
    const state = setup(of({ attributionRequired: false }));
    const target = order();

    state.facade.start(target, 77);

    expect(state.requestLegacyEvidence).toHaveBeenCalledWith(77);
    expect(state.markPaid).toHaveBeenCalledWith(77, evidence);
    expect(state.contexts).toEqual([]);
    expect(state.completed).toEqual([{ details: paidDetails, order: target }]);
    expect(state.mutationKey()).toBeNull();
  });

  it('fails closed when the mode request fails', () => {
    const state = setup(throwError(() => new Error('network')));

    state.facade.start(order(), 77);

    expect(state.requestLegacyEvidence).not.toHaveBeenCalled();
    expect(state.markPaid).not.toHaveBeenCalled();
    expect(state.contexts).toEqual([]);
    expect(state.failures).toEqual([{
      title: 'Режим ручной оплаты не проверен',
      message: 'Не удалось проверить режим учета получателей. Оплата не отмечена.'
    }]);
    expect(state.mutationKey()).toBeNull();
  });

  it('fails closed for a malformed mode response', () => {
    const state = setup(of({} as CommonManualPaymentAttributionModeResponse));

    state.facade.start(order(), 77);

    expect(state.requestLegacyEvidence).not.toHaveBeenCalled();
    expect(state.markPaid).not.toHaveBeenCalled();
    expect(state.failures[0]?.title).toBe('Режим ручной оплаты не проверен');
    expect(state.mutationKey()).toBeNull();
  });

  it('resets the mutation when the manager cancels legacy evidence entry', () => {
    const state = setup(of({ attributionRequired: false }), null);

    state.facade.start(order(), 77);

    expect(state.requestLegacyEvidence).toHaveBeenCalledWith(77);
    expect(state.markPaid).not.toHaveBeenCalled();
    expect(state.mutationKey()).toBeNull();
  });
});
