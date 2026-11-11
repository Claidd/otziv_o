import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import type { TbankPaymentStatus, ManagerPaymentLinkResponse, PaymentRouteChangeContext, PaymentRouteChangeRequest, PaymentRouteChangeResponse, PaymentRouteRecipientsResponse } from './api.service';
import { mobileEnvironment } from './mobile-environment';
@Injectable({ providedIn: 'root' })
export class OrderPaymentApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }
  getTbankStatus(): Observable<TbankPaymentStatus> {
    return this.http.get<TbankPaymentStatus>(this.apiUrl('/api/admin/payments/tbank-status'));
  }

  createManagerOrderPaymentLink(orderId: number): Observable<ManagerPaymentLinkResponse> {
    return this.http.post<ManagerPaymentLinkResponse>(
      this.apiUrl(`/api/manager/orders/${orderId}/payment-link`),
      {}
    );
  }

  getManagerOrderPaymentRouteChangeContext(orderId: number): Observable<PaymentRouteChangeContext> {
    return this.http.get<PaymentRouteChangeContext>(
      this.apiUrl(`/api/manager/orders/${orderId}/payment-route-change-context`)
    );
  }

  /** People a manager may name in the «Выдать номер карты» window of an order. */
  getManagerOrderPaymentRouteRecipients(orderId: number): Observable<PaymentRouteRecipientsResponse> {
    return this.http.get<PaymentRouteRecipientsResponse>(
      this.apiUrl(`/api/manager/orders/${orderId}/payment-route-recipients`)
    );
  }

  changeManagerOrderPaymentRoute(
    orderId: number,
    request: PaymentRouteChangeRequest
  ): Observable<PaymentRouteChangeResponse> {
    // A person named by the manager needs no «other than the current one» rule: the choice is explicit.
    const different = request.target === 'DIFFERENT_RECIPIENT' && request.recipientProfileId == null;
    return this.http.post<PaymentRouteChangeResponse>(
      this.apiUrl(`/api/manager/orders/${orderId}/payment-route-change${different ? '/recipient' : ''}`),
      request.target === 'DIFFERENT_RECIPIENT' ? { ...request, target: 'EMPLOYEE_REQUISITES' } : request
    );
  }

  markManagerOrderPaperInvoiceIssued(orderId: number): Observable<unknown> {
    return this.http.post(
      this.apiUrl(`/api/manager/orders/${orderId}/paper-invoice/issued`),
      {}
    );
  }
}
