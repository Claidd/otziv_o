import { AdminTaxonomyApi } from './admin-taxonomy.api';
import { AdminProductsApi } from './admin-products.api';
import { AdminCommunicationTextsApi } from './admin-communication-texts.api';
import { AdminClientMessageSettingsApi } from './admin-client-message-settings.api';
import { AdminCitiesApi } from './admin-cities.api';
import { AdminGamificationApi } from './admin-gamification.api';
import { AdminWorkSettingsApi } from './admin-work-settings.api';
import { AdminAccountsApi } from './admin-accounts.api';
import { AdminMessageMonitorApi } from './admin-message-monitor.api';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

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
  latitude?: number | null;
  longitude?: number | null;
  distanceMatrixReady: boolean;
  distanceCount: number;
}

export interface AdminProduct {
  id: number;
  title: string;
  price: number;
  photo: boolean;
  requiresPerformer: boolean;
  targetPlatform: PerformerTargetPlatform;
  performerRewardPercent: number;
  specialistRewardPercent: number;
  managerRewardPercent: number;
  category?: DictionaryOption | null;
}

export type PerformerTargetPlatform = 'YANDEX' | 'GOOGLE' | 'GIS' | 'OTHER';

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

export interface BotCityUnblockedCountResponse {
  cityId: number;
  unblockedAccounts: number;
}

export interface BotCountResponse {
  count: number;
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

export type WorkerCellularAccessMode = 'OFF' | 'AUDIT' | 'ENFORCE';
export type WorkerCellularAccessReason =
  | 'NON_CELLULAR_NETWORK'
  | 'VPN_PROXY_OR_DATACENTER'
  | 'DESKTOP_OR_UNKNOWN_DEVICE'
  | 'UNKNOWN_NETWORK';

export interface AdminWorkerCellularAccessSettings {
  mode: WorkerCellularAccessMode;
  enforcedReasons: WorkerCellularAccessReason[];
  enforceNativeVirtualDevice: boolean;
  requireMobileDevice: boolean;
  ipIntelligenceEnabled: boolean;
  allowedCidrs: string[];
  protectedSections: string[];
}

export interface WorkerCellularAccessSettingsRequest {
  mode: WorkerCellularAccessMode;
  enforcedReasons: WorkerCellularAccessReason[];
  enforceNativeVirtualDevice: boolean;
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
  unansweredAutoIgnoreEnabled: boolean;
  unansweredResolutionEnforcementEnabled: boolean;
  unansweredFastClickGuardEnabled: boolean;
  unansweredReplyQualityShadowEnabled: boolean;
  unansweredFastClickWarningCount: number;
  unansweredFastClickWarningSeconds: number;
  unansweredFastClickCriticalCount: number;
  unansweredFastClickCriticalSeconds: number;
  reviewCheckIntervalDays: number;
  reviewCheckAutoArchiveDays: number;
  clientTextReminderIntervalDays: number;
  paymentReminderIntervalDays: number;
  reviewCheckRetryDelayHours: number;
  paymentInvoiceRetryDelayHours: number;
  transientRetryMinutes: number;
  manualControlFailureThreshold: number;
  manualControlAfterMinutes: number;
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
  unansweredAutoIgnoreMaxLength: number;
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
  paymentInstructionSource: 'MANAGER_TEXT' | 'TBANK_LINK' | 'BANK_LINK' | 'TOCHKA_LINK';
  paymentReminderText: string;
  paymentLinkCopyText: string;
  paymentSuccessText: string;
  reviewRecoveryNoticeText: string;
  archiveOfferText: string;
  unansweredAutoIgnorePhrases: string;
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
  paymentInstructionSource?: 'MANAGER_TEXT' | 'TBANK_LINK' | 'BANK_LINK' | 'TOCHKA_LINK' | string | null;
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
  manualControlCandidates: number;
  retryWaitingCandidates: number;
  recoveryHoldCandidates: number;
  sentToday: number;
  failedToday: number;
  skippedToday: number;
  autoRecoveredToday: number;
  disabledStates: number;
  archiveDiagnostics?: AdminClientMessageArchiveDiagnostics | null;
  archiveOfferToday?: AdminClientMessageArchiveOfferToday | null;
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

export interface AdminWorkerAccountActionSettings {
  enabled: boolean;
  cooldownSeconds: number;
}

export interface AdminClientMessageArchiveOfferToday {
  plannedToday: number;
  queuedNow: number;
  processingNow: number;
  readyNow: number;
  sentToday: number;
  remainingToday: number;
  blockedByChannel: number;
  dailyLimitRemaining: number;
  forecastAdditionalToday: number;
  forecastTotalToday: number;
  forecastNote: string;
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

export interface CityRequest {
  title: string;
  latitude?: number | null;
  longitude?: number | null;
}

export interface CityDistanceRebuildResponse {
  citiesWithCoordinates: number;
  citiesWithoutCoordinates: number;
  distancesSaved: number;
}

export interface CityCoordinateImportResponse extends CityDistanceRebuildResponse {
  updated: number;
  skipped: number;
  errors: string[];
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
  requiresPerformer: boolean;
  targetPlatform: PerformerTargetPlatform;
  performerRewardPercent: number;
  specialistRewardPercent: number;
  managerRewardPercent: number;
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
  private readonly taxonomyApi = inject(AdminTaxonomyApi);
  private readonly productsApi = inject(AdminProductsApi);
  private readonly communicationTextsApi = inject(AdminCommunicationTextsApi);
  private readonly clientMessageSettingsApi = inject(AdminClientMessageSettingsApi);

  private readonly citiesApi = inject(AdminCitiesApi);
  private readonly gamificationApi = inject(AdminGamificationApi);

  private readonly workSettingsApi = inject(AdminWorkSettingsApi);

  private readonly accountsApi = inject(AdminAccountsApi);

  private readonly messageMonitorApi = inject(AdminMessageMonitorApi);

  getCategories(keyword = ''): Observable<AdminCategory[]> { return this.taxonomyApi.getCategories(keyword); }

  createCategory(request: TitleRequest): Observable<AdminCategory> { return this.taxonomyApi.createCategory(request); }

  updateCategory(id: number, request: TitleRequest): Observable<AdminCategory> { return this.taxonomyApi.updateCategory(id, request); }

  deleteCategory(id: number): Observable<void> { return this.taxonomyApi.deleteCategory(id); }

  getSubCategories(keyword = '', categoryId?: number | null): Observable<AdminSubCategory[]> { return this.taxonomyApi.getSubCategories(keyword, categoryId); }

  createSubCategory(request: SubCategoryRequest): Observable<AdminSubCategory> { return this.taxonomyApi.createSubCategory(request); }

  updateSubCategory(id: number, request: SubCategoryRequest): Observable<AdminSubCategory> { return this.taxonomyApi.updateSubCategory(id, request); }

  deleteSubCategory(id: number): Observable<void> { return this.taxonomyApi.deleteSubCategory(id); }

  getCities(keyword = ''): Observable<AdminCity[]> { return this.citiesApi.getCities(keyword); }
  createCity(request: CityRequest): Observable<AdminCity> { return this.citiesApi.createCity(request); }
  updateCity(id: number, request: CityRequest): Observable<AdminCity> { return this.citiesApi.updateCity(id, request); }
  deleteCity(id: number): Observable<void> { return this.citiesApi.deleteCity(id); }
  rebuildCityDistances(minCityId = 150): Observable<CityDistanceRebuildResponse> { return this.citiesApi.rebuildCityDistances(minCityId); }
  rebuildCityDistancesForCity(id: number): Observable<CityDistanceRebuildResponse> { return this.citiesApi.rebuildCityDistancesForCity(id); }
  importCityCoordinates(file: File): Observable<CityCoordinateImportResponse> { return this.citiesApi.importCityCoordinates(file); }

  getProducts(keyword = ''): Observable<ProductsResponse> { return this.productsApi.getProducts(keyword); }

  createProduct(request: ProductRequest): Observable<AdminProduct> { return this.productsApi.createProduct(request); }

  updateProduct(id: number, request: ProductRequest): Observable<AdminProduct> { return this.productsApi.updateProduct(id, request); }

  deleteProduct(id: number): Observable<void> { return this.productsApi.deleteProduct(id); }

  getBots(keyword = '', page = 0, size = 50): Observable<BotsResponse> { return this.accountsApi.getBots(keyword, page, size); }

  getBot(id: number): Observable<AdminBot> { return this.accountsApi.getBot(id); }

  getBotCount(): Observable<BotCountResponse> { return this.accountsApi.getBotCount(); }

  getBotCityUnblockedCount(cityId: number): Observable<BotCityUnblockedCountResponse> { return this.accountsApi.getBotCityUnblockedCount(cityId); }

  createBot(request: BotRequest): Observable<AdminBot> { return this.accountsApi.createBot(request); }

  updateBot(id: number, request: BotRequest): Observable<AdminBot> { return this.accountsApi.updateBot(id, request); }

  deleteBot(id: number): Observable<void> { return this.accountsApi.deleteBot(id); }

  importBots(file: File, cityId?: number | null): Observable<BotImportResponse> { return this.accountsApi.importBots(file, cityId); }

  openBotBrowser(botId: number): Observable<BotBrowserOpenResponse> {
    return this.accountsApi.openBotBrowser(botId);
  }

  getBotBrowserMetadata(botId: number): Observable<BotBrowserMetadata> {
    return this.accountsApi.getBotBrowserMetadata(botId);
  }

  heartbeatBotBrowser(botId: number, sessionId: string): Observable<void> {
    return this.accountsApi.heartbeatBotBrowser(botId, sessionId);
  }

  closeBotBrowser(botId: number, sessionId?: string | null): Observable<void> {
    return this.accountsApi.closeBotBrowser(botId, sessionId);
  }

  getPromoTexts(keyword = ''): Observable<AdminPromoText[]> { return this.communicationTextsApi.getPromoTexts(keyword); }

  getPromoTextManagement(keyword = ''): Observable<PromoTextManagementResponse> { return this.communicationTextsApi.getPromoTextManagement(keyword); }

  createPromoText(request: PromoTextRequest): Observable<AdminPromoText> { return this.communicationTextsApi.createPromoText(request); }

  updatePromoText(id: number, request: PromoTextRequest): Observable<AdminPromoText> { return this.communicationTextsApi.updatePromoText(id, request); }

  savePromoTextAssignment(request: PromoTextAssignmentRequest): Observable<PromoTextAssignment> { return this.communicationTextsApi.savePromoTextAssignment(request); }

  resetPromoTextAssignment(managerId: number, section: string, buttonKey: string): Observable<void> { return this.communicationTextsApi.resetPromoTextAssignment(managerId, section, buttonKey); }

  getManagerTexts(keyword = ''): Observable<AdminManagerText[]> { return this.communicationTextsApi.getManagerTexts(keyword); }

  updateManagerText(managerId: number, request: ManagerTextRequest): Observable<AdminManagerText> { return this.communicationTextsApi.updateManagerText(managerId, request); }

  getNagulSettings(): Observable<AdminNagulSettings> { return this.workSettingsApi.getNagulSettings(); }

  getWorkerAccountActionSettings(): Observable<AdminWorkerAccountActionSettings> { return this.workSettingsApi.getWorkerAccountActionSettings(); }

  updateWorkerAccountActionSettings(request: AdminWorkerAccountActionSettings): Observable<AdminWorkerAccountActionSettings> { return this.workSettingsApi.updateWorkerAccountActionSettings(request); }

  updateNagulSettings(request: NagulSettingsRequest): Observable<AdminNagulSettings> { return this.workSettingsApi.updateNagulSettings(request); }

  getWorkerCellularAccessSettings(): Observable<AdminWorkerCellularAccessSettings> { return this.workSettingsApi.getWorkerCellularAccessSettings(); }

  updateWorkerCellularAccessSettings(
    request: WorkerCellularAccessSettingsRequest
  ): Observable<AdminWorkerCellularAccessSettings> { return this.workSettingsApi.updateWorkerCellularAccessSettings(request); }

  getTelegramReportSettings(): Observable<AdminTelegramReportScheduleSettings> { return this.workSettingsApi.getTelegramReportSettings(); }

  updateTelegramReportSettings(
    request: TelegramReportScheduleSettingsRequest
  ): Observable<AdminTelegramReportScheduleSettings> { return this.workSettingsApi.updateTelegramReportSettings(request); }

  getWhatsAppGroupSyncSettings(): Observable<AdminWhatsAppGroupSyncSettings> { return this.workSettingsApi.getWhatsAppGroupSyncSettings(); }

  updateWhatsAppGroupSyncSettings(
    request: WhatsAppGroupSyncSettingsRequest
  ): Observable<AdminWhatsAppGroupSyncSettings> { return this.workSettingsApi.updateWhatsAppGroupSyncSettings(request); }

  runWhatsAppGroupSync(): Observable<AdminWhatsAppGroupSyncSettings> { return this.workSettingsApi.runWhatsAppGroupSync(); }

  getClientPublicationProgressReportSettings(): Observable<AdminClientPublicationProgressReportSettings> { return this.workSettingsApi.getClientPublicationProgressReportSettings(); }

  updateClientPublicationProgressReportSettings(
    request: ClientPublicationProgressReportSettingsRequest
  ): Observable<AdminClientPublicationProgressReportSettings> { return this.workSettingsApi.updateClientPublicationProgressReportSettings(request); }

  getGamificationSettings(): Observable<AdminGamificationSettings> { return this.gamificationApi.getGamificationSettings(); }

  updateGamificationSettings(request: AdminGamificationSettingsRequest): Observable<AdminGamificationSettings> { return this.gamificationApi.updateGamificationSettings(request); }

  getGamificationEvents(limit = 50): Observable<AdminGamificationEvent[]> { return this.gamificationApi.getGamificationEvents(limit); }

  getGamificationProgress(days = 1): Observable<AdminGamificationProgress> { return this.gamificationApi.getGamificationProgress(days); }

  getGamificationRules(): Observable<AdminGamificationRulesResponse> { return this.gamificationApi.getGamificationRules(); }

  updateGamificationRules(request: AdminGamificationRulesRequest): Observable<AdminGamificationRulesResponse> { return this.gamificationApi.updateGamificationRules(request); }

  getGamificationScorePreview(days = 1): Observable<AdminGamificationScorePreview> { return this.gamificationApi.getGamificationScorePreview(days); }

  getGamificationScoreLedger(days = 1): Observable<AdminGamificationScoreLedger> { return this.gamificationApi.getGamificationScoreLedger(days); }

  rebuildGamificationScoreLedger(days = 1): Observable<AdminGamificationScoreLedgerRebuild> { return this.gamificationApi.rebuildGamificationScoreLedger(days); }

  backfillGamificationEvents(days = 1): Observable<AdminGamificationBackfill> { return this.gamificationApi.backfillGamificationEvents(days); }

  getGamificationBalances(days = 1): Observable<AdminGamificationBalances> { return this.gamificationApi.getGamificationBalances(days); }

  getClientMessageSettings(): Observable<AdminClientMessageSettings> { return this.clientMessageSettingsApi.getClientMessageSettings(); }

  updateClientMessageSettings(request: ClientMessageSettingsRequest): Observable<AdminClientMessageSettings> { return this.clientMessageSettingsApi.updateClientMessageSettings(request); }

  getClientMessageMonitor(): Observable<AdminClientMessageMonitor> {
    return this.messageMonitorApi.getClientMessageMonitor();
  }

  getClientMessageMaintenancePreview(): Observable<AdminClientMessageMaintenancePreview> {
    return this.messageMonitorApi.getClientMessageMaintenancePreview();
  }

  applyClientMessageMaintenance(action: 'company-statuses' | 'payment-overdue' | 'missing-bad-tasks' | 'archive-offers' | 'publication-dates' | 'publication-completed'): Observable<AdminMaintenanceApplyResponse> {
    return this.messageMonitorApi.applyClientMessageMaintenance(action);
  }

  updateClientMessageMonitorSettings(enabled: boolean): Observable<AdminClientMessageMonitorSettings> {
    return this.messageMonitorApi.updateClientMessageMonitorSettings(enabled);
  }

  retryClientMessageNow(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.messageMonitorApi.retryClientMessageNow(stateId);
  }

  disableClientMessageCandidate(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.messageMonitorApi.disableClientMessageCandidate(stateId);
  }

  markClientMessageCandidateDone(stateId: number): Observable<AdminClientMessageMonitor> {
    return this.messageMonitorApi.markClientMessageCandidateDone(stateId);
  }

  runSharedChatLinkSync(): Observable<AdminSharedChatLinkSyncResponse> { return this.workSettingsApi.runSharedChatLinkSync(); }

}
