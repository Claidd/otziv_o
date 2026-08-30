import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { afterEach, vi } from 'vitest';
import type { CompanyCardItem, OrderCardItem, OrderDetailsPayload } from '../../core/manager.api';
import type { StatusAction } from './manager-board.config';
import { ManagerBoardActionFacade, type ManagerBoardActionFacadeDeps } from './manager-board-action.facade';

function company(overrides: Partial<CompanyCardItem> = {}): CompanyCardItem {
  return {
    id: 10,
    title: 'Company',
    countFilials: 1,
    status: 'Новая',
    ...overrides
  };
}

function order(overrides: Partial<OrderCardItem> = {}): OrderCardItem {
  return {
    id: 20,
    companyId: 10,
    companyTitle: 'Company',
    status: 'Новый',
    ...overrides
  };
}

function createFacade(config: {
  failCompanyStatus?: boolean;
  orderStatusError?: unknown;
  canOverrideActiveBankPayment?: boolean;
} = {}) {
  const mutationKey = signal<string | null>(null);
  const calls: string[] = [];
  const toastMessages: string[] = [];
  const details = {} as OrderDetailsPayload;
  const deps: ManagerBoardActionFacadeDeps = {
    managerApi: {
      updateCompanyStatus: (companyId: number, status: string) => {
        calls.push(`company-status:${companyId}:${status}`);
        return config.failCompanyStatus
          ? throwError(() => new Error('status failed'))
          : of(void 0);
      },
      updateOrderStatus: (orderId: number, status: string) => {
        calls.push(`order-status:${orderId}:${status}`);
        return config.orderStatusError === undefined
          ? of(void 0)
          : throwError(() => config.orderStatusError);
      },
      updateOrderClientWaiting: (orderId: number, waitingForClient: boolean) => {
        calls.push(`client-waiting:${orderId}:${waitingForClient}`);
        return of(void 0);
      },
      updateCompanyNote: (companyId: number, value: string) => {
        calls.push(`company-note:${companyId}:${value}`);
        return of(void 0);
      },
      updateOrderCompanyNote: (orderId: number, value: string) => {
        calls.push(`order-company-note:${orderId}:${value}`);
        return of(details);
      },
      updateOrderNote: (orderId: number, value: string) => {
        calls.push(`order-note:${orderId}:${value}`);
        return of(details);
      }
    },
    toastService: {
      success: (title: string, message?: string) => {
        toastMessages.push(`success:${title}:${message ?? ''}`);
        return toastMessages.length;
      },
      error: (title: string, message?: string) => {
        toastMessages.push(`error:${title}:${message ?? ''}`);
        return toastMessages.length;
      }
    },
    mutationKey,
    loadBoard: () => {
      calls.push('load-board');
    },
    errorMessage: (_err, fallback) => fallback,
    canOverrideActiveBankPayment: () => config.canOverrideActiveBankPayment ?? true,
    openManualCardPayment: (targetOrder) => {
      calls.push(`open-manual-card:${targetOrder.id}`);
    }
  };

  return {
    facade: new ManagerBoardActionFacade(deps),
    mutationKey,
    calls,
    toastMessages
  };
}

describe('ManagerBoardActionFacade', () => {
  afterEach(() => vi.restoreAllMocks());

  it('updates company and order statuses', () => {
    const { facade, calls, mutationKey, toastMessages } = createFacade();
    const companyAction: StatusAction = { label: 'archive', status: 'Архив', icon: 'archive' };
    const orderAction: StatusAction = { label: 'paid', status: 'Оплачено', icon: 'payments' };

    facade.updateCompanyStatus(company({ id: 10, title: 'Acme' }), companyAction);
    facade.updateOrderStatus(order({ id: 20, companyTitle: 'Acme' }), orderAction);

    expect(calls).toEqual([
      'company-status:10:Архив',
      'load-board',
      'order-status:20:Оплачено',
      'load-board'
    ]);
    expect(mutationKey()).toBeNull();
    expect(toastMessages).toContain('success:Статус изменен:Acme: Архив');
    expect(toastMessages).toContain('success:Статус изменен:Acme: Оплачено');
  });

  it('resets mutation key when status update fails', () => {
    const { facade, calls, mutationKey, toastMessages } = createFacade({ failCompanyStatus: true });

    facade.updateCompanyStatus(company({ id: 10 }), { label: 'archive', status: 'Архив', icon: 'archive' });

    expect(calls).toEqual(['company-status:10:Архив']);
    expect(mutationKey()).toBeNull();
    expect(toastMessages).toContain('error:Статус не изменен:Не удалось изменить статус компании');
  });

  it('opens the manual-card modal once after the exact active-bank conflict', () => {
    const conflict = {
      status: 409,
      error: {
        message: 'У заказа есть незавершенный банковский платёж. Проверьте его в журнале перед ручным закрытием.'
      }
    };
    const { facade, calls, mutationKey, toastMessages } = createFacade({ orderStatusError: conflict });
    const prompt = vi.spyOn(window, 'prompt').mockReturnValue('Устаревший прямой ввод');

    facade.updateOrderStatus(order({
      id: 25047,
      companyTitle: 'Мастер на дом',
      status: 'Напоминание',
      sum: 1000
    }), {
      label: 'оплатили',
      status: 'Оплачено',
      icon: 'payments'
    });

    expect(calls).toEqual([
      'order-status:25047:Оплачено',
      'open-manual-card:25047'
    ]);
    expect(calls.filter((call) => call === 'open-manual-card:25047')).toHaveLength(1);
    expect(prompt).not.toHaveBeenCalled();
    expect(mutationKey()).toBeNull();
    expect(toastMessages).toEqual([]);
  });

  it('opens the manual-card modal for the current T-Bank/SBP conflict text', () => {
    const { facade, calls, toastMessages } = createFacade({
      orderStatusError: {
        status: 409,
        error: {
          message: 'У заказа есть незавершенный T-Bank/СБП платеж. Проверьте его в журнале перед ручным закрытием.'
        }
      }
    });

    facade.updateOrderStatus(order({ id: 25443, sum: 1600 }), {
      label: 'оплатили', status: 'Оплачено', icon: 'payments'
    });

    expect(calls).toEqual([
      'order-status:25443:Оплачено',
      'open-manual-card:25443'
    ]);
    expect(toastMessages).toEqual([]);
  });

  it("opens the manual-card modal for actual-recipient conflict code", () => {
    const { facade, calls, toastMessages } = createFacade({
      orderStatusError: {
        status: 409,
        error: {
          code: "ACTUAL_RECIPIENT_REQUIRED",
          message: "Выберите фактического получателя оплаты перед закрытием заказа."
        }
      }
    });

    facade.updateOrderStatus(order({ id: 25047, sum: 1000 }), {
      label: "оплатили", status: "Оплачено", icon: "payments"
    });

    expect(calls).toEqual([
      "order-status:25047:Оплачено",
      "open-manual-card:25047"
    ]);
    expect(toastMessages).toEqual([]);
  });

  it("opens the manual-card modal for legacy actual-recipient conflict text", () => {
    const { facade, calls, toastMessages } = createFacade({
      orderStatusError: {
        status: 409,
        message: "Нужно заново выбрать фактического получателя оплаты."
      }
    });

    facade.updateOrderStatus(order({ id: 25047, sum: 1000 }), {
      label: "оплатили", status: "Оплачено", icon: "payments"
    });

    expect(calls).toEqual([
      "order-status:25047:Оплачено",
      "open-manual-card:25047"
    ]);
    expect(toastMessages).toEqual([]);
  });
  it('does not open the manual-card modal for a non-paid status', () => {
    const conflict = {
      status: 409,
      error: {
        message: 'У заказа есть незавершенный банковский платёж. Проверьте его в журнале перед ручным закрытием.'
      }
    };
    const { facade, calls, toastMessages } = createFacade({ orderStatusError: conflict });

    facade.updateOrderStatus(order({ id: 25047, sum: 1000 }), {
      label: 'архив', status: 'Архив', icon: 'archive'
    });

    expect(calls).toEqual(['order-status:25047:Архив']);
    expect(calls.some((call) => call.startsWith('open-manual-card:'))).toBe(false);
    expect(toastMessages).toContain('error:Статус не изменен:Не удалось изменить статус заказа');
  });

  it('does not open the manual-card modal for an order in a common invoice', () => {
    const conflict = {
      status: 409,
      error: {
        message: 'У заказа есть незавершенный банковский платёж. Проверьте его в журнале перед ручным закрытием.'
      }
    };
    const { facade, calls, toastMessages } = createFacade({ orderStatusError: conflict });

    facade.updateOrderStatus(order({ id: 25047, sum: 1000, commonInvoice: true }), {
      label: 'оплатили', status: 'Оплачено', icon: 'payments'
    });

    expect(calls).toEqual(['order-status:25047:Оплачено']);
    expect(calls.some((call) => call.startsWith('open-manual-card:'))).toBe(false);
    expect(toastMessages).toContain('error:Статус не изменен:Не удалось изменить статус заказа');
  });

  it('does not bypass an active bank payment for a manager', () => {
    const conflict = {
      status: 409,
      error: {
        message: 'У заказа есть незавершенный банковский платёж. Проверьте его в журнале перед ручным закрытием.'
      }
    };
    const { facade, calls, toastMessages } = createFacade({
      orderStatusError: conflict,
      canOverrideActiveBankPayment: false
    });

    facade.updateOrderStatus(order({ id: 25047, sum: 1000 }), {
      label: 'оплатили', status: 'Оплачено', icon: 'payments'
    });

    expect(calls).toEqual(['order-status:25047:Оплачено']);
    expect(calls.some((call) => call.startsWith('open-manual-card:'))).toBe(false);
    expect(toastMessages).toContain('error:Статус не изменен:Не удалось изменить статус заказа');
  });

  it('does not open the manual-card modal for a different 409 response', () => {
    const { facade, calls, toastMessages } = createFacade({
      orderStatusError: { status: 409, error: { message: 'Другой конфликт' } }
    });

    facade.updateOrderStatus(order({ id: 25047, sum: 1000 }), {
      label: 'оплатили', status: 'Оплачено', icon: 'payments'
    });

    expect(calls).toEqual(['order-status:25047:Оплачено']);
    expect(calls.some((call) => call.startsWith('open-manual-card:'))).toBe(false);
    expect(toastMessages).toContain('error:Статус не изменен:Не удалось изменить статус заказа');
  });

  it('opens the modal for the exact Spring ProblemDetail conflict', () => {
    const { facade, calls, toastMessages } = createFacade({
      orderStatusError: {
        status: 409,
        error: {
          detail: 'У заказа есть незавершенный банковский платёж. Проверьте его в журнале перед ручным закрытием.'
        }
      }
    });

    facade.updateOrderStatus(order({ id: 25047, sum: 1000 }), {
      label: 'оплатили', status: 'Оплачено', icon: 'payments'
    });

    expect(calls).toEqual([
      'order-status:25047:Оплачено',
      'open-manual-card:25047'
    ]);
    expect(calls.filter((call) => call === 'open-manual-card:25047')).toHaveLength(1);
    expect(toastMessages).toEqual([]);
  });
  it('toggles client waiting for an order', () => {
    const { facade, calls, mutationKey, toastMessages } = createFacade();

    facade.toggleOrderClientWaiting(order({ id: 21, companyTitle: 'Acme', waitingForClient: false }));

    expect(calls).toEqual(['client-waiting:21:true', 'load-board']);
    expect(mutationKey()).toBeNull();
    expect(toastMessages).toContain('success:Ждет клиента:Acme');
  });

  it('saves card notes and reloads board', () => {
    const { facade, calls, toastMessages } = createFacade();

    facade.saveCompanyCardNote(company({ id: 10, title: 'Acme' }), 'company note');
    facade.saveOrderCompanyNote(order({ id: 20, companyTitle: 'Acme' }), 'shared company note');
    facade.saveOrderCardNote(order({ id: 20, companyTitle: 'Acme' }), 'order note');

    expect(calls).toEqual([
      'company-note:10:company note',
      'load-board',
      'order-company-note:20:shared company note',
      'load-board',
      'order-note:20:order note',
      'load-board'
    ]);
    expect(toastMessages).toContain('success:Заметка компании сохранена:Acme');
    expect(toastMessages).toContain('success:Заметка заказа сохранена:Acme');
  });
});
