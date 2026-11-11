import { decodeCommonBillingAccounts } from '@otziv/client-common/billing-payments';
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { CommonBillingAccountResponse, CommonInvoiceReturnableOrdersResponse, CommonInvoiceArchivePreviewResponse, CommonInvoiceDetailsResponse, CommonInvoicePaymentRouteChangeContextResponse, CommonInvoicePaymentRouteChangeTarget, CommonInvoiceRouteRecipientsResponse, CommonManualPaymentAttributionModeResponse, CommonManualPaymentAttributionRequest, CommonManualPaymentMode, CommonManualPaymentOptions, ContractorCommonSourceConfirmationRequest, InvoicePaymentMode, ManualPaymentConfirmationRequest } from './api.service';
import { mobileEnvironment } from './mobile-environment';

@Injectable({ providedIn: 'root' })
export class CommonBillingApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }
  getCommonBillingAccounts(): Observable<CommonBillingAccountResponse[]> {
    return this.http.get<unknown>(this.apiUrl('/api/common-billing/accounts')).pipe(map(decodeCommonBillingAccounts));
  }

  addCommonBillingCompany(accountId: number, companyId: number): Observable<CommonBillingAccountResponse> {
    return this.http.post<CommonBillingAccountResponse>(
      this.apiUrl(`/api/common-billing/accounts/${accountId}/companies/${companyId}`),
      {}
    );
  }

  getCommonInvoice(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.get<CommonInvoiceDetailsResponse>(this.apiUrl(`/api/common-billing/invoices/${invoiceId}`));
  }

  sendCommonInvoice(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/send`),
      {},
      { headers: { "X-Otziv-Delivery-Protocol": "queued-v1" } }
    );
  }

  changeCommonInvoicePaymentMode(
    invoiceId: number,
    mode: InvoicePaymentMode, paperInvoiceWithdrawn = false
  ): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/payment-mode`),
      { mode, confirmedUnpaid: true, ...(paperInvoiceWithdrawn ? { paperInvoiceWithdrawn: true } : {}) }
    );
  }

  getCommonInvoicePaymentRouteChangeContext(
    invoiceId: number
  ): Observable<CommonInvoicePaymentRouteChangeContextResponse> {
    return this.http.get<CommonInvoicePaymentRouteChangeContextResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/payment-route-change-context`)
    );
  }

  /** People a manager may name in the «Выдать номер карты» window, with their reserve for this invoice. */
  getCommonInvoicePaymentRouteRecipients(invoiceId: number): Observable<CommonInvoiceRouteRecipientsResponse> {
    return this.http.get<CommonInvoiceRouteRecipientsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/payment-route-recipients`)
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
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/payment-route-change${different ? '/recipient' : ''}`),
      {
        target: target === 'DIFFERENT_RECIPIENT' ? 'EMPLOYEE_REQUISITES' : target,
        confirmedUnpaid: true, expectedPaymentEvidenceToken, expectedTargetPaymentProfileId,
        ...(recipientProfileId != null ? { recipientProfileId } : {})
      }
    );
  }

  markCommonInvoicePaperInvoiceIssued(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/paper-invoice/issued`),
      {}
    );
  }

  markCommonInvoicePaperInvoicePaid(
    invoiceId: number,
    request: ManualPaymentConfirmationRequest
  ): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/paper-invoice/paid`),
      request
    );
  }

  remindCommonInvoice(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/remind`),
      {},
      { headers: { "X-Otziv-Delivery-Protocol": "queued-v1" } }
    );
  }

  markCommonInvoicePaid(
    invoiceId: number,
    request: ManualPaymentConfirmationRequest

  ): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/paid`),
      request
    );
  }

  getCommonManualPaymentMode(invoiceId: number): Observable<CommonManualPaymentAttributionModeResponse> {
    return this.http.get<CommonManualPaymentAttributionModeResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/manual-payment-mode`)
    );
  }

  getCommonManualPaymentOptions(invoiceId: number): Observable<CommonManualPaymentOptions> {
    return this.http.get<CommonManualPaymentOptions>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/manual-payment-options`)
    );
  }

  rejectOwnerReport(invoiceId: number, reportId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/common-billing/invoices/${invoiceId}/owner-reports/${reportId}/reject`), {});
  }

  confirmCommonManualPayment(
    invoiceId: number,
    mode: CommonManualPaymentMode,
    request: CommonManualPaymentAttributionRequest
  ): Observable<CommonInvoiceDetailsResponse> {
    const action = mode === 'TBANK_FALLBACK'
      ? 'attention/manual-card-paid-with-attributions'
      : 'paid-with-attributions';
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/${action}`),
      request
    );
  }

  reportCommonInvoiceManualCardPayment(invoiceId: number, reason: string): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/attention/manual-card-paid`),
      { reason }
    );
  }

  confirmCommonInvoiceContractorSource(
    invoiceId: number,
    request: ContractorCommonSourceConfirmationRequest
  ): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/contractor-confirmation`),
      request
    );
  }

  repairCommonInvoicePaymentRoute(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/attention/repair-payment-route`),
      {}
    );
  }

  resolveCommonInvoiceTechnicalTail(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/technical-tail/resolve`),
      {}
    );
  }

  resolveCommonInvoicePaymentNotification(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/payment-notification/resolve`),
      {}
    );
  }

  markCommonInvoiceUnpaid(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/unpaid`),
      {}
    );
  }

  markCommonInvoiceBan(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/ban`),
      {}
    );
  }

  getCommonInvoiceArchivePreview(invoiceId: number): Observable<CommonInvoiceArchivePreviewResponse> {
    return this.http.get<CommonInvoiceArchivePreviewResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/archive-preview`)
    );
  }

  archiveCommonInvoice(invoiceId: number, comment = ''): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/archive`),
      { confirm: true, comment }
    );
  }

  retryCommonInvoiceAttention(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/attention/retry`),
      {}
    );
  }

  resolveCommonInvoiceAttention(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/attention/resolve`),
      {}
    );
  }

  applyCommonInvoiceLatePayment(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/attention/apply-late-payment`),
      {}
    );
  }

  confirmCommonInvoiceFinalPaymentCancelCheck(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/attention/confirm-final-cancel-check`),
      {}
    );
  }

  confirmCommonInvoicePaymentInitCheck(
    invoiceId: number,
    evidenceToken?: string | null
  ): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/attention/confirm-payment-init-check`),
      { evidenceToken: evidenceToken ?? null }
    );
  }

  markCommonInvoiceOrderPaid(
    invoiceId: number,
    orderId: number,
    request: ManualPaymentConfirmationRequest
  ): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/orders/${orderId}/paid`),
      request
    );
  }

  approveCommonInvoiceReviewOrders(invoiceId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/orders/approve-review`),
      {}
    );
  }

  /** Orders of this invoice's link that go apart from it and may be returned while it is being collected. */
  getCommonInvoiceReturnableOrders(invoiceId: number): Observable<CommonInvoiceReturnableOrdersResponse> {
    return this.http.get<CommonInvoiceReturnableOrdersResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/returnable-orders`)
    );
  }

  /** Puts an excluded order back into this collecting invoice. */
  returnCommonInvoiceOrder(invoiceId: number, orderId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.post<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/orders/${orderId}/attach`),
      {}
    );
  }

  detachCommonInvoiceOrder(invoiceId: number, orderId: number): Observable<CommonInvoiceDetailsResponse> {
    return this.http.delete<CommonInvoiceDetailsResponse>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}/orders/${orderId}`)
    );
  }

  deleteCommonInvoiceWithOrders(invoiceId: number): Observable<void> {
    return this.http.delete<void>(
      this.apiUrl(`/api/common-billing/invoices/${invoiceId}`)
    );
  }

}
