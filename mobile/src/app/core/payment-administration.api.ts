import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { AdminPaymentLinkResponse, AdminPaymentLinksPageResponse, PaymentLinkArchiveRunResponse, PaymentLinkListSource } from './api.service';
import { mobileEnvironment } from './mobile-environment';

export interface ContractorPaymentSourceConfirmationRequest {
  recipientStatementChecked: true;
  paymentReceived: true;
  confirmedTotalKopecks: number;
  reason: string;
}

export interface CloseManualPaymentUnpaidRequest {
  recipientStatementChecked: true;
  paymentAbsent: true;
  note: string;
}

export type SpecialistPaymentReviewStatus =
  | 'PENDING_DELIVERY' | 'WAITING' | 'SETTLING' | 'ADMIN_SETTLING'
  | 'DISPUTED' | 'CONFIRMED' | 'NEEDS_ATTENTION' | 'SUPERSEDED';

export interface SpecialistPaymentReview {
  id: number;
  orderId: number;
  paymentLinkId: number;
  amountKopecks: number;
  recipientUserId: number;
  status: SpecialistPaymentReviewStatus;
  requestedAt: string;
  notifiedAt: string | null;
  deadlineAt: string;
  lastError: string | null;
  retryNotificationAllowed: boolean;
}

export interface SpecialistPaymentAbsentRequest {
  recipientStatementChecked: true;
  paymentAbsent: true;
  note: string;
}

export interface SpecialistPaymentVerifyRequest {
  recipientStatementChecked: true;
  paymentReceived: true;
  receivedAmountKopecks: number;
  note: string;
}

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class PaymentAdministrationApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getSpecialistPaymentReviews(): Observable<SpecialistPaymentReview[]> {
    return this.http.get<SpecialistPaymentReview[]>(this.apiUrl('/api/admin/payments/specialist-reviews'));
  }

  retrySpecialistPaymentNotification(id: number): Observable<SpecialistPaymentReview> {
    return this.http.post<SpecialistPaymentReview>(
      this.apiUrl(`/api/admin/payments/specialist-reviews/${id}/retry-notification`), {}
    );
  }

  reconcileSpecialistPaymentReview(id: number): Observable<SpecialistPaymentReview> {
    return this.http.post<SpecialistPaymentReview>(
      this.apiUrl(`/api/admin/payments/specialist-reviews/${id}/reconcile`), {}
    );
  }

  markSpecialistPaymentAbsent(id: number, request: SpecialistPaymentAbsentRequest): Observable<SpecialistPaymentReview> {
    return this.http.post<SpecialistPaymentReview>(
      this.apiUrl(`/api/admin/payments/specialist-reviews/${id}/mark-absent`), request
    );
  }

  verifySpecialistPaymentReceived(id: number, request: SpecialistPaymentVerifyRequest): Observable<SpecialistPaymentReview> {
    return this.http.post<SpecialistPaymentReview>(
      this.apiUrl(`/api/admin/payments/specialist-reviews/${id}/verify-received`), request
    );
  }

  getAdminTbankPaymentLinks(params?: {
    page?: number;
    size?: number;
    status?: string;
    search?: string;
    source?: PaymentLinkListSource;
    sortDirection?: 'asc' | 'desc';
    from?: string;
    to?: string;
  }): Observable<AdminPaymentLinksPageResponse> {
    let httpParams = new HttpParams();
    if (params) {
      Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null && String(value).trim() !== '') {
          httpParams = httpParams.set(key, String(value));
        }
      });
    }
    return this.http.get<AdminPaymentLinksPageResponse>(
      this.apiUrl('/api/admin/payments/tbank-links'),
      { params: httpParams }
    );
  }

  runAdminPaymentLinkArchive(dryRun: boolean, batchSize?: number): Observable<PaymentLinkArchiveRunResponse> {
    let params = new HttpParams().set('dryRun', String(dryRun));
    if (batchSize != null && Number.isFinite(batchSize) && batchSize > 0) {
      params = params.set('batchSize', String(batchSize));
    }
    return this.http.post<PaymentLinkArchiveRunResponse>(
      this.apiUrl('/api/admin/payments/tbank-links/archive/run'),
      {},
      { params }
    );
  }

  cancelAdminTbankPaymentLink(linkId: number): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      this.apiUrl(`/api/admin/payments/tbank-links/${linkId}/cancel`),
      {}
    );
  }

  confirmAdminManualPaymentLink(linkId: number): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      this.apiUrl(`/api/admin/payments/manual-links/${linkId}/confirm`),
      {}
    );
  }

  markAdminManualPaymentReceipt(linkId: number): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      this.apiUrl(`/api/admin/payments/manual-links/${linkId}/receipt`),
      {}
    );
  }

  confirmAdminContractorPaymentSource(
    linkId: number,
    request: ContractorPaymentSourceConfirmationRequest
  ): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      this.apiUrl(`/api/admin/payments/manual-links/${linkId}/contractor-confirmation`),
      request
    );
  }

  closeAdminManualPaymentLinkAsUnpaid(
    linkId: number,
    request: CloseManualPaymentUnpaidRequest
  ): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      this.apiUrl(`/api/admin/payments/manual-links/${linkId}/close-unpaid`),
      request
    );
  }

  markAdminManualPaymentReceiptLegacyNotRequired(linkId: number): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      this.apiUrl(`/api/admin/payments/manual-links/${linkId}/receipt/legacy-not-required`),
      {}
    );
  }
}
