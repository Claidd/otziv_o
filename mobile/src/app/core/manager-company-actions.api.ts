import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { CompanyChatBindingRepair, CompanyOrderCreatePayload, CompanyOrderCreateRequest, CompanyOrderCreateResult } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class ManagerCompanyActionsApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  updateManagerCompanyStatus(companyId: number, status: string): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/manager/companies/${companyId}/status`), { status });
  }

  getManagerCompanyOrderCreate(companyId: number): Observable<CompanyOrderCreatePayload> {
    return this.http.get<CompanyOrderCreatePayload>(this.apiUrl(`/api/manager/companies/${companyId}/order-create`));
  }

  createManagerCompanyOrder(
    companyId: number,
    request: CompanyOrderCreateRequest
  ): Observable<CompanyOrderCreateResult> {
    return this.http.post<CompanyOrderCreateResult>(this.apiUrl(`/api/manager/companies/${companyId}/orders`), request);
  }

  updateManagerCompanyNote(companyId: number, companyComments: string): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/manager/companies/${companyId}/note`), { companyComments });
  }

  repairManagerCompanyChatBinding(companyId: number): Observable<CompanyChatBindingRepair> {
    return this.http.post<CompanyChatBindingRepair>(
      this.apiUrl(`/api/manager/companies/${companyId}/chat-binding/repair`),
      {}
    );
  }
}
