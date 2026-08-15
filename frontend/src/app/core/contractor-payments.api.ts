import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export type ContractorPaymentRole = 'SPECIALIST' | 'MANAGER';
export type ContractorPaymentMode = 'SHADOW' | 'LIVE';
export type ContractorPaymentSystemMode =
  | 'LEGACY'
  | 'COMPLETION_ACTIVE'
  | 'ROUTING_LIVE'
  | 'ROUTING_PAUSED'
  | 'CONFIGURATION_ERROR';
export type ContractorPaymentSourceType =
  | 'PAYMENT_LINK'
  | 'COMMON_INVOICE'
  | 'DIRECT_SETTLEMENT'
  | 'ACTUAL_PAYMENT';
export type ContractorPaymentRecipientType = 'SPECIALIST' | 'MANAGER' | 'OWNER';
export type ContractorRoutingDecisionReason =
  | 'SPECIALIST_SELECTED'
  | 'MANAGER_SELECTED'
  | 'SPECIALIST_NOT_ASSIGNED'
  | 'MANAGER_NOT_ASSIGNED'
  | 'MIXED_SPECIALISTS'
  | 'PRIOR_PAYMENT_EVIDENCE'
  | 'USER_INACTIVE'
  | 'REQUIRED_ROLE_MISSING'
  | 'PROFILE_NOT_FOUND'
  | 'PROFILE_DISABLED'
  | 'LIVE_PROFILE_DISABLED'
  | 'PROFILE_IDENTITY_MISMATCH'
  | 'RECIPIENT_DETAILS_INCOMPLETE'
  | 'INSUFFICIENT_AVAILABLE_BALANCE'
  | 'LIMIT_CONFIGURATION_INVALID'
  | 'LIMIT_ROUTE_INPUT_INVALID'
  | 'LIMIT_SINGLE_INVOICE_EXCEEDED'
  | 'LIMIT_DATABASE_CLOCK_INVALID'
  | 'LIMIT_DAILY_TOTALS_INVALID'
  | 'LIMIT_DAILY_TOTAL_OVERFLOW'
  | 'LIMIT_DAILY_AMOUNT_EXCEEDED'
  | 'LIMIT_DAILY_COUNT_EXCEEDED'
  | 'NO_ELIGIBLE_RECIPIENT'
  | 'LEGACY_UNCLASSIFIED';
export type ContractorPaymentAllocationStatus =
  | 'RESERVED'
  | 'CLIENT_REPORTED'
  | 'PARTIALLY_CONFIRMED'
  | 'CONFIRMED'
  | 'SIMULATED_PAID'
  | 'LATE_PAYMENT_AFTER_RELEASE'
  | 'OWNER_FALLBACK'
  | 'RELEASED_UNPAID'
  | 'EXPIRED'
  | 'CANCELED'
  | 'RETURNED'
  | 'PARTIALLY_RETURNED'
  | 'RETURN_AMOUNT_PENDING';

export interface ContractorPaymentSummary {
  profileId: number;
  userId: number;
  role: ContractorPaymentRole;
  profileEnabled: boolean;
  liveEnabled: boolean;
  accruedMonthKopecks: number;
  accruedTotalKopecks: number;
  reservedKopecks: number;
  clientReportedKopecks: number;
  partiallyConfirmedOutstandingKopecks: number;
  grossConfirmedMonthKopecks: number;
  grossConfirmedTotalKopecks: number;
  returnedMonthKopecks: number;
  returnedTotalKopecks: number;
  closedWithoutPaymentMonthKopecks: number;
  closedWithoutPaymentTotalKopecks: number;
  netReceivedMonthKopecks: number;
  netReceivedTotalKopecks: number;
  availableKopecks: number;
  creditKopecks: number;
  exposureOverrunKopecks: number;
  reportingLive: boolean;
  shadowMode: boolean;
  liveRouting: boolean;
  trackingStartedAt: string;
  currentMonthCoverageComplete: boolean;
}

export interface ContractorPaymentAllocationEvent {
  id: number;
  eventType: string;
  amountKopecks: number;
  routingDecisionReason?: ContractorRoutingDecisionReason | null;
  specialistRejectionReason?: ContractorRoutingDecisionReason | null;
  managerRejectionReason?: ContractorRoutingDecisionReason | null;
  effectiveAt: string;
  reason?: string | null;
  actor: string;
  createdAt: string;
}

export interface ContractorPaymentAllocationJournalItem {
  id: number;
  attemptNo: number;
  mode: ContractorPaymentMode;
  sourceType: ContractorPaymentSourceType;
  sourceId: number;
  orderId?: number | null;
  commonInvoiceId?: number | null;
  recipientType: ContractorPaymentRecipientType;
  recipientProfileId?: number | null;
  recipientUserId?: number | null;
  recipientName?: string | null;
  currentWorkerId?: number | null;
  currentManagerId?: number | null;
  amountKopecks: number;
  confirmedKopecks: number;
  returnedKopecks: number;
  status: ContractorPaymentAllocationStatus;
  routingDecisionReason?: ContractorRoutingDecisionReason | null;
  specialistRejectionReason?: ContractorRoutingDecisionReason | null;
  managerRejectionReason?: ContractorRoutingDecisionReason | null;
  availableBeforeKopecks?: number | null;
  reservedAt?: string | null;
  clientReportedAt?: string | null;
  confirmedAt?: string | null;
  releasedAt?: string | null;
  reconcileAttempts: number;
  reconcileNextRetryAt?: string | null;
  reconcileLastErrorCode?: string | null;
  createdAt: string;
  updatedAt: string;
  reason?: string | null;
  events: ContractorPaymentAllocationEvent[];
}

export interface ContractorPaymentJournalPage {
  content: ContractorPaymentAllocationJournalItem[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface ContractorPaymentJournalFilter {
  userId?: number;
  status?: ContractorPaymentAllocationStatus | '';
  mode?: ContractorPaymentMode | '';
  sourceType?: ContractorPaymentSourceType | '';
  sourceId?: number;
  page?: number;
  size?: number;
}

export interface ContractorReturnAmountRequest {
  returnedTotalKopecks: number;
  effectiveAt?: string;
  reason: string;
}

export interface ContractorPaymentQueueHealthItem {
  activeClaims: number;
  expiredClaims: number;
  retrying: number;
  dueRetries: number;
  oldestRetryAt?: string | null;
  oldestDueRetryAt?: string | null;
  lastErrorCode?: string | null;
}

export interface ContractorPaymentQueueHealth {
  allocationReconciliation: ContractorPaymentQueueHealthItem;
  rewardRepair: ContractorPaymentQueueHealthItem;
  shadowBackfill: ContractorPaymentQueueHealthItem;
  completionRewardRepair: ContractorPaymentQueueHealthItem;
  deferredActiveRecoveryBaseGaps: number;
  observedAt: string;
}

export interface ContractorPaymentSystemStatus {
  mode: ContractorPaymentSystemMode;
  systemEnabled: boolean;
  legacyBehavior: boolean;
  irreversible: boolean;
  routingRequested: boolean;
  completionAccountingEffective: boolean;
  liveRoutingEffective: boolean;
  completionBacklogReady: boolean;
  activationAvailable: boolean;
  activationBlockedReasons: string[];
  attributionStartDate: string | null;
  revision: number;
  liveRoutingMasterEnabled: boolean;
  rewardAttributionMasterEnabled: boolean;
}

export interface ContractorPaymentSystemActivationRequest {
  attributionStartDate: string;
  confirmation: string;
  reason: string;
  expectedRevision: number;
}

export interface ContractorPaymentSystemRoutingRequest {
  enabled: boolean;
  confirmation: string;
  reason: string;
  expectedRevision: number;
}

export interface ContractorLegacyRewardManualGroup {
  orderId: number;
  groupHash: string;
  evidenceCategory: string;
  status: 'PENDING' | 'APPLIED';
  rowCount: number;
  completedOn?: string | null;
  evidenceReference?: string | null;
}

export interface ContractorLegacyRewardReconciliation {
  runId?: number | null;
  startDate?: string | null;
  status: string;
  snapshotHash?: string | null;
  autoOrderCount: number;
  autoRowCount: number;
  autoRemainingRows: number;
  manualOrderCount: number;
  manualRowCount: number;
  manualRemainingOrders: number;
  createdAt?: string | null;
  expiresAt?: string | null;
  manualGroups: ContractorLegacyRewardManualGroup[];
}

export interface ContractorLegacyRewardReconciliationApplyRequest {
  snapshotHash: string;
  reason: string;
  confirmation: string;
}

export interface ContractorLegacyRewardManualResolutionRequest
  extends ContractorLegacyRewardReconciliationApplyRequest {
  groupHash: string;
  completedOn: string;
  evidenceReference: string;
}

export type ContractorDirectSettlementType = 'PAYMENT' | 'REVERSAL';

export interface ContractorDirectSettlement {
  id: number;
  profileId: number;
  userId: number;
  type: ContractorDirectSettlementType;
  mode: ContractorPaymentMode;
  simulated: boolean;
  amountKopecks: number;
  effectiveAt: string;
  reason: string;
  evidenceReference: string;
  idempotencyKey: string;
  actor: string;
  createdAt: string;
  originalSettlementId?: number | null;
  allocationId: number;
}

export interface ContractorDirectSettlementRequest {
  expectedMode: ContractorPaymentMode;
  amountKopecks: number;
  effectiveAt: string;
  reason: string;
  evidenceReference: string;
  idempotencyKey: string;
}

@Injectable({ providedIn: 'root' })
export class ContractorPaymentsApi {
  constructor(private readonly http: HttpClient) {}

  getMySummary(): Observable<ContractorPaymentSummary[]> {
    return this.http.get<ContractorPaymentSummary[]>(
      `${appEnvironment.apiBaseUrl}/api/contractor-payments/me`
    );
  }

  getSystemStatus(): Observable<ContractorPaymentSystemStatus> {
    return this.http.get<ContractorPaymentSystemStatus>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system`
    );
  }

  activateSystem(
    request: ContractorPaymentSystemActivationRequest
  ): Observable<ContractorPaymentSystemStatus> {
    return this.http.post<ContractorPaymentSystemStatus>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/activate`,
      request
    );
  }

  updateSystemRouting(
    request: ContractorPaymentSystemRoutingRequest
  ): Observable<ContractorPaymentSystemStatus> {
    return this.http.post<ContractorPaymentSystemStatus>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/routing`,
      request
    );
  }

  getLegacyRewardReconciliation(): Observable<ContractorLegacyRewardReconciliation> {
    return this.http.get<ContractorLegacyRewardReconciliation>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/legacy-reconciliation`
    );
  }

  prepareLegacyRewardReconciliation(): Observable<ContractorLegacyRewardReconciliation> {
    return this.http.post<ContractorLegacyRewardReconciliation>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/legacy-reconciliation/prepare`,
      {}
    );
  }

  applyLegacyRewardReconciliation(
    runId: number,
    request: ContractorLegacyRewardReconciliationApplyRequest
  ): Observable<ContractorLegacyRewardReconciliation> {
    return this.http.post<ContractorLegacyRewardReconciliation>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/legacy-reconciliation/${runId}/apply`,
      request
    );
  }

  resolveLegacyRewardManualGroup(
    runId: number,
    orderId: number,
    request: ContractorLegacyRewardManualResolutionRequest
  ): Observable<ContractorLegacyRewardReconciliation> {
    return this.http.post<ContractorLegacyRewardReconciliation>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/legacy-reconciliation/${runId}/orders/${orderId}/resolve`,
      request
    );
  }

  getAllocationJournal(filter: ContractorPaymentJournalFilter = {}): Observable<ContractorPaymentJournalPage> {
    let params = new HttpParams();
    if (filter.userId != null) {
      params = params.set('userId', filter.userId);
    }
    if (filter.status) {
      params = params.set('status', filter.status);
    }
    if (filter.mode) {
      params = params.set('mode', filter.mode);
    }
    if (filter.sourceType) {
      params = params.set('sourceType', filter.sourceType);
    }
    if (filter.sourceId != null) {
      params = params.set('sourceId', filter.sourceId);
    }
    if (filter.page != null) {
      params = params.set('page', filter.page);
    }
    if (filter.size != null) {
      params = params.set('size', filter.size);
    }

    return this.http.get<ContractorPaymentJournalPage>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payment-allocations`,
      { params }
    );
  }

  getQueueHealth(): Observable<ContractorPaymentQueueHealth> {
    return this.http.get<ContractorPaymentQueueHealth>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payment-allocations/health`
    );
  }

  getDirectSettlements(userId: number, profileId: number): Observable<ContractorDirectSettlement[]> {
    return this.http.get<ContractorDirectSettlement[]>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/direct-settlements`
    );
  }

  createDirectSettlement(
    userId: number,
    profileId: number,
    request: ContractorDirectSettlementRequest
  ): Observable<ContractorDirectSettlement> {
    return this.http.post<ContractorDirectSettlement>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/direct-settlements`,
      request
    );
  }

  reverseDirectSettlement(
    userId: number,
    profileId: number,
    originalSettlementId: number,
    request: ContractorDirectSettlementRequest
  ): Observable<ContractorDirectSettlement> {
    return this.http.post<ContractorDirectSettlement>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/direct-settlements/${originalSettlementId}/reversals`,
      request
    );
  }

  recordReturnedAmount(allocationId: number, request: ContractorReturnAmountRequest): Observable<void> {
    return this.http.put<void>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payment-allocations/${allocationId}/returned-amount`,
      request
    );
  }
}
