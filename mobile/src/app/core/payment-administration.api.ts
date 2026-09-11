import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { AdminPaymentLinkResponse, AdminPaymentLinksPageResponse, PaymentLinkArchiveRunResponse, PaymentLinkListSource } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class PaymentAdministrationApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

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
}
