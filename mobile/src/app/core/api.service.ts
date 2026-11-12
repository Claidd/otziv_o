export type { ManagerPerformanceScore, ManagerControlStatus, ManagerControlSeverity, ManagerControlGroup, ManagerControlItemStatus, ManagerControlActionType, ManagerControlActionPayload, ManagerControlClientReplyPayload, ManagerControlClientMessageReconciliation, ManagerControlStagePayload, ManagerControlClosePayload, ManagerControlCloseResponse, ManagerControlConcreteItem, ManagerControlSlaState, ManagerControlItemDetail, ManagerControlWorkerExplanationStats, ManagerControlManagerDetail, ManagerControlProblem, ManagerControlSection, ManagerControlOverdueStatus, ManagerControlManager, ManagerControlSummary } from './manager-control.models';
export type { PublicPaymentInitResponse, PublicCommonInvoiceOrder, PublicCommonInvoice, PublicSbpBank } from '@otziv/client-common/public-payments';
import type { CommonInvoiceSummaryResponse } from '@otziv/client-common/billing-payments';
export type { CommonBillingCompanyResponse, CommonInvoiceSummaryResponse, CommonBillingAccountResponse, InvoicePaymentMode, PublicPaymentLink } from '@otziv/client-common/billing-payments';
export type { OrderEditPayload } from '@otziv/client-common/order-editor';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  botBrowserApiPaths,
  botBrowserSessionClosePath,
  botBrowserSessionHeartbeatPath
} from './bot-browser-api-paths';
import { mobileEnvironment } from './mobile-environment';
import { backendImageUrl } from './image-urls';

const OPAQUE_REVIEW_CAPABILITY = /^rc1_[A-Za-z0-9_-]{43}$/;

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

export type PaymentRouteChangeTarget = 'EMPLOYEE_REQUISITES' | 'DIFFERENT_RECIPIENT' | 'OWNER_TBANK' | 'OWNER_PAPER_INVOICE';

export interface PaymentRouteChangeContext {
  paymentLinkId: number | null;
  currentRoute: string;
  currentRecipient: string;
  status: string;
  canChange: boolean;
  bankRecipientChangeAvailable?: boolean;
  blockReason: string;
  configuredMode?: 'AUTO_ROUTING' | PaymentRouteChangeTarget | string;
  paperInvoiceIssued?: boolean;
  expectedTargetPaymentProfileId?: number | null;
}

export interface PaymentRouteChangeRequest {
  expectedPaymentLinkId: number | null;
  target: PaymentRouteChangeTarget;
  confirmedUnpaid: boolean;
  paperInvoiceWithdrawn?: boolean;
  expectedTargetPaymentProfileId?: number | null;
  /** Contractor payment profile the manager named for the card number; omitted for the automatic choice. */
  recipientProfileId?: number | null;
}

/** A person a manager may name when a card number is issued for an order invoice. */
export interface PaymentRouteRecipient {
  profileId: number;
  userId?: number | null;
  role: string;
  name: string;
  orderSpecialist: boolean;
  manager: boolean;
  teamMember: boolean;
  workedWithClient: boolean;
  availableKopecks: number;
  overrunKopecks: number;
  current: boolean;
  blockReason?: string | null;
}

/** The owner's card in «Выдать номер карты»; absent where the owner takes money through the bank only. */
export interface PaymentRouteOwnerCard {
  name: string;
  remainingKopecks: number;
  current: boolean;
  blockReason?: string | null;
}

export interface PaymentRouteRecipientsResponse {
  orderId: number;
  paymentLinkId: number | null;
  amountKopecks: number;
  recipients: PaymentRouteRecipient[];
  ownerCard?: PaymentRouteOwnerCard | null;
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
  | 'DIFFERENT_RECIPIENT'
  | 'OWNER_TBANK'
  | 'OWNER_BANK_REISSUE';

export interface CommonInvoicePaymentRouteChangeContextResponse {
  currentRoute: string;
  currentTarget: CommonInvoicePaymentRouteChangeTarget;
  currentRecipient: string;
  status: string;
  canChange: boolean;
  bankRecipientChangeAvailable?: boolean;
  blockReason: string;
  paymentEvidenceToken: string;
  currentPaymentProfileId?: number | null;
  ownerBankTargetPaymentProfileId?: number | null;
  ownerBankTargetPaymentProfileName?: string | null;
  ownerBankTargetProvider?: string | null;
  canReissueOwnerBank: boolean;
  ownerBankReissueBlockReason: string;
}

/** A person a manager may name when a card number is issued for a common invoice. */
export interface CommonInvoiceRouteRecipient {
  profileId: number;
  userId?: number | null;
  role: string;
  name: string;
  orderSpecialist: boolean;
  manager: boolean;
  teamMember: boolean;
  workedWithClient: boolean;
  availableKopecks: number;
  overrunKopecks: number;
  current: boolean;
  blockReason?: string | null;
}

export interface CommonInvoiceRouteRecipientsResponse {
  invoiceId: number;
  amountKopecks: number;
  recipients: CommonInvoiceRouteRecipient[];
  /** The owner's card; absent where the owner takes money through the bank only. */
  ownerCard?: PaymentRouteOwnerCard | null;
}

/** An order of the invoice's own link that goes apart from the common invoice and may be returned to it. */
export interface CommonInvoiceReturnableOrder {
  orderId: number;
  companyId: number;
  companyTitle: string;
  orderTitle: string;
  status: string;
  /** What the order would add to the invoice; 0 when the amount cannot be calculated. */
  amountKopecks: number;
}

export interface CommonInvoiceReturnableOrdersResponse {
  invoiceId: number;
  canReturn: boolean;
  /** Why the composition is fixed and what to do instead; empty while orders can be returned. */
  blockReason?: string | null;
  orders: CommonInvoiceReturnableOrder[];
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
  ordinaryAfterPaperCancel?: boolean;
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
  /**
   * The invoice of the order is the owner's bank link. The owner is among the candidates only where the
   * bank profile lets the owner take card transfers; a payment by the link itself is marked by the bank.
   */
  bankLinkRoute?: boolean;
}

export interface ManualCardPaymentConfirmationRequest {
  reason: string;
  receiptUrl?: string | null;
  recipientKey: string;
  recipientType?: ManualCardPaymentRecipientType | null;
  recipientProfileId?: number | null;
}

export type ManagerManualCardPaymentResultStatus = 'COMPLETED' | 'OWNER_APPROVAL_PENDING' | 'SPECIALIST_REVIEW_PENDING';

export interface ManagerManualCardPaymentResult {
  status: ManagerManualCardPaymentResultStatus;
  orderId: number;
  paymentLinkId: number;
  message: string;
  manualPaymentReview?: import('@otziv/client-common/billing-payments').ManualPaymentReviewView | null;
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
  ownerReport?: {
    id: number;
    reportedBy: string;
    attentionMode: boolean;
    canDecide: boolean;
    request: CommonManualPaymentAttributionRequest;
  } | null;
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
  imageUrl?: string | null;
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

export type ContractorRole = 'SPECIALIST' | 'MANAGER';

export interface ContractorPaymentProfile {
  id: number;
  userId: number;
  role: ContractorRole;
  rowVersion: number;
  enabled: boolean;
  liveEnabled: boolean;
  recipientName?: string | null;
  paymentPhone?: string | null;
  bankName?: string | null;
  paymentComment?: string | null;
  openingBalanceKopecks: number;
  trackingStartedAt: string;
  accruedMonthKopecks: number;
  accruedTotalKopecks: number;
  workAccruedMonthKopecks: number;
  bonusMonthKopecks: number;
  deductionMonthKopecks: number;
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
  exposureOverrunKopecks: number;
  reportingLive: boolean;
  shadowMode: boolean;
  liveRouting: boolean;
}

export interface ContractorPaymentProfileRequest {
  role: ContractorRole;
  expectedVersion: number;
  enabled: boolean;
  liveEnabled: boolean;
  recipientName?: string;
  paymentPhone?: string;
  bankName?: string;
  paymentComment?: string;
  openingBalanceKopecks: number;
  openingBalanceReason?: string;
}

export interface ContractorOpeningBalanceChange {
  id: number;
  profileId: number;
  oldBalanceKopecks: number;
  newBalanceKopecks: number;
  deltaKopecks: number;
  reason: string;
  changedBy: string;
  effectiveAt: string;
  createdAt: string;
}

export type ContractorPaymentMode = 'SHADOW' | 'LIVE';

export interface ContractorDirectSettlement {
  id: number;
  profileId: number;
  userId: number;
  type: 'PAYMENT' | 'REVERSAL';
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

export interface ContractorAllocationEvent {
  id: number;
  eventType: string;
  amountKopecks: number;
  effectiveAt: string;
  reason?: string | null;
  actor: string;
}

export interface ContractorAllocationJournalItem {
  id: number;
  attemptNo: number;
  mode: ContractorPaymentMode;
  sourceType: 'PAYMENT_LINK' | 'COMMON_INVOICE' | 'DIRECT_SETTLEMENT' | 'ACTUAL_PAYMENT';
  sourceId: number;
  orderId?: number | null;
  commonInvoiceId?: number | null;
  recipientType: 'SPECIALIST' | 'MANAGER' | 'OWNER';
  recipientProfileId?: number | null;
  recipientName?: string | null;
  amountKopecks: number;
  confirmedKopecks: number;
  returnedKopecks: number;
  status: string;
  routingDecisionReason?: string | null;
  specialistRejectionReason?: string | null;
  managerRejectionReason?: string | null;
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
  events: ContractorAllocationEvent[];
}

export interface ContractorAllocationJournalPage {
  content: ContractorAllocationJournalItem[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export type ContractorAccrualAdjustmentKind = 'BONUS' | 'DEDUCTION';

export interface ContractorAccrualAdjustmentRequest {
  kind: ContractorAccrualAdjustmentKind;
  amountKopecks: number;
  reason: string;
  referenceMonth: string;
  evidenceReference?: string;
  idempotencyKey: string;
}

export interface ContractorAccrualAdjustment {
  id: number;
  profileId: number;
  kind: ContractorAccrualAdjustmentKind | 'REVERSAL';
  signedAmountKopecks: number;
  effectiveOn: string;
  referenceMonth: string;
  reason: string;
  evidenceReference?: string | null;
  createdBy: string;
  createdAt: string;
  reversalOfId?: number | null;
}

export interface ContractorAccrualAdjustmentHistoryPage {
  items: ContractorAccrualAdjustment[];
  hasMore: boolean;
}

export interface ContractorAccrualAdjustmentPreview {
  workMonthKopecks: number;
  bonusMonthKopecks: number;
  deductionMonthKopecks: number;
  accruedMonthBeforeKopecks: number;
  accruedMonthAfterKopecks: number;
  freeForNewPaymentsBeforeKopecks: number;
  freeForNewPaymentsAfterKopecks: number;
  paidTotalKopecks: number;
  outstandingKopecks: number;
  manualTaskCommitmentKopecks: number;
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
  imageUrl?: string | null;
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
  imageUrl?: string | null;
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
  imageUrl?: string | null;
  userId?: number | null;
  order1Month?: number | null;
  review1Month?: number | null;
  leadsNew?: number | null;
  leadsInWork?: number | null;
  percentInWork?: number | null;
}

export interface ScoreContractorPaymentSummary {
  profileId: number;
  userId: number;
  role: 'SPECIALIST' | 'MANAGER' | string;
  profileEnabled: boolean;
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
  contractorPayments?: ScoreContractorPaymentSummary[];
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
  recipientName?: string | null;
  paymentPhone?: string | null;
  bankName?: string | null;
  paymentComment?: string | null;
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
  workAccruedMonthKopecks?: number;
  bonusMonthKopecks?: number;
  deductionMonthKopecks?: number;
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
  constructor(private readonly http: HttpClient) {}

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

  getAdminContractorPaymentProfiles(userId: number): Observable<ContractorPaymentProfile[]> {
    return this.http.get<ContractorPaymentProfile[]>(
      this.apiUrl(`/api/admin/users/${userId}/contractor-payment-profiles`)
    );
  }

  updateAdminContractorPaymentProfile(
    userId: number, request: ContractorPaymentProfileRequest
  ): Observable<ContractorPaymentProfile> {
    return this.http.put<ContractorPaymentProfile>(
      this.apiUrl(`/api/admin/users/${userId}/contractor-payment-profiles`), request
    );
  }

  getAdminContractorOpeningBalanceHistory(
    userId: number, profileId: number
  ): Observable<ContractorOpeningBalanceChange[]> {
    return this.http.get<ContractorOpeningBalanceChange[]>(
      this.apiUrl(`/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/opening-balance-history`)
    );
  }

  getAdminContractorDirectSettlements(
    userId: number, profileId: number
  ): Observable<ContractorDirectSettlement[]> {
    return this.http.get<ContractorDirectSettlement[]>(
      this.apiUrl(`/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/direct-settlements`)
    );
  }

  createAdminContractorDirectSettlement(
    userId: number, profileId: number, request: ContractorDirectSettlementRequest
  ): Observable<ContractorDirectSettlement> {
    return this.http.post<ContractorDirectSettlement>(
      this.apiUrl(`/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/direct-settlements`), request
    );
  }

  reverseAdminContractorDirectSettlement(
    userId: number, profileId: number, originalId: number, request: ContractorDirectSettlementRequest
  ): Observable<ContractorDirectSettlement> {
    return this.http.post<ContractorDirectSettlement>(
      this.apiUrl(`/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/direct-settlements/${originalId}/reversals`),
      request
    );
  }

  getAdminContractorAllocationJournal(
    userId: number,
    filter: { status?: string; mode?: ContractorPaymentMode | ''; sourceType?: ContractorAllocationJournalItem['sourceType'] | ''; sourceId?: number | null; page?: number; size?: number } = {}
  ): Observable<ContractorAllocationJournalPage> {
    const params: Record<string, string | number> = {
      userId, page: filter.page ?? 0, size: filter.size ?? 10
    };
    if (filter.status) params['status'] = filter.status;
    if (filter.mode) params['mode'] = filter.mode;
    if (filter.sourceType) params['sourceType'] = filter.sourceType;
    if (filter.sourceId != null) params['sourceId'] = filter.sourceId;
    return this.http.get<ContractorAllocationJournalPage>(
      this.apiUrl('/api/admin/contractor-payment-allocations'), { params }
    );
  }

  recordAdminContractorReturnedAmount(
    allocationId: number, request: { returnedTotalKopecks: number; reason: string }
  ): Observable<void> {
    return this.http.put<void>(
      this.apiUrl(`/api/admin/contractor-payment-allocations/${allocationId}/returned-amount`), request
    );
  }

  getAdminContractorAccrualAdjustmentsPage(
    userId: number, profileId: number, page: number
  ): Observable<ContractorAccrualAdjustmentHistoryPage> {
    return this.http.get<ContractorAccrualAdjustmentHistoryPage>(
      this.apiUrl(`/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/accrual-adjustments/pages`),
      { params: { page } }
    );
  }

  previewAdminContractorAccrualAdjustment(
    userId: number, profileId: number, request: ContractorAccrualAdjustmentRequest
  ): Observable<ContractorAccrualAdjustmentPreview> {
    return this.http.post<ContractorAccrualAdjustmentPreview>(
      this.apiUrl(`/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/accrual-adjustments/preview`),
      request
    );
  }

  createAdminContractorAccrualAdjustment(
    userId: number, profileId: number, request: ContractorAccrualAdjustmentRequest
  ): Observable<ContractorAccrualAdjustment> {
    return this.http.post<ContractorAccrualAdjustment>(
      this.apiUrl(`/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/accrual-adjustments`),
      request
    );
  }

  reverseAdminContractorAccrualAdjustment(
    userId: number, profileId: number, adjustmentId: number,
    request: { reason: string; idempotencyKey: string }
  ): Observable<ContractorAccrualAdjustment> {
    return this.http.post<ContractorAccrualAdjustment>(
      this.apiUrl(`/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/accrual-adjustments/${adjustmentId}/reversals`),
      request
    );
  }

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

  getReviewCheck(orderDetailId: string, capabilityToken?: string | null): Observable<ReviewCheckPayload> {
    return this.http.get<ReviewCheckPayload>(
      this.reviewCheckUrl(orderDetailId, capabilityToken),
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

  deleteAllPersonalReminders(): Observable<number[]> {
    return this.http.delete<number[]>(this.apiUrl('/api/personal-reminders'));
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

  registerClient(request: RegisterClientRequest): Observable<ProvisionedUserResponse> {
    return this.http.post<ProvisionedUserResponse>(this.apiUrl('/api/auth/register'), request);
  }

  getPerformerCities(): Observable<PerformerCityOption[]> {
    return this.http.get<PerformerCityOption[]>(this.apiUrl('/api/auth/performer-cities'));
  }

  registerPerformer(request: RegisterPerformerRequest): Observable<RegisterPerformerResponse> {
    return this.http.post<RegisterPerformerResponse>(this.apiUrl('/api/auth/register-performer'), request);
  }

  getWhatsAppBindingStatus(): Observable<WhatsAppClientStatus> {
    return this.http.get<WhatsAppClientStatus>(this.apiUrl('/api/cabinet/whatsapp'));
  }

  imageUrl(imageId = 1, signedPath?: string | null): string {
    return backendImageUrl(mobileEnvironment.backendBaseUrl, imageId, signedPath);
  }

  private cabinetDateParams(date?: string, forceRefresh?: boolean): HttpParams {
    let params = date ? new HttpParams().set('date', date) : new HttpParams();
    if (forceRefresh) {
      params = params.set('refresh', 'true');
    }
    return params;
  }

  private apiUrl(path: string): string {
    return `${mobileEnvironment.apiBaseUrl}${path}`;
  }
}
