import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface SystemHealth {
  status: string;
  components?: Record<string, { status: string; details?: Record<string, unknown> }>;
}

@Injectable({ providedIn: 'root' })
export class SystemHealthApi {
  constructor(private readonly http: HttpClient) {}

  getHealth(): Observable<SystemHealth> {
    return this.http.get<SystemHealth>(`${appEnvironment.apiBaseUrl}/actuator/health`);
  }
}
