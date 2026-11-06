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
  sourceFileName?: string | null;
  importedAt?: string | null;
  duplicates?: BotImportDuplicate[];
  duplicateReportQueued?: boolean;
}

export interface BotImportDuplicate {
  rowNumber: number;
  login: string;
  reason: 'EXISTING_ACCOUNT' | 'DUPLICATE_IN_FILE';
  originalBotId: number | null;
  originalImportedAt: string | null;
  originalFileName: string | null;
  originalRowNumber: number | null;
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
