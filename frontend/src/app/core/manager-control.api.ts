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

export interface ManagerControlClientReplySuggestion {
  message: string;
  reasonCode: string;
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
  firstObservedAt?: string | null;
  targetDeadlineAt?: string | null;
  hardDeadlineAt?: string | null;
  slaState?: 'TARGET' | 'LATE' | 'OVERDUE' | 'COMPLETED_TARGET' | 'COMPLETED_LATE' | 'COMPLETED_OVERDUE' | null;
  companyId?: number | null;
  companyTitle?: string | null;
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
  hardBreachCount: number;
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
  firstObservedAt?: string | null;
  targetDeadlineAt?: string | null;
  hardDeadlineAt?: string | null;
  slaState?: 'TARGET' | 'LATE' | 'OVERDUE' | 'COMPLETED_TARGET' | 'COMPLETED_LATE' | 'COMPLETED_OVERDUE' | null;
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
  firstObservedAt?: string | null;
  targetDeadlineAt?: string | null;
  hardDeadlineAt?: string | null;
  slaState?: 'TARGET' | 'LATE' | 'OVERDUE' | 'COMPLETED_TARGET' | 'COMPLETED_LATE' | 'COMPLETED_OVERDUE' | null;
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
  actionTotalCount: number;
  actionCompletedCount: number;
  actionProgressPercent: number;
  actionAutoClosedCount?: number;
  actionRemainingCount?: number;
  actionResolvedCount?: number;
  actionTakenCount?: number;
  actionDeferredCount?: number;
  actionAcknowledgedCount?: number;
  actionOverdueRemainingCount?: number;
  actionRiskRemainingCount?: number;
  actionUnansweredRemainingCount?: number;
  actionOtherRemainingCount?: number;
  leadActionCount: number;
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
  activeWorkSeconds: number;
  averageDailyWorkSeconds: number;
  averageReactionSeconds: number;
  reactionCount: number;
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

export interface ManagerQueueState {
  enabled: boolean;
  date: string;
  state: string;
  openActionCount: number;
  withinTargetCount: number;
  targetMissedCount: number;
  overdueCount: number;
  controlledSeconds: number;
  cleanQueueSeconds: number;
  currentControlledStreakSeconds: number;
  controlTargetHours: number;
  controlPercent: number;
  activeWorkSeconds: number;
  averageDailyWorkSeconds: number;
  averageReactionSeconds: number;
  reactionCount: number;
  observedAt?: string | null;
}

export interface ManagerDailySummaryRow {
  date: string;
  managerId: number;
  managerUserId?: number | null;
  managerName?: string | null;
  score: number;
  grade: string;
  taskTotal: number;
  taskCompleted: number;
  taskOpen: number;
  taskAutoClosed?: number;
  taskResolved?: number;
  taskActionTaken?: number;
  taskDeferred?: number;
  taskAcknowledged?: number;
  taskProgressPercent: number;
  overdueCount: number;
  riskCount: number;
  unansweredCount: number;
  taskOtherOpen?: number;
  firstReplyCount: number;
  firstReplyAverageSeconds: number;
  firstReplyMedianSeconds: number;
  allReplyAverageSeconds: number;
  allReplyMedianSeconds: number;
  allReplyP90Seconds: number;
  replyCount: number;
  repliesInSla: number;
  problemCount: number;
  problemResolvedCount: number;
  problemActionTakenCount: number;
  problemOpenCount: number;
  problemResolutionAverageSeconds: number;
  siteActiveSeconds: number;
  messengerActiveSeconds: number;
  confirmedActiveSeconds: number;
  leadActionCount: number;
  targetSlaCount: number;
  targetSlaMetCount: number;
  hardSlaBreachCount: number;
  controlledSeconds: number;
  cleanQueueSeconds: number;
  dayStars: number;
  dayStatus: string;
  xpEarned: number;
  aggregationStatus: string;
  snapshotAt?: string | null;
}

export interface ManagerDailySummaryPreview {
  date: string;
  message: string;
  managers: ManagerDailySummaryRow[];
}

export interface ManagerSummaryTelegramSendResponse {
  date: string;
  managerCount: number;
  messageCount: number;
  recipient: string;
}

export interface ManagerReportReviewTestStartResponse {
  reviewId: number;
  date: string;
  sourceManagerId: number;
  sourceManagerName: string;
  recipient: string;
  issueCount: number;
}

export interface ManagerReportReviewEvent {
  eventId: number;
  eventType: string;
  actorUserId?: number | null;
  actorRole: string;
  source: string;
  payload?: string | null;
  createdAt: string;
}

export interface ManagerReportReviewIssue {
  issueId: number;
  questionIndex: number;
  title: string;
  question: string;
  status: 'PENDING' | 'ANSWERED' | 'DISPUTE_PENDING' | 'DISPUTED' | 'WITHDRAWN' | 'NEEDS_CONTEXT';
  disputeId?: number | null;
  disputeStatus?: 'DRAFT' | 'OPEN' | 'ACCEPTED' | 'REJECTED' | 'NEEDS_CONTEXT' | null;
  disputeText?: string | null;
  ownerComment?: string | null;
  disputedAt?: string | null;
  resolvedAt?: string | null;
}

export interface ManagerReportReview {
  reviewId: number;
  summaryDate: string;
  managerId: number;
  managerUserId: number;
  managerName: string;
  testMode: boolean;
  testOwnerUserId?: number | null;
  status: 'DELIVERED' | 'READING' | 'QUESTION_PENDING' | 'PLAN_PENDING' | 'COMPLETED' | 'DISPUTE_PENDING' | 'DISPUTED';
  currentQuestionIndex: number;
  issueCount: number;
  questionCount: number;
  answerAttemptCount: number;
  acceptedAnswerCount: number;
  minimumReadSeconds: number;
  readSeconds: number;
  totalReviewSeconds: number;
  quickReview: boolean;
  questionsSource?: string | null;
  aiVerificationPaused: boolean;
  aiUnavailableSeconds: number;
  suspiciousAnswerCount: number;
  answerQuality?: string | null;
  answerQualityReason?: string | null;
  actionPlan?: string | null;
  auditRequired: boolean;
  autoCompleted: boolean;
  disputeText?: string | null;
  deliveredAt?: string | null;
  startedAt?: string | null;
  readingConfirmedAt?: string | null;
  deadlineStartedAt?: string | null;
  completedAt?: string | null;
  disputedAt?: string | null;
  reminderOneSentAt?: string | null;
  reminderThreeSentAt?: string | null;
  restrictedAt?: string | null;
  restrictionReleasedAt?: string | null;
  openDisputeCount: number;
  issues: ManagerReportReviewIssue[];
  events: ManagerReportReviewEvent[];
}

export interface ManagerReportDisputeResolutionPayload {
  action: 'REPORT_INCORRECT' | 'REPORT_CONFIRMED' | 'REPORT_NEEDS_CONTEXT';
  comment?: string | null;
}

@Injectable({ providedIn: 'root' })
export class ManagerControlApi {
  constructor(private readonly http: HttpClient) {}

  today(): Observable<ManagerControlSummary> {
    return this.http.get<ManagerControlSummary>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/today`
    );
  }

  myQueueState(): Observable<ManagerQueueState> {
    return this.http.get<ManagerQueueState>(`${appEnvironment.apiBaseUrl}/api/admin/manager-control/queue-state/me`);
  }

  syncToday(): Observable<ManagerControlSummary> {
    return this.http.post<ManagerControlSummary>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/today/sync`,
      {}
    );
  }

  calculateDailySummary(date?: string): Observable<ManagerDailySummaryRow[]> {
    return this.http.post<ManagerDailySummaryRow[]>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-daily-summary/calculate`,
      {},
      { params: date ? { date } : {} }
    );
  }

  dailySummaryPreview(date?: string): Observable<ManagerDailySummaryPreview> {
    return this.http.get<ManagerDailySummaryPreview>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-daily-summary/preview`,
      { params: date ? { date } : {} }
    );
  }

  sendDailySummaryToTelegram(date?: string): Observable<ManagerSummaryTelegramSendResponse> {
    return this.http.post<ManagerSummaryTelegramSendResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-daily-summary/send-test`,
      {},
      { params: date ? { date } : {} }
    );
  }

  startManagerReportReviewTest(
    date?: string,
    managerId?: number
  ): Observable<ManagerReportReviewTestStartResponse> {
    const params: Record<string, string> = {};
    if (date) params['date'] = date;
    if (managerId) params['managerId'] = String(managerId);
    return this.http.post<ManagerReportReviewTestStartResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-daily-summary/review-test`,
      {},
      { params }
    );
  }

  managerReportReviews(date?: string): Observable<ManagerReportReview[]> {
    return this.http.get<ManagerReportReview[]>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-daily-summary/review-sessions`,
      { params: date ? { date } : {} }
    );
  }

  resolveManagerReportDispute(
    reviewId: number,
    payload: ManagerReportDisputeResolutionPayload
  ): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-daily-summary/review-sessions/${reviewId}/resolve-dispute`,
      payload
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

  suggestClientReply(concreteItemId: number): Observable<ManagerControlClientReplySuggestion> {
    return this.http.get<ManagerControlClientReplySuggestion>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/concrete-items/${concreteItemId}/reply-suggestion`
    );
  }

  markClientMessageAsStaff(concreteItemId: number, comment?: string | null): Observable<ManagerControlConcreteItem> {
    return this.http.post<ManagerControlConcreteItem>(
      `${appEnvironment.apiBaseUrl}/api/admin/manager-control/concrete-items/${concreteItemId}/mark-staff-message`,
      { actionType: 'RESOLVED', comment: comment ?? null, manualWorkerNotification: null }
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
