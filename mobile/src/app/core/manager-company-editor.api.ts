import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import type { CompanyEditPayload, CompanyUpdateRequest, CompanyFilialUpdateRequest, FilialDeletionPreview, ManagerOption } from './api.service';
import { mobileEnvironment } from './mobile-environment';

@Injectable({ providedIn: 'root' })
export class ManagerCompanyEditorApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getManagerCompanyEdit(companyId: number): Observable<CompanyEditPayload> {
    return this.http.get<CompanyEditPayload>(this.apiUrl(`/api/manager/companies/${companyId}/edit`));
  }

  updateManagerCompany(companyId: number, request: CompanyUpdateRequest): Observable<CompanyEditPayload> {
    return this.http.put<CompanyEditPayload>(this.apiUrl(`/api/manager/companies/${companyId}`), request);
  }

  getManagerCompanySubcategories(categoryId: number): Observable<ManagerOption[]> {
    return this.http.get<ManagerOption[]>(this.apiUrl(`/api/manager/categories/${categoryId}/subcategories`));
  }

  deleteManagerCompanyWorker(companyId: number, workerId: number): Observable<CompanyEditPayload> {
    return this.http.delete<CompanyEditPayload>(this.apiUrl(`/api/manager/companies/${companyId}/workers/${workerId}`));
  }

  deleteManagerCompanyFilial(companyId: number, filialId: number): Observable<CompanyEditPayload> {
    return this.http.delete<CompanyEditPayload>(this.apiUrl(`/api/manager/companies/${companyId}/filials/${filialId}`));
  }

  getManagerCompanyFilialDeletionPreview(companyId: number, filialId: number): Observable<FilialDeletionPreview> {
    return this.http.get<FilialDeletionPreview>(
      this.apiUrl(`/api/manager/companies/${companyId}/filials/${filialId}/deletion-preview`)
    );
  }

  restoreManagerCompanyFilial(companyId: number, filialId: number): Observable<CompanyEditPayload> {
    return this.http.post<CompanyEditPayload>(
      this.apiUrl(`/api/manager/companies/${companyId}/filials/${filialId}/restore`),
      {}
    );
  }

  updateManagerCompanyFilial(
    companyId: number,
    filialId: number,
    request: CompanyFilialUpdateRequest
  ): Observable<CompanyEditPayload> {
    return this.http.put<CompanyEditPayload>(
      this.apiUrl(`/api/manager/companies/${companyId}/filials/${filialId}`),
      request
    );
  }
}
