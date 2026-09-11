import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { ManagerPaymentProfileAssignmentRequest, PaymentProfilePolicyRequest, TbankPaymentProfilesResponse, TbankRuntimeSettings, UpdateTbankRuntimeSettingsRequest } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class PaymentConfigurationApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getAdminTbankPaymentProfiles(): Observable<TbankPaymentProfilesResponse> {
    return this.http.get<TbankPaymentProfilesResponse>(this.apiUrl('/api/admin/payments/tbank-profiles'));
  }

  getAdminTbankRuntimeSettings(): Observable<TbankRuntimeSettings> {
    return this.http.get<TbankRuntimeSettings>(this.apiUrl('/api/admin/payments/tbank-runtime-settings'));
  }

  updateAdminTbankRuntimeSettings(
    request: UpdateTbankRuntimeSettingsRequest
  ): Observable<TbankRuntimeSettings> {
    return this.http.put<TbankRuntimeSettings>(
      this.apiUrl('/api/admin/payments/tbank-runtime-settings'),
      request
    );
  }

  updateAdminTbankPaymentProfileAssignments(
    assignments: ManagerPaymentProfileAssignmentRequest[]
  ): Observable<TbankPaymentProfilesResponse> {
    return this.http.put<TbankPaymentProfilesResponse>(
      this.apiUrl('/api/admin/payments/tbank-profiles/manager-assignments'),
      { assignments }
    );
  }

  updateAdminPaymentProfilePolicies(
    profiles: PaymentProfilePolicyRequest[]
  ): Observable<TbankPaymentProfilesResponse> {
    return this.http.put<TbankPaymentProfilesResponse>(
      this.apiUrl('/api/admin/payments/tbank-profiles/policies'),
      { profiles }
    );
  }
}
