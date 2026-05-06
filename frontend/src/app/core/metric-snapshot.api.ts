import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface MetricSnapshotSeenRequest {
  page: 'manager' | 'worker';
  section: string;
  status?: string;
  value: number;
}

@Injectable({ providedIn: 'root' })
export class MetricSnapshotApi {
  constructor(private readonly http: HttpClient) {}

  markSeen(request: MetricSnapshotSeenRequest): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/metric-snapshots/seen`, request);
  }
}
