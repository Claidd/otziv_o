// Manager-control screen models. Wire validation remains in the generated client API interceptor.
export interface ManagerPerformanceScore {
  managerId: number;
  managerUserId?: number | null;
  performanceScore: number;
  loadAdjustedPerformanceScore: number;
  grade: string;
  workloadIndex: number;
  workloadLevel: 'LOW' | 'NORMAL' | 'HIGH' | 'EXTREME' | string;
  workloadTotal: number;
  workloadOrder: number;
  workloadWorker: number;
  actionTotal: number;
  incomingProblemCount: number;
  backlogCount: number;
  avgDailyWorkload: number;
  avgDailyOverdue: number;
  openCount: number;
  handledCount: number;
  problemSlaRate: number;
  clientSlaRate: number;
  overdueRate: number;
  averageOverdueAgeDays: number;
  clientReplyMedianMinutes: number;
  clientReplyP90Minutes: number;
  riskResolutionAvgHours: number;
  reopenRate: number;
  controlAcceptedCount: number;
  controlClosedCount: number;
  fastClickCount: number;
  problemSpeedScore: number;
  clientResponseScore: number;
  overdueControlScore: number;
  specialistRiskScore: number;
  riskQualityScore: number;
  controlDisciplineScore: number;
  stabilityScore: number;
  teamProgressEligibleDays: number;
  teamProgressReached100Days: number;
  teamProgressIncompleteDays: number;
  teamProgressReached100Rate: number;
  teamProgressAveragePercent: number;
  teamProgressMissedWorkerDays: number;
  teamCompletionScore: number;
}

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

export interface ManagerControlClientMessageReconciliation {
  requestedChats: number;
  receivedMessages: number;
  openBefore: number;
  openAfter: number;
  closedItems: number;
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

export interface ManagerControlConcreteItem {
  delivery?: import('@otziv/client-common/delivery-operations').DeliveryOperation | null;
  controlEntityId?: number | null;
  type: 'ORDER' | 'RISK' | string;
  entityId?: number | null;
  companyId?: number | null;
  companyTitle?: string | null;
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
  slaState?: ManagerControlSlaState | null;
}

export type ManagerControlSlaState =
  | 'TARGET'
  | 'LATE'
  | 'OVERDUE'
  | 'COMPLETED_TARGET'
  | 'COMPLETED_LATE'
  | 'COMPLETED_OVERDUE';

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

export interface ManagerControlWorkerExplanationStats {
  workerUserId?: number | null;
  workerName: string;
  requestCount: number;
  unansweredCount: number;
  overdueCount: number;
  hardBreachCount: number;
  averageResponseMinutes: number;
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
  events: unknown[];
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
  slaState?: ManagerControlSlaState | null;
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
  slaState?: ManagerControlSlaState | null;
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
  actionTotalCount?: number;
  actionCompletedCount?: number;
  actionRemainingCount?: number;
  actionProgressPercent?: number;
  actionResolvedCount?: number;
  actionTakenCount?: number;
  actionDeferredCount?: number;
  actionAcknowledgedCount?: number;
  actionAutoClosedCount?: number;
  actionOverdueRemainingCount?: number;
  actionRiskRemainingCount?: number;
  actionUnansweredRemainingCount?: number;
  actionOtherRemainingCount?: number;
  leadActionCount?: number;
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
