import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

export interface MobileAdminPerformer {
  id: number;
  userId: number | null;
  username: string;
  fio: string;
  phoneNumber: string;
  cityTitle: string;
  status: 'NEW' | 'ACTIVE' | 'PAUSED' | 'BLOCKED' | 'REJECTED';
  completedCount: number;
  cancelledCount: number;
  expiredOfferCount: number;
  failedCheckCount: number;
  telegramChatId: number | null;
  phoneVerifiedAt: string | null;
  activationReady: boolean;
  legacyApprovedBeforeSecureLifecycle: boolean;
  activationWarning: string;
}

export interface MobilePerformerAssignment {
  id: number;
  orderId: number;
  reviewId: number;
  companyTitle: string;
  filialTitle: string;
  cityTitle: string;
  platform: string;
  status: string;
  externalConfirmStatus: string;
  draftText: string;
  performerPublicationScreenshotUrl: string;
  managerConfirmationScreenshotUrl: string;
}

export interface MobilePerformerCityReport {
  cityId: number;
  cityTitle: string;
  activePerformers: number;
  queueAssignments: number;
  activeAssignments: number;
  verifiedAssignments: number;
  rejectedAssignments: number;
}

export interface MobilePerformerRollout {
  enabled: boolean;
  cityIds: string;
  productIds: string;
  parsedCityIds: number[];
  parsedProductIds: number[];
}

export interface MobilePerformerControl {
  performers: MobileAdminPerformer[];
  assignments: MobilePerformerAssignment[];
  cityReports: MobilePerformerCityReport[];
  rollout: MobilePerformerRollout;
}

export interface MobilePerformerRunResult {
  createdAssignments: number;
  expiredOffers: number;
  offeredAssignments: number;
  readyNotifications: number;
}

@Injectable({ providedIn: 'root' })
export class PerformerAdminApi {
  private readonly http = inject(HttpClient);
  private readonly root = `${mobileEnvironment.apiBaseUrl}/api/admin/performers`;

  control(): Observable<MobilePerformerControl> {
    return this.http.get<MobilePerformerControl>(`${this.root}/control`);
  }

  updateStatus(
    performerId: number,
    status: MobileAdminPerformer['status'],
    reason: string,
    phoneVerified: boolean
  ): Observable<MobileAdminPerformer> {
    let params = new HttpParams().set('status', status);
    if (reason.trim()) params = params.set('reason', reason.trim());
    if (phoneVerified) params = params.set('phoneVerified', 'true');
    return this.http.post<MobileAdminPerformer>(`${this.root}/${performerId}/status`, {}, { params });
  }

  updateRollout(settings: Pick<MobilePerformerRollout, 'enabled' | 'cityIds' | 'productIds'>): Observable<MobilePerformerRollout> {
    return this.http.put<MobilePerformerRollout>(`${this.root}/rollout`, settings);
  }

  createAssignments(orderId: number): Observable<MobilePerformerRunResult> {
    return this.http.post<MobilePerformerRunResult>(`${this.root}/orders/${orderId}/assignments`, {});
  }

  runScheduler(): Observable<MobilePerformerRunResult> {
    return this.http.post<MobilePerformerRunResult>(`${this.root}/scheduler/run`, {});
  }

  uploadManagerScreenshot(assignmentId: number, file: File): Observable<MobilePerformerAssignment> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<MobilePerformerAssignment>(`${this.root}/assignments/${assignmentId}/confirmation-screenshot`, form);
  }

  verifyAssignment(assignmentId: number): Observable<MobilePerformerAssignment> {
    return this.http.post<MobilePerformerAssignment>(`${this.root}/assignments/${assignmentId}/verify`, {});
  }
}
