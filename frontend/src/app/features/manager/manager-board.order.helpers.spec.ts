import type {
  CompanyFilialEditItem,
  CompanyOrderCreatePayload,
  ManagerOption,
  OrderEditPayload,
  OrderProductOption
} from '../../core/manager.api';
import {
  managerCreateOrderDraft,
  managerCreateOrderTotal,
  managerCreateOrderValidationError,
  managerNormalizeProductLabel,
  managerOrderEditDraft,
  managerPreferredOrderProduct,
  managerSelectedCreateOrderProduct
} from './manager-board.order.helpers';

function option(id: number, label = `Option ${id}`): ManagerOption {
  return { id, label };
}

function filial(id: number): CompanyFilialEditItem {
  return {
    id,
    title: `Filial ${id}`,
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
    counter: undefined,
    created: '',
    changed: '',
    payDay: '',
    orderComments: 'order note',
    commentsCompany: 'company note',
    complete: false,
    filial: option(21, 'Filial'),
    manager: option(31, 'Manager'),
    worker: option(41, 'Worker'),
    filials: [],
    managers: [],
    workers: [],
    canComplete: true,
    canDelete: true,
    ...overrides
  };
}

describe('manager-board order helpers', () => {
  it('normalizes and finds the preferred create-order product', () => {
    expect(managerNormalizeProductLabel(' Отзыв 2ГИС + ')).toBe('отзыв2гис+');
    expect(managerNormalizeProductLabel('Ёж 2ГИС')).toBe('еж2гис');
    expect(managerPreferredOrderProduct(createPayload().products)?.id).toBe(2);
    expect(managerPreferredOrderProduct(createPayload().products, 'missing')).toBeUndefined();
  });

  it('builds create-order draft with legacy default priority', () => {
    const draft = managerCreateOrderDraft(createPayload());

    expect(draft).toEqual({
      productId: 2,
      amount: 5,
      workerId: 11,
      filialId: 21
    });
  });

  it('falls back to payload defaults and first options when preferred values are unavailable', () => {
    expect(managerCreateOrderDraft(createPayload({
      products: [product(3, 'Обычный')],
      amounts: [2, 8],
      defaultProductId: null,
      defaultAmount: null,
      workers: [],
      filials: []
    }))).toEqual({
      productId: 3,
      amount: 2,
      workerId: null,
      filialId: null
    });

    expect(managerCreateOrderDraft(createPayload({
      products: [],
      amounts: [],
      defaultProductId: 9,
      defaultAmount: 7,
      defaultWorkerId: 12,
      defaultFilialId: 13
    }))).toEqual({
      productId: 9,
      amount: 7,
      workerId: 12,
      filialId: 13
    });
  });

  it('selects product and calculates create-order total', () => {
    const payload = createPayload();
    const draft = { productId: 2, amount: 4, workerId: 11, filialId: 21 };

    expect(managerSelectedCreateOrderProduct(payload, draft)?.label).toBe('Отзыв 2ГИС +');
    expect(managerSelectedCreateOrderProduct(payload, { ...draft, productId: 99 })).toBeNull();
    expect(managerCreateOrderTotal(payload, draft)).toBe(480);
    expect(managerCreateOrderTotal(null, draft)).toBe(0);
  });

  it('validates required create-order fields', () => {
    expect(managerCreateOrderValidationError(null)).toBe('Выберите продукт, количество, специалиста и филиал');
    expect(managerCreateOrderValidationError({ productId: 2, amount: 0, workerId: 11, filialId: 21 }))
      .toBe('Выберите продукт, количество, специалиста и филиал');
    expect(managerCreateOrderValidationError({ productId: 2, amount: 5, workerId: 11, filialId: 21 })).toBeNull();
  });

  it('builds order edit draft from payload', () => {
    expect(managerOrderEditDraft(orderPayload())).toEqual({
      filialId: 21,
      workerId: 41,
      managerId: 31,
      counter: 0,
      orderComments: 'order note',
      commentsCompany: 'company note',
      complete: false
    });

    expect(managerOrderEditDraft(orderPayload({
      counter: 3,
      filial: null,
      manager: null,
      worker: null,
      complete: true
    }))).toEqual({
      filialId: null,
      workerId: null,
      managerId: null,
      counter: 3,
      orderComments: 'order note',
      commentsCompany: 'company note',
      complete: true
    });
  });
});
