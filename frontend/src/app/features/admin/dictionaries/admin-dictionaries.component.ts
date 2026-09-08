import { AdminTaxonomyApi } from '../../../core/admin-taxonomy.api';
import { AdminTaxonomyFacade } from './admin-taxonomy.facade';
import { AdminProductsApi } from '../../../core/admin-products.api';
import { AdminProductsFacade } from './admin-products.facade';
import { AdminCommunicationTextsApi } from '../../../core/admin-communication-texts.api';
import { AdminCommunicationTextsFacade } from './admin-communication-texts.facade';
import { AdminClientMessageSettingsApi } from '../../../core/admin-client-message-settings.api';
import { AdminClientMessageSettingsFacade } from './admin-client-message-settings.facade';
import { AdminCitiesApi } from '../../../core/admin-cities.api';
import { AdminCitiesFacade } from './admin-cities.facade';
import { AdminContractorSystemApi } from '../../../core/admin-contractor-system.api';
import { AdminContractorSystemFacade } from './admin-contractor-system.facade';
import { AdminAccountsApi } from '../../../core/admin-accounts.api';
import { AdminWorkSettingsApi } from '../../../core/admin-work-settings.api';
import { AdminGamificationApi } from '../../../core/admin-gamification.api';
import { AdminAccountsFacade } from './admin-accounts.facade';
import { AdminPhonesFacade } from './admin-phones.facade';
import { AdminGamificationFacade } from './admin-gamification.facade';
import { AdminWorkSettingsFacade } from './admin-work-settings.facade';

import { AdminAiProviderFacade } from './admin-ai-provider.facade';
import { AdminMessageMonitorApi } from '../../../core/admin-message-monitor.api';
import { AdminMessageMonitorFacade } from './admin-message-monitor.facade';
import { DatePipe } from '@angular/common';
import { Component, HostListener, OnDestroy, computed, inject, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AdminBot, AdminCategory, AdminCity, AdminManagerText, AdminProduct, AdminPromoText, AdminSubCategory, DictionaryOption, PromoButtonSlot } from '../../../core/admin-dictionaries.api';
import { AuthService } from '../../../core/auth.service';

import { ReputationAiApi } from '../../../core/reputation-ai.api';
import { AdminGamificationRewardsApi } from '../../../core/admin-gamification-rewards.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';

import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';
import { UiTooltipDirective } from '../../../shared/ui-tooltip.directive';

import { SpecialistTransferComponent } from '../specialist-transfer/specialist-transfer.component';
import { DeviceToken, OperatorPhone, OperatorPhonesApi, PhoneOperatorOption } from '../../../core/operator-phones.api';
import { ManagerAuditSettingsComponent } from './manager-audit-settings.component';

type DictionaryTabKey = 'categories' | 'subcategories' | 'cities' | 'products' | 'phones' | 'accounts' | 'promo' | 'managerTexts' | 'messageDictionary' | 'specialistTransfer' | 'gamification' | 'settings' | 'audit' | 'aiProvider' | 'autoresponder' | 'autoresponderMonitor';

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
  tooltip?: string;
};

type DictionaryGuide = {
  title: string;
  text: string;
};

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
  messageDictionary: {
    title: 'Фразы без ответа',
    text: 'Короткие ответы клиента, после которых контроль менеджера не создает неотвеченное сообщение.'
  },
  specialistTransfer: {
    title: 'Передача компаний',
    text: 'Операционный перенос текущих компаний, активных заказов и неопубликованных отзывов между специалистами.'
  },
  gamification: {
    title: 'Геймификация',
    text: 'Отдельный контур настроек: глобальное включение, роли, показ в кабинете и события.'
  },
  settings: {
    title: 'Рабочие настройки',
    text: 'Паузы, расписания отчетов и синхронизации, которые влияют на автоматические процессы.'
  },
  audit: {
    title: 'Аудит менеджеров',
    text: 'Формирование ежедневного аудита, проверка понимания и персональное включение по менеджерам.'
  },
  aiProvider: {
    title: 'AI-провайдер',
    text: 'Основная модель для запросов «О компании», «Помощь» и инструментов AI-репутации.'
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
  imports: [AdminLayoutComponent, DatePipe, LoadErrorCardComponent, ReactiveFormsModule, RouterLink, UiTooltipDirective, SpecialistTransferComponent, ManagerAuditSettingsComponent],
  templateUrl: './admin-dictionaries.component.html',
  styleUrls: ['./admin-dictionaries.component.scss', './admin-dictionaries-monitor.component.scss']
})
export class AdminDictionariesComponent implements OnDestroy {

  private readonly route = inject(ActivatedRoute);

  private readonly auth = inject(AuthService);
  private readonly toastService = inject(ToastService);
  readonly selectedId = signal<number | null>(null);
  readonly search = signal('');
  private readonly phonesFeature = new AdminPhonesFacade({ api: inject(OperatorPhonesApi), toast: this.toastService,
    isActive: () => this.domainActive('phones'), selectedId: this.selectedId, search: this.search,
    requestedPhoneId: Number(this.route.snapshot.queryParamMap.get('phoneId')) });
  private readonly accountsFeature = new AdminAccountsFacade({ api: inject(AdminAccountsApi), toast: this.toastService,
    isActive: () => this.domainActive('accounts'), selectedId: this.selectedId, search: this.search });
  private readonly gamificationFeature = new AdminGamificationFacade({ api: inject(AdminGamificationApi), toast: this.toastService,
    isActive: () => this.domainActive('gamification'), selectedId: this.selectedId, search: this.search,
    rewardsApi: inject(AdminGamificationRewardsApi) });
  private readonly settingsFeature = new AdminWorkSettingsFacade({ api: inject(AdminWorkSettingsApi), toast: this.toastService,
    isActive: () => this.domainActive('settings'), selectedId: this.selectedId, search: this.search,
    canApplyMaintenance: () => this.canApplyMaintenance() });
  private readonly contractorFeature=new AdminContractorSystemFacade({api:inject(AdminContractorSystemApi),toast:this.toastService,isActive:()=>this.domainActive('settings'),ownerAllowed:()=>this.canControlContractorSystem()});
  private readonly citiesFeature = new AdminCitiesFacade({ api: inject(AdminCitiesApi), toast: this.toastService,
    isActive: () => this.domainActive('cities'), selectedId: this.selectedId, search: this.search });

  private readonly taxonomyFeature = new AdminTaxonomyFacade({ api: inject(AdminTaxonomyApi), toast: this.toastService,
    isActive: () => ['categories', 'subcategories'].includes(this.activeTab()) && this.documentVisible(),
    canWrite: () => this.canManageAllDictionaries(), tab: () => this.activeTab(), search: () => this.search() });
  private readonly productsFeature = new AdminProductsFacade({ api: inject(AdminProductsApi), toast: this.toastService,
    isActive: () => this.domainActive('products'), selectedId: this.selectedId, search: () => this.search() });
  private readonly textsFeature = new AdminCommunicationTextsFacade({ api: inject(AdminCommunicationTextsApi), toast: this.toastService,
    isActive: () => this.domainActive('promo') || this.domainActive('managerTexts'), tab: () => this.activeTab(),
    selectedId: this.selectedId, search: () => this.search() });
  private readonly messageSettingsFeature = new AdminClientMessageSettingsFacade({ api: inject(AdminClientMessageSettingsApi), toast: this.toastService,
    isActive: () => this.domainActive('messageDictionary') || this.domainActive('autoresponder'),
    onSettingsChanged: () => this.syncClientMessageMonitorPolling() });
  private documentVisible(): boolean { return typeof document === 'undefined' || document.visibilityState === 'visible'; }
  private dictionaryReloadOnVisible = false;
  private domainActive(tab: DictionaryTabKey): boolean {
    return this.activeTab() === tab && this.canManageAllDictionaries()
      && (typeof document === 'undefined' || document.visibilityState === 'visible');
  }
  private activeFeature() {
    switch (this.activeTab()) {
      case 'categories': case 'subcategories': return this.taxonomyFeature;
      case 'products': return this.productsFeature;
      case 'promo': case 'managerTexts': return this.textsFeature;
      case 'messageDictionary': case 'autoresponder': return this.messageSettingsFeature;
      case 'cities': return this.citiesFeature;
      case 'phones': return this.phonesFeature;
      case 'accounts': return this.accountsFeature;
      case 'gamification': return this.gamificationFeature;
      case 'settings': return this.settingsFeature;
      default: return null;
    }
  }

  private dictionaryFeatures() {
    return [this.taxonomyFeature, this.productsFeature, this.textsFeature, this.messageSettingsFeature,
      this.phonesFeature, this.accountsFeature, this.gamificationFeature, this.settingsFeature,
      this.contractorFeature, this.citiesFeature];
  }
  readonly activeSaving = computed(() => this.activeFeature()?.saving() ?? this.saving());
  readonly activeDeleting = computed(() => this.activeFeature()?.deleting() ?? this.deleting());
  readonly activeError = computed(() => { const feature=this.activeFeature(); return feature ? feature.error() : this.error(); });

  private readonly aiProvider = new AdminAiProviderFacade({
    api: inject(ReputationAiApi), toast: this.toastService,
    isActive: () => this.activeTab() === 'aiProvider'
  });
  private readonly messageMonitor: AdminMessageMonitorFacade = new AdminMessageMonitorFacade({
    api: inject(AdminMessageMonitorApi),
    toast: this.toastService,
    isActive: () => this.activeTab() === 'autoresponderMonitor',
    isVisible: () => typeof document === 'undefined' || document.visibilityState === 'visible',
    enabled: () => this.clientMessageSettings()?.monitorEnabled ?? this.clientMessageMonitor()?.enabled ?? false,
    setEnabled: enabled => this.patchClientMessageMonitorEnabled(enabled)
  });
  readonly activeLoading = computed(() => this.loading() || (this.activeFeature()?.loading() ?? false) || (this.activeTab() === 'autoresponderMonitor' && this.clientMessageMonitorLoading()) || (this.activeTab() === 'aiProvider' && this.aiProvider.loading()));

  private readonly allTabs: DictionaryTab[] = [
    { key: 'categories', label: 'Категории', icon: 'category' },
    { key: 'cities', label: 'Города', icon: 'location_city' },
    { key: 'products', label: 'Продукты', icon: 'inventory_2' },
    { key: 'phones', label: 'Телефоны', icon: 'phone_iphone' },
    { key: 'accounts', label: 'Аккаунты', icon: 'manage_accounts' },
    { key: 'promo', label: 'Промо', icon: 'smart_button' },
    { key: 'managerTexts', label: 'Тексты менеджеров', icon: 'article' },
    { key: 'messageDictionary', label: 'Фразы без ответа', icon: 'menu_book' },
    { key: 'specialistTransfer', label: 'Передача', icon: 'sync_alt' },
    { key: 'settings', label: 'Настройки', icon: 'tune' },
    { key: 'audit', label: 'Аудит', icon: 'fact_check' },
    { key: 'aiProvider', label: 'AI-провайдер', icon: 'psychology' },
    { key: 'autoresponder', label: 'Автоответчик', icon: 'mark_chat_unread' },
    { key: 'autoresponderMonitor', label: 'Мониторинг', icon: 'monitor_heart' }
  ];
  private readonly managerTabs: DictionaryTab[] = [
    { key: 'categories', label: 'Категории', icon: 'category' }
  ];

  readonly activeTab = signal<DictionaryTabKey>(this.initialTab());

  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly importing = this.accountsFeature.importing;
  readonly rebuildingCityDistances = this.citiesFeature.rebuildingCityDistances;
  readonly syncingWhatsAppGroups = this.settingsFeature.syncingWhatsAppGroups;
  readonly syncingSharedChatLinks = this.settingsFeature.syncingSharedChatLinks;
  readonly error = signal<string | null>(null);
  readonly importError = this.accountsFeature.importError;
  readonly importResult = this.accountsFeature.importResult;
  readonly importModalOpen = this.accountsFeature.importModalOpen;
  readonly importFile = this.accountsFeature.importFile;
  readonly importMode = this.accountsFeature.importMode;
  readonly importCitySearch = this.accountsFeature.importCitySearch;
  readonly importCityId = this.accountsFeature.importCityId;

  readonly activeCategoryId = this.taxonomyFeature.activeCategoryId;
  readonly editingCategoryId = this.taxonomyFeature.editingCategoryId;
  readonly editingSubCategoryId = this.taxonomyFeature.editingSubCategoryId;
  readonly categoryEditorOpen = this.taxonomyFeature.categoryEditorOpen;
  readonly subCategoryEditorOpen = this.taxonomyFeature.subCategoryEditorOpen;

  readonly categories = this.taxonomyFeature.categories;
  readonly subCategories = this.taxonomyFeature.subCategories;
  readonly cities = this.citiesFeature.cities;
  readonly products = this.productsFeature.products;
  readonly phones = this.phonesFeature.phones;
  readonly phoneOperators = this.phonesFeature.phoneOperators;
  readonly selectedPhone = this.phonesFeature.selectedPhone;
  readonly bots = this.accountsFeature.bots;
  readonly botDetailLoadingId = this.accountsFeature.botDetailLoadingId;
  readonly selectedBotPasswordPresent = this.accountsFeature.selectedBotPasswordPresent;
  readonly promoTexts = this.textsFeature.promoTexts;
  readonly managerTexts = this.textsFeature.managerTexts;
  readonly promoManagers = this.textsFeature.promoManagers;
  readonly promoAssignments = this.textsFeature.promoAssignments;
  readonly promoButtons = this.textsFeature.promoButtons;
  readonly selectedPromoManagerId = this.textsFeature.selectedPromoManagerId;
  readonly nagulSettings = this.settingsFeature.nagulSettings;
  readonly workerAccountActionSettings = this.settingsFeature.workerAccountActionSettings;
  readonly telegramReportSettings = this.settingsFeature.telegramReportSettings;
  readonly whatsAppGroupSyncSettings = this.settingsFeature.whatsAppGroupSyncSettings;
  readonly clientPublicationProgressReportSettings = this.settingsFeature.clientPublicationProgressReportSettings;
  readonly workerCellularAccessSettings = this.settingsFeature.workerCellularAccessSettings;
  readonly auditManagerCount = signal(0);
  readonly gamificationSettings = this.gamificationFeature.gamificationSettings;
  readonly rewardSettings = this.gamificationFeature.rewardSettings;
  readonly gamificationRules = this.gamificationFeature.gamificationRules;
  readonly gamificationProgress = this.gamificationFeature.gamificationProgress;
  readonly gamificationProgressDays = this.gamificationFeature.gamificationProgressDays;
  readonly gamificationScorePreview = this.gamificationFeature.gamificationScorePreview;
  readonly gamificationScoreLedger = this.gamificationFeature.gamificationScoreLedger;
  readonly gamificationLedgerRebuild = this.gamificationFeature.gamificationLedgerRebuild;
  readonly gamificationBackfill = this.gamificationFeature.gamificationBackfill;
  readonly gamificationBalances = this.gamificationFeature.gamificationBalances;
  readonly gamificationEvents = this.gamificationFeature.gamificationEvents;
  readonly topWorkerGamificationScoreActors = this.gamificationFeature.topWorkerGamificationScoreActors;
  readonly topManagerGamificationScoreActors = this.gamificationFeature.topManagerGamificationScoreActors;
  readonly topOtherGamificationScoreActors = this.gamificationFeature.topOtherGamificationScoreActors;
  readonly workerGamificationScoreActors = this.gamificationFeature.workerGamificationScoreActors;
  readonly managerGamificationScoreActors = this.gamificationFeature.managerGamificationScoreActors;
  readonly otherGamificationScoreActors = this.gamificationFeature.otherGamificationScoreActors;
  readonly workerGamificationLedgerActors = this.gamificationFeature.workerGamificationLedgerActors;
  readonly managerGamificationLedgerActors = this.gamificationFeature.managerGamificationLedgerActors;
  readonly otherGamificationLedgerActors = this.gamificationFeature.otherGamificationLedgerActors;
  readonly workerGamificationBalances = this.gamificationFeature.workerGamificationBalances;
  readonly managerGamificationBalances = this.gamificationFeature.managerGamificationBalances;
  readonly otherGamificationBalances = this.gamificationFeature.otherGamificationBalances;
  readonly recentGamificationEvents = this.gamificationFeature.recentGamificationEvents;
  readonly clientMessageSettings = this.messageSettingsFeature.clientMessageSettings;
  readonly contractorSystemStatus=this.contractorFeature.contractorSystemStatus;
  readonly contractorSystemLoading=this.contractorFeature.contractorSystemLoading;
  readonly contractorSystemSaving=this.contractorFeature.contractorSystemSaving;
  readonly contractorSystemError=this.contractorFeature.contractorSystemError;
  readonly contractorSystemActivationOpen=this.contractorFeature.contractorSystemActivationOpen;
  readonly contractorSystemRoutingOpen=this.contractorFeature.contractorSystemRoutingOpen;
  readonly contractorRoutingTargetEnabled=this.contractorFeature.contractorRoutingTargetEnabled;
  readonly contractorSystemToday=this.contractorFeature.contractorSystemToday;
  readonly contractorActivationConfirmation=this.contractorFeature.contractorActivationConfirmation;
  readonly contractorLegacyReconciliation=this.contractorFeature.contractorLegacyReconciliation;
  readonly contractorLegacyReconciliationSaving=this.contractorFeature.contractorLegacyReconciliationSaving;
  readonly contractorLegacyManualGroup=this.contractorFeature.contractorLegacyManualGroup;
  readonly contractorLegacyManualOpen=this.contractorFeature.contractorLegacyManualOpen;
  readonly contractorLegacyAutoConfirmation=this.contractorFeature.contractorLegacyAutoConfirmation;
  readonly contractorLegacyManualConfirmation=this.contractorFeature.contractorLegacyManualConfirmation;
  readonly aiProviderStatus = this.aiProvider.aiProviderStatus;
  readonly aiProviderError = this.aiProvider.aiProviderError;
  readonly switchingAiProvider = this.aiProvider.switchingAiProvider;
  readonly clientMessageMonitor = this.messageMonitor.clientMessageMonitor;
  readonly clientMessageMaintenancePreview = this.messageMonitor.clientMessageMaintenancePreview;
  readonly clientMessageMonitorLoading = this.messageMonitor.clientMessageMonitorLoading;
  readonly clientMessageMaintenancePreviewLoading = this.messageMonitor.clientMessageMaintenancePreviewLoading;
  readonly clientMessageMonitorSaving = this.messageMonitor.clientMessageMonitorSaving;
  readonly clientMessageMonitorError = this.messageMonitor.clientMessageMonitorError;
  readonly clientMessageMaintenancePreviewError = this.messageMonitor.clientMessageMaintenancePreviewError;
  readonly monitorScenarioFilter = this.messageMonitor.monitorScenarioFilter;
  readonly monitorQueueStatusFilter = this.messageMonitor.monitorQueueStatusFilter;
  readonly monitorAttemptStatusFilter = this.messageMonitor.monitorAttemptStatusFilter;
  readonly monitorSearch = this.messageMonitor.monitorSearch;
  readonly expandedMonitorQueueKey = this.messageMonitor.expandedMonitorQueueKey;
  readonly clientMessageManualAction = this.messageMonitor.clientMessageManualAction;
  readonly maintenanceAction = this.messageMonitor.maintenanceAction;
  readonly autoIgnorePhraseDraft = this.messageSettingsFeature.autoIgnorePhraseDraft;
  readonly editingAutoIgnorePhraseIndex = this.messageSettingsFeature.editingAutoIgnorePhraseIndex;
  readonly editingAutoIgnorePhraseValue = this.messageSettingsFeature.editingAutoIgnorePhraseValue;
  readonly productCategories = this.productsFeature.productCategories;
  readonly botWorkers = this.accountsFeature.botWorkers;
  readonly botStatuses = this.accountsFeature.botStatuses;
  readonly botCities = this.accountsFeature.botCities;
  readonly botPage = this.accountsFeature.botPage;
  readonly botPageSize = this.accountsFeature.botPageSize;
  readonly botsTotal = this.accountsFeature.botsTotal;
  readonly trackedBotCityId = this.accountsFeature.trackedBotCityId;
  readonly trackedCityUnblockedAccounts = this.accountsFeature.trackedCityUnblockedAccounts;
  readonly botPageSizeOptions = this.accountsFeature.botPageSizeOptions;
  readonly canManageAllDictionaries = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  });
  readonly canApplyMaintenance = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN']);
  });
  readonly canControlContractorSystem = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasRealmRole('OWNER');
  });
  readonly canActivateContractorSystem=this.contractorFeature.canActivateContractorSystem;
  readonly canChangeContractorRouting=this.contractorFeature.canChangeContractorRouting;
  readonly tabs = computed<DictionaryTab[]>(() => this.canManageAllDictionaries() ? this.allTabs : this.managerTabs);

  readonly categoryForm = this.taxonomyFeature.categoryForm;

  readonly subCategoryForm = this.taxonomyFeature.subCategoryForm;

  readonly cityForm = this.citiesFeature.cityForm;

  readonly productForm = this.productsFeature.productForm;

  readonly phoneForm = this.phonesFeature.phoneForm;

  readonly botForm = this.accountsFeature.botForm;

  readonly promoTextForm = this.textsFeature.promoTextForm;

  readonly managerTextForm = this.textsFeature.managerTextForm;

  readonly settingsForm = this.settingsFeature.settingsForm;

  readonly contractorSystemActivationForm=this.contractorFeature.contractorSystemActivationForm;

  readonly contractorSystemRoutingForm=this.contractorFeature.contractorSystemRoutingForm;

  readonly contractorLegacyAutoForm=this.contractorFeature.contractorLegacyAutoForm;

  readonly contractorLegacyManualForm=this.contractorFeature.contractorLegacyManualForm;

  readonly gamificationForm = this.gamificationFeature.gamificationForm;

  readonly autoresponderForm = this.messageSettingsFeature.autoresponderForm;

  readonly activeLabel = computed(() => this.tabs().find((tab) => tab.key === this.activeTab())?.label ?? '');
  readonly searchPlaceholder = computed(() =>
    this.activeTab() === 'accounts' ? 'Логин, ID, ФИО' : 'Найти'
  );
  readonly searchTooltip = computed(() =>
    this.activeTab() === 'accounts'
      ? 'Поиск аккаунтов работает по логину, ID, ФИО аккаунта, владельцу, статусу и городу. Нажмите Enter или кнопку поиска.'
      : 'Поиск работает по текущему справочнику. Нажмите Enter или кнопку поиска, чтобы обновить список.'
  );
  readonly activeGuide = computed(() => DICTIONARY_GUIDES[this.activeTab()]);
  readonly activeTabIcon = computed(() =>
    this.tabs().find((tab) => tab.key === this.activeTab())?.icon ?? 'help'
  );
  readonly activeCategory = this.taxonomyFeature.activeCategory;
  readonly selectedCategorySubCategories = this.taxonomyFeature.selectedCategorySubCategories;
  readonly selectedManagerText = this.textsFeature.selectedManagerText;
  readonly categoryEditorTitle = this.taxonomyFeature.categoryEditorTitle;
  readonly subCategoryEditorTitle = this.taxonomyFeature.subCategoryEditorTitle;
  readonly phoneDeviceTokenTotal = this.phonesFeature.phoneDeviceTokenTotal;
  readonly botTotalPages = this.accountsFeature.botTotalPages;
  readonly botPageStart = this.accountsFeature.botPageStart;
  readonly botPageEnd = this.accountsFeature.botPageEnd;
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
        return this.botsTotal();
      case 'promo':
        return this.promoTexts().length;
      case 'managerTexts':
        return this.managerTexts().length;
      case 'messageDictionary':
        return this.autoIgnorePhrases().length;
      case 'specialistTransfer':
        return 0;
      case 'gamification':
        return this.gamificationTotal();
      case 'settings':
        return this.settingsTotal();
      case 'audit':
        return this.auditManagerCount();
      case 'aiProvider':
        return this.aiProviderStatus()?.aiAvailable ? 1 : 0;
      case 'autoresponder':
        return this.autoresponderTotal();
      case 'autoresponderMonitor':
        return this.monitorTotal();
    }
  });
  readonly metrics = computed<DictionaryMetric[]>(() => {
    switch (this.activeTab()) {
      case 'categories':
        return [{ label: 'Категории', value: this.categories().length, icon: 'category', tone: 'blue' }];
      case 'subcategories':
        return [{ label: 'Подкатегории', value: this.subCategories().length, icon: 'account_tree', tone: 'teal' }];
      case 'cities':
        return [{ label: 'Города', value: this.cities().length, icon: 'location_city', tone: 'green' }];
      case 'products':
        return [{ label: 'Продукты', value: this.products().length, icon: 'inventory_2', tone: 'yellow' }];
      case 'phones':
        return [
          { label: 'Телефоны', value: this.phones().length, icon: 'phone_iphone', tone: 'teal' },
          { label: 'Токены', value: this.phoneDeviceTokenTotal(), icon: 'devices', tone: 'yellow' }
        ];
      case 'accounts':
        return [{ label: 'Аккаунты', value: this.botsTotal(), icon: 'manage_accounts', tone: 'pink' }];
      case 'promo':
        return [{ label: 'Промо', value: this.promoTexts().length, icon: 'smart_button', tone: 'blue' }];
      case 'managerTexts':
        return [{ label: 'Тексты менеджеров', value: this.managerTexts().length, icon: 'article', tone: 'green' }];
      case 'messageDictionary':
        return [{ label: 'Фраз без ответа', value: this.autoIgnorePhrases().length, icon: 'menu_book', tone: 'blue' }];
      case 'settings':
        return [
          { label: 'Пауза выгула', value: this.nagulSettings()?.cooldownMinutes ?? 0, icon: 'timer', tone: 'teal' },
          { label: 'Дней в выдаче', value: this.nagulSettings()?.lookaheadDays ?? 60, icon: 'event_upcoming', tone: 'blue' },
          { label: 'Порог аккаунта', value: this.nagulSettings()?.accountWalkedCounterThreshold ?? 3, icon: 'verified_user', tone: 'green' },
          { label: 'Сдвиг дат', value: this.nagulSettings()?.accountWalkDelayDays ?? 2, icon: 'date_range', tone: 'yellow' },
          { label: 'Telegram', value: this.telegramReportSettings()?.morningEnabled || this.telegramReportSettings()?.eveningEnabled ? 1 : 0, icon: 'send', tone: 'green' },
          { label: 'WhatsApp sync', value: this.whatsAppGroupSyncSettings()?.enabled ? 1 : 0, icon: 'sync', tone: 'teal' },
          { label: 'Отчеты клиентам', value: this.clientPublicationProgressReportSettings()?.enabled ? 1 : 0, icon: 'reviews', tone: 'blue' }
        ];
      case 'autoresponder':
        return [
          { label: 'Автоответчик', value: this.clientMessageSettings()?.workerEnabled ? 1 : 0, icon: 'mark_chat_unread', tone: 'green' },
          { label: 'Лимит сообщений', value: this.clientMessageSettings()?.dailyLimit ?? 0, icon: 'speed', tone: 'yellow' }
        ];
      default:
        return [];
    }
  });

  readonly monitorMetrics = this.messageMonitor.monitorMetrics;

  readonly archiveOfferMetrics = this.messageMonitor.archiveOfferMetrics;

  readonly monitorArchiveOfferText = computed(() => {
    const template = this.clientMessageSettings()?.archiveOfferText?.trim() ?? '';
    return template.replace(/^\{company\}\s*/i, '').trim() || 'Текст архивного оффера не задан.';
  });

  readonly selectedImportCity = this.accountsFeature.selectedImportCity;
  readonly filteredImportCities = this.accountsFeature.filteredImportCities;
  readonly canUploadBotImport = this.accountsFeature.canUploadBotImport;

  readonly filteredMonitorQueue = this.messageMonitor.filteredMonitorQueue;

  readonly filteredMonitorAttempts = this.messageMonitor.filteredMonitorAttempts;

  constructor() {
    if (!this.tabs().some((item) => item.key === this.activeTab())) {
      this.activeTab.set('categories');
    }

    this.loadActive();
  }

  ngOnDestroy(): void {
    for (const feature of this.dictionaryFeatures()) feature.destroy();
    this.aiProvider.destroy(); this.messageMonitor.destroy();
  }

  @HostListener('document:visibilitychange')
  onDocumentVisibilityChange(): void {
    this.syncClientMessageMonitorPolling();
    if (document.visibilityState !== 'visible') {
      this.dictionaryReloadOnVisible = this.activeLoading();
      this.loading.set(false);
      for (const feature of this.dictionaryFeatures()) feature.deactivate();
    } else if (this.dictionaryReloadOnVisible) {
      this.dictionaryReloadOnVisible = false;
      this.loadActive();
    }
  }

  setTab(tab: DictionaryTabKey): void {
    if (!this.tabs().some((item) => item.key === tab)) {
      return;
    }

    for (const feature of this.dictionaryFeatures()) feature.deactivate();
    this.dictionaryReloadOnVisible=false;
    this.activeTab.set(tab);
    if (tab !== 'aiProvider') this.aiProvider.deactivate();
    this.search.set('');
    this.clearSelection();
    this.syncClientMessageMonitorPolling();
    if (tab === 'aiProvider') {
      this.loading.set(false);
      this.loadAiProviderStatus();
      return;
    }
    this.loadActive();
  }

  loadAll(): void {
    this.loadActive();
  }

  readonly loadContractorPaymentSystemStatus=this.contractorFeature.loadContractorPaymentSystemStatus.bind(this.contractorFeature);

  readonly loadContractorLegacyReconciliation=this.contractorFeature.loadContractorLegacyReconciliation.bind(this.contractorFeature);

  readonly prepareContractorLegacyReconciliation=this.contractorFeature.prepareContractorLegacyReconciliation.bind(this.contractorFeature);

  readonly applyContractorLegacyAutomatic=this.contractorFeature.applyContractorLegacyAutomatic.bind(this.contractorFeature);

  readonly openContractorLegacyManual=this.contractorFeature.openContractorLegacyManual.bind(this.contractorFeature);

  readonly closeContractorLegacyManual=this.contractorFeature.closeContractorLegacyManual.bind(this.contractorFeature);

  readonly resolveContractorLegacyManual=this.contractorFeature.resolveContractorLegacyManual.bind(this.contractorFeature);

  readonly finishLegacyReconciliationError=this.contractorFeature.finishLegacyReconciliationError.bind(this.contractorFeature);

  readonly requestContractorSystemActivation=this.contractorFeature.requestContractorSystemActivation.bind(this.contractorFeature);

  readonly closeContractorSystemActivation=this.contractorFeature.closeContractorSystemActivation.bind(this.contractorFeature);

  readonly contractorSystemActivationReady=this.contractorFeature.contractorSystemActivationReady.bind(this.contractorFeature);

  readonly activateContractorSystem=this.contractorFeature.activateContractorSystem.bind(this.contractorFeature);

  readonly openContractorRoutingChange=this.contractorFeature.openContractorRoutingChange.bind(this.contractorFeature);

  readonly closeContractorRoutingChange=this.contractorFeature.closeContractorRoutingChange.bind(this.contractorFeature);

  readonly contractorRoutingConfirmationPrompt=this.contractorFeature.contractorRoutingConfirmationPrompt.bind(this.contractorFeature);

  readonly contractorRoutingChangeReady=this.contractorFeature.contractorRoutingChangeReady.bind(this.contractorFeature);

  readonly updateContractorRouting=this.contractorFeature.updateContractorRouting.bind(this.contractorFeature);

  readonly contractorPaymentSystemModeLabel=this.contractorFeature.contractorPaymentSystemModeLabel.bind(this.contractorFeature);

  searchActive(): void {
    if (this.activeTab() === 'accounts') {
      this.botPage.set(0);
    }
    this.clearSelection();
    this.loadActive();
  }

  clearSearch(): void {
    this.search.set('');
    if (this.activeTab() === 'accounts') {
      this.botPage.set(0);
    }
    this.clearSelection();
    this.loadActive();
  }

  readonly loadAiProviderStatus = this.aiProvider.loadAiProviderStatus.bind(this.aiProvider);

  readonly selectAiProvider = this.aiProvider.selectAiProvider.bind(this.aiProvider);

  readonly aiProviderDisplayName = this.aiProvider.aiProviderDisplayName.bind(this.aiProvider);

  readonly aiProviderConfigured = this.aiProvider.aiProviderConfigured.bind(this.aiProvider);

  readonly aiProviderModel = this.aiProvider.aiProviderModel.bind(this.aiProvider);

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

  readonly selectCategory = this.taxonomyFeature.selectCategory.bind(this.taxonomyFeature);

  readonly openNewCategory = this.taxonomyFeature.openNewCategory.bind(this.taxonomyFeature);

  readonly editCategory = this.taxonomyFeature.editCategory.bind(this.taxonomyFeature);

  readonly closeCategoryEditor = this.taxonomyFeature.closeCategoryEditor.bind(this.taxonomyFeature);

  readonly selectSubCategory = this.taxonomyFeature.selectSubCategory.bind(this.taxonomyFeature);

  readonly openNewSubCategory = this.taxonomyFeature.openNewSubCategory.bind(this.taxonomyFeature);

  readonly editSubCategory = this.taxonomyFeature.editSubCategory.bind(this.taxonomyFeature);

  readonly closeSubCategoryEditor = this.taxonomyFeature.closeSubCategoryEditor.bind(this.taxonomyFeature);

  readonly selectCity = this.citiesFeature.selectCity.bind(this.citiesFeature);

  readonly selectProduct = this.productsFeature.selectProduct.bind(this.productsFeature);

  readonly selectPhone = this.phonesFeature.selectPhone.bind(this.phonesFeature);

  readonly startNewPhone = this.phonesFeature.startNewPhone.bind(this.phonesFeature);

  readonly selectBot = this.accountsFeature.selectBot.bind(this.accountsFeature);

  readonly selectPromoText = this.textsFeature.selectPromoText.bind(this.textsFeature);

  readonly selectManagerText = this.textsFeature.selectManagerText.bind(this.textsFeature);

  clearSelection(): void {
    this.selectedId.set(null); this.error.set(null);
    this.taxonomyFeature.clearSelection(); this.productsFeature.clearSelection();
    this.textsFeature.clearSelection(); this.citiesFeature.clearSelection();
    this.accountsFeature.clearSelection(); this.phonesFeature.clearSelection();
    this.settingsFeature.resetSettingsForm(); this.messageSettingsFeature.resetAutoresponderForm();
  }

  readonly startNewCategory = this.taxonomyFeature.startNewCategory.bind(this.taxonomyFeature);

  readonly startNewSubCategory = this.taxonomyFeature.startNewSubCategory.bind(this.taxonomyFeature);

  readonly saveSelectedSubCategory = this.taxonomyFeature.saveSelectedSubCategory.bind(this.taxonomyFeature);

  readonly deleteEditingCategory = this.taxonomyFeature.deleteEditingCategory.bind(this.taxonomyFeature);

  readonly deleteEditingSubCategory = this.taxonomyFeature.deleteEditingSubCategory.bind(this.taxonomyFeature);

  saveActive(): void {
    if(this.activeSaving() || this.activeDeleting()) return;
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
      case 'messageDictionary':
        this.saveAutoresponderSettings('Справочник сохранен', (settings) => `${settings.unansweredAutoIgnorePhrases ? this.splitAutoIgnorePhrases(settings.unansweredAutoIgnorePhrases).length : 0} фраз`);
        return;
      case 'autoresponder':
        this.saveAutoresponderSettings();
        return;
      case 'autoresponderMonitor':
        this.loadClientMessageMonitor();
        return;
    }
  }

  readonly runWhatsAppGroupSync = this.settingsFeature.runWhatsAppGroupSync.bind(this.settingsFeature);

  readonly runSharedChatLinkSync = this.settingsFeature.runSharedChatLinkSync.bind(this.settingsFeature);

  readonly autoIgnorePhrases = this.messageSettingsFeature.autoIgnorePhrases.bind(this.messageSettingsFeature);

  readonly updateAutoIgnorePhraseDraft = this.messageSettingsFeature.updateAutoIgnorePhraseDraft.bind(this.messageSettingsFeature);

  readonly updateEditingAutoIgnorePhraseValue = this.messageSettingsFeature.updateEditingAutoIgnorePhraseValue.bind(this.messageSettingsFeature);

  readonly addAutoIgnorePhrase = this.messageSettingsFeature.addAutoIgnorePhrase.bind(this.messageSettingsFeature);

  readonly startEditAutoIgnorePhrase = this.messageSettingsFeature.startEditAutoIgnorePhrase.bind(this.messageSettingsFeature);

  readonly saveAutoIgnorePhraseEdit = this.messageSettingsFeature.saveAutoIgnorePhraseEdit.bind(this.messageSettingsFeature);

  readonly cancelAutoIgnorePhraseEdit = this.messageSettingsFeature.cancelAutoIgnorePhraseEdit.bind(this.messageSettingsFeature);

  readonly removeAutoIgnorePhrase = this.messageSettingsFeature.removeAutoIgnorePhrase.bind(this.messageSettingsFeature);

  readonly setClientMessageMonitorEnabled = this.messageMonitor.setClientMessageMonitorEnabled.bind(this.messageMonitor);

  readonly loadClientMessageMonitor = this.messageMonitor.loadClientMessageMonitor.bind(this.messageMonitor);

  readonly loadClientMessageMaintenancePreview = this.messageMonitor.loadClientMessageMaintenancePreview.bind(this.messageMonitor);

  readonly applyClientMessageMaintenance = this.messageMonitor.applyClientMessageMaintenance.bind(this.messageMonitor);

  readonly setMonitorScenarioFilter = this.messageMonitor.setMonitorScenarioFilter.bind(this.messageMonitor);

  readonly setMonitorQueueStatusFilter = this.messageMonitor.setMonitorQueueStatusFilter.bind(this.messageMonitor);

  readonly setMonitorAttemptStatusFilter = this.messageMonitor.setMonitorAttemptStatusFilter.bind(this.messageMonitor);

  readonly setMonitorSearch = this.messageMonitor.setMonitorSearch.bind(this.messageMonitor);

  readonly toggleMonitorQueueDetails = this.messageMonitor.toggleMonitorQueueDetails.bind(this.messageMonitor);

  readonly retryClientMessageCandidate = this.messageMonitor.retryClientMessageCandidate.bind(this.messageMonitor);

  readonly disableClientMessageCandidate = this.messageMonitor.disableClientMessageCandidate.bind(this.messageMonitor);

  readonly markClientMessageCandidateDone = this.messageMonitor.markClientMessageCandidateDone.bind(this.messageMonitor);

  readonly monitorManualActionKey = this.messageMonitor.monitorManualActionKey.bind(this.messageMonitor);

  readonly monitorQueueTimingLabel = this.messageMonitor.monitorQueueTimingLabel.bind(this.messageMonitor);

  readonly monitorQueueTimingClass = this.messageMonitor.monitorQueueTimingClass.bind(this.messageMonitor);

  readonly monitorAttemptStatusClass = this.messageMonitor.monitorAttemptStatusClass.bind(this.messageMonitor);

  readonly monitorScenarioIcon = this.messageMonitor.monitorScenarioIcon.bind(this.messageMonitor);

  readonly monitorScenarioTone = this.messageMonitor.monitorScenarioTone.bind(this.messageMonitor);

  readonly monitorScenarioDueValue = this.messageMonitor.monitorScenarioDueValue.bind(this.messageMonitor);

  readonly monitorScenarioDueLabel = this.messageMonitor.monitorScenarioDueLabel.bind(this.messageMonitor);

  readonly monitorReadinessClass = this.messageMonitor.monitorReadinessClass.bind(this.messageMonitor);

  readonly monitorScenarioTooltip = this.messageMonitor.monitorScenarioTooltip.bind(this.messageMonitor);

  readonly trackMonitorMetric = this.messageMonitor.trackMonitorMetric.bind(this.messageMonitor);

  readonly trackMonitorScenario = this.messageMonitor.trackMonitorScenario.bind(this.messageMonitor);

  readonly trackMonitorQueueItem = this.messageMonitor.trackMonitorQueueItem.bind(this.messageMonitor);

  readonly trackMonitorAttempt = this.messageMonitor.trackMonitorAttempt.bind(this.messageMonitor);

  deleteSelected(): void {
    switch (this.activeTab()) {
      case 'categories': this.taxonomyFeature.deleteEditingCategory(); return;
      case 'subcategories': this.taxonomyFeature.deleteEditingSubCategory(); return;
      case 'products': this.productsFeature.deleteSelected(); return;
      case 'cities': this.citiesFeature.deleteSelected(); return;
      case 'accounts': this.accountsFeature.deleteSelectedBot(); return;
      case 'phones': this.phonesFeature.deleteSelectedPhone(); return;
      default: return;
    }
  }

  readonly deleteDeviceToken = this.phonesFeature.deleteDeviceToken.bind(this.phonesFeature);

  tabTotal(tab: DictionaryTabKey): number {
    return {
      categories: this.categories().length,
      subcategories: this.subCategories().length,
      cities: this.cities().length,
      products: this.products().length,
      phones: this.phones().length,
      accounts: this.botsTotal(),
      promo: this.promoTexts().length,
      managerTexts: this.managerTexts().length,
      messageDictionary: this.autoIgnorePhrases().length,
      specialistTransfer: 0,
      gamification: this.gamificationTotal(),
      settings: this.settingsTotal(),
      audit: this.auditManagerCount(),
      aiProvider: this.aiProviderStatus()?.aiAvailable ? 1 : 0,
      autoresponder: this.autoresponderTotal(),
      autoresponderMonitor: this.monitorTotal()
    }[tab];
  }

  readonly settingsTotal = this.settingsFeature.settingsTotal.bind(this.settingsFeature);

  readonly gamificationTotal = this.gamificationFeature.gamificationTotal.bind(this.gamificationFeature);

  readonly gamificationEventLabel = this.gamificationFeature.gamificationEventLabel.bind(this.gamificationFeature);

  readonly gamificationRoleLabel = this.gamificationFeature.gamificationRoleLabel.bind(this.gamificationFeature);

  readonly gamificationActor = this.gamificationFeature.gamificationActor.bind(this.gamificationFeature);

  readonly gamificationEventTarget = this.gamificationFeature.gamificationEventTarget.bind(this.gamificationFeature);

  readonly gamificationTimelinessLabel = this.gamificationFeature.gamificationTimelinessLabel.bind(this.gamificationFeature);

  readonly trackGamificationEvent = this.gamificationFeature.trackGamificationEvent.bind(this.gamificationFeature);

  readonly trackGamificationScoreActor = this.gamificationFeature.trackGamificationScoreActor.bind(this.gamificationFeature);

  readonly trackGamificationBalance = this.gamificationFeature.trackGamificationBalance.bind(this.gamificationFeature);

  readonly byRole = this.gamificationFeature.byRole.bind(this.gamificationFeature);

  readonly withoutRoles = this.gamificationFeature.withoutRoles.bind(this.gamificationFeature);

  readonly setGamificationProgressDays = this.gamificationFeature.setGamificationProgressDays.bind(this.gamificationFeature);

  readonly trackGamificationType = this.gamificationFeature.trackGamificationType.bind(this.gamificationFeature);

  readonly trackGamificationActor = this.gamificationFeature.trackGamificationActor.bind(this.gamificationFeature);

  readonly saveGamificationRules = this.gamificationFeature.saveGamificationRules.bind(this.gamificationFeature);

  readonly rebuildGamificationLedger = this.gamificationFeature.rebuildGamificationLedger.bind(this.gamificationFeature);

  readonly backfillGamificationEvents = this.gamificationFeature.backfillGamificationEvents.bind(this.gamificationFeature);

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

  readonly monitorTotal = this.messageMonitor.monitorTotal.bind(this.messageMonitor);

  paymentInstructionSourceLabel(source?: 'MANAGER_TEXT' | 'TBANK_LINK' | string | null): string {
    return source === 'TBANK_LINK' || source === 'BANK_LINK' || source === 'TOCHKA_LINK'
      ? 'банковская /pay-ссылка'
      : 'текст менеджера';
  }

  categoryTitle(category?: DictionaryOption | null): string {
    return category?.title || '-';
  }

  readonly phoneOperatorName = this.phonesFeature.phoneOperatorName.bind(this.phonesFeature);

  readonly timerState = this.phonesFeature.timerState.bind(this.phonesFeature);

  readonly isTimerReady = this.phonesFeature.isTimerReady.bind(this.phonesFeature);

  readonly deviceTokenCount = this.phonesFeature.deviceTokenCount.bind(this.phonesFeature);

  readonly tokenPreview = this.phonesFeature.tokenPreview.bind(this.phonesFeature);

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

    if (this.activeTab() === 'messageDictionary') {
      return 'Фразы без ответа';
    }

    if (this.activeTab() === 'specialistTransfer') {
      return 'Передача текущей работы';
    }

    if (this.activeTab() === 'autoresponder') {
      return 'Клиентские автоответы';
    }

    if (this.activeTab() === 'autoresponderMonitor') {
      return 'Кабинет мониторинга';
    }

    return this.selectedId() == null ? 'Новая запись' : `ID ${this.selectedId()}`;
  }

  readonly promoTextLabel = this.textsFeature.promoTextLabel.bind(this.textsFeature);

  readonly promoTextMeta = this.textsFeature.promoTextMeta.bind(this.textsFeature);

  readonly promoTextPreview = this.textsFeature.promoTextPreview.bind(this.textsFeature);

  readonly promoUsageSummary = this.textsFeature.promoUsageSummary.bind(this.textsFeature);

  readonly managerTextSummary = this.textsFeature.managerTextSummary.bind(this.textsFeature);

  readonly managerTextPreview = this.textsFeature.managerTextPreview.bind(this.textsFeature);

  readonly selectedPromoManagerTitle = this.textsFeature.selectedPromoManagerTitle.bind(this.textsFeature);

  readonly selectPromoManager = this.textsFeature.selectPromoManager.bind(this.textsFeature);

  readonly promoAssignmentValue = this.textsFeature.promoAssignmentValue.bind(this.textsFeature);

  readonly promoDefaultTextLabel = this.textsFeature.promoDefaultTextLabel.bind(this.textsFeature);

  readonly promoAssignedTextLabel = this.textsFeature.promoAssignedTextLabel.bind(this.textsFeature);

  readonly savePromoAssignment = this.textsFeature.savePromoAssignment.bind(this.textsFeature);

  readonly botBrowserUrl = this.accountsFeature.botBrowserUrl.bind(this.accountsFeature);

  readonly openBotImport = this.accountsFeature.openBotImport.bind(this.accountsFeature);

  readonly closeBotImport = this.accountsFeature.closeBotImport.bind(this.accountsFeature);

  readonly selectBotImportFile = this.accountsFeature.selectBotImportFile.bind(this.accountsFeature);

  readonly selectBotImportCity = this.accountsFeature.selectBotImportCity.bind(this.accountsFeature);

  readonly uploadBotImport = this.accountsFeature.uploadBotImport.bind(this.accountsFeature);

  readonly importResultMessage = this.accountsFeature.importResultMessage.bind(this.accountsFeature);

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

  goToBotPage(page: number): void {
    const nextPage = Math.max(0, Math.min(page, this.botTotalPages() - 1));
    if (nextPage === this.botPage() || this.activeLoading()) {
      return;
    }

    this.botPage.set(nextPage);
    this.clearSelection();
    this.loadActive();
  }

  changeBotPageSize(event: Event): void {
    const value = Number((event.target as HTMLSelectElement).value);
    if (!Number.isFinite(value) || value === this.botPageSize()) {
      return;
    }

    this.botPageSize.set(value);
    this.botPage.set(0);
    this.clearSelection();
    this.loadActive();
  }

  private loadActive(): void {
    this.loading.set(false); this.error.set(null);
    const feature = this.activeFeature();
    if (feature) {
      if (this.activeTab() === 'settings') this.loadContractorPaymentSystemStatus();
      feature.load();
      return;
    }
    if (this.activeTab() === 'aiProvider') this.loadAiProviderStatus();
    else if (this.activeTab() === 'autoresponderMonitor') this.messageMonitor.loadClientMessageMonitor(false, true);
  }

  readonly saveCategory = this.taxonomyFeature.saveCategory.bind(this.taxonomyFeature);

  readonly saveSubCategory = this.taxonomyFeature.saveSubCategory.bind(this.taxonomyFeature);

  readonly saveCity = this.citiesFeature.saveCity.bind(this.citiesFeature);
  readonly rebuildCityDistances = this.citiesFeature.rebuildCityDistances.bind(this.citiesFeature);
  readonly rebuildSelectedCityDistances = this.citiesFeature.rebuildSelectedCityDistances.bind(this.citiesFeature);
  readonly importCityCoordinates = this.citiesFeature.importCityCoordinates.bind(this.citiesFeature);

  readonly saveProduct = this.productsFeature.saveProduct.bind(this.productsFeature);

  readonly savePhone = this.phonesFeature.savePhone.bind(this.phonesFeature);

  readonly deleteSelectedPhone = this.phonesFeature.deleteSelectedPhone.bind(this.phonesFeature);

  readonly saveBot = this.accountsFeature.saveBot.bind(this.accountsFeature);

  readonly savePromoText = this.textsFeature.savePromoText.bind(this.textsFeature);

  readonly saveManagerText = this.textsFeature.saveManagerText.bind(this.textsFeature);

  readonly saveGamificationSettings = this.gamificationFeature.saveGamificationSettings.bind(this.gamificationFeature);

  readonly saveSettings = this.settingsFeature.saveSettings.bind(this.settingsFeature);

  readonly saveAutoresponderSettings = this.messageSettingsFeature.saveAutoresponderSettings.bind(this.messageSettingsFeature);

  readonly defaultCategoryId = this.taxonomyFeature.defaultCategoryId.bind(this.taxonomyFeature);

  readonly defaultProductCategoryId = this.productsFeature.defaultProductCategoryId.bind(this.productsFeature);

  readonly toPhoneRequest = this.phonesFeature.toPhoneRequest.bind(this.phonesFeature);

  readonly toDateInput = this.phonesFeature.toDateInput.bind(this.phonesFeature);

  readonly toDateTimeInput = this.phonesFeature.toDateTimeInput.bind(this.phonesFeature);

  readonly emptyToNull = this.phonesFeature.emptyToNull.bind(this.phonesFeature);

  readonly patchSavedPhone = this.phonesFeature.patchSavedPhone.bind(this.phonesFeature);

  readonly removeDeviceToken = this.phonesFeature.removeDeviceToken.bind(this.phonesFeature);

  readonly applyPhonesResponse = this.phonesFeature.applyPhonesResponse.bind(this.phonesFeature);

  readonly restorePhoneSelection = this.phonesFeature.restorePhoneSelection.bind(this.phonesFeature);

  readonly defaultBotWorkerId = this.accountsFeature.defaultBotWorkerId.bind(this.accountsFeature);

  readonly defaultBotStatusId = this.accountsFeature.defaultBotStatusId.bind(this.accountsFeature);

  readonly defaultBotCityId = this.accountsFeature.defaultBotCityId.bind(this.accountsFeature);

  readonly applyBotsResponse = this.accountsFeature.applyBotsResponse.bind(this.accountsFeature);

  readonly loadTrackedCityUnblockedAccountsSnapshot = this.accountsFeature.loadTrackedCityUnblockedAccountsSnapshot.bind(this.accountsFeature);

  readonly applyNagulSettings = this.settingsFeature.applyNagulSettings.bind(this.settingsFeature);

  readonly applyTelegramReportSettings = this.settingsFeature.applyTelegramReportSettings.bind(this.settingsFeature);

  readonly applyWhatsAppGroupSyncSettings = this.settingsFeature.applyWhatsAppGroupSyncSettings.bind(this.settingsFeature);

  readonly applyClientPublicationProgressReportSettings = this.settingsFeature.applyClientPublicationProgressReportSettings.bind(this.settingsFeature);

  readonly applyWorkerAccountActionSettings = this.settingsFeature.applyWorkerAccountActionSettings.bind(this.settingsFeature);

  readonly applyWorkerCellularAccessSettings = this.settingsFeature.applyWorkerCellularAccessSettings.bind(this.settingsFeature);

  readonly workerCellularAccessModeLabel = this.settingsFeature.workerCellularAccessModeLabel.bind(this.settingsFeature);

  readonly workerProtectedSectionLabel = this.settingsFeature.workerProtectedSectionLabel.bind(this.settingsFeature);

  readonly applyGamificationSettings = this.gamificationFeature.applyGamificationSettings.bind(this.gamificationFeature);

  readonly applyRewardSettings = this.gamificationFeature.applyRewardSettings.bind(this.gamificationFeature);

  readonly applyGamificationRules = this.gamificationFeature.applyGamificationRules.bind(this.gamificationFeature);

  readonly applyGamificationResponse = this.gamificationFeature.applyGamificationResponse.bind(this.gamificationFeature);

  readonly loadGamificationProgress = this.gamificationFeature.loadGamificationProgress.bind(this.gamificationFeature);

  readonly applyClientMessageSettings = this.messageSettingsFeature.applyClientMessageSettings.bind(this.messageSettingsFeature);

  readonly patchClientMessageMonitorEnabled = this.messageSettingsFeature.patchClientMessageMonitorEnabled.bind(this.messageSettingsFeature);

  private syncClientMessageMonitorPolling(): void { this.messageMonitor.syncClientMessageMonitorPolling(); }

  readonly splitAutoIgnorePhrases = this.messageSettingsFeature.splitAutoIgnorePhrases.bind(this.messageSettingsFeature);

  readonly sharedChatSyncSummary = this.settingsFeature.sharedChatSyncSummary.bind(this.settingsFeature);

  readonly resetSettingsForm = this.settingsFeature.resetSettingsForm.bind(this.settingsFeature);

  readonly resetGamificationForm = this.gamificationFeature.resetGamificationForm.bind(this.gamificationFeature);

  readonly resetAutoresponderForm = this.messageSettingsFeature.resetAutoresponderForm.bind(this.messageSettingsFeature);

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
      'messageDictionary',
      'specialistTransfer',
      'settings',
      'audit',
      'aiProvider',
      'autoresponder',
      'autoresponderMonitor'
    ].includes(String(value));
  }

}
