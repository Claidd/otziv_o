import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

export type MobileArchiveRunMode = 'dry-run' | 'live';

export interface MobileArchiveSettings {
  boardLiveSliceRetentionDays: number;
  archiveRetentionDays: number;
  batchSize: number;
  maxBatchSize: number;
  applyEnabled: boolean;
  scheduleWorkerEnabled: boolean;
  scheduleEnabled: boolean;
  runMode: MobileArchiveRunMode;
  reason: string;
  scheduleTime: string;
  scheduleCron: string;
  scheduleZone: string;
}

export interface MobileArchiveSettingsRequest {
  archiveRetentionDays: number;
  batchSize: number;
  applyEnabled: boolean;
  scheduleWorkerEnabled: boolean;
  scheduleEnabled: boolean;
  runMode: MobileArchiveRunMode;
  reason: string;
  scheduleTime: string;
  scheduleZone: string;
}

export interface MobileArchiveCounts {
  orders: number;
  orderDetails: number;
  reviews: number;
  badReviewTasks: number;
  nextOrderRequests: number;
  zp: number;
  paymentCheck: number;
}

export interface MobileArchiveOrderItem {
  id: number;
  companyTitle: string;
  companyCity: string | null;
  filialTitle: string | null;
  status: string;
  sum: number;
  managerName: string | null;
  candidateDate: string | null;
  orderDetailsCount: number;
  reviewsCount: number;
}

export interface MobileArchivePreview {
  cutoffDate: string;
  retentionDays: number;
  batchLimit: number;
  eligibleOrders: number;
  selected: MobileArchiveCounts;
  missingClosedAnalyticsMonths: number;
  items: MobileArchiveOrderItem[];
}

export interface MobileArchiveRunResult extends Omit<MobileArchivePreview, 'items'> {
  batchId: number;
  dryRun: boolean;
  archived?: MobileArchiveCounts;
  deleted?: MobileArchiveCounts;
  message: string;
}

export interface MobileArchiveBatchSummary {
  batchId: number;
  startedAt: string | null;
  finishedAt: string | null;
  dryRun: boolean;
  status: string;
  archiveReason: string;
  retentionDays: number;
  ordersSelected: number;
  ordersArchived: number;
  orderDetailsArchived: number;
  reviewsArchived: number;
  badReviewTasksArchived: number;
  nextOrderRequestsArchived: number;
  zpArchived: number;
  paymentCheckArchived: number;
  message: string;
}

export interface MobileArchiveBatchDetails {
  summary: MobileArchiveBatchSummary;
  totals: MobileArchiveCounts;
  orders: MobileArchiveOrderItem[];
}

export interface MobileArchiveLockStatus {
  name: string;
  locked: boolean;
  ownerConnectionId: number | null;
  heldByCurrentConnection: boolean;
}

export interface MobileArchiveRunRequest {
  retentionDays: number;
  batchLimit: number;
  reason: string;
}

@Injectable({ providedIn: 'root' })
export class MobileArchiveAdminApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${mobileEnvironment.apiBaseUrl}/api/admin/archive`;

  settings(): Observable<MobileArchiveSettings> {
    return this.http.get<MobileArchiveSettings>(`${this.baseUrl}/orders/settings`);
  }

  updateSettings(request: MobileArchiveSettingsRequest): Observable<MobileArchiveSettings> {
    return this.http.put<MobileArchiveSettings>(`${this.baseUrl}/orders/settings`, request);
  }

  preview(request: MobileArchiveRunRequest): Observable<MobileArchivePreview> {
    return this.http.get<MobileArchivePreview>(`${this.baseUrl}/orders/candidates`, {
      params: this.params(request).set('previewLimit', 8)
    });
  }

  lock(): Observable<MobileArchiveLockStatus> {
    return this.http.get<MobileArchiveLockStatus>(`${this.baseUrl}/orders/lock`);
  }

  dryRun(request: MobileArchiveRunRequest): Observable<MobileArchiveRunResult> {
    return this.http.post<MobileArchiveRunResult>(`${this.baseUrl}/orders/dry-run`, {}, { params: this.params(request) });
  }

  runLive(request: MobileArchiveRunRequest): Observable<MobileArchiveRunResult> {
    return this.http.post<MobileArchiveRunResult>(`${this.baseUrl}/orders/run`, {}, {
      params: this.params(request).set('confirm', 'true')
    });
  }

  batches(limit = 30): Observable<MobileArchiveBatchSummary[]> {
    return this.http.get<MobileArchiveBatchSummary[]>(`${this.baseUrl}/batches`, { params: { limit } });
  }

  batchDetails(batchId: number): Observable<MobileArchiveBatchDetails> {
    return this.http.get<MobileArchiveBatchDetails>(`${this.baseUrl}/batches/${batchId}`);
  }

  private params(request: MobileArchiveRunRequest): HttpParams {
    return new HttpParams()
      .set('retentionDays', request.retentionDays)
      .set('batchLimit', request.batchLimit)
      .set('reason', request.reason.trim());
  }
}
