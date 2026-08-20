import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import {
  CabinetApi,
  CabinetProfile,
  ManagerManualPaymentSettings,
  ManualPaymentTaskAccountingTargetOption,
  ManualPaymentTaskResponse,
  ManualPaymentType,
  ManualPaymentTaskStatus
} from '../../core/cabinet.api';
import { CurrentUser, CurrentUserApi } from '../../core/current-user.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';
import { CabinetNavigationComponent } from '../../shared/cabinet-navigation.component';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { SystemHealth, SystemHealthApi } from '../../core/system-health.api';
import { appEnvironment } from '../../core/app-environment';
import { ToastService } from '../../shared/toast.service';
import { businessDateIso } from '../../shared/business-date';
import { normalizeRole, roleLabel } from '../../shared/role-labels';
import { CabinetBarChartComponent } from '../cabinet/cabinet-bar-chart.component';
import {
  cabinetDailyBarChartFrom,
  cabinetPeriodTotalFrom,
  cabinetYearlyLineChartFrom,
  type CabinetBarChart,
  type CabinetLineChart
} from '../cabinet/cabinet-chart.helpers';
import { CabinetLineChartComponent } from '../cabinet/cabinet-line-chart.component';
import { ManagerControlComponent } from '../admin/manager-control/manager-control.component';
import { DailyProgressStripComponent } from '../../shared/daily-progress-strip.component';
import { ManagerReportReviewAccessApi } from '../../core/manager-report-review-access.api';
import { WorkloadShadowApi } from '../../core/workload-shadow.api';
import {
  ContractorPaymentSummary,
  ContractorPaymentsApi
} from '../../core/contractor-payments.api';
import {
  contractorCoverageStartLabel,
  contractorPaymentModeClass as paymentModeClass,
  contractorPaymentModeLabel as paymentModeLabel,
  shouldShowLegacyCabinetMetrics
} from './contractor-payment-coverage';
import { ContractorPaymentMetricHelpComponent } from './contractor-payment-metric-help.component';
import {
  manualPaymentTaskRecommendedTarget,
  manualPaymentTaskSelectedTarget,
  manualPaymentTaskTargetEffect,
  manualPaymentTaskTargetForSnapshot,
  manualPaymentTaskTargetValid
} from '../../shared/manual-payment-task-target';
import { ManualPaymentTaskOperationKeyDraft } from '../../shared/manual-payment-operation-key';

type DashboardAction = {
  label: string;
  description: string;
  icon: string;
  roles: string[];
  routerLink?: string;
  href?: string;
};

type DashboardWarning = {
  title: string;
  message: string;
  detail: string;
  icon: string;
};

type PublicHomeLink = {
  label: string;
  description: string;
  icon: string;
  routerLink: string;
};

type ContractorRewardMetric = {
  label: string;
  value: string;
  percent: number | null;
};

const MONTH_NAMES = [
  'Январь',
  'Февраль',
  'Март',
  'Апрель',
  'Май',
  'Июнь',
  'Июль',
  'Август',
  'Сентябрь',
  'Октябрь',
  'Ноябрь',
  'Декабрь'
];

const DEFAULT_MANUAL_PAYMENT_TYPE: ManualPaymentType = 'MOBILE_BANK';
const DEFAULT_MANUAL_RECIPIENT_NAME = 'Сивохин И.И.';
const DEFAULT_MANUAL_PAYMENT_URL = 'https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR';
const DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL = 'Оплатить через Альфа-Банк';

@Component({
  selector: 'app-home',
  imports: [
    AdminLayoutComponent,
    CabinetNavigationComponent,
    FormsModule,
    LoadErrorCardComponent,
    RouterLink,
    CabinetBarChartComponent,
    CabinetLineChartComponent,
    ManagerControlComponent,
    DailyProgressStripComponent,
    ContractorPaymentMetricHelpComponent
  ],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss'
})
export class HomeComponent {
  private reportReviewToastShown = false;
  private cabinetLoadEpoch = 0;
  readonly roleLabel = roleLabel;
  readonly me = signal<CurrentUser | null>(null);
  readonly health = signal<SystemHealth | null>(null);
  readonly cabinet = signal<CabinetProfile | null>(null);
  readonly cabinetDate = signal(this.todayIso());
  readonly loading = signal(false);
  readonly healthLoading = signal(false);
  readonly cabinetLoading = signal(false);
  readonly manualPaymentSettings = signal<ManagerManualPaymentSettings | null>(null);
  readonly manualPaymentLoading = signal(false);
  readonly manualPaymentSaving = signal(false);
  readonly manualPaymentType = signal<ManualPaymentType>(DEFAULT_MANUAL_PAYMENT_TYPE);
  readonly manualPaymentPhone = signal('');
  readonly manualPaymentRecipient = signal(DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly manualPaymentUrl = signal(DEFAULT_MANUAL_PAYMENT_URL);
  readonly manualPaymentButtonLabel = signal(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly manualPaymentTasks = signal<ManualPaymentTaskResponse[]>([]);
  readonly manualTaskLoading = signal(false);
  readonly manualTaskSaving = signal(false);
  readonly manualTaskMutatingId = signal<number | null>(null);
  readonly manualTaskEditingId = signal<number | null>(null);
  readonly manualTaskPaymentType = signal<ManualPaymentType>(DEFAULT_MANUAL_PAYMENT_TYPE);
  readonly manualTaskPhone = signal('');
  readonly manualTaskRecipient = signal(DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly manualTaskPaymentUrl = signal(DEFAULT_MANUAL_PAYMENT_URL);
  readonly manualTaskPaymentButtonLabel = signal(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly manualTaskAmountRubles = signal('');
  readonly manualTaskComment = signal('');
  readonly manualTaskAccountingTargets = signal<ManualPaymentTaskAccountingTargetOption[]>([]);
  readonly manualTaskAccountingTargetKey = signal('');
  readonly manualTaskAccountingTargetAcknowledged = signal(false);
  readonly manualTaskAccountingTargetsLoading = signal(false);
  readonly manualTaskAccountingTargetError = signal<string | null>(null);
  readonly manualTaskEditPaymentType = signal<ManualPaymentType>(DEFAULT_MANUAL_PAYMENT_TYPE);
  readonly manualTaskEditPhone = signal('');
  readonly manualTaskEditRecipient = signal(DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly manualTaskEditPaymentUrl = signal(DEFAULT_MANUAL_PAYMENT_URL);
  readonly manualTaskEditPaymentButtonLabel = signal(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly manualTaskEditAmountRubles = signal('');
  readonly manualTaskEditComment = signal('');
  readonly manualTaskEditAccountingTargets = signal<ManualPaymentTaskAccountingTargetOption[]>([]);
  readonly manualTaskEditAccountingTargetKey = signal('');
  readonly manualTaskEditAccountingTargetAcknowledged = signal(false);
  readonly manualTaskEditAccountingTargetsLoading = signal(false);
  readonly manualTaskEditAccountingTargetError = signal<string | null>(null);
  private manualTaskAccountingPreviewEpoch = 0;
  private manualTaskEditAccountingPreviewEpoch = 0;
  private readonly manualTaskOperationKey = new ManualPaymentTaskOperationKeyDraft();
  readonly error = signal<DashboardWarning | null>(null);
  readonly healthError = signal<DashboardWarning | null>(null);
  readonly cabinetError = signal<DashboardWarning | null>(null);
  readonly manualPaymentError = signal<DashboardWarning | null>(null);
  readonly manualTaskError = signal<DashboardWarning | null>(null);
  readonly transferPreference = signal<boolean | null>(null);
  readonly transferPreferenceLoading = signal(false);
  readonly transferPreferenceSaving = signal(false);
  readonly transferPreferenceError = signal<string | null>(null);
  readonly contractorPayments = signal<ContractorPaymentSummary[]>([]);
  readonly contractorPaymentsLoading = signal(false);
  readonly contractorPaymentsError = signal<string | null>(null);
  private contractorPaymentsRequested = false;

  readonly actions: DashboardAction[] = [
    {
      label: 'Пользователи',
      description: 'Keycloak, роли и связи команды',
      icon: 'group_add',
      roles: ['ADMIN', 'OWNER'],
      routerLink: '/admin/users'
    },
    {
      label: 'Лиды',
      description: 'Новая рабочая доска',
      icon: 'notifications_active',
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG'],
      routerLink: '/leads'
    },
    {
      label: 'Оператор',
      description: 'Операторы и обработка заявок',
      icon: 'support_agent',
      roles: ['ADMIN', 'OWNER', 'OPERATOR'],
      routerLink: '/operator'
    },
    {
      label: 'Компании',
      description: 'Клиенты, филиалы и рабочие статусы',
      icon: 'business',
      roles: ['ADMIN', 'OWNER', 'MANAGER'],
      routerLink: '/companies'
    },
    {
      label: 'Заказы',
      description: 'Проверки, публикации и оплаты',
      icon: 'inventory_2',
      roles: ['ADMIN', 'OWNER', 'MANAGER'],
      routerLink: '/orders'
    },
    {
      label: 'Специалист',
      description: 'Аккаунты, публикации и задачи',
      icon: 'engineering',
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'],
      routerLink: '/worker'
    },
    {
      label: 'Grafana',
      description: 'Метрики и наблюдаемость',
      icon: 'monitoring',
      roles: ['ADMIN', 'OWNER'],
      href: appEnvironment.metricsBaseUrl
    }
  ];

  readonly publicLinks: PublicHomeLink[] = [
    {
      label: 'Услуги',
      description: 'Что именно оказывает компания',
      icon: 'rate_review',
      routerLink: '/services'
    },
    {
      label: 'Цены',
      description: 'Тарифы и состав работ',
      icon: 'payments',
      routerLink: '/prices'
    },
    {
      label: 'Оплата',
      description: 'Порядок онлайн-оплаты',
      icon: 'credit_card',
      routerLink: '/payment'
    },
    {
      label: 'Возврат',
      description: 'Отмена услуги и возврат',
      icon: 'undo',
      routerLink: '/refund'
    },
    {
      label: 'Оферта',
      description: 'Условия договора',
      icon: 'article',
      routerLink: '/offer'
    },
    {
      label: 'Контакты',
      description: 'Связь и реквизиты',
      icon: 'contacts',
      routerLink: '/contacts'
    },
    {
      label: 'Электронный чек',
      description: 'Согласие на получение чека',
      icon: 'mark_email_read',
      routerLink: '/receipt-consent'
    },
    {
      label: 'Оплатить',
      description: 'Форма оплаты услуги',
      icon: 'shopping_cart',
      routerLink: '/pay'
    }
  ];

  readonly realmRoles = computed(() => {
    return this.auth.tokenParsed()?.['realm_access']?.['roles'] as string[] | undefined ?? [];
  });

  readonly businessRoles = computed(() => {
    const ignored = new Set(['default-roles-otziv', 'offline_access', 'uma_authorization']);
    return this.realmRoles().filter((role) => !ignored.has(role));
  });

  readonly isClientUser = computed(() => {
    return this.currentRoles().some((role) => normalizeRole(role) === 'CLIENT');
  });
  readonly showTransferPreference = computed(() => {
    return this.currentRoles().some((role) => normalizeRole(role) === 'WORKER');
  });
  readonly showContractorPayments = computed(() => this.contractorPayments().length > 0
    || this.currentRoles().some((role) => {
      const normalized = normalizeRole(role);
      return normalized === 'WORKER' || normalized === 'MANAGER';
    }));
  readonly showLegacyCabinetMetrics = computed(() => shouldShowLegacyCabinetMetrics(
    this.showContractorPayments(),
    this.contractorPaymentsError(),
    this.contractorPayments()
  ));

  readonly visibleActions = computed(() => {
    if (!this.auth.authenticated()) {
      return [];
    }

    if (this.reportReviewAccess.state()?.restricted) {
      return [];
    }
    const roles = new Set(this.realmRoles());
    const canSeeAll = roles.has('ADMIN') || roles.has('OWNER');
    return this.actions.filter((action) => canSeeAll || action.roles.some((role) => roles.has(role)));
  });
  readonly warnings = computed<DashboardWarning[]>(() => {
    const authError = this.auth.error();

    return [
      authError ? {
        title: 'Вход не завершился',
        message: 'Не удалось подтвердить сессию. Часть данных может быть недоступна.',
        detail: this.readableErrorDetail(authError),
        icon: 'lock'
      } : null,
      this.error(),
      this.healthError(),
      this.cabinetError(),
      this.manualPaymentError(),
      this.manualTaskError()
    ].filter((warning): warning is DashboardWarning => warning !== null);
  });

  readonly showManualPaymentSettings = computed(() => {
    return false;
  });

  readonly manualPaymentChanged = computed(() => {
    const settings = this.manualPaymentSettings();
    if (!settings) {
      return false;
    }
    return this.manualPaymentType() !== this.normalizeManualPaymentType(settings.manualPaymentType)
      || this.manualPaymentPhone().trim() !== (settings.manualPhone ?? '')
      || this.manualPaymentRecipient().trim() !== this.manualRecipientOrDefault(settings.manualRecipientName)
      || this.manualPaymentUrl().trim() !== this.manualPaymentUrlFromResponse(settings.manualPaymentUrl)
      || this.manualPaymentButtonLabel().trim() !== this.manualPaymentButtonLabelOrDefault(settings.manualPaymentButtonLabel);
  });

  readonly showManualPaymentTasks = computed(() =>
    this.isManagerUser() && !this.reportReviewAccess.state()?.restricted
  );
  readonly showManagerControl = computed(() =>
    this.isManagerUser() && !this.reportReviewAccess.state()?.restricted
  );
  readonly showManagerTeamProgress = computed(() =>
    this.isManagerUser() && !this.reportReviewAccess.state()?.restricted
  );
  readonly selectedManualTaskAccountingTarget = computed(() => manualPaymentTaskSelectedTarget(
    this.manualTaskAccountingTargets(),
    this.manualTaskAccountingTargetKey()
  ));
  readonly selectedManualTaskEditAccountingTarget = computed(() => manualPaymentTaskSelectedTarget(
    this.manualTaskEditAccountingTargets(),
    this.manualTaskEditAccountingTargetKey()
  ));
  readonly manualTaskTargetEffect = manualPaymentTaskTargetEffect;

  readonly canCreateManualTask = computed(() => {
    const hasTarget = this.manualTaskPaymentType() === 'MOBILE_BANK'
      ? Boolean(this.manualTaskPhone().trim()) && Boolean(this.manualTaskRecipient().trim())
      : Boolean(this.manualTaskPaymentUrl().trim()) && Boolean(this.manualTaskRecipient().trim());
    return !this.manualTaskSaving()
      && hasTarget
      && this.manualTaskTargetKopecks() > 0
      && !this.manualTaskAccountingTargetsLoading()
      && manualPaymentTaskTargetValid(
        this.selectedManualTaskAccountingTarget(), this.manualTaskAccountingTargetAcknowledged()
      );
  });

  readonly cabinetMetrics = computed(() => {
    const workerZp = this.cabinet()?.workerZp;
    if (!workerZp) {
      return [];
    }

    return [
      { label: 'За сегодня', value: this.money(workerZp.sum1Day), percent: workerZp.percent1Day },
      { label: 'За неделю', value: this.money(workerZp.sum1Week), percent: workerZp.percent1Week },
      { label: 'За месяц', value: this.money(workerZp.sum1Month), percent: workerZp.percent1Month },
      { label: 'За год', value: this.money(workerZp.sum1Year), percent: workerZp.percent1Year },
      { label: 'Заказов за месяц', value: this.count(workerZp.sumOrders1Month), percent: workerZp.percent1MonthOrders },
      { label: 'За прошлый месяц', value: this.count(workerZp.sumOrders2Month), percent: workerZp.percent2MonthOrders }
    ];
  });

  readonly contractorRewardMetrics = computed<ContractorRewardMetric[]>(() => {
    const workerZp = this.cabinet()?.workerZp;
    if (!workerZp) {
      return [];
    }

    return [
      { label: 'За период', value: this.money(this.contractorRewardPeriodTotal(workerZp.zpPayMapMonth)), percent: null },
      { label: 'За день', value: this.money(workerZp.sum1Day), percent: workerZp.percent1Day },
      { label: 'За неделю', value: this.money(workerZp.sum1Week), percent: workerZp.percent1Week },
      { label: 'За месяц', value: this.money(workerZp.sum1Month), percent: workerZp.percent1Month }
    ];
  });

  constructor(
    readonly auth: AuthService,
    private readonly currentUserApi: CurrentUserApi,
    private readonly systemHealthApi: SystemHealthApi,
    private readonly cabinetApi: CabinetApi,
    readonly reportReviewAccess: ManagerReportReviewAccessApi,
    private readonly workloadShadowApi: WorkloadShadowApi,
    private readonly contractorPaymentsApi: ContractorPaymentsApi,
    private readonly toastService: ToastService,
    private readonly router: Router
  ) {
    if (this.shouldOpenAnalyticsHome()) {
      void this.router.navigate(['/admin/analyse']);
      return;
    }

    if (this.auth.isAuthenticated()) {
      this.loadCurrentUser();
      if (!this.isClientUser()) {
        this.loadCabinet();
        if (this.showTransferPreference()) {
          this.loadTransferPreference();
        }
        if (!this.isClientUser()) {
          this.loadContractorPayments();
        }
        if (this.isManagerUser() && this.showManualPaymentSettings()) {
          this.loadManualPaymentSettings();
        }
        if (this.isManagerUser()) {
          void this.loadReportReviewAccess();
        }
      }
    }

    this.loadHealth();
  }

  login(): void {
    void this.auth.login('/');
  }

  logout(): void {
    void this.auth.logout();
  }

  loadCurrentUser(): void {
    this.loading.set(true);
    this.error.set(null);

    this.currentUserApi.getMe().subscribe({
      next: (user) => {
        this.me.set(user);
        if (this.isClientUser()) {
          this.clearCabinetForClient();
        }
        if (this.showTransferPreference()
          && this.transferPreference() == null
          && !this.transferPreferenceLoading()) {
          this.loadTransferPreference();
        }
        if (!this.isClientUser() && !this.contractorPaymentsRequested) {
          this.loadContractorPayments();
        }
        if (this.isManagerUser()
          && this.showManualPaymentSettings()
          && !this.manualPaymentSettings()
          && !this.manualPaymentLoading()) {
          this.loadManualPaymentSettings();
        }
        if (this.isManagerUser()
          && this.reportReviewAccess.state()
          && !this.reportReviewAccess.state()?.restricted
          && !this.manualPaymentTasks().length
          && !this.manualTaskLoading()) {
          this.loadManualPaymentTasks();
        }
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(this.requestWarning(
          'Профиль не загрузился',
          'Не удалось получить данные текущего пользователя. На странице пока показана информация из активной сессии.',
          err,
          'account_circle'
        ));
        this.loading.set(false);
      }
    });
  }

  loadHealth(showToast = false): void {
    this.healthLoading.set(true);
    this.healthError.set(null);

    this.systemHealthApi.getHealth().subscribe({
      next: (health) => {
        this.health.set(health);
        this.healthLoading.set(false);
        if (showToast) {
          this.showHealthToast(health);
        }
      },
      error: (err) => {
        const warning = this.requestWarning(
          'Сервер требует внимания',
          'Проверка состояния не прошла. Некоторые разделы могут открываться с ошибками.',
          err,
          'monitor_heart'
        );

        this.healthError.set(warning);
        this.healthLoading.set(false);
        if (showToast) {
          this.toastService.error(warning.title, warning.detail);
        }
      }
    });
  }

  loadCabinet(forceRefresh = false): void {
    const requestId = ++this.cabinetLoadEpoch;
    if (this.isClientUser()) {
      this.clearCabinetForClient();
      return;
    }

    this.cabinetLoading.set(true);
    this.cabinetError.set(null);

    this.cabinetApi.getProfile(this.cabinetDate(), { forceRefresh }).subscribe({
      next: (profile) => {
        if (requestId !== this.cabinetLoadEpoch) {
          return;
        }
        this.cabinet.set(profile);
        if (this.showTransferPreference()
          && this.transferPreference() == null
          && !this.transferPreferenceLoading()) {
          this.loadTransferPreference();
        }
        this.cabinetLoading.set(false);
      },
      error: (err) => {
        if (requestId !== this.cabinetLoadEpoch) {
          return;
        }
        this.cabinetError.set(this.requestWarning(
          'Личный кабинет не загрузился',
          'Вознаграждения, графики и профиль временно недоступны для выбранной даты.',
          err,
          'dashboard'
        ));
        this.cabinetLoading.set(false);
      }
    });
  }

  refreshCabinet(): void {
    this.loadCabinet(true);
    if (!this.isClientUser()) {
      this.loadContractorPayments(true);
    }
    if (this.showTransferPreference()) {
      this.loadTransferPreference();
    }
    if (this.isManagerUser() && this.showManualPaymentSettings()) {
      this.loadManualPaymentSettings(true);
    }
    if (this.isManagerUser()) {
      if (!this.reportReviewAccess.state()?.restricted) {
        this.loadManualPaymentTasks(true);
      }
    }
  }

  loadTransferPreference(): void {
    if (!this.showTransferPreference() || this.transferPreferenceLoading()) {
      return;
    }
    this.transferPreferenceLoading.set(true);
    this.transferPreferenceError.set(null);
    this.workloadShadowApi.getMyTransferPreference().subscribe({
      next: (preference) => {
        this.transferPreference.set(preference.acceptsCompanyTransfers);
        this.transferPreferenceLoading.set(false);
      },
      error: (error) => {
        this.transferPreferenceError.set(
          apiErrorDetail(error, 'Не удалось получить настройку получения компаний.')
        );
        this.transferPreferenceLoading.set(false);
      }
    });
  }

  toggleTransferPreference(): void {
    if (this.transferPreferenceSaving() || this.transferPreferenceLoading()) {
      return;
    }
    const previous = this.transferPreference() ?? true;
    const next = !previous;
    this.transferPreferenceSaving.set(true);
    this.transferPreferenceError.set(null);
    this.workloadShadowApi.updateMyTransferPreference(next).subscribe({
      next: (preference) => {
        this.transferPreference.set(preference.acceptsCompanyTransfers);
        this.transferPreferenceSaving.set(false);
        const message = preference.acceptsCompanyTransfers
          ? 'Вы снова участвуете в списке возможных получателей компаний.'
          : 'Вы исключены из списка возможных получателей компаний.';
        this.toastService.success('Настройка сохранена', message);
      },
      error: (error) => {
        const detail = apiErrorDetail(error, 'Не удалось изменить настройку получения компаний.');
        this.transferPreferenceError.set(detail);
        this.transferPreferenceSaving.set(false);
        this.toastService.error('Настройка не сохранена', detail);
      }
    });
  }

  private async loadReportReviewAccess(): Promise<void> {
    try {
      const state = await this.reportReviewAccess.checkIn();
      this.showReportReviewToast(state);
      if (!state.restricted && !this.manualPaymentTasks().length && !this.manualTaskLoading()) {
        this.loadManualPaymentTasks();
      }
    } catch {
      this.reportReviewAccess.clear();
      if (!this.manualPaymentTasks().length && !this.manualTaskLoading()) {
        this.loadManualPaymentTasks();
      }
    }
  }

  private showReportReviewToast(state: {
    pending: boolean;
    restricted: boolean;
    questionCount: number;
    answeredQuestionCount: number;
    message?: string | null;
  }): void {
    if (!state.pending || this.reportReviewToastShown) return;
    this.reportReviewToastShown = true;
    const progress = state.questionCount > 0
      ? ` Прогресс: ${state.answeredQuestionCount} из ${state.questionCount}.`
      : '';
    if (state.restricted) {
      this.toastService.warning(
        'Рабочие разделы временно закрыты',
        `${state.message || 'Завершите проверку отчёта в Telegram.'}${progress}`
      );
      return;
    }
    this.toastService.warning(
      'Необходимо проверить персональный отчёт',
      `${state.message || 'Откройте Telegram и изучите отчёт.'}${progress}`
    );
  }

  selectCabinetDate(date: string): void {
    this.cabinetDate.set(date);
    this.loadCabinet();
  }

  hasActionLink(action: DashboardAction): boolean {
    return Boolean(action.routerLink || action.href);
  }

  imageUrl(imageId?: number | null): string {
    return this.cabinetApi.imageUrl(imageId);
  }

  profileImageUrl(): string | null {
    if (this.isClientUser()) {
      return null;
    }

    const imageId = this.cabinet()?.workerZp?.imageId || this.cabinet()?.user?.image;
    return imageId ? this.imageUrl(imageId) : null;
  }

  loadManualPaymentSettings(forceRefresh = false): void {
    this.manualPaymentLoading.set(true);
    this.manualPaymentError.set(null);

    this.cabinetApi.getManagerManualPaymentSettings({ forceRefresh }).subscribe({
      next: (settings) => {
        this.applyManualPaymentSettings(settings);
        this.manualPaymentLoading.set(false);
      },
      error: (err) => {
        this.manualPaymentSettings.set(null);
        this.manualPaymentError.set(this.requestWarning(
          'Реквизиты ручной оплаты не загрузились',
          'Если менеджеру включена оплата на телефон, проверьте профиль оплаты в T-Bank.',
          err,
          'account_balance_wallet'
        ));
        this.manualPaymentLoading.set(false);
      }
    });
  }

  setManualPaymentType(value: ManualPaymentType): void {
    this.manualPaymentType.set(value);
    if (!this.manualPaymentRecipient().trim()) {
      this.manualPaymentRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
    }
  }

  setManualPaymentPhone(value: string): void {
    this.manualPaymentPhone.set(value ?? '');
  }

  setManualPaymentRecipient(value: string): void {
    this.manualPaymentRecipient.set(value ?? '');
  }

  setManualPaymentUrl(value: string): void {
    this.manualPaymentUrl.set(value ?? '');
  }

  setManualPaymentButtonLabel(value: string): void {
    this.manualPaymentButtonLabel.set(value ?? '');
  }

  saveManualPaymentSettings(): void {
    if (this.manualPaymentSaving() || !this.manualPaymentChanged()) {
      return;
    }

    const manualPaymentType = this.manualPaymentType();
    const manualPhone = this.manualPaymentPhone().trim();
    const manualRecipientName = this.manualPaymentRecipient().trim() || DEFAULT_MANUAL_RECIPIENT_NAME;
    const manualPaymentUrl = this.manualPaymentUrl().trim();
    const manualPaymentButtonLabel = this.manualPaymentButtonLabel().trim() || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL;
    if (manualPaymentType === 'MOBILE_BANK' && (!manualPhone || !manualRecipientName)) {
      this.toastService.error('Заполните реквизиты', 'Для оплаты на телефон нужны номер и получатель.');
      return;
    }
    if (manualPaymentType === 'EXTERNAL_LINK' && (!manualPaymentUrl || !manualRecipientName)) {
      this.toastService.error('Заполните ссылку', 'Для оплаты по ссылке нужны ссылка банка и получатель.');
      return;
    }

    this.manualPaymentSaving.set(true);
    this.manualPaymentError.set(null);
    this.cabinetApi.updateManagerManualPaymentSettings({
      manualPaymentType,
      manualPhone,
      manualRecipientName,
      manualPaymentUrl,
      manualPaymentButtonLabel
    }).subscribe({
      next: (settings) => {
        this.applyManualPaymentSettings(settings);
        this.manualPaymentSaving.set(false);
        this.toastService.success('Реквизиты оплаты сохранены', 'Новые платежные ссылки возьмут обновленные данные.');
      },
      error: (err) => {
        const warning = this.requestWarning(
          'Реквизиты не сохранились',
          'Проверьте ссылку, номер, получателя и текущий платежный профиль менеджера.',
          err,
          'account_balance_wallet'
        );
        this.manualPaymentError.set(warning);
        this.manualPaymentSaving.set(false);
        this.toastService.error(warning.title, warning.detail);
      }
    });
  }

  loadManualPaymentTasks(forceRefresh = false): void {
    this.manualTaskLoading.set(true);
    this.manualTaskError.set(null);

    this.cabinetApi.getManagerManualPaymentTasks({ forceRefresh }).subscribe({
      next: (tasks) => {
        this.manualPaymentTasks.set(tasks ?? []);
        this.manualTaskLoading.set(false);
      },
      error: (err) => {
        this.manualPaymentTasks.set([]);
        this.manualTaskError.set(this.requestWarning(
          'Платежные задания не загрузились',
          'Менеджер может создавать задания для ручной оплаты сверх общего лимита.',
          err,
          'playlist_add_check'
        ));
        this.manualTaskLoading.set(false);
      }
    });
  }

  setManualTaskPaymentType(value: ManualPaymentType): void {
    this.manualTaskPaymentType.set(value);
    if (!this.manualTaskRecipient().trim()) {
      this.manualTaskRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
    }
  }

  setManualTaskPhone(value: string): void {
    this.manualTaskPhone.set(value ?? '');
  }

  setManualTaskRecipient(value: string): void {
    this.manualTaskRecipient.set(value ?? '');
  }

  setManualTaskPaymentUrl(value: string): void {
    this.manualTaskPaymentUrl.set(value ?? '');
  }

  setManualTaskPaymentButtonLabel(value: string): void {
    this.manualTaskPaymentButtonLabel.set(value ?? '');
  }

  setManualTaskAmount(value: string | number | null): void {
    this.manualTaskAmountRubles.set(value == null ? '' : String(value));
    this.loadManualTaskAccountingTargets();
  }

  setManualTaskAccountingTarget(value: string | null): void {
    this.manualTaskAccountingTargetKey.set(value?.trim() ?? '');
    this.manualTaskAccountingTargetAcknowledged.set(false);
  }

  setManualTaskAccountingTargetAcknowledged(value: boolean): void {
    this.manualTaskAccountingTargetAcknowledged.set(Boolean(value));
  }

  setManualTaskComment(value: string): void {
    this.manualTaskComment.set(value ?? '');
  }

  setManualTaskEditPaymentType(value: ManualPaymentType): void {
    this.manualTaskEditPaymentType.set(value);
    if (!this.manualTaskEditRecipient().trim()) {
      this.manualTaskEditRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
    }
  }

  setManualTaskEditPhone(value: string): void {
    this.manualTaskEditPhone.set(value ?? '');
  }

  setManualTaskEditRecipient(value: string): void {
    this.manualTaskEditRecipient.set(value ?? '');
  }

  setManualTaskEditPaymentUrl(value: string): void {
    this.manualTaskEditPaymentUrl.set(value ?? '');
  }

  setManualTaskEditPaymentButtonLabel(value: string): void {
    this.manualTaskEditPaymentButtonLabel.set(value ?? '');
  }

  setManualTaskEditAmount(value: string | number | null): void {
    this.manualTaskEditAmountRubles.set(value == null ? '' : String(value));
    this.loadManualTaskEditAccountingTargets();
  }

  setManualTaskEditAccountingTarget(value: string | null): void {
    this.manualTaskEditAccountingTargetKey.set(value?.trim() ?? '');
    this.manualTaskEditAccountingTargetAcknowledged.set(false);
  }

  setManualTaskEditAccountingTargetAcknowledged(value: boolean): void {
    this.manualTaskEditAccountingTargetAcknowledged.set(Boolean(value));
  }

  setManualTaskEditComment(value: string): void {
    this.manualTaskEditComment.set(value ?? '');
  }

  startManualTaskEdit(task: ManualPaymentTaskResponse): void {
    if (!task?.id || task.status === 'COMPLETED' || task.status === 'CANCELED') {
      return;
    }
    this.manualTaskEditingId.set(task.id);
    this.manualTaskEditPaymentType.set(this.normalizeManualPaymentType(task.manualPaymentType));
    this.manualTaskEditPhone.set(task.manualPhone ?? '');
    this.manualTaskEditRecipient.set(this.manualRecipientOrDefault(task.manualRecipientName));
    this.manualTaskEditPaymentUrl.set(this.manualPaymentUrlFromResponse(task.manualPaymentUrl));
    this.manualTaskEditPaymentButtonLabel.set(this.manualPaymentButtonLabelOrDefault(task.manualPaymentButtonLabel));
    this.manualTaskEditAmountRubles.set(String((task.targetAmountKopecks ?? 0) / 100));
    this.manualTaskEditComment.set(task.comment ?? '');
    this.manualTaskEditAccountingTargetKey.set('');
    this.manualTaskEditAccountingTargetAcknowledged.set(false);
    this.loadManualTaskEditAccountingTargets(task);
  }

  cancelManualTaskEdit(): void {
    this.manualTaskEditingId.set(null);
    this.manualTaskEditAccountingTargets.set([]);
    this.manualTaskEditAccountingTargetKey.set('');
    this.manualTaskEditAccountingTargetAcknowledged.set(false);
    this.manualTaskEditAccountingTargetError.set(null);
    this.manualTaskEditAccountingPreviewEpoch += 1;
  }

  canSaveManualTaskEdit(task: ManualPaymentTaskResponse): boolean {
    const hasTarget = this.manualTaskEditPaymentType() === 'MOBILE_BANK'
      ? Boolean(this.manualTaskEditPhone().trim()) && Boolean(this.manualTaskEditRecipient().trim())
      : Boolean(this.manualTaskEditPaymentUrl().trim()) && Boolean(this.manualTaskEditRecipient().trim());
    return this.manualTaskEditingId() === task.id
      && this.manualTaskMutatingId() !== task.id
      && task.status !== 'COMPLETED'
      && task.status !== 'CANCELED'
      && hasTarget
      && this.manualTaskEditTargetKopecks() >= Math.max(1, task.reservedAmountKopecks ?? 0)
      && !this.manualTaskEditAccountingTargetsLoading()
      && manualPaymentTaskTargetValid(
        this.selectedManualTaskEditAccountingTarget(), this.manualTaskEditAccountingTargetAcknowledged()
      );
  }

  createManualPaymentTask(): void {
    if (!this.canCreateManualTask()) {
      return;
    }
    const accountingTarget = this.selectedManualTaskAccountingTarget();
    if (!accountingTarget) {
      return;
    }

    this.manualTaskSaving.set(true);
    this.manualTaskError.set(null);
    this.cabinetApi.createManagerManualPaymentTask({
      operationKey: this.manualTaskOperationKey.current(),
      manualPaymentType: this.manualTaskPaymentType(),
      manualPhone: this.manualTaskPhone().trim(),
      manualRecipientName: this.manualTaskRecipient().trim() || DEFAULT_MANUAL_RECIPIENT_NAME,
      manualPaymentUrl: this.manualTaskPaymentUrl().trim(),
      manualPaymentButtonLabel: this.manualTaskPaymentButtonLabel().trim() || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
      targetAmountKopecks: this.manualTaskTargetKopecks(),
      comment: this.manualTaskComment().trim() || null,
      accountingTargetKind: accountingTarget.kind,
      accountingTargetProfileId: accountingTarget.profileId ?? null,
      accountingTargetOverrunAcknowledged: this.manualTaskAccountingTargetAcknowledged()
    }).subscribe({
      next: (task) => {
        this.manualPaymentTasks.update((tasks) => [task, ...tasks.filter((item) => item.id !== task.id)]);
        this.manualTaskSaving.set(false);
        this.resetManualPaymentTaskDraft();
        this.toastService.success('Платежное задание создано', 'Новые ссылки сначала будут проверять это задание.');
      },
      error: (err) => {
        const warning = this.requestWarning(
          'Задание не создано',
          'Проверьте ссылку, телефон, получателя и сумму задания.',
          err,
          'playlist_add_check'
        );
        this.manualTaskError.set(warning);
        this.manualTaskSaving.set(false);
        this.toastService.error(warning.title, warning.detail);
      }
    });
  }

  resetManualPaymentTaskDraft(): void {
    if (this.manualTaskSaving()) {
      return;
    }
    this.manualTaskPaymentType.set(DEFAULT_MANUAL_PAYMENT_TYPE);
    this.manualTaskPhone.set('');
    this.manualTaskRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
    this.manualTaskPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
    this.manualTaskPaymentButtonLabel.set(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
    this.manualTaskAmountRubles.set('');
    this.manualTaskComment.set('');
    this.manualTaskAccountingTargets.set([]);
    this.manualTaskAccountingTargetKey.set('');
    this.manualTaskAccountingTargetAcknowledged.set(false);
    this.manualTaskAccountingTargetError.set(null);
    this.manualTaskAccountingPreviewEpoch += 1;
    this.manualTaskOperationKey.rotate();
  }

  updateManualTaskStatus(task: ManualPaymentTaskResponse, status: ManualPaymentTaskStatus): void {
    if (!task?.id || this.manualTaskMutatingId()) {
      return;
    }
    this.manualTaskMutatingId.set(task.id);
    this.cabinetApi.updateManagerManualPaymentTaskStatus(task.id, status).subscribe({
      next: (updated) => {
        this.replaceManualTask(updated);
        this.manualTaskMutatingId.set(null);
      },
      error: (err) => {
        this.manualTaskMutatingId.set(null);
        this.toastService.error('Статус не сохранен', apiErrorDetail(err, 'Не удалось обновить платежное задание'));
      }
    });
  }

  saveManualTaskEdit(task: ManualPaymentTaskResponse): void {
    if (!task?.id || !this.canSaveManualTaskEdit(task)) {
      return;
    }
    const accountingTarget = this.selectedManualTaskEditAccountingTarget();
    if (!accountingTarget) {
      return;
    }
    this.manualTaskMutatingId.set(task.id);
    this.cabinetApi.updateManagerManualPaymentTask(task.id, {
      manualPaymentType: this.manualTaskEditPaymentType(),
      manualPhone: this.manualTaskEditPhone().trim(),
      manualRecipientName: this.manualTaskEditRecipient().trim() || DEFAULT_MANUAL_RECIPIENT_NAME,
      manualPaymentUrl: this.manualTaskEditPaymentUrl().trim(),
      manualPaymentButtonLabel: this.manualTaskEditPaymentButtonLabel().trim() || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
      targetAmountKopecks: this.manualTaskEditTargetKopecks(),
      comment: this.manualTaskEditComment().trim() || null,
      manualPaymentUrlReplacementConfirmed: this.manualTaskEditPaymentType() === 'EXTERNAL_LINK'
        && !Boolean(task.manualPaymentUrl?.trim())
        && Boolean(this.manualTaskEditPaymentUrl().trim()),
      accountingTargetKind: accountingTarget.kind,
      accountingTargetProfileId: accountingTarget.profileId ?? null,
      accountingTargetOverrunAcknowledged: this.manualTaskEditAccountingTargetAcknowledged(),
      expectedGeneration: task.generation ?? null
    }).subscribe({
      next: (updated) => {
        this.replaceManualTask(updated);
        this.manualTaskEditingId.set(null);
        this.manualTaskMutatingId.set(null);
        this.toastService.success('Задание сохранено');
      },
      error: (err) => {
        this.manualTaskMutatingId.set(null);
        this.toastService.error('Задание не сохранено', apiErrorDetail(err, 'Не удалось обновить платежное задание'));
      }
    });
  }

  manualTaskProgressPercent(task: ManualPaymentTaskResponse): number {
    if (!task.targetAmountKopecks) {
      return 0;
    }
    return Math.min(100, Math.round((task.reservedAmountKopecks / task.targetAmountKopecks) * 100));
  }

  manualTaskStatusLabel(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'Активно';
      case 'PAUSED':
        return 'Пауза';
      case 'COMPLETED':
        return 'Выполнено';
      case 'CANCELED':
        return 'Отменено';
      default:
        return status || 'Неизвестно';
    }
  }

  formatKopecks(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    }).format((value ?? 0) / 100)} руб.`;
  }

  isExternalManualTask(task: ManualPaymentTaskResponse): boolean {
    return this.normalizeManualPaymentType(task.manualPaymentType) === 'EXTERNAL_LINK';
  }

  manualTaskTitle(task: ManualPaymentTaskResponse): string {
    return task.manualRecipientName || DEFAULT_MANUAL_RECIPIENT_NAME;
  }

  manualTaskSubtitle(task: ManualPaymentTaskResponse): string {
    const profile = task.paymentProfileName || 'профиль оплаты';
    if (this.isExternalManualTask(task)) {
      return `${this.manualPaymentUrlFromResponse(task.manualPaymentUrl) || 'ссылка не настроена'} · ${profile}`;
    }
    return `${task.manualPhone || 'телефон не указан'} · ${profile}`;
  }

  private currentRoles(): string[] {
    return [
      ...this.realmRoles(),
      ...(this.me()?.realmRoles ?? []),
      ...(this.me()?.authorities ?? []),
      this.cabinet()?.user?.role ?? ''
    ];
  }

  private isManagerUser(): boolean {
    return this.currentRoles().some((role) => normalizeRole(role) === 'MANAGER');
  }

  private applyManualPaymentSettings(settings: ManagerManualPaymentSettings): void {
    this.manualPaymentSettings.set(settings);
    this.manualPaymentType.set(this.normalizeManualPaymentType(settings.manualPaymentType));
    this.manualPaymentPhone.set(settings.manualPhone ?? '');
    this.manualPaymentRecipient.set(this.manualRecipientOrDefault(settings.manualRecipientName));
    this.manualPaymentUrl.set(this.manualPaymentUrlFromResponse(settings.manualPaymentUrl));
    this.manualPaymentButtonLabel.set(this.manualPaymentButtonLabelOrDefault(settings.manualPaymentButtonLabel));
  }

  private replaceManualTask(task: ManualPaymentTaskResponse): void {
    this.manualPaymentTasks.update((tasks) => tasks.map((item) => item.id === task.id ? task : item));
  }

  private manualTaskTargetKopecks(): number {
    const value = Number(this.manualTaskAmountRubles());
    return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : 0;
  }

  private manualTaskEditTargetKopecks(): number {
    const value = Number(this.manualTaskEditAmountRubles());
    return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : 0;
  }

  private loadManualTaskAccountingTargets(): void {
    const amount = this.manualTaskTargetKopecks();
    const previousKey = this.manualTaskAccountingTargetKey();
    const epoch = ++this.manualTaskAccountingPreviewEpoch;
    this.manualTaskAccountingTargetAcknowledged.set(false);
    this.manualTaskAccountingTargetError.set(null);
    if (amount <= 0) {
      this.manualTaskAccountingTargets.set([]);
      this.manualTaskAccountingTargetKey.set('');
      this.manualTaskAccountingTargetsLoading.set(false);
      return;
    }
    this.manualTaskAccountingTargetsLoading.set(true);
    this.cabinetApi.getManagerManualPaymentTaskAccountingTargets(amount).subscribe({
      next: (options) => {
        if (epoch !== this.manualTaskAccountingPreviewEpoch) {
          return;
        }
        const normalized = options ?? [];
        const restored = normalized.find(option => option.key === previousKey)
          ?? manualPaymentTaskRecommendedTarget(normalized);
        this.manualTaskAccountingTargets.set(normalized);
        this.manualTaskAccountingTargetKey.set(restored?.key ?? '');
        this.manualTaskAccountingTargetsLoading.set(false);
      },
      error: (error) => {
        if (epoch !== this.manualTaskAccountingPreviewEpoch) {
          return;
        }
        this.manualTaskAccountingTargets.set([]);
        this.manualTaskAccountingTargetKey.set('');
        this.manualTaskAccountingTargetsLoading.set(false);
        this.manualTaskAccountingTargetError.set(apiErrorDetail(
          error, 'Не удалось рассчитать получателя и возможное превышение.'
        ));
      }
    });
  }

  private loadManualTaskEditAccountingTargets(sourceTask?: ManualPaymentTaskResponse): void {
    const task = sourceTask ?? this.manualPaymentTasks().find(item => item.id === this.manualTaskEditingId());
    const amount = this.manualTaskEditTargetKopecks();
    const previousKey = this.manualTaskEditAccountingTargetKey();
    const epoch = ++this.manualTaskEditAccountingPreviewEpoch;
    this.manualTaskEditAccountingTargetAcknowledged.set(false);
    this.manualTaskEditAccountingTargetError.set(null);
    if (!task || amount <= 0) {
      this.manualTaskEditAccountingTargets.set([]);
      this.manualTaskEditAccountingTargetKey.set('');
      this.manualTaskEditAccountingTargetsLoading.set(false);
      return;
    }
    this.manualTaskEditAccountingTargetsLoading.set(true);
    this.cabinetApi.getManagerManualPaymentTaskAccountingTargets(amount, task.id).subscribe({
      next: (options) => {
        if (epoch !== this.manualTaskEditAccountingPreviewEpoch) {
          return;
        }
        const normalized = options ?? [];
        const restored = normalized.find(option => option.key === previousKey)
          ?? manualPaymentTaskTargetForSnapshot(normalized, task);
        this.manualTaskEditAccountingTargets.set(normalized);
        this.manualTaskEditAccountingTargetKey.set(restored?.key ?? '');
        this.manualTaskEditAccountingTargetsLoading.set(false);
      },
      error: (error) => {
        if (epoch !== this.manualTaskEditAccountingPreviewEpoch) {
          return;
        }
        this.manualTaskEditAccountingTargets.set([]);
        this.manualTaskEditAccountingTargetKey.set('');
        this.manualTaskEditAccountingTargetsLoading.set(false);
        this.manualTaskEditAccountingTargetError.set(apiErrorDetail(
          error, 'Не удалось пересчитать получателя задания.'
        ));
      }
    });
  }

  private normalizeManualPaymentType(value?: string | null): ManualPaymentType {
    return value === 'EXTERNAL_LINK' ? 'EXTERNAL_LINK' : DEFAULT_MANUAL_PAYMENT_TYPE;
  }

  private manualPaymentUrlFromResponse(value?: string | null): string {
    return (value ?? '').trim();
  }

  private manualPaymentButtonLabelOrDefault(value?: string | null): string {
    const clean = (value ?? '').trim();
    return clean || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL;
  }

  private manualRecipientOrDefault(value?: string | null): string {
    const clean = (value ?? '').trim();
    return !clean || clean === DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL ? DEFAULT_MANUAL_RECIPIENT_NAME : clean;
  }

  private clearCabinetForClient(): void {
    this.cabinet.set(null);
    this.cabinetLoading.set(false);
    this.cabinetError.set(null);
    this.manualPaymentSettings.set(null);
    this.manualPaymentTasks.set([]);
    this.manualPaymentLoading.set(false);
    this.manualTaskLoading.set(false);
    this.manualPaymentError.set(null);
    this.manualTaskError.set(null);
  }

  private showHealthToast(health: SystemHealth): void {
    const status = health.status || 'UNKNOWN';
    const message = `Состояние сервера: ${status}`;

    if (status.toUpperCase() === 'UP') {
      this.toastService.success('Сервер работает', message);
      return;
    }

    this.toastService.info('Сервер ответил с предупреждением', message);
  }

  private shouldOpenAnalyticsHome(): boolean {
    return this.auth.isAuthenticated() && this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  }

  dailyChartFrom(map?: string | null): CabinetBarChart {
    return cabinetDailyBarChartFrom(map, this.cabinetDate());
  }

  yearlyLineChartFrom(map?: string | null): CabinetLineChart {
    return cabinetYearlyLineChartFrom(map, { fallbackYear: new Date(this.cabinetDate()).getFullYear() });
  }

  selectedMonthLabel(): string {
    const date = new Date(this.cabinetDate());
    return `Месяц: ${MONTH_NAMES[date.getMonth()] ?? MONTH_NAMES[0]}`;
  }

  tone(percent: number): string {
    if (percent > 25) {
      return 'green';
    }

    if (percent >= 0) {
      return 'blue';
    }

    if (percent > -25) {
      return 'yellow';
    }

    return 'red';
  }

  contractorRewardMetricTone(metric: ContractorRewardMetric): string {
    return metric.percent == null ? 'blue' : this.tone(metric.percent);
  }

  private money(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} руб.`;
  }

  private contractorRewardPeriodTotal(map?: string | null): number {
    return cabinetPeriodTotalFrom(map, {
      fallbackYear: new Date(this.cabinetDate()).getFullYear()
    });
  }

  private count(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} шт.`;
  }

  private todayIso(): string {
    return businessDateIso();
  }

  loadContractorPayments(force = false): void {
    if (this.isClientUser() || this.contractorPaymentsLoading()) {
      return;
    }
    if (this.contractorPaymentsRequested && !force) {
      return;
    }

    this.contractorPaymentsRequested = true;
    this.contractorPaymentsLoading.set(true);
    this.contractorPaymentsError.set(null);
    this.contractorPaymentsApi.getMySummary().subscribe({
      next: (summaries) => {
        this.contractorPayments.set(summaries);
        this.contractorPaymentsLoading.set(false);
      },
      error: (error) => {
        this.contractorPaymentsError.set(apiErrorDetail(
          error,
          'Расчёты по вознаграждениям временно недоступны.'
        ));
        this.contractorPaymentsLoading.set(false);
      }
    });
  }

  contractorPaymentRoleLabel(role: ContractorPaymentSummary['role']): string {
    return role === 'SPECIALIST' ? 'Специалист' : 'Менеджер';
  }

  contractorPaymentModeLabel(summary: ContractorPaymentSummary): string {
    return paymentModeLabel(summary);
  }

  contractorPaymentModeClass(summary: ContractorPaymentSummary): string {
    return paymentModeClass(summary);
  }

  contractorMoney(kopecks?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format((kopecks ?? 0) / 100)} руб.`;
  }

  contractorCoverageStartLabel(summary: ContractorPaymentSummary): string {
    return contractorCoverageStartLabel(summary.trackingStartedAt);
  }

  private requestWarning(title: string, message: string, err: unknown, icon: string): DashboardWarning {
    return {
      title,
      message,
      detail: this.readableErrorDetail(err),
      icon
    };
  }

  private readableErrorDetail(err: unknown): string {
    return apiErrorDetail(err, 'Попробуйте обновить страницу. Если ошибка повторится, проверьте серверную часть.');
  }
}
