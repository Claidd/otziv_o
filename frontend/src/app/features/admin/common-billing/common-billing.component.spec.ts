import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { Subject, of, throwError } from 'rxjs';
import {
  PAYMENT_INIT_NO_PAYMENT_BUTTON_LABEL,
  CommonBillingComponent,
  PAPER_INVOICE_ISSUED_ROLES,
  canMarkCommonInvoicePaid,
  canReturnOrdersToCommonInvoice,
  commonInvoicePaymentEvidence,
  commonInvoiceProblemCode,
  commonInvoicePaymentEvidenceSnapshot,
  commonInvoiceStatusProblem,
  isIncompletePartiallyPaidInvoice,
  isManualTbankReconciliationRetryError,
  isMigrationPaymentRegistryError,
  isPaymentInitManualCheckError,
  paymentInitNoPaymentActionLabel,
  paymentInitNoPaymentConfirmation,
  paymentInitNoPaymentInstructions,
  paperInvoiceRequiresDeliveryConfirmation
} from './common-billing.component';
import { commonInvoiceDeliveryWait, commonInvoiceDeliveryWarning } from '@otziv/client-common/billing-payments';
import type {
  CommonInvoiceDetailsResponse,
  CommonInvoiceOrderResponse,
  CommonInvoiceReturnableOrdersResponse
} from '../../../core/common-billing.api';

describe('ordinary payment after cancelling only one paper part', () => {
  for (const ordinary of [true,false]) {
    it(ordinary ? 'copies the selected order payment instructions' : 'keeps the common link for other orders', async () => {
      const prepare=vi.fn(()=>of({copyText:'ordinary order instructions',url:'https://example.test/pay'}));
      const copyText=vi.fn();
      const context={invoiceOrderInfo:()=>({ordinaryAfterPaperCancel:ordinary}),paymentsApi:{createOrderPaymentLink:prepare},
        currentInvoice:()=>({publicUrl:'https://example.test/common'}),copyText} as unknown as CommonBillingComponent;
      await CommonBillingComponent.prototype.copyOrderText.call(context,{id:28293} as Parameters<CommonBillingComponent['copyOrderText']>[0],'payment');
      expect(copyText.mock.calls[0][0]).toBe(ordinary ? 'ordinary order instructions' : 'https://example.test/common');
      expect(prepare).toHaveBeenCalledTimes(ordinary ? 1 : 0);
    });
  }
  it('shows an ordinary-route failure without falling back to the wrong common payment',async()=>{
    const copyText=vi.fn(),error=vi.fn();
    const context={invoiceOrderInfo:()=>({ordinaryAfterPaperCancel:true}),paymentsApi:{createOrderPaymentLink:()=>throwError(()=>new Error('unavailable'))},
      copyText,toastService:{error}} as unknown as CommonBillingComponent;
    await CommonBillingComponent.prototype.copyOrderText.call(context,{id:28293} as Parameters<CommonBillingComponent['copyOrderText']>[0],'payment');
    expect(error).toHaveBeenCalledOnce();expect(copyText).not.toHaveBeenCalled();
  });
});

describe('invoice composition confirmation', () => {
  for (const action of ['detachOrder', 'archiveDisputedOrder'] as const) {
    it(`${action} asks before submitting and cancellation keeps the invoice untouched`, () => {
      const invoiceAction = vi.fn();
      const context = {
        currentInvoice: () => ({ id: 297, status: 'NEEDS_ATTENTION', sentAt: null }),
        mutating: () => '',
        invoiceAction
      } as unknown as CommonBillingComponent;
      const position = { orderId: 27391, detachable: true, paid: false, originalOrderStatus: 'На проверке' } as CommonInvoiceOrderResponse;
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
      try {
        CommonBillingComponent.prototype[action].call(context, position);
        expect(confirm).toHaveBeenCalledOnce();
        expect(confirm.mock.calls[0][0]).toContain('27391');
        expect(confirm.mock.calls[0][0]).toContain('297');
        expect(confirm.mock.calls[0][0]).toContain('резерв освобождён');
        expect(invoiceAction).not.toHaveBeenCalled();

        confirm.mockReturnValue(true);
        CommonBillingComponent.prototype[action].call(context, position);
        expect(invoiceAction).toHaveBeenCalledOnce();
      } finally { confirm.mockRestore(); }
    });
  }

  it('the order card archive action uses the same atomic invoice archive and its confirmation', () => {
    const position = { orderId: 27391 } as CommonInvoiceOrderResponse;
    const archiveDisputedOrder = vi.fn();
    const context = {
      invoiceDetails: () => ({ orders: [position] }), archiveDisputedOrder
    } as unknown as CommonBillingComponent;
    CommonBillingComponent.prototype.updateOrderStatus.call(
      context,
      { id: 27391 } as Parameters<CommonBillingComponent['updateOrderStatus']>[0],
      { status: 'Архив' } as Parameters<CommonBillingComponent['updateOrderStatus']>[1]
    );
    expect(archiveDisputedOrder).toHaveBeenCalledWith(position);
  });
});

describe('paper invoice issue access', () => {
  it('checks the bank and reloads the invoice without sending a manual payment confirmation',()=>{
    const reconcilePaperInvoice=vi.fn(()=>of(undefined));
    const invoice=vi.fn(()=>of({summary:{id:322,status:'INVOICED'}}));
    const markPaperInvoicePaid=vi.fn();
    const context={currentInvoice:()=>({id:322,status:'INVOICED'}),mutating:()=>'',paperWorkflow:()=>({bankManaged:true}),
      commonBillingApi:{reconcilePaperInvoice,invoice,markPaperInvoicePaid},
      invoiceAction:(_id:number,_key:string,operation:()=>ReturnType<typeof invoice>)=>operation().subscribe()
    } as unknown as CommonBillingComponent;
    CommonBillingComponent.prototype.reconcilePaperInvoice.call(context);
    expect(reconcilePaperInvoice).toHaveBeenCalledExactlyOnceWith(322);
    expect(invoice).toHaveBeenCalledExactlyOnceWith(322);
    expect(markPaperInvoicePaid).not.toHaveBeenCalled();
  });
  it('lets a manager record document delivery without granting payment-mode administration', () => {
    expect(PAPER_INVOICE_ISSUED_ROLES).toEqual(['ADMIN', 'OWNER', 'MANAGER']);
  });

  it('keeps paid disabled until paper-invoice delivery is confirmed', () => {
    const invoice = {
      status: 'INVOICED',
      totalOrders: 8,
      readyOrders: 8,
      invoicePaymentMode: 'OWNER_PAPER_INVOICE',
      paperInvoiceIssuedAt: null
    } as const;

    expect(paperInvoiceRequiresDeliveryConfirmation(invoice)).toBe(true);
    expect(canMarkCommonInvoicePaid(invoice)).toBe(false);
    expect(canMarkCommonInvoicePaid({
      ...invoice,
      paperInvoiceIssuedAt: '2026-08-30T12:00:00'
    })).toBe(true);
  });

  it('does not add the paper-invoice gate to other payment modes', () => {
    expect(canMarkCommonInvoicePaid({
      status: 'INVOICED',
      totalOrders: 8,
      readyOrders: 8,
      invoicePaymentMode: 'AUTO_ROUTING',
      paperInvoiceIssuedAt: null
    })).toBe(true);
  });
});

describe('commonInvoiceStatusProblem', () => {
  it.each(['INVOICED', 'REMINDER', 'PARTIALLY_PAID', 'PAID'])(
    'does not show a false warning for the normal %s lifecycle state',
    (status) => {
      expect(commonInvoiceStatusProblem(status)).toBe('');
    }
  );

  it('keeps actionable warnings for states that require intervention', () => {
    expect(commonInvoiceStatusProblem('READY')).toContain('готов к отправке');
    expect(commonInvoiceStatusProblem('NEEDS_ATTENTION')).toContain('ручного разбора');
  });
});

describe('isManualTbankReconciliationRetryError', () => {
  it('allows retry only for an unfinished or ambiguous old-route reconciliation', () => {
    expect(isManualTbankReconciliationRetryError(
      'manual_payment_tbank_reconciliation_in_progress:INVOICED'
    )).toBe(true);
    expect(isManualTbankReconciliationRetryError(
      'manual_payment_tbank_reconciliation_retry:INVOICED: timeout'
    )).toBe(true);
    expect(isManualTbankReconciliationRetryError(
      'manual_payment_tbank_payment_detected:INVOICED: CONFIRMED'
    )).toBe(false);
  });
});

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
      tbankTerminalKey: 'terminal-key',
      contractorPaymentRoute: false
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

describe('payment-init manual-check copy', () => {
  it('names the action as a no-payment outcome without implying paid or unpaid status', () => {
    expect(PAYMENT_INIT_NO_PAYMENT_BUTTON_LABEL).toBe('В банке оплаты нет — продолжить');

    const helper = paymentInitNoPaymentActionLabel();
    expect(helper).toContain('по перечисленным OrderId и PaymentId в банке платежа');
    expect(helper).toContain('не будет отмечен оплаченным');
    expect(helper).toContain('не перейдёт в статус «Не оплачено»');
    expect(helper).toContain('отдельным действием «Оплачено»');
    expect(helper).toContain('комментарий или чек');
  });

  it('branches explicitly for active, successful and cancel/refund payments', () => {
    const instructions = paymentInitNoPaymentInstructions().join(' ');

    expect(instructions).toContain('кабинете банка, указанного в маршруте счёта');
    expect(instructions).toContain('ищите платёж по OrderId');
    expect(instructions).toContain('PaymentId — это отдельный идентификатор платежа');
    expect(instructions).toContain('Не подставляйте его вместо OrderId');
    expect(instructions).toContain('выберите поиск по PaymentId');
    expect(instructions).toContain('найден активный платёж');
    expect(instructions).toContain('найден успешный платёж');
    expect(instructions).toContain('отмена/возврат');
    expect(instructions).toContain('не нажимайте кнопку');
  });

  it('makes the confirmation acknowledge only that no actionable payment exists', () => {
    const confirmation = paymentInitNoPaymentConfirmation('\n\nПроверьте: OrderId test');

    expect(confirmation).toContain('РЕЗУЛЬТАТ ПРОВЕРКИ БАНКА: ПО УКАЗАННЫМ ID ОПЛАТЫ НЕТ');
    expect(confirmation).toContain('ВСЕ перечисленные OrderId и PaymentId');
    expect(confirmation).toContain('не активен, не успешен');
    expect(confirmation).toContain('ТОЛЬКО отсутствие оплаты по перечисленным банковским ID');
    expect(confirmation).toContain('НЕ отмечает счёт оплаченным');
    expect(confirmation).toContain('НЕ переводит его в «Не оплачено»');
    expect(confirmation).toContain('отдельным действием «Оплачено»');
    expect(confirmation).toContain('комментарий или чек');
    expect(confirmation).toContain('OrderId test');
  });
});

describe('recipient shown in the route block', () => {
  const proto = CommonBillingComponent.prototype;
  const bank = { invoicePaymentMode: 'AUTO_ROUTING', paymentRouteType: 'TBANK_LINK', paymentRouteProfileName: 'Основной магазин',
    paymentRouteProvider: 'T_BANK', paymentRouteRecipient: null, paymentRouteManualTaskId: null } as unknown as Parameters<CommonBillingComponent['routeRecipientName']>[0];
  const card = { invoicePaymentMode: 'AUTO_ROUTING', paymentRouteType: 'MANUAL_MOBILE_BANK',
    paymentRouteRecipient: 'Мария Олеговна Р. (Сбер) · специалист заказа: Лидия З.', paymentRouteManualTaskId: 12 } as unknown as Parameters<CommonBillingComponent['routeRecipientName']>[0];

  it('names the owner for a bank link and the person for a card number', () => {
    expect(proto.routeRecipientName.call({}, bank)).toBe('Владелец');
    expect(proto.routeMethodLabel.call({}, bank)).toContain('банковская ссылка');
    expect(proto.routeRecipientName.call({}, card)).toBe('Мария Олеговна Р. (Сбер)');
    expect(proto.routeMethodLabel.call({}, card)).toBe('номер карты · специалист заказа: Лидия З.');
  });

  it('one card-number button keeps any recipient from a link and asks for another person from a number', () => {
    expect(proto.cardNumberRouteTarget.call({}, bank)).toBe('EMPLOYEE_REQUISITES');
    expect(proto.cardNumberRouteTarget.call({}, card)).toBe('DIFFERENT_RECIPIENT');
  });

  it('opens the recipient window for a card number and shows «было → стало» after the change', () => {
    const invoiceAction = vi.fn();
    const success = vi.fn();
    const lastRouteChange = { set: vi.fn() };
    const changeRoute = vi.fn();
    let mutation = '';
    const context = {
      currentInvoice: () => ({ ...bank, id: 385, amountKopecks: 560_000, paidKopecks: 0 }),
      mutating: Object.assign(() => mutation, { set: (value: string) => { mutation = value; } }),
      invoiceViewGeneration: 1,
      isCurrentPageView: () => true,
      commonBillingApi: {
        commonInvoicePaymentRouteChangeContext: () => of({ canChange: true, currentRecipient: 'Владелец', paymentEvidenceToken: 't' }),
        commonInvoiceRouteRecipients: () => of({ invoiceId: 385, amountKopecks: 560_000, recipients: [
          { profileId: 44, role: 'SPECIALIST', name: 'Лидия З.', orderSpecialist: true, manager: false, teamMember: true,
            workedWithClient: true, availableKopecks: 100_000, overrunKopecks: 460_000, current: false, blockReason: null }
        ] }),
        changeCommonInvoicePaymentRoute: changeRoute
      },
      toastService: { success, error: vi.fn() },
      lastRouteChange,
      invoiceAction,
      recipientPicker: signal(null),
      recipientPickerOptions: signal(null),
      recipientPickerLoading: signal(false),
      recipientPickerError: signal(''),
      recipientPickerSubmitError: signal(''),
      loadRecipientPickerOptions: proto.loadRecipientPickerOptions,
      closeRecipientPicker: proto.closeRecipientPicker,
      routeDescription: proto.routeDescription,
      routeRecipientName: proto.routeRecipientName,
      routeMethodLabel: proto.routeMethodLabel
    } as unknown as CommonBillingComponent;
    const confirm = vi.spyOn(window, 'confirm');
    try {
      proto.changeCommonInvoicePaymentRoute.call(context, 'EMPLOYEE_REQUISITES');
      // No browser confirmation any more: the window itself names the consequence.
      expect(confirm).not.toHaveBeenCalled();
      expect(invoiceAction).not.toHaveBeenCalled();
      expect(context.recipientPicker()).toEqual(expect.objectContaining({
        invoiceId: 385, target: 'EMPLOYEE_REQUISITES', token: 't', amountKopecks: 560_000
      }));
      expect(context.recipientPickerOptions()?.map(option => option.profileId)).toEqual([44]);

      proto.submitRecipientChoice.call(context, { profileId: 44 });
      expect(invoiceAction).toHaveBeenCalledOnce();
      (invoiceAction.mock.calls[0][2] as () => unknown)();
      expect(changeRoute).toHaveBeenCalledWith(385, 'EMPLOYEE_REQUISITES', 't', null, 44);

      // A refusal keeps the window open, so another person can be chosen at once.
      const onError = invoiceAction.mock.calls[0][5] as (message: string, status?: number) => boolean;
      expect(onError('Выбранному получателю сейчас нельзя выдать счёт: допуск к приёму оплат выключен', 409)).toBe(true);
      expect(context.recipientPicker()).not.toBeNull();
      expect(context.recipientPickerSubmitError()).toContain('допуск');

      const onSuccess = invoiceAction.mock.calls[0][4] as (details: unknown) => void;
      onSuccess({ summary: { ...card, id: 385 } });
      expect(context.recipientPicker()).toBeNull();
      expect(lastRouteChange.set).toHaveBeenCalledWith(expect.objectContaining({
        invoiceId: 385,
        from: expect.stringContaining('Владелец'),
        to: expect.stringContaining('Мария Олеговна Р. (Сбер)')
      }));
      expect(success).toHaveBeenCalledWith('Получатель изменён', expect.stringContaining('→'));
    } finally {
      confirm.mockRestore();
    }
  });
});

describe('returning an excluded order to the collecting invoice', () => {
  const proto = CommonBillingComponent.prototype;
  const collecting = { id: 318, status: 'COLLECTING', sentAt: null, paymentRouteSelectedAt: null,
    paperInvoiceIssuedAt: null, invoicePaymentMode: 'AUTO_ROUTING', invoicePurpose: 'STANDARD' };
  const row = { orderId: 27391, companyId: 20, companyTitle: 'Компания', orderTitle: 'Заказ №27391 · Центр',
    status: 'Опубликовано', amountKopecks: 100_000 };
  const answer: CommonInvoiceReturnableOrdersResponse = { invoiceId: 318, canReturn: true, blockReason: null, orders: [row] };

  function page(invoice: Record<string, unknown> = collecting) {
    let mutation = '';
    const returnableOrders = vi.fn((_invoiceId: number) => of(answer));
    const returnOrder = vi.fn();
    const invoiceAction = vi.fn();
    const reloadCurrentInvoice = vi.fn();
    const toast = { success: vi.fn(), error: vi.fn() };
    const context = {
      currentInvoice: () => invoice,
      mutating: Object.assign(() => mutation, { set: (value: string) => { mutation = value; } }),
      commonBillingApi: { returnableOrders, returnOrder },
      toastService: toast,
      invoiceAction,
      reloadCurrentInvoice,
      returnOrdersWindow: signal<{ invoiceId: number } | null>(null),
      returnableOrders: signal<CommonInvoiceReturnableOrdersResponse | null>(null),
      returnableOrdersLoading: signal(false),
      returnableOrdersError: signal(''),
      returnOrderRefusal: signal(''),
      loadReturnableOrders: proto.loadReturnableOrders,
      closeReturnOrders: proto.closeReturnOrders
    } as unknown as CommonBillingComponent;
    return { context, returnableOrders, returnOrder, invoiceAction, reloadCurrentInvoice, toast };
  }

  it('offers the button only while the composition of the invoice can still change', () => {
    expect(canReturnOrdersToCommonInvoice(collecting)).toBe(true);
    expect(canReturnOrdersToCommonInvoice({ ...collecting, status: 'READY' })).toBe(true);
    expect(canReturnOrdersToCommonInvoice({ ...collecting, status: 'INVOICED', sentAt: '2026-11-10T10:00:00' })).toBe(false);
    expect(canReturnOrdersToCommonInvoice({ ...collecting, status: 'READY', sentAt: '2026-11-10T10:00:00' })).toBe(false);
    expect(canReturnOrdersToCommonInvoice({ ...collecting, paymentRouteSelectedAt: '2026-11-10T10:00:00' })).toBe(false);
    expect(canReturnOrdersToCommonInvoice({ ...collecting, invoicePaymentMode: 'OWNER_PAPER_INVOICE' })).toBe(false);
    expect(canReturnOrdersToCommonInvoice({ ...collecting, invoicePurpose: 'BAD_REVIEW_SUCCESSOR' })).toBe(false);
    expect(canReturnOrdersToCommonInvoice({ ...collecting, status: 'NEEDS_ATTENTION' })).toBe(false);
    expect(canReturnOrdersToCommonInvoice({ ...collecting, status: 'PAID' })).toBe(false);
    expect(canReturnOrdersToCommonInvoice(null)).toBe(false);
  });

  it('opens the window with the orders the server offers for this invoice', () => {
    const { context, returnableOrders, invoiceAction } = page();
    const confirm = vi.spyOn(window, 'confirm');
    try {
      proto.openReturnOrders.call(context);
      expect(returnableOrders).toHaveBeenCalledExactlyOnceWith(318);
      expect(context.returnOrdersWindow()).toEqual({ invoiceId: 318 });
      expect(context.returnableOrders()?.orders).toEqual([row]);
      expect(context.returnableOrdersLoading()).toBe(false);
      // Opening the window changes nothing and asks nothing: the confirmation is a step inside it.
      expect(confirm).not.toHaveBeenCalled();
      expect(invoiceAction).not.toHaveBeenCalled();
    } finally {
      confirm.mockRestore();
    }
  });

  it('does not open for a sent invoice or while another action is running', () => {
    const sent = page({ ...collecting, status: 'INVOICED', sentAt: '2026-11-10T10:00:00' });
    proto.openReturnOrders.call(sent.context);
    expect(sent.returnableOrders).not.toHaveBeenCalled();
    expect(sent.context.returnOrdersWindow()).toBeNull();

    const busy = page();
    busy.context.mutating.set('send-invoice');
    proto.openReturnOrders.call(busy.context);
    expect(busy.returnableOrders).not.toHaveBeenCalled();
    expect(busy.context.returnOrdersWindow()).toBeNull();
  });

  it('shows a failed load inside the window and loads again on retry', () => {
    const { context, returnableOrders } = page();
    returnableOrders.mockReturnValueOnce(throwError(() => ({ status: 500 })));
    proto.openReturnOrders.call(context);
    expect(context.returnOrdersWindow()).not.toBeNull();
    expect(context.returnableOrdersError()).not.toBe('');
    expect(context.returnableOrders()).toBeNull();
    expect(context.returnableOrdersLoading()).toBe(false);

    proto.loadReturnableOrders.call(context);
    expect(context.returnableOrdersError()).toBe('');
    expect(context.returnableOrders()?.orders).toEqual([row]);
  });

  it('ignores a list that arrives after the window was closed', () => {
    const { context, returnableOrders } = page();
    const pending = new Subject<CommonInvoiceReturnableOrdersResponse>();
    returnableOrders.mockReturnValueOnce(pending.asObservable());
    proto.openReturnOrders.call(context);
    expect(context.returnableOrdersLoading()).toBe(true);
    proto.closeReturnOrders.call(context);
    pending.next(answer);
    expect(context.returnOrdersWindow()).toBeNull();
    expect(context.returnableOrders()).toBeNull();
    expect(context.returnableOrdersLoading()).toBe(false);
  });

  it('returns the confirmed order, names the result and closes the window', () => {
    const { context, returnOrder, invoiceAction, toast } = page();
    proto.openReturnOrders.call(context);
    proto.returnOrder.call(context, row);
    expect(invoiceAction).toHaveBeenCalledOnce();
    const [invoiceId, key, operation, successTitle, onSuccess] = invoiceAction.mock.calls[0];
    expect(invoiceId).toBe(318);
    expect(key).toBe('return-order-27391');
    expect(successTitle).toBe('Заказ №27391 возвращён в общий счёт');
    (operation as () => unknown)();
    expect(returnOrder).toHaveBeenCalledExactlyOnceWith(318, 27391);

    // invoiceAction puts the returned invoice on the page; the window has nothing left to show.
    (onSuccess as (details: unknown) => void)({ summary: collecting });
    expect(context.returnOrdersWindow()).toBeNull();
    expect(context.returnableOrders()).toBeNull();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('a refusal says the invoice is unchanged, stays in the window and refreshes the list and the invoice', () => {
    const { context, returnableOrders, invoiceAction, reloadCurrentInvoice, toast } = page();
    proto.openReturnOrders.call(context);
    proto.returnOrder.call(context, row);
    const onError = invoiceAction.mock.calls[0][5] as (message: string, status?: number) => boolean;
    const refusal = 'Заказ не возвращён, счёт не изменён. Общий счёт уже отправлен клиенту, его состав зафиксирован. '
      + 'Что делать: ведите заказ отдельно — он оплачивается своим счётом.';

    expect(onError(refusal, 409)).toBe(true);
    expect(toast.error).toHaveBeenCalledExactlyOnceWith('Заказ не возвращён', refusal);
    expect(toast.success).not.toHaveBeenCalled();
    expect(context.returnOrdersWindow()).toEqual({ invoiceId: 318 });
    expect(context.returnOrderRefusal()).toBe(refusal);
    expect(returnableOrders).toHaveBeenCalledTimes(2);
    expect(reloadCurrentInvoice).toHaveBeenCalledOnce();
  });

  it('does not start a second return while one is running or after the window was closed', () => {
    const running = page();
    proto.openReturnOrders.call(running.context);
    running.context.mutating.set('return-order-27391');
    proto.returnOrder.call(running.context, row);
    expect(running.invoiceAction).not.toHaveBeenCalled();

    const closed = page();
    proto.returnOrder.call(closed.context, row);
    expect(closed.invoiceAction).not.toHaveBeenCalled();
  });
});

describe('a client message that is still in the queue', () => {
  const sending = 'message_send_in_progress';
  const routeChanged = 'payment_route_changed_message_in_progress';

  it('is said as a wait in plain words, never printed as a code', () => {
    expect(commonInvoiceDeliveryWait(sending))
      .toBe('Сообщение клиенту ещё отправляется — это не ошибка. Отправлять повторно не нужно: '
        + 'если отправка не удастся, здесь появится ошибка.');
    expect(commonInvoiceDeliveryWait(` ${routeChanged.toUpperCase()} `))
      .toBe('Сообщение клиенту с новым способом оплаты ещё отправляется — это не ошибка. '
        + 'Менять способ оплаты повторно не нужно: если отправка не удастся, здесь появится ошибка.');
    for (const text of [commonInvoiceDeliveryWait(sending), commonInvoiceDeliveryWait(routeChanged)]) {
      expect(text).not.toMatch(/[a-z_]{6,}/u);
    }
  });

  it('is not a problem of the invoice: the problem block gets no code for it', () => {
    expect(commonInvoiceProblemCode(sending)).toBe('');
    expect(commonInvoiceProblemCode(routeChanged)).toBe('');
    expect(commonInvoiceProblemCode(null)).toBe('');
  });

  it('does not call the invoice stuck: it is «READY» only for the time of the send', () => {
    const stuck = 'Счет готов к отправке. Проверьте, не завис ли он без выставления клиенту.';
    expect(commonInvoiceStatusProblem('READY')).toBe(stuck);
    expect(commonInvoiceStatusProblem('READY', null)).toBe(stuck);
    expect(commonInvoiceStatusProblem('READY', 'message_send_stale: previous attempt')).toBe(stuck);
    expect(commonInvoiceStatusProblem('READY', sending)).toBe('');
    expect(commonInvoiceStatusProblem('READY', routeChanged)).toBe('');
    // Every other status keeps its text whatever the marker says.
    expect(commonInvoiceStatusProblem('NEEDS_ATTENTION', routeChanged)).toBe('Счет требует ручного разбора.');
  });

  it('says an unconfirmed or stopped send as it is while the marker is still there', () => {
    const operation = (status: string, errorCode: string | null = null) =>
      ({ operationId: 'invoice-message:312:1', status, attempts: 1, errorCode });
    const wait = commonInvoiceDeliveryWait(routeChanged);
    for (const pending of ['QUEUED', 'SENDING', 'RETRYABLE', 'SENT']) {
      expect(commonInvoiceDeliveryWait(routeChanged, operation(pending))).toBe(wait);
    }
    expect(commonInvoiceDeliveryWait(routeChanged, null)).toBe(wait);
    expect(commonInvoiceDeliveryWait(routeChanged, operation('UNKNOWN', 'operation_unknown')))
      .toBe('Исход отправки уточняется. Повторная рассылка заблокирована.');
    expect(commonInvoiceDeliveryWait(sending, operation('FAILED')))
      .toBe('Сообщение не отправлено. Проверьте результат попыток перед повтором.');
    expect(commonInvoiceDeliveryWait(sending, operation('UNKNOWN'))).not.toContain('это не ошибка');
    // Without the marker the operation alone is not a wait of the invoice.
    expect(commonInvoiceDeliveryWait(null, operation('UNKNOWN'))).toBeNull();
    expect(commonInvoiceDeliveryWait('whatsapp_group_missing', operation('QUEUED'))).toBeNull();
  });

  it('promises nothing about the outcome and says where a failure will show', () => {
    for (const text of [commonInvoiceDeliveryWait(sending)!, commonInvoiceDeliveryWait(routeChanged)!]) {
      expect(text).toContain('это не ошибка');
      expect(text).toContain('если отправка не удастся, здесь появится ошибка');
      expect(text).not.toContain('уйдёт само');
    }
  });

  it('leaves every real error as it was, including a send that got stuck', () => {
    for (const error of ['message_send_stale: previous attempt', 'message_send_unknown', 'whatsapp_group_missing',
      'payment_init_conflict: duplicate request', 'message_send_in_progress_extra', 'constructor', 'toString']) {
      expect(commonInvoiceDeliveryWait(error)).toBeNull();
      expect(commonInvoiceProblemCode(` ${error} `)).toBe(error);
      expect(commonInvoiceDeliveryWarning(error)).toBe(`Отправка не подтверждена. ${error}`);
    }
    expect(commonInvoiceDeliveryWait('')).toBeNull();
    expect(commonInvoiceDeliveryWait(undefined)).toBeNull();
  });

  it('the notice after sending says the wait instead of «не подтверждена» with a code', () => {
    expect(commonInvoiceDeliveryWarning(sending)).toBe(commonInvoiceDeliveryWait(sending));
    expect(commonInvoiceDeliveryWarning(routeChanged)).toBe(commonInvoiceDeliveryWait(routeChanged));
    expect(commonInvoiceDeliveryWarning('  ')).toBeNull();
  });
});
