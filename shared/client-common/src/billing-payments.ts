import {
  billingPaymentEnums, billingPaymentSchemas, billingPaymentOptionalFields,
  type CommonBillingAccountResponse, type PublicPaymentLinkResponse, type DeliveryOperation
} from './billing-payments.generated';
import { ClientContractError, validateWire } from './wire-schema';
import { deliveryOperationMessage, deliveryOperationPending } from './delivery-operations';

export { ClientContractError } from './wire-schema';
export { BILLING_PAYMENT_CONTRACT_VERSION } from './billing-payments.generated';
export type { DeliveryOperation, CommonBillingCompanyResponse, CommonBillingAccountResponse, CommonInvoiceSummaryResponse, InvoicePaymentMode } from './billing-payments.generated';
export type PublicPaymentLink = PublicPaymentLinkResponse;

function known(value: string, values: readonly string[]): boolean { return values.includes(value); }

export function decodeCommonBillingAccount(value: unknown): CommonBillingAccountResponse {
  validateWire(value, billingPaymentSchemas.CommonBillingAccountResponse, billingPaymentSchemas, billingPaymentOptionalFields, 'CommonBillingAccountResponse');
  const account = value as CommonBillingAccountResponse;
  if (account.invoicePaymentMode && !known(account.invoicePaymentMode, billingPaymentEnums.InvoicePaymentMode)) {
    throw new ClientContractError('Unsupported invoice payment mode');
  }
  const invoice = account.currentInvoice;
  if (invoice && (!known(invoice.status, billingPaymentEnums.CommonInvoiceStatus)
    || (invoice.invoicePaymentMode && !known(invoice.invoicePaymentMode, billingPaymentEnums.InvoicePaymentMode)))) {
    throw new ClientContractError('Unsupported invoice state');
  }
  return account;
}

export function decodeCommonBillingAccounts(value: unknown): CommonBillingAccountResponse[] {
  if (!Array.isArray(value)) throw new ClientContractError('Invalid billing account list');
  return value.map(decodeCommonBillingAccount);
}

export function decodePublicPaymentLink(value: unknown): PublicPaymentLink {
  validateWire(value, billingPaymentSchemas.PublicPaymentLinkResponse, billingPaymentSchemas, billingPaymentOptionalFields, 'PublicPaymentLinkResponse');
  const payment = { ...(value as PublicPaymentLink) };
  const unsupported = !known(payment.status, billingPaymentEnums.PaymentLinkStatus)
    || (payment.paymentMethod != null && !known(payment.paymentMethod, billingPaymentEnums.PaymentMethod))
    || (payment.paymentPageMode != null && !known(payment.paymentPageMode, billingPaymentEnums.TbankPaymentPageMode));
  if (unsupported) {
    // Preserve the server's unknown state for display while disabling every payment action.
    // Never turn a future status/method into a known payable state or guess a bank route.
    payment.payable = false;
    payment.sbpBankSelectionSupported = false;
    payment.tpayEnabled = false;
    payment.sberpayEnabled = false;
    payment.mirpayEnabled = false;
  }
  return payment;
}

/** Retain platform display models and legacy empty-route presentation; never authorize a future invoice state or route. */
export function guardPublicCommonInvoice<T extends { status: string; paymentRouteType?: string | null; payable: boolean; clientReportable: boolean }>(value: T): T {
  const route = (value.paymentRouteType ?? '').trim().toUpperCase();
  const knownRoute = !route || ['BANK_LINK', 'TBANK_LINK', 'TOCHKA_LINK', 'MANAGER_TEXT', 'MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK'].includes(route);
  if (!known(value.status, billingPaymentEnums.CommonInvoiceStatus) || !knownRoute) {
    return { ...value, payable: false, clientReportable: false };
  }
  return value;
}

/**
 * What a common invoice says while its client message is still being sent (queued, in a retry, or with an outcome
 * the server is still finding out). For that time the server keeps a marker in `lastError`; it is a wait, not an
 * error, so the pages show this text instead of the marker. The text promises nothing: a send that fails or gets
 * stuck replaces the marker with a real error (`delivery_failed`, `message_send_stale`), shown as before.
 */
export function commonInvoiceDeliveryWait(
  lastError: string | null | undefined,
  delivery?: DeliveryOperation | null
): string | null {
  const marker = (lastError ?? '').trim().toLowerCase();
  const routeChanged = marker === 'payment_route_changed_message_in_progress';
  if (!routeChanged && marker !== 'message_send_in_progress') {
    return null;
  }
  // The operation knows more than the marker. An outcome the server could not confirm, or a stopped send, is
  // said as it is: the marker can stay for as long as that operation is unresolved.
  if (delivery && delivery.status !== 'SENT' && !deliveryOperationPending(delivery)) {
    return deliveryOperationMessage(delivery);
  }
  return routeChanged
    ? 'Сообщение клиенту с новым способом оплаты ещё отправляется — это не ошибка. '
      + 'Менять способ оплаты повторно не нужно: если отправка не удастся, здесь появится ошибка.'
    : 'Сообщение клиенту ещё отправляется — это не ошибка. Отправлять повторно не нужно: '
      + 'если отправка не удастся, здесь появится ошибка.';
}

/** Delivery status is independent of the invoice's legacy business status. */
export function commonInvoiceDeliveryWarning(lastError: string | null | undefined): string | null {
  const detail = lastError?.trim();
  if (!detail) return null;
  return commonInvoiceDeliveryWait(detail) ?? `Отправка не подтверждена. ${detail}`;
}

export { deliveryOperationMessage, deliveryOperationPending, DeliveryStatusWatcher } from './delivery-operations';
export * from './manual-payment-review';

export * from './manual-transfer';
