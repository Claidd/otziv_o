import { decodeCommonBillingAccounts } from '@otziv/client-common/billing-payments';
import type { CommonBillingCompanyResponse, CommonInvoiceSummaryResponse, CommonBillingAccountResponse, InvoicePaymentMode } from '@otziv/client-common/billing-payments';
export type { CommonBillingCompanyResponse, CommonInvoiceSummaryResponse, CommonBillingAccountResponse, InvoicePaymentMode } from '@otziv/client-common/billing-payments';
import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type { OrderCardItem } from './manager.api';





export interface CommonBillingAccountRequest {
  name: string;
  enabled?: boolean;
  autoRepeatOrders?: boolean;
  managerId?: number | null;
  invoiceCompanyId?: number | null;
  companyIds?: number[];
}

export interface CommonInvoiceOrderResponse {
  orderId: number;
  companyId: number;
  companyTitle: string;
  filialTitle?: string | null;
  orderStatus: string;
  originalOrderStatus?: string | null;
  amount: number;
  amountKopecks: number;
  ready: boolean;
  paid: boolean;
  unpaid: boolean;
  detachable: boolean;
  ordinaryAfterPaperCancel?: boolean;
  detachBlockedReason?: string | null;
  paidAt?: string | null;
  paymentMethod?: 'TBANK' | 'TOCHKA' | 'BANK' | 'MANUAL' | 'MIXED' | 'MANUAL_LEGACY' | 'OWNER_PAPER_INVOICE' | null;
  paidBy?: string | null;
  paymentComment?: string | null;
  paymentReceiptUrl?: string | null;
}

export interface CommonInvoiceNextCycleResponse {
  sourceOrderId: number;
  orderId: number;
  invoiceId?: number | null;
  invoiceStatus?: string | null;
  companyTitle: string;
  filialTitle?: string | null;
  orderStatus: string;
}

export interface ManualPaymentConfirmationRequest {
  comment: string;
  receiptUrl: string;
}

export interface ContractorCommonSourceConfirmationRequest {
  recipientStatementChecked: true;
  paymentReceived: true;
  confirmedTotalKopecks: number;
  effectiveAt?: string | null;
  reason: string;
}

export interface CommonInvoicePaymentRefResponse {
  id: number;
  provider?: string | null;
  status: string;
  orderId?: string | null;
  paymentId?: string | null;
  amountKopecks?: number | null;
  reason?: string | null;
  terminalLabel?: string | null;
  terminalKey?: string | null;
}

export interface CommonInvoiceDetailsResponse {
  delivery?: import('@otziv/client-common/billing-payments').DeliveryOperation | null;
  summary: CommonInvoiceSummaryResponse;
  orders: CommonInvoiceOrderResponse[];
  orderCards: OrderCardItem[];
  nextCycleOrders: CommonInvoiceNextCycleResponse[];
  paymentRefs?: CommonInvoicePaymentRefResponse[];
  paymentEvidenceToken?: string | null;
}

export interface CommonInvoiceRouteRecipient {
  profileId: number;
  userId?: number | null;
  role: string;
  name: string;
  orderSpecialist: boolean;
  manager: boolean;
  teamMember: boolean;
  workedWithClient: boolean;
  availableKopecks: number;
  overrunKopecks: number;
  current: boolean;
  blockReason?: string | null;
}

/** The owner's card in «Выдать номер карты»; absent where the owner takes money through the bank only. */
export interface CommonInvoiceRouteOwnerCard {
  name: string;
  remainingKopecks: number;
  current: boolean;
  blockReason?: string | null;
}

export interface CommonInvoiceRouteRecipientsResponse {
  invoiceId: number;
  amountKopecks: number;
  recipients: CommonInvoiceRouteRecipient[];
  ownerCard?: CommonInvoiceRouteOwnerCard | null;
}

/** A row of the «Вернуть заказ в счёт» window: an order of the invoice's own link that goes separately now. */
export interface CommonInvoiceReturnableOrder {
  orderId: number;
  companyId: number;
  companyTitle: string;
  orderTitle: string;
  status: string;
  /** What the order would add to the invoice; 0 when the amount cannot be calculated. */
  amountKopecks: number;
}

export interface CommonInvoiceReturnableOrdersResponse {
  invoiceId: number;
  canReturn: boolean;
  /** Why the composition is fixed and what to do instead; empty while orders can be returned. */
  blockReason?: string | null;
  orders: CommonInvoiceReturnableOrder[];
}

export type CommonInvoicePaymentRouteChangeTarget =
  | 'EMPLOYEE_REQUISITES'
  | 'DIFFERENT_RECIPIENT'
  | 'OWNER_TBANK'
  | 'OWNER_BANK_REISSUE';

export interface CommonInvoicePaymentRouteChangeContextResponse {
  currentRoute: string;
  currentTarget: CommonInvoicePaymentRouteChangeTarget;
  currentRecipient: string;
  status: string;
  canChange: boolean;
  bankRecipientChangeAvailable?: boolean;
  blockReason: string;
  paymentEvidenceToken: string;
  currentPaymentProfileId?: number | null;
  ownerBankTargetPaymentProfileId?: number | null;
  ownerBankTargetPaymentProfileName?: string | null;
  ownerBankTargetProvider?: string | null;
  canReissueOwnerBank: boolean;
  ownerBankReissueBlockReason: string;
  ownerManualRoute?: boolean;
}

export interface CommonInvoiceArchiveOrderPreview {
  orderId: number;
  companyTitle: string;
  status: string;
  allowed: boolean;
  blockers: string[];
}

export interface CommonInvoiceArchivePreviewResponse {
  invoiceId: number;
  allowed: boolean;
  totalOrders: number;
  orders: CommonInvoiceArchiveOrderPreview[];
  blockers: string[];
}

export interface CommonInvoiceArchiveListItem {
  id: number;
  accountName: string;
  title: string;
  status: 'ARCHIVED' | 'BAN' | 'PAID' | string;
  amountKopecks: number;
  paidKopecks: number;
  orderCount: number;
  closedAt?: string | null;
  closedBy?: string | null;
  closeReason?: string | null;
  archivedAt?: string | null;
  source: 'live' | 'archive';
  restorable: boolean;
}

export interface CommonInvoiceArchiveOrderItem {
  orderId: number;
  companyTitle: string;
  filialTitle: string;
  status: string;
  archiveSourceStatus: string;
  amountKopecks: number;
  paid: boolean;
}

export interface CommonInvoiceArchiveDetailsResponse {
  invoice: CommonInvoiceArchiveListItem;
  orders: CommonInvoiceArchiveOrderItem[];
}

export interface CommonInvoiceArchiveRestoreResult {
  invoiceId: number;
  status: string;
  source: 'live' | 'archive';
  restoredAt: string;
  restoredBy: string;
  orderIds: number[];
  message: string;
}

export interface CommonInvoiceArchivePage {
  content: CommonInvoiceArchiveListItem[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

@Injectable({ providedIn: 'root' })
export class CommonBillingApi {
  constructor(private readonly http: HttpClient) {}

  accounts(): Observable<CommonBillingAccountResponse[]> {
    return this.http.get<unknown>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/accounts`
    ).pipe(map(decodeCommonBillingAccounts));
  }

  accountsForCompany(companyId: number): Observable<CommonBillingAccountResponse[]> {
    return this.http.get<unknown>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/accounts/by-company/${companyId}`
    ).pipe(map(decodeCommonBillingAccounts));
  }

  createAccount(request: CommonBillingAccountRequest): Observable<CommonBillingAccountResponse> {
    return this.http.post<CommonBillingAccountResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/accounts`,
      request
    );
  }

  updateAccount(accountId: number, request: CommonBillingAccountRequest): Observable<CommonBillingAccountResponse> {
    return this.http.put<CommonBillingAccountResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/accounts/${accountId}`,
      request
    );
  }

  addCompany(accountId: number, companyId: number): Observable<CommonBillingAccountResponse> {
    return this.http.post<CommonBillingAccountResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/accounts/${accountId}/companies/${companyId}`,
      {}
    );
  }

  removeCompany(accountId: number, companyId: number, detachCurrent = false): Observable<CommonBillingAccountResponse> {
    return this.http.delete<CommonBillingAccountResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/accounts/${accountId}/companies/${companyId}`,
      { params: { detachCurrent } }
    );
  }

  invoice(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.get<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}`
    );
  }

  sendInvoice(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/send`,
      {},
      { headers: { "X-Otziv-Delivery-Protocol": "queued-v1" } }
    );
  }

  changeInvoicePaymentMode(
    invoiceId: number,
    mode: InvoicePaymentMode, paperInvoiceWithdrawn = false
  ): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/payment-mode`,
      { mode, confirmedUnpaid: true, ...(paperInvoiceWithdrawn ? { paperInvoiceWithdrawn: true } : {}) }
    );
  }

  /** People a manager may name in the «Выдать номер карты» window, with their reserve for this invoice. */
  commonInvoiceRouteRecipients(invoiceId: number): Observable<CommonInvoiceRouteRecipientsResponse> {
    return this.http.get<CommonInvoiceRouteRecipientsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/payment-route-recipients`
    );
  }

  commonInvoicePaymentRouteChangeContext(
    invoiceId: number
  ): Observable<CommonInvoicePaymentRouteChangeContextResponse> {
    return this.http.get<CommonInvoicePaymentRouteChangeContextResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/payment-route-change-context`
    );
  }

  changeCommonInvoicePaymentRoute(
    invoiceId: number,
    target: CommonInvoicePaymentRouteChangeTarget,
    expectedPaymentEvidenceToken: string,
    expectedTargetPaymentProfileId?: number | null,
    recipientProfileId?: number | null
  ): Observable<CommonInvoiceDetailsResponse> {
    // A person named by the manager needs no «other than the current one» rule: the choice is explicit.
    const different = target === 'DIFFERENT_RECIPIENT' && recipientProfileId == null;
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/payment-route-change${different ? '/recipient' : ''}`,
      {
        target: target === 'DIFFERENT_RECIPIENT' ? 'EMPLOYEE_REQUISITES' : target,
        confirmedUnpaid: true,
        expectedPaymentEvidenceToken,
        expectedTargetPaymentProfileId: expectedTargetPaymentProfileId ?? null,
        ...(recipientProfileId != null ? { recipientProfileId } : {})
      }
    );
  }

  markPaperInvoiceIssued(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/paper-invoice/issued`,
      {}
    );
  }

  reconcilePaperInvoice(invoiceId: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/paper-invoices/COMMON_INVOICE/${invoiceId}/reconcile`, {});
  }

  markPaperInvoicePaid(
    invoiceId: number,
    request: ManualPaymentConfirmationRequest
  ): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/paper-invoice/paid`,
      request
    );
  }

  remind(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/remind`,
      {},
      { headers: { "X-Otziv-Delivery-Protocol": "queued-v1" } }
    );
  }

  markPaid(invoiceId: number, request: ManualPaymentConfirmationRequest): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/paid`,
      request
    );
  }

  confirmContractorSource(
    invoiceId: number,
    request: ContractorCommonSourceConfirmationRequest
  ): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/contractor-confirmation`,
      request
    );
  }

  retryAttention(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/attention/retry`,
      {}
    );
  }

  resolveAttention(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/attention/resolve`,
      {}
    );
  }

  repairPaymentRoute(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/attention/repair-payment-route`,
      {}
    );
  }

  reportManualCardPayment(invoiceId: number, reason: string): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/attention/manual-card-paid`,
      { reason }
    );
  }

  resolveTechnicalTail(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/technical-tail/resolve`,
      {}
    );
  }

  resolvePaymentSuccessNotification(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/payment-notification/resolve`,
      {}
    );
  }

  applyLatePayment(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/attention/apply-late-payment`,
      {}
    );
  }

  confirmFinalPaymentCancelCheck(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/attention/confirm-final-cancel-check`,
      {}
    );
  }

  confirmPaymentInitCheck(
    invoiceId: number,
    evidenceToken?: string | null
  ): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/attention/confirm-payment-init-check`,
      { evidenceToken: evidenceToken ?? null }
    );
  }

  markUnpaid(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/unpaid`,
      {}
    );
  }

  markBan(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/ban`,
      {}
    );
  }

  archivePreview(invoiceId: number): Observable<CommonInvoiceArchivePreviewResponse> {
    return this.http.get<CommonInvoiceArchivePreviewResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/archive-preview`
    );
  }

  archiveInvoice(invoiceId: number, comment = ''): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/archive`,
      { confirm: true, comment }
    );
  }

  archiveInvoices(query: {
    keyword?: string;
    pageNumber?: number;
    pageSize?: number;
    sortDirection?: 'asc' | 'desc';
  } = {}): Observable<CommonInvoiceArchivePage> {
    return this.http.get<CommonInvoiceArchivePage>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/archive/invoices`,
      {
        params: {
          keyword: query.keyword ?? '',
          pageNumber: query.pageNumber ?? 0,
          pageSize: query.pageSize ?? 10,
          sortDirection: query.sortDirection ?? 'desc'
        }
      }
    );
  }

  archiveInvoiceDetails(invoiceId: number): Observable<CommonInvoiceArchiveDetailsResponse> {
    return this.http.get<CommonInvoiceArchiveDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/archive/invoices/${invoiceId}`
    );
  }

  restoreArchiveInvoice(invoiceId: number): Observable<CommonInvoiceArchiveRestoreResult> {
    return this.http.post<CommonInvoiceArchiveRestoreResult>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/archive/invoices/${invoiceId}/restore`,
      {},
      { params: { confirm: true } }
    );
  }

  markOrderPaid(
    invoiceId: number,
    orderId: number,
    request: ManualPaymentConfirmationRequest
  ): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/orders/${orderId}/paid`,
      request
    );
  }

  approveReviewOrders(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/orders/approve-review`,
      {}
    );
  }

  detachOrder(invoiceId: number, orderId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.delete<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/orders/${orderId}`
    );
  }

  archiveDisputedOrder(invoiceId: number, orderId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/orders/${orderId}/archive-disputed`,
      {}
    );
  }

  /** Orders of the invoice's own link that can still be put back into it. */
  returnableOrders(invoiceId: number): Observable<CommonInvoiceReturnableOrdersResponse> {
    return this.http.get<CommonInvoiceReturnableOrdersResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/returnable-orders`
    );
  }

  /** The reverse of detachOrder: only into this collecting invoice, only an order of its link. */
  returnOrder(invoiceId: number, orderId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/orders/${orderId}/attach`,
      {}
    );
  }

  deleteInvoiceWithOrders(invoiceId: number): Observable<void> {
    return this.http.delete<void>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}`
    );
  }
}
