import type { ManagerPerformanceScore, ManagerControlStatus, ManagerControlSeverity, ManagerControlGroup, ManagerControlItemStatus, ManagerControlActionType, ManagerControlActionPayload, ManagerControlClientReplyPayload, ManagerControlClientMessageReconciliation, ManagerControlStagePayload, ManagerControlClosePayload, ManagerControlCloseResponse, ManagerControlConcreteItem, ManagerControlSlaState, ManagerControlItemDetail, ManagerControlWorkerExplanationStats, ManagerControlManagerDetail, ManagerControlProblem, ManagerControlSection, ManagerControlOverdueStatus, ManagerControlManager, ManagerControlSummary } from './manager-control.models';
export type { ManagerPerformanceScore, ManagerControlStatus, ManagerControlSeverity, ManagerControlGroup, ManagerControlItemStatus, ManagerControlActionType, ManagerControlActionPayload, ManagerControlClientReplyPayload, ManagerControlClientMessageReconciliation, ManagerControlStagePayload, ManagerControlClosePayload, ManagerControlCloseResponse, ManagerControlConcreteItem, ManagerControlSlaState, ManagerControlItemDetail, ManagerControlWorkerExplanationStats, ManagerControlManagerDetail, ManagerControlProblem, ManagerControlSection, ManagerControlOverdueStatus, ManagerControlManager, ManagerControlSummary } from './manager-control.models';
import type { PublicPaymentInitResponse, PublicCommonInvoiceOrder, PublicCommonInvoice, PublicSbpBank } from '@otziv/client-common/public-payments';
export type { PublicPaymentInitResponse, PublicCommonInvoiceOrder, PublicCommonInvoice, PublicSbpBank } from '@otziv/client-common/public-payments';
import { ManualPaymentTasksApi } from './manual-payment-tasks.api';
import { ManagerBoardApi } from './manager-board.api';
import { ManagerControlApi } from './manager-control.api';
import { ManagerReportsApi } from './manager-reports.api';
import { ManagerWorkerRiskApi } from './manager-worker-risk.api';
import { ManagerArchiveApi } from './manager-archive.api';
import { ManagerCompanyActionsApi } from './manager-company-actions.api';
import { ManagerOrdersApi } from './manager-orders.api';
import { ManagerManualPaymentsApi } from './manager-manual-payments.api';
import { ManagerReviewActionsApi } from './manager-review-actions.api';
import { ManagerReviewTasksApi } from './manager-review-tasks.api';
import { PublicPaymentsApi } from './public-payments.api';
import { PaymentAdministrationApi } from './payment-administration.api';
import { PaymentConfigurationApi } from './payment-configuration.api';
import { WorkerApi } from './worker.api';
import { CompaniesApi } from './companies.api';
import { CommonBillingApi } from './common-billing.api';
import { DictionariesApi } from './dictionaries.api';
import { OrderReviewsApi } from './order-reviews.api';
import { OrderPaymentApi } from './order-payment.api';
import { OrderCompanyReportApi } from './order-company-report.api';
import { decodePublicPaymentLink, decodeCommonBillingAccounts } from '@otziv/client-common/billing-payments';
import type { CommonBillingCompanyResponse, CommonInvoiceSummaryResponse, CommonBillingAccountResponse, InvoicePaymentMode, PublicPaymentLink } from '@otziv/client-common/billing-payments';
export type { CommonBillingCompanyResponse, CommonInvoiceSummaryResponse, CommonBillingAccountResponse, InvoicePaymentMode, PublicPaymentLink } from '@otziv/client-common/billing-payments';
import { ManagerCompanyEditorApi } from './manager-company-editor.api';
import { ManagerCompanyBillingApi } from './manager-company-billing.api';
import type { OrderEditPayload } from '@otziv/client-common/order-editor';
export type { OrderEditPayload } from '@otziv/client-common/order-editor';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { ManagerOrderEditorApi } from './manager-order-editor.api';
import { forkJoin, map, Observable } from 'rxjs';
import {
  botBrowserApiPaths,
  botBrowserSessionClosePath,
  botBrowserSessionHeartbeatPath
} from './bot-browser-api-paths';
import { mobileEnvironment } from './mobile-environment';

const OPAQUE_REVIEW_CAPABILITY = /^rc1_[A-Za-z0-9_-]{43}$/;

export interface CurrentUser {
  authenticated: boolean;
  localUserId?: number | null;
  active?: boolean | null;
  authEpoch?: number | null;
  name: string;
  authorities: string[];
  preferredUsername?: string;
  email?: string;
  realmRoles?: string[];
}

export type TbankRuntimeMode = 'TEST' | 'LIVE';
export type PaymentInstructionSource = 'MANAGER_TEXT' | 'BANK_LINK' | 'TBANK_LINK' | 'TOCHKA_LINK';
export type TbankPaymentPageMode = 'SBP_PRIMARY' | 'BANK_PRIMARY' | 'SBP_PAY_ONLY' | 'SBP_ONLY' | 'BANK_ONLY';
export type PaymentPolicy = 'T_BANK_ONLY' | 'MANUAL_UNTIL_LIMIT_THEN_TBANK';
export type PaymentMethod = 'BANK_FORM' | 'SBP_QR' | 'MANUAL_MOBILE_BANK' | 'MANUAL_EXTERNAL_LINK' | string;
export type PaymentReceiptStatus = 'PENDING' | 'MARKED';
export type ManualPaymentSource = 'PROFILE_MONTHLY_LIMIT' | 'MANUAL_TASK';
export type ManualPaymentType = 'MOBILE_BANK' | 'EXTERNAL_LINK';
export type ManualPaymentTaskStatus = 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'CANCELED';
export type ManualPaymentTaskAccountingTargetKind =
  | 'UNRESOLVED'
  | 'EXTERNAL_TASK'
  | 'OWNER'
  | 'SPECIALIST'
  | 'MANAGER';

export interface ManualPaymentTaskAccountingTargetOption {
  key: string;
  kind: ManualPaymentTaskAccountingTargetKind;
  profileId?: number | null;
  userId?: number | null;
  role?: 'SPECIALIST' | 'MANAGER' | null;
  label: string;
  enabled: boolean;
  currentAvailableKopecks?: number | null;
  projectedOverrunKopecks?: number | null;
  overrunAcknowledgementRequired: boolean;
  recommended?: boolean | null;
}
export type PaymentLinkListSource = 'LIVE' | 'ARCHIVE';

export interface AdminPaymentLinkResponse {
  id: number;
  token: string;
  publicUrl: string;
  orderId?: number | null;
  companyTitle: string;
  filialTitle: string;
  description: string;
  amount: number;
  amountKopecks: number;
  reservedAmountKopecks?: number | null;
  confirmedAmountKopecks?: number | null;
  status: string;
  paymentMethod?: PaymentMethod;
  paymentProfileCode?: string | null;
  paymentProfileName?: string | null;
  manualSource?: ManualPaymentSource | string | null;
  manualTaskId?: number | null;
  manualTaskTitle?: string | null;
  tbankTerminalKey?: string | null;
  tbankPaymentId?: string | null;
  tbankOrderId?: string | null;
  payerEmail?: string | null;
  paymentUrl?: string | null;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  specialistName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  manualComment?: string | null;
  manualReportedAt?: string | null;
  manualConfirmedBy?: string | null;
  manualConfirmedAt?: string | null;
  receiptStatus?: PaymentReceiptStatus | string | null;
  paymentSuccessNotifiedAt?: string | null;
  paymentSuccessNotificationError?: string | null;
  clientChatPlatform?: string | null;
  clientChatReady?: boolean | null;
  clientChatWarning?: string | null;
  lastError?: string | null;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  initiatedAt?: string | null;
  paidAt?: string | null;
  sbpQrCreatedAt?: string | null;
  archived?: boolean;
  archivedAt?: string | null;
  archiveReason?: string | null;
  refundable: boolean;
}

export interface AdminPaymentLinkSummaryResponse {
  totalElements: number;
  totalAmount: number;
  totalAmountKopecks: number;
  paid: number;
  manualPending: number;
  confirmed: number;
  notificationsSent: number;
  notificationErrors: number;
  refundable: number;
  refunded: number;
  rejected: number;
}

export interface AdminPaymentLinksPageResponse {
  items: AdminPaymentLinkResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  source: PaymentLinkListSource | string;
  summary: AdminPaymentLinkSummaryResponse;
}

export interface PaymentLinkArchiveRunResponse {
  eligible: number;
  archived: number;
  deleted: number;
  dryRun: boolean;
  reason: string;
}

export interface PaymentProfileResponse {
  id: number;
  code: string;
  provider: string;
  name: string;
  terminalKey: string;
  passwordEnvKey?: string | null;
  enabled: boolean;
  defaultProfile: boolean;
  testMode: boolean;
  hasPassword: boolean;
  paymentPolicy: PaymentPolicy;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentUrlConfigured?: boolean;
  manualPaymentButtonLabel?: string | null;
  manualComment?: string | null;
  manualMonthlySoftLimitKopecks?: number | null;
  manualMonthlyHardLimitKopecks?: number | null;
  manualMonthlyUsedKopecks: number;
  manualMonthlyConfirmedKopecks?: number;
  manualMonthlyPendingAmountKopecks?: number;
  manualMonthlyPendingCount: number;
}

export interface ManagerPaymentProfileResponse {
  managerId: number;
  managerTitle: string;
  username: string;
  paymentProfileId?: number | null;
  paymentProfileName?: string | null;
}

export interface TbankPaymentProfilesResponse {
  profiles: PaymentProfileResponse[];
  managers: ManagerPaymentProfileResponse[];
}

export interface ManagerPaymentProfileAssignmentRequest {
  managerId: number;
  paymentProfileId?: number | null;
}

export interface PaymentProfilePolicyRequest {
  profileId: number;
  paymentPolicy: PaymentPolicy;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  manualComment?: string | null;
  manualMonthlySoftLimitKopecks?: number | null;
  manualMonthlyHardLimitKopecks?: number | null;
  manualPaymentUrlReplacementConfirmed?: boolean;
}

export interface ManualPaymentTaskResponse {
  id: number;
  managerId?: number | null;
  managerTitle: string;
  username: string;
  paymentProfileId?: number | null;
  paymentProfileName: string;
  status: ManualPaymentTaskStatus | string;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  targetAmountKopecks: number;
  reservedAmountKopecks: number;
  confirmedAmountKopecks: number;
  pendingAmountKopecks: number;
  remainingAmountKopecks: number;
  pendingCount: number;
  comment?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  completedAt?: string | null;
  routable: boolean;
  accountingTargetKind?: ManualPaymentTaskAccountingTargetKind | null;
  accountingTargetProfileId?: number | null;
  accountingTargetLabel?: string | null;
  accountingTargetResolved?: boolean;
  generation?: number;
  rowVersion?: number | null;
  targetCurrentAvailableKopecks?: number | null;
  targetProjectedOverrunKopecks?: number | null;
  accountingTargetOverrunAcknowledged?: boolean;
  accountingTargetOverrunAcknowledgedAt?: string | null;
  accountingTargetOverrunAcknowledgedBy?: string | null;
}

export interface ManualPaymentRecipientMonthlySummaryItem {
  manualRecipientName: string;
  manualPhone?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  paymentProfileName?: string | null;
  manualSource?: ManualPaymentSource | string | null;
  manualPaymentType?: ManualPaymentType | string | null;
  accountingRecipientKey?: string | null;
  accountingRecipientLabel?: string | null;
  accountingDestinationKind?: 'OWNER' | 'CONTRACTOR_PROFILE' | 'MANUAL_PAYMENT_TASK' | string | null;
  accountingRecipientType?: 'SPECIALIST' | 'MANAGER' | 'OWNER' | string | null;
  accountingRecipientProfileId?: number | null;
  manualPaymentTaskId?: number | null;
  manualPaymentTaskGeneration?: number | null;
  manualPaymentTaskTargetKind?: ManualPaymentTaskAccountingTargetKind | string | null;
  attributionKnown?: boolean;
  paymentCount: number;
  amountKopecks: number;
  firstConfirmedAt?: string | null;
  lastConfirmedAt?: string | null;
}

export interface ManualPaymentRecipientMonthlySummaryResponse {
  month: string;
  from: string;
  toExclusive: string;
  totalRecipients: number;
  totalPayments: number;
  totalAmountKopecks: number;
  totalAmount: number;
  items: ManualPaymentRecipientMonthlySummaryItem[];
}

export interface CreateManualPaymentTaskRequest {
  operationKey: string;
  managerId?: number | null;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  targetAmountKopecks: number;
  comment?: string | null;
  accountingTargetKind: ManualPaymentTaskAccountingTargetKind;
  accountingTargetProfileId?: number | null;
  accountingTargetOverrunAcknowledged: boolean;
}

export interface UpdateManualPaymentTaskRequest {
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  targetAmountKopecks: number;
  comment?: string | null;
  manualPaymentUrlReplacementConfirmed?: boolean;
  accountingTargetKind: ManualPaymentTaskAccountingTargetKind;
  accountingTargetProfileId?: number | null;
  accountingTargetOverrunAcknowledged: boolean;
  expectedGeneration: number | null;
}

export interface ManagerPaymentLinkResponse {
  token: string;
  url: string;
  orderId?: number | null;
  amount: number;
  amountKopecks: number;
  status: string;
  paymentMethod?: PaymentMethod;
  expiresAt: string;
  instructionText?: string | null;
  copyText: string;
}

export type PaymentRouteChangeTarget = 'EMPLOYEE_REQUISITES' | 'OWNER_TBANK' | 'OWNER_PAPER_INVOICE';

export interface PaymentRouteChangeContext {
  paymentLinkId: number | null;
  currentRoute: string;
  currentRecipient: string;
  status: string;
  canChange: boolean;
  blockReason: string;
  configuredMode?: 'AUTO_ROUTING' | PaymentRouteChangeTarget | string;
  paperInvoiceIssued?: boolean;
  expectedTargetPaymentProfileId?: number | null;
}

export interface PaymentRouteChangeRequest {
  expectedPaymentLinkId: number | null;
  target: PaymentRouteChangeTarget;
  confirmedUnpaid: boolean;
  expectedTargetPaymentProfileId?: number | null;
}

export interface PaymentRouteChangeResponse {
  previousPaymentLinkId: number | null;
  paymentLinkId: number | null;
  target: PaymentRouteChangeTarget;
  clientNotificationScheduled: boolean;
}

export interface TbankPaymentStatus {
  enabled: boolean;
  paymentLinksEnabled: boolean;
  managerUiEnabled: boolean;
  applyConfirmedPayments: boolean;
  hasCredentials: boolean;
  testMode: boolean;
  runtimeMode: TbankRuntimeMode;
  baseUrl: string;
  publicBaseUrl: string;
  notificationUrl: string;
  successUrl: string;
  failUrl: string;
}

export interface RegisterClientRequest {
  username: string;
  email: string;
  fio?: string;
  phoneNumber?: string;
  password: string;
  matchingPassword: string;
}

export interface PerformerCityOption {
  id: number;
  cityTitle: string;
}

export interface RegisterPerformerRequest {
  phoneNumber: string;
  cityId: number;
  gender?: 'MALE' | 'FEMALE' | 'OTHER' | 'NOT_SPECIFIED';
  fio: string;
  telegramUsername?: string;
  registeredSource?: string;
  personalDataConsentAccepted: boolean;
  rulesConsentAccepted: boolean;
  honestReviewConsentAccepted: boolean;
}

export interface RegisterPerformerResponse {
  userId: number;
  performerId: number;
  username: string;
  temporaryPassword?: string | null;
  telegramLinkToken?: string | null;
  telegramLinkUrl: string;
  status: string;
  registrationExpiresAt: string;
  requiresAdminApproval: boolean;
}

export interface LegacyUserMigrationRequest {
  username: string;
  password: string;
}

export interface ProvisionedUserResponse {
  id: number;
  keycloakId: string;
  username: string;
  email?: string;
  fio?: string;
  phoneNumber?: string;
  coefficient?: number;
  active: boolean;
  roles: string[];
}

export interface WhatsAppClientStatus {
  clientId: string;
  configured: boolean;
  ready: boolean;
  qrDataUrl?: string | null;
  message?: string | null;
  lastError?: string | null;
  lastQrAt?: string | null;
  lastReadyAt?: string | null;
}

export interface TbankRuntimeSettings {
  runtimeMode: TbankRuntimeMode;
  testMode: boolean;
  tbankEnabled: boolean;
  paymentLinksEnabled: boolean;
  managerUiEnabled: boolean;
  applyConfirmedPayments: boolean;
  paymentInstructionSource: PaymentInstructionSource;
  clientTbankEnabled: boolean;
  paymentPageMode: TbankPaymentPageMode;
  tpayEnabled: boolean;
  sberpayEnabled: boolean;
  mirpayEnabled: boolean;
}

export interface UpdateTbankRuntimeSettingsRequest {
  runtimeMode?: TbankRuntimeMode;
  tbankEnabled?: boolean;
  paymentLinksEnabled?: boolean;
  managerUiEnabled?: boolean;
  applyConfirmedPayments?: boolean;
  paymentInstructionSource?: PaymentInstructionSource;
  paymentPageMode?: TbankPaymentPageMode;
  tpayEnabled?: boolean;
  sberpayEnabled?: boolean;
  mirpayEnabled?: boolean;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  first?: boolean;
  last?: boolean;
  number?: number;
  pageNumber?: number;
  size?: number;
  pageSize?: number;
}

export type ManagerBoardSection = 'companies' | 'orders';
export type ArchiveOrderMode = 'all' | 'archive' | 'paid';

export interface MetricItem {
  label: string;
  value: number;
  delta?: number;
  icon?: string;
  tone?: 'blue' | 'green' | 'teal' | 'yellow' | 'pink' | 'gray';
  section?: string;
  status?: string;
}

export interface ManagerOption {
  id: number;
  label: string;
}

export interface ArchiveOrderListItem {
  id: number;
  companyId?: number | null;
  orderDetailsId?: string | null;
  companyTitle: string;
  companyTelephone: string;
  companyUrlChat?: string;
  companyCity: string;
  filialTitle: string;
  filialUrl?: string;
  status: string;
  sum?: number;
  amount?: number;
  counter?: number;
  waitingForClient: boolean;
  managerName: string;
  workerName: string;
  created?: string;
  changed?: string;
  payDay?: string;
  archivedAt?: string;
  archiveReason: string;
  archiveBatchId?: number;
  restoredAt?: string;
  restoredBy: string;
  restoreBatchId?: number;
  orderDetailsCount: number;
  reviewsCount: number;
  paymentCheckSum?: number;
  zpSum?: number;
  source?: 'archive' | 'live';
}

export interface ArchiveOrderDetailItem {
  id: string;
  productId?: number | null;
  productTitle: string;
  amount?: number;
  price?: number;
  comment: string;
  publishedDate?: string;
}

export interface ArchiveReviewItem {
  id: number;
  orderDetailsId?: string | null;
  text: string;
  answer: string;
  category: string;
  subCategory: string;
  botId?: number | null;
  botFio: string;
  productId?: number | null;
  productTitle: string;
  workerFio: string;
  filialTitle: string;
  created?: string;
  changed?: string;
  publishedDate?: string;
  publish: boolean;
  vigul: boolean;
  price?: number;
  url: string;
}

export interface ArchiveBadReviewTaskItem {
  id: number;
  sourceReviewId?: number | null;
  status: string;
  originalRating?: number | null;
  targetRating?: number | null;
  price?: number;
  scheduledDate?: string;
  completedDate?: string;
  workerFio: string;
  botFio: string;
  comment: string;
}

export interface ArchiveNextOrderRequestItem {
  id: number;
  companyId?: number | null;
  filialId?: number | null;
  sourceOrderId?: number | null;
  createdOrderId?: number | null;
  status: string;
  attempts: number;
  errorMessage: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ArchiveZpItem {
  id: number;
  fio: string;
  sum?: number;
  userId?: number | null;
  professionId?: number | null;
  orderId?: number | null;
  amount: number;
  created?: string;
  active: boolean;
}

export interface ArchivePaymentCheckItem {
  id: number;
  title: string;
  sum?: number;
  companyId?: number | null;
  orderId?: number | null;
  managerId?: number | null;
  workerId?: number | null;
  created?: string;
  active: boolean;
}

export interface ArchiveOrderDetailsPayload {
  order: ArchiveOrderListItem;
  orderComments: string;
  details: ArchiveOrderDetailItem[];
  reviews: ArchiveReviewItem[];
  badReviewTasks: ArchiveBadReviewTaskItem[];
  nextOrderRequests: ArchiveNextOrderRequestItem[];
  zp: ArchiveZpItem[];
  paymentChecks: ArchivePaymentCheckItem[];
}

export interface ArchiveCandidateCounts {
  orders: number;
  orderDetails: number;
  reviews: number;
  badReviewTasks: number;
  nextOrderRequests: number;
  zp: number;
  paymentCheck: number;
}

export interface ArchiveRestoreResult {
  batchId: number;
  orderId: number;
  restoredAt: string;
  restoredBy: string;
  targetStatus: string;
  selected: ArchiveCandidateCounts;
  restored: ArchiveCandidateCounts;
  message: string;
}

export interface CompanyFilialEditItem {
  id: number;
  title: string;
  url: string;
  cityId?: number | null;
  city: string;
  archived?: boolean;
  archivedAt?: string | null;
}

export interface FilialDeletionPreview {
  filialId: number;
  orderCount: number;
  reviewCount: number;
  willArchive: boolean;
}

export interface CompanyEditPayload {
  id: number;
  title: string;
  urlChat: string;
  groupId?: string | null;
  telegramGroupChatId?: number | null;
  maxGroupChatId?: number | null;
  urlSite: string;
  telephone: string;
  city: string;
  email: string;
  commentsCompany: string;
  active: boolean;
  createDate: string;
  updateStatus: string;
  dateNewTry: string;
  status?: ManagerOption | null;
  category?: ManagerOption | null;
  subCategory?: ManagerOption | null;
  manager?: ManagerOption | null;
  categories: ManagerOption[];
  subCategories: ManagerOption[];
  statuses: ManagerOption[];
  managers: ManagerOption[];
  workers: ManagerOption[];
  currentWorkers: ManagerOption[];
  filials: CompanyFilialEditItem[];
  cities: ManagerOption[];
  canChangeManager: boolean;
}

export interface CompanyChatBindingRepair {
  companyId: number;
  companyTitle: string;
  platform: 'whatsapp' | 'telegram' | 'max' | 'unknown';
  urlChat: string;
  groupId?: string | null;
  telegramGroupChatId?: number | null;
  maxGroupChatId?: number | null;
  telegramBotInviteUrl?: string;
  maxBotInviteUrl?: string;
  repaired: boolean;
  launchUrl: string;
  message: string;
}

export interface CompanyUpdateRequest {
  title: string;
  urlChat: string;
  urlSite: string;
  telephone: string;
  city: string;
  email: string;
  categoryId: number | null;
  subCategoryId: number | null;
  statusId: number | null;
  managerId: number | null;
  commentsCompany: string;
  active: boolean;
  newWorkerId: number | null;
  newFilialCityId: number | null;
  newFilialTitle: string;
  newFilialUrl: string;
}

export interface CompanyFilialUpdateRequest {
  title: string;
  url: string;
  cityId: number | null;
}

export interface OrderItem {
  id: number;
  companyId?: number;
  orderDetailsId?: string;
  companyTitle: string;
  filialTitle?: string;
  filialUrl?: string;
  filialCity?: string;
  status: string;
  sum?: number;
  totalSumWithBadReviews?: number;
  badReviewTasksSum?: number;
  badReviewTasksTotal?: number;
  badReviewTasksPending?: number;
  badReviewTasksDone?: number;
  badReviewTasksCanceled?: number;
  managerPayText?: string;
  amount?: number;
  counter?: number;
  companyUrlChat?: string;
  companyTelephone?: string;
  orderComments?: string;
  companyComments?: string;
  waitingForClient?: boolean;
  firstOrderForCompany?: boolean;
  workerUserFio?: string;
  categoryTitle?: string;
  subCategoryTitle?: string;
  created?: string;
  changed?: string;
  payDay?: string;
  dayToChangeStatusAgo?: number;
  groupId?: string;
  telegramGroupChatId?: number | null;
  maxGroupChatId?: number | null;
  telegramBotInviteUrl?: string;
  maxBotInviteUrl?: string;
  commonInvoice?: boolean;
  commonInvoiceId?: number | null;
  commonBillingAccountId?: number | null;
  commonInvoiceStatus?: string | null;
  commonInvoicePublicUrl?: string | null;
  commonInvoiceTotalOrders?: number | null;
  commonInvoiceReadyOrders?: number | null;
  commonInvoicePaidOrders?: number | null;
  commonInvoiceAmount?: number | null;
  commonInvoicePaid?: number | null;
  commonInvoiceRemaining?: number | null;
  commonInvoiceSentAt?: string | null;
  commonInvoiceLastReminderAt?: string | null;
  commonInvoiceNextReminderAt?: string | null;
  commonInvoiceLastError?: string | null;
  clientMessageStatus?: ClientMessageStatus | null;
}

export interface ClientMessageStatus {
  state: 'sent' | 'scheduled' | 'waiting_recovery' | 'failed' | 'manual_control' | 'none';
  label: string;
  tone: 'success' | 'wait' | 'danger' | 'muted';
  scenario?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  lastAttemptAt?: string | null;
  lastSuccessAt?: string | null;
  nextAttemptAt?: string | null;
  consecutiveFailures: number;
  sentCount: number;
}



export type CommonInvoicePaymentRouteChangeTarget =
  | 'EMPLOYEE_REQUISITES'
  | 'OWNER_TBANK'
  | 'OWNER_BANK_REISSUE';

export interface CommonInvoicePaymentRouteChangeContextResponse {
  currentRoute: string;
  currentTarget: CommonInvoicePaymentRouteChangeTarget;
  currentRecipient: string;
  status: string;
  canChange: boolean;
  blockReason: string;
  paymentEvidenceToken: string;
  currentPaymentProfileId?: number | null;
  ownerBankTargetPaymentProfileId?: number | null;
  ownerBankTargetPaymentProfileName?: string | null;
  ownerBankTargetProvider?: string | null;
  canReissueOwnerBank: boolean;
  ownerBankReissueBlockReason: string;
}

export interface ContractorCommonSourceConfirmationRequest {
  recipientStatementChecked: true;
  paymentReceived: true;
  confirmedTotalKopecks: number;
  effectiveAt?: string | null;
  reason: string;
}

export interface CommonInvoiceArchivePreviewResponse {
  invoiceId: number;
  allowed: boolean;
  totalOrders: number;
  blockers: string[];
}



export interface CommonBillingAccountRequest {
  name: string;
  enabled?: boolean;
  autoRepeatOrders?: boolean;
  managerId?: number | null;
  invoiceCompanyId?: number | null;
  companyIds?: number[];
}

export interface CommonInvoiceOrderResponse {
  orderId: number;
  companyId: number;
  companyTitle: string;
  filialTitle?: string | null;
  orderStatus: string;
  originalOrderStatus?: string | null;
  amount: number;
  amountKopecks: number;
  ready: boolean;
  paid: boolean;
  unpaid: boolean;
  detachable: boolean;
  paidAt?: string | null;
  paymentMethod?: 'TBANK' | 'MANUAL' | 'MIXED' | 'MANUAL_LEGACY' | 'OWNER_PAPER_INVOICE' | null;
  paidBy?: string | null;
  paymentComment?: string | null;
  paymentReceiptUrl?: string | null;
}

export interface CommonInvoiceNextCycleResponse {
  sourceOrderId: number;
  orderId: number;
  invoiceId?: number | null;
  invoiceStatus?: string | null;
  companyTitle: string;
  filialTitle?: string | null;
  orderStatus: string;
}

export interface CommonInvoiceDetailsResponse {
  delivery?: import('@otziv/client-common/billing-payments').DeliveryOperation | null;
  summary: CommonInvoiceSummaryResponse;
  orders: CommonInvoiceOrderResponse[];
  orderCards: OrderItem[];
  nextCycleOrders?: CommonInvoiceNextCycleResponse[];
  paymentRefs?: Array<{
    id: number;
    status: string;
    orderId?: string | null;
    paymentId?: string | null;
    amountKopecks?: number | null;
    reason?: string | null;
    terminalLabel?: string | null;
    terminalKey?: string | null;
  }>;
  paymentEvidenceToken?: string | null;
}

export interface OrderNotesResponse {
  orderComments: string;
  companyComments: string;
}


export interface OrderUpdateRequest {
  filialId: number | null;
  workerId: number | null;
  managerId: number | null;
  counter: number;
  orderComments: string;
  commentsCompany: string;
  complete: boolean;
}

export interface OrderProductOption {
  id: number;
  label: string;
  price?: number;
  photo: boolean;
}

export interface CompanyOrderCreatePayload {
  companyId: number;
  companyTitle: string;
  products: OrderProductOption[];
  amounts: number[];
  workers: ManagerOption[];
  filials: CompanyFilialEditItem[];
  defaultProductId?: number | null;
  defaultAmount?: number | null;
  defaultWorkerId?: number | null;
  defaultFilialId?: number | null;
}

export interface CompanyOrderCreateRequest {
  productId: number | null;
  amount: number;
  workerId: number | null;
  filialId: number | null;
}

export interface CompanyOrderCreateResult {
  companyId: number;
  companyTitle: string;
  productId: number;
  productTitle: string;
  amount: number;
}

export interface OrderReviewItem {
  id: number;
  companyId: number;
  orderDetailsId?: string;
  orderId: number;
  text: string;
  answer: string;
  category: string;
  subCategory: string;
  botId?: number | null;
  botFio: string;
  botLoginPresent: boolean;
  botPasswordPresent: boolean;
  botCounter: number;
  botActive?: boolean;
  companyTitle: string;
  commentCompany: string;
  orderComments: string;
  filialCity: string;
  filialTitle: string;
  filialUrl: string;
  productId?: number | null;
  productTitle: string;
  productPhoto: boolean;
  workerFio: string;
  created: string;
  changed: string;
  publishedDate: string;
  publish: boolean;
  vigul: boolean;
  comment: string;
  price?: number;
  url: string;
  urlPhoto: string;
}

export interface BadReviewSummary {
  total: number;
  pending: number;
  done: number;
  canceled: number;
  doneSum?: number;
  pendingSum?: number;
  totalSumWithBadReviews?: number;
}

export interface BadReviewTaskItem {
  id: number;
  sourceReviewId?: number | null;
  status: string;
  statusCode: string;
  originalRating?: number | null;
  targetRating?: number | null;
  price?: number;
  scheduledDate?: string;
  completedDate?: string;
  workerFio?: string;
  botId?: number | null;
  botFio?: string;
  botLoginPresent: boolean;
  botPasswordPresent: boolean;
  taskText?: string;
  comment?: string;
}

export interface BadReviewTaskUpdateRequest {
  taskText: string;
  scheduledDate: string | null;
}

export interface ReviewRecoveryBatchItem {
  id: number;
  status: string;
  statusCode: string;
  completedAt?: string;
  clientNotifiedAt?: string;
}

export interface ReviewRecoveryTaskItem {
  id: number;
  batchId?: number | null;
  sourceReviewId?: number | null;
  status: string;
  statusCode: string;
  recoveryText: string;
  recoveryAnswer?: string;
  scheduledDate?: string;
  completedDate?: string;
  workerFio?: string;
  botId?: number | null;
  botFio?: string;
  botLoginPresent: boolean;
  botPasswordPresent: boolean;
  batch?: ReviewRecoveryBatchItem | null;
}

export interface ReviewRecoveryTaskUpdateRequest {
  recoveryText: string;
  recoveryAnswer?: string | null;
  scheduledDate?: string | null;
}

export interface WorkerCredentialPreparation {
  scope: string;
  reviewId: number;
  botId?: number | null;
  loginCopiedAt?: string | null;
  passwordCopiedAt?: string | null;
  updatedAt?: string | null;
  loginCopied?: boolean;
  passwordCopied?: boolean;
  ready?: boolean;
  remainingSeconds?: number;
  waitSeconds?: number;
}

export interface ManualPaymentConfirmationRequest {
  comment: string;
  receiptUrl: string;
}

export type ManualCardPaymentRecipientType = 'OWNER' | 'MANAGER' | 'SPECIALIST';
export type ManualPaymentCashDestinationKind = 'OWNER' | 'CONTRACTOR_PROFILE' | 'MANUAL_PAYMENT_TASK';
export type ManualPaymentTaskTargetKind = 'EXTERNAL_TASK' | 'OWNER' | 'SPECIALIST' | 'MANAGER';

export interface ManualCardPaymentRecipientOption {
  key?: string | null;
  cashDestinationKind?: ManualPaymentCashDestinationKind | null;
  recipientType?: ManualCardPaymentRecipientType | null;
  recipientProfileId?: number | null;
  recipientUserId?: number | null;
  displayName: string;
  availableKopecks?: number | null;
  projectedOverrunKopecks?: number | null;
  anomalyWarning?: string | null;
  manualPaymentTaskId?: number | null;
  manualPaymentTaskGeneration?: number | null;
  taskTargetKind?: ManualPaymentTaskTargetKind | null;
  taskRecipientName?: string | null;
  accountingTargetLabel?: string | null;
  effectText?: string | null;
}

export interface ManualCardPaymentContext {
  orderId: number;
  amountKopecks: number;
  contractVersion?: 'TASK_RECIPIENT_V1' | string | null;
  routeRevision?: string | null;
  originalRecipient: ManualCardPaymentRecipientOption;
  candidates: ManualCardPaymentRecipientOption[];
  anomalyWarning?: string | null;
  recipientSelectionFrozen: boolean;
  preparedRecipient: ManualCardPaymentRecipientOption | null;
  preparedReason: string | null;
  preparedReceiptUrl: string | null;
}

export interface ManualCardPaymentConfirmationRequest {
  reason: string;
  receiptUrl?: string | null;
  recipientKey: string;
  recipientType?: ManualCardPaymentRecipientType | null;
  recipientProfileId?: number | null;
}

export type ManagerManualCardPaymentResultStatus = 'COMPLETED' | 'OWNER_APPROVAL_PENDING';

export interface ManagerManualCardPaymentResult {
  status: ManagerManualCardPaymentResultStatus;
  orderId: number;
  paymentLinkId: number;
  message: string;
}

export interface CommonManualPaymentAttributionModeResponse {
  attributionRequired: boolean;
}

export type CommonActualRecipientType = 'SPECIALIST' | 'MANAGER' | 'OWNER';
export type CommonManualPaymentMode = 'STANDARD' | 'TBANK_FALLBACK';
export type CommonPaymentCashDestinationKind = 'OWNER' | 'CONTRACTOR_PROFILE' | 'MANUAL_PAYMENT_TASK';
export type CommonManualPaymentTaskTargetKind = 'EXTERNAL_TASK' | 'OWNER' | 'SPECIALIST' | 'MANAGER';

export interface CommonManualPaymentRecipientCandidate {
  key: string;
  cashDestinationKind?: CommonPaymentCashDestinationKind | null;
  recipientType?: CommonActualRecipientType | null;
  recipientProfileId?: number | null;
  recipientUserId?: number | null;
  label: string;
  originalRecipient: boolean;
  currentParticipant: boolean;
  profileEnabled: boolean;
  availableKopecks?: number | null;
  manualPaymentTaskId?: number | null;
  manualPaymentTaskGeneration?: number | null;
  taskTargetKind?: CommonManualPaymentTaskTargetKind | null;
  taskRecipientName?: string | null;
  accountingTargetLabel?: string | null;
  effectText?: string | null;
}

export interface CommonManualPaymentAttributionHistoryItem {
  id: number;
  attributionKey: string;
  accountingMode: 'SHADOW' | 'LIVE';
  originalRecipientType: CommonActualRecipientType | null;
  originalRecipientProfileId?: number | null;
  originalRecipientLabel: string;
  actualRecipientType: CommonActualRecipientType | null;
  actualRecipientProfileId?: number | null;
  actualRecipientLabel: string;
  amountKopecks: number;
  availableBeforeKopecks?: number | null;
  projectedOverrunKopecks: number;
  effectiveAt: string;
  reason: string;
  evidenceReference: string;
  actor: string;
  createdAt: string;
  originalCashDestinationKind?: CommonPaymentCashDestinationKind | null;
  originalManualPaymentTaskId?: number | null;
  originalManualPaymentTaskGeneration?: number | null;
  originalTaskTargetKind?: CommonManualPaymentTaskTargetKind | null;
  actualCashDestinationKind?: CommonPaymentCashDestinationKind | null;
  actualManualPaymentTaskId?: number | null;
  actualManualPaymentTaskGeneration?: number | null;
  actualTaskTargetKind?: CommonManualPaymentTaskTargetKind | null;
}

export interface CommonManualPaymentOptions {
  invoiceId: number;
  remainingKopecks: number;
  defaultRecipientKey: string;
  contractVersion?: 'TASK_RECIPIENT_V1' | string | null;
  routeRevision?: string | null;
  candidates: CommonManualPaymentRecipientCandidate[];
  history: CommonManualPaymentAttributionHistoryItem[];
}

export interface CommonManualPaymentAttributionRowRequest {
  rowKey: string;
  recipientKey: string;
  recipientType: CommonActualRecipientType | null;
  recipientProfileId: number | null;
  amountKopecks: number;
}

export interface CommonManualPaymentAttributionRequest {
  idempotencyKey: string;
  finalAccountingAcknowledged: true;
  paymentReceived: true;
  effectiveAt: string;
  reason: string;
  receiptUrl: string;
  attributions: CommonManualPaymentAttributionRowRequest[];
}

export interface CredentialRevealResponse {
  value: string;
  credentialPreparation?: WorkerCredentialPreparation | null;
}

export interface OrderDetailsPayload {
  orderId: number;
  companyId?: number | null;
  orderDetailsId?: string | null;
  title: string;
  companyTitle: string;
  productTitle: string;
  status: string;
  amount?: number;
  counter?: number;
  sum?: number;
  totalSumWithBadReviews?: number;
  badReviewSummary?: BadReviewSummary;
  orderComments: string;
  companyComments: string;
  created: string;
  changed: string;
  reviews: OrderReviewItem[];
  badReviewTasks: BadReviewTaskItem[];
  recoveryTasks: ReviewRecoveryTaskItem[];
  products: OrderProductOption[];
  canEditReviews: boolean;
  canSendToCheck: boolean;
  canEditReviewDates: boolean;
  canEditReviewPublish: boolean;
  canEditReviewVigul: boolean;
  canDeleteReviews: boolean;
  credentialPreparation?: WorkerCredentialPreparation | null;
}

export interface ReviewUpdateRequest {
  text: string;
  answer: string;
  comment: string;
  created: string | null;
  changed: string | null;
  publishedDate: string | null;
  publish: boolean;
  vigul: boolean;
  botName: string;
  botPassword: string;
  productId: number | null;
  url: string;
}

export interface ReviewCheckPermissions {
  authenticated: boolean;
  canSeeInternalInfo: boolean;
  canSeeBot: boolean;
  canApprovePublication: boolean;
  canSave: boolean;
  canSendCorrection: boolean;
  canSendToCheck: boolean;
  canMarkPaid: boolean;
  canOpenManagerLinks: boolean;
  canEditNotes: boolean;
}

export interface ReviewCheckReview {
  id: number;
  text: string;
  answer: string;
  botName: string;
  comment: string;
  orderComments: string;
  commentCompany: string;
  productTitle: string;
  productPhoto: boolean;
  url: string;
  publishedDate: string;
  publish: boolean;
}

export interface ReviewCheckPayload {
  orderDetailId: string;
  orderId?: number | null;
  companyId?: number | null;
  companyTitle: string;
  filialTitle: string;
  status: string;
  workerFio: string;
  orderComments: string;
  companyComments: string;
  comment: string;
  amount: number;
  counter: number;
  sum?: number;
  approved: boolean;
  reviews: ReviewCheckReview[];
  permissions: ReviewCheckPermissions;
}

export interface ReviewCheckReviewUpdate {
  id: number;
  text: string;
  answer: string;
  publish: boolean;
  publishedDate: string | null;
  url: string;
}

export interface ReviewCheckUpdateRequest {
  comment: string;
  reviews: ReviewCheckReviewUpdate[];
}

export interface ReviewCheckNotes {
  orderComments: string;
  companyComments: string;
}

export interface CompanyDeepReportState {
  companyId: number;
  companyName: string;
  latestJob?: {
    jobId?: number;
    id?: number;
    status?: string;
    report?: unknown;
    errorMessage?: string | null;
    provider?: string | null;
    model?: string | null;
    createdAt?: string | null;
    updatedAt?: string | null;
    startedAt?: string | null;
    completedAt?: string | null;
  } | null;
  activeJob?: {
    jobId?: number;
    id?: number;
    status?: string;
    report?: unknown;
    errorMessage?: string | null;
    provider?: string | null;
    model?: string | null;
    createdAt?: string | null;
    updatedAt?: string | null;
    startedAt?: string | null;
    completedAt?: string | null;
  } | null;
  canStart: boolean;
  canRefresh: boolean;
  unavailableReason: string;
}

export interface CompanyItem {
  id: number;
  title: string;
  urlChat?: string;
  telephone?: string;
  urlFilial?: string;
  city?: string;
  status: string;
  manager?: string;
  commentsCompany?: string;
  countFilials?: number;
  dateNewTry?: string;
  groupId?: string | null;
  telegramGroupChatId?: number | null;
  telegramBotInviteUrl?: string;
  maxGroupChatId?: number | null;
  maxBotInviteUrl?: string;
  nextOrderRequestsCount?: number;
  failedNextOrderRequestsCount?: number;
  nextOrderRequestFilialTitle?: string;
  nextOrderRequestError?: string;
}

export interface ManagerBoard {
  section?: ManagerBoardSection;
  status?: string;
  metrics: MetricItem[];
  companies: Page<CompanyItem>;
  orders: Page<OrderItem>;
  orderStatuses: string[];
  companyStatuses: string[];
  dailyProgress?: DailyWorkProgress | null;
}

export interface ManagerBoardQuery {
  section?: ManagerBoardSection;
  status?: string;
  keyword?: string;
  companyId?: number;
  pageNumber?: number;
  pageSize?: number;
  sortDirection?: 'desc' | 'asc';
}

export type WorkerRiskIncidentStatus = 'OPEN' | 'RESOLVED' | 'IGNORED' | 'VIOLATION';
export type WorkerRiskIncidentLevel = 'WARNING' | 'MANAGER_REVIEW' | 'HIGH_RISK';
export type WorkerRiskRollbackStatus = 'APPLIED' | 'NOT_APPLICABLE';
export type WorkerRiskResolutionAction =
  | 'VERIFIED'
  | 'FALSE_POSITIVE'
  | 'NORMAL_ACCOUNT_SELECTION'
  | 'EXPLANATION_REQUESTED'
  | 'VIOLATION_CONFIRMED'
  | 'WORKER_WARNED';

export interface WorkerRiskIncident {
  id: number;
  createdAt: string;
  status: WorkerRiskIncidentStatus;
  level: WorkerRiskIncidentLevel;
  ruleCode: string;
  score: number;
  workerUserId: number;
  workerUsername: string;
  workerName: string;
  activityEventId?: number | null;
  action?: string | null;
  entityType?: string | null;
  entityId?: number | null;
  orderId?: number | null;
  reviewId?: number | null;
  title: string;
  message?: string | null;
  details?: string | null;
  explanationRequestedAt?: string | null;
  explanationPromptedAt?: string | null;
  workerExplanation?: string | null;
  workerExplanationAt?: string | null;
  workerExplanationByUserId?: number | null;
  resolutionAction?: WorkerRiskResolutionAction | null;
  resolvedAt?: string | null;
  resolvedByUserId?: number | null;
  resolvedByUsername?: string | null;
  penaltyPoints: number;
  rollbackStatus?: WorkerRiskRollbackStatus | null;
  rolledBackAt?: string | null;
  rolledBackByUserId?: number | null;
  rolledBackByUsername?: string | null;
  rollbackMessage?: string | null;
  canRollback: boolean;
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

export interface ManagerArchiveOrdersQuery {
  keyword?: string;
  mode?: ArchiveOrderMode;
  pageNumber?: number;
  pageSize?: number;
  sortDirection?: 'desc' | 'asc';
}

export interface WorkerReviewItem {
  id: number;
  companyId?: number;
  orderDetailsId?: string;
  orderId: number;
  orderStatus?: string;
  text: string;
  answer: string;
  category?: string;
  subCategory?: string;
  botId?: number | null;
  companyTitle: string;
  commentCompany?: string;
  orderComments?: string;
  filialCity?: string;
  filialUrl?: string;
  productTitle: string;
  productPhoto?: boolean;
  botFio: string;
  botLoginPresent: boolean;
  botPasswordPresent: boolean;
  botCounter?: number;
  filialTitle?: string;
  workerFio?: string;
  created?: string;
  changed?: string;
  publishedDate?: string;
  publish?: boolean;
  vigul?: boolean;
  comment?: string;
  price?: number;
  url?: string;
  urlPhoto?: string;
  badTask?: boolean;
  badTaskId?: number | null;
  sourceReviewId?: number | null;
  originalRating?: number | null;
  targetRating?: number | null;
  badTaskStatus?: string;
  badTaskPrice?: number;
  badTaskScheduledDate?: string;
  badTaskCompletedDate?: string;
  badTaskComment?: string;
  recoveryTask?: boolean;
  recoveryTaskId?: number | null;
  recoveryTaskStatus?: string;
  recoveryTaskScheduledDate?: string;
  recoveryTaskCompletedDate?: string;
}

export interface WorkerBotItem {
  id: number;
  loginPresent: boolean;
  passwordPresent: boolean;
  fio: string;
  city: string;
  counter: number;
  workerFio: string;
  status: string;
  active: boolean;
}

export interface WorkerPermissions {
  canManageOrderStatuses: boolean;
  canManageClientWaiting: boolean;
  canSeePhoneAndPayment: boolean;
  canManageBots: boolean;
  canAddBot: boolean;
  canSeeMoney: boolean;
  canWorkReviews: boolean;
  canEditNotes: boolean;
}

export interface WorkerOption {
  id: number;
  label: string;
}

export type WorkerBoardSection = 'new' | 'correct' | 'nagul' | 'recovery' | 'publish' | 'bad' | 'all';
export type WorkerBoardSectionQuery = WorkerBoardSection | 'current';
export type WorkerActivitySource = {
  sourcePage?: string;
  sourceEntry?: string;
  sourceSection?: string;
};

export interface WorkerBoard {
  section?: WorkerBoardSection;
  title: string;
  metrics: MetricItem[];
  orders: Page<OrderItem>;
  reviews: Page<WorkerReviewItem>;
  bots?: WorkerBotItem[];
  promoTexts?: string[];
  permissions?: WorkerPermissions;
  workerOptions?: WorkerOption[];
  selectedWorkerId?: number | null;
  workerFilterAvailable?: boolean;
  message?: string;
  warning?: boolean;
  credentialPreparation?: WorkerCredentialPreparation | null;
  dailyProgress?: DailyWorkProgress | null;
}

export interface WorkerBoardQuery {
  section?: WorkerBoardSectionQuery;
  keyword?: string;
  pageNumber?: number;
  pageSize?: number;
  sortDirection?: 'desc' | 'asc';
  workerId?: number | null;
}

export interface WorkerActionResponse {
  success: boolean;
  message: string;
}

export interface BotChangeResponse {
  oldBotId?: number | null;
  newBotId?: number | null;
}

export interface ManagerOverdueStatus {
  status: string;
  count: number;
  maxDays: number;
}

export interface ManagerOverdueOrders {
  thresholdDays: number;
  total: number;
  statuses: ManagerOverdueStatus[];
}

export interface LeadItem {
  id: number;
  telephoneLead: string;
  companyName?: string;
  phones?: string;
  mobilePhones?: string;
  whatsappPhones?: string;
  emails?: string;
  websites?: string;
  vkUrl?: string;
  telegramUrl?: string;
  industries?: string;
  companyType?: string;
  region?: string;
  address?: string;
  cityLead: string;
  commentsLead?: string;
  lidStatus: string;
  createDate?: string;
  updateStatus?: string;
  dateNewTry?: string;
  offer?: boolean;
  operatorId?: number;
  telephoneId?: number | null;
  operator?: LeadPerson;
  manager?: LeadPerson;
  marketolog?: LeadPerson;
}

export interface LeadPerson {
  id: number;
  userId?: number;
  username?: string;
  fio?: string;
}

export interface LeadBoard {
  toWork: Page<LeadItem>;
  newLeads: Page<LeadItem>;
  send: Page<LeadItem>;
  archive?: Page<LeadItem>;
  inWork: Page<LeadItem>;
  all: Page<LeadItem>;
  statuses?: string[];
  promoTexts?: string[];
}

export type LeadBucketKey = 'toWork' | 'newLeads' | 'send' | 'archive' | 'inWork' | 'all';

export interface LeadBoardQuery {
  keyword?: string;
  pageNumber?: number;
  pageSize?: number;
  sortDirection?: 'desc' | 'asc';
  section?: LeadBucketKey;
}

export interface LeadPersonOption {
  id: number;
  userId?: number;
  username?: string;
  fio?: string;
  email?: string;
}

export interface LeadEditOptions {
  operators: LeadPersonOption[];
  managers: LeadPersonOption[];
  marketologs: LeadPersonOption[];
  statuses: string[];
}

export interface LeadImportManagerAssignment {
  managerId: number;
  managerName: string;
  added: number;
}

export interface LeadImportResponse {
  totalRows: number;
  added: number;
  skippedDuplicates: number;
  skippedWithoutPhones: number;
  skippedInvalid: number;
  errors: string[];
  managerAssignments: LeadImportManagerAssignment[];
}

export interface LeadImportRequest {
  file: File;
  managerIds: number[];
  operatorId?: number | null;
  marketologId?: number | null;
}

export interface LeadCreateRequest {
  telephoneLead: string;
  companyName?: string;
  phones?: string;
  mobilePhones?: string;
  whatsappPhones?: string;
  emails?: string;
  websites?: string;
  vkUrl?: string;
  telegramUrl?: string;
  industries?: string;
  companyType?: string;
  region?: string;
  address?: string;
  cityLead: string;
  commentsLead?: string;
  managerId?: number | null;
}

export interface LeadUpdateRequest {
  telephoneLead: string;
  companyName?: string;
  phones?: string;
  mobilePhones?: string;
  whatsappPhones?: string;
  emails?: string;
  websites?: string;
  vkUrl?: string;
  telegramUrl?: string;
  industries?: string;
  companyType?: string;
  region?: string;
  address?: string;
  cityLead: string;
  commentsLead?: string;
  lidStatus: string;
  operatorId?: number | null;
  telephoneId?: number | null;
  managerId?: number | null;
  marketologId?: number | null;
}

export type CompanyCreateSource = 'manager' | 'operator' | 'manual';

export interface CompanyCreateOption {
  id: number;
  label: string;
}

export interface CompanyCreatePayload {
  source: CompanyCreateSource;
  leadId?: number | null;
  title: string;
  urlChat: string;
  urlSite: string;
  telephone: string;
  city: string;
  email: string;
  commentsCompany: string;
  operator: string;
  manager?: CompanyCreateOption | null;
  worker?: CompanyCreateOption | null;
  status?: CompanyCreateOption | null;
  category?: CompanyCreateOption | null;
  subCategory?: CompanyCreateOption | null;
  filialCity?: CompanyCreateOption | null;
  filialTitle: string;
  filialUrl: string;
  managers: CompanyCreateOption[];
  workers: CompanyCreateOption[];
  categories: CompanyCreateOption[];
  subCategories: CompanyCreateOption[];
  cities: CompanyCreateOption[];
  canChangeManager: boolean;
}

export interface CompanyCreateRequest {
  source: CompanyCreateSource;
  leadId?: number | null;
  managerId?: number | null;
  title: string;
  urlChat: string;
  urlSite: string;
  telephone: string;
  city: string;
  email: string;
  commentsCompany: string;
  categoryId: number | null;
  subCategoryId: number | null;
  workerId: number | null;
  filialCityId: number | null;
  filialTitle: string;
  filialUrl: string;
}

export interface CompanyCreateResult {
  companyId?: number | null;
  title: string;
  leadId?: number | null;
  source: CompanyCreateSource;
  deepReportLaunch?: {
    attempted: boolean;
    started: boolean;
    jobId?: number | null;
    status: string;
    message: string;
  } | null;
}

export type OperatorBoardSection = 'queue' | 'sent';

export interface OperatorText {
  beginText: string;
  offerText: string;
  offer2Text: string;
  startText: string;
}

export interface OperatorBoard {
  leads: Page<LeadItem>;
  promoTexts?: string[];
  text?: OperatorText;
  queueTotal: number;
  sentTotal: number;
  timer?: string | null;
  timerExpired?: boolean;
  requireDeviceId: boolean;
  telephoneId?: number | null;
  operatorId?: number | null;
  section?: OperatorBoardSection;
}

export interface OperatorBoardQuery {
  keyword?: string;
  pageNumber?: number;
  pageSize?: number;
  section?: OperatorBoardSection;
}

export interface PhoneOperatorOption {
  id: number;
  title: string;
}

export interface DeviceToken {
  token: string;
  createdAt?: string | null;
  active: boolean;
}

export interface OperatorPhone {
  id: number;
  number: string;
  fio?: string | null;
  birthday?: string | null;
  amountAllowed: number;
  amountSent: number;
  blockTime: number;
  timer?: string | null;
  googleLoginMasked?: string | null;
  googleLoginPresent: boolean;
  googlePasswordPresent: boolean;
  avitoPasswordPresent: boolean;
  mailLoginMasked?: string | null;
  mailLoginPresent: boolean;
  mailPasswordPresent: boolean;
  fotoInstagram?: string | null;
  active: boolean;
  createDate?: string | null;
  updateStatus?: string | null;
  operator?: PhoneOperatorOption | null;
  deviceTokens?: DeviceToken[];
}

export interface OperatorPhonesResponse {
  phones: OperatorPhone[];
  operators: PhoneOperatorOption[];
}

export interface OperatorPhoneRequest {
  number: string;
  fio?: string | null;
  birthday?: string | null;
  amountAllowed: number;
  amountSent: number;
  blockTime: number;
  timer?: string | null;
  googleLogin?: string | null;
  googlePassword?: string | null;
  avitoPassword?: string | null;
  mailLogin?: string | null;
  mailPassword?: string | null;
  fotoInstagram?: string | null;
  active: boolean;
  createDate?: string | null;
  operatorId?: number | null;
}

export interface DictionaryOption {
  id: number;
  title: string;
}

export interface AdminCategory {
  id: number;
  title: string;
  subCategoryCount: number;
  subCategories: DictionaryOption[];
}

export interface AdminSubCategory {
  id: number;
  title: string;
  category?: DictionaryOption | null;
}

export interface AdminCity {
  id: number;
  title: string;
}

export interface AdminProduct {
  id: number;
  title: string;
  price: number;
  photo: boolean;
  category?: DictionaryOption | null;
}

export interface AdminBot {
  id: number;
  login: string;
  fio: string;
  active: boolean;
  counter: number;
  passwordPresent: boolean;
  status?: DictionaryOption | null;
  worker?: DictionaryOption | null;
  city?: DictionaryOption | null;
}

export interface CreateKeycloakUserRequest {
  username: string;
  email: string;
  fio?: string;
  phoneNumber?: string;
  password: string;
  temporaryPassword: boolean;
  enabled: boolean;
  emailVerified: boolean;
  coefficient?: number;
  roles: string[];
}

export interface CreatedKeycloakUserResponse {
  id: number;
  keycloakId: string;
  username: string;
  email: string;
  fio?: string;
  phoneNumber?: string;
  coefficient?: number;
  active: boolean;
  roles: string[];
}

export interface AdminUser {
  id: number;
  keycloakId?: string;
  keycloakLinked: boolean;
  authProvider: string;
  username: string;
  email?: string;
  fio?: string;
  phoneNumber?: string;
  coefficient?: number;
  managerAuditChatUrl?: string;
  managerAuditTelegramGroupChatId?: number | null;
  managerAuditTelegramBotInviteUrl?: string;
  imageId?: number | null;
  active: boolean;
  createTime?: string;
  lastLoginAt?: string;
  roles: string[];
}

export interface UpdateKeycloakUserRequest {
  email?: string;
  fio?: string;
  phoneNumber?: string;
  coefficient?: number;
  managerAuditChatUrl?: string;
  enabled: boolean;
  roles: string[];
}

export interface ChangeKeycloakPasswordRequest {
  password: string;
  temporary: boolean;
}

export interface AssignmentOption {
  id: number;
  userId: number;
  username: string;
  fio?: string;
  email?: string;
  role: string;
}

export interface AssignmentOptions {
  managers: AssignmentOption[];
  workers: AssignmentOption[];
  operators: AssignmentOption[];
  marketologs: AssignmentOption[];
}

export interface UserAssignments {
  userId: number;
  managerIds: number[];
  workerIds: number[];
  operatorIds: number[];
  marketologIds: number[];
}

export interface UpdateUserAssignmentsRequest {
  managerIds: number[];
  workerIds: number[];
  operatorIds: number[];
  marketologIds: number[];
}

export interface AdminPromoText {
  id: number;
  position: number;
  text: string;
}

export interface AdminManagerText {
  managerId: number;
  managerTitle: string;
  payText: string;
  beginText: string;
  offerText: string;
  reminderText: string;
  startText: string;
}

export interface AdminNagulSettings {
  cooldownMinutes: number;
  lookaheadDays: number;
}

export interface AdminTelegramReportScheduleSettings {
  morningEnabled: boolean;
  morningTime: string;
  eveningEnabled: boolean;
  eveningTime: string;
  zone: string;
  morningLastRunKey: string;
  eveningLastRunKey: string;
}

export interface AdminWhatsAppGroupSyncSettings {
  enabled: boolean;
  intervalMinutes: number;
  lastRunAt: string;
  lastLinkedCount: number;
}

export interface AdminClientPublicationProgressReportSettings {
  enabled: boolean;
}

export interface AdminClientMessageSettings {
  workerEnabled: boolean;
  liveEnabled: boolean;
  immediateEnabled: boolean;
  monitorEnabled: boolean;
  reviewCheckEnabled: boolean;
  reviewCheckAutoArchiveEnabled: boolean;
  clientTextReminderEnabled: boolean;
  paymentReminderEnabled: boolean;
  badReviewInvoiceEnabled: boolean;
  badReviewAutoBanEnabled: boolean;
  reviewRecoveryNoticeEnabled: boolean;
  paymentOverdueEnabled: boolean;
  paymentOverdueLiveEnabled: boolean;
  archiveReorderEnabled: boolean;
  errorProtectionEnabled: boolean;
  reviewCheckIntervalDays: number;
  reviewCheckAutoArchiveDays: number;
  clientTextReminderIntervalDays: number;
  paymentReminderIntervalDays: number;
  reviewCheckRetryDelayHours: number;
  paymentInvoiceRetryDelayHours: number;
  badReviewInvoiceRetryDelayHours: number;
  badReviewAutoBanDelayDays: number;
  reviewRecoveryNoticeRetryDelayHours: number;
  paymentOverdueDays: number;
  archiveReorderMonths: number;
  archiveReorderJitterDays: number;
  archiveOrderRetentionDays: number;
  errorProtectionThreshold: number;
  errorProtectionWindowMinutes: number;
  errorProtectionCooldownMinutes: number;
  whatsAppAuthRetryHours: number;
  whatsAppAuthAlertCooldownHours: number;
  retentionDays: number;
  tickBatchSize: number;
  candidateLimit: number;
  dailyLimit: number;
  defaultGapSeconds: number;
  whatsAppGapSeconds: number;
  telegramGapSeconds: number;
  maxGapSeconds: number;
  businessWindows: string;
  reviewCheckStatuses: string;
  clientTextReminderStatuses: string;
  paymentReminderStatuses: string;
  paymentOverdueStatuses: string;
  closedOrderStatuses: string;
  paymentOverdueTargetStatus: string;
  archiveCompanyStatus: string;
  archiveInactiveOrderStatuses: string;
  openNextOrderRequestStatuses: string;
  reviewLinkBaseUrl: string;
  reviewReminderText: string;
  clientTextReminderText: string;
  publicationStartedText: string;
  publicationProgressReportText: string;
  paymentInstructionSource: PaymentInstructionSource;
  paymentReminderText: string;
  paymentLinkCopyText: string;
  paymentSuccessText: string;
  reviewRecoveryNoticeText: string;
  archiveOfferText: string;
}

export interface AdminClientMessageMonitorScenario {
  scenario: string;
  label: string;
  activeCandidates: number;
  dueNow: number;
  readyToSendNow?: number;
  waitingForWindow?: number;
  missingChannelBindings?: number;
  sentToday: number;
  sentSevenDays: number;
  failedToday: number;
  skippedToday: number;
  lastError?: string | null;
  lastErrorAt?: string | null;
}

export interface AdminClientMessageMonitorQueueItem {
  id: number;
  scenario: string;
  scenarioLabel: string;
  targetType: string;
  targetKey: string;
  companyId?: number | null;
  companyTitle: string;
  orderId?: number | null;
  orderTitle?: string | null;
  statusTitle?: string | null;
  nextAttemptAt?: string | null;
  lastAttemptAt?: string | null;
  lastSuccessAt?: string | null;
  lastErrorCode?: string | null;
  lastErrorMessage?: string | null;
  sentCount: number;
  consecutiveFailures: number;
  expectedChannel?: string | null;
  channelDetails?: string | null;
  paymentInstructionSource?: PaymentInstructionSource | string | null;
  messagePreview?: string | null;
  readiness?: string | null;
  readinessLabel?: string | null;
  readinessReason?: string | null;
  link?: string | null;
}

export interface AdminClientMessageMonitorAttempt {
  id: number;
  stateId?: number | null;
  scenario: string;
  scenarioLabel: string;
  targetType: string;
  targetKey: string;
  companyId?: number | null;
  companyTitle: string;
  orderId?: number | null;
  orderTitle?: string | null;
  status: 'SENT' | 'FAILED' | 'SKIPPED' | string;
  statusLabel: string;
  channel?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  messagePreview?: string | null;
  durationMs?: number | null;
  attemptedAt: string;
  link?: string | null;
}

export interface AdminClientMessageMonitor {
  enabled: boolean;
  workerEnabled: boolean;
  liveEnabled: boolean;
  windowAllowed: boolean;
  businessWindows: string;
  nowMoscow?: string;
  nowIrkutsk?: string;
  updatedAt: string;
  nextAttemptAt?: string | null;
  pausedUntil?: string | null;
  pauseReason?: string | null;
  activeCandidates: number;
  dueNow: number;
  readyToSendNow?: number;
  waitingForWindow?: number;
  missingChannelBindings?: number;
  manualControlCandidates?: number;
  retryWaitingCandidates?: number;
  recoveryHoldCandidates?: number;
  autoRecoveredToday?: number;
  sentToday: number;
  failedToday: number;
  skippedToday: number;
  disabledStates: number;
  archiveDiagnostics?: AdminClientMessageArchiveDiagnostics | null;
  scenarios: AdminClientMessageMonitorScenario[];
  queue: AdminClientMessageMonitorQueueItem[];
  attempts: AdminClientMessageMonitorAttempt[];
}

export interface AdminClientMessageArchiveDiagnostics {
  status: string;
  totalInStatus: number;
  ready: number;
  tooFresh: number;
  withoutChat: number;
  blockedByActiveOrder: number;
  blockedByOpenRequest: number;
}

export interface AdminClientMessageMonitorSettings {
  enabled: boolean;
}

export interface AdminClientMessageMaintenancePreview {
  updatedAt: string;
  paymentOverdueDays: number;
  publicationStaleDays: number;
  companyStatuses: AdminMaintenanceCompanyStatusPreview;
  paymentStatuses: AdminMaintenancePaymentStatusPreview;
  unpaidRecovery: AdminMaintenanceUnpaidRecoveryPreview;
  publication: AdminMaintenancePublicationPreview;
  archiveOffers: AdminMaintenanceArchiveOfferPreview;
  suggestedActions: AdminMaintenanceActionItem[];
}

export interface AdminMaintenanceCompanyStatusPreview {
  shouldMoveToWork: number;
  stoppedWithActiveOrders: number;
  newOrderWithActiveOrders: number;
  bannedWithActiveOrders: number;
  workWithoutActiveOrders: number;
  newOrderWithoutActiveOrders: number;
  samplesToWork: AdminMaintenanceCompanyStatusSample[];
  samplesToStop: AdminMaintenanceCompanyStatusSample[];
  samplesBannedWithActiveOrders: AdminMaintenanceCompanyStatusSample[];
}

export interface AdminMaintenanceCompanyStatusSample {
  companyId: number;
  companyTitle: string;
  currentStatus: string;
  activeOrders: number;
  activeOrderStatuses: string;
}

export interface AdminMaintenancePaymentStatusPreview {
  invoiceOrReminderTotal: number;
  invoiceOrReminderOlderThanThreshold: number;
  invoiceOrReminderOlderThanThirtyDays: number;
  invoiceOrReminderWithoutActiveState: number;
  overdueSamples: AdminMaintenanceOrderRiskSample[];
}

export interface AdminMaintenanceUnpaidRecoveryPreview {
  total: number;
  olderThanThreshold: number;
  olderThanThreeHundredDays: number;
  withoutBadTasks: number;
  canCreateBadTasks: number;
  withoutPublishedReviews: number;
  withPendingBadTasks: number;
  allBadTasksDone: number;
  oldSamples: AdminMaintenanceOrderRiskSample[];
}

export interface AdminMaintenancePublicationPreview {
  total: number;
  suspicious: number;
  olderThanStaleDays: number;
  overdueUnpublished: number;
  undatedUnpublished: number;
  longPublishSpan: number;
  farFuturePublishDate: number;
  oldAllReviewsPublished: number;
  oldWithFuturePublishDate: number;
  oldSamples: AdminMaintenanceOrderRiskSample[];
}

export interface AdminMaintenanceArchiveOfferPreview {
  activeStates: number;
  dueNow: number;
  blockedByActiveOrders: number;
  blockedByOpenNextRequest: number;
}

export interface AdminMaintenanceOrderRiskSample {
  orderId: number;
  companyId?: number | null;
  companyTitle: string;
  status: string;
  ageDays: number;
  orderAmount?: number | null;
  orderSum?: string | null;
  reviews: number;
  publishedReviews: number;
  badTasks: number;
  pendingBadTasks: number;
  maxPublishDate?: string | null;
  reason: string;
}

export interface AdminMaintenanceActionItem {
  tone: 'safe' | 'warning' | 'danger' | string;
  title: string;
  description: string;
  count: number;
}

export interface AdminMaintenanceApplyResponse {
  action: string;
  changed: number;
  message: string;
  appliedAt: string;
  preview: AdminClientMessageMaintenancePreview;
}

export interface AdminSharedChatLinkSyncResponse {
  scannedCompanies: number;
  sharedChatGroups: number;
  updatedCompanies: number;
  whatsappLinked: number;
  telegramLinked: number;
  maxLinked: number;
  conflictGroups: number;
}

export interface PromoButtonSlot {
  section: string;
  sectionTitle: string;
  buttonKey: string;
  buttonLabel: string;
  outputPosition: number;
  defaultPromoPosition: number;
  defaultPromoTextId?: number | null;
}

export interface PromoTextAssignment {
  id: number;
  managerId?: number | null;
  managerTitle: string;
  section: string;
  sectionTitle: string;
  buttonKey: string;
  buttonLabel: string;
  outputPosition: number;
  promoTextId?: number | null;
  promoTextLabel: string;
}

export interface PromoTextManagementResponse {
  texts: AdminPromoText[];
  managers: DictionaryOption[];
  assignments: PromoTextAssignment[];
  buttons: PromoButtonSlot[];
}

export interface ProductsResponse {
  products: AdminProduct[];
  categories: DictionaryOption[];
}

export interface BotsResponse {
  bots: AdminBot[];
  workers: DictionaryOption[];
  statuses: DictionaryOption[];
  cities: DictionaryOption[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface BotCountResponse {
  count: number;
}

export interface BotImportResponse {
  totalRows: number;
  added: number;
  skippedDuplicates: number;
  skippedInvalid: number;
  errors: string[];
}

export interface BotBrowserOpenResponse {
  sessionId?: string;
  vncUrl: string;
  vncPassword: string;
  heartbeatIntervalSeconds?: number;
  expiresAt?: string;
  botId?: number;
  userAgent?: string;
  platform?: string;
  screenResolution?: string;
}

export interface BotBrowserMetadata {
  botId: number;
  login: string;
  fio: string;
}

export interface TitleRequest {
  title: string;
}

export interface SubCategoryRequest {
  title: string;
  categoryId: number | null;
}

export interface ProductRequest {
  title: string;
  price: number;
  categoryId: number | null;
  photo: boolean;
}

export interface BotRequest {
  login: string;
  password: string;
  fio: string;
  workerId: number | null;
  cityId: number | null;
  statusId: number | null;
  active: boolean;
  counter: number;
}

export interface PromoTextRequest {
  text: string;
}

export interface PromoTextAssignmentRequest {
  managerId: number;
  section: string;
  buttonKey: string;
  promoTextId: number;
}

export interface ManagerTextRequest {
  payText: string;
  beginText: string;
  offerText: string;
  reminderText: string;
  startText: string;
}

export interface NagulSettingsRequest {
  cooldownMinutes: number;
  lookaheadDays: number;
}

export interface TelegramReportScheduleSettingsRequest {
  morningEnabled: boolean;
  morningTime: string;
  eveningEnabled: boolean;
  eveningTime: string;
  zone: string;
}

export interface WhatsAppGroupSyncSettingsRequest {
  enabled: boolean;
  intervalMinutes: number;
}

export interface ClientPublicationProgressReportSettingsRequest {
  enabled: boolean;
}

export type ClientMessageSettingsRequest = AdminClientMessageSettings;

export interface CabinetProfile {
  date: string;
  user: {
    username: string;
    role: string;
    image?: number | null;
    leadCount: number;
    reviewCount: number;
  };
  workerZp?: CabinetUserStat | null;
}

export interface CabinetUserStat {
  id: number;
  fio: string;
  imageId: number;
  coefficient?: number | null;
  percentNoPay?: number | null;
  avgPublish1Day?: number | null;
  zpPayMap?: string | null;
  zpPayMapMonth?: string | null;
  sum1Day: number;
  sum1Week: number;
  sum1Month: number;
  sum1Year: number;
  sumOrders1Month: number;
  sumOrders2Month: number;
  percent1Day: number;
  percent1Week: number;
  percent1Month: number;
  percent1Year: number;
  percent1MonthOrders: number;
  percent2MonthOrders: number;
}

export interface CabinetStatDto {
  zpPayMap?: string | null;
  zpPayMapMonth?: string | null;
  orderPayMap?: string | null;
  orderPayMapMonth?: string | null;
  sum1DayPay: number;
  sum1WeekPay: number;
  sum1MonthPay: number;
  sum1YearPay: number;
  sumOrders1MonthPay: number;
  sumOrders2MonthPay: number;
  newLeads: number;
  leadsInWork: number;
  percent1DayPay: number;
  percent1WeekPay: number;
  percent1MonthPay: number;
  percent1YearPay: number;
  percent1MonthOrdersPay: number;
  percent2MonthOrdersPay: number;
  percent1NewLeadsPay: number;
  percent2InWorkLeadsPay: number;
  sum1Day: number;
  sum1Week: number;
  sum1Month: number;
  sum1Year: number;
  sumOrders1Month: number;
  sumOrders2Month: number;
  percent1Day: number;
  percent1Week: number;
  percent1Month: number;
  percent1Year: number;
  percent1MonthOrders: number;
  percent2MonthOrders: number;
}

export interface DailyWorkProgress {
  visible: boolean;
  roleType: 'MANAGER' | 'WORKER' | string;
  date: string;
  completed: number;
  active: number;
  total: number;
  percent: number;
  checked: boolean;
  firstCompletedAt?: string | null;
  lastCompletedAt?: string | null;
  averageCloseSeconds: number;
  medianCloseSeconds: number;
  p90CloseSeconds: number;
  firstActivityAt?: string | null;
  lastActivityAt?: string | null;
  activeWorkSeconds: number;
  workWindowSeconds: number;
  activityEvents: number;
  loadScore: number;
  efficiencyScore: number;
  openedCount?: number;
  orderCompletedCount?: number;
  nagulCompletedCount?: number;
  publishCompletedCount?: number;
  badCompletedCount?: number;
  recoveryCompletedCount?: number;
  recoveryCreatedCount?: number;
  orderOverdueCount?: number;
  totalOverdueCount?: number;
  speedScore?: number;
  disciplineScore?: number;
  workloadScore?: number;
  botChangeCount?: number;
  botBlockCount?: number;
  reached100?: boolean;
  firstReached100At?: string | null;
  lastReached100At?: string | null;
  periodType?: 'DAY' | 'MONTH' | string;
  workingDays?: number;
  checkedDays?: number;
  reached100Days?: number;
  closedPeriod?: boolean;
  updating?: boolean;
}

export interface TeamMember {
  id: number;
  userId: number;
  login: string;
  fio: string;
  imageId: number;
  sum1Month?: number | null;
  order1Month?: number | null;
  review1Month?: number | null;
  payment1Month?: number | null;
  leadsInWorkInMonth?: number | null;
  leadsNew?: number | null;
  leadsInWork?: number | null;
  percentInWork?: number | null;
  newOrder?: number | null;
  inCorrect?: number | null;
  intVigul?: number | null;
  publish?: number | null;
  dailyProgress?: DailyWorkProgress | null;
  monthlyProgress?: DailyWorkProgress | null;
  dailyNetworkViolations?: WorkerNetworkViolationStats | null;
  monthlyNetworkViolations?: WorkerNetworkViolationStats | null;
}

export interface WorkerNetworkViolationStats {
  visible: boolean;
  episodeCount: number;
  attemptCount: number;
  daysWithViolations: number;
  severity: 'NONE' | 'WARNING' | 'CRITICAL';
  details: WorkerNetworkViolationDetail[];
}

export interface WorkerNetworkViolationDetail {
  firstSeenAt: string;
  lastSeenAt: string;
  reason: string;
  scope: string;
  attemptCount: number;
  provider?: string | null;
  blocked: boolean;
}

export interface TeamResponse {
  date: string;
  role: string;
  canEditUsers: boolean;
  canAddUsers: boolean;
  canOpenUserInfo: boolean;
  managers: TeamMember[];
  marketologs: TeamMember[];
  workers: TeamMember[];
  operators: TeamMember[];
}

export interface ScoreUser {
  fio: string;
  role: string;
  salary?: number | null;
  totalSum?: number | null;
  zpTotal?: number | null;
  newCompanies?: number | null;
  newOrders?: number | null;
  correctOrders?: number | null;
  inVigul?: number | null;
  inPublish?: number | null;
  imageId?: number | null;
  userId?: number | null;
  order1Month?: number | null;
  review1Month?: number | null;
  leadsNew?: number | null;
  leadsInWork?: number | null;
  percentInWork?: number | null;
}

export interface ScoreResponse {
  date: string;
  user: {
    username: string;
    role: string;
    image?: number | null;
    leadCount: number;
    reviewCount: number;
  };
  financeVisible: boolean;
  groups: {
    managers: ScoreUser[];
    marketologs: ScoreUser[];
    workers: ScoreUser[];
    operators: ScoreUser[];
  };
}

export interface AnalyticsPeriod {
  from: string;
  to: string;
  allTime: boolean;
}

export interface AnalyticsResponse {
  date: string;
  period?: AnalyticsPeriod;
  user: {
    username: string;
    role: string;
    image?: number | null;
    leadCount: number;
    reviewCount: number;
  };
  stats: CabinetStatDto;
}

export interface AnalyticsOptions {
  forceRefresh?: boolean;
  from?: string;
  to?: string;
  allTime?: boolean;
}

export interface TeamOptions {
  forceRefresh?: boolean;
  month?: string;
}

export type ContractorPaymentRole = 'SPECIALIST' | 'MANAGER';

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

export interface ManagerManualPaymentSettings {
  profileId?: number | null;
  profileName: string;
  paymentPolicy: PaymentPolicy | string;
  manualPaymentEnabled: boolean;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone: string;
  manualRecipientName: string;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
}

export interface UpdateManagerManualPaymentSettingsRequest {
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone: string;
  manualRecipientName: string;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
}

export interface DictionarySummaryItem {
  key: string;
  title: string;
  icon: string;
  count: number;
  description: string;
}

export interface DictionarySummary {
  items: DictionarySummaryItem[];
}

export type PersonalReminderMode = 'none' | 'datetime' | 'timer';

export interface PersonalReminder {
  id: number;
  title: string;
  text: string;
  reminderMode: PersonalReminderMode;
  remindAt: string | null;
  timerMinutes: number | null;
  completedAt: string | null;
  sourceType?: string | null;
  sourceId?: number | null;
  sourceOrderId?: number | null;
  paymentCopyText?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PersonalReminderRequest {
  title: string;
  text: string;
  reminderMode: PersonalReminderMode;
  remindAt: string | null;
  timerMinutes: number | null;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly manualPaymentTasksApi = inject(ManualPaymentTasksApi);
  private readonly managerBoardApi = inject(ManagerBoardApi);
  private readonly managerControlApi = inject(ManagerControlApi);
  private readonly managerReportsApi = inject(ManagerReportsApi);
  private readonly managerWorkerRiskApi = inject(ManagerWorkerRiskApi);
  private readonly managerArchiveApi = inject(ManagerArchiveApi);
  private readonly managerCompanyActionsApi = inject(ManagerCompanyActionsApi);
  private readonly managerOrdersApi = inject(ManagerOrdersApi);
  private readonly managerManualPaymentsApi = inject(ManagerManualPaymentsApi);
  private readonly managerReviewActionsApi = inject(ManagerReviewActionsApi);
  private readonly managerReviewTasksApi = inject(ManagerReviewTasksApi);
  private readonly publicPaymentsApi = inject(PublicPaymentsApi);
  private readonly paymentAdministrationApi = inject(PaymentAdministrationApi);
  private readonly paymentConfigurationApi = inject(PaymentConfigurationApi);
  private readonly workerApi = inject(WorkerApi);
  private readonly companiesApi = inject(CompaniesApi);
  private readonly commonBillingApi = inject(CommonBillingApi);
  private readonly dictionariesApi = inject(DictionariesApi);
  private readonly orderReviewsApi = inject(OrderReviewsApi);
  private readonly orderPaymentApi = inject(OrderPaymentApi);
  private readonly companyReportApi = inject(OrderCompanyReportApi);
  private readonly companyEditorApi = inject(ManagerCompanyEditorApi);
  private readonly companyBillingApi = inject(ManagerCompanyBillingApi);
  private readonly orderEditorApi = inject(ManagerOrderEditorApi);
  constructor(private readonly http: HttpClient) {}

  getCurrentUser(): Observable<CurrentUser> {
    return this.http.get<CurrentUser>(this.apiUrl('/api/me'));
  }

  getMyContractorPaymentSummaries(): Observable<ContractorPaymentSummary[]> {
    return this.http.get<ContractorPaymentSummary[]>(this.apiUrl('/api/contractor-payments/me'));
  }

  getCabinetProfile(date?: string, options: { forceRefresh?: boolean } = {}): Observable<CabinetProfile> {
    return this.http.get<CabinetProfile>(this.apiUrl('/api/cabinet/profile'), {
      params: this.cabinetDateParams(date, options.forceRefresh)
    });
  }

  getCabinetTeam(date?: string, options: TeamOptions = {}): Observable<TeamResponse> {
    let params = this.cabinetDateParams(date, options.forceRefresh);
    if (options.month) {
      params = params.set('month', options.month);
    }
    return this.http.get<TeamResponse>(this.apiUrl('/api/cabinet/team'), {
      params
    });
  }

  getCabinetScore(date?: string, options: { forceRefresh?: boolean } = {}): Observable<ScoreResponse> {
    return this.http.get<ScoreResponse>(this.apiUrl('/api/cabinet/score'), {
      params: this.cabinetDateParams(date, options.forceRefresh)
    });
  }

  getCabinetAnalytics(date?: string, options: AnalyticsOptions = {}): Observable<AnalyticsResponse> {
    let params = this.cabinetDateParams(date, options.forceRefresh);
    if (options.allTime) {
      params = params.set('allTime', 'true');
    } else {
      if (options.from) {
        params = params.set('from', options.from);
      }
      if (options.to) {
        params = params.set('to', options.to);
      }
    }

    return this.http.get<AnalyticsResponse>(this.apiUrl('/api/cabinet/analyse'), { params });
  }

  getManagerManualPaymentSettings(
    options: { forceRefresh?: boolean } = {}
  ): Observable<ManagerManualPaymentSettings> { return this.manualPaymentTasksApi.getManagerManualPaymentSettings(options); }

  updateManagerManualPaymentSettings(
    request: UpdateManagerManualPaymentSettingsRequest
  ): Observable<ManagerManualPaymentSettings> { return this.manualPaymentTasksApi.updateManagerManualPaymentSettings(request); }

  getManagerManualPaymentTasks(
    options: { forceRefresh?: boolean } = {}
  ): Observable<ManualPaymentTaskResponse[]> { return this.manualPaymentTasksApi.getManagerManualPaymentTasks(options); }

  getManagerManualPaymentTaskAccountingTargets(
    targetAmountKopecks: number,
    taskId?: number | null
  ): Observable<ManualPaymentTaskAccountingTargetOption[]> { return this.manualPaymentTasksApi.getManagerManualPaymentTaskAccountingTargets(targetAmountKopecks, taskId); }

  createManagerManualPaymentTask(
    request: CreateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> { return this.manualPaymentTasksApi.createManagerManualPaymentTask(request); }

  updateManagerManualPaymentTaskStatus(
    taskId: number,
    status: ManualPaymentTaskStatus
  ): Observable<ManualPaymentTaskResponse> { return this.manualPaymentTasksApi.updateManagerManualPaymentTaskStatus(taskId, status); }

  updateManagerManualPaymentTask(
    taskId: number,
    request: UpdateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> { return this.manualPaymentTasksApi.updateManagerManualPaymentTask(taskId, request); }

  getDictionarySummary(includeAdminTabs: boolean): Observable<DictionarySummary>{ return this.dictionariesApi.getDictionarySummary(includeAdminTabs); }

  getAdminCategories(keyword = ''): Observable<AdminCategory[]>{ return this.dictionariesApi.getAdminCategories(keyword); }

  createAdminCategory(request: TitleRequest): Observable<AdminCategory>{ return this.dictionariesApi.createAdminCategory(request); }

  updateAdminCategory(id: number, request: TitleRequest): Observable<AdminCategory>{ return this.dictionariesApi.updateAdminCategory(id, request); }

  deleteAdminCategory(id: number): Observable<void>{ return this.dictionariesApi.deleteAdminCategory(id); }

  getAdminSubCategories(keyword = '', categoryId?: number | null): Observable<AdminSubCategory[]>{ return this.dictionariesApi.getAdminSubCategories(keyword, categoryId); }

  createAdminSubCategory(request: SubCategoryRequest): Observable<AdminSubCategory>{ return this.dictionariesApi.createAdminSubCategory(request); }

  updateAdminSubCategory(id: number, request: SubCategoryRequest): Observable<AdminSubCategory>{ return this.dictionariesApi.updateAdminSubCategory(id, request); }

  deleteAdminSubCategory(id: number): Observable<void>{ return this.dictionariesApi.deleteAdminSubCategory(id); }

  getAdminCities(keyword = ''): Observable<AdminCity[]>{ return this.dictionariesApi.getAdminCities(keyword); }

  createAdminCity(request: TitleRequest): Observable<AdminCity>{ return this.dictionariesApi.createAdminCity(request); }

  updateAdminCity(id: number, request: TitleRequest): Observable<AdminCity>{ return this.dictionariesApi.updateAdminCity(id, request); }

  deleteAdminCity(id: number): Observable<void>{ return this.dictionariesApi.deleteAdminCity(id); }

  getAdminProducts(keyword = ''): Observable<ProductsResponse>{ return this.dictionariesApi.getAdminProducts(keyword); }

  createAdminProduct(request: ProductRequest): Observable<AdminProduct>{ return this.dictionariesApi.createAdminProduct(request); }

  updateAdminProduct(id: number, request: ProductRequest): Observable<AdminProduct>{ return this.dictionariesApi.updateAdminProduct(id, request); }

  deleteAdminProduct(id: number): Observable<void>{ return this.dictionariesApi.deleteAdminProduct(id); }

  getAdminBots(keyword = '', page = 0, size = 50): Observable<BotsResponse>{ return this.dictionariesApi.getAdminBots(keyword, page, size); }

  getAdminBot(id: number): Observable<AdminBot>{ return this.dictionariesApi.getAdminBot(id); }

  createAdminBot(request: BotRequest): Observable<AdminBot>{ return this.dictionariesApi.createAdminBot(request); }

  updateAdminBot(id: number, request: BotRequest): Observable<AdminBot>{ return this.dictionariesApi.updateAdminBot(id, request); }

  deleteAdminBot(id: number): Observable<void>{ return this.dictionariesApi.deleteAdminBot(id); }

  importAdminBots(file: File): Observable<BotImportResponse>{ return this.dictionariesApi.importAdminBots(file); }

  openAdminBotBrowser(botId: number): Observable<BotBrowserOpenResponse> {
    return this.http.post<BotBrowserOpenResponse>(
      this.apiUrl(botBrowserApiPaths(botId).open),
      { heartbeatSupported: true }
    );
  }

  getBotBrowserMetadata(botId: number): Observable<BotBrowserMetadata> {
    return this.http.get<BotBrowserMetadata>(this.apiUrl(botBrowserApiPaths(botId).metadata));
  }

  heartbeatAdminBotBrowser(botId: number, sessionId: string): Observable<void> {
    return this.http.post<void>(this.apiUrl(botBrowserSessionHeartbeatPath(botId, sessionId)), {});
  }

  closeAdminBotBrowser(botId: number, sessionId?: string | null): Observable<void> {
    const path = sessionId
      ? botBrowserSessionClosePath(botId, sessionId)
      : botBrowserApiPaths(botId).close;
    return this.http.post<void>(this.apiUrl(path), {});
  }

  getAdminUsers(): Observable<AdminUser[]> {
    return this.http.get<AdminUser[]>(this.apiUrl('/api/admin/users'));
  }

  createAdminUser(request: CreateKeycloakUserRequest): Observable<CreatedKeycloakUserResponse> {
    return this.http.post<CreatedKeycloakUserResponse>(this.apiUrl('/api/admin/users'), request);
  }

  updateAdminUser(id: number, request: UpdateKeycloakUserRequest): Observable<AdminUser> {
    return this.http.put<AdminUser>(this.apiUrl(`/api/admin/users/${id}`), request);
  }

  deleteAdminUser(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/users/${id}`));
  }

  updateAdminUserPhoto(id: number, photo: File): Observable<AdminUser> {
    const formData = new FormData();
    formData.append('photo', photo);
    return this.http.post<AdminUser>(this.apiUrl(`/api/admin/users/${id}/photo`), formData);
  }

  changeAdminUserPassword(id: number, request: ChangeKeycloakPasswordRequest): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/admin/users/${id}/password`), request);
  }

  getAdminUserAssignmentOptions(): Observable<AssignmentOptions> {
    return this.http.get<AssignmentOptions>(this.apiUrl('/api/admin/users/assignment-options'));
  }

  getAdminUserAssignments(id: number): Observable<UserAssignments> {
    return this.http.get<UserAssignments>(this.apiUrl(`/api/admin/users/${id}/assignments`));
  }

  updateAdminUserAssignments(id: number, request: UpdateUserAssignmentsRequest): Observable<UserAssignments> {
    return this.http.put<UserAssignments>(this.apiUrl(`/api/admin/users/${id}/assignments`), request);
  }

  getAdminPromoTextManagement(keyword = ''): Observable<PromoTextManagementResponse>{ return this.dictionariesApi.getAdminPromoTextManagement(keyword); }

  createAdminPromoText(request: PromoTextRequest): Observable<AdminPromoText>{ return this.dictionariesApi.createAdminPromoText(request); }

  updateAdminPromoText(id: number, request: PromoTextRequest): Observable<AdminPromoText>{ return this.dictionariesApi.updateAdminPromoText(id, request); }

  saveAdminPromoTextAssignment(request: PromoTextAssignmentRequest): Observable<PromoTextAssignment>{ return this.dictionariesApi.saveAdminPromoTextAssignment(request); }

  resetAdminPromoTextAssignment(managerId: number, section: string, buttonKey: string): Observable<void>{ return this.dictionariesApi.resetAdminPromoTextAssignment(managerId, section, buttonKey); }

  getAdminManagerTexts(keyword = ''): Observable<AdminManagerText[]>{ return this.dictionariesApi.getAdminManagerTexts(keyword); }

  updateAdminManagerText(managerId: number, request: ManagerTextRequest): Observable<AdminManagerText>{ return this.dictionariesApi.updateAdminManagerText(managerId, request); }

  getAdminNagulSettings(): Observable<AdminNagulSettings> {
    return this.http.get<AdminNagulSettings>(this.apiUrl('/api/admin/settings/nagul'));
  }

  updateAdminNagulSettings(request: NagulSettingsRequest): Observable<AdminNagulSettings> {
    return this.http.put<AdminNagulSettings>(this.apiUrl('/api/admin/settings/nagul'), request);
  }

  getAdminTelegramReportSettings(): Observable<AdminTelegramReportScheduleSettings> {
    return this.http.get<AdminTelegramReportScheduleSettings>(this.apiUrl('/api/admin/settings/telegram-reports'));
  }

  updateAdminTelegramReportSettings(
    request: TelegramReportScheduleSettingsRequest
  ): Observable<AdminTelegramReportScheduleSettings> {
    return this.http.put<AdminTelegramReportScheduleSettings>(
      this.apiUrl('/api/admin/settings/telegram-reports'),
      request
    );
  }

  getAdminWhatsAppGroupSyncSettings(): Observable<AdminWhatsAppGroupSyncSettings> {
    return this.http.get<AdminWhatsAppGroupSyncSettings>(this.apiUrl('/api/admin/settings/whatsapp-group-sync'));
  }

  updateAdminWhatsAppGroupSyncSettings(
    request: WhatsAppGroupSyncSettingsRequest
  ): Observable<AdminWhatsAppGroupSyncSettings> {
    return this.http.put<AdminWhatsAppGroupSyncSettings>(
      this.apiUrl('/api/admin/settings/whatsapp-group-sync'),
      request
    );
  }

  runAdminWhatsAppGroupSync(): Observable<AdminWhatsAppGroupSyncSettings> {
    return this.http.post<AdminWhatsAppGroupSyncSettings>(
      this.apiUrl('/api/admin/settings/whatsapp-group-sync/run'),
      {}
    );
  }

  getAdminClientPublicationProgressReportSettings(): Observable<AdminClientPublicationProgressReportSettings> {
    return this.http.get<AdminClientPublicationProgressReportSettings>(
      this.apiUrl('/api/admin/settings/client-publication-progress-reports')
    );
  }

  updateAdminClientPublicationProgressReportSettings(
    request: ClientPublicationProgressReportSettingsRequest
  ): Observable<AdminClientPublicationProgressReportSettings> {
    return this.http.put<AdminClientPublicationProgressReportSettings>(
      this.apiUrl('/api/admin/settings/client-publication-progress-reports'),
      request
    );
  }

  getAdminClientMessageSettings(): Observable<AdminClientMessageSettings> {
    return this.http.get<AdminClientMessageSettings>(this.apiUrl('/api/admin/settings/client-messages'));
  }

  updateAdminClientMessageSettings(request: ClientMessageSettingsRequest): Observable<AdminClientMessageSettings> {
    return this.http.put<AdminClientMessageSettings>(
      this.apiUrl('/api/admin/settings/client-messages'),
      request
    );
  }

  getAdminClientMessageMonitor(): Observable<AdminClientMessageMonitor> {
    return this.http.get<AdminClientMessageMonitor>(this.apiUrl('/api/admin/client-messages/monitor'));
  }

  getAdminClientMessageMaintenancePreview(): Observable<AdminClientMessageMaintenancePreview> {
    return this.http.get<AdminClientMessageMaintenancePreview>(
      this.apiUrl('/api/admin/client-messages/maintenance/preview')
    );
  }

  applyAdminClientMessageMaintenance(
    action: 'company-statuses' | 'payment-overdue' | 'missing-bad-tasks' | 'archive-offers' | 'publication-dates' | 'publication-completed'
  ): Observable<AdminMaintenanceApplyResponse> {
    return this.http.post<AdminMaintenanceApplyResponse>(
      this.apiUrl(`/api/admin/client-messages/maintenance/${action}`),
      {}
    );
  }

  updateAdminClientMessageMonitorSettings(enabled: boolean): Observable<AdminClientMessageMonitorSettings> {
    return this.http.put<AdminClientMessageMonitorSettings>(
      this.apiUrl('/api/admin/client-messages/monitor'),
      { enabled }
    );
  }

  retryAdminClientMessageNow(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.http.post<AdminClientMessageMonitor>(
      this.apiUrl(`/api/admin/client-messages/monitor/states/${stateId}/retry-now`),
      {}
    );
  }

  disableAdminClientMessageCandidate(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.http.post<AdminClientMessageMonitor>(
      this.apiUrl(`/api/admin/client-messages/monitor/states/${stateId}/disable`),
      {}
    );
  }

  markAdminClientMessageCandidateDone(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.http.post<AdminClientMessageMonitor>(
      this.apiUrl(`/api/admin/client-messages/monitor/states/${stateId}/mark-done`),
      {}
    );
  }

  runAdminSharedChatLinkSync(): Observable<AdminSharedChatLinkSyncResponse> {
    return this.http.post<AdminSharedChatLinkSyncResponse>(
      this.apiUrl('/api/admin/settings/shared-chat-links/sync'),
      {}
    );
  }

  getManagerBoard(query: ManagerBoardQuery = {}): Observable<ManagerBoard> { return this.managerBoardApi.getManagerBoard(query); }

  getManagerControlToday(): Observable<ManagerControlSummary> { return this.managerControlApi.getManagerControlToday(); }

  sendManagerDailyAuditToTelegram(date?: string): Observable<ManagerSummaryTelegramSendResponse> { return this.managerReportsApi.sendManagerDailyAuditToTelegram(date); }

  startManagerReportReviewTest(
    date?: string,
    managerId?: number
  ): Observable<ManagerReportReviewTestStartResponse> { return this.managerReportsApi.startManagerReportReviewTest(date, managerId); }

  getManagerReportReviews(date?: string): Observable<ManagerReportReview[]> { return this.managerReportsApi.getManagerReportReviews(date); }

  resolveManagerReportDispute(
    reviewId: number,
    payload: ManagerReportDisputeResolutionPayload
  ): Observable<void> { return this.managerReportsApi.resolveManagerReportDispute(reviewId, payload); }

  syncManagerControlToday(): Observable<ManagerControlSummary> { return this.managerControlApi.syncManagerControlToday(); }

  getManagerControlDetails(managerId: number): Observable<ManagerControlManagerDetail> { return this.managerControlApi.getManagerControlDetails(managerId); }

  syncManagerControlDetails(managerId: number): Observable<ManagerControlManagerDetail> { return this.managerControlApi.syncManagerControlDetails(managerId); }

  reconcileManagerControlClientMessages(managerId: number): Observable<ManagerControlClientMessageReconciliation> { return this.managerControlApi.reconcileManagerControlClientMessages(managerId); }

  acceptManagerControl(controlId: number): Observable<ManagerControlManagerDetail> { return this.managerControlApi.acceptManagerControl(controlId); }

  markManagerControlStage(controlId: number, payload: ManagerControlStagePayload): Observable<ManagerControlManagerDetail> { return this.managerControlApi.markManagerControlStage(controlId, payload); }

  closeManagerControlDay(controlId: number, payload: ManagerControlClosePayload): Observable<ManagerControlCloseResponse> { return this.managerControlApi.closeManagerControlDay(controlId, payload); }

  actionManagerControlItem(itemId: number, payload: ManagerControlActionPayload): Observable<void> { return this.managerControlApi.actionManagerControlItem(itemId, payload); }

  actionManagerControlConcreteItem(
    concreteItemId: number,
    payload: ManagerControlActionPayload
  ): Observable<ManagerControlConcreteItem> { return this.managerControlApi.actionManagerControlConcreteItem(concreteItemId, payload); }

  sendManagerControlClientMessage(concreteItemId: number): Observable<ManagerControlConcreteItem> { return this.managerControlApi.sendManagerControlClientMessage(concreteItemId); }

  replyManagerControlClientMessage(
    concreteItemId: number,
    payload: ManagerControlClientReplyPayload
  ): Observable<ManagerControlConcreteItem> { return this.managerControlApi.replyManagerControlClientMessage(concreteItemId, payload); }

  repairManagerControlConcreteItem(concreteItemId: number): Observable<ManagerControlConcreteItem> { return this.managerControlApi.repairManagerControlConcreteItem(concreteItemId); }

  getManagerWorkerRiskIncidents(
    status: WorkerRiskIncidentStatus = 'OPEN',
    page = 0,
    size = 50
  ): Observable<Page<WorkerRiskIncident>> { return this.managerWorkerRiskApi.getManagerWorkerRiskIncidents(status, page, size); }

  setManagerWorkerRiskIncidentResolution(
    incidentId: number,
    action: WorkerRiskResolutionAction,
    penaltyPoints?: number,
    comment?: string | null
  ): Observable<WorkerRiskIncident> { return this.managerWorkerRiskApi.setManagerWorkerRiskIncidentResolution(incidentId, action, penaltyPoints, comment); }

  rollbackManagerWorkerRiskIncident(incidentId: number): Observable<WorkerRiskIncident> { return this.managerWorkerRiskApi.rollbackManagerWorkerRiskIncident(incidentId); }

  getManagerArchiveOrders(query: ManagerArchiveOrdersQuery = {}): Observable<Page<ArchiveOrderListItem>> { return this.managerArchiveApi.getManagerArchiveOrders(query); }

  getManagerArchiveOrder(orderId: number): Observable<ArchiveOrderDetailsPayload> { return this.managerArchiveApi.getManagerArchiveOrder(orderId); }

  restoreManagerArchiveOrder(orderId: number, targetStatus = 'Архив'): Observable<ArchiveRestoreResult> { return this.managerArchiveApi.restoreManagerArchiveOrder(orderId, targetStatus); }

  updateManagerCompanyStatus(companyId: number, status: string): Observable<void> { return this.managerCompanyActionsApi.updateManagerCompanyStatus(companyId, status); }

  updateManagerOrderStatus(orderId: number, status: string): Observable<void> { return this.managerOrdersApi.updateManagerOrderStatus(orderId, status); }

  getManagerManualCardPaymentContext(orderId: number): Observable<ManualCardPaymentContext> { return this.managerManualPaymentsApi.getManagerManualCardPaymentContext(orderId); }

  confirmManagerManualCardPayment(
    orderId: number,
    request: ManualCardPaymentConfirmationRequest
  ): Observable<ManagerManualCardPaymentResult> { return this.managerManualPaymentsApi.confirmManagerManualCardPayment(orderId, request); }

  updateManagerOrderClientWaiting(orderId: number, waitingForClient: boolean): Observable<void>{ return this.workerApi.updateManagerOrderClientWaiting(orderId, waitingForClient); }

  getManagerCompanyOrderCreate(companyId: number): Observable<CompanyOrderCreatePayload> { return this.managerCompanyActionsApi.getManagerCompanyOrderCreate(companyId); }

  createManagerCompanyOrder(
    companyId: number,
    request: CompanyOrderCreateRequest
  ): Observable<CompanyOrderCreateResult> { return this.managerCompanyActionsApi.createManagerCompanyOrder(companyId, request); }

  getManagerOrderEdit(orderId: number): Observable<OrderEditPayload> {
    return this.orderEditorApi.getEdit(orderId);
  }

  updateManagerOrder(orderId: number, request: OrderUpdateRequest): Observable<OrderEditPayload> {
    return this.orderEditorApi.update(orderId, request);
  }

  deleteManagerOrder(orderId: number): Observable<void> {
    return this.orderEditorApi.delete(orderId);
  }

  getManagerOrderDetails(orderId: number): Observable<OrderDetailsPayload> { return this.managerOrdersApi.getManagerOrderDetails(orderId); }

  getManagerOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> { return this.companyReportApi.getManagerOrderCompanyReport(orderId); }

  startManagerOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> { return this.companyReportApi.startManagerOrderCompanyReport(orderId); }

  refreshManagerOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> { return this.companyReportApi.refreshManagerOrderCompanyReport(orderId); }

  addManagerOrderReview(orderId: number): Observable<OrderDetailsPayload> { return this.managerReviewActionsApi.addManagerOrderReview(orderId); }

  updateManagerOrderReviewText(orderId: number, reviewId: number, text: string): Observable<OrderReviewItem>{ return this.orderReviewsApi.updateManagerOrderReviewText(orderId, reviewId, text); }

  updateManagerOrderReviewAnswer(orderId: number, reviewId: number, answer: string): Observable<OrderReviewItem>{ return this.orderReviewsApi.updateManagerOrderReviewAnswer(orderId, reviewId, answer); }

  updateManagerOrderReviewNote(orderId: number, reviewId: number, comment: string): Observable<OrderReviewItem>{ return this.orderReviewsApi.updateManagerOrderReviewNote(orderId, reviewId, comment); }

  updateManagerOrderReview(orderId: number, reviewId: number, request: ReviewUpdateRequest): Observable<OrderReviewItem>{ return this.orderReviewsApi.updateManagerOrderReview(orderId, reviewId, request); }

  uploadManagerOrderReviewPhoto(orderId: number, reviewId: number, file: File): Observable<OrderReviewItem>{ return this.orderReviewsApi.uploadManagerOrderReviewPhoto(orderId, reviewId, file); }

  deleteManagerOrderReview(orderId: number, reviewId: number): Observable<OrderDetailsPayload>{ return this.orderReviewsApi.deleteManagerOrderReview(orderId, reviewId); }

  publishManagerOrderReview(orderId: number, reviewId: number, source?: WorkerActivitySource): Observable<OrderDetailsPayload> { return this.managerReviewActionsApi.publishManagerOrderReview(orderId, reviewId, source); }

  changeManagerOrderReviewText(orderId: number, reviewId: number): Observable<OrderReviewItem> { return this.managerReviewActionsApi.changeManagerOrderReviewText(orderId, reviewId); }

  assignManagerOrderReviewNewAccount(orderId: number, reviewId: number, source?: WorkerActivitySource): Observable<OrderReviewItem> { return this.managerReviewActionsApi.assignManagerOrderReviewNewAccount(orderId, reviewId, source); }

  changeManagerOrderReviewBot(orderId: number, reviewId: number, source?: WorkerActivitySource): Observable<OrderReviewItem> { return this.managerReviewActionsApi.changeManagerOrderReviewBot(orderId, reviewId, source); }

  deactivateManagerOrderReviewBot(orderId: number, reviewId: number, botId: number, source?: WorkerActivitySource): Observable<OrderReviewItem> { return this.managerReviewActionsApi.deactivateManagerOrderReviewBot(orderId, reviewId, botId, source); }

  revealManagerOrderReviewCredential(
    orderId: number,
    reviewId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse> { return this.managerReviewActionsApi.revealManagerOrderReviewCredential(orderId, reviewId, field, source); }

  revealManagerBadReviewTaskCredential(
    orderId: number,
    taskId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse> { return this.managerReviewTasksApi.revealManagerBadReviewTaskCredential(orderId, taskId, field, source); }

  revealManagerRecoveryTaskCredential(
    orderId: number,
    taskId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse> { return this.managerReviewTasksApi.revealManagerRecoveryTaskCredential(orderId, taskId, field, source); }

  cancelManagerBadReviewTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> { return this.managerReviewTasksApi.cancelManagerBadReviewTask(orderId, taskId); }

  completeManagerBadReviewTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> { return this.managerReviewTasksApi.completeManagerBadReviewTask(orderId, taskId); }

  updateManagerBadReviewTask(
    orderId: number,
    taskId: number,
    request: BadReviewTaskUpdateRequest
  ): Observable<OrderDetailsPayload> { return this.managerReviewTasksApi.updateManagerBadReviewTask(orderId, taskId, request); }

  changeManagerBadReviewTaskBot(orderId: number, taskId: number): Observable<OrderDetailsPayload> { return this.managerReviewTasksApi.changeManagerBadReviewTaskBot(orderId, taskId); }

  createManagerReviewRecoveryTask(orderId: number, reviewId: number): Observable<OrderDetailsPayload> { return this.managerReviewTasksApi.createManagerReviewRecoveryTask(orderId, reviewId); }

  updateManagerReviewRecoveryTask(
    orderId: number,
    taskId: number,
    request: ReviewRecoveryTaskUpdateRequest
  ): Observable<OrderDetailsPayload> { return this.managerReviewTasksApi.updateManagerReviewRecoveryTask(orderId, taskId, request); }

  completeManagerReviewRecoveryTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> { return this.managerReviewTasksApi.completeManagerReviewRecoveryTask(orderId, taskId); }

  deleteManagerReviewRecoveryTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> { return this.managerReviewTasksApi.deleteManagerReviewRecoveryTask(orderId, taskId); }

  markManagerRecoveryClientNotified(orderId: number, batchId: number): Observable<OrderDetailsPayload> { return this.managerReviewTasksApi.markManagerRecoveryClientNotified(orderId, batchId); }

  createManagerReviewHelpDrafts(orderId: number): Observable<OrderDetailsPayload> { return this.managerReviewActionsApi.createManagerReviewHelpDrafts(orderId); }

  createManagerReviewHelpDraftForCard(orderId: number, reviewId: number): Observable<OrderDetailsPayload> { return this.managerReviewActionsApi.createManagerReviewHelpDraftForCard(orderId, reviewId); }

  updateManagerCompanyNote(companyId: number, companyComments: string): Observable<void> { return this.managerCompanyActionsApi.updateManagerCompanyNote(companyId, companyComments); }

  updateManagerOrderNote(orderId: number, orderComments: string): Observable<OrderNotesResponse>{ return this.orderReviewsApi.updateManagerOrderNote(orderId, orderComments); }

  updateManagerOrderCompanyNote(orderId: number, companyComments: string): Observable<OrderNotesResponse>{ return this.orderReviewsApi.updateManagerOrderCompanyNote(orderId, companyComments); }

  getCommonBillingAccounts(): Observable<CommonBillingAccountResponse[]>{ return this.commonBillingApi.getCommonBillingAccounts(); }

  getCommonBillingAccountsForCompany(companyId: number): Observable<CommonBillingAccountResponse[]> {
    return this.companyBillingApi.getCommonBillingAccountsForCompany(companyId);
  }

  createCommonBillingAccount(request: CommonBillingAccountRequest): Observable<CommonBillingAccountResponse> {
    return this.companyBillingApi.createCommonBillingAccount(request);
  }

  updateCommonBillingAccount(
    accountId: number,
    request: CommonBillingAccountRequest
  ): Observable<CommonBillingAccountResponse> {
    return this.companyBillingApi.updateCommonBillingAccount(accountId, request);
  }

  addCommonBillingCompany(accountId: number, companyId: number): Observable<CommonBillingAccountResponse>{ return this.commonBillingApi.addCommonBillingCompany(accountId, companyId); }

  removeCommonBillingCompany(
    accountId: number,
    companyId: number,
    detachCurrent = false
  ): Observable<CommonBillingAccountResponse> {
    return this.companyBillingApi.removeCommonBillingCompany(accountId, companyId, detachCurrent);
  }

  getCommonInvoice(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.getCommonInvoice(invoiceId); }

  sendCommonInvoice(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.sendCommonInvoice(invoiceId); }

  changeCommonInvoicePaymentMode(
    invoiceId: number,
    mode: InvoicePaymentMode
  ): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.changeCommonInvoicePaymentMode(invoiceId, mode); }

  getCommonInvoicePaymentRouteChangeContext(
    invoiceId: number
  ): Observable<CommonInvoicePaymentRouteChangeContextResponse>{ return this.commonBillingApi.getCommonInvoicePaymentRouteChangeContext(invoiceId); }

  changeCommonInvoicePaymentRoute(
    invoiceId: number,
    target: CommonInvoicePaymentRouteChangeTarget,
    expectedPaymentEvidenceToken: string,
    expectedTargetPaymentProfileId?: number | null
  ): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.changeCommonInvoicePaymentRoute(invoiceId, target, expectedPaymentEvidenceToken, expectedTargetPaymentProfileId); }

  markCommonInvoicePaperInvoiceIssued(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.markCommonInvoicePaperInvoiceIssued(invoiceId); }

  markCommonInvoicePaperInvoicePaid(
    invoiceId: number,
    request: ManualPaymentConfirmationRequest
  ): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.markCommonInvoicePaperInvoicePaid(invoiceId, request); }

  remindCommonInvoice(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.remindCommonInvoice(invoiceId); }

  markCommonInvoicePaid(
    invoiceId: number,
    request: ManualPaymentConfirmationRequest

  ): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.markCommonInvoicePaid(invoiceId, request); }

  getCommonManualPaymentMode(invoiceId: number): Observable<CommonManualPaymentAttributionModeResponse>{ return this.commonBillingApi.getCommonManualPaymentMode(invoiceId); }

  getCommonManualPaymentOptions(invoiceId: number): Observable<CommonManualPaymentOptions>{ return this.commonBillingApi.getCommonManualPaymentOptions(invoiceId); }

  confirmCommonManualPayment(
    invoiceId: number,
    mode: CommonManualPaymentMode,
    request: CommonManualPaymentAttributionRequest
  ): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.confirmCommonManualPayment(invoiceId, mode, request); }

  reportCommonInvoiceManualCardPayment(invoiceId: number, reason: string): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.reportCommonInvoiceManualCardPayment(invoiceId, reason); }

  confirmCommonInvoiceContractorSource(
    invoiceId: number,
    request: ContractorCommonSourceConfirmationRequest
  ): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.confirmCommonInvoiceContractorSource(invoiceId, request); }

  repairCommonInvoicePaymentRoute(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.repairCommonInvoicePaymentRoute(invoiceId); }

  resolveCommonInvoiceTechnicalTail(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.resolveCommonInvoiceTechnicalTail(invoiceId); }

  resolveCommonInvoicePaymentNotification(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.resolveCommonInvoicePaymentNotification(invoiceId); }

  markCommonInvoiceUnpaid(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.markCommonInvoiceUnpaid(invoiceId); }

  markCommonInvoiceBan(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.markCommonInvoiceBan(invoiceId); }

  getCommonInvoiceArchivePreview(invoiceId: number): Observable<CommonInvoiceArchivePreviewResponse>{ return this.commonBillingApi.getCommonInvoiceArchivePreview(invoiceId); }

  archiveCommonInvoice(invoiceId: number, comment = ''): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.archiveCommonInvoice(invoiceId, comment); }

  retryCommonInvoiceAttention(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.retryCommonInvoiceAttention(invoiceId); }

  resolveCommonInvoiceAttention(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.resolveCommonInvoiceAttention(invoiceId); }

  applyCommonInvoiceLatePayment(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.applyCommonInvoiceLatePayment(invoiceId); }

  confirmCommonInvoiceFinalPaymentCancelCheck(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.confirmCommonInvoiceFinalPaymentCancelCheck(invoiceId); }

  confirmCommonInvoicePaymentInitCheck(
    invoiceId: number,
    evidenceToken?: string | null
  ): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.confirmCommonInvoicePaymentInitCheck(invoiceId, evidenceToken); }

  markCommonInvoiceOrderPaid(
    invoiceId: number,
    orderId: number,
    request: ManualPaymentConfirmationRequest
  ): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.markCommonInvoiceOrderPaid(invoiceId, orderId, request); }

  approveCommonInvoiceReviewOrders(invoiceId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.approveCommonInvoiceReviewOrders(invoiceId); }

  detachCommonInvoiceOrder(invoiceId: number, orderId: number): Observable<CommonInvoiceDetailsResponse>{ return this.commonBillingApi.detachCommonInvoiceOrder(invoiceId, orderId); }

  deleteCommonInvoiceWithOrders(invoiceId: number): Observable<void>{ return this.commonBillingApi.deleteCommonInvoiceWithOrders(invoiceId); }

  getReviewCheck(orderDetailId: string, capabilityToken?: string | null): Observable<ReviewCheckPayload> {
    return this.http.get<ReviewCheckPayload>(
      this.reviewCheckUrl(orderDetailId, capabilityToken),
      this.reviewCapabilityOptions(capabilityToken)
    );
  }

  saveReviewCheck(orderDetailId: string, request: ReviewCheckUpdateRequest, capabilityToken?: string | null): Observable<ReviewCheckPayload> {
    return this.http.put<ReviewCheckPayload>(
      this.reviewCheckUrl(orderDetailId, capabilityToken),
      request,
      this.reviewCapabilityOptions(capabilityToken)
    );
  }

  updateReviewCheckText(orderDetailId: string, reviewId: number, text: string, capabilityToken?: string | null): Observable<ReviewCheckReview> {
    return this.http.put<ReviewCheckReview>(
      this.reviewCheckUrl(orderDetailId, capabilityToken, `/reviews/${reviewId}/text`),
      { text },
      this.reviewCapabilityOptions(capabilityToken)
    );
  }

  updateReviewCheckAnswer(orderDetailId: string, reviewId: number, answer: string, capabilityToken?: string | null): Observable<ReviewCheckReview> {
    return this.http.put<ReviewCheckReview>(
      this.reviewCheckUrl(orderDetailId, capabilityToken, `/reviews/${reviewId}/answer`),
      { answer },
      this.reviewCapabilityOptions(capabilityToken)
    );
  }

  approveReviewCheck(orderDetailId: string, request: ReviewCheckUpdateRequest, capabilityToken?: string | null): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      this.reviewCheckUrl(orderDetailId, capabilityToken, '/approve'),
      request,
      this.reviewCapabilityOptions(capabilityToken)
    );
  }

  sendReviewCheckToCorrection(orderDetailId: string, request: ReviewCheckUpdateRequest, capabilityToken?: string | null): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      this.reviewCheckUrl(orderDetailId, capabilityToken, '/correction'),
      request,
      this.reviewCapabilityOptions(capabilityToken)
    );
  }

  sendReviewCheckToCheck(orderDetailId: string, request: ReviewCheckUpdateRequest): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(this.apiUrl(`/api/review-check/${orderDetailId}/send-to-check`), request);
  }

  markReviewCheckPaid(orderDetailId: string): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(this.apiUrl(`/api/review-check/${orderDetailId}/pay-ok`), {});
  }

  updateReviewCheckNote(orderDetailId: string, reviewId: number, comment: string): Observable<ReviewCheckReview> {
    return this.http.put<ReviewCheckReview>(
      this.apiUrl(`/api/review-check/${orderDetailId}/reviews/${reviewId}/note`),
      { comment }
    );
  }

  updateReviewCheckOrderNote(orderDetailId: string, orderComments: string): Observable<ReviewCheckNotes> {
    return this.http.put<ReviewCheckNotes>(this.apiUrl(`/api/review-check/${orderDetailId}/order-note`), { orderComments });
  }

  updateReviewCheckCompanyNote(orderDetailId: string, companyComments: string): Observable<ReviewCheckNotes> {
    return this.http.put<ReviewCheckNotes>(this.apiUrl(`/api/review-check/${orderDetailId}/company-note`), { companyComments });
  }

  private reviewCheckUrl(orderDetailId: string, capabilityToken?: string | null, suffix = ''): string {
    return capabilityToken
      ? this.apiUrl(`/api/review-capability${suffix}`)
      : this.apiUrl(`/api/review-check/${orderDetailId}${suffix}`);
  }

  private reviewCapabilityOptions(capabilityToken?: string | null): { headers?: HttpHeaders } {
    return capabilityToken && OPAQUE_REVIEW_CAPABILITY.test(capabilityToken)
      ? { headers: new HttpHeaders({ 'X-Review-Capability': capabilityToken }) }
      : {};
  }

  getManagerCompanyEdit(companyId: number): Observable<CompanyEditPayload> {
    return this.companyEditorApi.getManagerCompanyEdit(companyId);
  }

  updateManagerCompany(companyId: number, request: CompanyUpdateRequest): Observable<CompanyEditPayload> {
    return this.companyEditorApi.updateManagerCompany(companyId, request);
  }

  repairManagerCompanyChatBinding(companyId: number): Observable<CompanyChatBindingRepair> { return this.managerCompanyActionsApi.repairManagerCompanyChatBinding(companyId); }

  getManagerCompanySubcategories(categoryId: number): Observable<ManagerOption[]> {
    return this.companyEditorApi.getManagerCompanySubcategories(categoryId);
  }

  deleteManagerCompanyWorker(companyId: number, workerId: number): Observable<CompanyEditPayload> {
    return this.companyEditorApi.deleteManagerCompanyWorker(companyId, workerId);
  }

  deleteManagerCompanyFilial(companyId: number, filialId: number): Observable<CompanyEditPayload> {
    return this.companyEditorApi.deleteManagerCompanyFilial(companyId, filialId);
  }

  getManagerCompanyFilialDeletionPreview(companyId: number, filialId: number): Observable<FilialDeletionPreview> {
    return this.companyEditorApi.getManagerCompanyFilialDeletionPreview(companyId, filialId);
  }

  restoreManagerCompanyFilial(companyId: number, filialId: number): Observable<CompanyEditPayload> {
    return this.companyEditorApi.restoreManagerCompanyFilial(companyId, filialId);
  }

  updateManagerCompanyFilial(
    companyId: number,
    filialId: number,
    request: CompanyFilialUpdateRequest
  ): Observable<CompanyEditPayload> {
    return this.companyEditorApi.updateManagerCompanyFilial(companyId, filialId, request);
  }

  getWorkerBoard(query: WorkerBoardQuery = {}): Observable<WorkerBoard>{ return this.workerApi.getWorkerBoard(query); }

  getWorkerOverdueOrders(): Observable<ManagerOverdueOrders>{ return this.workerApi.getWorkerOverdueOrders(); }

  updateWorkerOrderStatus(orderId: number, status: string): Observable<void>{ return this.workerApi.updateWorkerOrderStatus(orderId, status); }

  updateWorkerOrderClientWaiting(orderId: number, waitingForClient: boolean): Observable<void>{ return this.workerApi.updateWorkerOrderClientWaiting(orderId, waitingForClient); }

  updateWorkerOrderNote(orderId: number, orderComments: string): Observable<void>{ return this.workerApi.updateWorkerOrderNote(orderId, orderComments); }

  updateWorkerOrderCompanyNote(orderId: number, companyComments: string): Observable<void>{ return this.workerApi.updateWorkerOrderCompanyNote(orderId, companyComments); }

  changeWorkerReviewBot(reviewId: number, source?: WorkerActivitySource): Observable<BotChangeResponse>{ return this.workerApi.changeWorkerReviewBot(reviewId, source); }

  deactivateWorkerReviewBot(reviewId: number, botId: number, source?: WorkerActivitySource): Observable<void>{ return this.workerApi.deactivateWorkerReviewBot(reviewId, botId, source); }

  publishWorkerReview(reviewId: number): Observable<void>{ return this.workerApi.publishWorkerReview(reviewId); }

  nagulWorkerReview(reviewId: number): Observable<WorkerActionResponse>{ return this.workerApi.nagulWorkerReview(reviewId); }

  completeWorkerBadReviewTask(taskId: number): Observable<void>{ return this.workerApi.completeWorkerBadReviewTask(taskId); }

  updateWorkerBadReviewTask(taskId: number, taskText: string, scheduledDate?: string | null): Observable<void>{ return this.workerApi.updateWorkerBadReviewTask(taskId, taskText, scheduledDate); }

  changeWorkerBadReviewTaskBot(taskId: number): Observable<BotChangeResponse>{ return this.workerApi.changeWorkerBadReviewTaskBot(taskId); }

  deactivateWorkerBadReviewTaskBot(taskId: number, botId: number): Observable<void>{ return this.workerApi.deactivateWorkerBadReviewTaskBot(taskId, botId); }

  updateWorkerRecoveryTask(
    taskId: number,
    recoveryText: string,
    scheduledDate?: string | null,
    recoveryAnswer?: string | null
  ): Observable<void>{ return this.workerApi.updateWorkerRecoveryTask(taskId, recoveryText, scheduledDate, recoveryAnswer); }

  completeWorkerRecoveryTask(taskId: number): Observable<void>{ return this.workerApi.completeWorkerRecoveryTask(taskId); }

  changeWorkerRecoveryTaskBot(taskId: number): Observable<BotChangeResponse>{ return this.workerApi.changeWorkerRecoveryTaskBot(taskId); }

  deactivateWorkerRecoveryTaskBot(taskId: number, botId: number): Observable<void>{ return this.workerApi.deactivateWorkerRecoveryTaskBot(taskId, botId); }

  revealWorkerReviewCredential(
    reviewId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse>{ return this.workerApi.revealWorkerReviewCredential(reviewId, field, source); }

  revealWorkerBadReviewTaskCredential(
    taskId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse>{ return this.workerApi.revealWorkerBadReviewTaskCredential(taskId, field, source); }

  revealWorkerRecoveryTaskCredential(
    taskId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse>{ return this.workerApi.revealWorkerRecoveryTaskCredential(taskId, field, source); }

  deleteWorkerBot(botId: number): Observable<void>{ return this.workerApi.deleteWorkerBot(botId); }

  updateWorkerReviewText(reviewId: number, orderId: number, text: string, source?: WorkerActivitySource): Observable<void>{ return this.workerApi.updateWorkerReviewText(reviewId, orderId, text, source); }

  updateWorkerReviewBotName(reviewId: number, botName: string): Observable<void>{ return this.workerApi.updateWorkerReviewBotName(reviewId, botName); }

  updateWorkerReviewAnswer(reviewId: number, orderId: number, answer: string, source?: WorkerActivitySource): Observable<void>{ return this.workerApi.updateWorkerReviewAnswer(reviewId, orderId, answer, source); }

  updateWorkerReviewNote(reviewId: number, orderId: number, comment: string, source?: WorkerActivitySource): Observable<void>{ return this.workerApi.updateWorkerReviewNote(reviewId, orderId, comment, source); }

  getLeadBoard(query: LeadBoardQuery = {}): Observable<LeadBoard> {
    const params = new HttpParams()
      .set('keyword', query.keyword?.trim() ?? '')
      .set('section', query.section ?? 'newLeads')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 12))
      .set('sortDirection', query.sortDirection ?? 'desc');

    return this.http.get<LeadBoard>(this.apiUrl('/api/leads/board'), { params });
  }

  getLeadEditOptions(): Observable<LeadEditOptions> {
    return this.http.get<LeadEditOptions>(this.apiUrl('/api/leads/edit-options'));
  }

  importLeads(request: LeadImportRequest): Observable<LeadImportResponse> {
    const formData = new FormData();
    formData.append('file', request.file);
    for (const managerId of request.managerIds) {
      formData.append('managerIds', String(managerId));
    }
    if (request.operatorId != null) {
      formData.append('operatorId', String(request.operatorId));
    }
    if (request.marketologId != null) {
      formData.append('marketologId', String(request.marketologId));
    }

    return this.http.post<LeadImportResponse>(this.apiUrl('/api/leads/file-import'), formData);
  }

  createLead(request: LeadCreateRequest): Observable<LeadItem> {
    return this.http.post<LeadItem>(this.apiUrl('/api/leads'), request);
  }

  updateLead(id: number, request: LeadUpdateRequest): Observable<LeadItem> {
    return this.http.put<LeadItem>(this.apiUrl(`/api/leads/${id}`), request);
  }

  deleteLead(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/leads/${id}`));
  }

  markLeadSend(id: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/leads/${id}/status/send`), {});
  }

  markLeadResend(id: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/leads/${id}/status/resend`), {});
  }

  markLeadArchive(id: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/leads/${id}/status/archive`), {});
  }

  markLeadNew(id: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/leads/${id}/status/new`), {});
  }

  markLeadToWork(id: number, commentsLead?: string): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/leads/${id}/status/to-work`), { commentsLead });
  }

  getCompanyCreatePayload(
    source: CompanyCreateSource,
    leadId?: number | null,
    managerId?: number | null
  ): Observable<CompanyCreatePayload>{ return this.companiesApi.getCompanyCreatePayload(source, leadId, managerId); }

  getCompanySubcategories(categoryId: number): Observable<CompanyCreateOption[]>{ return this.companiesApi.getCompanySubcategories(categoryId); }

  createCompany(request: CompanyCreateRequest): Observable<CompanyCreateResult>{ return this.companiesApi.createCompany(request); }

  getPersonalReminders(): Observable<PersonalReminder[]> {
    return this.http.get<PersonalReminder[]>(this.apiUrl('/api/personal-reminders'));
  }

  createPersonalReminder(request: PersonalReminderRequest): Observable<PersonalReminder> {
    return this.http.post<PersonalReminder>(this.apiUrl('/api/personal-reminders'), request);
  }

  updatePersonalReminder(id: number, request: PersonalReminderRequest): Observable<PersonalReminder> {
    return this.http.put<PersonalReminder>(this.apiUrl(`/api/personal-reminders/${id}`), request);
  }

  completePersonalReminder(id: number): Observable<PersonalReminder> {
    return this.http.post<PersonalReminder>(this.apiUrl(`/api/personal-reminders/${id}/complete`), {});
  }

  deletePersonalReminder(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/personal-reminders/${id}`));
  }

  getOperatorBoard(query: OperatorBoardQuery = {}): Observable<OperatorBoard> {
    const params = new HttpParams()
      .set('keyword', query.keyword?.trim() ?? '')
      .set('section', query.section ?? 'queue')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10));

    return this.http.get<OperatorBoard>(this.apiUrl('/api/operator/board'), { params });
  }

  bindOperatorDevice(telephoneId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl('/api/operator/device-token'), { telephoneId });
  }

  markOperatorLeadSend(id: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/operator/leads/${id}/status/send`), {});
  }

  markOperatorLeadToWork(id: number, commentsLead?: string): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/operator/leads/${id}/status/to-work`), { commentsLead });
  }

  getOperatorPhones(keyword = ''): Observable<OperatorPhonesResponse>{ return this.dictionariesApi.getOperatorPhones(keyword); }

  createOperatorPhone(request: OperatorPhoneRequest): Observable<OperatorPhone>{ return this.dictionariesApi.createOperatorPhone(request); }

  updateOperatorPhone(id: number, request: OperatorPhoneRequest): Observable<OperatorPhone>{ return this.dictionariesApi.updateOperatorPhone(id, request); }

  deleteOperatorPhone(id: number): Observable<void>{ return this.dictionariesApi.deleteOperatorPhone(id); }

  deleteOperatorPhoneDeviceToken(phoneId: number, token: string): Observable<void>{ return this.dictionariesApi.deleteOperatorPhoneDeviceToken(phoneId, token); }

  getTbankStatus(): Observable<TbankPaymentStatus>{ return this.orderPaymentApi.getTbankStatus(); }

  getPublicPaymentLink(token: string): Observable<PublicPaymentLink> { return this.publicPaymentsApi.getPublicPaymentLink(token); }

  getPublicCommonInvoice(token: string): Observable<PublicCommonInvoice> { return this.publicPaymentsApi.getPublicCommonInvoice(token); }

  initPublicPayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean
  ): Observable<PublicPaymentInitResponse> { return this.publicPaymentsApi.initPublicPayment(token, email, offerConsent, privacyConsent, receiptConsent); }

  initPublicCommonInvoicePayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean
  ): Observable<PublicPaymentInitResponse> { return this.publicPaymentsApi.initPublicCommonInvoicePayment(token, email, offerConsent, privacyConsent, receiptConsent); }

  reportPublicCommonInvoicePaid(token: string): Observable<PublicCommonInvoice> { return this.publicPaymentsApi.reportPublicCommonInvoicePaid(token); }

  initPublicSbpPayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean,
    sbpBankId?: string | null
  ): Observable<PublicPaymentInitResponse> { return this.publicPaymentsApi.initPublicSbpPayment(token, email, offerConsent, privacyConsent, receiptConsent, sbpBankId); }

  getPublicSbpBanks(token: string): Observable<PublicSbpBank[]> { return this.publicPaymentsApi.getPublicSbpBanks(token); }

  reportPublicManualPayment(token: string): Observable<PublicPaymentLink> { return this.publicPaymentsApi.reportPublicManualPayment(token); }

  registerClient(request: RegisterClientRequest): Observable<ProvisionedUserResponse> {
    return this.http.post<ProvisionedUserResponse>(this.apiUrl('/api/auth/register'), request);
  }

  getPerformerCities(): Observable<PerformerCityOption[]> {
    return this.http.get<PerformerCityOption[]>(this.apiUrl('/api/auth/performer-cities'));
  }

  registerPerformer(request: RegisterPerformerRequest): Observable<RegisterPerformerResponse> {
    return this.http.post<RegisterPerformerResponse>(this.apiUrl('/api/auth/register-performer'), request);
  }

  migrateLegacyUser(request: LegacyUserMigrationRequest): Observable<ProvisionedUserResponse> {
    return this.http.post<ProvisionedUserResponse>(this.apiUrl('/api/auth/legacy-migration'), request);
  }

  getWhatsAppBindingStatus(): Observable<WhatsAppClientStatus> {
    return this.http.get<WhatsAppClientStatus>(this.apiUrl('/api/cabinet/whatsapp'));
  }

  createManagerOrderPaymentLink(orderId: number): Observable<ManagerPaymentLinkResponse>{ return this.orderPaymentApi.createManagerOrderPaymentLink(orderId); }

  getManagerOrderPaymentRouteChangeContext(orderId: number): Observable<PaymentRouteChangeContext>{ return this.orderPaymentApi.getManagerOrderPaymentRouteChangeContext(orderId); }

  changeManagerOrderPaymentRoute(
    orderId: number,
    request: PaymentRouteChangeRequest
  ): Observable<PaymentRouteChangeResponse>{ return this.orderPaymentApi.changeManagerOrderPaymentRoute(orderId, request); }

  markManagerOrderPaperInvoiceIssued(orderId: number): Observable<unknown>{ return this.orderPaymentApi.markManagerOrderPaperInvoiceIssued(orderId); }

  getAdminTbankPaymentLinks(params?: {
    page?: number;
    size?: number;
    status?: string;
    search?: string;
    source?: PaymentLinkListSource;
    sortDirection?: 'asc' | 'desc';
    from?: string;
    to?: string;
  }): Observable<AdminPaymentLinksPageResponse> { return this.paymentAdministrationApi.getAdminTbankPaymentLinks(params); }

  runAdminPaymentLinkArchive(dryRun: boolean, batchSize?: number): Observable<PaymentLinkArchiveRunResponse> { return this.paymentAdministrationApi.runAdminPaymentLinkArchive(dryRun, batchSize); }

  cancelAdminTbankPaymentLink(linkId: number): Observable<AdminPaymentLinkResponse> { return this.paymentAdministrationApi.cancelAdminTbankPaymentLink(linkId); }

  confirmAdminManualPaymentLink(linkId: number): Observable<AdminPaymentLinkResponse> { return this.paymentAdministrationApi.confirmAdminManualPaymentLink(linkId); }

  markAdminManualPaymentReceipt(linkId: number): Observable<AdminPaymentLinkResponse> { return this.paymentAdministrationApi.markAdminManualPaymentReceipt(linkId); }

  getAdminTbankPaymentProfiles(): Observable<TbankPaymentProfilesResponse> { return this.paymentConfigurationApi.getAdminTbankPaymentProfiles(); }

  getAdminTbankRuntimeSettings(): Observable<TbankRuntimeSettings> { return this.paymentConfigurationApi.getAdminTbankRuntimeSettings(); }

  updateAdminTbankRuntimeSettings(
    request: UpdateTbankRuntimeSettingsRequest
  ): Observable<TbankRuntimeSettings> { return this.paymentConfigurationApi.updateAdminTbankRuntimeSettings(request); }

  updateAdminTbankPaymentProfileAssignments(
    assignments: ManagerPaymentProfileAssignmentRequest[]
  ): Observable<TbankPaymentProfilesResponse> { return this.paymentConfigurationApi.updateAdminTbankPaymentProfileAssignments(assignments); }

  updateAdminPaymentProfilePolicies(
    profiles: PaymentProfilePolicyRequest[]
  ): Observable<TbankPaymentProfilesResponse> { return this.paymentConfigurationApi.updateAdminPaymentProfilePolicies(profiles); }

  getAdminManualPaymentTasks(): Observable<ManualPaymentTaskResponse[]> { return this.manualPaymentTasksApi.getAdminManualPaymentTasks(); }

  getAdminManualPaymentTaskAccountingTargets(
    managerId: number,
    targetAmountKopecks: number,
    taskId?: number | null
  ): Observable<ManualPaymentTaskAccountingTargetOption[]> { return this.manualPaymentTasksApi.getAdminManualPaymentTaskAccountingTargets(managerId, targetAmountKopecks, taskId); }

  getAdminManualRecipientMonthlySummary(month: string): Observable<ManualPaymentRecipientMonthlySummaryResponse> { return this.manualPaymentTasksApi.getAdminManualRecipientMonthlySummary(month); }

  createAdminManualPaymentTask(
    request: CreateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> { return this.manualPaymentTasksApi.createAdminManualPaymentTask(request); }

  updateAdminManualPaymentTaskStatus(
    taskId: number,
    status: ManualPaymentTaskStatus
  ): Observable<ManualPaymentTaskResponse> { return this.manualPaymentTasksApi.updateAdminManualPaymentTaskStatus(taskId, status); }

  updateAdminManualPaymentTask(
    taskId: number,
    request: UpdateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> { return this.manualPaymentTasksApi.updateAdminManualPaymentTask(taskId, request); }

  imageUrl(imageId = 1): string {
    return `${mobileEnvironment.backendBaseUrl}/images/${imageId}`;
  }

  private cabinetDateParams(date?: string, forceRefresh?: boolean): HttpParams {
    let params = date ? new HttpParams().set('date', date) : new HttpParams();
    if (forceRefresh) {
      params = params.set('refresh', 'true');
    }
    return params;
  }

  private keywordParams(keyword: string): HttpParams {
    const value = keyword.trim();
    return value ? new HttpParams().set('keyword', value) : new HttpParams();
  }

  private apiUrl(path: string): string {
    return `${mobileEnvironment.apiBaseUrl}${path}`;
  }
}
