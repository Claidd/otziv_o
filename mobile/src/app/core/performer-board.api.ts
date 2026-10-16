import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

export interface PerformerTask {
  id: number;
  orderId: number;
  reviewId: number;
  offerId: number | null;
  companyTitle: string;
  filialTitle: string;
  cityTitle: string;
  platform: string;
  status: string;
  externalConfirmStatus: string;
  draftText: string;
  finalText: string;
  instruction: string;
  publicationUrl: string;
  performerPublicationScreenshotUrl: string;
  publishAvailableAt: string;
  payoutAmount: number | null;
}

export interface PerformerTaskBoard {
  offers: PerformerTask[];
  active: PerformerTask[];
  waitingPublication: PerformerTask[];
  published: PerformerTask[];
  paid: PerformerTask[];
}

@Injectable({ providedIn: 'root' })
export class PerformerBoardApi {
  private readonly http = inject(HttpClient);
  private readonly root = `${mobileEnvironment.apiBaseUrl}/api/performer`;

  board(): Observable<PerformerTaskBoard> {
    return this.http.get<PerformerTaskBoard>(`${this.root}/board`);
  }

  acceptOffer(offerId: number): Observable<PerformerTask> {
    return this.http.post<PerformerTask>(`${this.root}/offers/${offerId}/accept`, {});
  }

  declineOffer(offerId: number): Observable<void> {
    return this.http.post<void>(`${this.root}/offers/${offerId}/decline`, {});
  }

  markWalked(assignmentId: number): Observable<PerformerTask> {
    return this.http.post<PerformerTask>(`${this.root}/assignments/${assignmentId}/walked`, {});
  }

  markPublished(assignmentId: number, request: { finalText: string; publicationUrl?: string; comment?: string }): Observable<PerformerTask> {
    return this.http.post<PerformerTask>(`${this.root}/assignments/${assignmentId}/published`, request);
  }

  uploadScreenshot(assignmentId: number, file: File): Observable<PerformerTask> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<PerformerTask>(`${this.root}/assignments/${assignmentId}/publication-screenshot`, form);
  }

  reportProblem(assignmentId: number, comment: string): Observable<PerformerTask> {
    return this.http.post<PerformerTask>(`${this.root}/assignments/${assignmentId}/problem`, { comment });
  }
}
