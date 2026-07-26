import { HttpClient } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface ManagerReportReviewAccessState {
  pending: boolean;
  restricted: boolean;
  reviewId?: number | null;
  summaryDate?: string | null;
  restrictedFrom?: string | null;
  reviewStatus?: string | null;
  questionCount: number;
  answeredQuestionCount: number;
  readingStartedAt?: string | null;
  readingConfirmedAt?: string | null;
  message?: string | null;
}

@Injectable({ providedIn: 'root' })
export class ManagerReportReviewAccessApi {
  readonly state = signal<ManagerReportReviewAccessState | null>(null);

  constructor(private readonly http: HttpClient) {}

  async refresh(): Promise<ManagerReportReviewAccessState> {
    const state = await firstValueFrom(
      this.http.get<ManagerReportReviewAccessState>(
        `${appEnvironment.apiBaseUrl}/api/manager-report-review/access-state`
      )
    );
    this.state.set(state);
    return state;
  }

  async checkIn(): Promise<ManagerReportReviewAccessState> {
    const state = await firstValueFrom(
      this.http.post<ManagerReportReviewAccessState>(
        `${appEnvironment.apiBaseUrl}/api/manager-report-review/check-in`,
        {}
      )
    );
    this.state.set(state);
    return state;
  }

  clear(): void {
    this.state.set(null);
  }
}
