import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { ManagerReportDisputeResolutionPayload, ManagerReportReview, ManagerReportReviewTestStartResponse, ManagerSummaryTelegramSendResponse } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class ManagerReportsApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  sendManagerDailyAuditToTelegram(date?: string): Observable<ManagerSummaryTelegramSendResponse> {
    return this.http.post<ManagerSummaryTelegramSendResponse>(
      this.apiUrl('/api/admin/manager-daily-summary/send-test'),
      {},
      { params: date ? { date } : {} }
    );
  }

  startManagerReportReviewTest(
    date?: string,
    managerId?: number
  ): Observable<ManagerReportReviewTestStartResponse> {
    const params: Record<string, string> = {};
    if (date) params['date'] = date;
    if (managerId) params['managerId'] = String(managerId);
    return this.http.post<ManagerReportReviewTestStartResponse>(
      this.apiUrl('/api/admin/manager-daily-summary/review-test'),
      {},
      { params }
    );
  }

  getManagerReportReviews(date?: string): Observable<ManagerReportReview[]> {
    return this.http.get<ManagerReportReview[]>(
      this.apiUrl('/api/admin/manager-daily-summary/review-sessions'),
      { params: date ? { date } : {} }
    );
  }

  resolveManagerReportDispute(
    reviewId: number,
    payload: ManagerReportDisputeResolutionPayload
  ): Observable<void> {
    return this.http.post<void>(
      this.apiUrl(`/api/admin/manager-daily-summary/review-sessions/${reviewId}/resolve-dispute`),
      payload
    );
  }
}
