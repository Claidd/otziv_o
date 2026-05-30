import { DatePipe } from '@angular/common';
import { Component, HostListener, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { forkJoin, Observable } from 'rxjs';
import {
  AdminBot,
  AdminCategory,
  AdminClientMessageMaintenancePreview,
  AdminClientMessageMonitor,
  AdminClientMessageMonitorAttempt,
  AdminClientMessageMonitorQueueItem,
  AdminClientMessageMonitorScenario,
  AdminClientMessageSettings,
  AdminClientPublicationProgressReportSettings,
  AdminCity,
  AdminDictionariesApi,
  AdminGamificationBalance,
  AdminGamificationBalances,
  AdminGamificationBackfill,
  AdminGamificationEvent,
  AdminGamificationProgress,
  AdminGamificationRule,
  AdminGamificationRulesRequest,
  AdminGamificationRulesResponse,
  AdminGamificationScoreLedger,
  AdminGamificationScoreLedgerRebuild,
  AdminGamificationScorePreview,
  AdminGamificationSettings,
  AdminGamificationSettingsRequest,
  AdminManagerText,
  AdminNagulSettings,
  AdminProduct,
  AdminPromoText,
  AdminSharedChatLinkSyncResponse,
  AdminSubCategory,
  AdminTelegramReportScheduleSettings,
  AdminWhatsAppGroupSyncSettings,
  BotImportResponse,
  BotRequest,
  BotsResponse,
  ClientPublicationProgressReportSettingsRequest,
  ClientMessageSettingsRequest,
  DictionaryOption,
  ManagerTextRequest,
  NagulSettingsRequest,
  PromoButtonSlot,
  PromoTextAssignment,
  PromoTextAssignmentRequest,
  PromoTextManagementResponse,
  ProductRequest,
  ProductsResponse,
  PromoTextRequest,
  SubCategoryRequest,
  TelegramReportScheduleSettingsRequest,
  TitleRequest,
  WhatsAppGroupSyncSettingsRequest
} from '../../../core/admin-dictionaries.api';
import { AuthService } from '../../../core/auth.service';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';
import { UiTooltipDirective } from '../../../shared/ui-tooltip.directive';
import {
  DeviceToken,
  OperatorPhone,
  OperatorPhoneRequest,
  OperatorPhonesApi,
  OperatorPhonesResponse,
  PhoneOperatorOption
} from '../../../core/operator-phones.api';

type DictionaryTabKey = 'categories' | 'subcategories' | 'cities' | 'products' | 'phones' | 'accounts' | 'promo' | 'managerTexts' | 'gamification' | 'settings' | 'autoresponder' | 'autoresponderMonitor';

type DictionaryTab = {
  key: DictionaryTabKey;
  label: string;
  icon: string;
};

type DictionaryMetric = {
  label: string;
  value: number;
  icon: string;
  tone: 'blue' | 'green' | 'teal' | 'yellow' | 'pink';
};

type DictionaryGuide = {
  title: string;
  text: string;
};

type DictionarySettingsResponse = {
  nagulSettings: AdminNagulSettings;
  telegramReportSettings: AdminTelegramReportScheduleSettings;
  whatsAppGroupSyncSettings: AdminWhatsAppGroupSyncSettings;
  clientPublicationProgressReportSettings: AdminClientPublicationProgressReportSettings;
};

type GamificationDictionaryResponse = {
  settings: AdminGamificationSettings;
  rules: AdminGamificationRulesResponse;
  progress: AdminGamificationProgress;
  scorePreview: AdminGamificationScorePreview;
  scoreLedger: AdminGamificationScoreLedger;
  balances: AdminGamificationBalances;
  events: AdminGamificationEvent[];
};

type GamificationProgressDays = 1 | 7 | 30;

const PROMO_TEXT_LABELS: Record<number, string> = {
  1: 'предложение',
  2: 'напоминание',
  3: 'данные',
  4: 'ответы',
  5: 'ссылка на проверку',
  6: 'напоминание заказа',
  7: 'угроза',
  10: 'рассылка',
  11: 'пояснение',
  12: 'текст повторного заказа'
};

const CLIENT_MESSAGE_MONITOR_POLLING_MS = 60000;

const DICTIONARY_GUIDES: Record<DictionaryTabKey, DictionaryGuide> = {
  categories: {
    title: 'Категории и подкатегории',
    text: 'Структура услуг, по которой менеджеры выбирают направление компании и продукта.'
  },
  subcategories: {
    title: 'Подкатегории',
    text: 'Уточняют основную категорию и помогают точнее разнести продукты.'
  },
  cities: {
    title: 'Города',
    text: 'Города используются в карточках, аккаунтах и фильтрах.'
  },
  products: {
    title: 'Продукты',
    text: 'Шаблонные позиции, которые попадают в заказы и сценарии работы.'
  },
  phones: {
    title: 'Телефоны',
    text: 'Рабочие номера, лимиты отправки и привязанные устройства.'
  },
  accounts: {
    title: 'Аккаунты',
    text: 'Аккаунты публикации и их привязка к исполнителю, городу и статусу.'
  },
  promo: {
    title: 'Промо-тексты',
    text: 'Готовые тексты для кнопок менеджера и персональные назначения.'
  },
  managerTexts: {
    title: 'Тексты менеджеров',
    text: 'Персональные шаблоны сообщений, которые менеджер использует в работе.'
  },
  gamification: {
    title: 'Геймификация',
    text: 'Отдельный контур настроек: глобальное включение, роли, показ в кабинете и события.'
  },
  settings: {
    title: 'Рабочие настройки',
    text: 'Паузы, расписания отчетов и синхронизации, которые влияют на автоматические процессы.'
  },
  autoresponder: {
    title: 'Автоответчик',
    text: 'Расписания, статусы, лимиты и тексты клиентских автонапоминаний.'
  },
  autoresponderMonitor: {
    title: 'Мониторинг автоответчика',
    text: 'Очередь кандидатов, сценарии, последние попытки отправки и ошибки без вмешательства в работу сервиса.'
  }
};

@Component({
  selector: 'app-admin-dictionaries',
  imports: [AdminLayoutComponent, DatePipe, LoadErrorCardComponent, ReactiveFormsModule, UiTooltipDirective],
  templateUrl: './admin-dictionaries.component.html',
  styleUrls: ['./admin-dictionaries.component.scss', './admin-dictionaries-monitor.component.scss']
})
export class AdminDictionariesComponent implements OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly dictionariesApi = inject(AdminDictionariesApi);
  private readonly phonesApi = inject(OperatorPhonesApi);
  private readonly auth = inject(AuthService);
  private readonly toastService = inject(ToastService);
  private readonly requestedPhoneId = Number(this.route.snapshot.queryParamMap.get('phoneId'));
  private monitorTimerId: ReturnType<typeof window.setInterval> | null = null;

  private readonly allTabs: DictionaryTab[] = [
    { key: 'categories', label: 'Категории', icon: 'category' },
    { key: 'cities', label: 'Города', icon: 'location_city' },
    { key: 'products', label: 'Продукты', icon: 'inventory_2' },
    { key: 'phones', label: 'Телефоны', icon: 'phone_iphone' },
    { key: 'accounts', label: 'Аккаунты', icon: 'manage_accounts' },
    { key: 'promo', label: 'Промо', icon: 'smart_button' },
    { key: 'managerTexts', label: 'Тексты менеджеров', icon: 'article' },
    { key: 'gamification', label: 'Геймификация', icon: 'emoji_events' },
    { key: 'settings', label: 'Настройки', icon: 'tune' },
    { key: 'autoresponder', label: 'Автоответчик', icon: 'mark_chat_unread' },
    { key: 'autoresponderMonitor', label: 'Мониторинг', icon: 'monitor_heart' }
  ];
  private readonly managerTabs: DictionaryTab[] = [
    { key: 'categories', label: 'Категории', icon: 'category' }
  ];

  readonly activeTab = signal<DictionaryTabKey>(this.initialTab());
  readonly search = signal('');
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly importing = signal(false);
  readonly syncingWhatsAppGroups = signal(false);
  readonly syncingSharedChatLinks = signal(false);
  readonly error = signal<string | null>(null);
  readonly importError = signal<string | null>(null);
  readonly importResult = signal<BotImportResponse | null>(null);
  readonly importModalOpen = signal(false);
  readonly importFile = signal<File | null>(null);
  readonly selectedId = signal<number | null>(null);
  readonly activeCategoryId = signal<number | null>(null);
  readonly editingCategoryId = signal<number | null>(null);
  readonly editingSubCategoryId = signal<number | null>(null);
  readonly categoryEditorOpen = signal(false);
  readonly subCategoryEditorOpen = signal(false);

  readonly categories = signal<AdminCategory[]>([]);
  readonly subCategories = signal<AdminSubCategory[]>([]);
  readonly cities = signal<AdminCity[]>([]);
  readonly products = signal<AdminProduct[]>([]);
  readonly phones = signal<OperatorPhone[]>([]);
  readonly phoneOperators = signal<PhoneOperatorOption[]>([]);
  readonly selectedPhone = signal<OperatorPhone | null>(null);
  readonly bots = signal<AdminBot[]>([]);
  readonly promoTexts = signal<AdminPromoText[]>([]);
  readonly managerTexts = signal<AdminManagerText[]>([]);
  readonly promoManagers = signal<DictionaryOption[]>([]);
  readonly promoAssignments = signal<PromoTextAssignment[]>([]);
  readonly promoButtons = signal<PromoButtonSlot[]>([]);
  readonly selectedPromoManagerId = signal<number | null>(null);
  readonly nagulSettings = signal<AdminNagulSettings | null>(null);
  readonly telegramReportSettings = signal<AdminTelegramReportScheduleSettings | null>(null);
  readonly whatsAppGroupSyncSettings = signal<AdminWhatsAppGroupSyncSettings | null>(null);
  readonly clientPublicationProgressReportSettings = signal<AdminClientPublicationProgressReportSettings | null>(null);
  readonly gamificationSettings = signal<AdminGamificationSettings | null>(null);
  readonly gamificationRules = signal<AdminGamificationRule[]>([]);
  readonly gamificationProgress = signal<AdminGamificationProgress | null>(null);
  readonly gamificationProgressDays = signal<GamificationProgressDays>(1);
  readonly gamificationScorePreview = signal<AdminGamificationScorePreview | null>(null);
  readonly gamificationScoreLedger = signal<AdminGamificationScoreLedger | null>(null);
  readonly gamificationLedgerRebuild = signal<AdminGamificationScoreLedgerRebuild | null>(null);
  readonly gamificationBackfill = signal<AdminGamificationBackfill | null>(null);
  readonly gamificationBalances = signal<AdminGamificationBalances | null>(null);
  readonly gamificationEvents = signal<AdminGamificationEvent[]>([]);
  readonly topWorkerGamificationScoreActors = computed(() => this.byRole(this.gamificationScorePreview()?.topActors ?? [], 'WORKER').slice(0, 8));
  readonly topManagerGamificationScoreActors = computed(() => this.byRole(this.gamificationScorePreview()?.topActors ?? [], 'MANAGER').slice(0, 8));
  readonly topOtherGamificationScoreActors = computed(() => this.withoutRoles(this.gamificationScorePreview()?.topActors ?? [], ['WORKER', 'MANAGER']).slice(0, 8));
  readonly workerGamificationScoreActors = computed(() => this.byRole(this.gamificationScorePreview()?.topActors ?? [], 'WORKER'));
  readonly managerGamificationScoreActors = computed(() => this.byRole(this.gamificationScorePreview()?.topActors ?? [], 'MANAGER'));
  readonly otherGamificationScoreActors = computed(() => this.withoutRoles(this.gamificationScorePreview()?.topActors ?? [], ['WORKER', 'MANAGER']));
  readonly workerGamificationLedgerActors = computed(() => this.byRole(this.gamificationScoreLedger()?.topActors ?? [], 'WORKER'));
  readonly managerGamificationLedgerActors = computed(() => this.byRole(this.gamificationScoreLedger()?.topActors ?? [], 'MANAGER'));
  readonly otherGamificationLedgerActors = computed(() => this.withoutRoles(this.gamificationScoreLedger()?.topActors ?? [], ['WORKER', 'MANAGER']));
  readonly workerGamificationBalances = computed(() => this.byRole(this.gamificationBalances()?.balances ?? [], 'WORKER'));
  readonly managerGamificationBalances = computed(() => this.byRole(this.gamificationBalances()?.balances ?? [], 'MANAGER'));
  readonly otherGamificationBalances = computed(() => this.withoutRoles(this.gamificationBalances()?.balances ?? [], ['WORKER', 'MANAGER']));
  readonly recentGamificationEvents = computed(() => this.gamificationEvents().slice(0, 6));
  readonly clientMessageSettings = signal<AdminClientMessageSettings | null>(null);
  readonly clientMessageMonitor = signal<AdminClientMessageMonitor | null>(null);
  readonly clientMessageMaintenancePreview = signal<AdminClientMessageMaintenancePreview | null>(null);
  readonly clientMessageMonitorLoading = signal(false);
  readonly clientMessageMaintenancePreviewLoading = signal(false);
  readonly clientMessageMonitorSaving = signal(false);
  readonly clientMessageMonitorError = signal<string | null>(null);
  readonly clientMessageMaintenancePreviewError = signal<string | null>(null);
  readonly monitorScenarioFilter = signal('ALL');
  readonly monitorQueueStatusFilter = signal('ALL');
  readonly monitorAttemptStatusFilter = signal('ALL');
  readonly monitorSearch = signal('');
  readonly expandedMonitorQueueKey = signal<string | null>(null);
  readonly clientMessageManualAction = signal<string | null>(null);
  readonly maintenanceAction = signal<string | null>(null);
  readonly productCategories = signal<DictionaryOption[]>([]);
  readonly botWorkers = signal<DictionaryOption[]>([]);
  readonly botStatuses = signal<DictionaryOption[]>([]);
  readonly botCities = signal<DictionaryOption[]>([]);
  readonly canManageAllDictionaries = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  });
  readonly canApplyMaintenance = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN']);
  });
  readonly tabs = computed<DictionaryTab[]>(() => this.canManageAllDictionaries() ? this.allTabs : this.managerTabs);

  readonly categoryForm = this.fb.nonNullable.group({
    title: ['', Validators.required]
  });

  readonly subCategoryForm = this.fb.group({
    title: this.fb.nonNullable.control('', Validators.required),
    categoryId: this.fb.control<number | null>(null, Validators.required)
  });

  readonly cityForm = this.fb.nonNullable.group({
    title: ['', Validators.required]
  });

  readonly productForm = this.fb.group({
    title: this.fb.nonNullable.control('', Validators.required),
    price: this.fb.nonNullable.control('0', Validators.required),
    categoryId: this.fb.control<number | null>(null, Validators.required),
    photo: this.fb.nonNullable.control(false)
  });

  readonly phoneForm = this.fb.nonNullable.group({
    number: ['+7', Validators.required],
    fio: [''],
    birthday: [''],
    amountAllowed: [1, Validators.required],
    amountSent: [0, Validators.required],
    blockTime: [3, Validators.required],
    timer: [''],
    googleLogin: [''],
    googlePassword: [''],
    avitoPassword: [''],
    mailLogin: [''],
    mailPassword: [''],
    fotoInstagram: [''],
    active: [true],
    createDate: [''],
    operatorId: this.fb.control<number | null>(null)
  });

  readonly botForm = this.fb.group({
    login: this.fb.nonNullable.control('', Validators.required),
    password: this.fb.nonNullable.control('', Validators.required),
    fio: this.fb.nonNullable.control('', Validators.required),
    workerId: this.fb.control<number | null>(null, Validators.required),
    cityId: this.fb.control<number | null>(null),
    statusId: this.fb.control<number | null>(null, Validators.required),
    counter: this.fb.nonNullable.control('0', Validators.required),
    active: this.fb.nonNullable.control(true)
  });

  readonly promoTextForm = this.fb.nonNullable.group({
    text: ['', Validators.required]
  });

  readonly managerTextForm = this.fb.nonNullable.group({
    payText: [''],
    beginText: [''],
    offerText: [''],
    reminderText: [''],
    startText: ['']
  });

  readonly settingsForm = this.fb.nonNullable.group({
    nagulCooldownMinutes: [60, [Validators.required, Validators.min(0), Validators.max(1440)]],
    nagulLookaheadDays: [60, [Validators.required, Validators.min(0), Validators.max(365)]],
    accountWalkedCounterThreshold: [3, [Validators.required, Validators.min(1), Validators.max(30)]],
    accountWalkDelayDays: [2, [Validators.required, Validators.min(0), Validators.max(30)]],
    morningReportEnabled: [true],
    morningReportTime: ['11:30', [Validators.required, Validators.pattern(/^([01]\d|2[0-3]):[0-5]\d$/)]],
    eveningReportEnabled: [true],
    eveningReportTime: ['22:00', [Validators.required, Validators.pattern(/^([01]\d|2[0-3]):[0-5]\d$/)]],
    telegramReportZone: ['Asia/Irkutsk', Validators.required],
    whatsAppGroupSyncEnabled: [true],
    whatsAppGroupSyncIntervalMinutes: [30, [Validators.required, Validators.min(5), Validators.max(1440)]],
    clientPublicationProgressReportsEnabled: [true]
  });

  readonly gamificationForm = this.fb.nonNullable.group({
    enabled: [false],
    workerEnabled: [true],
    managerEnabled: [true],
    operatorEnabled: [true],
    marketologEnabled: [true],
    showInCabinet: [false],
    showInScore: [false],
    eventsEnabled: [false],
    shadowScoringEnabled: [false],
    reviewPublishedRuleEnabled: [true],
    reviewPublishedRulePoints: [10],
    orderPaidRuleEnabled: [true],
    orderPaidRulePoints: [25],
    badReviewTaskDoneRuleEnabled: [true],
    badReviewTaskDoneRulePoints: [15],
    reviewRecoveryTaskDoneRuleEnabled: [true],
    reviewRecoveryTaskDoneRulePoints: [20]
  });

  readonly autoresponderForm = this.fb.nonNullable.group({
    workerEnabled: [true],
    liveEnabled: [true],
    immediateEnabled: [true],
    monitorEnabled: [false],
    reviewCheckEnabled: [true],
    reviewCheckAutoArchiveEnabled: [true],
    clientTextReminderEnabled: [true],
    paymentReminderEnabled: [true],
    badReviewInvoiceEnabled: [true],
    badReviewAutoBanEnabled: [true],
    reviewRecoveryNoticeEnabled: [true],
    paymentOverdueEnabled: [true],
    paymentOverdueLiveEnabled: [false],
    archiveReorderEnabled: [true],
    errorProtectionEnabled: [true],
    reviewCheckIntervalDays: [2, [Validators.required, Validators.min(1), Validators.max(365)]],
    reviewCheckAutoArchiveDays: [30, [Validators.required, Validators.min(1), Validators.max(3650)]],
    clientTextReminderIntervalDays: [3, [Validators.required, Validators.min(1), Validators.max(365)]],
    paymentReminderIntervalDays: [2, [Validators.required, Validators.min(1), Validators.max(365)]],
    reviewCheckRetryDelayHours: [2, [Validators.required, Validators.min(1), Validators.max(168)]],
    paymentInvoiceRetryDelayHours: [2, [Validators.required, Validators.min(1), Validators.max(168)]],
    badReviewInvoiceRetryDelayHours: [2, [Validators.required, Validators.min(1), Validators.max(168)]],
    badReviewAutoBanDelayDays: [2, [Validators.required, Validators.min(1), Validators.max(365)]],
    reviewRecoveryNoticeRetryDelayHours: [2, [Validators.required, Validators.min(1), Validators.max(168)]],
    paymentOverdueDays: [30, [Validators.required, Validators.min(1), Validators.max(365)]],
    archiveReorderMonths: [3, [Validators.required, Validators.min(1), Validators.max(36)]],
    archiveReorderJitterDays: [10, [Validators.required, Validators.min(0), Validators.max(30)]],
    archiveOrderRetentionDays: [90, [Validators.required, Validators.min(1), Validators.max(3650)]],
    errorProtectionThreshold: [20, [Validators.required, Validators.min(1), Validators.max(10000)]],
    errorProtectionWindowMinutes: [10, [Validators.required, Validators.min(1), Validators.max(1440)]],
    errorProtectionCooldownMinutes: [60, [Validators.required, Validators.min(1), Validators.max(1440)]],
    whatsAppAuthRetryHours: [2, [Validators.required, Validators.min(1), Validators.max(48)]],
    whatsAppAuthAlertCooldownHours: [12, [Validators.required, Validators.min(1), Validators.max(168)]],
    retentionDays: [90, [Validators.required, Validators.min(1), Validators.max(3650)]],
    tickBatchSize: [5, [Validators.required, Validators.min(1), Validators.max(100)]],
    candidateLimit: [200, [Validators.required, Validators.min(1), Validators.max(5000)]],
    dailyLimit: [140, [Validators.required, Validators.min(1), Validators.max(5000)]],
    defaultGapSeconds: [180, [Validators.required, Validators.min(30), Validators.max(86400)]],
    whatsAppGapSeconds: [180, [Validators.required, Validators.min(30), Validators.max(86400)]],
    telegramGapSeconds: [90, [Validators.required, Validators.min(30), Validators.max(86400)]],
    maxGapSeconds: [90, [Validators.required, Validators.min(30), Validators.max(86400)]],
    businessWindows: ['10:00-12:00,14:00-17:00,19:00-21:00', [Validators.required, Validators.maxLength(500)]],
    reviewCheckStatuses: ['На проверке', [Validators.required, Validators.maxLength(500)]],
    clientTextReminderStatuses: ['Новый', [Validators.required, Validators.maxLength(500)]],
    paymentReminderStatuses: ['Выставлен счет,Напоминание', [Validators.required, Validators.maxLength(500)]],
    paymentOverdueStatuses: ['Выставлен счет,Напоминание', [Validators.required, Validators.maxLength(500)]],
    closedOrderStatuses: ['Оплачено,Архив,Бан,Не оплачено', [Validators.required, Validators.maxLength(500)]],
    paymentOverdueTargetStatus: ['Не оплачено', [Validators.required, Validators.maxLength(500)]],
    archiveCompanyStatus: ['На стопе', [Validators.required, Validators.maxLength(500)]],
    archiveInactiveOrderStatuses: ['Оплачено,Архив,Бан', [Validators.required, Validators.maxLength(500)]],
    openNextOrderRequestStatuses: ['PENDING,FAILED', [Validators.required, Validators.maxLength(500)]],
    reviewLinkBaseUrl: ['https://o-ogo.ru', [Validators.required, Validators.maxLength(500)]],
    reviewReminderText: ['', [Validators.required, Validators.maxLength(500)]],
    clientTextReminderText: ['', [Validators.required, Validators.maxLength(500)]],
    publicationStartedText: ['', [Validators.required, Validators.maxLength(500)]],
    publicationProgressReportText: ['', [Validators.required, Validators.maxLength(500)]],
    paymentInstructionSource: ['MANAGER_TEXT' as 'MANAGER_TEXT' | 'TBANK_LINK', [Validators.required]],
    paymentReminderText: ['', [Validators.required, Validators.maxLength(500)]],
    paymentLinkCopyText: ['', [Validators.required, Validators.maxLength(500)]],
    paymentSuccessText: ['', [Validators.required, Validators.maxLength(500)]],
    reviewRecoveryNoticeText: ['', [Validators.required, Validators.maxLength(500)]],
    archiveOfferText: ['', [Validators.required, Validators.maxLength(500)]]
  });

  readonly activeLabel = computed(() => this.tabs().find((tab) => tab.key === this.activeTab())?.label ?? '');
  readonly activeGuide = computed(() => DICTIONARY_GUIDES[this.activeTab()]);
  readonly activeTabIcon = computed(() =>
    this.tabs().find((tab) => tab.key === this.activeTab())?.icon ?? 'help'
  );
  readonly activeCategory = computed(() => {
    const id = this.activeCategoryId();
    return this.categories().find((category) => category.id === id) ?? null;
  });
  readonly selectedCategorySubCategories = computed(() => {
    const categoryId = this.activeCategoryId();
    if (categoryId == null) {
      return [];
    }

    return this.subCategories().filter((subCategory) => subCategory.category?.id === categoryId);
  });
  readonly selectedManagerText = computed(() => {
    const managerId = this.selectedId();
    return managerId == null
      ? null
      : this.managerTexts().find((managerText) => managerText.managerId === managerId) ?? null;
  });
  readonly categoryEditorTitle = computed(() =>
    this.editingCategoryId() == null ? 'Новая категория' : `Категория #${this.editingCategoryId()}`
  );
  readonly subCategoryEditorTitle = computed(() =>
    this.editingSubCategoryId() == null ? 'Новая подкатегория' : `Подкатегория #${this.editingSubCategoryId()}`
  );
  readonly phoneDeviceTokenTotal = computed(() =>
    this.phones().reduce((total, phone) => total + this.deviceTokenCount(phone), 0)
  );
  readonly activeItemsTotal = computed(() => {
    switch (this.activeTab()) {
      case 'categories':
        return this.categories().length;
      case 'subcategories':
        return this.subCategories().length;
      case 'cities':
        return this.cities().length;
      case 'products':
        return this.products().length;
      case 'phones':
        return this.phones().length;
      case 'accounts':
        return this.bots().length;
      case 'promo':
        return this.promoTexts().length;
      case 'managerTexts':
        return this.managerTexts().length;
      case 'gamification':
        return this.gamificationTotal();
      case 'settings':
        return this.settingsTotal();
      case 'autoresponder':
        return this.autoresponderTotal();
      case 'autoresponderMonitor':
        return this.monitorTotal();
    }
  });
  readonly metrics = computed<DictionaryMetric[]>(() => {
    const categoryMetrics: DictionaryMetric[] = [
      { label: 'Категории', value: this.categories().length, icon: 'category', tone: 'blue' },
      { label: 'Подкатегории', value: this.subCategories().length, icon: 'account_tree', tone: 'teal' }
    ];

    if (!this.canManageAllDictionaries()) {
      return categoryMetrics;
    }

    return [
      ...categoryMetrics,
      { label: 'Города', value: this.cities().length, icon: 'location_city', tone: 'green' },
      { label: 'Продукты', value: this.products().length, icon: 'inventory_2', tone: 'yellow' },
      { label: 'Телефоны', value: this.phones().length, icon: 'phone_iphone', tone: 'teal' },
      { label: 'Токены', value: this.phoneDeviceTokenTotal(), icon: 'devices', tone: 'yellow' },
      { label: 'Аккаунты', value: this.bots().length, icon: 'manage_accounts', tone: 'pink' },
      { label: 'Промо', value: this.promoTexts().length, icon: 'smart_button', tone: 'blue' },
      { label: 'Тексты менеджеров', value: this.managerTexts().length, icon: 'article', tone: 'green' },
      { label: 'Пауза выгула', value: this.nagulSettings()?.cooldownMinutes ?? 0, icon: 'timer', tone: 'teal' },
      { label: 'Дней в выдаче', value: this.nagulSettings()?.lookaheadDays ?? 60, icon: 'event_upcoming', tone: 'blue' },
      { label: 'Порог аккаунта', value: this.nagulSettings()?.accountWalkedCounterThreshold ?? 3, icon: 'verified_user', tone: 'green' },
      { label: 'Сдвиг дат', value: this.nagulSettings()?.accountWalkDelayDays ?? 2, icon: 'date_range', tone: 'yellow' },
      { label: 'Геймификация', value: this.gamificationSettings()?.enabled ? 1 : 0, icon: 'emoji_events', tone: this.gamificationSettings()?.enabled ? 'green' : 'pink' },
      { label: 'Telegram', value: this.telegramReportSettings()?.morningEnabled || this.telegramReportSettings()?.eveningEnabled ? 1 : 0, icon: 'send', tone: 'green' },
      { label: 'WhatsApp sync', value: this.whatsAppGroupSyncSettings()?.enabled ? 1 : 0, icon: 'sync', tone: 'teal' },
      { label: 'Отчеты клиентам', value: this.clientPublicationProgressReportSettings()?.enabled ? 1 : 0, icon: 'reviews', tone: 'blue' },
      { label: 'Автоответчик', value: this.clientMessageSettings()?.workerEnabled ? 1 : 0, icon: 'mark_chat_unread', tone: 'green' },
      { label: 'Лимит сообщений', value: this.clientMessageSettings()?.dailyLimit ?? 0, icon: 'speed', tone: 'yellow' }
    ];
  });

  readonly monitorMetrics = computed<DictionaryMetric[]>(() => {
    const monitor = this.clientMessageMonitor();
    return [
      { label: 'Активных', value: monitor?.activeCandidates ?? 0, icon: 'playlist_add_check', tone: 'blue' },
      { label: 'Пора проверить', value: monitor?.dueNow ?? 0, icon: 'schedule', tone: 'blue' },
      { label: 'Готово к отправке', value: monitor?.readyToSendNow ?? 0, icon: 'bolt', tone: 'green' },
      { label: 'Ждет окно', value: monitor?.waitingForWindow ?? 0, icon: 'access_time', tone: 'yellow' },
      { label: 'Нет chatId', value: monitor?.missingChannelBindings ?? 0, icon: 'link_off', tone: monitor?.missingChannelBindings ? 'pink' : 'teal' },
      { label: 'Отправлено сегодня', value: monitor?.sentToday ?? 0, icon: 'send', tone: 'green' },
      { label: 'Ошибок сегодня', value: monitor?.failedToday ?? 0, icon: 'priority_high', tone: monitor?.failedToday ? 'pink' : 'teal' },
      { label: 'Пропущено', value: monitor?.skippedToday ?? 0, icon: 'pause_circle', tone: 'teal' },
      { label: 'Отключено задач', value: monitor?.disabledStates ?? 0, icon: 'block', tone: monitor?.disabledStates ? 'pink' : 'blue' }
    ];
  });

  readonly filteredMonitorQueue = computed(() => {
    const monitor = this.clientMessageMonitor();
    if (!monitor) {
      return [];
    }
    const scenarioFilter = this.monitorScenarioFilter();
    const statusFilter = this.monitorQueueStatusFilter();
    const search = this.monitorSearch().trim().toLowerCase();
    const nowMs = Date.parse(monitor.updatedAt);
    return monitor.queue.filter((item) => {
      if (scenarioFilter !== 'ALL' && item.scenario !== scenarioFilter) {
        return false;
      }
      if (statusFilter === 'DUE' && !this.isMonitorQueueDue(item, nowMs)) {
        return false;
      }
      if (statusFilter === 'ERROR' && !item.lastErrorMessage) {
        return false;
      }
      return this.matchesMonitorQueueSearch(item, search);
    });
  });

  readonly filteredMonitorAttempts = computed(() => {
    const monitor = this.clientMessageMonitor();
    if (!monitor) {
      return [];
    }
    const scenarioFilter = this.monitorScenarioFilter();
    const statusFilter = this.monitorAttemptStatusFilter();
    const search = this.monitorSearch().trim().toLowerCase();
    return monitor.attempts.filter((attempt) => {
      if (scenarioFilter !== 'ALL' && attempt.scenario !== scenarioFilter) {
        return false;
      }
      if (statusFilter !== 'ALL' && attempt.status !== statusFilter) {
        return false;
      }
      return this.matchesMonitorAttemptSearch(attempt, search);
    });
  });

  constructor() {
    if (!this.tabs().some((item) => item.key === this.activeTab())) {
      this.activeTab.set('categories');
    }

    this.loadAll();
  }

  ngOnDestroy(): void {
    this.stopClientMessageMonitorPolling();
  }

  @HostListener('document:visibilitychange')
  onDocumentVisibilityChange(): void {
    this.syncClientMessageMonitorPolling();
  }

  setTab(tab: DictionaryTabKey): void {
    if (!this.tabs().some((item) => item.key === tab)) {
      return;
    }

    this.activeTab.set(tab);
    this.search.set('');
    this.clearSelection();
    this.syncClientMessageMonitorPolling();
  }

  loadAll(): void {
    this.loading.set(true);
    this.error.set(null);

    if (this.canManageAllDictionaries()) {
      forkJoin({
        categories: this.dictionariesApi.getCategories(),
        subCategories: this.dictionariesApi.getSubCategories(),
        cities: this.dictionariesApi.getCities(),
        products: this.dictionariesApi.getProducts(),
        phones: this.phonesApi.getPhones(),
        bots: this.dictionariesApi.getBots(),
        promoTexts: this.dictionariesApi.getPromoTextManagement(),
        managerTexts: this.dictionariesApi.getManagerTexts(),
        nagulSettings: this.dictionariesApi.getNagulSettings(),
        telegramReportSettings: this.dictionariesApi.getTelegramReportSettings(),
        whatsAppGroupSyncSettings: this.dictionariesApi.getWhatsAppGroupSyncSettings(),
        clientPublicationProgressReportSettings: this.dictionariesApi.getClientPublicationProgressReportSettings(),
        gamificationSettings: this.dictionariesApi.getGamificationSettings(),
        gamificationRules: this.dictionariesApi.getGamificationRules(),
        gamificationProgress: this.dictionariesApi.getGamificationProgress(this.gamificationProgressDays()),
        gamificationScorePreview: this.dictionariesApi.getGamificationScorePreview(this.gamificationProgressDays()),
        gamificationScoreLedger: this.dictionariesApi.getGamificationScoreLedger(this.gamificationProgressDays()),
        gamificationBalances: this.dictionariesApi.getGamificationBalances(this.gamificationProgressDays()),
        gamificationEvents: this.dictionariesApi.getGamificationEvents(),
        clientMessageSettings: this.dictionariesApi.getClientMessageSettings()
      }).subscribe({
        next: ({
          categories,
          subCategories,
          cities,
          products,
          phones,
          bots,
          promoTexts,
          managerTexts,
          nagulSettings,
          telegramReportSettings,
          whatsAppGroupSyncSettings,
          clientPublicationProgressReportSettings,
          gamificationSettings,
          gamificationRules,
          gamificationProgress,
          gamificationScorePreview,
          gamificationScoreLedger,
          gamificationBalances,
          gamificationEvents,
          clientMessageSettings
        }) => {
          this.categories.set(categories);
          this.subCategories.set(subCategories);
          this.cities.set(cities);
          this.products.set(products.products);
          this.productCategories.set(products.categories);
          this.applyPhonesResponse(phones);
          this.applyBotsResponse(bots);
          this.applyPromoManagement(promoTexts);
          this.managerTexts.set(managerTexts);
          this.applyNagulSettings(nagulSettings);
          this.applyTelegramReportSettings(telegramReportSettings);
          this.applyWhatsAppGroupSyncSettings(whatsAppGroupSyncSettings);
          this.applyClientPublicationProgressReportSettings(clientPublicationProgressReportSettings);
          this.applyGamificationSettings(gamificationSettings);
          this.applyGamificationRules(gamificationRules);
          this.gamificationProgress.set(gamificationProgress);
          this.gamificationScorePreview.set(gamificationScorePreview);
          this.gamificationScoreLedger.set(gamificationScoreLedger);
          this.gamificationBalances.set(gamificationBalances);
          this.gamificationEvents.set(gamificationEvents);
          this.applyClientMessageSettings(clientMessageSettings);
          this.loading.set(false);
          this.ensureDefaults();
        },
        error: (err) => this.handleLoadAllError(err)
      });
      return;
    }

    forkJoin({
        categories: this.dictionariesApi.getCategories(),
        subCategories: this.dictionariesApi.getSubCategories()
    }).subscribe({
      next: ({ categories, subCategories }) => {
        this.categories.set(categories);
        this.subCategories.set(subCategories);
        this.cities.set([]);
        this.products.set([]);
        this.productCategories.set([]);
        this.phones.set([]);
        this.phoneOperators.set([]);
        this.selectedPhone.set(null);
        this.bots.set([]);
        this.promoTexts.set([]);
        this.managerTexts.set([]);
        this.promoManagers.set([]);
        this.promoAssignments.set([]);
        this.promoButtons.set([]);
        this.selectedPromoManagerId.set(null);
        this.nagulSettings.set(null);
        this.telegramReportSettings.set(null);
        this.whatsAppGroupSyncSettings.set(null);
        this.clientPublicationProgressReportSettings.set(null);
        this.gamificationSettings.set(null);
        this.gamificationRules.set([]);
        this.gamificationProgress.set(null);
        this.gamificationScorePreview.set(null);
        this.gamificationScoreLedger.set(null);
        this.gamificationBalances.set(null);
        this.gamificationEvents.set([]);
        this.clientMessageSettings.set(null);
        this.loading.set(false);
        this.ensureDefaults();
      },
      error: (err) => this.handleLoadAllError(err)
    });
  }

  searchActive(): void {
    this.loadActive();
  }

  clearSearch(): void {
    this.search.set('');
    this.loadActive();
  }

  openActiveCreate(): void {
    if (this.activeTab() === 'categories') {
      this.openNewCategory();
      return;
    }

    if (this.activeTab() === 'subcategories') {
      this.openNewSubCategory();
      return;
    }

    if (this.activeTab() === 'phones') {
      this.startNewPhone();
      return;
    }

    if (this.activeTab() === 'settings') {
      this.resetSettingsForm();
      return;
    }

    if (this.activeTab() === 'gamification') {
      this.resetGamificationForm();
      return;
    }

    if (this.activeTab() === 'autoresponder') {
      this.resetAutoresponderForm();
      return;
    }

    this.clearSelection();
  }

  selectCategory(category: AdminCategory): void {
    this.activeCategoryId.set(category.id);
    this.startNewSubCategory();
    this.error.set(null);
  }

  openNewCategory(): void {
    this.startNewCategory();
    this.categoryEditorOpen.set(true);
  }

  editCategory(event: Event, category: AdminCategory): void {
    event.preventDefault();
    event.stopPropagation();
    this.activeCategoryId.set(category.id);
    this.editingCategoryId.set(category.id);
    this.categoryForm.setValue({ title: category.title });
    this.categoryEditorOpen.set(true);
    this.error.set(null);
  }

  closeCategoryEditor(): void {
    if (this.saving() || this.deleting()) {
      return;
    }

    this.categoryEditorOpen.set(false);
    this.startNewCategory();
  }

  selectSubCategory(subCategory: AdminSubCategory): void {
    const categoryId = subCategory.category?.id ?? this.activeCategoryId() ?? this.defaultCategoryId();
    this.editingSubCategoryId.set(subCategory.id);
    this.activeCategoryId.set(categoryId);
    this.subCategoryForm.setValue({
      title: subCategory.title,
      categoryId
    });
    this.error.set(null);
  }

  openNewSubCategory(): void {
    if (this.activeCategoryId() == null) {
      this.activeCategoryId.set(this.defaultCategoryId());
    }

    this.startNewSubCategory();
    this.subCategoryEditorOpen.set(true);
  }

  editSubCategory(event: Event, subCategory: AdminSubCategory): void {
    event.preventDefault();
    event.stopPropagation();
    this.selectSubCategory(subCategory);
    this.subCategoryEditorOpen.set(true);
  }

  closeSubCategoryEditor(): void {
    if (this.saving() || this.deleting()) {
      return;
    }

    this.subCategoryEditorOpen.set(false);
    this.startNewSubCategory();
  }

  selectCity(city: AdminCity): void {
    this.selectedId.set(city.id);
    this.cityForm.setValue({ title: city.title });
    this.error.set(null);
  }

  selectProduct(product: AdminProduct): void {
    this.selectedId.set(product.id);
    this.productForm.setValue({
      title: product.title,
      price: String(product.price ?? 0),
      categoryId: product.category?.id ?? this.defaultProductCategoryId(),
      photo: product.photo
    });
    this.error.set(null);
  }

  selectPhone(phone: OperatorPhone): void {
    this.selectedId.set(phone.id);
    this.selectedPhone.set(phone);
    this.error.set(null);
    this.phoneForm.reset({
      number: phone.number ?? '+7',
      fio: phone.fio ?? '',
      birthday: this.toDateInput(phone.birthday),
      amountAllowed: phone.amountAllowed,
      amountSent: phone.amountSent,
      blockTime: phone.blockTime,
      timer: this.toDateTimeInput(phone.timer),
      googleLogin: phone.googleLogin ?? '',
      googlePassword: phone.googlePassword ?? '',
      avitoPassword: phone.avitoPassword ?? '',
      mailLogin: phone.mailLogin ?? '',
      mailPassword: phone.mailPassword ?? '',
      fotoInstagram: phone.fotoInstagram ?? '',
      active: phone.active,
      createDate: this.toDateInput(phone.createDate),
      operatorId: phone.operator?.id ?? null
    });
  }

  startNewPhone(): void {
    this.selectedId.set(null);
    this.selectedPhone.set(null);
    this.error.set(null);
    this.phoneForm.reset({
      number: '+7',
      fio: '',
      birthday: '',
      amountAllowed: 1,
      amountSent: 0,
      blockTime: 3,
      timer: this.toDateTimeInput(new Date().toISOString()),
      googleLogin: '',
      googlePassword: '',
      avitoPassword: '',
      mailLogin: '',
      mailPassword: '',
      fotoInstagram: '',
      active: true,
      createDate: this.toDateInput(new Date().toISOString()),
      operatorId: null
    });
  }

  selectBot(bot: AdminBot): void {
    this.selectedId.set(bot.id);
    this.botForm.setValue({
      login: bot.login,
      password: bot.password,
      fio: bot.fio,
      workerId: bot.worker?.id ?? this.defaultBotWorkerId(),
      cityId: bot.city?.id ?? this.defaultBotCityId(),
      statusId: bot.status?.id ?? this.defaultBotStatusId(),
      counter: String(bot.counter ?? 0),
      active: bot.active
    });
    this.error.set(null);
  }

  selectPromoText(promoText: AdminPromoText): void {
    this.selectedId.set(promoText.id);
    this.promoTextForm.setValue({ text: promoText.text });
    this.error.set(null);
  }

  selectManagerText(managerText: AdminManagerText): void {
    this.selectedId.set(managerText.managerId);
    this.managerTextForm.setValue({
      payText: managerText.payText,
      beginText: managerText.beginText,
      offerText: managerText.offerText,
      reminderText: managerText.reminderText,
      startText: managerText.startText
    });
    this.error.set(null);
  }

  clearSelection(): void {
    this.selectedId.set(null);
    this.editingCategoryId.set(null);
    this.editingSubCategoryId.set(null);
    this.categoryEditorOpen.set(false);
    this.subCategoryEditorOpen.set(false);
    this.error.set(null);

    this.categoryForm.reset({ title: '' });
    this.subCategoryForm.reset({ title: '', categoryId: this.activeCategoryId() ?? this.defaultCategoryId() });
    this.cityForm.reset({ title: '' });
    this.productForm.reset({
      title: '',
      price: '0',
      categoryId: this.defaultProductCategoryId(),
      photo: false
    });
    this.selectedPhone.set(null);
    this.phoneForm.reset({
      number: '+7',
      fio: '',
      birthday: '',
      amountAllowed: 1,
      amountSent: 0,
      blockTime: 3,
      timer: '',
      googleLogin: '',
      googlePassword: '',
      avitoPassword: '',
      mailLogin: '',
      mailPassword: '',
      fotoInstagram: '',
      active: true,
      createDate: '',
      operatorId: null
    });
    this.botForm.reset({
      login: '',
      password: '',
      fio: '',
      workerId: this.defaultBotWorkerId(),
      cityId: this.defaultBotCityId(),
      statusId: this.defaultBotStatusId(),
      counter: '0',
      active: true
    });
    this.promoTextForm.reset({ text: '' });
    this.managerTextForm.reset({
      payText: '',
      beginText: '',
      offerText: '',
      reminderText: '',
      startText: ''
    });
    this.settingsForm.reset({
      nagulCooldownMinutes: this.nagulSettings()?.cooldownMinutes ?? 60,
      nagulLookaheadDays: this.nagulSettings()?.lookaheadDays ?? 60,
      accountWalkedCounterThreshold: this.nagulSettings()?.accountWalkedCounterThreshold ?? 3,
      accountWalkDelayDays: this.nagulSettings()?.accountWalkDelayDays ?? 2,
      morningReportEnabled: this.telegramReportSettings()?.morningEnabled ?? true,
      morningReportTime: this.telegramReportSettings()?.morningTime ?? '11:30',
      eveningReportEnabled: this.telegramReportSettings()?.eveningEnabled ?? true,
      eveningReportTime: this.telegramReportSettings()?.eveningTime ?? '22:00',
      telegramReportZone: this.telegramReportSettings()?.zone ?? 'Asia/Irkutsk',
      whatsAppGroupSyncEnabled: this.whatsAppGroupSyncSettings()?.enabled ?? true,
      whatsAppGroupSyncIntervalMinutes: this.whatsAppGroupSyncSettings()?.intervalMinutes ?? 30,
      clientPublicationProgressReportsEnabled: this.clientPublicationProgressReportSettings()?.enabled ?? true
    });
    this.resetAutoresponderForm();
  }

  startNewCategory(): void {
    this.editingCategoryId.set(null);
    this.categoryForm.reset({ title: '' });
    this.error.set(null);
  }

  startNewSubCategory(): void {
    this.editingSubCategoryId.set(null);
    this.subCategoryForm.reset({
      title: '',
      categoryId: this.activeCategoryId() ?? this.defaultCategoryId()
    });
    this.error.set(null);
  }

  saveSelectedSubCategory(): void {
    this.saveSubCategory();
  }

  deleteEditingCategory(): void {
    const selectedId = this.editingCategoryId();
    if (selectedId == null || this.deleting()) {
      return;
    }

    const confirmed = window.confirm('Удалить категорию?');
    if (!confirmed) {
      return;
    }

    this.deleting.set(true);
    this.error.set(null);

    this.dictionariesApi.deleteCategory(selectedId).subscribe({
      next: () => {
        this.deleting.set(false);
        this.categoryEditorOpen.set(false);
        this.editingCategoryId.set(null);
        this.categoryForm.reset({ title: '' });

        if (this.activeCategoryId() === selectedId) {
          this.activeCategoryId.set(null);
          this.startNewSubCategory();
        }

        this.toastService.success('Категория удалена');
        this.reloadAfterMutation();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить категорию');
        this.error.set(message);
        this.deleting.set(false);
        this.toastService.error('Категория не удалена', message);
      }
    });
  }

  deleteEditingSubCategory(): void {
    const selectedId = this.editingSubCategoryId();
    if (selectedId == null || this.deleting()) {
      return;
    }

    const confirmed = window.confirm('Удалить подкатегорию?');
    if (!confirmed) {
      return;
    }

    this.deleting.set(true);
    this.error.set(null);

    this.dictionariesApi.deleteSubCategory(selectedId).subscribe({
      next: () => {
        this.deleting.set(false);
        this.subCategoryEditorOpen.set(false);
        this.startNewSubCategory();
        this.toastService.success('Подкатегория удалена');
        this.reloadAfterMutation();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить подкатегорию');
        this.error.set(message);
        this.deleting.set(false);
        this.toastService.error('Подкатегория не удалена', message);
      }
    });
  }

  saveActive(): void {
    switch (this.activeTab()) {
      case 'categories':
        this.saveCategory();
        return;
      case 'subcategories':
        this.saveSubCategory();
        return;
      case 'cities':
        this.saveCity();
        return;
      case 'products':
        this.saveProduct();
        return;
      case 'phones':
        this.savePhone();
        return;
      case 'accounts':
        this.saveBot();
        return;
      case 'promo':
        this.savePromoText();
        return;
      case 'managerTexts':
        this.saveManagerText();
        return;
      case 'gamification':
        this.saveGamificationSettings();
        return;
      case 'settings':
        this.saveSettings();
        return;
      case 'autoresponder':
        this.saveAutoresponderSettings();
        return;
      case 'autoresponderMonitor':
        this.loadClientMessageMonitor();
        return;
    }
  }

  runWhatsAppGroupSync(): void {
    if (this.syncingWhatsAppGroups() || this.saving()) {
      return;
    }

    this.syncingWhatsAppGroups.set(true);
    this.error.set(null);

    this.dictionariesApi.runWhatsAppGroupSync().subscribe({
      next: (settings) => {
        this.syncingWhatsAppGroups.set(false);
        this.applyWhatsAppGroupSyncSettings(settings);
        this.toastService.success(
          'WhatsApp-группы проверены',
          `Новых привязок: ${settings.lastLinkedCount}`
        );
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось запустить синхронизацию WhatsApp-групп');
        this.error.set(message);
        this.syncingWhatsAppGroups.set(false);
        this.toastService.error('WhatsApp не синхронизирован', message);
      }
    });
  }

  runSharedChatLinkSync(): void {
    if (this.syncingSharedChatLinks() || this.saving()) {
      return;
    }

    this.syncingSharedChatLinks.set(true);
    this.error.set(null);

    this.dictionariesApi.runSharedChatLinkSync().subscribe({
      next: (response) => {
        this.syncingSharedChatLinks.set(false);
        this.toastService.success(
          'Общие чаты синхронизированы',
          this.sharedChatSyncSummary(response)
        );
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось синхронизировать общие чаты');
        this.error.set(message);
        this.syncingSharedChatLinks.set(false);
        this.toastService.error('Общие чаты не синхронизированы', message);
      }
    });
  }

  setClientMessageMonitorEnabled(enabled: boolean): void {
    if (this.clientMessageMonitorSaving()) {
      return;
    }

    const previous = this.clientMessageSettings()?.monitorEnabled ?? false;
    this.clientMessageMonitorSaving.set(true);
    this.clientMessageMonitorError.set(null);
    this.dictionariesApi.updateClientMessageMonitorSettings(enabled).subscribe({
      next: (settings) => {
        this.clientMessageMonitorSaving.set(false);
        this.patchClientMessageMonitorEnabled(settings.enabled);
        if (settings.enabled) {
          this.loadClientMessageMonitor();
          this.startClientMessageMonitorPolling();
        } else {
          this.stopClientMessageMonitorPolling();
          this.clientMessageMonitor.set(null);
        }
        this.toastService.success(
          settings.enabled ? 'Мониторинг включен' : 'Мониторинг выключен',
          settings.enabled ? 'Данные будут обновляться раз в минуту, пока вкладка открыта.' : 'Автоответчик продолжит работать без UI-опроса.'
        );
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось переключить мониторинг автоответчика');
        this.patchClientMessageMonitorEnabled(previous);
        this.clientMessageMonitorSaving.set(false);
        this.clientMessageMonitorError.set(message);
        this.toastService.error('Мониторинг не переключен', message);
      }
    });
  }

  loadClientMessageMonitor(silent = false): void {
    if (!this.clientMessageSettings()?.monitorEnabled) {
      this.clientMessageMonitor.set(null);
      this.stopClientMessageMonitorPolling();
      return;
    }
    if (!silent) {
      this.clientMessageMonitorLoading.set(true);
    }
    this.clientMessageMonitorError.set(null);
    this.dictionariesApi.getClientMessageMonitor().subscribe({
      next: (monitor) => {
        this.clientMessageMonitor.set(monitor);
        this.patchClientMessageMonitorEnabled(monitor.enabled);
        this.clientMessageMonitorLoading.set(false);
        this.loadClientMessageMaintenancePreview(true);
        if (monitor.enabled && this.activeTab() === 'autoresponderMonitor') {
          this.startClientMessageMonitorPolling();
        }
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось загрузить мониторинг автоответчика');
        this.clientMessageMonitorError.set(message);
        this.clientMessageMonitorLoading.set(false);
        if (!silent) {
          this.toastService.error('Мониторинг не загрузился', message);
        }
      }
    });
  }

  loadClientMessageMaintenancePreview(silent = false): void {
    if (!silent) {
      this.clientMessageMaintenancePreviewLoading.set(true);
    }
    this.clientMessageMaintenancePreviewError.set(null);
    this.dictionariesApi.getClientMessageMaintenancePreview().subscribe({
      next: (preview) => {
        this.clientMessageMaintenancePreview.set(preview);
        this.clientMessageMaintenancePreviewLoading.set(false);
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось загрузить dry-run актуализации');
        this.clientMessageMaintenancePreviewError.set(message);
        this.clientMessageMaintenancePreviewLoading.set(false);
        if (!silent) {
          this.toastService.error('Dry-run не загрузился', message);
        }
      }
    });
  }

  applyClientMessageMaintenance(
    action: 'company-statuses' | 'payment-overdue' | 'missing-bad-tasks' | 'archive-offers' | 'publication-dates' | 'publication-completed',
    label: string
  ): void {
    if (this.maintenanceAction()) {
      return;
    }
    const confirmed = window.confirm(`Применить: ${label}? Перед применением лучше проверить текущий dry-run.`);
    if (!confirmed) {
      return;
    }

    this.maintenanceAction.set(action);
    this.clientMessageMaintenancePreviewError.set(null);
    this.dictionariesApi.applyClientMessageMaintenance(action).subscribe({
      next: (response) => {
        this.maintenanceAction.set(null);
        this.clientMessageMaintenancePreview.set(response.preview);
        this.toastService.success('Актуализация выполнена', response.message);
        this.loadClientMessageMonitor(true);
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось выполнить актуализацию');
        this.maintenanceAction.set(null);
        this.clientMessageMaintenancePreviewError.set(message);
        this.toastService.error('Актуализация не выполнена', message);
      }
    });
  }

  setMonitorScenarioFilter(value: string): void {
    this.monitorScenarioFilter.set(value);
    this.expandedMonitorQueueKey.set(null);
  }

  setMonitorQueueStatusFilter(value: string): void {
    this.monitorQueueStatusFilter.set(value);
    this.expandedMonitorQueueKey.set(null);
  }

  setMonitorAttemptStatusFilter(value: string): void {
    this.monitorAttemptStatusFilter.set(value);
  }

  setMonitorSearch(value: string): void {
    this.monitorSearch.set(value);
    this.expandedMonitorQueueKey.set(null);
  }

  toggleMonitorQueueDetails(item: AdminClientMessageMonitorQueueItem): void {
    this.expandedMonitorQueueKey.set(this.expandedMonitorQueueKey() === item.targetKey ? null : item.targetKey);
  }

  retryClientMessageCandidate(item: AdminClientMessageMonitorQueueItem): void {
    this.runClientMessageManualAction(
      item,
      'retry',
      () => this.dictionariesApi.retryClientMessageNow(item.id),
      'Кандидат поставлен на ближайшую попытку'
    );
  }

  disableClientMessageCandidate(item: AdminClientMessageMonitorQueueItem): void {
    if (!window.confirm(`Отключить кандидата "${item.orderTitle || item.companyTitle}"?`)) {
      return;
    }
    this.runClientMessageManualAction(
      item,
      'disable',
      () => this.dictionariesApi.disableClientMessageCandidate(item.id),
      'Кандидат отключен'
    );
  }

  markClientMessageCandidateDone(item: AdminClientMessageMonitorQueueItem): void {
    if (!window.confirm(`Пометить кандидата "${item.orderTitle || item.companyTitle}" выполненным?`)) {
      return;
    }
    this.runClientMessageManualAction(
      item,
      'done',
      () => this.dictionariesApi.markClientMessageCandidateDone(item.id),
      'Кандидат помечен выполненным'
    );
  }

  monitorManualActionKey(item: AdminClientMessageMonitorQueueItem, action: string): string {
    return `${item.id}:${action}`;
  }

  private runClientMessageManualAction(
    item: AdminClientMessageMonitorQueueItem,
    action: string,
    requestFactory: () => Observable<AdminClientMessageMonitor>,
    successTitle: string
  ): void {
    const key = this.monitorManualActionKey(item, action);
    if (this.clientMessageManualAction()) {
      return;
    }
    this.clientMessageManualAction.set(key);
    this.clientMessageMonitorError.set(null);
    requestFactory().subscribe({
      next: (monitor) => {
        this.clientMessageManualAction.set(null);
        this.clientMessageMonitor.set(monitor);
        this.patchClientMessageMonitorEnabled(monitor.enabled);
        this.expandedMonitorQueueKey.set(null);
        this.toastService.success(successTitle, item.scenarioLabel);
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось выполнить действие с кандидатом');
        this.clientMessageManualAction.set(null);
        this.clientMessageMonitorError.set(message);
        this.toastService.error('Действие не выполнено', message);
      }
    });
  }

  private isMonitorQueueDue(item: AdminClientMessageMonitorQueueItem, nowMs: number): boolean {
    if (!item.nextAttemptAt || Number.isNaN(nowMs)) {
      return false;
    }
    const nextMs = Date.parse(item.nextAttemptAt);
    return !Number.isNaN(nextMs) && nextMs <= nowMs;
  }

  monitorQueueTimingLabel(item: AdminClientMessageMonitorQueueItem): string | null {
    const monitor = this.clientMessageMonitor();
    if (!monitor || !item.nextAttemptAt) {
      return null;
    }
    const nowMs = Date.parse(monitor.nowIrkutsk || monitor.updatedAt);
    const nextMs = Date.parse(item.nextAttemptAt);
    if (Number.isNaN(nowMs) || Number.isNaN(nextMs)) {
      return null;
    }
    if (nextMs > nowMs) {
      return 'по расписанию';
    }

    switch (item.readiness) {
      case 'WAITING_WINDOW':
        return 'просрочено, ждет рабочее окно';
      case 'MISSING_CHANNEL':
        return 'просрочено, нужна привязка';
      case 'READY_TO_SEND':
        return 'просрочено, готово к отправке';
      case 'READY_TO_RUN':
        return 'просрочено, готово к действию';
      case 'PAUSED':
        return 'просрочено, пауза';
      case 'WORKER_DISABLED':
        return 'просрочено, worker выключен';
      case 'DRY_RUN':
        return 'просрочено, dry-run';
      case 'LOCKED':
        return 'в обработке';
      default:
        return 'просрочено';
    }
  }

  monitorQueueTimingClass(item: AdminClientMessageMonitorQueueItem): string {
    const label = this.monitorQueueTimingLabel(item);
    if (!label || label === 'по расписанию') {
      return 'status-pill off';
    }
    const code = item.readiness ?? '';
    if (code === 'MISSING_CHANNEL' || code === 'DRY_RUN' || code === 'WORKER_DISABLED') {
      return 'status-pill danger';
    }
    if (code === 'READY_TO_SEND' || code === 'READY_TO_RUN') {
      return 'status-pill';
    }
    return 'status-pill warning';
  }

  private matchesMonitorQueueSearch(item: AdminClientMessageMonitorQueueItem, search: string): boolean {
    if (!search) {
      return true;
    }
    return [
      item.scenarioLabel,
      item.companyTitle,
      item.orderTitle,
      item.statusTitle,
      item.lastErrorMessage,
      item.readinessLabel,
      item.readinessReason,
      item.expectedChannel,
      item.channelDetails,
      item.messagePreview,
      item.orderId?.toString(),
      item.companyId?.toString()
    ].some((value) => String(value ?? '').toLowerCase().includes(search));
  }

  private matchesMonitorAttemptSearch(attempt: AdminClientMessageMonitorAttempt, search: string): boolean {
    if (!search) {
      return true;
    }
    return [
      attempt.scenarioLabel,
      attempt.companyTitle,
      attempt.orderTitle,
      attempt.statusLabel,
      attempt.errorCode,
      attempt.errorMessage,
      attempt.messagePreview,
      attempt.channel,
      attempt.orderId?.toString(),
      attempt.companyId?.toString()
    ].some((value) => String(value ?? '').toLowerCase().includes(search));
  }

  monitorAttemptStatusClass(status: string): string {
    if (status === 'SENT') {
      return 'status-pill';
    }
    if (status === 'FAILED') {
      return 'status-pill danger';
    }
    return 'status-pill off';
  }

  monitorScenarioIcon(scenario: AdminClientMessageMonitorScenario): string {
    return {
      CLIENT_TEXT_REMINDER: 'edit_note',
      REVIEW_CHECK_REMINDER: 'playlist_add_check',
      PAYMENT_REMINDER: 'receipt_long',
      PAYMENT_OVERDUE_ESCALATION: 'priority_high',
      ARCHIVE_REORDER_OFFER: 'archive',
      BAD_REVIEW_INVOICE: 'request_quote',
      BAD_REVIEW_AUTO_BAN: 'gavel',
      REVIEW_RECOVERY_NOTICE: 'restore_page'
    }[scenario.scenario] ?? 'mark_chat_unread';
  }

  monitorScenarioTone(scenario: AdminClientMessageMonitorScenario): DictionaryMetric['tone'] {
    if (scenario.failedToday > 0 || scenario.lastError) {
      return 'pink';
    }
    if ((scenario.missingChannelBindings ?? 0) > 0) {
      return 'pink';
    }
    if ((scenario.readyToSendNow ?? 0) > 0) {
      return 'green';
    }
    if ((scenario.waitingForWindow ?? 0) > 0 || scenario.dueNow > 0) {
      return 'yellow';
    }
    if (scenario.activeCandidates > 0) {
      return 'green';
    }
    return 'teal';
  }

  monitorScenarioDueValue(scenario: AdminClientMessageMonitorScenario): number {
    if ((scenario.readyToSendNow ?? 0) > 0) {
      return scenario.readyToSendNow;
    }
    if ((scenario.waitingForWindow ?? 0) > 0) {
      return scenario.waitingForWindow;
    }
    return scenario.dueNow;
  }

  monitorScenarioDueLabel(scenario: AdminClientMessageMonitorScenario): string {
    if ((scenario.readyToSendNow ?? 0) > 0) {
      return 'готово к отправке';
    }
    if ((scenario.waitingForWindow ?? 0) > 0) {
      return 'ждет окно';
    }
    return 'пора проверить';
  }

  monitorReadinessClass(item: AdminClientMessageMonitorQueueItem): string {
    const code = item.readiness ?? '';
    if (code === 'READY_TO_SEND' || code === 'READY_TO_RUN') {
      return 'status-pill';
    }
    if (code === 'MISSING_CHANNEL' || code === 'DRY_RUN' || code === 'WORKER_DISABLED') {
      return 'status-pill danger';
    }
    if (code === 'WAITING_WINDOW' || code === 'SCHEDULED' || code === 'PAUSED' || code === 'LOCKED') {
      return 'status-pill warning';
    }
    return 'status-pill off';
  }

  monitorScenarioTooltip(scenario: AdminClientMessageMonitorScenario): string {
    return {
      CLIENT_TEXT_REMINDER: 'Напоминает клиенту прислать текст или пожелания, когда заказ в режиме "ждем текст от клиента".',
      REVIEW_CHECK_REMINDER: 'Напоминает клиенту проверить шаблоны отзывов и перейти по ссылке проверки.',
      PAYMENT_REMINDER: 'Напоминает клиенту об оплате заказа в статусах счета и напоминания.',
      PAYMENT_OVERDUE_ESCALATION: 'Следит за долгой просрочкой оплаты и готовит перевод в целевой статус по настройкам.',
      ARCHIVE_REORDER_OFFER: 'Предлагает новый заказ компаниям из архивного цикла, если нет активных заказов и открытой заявки.',
      BAD_REVIEW_INVOICE: 'Фиксирует отправку счета после выполненного плохого отзыва с учетом доплаты.',
      BAD_REVIEW_AUTO_BAN: 'Переводит заказ и компанию в Бан, если финальный счет после плохих не оплатили за заданный срок.',
      REVIEW_RECOVERY_NOTICE: 'Финально уведомляет клиента, что все восстановления по заказу завершены, и снимает паузу с платежных таймеров.'
    }[scenario.scenario] ?? 'Сценарий автоответчика: кандидаты, отправки, пропуски и ошибки.';
  }

  trackMonitorMetric(_index: number, metric: DictionaryMetric): string {
    return metric.label;
  }

  trackMonitorScenario(_index: number, scenario: AdminClientMessageMonitorScenario): string {
    return scenario.scenario;
  }

  trackMonitorQueueItem(_index: number, item: AdminClientMessageMonitorQueueItem): number {
    return item.id;
  }

  trackMonitorAttempt(_index: number, attempt: AdminClientMessageMonitorAttempt): number {
    return attempt.id;
  }

  deleteSelected(): void {
    const activeTab = this.activeTab();
    if (activeTab === 'promo' || activeTab === 'managerTexts' || activeTab === 'gamification' || activeTab === 'settings' || activeTab === 'autoresponder' || activeTab === 'autoresponderMonitor') {
      return;
    }

    if (activeTab === 'phones') {
      this.deleteSelectedPhone();
      return;
    }

    const selectedId = this.selectedId();
    if (selectedId == null || this.deleting()) {
      return;
    }

    const confirmed = window.confirm(`Удалить запись из справочника "${this.activeLabel()}"?`);
    if (!confirmed) {
      return;
    }

    this.deleting.set(true);
    this.error.set(null);

    const request = {
      categories: () => this.dictionariesApi.deleteCategory(selectedId),
      subcategories: () => this.dictionariesApi.deleteSubCategory(selectedId),
      cities: () => this.dictionariesApi.deleteCity(selectedId),
      products: () => this.dictionariesApi.deleteProduct(selectedId),
      accounts: () => this.dictionariesApi.deleteBot(selectedId),
      promo: () => this.dictionariesApi.deleteBot(selectedId)
    }[activeTab]();

    request.subscribe({
      next: () => {
        this.deleting.set(false);
        this.clearSelection();
        this.toastService.success('Запись удалена', this.activeLabel());
        this.reloadAfterMutation();
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось удалить запись');
        this.error.set(message);
        this.deleting.set(false);
        this.toastService.error('Запись не удалена', message);
      }
    });
  }

  deleteDeviceToken(phone: OperatorPhone, deviceToken: DeviceToken): void {
    if (this.deleting()) {
      return;
    }

    const confirmed = window.confirm(`Удалить токен устройства телефона ${phone.number}?`);
    if (!confirmed) {
      return;
    }

    this.deleting.set(true);
    this.error.set(null);

    this.phonesApi.deleteDeviceToken(phone.id, deviceToken.token).subscribe({
      next: () => {
        this.deleting.set(false);
        this.removeDeviceToken(phone.id, deviceToken.token);
        this.toastService.success('Токен удален', phone.number);
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось удалить токен устройства');
        this.error.set(message);
        this.deleting.set(false);
        this.toastService.error('Токен не удален', message);
      }
    });
  }

  tabTotal(tab: DictionaryTabKey): number {
    return {
      categories: this.categories().length,
      subcategories: this.subCategories().length,
      cities: this.cities().length,
      products: this.products().length,
      phones: this.phones().length,
      accounts: this.bots().length,
      promo: this.promoTexts().length,
      managerTexts: this.managerTexts().length,
      gamification: this.gamificationTotal(),
      settings: this.settingsTotal(),
      autoresponder: this.autoresponderTotal(),
      autoresponderMonitor: this.monitorTotal()
    }[tab];
  }

  settingsTotal(): number {
    return (this.nagulSettings() ? 1 : 0)
      + (this.telegramReportSettings() ? 1 : 0)
      + (this.whatsAppGroupSyncSettings() ? 1 : 0)
      + (this.clientPublicationProgressReportSettings() ? 1 : 0);
  }

  gamificationTotal(): number {
    const settings = this.gamificationSettings();
    if (!settings) {
      return 0;
    }
    return [
      settings.enabled,
      settings.workerEnabled,
      settings.managerEnabled,
      settings.operatorEnabled,
      settings.marketologEnabled,
      settings.showInCabinet,
      settings.showInScore,
      settings.eventsEnabled,
      settings.shadowScoringEnabled
    ].filter(Boolean).length;
  }

  gamificationEventLabel(eventType: string | null | undefined): string {
    return {
      REVIEW_PUBLISHED: 'Отзыв опубликован',
      ORDER_PAID: 'Заказ закрыт оплатой',
      BAD_REVIEW_TASK_DONE: 'Плохой отзыв выполнен',
      REVIEW_RECOVERY_TASK_DONE: 'Восстановление выполнено'
    }[eventType ?? ''] ?? (eventType || 'Событие');
  }

  gamificationRoleLabel(role: string | null | undefined): string {
    return {
      WORKER: 'Специалист',
      MANAGER: 'Менеджер',
      OPERATOR: 'Оператор',
      MARKETOLOG: 'Маркетолог'
    }[role ?? ''] ?? (role || '-');
  }

  gamificationActor(event: AdminGamificationEvent): string {
    if (event.actorName) {
      return event.actorName;
    }
    if (event.actorRole) {
      return event.actorRole;
    }
    return 'Система';
  }

  gamificationEventTarget(event: AdminGamificationEvent): string {
    const parts = [
      event.orderId ? `Заказ ${event.orderId}` : null,
      event.reviewId ? `Отзыв ${event.reviewId}` : null,
      event.badReviewTaskId ? `Плохая ${event.badReviewTaskId}` : null,
      event.recoveryTaskId ? `Восстановление ${event.recoveryTaskId}` : null
    ].filter(Boolean);
    return parts.length ? parts.join(' · ') : 'Без привязки';
  }

  gamificationTimelinessLabel(event: AdminGamificationEvent): string {
    if (!event.plannedDate || !event.actualDate) {
      return 'без срока';
    }
    const delay = event.delayDays ?? 0;
    if (delay <= 0) {
      return 'в срок';
    }
    const percent = Math.round((event.timelinessMultiplier ?? 1) * 100);
    return `+${delay} дн. · ${percent}%`;
  }

  trackGamificationEvent(_index: number, event: AdminGamificationEvent): number {
    return event.id;
  }

  trackGamificationScoreActor(index: number, item: { actorUserId?: number | null; actorName?: string | null; actorRole?: string | null }): string {
    return `${item.actorUserId ?? 'system'}-${item.actorRole ?? 'role'}-${item.actorName ?? index}`;
  }

  trackGamificationBalance(index: number, item: AdminGamificationBalance): string {
    return `${item.actorUserId ?? 'system'}-${item.actorRole ?? 'role'}-${item.actorName ?? index}`;
  }

  private byRole<T extends { actorRole?: string | null }>(items: T[], role: string): T[] {
    return items.filter((item) => item.actorRole === role);
  }

  private withoutRoles<T extends { actorRole?: string | null }>(items: T[], roles: string[]): T[] {
    return items.filter((item) => !roles.includes(item.actorRole ?? ''));
  }

  setGamificationProgressDays(days: GamificationProgressDays): void {
    if (this.gamificationProgressDays() === days) {
      return;
    }
    this.gamificationProgressDays.set(days);
    this.loadGamificationProgress();
  }

  trackGamificationType(_index: number, item: { eventType: string }): string {
    return item.eventType;
  }

  trackGamificationActor(index: number, item: { actorUserId?: number | null; actorName?: string | null; actorRole?: string | null }): string {
    return `${item.actorUserId ?? 'system'}-${item.actorRole ?? 'role'}-${item.actorName ?? index}`;
  }

  saveGamificationRules(): void {
    const raw = this.gamificationForm.getRawValue();
    const request: AdminGamificationRulesRequest = {
      rules: [
        { eventType: 'REVIEW_PUBLISHED', enabled: raw.reviewPublishedRuleEnabled, points: raw.reviewPublishedRulePoints },
        { eventType: 'ORDER_PAID', enabled: raw.orderPaidRuleEnabled, points: raw.orderPaidRulePoints },
        { eventType: 'BAD_REVIEW_TASK_DONE', enabled: raw.badReviewTaskDoneRuleEnabled, points: raw.badReviewTaskDoneRulePoints },
        { eventType: 'REVIEW_RECOVERY_TASK_DONE', enabled: raw.reviewRecoveryTaskDoneRuleEnabled, points: raw.reviewRecoveryTaskDoneRulePoints }
      ]
    };

    this.saving.set(true);
    this.error.set(null);

    this.dictionariesApi.updateGamificationRules(request).subscribe({
      next: (rules) => {
        this.saving.set(false);
        this.applyGamificationRules(rules);
        this.loadGamificationProgress();
        this.toastService.success('Правила очков сохранены', 'Предпросмотр обновлен');
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить правила очков');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Правила не сохранены', message);
      }
    });
  }

  rebuildGamificationLedger(): void {
    if (!this.gamificationSettings()?.shadowScoringEnabled) {
      this.toastService.error('Ledger не пересобран', 'Сначала включите теневое начисление очков');
      return;
    }

    const days = this.gamificationProgressDays();
    const confirmed = window.confirm(`Пересобрать shadow-ledger за ${days} дн.? Текущие shadow-записи за период будут заменены.`);
    if (!confirmed) {
      return;
    }

    this.saving.set(true);
    this.error.set(null);

    this.dictionariesApi.rebuildGamificationScoreLedger(days).subscribe({
      next: (result) => {
        this.saving.set(false);
        this.gamificationLedgerRebuild.set(result);
        this.loadGamificationProgress();
        this.toastService.success(
          'Ledger пересобран',
          `Событий: ${result.eventsReviewed}, записей: ${result.entriesCreated}, очков: ${result.totalPoints}`
        );
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось пересобрать ledger');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Ledger не пересобран', message);
      }
    });
  }

  backfillGamificationEvents(): void {
    const days = this.gamificationProgressDays();
    const confirmed = window.confirm(`Собрать исторические события геймификации за ${days} дн.? Дубли будут пропущены, ledger будет пересобран.`);
    if (!confirmed) {
      return;
    }

    this.saving.set(true);
    this.error.set(null);

    this.dictionariesApi.backfillGamificationEvents(days).subscribe({
      next: (result) => {
        this.saving.set(false);
        this.gamificationBackfill.set(result);
        this.gamificationLedgerRebuild.set(result.ledgerRebuild);
        this.loadGamificationProgress();
        this.toastService.success(
          'История собрана',
          `Проверено: ${result.reviewedCandidates}, новых событий: ${result.eventsCreated}, очков: ${result.ledgerRebuild.totalPoints}`
        );
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось собрать исторические события');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('История не собрана', message);
      }
    });
  }

  autoresponderTotal(): number {
    const settings = this.clientMessageSettings();
    if (!settings) {
      return 0;
    }
    return [
      settings.workerEnabled,
      settings.liveEnabled,
      settings.reviewCheckEnabled,
      settings.clientTextReminderEnabled,
      settings.paymentReminderEnabled,
      settings.badReviewInvoiceEnabled,
      settings.badReviewAutoBanEnabled,
      settings.paymentOverdueEnabled,
      settings.archiveReorderEnabled
    ].filter(Boolean).length;
  }

  monitorTotal(): number {
    if (!this.clientMessageSettings()?.monitorEnabled) {
      return 0;
    }
    return this.clientMessageMonitor()?.activeCandidates ?? 0;
  }

  paymentInstructionSourceLabel(source?: 'MANAGER_TEXT' | 'TBANK_LINK' | string | null): string {
    return source === 'TBANK_LINK' ? 'T-Bank /pay ссылка' : 'текст менеджера';
  }

  categoryTitle(category?: DictionaryOption | null): string {
    return category?.title || '-';
  }

  phoneOperatorName(operator?: PhoneOperatorOption | null): string {
    return operator?.title || '-';
  }

  timerState(phone: OperatorPhone): string {
    return this.isTimerReady(phone) ? 'готов' : 'пауза';
  }

  isTimerReady(phone: OperatorPhone): boolean {
    return !phone.timer || new Date(phone.timer).getTime() <= Date.now();
  }

  deviceTokenCount(phone: OperatorPhone): number {
    return phone.deviceTokens?.length ?? 0;
  }

  tokenPreview(token: string): string {
    return token.length > 18 ? `${token.slice(0, 8)}...${token.slice(-6)}` : token;
  }

  selectedTitle(): string {
    if (this.activeTab() === 'managerTexts') {
      return this.selectedManagerText()?.managerTitle ?? 'Выберите менеджера';
    }

    if (this.activeTab() === 'promo' && this.selectedId() == null) {
      return 'Новый промо-текст';
    }

    if (this.activeTab() === 'phones') {
      return this.selectedPhone() ? `Телефон #${this.selectedPhone()!.id}` : 'Новый телефон';
    }

    if (this.activeTab() === 'settings') {
      return 'Рассылки и выгул';
    }

    if (this.activeTab() === 'autoresponder') {
      return 'Клиентские автоответы';
    }

    if (this.activeTab() === 'autoresponderMonitor') {
      return 'Кабинет мониторинга';
    }

    return this.selectedId() == null ? 'Новая запись' : `ID ${this.selectedId()}`;
  }

  promoTextLabel(promoText: AdminPromoText): string {
    return PROMO_TEXT_LABELS[promoText.position] ?? `Текст #${promoText.position}`;
  }

  promoTextMeta(promoText: AdminPromoText): string {
    return `ID ${promoText.id} · позиция ${promoText.position}`;
  }

  promoTextPreview(value: string): string {
    const normalized = value.replace(/\s+/g, ' ').trim();
    return normalized.length > 110 ? `${normalized.slice(0, 110)}...` : normalized;
  }

  promoUsageSummary(promoText: AdminPromoText): string {
    const usages = this.promoAssignments()
      .filter((assignment) => assignment.promoTextId === promoText.id)
      .map((assignment) => `${assignment.managerTitle}: ${assignment.buttonLabel}`);

    if (usages.length) {
      return usages.slice(0, 2).join(', ') + (usages.length > 2 ? ` +${usages.length - 2}` : '');
    }

    const defaultButtons = this.promoButtons()
      .filter((button) => button.defaultPromoTextId === promoText.id)
      .map((button) => `${button.sectionTitle}: ${button.buttonLabel}`);

    return defaultButtons.length ? `по умолчанию: ${defaultButtons.slice(0, 2).join(', ')}` : 'не назначен';
  }

  managerTextSummary(managerText: AdminManagerText): string {
    const fields = [
      ['оплата', managerText.payText],
      ['начало', managerText.beginText],
      ['оффер', managerText.offerText],
      ['напоминание', managerText.reminderText],
      ['старт', managerText.startText]
    ];
    const filled = fields.filter(([, value]) => value.trim().length > 0).map(([label]) => label);
    return filled.length ? filled.join(', ') : 'тексты не заполнены';
  }

  managerTextPreview(managerText: AdminManagerText): string {
    const value = [
      managerText.payText,
      managerText.beginText,
      managerText.offerText,
      managerText.reminderText,
      managerText.startText
    ].find((text) => text.trim().length > 0) ?? '';
    return this.promoTextPreview(value);
  }

  selectedPromoManagerTitle(): string {
    const managerId = this.selectedPromoManagerId();
    return this.promoManagers().find((manager) => manager.id === managerId)?.title ?? 'Выберите менеджера';
  }

  selectPromoManager(value: string | number): void {
    const managerId = Number(value);
    this.selectedPromoManagerId.set(Number.isFinite(managerId) && managerId > 0 ? managerId : null);
    this.error.set(null);
  }

  promoAssignmentValue(button: PromoButtonSlot): number | '' {
    return this.promoAssignmentFor(button)?.promoTextId ?? '';
  }

  promoDefaultTextLabel(button: PromoButtonSlot): string {
    const defaultText = this.promoTexts().find((text) => text.id === button.defaultPromoTextId);
    return defaultText ? this.promoTextLabel(defaultText) : `позиция ${button.defaultPromoPosition}`;
  }

  promoAssignedTextLabel(button: PromoButtonSlot): string {
    const assignment = this.promoAssignmentFor(button);
    if (!assignment?.promoTextId) {
      return `по умолчанию: ${this.promoDefaultTextLabel(button)}`;
    }

    const text = this.promoTexts().find((promoText) => promoText.id === assignment.promoTextId);
    return text ? this.promoTextLabel(text) : assignment.promoTextLabel;
  }

  savePromoAssignment(button: PromoButtonSlot, value: string): void {
    const managerId = this.selectedPromoManagerId();
    if (managerId == null) {
      this.error.set('Выберите менеджера для назначения промо-текста.');
      return;
    }

    this.saving.set(true);
    this.error.set(null);

    const request: Observable<PromoTextAssignment | void> = value
      ? this.dictionariesApi.savePromoTextAssignment({
          managerId,
          section: button.section,
          buttonKey: button.buttonKey,
          promoTextId: Number(value)
        } satisfies PromoTextAssignmentRequest)
      : this.dictionariesApi.resetPromoTextAssignment(managerId, button.section, button.buttonKey);

    request.subscribe({
      next: () => {
        this.saving.set(false);
        this.toastService.success('Назначение сохранено', `${button.sectionTitle}: ${button.buttonLabel}`);
        this.reloadPromoManagement();
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось сохранить назначение');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Назначение не сохранено', message);
      }
    });
  }

  botBrowserUrl(bot: AdminBot): string {
    return `/admin/dictionaries/accounts/${bot.id}/browser`;
  }

  openBotImport(): void {
    this.importModalOpen.set(true);
    this.importFile.set(null);
    this.importResult.set(null);
    this.importError.set(null);
  }

  closeBotImport(): void {
    if (this.importing()) {
      return;
    }

    this.importModalOpen.set(false);
    this.importFile.set(null);
    this.importError.set(null);
  }

  selectBotImportFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.importFile.set(input.files?.[0] ?? null);
    this.importResult.set(null);
    this.importError.set(null);
  }

  uploadBotImport(): void {
    const file = this.importFile();
    if (!file || this.importing()) {
      this.importError.set('Выберите CSV или Excel-файл.');
      return;
    }

    this.importing.set(true);
    this.importError.set(null);
    this.importResult.set(null);

    this.dictionariesApi.importBots(file).subscribe({
      next: (result) => {
        this.importing.set(false);
        this.importResult.set(result);
        this.importFile.set(null);
        this.toastService.success('Импорт аккаунтов завершен', this.importResultMessage(result));
        this.reloadAfterMutation();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось импортировать аккаунты');
        this.importError.set(message);
        this.importing.set(false);
        this.toastService.error('Аккаунты не импортированы', message);
      }
    });
  }

  importResultMessage(result: BotImportResponse): string {
    const duplicateText = result.skippedDuplicates ? `, дублей пропущено: ${result.skippedDuplicates}` : '';
    const invalidText = result.skippedInvalid ? `, строк с ошибками: ${result.skippedInvalid}` : '';
    return `Добавлено: ${result.added}${duplicateText}${invalidText}`;
  }

  trackTab(_index: number, tab: DictionaryTab): DictionaryTabKey {
    return tab.key;
  }

  trackCategory(_index: number, category: AdminCategory): number {
    return category.id;
  }

  trackSubCategory(_index: number, subCategory: AdminSubCategory): number {
    return subCategory.id;
  }

  trackCity(_index: number, city: AdminCity): number {
    return city.id;
  }

  trackProduct(_index: number, product: AdminProduct): number {
    return product.id;
  }

  trackPhone(_index: number, phone: OperatorPhone): number {
    return phone.id;
  }

  trackPhoneOperator(_index: number, operator: PhoneOperatorOption): number {
    return operator.id;
  }

  trackDeviceToken(_index: number, deviceToken: DeviceToken): string {
    return deviceToken.token;
  }

  trackBot(_index: number, bot: AdminBot): number {
    return bot.id;
  }

  trackPromoText(_index: number, promoText: AdminPromoText): number {
    return promoText.id;
  }

  trackManagerText(_index: number, managerText: AdminManagerText): number {
    return managerText.managerId;
  }

  trackPromoButton(_index: number, button: PromoButtonSlot): string {
    return `${button.section}:${button.buttonKey}`;
  }

  trackOption(_index: number, option: DictionaryOption): number {
    return option.id;
  }

  trackMetric(_index: number, metric: DictionaryMetric): string {
    return metric.label;
  }

  private loadActive(): void {
    this.loading.set(true);
    this.error.set(null);
    this.selectedId.set(null);

    const keyword = this.search();
    let request: Observable<AdminCategory[] | AdminSubCategory[] | AdminCity[] | ProductsResponse | OperatorPhonesResponse | BotsResponse | PromoTextManagementResponse | AdminManagerText[] | GamificationDictionaryResponse | AdminNagulSettings | DictionarySettingsResponse | AdminClientMessageSettings | AdminClientMessageMonitor>;
    switch (this.activeTab()) {
      case 'categories':
        request = this.dictionariesApi.getCategories(keyword);
        break;
      case 'subcategories':
        request = this.dictionariesApi.getSubCategories(keyword);
        break;
      case 'cities':
        request = this.dictionariesApi.getCities(keyword);
        break;
      case 'products':
        request = this.dictionariesApi.getProducts(keyword);
        break;
      case 'phones':
        request = this.phonesApi.getPhones(keyword);
        break;
      case 'accounts':
        request = this.dictionariesApi.getBots(keyword);
        break;
      case 'promo':
        request = this.dictionariesApi.getPromoTextManagement(keyword);
        break;
      case 'managerTexts':
        request = this.dictionariesApi.getManagerTexts(keyword);
        break;
      case 'gamification':
        request = forkJoin({
          settings: this.dictionariesApi.getGamificationSettings(),
          rules: this.dictionariesApi.getGamificationRules(),
          progress: this.dictionariesApi.getGamificationProgress(this.gamificationProgressDays()),
          scorePreview: this.dictionariesApi.getGamificationScorePreview(this.gamificationProgressDays()),
          scoreLedger: this.dictionariesApi.getGamificationScoreLedger(this.gamificationProgressDays()),
          balances: this.dictionariesApi.getGamificationBalances(this.gamificationProgressDays()),
          events: this.dictionariesApi.getGamificationEvents()
        });
        break;
      case 'settings':
        request = forkJoin({
          nagulSettings: this.dictionariesApi.getNagulSettings(),
          telegramReportSettings: this.dictionariesApi.getTelegramReportSettings(),
          whatsAppGroupSyncSettings: this.dictionariesApi.getWhatsAppGroupSyncSettings(),
          clientPublicationProgressReportSettings: this.dictionariesApi.getClientPublicationProgressReportSettings()
        });
        break;
      case 'autoresponder':
        request = this.dictionariesApi.getClientMessageSettings();
        break;
      case 'autoresponderMonitor':
        request = this.dictionariesApi.getClientMessageMonitor();
        break;
    }

    request.subscribe({
      next: (response: AdminCategory[] | AdminSubCategory[] | AdminCity[] | ProductsResponse | OperatorPhonesResponse | BotsResponse | PromoTextManagementResponse | AdminManagerText[] | GamificationDictionaryResponse | AdminNagulSettings | DictionarySettingsResponse | AdminClientMessageSettings | AdminClientMessageMonitor) => {
        switch (this.activeTab()) {
          case 'categories':
            this.categories.set(response as AdminCategory[]);
            this.ensureDefaults();
            break;
          case 'subcategories':
            this.subCategories.set(response as AdminSubCategory[]);
            break;
          case 'cities':
            this.cities.set(response as AdminCity[]);
            break;
          case 'products': {
            const payload = response as ProductsResponse;
            this.products.set(payload.products);
            this.productCategories.set(payload.categories);
            break;
          }
          case 'phones':
            this.applyPhonesResponse(response as OperatorPhonesResponse);
            break;
          case 'accounts':
            this.applyBotsResponse(response as BotsResponse);
            this.ensureDefaults();
            break;
          case 'promo':
            this.applyPromoManagement(response as PromoTextManagementResponse);
            break;
          case 'managerTexts':
            this.managerTexts.set(response as AdminManagerText[]);
            break;
          case 'gamification':
            this.applyGamificationResponse(response as GamificationDictionaryResponse);
            break;
          case 'settings': {
            const payload = response as DictionarySettingsResponse;
            this.applyNagulSettings(payload.nagulSettings);
            this.applyTelegramReportSettings(payload.telegramReportSettings);
            this.applyWhatsAppGroupSyncSettings(payload.whatsAppGroupSyncSettings);
            this.applyClientPublicationProgressReportSettings(payload.clientPublicationProgressReportSettings);
            break;
          }
          case 'autoresponder':
            this.applyClientMessageSettings(response as AdminClientMessageSettings);
            break;
          case 'autoresponderMonitor': {
            const monitor = response as AdminClientMessageMonitor;
            this.clientMessageMonitor.set(monitor);
            this.patchClientMessageMonitorEnabled(monitor.enabled);
            break;
          }
        }

        this.loading.set(false);
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось загрузить справочник');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Справочник не загрузился', message);
      }
    });
  }

  private saveCategory(): void {
    if (this.categoryForm.invalid) {
      this.categoryForm.markAllAsTouched();
      return;
    }

    const request: TitleRequest = { title: this.categoryForm.controls.title.value.trim() };
    const selectedId = this.editingCategoryId();
    const call = selectedId == null
      ? this.dictionariesApi.createCategory(request)
      : this.dictionariesApi.updateCategory(selectedId, request);

    this.runSave(call, 'Категория сохранена', (saved) => {
      this.activeCategoryId.set(saved.id);
      this.editingCategoryId.set(saved.id);
      this.categoryEditorOpen.set(false);
    });
  }

  private saveSubCategory(): void {
    if (this.subCategoryForm.invalid) {
      this.subCategoryForm.markAllAsTouched();
      return;
    }

    const raw = this.subCategoryForm.getRawValue();
    const request: SubCategoryRequest = {
      title: raw.title.trim(),
      categoryId: raw.categoryId ?? this.activeCategoryId()
    };
    const selectedId = this.editingSubCategoryId();
    const call = selectedId == null
      ? this.dictionariesApi.createSubCategory(request)
      : this.dictionariesApi.updateSubCategory(selectedId, request);

    this.runSave(call, 'Подкатегория сохранена', (saved) => {
      this.editingSubCategoryId.set(saved.id);
      this.subCategoryForm.controls.categoryId.setValue(request.categoryId);
      this.subCategoryEditorOpen.set(false);
    });
  }

  private saveCity(): void {
    if (this.cityForm.invalid) {
      this.cityForm.markAllAsTouched();
      return;
    }

    const request: TitleRequest = { title: this.cityForm.controls.title.value.trim() };
    const selectedId = this.selectedId();
    const call = selectedId == null
      ? this.dictionariesApi.createCity(request)
      : this.dictionariesApi.updateCity(selectedId, request);

    this.runSave(call, 'Город сохранен');
  }

  private saveProduct(): void {
    if (this.productForm.invalid) {
      this.productForm.markAllAsTouched();
      return;
    }

    const raw = this.productForm.getRawValue();
    const request: ProductRequest = {
      title: raw.title.trim(),
      price: Number(raw.price || 0),
      categoryId: raw.categoryId,
      photo: raw.photo
    };
    const selectedId = this.selectedId();
    const call = selectedId == null
      ? this.dictionariesApi.createProduct(request)
      : this.dictionariesApi.updateProduct(selectedId, request);

    this.runSave(call, 'Продукт сохранен');
  }

  private savePhone(): void {
    if (this.phoneForm.invalid) {
      this.phoneForm.markAllAsTouched();
      return;
    }

    const phone = this.selectedPhone();
    const request = this.toPhoneRequest();
    const call = phone
      ? this.phonesApi.updatePhone(phone.id, request)
      : this.phonesApi.createPhone(request);

    this.saving.set(true);
    this.error.set(null);

    call.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.selectedId.set(saved.id);
        this.selectedPhone.set(saved);
        this.patchSavedPhone(saved);
        this.selectPhone(saved);
        this.toastService.success('Телефон сохранен', `ID ${saved.id}`);
        this.reloadAfterMutation();
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось сохранить телефон');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Телефон не сохранен', message);
      }
    });
  }

  private deleteSelectedPhone(): void {
    const phone = this.selectedPhone();
    if (!phone || this.deleting()) {
      return;
    }

    const confirmed = window.confirm(`Удалить телефон ${phone.number}?`);
    if (!confirmed) {
      return;
    }

    this.deleting.set(true);
    this.error.set(null);

    this.phonesApi.deletePhone(phone.id).subscribe({
      next: () => {
        this.deleting.set(false);
        this.toastService.success('Телефон удален', phone.number);
        this.startNewPhone();
        this.reloadAfterMutation();
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось удалить телефон');
        this.error.set(message);
        this.deleting.set(false);
        this.toastService.error('Телефон не удален', message);
      }
    });
  }

  private saveBot(): void {
    if (this.botForm.invalid) {
      this.botForm.markAllAsTouched();
      return;
    }

    const raw = this.botForm.getRawValue();
    const request: BotRequest = {
      login: raw.login.trim(),
      password: raw.password.trim(),
      fio: raw.fio.trim(),
      workerId: raw.workerId,
      cityId: raw.cityId,
      statusId: raw.statusId,
      counter: Number(raw.counter || 0),
      active: raw.active
    };
    const selectedId = this.selectedId();
    const call = selectedId == null
      ? this.dictionariesApi.createBot(request)
      : this.dictionariesApi.updateBot(selectedId, request);

    this.runSave(call, 'Аккаунт сохранен');
  }

  private savePromoText(): void {
    if (this.promoTextForm.invalid) {
      this.promoTextForm.markAllAsTouched();
      return;
    }

    const request: PromoTextRequest = {
      text: this.promoTextForm.controls.text.value.trim()
    };

    const selectedId = this.selectedId();
    const call = selectedId == null
      ? this.dictionariesApi.createPromoText(request)
      : this.dictionariesApi.updatePromoText(selectedId, request);

    this.runSave(call, 'Промо-текст сохранен');
  }

  private saveManagerText(): void {
    const managerId = this.selectedId();
    if (managerId == null) {
      this.error.set('Выберите менеджера для редактирования текстов.');
      return;
    }

    const raw = this.managerTextForm.getRawValue();
    const request: ManagerTextRequest = {
      payText: raw.payText,
      beginText: raw.beginText,
      offerText: raw.offerText,
      reminderText: raw.reminderText,
      startText: raw.startText
    };

    this.saving.set(true);
    this.error.set(null);

    this.dictionariesApi.updateManagerText(managerId, request).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.selectedId.set(saved.managerId);
        this.managerTexts.update((items) =>
          items.map((item) => item.managerId === saved.managerId ? saved : item)
        );
        this.managerTextForm.setValue({
          payText: saved.payText,
          beginText: saved.beginText,
          offerText: saved.offerText,
          reminderText: saved.reminderText,
          startText: saved.startText
        });
        this.toastService.success('Тексты менеджера сохранены', saved.managerTitle);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить тексты менеджера');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Тексты не сохранены', message);
      }
    });
  }

  private saveGamificationSettings(): void {
    const raw = this.gamificationForm.getRawValue();
    const request: AdminGamificationSettingsRequest = {
      enabled: raw.enabled,
      workerEnabled: raw.workerEnabled,
      managerEnabled: raw.managerEnabled,
      operatorEnabled: raw.operatorEnabled,
      marketologEnabled: raw.marketologEnabled,
      showInCabinet: raw.showInCabinet,
      showInScore: raw.showInScore,
      eventsEnabled: raw.eventsEnabled,
      shadowScoringEnabled: raw.shadowScoringEnabled
    };

    this.saving.set(true);
    this.error.set(null);

    this.dictionariesApi.updateGamificationSettings(request).subscribe({
      next: (settings) => {
        this.saving.set(false);
        this.applyGamificationSettings(settings);
        this.toastService.success(
          'Геймификация сохранена',
          settings.enabled ? 'Контур включен' : 'Контур выключен'
        );
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить геймификацию');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Геймификация не сохранена', message);
      }
    });
  }

  private saveSettings(): void {
    if (this.settingsForm.invalid) {
      this.settingsForm.markAllAsTouched();
      return;
    }

    const raw = this.settingsForm.getRawValue();
    const nagulRequest: NagulSettingsRequest = {
      cooldownMinutes: Number(raw.nagulCooldownMinutes ?? 0),
      lookaheadDays: Number(raw.nagulLookaheadDays ?? 60),
      accountWalkedCounterThreshold: Number(raw.accountWalkedCounterThreshold ?? 3),
      accountWalkDelayDays: Number(raw.accountWalkDelayDays ?? 2)
    };
    const telegramRequest: TelegramReportScheduleSettingsRequest = {
      morningEnabled: raw.morningReportEnabled,
      morningTime: raw.morningReportTime,
      eveningEnabled: raw.eveningReportEnabled,
      eveningTime: raw.eveningReportTime,
      zone: raw.telegramReportZone.trim()
    };
    const whatsAppRequest: WhatsAppGroupSyncSettingsRequest = {
      enabled: raw.whatsAppGroupSyncEnabled,
      intervalMinutes: Number(raw.whatsAppGroupSyncIntervalMinutes ?? 30)
    };
    const clientPublicationProgressReportRequest: ClientPublicationProgressReportSettingsRequest = {
      enabled: raw.clientPublicationProgressReportsEnabled
    };

    this.saving.set(true);
    this.error.set(null);

    forkJoin({
      nagulSettings: this.dictionariesApi.updateNagulSettings(nagulRequest),
      telegramReportSettings: this.dictionariesApi.updateTelegramReportSettings(telegramRequest),
      whatsAppGroupSyncSettings: this.dictionariesApi.updateWhatsAppGroupSyncSettings(whatsAppRequest),
      clientPublicationProgressReportSettings: this.dictionariesApi.updateClientPublicationProgressReportSettings(
        clientPublicationProgressReportRequest
      )
    }).subscribe({
      next: ({ nagulSettings, telegramReportSettings, whatsAppGroupSyncSettings, clientPublicationProgressReportSettings }) => {
        this.saving.set(false);
        this.applyNagulSettings(nagulSettings);
        this.applyTelegramReportSettings(telegramReportSettings);
        this.applyWhatsAppGroupSyncSettings(whatsAppGroupSyncSettings);
        this.applyClientPublicationProgressReportSettings(clientPublicationProgressReportSettings);
        this.toastService.success(
          'Настройки сохранены',
          `${telegramReportSettings.morningTime} / ${telegramReportSettings.eveningTime}, WhatsApp ${whatsAppGroupSyncSettings.intervalMinutes} мин.`
        );
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить настройки');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Настройки не сохранены', message);
      }
    });
  }

  private saveAutoresponderSettings(): void {
    if (this.autoresponderForm.invalid) {
      this.autoresponderForm.markAllAsTouched();
      return;
    }

    const raw = this.autoresponderForm.getRawValue();
    const request: ClientMessageSettingsRequest = {
      workerEnabled: raw.workerEnabled,
      liveEnabled: raw.liveEnabled,
      immediateEnabled: raw.immediateEnabled,
      monitorEnabled: raw.monitorEnabled,
      reviewCheckEnabled: raw.reviewCheckEnabled,
      reviewCheckAutoArchiveEnabled: raw.reviewCheckAutoArchiveEnabled,
      clientTextReminderEnabled: raw.clientTextReminderEnabled,
      paymentReminderEnabled: raw.paymentReminderEnabled,
      badReviewInvoiceEnabled: raw.badReviewInvoiceEnabled,
      badReviewAutoBanEnabled: raw.badReviewAutoBanEnabled,
      reviewRecoveryNoticeEnabled: raw.reviewRecoveryNoticeEnabled,
      paymentOverdueEnabled: raw.paymentOverdueEnabled,
      paymentOverdueLiveEnabled: raw.paymentOverdueLiveEnabled,
      archiveReorderEnabled: raw.archiveReorderEnabled,
      errorProtectionEnabled: raw.errorProtectionEnabled,
      reviewCheckIntervalDays: Number(raw.reviewCheckIntervalDays ?? 2),
      reviewCheckAutoArchiveDays: Number(raw.reviewCheckAutoArchiveDays ?? 30),
      clientTextReminderIntervalDays: Number(raw.clientTextReminderIntervalDays ?? 3),
      paymentReminderIntervalDays: Number(raw.paymentReminderIntervalDays ?? 2),
      reviewCheckRetryDelayHours: Number(raw.reviewCheckRetryDelayHours ?? 2),
      paymentInvoiceRetryDelayHours: Number(raw.paymentInvoiceRetryDelayHours ?? 2),
      badReviewInvoiceRetryDelayHours: Number(raw.badReviewInvoiceRetryDelayHours ?? 2),
      badReviewAutoBanDelayDays: Number(raw.badReviewAutoBanDelayDays ?? 2),
      reviewRecoveryNoticeRetryDelayHours: Number(raw.reviewRecoveryNoticeRetryDelayHours ?? 2),
      paymentOverdueDays: Number(raw.paymentOverdueDays ?? 30),
      archiveReorderMonths: Number(raw.archiveReorderMonths ?? 3),
      archiveReorderJitterDays: Number(raw.archiveReorderJitterDays ?? 10),
      archiveOrderRetentionDays: Number(raw.archiveOrderRetentionDays ?? 90),
      errorProtectionThreshold: Number(raw.errorProtectionThreshold ?? 20),
      errorProtectionWindowMinutes: Number(raw.errorProtectionWindowMinutes ?? 10),
      errorProtectionCooldownMinutes: Number(raw.errorProtectionCooldownMinutes ?? 60),
      whatsAppAuthRetryHours: Number(raw.whatsAppAuthRetryHours ?? 2),
      whatsAppAuthAlertCooldownHours: Number(raw.whatsAppAuthAlertCooldownHours ?? 12),
      retentionDays: Number(raw.retentionDays ?? 90),
      tickBatchSize: Number(raw.tickBatchSize ?? 5),
      candidateLimit: Number(raw.candidateLimit ?? 200),
      dailyLimit: Number(raw.dailyLimit ?? 140),
      defaultGapSeconds: Number(raw.defaultGapSeconds ?? 180),
      whatsAppGapSeconds: Number(raw.whatsAppGapSeconds ?? 180),
      telegramGapSeconds: Number(raw.telegramGapSeconds ?? 90),
      maxGapSeconds: Number(raw.maxGapSeconds ?? 90),
      businessWindows: raw.businessWindows.trim(),
      reviewCheckStatuses: raw.reviewCheckStatuses.trim(),
      clientTextReminderStatuses: raw.clientTextReminderStatuses.trim(),
      paymentReminderStatuses: raw.paymentReminderStatuses.trim(),
      paymentOverdueStatuses: raw.paymentOverdueStatuses.trim(),
      closedOrderStatuses: raw.closedOrderStatuses.trim(),
      paymentOverdueTargetStatus: raw.paymentOverdueTargetStatus.trim(),
      archiveCompanyStatus: raw.archiveCompanyStatus.trim(),
      archiveInactiveOrderStatuses: raw.archiveInactiveOrderStatuses.trim(),
      openNextOrderRequestStatuses: raw.openNextOrderRequestStatuses.trim(),
      reviewLinkBaseUrl: raw.reviewLinkBaseUrl.trim(),
      reviewReminderText: raw.reviewReminderText.trim(),
      clientTextReminderText: raw.clientTextReminderText.trim(),
      publicationStartedText: raw.publicationStartedText.trim(),
      publicationProgressReportText: raw.publicationProgressReportText.trim(),
      paymentInstructionSource: raw.paymentInstructionSource,
      paymentReminderText: raw.paymentReminderText.trim(),
      paymentLinkCopyText: raw.paymentLinkCopyText.trim(),
      paymentSuccessText: raw.paymentSuccessText.trim(),
      reviewRecoveryNoticeText: raw.reviewRecoveryNoticeText.trim(),
      archiveOfferText: raw.archiveOfferText.trim()
    };

    this.saving.set(true);
    this.error.set(null);

    this.dictionariesApi.updateClientMessageSettings(request).subscribe({
      next: (settings) => {
        this.saving.set(false);
        this.applyClientMessageSettings(settings);
        this.toastService.success(
          'Автоответчик сохранен',
          settings.workerEnabled ? `лимит ${settings.dailyLimit} в день` : 'сервис выключен'
        );
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить автоответчик');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Автоответчик не сохранен', message);
      }
    });
  }

  private runSave<T extends { id: number }>(
    request: Observable<T>,
    title: string,
    afterSave?: (saved: T) => void
  ): void {
    this.saving.set(true);
    this.error.set(null);

    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        if (afterSave) {
          afterSave(saved);
        } else {
          this.selectedId.set(saved.id);
        }
        this.toastService.success(title, `ID ${saved.id}`);
        this.reloadAfterMutation();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить запись');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Запись не сохранена', message);
      }
    });
  }

  private reloadAfterMutation(): void {
    if (this.activeTab() === 'promo') {
      this.reloadPromoManagement();
      return;
    }

    if (this.activeTab() === 'categories' || this.activeTab() === 'subcategories') {
      forkJoin({
        categories: this.dictionariesApi.getCategories(this.activeTab() === 'categories' ? this.search() : ''),
        subCategories: this.dictionariesApi.getSubCategories(this.activeTab() === 'subcategories' ? this.search() : '')
      }).subscribe({
        next: ({ categories, subCategories }) => {
          this.categories.set(categories);
          this.subCategories.set(subCategories);
          this.ensureDefaults();
        }
      });
      return;
    }

    this.loadActive();
  }

  private defaultCategoryId(): number | null {
    return this.categories()[0]?.id ?? null;
  }

  private defaultProductCategoryId(): number | null {
    return this.productCategories()[0]?.id ?? null;
  }

  private toPhoneRequest(): OperatorPhoneRequest {
    const raw = this.phoneForm.getRawValue();
    return {
      number: raw.number.trim(),
      fio: this.emptyToNull(raw.fio),
      birthday: raw.birthday || null,
      amountAllowed: Number(raw.amountAllowed || 0),
      amountSent: Number(raw.amountSent || 0),
      blockTime: Number(raw.blockTime || 0),
      timer: raw.timer || null,
      googleLogin: this.emptyToNull(raw.googleLogin),
      googlePassword: this.emptyToNull(raw.googlePassword),
      avitoPassword: this.emptyToNull(raw.avitoPassword),
      mailLogin: this.emptyToNull(raw.mailLogin),
      mailPassword: this.emptyToNull(raw.mailPassword),
      fotoInstagram: this.emptyToNull(raw.fotoInstagram),
      active: raw.active,
      createDate: raw.createDate || null,
      operatorId: raw.operatorId
    };
  }

  private toDateInput(value?: string | null): string {
    return value ? value.slice(0, 10) : '';
  }

  private toDateTimeInput(value?: string | null): string {
    return value ? value.slice(0, 16) : '';
  }

  private emptyToNull(value: string): string | null {
    const trimmed = value.trim();
    return trimmed ? trimmed : null;
  }

  private patchSavedPhone(phone: OperatorPhone): void {
    this.phones.update((phones) => {
      const exists = phones.some((item) => item.id === phone.id);
      return exists
        ? phones.map((item) => item.id === phone.id ? phone : item)
        : [phone, ...phones];
    });
  }

  private removeDeviceToken(phoneId: number, token: string): void {
    const removeFromPhone = (phone: OperatorPhone): OperatorPhone => ({
      ...phone,
      deviceTokens: (phone.deviceTokens ?? []).filter((item) => item.token !== token)
    });

    this.phones.update((phones) =>
      phones.map((phone) => phone.id === phoneId ? removeFromPhone(phone) : phone)
    );

    const selectedPhone = this.selectedPhone();
    if (selectedPhone?.id === phoneId) {
      const updatedPhone = removeFromPhone(selectedPhone);
      this.selectedPhone.set(updatedPhone);
      this.selectPhone(updatedPhone);
    }
  }

  private applyPhonesResponse(response: OperatorPhonesResponse): void {
    this.phones.set(response.phones);
    this.phoneOperators.set(response.operators);
    this.restorePhoneSelection(response.phones);
  }

  private restorePhoneSelection(phones: OperatorPhone[]): void {
    const selectedId = this.selectedPhone()?.id || (Number.isFinite(this.requestedPhoneId) ? this.requestedPhoneId : null);
    const nextSelected = selectedId ? phones.find((phone) => phone.id === selectedId) : null;

    if (nextSelected) {
      this.selectPhone(nextSelected);
      return;
    }

    if (this.activeTab() === 'phones' && !this.selectedPhone()) {
      this.startNewPhone();
    }
  }

  private defaultBotWorkerId(): number | null {
    return this.botWorkers()[0]?.id ?? null;
  }

  private defaultBotStatusId(): number | null {
    return this.botStatuses()[0]?.id ?? null;
  }

  private defaultBotCityId(): number | null {
    return this.botCities()[0]?.id ?? null;
  }

  private applyBotsResponse(response: BotsResponse): void {
    this.bots.set(response.bots);
    this.botWorkers.set(response.workers);
    this.botStatuses.set(response.statuses);
    this.botCities.set(response.cities);
  }

  private applyPromoManagement(response: PromoTextManagementResponse): void {
    this.promoTexts.set(response.texts);
    this.promoManagers.set(response.managers);
    this.promoAssignments.set(response.assignments);
    this.promoButtons.set(response.buttons);

    const selectedManagerId = this.selectedPromoManagerId();
    const hasSelectedManager = selectedManagerId != null
      && response.managers.some((manager) => manager.id === selectedManagerId);
    if (!hasSelectedManager) {
      this.selectedPromoManagerId.set(response.managers[0]?.id ?? null);
    }
  }

  private applyNagulSettings(response: AdminNagulSettings): void {
    this.nagulSettings.set(response);
    this.settingsForm.patchValue({
      nagulCooldownMinutes: response.cooldownMinutes,
      nagulLookaheadDays: response.lookaheadDays,
      accountWalkedCounterThreshold: response.accountWalkedCounterThreshold,
      accountWalkDelayDays: response.accountWalkDelayDays
    });
  }

  private applyTelegramReportSettings(response: AdminTelegramReportScheduleSettings): void {
    this.telegramReportSettings.set(response);
    this.settingsForm.patchValue({
      morningReportEnabled: response.morningEnabled,
      morningReportTime: response.morningTime,
      eveningReportEnabled: response.eveningEnabled,
      eveningReportTime: response.eveningTime,
      telegramReportZone: response.zone
    });
  }

  private applyWhatsAppGroupSyncSettings(response: AdminWhatsAppGroupSyncSettings): void {
    this.whatsAppGroupSyncSettings.set(response);
    this.settingsForm.patchValue({
      whatsAppGroupSyncEnabled: response.enabled,
      whatsAppGroupSyncIntervalMinutes: response.intervalMinutes
    });
  }

  private applyClientPublicationProgressReportSettings(response: AdminClientPublicationProgressReportSettings): void {
    this.clientPublicationProgressReportSettings.set(response);
    this.settingsForm.patchValue({
      clientPublicationProgressReportsEnabled: response.enabled
    });
  }

  private applyGamificationSettings(response: AdminGamificationSettings): void {
    this.gamificationSettings.set(response);
    this.gamificationForm.patchValue({
      enabled: response.enabled,
      workerEnabled: response.workerEnabled,
      managerEnabled: response.managerEnabled,
      operatorEnabled: response.operatorEnabled,
      marketologEnabled: response.marketologEnabled,
      showInCabinet: response.showInCabinet,
      showInScore: response.showInScore,
      eventsEnabled: response.eventsEnabled,
      shadowScoringEnabled: response.shadowScoringEnabled
    });
  }

  private applyGamificationRules(response: AdminGamificationRulesResponse): void {
    this.gamificationRules.set(response.rules);
    const rule = (eventType: string): AdminGamificationRule | undefined =>
      response.rules.find((item) => item.eventType === eventType);
    this.gamificationForm.patchValue({
      reviewPublishedRuleEnabled: rule('REVIEW_PUBLISHED')?.enabled ?? true,
      reviewPublishedRulePoints: rule('REVIEW_PUBLISHED')?.points ?? 10,
      orderPaidRuleEnabled: rule('ORDER_PAID')?.enabled ?? true,
      orderPaidRulePoints: rule('ORDER_PAID')?.points ?? 25,
      badReviewTaskDoneRuleEnabled: rule('BAD_REVIEW_TASK_DONE')?.enabled ?? true,
      badReviewTaskDoneRulePoints: rule('BAD_REVIEW_TASK_DONE')?.points ?? 15,
      reviewRecoveryTaskDoneRuleEnabled: rule('REVIEW_RECOVERY_TASK_DONE')?.enabled ?? true,
      reviewRecoveryTaskDoneRulePoints: rule('REVIEW_RECOVERY_TASK_DONE')?.points ?? 20
    });
  }

  private applyGamificationResponse(response: GamificationDictionaryResponse): void {
    this.applyGamificationSettings(response.settings);
    this.applyGamificationRules(response.rules);
    this.gamificationProgress.set(response.progress);
    this.gamificationScorePreview.set(response.scorePreview);
    this.gamificationScoreLedger.set(response.scoreLedger);
    this.gamificationBalances.set(response.balances);
    this.gamificationEvents.set(response.events);
  }

  private loadGamificationProgress(): void {
    forkJoin({
      progress: this.dictionariesApi.getGamificationProgress(this.gamificationProgressDays()),
      scorePreview: this.dictionariesApi.getGamificationScorePreview(this.gamificationProgressDays()),
      scoreLedger: this.dictionariesApi.getGamificationScoreLedger(this.gamificationProgressDays()),
      balances: this.dictionariesApi.getGamificationBalances(this.gamificationProgressDays())
    }).subscribe({
      next: ({ progress, scorePreview, scoreLedger, balances }) => {
        this.gamificationProgress.set(progress);
        this.gamificationScorePreview.set(scorePreview);
        this.gamificationScoreLedger.set(scoreLedger);
        this.gamificationBalances.set(balances);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить прогресс геймификации');
        this.toastService.error('Прогресс не загружен', message);
      }
    });
  }

  private applyClientMessageSettings(response: AdminClientMessageSettings): void {
    this.clientMessageSettings.set(response);
    this.autoresponderForm.patchValue(response);
    this.syncClientMessageMonitorPolling();
  }

  private patchClientMessageMonitorEnabled(enabled: boolean): void {
    const current = this.clientMessageSettings();
    if (current) {
      this.clientMessageSettings.set({ ...current, monitorEnabled: enabled });
    }
    this.autoresponderForm.patchValue({ monitorEnabled: enabled });
  }

  private syncClientMessageMonitorPolling(): void {
    if (this.canPollClientMessageMonitor()) {
      this.loadClientMessageMonitor(true);
      this.startClientMessageMonitorPolling();
      return;
    }
    this.stopClientMessageMonitorPolling();
  }

  private startClientMessageMonitorPolling(): void {
    if (this.monitorTimerId != null || !this.canPollClientMessageMonitor()) {
      return;
    }
    this.monitorTimerId = window.setInterval(() => {
      if (this.canPollClientMessageMonitor()) {
        this.loadClientMessageMonitor(true);
      } else {
        this.stopClientMessageMonitorPolling();
      }
    }, CLIENT_MESSAGE_MONITOR_POLLING_MS);
  }

  private stopClientMessageMonitorPolling(): void {
    if (this.monitorTimerId == null) {
      return;
    }
    window.clearInterval(this.monitorTimerId);
    this.monitorTimerId = null;
  }

  private canPollClientMessageMonitor(): boolean {
    return this.activeTab() === 'autoresponderMonitor'
      && !!this.clientMessageSettings()?.monitorEnabled
      && (typeof document === 'undefined' || document.visibilityState === 'visible');
  }

  private sharedChatSyncSummary(response: AdminSharedChatLinkSyncResponse): string {
    const parts = [
      `компаний обновлено: ${response.updatedCompanies}`,
      `WhatsApp: ${response.whatsappLinked}`,
      `Telegram: ${response.telegramLinked}`,
      `MAX: ${response.maxLinked}`
    ];
    if (response.conflictGroups > 0) {
      parts.push(`конфликтов: ${response.conflictGroups}`);
    }
    return parts.join(', ');
  }

  private resetSettingsForm(): void {
    this.settingsForm.reset({
      nagulCooldownMinutes: this.nagulSettings()?.cooldownMinutes ?? 60,
      nagulLookaheadDays: this.nagulSettings()?.lookaheadDays ?? 60,
      accountWalkedCounterThreshold: this.nagulSettings()?.accountWalkedCounterThreshold ?? 3,
      accountWalkDelayDays: this.nagulSettings()?.accountWalkDelayDays ?? 2,
      morningReportEnabled: this.telegramReportSettings()?.morningEnabled ?? true,
      morningReportTime: this.telegramReportSettings()?.morningTime ?? '11:30',
      eveningReportEnabled: this.telegramReportSettings()?.eveningEnabled ?? true,
      eveningReportTime: this.telegramReportSettings()?.eveningTime ?? '22:00',
      telegramReportZone: this.telegramReportSettings()?.zone ?? 'Asia/Irkutsk',
      whatsAppGroupSyncEnabled: this.whatsAppGroupSyncSettings()?.enabled ?? true,
      whatsAppGroupSyncIntervalMinutes: this.whatsAppGroupSyncSettings()?.intervalMinutes ?? 30,
      clientPublicationProgressReportsEnabled: this.clientPublicationProgressReportSettings()?.enabled ?? true
    });
  }

  resetGamificationForm(): void {
    const settings = this.gamificationSettings();
    const rule = (eventType: string): AdminGamificationRule | undefined =>
      this.gamificationRules().find((item) => item.eventType === eventType);
    this.gamificationForm.reset({
      enabled: settings?.enabled ?? false,
      workerEnabled: settings?.workerEnabled ?? true,
      managerEnabled: settings?.managerEnabled ?? true,
      operatorEnabled: settings?.operatorEnabled ?? true,
      marketologEnabled: settings?.marketologEnabled ?? true,
      showInCabinet: settings?.showInCabinet ?? false,
      showInScore: settings?.showInScore ?? false,
      eventsEnabled: settings?.eventsEnabled ?? false,
      shadowScoringEnabled: settings?.shadowScoringEnabled ?? false,
      reviewPublishedRuleEnabled: rule('REVIEW_PUBLISHED')?.enabled ?? true,
      reviewPublishedRulePoints: rule('REVIEW_PUBLISHED')?.points ?? 10,
      orderPaidRuleEnabled: rule('ORDER_PAID')?.enabled ?? true,
      orderPaidRulePoints: rule('ORDER_PAID')?.points ?? 25,
      badReviewTaskDoneRuleEnabled: rule('BAD_REVIEW_TASK_DONE')?.enabled ?? true,
      badReviewTaskDoneRulePoints: rule('BAD_REVIEW_TASK_DONE')?.points ?? 15,
      reviewRecoveryTaskDoneRuleEnabled: rule('REVIEW_RECOVERY_TASK_DONE')?.enabled ?? true,
      reviewRecoveryTaskDoneRulePoints: rule('REVIEW_RECOVERY_TASK_DONE')?.points ?? 20
    });
  }

  resetAutoresponderForm(): void {
    const settings = this.clientMessageSettings();
    this.autoresponderForm.reset({
      workerEnabled: settings?.workerEnabled ?? true,
      liveEnabled: settings?.liveEnabled ?? true,
      immediateEnabled: settings?.immediateEnabled ?? true,
      monitorEnabled: settings?.monitorEnabled ?? false,
      reviewCheckEnabled: settings?.reviewCheckEnabled ?? true,
      reviewCheckAutoArchiveEnabled: settings?.reviewCheckAutoArchiveEnabled ?? true,
      clientTextReminderEnabled: settings?.clientTextReminderEnabled ?? true,
      paymentReminderEnabled: settings?.paymentReminderEnabled ?? true,
      badReviewInvoiceEnabled: settings?.badReviewInvoiceEnabled ?? true,
      badReviewAutoBanEnabled: settings?.badReviewAutoBanEnabled ?? true,
      reviewRecoveryNoticeEnabled: settings?.reviewRecoveryNoticeEnabled ?? true,
      paymentOverdueEnabled: settings?.paymentOverdueEnabled ?? true,
      paymentOverdueLiveEnabled: settings?.paymentOverdueLiveEnabled ?? false,
      archiveReorderEnabled: settings?.archiveReorderEnabled ?? true,
      errorProtectionEnabled: settings?.errorProtectionEnabled ?? true,
      reviewCheckIntervalDays: settings?.reviewCheckIntervalDays ?? 2,
      reviewCheckAutoArchiveDays: settings?.reviewCheckAutoArchiveDays ?? 30,
      clientTextReminderIntervalDays: settings?.clientTextReminderIntervalDays ?? 3,
      paymentReminderIntervalDays: settings?.paymentReminderIntervalDays ?? 2,
      reviewCheckRetryDelayHours: settings?.reviewCheckRetryDelayHours ?? 2,
      paymentInvoiceRetryDelayHours: settings?.paymentInvoiceRetryDelayHours ?? 2,
      badReviewInvoiceRetryDelayHours: settings?.badReviewInvoiceRetryDelayHours ?? 2,
      badReviewAutoBanDelayDays: settings?.badReviewAutoBanDelayDays ?? 2,
      reviewRecoveryNoticeRetryDelayHours: settings?.reviewRecoveryNoticeRetryDelayHours ?? 2,
      paymentOverdueDays: settings?.paymentOverdueDays ?? 30,
      archiveReorderMonths: settings?.archiveReorderMonths ?? 3,
      archiveReorderJitterDays: settings?.archiveReorderJitterDays ?? 10,
      archiveOrderRetentionDays: settings?.archiveOrderRetentionDays ?? 90,
      errorProtectionThreshold: settings?.errorProtectionThreshold ?? 20,
      errorProtectionWindowMinutes: settings?.errorProtectionWindowMinutes ?? 10,
      errorProtectionCooldownMinutes: settings?.errorProtectionCooldownMinutes ?? 60,
      whatsAppAuthRetryHours: settings?.whatsAppAuthRetryHours ?? 2,
      whatsAppAuthAlertCooldownHours: settings?.whatsAppAuthAlertCooldownHours ?? 12,
      retentionDays: settings?.retentionDays ?? 90,
      tickBatchSize: settings?.tickBatchSize ?? 5,
      candidateLimit: settings?.candidateLimit ?? 200,
      dailyLimit: settings?.dailyLimit ?? 140,
      defaultGapSeconds: settings?.defaultGapSeconds ?? 180,
      whatsAppGapSeconds: settings?.whatsAppGapSeconds ?? 180,
      telegramGapSeconds: settings?.telegramGapSeconds ?? 90,
      maxGapSeconds: settings?.maxGapSeconds ?? 90,
      businessWindows: settings?.businessWindows ?? '10:00-12:00,14:00-17:00,19:00-21:00',
      reviewCheckStatuses: settings?.reviewCheckStatuses ?? 'На проверке',
      clientTextReminderStatuses: settings?.clientTextReminderStatuses ?? 'Новый',
      paymentReminderStatuses: settings?.paymentReminderStatuses ?? 'Выставлен счет,Напоминание',
      paymentOverdueStatuses: settings?.paymentOverdueStatuses ?? 'Выставлен счет,Напоминание',
      closedOrderStatuses: settings?.closedOrderStatuses ?? 'Оплачено,Архив,Бан,Не оплачено',
      paymentOverdueTargetStatus: settings?.paymentOverdueTargetStatus ?? 'Не оплачено',
      archiveCompanyStatus: settings?.archiveCompanyStatus ?? 'На стопе',
      archiveInactiveOrderStatuses: settings?.archiveInactiveOrderStatuses ?? 'Оплачено,Архив,Бан',
      openNextOrderRequestStatuses: settings?.openNextOrderRequestStatuses ?? 'PENDING,FAILED',
      reviewLinkBaseUrl: settings?.reviewLinkBaseUrl ?? 'https://o-ogo.ru',
      reviewReminderText: settings?.reviewReminderText
        ?? '{companyAndFilial}\n\nЗдравствуйте! Напоминаем, пожалуйста, проверьте шаблоны отзывов и внесите правки, если они нужны.\n\nСсылка на проверку отзывов: {reviewLink}',
      clientTextReminderText: settings?.clientTextReminderText
        ?? '{companyAndFilial}\n\nЗдравствуйте! Напоминаем, пожалуйста, пришлите текст или пожелания для отзывов по заказу №{orderId}, чтобы мы могли продолжить работу.',
      publicationStartedText: settings?.publicationStartedText
        ?? '{companyAndFilial}\n\nСпасибо, правки получили. Отзывы переданы в публикацию. Будем присылать короткие отчёты по мере публикации.',
      publicationProgressReportText: settings?.publicationProgressReportText
        ?? '{companyAndFilial}. Опубликован новый отзыв {progress}.',
      paymentInstructionSource: settings?.paymentInstructionSource ?? 'MANAGER_TEXT',
      paymentReminderText: settings?.paymentReminderText ?? '{companyAndFilial}\n\n{managerPayText} К оплате: {sum} руб.',
      paymentLinkCopyText: settings?.paymentLinkCopyText
        ?? '{companyAndFilial}\n\nЗдравствуйте, ваш заказ выполнен. К оплате: {sum} руб.\n\n{paymentInstruction}\n\n{paymentAfterword}',
      paymentSuccessText: settings?.paymentSuccessText
        ?? 'Оплата прошла успешно.\n\nНовый заказ принят в работу.\n{orderLine}{companyLine}Сумма: {sum}\nСтраница оплаты: {paymentPage}\n\n{receiptText}',
      reviewRecoveryNoticeText: settings?.reviewRecoveryNoticeText
        ?? '{companyAndFilial}\n\nВсе отзывы по заказу №{orderId} восстановлены. Продолжаем работу.',
      archiveOfferText: settings?.archiveOfferText
        ?? '{company}\n\nЗдравствуйте! Давно не запускали новый заказ. Можем подготовить новую аккуратную серию отзывов и обновить карточку компании. Если актуально, напишите, пожалуйста, сколько отзывов нужно в этот раз.'
    });
  }

  private reloadPromoManagement(): void {
    this.dictionariesApi.getPromoTextManagement(this.search()).subscribe({
      next: (response) => this.applyPromoManagement(response),
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось обновить промо-тексты');
        this.error.set(message);
        this.toastService.error('Промо не обновилось', message);
      }
    });
  }

  private promoAssignmentFor(button: PromoButtonSlot): PromoTextAssignment | null {
    const managerId = this.selectedPromoManagerId();
    if (managerId == null) {
      return null;
    }

    return this.promoAssignments().find((assignment) =>
      assignment.managerId === managerId
      && assignment.section === button.section
      && assignment.buttonKey === button.buttonKey
    ) ?? null;
  }

  private handleLoadAllError(err: unknown): void {
    const message = this.errorMessage(err, 'Не удалось загрузить справочники');
    this.error.set(message);
    this.loading.set(false);
    this.toastService.error('Справочники не загрузились', message);
  }

  private initialTab(): DictionaryTabKey {
    const routeTab = this.route.snapshot.data['initialTab'] ?? this.route.snapshot.queryParamMap.get('tab');
    return this.isDictionaryTab(routeTab) ? routeTab : 'categories';
  }

  private isDictionaryTab(value: unknown): value is DictionaryTabKey {
    return [
      'categories',
      'subcategories',
      'cities',
      'products',
      'phones',
      'accounts',
      'promo',
      'managerTexts',
      'gamification',
      'settings',
      'autoresponder',
      'autoresponderMonitor'
    ].includes(String(value));
  }

  private ensureDefaults(): void {
    const activeCategoryId = this.activeCategoryId();
    const hasActiveCategory = activeCategoryId != null
      && this.categories().some((category) => category.id === activeCategoryId);

    if (!hasActiveCategory) {
      this.activeCategoryId.set(this.defaultCategoryId());
    }

    const subCategoryCategoryId = this.subCategoryForm.controls.categoryId.value;
    const hasSubCategoryCategory = subCategoryCategoryId != null
      && this.categories().some((category) => category.id === subCategoryCategoryId);

    if (subCategoryCategoryId == null || !hasSubCategoryCategory) {
      this.subCategoryForm.controls.categoryId.setValue(this.activeCategoryId() ?? this.defaultCategoryId());
    }

    if (this.productForm.controls.categoryId.value == null) {
      this.productForm.controls.categoryId.setValue(this.defaultProductCategoryId());
    }

    if (this.botForm.controls.workerId.value == null) {
      this.botForm.controls.workerId.setValue(this.defaultBotWorkerId());
    }

    if (this.botForm.controls.statusId.value == null) {
      this.botForm.controls.statusId.setValue(this.defaultBotStatusId());
    }

    if (this.botForm.controls.cityId.value == null) {
      this.botForm.controls.cityId.setValue(this.defaultBotCityId());
    }
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }
}
