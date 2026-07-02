import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import { ManagerPerformanceScore } from './cabinet.api';

export type ManagerControlStatus = 'GREEN' | 'YELLOW' | 'RED';
export type ManagerControlSeverity = 'INFO' | 'WARNING' | 'CRITICAL';
export type ManagerControlGroup = 'ACTION' | 'WORKLOAD';
export type ManagerControlItemStatus = 'OPEN' | 'ACKNOWLEDGED' | 'ACTION_TAKEN' | 'DEFERRED' | 'RESOLVED';
export type ManagerControlActionType = 'ACKNOWLEDGED' | 'ACTION_TAKEN' | 'DEFERRED' | 'RESOLVED';

export interface ManagerControlActionPayload {
  actionType: ManagerControlActionType;
  comment?: string | null;
  manualWorkerNotification?: boolean | null;
}

export interface ManagerControlClientReplyPayload {
  message: string;
}

export interface ManagerControlStagePayload {
  stage: 'MORNING_DONE' | 'FINAL_CHECK';
  comment?: string | null;
}

export interface ManagerControlClosePayload {
  comment?: string | null;
}

export interface ManagerControlCloseResponse {
  closed: boolean;
  status: string;
  qualityScore: number;
  qualityGrade?: string | null;
  riskScore: number;
  fastClickRisk: boolean;
  blockers: string[];
}

export interface ManagerControlEvent {
  eventId: number;
  itemId?: number | null;
  itemLabel?: string | null;
  actorUserId?: number | null;
  eventType: string;
  actionType?: ManagerControlActionType | null;
  comment?: string | null;
  createdAt: string;
}

export interface ManagerControlItemDetail {
  itemId: number;
  itemKey: string;
  itemType: string;
  reasonCode: string;
  reasonLabel: string;
  sectionCode?: string | null;
  label: string;
  targetUrl: string;
  count: number;
  severity: ManagerControlSeverity;
  group: ManagerControlGroup;
  itemStatus: ManagerControlItemStatus;
  actionType?: ManagerControlActionType | null;
  comment?: string | null;
  examples: ManagerControlConcreteItem[];
  hiddenExampleCount: number;
  createdAt: string;
  updatedAt: string;
  resolvedAt?: string | null;
}

export interface ManagerControlConcreteItem {
  controlEntityId?: number | null;
  type: 'ORDER' | 'RISK' | string;
  entityId?: number | null;
  title: string;
  subtitle?: string | null;
  status?: string | null;
  ageDays?: number | null;
  reason?: string | null;
  targetUrl?: string | null;
  orderDetailsId?: string | null;
  chatUrl?: string | null;
  followUpAt?: string | null;
  lastManualTouchAt?: string | null;
  itemStatus?: ManagerControlItemStatus | null;
  actionType?: ManagerControlActionType | null;
  comment?: string | null;
  updatedAt?: string | null;
  resolvedAt?: string | null;
  workerNotificationAttemptedAt?: string | null;
  workerNotificationSentAt?: string | null;
  workerNotificationAcceptedAt?: string | null;
  workerNotificationAcceptedByUserId?: number | null;
  workerNotificationFailureReason?: string | null;
  contactText?: string | null;
  riskResolutionAction?: string | null;
  workerExplanation?: string | null;
  workerExplanationAt?: string | null;
  penaltyPoints?: number | null;
  rollbackStatus?: string | null;
  rollbackMessage?: string | null;
  canRollback?: boolean | null;
  specialistName?: string | null;
}

export interface ManagerControlManagerDetail {
  managerId: number;
  userId?: number | null;
  username: string;
  name: string;
  dailyControlId?: number | null;
  controlDate: string;
  dailyControlStatus: 'NOT_STARTED' | 'IN_PROGRESS' | 'GREEN' | 'YELLOW' | 'RED';
  startedAt?: string | null;
  closedAt?: string | null;
  lastActivityAt?: string | null;
  morningStartedAt?: string | null;
  morningCompletedAt?: string | null;
  dayCheckedAt?: string | null;
  finalCheckedAt?: string | null;
  qualityScore: number;
  qualityGrade?: string | null;
  riskScore: number;
  fastClickRisk: boolean;
  canCloseDay: boolean;
  closeBlockers: string[];
  openItemCount: number;
  handledItemCount: number;
  workerExplanationStats: ManagerControlWorkerExplanationStats[];
  items: ManagerControlItemDetail[];
  events: ManagerControlEvent[];
}

export interface ManagerControlWorkerExplanationStats {
  workerUserId?: number | null;
  workerName: string;
  requestCount: number;
  unansweredCount: number;
  overdueCount: number;
  averageResponseMinutes: number;
}

export interface ManagerControlProblem {
  code: string;
  label: string;
  count: number;
  severity: ManagerControlSeverity;
  group: ManagerControlGroup;
  icon: string;
  targetUrl: string;
  itemId?: number | null;
  itemStatus?: ManagerControlItemStatus | null;
  actionType?: ManagerControlActionType | null;
  comment?: string | null;
}

export interface ManagerControlSection {
  code: string;
  label: string;
  count: number;
  severity: ManagerControlSeverity;
  group: ManagerControlGroup;
  targetUrl: string;
  itemId?: number | null;
  itemStatus?: ManagerControlItemStatus | null;
  actionType?: ManagerControlActionType | null;
  comment?: string | null;
}

export interface ManagerControlOverdueStatus {
  status: string;
  count: number;
  maxDays: number;
  targetUrl: string;
  itemId?: number | null;
  itemStatus?: ManagerControlItemStatus | null;
  actionType?: ManagerControlActionType | null;
  comment?: string | null;
}

export interface ManagerControlManager {
  managerId: number;
  userId?: number | null;
  username: string;
  name: string;
  active: boolean;
  dailyControlId?: number | null;
  dailyControlStatus?: 'NOT_STARTED' | 'IN_PROGRESS' | 'GREEN' | 'YELLOW' | 'RED' | null;
  startedAt?: string | null;
  closedAt?: string | null;
  morningStartedAt?: string | null;
  morningCompletedAt?: string | null;
  dayCheckedAt?: string | null;
  finalCheckedAt?: string | null;
  qualityScore: number;
  qualityGrade?: string | null;
  riskScore: number;
  fastClickRisk: boolean;
  canCloseDay: boolean;
  openItemCount: number;
  handledItemCount: number;
  status: ManagerControlStatus;
  criticalCount: number;
  warningCount: number;
  workloadCount: number;
  totalAttentionCount: number;
  overdueOrderCount: number;
  openRiskCount: number;
  orderAttentionCount: number;
  workerSectionCount: number;
  problems: ManagerControlProblem[];
  workerSections: ManagerControlSection[];
  overdueStatuses: ManagerControlOverdueStatus[];
  workerExplanationStats: ManagerControlWorkerExplanationStats[];
  managerPerformance?: ManagerPerformanceScore | null;
}

export interface ManagerControlSummary {
  date: string;
  generatedAt: string;
  testMode: boolean;
  managerVisible: boolean;
  managersTotal: number;
  greenCount: number;
  yellowCount: number;
  redCount: number;
  criticalTotal: number;
  warningTotal: number;
  workloadTotal: number;
  attentionTotal: number;
  managers: ManagerControlManager[];
}

@Injectable({ providedIn: 'root' })
export class ManagerControlApi {
  constructor(private readonly http: HttpClient) {}

  today(): Observable<ManagerControlSummary> {
    return this.http.get<ManagerControlSummary>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/today`
    );
  }

  syncToday(): Observable<ManagerControlSummary> {
    return this.http.post<ManagerControlSummary>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/today/sync`,
      {}
    );
  }

  actionItem(itemId: number, payload: ManagerControlActionPayload): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/items/${itemId}/action`,
      payload
    );
  }

  actionConcreteItem(concreteItemId: number, payload: ManagerControlActionPayload): Observable<ManagerControlConcreteItem> {
    return this.http.post<ManagerControlConcreteItem>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/concrete-items/${concreteItemId}/action`,
      payload
    );
  }

  sendClientMessage(concreteItemId: number): Observable<ManagerControlConcreteItem> {
    return this.http.post<ManagerControlConcreteItem>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/concrete-items/${concreteItemId}/send-client-message`,
      {}
    );
  }

  replyToClientMessage(concreteItemId: number, payload: ManagerControlClientReplyPayload): Observable<ManagerControlConcreteItem> {
    return this.http.post<ManagerControlConcreteItem>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/concrete-items/${concreteItemId}/reply`,
      payload
    );
  }

  repairConcreteItem(concreteItemId: number): Observable<ManagerControlConcreteItem> {
    return this.http.post<ManagerControlConcreteItem>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/concrete-items/${concreteItemId}/repair`,
      {}
    );
  }

  managerDetails(managerId: number): Observable<ManagerControlManagerDetail> {
    return this.http.get<ManagerControlManagerDetail>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/managers/${managerId}/today`
    );
  }

  syncManagerDetails(managerId: number): Observable<ManagerControlManagerDetail> {
    return this.http.post<ManagerControlManagerDetail>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/managers/${managerId}/today/sync`,
      {}
    );
  }

  acceptControl(controlId: number): Observable<ManagerControlManagerDetail> {
    return this.http.post<ManagerControlManagerDetail>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/controls/${controlId}/accept`,
      {}
    );
  }

  markStage(controlId: number, payload: ManagerControlStagePayload): Observable<ManagerControlManagerDetail> {
    return this.http.post<ManagerControlManagerDetail>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/controls/${controlId}/stage`,
      payload
    );
  }

  closeDay(controlId: number, payload: ManagerControlClosePayload): Observable<ManagerControlCloseResponse> {
    return this.http.post<ManagerControlCloseResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/controls/${controlId}/close`,
      payload
    );
  }
}
