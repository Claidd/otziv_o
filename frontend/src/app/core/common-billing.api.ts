import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type { OrderCardItem } from './manager.api';

export interface CommonBillingCompanyResponse {
  companyId: number;
  companyTitle: string;
  enabled: boolean;
}

export interface CommonInvoiceSummaryResponse {
  id: number;
  accountId: number;
  accountName: string;
  title: string;
  token: string;
  publicUrl: string;
  status: string;
  totalOrders: number;
  readyOrders: number;
  paidOrders: number;
  amount: number;
  paid: number;
  remaining: number;
  amountKopecks: number;
  paidKopecks: number;
  remainingKopecks: number;
  sentAt?: string | null;
  lastReminderAt?: string | null;
  nextReminderAt?: string | null;
  closedAt?: string | null;
  closedBy?: string | null;
  closeReason?: string | null;
  lastError?: string | null;
  paymentSuccessNotificationError?: string | null;
  tbankOrderId?: string | null;
  tbankPaymentId?: string | null;
  tbankPaymentAmountKopecks?: number | null;
  tbankTerminalLabel?: string | null;
  tbankTerminalKey?: string | null;
  paymentRouteType?: string | null;
  paymentRouteProfileName?: string | null;
  paymentRouteManualTaskId?: number | null;
  paymentRouteSelectedAt?: string | null;
}

export interface CommonBillingAccountResponse {
  id: number;
  name: string;
  enabled: boolean;
  autoRepeatOrders: boolean;
  managerId?: number | null;
  managerName?: string | null;
  invoiceCompanyId?: number | null;
  invoiceCompanyTitle?: string | null;
  companies: CommonBillingCompanyResponse[];
  currentInvoice?: CommonInvoiceSummaryResponse | null;
}

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
  paidAt?: string | null;
  paymentMethod?: 'TBANK' | 'MANUAL' | 'MIXED' | 'MANUAL_LEGACY' | null;
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

export interface CommonInvoicePaymentRefResponse {
  id: number;
  status: string;
  orderId?: string | null;
  paymentId?: string | null;
  amountKopecks?: number | null;
  reason?: string | null;
  terminalLabel?: string | null;
  terminalKey?: string | null;
}

export interface CommonInvoiceDetailsResponse {
  summary: CommonInvoiceSummaryResponse;
  orders: CommonInvoiceOrderResponse[];
  orderCards: OrderCardItem[];
  nextCycleOrders: CommonInvoiceNextCycleResponse[];
  paymentRefs?: CommonInvoicePaymentRefResponse[];
  paymentEvidenceToken?: string | null;
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
    return this.http.get<CommonBillingAccountResponse[]>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/accounts`
    );
  }

  accountsForCompany(companyId: number): Observable<CommonBillingAccountResponse[]> {
    return this.http.get<CommonBillingAccountResponse[]>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/accounts/by-company/${companyId}`
    );
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
      {}
    );
  }

  remind(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/remind`,
      {}
    );
  }

  markPaid(invoiceId: number, request: ManualPaymentConfirmationRequest): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/paid`,
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

  deleteInvoiceWithOrders(invoiceId: number): Observable<void> {
    return this.http.delete<void>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}`
    );
  }
}
