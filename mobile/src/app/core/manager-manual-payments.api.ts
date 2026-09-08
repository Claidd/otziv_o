import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { ManagerManualCardPaymentResult, ManualCardPaymentConfirmationRequest, ManualCardPaymentContext } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class ManagerManualPaymentsApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getManagerManualCardPaymentContext(orderId: number): Observable<ManualCardPaymentContext> {
    return this.http.get<ManualCardPaymentContext>(
      this.apiUrl(`/api/manager/orders/${orderId}/manual-card-payment-context`)
    );
  }

  confirmManagerManualCardPayment(
    orderId: number,
    request: ManualCardPaymentConfirmationRequest
  ): Observable<ManagerManualCardPaymentResult> {
    return this.http.post<ManagerManualCardPaymentResult>(
      this.apiUrl(`/api/manager/orders/${orderId}/confirm-manual-card-payment`),
      request
    );
  }
}
