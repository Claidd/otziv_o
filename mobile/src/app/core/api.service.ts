import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { forkJoin, map, Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

export interface CurrentUser {
  authenticated: boolean;
  name: string;
  authorities: string[];
  preferredUsername?: string;
  email?: string;
  realmRoles?: string[];
}

export type TbankRuntimeMode = 'TEST' | 'LIVE';
export type PaymentInstructionSource = 'MANAGER_TEXT' | 'TBANK_LINK';
export type TbankPaymentPageMode = 'SBP_PRIMARY' | 'BANK_PRIMARY' | 'SBP_PAY_ONLY' | 'SBP_ONLY' | 'BANK_ONLY';
export type PaymentPolicy = 'T_BANK_ONLY' | 'MANUAL_UNTIL_LIMIT_THEN_TBANK';
export type PaymentMethod = 'BANK_FORM' | 'SBP_QR' | 'MANUAL_MOBILE_BANK' | 'MANUAL_EXTERNAL_LINK' | string;
export type PaymentReceiptStatus = 'PENDING' | 'MARKED';
export type ManualPaymentSource = 'PROFILE_MONTHLY_LIMIT' | 'MANUAL_TASK';
export type ManualPaymentType = 'MOBILE_BANK' | 'EXTERNAL_LINK';
export type ManualPaymentTaskStatus = 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'CANCELED';
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
  manualPaymentButtonLabel?: string | null;
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
  manualMonthlySoftLimitKopecks?: number | null;
  manualMonthlyHardLimitKopecks?: number | null;
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
}

export interface CreateManualPaymentTaskRequest {
  managerId?: number | null;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  targetAmountKopecks: number;
  comment?: string | null;
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
  botLogin: string;
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
}

export interface CompanyEditPayload {
  id: number;
  title: string;
  urlChat: string;
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
}

export interface OrderNotesResponse {
  orderComments: string;
  companyComments: string;
}

export interface OrderEditPayload {
  id: number;
  companyId: number;
  companyTitle: string;
  status: string;
  sum?: number;
  amount?: number;
  counter?: number;
  created: string;
  changed: string;
  payDay: string;
  orderComments: string;
  commentsCompany: string;
  complete: boolean;
  filial?: ManagerOption | null;
  manager?: ManagerOption | null;
  worker?: ManagerOption | null;
  filials: ManagerOption[];
  managers: ManagerOption[];
  workers: ManagerOption[];
  canComplete: boolean;
  canDelete: boolean;
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
  botLogin: string;
  botPassword: string;
  botCounter: number;
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
  botLogin?: string;
  botPassword?: string;
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
  botLogin?: string;
  botPassword?: string;
  batch?: ReviewRecoveryBatchItem | null;
}

export interface ReviewRecoveryTaskUpdateRequest {
  recoveryText: string;
  recoveryAnswer?: string | null;
  scheduledDate?: string | null;
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
  orderId: number;
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
  telegramBotInviteUrl?: string;
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
  botLogin?: string;
  botPassword?: string;
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
  login: string;
  password: string;
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
  googleLogin?: string | null;
  googlePassword?: string | null;
  avitoPassword?: string | null;
  mailLogin?: string | null;
  mailPassword?: string | null;
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
  password: string;
  fio: string;
  active: boolean;
  counter: number;
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
  paymentInstructionSource: 'MANAGER_TEXT' | 'TBANK_LINK';
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
  paymentInstructionSource?: 'MANAGER_TEXT' | 'TBANK_LINK' | string | null;
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
}

export interface BotImportResponse {
  totalRows: number;
  added: number;
  skippedDuplicates: number;
  skippedInvalid: number;
  errors: string[];
}

export interface BotBrowserOpenResponse {
  botId: number;
  vncUrl: string;
  userAgent?: string;
  platform?: string;
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

  getCurrentUser(): Observable<CurrentUser> {
    return this.http.get<CurrentUser>(this.apiUrl('/api/me'));
  }

  getCabinetProfile(date?: string, options: { forceRefresh?: boolean } = {}): Observable<CabinetProfile> {
    return this.http.get<CabinetProfile>(this.apiUrl('/api/cabinet/profile'), {
      params: this.cabinetDateParams(date, options.forceRefresh)
    });
  }

  getCabinetTeam(date?: string, options: { forceRefresh?: boolean } = {}): Observable<TeamResponse> {
    return this.http.get<TeamResponse>(this.apiUrl('/api/cabinet/team'), {
      params: this.cabinetDateParams(date, options.forceRefresh)
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
  ): Observable<ManagerManualPaymentSettings> {
    let params = new HttpParams();
    if (options.forceRefresh) {
      params = params.set('refresh', 'true');
    }
    return this.http.get<ManagerManualPaymentSettings>(
      this.apiUrl('/api/cabinet/payment-profile/manual'),
      { params }
    );
  }

  updateManagerManualPaymentSettings(
    request: UpdateManagerManualPaymentSettingsRequest
  ): Observable<ManagerManualPaymentSettings> {
    return this.http.put<ManagerManualPaymentSettings>(
      this.apiUrl('/api/cabinet/payment-profile/manual'),
      request
    );
  }

  getManagerManualPaymentTasks(
    options: { forceRefresh?: boolean } = {}
  ): Observable<ManualPaymentTaskResponse[]> {
    let params = new HttpParams();
    if (options.forceRefresh) {
      params = params.set('refresh', 'true');
    }
    return this.http.get<ManualPaymentTaskResponse[]>(
      this.apiUrl('/api/cabinet/manual-payment-tasks'),
      { params }
    );
  }

  createManagerManualPaymentTask(
    request: CreateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.post<ManualPaymentTaskResponse>(
      this.apiUrl('/api/cabinet/manual-payment-tasks'),
      request
    );
  }

  updateManagerManualPaymentTaskStatus(
    taskId: number,
    status: ManualPaymentTaskStatus
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.put<ManualPaymentTaskResponse>(
      this.apiUrl(`/api/cabinet/manual-payment-tasks/${taskId}/status`),
      { status }
    );
  }

  getDictionarySummary(includeAdminTabs: boolean): Observable<DictionarySummary> {
    const categories$ = this.http.get<unknown[]>(this.apiUrl('/api/admin/categories'));

    if (!includeAdminTabs) {
      return categories$.pipe(
        map((categories) => ({
          items: [
            {
              key: 'categories',
              title: 'Категории',
              icon: 'category',
              count: categories.length,
              description: 'доступные категории компаний'
            }
          ]
        }))
      );
    }

    return forkJoin({
      categories: categories$,
      cities: this.http.get<unknown[]>(this.apiUrl('/api/admin/cities')),
      products: this.http.get<{ products?: unknown[] }>(this.apiUrl('/api/admin/products')),
      phones: this.http.get<{ phones?: unknown[] }>(this.apiUrl('/api/admin/phones')),
      accounts: this.http.get<{ bots?: unknown[] }>(this.apiUrl('/api/admin/bots')),
      promo: this.http.get<unknown[]>(this.apiUrl('/api/admin/promo-texts')),
      managerTexts: this.http.get<unknown[]>(this.apiUrl('/api/admin/manager-texts'))
    }).pipe(
      map((response) => ({
        items: [
          { key: 'categories', title: 'Категории', icon: 'category', count: response.categories.length, description: 'типы компаний и подкатегории' },
          { key: 'cities', title: 'Города', icon: 'location_city', count: response.cities.length, description: 'города филиалов и заказов' },
          { key: 'products', title: 'Продукты', icon: 'inventory_2', count: response.products.products?.length ?? 0, description: 'услуги и цены заказов' },
          { key: 'phones', title: 'Телефоны', icon: 'phone_iphone', count: response.phones.phones?.length ?? 0, description: 'телефоны операторов' },
          { key: 'accounts', title: 'Аккаунты', icon: 'manage_accounts', count: response.accounts.bots?.length ?? 0, description: 'боты и рабочие аккаунты' },
          { key: 'promo', title: 'Промо', icon: 'smart_button', count: response.promo.length, description: 'шаблоны сообщений' },
          { key: 'managerTexts', title: 'Тексты менеджеров', icon: 'article', count: response.managerTexts.length, description: 'персональные тексты' }
        ]
      }))
    );
  }

  getAdminCategories(keyword = ''): Observable<AdminCategory[]> {
    return this.http.get<AdminCategory[]>(this.apiUrl('/api/admin/categories'), {
      params: this.keywordParams(keyword)
    });
  }

  createAdminCategory(request: TitleRequest): Observable<AdminCategory> {
    return this.http.post<AdminCategory>(this.apiUrl('/api/admin/categories'), request);
  }

  updateAdminCategory(id: number, request: TitleRequest): Observable<AdminCategory> {
    return this.http.put<AdminCategory>(this.apiUrl(`/api/admin/categories/${id}`), request);
  }

  deleteAdminCategory(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/categories/${id}`));
  }

  getAdminSubCategories(keyword = '', categoryId?: number | null): Observable<AdminSubCategory[]> {
    let params = this.keywordParams(keyword);
    if (categoryId != null) {
      params = params.set('categoryId', String(categoryId));
    }
    return this.http.get<AdminSubCategory[]>(this.apiUrl('/api/admin/subcategories'), { params });
  }

  createAdminSubCategory(request: SubCategoryRequest): Observable<AdminSubCategory> {
    return this.http.post<AdminSubCategory>(this.apiUrl('/api/admin/subcategories'), request);
  }

  updateAdminSubCategory(id: number, request: SubCategoryRequest): Observable<AdminSubCategory> {
    return this.http.put<AdminSubCategory>(this.apiUrl(`/api/admin/subcategories/${id}`), request);
  }

  deleteAdminSubCategory(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/subcategories/${id}`));
  }

  getAdminCities(keyword = ''): Observable<AdminCity[]> {
    return this.http.get<AdminCity[]>(this.apiUrl('/api/admin/cities'), {
      params: this.keywordParams(keyword)
    });
  }

  createAdminCity(request: TitleRequest): Observable<AdminCity> {
    return this.http.post<AdminCity>(this.apiUrl('/api/admin/cities'), request);
  }

  updateAdminCity(id: number, request: TitleRequest): Observable<AdminCity> {
    return this.http.put<AdminCity>(this.apiUrl(`/api/admin/cities/${id}`), request);
  }

  deleteAdminCity(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/cities/${id}`));
  }

  getAdminProducts(keyword = ''): Observable<ProductsResponse> {
    return this.http.get<ProductsResponse>(this.apiUrl('/api/admin/products'), {
      params: this.keywordParams(keyword)
    });
  }

  createAdminProduct(request: ProductRequest): Observable<AdminProduct> {
    return this.http.post<AdminProduct>(this.apiUrl('/api/admin/products'), request);
  }

  updateAdminProduct(id: number, request: ProductRequest): Observable<AdminProduct> {
    return this.http.put<AdminProduct>(this.apiUrl(`/api/admin/products/${id}`), request);
  }

  deleteAdminProduct(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/products/${id}`));
  }

  getAdminBots(keyword = ''): Observable<BotsResponse> {
    return this.http.get<BotsResponse>(this.apiUrl('/api/admin/bots'), {
      params: this.keywordParams(keyword)
    });
  }

  getAdminBot(id: number): Observable<AdminBot> {
    return this.http.get<AdminBot>(this.apiUrl(`/api/admin/bots/${id}`));
  }

  createAdminBot(request: BotRequest): Observable<AdminBot> {
    return this.http.post<AdminBot>(this.apiUrl('/api/admin/bots'), request);
  }

  updateAdminBot(id: number, request: BotRequest): Observable<AdminBot> {
    return this.http.put<AdminBot>(this.apiUrl(`/api/admin/bots/${id}`), request);
  }

  deleteAdminBot(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/bots/${id}`));
  }

  importAdminBots(file: File): Observable<BotImportResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<BotImportResponse>(this.apiUrl('/api/admin/bots/import'), formData);
  }

  openAdminBotBrowser(botId: number): Observable<BotBrowserOpenResponse> {
    return this.http.post<BotBrowserOpenResponse>(this.apiUrl(`/api/bots/${botId}/browser/open`), {});
  }

  closeAdminBotBrowser(botId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/bots/${botId}/browser/close`), {});
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

  getAdminPromoTextManagement(keyword = ''): Observable<PromoTextManagementResponse> {
    return this.http.get<PromoTextManagementResponse>(this.apiUrl('/api/admin/promo-texts/management'), {
      params: this.keywordParams(keyword)
    });
  }

  createAdminPromoText(request: PromoTextRequest): Observable<AdminPromoText> {
    return this.http.post<AdminPromoText>(this.apiUrl('/api/admin/promo-texts'), request);
  }

  updateAdminPromoText(id: number, request: PromoTextRequest): Observable<AdminPromoText> {
    return this.http.put<AdminPromoText>(this.apiUrl(`/api/admin/promo-texts/${id}`), request);
  }

  saveAdminPromoTextAssignment(request: PromoTextAssignmentRequest): Observable<PromoTextAssignment> {
    return this.http.put<PromoTextAssignment>(this.apiUrl('/api/admin/promo-text-assignments'), request);
  }

  resetAdminPromoTextAssignment(managerId: number, section: string, buttonKey: string): Observable<void> {
    return this.http.delete<void>(
      this.apiUrl(`/api/admin/promo-text-assignments/${managerId}/${section}/${buttonKey}`)
    );
  }

  getAdminManagerTexts(keyword = ''): Observable<AdminManagerText[]> {
    return this.http.get<AdminManagerText[]>(this.apiUrl('/api/admin/manager-texts'), {
      params: this.keywordParams(keyword)
    });
  }

  updateAdminManagerText(managerId: number, request: ManagerTextRequest): Observable<AdminManagerText> {
    return this.http.put<AdminManagerText>(this.apiUrl(`/api/admin/manager-texts/${managerId}`), request);
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

  getManagerBoard(query: ManagerBoardQuery = {}): Observable<ManagerBoard> {
    let params = new HttpParams()
      .set('section', query.section ?? 'companies')
      .set('status', query.status?.trim() || 'Все')
      .set('keyword', query.keyword?.trim() ?? '')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('sortDirection', query.sortDirection ?? 'desc');

    if (query.companyId != null) {
      params = params.set('companyId', String(query.companyId));
    }

    return this.http.get<ManagerBoard>(this.apiUrl('/api/manager/board'), { params });
  }

  getManagerArchiveOrders(query: ManagerArchiveOrdersQuery = {}): Observable<Page<ArchiveOrderListItem>> {
    const params = new HttpParams()
      .set('keyword', query.keyword?.trim() ?? '')
      .set('mode', query.mode ?? 'all')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('sortDirection', query.sortDirection ?? 'desc');

    return this.http.get<Page<ArchiveOrderListItem>>(this.apiUrl('/api/manager/archive/orders'), { params });
  }

  getManagerArchiveOrder(orderId: number): Observable<ArchiveOrderDetailsPayload> {
    return this.http.get<ArchiveOrderDetailsPayload>(this.apiUrl(`/api/manager/archive/orders/${orderId}`));
  }

  restoreManagerArchiveOrder(orderId: number, targetStatus = 'Архив'): Observable<ArchiveRestoreResult> {
    const params = new HttpParams()
      .set('targetStatus', targetStatus)
      .set('confirm', 'true');

    return this.http.post<ArchiveRestoreResult>(
      this.apiUrl(`/api/manager/archive/orders/${orderId}/restore`),
      {},
      { params }
    );
  }

  updateManagerCompanyStatus(companyId: number, status: string): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/manager/companies/${companyId}/status`), { status });
  }

  updateManagerOrderStatus(orderId: number, status: string): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/manager/orders/${orderId}/status`), { status });
  }

  updateManagerOrderClientWaiting(orderId: number, waitingForClient: boolean): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/orders/${orderId}/client-waiting`), { waitingForClient });
  }

  getManagerCompanyOrderCreate(companyId: number): Observable<CompanyOrderCreatePayload> {
    return this.http.get<CompanyOrderCreatePayload>(this.apiUrl(`/api/manager/companies/${companyId}/order-create`));
  }

  createManagerCompanyOrder(
    companyId: number,
    request: CompanyOrderCreateRequest
  ): Observable<CompanyOrderCreateResult> {
    return this.http.post<CompanyOrderCreateResult>(this.apiUrl(`/api/manager/companies/${companyId}/orders`), request);
  }

  getManagerOrderEdit(orderId: number): Observable<OrderEditPayload> {
    return this.http.get<OrderEditPayload>(this.apiUrl(`/api/manager/orders/${orderId}/edit`));
  }

  updateManagerOrder(orderId: number, request: OrderUpdateRequest): Observable<OrderEditPayload> {
    return this.http.put<OrderEditPayload>(this.apiUrl(`/api/manager/orders/${orderId}`), request);
  }

  deleteManagerOrder(orderId: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/manager/orders/${orderId}`));
  }

  getManagerOrderDetails(orderId: number): Observable<OrderDetailsPayload> {
    return this.http.get<OrderDetailsPayload>(this.apiUrl(`/api/manager/orders/${orderId}/details`));
  }

  getManagerOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> {
    return this.http.get<CompanyDeepReportState>(this.apiUrl(`/api/manager/orders/${orderId}/company-report`));
  }

  startManagerOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> {
    return this.http.post<CompanyDeepReportState>(this.apiUrl(`/api/manager/orders/${orderId}/company-report`), {});
  }

  refreshManagerOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> {
    return this.http.post<CompanyDeepReportState>(this.apiUrl(`/api/manager/orders/${orderId}/company-report/refresh`), {});
  }

  addManagerOrderReview(orderId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(this.apiUrl(`/api/manager/orders/${orderId}/reviews`), {});
  }

  updateManagerOrderReviewText(orderId: number, reviewId: number, text: string): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/text`), { text });
  }

  updateManagerOrderReviewAnswer(orderId: number, reviewId: number, answer: string): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/answer`), { answer });
  }

  updateManagerOrderReviewNote(orderId: number, reviewId: number, comment: string): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/note`), { comment });
  }

  updateManagerOrderReview(orderId: number, reviewId: number, request: ReviewUpdateRequest): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}`), request);
  }

  uploadManagerOrderReviewPhoto(orderId: number, reviewId: number, file: File): Observable<OrderReviewItem> {
    const formData = new FormData();
    formData.append('file', file);

    return this.http.post<OrderReviewItem>(
      this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/photo`),
      formData
    );
  }

  deleteManagerOrderReview(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.delete<OrderDetailsPayload>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}`));
  }

  publishManagerOrderReview(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/publish`), {});
  }

  changeManagerOrderReviewText(orderId: number, reviewId: number): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/change-text`), {});
  }

  assignManagerOrderReviewNewAccount(orderId: number, reviewId: number): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/new-account`), {});
  }

  changeManagerOrderReviewBot(orderId: number, reviewId: number): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/change-bot`), {});
  }

  deactivateManagerOrderReviewBot(orderId: number, reviewId: number, botId: number): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(
      this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/bots/${botId}/deactivate`),
      {}
    );
  }

  cancelManagerBadReviewTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/cancel`),
      {}
    );
  }

  completeManagerBadReviewTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/complete`),
      {}
    );
  }

  updateManagerBadReviewTask(
    orderId: number,
    taskId: number,
    request: BadReviewTaskUpdateRequest
  ): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/bad-review-tasks/${taskId}`),
      request
    );
  }

  changeManagerBadReviewTaskBot(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/change-bot`),
      {}
    );
  }

  createManagerReviewRecoveryTask(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/recovery-tasks`),
      {}
    );
  }

  updateManagerReviewRecoveryTask(
    orderId: number,
    taskId: number,
    request: ReviewRecoveryTaskUpdateRequest
  ): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/recovery-tasks/${taskId}`),
      request
    );
  }

  completeManagerReviewRecoveryTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/recovery-tasks/${taskId}/complete`),
      {}
    );
  }

  deleteManagerReviewRecoveryTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.delete<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/recovery-tasks/${taskId}`)
    );
  }

  markManagerRecoveryClientNotified(orderId: number, batchId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/recovery-batches/${batchId}/client-notified`),
      {}
    );
  }

  createManagerReviewHelpDrafts(orderId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/help-drafts`), {});
  }

  createManagerReviewHelpDraftForCard(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/help-drafts`),
      {}
    );
  }

  updateManagerCompanyNote(companyId: number, companyComments: string): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/manager/companies/${companyId}/note`), { companyComments });
  }

  updateManagerOrderNote(orderId: number, orderComments: string): Observable<OrderNotesResponse> {
    return this.http.put<OrderNotesResponse>(this.apiUrl(`/api/manager/orders/${orderId}/note`), { orderComments });
  }

  updateManagerOrderCompanyNote(orderId: number, companyComments: string): Observable<OrderNotesResponse> {
    return this.http.put<OrderNotesResponse>(this.apiUrl(`/api/manager/orders/${orderId}/company-note`), { companyComments });
  }

  getReviewCheck(orderDetailId: string): Observable<ReviewCheckPayload> {
    return this.http.get<ReviewCheckPayload>(this.apiUrl(`/api/review-check/${orderDetailId}`));
  }

  saveReviewCheck(orderDetailId: string, request: ReviewCheckUpdateRequest): Observable<ReviewCheckPayload> {
    return this.http.put<ReviewCheckPayload>(this.apiUrl(`/api/review-check/${orderDetailId}`), request);
  }

  updateReviewCheckText(orderDetailId: string, reviewId: number, text: string): Observable<ReviewCheckReview> {
    return this.http.put<ReviewCheckReview>(
      this.apiUrl(`/api/review-check/${orderDetailId}/reviews/${reviewId}/text`),
      { text }
    );
  }

  updateReviewCheckAnswer(orderDetailId: string, reviewId: number, answer: string): Observable<ReviewCheckReview> {
    return this.http.put<ReviewCheckReview>(
      this.apiUrl(`/api/review-check/${orderDetailId}/reviews/${reviewId}/answer`),
      { answer }
    );
  }

  approveReviewCheck(orderDetailId: string, request: ReviewCheckUpdateRequest): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(this.apiUrl(`/api/review-check/${orderDetailId}/approve`), request);
  }

  sendReviewCheckToCorrection(orderDetailId: string, request: ReviewCheckUpdateRequest): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(this.apiUrl(`/api/review-check/${orderDetailId}/correction`), request);
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

  getManagerCompanyEdit(companyId: number): Observable<CompanyEditPayload> {
    return this.http.get<CompanyEditPayload>(this.apiUrl(`/api/manager/companies/${companyId}/edit`));
  }

  updateManagerCompany(companyId: number, request: CompanyUpdateRequest): Observable<CompanyEditPayload> {
    return this.http.put<CompanyEditPayload>(this.apiUrl(`/api/manager/companies/${companyId}`), request);
  }

  getManagerCompanySubcategories(categoryId: number): Observable<ManagerOption[]> {
    return this.http.get<ManagerOption[]>(this.apiUrl(`/api/manager/categories/${categoryId}/subcategories`));
  }

  deleteManagerCompanyWorker(companyId: number, workerId: number): Observable<CompanyEditPayload> {
    return this.http.delete<CompanyEditPayload>(this.apiUrl(`/api/manager/companies/${companyId}/workers/${workerId}`));
  }

  deleteManagerCompanyFilial(companyId: number, filialId: number): Observable<CompanyEditPayload> {
    return this.http.delete<CompanyEditPayload>(this.apiUrl(`/api/manager/companies/${companyId}/filials/${filialId}`));
  }

  updateManagerCompanyFilial(
    companyId: number,
    filialId: number,
    request: CompanyFilialUpdateRequest
  ): Observable<CompanyEditPayload> {
    return this.http.put<CompanyEditPayload>(
      this.apiUrl(`/api/manager/companies/${companyId}/filials/${filialId}`),
      request
    );
  }

  getWorkerBoard(query: WorkerBoardQuery = {}): Observable<WorkerBoard> {
    let params = new HttpParams()
      .set('section', query.section ?? 'all')
      .set('keyword', query.keyword?.trim() ?? '')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('sortDirection', query.sortDirection ?? 'desc');

    if (query.workerId) {
      params = params.set('workerId', String(query.workerId));
    }

    return this.http.get<WorkerBoard>(this.apiUrl('/api/worker/board'), { params });
  }

  getWorkerOverdueOrders(): Observable<ManagerOverdueOrders> {
    return this.http.get<ManagerOverdueOrders>(this.apiUrl('/api/worker/overdue-orders'));
  }

  updateWorkerOrderStatus(orderId: number, status: string): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/orders/${orderId}/status`), { status });
  }

  updateWorkerOrderClientWaiting(orderId: number, waitingForClient: boolean): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/orders/${orderId}/client-waiting`), { waitingForClient });
  }

  updateWorkerOrderNote(orderId: number, orderComments: string): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/orders/${orderId}/note`), { orderComments });
  }

  updateWorkerOrderCompanyNote(orderId: number, companyComments: string): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/orders/${orderId}/company-note`), { companyComments });
  }

  changeWorkerReviewBot(reviewId: number): Observable<BotChangeResponse> {
    return this.http.post<BotChangeResponse>(this.apiUrl(`/api/worker/reviews/${reviewId}/change-bot`), {});
  }

  deactivateWorkerReviewBot(reviewId: number, botId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/bots/${botId}/deactivate`), {});
  }

  publishWorkerReview(reviewId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/publish`), {});
  }

  nagulWorkerReview(reviewId: number): Observable<WorkerActionResponse> {
    return this.http.post<WorkerActionResponse>(this.apiUrl(`/api/worker/reviews/${reviewId}/nagul`), {});
  }

  completeWorkerBadReviewTask(taskId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/bad-review-tasks/${taskId}/complete`), {});
  }

  updateWorkerBadReviewTask(taskId: number, taskText: string, scheduledDate?: string | null): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/bad-review-tasks/${taskId}`), {
      taskText,
      scheduledDate: scheduledDate || null
    });
  }

  changeWorkerBadReviewTaskBot(taskId: number): Observable<BotChangeResponse> {
    return this.http.post<BotChangeResponse>(this.apiUrl(`/api/worker/bad-review-tasks/${taskId}/change-bot`), {});
  }

  deactivateWorkerBadReviewTaskBot(taskId: number, botId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/bad-review-tasks/${taskId}/bots/${botId}/deactivate`), {});
  }

  updateWorkerRecoveryTask(
    taskId: number,
    recoveryText: string,
    scheduledDate?: string | null,
    recoveryAnswer?: string | null
  ): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/recovery-tasks/${taskId}`), {
      recoveryText,
      recoveryAnswer,
      scheduledDate: scheduledDate || null
    });
  }

  completeWorkerRecoveryTask(taskId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/recovery-tasks/${taskId}/complete`), {});
  }

  changeWorkerRecoveryTaskBot(taskId: number): Observable<BotChangeResponse> {
    return this.http.post<BotChangeResponse>(this.apiUrl(`/api/worker/recovery-tasks/${taskId}/change-bot`), {});
  }

  deactivateWorkerRecoveryTaskBot(taskId: number, botId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/recovery-tasks/${taskId}/bots/${botId}/deactivate`), {});
  }

  deleteWorkerBot(botId: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/worker/bots/${botId}`));
  }

  logWorkerReviewCopyClick(reviewId: number, field: 'login' | 'password'): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/copy-click`), { field });
  }

  updateWorkerReviewText(reviewId: number, orderId: number, text: string): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/text`), { orderId, text });
  }

  updateWorkerReviewAnswer(reviewId: number, orderId: number, answer: string): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/answer`), { orderId, answer });
  }

  updateWorkerReviewNote(reviewId: number, orderId: number, comment: string): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/note`), { orderId, comment });
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

  getCompanyCreatePayload(
    source: CompanyCreateSource,
    leadId?: number | null,
    managerId?: number | null
  ): Observable<CompanyCreatePayload> {
    let params = new HttpParams().set('source', source);

    if (leadId != null) {
      params = params.set('leadId', String(leadId));
    }

    if (managerId != null) {
      params = params.set('managerId', String(managerId));
    }

    return this.http.get<CompanyCreatePayload>(this.apiUrl('/api/companies/create-payload'), { params });
  }

  getCompanySubcategories(categoryId: number): Observable<CompanyCreateOption[]> {
    return this.http.get<CompanyCreateOption[]>(this.apiUrl(`/api/companies/categories/${categoryId}/subcategories`));
  }

  createCompany(request: CompanyCreateRequest): Observable<CompanyCreateResult> {
    return this.http.post<CompanyCreateResult>(this.apiUrl('/api/companies'), request);
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

  getOperatorPhones(keyword = ''): Observable<OperatorPhonesResponse> {
    const params = this.keywordParams(keyword);
    return this.http.get<OperatorPhonesResponse>(this.apiUrl('/api/admin/phones'), { params });
  }

  createOperatorPhone(request: OperatorPhoneRequest): Observable<OperatorPhone> {
    return this.http.post<OperatorPhone>(this.apiUrl('/api/admin/phones'), request);
  }

  updateOperatorPhone(id: number, request: OperatorPhoneRequest): Observable<OperatorPhone> {
    return this.http.put<OperatorPhone>(this.apiUrl(`/api/admin/phones/${id}`), request);
  }

  deleteOperatorPhone(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/phones/${id}`));
  }

  deleteOperatorPhoneDeviceToken(phoneId: number, token: string): Observable<void> {
    return this.http.delete<void>(
      this.apiUrl(`/api/admin/phones/${phoneId}/device-tokens/${encodeURIComponent(token)}`)
    );
  }

  getTbankStatus(): Observable<TbankPaymentStatus> {
    return this.http.get<TbankPaymentStatus>(this.apiUrl('/api/payments/public/tbank-status'));
  }

  createManagerOrderPaymentLink(orderId: number): Observable<ManagerPaymentLinkResponse> {
    return this.http.post<ManagerPaymentLinkResponse>(
      this.apiUrl(`/api/manager/orders/${orderId}/payment-link`),
      {}
    );
  }

  getAdminTbankPaymentLinks(params?: {
    page?: number;
    size?: number;
    status?: string;
    search?: string;
    source?: PaymentLinkListSource;
    from?: string;
    to?: string;
  }): Observable<AdminPaymentLinksPageResponse> {
    let httpParams = new HttpParams();
    if (params) {
      Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null && String(value).trim() !== '') {
          httpParams = httpParams.set(key, String(value));
        }
      });
    }
    return this.http.get<AdminPaymentLinksPageResponse>(
      this.apiUrl('/api/admin/payments/tbank-links'),
      { params: httpParams }
    );
  }

  runAdminPaymentLinkArchive(dryRun: boolean, batchSize?: number): Observable<PaymentLinkArchiveRunResponse> {
    let params = new HttpParams().set('dryRun', String(dryRun));
    if (batchSize != null && Number.isFinite(batchSize) && batchSize > 0) {
      params = params.set('batchSize', String(batchSize));
    }
    return this.http.post<PaymentLinkArchiveRunResponse>(
      this.apiUrl('/api/admin/payments/tbank-links/archive/run'),
      {},
      { params }
    );
  }

  cancelAdminTbankPaymentLink(linkId: number): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      this.apiUrl(`/api/admin/payments/tbank-links/${linkId}/cancel`),
      {}
    );
  }

  confirmAdminManualPaymentLink(linkId: number): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      this.apiUrl(`/api/admin/payments/manual-links/${linkId}/confirm`),
      {}
    );
  }

  markAdminManualPaymentReceipt(linkId: number): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      this.apiUrl(`/api/admin/payments/manual-links/${linkId}/receipt`),
      {}
    );
  }

  getAdminTbankPaymentProfiles(): Observable<TbankPaymentProfilesResponse> {
    return this.http.get<TbankPaymentProfilesResponse>(this.apiUrl('/api/admin/payments/tbank-profiles'));
  }

  getAdminTbankRuntimeSettings(): Observable<TbankRuntimeSettings> {
    return this.http.get<TbankRuntimeSettings>(this.apiUrl('/api/admin/payments/tbank-runtime-settings'));
  }

  updateAdminTbankRuntimeSettings(
    request: UpdateTbankRuntimeSettingsRequest
  ): Observable<TbankRuntimeSettings> {
    return this.http.put<TbankRuntimeSettings>(
      this.apiUrl('/api/admin/payments/tbank-runtime-settings'),
      request
    );
  }

  updateAdminTbankPaymentProfileAssignments(
    assignments: ManagerPaymentProfileAssignmentRequest[]
  ): Observable<TbankPaymentProfilesResponse> {
    return this.http.put<TbankPaymentProfilesResponse>(
      this.apiUrl('/api/admin/payments/tbank-profiles/manager-assignments'),
      { assignments }
    );
  }

  updateAdminPaymentProfilePolicies(
    profiles: PaymentProfilePolicyRequest[]
  ): Observable<TbankPaymentProfilesResponse> {
    return this.http.put<TbankPaymentProfilesResponse>(
      this.apiUrl('/api/admin/payments/tbank-profiles/policies'),
      { profiles }
    );
  }

  getAdminManualPaymentTasks(): Observable<ManualPaymentTaskResponse[]> {
    return this.http.get<ManualPaymentTaskResponse[]>(this.apiUrl('/api/admin/payments/manual-tasks'));
  }

  createAdminManualPaymentTask(
    request: CreateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.post<ManualPaymentTaskResponse>(
      this.apiUrl('/api/admin/payments/manual-tasks'),
      request
    );
  }

  updateAdminManualPaymentTaskStatus(
    taskId: number,
    status: ManualPaymentTaskStatus
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.put<ManualPaymentTaskResponse>(
      this.apiUrl(`/api/admin/payments/manual-tasks/${taskId}/status`),
      { status }
    );
  }

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
