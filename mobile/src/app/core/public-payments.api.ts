import { decodePublicCommonInvoice, decodePublicPaymentInit, decodePublicSbpBanks } from '@otziv/client-common/public-payments';
import type { PublicCommonInvoiceResponseOutput, PublicPaymentInitRequestInput, PublicPaymentInitResponseOutput, PublicSbpBankResponseOutput } from '@otziv/client-common/client-api';
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { PublicCommonInvoice, PublicPaymentInitResponse, PublicSbpBank } from '@otziv/client-common/public-payments';
import type { PublicPaymentLink } from '@otziv/client-common/billing-payments';
import { mobileEnvironment } from './mobile-environment';
import { decodePublicPaymentLink } from '@otziv/client-common/billing-payments';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class PublicPaymentsApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getPublicPaymentLink(token: string): Observable<PublicPaymentLink> {
    return this.http.get<unknown>(this.apiUrl(`/api/payments/public/${encodeURIComponent(token)}`)).pipe(map(decodePublicPaymentLink));
  }

  getPublicCommonInvoice(token: string): Observable<PublicCommonInvoice> {
    return this.http.get<PublicCommonInvoiceResponseOutput>(this.apiUrl(`/api/payments/public/group/${encodeURIComponent(token)}`)).pipe(map(decodePublicCommonInvoice));
  }

  initPublicPayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean
  ): Observable<PublicPaymentInitResponse> {
    return this.http.post<PublicPaymentInitResponseOutput>(
      this.apiUrl(`/api/payments/public/${encodeURIComponent(token)}/init`),
      { email, offerConsent, privacyConsent, receiptConsent } satisfies PublicPaymentInitRequestInput
    ).pipe(map(decodePublicPaymentInit));
  }

  initPublicCommonInvoicePayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean
  ): Observable<PublicPaymentInitResponse> {
    return this.http.post<PublicPaymentInitResponseOutput>(
      this.apiUrl(`/api/payments/public/group/${encodeURIComponent(token)}/init`),
      { email, offerConsent, privacyConsent, receiptConsent } satisfies PublicPaymentInitRequestInput
    ).pipe(map(decodePublicPaymentInit));
  }

  reportPublicCommonInvoicePaid(token: string): Observable<PublicCommonInvoice> {
    return this.http.post<PublicCommonInvoiceResponseOutput>(
      this.apiUrl(`/api/payments/public/group/${encodeURIComponent(token)}/reported-paid`),
      {}
    ).pipe(map(decodePublicCommonInvoice));
  }

  initPublicSbpPayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean,
    sbpBankId?: string | null
  ): Observable<PublicPaymentInitResponse> {
    return this.http.post<PublicPaymentInitResponseOutput>(
      this.apiUrl(`/api/payments/public/${encodeURIComponent(token)}/sbp`),
      { email, offerConsent, privacyConsent, receiptConsent, sbpBankId } satisfies PublicPaymentInitRequestInput
    ).pipe(map(decodePublicPaymentInit));
  }

  getPublicSbpBanks(token: string): Observable<PublicSbpBank[]> {
    return this.http.get<PublicSbpBankResponseOutput[]>(
      this.apiUrl(`/api/payments/public/${encodeURIComponent(token)}/sbp/banks`)
    ).pipe(map(decodePublicSbpBanks));
  }

  reportPublicManualPayment(token: string): Observable<PublicPaymentLink> {
    return this.http.post<unknown>(
      this.apiUrl(`/api/payments/public/${encodeURIComponent(token)}/manual-paid`),
      {}
    ).pipe(map(decodePublicPaymentLink));
  }
}
