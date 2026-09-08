import type {
  CommonInvoiceOrderResponseOutput, PublicCommonInvoiceResponseOutput,
  PublicPaymentInitResponseOutput, PublicSbpBankResponseOutput
} from './client-api.generated';
import { ClientContractError } from './wire-schema';
import { guardPublicCommonInvoice } from './billing-payments';

/** UI projections of generated wire DTOs. Required display/action fields are
 * narrowed at the boundary; legacy optional fields retain their nullability.
 * These models never derive amounts, routes or permissions on the client. */
type Presentation<T, RequiredFields extends keyof T> =
  { [Field in RequiredFields]-?: NonNullable<T[Field]> } & Partial<Omit<T, RequiredFields>>;

// QR-only and unconfirmed bank responses may legitimately have no URL/ID.
export type PublicPaymentInitResponse = Pick<PublicPaymentInitResponseOutput, 'paymentUrl' | 'paymentId' | 'status'>
  & Partial<Omit<PublicPaymentInitResponseOutput, 'paymentUrl' | 'paymentId' | 'status'>>;
export type PublicSbpBank = Presentation<PublicSbpBankResponseOutput, 'bankId' | 'name' | 'featured'>;
export type PublicCommonInvoiceOrder = Presentation<CommonInvoiceOrderResponseOutput,
  'orderId' | 'companyId' | 'companyTitle' | 'orderStatus' | 'amount' | 'amountKopecks' | 'ready' | 'paid' | 'unpaid'>;
export type PublicCommonInvoice = Omit<Presentation<PublicCommonInvoiceResponseOutput,
  'token' | 'title' | 'accountName' | 'status' | 'amount' | 'paid' | 'remaining' | 'amountKopecks' | 'paidKopecks'
  | 'remainingKopecks' | 'payable' | 'clientReportable' | 'orders'>, 'orders'> & { orders: PublicCommonInvoiceOrder[] };

type FieldKind<T> = NonNullable<T> extends string ? 'string' | (null extends T ? 'nullable-string' : never) : NonNullable<T> extends number ? 'number' | 'integer'
  : NonNullable<T> extends boolean ? 'boolean' : NonNullable<T> extends readonly unknown[] ? 'array' : never;
type Fields<T> = { [Field in keyof T]?: FieldKind<T[Field]> };
function requireFields(value: unknown, fields: Readonly<Record<string, string>>, name: string): void {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ClientContractError(`Invalid ${name}`);
  const record = value as Record<string, unknown>;
  for (const [field, kind] of Object.entries(fields)) {
    const item = record[field];
    const valid = kind === 'array' ? Array.isArray(item) : kind === 'integer' ? Number.isSafeInteger(item)
      : kind === 'nullable-string' ? item === null || typeof item === 'string'
      : typeof item === kind && (kind !== 'number' || Number.isFinite(item));
    if (!valid) throw new ClientContractError(`Invalid ${name}.${field}`);
  }
}

export function decodePublicPaymentInit(value: PublicPaymentInitResponseOutput): PublicPaymentInitResponse {
  requireFields(value, { paymentUrl: 'nullable-string', paymentId: 'nullable-string', status: 'nullable-string' } satisfies Fields<PublicPaymentInitResponseOutput>, 'public payment initiation');
  return value as PublicPaymentInitResponse;
}
export function decodePublicSbpBanks(values: PublicSbpBankResponseOutput[]): PublicSbpBank[] {
  if (!Array.isArray(values)) throw new ClientContractError('Invalid public SBP bank list');
  return values.map(value => {
    requireFields(value, { bankId: 'string', name: 'string', featured: 'boolean' } satisfies Fields<PublicSbpBankResponseOutput>, 'public SBP bank');
    return value as PublicSbpBank;
  });
}
export function decodePublicCommonInvoice(value: PublicCommonInvoiceResponseOutput): PublicCommonInvoice {
  requireFields(value, {
    token: 'string', title: 'string', accountName: 'string', status: 'string', amount: 'number', paid: 'number', remaining: 'number',
    amountKopecks: 'integer', paidKopecks: 'integer', remainingKopecks: 'integer', payable: 'boolean', clientReportable: 'boolean', orders: 'array'
  } satisfies Fields<PublicCommonInvoiceResponseOutput>, 'public invoice');
  for (const order of value.orders!) {
    requireFields(order, { orderId: 'integer', companyId: 'integer', companyTitle: 'string', orderStatus: 'string',
      amount: 'number', amountKopecks: 'integer', ready: 'boolean', paid: 'boolean', unpaid: 'boolean'
    } satisfies Fields<CommonInvoiceOrderResponseOutput>, 'public invoice order');
  }
  // Unknown statuses/routes are retained for display while payment capabilities
  // are disabled by the existing shared policy; no status or amount is invented.
  return guardPublicCommonInvoice(value as PublicCommonInvoice);
}
