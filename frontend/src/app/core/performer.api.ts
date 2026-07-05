import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface PerformerAssignment {
  id: number;
  orderId: number;
  reviewId: number;
  offerId?: number | null;
  companyTitle: string;
  filialTitle: string;
  cityTitle: string;
  platform: string;
  status: string;
  draftText: string;
  finalText: string;
  instruction: string;
  publicationUrl: string;
  acceptedAt: string;
  walkedAt: string;
  publishAvailableAt: string;
  publishedClaimedAt: string;
  verifiedAt: string;
  payoutAmount?: number | null;
}

export interface PerformerBoard {
  offers: PerformerAssignment[];
  active: PerformerAssignment[];
  waitingPublication: PerformerAssignment[];
  published: PerformerAssignment[];
  paid: PerformerAssignment[];
}

export interface PerformerPublishRequest {
  finalText: string;
  publicationUrl?: string;
  comment?: string;
}

export interface PerformerProblemRequest {
  comment: string;
}

export interface AdminPerformer {
  id: number;
  userId: number;
  username: string;
  fio: string;
  phoneNumber: string;
  cityTitle: string;
  gender: string;
  status: string;
  rating?: number | null;
  reliabilityScore?: number | null;
  completedCount: number;
  cancelledCount: number;
  expiredOfferCount: number;
  failedCheckCount: number;
  telegramChatId?: number | null;
}

export interface PerformerCityReport {
  cityId: number;
  cityTitle: string;
  activePerformers: number;
  queueAssignments: number;
  activeAssignments: number;
  verifiedAssignments: number;
  rejectedAssignments: number;
}

export interface PerformerRolloutSettings {
  enabled: boolean;
  cityIds: string;
  productIds: string;
  parsedCityIds: number[];
  parsedProductIds: number[];
}

export interface PerformerRolloutSettingsRequest {
  enabled: boolean;
  cityIds: string;
  productIds: string;
}

export interface AdminPerformerControl {
  performers: AdminPerformer[];
  assignments: PerformerAssignment[];
  cityReports: PerformerCityReport[];
  rollout: PerformerRolloutSettings;
}

export interface AdminPerformerManualRunResponse {
  createdAssignments: number;
  expiredOffers: number;
  offeredAssignments: number;
  readyNotifications: number;
}

@Injectable({ providedIn: 'root' })
export class PerformerApi {
  constructor(private readonly http: HttpClient) {}

  board(): Observable<PerformerBoard> {
    return this.http.get<PerformerBoard>(`${appEnvironment.apiBaseUrl}/api/performer/board`);
  }

  acceptOffer(offerId: number): Observable<PerformerAssignment> {
    return this.http.post<PerformerAssignment>(`${appEnvironment.apiBaseUrl}/api/performer/offers/${offerId}/accept`, {});
  }

  declineOffer(offerId: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/performer/offers/${offerId}/decline`, {});
  }

  markWalked(assignmentId: number): Observable<PerformerAssignment> {
    return this.http.post<PerformerAssignment>(`${appEnvironment.apiBaseUrl}/api/performer/assignments/${assignmentId}/walked`, {});
  }

  markPublished(assignmentId: number, request: PerformerPublishRequest): Observable<PerformerAssignment> {
    return this.http.post<PerformerAssignment>(`${appEnvironment.apiBaseUrl}/api/performer/assignments/${assignmentId}/published`, request);
  }

  reportProblem(assignmentId: number, request: PerformerProblemRequest): Observable<PerformerAssignment> {
    return this.http.post<PerformerAssignment>(`${appEnvironment.apiBaseUrl}/api/performer/assignments/${assignmentId}/problem`, request);
  }

  adminControl(): Observable<AdminPerformerControl> {
    return this.http.get<AdminPerformerControl>(`${appEnvironment.apiBaseUrl}/api/admin/performers/control`);
  }

  updatePerformerStatus(id: number, status: string, reason = ''): Observable<AdminPerformer> {
    const params = new URLSearchParams({ status });
    if (reason.trim()) {
      params.set('reason', reason.trim());
    }
    return this.http.post<AdminPerformer>(`${appEnvironment.apiBaseUrl}/api/admin/performers/${id}/status?${params.toString()}`, {});
  }

  verifyAssignment(id: number): Observable<PerformerAssignment> {
    return this.http.post<PerformerAssignment>(`${appEnvironment.apiBaseUrl}/api/admin/performers/assignments/${id}/verify`, {});
  }

  updateRollout(request: PerformerRolloutSettingsRequest): Observable<PerformerRolloutSettings> {
    return this.http.put<PerformerRolloutSettings>(`${appEnvironment.apiBaseUrl}/api/admin/performers/rollout`, request);
  }

  createAssignmentsForOrder(orderId: number): Observable<AdminPerformerManualRunResponse> {
    return this.http.post<AdminPerformerManualRunResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/performers/orders/${orderId}/assignments`,
      {}
    );
  }

  runSchedulerOnce(): Observable<AdminPerformerManualRunResponse> {
    return this.http.post<AdminPerformerManualRunResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/performers/scheduler/run`,
      {}
    );
  }
}
