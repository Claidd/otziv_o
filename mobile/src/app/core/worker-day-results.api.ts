import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

/** Final end-of-day result of one specialist; counts are the real numbers of the day. */
export interface WorkerDayResult {
  workerId: number;
  date: string;
  eligibleCount: number;
  completedCount: number;
  progressPercent: number;
  reached100: boolean;
  streakDays: number;
  excused: boolean;
  excuseReason?: string | null;
  excusedBy?: string | null;
  excusedAt?: string | null;
  canExcuse: boolean;
  canRevoke: boolean;
}

export interface WorkerDayResults {
  date: string;
  editable: boolean;
  workers: WorkerDayResult[];
}

export interface WorkerDayExcuseResult {
  changed: boolean;
  message: string;
  laterDaysUpdated: number;
  day: WorkerDayResult;
}

/** Owner/admin action "Засчитать день" and its audited undo. */
@Injectable({ providedIn: 'root' })
export class WorkerDayResultsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${mobileEnvironment.apiBaseUrl}/api/admin/worker-day-results`;

  getDayResults(date: string): Observable<WorkerDayResults> {
    return this.http.get<WorkerDayResults>(this.baseUrl, { params: { date } });
  }

  excuse(workerId: number, date: string, reason: string): Observable<WorkerDayExcuseResult> {
    return this.http.post<WorkerDayExcuseResult>(
      `${this.baseUrl}/${encodeURIComponent(workerId)}/${encodeURIComponent(date)}/excuse`,
      { reason }
    );
  }

  revoke(workerId: number, date: string, reason: string): Observable<WorkerDayExcuseResult> {
    return this.http.post<WorkerDayExcuseResult>(
      `${this.baseUrl}/${encodeURIComponent(workerId)}/${encodeURIComponent(date)}/excuse/revoke`,
      { reason }
    );
  }
}
