import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { CreateManualPaymentTaskRequest, ManagerManualPaymentSettings, ManualPaymentRecipientMonthlySummaryResponse, ManualPaymentTaskAccountingTargetOption, ManualPaymentTaskResponse, ManualPaymentTaskStatus, UpdateManagerManualPaymentSettingsRequest, UpdateManualPaymentTaskRequest } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class ManualPaymentTasksApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getManagerManualPaymentSettings(
    options: { forceRefresh?: boolean } = {}
  ): Observable<ManagerManualPaymentSettings> {
    let params = new HttpParams();
    if (options.forceRefresh) {
      params = params.set('refresh', 'true');
    }
    return this.http.get<ManagerManualPaymentSettings>(
      this.apiUrl('/api/cabinet/payment-profile/manual'),
      { params }
    );
  }

  updateManagerManualPaymentSettings(
    request: UpdateManagerManualPaymentSettingsRequest
  ): Observable<ManagerManualPaymentSettings> {
    return this.http.put<ManagerManualPaymentSettings>(
      this.apiUrl('/api/cabinet/payment-profile/manual'),
      request
    );
  }

  getManagerManualPaymentTasks(
    options: { forceRefresh?: boolean } = {}
  ): Observable<ManualPaymentTaskResponse[]> {
    let params = new HttpParams();
    if (options.forceRefresh) {
      params = params.set('refresh', 'true');
    }
    return this.http.get<ManualPaymentTaskResponse[]>(
      this.apiUrl('/api/cabinet/manual-payment-tasks'),
      { params }
    );
  }

  getManagerManualPaymentTaskAccountingTargets(
    targetAmountKopecks: number,
    taskId?: number | null
  ): Observable<ManualPaymentTaskAccountingTargetOption[]> {
    let params = new HttpParams().set('targetAmountKopecks', targetAmountKopecks);
    if (taskId != null) {
      params = params.set('taskId', taskId);
    }
    return this.http.get<ManualPaymentTaskAccountingTargetOption[]>(
      this.apiUrl('/api/cabinet/manual-payment-tasks/accounting-targets'),
      { params }
    );
  }

  createManagerManualPaymentTask(
    request: CreateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.post<ManualPaymentTaskResponse>(
      this.apiUrl('/api/cabinet/manual-payment-tasks'),
      request
    );
  }

  updateManagerManualPaymentTaskStatus(
    taskId: number,
    status: ManualPaymentTaskStatus
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.put<ManualPaymentTaskResponse>(
      this.apiUrl(`/api/cabinet/manual-payment-tasks/${taskId}/status`),
      { status }
    );
  }

  updateManagerManualPaymentTask(
    taskId: number,
    request: UpdateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.put<ManualPaymentTaskResponse>(
      this.apiUrl(`/api/cabinet/manual-payment-tasks/${taskId}`),
      request
    );
  }

  getAdminManualPaymentTasks(): Observable<ManualPaymentTaskResponse[]> {
    return this.http.get<ManualPaymentTaskResponse[]>(this.apiUrl('/api/admin/payments/manual-tasks'));
  }

  getAdminManualPaymentTaskAccountingTargets(
    managerId: number,
    targetAmountKopecks: number,
    taskId?: number | null
  ): Observable<ManualPaymentTaskAccountingTargetOption[]> {
    let params = new HttpParams()
      .set('managerId', managerId)
      .set('targetAmountKopecks', targetAmountKopecks);
    if (taskId != null) {
      params = params.set('taskId', taskId);
    }
    return this.http.get<ManualPaymentTaskAccountingTargetOption[]>(
      this.apiUrl('/api/admin/payments/manual-tasks/accounting-targets'),
      { params }
    );
  }

  getAdminManualRecipientMonthlySummary(month: string): Observable<ManualPaymentRecipientMonthlySummaryResponse> {
    const params = month ? new HttpParams().set('month', month) : new HttpParams();
    return this.http.get<ManualPaymentRecipientMonthlySummaryResponse>(
      this.apiUrl('/api/admin/payments/manual-recipients/monthly-summary'),
      { params }
    );
  }

  createAdminManualPaymentTask(
    request: CreateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.post<ManualPaymentTaskResponse>(
      this.apiUrl('/api/admin/payments/manual-tasks'),
      request
    );
  }

  updateAdminManualPaymentTaskStatus(
    taskId: number,
    status: ManualPaymentTaskStatus
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.put<ManualPaymentTaskResponse>(
      this.apiUrl(`/api/admin/payments/manual-tasks/${taskId}/status`),
      { status }
    );
  }

  updateAdminManualPaymentTask(
    taskId: number,
    request: UpdateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.put<ManualPaymentTaskResponse>(
      this.apiUrl(`/api/admin/payments/manual-tasks/${taskId}`),
      request
    );
  }
}
