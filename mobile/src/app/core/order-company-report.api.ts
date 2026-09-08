import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import type { CompanyDeepReportState } from './api.service';
import { mobileEnvironment } from './mobile-environment';

@Injectable({ providedIn: 'root' })
export class OrderCompanyReportApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }
  getManagerOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> {
    return this.http.get<CompanyDeepReportState>(this.apiUrl(`/api/manager/orders/${orderId}/company-report`));
  }

  startManagerOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> {
    return this.http.post<CompanyDeepReportState>(this.apiUrl(`/api/manager/orders/${orderId}/company-report`), {});
  }

  refreshManagerOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> {
    return this.http.post<CompanyDeepReportState>(this.apiUrl(`/api/manager/orders/${orderId}/company-report/refresh`), {});
  }
}
