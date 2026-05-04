import type {
  CompanyOrderCreatePayload,
  CompanyOrderCreateRequest,
  OrderEditPayload,
  OrderProductOption,
  OrderUpdateRequest
} from '../../core/manager.api';
import { PREFERRED_CREATE_ORDER_PRODUCT } from './manager-board.config';

export function managerSelectedCreateOrderProduct(
  payload: CompanyOrderCreatePayload | null,
  draft: CompanyOrderCreateRequest | null
): OrderProductOption | null {
  return payload?.products.find((product) => product.id === draft?.productId) ?? null;
}

export function managerCreateOrderTotal(
  payload: CompanyOrderCreatePayload | null,
  draft: CompanyOrderCreateRequest | null
): number {
  const product = managerSelectedCreateOrderProduct(payload, draft);
  return (product?.price ?? 0) * (draft?.amount ?? 0);
}

export function managerCreateOrderDraft(
  payload: CompanyOrderCreatePayload,
  preferredProductLabel = PREFERRED_CREATE_ORDER_PRODUCT
): CompanyOrderCreateRequest {
  const defaultProductId = managerPreferredOrderProduct(payload.products, preferredProductLabel)?.id
    ?? payload.defaultProductId
    ?? payload.products[0]?.id
    ?? null;
  const defaultAmount = payload.amounts.includes(5)
    ? 5
    : payload.defaultAmount ?? payload.amounts[0] ?? 5;

  return {
    productId: defaultProductId,
    amount: defaultAmount,
    workerId: payload.defaultWorkerId ?? payload.workers[0]?.id ?? null,
    filialId: payload.defaultFilialId ?? payload.filials[0]?.id ?? null
  };
}

export function managerOrderEditDraft(payload: OrderEditPayload): OrderUpdateRequest {
  return {
    filialId: payload.filial?.id ?? null,
    workerId: payload.worker?.id ?? null,
    managerId: payload.manager?.id ?? null,
    counter: payload.counter ?? 0,
    orderComments: payload.orderComments,
    commentsCompany: payload.commentsCompany,
    complete: payload.complete
  };
}

export function managerCreateOrderValidationError(draft: CompanyOrderCreateRequest | null): string | null {
  if (!draft?.productId || !draft.amount || !draft.workerId || !draft.filialId) {
    return 'Выберите продукт, количество, специалиста и филиал';
  }

  return null;
}

export function managerPreferredOrderProduct(
  products: OrderProductOption[],
  preferredProductLabel = PREFERRED_CREATE_ORDER_PRODUCT
): OrderProductOption | undefined {
  const preferred = managerNormalizeProductLabel(preferredProductLabel);
  return products.find((product) => managerNormalizeProductLabel(product.label) === preferred);
}

export function managerNormalizeProductLabel(label: string): string {
  return label
    .toLocaleLowerCase('ru-RU')
    .replace(/ё/g, 'е')
    .replace(/\s+/g, '');
}
