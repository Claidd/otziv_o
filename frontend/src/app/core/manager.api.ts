import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type {
  DeepCompanyResearchJob,
  ReputationSingleReviewDraftRequest,
  ReputationSingleReviewDraftResult
} from './reputation-ai.api';

export type ManagerSection = 'companies' | 'orders';
export type ArchiveOrderMode = 'all' | 'archive' | 'paid';

export interface ManagerPage<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
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

export interface CompanyCardItem {
  id: number;
  title: string;
  urlChat?: string;
  telephone?: string;
  countFilials: number;
  urlFilial?: string;
  status: string;
  manager?: string;
  commentsCompany?: string;
  city?: string;
  dateNewTry?: string;
  groupId?: string;
  telegramGroupChatId?: number | null;
  telegramGroupLinked?: boolean;
  telegramBotInviteUrl?: string;
  maxGroupChatId?: number | null;
  maxGroupLinked?: boolean;
  maxBotInviteUrl?: string;
  nextOrderRequestsCount?: number;
  failedNextOrderRequestsCount?: number;
  nextOrderRequestFilialTitle?: string;
  nextOrderRequestError?: string;
}

export interface ClientMessageStatus {
  state: 'sent' | 'scheduled' | 'failed' | 'manual_control' | 'none';
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

export interface OrderCardItem {
  id: number;
  companyId: number;
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
  companyUrlChat?: string;
  companyTelephone?: string;
  orderComments?: string;
  companyComments?: string;
  managerPayText?: string;
  amount?: number;
  counter?: number;
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
  telegramGroupLinked?: boolean;
  telegramBotInviteUrl?: string;
  maxGroupChatId?: number | null;
  maxGroupLinked?: boolean;
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

export interface ManagerMetric {
  label: string;
  value: number;
  delta?: number;
  icon: string;
  tone: 'blue' | 'green' | 'teal' | 'yellow' | 'pink' | 'gray';
  section: ManagerSection;
  status: string;
}

export interface ManagerBoard {
  section: ManagerSection;
  status: string;
  companies: ManagerPage<CompanyCardItem>;
  orders: ManagerPage<OrderCardItem>;
  companyStatuses: string[];
  orderStatuses: string[];
  metrics: ManagerMetric[];
  promoTexts: string[];
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

export interface ManagerOption {
  id: number;
  label: string;
}

export interface ProductOption {
  id: number;
  label: string;
  photo: boolean;
}

export interface OrderProductOption {
  id: number;
  label: string;
  price?: number;
  photo: boolean;
}

export interface CompanyFilialEditItem {
  id: number;
  title: string;
  url: string;
  cityId?: number | null;
  city: string;
}

export interface CompanyFilialUpdateRequest {
  title: string;
  url: string;
  cityId: number | null;
}

export interface CompanyEditPayload {
  id: number;
  title: string;
  urlChat: string;
  groupId?: string;
  telegramGroupChatId?: number | null;
  maxGroupChatId?: number | null;
  urlSite: string;
  telephone: string;
  city: string;
  email: string;
  phones: string;
  mobilePhones: string;
  whatsappPhones: string;
  emails: string;
  websites: string;
  vkUrl: string;
  telegramUrl: string;
  region: string;
  address: string;
  industries: string;
  companyType: string;
  commentsCompany: string;
  active: boolean;
  publicationProgressReportsEnabled: boolean;
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
  phones: string;
  mobilePhones: string;
  whatsappPhones: string;
  emails: string;
  websites: string;
  vkUrl: string;
  telegramUrl: string;
  region: string;
  address: string;
  industries: string;
  companyType: string;
  categoryId: number | null;
  subCategoryId: number | null;
  statusId: number | null;
  managerId: number | null;
  commentsCompany: string;
  active: boolean;
  publicationProgressReportsEnabled: boolean;
  newWorkerId: number | null;
  newFilialCityId: number | null;
  newFilialTitle: string;
  newFilialUrl: string;
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
  canCancelPayment: boolean;
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
  filialId?: number | null;
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
  filials: ManagerOption[];
  products: ProductOption[];
  canEditReviews: boolean;
  canSendToCheck: boolean;
  canEditReviewDates: boolean;
  canEditReviewPublish: boolean;
  canEditReviewVigul: boolean;
  canDeleteReviews: boolean;
}

export interface ReviewActivitySource {
  sourcePage?: string;
  sourceEntry?: string;
  sourceSection?: string;
}

export interface CompanyDeepReportState {
  companyId: number;
  companyName: string;
  latestJob: DeepCompanyResearchJob | null;
  activeJob: DeepCompanyResearchJob | null;
  canStart: boolean;
  canRefresh: boolean;
  unavailableReason: string;
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
  filialId: number | null;
  url: string;
}

export interface OrderNotesUpdate {
  orderComments: string;
  companyComments: string;
}

export interface ManagerBoardQuery {
  section: ManagerSection;
  status?: string;
  keyword?: string;
  companyId?: number;
  managerId?: number | null;
  control?: string | null;
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

@Injectable({ providedIn: 'root' })
export class ManagerApi {
  constructor(private readonly http: HttpClient) {}

  getBoard(query: ManagerBoardQuery): Observable<ManagerBoard> {
    let params = new HttpParams()
      .set('section', query.section)
      .set('status', query.status?.trim() || 'Все')
      .set('keyword', query.keyword?.trim() ?? '')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('sortDirection', query.sortDirection ?? 'desc');

    if (query.companyId != null) {
      params = params.set('companyId', String(query.companyId));
    }
    if (query.managerId != null) {
      params = params.set('managerId', String(query.managerId));
    }
    if (query.control) {
      params = params.set('control', query.control);
    }

    return this.http.get<ManagerBoard>(`${appEnvironment.apiBaseUrl}/api/manager/board`, { params });
  }

  getOverdueOrders(): Observable<ManagerOverdueOrders> {
    return this.http.get<ManagerOverdueOrders>(`${appEnvironment.apiBaseUrl}/api/manager/overdue-orders`);
  }

  getWorkerRiskIncidents(
    status: WorkerRiskIncidentStatus = 'OPEN',
    page = 0,
    size = 50
  ): Observable<ManagerPage<WorkerRiskIncident>> {
    const params = new HttpParams()
      .set('status', status)
      .set('page', String(page))
      .set('size', String(size));

    return this.http.get<ManagerPage<WorkerRiskIncident>>(
      `${appEnvironment.apiBaseUrl}/api/manager/worker-risk/incidents`,
      { params }
    );
  }

  resolveWorkerRiskIncident(incidentId: number): Observable<WorkerRiskIncident> {
    return this.http.post<WorkerRiskIncident>(
      `${appEnvironment.apiBaseUrl}/api/manager/worker-risk/incidents/${incidentId}/resolve`,
      {}
    );
  }

  ignoreWorkerRiskIncident(incidentId: number): Observable<WorkerRiskIncident> {
    return this.http.post<WorkerRiskIncident>(
      `${appEnvironment.apiBaseUrl}/api/manager/worker-risk/incidents/${incidentId}/ignore`,
      {}
    );
  }

  setWorkerRiskIncidentResolution(
    incidentId: number,
    action: WorkerRiskResolutionAction,
    penaltyPoints?: number
  ): Observable<WorkerRiskIncident> {
    return this.http.post<WorkerRiskIncident>(
      `${appEnvironment.apiBaseUrl}/api/manager/worker-risk/incidents/${incidentId}/resolution`,
      { action, penaltyPoints }
    );
  }

  rollbackWorkerRiskIncident(incidentId: number): Observable<WorkerRiskIncident> {
    return this.http.post<WorkerRiskIncident>(
      `${appEnvironment.apiBaseUrl}/api/manager/worker-risk/incidents/${incidentId}/rollback`,
      {}
    );
  }

  getArchiveOrders(query: ManagerArchiveOrdersQuery): Observable<ManagerPage<ArchiveOrderListItem>> {
    const params = new HttpParams()
      .set('keyword', query.keyword?.trim() ?? '')
      .set('mode', query.mode ?? 'all')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('sortDirection', query.sortDirection ?? 'desc');

    return this.http.get<ManagerPage<ArchiveOrderListItem>>(
      `${appEnvironment.apiBaseUrl}/api/manager/archive/orders`,
      { params }
    );
  }

  getArchiveOrder(orderId: number): Observable<ArchiveOrderDetailsPayload> {
    return this.http.get<ArchiveOrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/archive/orders/${orderId}`
    );
  }

  restoreArchiveOrder(orderId: number, targetStatus = 'Архив'): Observable<ArchiveRestoreResult> {
    const params = new HttpParams()
      .set('targetStatus', targetStatus)
      .set('confirm', 'true');

    return this.http.post<ArchiveRestoreResult>(
      `${appEnvironment.apiBaseUrl}/api/manager/archive/orders/${orderId}/restore`,
      {},
      { params }
    );
  }

  getCompanyEdit(companyId: number): Observable<CompanyEditPayload> {
    return this.http.get<CompanyEditPayload>(`${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/edit`);
  }

  getCompanyOrderCreate(companyId: number): Observable<CompanyOrderCreatePayload> {
    return this.http.get<CompanyOrderCreatePayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/order-create`
    );
  }

  createCompanyOrder(companyId: number, request: CompanyOrderCreateRequest): Observable<CompanyOrderCreateResult> {
    return this.http.post<CompanyOrderCreateResult>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/orders`,
      request
    );
  }

  updateCompany(companyId: number, request: CompanyUpdateRequest): Observable<CompanyEditPayload> {
    return this.http.put<CompanyEditPayload>(`${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}`, request);
  }

  updateCompanyNote(companyId: number, companyComments: string): Observable<void> {
    return this.http.put<void>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/note`,
      { companyComments }
    );
  }

  deleteCompanyWorker(companyId: number, workerId: number): Observable<CompanyEditPayload> {
    return this.http.delete<CompanyEditPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/workers/${workerId}`
    );
  }

  deleteCompanyFilial(companyId: number, filialId: number): Observable<CompanyEditPayload> {
    return this.http.delete<CompanyEditPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/filials/${filialId}`
    );
  }

  updateCompanyFilial(
    companyId: number,
    filialId: number,
    request: CompanyFilialUpdateRequest
  ): Observable<CompanyEditPayload> {
    return this.http.put<CompanyEditPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/filials/${filialId}`,
      request
    );
  }

  getCompanySubcategories(categoryId: number): Observable<ManagerOption[]> {
    return this.http.get<ManagerOption[]>(
      `${appEnvironment.apiBaseUrl}/api/manager/categories/${categoryId}/subcategories`
    );
  }

  updateCompanyStatus(companyId: number, status: string): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/status`,
      { status }
    );
  }

  getOrderEdit(orderId: number): Observable<OrderEditPayload> {
    return this.http.get<OrderEditPayload>(`${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/edit`);
  }

  updateOrder(orderId: number, request: OrderUpdateRequest): Observable<OrderEditPayload> {
    return this.http.put<OrderEditPayload>(`${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}`, request);
  }

  cancelOrderPayment(orderId: number): Observable<OrderEditPayload> {
    return this.http.post<OrderEditPayload>(`${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/payment-cancel`, {});
  }

  deleteOrder(orderId: number): Observable<void> {
    return this.http.delete<void>(`${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}`);
  }

  updateOrderStatus(orderId: number, status: string): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/status`,
      { status }
    );
  }

  updateOrderClientWaiting(orderId: number, waitingForClient: boolean): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/worker/orders/${orderId}/client-waiting`,
      { waitingForClient }
    );
  }

  getOrderDetails(orderId: number): Observable<OrderDetailsPayload> {
    return this.http.get<OrderDetailsPayload>(`${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/details`);
  }

  getOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> {
    return this.http.get<CompanyDeepReportState>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/company-report`
    );
  }

  startOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> {
    return this.http.post<CompanyDeepReportState>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/company-report`,
      {}
    );
  }

  refreshOrderCompanyReport(orderId: number): Observable<CompanyDeepReportState> {
    return this.http.post<CompanyDeepReportState>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/company-report/refresh`,
      {}
    );
  }

  addOrderReview(orderId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(`${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews`, {});
  }

  changeOrderReviewText(orderId: number, reviewId: number): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/change-text`,
      {}
    );
  }

  changeOrderReviewBot(orderId: number, reviewId: number, source?: ReviewActivitySource): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/change-bot`,
      source ?? {}
    );
  }

  assignOrderReviewNewAccount(orderId: number, reviewId: number): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/new-account`,
      {}
    );
  }

  deactivateOrderReviewBot(orderId: number, reviewId: number, botId: number, source?: ReviewActivitySource): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/bots/${botId}/deactivate`,
      source ?? {}
    );
  }

  logOrderReviewCopyClick(
    reviewId: number,
    field: 'login' | 'password',
    source?: ReviewActivitySource
  ): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/worker/reviews/${reviewId}/copy-click`,
      { field, ...source }
    );
  }

  publishOrderReview(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/publish`,
      {}
    );
  }

  cancelBadReviewTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/cancel`,
      {}
    );
  }

  completeBadReviewTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/complete`,
      {}
    );
  }

  updateBadReviewTask(
    orderId: number,
    taskId: number,
    request: BadReviewTaskUpdateRequest
  ): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/bad-review-tasks/${taskId}`,
      request
    );
  }

  changeBadReviewTaskBot(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/change-bot`,
      {}
    );
  }

  createReviewRecoveryTask(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/recovery-tasks`,
      {}
    );
  }

  createReviewHelpDraft(
    orderId: number,
    reviewId: number,
    request: ReputationSingleReviewDraftRequest
  ): Observable<ReputationSingleReviewDraftResult> {
    return this.http.post<ReputationSingleReviewDraftResult>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/help-draft`,
      request
    );
  }

  createReviewHelpDrafts(orderId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/help-drafts`,
      {}
    );
  }

  createReviewHelpDraftForCard(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/help-drafts`,
      {}
    );
  }

  updateReviewRecoveryTask(
    orderId: number,
    taskId: number,
    request: ReviewRecoveryTaskUpdateRequest
  ): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/recovery-tasks/${taskId}`,
      request
    );
  }

  completeReviewRecoveryTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/recovery-tasks/${taskId}/complete`,
      {}
    );
  }

  deleteReviewRecoveryTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.delete<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/recovery-tasks/${taskId}`
    );
  }

  markRecoveryClientNotified(orderId: number, batchId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/recovery-batches/${batchId}/client-notified`,
      {}
    );
  }

  updateOrderReview(orderId: number, reviewId: number, request: ReviewUpdateRequest): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}`,
      request
    );
  }

  uploadOrderReviewPhoto(orderId: number, reviewId: number, file: File): Observable<OrderReviewItem> {
    const formData = new FormData();
    formData.append('file', file);

    return this.http.post<OrderReviewItem>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/photo`,
      formData
    );
  }

  deleteOrderReview(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.delete<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}`
    );
  }

  updateOrderReviewText(orderId: number, reviewId: number, text: string): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/text`,
      { text }
    );
  }

  updateOrderReviewAnswer(orderId: number, reviewId: number, answer: string): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/answer`,
      { answer }
    );
  }

  updateOrderReviewNote(orderId: number, reviewId: number, comment: string): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/note`,
      { comment }
    );
  }

  updateOrderNote(orderId: number, orderComments: string): Observable<OrderNotesUpdate> {
    return this.http.put<OrderNotesUpdate>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/note`,
      { orderComments }
    );
  }

  updateOrderCompanyNote(orderId: number, companyComments: string): Observable<OrderNotesUpdate> {
    return this.http.put<OrderNotesUpdate>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/company-note`,
      { companyComments }
    );
  }
}
