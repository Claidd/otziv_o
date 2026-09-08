import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { PublicCommonInvoice, PublicPaymentInitResponse, PublicPaymentLink, PublicSbpBank } from './api.service';
import { mobileEnvironment } from './mobile-environment';
import { decodePublicPaymentLink, guardPublicCommonInvoice } from '@otziv/client-common/billing-payments';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class PublicPaymentsApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getPublicPaymentLink(token: string): Observable<PublicPaymentLink> {
    return this.http.get<unknown>(this.apiUrl(`/api/payments/public/${encodeURIComponent(token)}`)).pipe(map(decodePublicPaymentLink));
  }

  getPublicCommonInvoice(token: string): Observable<PublicCommonInvoice> {
    return this.http.get<PublicCommonInvoice>(this.apiUrl(`/api/payments/public/group/${encodeURIComponent(token)}`)).pipe(map(guardPublicCommonInvoice));
  }

  initPublicPayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean
  ): Observable<PublicPaymentInitResponse> {
    return this.http.post<PublicPaymentInitResponse>(
      this.apiUrl(`/api/payments/public/${encodeURIComponent(token)}/init`),
      { email, offerConsent, privacyConsent, receiptConsent }
    );
  }

  initPublicCommonInvoicePayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean
  ): Observable<PublicPaymentInitResponse> {
    return this.http.post<PublicPaymentInitResponse>(
      this.apiUrl(`/api/payments/public/group/${encodeURIComponent(token)}/init`),
      { email, offerConsent, privacyConsent, receiptConsent }
    );
  }

  reportPublicCommonInvoicePaid(token: string): Observable<PublicCommonInvoice> {
    return this.http.post<PublicCommonInvoice>(
      this.apiUrl(`/api/payments/public/group/${encodeURIComponent(token)}/reported-paid`),
      {}
    ).pipe(map(guardPublicCommonInvoice));
  }

  initPublicSbpPayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean,
    sbpBankId?: string | null
  ): Observable<PublicPaymentInitResponse> {
    return this.http.post<PublicPaymentInitResponse>(
      this.apiUrl(`/api/payments/public/${encodeURIComponent(token)}/sbp`),
      { email, offerConsent, privacyConsent, receiptConsent, sbpBankId }
    );
  }

  getPublicSbpBanks(token: string): Observable<PublicSbpBank[]> {
    return this.http.get<PublicSbpBank[]>(
      this.apiUrl(`/api/payments/public/${encodeURIComponent(token)}/sbp/banks`)
    );
  }

  reportPublicManualPayment(token: string): Observable<PublicPaymentLink> {
    return this.http.post<PublicPaymentLink>(
      this.apiUrl(`/api/payments/public/${encodeURIComponent(token)}/manual-paid`),
      {}
    );
  }
}
