import type { PaymentRouteChangeContext, PaymentRouteChangeTarget } from '../../core/manager.api';

/**
 * One «Выдать номер карты» action replaces «реквизиты без ссылки» and «сменить получателя».
 * From a bank link any transfer number fits, the same recipient included; once the order
 * already has a transfer number, the client wants another person.
 */
export function cardNumberRouteTarget(context: PaymentRouteChangeContext): PaymentRouteChangeTarget {
  if (context.paymentLinkId == null) {
    return 'EMPLOYEE_REQUISITES';
  }
  const bankLink = context.bankRecipientChangeAvailable === true
    || context.configuredMode === 'OWNER_TBANK'
    || (context.currentRoute ?? '').trim().startsWith('Эквайринг');
  return bankLink ? 'EMPLOYEE_REQUISITES' : 'DIFFERENT_RECIPIENT';
}
