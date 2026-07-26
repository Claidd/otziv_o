import { HttpClient } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

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
export class ManagerReportReviewAccessService {
  readonly state = signal<ManagerReportReviewAccessState | null>(null);

  private notifiedReviewId: number | null = null;

  constructor(private readonly http: HttpClient) {}

  async refresh(): Promise<ManagerReportReviewAccessState> {
    const state = await firstValueFrom(
      this.http.get<ManagerReportReviewAccessState>(
        this.apiUrl('/api/manager-report-review/access-state')
      )
    );
    this.state.set(state);
    return state;
  }

  async checkIn(): Promise<ManagerReportReviewAccessState> {
    const state = await firstValueFrom(
      this.http.post<ManagerReportReviewAccessState>(
        this.apiUrl('/api/manager-report-review/check-in'),
        {}
      )
    );
    this.state.set(state);
    return state;
  }

  shouldNotify(state: ManagerReportReviewAccessState): boolean {
    if (!state.pending) {
      return false;
    }
    const reviewId = state.reviewId ?? -1;
    if (this.notifiedReviewId === reviewId) {
      return false;
    }
    this.notifiedReviewId = reviewId;
    return true;
  }

  clear(): void {
    this.state.set(null);
    this.notifiedReviewId = null;
  }

  private apiUrl(path: string): string {
    return `${mobileEnvironment.apiBaseUrl}${path}`;
  }
}

export function isManagerReportReviewPersonalRoute(url: string): boolean {
  const path = (url || '').split('?')[0].replace(/\/+$/, '');
  return path === '/tabs/home'
    || path === '/tabs/home/profile'
    || path === '/tabs/profile';
}
