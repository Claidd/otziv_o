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
import { BotImportReportComponent } from './bot-import-report.component';
import { AdminPhonesFacade } from './admin-phones.facade';
import { AdminGamificationFacade } from './admin-gamification.facade';
import { AdminWorkSettingsFacade } from './admin-work-settings.facade';

import { AdminAiProviderFacade } from './admin-ai-provider.facade';
import { AdminMessageMonitorApi } from '../../../core/admin-message-monitor.api';
import { AdminMessageMonitorFacade } from './admin-message-monitor.facade';
import { DatePipe } from '@angular/common';
import { Component, HostListener, OnDestroy, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { AbstractControl, ReactiveFormsModule } from '@angular/forms';
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
import { ReviewArchiveChecksComponent } from './review-archive-checks.component';

type DictionaryTabKey = 'categories' | 'subcategories' | 'cities' | 'products' | 'phones' | 'accounts' | 'promo' | 'managerTexts' | 'messageDictionary' | 'specialistTransfer' | 'gamification' | 'settings' | 'audit' | 'aiProvider' | 'autoresponder' | 'autoresponderMonitor';

type DictionaryGroupKey = 'catalog' | 'texts' | 'access' | 'autoresponder' | 'management';

type DictionaryTab = {
  key: DictionaryTabKey;
  label: string;
  icon: string;
  group: DictionaryGroupKey;
};

type DictionaryGroup = {
  key: DictionaryGroupKey;
  label: string;
  icon: string;
  tabs: DictionaryTab[];
};

/** A number shown in the help column: only data that is already loaded for the open tab. */
type DictionaryFact = {
  label: string;
  value: number | string;
};

/** What a tab is for and how it is edited: the tab head shows summary and howTo, the help column the steps. */
type DictionaryHelp = {
  title: string;
  summary: string;
  howTo: string;
  steps: string[];
  usedIn?: string;
  caution?: string;
};

const DICTIONARY_GROUPS: { key: DictionaryGroupKey; label: string; icon: string }[] = [
  { key: 'catalog', label: 'Каталог', icon: 'category' },
  { key: 'texts', label: 'Тексты', icon: 'article' },
  { key: 'access', label: 'Доступы', icon: 'key' },
  { key: 'autoresponder', label: 'Автоответчик', icon: 'mark_chat_unread' },
  { key: 'management', label: 'Управление', icon: 'admin_panel_settings' }
];

/** Tabs whose number of records is shown in the menu once they have been opened. */
const COUNTED_TABS: ReadonlySet<DictionaryTabKey> = new Set<DictionaryTabKey>([
  'categories', 'cities', 'products', 'phones', 'accounts', 'promo', 'managerTexts', 'messageDictionary'
]);

const PRODUCT_PLATFORM_LABELS: Record<string, string> = {
  YANDEX: 'Яндекс',
  GOOGLE: 'Google',
  GIS: '2ГИС',
  OTHER: 'другая площадка'
};

const DICTIONARY_HELP: Record<DictionaryTabKey, DictionaryHelp> = {
  categories: {
    title: 'Категории и подкатегории',
    summary: 'Структура услуг компаний: менеджер выбирает категорию и подкатегорию в карточке компании, они же попадают в отзывы. У продуктов свой отдельный список категорий.',
    howTo: 'выберите категорию — появятся её подкатегории; карандаш в строке — переименовать или удалить.',
    steps: [
      'Выберите категорию в списке — рядом (на телефоне ниже) откроются её подкатегории.',
      '«Новая категория» создаёт категорию, «Новая подкатегория» — подкатегорию выбранной категории.',
      'Карандаш в строке открывает окно: переименовать или удалить запись.'
    ],
    usedIn: 'Карточка компании, отзывы и фильтры менеджера.',
    caution: 'Удаление категории удаляет и её подкатегории, а у компаний и отзывов поле категории становится пустым. Категорию с опубликованными отзывами удалить не получится. Отменить нельзя.'
  },
  subcategories: {
    title: 'Подкатегории',
    summary: 'Уточняют основную категорию и помогают точнее разнести продукты.',
    howTo: 'выберите подкатегорию, измените название или категорию и нажмите «Сохранить».',
    steps: ['Выберите подкатегорию в списке.', 'Измените поля в карточке и нажмите «Сохранить».']
  },
  cities: {
    title: 'Города',
    summary: 'Города для карточек компаний, аккаунтов и фильтров. Координаты нужны, чтобы задания доставались исполнителям из ближайших городов.',
    howTo: 'выберите город, поправьте название или координаты и нажмите «Сохранить».',
    steps: [
      'Выберите город — откроется его карточка: название, широта и долгота.',
      '«Новый город» очищает карточку для новой записи.',
      '«Загрузить координаты» принимает файл CSV или TSV со столбцами: ID города, широта, долгота. Первая строка может быть заголовком.',
      '«Пересчитать новые города» считает расстояния для городов с ID от 150. Один город пересчитывается кнопкой в его карточке.'
    ],
    usedIn: 'Карточки компаний и аккаунтов, подбор ближайшего исполнителя.'
  },
  products: {
    title: 'Продукты',
    summary: 'Услуги, которые выбирают при создании заказа: цена, категория, нужна ли фотография и кто публикует отзывы.',
    howTo: 'выберите продукт в списке, измените его карточку и нажмите «Сохранить».',
    steps: [
      'Выберите продукт — откроется его карточка.',
      '«Новый продукт» очищает карточку для новой записи.',
      'Если публикует исполнитель, выберите площадку и проценты вознаграждения.',
      '«Сохранить» применяет изменения, «Удалить» убирает продукт из справочника.'
    ],
    usedIn: 'Создание и редактирование заказов, вознаграждения исполнителей.',
    caution: 'Продукт, который уже есть в заказах, удалить нельзя.'
  },
  phones: {
    title: 'Телефоны операторов',
    summary: 'Рабочие номера операторов: лимит отправок, пауза после лимита, привязанные устройства и доступы к Google, Avito и почте.',
    howTo: 'выберите номер в списке, измените его карточку и нажмите «Сохранить».',
    steps: [
      'Выберите номер — откроется его карточка.',
      'Пароли не показываются. Чтобы заменить пароль, введите новый; пустое поле оставляет сохранённый.',
      'Снимите «Телефон активен», чтобы номер больше не выдавался в работу.',
      'Устройства появляются после привязки телефона в кабинете оператора; лишнее устройство можно удалить.'
    ],
    usedIn: 'Кабинет оператора: выдача номера, лимиты и блокировка.'
  },
  accounts: {
    title: 'Аккаунты публикации',
    summary: 'Аккаунты, с которых публикуются отзывы: владелец, город, статус и число публикаций.',
    howTo: 'найдите аккаунт поиском, выберите строку, измените его карточку и нажмите «Сохранить».',
    steps: [
      'Поиск ищет по логину, ID, ФИО аккаунта, владельцу, статусу и городу.',
      'Выберите строку — откроется карточка аккаунта; пустое поле пароля не меняет сохранённый пароль.',
      'Много аккаунтов удобнее добавить файлом: «Добавить из файла» или «В город из файла».',
      'Значок в конце строки открывает браузер аккаунта.'
    ],
    usedIn: 'Публикация отзывов специалистами, выгул и замена аккаунтов.'
  },
  promo: {
    title: 'Промо-тексты',
    summary: 'Готовые тексты для кнопок менеджера. У каждой кнопки есть текст по умолчанию, отдельному менеджеру можно назначить другой.',
    howTo: 'выберите текст, чтобы изменить его, или нажмите «Новый текст»; ниже назначьте тексты кнопкам менеджера.',
    steps: [
      'Выберите текст в списке — он откроется для правки.',
      '«Новый текст» очищает поле для нового текста.',
      'В блоке «Кнопки менеджера» выберите менеджера и текст для каждой кнопки — выбор сохраняется сразу.',
      'Личный текст видит только сам менеджер на своих досках «Лиды» и «Менеджер». Владелец, администратор и специалисты видят текст по умолчанию.'
    ],
    usedIn: 'Кнопки копирования текстов на досках «Лиды» и «Менеджер». Тексты на позициях 5 и 12 специалисты отправляют клиентам вместе с отзывами на проверку.',
    caution: 'Правка текста меняет его везде, где он стоит: на кнопках по умолчанию — у всех менеджеров без личного выбора.'
  },
  managerTexts: {
    title: 'Тексты менеджеров',
    summary: 'Личные шаблоны каждого менеджера. В сообщениях клиентам сейчас используется «Текст оплаты»; остальные поля хранятся, но сайтом пока не подставляются.',
    howTo: 'выберите менеджера — откроются его тексты; после правки нажмите «Сохранить».',
    steps: [
      'Выберите менеджера в списке.',
      'Измените нужные тексты и нажмите «Сохранить».',
      'Новый менеджер появляется здесь после добавления в разделе «Пользователи».'
    ],
    usedIn: '«Текст оплаты» — в счетах и напоминаниях об оплате, когда источник оплаты «текст менеджера».'
  },
  messageDictionary: {
    title: 'Фразы без ответа',
    summary: 'Короткие ответы клиента вроде «ок» и «спасибо», на которые менеджеру не нужно отвечать: они не становятся неотвеченными в контроле менеджера.',
    howTo: 'добавьте фразу, поправьте или удалите лишние и нажмите «Сохранить».',
    steps: [
      'Введите фразу в поле «Новая фраза» и нажмите «+» или Enter.',
      'Карандаш правит фразу, корзина удаляет её.',
      'Изменения применяются после «Сохранить»; «Сбросить» возвращает сохранённые значения.'
    ],
    usedIn: 'Контроль менеджеров: неотвеченные сообщения.'
  },
  specialistTransfer: {
    title: 'Передача работы между специалистами',
    summary: 'Незавершённые заказы специалиста переходят другому вместе с неопубликованными отзывами и открытыми задачами. Новый специалист добавляется в компании, прежний в них остаётся.',
    howTo: 'выберите, от кого и кому передать, нажмите «Проверить», затем подтвердите перенос.',
    steps: [
      'Выберите специалиста, от которого забираете работу, и того, кому передаёте.',
      '«Проверить» показывает, что будет перенесено, ничего не меняя.',
      'Перенос выполняется только после отдельного подтверждения.',
      'Владелец переносит работу только между специалистами своей команды и только по компаниям своих менеджеров.'
    ],
    usedIn: 'Компании, заказы и отзывы специалистов.',
    caution: 'Сначала передайте работу, потом отключайте пользователя: отключённого специалиста нет в списках.'
  },
  gamification: {
    title: 'Геймификация',
    summary: 'Отдельный контур настроек: глобальное включение, роли, показ в кабинете и события.',
    howTo: 'измените настройки и нажмите «Сохранить».',
    steps: []
  },
  settings: {
    title: 'Рабочие настройки',
    summary: 'Общие правила работы: новая система расчётов, таймер смены аккаунтов, выгул, контроль мобильного доступа, Telegram-рассылки, WhatsApp-группы и отчёты клиентам.',
    howTo: 'измените значения в нужном блоке и нажмите «Сохранить» внизу формы; кнопки синхронизации срабатывают сразу.',
    steps: [
      'Блок «Новая система расчётов» показывает фактический режим и меняется только отдельными окнами подтверждения.',
      'Остальные блоки — одна форма: измените значения и нажмите «Сохранить» внизу.',
      '«Синхронизировать сейчас» и «Скопировать id общих чатов» выполняются сразу, без сохранения формы.'
    ],
    caution: 'Включение новой системы расчётов необратимо и требует подтверждения фразой.'
  },
  audit: {
    title: 'Аудит менеджеров',
    summary: 'Ежедневный аудит работы менеджеров: кто участвует, как проверяется понимание замечаний и когда ограничивается доступ.',
    howTo: 'включите нужные режимы, задайте сроки и нажмите «Сохранить настройки»; переключатели менеджеров сохраняются сразу.',
    steps: [
      'Включите формирование аудита и нужные проверки.',
      'Задайте сроки чтения и напоминаний и нажмите «Сохранить настройки».',
      'Переключатель менеджера в списке сохраняется сразу, без кнопки.'
    ],
    usedIn: 'Ежедневный аудит в разделе «Контроль».'
  },
  aiProvider: {
    title: 'AI-провайдер',
    summary: 'Модель, которая отвечает в запросах «О компании», «Помощь», в инструментах AI-репутации и в проверках контроля менеджеров.',
    howTo: 'нажмите на карточку провайдера — выбор сразу сохраняется для всей системы; ненастроенные провайдеры недоступны.',
    steps: [
      'Нажмите на карточку нужного провайдера — переключение сохраняется сразу. Уход с DeepSeek сначала спрашивает подтверждение.',
      'Ненастроенный провайдер недоступен для выбора.',
      'Ниже видно активную модель, поиск и предупреждения.'
    ],
    usedIn: '«О компании», «Помощь», AI-репутация, контроль менеджеров (только DeepSeek).',
    caution: 'Проверки контроля менеджеров работают только с DeepSeek. С YandexGPT или OpenAI менеджеры не смогут закрывать неотвеченные сообщения как «ответ не нужен» (будет ошибка), не будет вопросов по отчётам, оценки пояснений исполнителей и AI-разбора в ежедневных сводках.'
  },
  autoresponder: {
    title: 'Автоответчик: настройки и тексты',
    summary: 'Автоматические сообщения клиентам по заказу: напоминания о тексте, проверке и оплате, счета, бан, архивные предложения, а также расписание, лимиты и тексты.',
    howTo: 'откройте нужный сценарий, измените значения или тексты и нажмите «Сохранить» внизу.',
    steps: [
      'Сверху — сводка: что включено, окна отправки и маршрут заказа.',
      'Откройте сценарий и измените значения; тексты сообщений — в отдельном блоке ниже.',
      '«Сохранить» применяет все изменения сразу; «Сбросить» возвращает сохранённые значения.'
    ],
    usedIn: 'Фоновая очередь сообщений и сообщения при смене статуса заказа.',
    caution: '«Фоновой очереди разрешено отправлять клиентам» управляет реальной отправкой сообщений клиентам: включение начинает отправку, выключение её останавливает. Проверьте переключатель перед сохранением.'
  },
  autoresponderMonitor: {
    title: 'Мониторинг автоответчика',
    summary: 'Наблюдение за автоответчиком: очередь, сценарии, отправки и ошибки за сегодня, проверка автоархива и проверка базы без изменений.',
    howTo: 'включите наблюдение, чтобы видеть очередь и отправки; кнопки в строке очереди действуют на одну задачу и сначала спрашивают подтверждение.',
    steps: [
      'Переключатель наблюдения — общая настройка для всех администраторов: включает сбор данных и автообновление этой страницы, на отправку сообщений не влияет.',
      '«Проверить базу» показывает, что можно исправить, ничего не меняя; применение — отдельными кнопками.',
      'В строке очереди: превью сообщения, повторить сейчас, отметить выполненным, отключить задачу.'
    ],
    usedIn: 'Контроль работы автоответчика.'
  }
};

@Component({
  selector: 'app-admin-dictionaries',
  imports: [AdminLayoutComponent, DatePipe, LoadErrorCardComponent, ReactiveFormsModule, RouterLink, UiTooltipDirective, SpecialistTransferComponent, ManagerAuditSettingsComponent, BotImportReportComponent, ReviewArchiveChecksComponent],
  templateUrl: './admin-dictionaries.component.html',
  styleUrls: [
    './admin-dictionaries.component.scss',
    './admin-dictionaries-monitor.component.scss',
    './admin-dictionaries-shell.component.scss'
  ]
})
export class AdminDictionariesComponent implements OnDestroy {

  private readonly route = inject(ActivatedRoute);

  private readonly auth = inject(AuthService);
  private readonly toastService = inject(ToastService);
  readonly selectedId = signal<number | null>(null);
  /** The applied search: what the open list was asked for. The facades read it on every load. */
  readonly search = signal('');
  /** The text in the search field; it becomes the applied search only by «Найти» or Enter. */
  readonly searchDraft = signal('');
  /** The accounts list was opened by «Боты» of a city: the search is by the city title, not an exact city filter. */
  readonly cityLinkTitle = signal<string | null>(null);
  private readonly phonesFeature = new AdminPhonesFacade({ api: inject(OperatorPhonesApi), toast: this.toastService,
    isActive: () => this.domainActive('phones'), selectedId: this.selectedId, search: this.search,
    requestedPhoneId: Number(this.route.snapshot.queryParamMap.get('phoneId')),
    operatorOptional: () => this.auth.hasRealmRole('ADMIN') });
  private readonly accountsFeature = new AdminAccountsFacade({ api: inject(AdminAccountsApi), toast: this.toastService,
    isActive: () => this.domainActive('accounts'), selectedId: this.selectedId, search: this.search,
    afterLoad: () => this.resolvePendingAccountCity() });
  private readonly gamificationFeature = new AdminGamificationFacade({ api: inject(AdminGamificationApi), toast: this.toastService,
    isActive: () => this.domainActive('gamification'), selectedId: this.selectedId, search: this.search,
    rewardsApi: inject(AdminGamificationRewardsApi) });
  private readonly settingsFeature = new AdminWorkSettingsFacade({ api: inject(AdminWorkSettingsApi), toast: this.toastService,
    isActive: () => this.domainActive('settings'), selectedId: this.selectedId, search: this.search,
    canApplyMaintenance: () => this.canApplyMaintenance(), onLateResult: () => { if (this.activeTab() === 'settings') this.dictionaryReloadOnVisible = true; } });
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
  /** A save or delete was in flight when the page was hidden: its result never reached the page. */
  private writeInterruptedByHiding = false;
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
    { key: 'categories', label: 'Категории', icon: 'category', group: 'catalog' },
    { key: 'products', label: 'Продукты', icon: 'inventory_2', group: 'catalog' },
    { key: 'cities', label: 'Города', icon: 'location_city', group: 'catalog' },
    { key: 'promo', label: 'Промо', icon: 'smart_button', group: 'texts' },
    { key: 'managerTexts', label: 'Тексты менеджеров', icon: 'article', group: 'texts' },
    { key: 'messageDictionary', label: 'Фразы без ответа', icon: 'menu_book', group: 'texts' },
    { key: 'phones', label: 'Телефоны', icon: 'phone_iphone', group: 'access' },
    { key: 'accounts', label: 'Аккаунты', icon: 'manage_accounts', group: 'access' },
    { key: 'autoresponder', label: 'Настройки и тексты', icon: 'tune', group: 'autoresponder' },
    { key: 'autoresponderMonitor', label: 'Мониторинг', icon: 'monitor_heart', group: 'autoresponder' },
    { key: 'settings', label: 'Рабочие настройки', icon: 'settings', group: 'management' },
    { key: 'audit', label: 'Аудит', icon: 'fact_check', group: 'management' },
    { key: 'aiProvider', label: 'AI-провайдер', icon: 'psychology', group: 'management' },
    { key: 'specialistTransfer', label: 'Передача работы', icon: 'sync_alt', group: 'management' }
  ];
  private readonly managerTabs: DictionaryTab[] = [
    { key: 'categories', label: 'Категории', icon: 'category', group: 'catalog' }
  ];

  readonly activeTab = signal<DictionaryTabKey>(this.initialTab());
  /** Tabs opened on this visit: only their lists are loaded, so only they get a number in the menu. */
  private readonly visitedTabs = signal<ReadonlySet<DictionaryTabKey>>(new Set());
  /** The phone layout folds the menu into one button. */
  readonly menuOpen = signal(false);
  /** A city requested by a link from «Города» (?cityId=…&section=bots): resolved to its title after the first account load. */
  private pendingAccountCityId: number | null = null;
  /** An account requested by ?botId=…: opened once its list is read (also after a background browser tab shows). */
  private pendingEntryBotId: number | null = null;

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
  readonly settingsLoaded = this.settingsFeature.settingsLoaded;
  readonly canSaveSettings = this.settingsFeature.canSaveSettings;
  readonly canRunSettingsSync = this.settingsFeature.canRunSync;
  readonly whatsAppSyncError = this.settingsFeature.whatsAppSyncError;
  readonly sharedChatSyncError = this.settingsFeature.sharedChatSyncError;
  readonly settingsFieldError = this.settingsFeature.settingsFieldError.bind(this.settingsFeature);
  readonly settingsInvalidSummary = this.settingsFeature.settingsInvalidSummary.bind(this.settingsFeature);
  readonly telegramZoneWarning = this.settingsFeature.telegramZoneWarning.bind(this.settingsFeature);
  readonly telegramRunLabel = this.settingsFeature.telegramRunLabel.bind(this.settingsFeature);
  readonly auditManagerCount = signal(0);
  /** The audit settings keep their own form: its unsaved edits must ask before a tab switch or leaving the page. */
  private readonly auditSettings = viewChild(ManagerAuditSettingsComponent);
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
  readonly contractorActivationConfirmation=this.contractorFeature.contractorActivationConfirmation;
  readonly contractorLegacyReconciliation=this.contractorFeature.contractorLegacyReconciliation;
  readonly contractorLegacyReconciliationSaving=this.contractorFeature.contractorLegacyReconciliationSaving;
  readonly contractorLegacyManualGroup=this.contractorFeature.contractorLegacyManualGroup;
  readonly contractorLegacyManualOpen=this.contractorFeature.contractorLegacyManualOpen;
  readonly contractorLegacyAutoConfirmation=this.contractorFeature.contractorLegacyAutoConfirmation;
  readonly contractorLegacyManualConfirmation=this.contractorFeature.contractorLegacyManualConfirmation;
  readonly contractorLegacyManualError=this.contractorFeature.contractorLegacyManualError;
  readonly canApplyContractorLegacy=this.contractorFeature.canApplyContractorLegacy;
  readonly contractorLegacyManualMaxDate=this.contractorFeature.contractorLegacyManualMaxDate;
  readonly contractorLegacyAutoReady=this.contractorFeature.contractorLegacyAutoReady.bind(this.contractorFeature);
  readonly contractorLegacyManualReady=this.contractorFeature.contractorLegacyManualReady.bind(this.contractorFeature);
  readonly contractorLegacySnapshotStale=this.contractorFeature.contractorLegacySnapshotStale.bind(this.contractorFeature);
  readonly contractorLegacyRunStatusLabel=this.contractorFeature.contractorLegacyRunStatusLabel.bind(this.contractorFeature);
  readonly contractorLegacyCategoryLabel=this.contractorFeature.contractorLegacyCategoryLabel.bind(this.contractorFeature);
  readonly contractorLegacyGroupStatusLabel=this.contractorFeature.contractorLegacyGroupStatusLabel.bind(this.contractorFeature);
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
  readonly maintenanceResult = this.messageMonitor.maintenanceResult;
  readonly monitorEnabled = this.messageMonitor.monitorEnabled;
  readonly monitorSwitchChecked = this.messageMonitor.monitorSwitchChecked;
  readonly monitorSwitchLabel = this.messageMonitor.monitorSwitchLabel;
  readonly isMonitorQueueExpanded = this.messageMonitor.isMonitorQueueExpanded.bind(this.messageMonitor);
  readonly monitorQueueHasError = this.messageMonitor.monitorQueueHasError.bind(this.messageMonitor);
  readonly monitorTargetTypeLabel = this.messageMonitor.monitorTargetTypeLabel.bind(this.messageMonitor);
  readonly monitorChannelLabel = this.messageMonitor.monitorChannelLabel.bind(this.messageMonitor);
  readonly monitorScenarioHasPayment = this.messageMonitor.monitorScenarioHasPayment.bind(this.messageMonitor);
  readonly monitorScenarioSentWord = this.messageMonitor.monitorScenarioSentWord.bind(this.messageMonitor);
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
  /** The menu: groups in a fixed order, each with the tabs the current role may open. */
  readonly menuGroups = computed<DictionaryGroup[]>(() => DICTIONARY_GROUPS
    .map((group) => ({ ...group, tabs: this.tabs().filter((tab) => tab.group === group.key) }))
    .filter((group) => group.tabs.length > 0));
  readonly activeGroup = computed(() => {
    const tab = this.tabs().find((item) => item.key === this.activeTab());
    return DICTIONARY_GROUPS.find((group) => group.key === tab?.group) ?? DICTIONARY_GROUPS[0];
  });
  readonly activeHelp = computed<DictionaryHelp>(() => {
    const help = DICTIONARY_HELP[this.activeTab()];
    if (this.canManageAllDictionaries()) return help;
    // A manager opens only «Категории» and cannot change them: the help must not promise buttons that role lacks.
    return {
      ...help,
      howTo: 'выберите категорию — появятся её подкатегории.',
      steps: [
        'Выберите категорию в списке — рядом (на телефоне ниже) откроются её подкатегории.',
        'Создают, переименовывают и удаляют категории администратор и владелец.'
      ]
    };
  });
  /** Tabs with a list that is searched on the server. */
  readonly searchableTab = computed(() =>
    ['categories', 'cities', 'products', 'phones', 'accounts', 'promo', 'managerTexts'].includes(this.activeTab())
  );
  readonly searchPlaceholder = computed(() => ({
    categories: 'Найти категорию',
    cities: 'Название города',
    products: 'Найти продукт',
    phones: 'Номер или ФИО',
    accounts: 'Логин, ID, ФИО, владелец, город',
    promo: 'Найти текст',
    managerTexts: 'Найти менеджера'
  } as Partial<Record<DictionaryTabKey, string>>)[this.activeTab()] ?? 'Найти');
  /** The «new record» action of a list tab, or null where records are not created on this page. */
  readonly createLabel = computed<string | null>(() => ({
    categories: 'Новая категория',
    cities: 'Новый город',
    products: 'Новый продукт',
    phones: 'Новый телефон',
    accounts: 'Новый аккаунт',
    promo: 'Новый текст'
  } as Partial<Record<DictionaryTabKey, string>>)[this.activeTab()] ?? null);
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
  /** Numbers for the help column: computed from data the open tab has already loaded, never a second request. */
  readonly helpFacts = computed<DictionaryFact[]>(() => {
    const tab = this.activeTab();
    if (!this.listReady(tab)) return [];
    if (this.filteredTabs().has(tab)) {
      // A search result is not a total of the dictionary: only the number found is shown.
      const found = this.listCount(tab);
      return found == null ? [] : [{ label: `Найдено по запросу «${this.search()}»`, value: found }];
    }
    switch (tab) {
      case 'categories':
        return [
          { label: 'Категорий', value: this.categories().length },
          { label: 'Подкатегорий', value: this.categories().reduce((sum, category) => sum + (category.subCategoryCount ?? 0), 0) }
        ];
      case 'cities': {
        const cities = this.cities();
        return [
          { label: 'Городов', value: cities.length },
          { label: 'С координатами', value: cities.filter((city) => city.latitude != null && city.longitude != null).length },
          { label: 'Близость рассчитана', value: cities.filter((city) => city.distanceMatrixReady).length }
        ];
      }
      case 'products':
        return [
          { label: 'Продуктов', value: this.products().length },
          { label: 'Публикует исполнитель', value: this.products().filter((product) => product.requiresPerformer).length }
        ];
      case 'phones':
        return [
          { label: 'Номеров', value: this.phones().length },
          { label: 'Активных', value: this.phones().filter((phone) => phone.active).length },
          { label: 'Устройств', value: this.phoneDeviceTokenTotal() }
        ];
      case 'accounts': {
        const tracked = this.trackedCityUnblockedAccounts();
        return [
          { label: 'Аккаунтов всего', value: this.botsTotal() },
          ...(tracked == null ? [] : [{ label: `Не заблокировано в городе «${this.trackedCityTitle()}»`, value: tracked }])
        ];
      }
      case 'promo':
        return [
          { label: 'Текстов', value: this.promoTexts().length },
          { label: 'Кнопок менеджера', value: this.promoButtons().length }
        ];
      case 'managerTexts':
        return [{ label: 'Менеджеров', value: this.managerTexts().length }];
      case 'messageDictionary':
        return [
          { label: 'Фраз', value: this.autoIgnorePhrases().length },
          { label: 'Правило', value: this.autoresponderForm.controls.unansweredAutoIgnoreEnabled.value ? 'включено' : 'выключено' }
        ];
      case 'autoresponder': {
        const settings = this.clientMessageSettings();
        return settings ? [
          { label: 'Фоновая очередь', value: settings.workerEnabled ? 'работает' : 'выключена' },
          { label: 'Отправка клиентам', value: settings.liveEnabled ? 'включена' : 'выключена' },
          { label: 'Лимит в день', value: settings.dailyLimit ?? 0 }
        ] : [];
      }
      default:
        return [];
    }
  });

  /** The city whose unblocked accounts are counted on entry (325 is the service city «Шаблон» on production). */
  /** The owner an account import gives a row without one (BotImportService.DEFAULT_WORKER_ID = 5). */
  readonly importDefaultWorkerTitle = computed(() => {
    const worker = this.botWorkers().find((option) => option.id === 5);
    return worker ? `«${worker.title}»` : 'с ID 5';
  });

  readonly trackedCityTitle = computed(() =>
    this.botCities().find((city) => city.id === this.trackedBotCityId)?.title ?? `ID ${this.trackedBotCityId}`
  );

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

    this.pendingEntryBotId = this.applyEntryQuery();
    this.loadActive();
  }

  /**
   * Entry links into the page: ?q=… fills the search of the opened list; for accounts ?botId=… narrows the list to
   * that account, and ?cityId=… (the «Боты» link of «Города») narrows it to the city once its title is known.
   * Returns the account to open, if any.
   */
  private applyEntryQuery(): number | null {
    const query = this.route.snapshot.queryParamMap;
    const text = (query.get('q') ?? '').trim();
    if (text && this.searchableTab()) this.applySearch(text);
    if (this.activeTab() !== 'accounts') return null;
    const botId = this.positiveId(query.get('botId'));
    if (botId != null) {
      // Digits of an ID would also match other IDs and logins: the list is narrowed by the login of the opened card.
      this.entryBotAwaitingLogin.set(botId);
      return botId;
    }
    const cityId = this.positiveId(query.get('cityId'));
    if (cityId != null && !text) this.pendingAccountCityId = cityId;
    return null;
  }

  private applySearch(text: string): void {
    this.searchDraft.set(text);
    this.search.set(text);
  }

  /** An account opened by ?botId=… waits for its card; then the list is narrowed to its login. */
  private readonly entryBotAwaitingLogin = signal<number | null>(null);
  /** The city coordinates are typed as text: a value that is not a number or is out of range is named under the form. */
  cityCoordinatesInvalid(): boolean {
    const { latitude, longitude } = this.cityForm.getRawValue();
    return !this.coordinateInRange(latitude, 90) || !this.coordinateInRange(longitude, 180);
  }

  private coordinateInRange(value: string | number | null | undefined, limit: number): boolean {
    if (value == null) return true;
    const text = String(value).trim().replace(',', '.');
    if (!text) return true;
    const number = Number(text);
    return Number.isFinite(number) && Math.abs(number) <= limit;
  }

  /** «#contractor-system» from the users page: once the settings are on screen, the shared switch is brought into view. */
  private pendingSectionAnchor = this.route.snapshot?.fragment === 'contractor-system' ? 'contractor-system' : null;

  private readonly sectionAnchorScroll = effect(() => {
    if (this.pendingSectionAnchor == null || this.activeTab() !== 'settings' || !this.settingsLoaded()) return;
    const anchor = this.pendingSectionAnchor;
    this.pendingSectionAnchor = null;
    setTimeout(() => document.getElementById(anchor)?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  });

  private readonly entryBotLoginSearch = effect(() => {
    const requested = this.entryBotAwaitingLogin();
    if (requested == null || this.selectedId() !== requested || this.botDetailLoadingId() != null) return;
    untracked(() => {
      this.entryBotAwaitingLogin.set(null);
      const login = (this.botForm.controls.login.value ?? '').trim();
      if (!login || this.activeTab() !== 'accounts' || this.search()) return;
      this.applySearch(login);
      this.botPage.set(0);
      this.loadActive();
    });
  });

  private positiveId(value: string | null): number | null {
    const id = Number(value);
    return Number.isInteger(id) && id > 0 ? id : null;
  }

  /** Called after each account page load: turns a pending ?cityId=… into a search by the city title, once. */
  private resolvePendingAccountCity(): void {
    const cityId = this.pendingAccountCityId;
    if (cityId == null || this.activeTab() !== 'accounts') return;
    this.pendingAccountCityId = null;
    const city = this.botCities().find((item) => item.id === cityId);
    if (!city || this.search().trim()) return;
    this.applySearch(city.title);
    this.cityLinkTitle.set(city.title);
    this.botPage.set(0);
    this.loadActive();
  }

  private markVisited(tab: DictionaryTabKey): void {
    if (this.visitedTabs().has(tab)) return;
    this.visitedTabs.update((tabs) => new Set([...tabs, tab]));
  }

  /** Tabs whose loaded list is a search result: their numbers are not totals of the dictionary. */
  private readonly filteredTabs = signal<ReadonlySet<DictionaryTabKey>>(new Set());

  private markFiltered(tab: DictionaryTabKey, filtered: boolean): void {
    if (this.filteredTabs().has(tab) === filtered) return;
    this.filteredTabs.update((tabs) => {
      const next = new Set(tabs);
      if (filtered) next.add(tab); else next.delete(tab);
      return next;
    });
  }

  /** The open list shows a search result (its numbers are «найдено», not totals). */
  readonly activeListFiltered = computed(() => this.filteredTabs().has(this.activeTab()));

  /** True when the list of a tab was requested on this visit and is not being loaded right now. */
  private listReady(tab: DictionaryTabKey): boolean {
    return this.visitedTabs().has(tab) && !(this.activeTab() === tab && this.activeLoading());
  }

  ngOnDestroy(): void {
    for (const feature of this.dictionaryFeatures()) feature.destroy();
    this.aiProvider.destroy(); this.messageMonitor.destroy();
  }

  @HostListener('document:visibilitychange')
  onDocumentVisibilityChange(): void {
    this.syncClientMessageMonitorPolling();
    if (document.visibilityState !== 'visible') {
      // Any read of the open tab that hiding cancels is repeated on return: the list, the payment system status of
      // «Рабочие настройки», the account card being opened.
      this.dictionaryReloadOnVisible = this.dictionaryReloadOnVisible || this.activeLoading()
        || this.contractorSystemLoading() || this.botDetailLoadingId() != null;
      if (this.activeSaving() || this.activeDeleting()) {
        this.dictionaryReloadOnVisible = true;
        this.writeInterruptedByHiding = true;
      }
      this.loading.set(false);
      for (const feature of this.dictionaryFeatures()) feature.deactivate();
    } else if (this.dictionaryReloadOnVisible) {
      this.dictionaryReloadOnVisible = false;
      if (this.writeInterruptedByHiding) {
        this.writeInterruptedByHiding = false;
        this.toastService.warning('Проверьте последнее действие', 'Страница была скрыта, пока шло сохранение или удаление. Список обновлён: проверьте запись, прежде чем повторять действие.');
      }
      this.loadActive();
    }
  }

  setTab(tab: DictionaryTabKey): void {
    if (!this.tabs().some((item) => item.key === tab)) {
      return;
    }
    const fromFoldedMenu = this.menuOpen();
    if (tab === this.activeTab()) {
      // The open tab again (also closing the phone menu by its highlighted item): keep the work, only fold the menu.
      this.menuOpen.set(false);
      return;
    }
    if (this.hasUnsavedChanges()
      && !window.confirm('На вкладке есть несохранённые изменения. Перейти без сохранения?')) {
      return;
    }

    for (const feature of this.dictionaryFeatures()) feature.deactivate();
    this.closePageWindows();
    this.dictionaryReloadOnVisible=false;
    this.pendingAccountCityId = null;
    this.pendingEntryBotId = null;
    this.entryBotAwaitingLogin.set(null);
    this.cityLinkTitle.set(null);
    this.pendingSaveSnapshot = null;
    this.menuOpen.set(false);
    this.activeTab.set(tab);
    if (tab !== 'aiProvider') this.aiProvider.deactivate();
    this.search.set('');
    this.searchDraft.set('');
    this.clearSelection();
    if (fromFoldedMenu) this.focusTabHead();
    // Entering the monitor: its normal read in loadActive() starts polling after the answer, so no silent read first.
    if (tab !== 'autoresponderMonitor') this.syncClientMessageMonitorPolling();
    if (tab === 'aiProvider') {
      this.loading.set(false);
      this.loadAiProviderStatus();
      return;
    }
    this.loadActive();
  }

  loadAll(): void {
    // «Рабочие настройки» are filled from the server again on a reload: typed values are dropped only after a
    // question. Other tabs keep the open card, and the autoresponder fills only the fields nobody touched.
    if (this.activeTab() === 'settings' && this.hasUnsavedChanges()
      && !window.confirm('Есть несохранённые изменения. Обновить без сохранения?')) return;
    // «Обновить» also re-reads the unblocked accounts of the tracked city: a cleared count is read with the list.
    if (this.activeTab() === 'accounts') this.trackedCityUnblockedAccounts.set(null);
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

  /** «Найти»: the typed text becomes the applied search of the list. */
  searchActive(): void {
    if (!this.confirmLeaveEditor()) return;
    this.search.set(this.searchDraft().trim());
    this.cityLinkTitle.set(null);
    if (this.activeTab() === 'accounts') {
      this.botPage.set(0);
    }
    this.clearSelection();
    this.loadActive();
  }

  clearSearch(): void {
    if (!this.confirmLeaveEditor()) return;
    this.searchDraft.set('');
    this.search.set('');
    this.cityLinkTitle.set(null);
    if (this.activeTab() === 'accounts') {
      this.botPage.set(0);
    }
    this.clearSelection();
    this.loadActive();
  }

  /** Windows of the page belong to the open tab: leaving the tab closes them instead of leaving dead buttons. */
  private closePageWindows(): void {
    this.closeBotImport();
    this.closeContractorSystemActivation();
    this.closeContractorRoutingChange();
    this.closeContractorLegacyManual();
  }

  /** After a tab is chosen from the folded phone menu, focus goes to the tab title instead of the page top. */
  private focusTabHead(): void {
    if (typeof document === 'undefined') return;
    setTimeout(() => document.querySelector<HTMLElement>('.dictionary-tab-head h2')?.focus());
  }

  /** Asked by the route guard and the browser before leaving the page. */
  confirmLeavePage(): boolean {
    return !this.hasUnsavedChanges()
      || window.confirm('Есть несохранённые изменения. Уйти со страницы без сохранения?');
  }

  @HostListener('window:beforeunload', ['$event'])
  onBeforeUnload(event: BeforeUnloadEvent): void {
    if (!this.hasUnsavedChanges()) return;
    event.preventDefault();
    event.returnValue = '';
  }

  readonly loadAiProviderStatus = this.aiProvider.loadAiProviderStatus.bind(this.aiProvider);

  readonly selectAiProvider = this.aiProvider.selectAiProvider.bind(this.aiProvider);

  readonly aiProviderDisplayName = this.aiProvider.aiProviderDisplayName.bind(this.aiProvider);

  readonly aiProviderConfigured = this.aiProvider.aiProviderConfigured.bind(this.aiProvider);

  readonly aiProviderModel = this.aiProvider.aiProviderModel.bind(this.aiProvider);

  readonly aiSearchProviderLabel = this.aiProvider.aiSearchProviderLabel.bind(this.aiProvider);

  openActiveCreate(): void {
    if (this.activeTab() !== 'categories' && !this.confirmLeaveEditor()) return;
    this.createActive();
    this.markFormsPristine();
    this.revealEditor();
  }

  private createActive(): void {
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

  /** Below 1180px the subcategories stand under the category list: a picked category brings them into view. */
  selectCategory(category: AdminCategory): void {
    this.taxonomyFeature.selectCategory(category);
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    if (!window.matchMedia('(max-width: 1180px)').matches) return;
    setTimeout(() => document.querySelector('.category-builder-grid .subcategory-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

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
    // The forms submitted by «Сохранить»: after a successful save they become clean, unless the user typed during the
    // request (the facades keep such text in the form, and it is still unsaved).
    this.pendingSaveSnapshot = this.activeForms();
    this.userTypedDuringSave = false;
    this.dispatchSave();
    if (!this.activeSaving()) this.pendingSaveSnapshot = null;
  }

  @HostListener('input')
  @HostListener('change')
  onUserEdit(): void {
    if (this.activeSaving()) this.userTypedDuringSave = true;
  }

  private dispatchSave(): void {
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
        this.messageSettingsFeature.savePhraseDictionary();
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

  /**
   * The number of records shown next to a menu item. Lists load only when their tab opens, so a tab that has not been
   * opened yet (or is loading right now) shows nothing instead of a misleading 0; settings tabs never show a number.
   */
  tabTotal(tab: DictionaryTabKey): number | null {
    if (!COUNTED_TABS.has(tab) || !this.listReady(tab) || this.filteredTabs().has(tab)) return null;
    return this.listCount(tab);
  }

  /** The number of records the last load of a tab returned (for accounts: the server total of the query). */
  private listCount(tab: DictionaryTabKey): number | null {
    switch (tab) {
      case 'categories': return this.categories().length;
      case 'cities': return this.cities().length;
      case 'products': return this.products().length;
      case 'phones': return this.phones().length;
      case 'accounts': return this.botsTotal();
      case 'promo': return this.promoTexts().length;
      case 'managerTexts': return this.managerTexts().length;
      case 'messageDictionary': return this.autoIgnorePhrases().length;
      default: return null;
    }
  }

  toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  /** The forms of the open tab whose edits would be lost by opening another record or tab. */
  private activeForms(): AbstractControl[] {
    switch (this.activeTab()) {
      case 'categories':
      case 'subcategories':
        return [
          ...(this.categoryEditorOpen() ? [this.categoryForm] : []),
          ...(this.subCategoryEditorOpen() ? [this.subCategoryForm] : [])
        ];
      case 'cities': return [this.cityForm];
      case 'products': return [this.productForm];
      case 'phones': return [this.phoneForm];
      case 'accounts': return [this.botForm];
      case 'promo': return [this.promoTextForm];
      case 'managerTexts': return [this.managerTextForm];
      case 'messageDictionary':
      case 'autoresponder': return [this.autoresponderForm];
      case 'settings': return [this.settingsForm];
      case 'gamification': return [this.gamificationForm];
      default: return [];
    }
  }

  /**
   * Edits typed by the user and not saved yet. A save in progress does not count: its result arrives on its own and
   * the editor may move on meanwhile.
   */
  hasUnsavedChanges(): boolean {
    if (this.activeSaving()) return false;
    const phraseDraft = this.activeTab() === 'messageDictionary'
      && (this.autoIgnorePhraseDraft().trim() !== '' || this.editingAutoIgnorePhraseIndex() !== null);
    const auditDraft = this.activeTab() === 'audit' && this.auditSettings()?.form.dirty === true;
    return phraseDraft || auditDraft || this.activeForms().some((form) => form.dirty);
  }

  private confirmLeaveEditor(): boolean {
    return !this.hasUnsavedChanges()
      || window.confirm('Есть несохранённые изменения. Открыть другую запись без сохранения?');
  }

  private markFormsPristine(): void {
    for (const form of this.activeForms()) form.markAsPristine();
  }

  private afterRowPick(): void {
    this.markFormsPristine();
    this.revealEditor();
  }

  pickCity(city: AdminCity): void {
    if (!this.confirmLeaveEditor()) return;
    this.selectCity(city);
    this.afterRowPick();
  }

  pickProduct(product: AdminProduct): void {
    if (!this.confirmLeaveEditor()) return;
    this.selectProduct(product);
    this.afterRowPick();
  }

  pickPhone(phone: OperatorPhone): void {
    if (!this.confirmLeaveEditor()) return;
    this.selectPhone(phone);
    this.afterRowPick();
  }

  /** The second fast-close threshold below the first is raised by the server; an emptied field is not compared. */
  fastClickThresholdInverted(): boolean {
    const value = this.autoresponderForm.getRawValue();
    const below = (second: number | null, first: number | null) => second != null && first != null && second < first;
    return below(value.unansweredFastClickCriticalCount, value.unansweredFastClickWarningCount)
      || below(value.unansweredFastClickCriticalSeconds, value.unansweredFastClickWarningSeconds);
  }

  /** Enter or Space on a focused list row opens its record like a click; keys pressed in controls of the row stay theirs. */
  rowKey(event: Event): boolean {
    if (event.target !== event.currentTarget) return false;
    event.preventDefault();
    return true;
  }

  pickBot(bot: AdminBot): void {
    if (!this.confirmLeaveEditor()) return;
    this.selectBot(bot);
    if (this.botDetailLoadingId() === bot.id || this.selectedId() === bot.id) this.afterRowPick();
  }

  pickPromoText(promoText: AdminPromoText): void {
    if (!this.confirmLeaveEditor()) return;
    this.selectPromoText(promoText);
    this.afterRowPick();
  }

  pickManagerText(managerText: AdminManagerText): void {
    if (!this.confirmLeaveEditor()) return;
    this.selectManagerText(managerText);
    this.afterRowPick();
  }

  /** After a successful save the form holds what the server has: it is clean again. */
  private readonly pristineAfterSave = effect(() => {
    const saving = this.activeSaving();
    const failed = this.activeError() != null;
    untracked(() => {
      if (this.wasSaving && !saving) {
        // Only the save started by «Сохранить» of this editor counts: a promo button assignment or a phrase list
        // reload uses the same busy flag but saves nothing of the form.
        const submitted = this.pendingSaveSnapshot;
        this.pendingSaveSnapshot = null;
        if (submitted && !failed && !this.userTypedDuringSave) {
          for (const form of submitted) form.markAsPristine();
        }
      }
      this.wasSaving = saving;
    });
  });
  private wasSaving = false;
  private pendingSaveSnapshot: AbstractControl[] | null = null;
  private userTypedDuringSave = false;

  /**
   * Below 1180px the list and the editor stand one under the other: after a row is chosen the editor is brought into
   * view, otherwise a tap on a phone seems to do nothing.
   */
  revealEditor(): void {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    if (!window.matchMedia('(max-width: 1180px)').matches) return;
    setTimeout(() => document.querySelector('.dictionary-grid .editor-card')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  /** The platform of a performer product in words («Яндекс», «2ГИС») instead of its code. */
  productPlatformLabel(code?: string | null): string {
    return PRODUCT_PLATFORM_LABELS[code ?? ''] ?? (code || 'площадка не выбрана');
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
      ? 'ссылка банка на оплату'
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

    if (this.activeTab() === 'promo') {
      return this.textsFeature.selectedPromoTextTitle();
    }

    if (this.activeTab() === 'phones') {
      return this.selectedPhone() ? `Телефон #${this.selectedPhone()!.id}` : 'Новый телефон';
    }

    if (this.activeTab() === 'settings') {
      return 'Настройки работы';
    }

    if (this.activeTab() === 'messageDictionary') {
      return 'Правило и список фраз';
    }

    if (this.activeTab() === 'specialistTransfer') {
      return 'Передача текущей работы';
    }

    if (this.activeTab() === 'autoresponder') {
      return 'Сценарии и тексты сообщений';
    }

    if (this.activeTab() === 'autoresponderMonitor') {
      return 'Кабинет мониторинга';
    }

    if (this.activeTab() === 'products' && this.selectedId() != null) {
      const product = this.products().find((item) => item.id === this.selectedId());
      if (product) return product.title;
    }

    return this.selectedId() == null ? 'Новая запись' : `ID ${this.selectedId()}`;
  }

  /** The line above the editor title: whether the form creates a record, edits one or holds settings. */
  editorKicker(): string {
    switch (this.activeTab()) {
      case 'settings':
      case 'autoresponder':
      case 'messageDictionary':
      case 'gamification':
        return 'Настройки';
      case 'managerTexts':
        return this.selectedManagerText() ? 'Тексты менеджера' : 'Выбор менеджера';
      case 'phones':
        return this.selectedPhone() ? 'Редактирование' : 'Новая запись';
      default:
        return this.selectedId() == null ? 'Новая запись' : 'Редактирование';
    }
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

  readonly allPromoTexts = this.textsFeature.allPromoTexts;

  readonly promoTextOptionLabel = this.textsFeature.promoTextOptionLabel.bind(this.textsFeature);

  readonly selectedPromoTextUsage = this.textsFeature.selectedPromoTextUsage.bind(this.textsFeature);

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

  trackFact(_index: number, fact: DictionaryFact): string {
    return fact.label;
  }

  goToBotPage(page: number): void {
    const nextPage = Math.max(0, Math.min(page, this.botTotalPages() - 1));
    if (nextPage === this.botPage() || this.activeLoading() || !this.confirmLeaveEditor()) {
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
    if (!this.confirmLeaveEditor()) {
      (event.target as HTMLSelectElement).value = String(this.botPageSize());
      return;
    }

    this.botPageSize.set(value);
    this.botPage.set(0);
    this.clearSelection();
    this.loadActive();
  }

  private loadActive(): void {
    this.loading.set(false); this.error.set(null);
    if (!this.documentVisible()) {
      // Opened in a background browser tab: the features refuse to read while hidden, so the read waits for the
      // tab to become visible instead of leaving an empty list behind.
      this.dictionaryReloadOnVisible = true;
      return;
    }
    this.markVisited(this.activeTab());
    this.markFiltered(this.activeTab(), this.search().trim() !== '');
    const feature = this.activeFeature();
    if (feature) {
      if (this.activeTab() === 'settings') this.loadContractorPaymentSystemStatus();
      feature.load();
      const botId = this.pendingEntryBotId;
      if (botId != null && this.activeTab() === 'accounts') {
        // A link to one account (?botId=…) opens its card next to the narrowed list.
        this.pendingEntryBotId = null;
        this.accountsFeature.selectBot({ id: botId } as AdminBot);
      }
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
    const query = this.route.snapshot.queryParamMap;
    // «Боты» on the «Города» page links with ?section=bots, a worker's account link with ?botId=…: both mean accounts.
    const accountLink = query.get('section') === 'bots' || Number(query.get('botId')) > 0 ? 'accounts' : null;
    const routeTab = this.route.snapshot.data['initialTab'] ?? query.get('tab') ?? accountLink;
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
