import { decodeCommonBillingAccounts } from '@otziv/client-common/billing-payments';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map, Observable } from 'rxjs';
import type { CommonBillingAccountResponse, CommonBillingAccountRequest } from './api.service';
import { mobileEnvironment } from './mobile-environment';

@Injectable({ providedIn: 'root' })
export class ManagerCompanyBillingApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getCommonBillingAccountsForCompany(companyId: number): Observable<CommonBillingAccountResponse[]> {
    return this.http.get<unknown>(
      this.apiUrl(`/api/common-billing/accounts/by-company/${companyId}`)
    ).pipe(map(decodeCommonBillingAccounts));
  }

  createCommonBillingAccount(request: CommonBillingAccountRequest): Observable<CommonBillingAccountResponse> {
    return this.http.post<CommonBillingAccountResponse>(this.apiUrl('/api/common-billing/accounts'), request);
  }

  updateCommonBillingAccount(
    accountId: number,
    request: CommonBillingAccountRequest
  ): Observable<CommonBillingAccountResponse> {
    return this.http.put<CommonBillingAccountResponse>(
      this.apiUrl(`/api/common-billing/accounts/${accountId}`),
      request
    );
  }

  removeCommonBillingCompany(
    accountId: number,
    companyId: number,
    detachCurrent = false
  ): Observable<CommonBillingAccountResponse> {
    const params = new HttpParams().set('detachCurrent', String(detachCurrent));
    return this.http.delete<CommonBillingAccountResponse>(
      this.apiUrl(`/api/common-billing/accounts/${accountId}/companies/${companyId}`),
      { params }
    );
  }
}
