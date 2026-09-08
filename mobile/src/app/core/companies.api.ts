import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, forkJoin, map } from 'rxjs';
import type { CompanyCreateOption, CompanyCreatePayload, CompanyCreateRequest, CompanyCreateResult, CompanyCreateSource } from './api.service';
import { mobileEnvironment } from './mobile-environment';

@Injectable({ providedIn: 'root' })
export class CompaniesApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }
  getCompanyCreatePayload(
    source: CompanyCreateSource,
    leadId?: number | null,
    managerId?: number | null
  ): Observable<CompanyCreatePayload> {
    let params = new HttpParams().set('source', source);

    if (leadId != null) {
      params = params.set('leadId', String(leadId));
    }

    if (managerId != null) {
      params = params.set('managerId', String(managerId));
    }

    return this.http.get<CompanyCreatePayload>(this.apiUrl('/api/companies/create-payload'), { params });
  }

  getCompanySubcategories(categoryId: number): Observable<CompanyCreateOption[]> {
    return this.http.get<CompanyCreateOption[]>(this.apiUrl(`/api/companies/categories/${categoryId}/subcategories`));
  }

  createCompany(request: CompanyCreateRequest): Observable<CompanyCreateResult> {
    return this.http.post<CompanyCreateResult>(this.apiUrl('/api/companies'), request);
  }

}
