import { of } from 'rxjs';
import type {
  CompanyCardItem,
  CompanyFilialEditItem,
  CompanyOrderCreatePayload,
  CompanyOrderCreateRequest,
  ManagerOption,
  OrderCardItem,
  OrderEditPayload,
  OrderProductOption,
  OrderUpdateRequest
} from '../../core/manager.api';
import { ManagerBoardOrderFacade, type ManagerBoardOrderFacadeDeps } from './manager-board-order.facade';

function option(id: number, label = `Option ${id}`): ManagerOption {
  return { id, label };
}

function filial(id: number, title = `Filial ${id}`): CompanyFilialEditItem {
  return {
    id,
    title,
    url: '',
    city: 'City'
  };
}

function product(id: number, label: string, price = 100): OrderProductOption {
  return {
    id,
    label,
    price,
    photo: false
  };
}

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
    id: 30,
    companyId: 10,
    companyTitle: 'Company',
    status: 'Новый',
    ...overrides
  };
}

function createPayload(overrides: Partial<CompanyOrderCreatePayload> = {}): CompanyOrderCreatePayload {
  return {
    companyId: 10,
    companyTitle: 'Company',
    products: [
      product(1, 'Другой', 50),
      product(2, 'Отзыв 2ГИС +', 120)
    ],
    amounts: [3, 5, 10],
    workers: [option(11, 'Worker')],
    filials: [filial(21)],
    defaultProductId: 1,
    defaultAmount: 10,
    defaultWorkerId: null,
    defaultFilialId: null,
    ...overrides
  };
}

function orderPayload(overrides: Partial<OrderEditPayload> = {}): OrderEditPayload {
  return {
    id: 30,
    companyId: 10,
    companyTitle: 'Company',
    status: 'Новый',
    counter: 2,
    created: '2026-01-01',
    changed: '2026-01-02',
    payDay: '2026-01-03',
    orderComments: 'order note',
    commentsCompany: 'company note',
    complete: false,
    filial: option(21, 'Filial'),
    manager: option(31, 'Manager'),
    worker: option(41, 'Worker'),
    filials: [option(21, 'Filial')],
    managers: [option(31, 'Manager')],
    workers: [option(41, 'Worker')],
    canComplete: true,
    canDelete: true,
    ...overrides
  };
}

function createFacade(config: {
  createPayload?: CompanyOrderCreatePayload;
  orderPayload?: OrderEditPayload;
} = {}) {
  const calls: string[] = [];
  const toastMessages: string[] = [];
  const openedCompanies: string[] = [];
  let lastCreateRequest: CompanyOrderCreateRequest | null = null;
  let lastOrderRequest: OrderUpdateRequest | null = null;
  const sourceCreatePayload = config.createPayload ?? createPayload();
  const sourceOrderPayload = config.orderPayload ?? orderPayload();
  const deps: ManagerBoardOrderFacadeDeps = {
    managerApi: {
      getCompanyOrderCreate: (companyId: number) => {
        calls.push(`get-create:${companyId}`);
        return of(sourceCreatePayload);
      },
      createCompanyOrder: (companyId: number, request: CompanyOrderCreateRequest) => {
        calls.push(`create:${companyId}`);
        lastCreateRequest = request;
        return of({
          companyId,
          companyTitle: sourceCreatePayload.companyTitle,
          productId: request.productId ?? 0,
          productTitle: 'Отзыв 2ГИС +',
          amount: request.amount
        });
      },
      getOrderEdit: (orderId: number) => {
        calls.push(`get-order:${orderId}`);
        return of(sourceOrderPayload);
      },
      updateOrder: (orderId: number, request: OrderUpdateRequest) => {
        calls.push(`update-order:${orderId}`);
        lastOrderRequest = request;
        return of(sourceOrderPayload);
      },
      deleteOrder: (orderId: number) => {
        calls.push(`delete-order:${orderId}`);
        return of(void 0);
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
    loadBoard: () => {
      calls.push('load-board');
    },
    errorMessage: (_err, fallback) => fallback,
    openCreatedCompanyOrders: (result) => {
      openedCompanies.push(`${result.companyId}:${result.companyTitle}`);
    }
  };

  return {
    facade: new ManagerBoardOrderFacade(deps),
    calls,
    toastMessages,
    openedCompanies,
    get lastCreateRequest() {
      return lastCreateRequest;
    },
    get lastOrderRequest() {
      return lastOrderRequest;
    }
  };
}

describe('ManagerBoardOrderFacade', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('opens create-order payload and owns draft totals', () => {
    const { facade, calls } = createFacade();

    facade.openCompanyOrderCreate(company({ id: 10 }));

    expect(calls).toContain('get-create:10');
    expect(facade.createOrderPayload()?.companyTitle).toBe('Company');
    expect(facade.createOrderDraft()).toEqual({
      productId: 2,
      amount: 5,
      workerId: 11,
      filialId: 21
    });
    expect(facade.selectedCreateOrderProduct()?.label).toBe('Отзыв 2ГИС +');
    expect(facade.createOrderTotal()).toBe(600);
  });

  it('validates and creates company order', () => {
    const state = createFacade();

    state.facade.openCompanyOrderCreate(company());
    state.facade.handleCreateOrderDraftChange({ field: 'workerId', value: null });
    state.facade.createCompanyOrder();

    expect(state.lastCreateRequest).toBeNull();
    expect(state.facade.createOrderError()).toBe('Выберите продукт, количество, специалиста и филиал');

    state.facade.handleCreateOrderDraftChange({ field: 'workerId', value: 11 });
    state.facade.createCompanyOrder();

    expect(state.calls).toContain('create:10');
    expect(state.lastCreateRequest?.productId).toBe(2);
    expect(state.openedCompanies).toEqual(['10:Company']);
    expect(state.facade.createOrderPayload()).toBeNull();
    expect(state.toastMessages.some((message) => message.startsWith('success:Заказ создан'))).toBe(true);
  });

  it('opens order edit and saves changed draft', () => {
    const state = createFacade();

    state.facade.openOrderEdit(order({ id: 30 }));
    state.facade.handleOrderEditDraftChange({ field: 'counter', value: 9 });
    state.facade.saveOrderEdit();

    expect(state.calls).toContain('get-order:30');
    expect(state.calls).toContain('update-order:30');
    expect(state.lastOrderRequest?.counter).toBe(9);
    expect(state.facade.editOrder()).toBeNull();
    expect(state.calls).toContain('load-board');
  });

  it('deletes order after confirmation', () => {
    const state = createFacade();
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    state.facade.openOrderEdit(order({ id: 30 }));
    state.facade.deleteOrderEdit();

    expect(state.calls).toContain('delete-order:30');
    expect(state.facade.editOrder()).toBeNull();
    expect(state.toastMessages).toContain('success:Заказ удален:Заказ #30 удален');
    expect(state.calls).toContain('load-board');
  });
});
