import {
  billingPaymentEnums, billingPaymentSchemas, billingPaymentOptionalFields,
  type CommonBillingAccountResponse, type PublicPaymentLinkResponse, type DeliveryOperation
} from './billing-payments.generated';
import { ClientContractError, validateWire } from './wire-schema';

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

/** Delivery status is independent of the invoice's legacy business status. */
export function commonInvoiceDeliveryWarning(lastError: string | null | undefined): string | null {
  const detail = lastError?.trim();
  return detail ? `Отправка не подтверждена. ${detail}` : null;
}

export { deliveryOperationMessage, deliveryOperationPending, DeliveryStatusWatcher } from './delivery-operations';
