import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { Page, WorkerRiskIncident, WorkerRiskIncidentStatus, WorkerRiskResolutionAction } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class ManagerWorkerRiskApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getManagerWorkerRiskIncidents(
    status: WorkerRiskIncidentStatus = 'OPEN',
    page = 0,
    size = 50
  ): Observable<Page<WorkerRiskIncident>> {
    const params = new HttpParams()
      .set('status', status)
      .set('page', String(page))
      .set('size', String(size));

    return this.http.get<Page<WorkerRiskIncident>>(this.apiUrl('/api/manager/worker-risk/incidents'), { params });
  }

  setManagerWorkerRiskIncidentResolution(
    incidentId: number,
    action: WorkerRiskResolutionAction,
    penaltyPoints?: number,
    comment?: string | null
  ): Observable<WorkerRiskIncident> {
    return this.http.post<WorkerRiskIncident>(
      this.apiUrl(`/api/manager/worker-risk/incidents/${incidentId}/resolution`),
      { action, penaltyPoints, comment }
    );
  }

  rollbackManagerWorkerRiskIncident(incidentId: number): Observable<WorkerRiskIncident> {
    return this.http.post<WorkerRiskIncident>(
      this.apiUrl(`/api/manager/worker-risk/incidents/${incidentId}/rollback`),
      {}
    );
  }
}
