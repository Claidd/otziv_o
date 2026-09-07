import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type { AdminClientMessageMonitor, AdminClientMessageMaintenancePreview, AdminMaintenanceApplyResponse, AdminClientMessageMonitorSettings } from './admin-dictionaries.api';

@Injectable({ providedIn: 'root' })
export class AdminMessageMonitorApi {
  private readonly http = inject(HttpClient);

  getClientMessageMonitor(): Observable<AdminClientMessageMonitor> {
    return this.http.get<AdminClientMessageMonitor>(`${appEnvironment.apiBaseUrl}/api/admin/client-messages/monitor`);
  }

  getClientMessageMaintenancePreview(): Observable<AdminClientMessageMaintenancePreview> {
    return this.http.get<AdminClientMessageMaintenancePreview>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/maintenance-preview`
    );
  }

  applyClientMessageMaintenance(action: 'company-statuses' | 'payment-overdue' | 'missing-bad-tasks' | 'archive-offers' | 'publication-dates' | 'publication-completed'): Observable<AdminMaintenanceApplyResponse> {
    return this.http.post<AdminMaintenanceApplyResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/maintenance/${action}`,
      {}
    );
  }

  updateClientMessageMonitorSettings(enabled: boolean): Observable<AdminClientMessageMonitorSettings> {
    return this.http.put<AdminClientMessageMonitorSettings>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/monitor`,
      { enabled }
    );
  }

  retryClientMessageNow(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.http.post<AdminClientMessageMonitor>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/monitor/${stateId}/retry-now`,
      {}
    );
  }

  disableClientMessageCandidate(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.http.post<AdminClientMessageMonitor>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/monitor/${stateId}/disable`,
      {}
    );
  }

  markClientMessageCandidateDone(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.http.post<AdminClientMessageMonitor>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/monitor/${stateId}/done`,
      {}
    );
  }
}
