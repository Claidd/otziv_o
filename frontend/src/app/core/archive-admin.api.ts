import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type { ArchiveCandidateCounts } from './manager.api';

export type ArchiveRunMode = 'dry-run' | 'live';

export interface ArchiveOrdersSettings {
  boardLiveSliceRetentionDays: number;
  archiveRetentionDays: number;
  batchSize: number;
  maxBatchSize: number;
  applyEnabled: boolean;
  scheduleWorkerEnabled: boolean;
  scheduleEnabled: boolean;
  runMode: ArchiveRunMode;
  reason: string;
  scheduleTime: string;
  scheduleCron: string;
  scheduleZone: string;
}

export interface ArchiveOrderCandidateItem {
  id: number;
  companyId?: number;
  companyTitle: string;
  companyPhone: string;
  companyCity: string;
  filialTitle: string;
  status: string;
  sum: number;
  amount?: number;
  counter?: number;
  managerName: string;
  workerName: string;
  created?: string;
  changed?: string;
  payDay?: string;
  candidateDate?: string;
  orderDetailsCount: number;
  reviewsCount: number;
}

export interface ArchiveCandidatesPreview {
  cutoffDate: string;
  retentionDays: number;
  batchLimit: number;
  eligibleOrders: number;
  selected: ArchiveCandidateCounts;
  missingClosedAnalyticsMonths: number;
  items: ArchiveOrderCandidateItem[];
}

export interface ArchiveDryRunResult {
  batchId: number;
  dryRun: true;
  cutoffDate: string;
  retentionDays: number;
  batchLimit: number;
  eligibleOrders: number;
  selected: ArchiveCandidateCounts;
  missingClosedAnalyticsMonths: number;
  message: string;
}

export interface ArchiveRunResult {
  batchId: number;
  dryRun: false;
  cutoffDate: string;
  retentionDays: number;
  batchLimit: number;
  eligibleOrders: number;
  selected: ArchiveCandidateCounts;
  archived: ArchiveCandidateCounts;
  deleted: ArchiveCandidateCounts;
  missingClosedAnalyticsMonths: number;
  message: string;
}

export type ArchiveExecutionResult = ArchiveDryRunResult | ArchiveRunResult;

export interface ArchiveBatchSummary {
  batchId: number;
  startedAt?: string;
  finishedAt?: string;
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

export interface ArchiveBatchDetails {
  summary: ArchiveBatchSummary;
  totals: ArchiveCandidateCounts;
  orders: ArchiveOrderCandidateItem[];
}

export interface ArchiveLockStatus {
  name: string;
  locked: boolean;
  ownerConnectionId?: number;
  heldByCurrentConnection: boolean;
}

export interface ArchiveRunRequest {
  retentionDays?: number | null;
  batchLimit?: number | null;
  reason?: string | null;
}

export interface ArchiveCandidatePreviewRequest {
  retentionDays?: number | null;
  batchLimit?: number | null;
  previewLimit?: number | null;
}

export interface ArchiveOrdersSettingsRequest {
  archiveRetentionDays: number;
  batchSize: number;
  applyEnabled: boolean;
  scheduleWorkerEnabled: boolean;
  scheduleEnabled: boolean;
  runMode: ArchiveRunMode;
  reason: string;
  scheduleTime: string;
  scheduleZone: string;
}

@Injectable({ providedIn: 'root' })
export class ArchiveAdminApi {
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin/archive`;

  constructor(private readonly http: HttpClient) {}

  getOrderSettings(): Observable<ArchiveOrdersSettings> {
    return this.http.get<ArchiveOrdersSettings>(`${this.baseUrl}/orders/settings`);
  }

  updateOrderSettings(request: ArchiveOrdersSettingsRequest): Observable<ArchiveOrdersSettings> {
    return this.http.put<ArchiveOrdersSettings>(`${this.baseUrl}/orders/settings`, request);
  }

  previewOrderCandidates(request: ArchiveCandidatePreviewRequest): Observable<ArchiveCandidatesPreview> {
    let params = this.params(request);
    if (request.previewLimit != null) {
      params = params.set('previewLimit', String(request.previewLimit));
    }
    return this.http.get<ArchiveCandidatesPreview>(`${this.baseUrl}/orders/candidates`, { params });
  }

  dryRunOrders(request: ArchiveRunRequest): Observable<ArchiveDryRunResult> {
    return this.http.post<ArchiveDryRunResult>(`${this.baseUrl}/orders/dry-run`, {}, {
      params: this.params(request)
    });
  }

  runOrders(request: ArchiveRunRequest): Observable<ArchiveRunResult> {
    return this.http.post<ArchiveRunResult>(`${this.baseUrl}/orders/run`, {}, {
      params: this.params(request).set('confirm', 'true')
    });
  }

  latestBatches(limit = 30): Observable<ArchiveBatchSummary[]> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<ArchiveBatchSummary[]>(`${this.baseUrl}/batches`, { params });
  }

  getBatchDetails(batchId: number): Observable<ArchiveBatchDetails> {
    return this.http.get<ArchiveBatchDetails>(`${this.baseUrl}/batches/${batchId}`);
  }

  getOrderArchiveLockStatus(): Observable<ArchiveLockStatus> {
    return this.http.get<ArchiveLockStatus>(`${this.baseUrl}/orders/lock`);
  }

  private params(request: ArchiveRunRequest): HttpParams {
    let params = new HttpParams();
    if (request.retentionDays != null) {
      params = params.set('retentionDays', String(request.retentionDays));
    }
    if (request.batchLimit != null) {
      params = params.set('batchLimit', String(request.batchLimit));
    }
    if (request.reason?.trim()) {
      params = params.set('reason', request.reason.trim());
    }
    return params;
  }
}
