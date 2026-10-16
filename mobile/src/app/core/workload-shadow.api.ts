import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

/** Read-only subset of the guarded workload administration API. */
export interface WorkloadShadowSettings {
  mode: string;
  observationEnabled: boolean;
  applyEnabled: boolean;
  groupNotificationsEnabled: boolean;
  shiftStart: string;
  shiftEnd: string;
  businessZone: string;
  revision: number;
}

export interface WorkloadLiveSettings {
  mode: string;
  applyEnabled: boolean;
  revision: number;
  historyStartDate: string;
  minFinalizedDays: number;
  stableHours: number;
  canaryManagerIds: number[];
}

export interface WorkloadManagerSummary {
  managerId: number;
  managerName: string;
  workerCount: number;
  workersAt100?: number;
  hundredPercentWorkers?: number;
  progressPercent?: number | null;
  transferCaseCount?: number;
  pendingProposals?: number;
  staffingRequired?: boolean;
}

export interface WorkloadMonitorSummary {
  updatedAt: string;
  progressDate?: string | null;
  mode: string;
  observationEnabled: boolean;
  applyEnabled: boolean;
  managers?: WorkloadManagerSummary[];
  managerCount?: number;
  workerCount?: number;
  workersAt100?: number;
  hundredPercentWorkers?: number;
  riskWorkers?: number;
  atRiskWorkerCount?: number;
  eligibleRecipients?: number;
  pendingProposals?: number;
  transferCaseCount?: number;
  lateExcludedUnits?: number;
  lateUnits?: number;
  staffingSignals?: number;
  staffingSignalCount?: number;
  lastRunAt?: string | null;
  nextRunAt?: string | null;
  healthStatus?: string | null;
}

export interface WorkloadWorker {
  workerId: number;
  workerName: string;
  username?: string | null;
  managerId?: number | null;
  managerName?: string | null;
  currentPercent?: number | null;
  progressPercent?: number | null;
  predictedPercent?: number | null;
  rating?: number | null;
  evaluatedDays?: number;
  hundredPercentDays?: number;
  failureDays?: number;
  transferStage?: number;
  lastDayReached100?: boolean | null;
  eligibleRecipient?: boolean;
  recipientEligible?: boolean;
  distributionRank?: number | null;
  distributionStatus?: string | null;
  diagnosticStatus?: string | null;
  acceptsCompanyTransfers?: boolean;
  workerGroupConnected?: boolean;
  activeUnits?: number;
  completedUnits?: number;
  lateExcludedUnits?: number;
  lateUnits?: number;
  blockedUnits?: number;
  estimatedRemainingMinutes?: number | null;
  reasons?: string[];
}

export interface WorkloadProposal {
  id: number | string;
  status: string;
  sourceWorkerName?: string | null;
  managerId?: number | null;
  managerName?: string | null;
  companyCount?: number;
  companyTitle?: string | null;
  targetPercent?: number | null;
  transferPercent?: number | null;
  estimatedMinutes?: number | null;
  problemUnits?: number;
  staffingRequired?: boolean;
  recommendedWorkerName?: string | null;
  fallbackWorkerName?: string | null;
  reason?: string | null;
  graphWarningCount?: number;
  graphErrorCount?: number;
  companies?: { companyId: number; companyName: string; activeUnitCount?: number }[];
  candidates?: { workerId: number; workerName: string; simulatedOfferStatus?: string | null; workerGroupConnected?: boolean }[];
  updatedAt?: string | null;
}

export interface WorkloadEvent {
  id: number | string;
  eventType: string;
  severity?: string;
  message: string;
  managerName?: string | null;
  workerName?: string | null;
  companyName?: string | null;
  companyTitle?: string | null;
  deliveryStatus?: string | null;
  occurrenceCount?: number;
  createdAt?: string | null;
  lastSeenAt?: string | null;
}

export interface WorkloadHealth {
  status: string;
  checkedAt?: string | null;
  lastRunAt?: string | null;
  lastSuccessfulRunAt?: string | null;
  lastSnapshotAt?: string | null;
  nextRunAt?: string | null;
  queueDepth?: number;
  dueEvents?: number;
  processingEvents?: number;
  deadEvents?: number;
  staleRunningRuns?: number;
  graphWarningCases?: number;
  graphErrorCases?: number;
  nodes?: { name: string; status: string; message?: string | null }[];
  issues?: { code: string; severity?: string; message: string; component?: string | null }[];
}

export interface WorkloadReadiness {
  ready: boolean;
  targetMode: string;
  checkedAt: string;
  checks: { code: string; status: string; message: string }[];
}

export interface WorkloadWorkflow {
  id: number;
  status: string;
  mode: string;
  managerName: string;
  sourceWorkerName: string;
  targetWorkerName?: string | null;
  companyTitle: string;
  ownerConfirmationRequired: boolean;
  currentOfferExpiresAt?: string | null;
  lastErrorMessage?: string | null;
  lastTransitionAt: string;
}

export interface WorkloadExecution {
  id: number;
  status: string;
  managerName: string;
  sourceWorkerName: string;
  targetWorkerName: string;
  companyTitle: string;
  orderCount: number;
  appliedAt?: string | null;
  rollbackDeadlineAt?: string | null;
  errorMessage?: string | null;
}

export interface WorkloadEmergencyAssignment {
  id: number;
  status: string;
  sourceManagerName: string;
  sourceWorkerName: string;
  targetManagerName: string;
  targetWorkerName: string;
  companyTitle: string;
  reason: string;
  appliedAt?: string | null;
  rollbackDeadlineAt?: string | null;
  lastError?: string | null;
}

export type WorkloadCollection<T> = T[] | { items: T[] };

@Injectable({ providedIn: 'root' })
export class WorkloadShadowReadApi {
  private readonly http = inject(HttpClient);
  private readonly root = `${mobileEnvironment.apiBaseUrl}/api/admin/workload-shadow`;

  settings(): Observable<WorkloadShadowSettings> { return this.http.get<WorkloadShadowSettings>(`${this.root}/settings`); }
  liveSettings(): Observable<WorkloadLiveSettings> { return this.http.get<WorkloadLiveSettings>(`${this.root}/live/settings`); }
  summary(): Observable<WorkloadMonitorSummary> { return this.http.get<WorkloadMonitorSummary>(`${this.root}/monitor/summary`); }
  health(): Observable<WorkloadHealth> { return this.http.get<WorkloadHealth>(`${this.root}/monitor/health`); }
  workers(managerId?: number | null): Observable<WorkloadCollection<WorkloadWorker>> {
    return this.http.get<WorkloadCollection<WorkloadWorker>>(`${this.root}/monitor/workers`, { params: this.managerParams(managerId) });
  }
  proposals(managerId?: number | null): Observable<WorkloadCollection<WorkloadProposal>> {
    return this.http.get<WorkloadCollection<WorkloadProposal>>(`${this.root}/monitor/proposals`, { params: this.managerParams(managerId) });
  }
  events(limit = 50): Observable<WorkloadCollection<WorkloadEvent>> {
    return this.http.get<WorkloadCollection<WorkloadEvent>>(`${this.root}/monitor/events`, {
      params: new HttpParams().set('limit', limit)
    });
  }
  readiness(targetMode: 'CANARY' | 'LIVE'): Observable<WorkloadReadiness> {
    return this.http.get<WorkloadReadiness>(`${this.root}/live/readiness`, {
      params: new HttpParams().set('targetMode', targetMode)
    });
  }
  workflows(managerId?: number | null): Observable<WorkloadWorkflow[]> {
    return this.http.get<WorkloadWorkflow[]>(`${this.root}/live/workflows`, { params: this.managerParams(managerId) });
  }
  executions(managerId?: number | null): Observable<WorkloadExecution[]> {
    return this.http.get<WorkloadExecution[]>(`${this.root}/live/executions`, { params: this.managerParams(managerId) });
  }
  emergencyAssignments(managerId?: number | null): Observable<WorkloadEmergencyAssignment[]> {
    return this.http.get<WorkloadEmergencyAssignment[]>(`${this.root}/live/emergency-assignments`, { params: this.managerParams(managerId) });
  }

  private managerParams(managerId?: number | null): HttpParams {
    return managerId == null ? new HttpParams() : new HttpParams().set('managerId', managerId);
  }
}
