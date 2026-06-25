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
  lastError?: string | null;
  paymentSuccessNotificationError?: string | null;
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
}

export interface CommonInvoiceDetailsResponse {
  summary: CommonInvoiceSummaryResponse;
  orders: CommonInvoiceOrderResponse[];
  orderCards: OrderCardItem[];
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

  markPaid(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/paid`,
      {}
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

  confirmPaymentInitCheck(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/attention/confirm-payment-init-check`,
      {}
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

  markOrderPaid(invoiceId: number, orderId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/orders/${orderId}/paid`,
      {}
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
}
