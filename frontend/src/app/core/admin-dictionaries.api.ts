import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

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
  accountWalkedCounterThreshold: number;
  accountWalkDelayDays: number;
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

export interface AdminGamificationSettings {
  enabled: boolean;
  workerEnabled: boolean;
  managerEnabled: boolean;
  operatorEnabled: boolean;
  marketologEnabled: boolean;
  showInCabinet: boolean;
  showInScore: boolean;
  eventsEnabled: boolean;
  shadowScoringEnabled: boolean;
  updatedAt?: string | null;
}

export type AdminGamificationSettingsRequest = Omit<AdminGamificationSettings, 'updatedAt'>;

export interface AdminGamificationEvent {
  id: number;
  eventType: string;
  actorUserId?: number | null;
  actorRole?: string | null;
  actorName?: string | null;
  orderId?: number | null;
  reviewId?: number | null;
  badReviewTaskId?: number | null;
  recoveryTaskId?: number | null;
  workerId?: number | null;
  managerId?: number | null;
  companyId?: number | null;
  companyTitle?: string | null;
  source?: string | null;
  payload?: string | null;
  plannedDate?: string | null;
  actualDate?: string | null;
  delayDays?: number | null;
  timelinessBucket?: string | null;
  timelinessMultiplier?: number | null;
  createdAt: string;
}

export interface AdminGamificationEventTypeProgress {
  eventType: string;
  events: number;
}

export interface AdminGamificationActorProgress {
  actorUserId?: number | null;
  actorName?: string | null;
  actorRole?: string | null;
  events: number;
}

export interface AdminGamificationProgress {
  from: string;
  to: string;
  days: number;
  totalEvents: number;
  eventTypes: AdminGamificationEventTypeProgress[];
  topActors: AdminGamificationActorProgress[];
}

export interface AdminGamificationRule {
  eventType: string;
  enabled: boolean;
  points: number;
  updatedAt?: string | null;
}

export interface AdminGamificationRulesResponse {
  rules: AdminGamificationRule[];
}

export interface AdminGamificationRulesRequest {
  rules: Array<Pick<AdminGamificationRule, 'eventType' | 'enabled' | 'points'>>;
}

export interface AdminGamificationScorePreviewActor {
  actorUserId?: number | null;
  actorName?: string | null;
  actorRole?: string | null;
  totalEvents: number;
  totalPoints: number;
}

export interface AdminGamificationScorePreview {
  from: string;
  to: string;
  days: number;
  totalPoints: number;
  topActors: AdminGamificationScorePreviewActor[];
}

export interface AdminGamificationScoreLedger {
  from: string;
  to: string;
  days: number;
  totalEvents: number;
  totalPoints: number;
  previewPoints: number;
  pointsDelta: number;
  topActors: AdminGamificationScorePreviewActor[];
}

export interface AdminGamificationScoreLedgerRebuild {
  from: string;
  to: string;
  days: number;
  shadowScoringEnabled: boolean;
  eventsReviewed: number;
  entriesDeleted: number;
  entriesCreated: number;
  totalPoints: number;
}

export interface AdminGamificationBackfill {
  from: string;
  to: string;
  days: number;
  reviewedCandidates: number;
  eventsCreated: number;
  reviewPublishedReviewed: number;
  reviewPublishedCreated: number;
  orderPaidReviewed: number;
  orderPaidCreated: number;
  badReviewTaskDoneReviewed: number;
  badReviewTaskDoneCreated: number;
  reviewRecoveryTaskDoneReviewed: number;
  reviewRecoveryTaskDoneCreated: number;
  ledgerRebuild: AdminGamificationScoreLedgerRebuild;
}

export interface AdminGamificationBalance {
  actorUserId?: number | null;
  actorName?: string | null;
  actorRole?: string | null;
  totalEvents: number;
  totalPoints: number;
  reviewPublishedEvents: number;
  reviewPublishedPoints: number;
  orderPaidEvents: number;
  orderPaidPoints: number;
  badReviewTaskDoneEvents: number;
  badReviewTaskDonePoints: number;
  reviewRecoveryTaskDoneEvents: number;
  reviewRecoveryTaskDonePoints: number;
  onTimeEvents: number;
  delayedEvents: number;
  lostPoints: number;
}

export interface AdminGamificationBalances {
  from: string;
  to: string;
  days: number;
  balances: AdminGamificationBalance[];
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
  readyToSendNow: number;
  waitingForWindow: number;
  missingChannelBindings: number;
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
  nowIrkutsk: string;
  updatedAt: string;
  nextAttemptAt?: string | null;
  pausedUntil?: string | null;
  pauseReason?: string | null;
  activeCandidates: number;
  dueNow: number;
  readyToSendNow: number;
  waitingForWindow: number;
  missingChannelBindings: number;
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
  accountWalkedCounterThreshold: number;
  accountWalkDelayDays: number;
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

@Injectable({ providedIn: 'root' })
export class AdminDictionariesApi {
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin`;

  constructor(private readonly http: HttpClient) {}

  getCategories(keyword = ''): Observable<AdminCategory[]> {
    return this.http.get<AdminCategory[]>(`${this.baseUrl}/categories`, {
      params: this.keywordParams(keyword)
    });
  }

  createCategory(request: TitleRequest): Observable<AdminCategory> {
    return this.http.post<AdminCategory>(`${this.baseUrl}/categories`, request);
  }

  updateCategory(id: number, request: TitleRequest): Observable<AdminCategory> {
    return this.http.put<AdminCategory>(`${this.baseUrl}/categories/${id}`, request);
  }

  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/categories/${id}`);
  }

  getSubCategories(keyword = '', categoryId?: number | null): Observable<AdminSubCategory[]> {
    let params = this.keywordParams(keyword);
    if (categoryId != null) {
      params = params.set('categoryId', String(categoryId));
    }

    return this.http.get<AdminSubCategory[]>(`${this.baseUrl}/subcategories`, { params });
  }

  createSubCategory(request: SubCategoryRequest): Observable<AdminSubCategory> {
    return this.http.post<AdminSubCategory>(`${this.baseUrl}/subcategories`, request);
  }

  updateSubCategory(id: number, request: SubCategoryRequest): Observable<AdminSubCategory> {
    return this.http.put<AdminSubCategory>(`${this.baseUrl}/subcategories/${id}`, request);
  }

  deleteSubCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/subcategories/${id}`);
  }

  getCities(keyword = ''): Observable<AdminCity[]> {
    return this.http.get<AdminCity[]>(`${this.baseUrl}/cities`, {
      params: this.keywordParams(keyword)
    });
  }

  createCity(request: TitleRequest): Observable<AdminCity> {
    return this.http.post<AdminCity>(`${this.baseUrl}/cities`, request);
  }

  updateCity(id: number, request: TitleRequest): Observable<AdminCity> {
    return this.http.put<AdminCity>(`${this.baseUrl}/cities/${id}`, request);
  }

  deleteCity(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/cities/${id}`);
  }

  getProducts(keyword = ''): Observable<ProductsResponse> {
    return this.http.get<ProductsResponse>(`${this.baseUrl}/products`, {
      params: this.keywordParams(keyword)
    });
  }

  createProduct(request: ProductRequest): Observable<AdminProduct> {
    return this.http.post<AdminProduct>(`${this.baseUrl}/products`, request);
  }

  updateProduct(id: number, request: ProductRequest): Observable<AdminProduct> {
    return this.http.put<AdminProduct>(`${this.baseUrl}/products/${id}`, request);
  }

  deleteProduct(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/products/${id}`);
  }

  getBots(keyword = '', page = 0, size = 50): Observable<BotsResponse> {
    const params = this.keywordParams(keyword)
      .set('page', String(page))
      .set('size', String(size));
    return this.http.get<BotsResponse>(`${this.baseUrl}/bots`, {
      params
    });
  }

  getBot(id: number): Observable<AdminBot> {
    return this.http.get<AdminBot>(`${this.baseUrl}/bots/${id}`);
  }

  createBot(request: BotRequest): Observable<AdminBot> {
    return this.http.post<AdminBot>(`${this.baseUrl}/bots`, request);
  }

  updateBot(id: number, request: BotRequest): Observable<AdminBot> {
    return this.http.put<AdminBot>(`${this.baseUrl}/bots/${id}`, request);
  }

  deleteBot(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/bots/${id}`);
  }

  importBots(file: File, cityId?: number | null): Observable<BotImportResponse> {
    const formData = new FormData();
    formData.append('file', file);
    const params = cityId == null ? undefined : new HttpParams().set('cityId', String(cityId));
    return this.http.post<BotImportResponse>(`${this.baseUrl}/bots/import`, formData, { params });
  }

  openBotBrowser(botId: number): Observable<BotBrowserOpenResponse> {
    return this.http.post<BotBrowserOpenResponse>(
      `${appEnvironment.apiBaseUrl}/api/bots/${botId}/browser/open`,
      {}
    );
  }

  closeBotBrowser(botId: number): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/bots/${botId}/browser/close`,
      {}
    );
  }

  getPromoTexts(keyword = ''): Observable<AdminPromoText[]> {
    return this.http.get<AdminPromoText[]>(`${this.baseUrl}/promo-texts`, {
      params: this.keywordParams(keyword)
    });
  }

  getPromoTextManagement(keyword = ''): Observable<PromoTextManagementResponse> {
    return this.http.get<PromoTextManagementResponse>(`${this.baseUrl}/promo-texts/management`, {
      params: this.keywordParams(keyword)
    });
  }

  createPromoText(request: PromoTextRequest): Observable<AdminPromoText> {
    return this.http.post<AdminPromoText>(`${this.baseUrl}/promo-texts`, request);
  }

  updatePromoText(id: number, request: PromoTextRequest): Observable<AdminPromoText> {
    return this.http.put<AdminPromoText>(`${this.baseUrl}/promo-texts/${id}`, request);
  }

  savePromoTextAssignment(request: PromoTextAssignmentRequest): Observable<PromoTextAssignment> {
    return this.http.put<PromoTextAssignment>(`${this.baseUrl}/promo-text-assignments`, request);
  }

  resetPromoTextAssignment(managerId: number, section: string, buttonKey: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/promo-text-assignments/${managerId}/${section}/${buttonKey}`
    );
  }

  getManagerTexts(keyword = ''): Observable<AdminManagerText[]> {
    return this.http.get<AdminManagerText[]>(`${this.baseUrl}/manager-texts`, {
      params: this.keywordParams(keyword)
    });
  }

  updateManagerText(managerId: number, request: ManagerTextRequest): Observable<AdminManagerText> {
    return this.http.put<AdminManagerText>(`${this.baseUrl}/manager-texts/${managerId}`, request);
  }

  getNagulSettings(): Observable<AdminNagulSettings> {
    return this.http.get<AdminNagulSettings>(`${this.baseUrl}/settings/nagul`);
  }

  updateNagulSettings(request: NagulSettingsRequest): Observable<AdminNagulSettings> {
    return this.http.put<AdminNagulSettings>(`${this.baseUrl}/settings/nagul`, request);
  }

  getTelegramReportSettings(): Observable<AdminTelegramReportScheduleSettings> {
    return this.http.get<AdminTelegramReportScheduleSettings>(`${this.baseUrl}/settings/telegram-reports`);
  }

  updateTelegramReportSettings(
    request: TelegramReportScheduleSettingsRequest
  ): Observable<AdminTelegramReportScheduleSettings> {
    return this.http.put<AdminTelegramReportScheduleSettings>(`${this.baseUrl}/settings/telegram-reports`, request);
  }

  getWhatsAppGroupSyncSettings(): Observable<AdminWhatsAppGroupSyncSettings> {
    return this.http.get<AdminWhatsAppGroupSyncSettings>(`${this.baseUrl}/settings/whatsapp-group-sync`);
  }

  updateWhatsAppGroupSyncSettings(
    request: WhatsAppGroupSyncSettingsRequest
  ): Observable<AdminWhatsAppGroupSyncSettings> {
    return this.http.put<AdminWhatsAppGroupSyncSettings>(`${this.baseUrl}/settings/whatsapp-group-sync`, request);
  }

  runWhatsAppGroupSync(): Observable<AdminWhatsAppGroupSyncSettings> {
    return this.http.post<AdminWhatsAppGroupSyncSettings>(`${this.baseUrl}/settings/whatsapp-group-sync/run`, {});
  }

  getClientPublicationProgressReportSettings(): Observable<AdminClientPublicationProgressReportSettings> {
    return this.http.get<AdminClientPublicationProgressReportSettings>(
      `${this.baseUrl}/settings/client-publication-progress-reports`
    );
  }

  updateClientPublicationProgressReportSettings(
    request: ClientPublicationProgressReportSettingsRequest
  ): Observable<AdminClientPublicationProgressReportSettings> {
    return this.http.put<AdminClientPublicationProgressReportSettings>(
      `${this.baseUrl}/settings/client-publication-progress-reports`,
      request
    );
  }

  getGamificationSettings(): Observable<AdminGamificationSettings> {
    return this.http.get<AdminGamificationSettings>(`${this.baseUrl}/gamification/settings`);
  }

  updateGamificationSettings(request: AdminGamificationSettingsRequest): Observable<AdminGamificationSettings> {
    return this.http.put<AdminGamificationSettings>(`${this.baseUrl}/gamification/settings`, request);
  }

  getGamificationEvents(limit = 50): Observable<AdminGamificationEvent[]> {
    return this.http.get<AdminGamificationEvent[]>(`${this.baseUrl}/gamification/events`, {
      params: { limit }
    });
  }

  getGamificationProgress(days = 1): Observable<AdminGamificationProgress> {
    return this.http.get<AdminGamificationProgress>(`${this.baseUrl}/gamification/progress`, {
      params: { days }
    });
  }

  getGamificationRules(): Observable<AdminGamificationRulesResponse> {
    return this.http.get<AdminGamificationRulesResponse>(`${this.baseUrl}/gamification/rules`);
  }

  updateGamificationRules(request: AdminGamificationRulesRequest): Observable<AdminGamificationRulesResponse> {
    return this.http.put<AdminGamificationRulesResponse>(`${this.baseUrl}/gamification/rules`, request);
  }

  getGamificationScorePreview(days = 1): Observable<AdminGamificationScorePreview> {
    return this.http.get<AdminGamificationScorePreview>(`${this.baseUrl}/gamification/score-preview`, {
      params: { days }
    });
  }

  getGamificationScoreLedger(days = 1): Observable<AdminGamificationScoreLedger> {
    return this.http.get<AdminGamificationScoreLedger>(`${this.baseUrl}/gamification/score-ledger`, {
      params: { days }
    });
  }

  rebuildGamificationScoreLedger(days = 1): Observable<AdminGamificationScoreLedgerRebuild> {
    return this.http.post<AdminGamificationScoreLedgerRebuild>(
      `${this.baseUrl}/gamification/score-ledger/rebuild`,
      {},
      { params: { days } }
    );
  }

  backfillGamificationEvents(days = 1): Observable<AdminGamificationBackfill> {
    return this.http.post<AdminGamificationBackfill>(
      `${this.baseUrl}/gamification/events/backfill`,
      {},
      { params: { days } }
    );
  }

  getGamificationBalances(days = 1): Observable<AdminGamificationBalances> {
    return this.http.get<AdminGamificationBalances>(`${this.baseUrl}/gamification/balances`, {
      params: { days }
    });
  }

  getClientMessageSettings(): Observable<AdminClientMessageSettings> {
    return this.http.get<AdminClientMessageSettings>(`${this.baseUrl}/settings/client-messages`);
  }

  updateClientMessageSettings(request: ClientMessageSettingsRequest): Observable<AdminClientMessageSettings> {
    return this.http.put<AdminClientMessageSettings>(`${this.baseUrl}/settings/client-messages`, request);
  }

  getClientMessageMonitor(): Observable<AdminClientMessageMonitor> {
    return this.http.get<AdminClientMessageMonitor>(`${appEnvironment.apiBaseUrl}/api/admin/client-messages/monitor`);
  }

  getClientMessageMaintenancePreview(): Observable<AdminClientMessageMaintenancePreview> {
    return this.http.get<AdminClientMessageMaintenancePreview>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/maintenance-preview`
    );
  }

  applyClientMessageMaintenance(action: 'company-statuses' | 'payment-overdue' | 'missing-bad-tasks' | 'archive-offers' | 'publication-dates' | 'publication-completed'): Observable<AdminMaintenanceApplyResponse> {
    return this.http.post<AdminMaintenanceApplyResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/maintenance/${action}`,
      {}
    );
  }

  updateClientMessageMonitorSettings(enabled: boolean): Observable<AdminClientMessageMonitorSettings> {
    return this.http.put<AdminClientMessageMonitorSettings>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/monitor`,
      { enabled }
    );
  }

  retryClientMessageNow(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.http.post<AdminClientMessageMonitor>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/monitor/${stateId}/retry-now`,
      {}
    );
  }

  disableClientMessageCandidate(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.http.post<AdminClientMessageMonitor>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/monitor/${stateId}/disable`,
      {}
    );
  }

  markClientMessageCandidateDone(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.http.post<AdminClientMessageMonitor>(
      `${appEnvironment.apiBaseUrl}/api/admin/client-messages/monitor/${stateId}/done`,
      {}
    );
  }

  runSharedChatLinkSync(): Observable<AdminSharedChatLinkSyncResponse> {
    return this.http.post<AdminSharedChatLinkSyncResponse>(`${this.baseUrl}/settings/shared-chat-links/sync`, {});
  }

  private keywordParams(keyword: string): HttpParams {
    const value = keyword.trim();
    return value ? new HttpParams().set('keyword', value) : new HttpParams();
  }
}
