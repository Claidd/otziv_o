import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import type { ManualPaymentReviewView } from '@otziv/client-common/billing-payments';
import { appEnvironment } from './app-environment';

@Injectable({ providedIn: 'root' })
export class ManualPaymentReviewApi {
  private readonly http = inject(HttpClient);
  get(orderId: number): Observable<ManualPaymentReviewView | null> {
    return this.http.get<ManualPaymentReviewView | null>(
      `${appEnvironment.apiBaseUrl}/api/orders/${orderId}/manual-payment-review`
    );
  }
  dispute(orderId: number, request: { recipientReportedMissing: true; note: string; expectedReviewId: number }): Observable<ManualPaymentReviewView> {
    return this.http.post<ManualPaymentReviewView>(
      `${appEnvironment.apiBaseUrl}/api/orders/${orderId}/manual-payment-review/dispute`, request
    );
  }
}
