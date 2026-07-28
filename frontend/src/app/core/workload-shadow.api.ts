import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export type WorkloadShadowMode = 'OFF' | 'SHADOW' | 'LIVE';
export type WorkloadShadowSeverity = 'INFO' | 'WARNING' | 'CRITICAL' | string;

export interface WorkloadShadowSettings {
  mode: WorkloadShadowMode | string;
  applyEnabled: boolean;
  observationEnabled: boolean;
  groupNotificationsEnabled: boolean;
  notificationGroupChatId: number | null;
  schedulerIntervalMinutes: number;
  nearEndIntervalMinutes: number;
  nearEndWindowMinutes: number;
  businessZone: string;
  shiftStart: string;
  shiftEnd: string;
  walkMinutesPerCard: number;
  walkMinimumMinutesPerCard: number;
  newMinutesPerCard: number;
  correctionMinutesPerOrder: number;
  publishMinutesPerCard: number;
  recoveryMinutesPerTask: number;
  badMinutesPerTask: number;
  adaptiveEstimatesEnabled: boolean;
  adaptiveMinimumSamples: number;
  lookbackDays: number;
  allowedFailureDays: number;
  recipientMinimumRating: number;
  recipientMinimumHundredPercentRate: number;
  recipientMaximumFailureDays: number;
  fourthFailurePercent: number;
  fourthFailureMaxCompanies: number;
  fifthFailurePercent: number;
  fifthFailureMaxCompanies: number;
  sixthFailurePercent: number;
  sixthFailureMaxCompanies: number;
  freezeEarnDays: number;
  freezeMaxCredits: number;
  alertCooldownMinutes: number;
  notificationBatchSize: number;
  notificationMaxAttempts: number;
  notificationLeaseMinutes: number;
  notificationRetryBaseMinutes: number;
  maintenanceBatchSize: number;
  runRetentionDays: number;
  dailyRetentionDays: number;
  eventRetentionDays: number;
  decisionRetentionDays: number;
  staleRunMinutes: number;
  revision: number;
  updatedAt?: string | null;
}

export type WorkloadShadowSettingsRequest = WorkloadShadowSettings;

export interface WorkloadShadowManagerSummary {
  managerId: number;
  managerName: string;
  workerCount: number;
  hundredPercentWorkers?: number;
  workersAt100?: number;
  riskWorkers?: number;
  eligibleRecipients?: number;
  pendingProposals?: number;
  progressPercent?: number | null;
  transferCaseCount?: number;
  staffingRequired?: boolean;
  groupConnected?: boolean;
}

export interface WorkloadShadowMonitorSummary {
  updatedAt: string;
  progressDate?: string | null;
  mode: WorkloadShadowMode | string;
  observationEnabled: boolean;
  applyEnabled: boolean;
  lastRunAt?: string | null;
  nextRunAt?: string | null;
  managers?: WorkloadShadowManagerSummary[];
  managerCount?: number;
  workerCount?: number;
  hundredPercentWorkers?: number;
  workersAt100?: number;
  belowHundredPercentWorkers?: number;
  riskWorkers?: number;
  atRiskWorkerCount?: number;
  eligibleRecipients?: number;
  pendingProposals?: number;
  transferCaseCount?: number;
  lateUnits?: number;
  lateExcludedUnits?: number;
  blockedUnits?: number;
  qualifyingFailures?: number;
  staffingSignals?: number;
  staffingSignalCount?: number;
  emergencyAssignments?: number;
  missingManagerGroupCount?: number;
  missingWorkerGroupCount?: number;
  healthStatus?: string | null;
  walkEstimate?: {
    defaultMinutes: number;
    minimumMinutes: number;
    effectiveMinutes: number;
    sampleCount: number;
    averageSeconds: number;
    source: 'DEFAULT' | 'ADAPTIVE' | string;
    calculatedAt?: string | null;
  } | null;
  lastRun?: {
    id?: number | null;
    status?: string | null;
    triggerType?: string | null;
    startedAt?: string | null;
    finishedAt?: string | null;
    durationMs?: number | null;
    errorMessage?: string | null;
  } | null;
}

export interface WorkloadShadowWorker {
  workerId: number;
  userId?: number | null;
  workerUserId?: number | null;
  workerName: string;
  username?: string | null;
  managerId?: number | null;
  managerName?: string | null;
  rating?: number | null;
  currentPercent?: number | null;
  progressPercent?: number | null;
  predictedPercent?: number | null;
  hundredPercentDays?: number;
  failureDays?: number;
  evaluatedDays?: number;
  hundredPercentRate?: number | null;
  acceptsCompanyTransfers?: boolean;
  eligibleRecipient?: boolean;
  recipientEligible?: boolean;
  distributionRank?: number | null;
  distributionStatus?: string | null;
  diagnosticStatus?: string | null;
  progressDate?: string | null;
  snapshotAt?: string | null;
  activeUnits?: number;
  completedUnits?: number;
  eligibleUnits?: number;
  feasibleUnits?: number;
  lateUnits?: number;
  lateExcludedUnits?: number;
  blockedUnits?: number;
  estimatedRemainingMinutes?: number | null;
  plannedUnits?: number;
  incomingUnits?: number;
  urgentUnits?: number;
  externalBlockedUnits?: number;
  clientDeferredUnits?: number;
  managerDeferredUnits?: number;
  newUnits?: number;
  correctionUnits?: number;
  nagulUnits?: number;
  publishUnits?: number;
  recoveryUnits?: number;
  badUnits?: number;
  freezeCredits?: number;
  transferStage?: number;
  lastDayReached100?: boolean;
  workerGroupConnected?: boolean;
  lastAvailableAt?: string | null;
  reasons?: string[];
  updatedAt?: string | null;
}

export interface WorkloadShadowProposalCompany {
  companyId: number;
  companyName: string;
  activeOrderCount?: number;
  activeUnitCount?: number;
  estimatedMinutes?: number | null;
}

export interface WorkloadShadowProposalCandidate {
  workerId: number;
  workerName: string;
  rating?: number | null;
  rank?: number | null;
  sequenceNumber?: number | null;
  eligible?: boolean;
  reason?: string | null;
  hundredPercentDays?: number;
  failureDays?: number;
  currentEstimatedMinutes?: number | null;
  workerGroupConnected?: boolean;
  simulatedOfferStatus?: string | null;
}

export interface WorkloadShadowProposal {
  id: number | string;
  status: string;
  level?: string | null;
  failureNumber?: number;
  sourceWorkerId?: number | null;
  sourceWorkerName?: string | null;
  managerId?: number | null;
  managerName?: string | null;
  targetPercent?: number | null;
  transferPercent?: number | null;
  companyCount?: number;
  companyId?: number | null;
  companyTitle?: string | null;
  estimatedMinutes?: number | null;
  problemUnits?: number;
  selectionRank?: number;
  staffingRequired?: boolean;
  fallbackWorkerId?: number | null;
  fallbackWorkerName?: string | null;
  fallbackReviewId?: number | null;
  recommendedWorkerId?: number | null;
  recommendedWorkerName?: string | null;
  reason?: string | null;
  companies?: WorkloadShadowProposalCompany[];
  candidates?: WorkloadShadowProposalCandidate[];
  graph?: {
    activeOrders?: number;
    newUnits?: number;
    correction?: number;
    nagul?: number;
    publish?: number;
    recovery?: number;
    bad?: number;
  } | null;
  graphWarningCount?: number;
  graphErrorCount?: number;
  graphWarningCodes?: string | null;
  graphErrorCodes?: string | null;
  firstDetectedAt?: string | null;
  lastSeenAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface WorkloadShadowEvent {
  id: number | string;
  eventType: string;
  severity?: WorkloadShadowSeverity;
  message: string;
  managerId?: number | null;
  managerName?: string | null;
  workerId?: number | null;
  workerName?: string | null;
  companyId?: number | null;
  companyName?: string | null;
  companyTitle?: string | null;
  title?: string | null;
  source?: string | null;
  details?: string | null;
  targetGroupType?: string | null;
  targetGroupConnected?: boolean;
  deliveryStatus?: string | null;
  deliveryAttempts?: number;
  occurrenceCount?: number;
  firstSeenAt?: string | null;
  lastSeenAt?: string | null;
  deliveredAt?: string | null;
  lastError?: string | null;
  active?: boolean;
  createdAt?: string | null;
}

export interface WorkloadShadowHealthIssue {
  code: string;
  severity?: WorkloadShadowSeverity;
  message: string;
  component?: string | null;
  detectedAt?: string | null;
}

export interface WorkloadShadowHealthNode {
  name: string;
  status: string;
  message?: string | null;
  updatedAt?: string | null;
  lagSeconds?: number | null;
}

export interface WorkloadShadowHealth {
  status: string;
  updatedAt?: string | null;
  checkedAt?: string | null;
  lastRunAt?: string | null;
  lastSuccessfulRunAt?: string | null;
  nextRunAt?: string | null;
  stale?: boolean;
  queueDepth?: number;
  failedRuns?: number;
  repairedItems?: number;
  groupNotificationsEnabled?: boolean;
  dueEvents?: number;
  processingEvents?: number;
  staleProcessingEvents?: number;
  deadEvents?: number;
  missingGroupBindings?: number;
  runningRuns?: number;
  staleRunningRuns?: number;
  graphWarningCases?: number;
  graphErrorCases?: number;
  expiredRecalculationLocks?: number;
  snapshotAgeSeconds?: number;
  oldestDueAgeSeconds?: number;
  oldestDueEventAt?: string | null;
  lastSnapshotAt?: string | null;
  nodes?: WorkloadShadowHealthNode[];
  issues?: WorkloadShadowHealthIssue[];
}

export interface WorkloadShadowPage<T> {
  items: T[];
  total?: number;
  page?: number;
  size?: number;
}

export type WorkloadShadowCollection<T> = T[] | WorkloadShadowPage<T>;

export interface WorkloadShadowActionResult {
  accepted?: boolean;
  status?: string | null;
  triggerType?: string | null;
  message?: string | null;
  runId?: number | string | null;
  managerCount?: number;
  workerCount?: number;
  transferCaseCount?: number;
  eventCount?: number;
  failedRuns?: number;
  retriedEvents?: number;
  cancelledEvents?: number;
  updatedAt?: string | null;
  health?: WorkloadShadowHealth | null;
}

export interface WorkloadTransferPreference {
  acceptsCompanyTransfers: boolean;
  changedAt?: string | null;
  updatedAt?: string | null;
}

@Injectable({ providedIn: 'root' })
export class WorkloadShadowApi {
  private readonly adminBaseUrl = `${appEnvironment.apiBaseUrl}/api/admin/workload-shadow`;
  private readonly preferenceUrl = `${appEnvironment.apiBaseUrl}/api/workload-shadow/preferences/me`;

  constructor(private readonly http: HttpClient) {}

  getSettings(): Observable<WorkloadShadowSettings> {
    return this.http.get<WorkloadShadowSettings>(`${this.adminBaseUrl}/settings`);
  }

  updateSettings(request: WorkloadShadowSettingsRequest): Observable<WorkloadShadowSettings> {
    return this.http.put<WorkloadShadowSettings>(`${this.adminBaseUrl}/settings`, request);
  }

  getSummary(): Observable<WorkloadShadowMonitorSummary> {
    return this.http.get<WorkloadShadowMonitorSummary>(`${this.adminBaseUrl}/monitor/summary`);
  }

  getWorkers(managerId?: number | null): Observable<WorkloadShadowCollection<WorkloadShadowWorker>> {
    return this.http.get<WorkloadShadowCollection<WorkloadShadowWorker>>(
      `${this.adminBaseUrl}/monitor/workers`,
      { params: this.managerParams(managerId) }
    );
  }

  getProposals(managerId?: number | null): Observable<WorkloadShadowCollection<WorkloadShadowProposal>> {
    return this.http.get<WorkloadShadowCollection<WorkloadShadowProposal>>(
      `${this.adminBaseUrl}/monitor/proposals`,
      { params: this.managerParams(managerId) }
    );
  }

  getEvents(limit = 50): Observable<WorkloadShadowCollection<WorkloadShadowEvent>> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<WorkloadShadowCollection<WorkloadShadowEvent>>(
      `${this.adminBaseUrl}/monitor/events`,
      { params }
    );
  }

  getHealth(): Observable<WorkloadShadowHealth> {
    return this.http.get<WorkloadShadowHealth>(`${this.adminBaseUrl}/monitor/health`);
  }

  recalculate(): Observable<WorkloadShadowActionResult> {
    return this.http.post<WorkloadShadowActionResult>(
      `${this.adminBaseUrl}/monitor/recalculate`,
      {}
    );
  }

  repair(): Observable<WorkloadShadowActionResult> {
    return this.http.post<WorkloadShadowActionResult>(
      `${this.adminBaseUrl}/monitor/repair`,
      {}
    );
  }

  getMyTransferPreference(): Observable<WorkloadTransferPreference> {
    return this.http.get<WorkloadTransferPreference>(this.preferenceUrl);
  }

  updateMyTransferPreference(
    acceptsCompanyTransfers: boolean
  ): Observable<WorkloadTransferPreference> {
    return this.http.put<WorkloadTransferPreference>(this.preferenceUrl, {
      acceptsCompanyTransfers
    });
  }

  private managerParams(managerId?: number | null): HttpParams {
    return managerId == null
      ? new HttpParams()
      : new HttpParams().set('managerId', String(managerId));
  }
}
