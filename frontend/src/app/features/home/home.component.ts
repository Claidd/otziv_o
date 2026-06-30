import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import {
  CabinetApi,
  CabinetProfile,
  ManagerManualPaymentSettings,
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
import { normalizeRole, roleLabel } from '../../shared/role-labels';
import { CabinetBarChartComponent } from '../cabinet/cabinet-bar-chart.component';
import {
  cabinetDailyBarChartFrom,
  cabinetYearlyLineChartFrom,
  type CabinetBarChart,
  type CabinetLineChart
} from '../cabinet/cabinet-chart.helpers';
import { CabinetLineChartComponent } from '../cabinet/cabinet-line-chart.component';
import { ManagerControlComponent } from '../admin/manager-control/manager-control.component';

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
    ManagerControlComponent
  ],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss'
})
export class HomeComponent {
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
  readonly manualTaskEditPaymentType = signal<ManualPaymentType>(DEFAULT_MANUAL_PAYMENT_TYPE);
  readonly manualTaskEditPhone = signal('');
  readonly manualTaskEditRecipient = signal(DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly manualTaskEditPaymentUrl = signal(DEFAULT_MANUAL_PAYMENT_URL);
  readonly manualTaskEditPaymentButtonLabel = signal(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly manualTaskEditAmountRubles = signal('');
  readonly manualTaskEditComment = signal('');
  readonly error = signal<DashboardWarning | null>(null);
  readonly healthError = signal<DashboardWarning | null>(null);
  readonly cabinetError = signal<DashboardWarning | null>(null);
  readonly manualPaymentError = signal<DashboardWarning | null>(null);
  readonly manualTaskError = signal<DashboardWarning | null>(null);

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

  readonly visibleActions = computed(() => {
    if (!this.auth.authenticated()) {
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
      || this.manualPaymentUrl().trim() !== this.manualPaymentUrlOrDefault(settings.manualPaymentUrl)
      || this.manualPaymentButtonLabel().trim() !== this.manualPaymentButtonLabelOrDefault(settings.manualPaymentButtonLabel);
  });

  readonly showManualPaymentTasks = computed(() => this.isManagerUser());
  readonly showManagerControl = computed(() => this.isManagerUser());

  readonly canCreateManualTask = computed(() => {
    const hasTarget = this.manualTaskPaymentType() === 'MOBILE_BANK'
      ? Boolean(this.manualTaskPhone().trim()) && Boolean(this.manualTaskRecipient().trim())
      : Boolean(this.manualTaskPaymentUrl().trim()) && Boolean(this.manualTaskRecipient().trim());
    return !this.manualTaskSaving()
      && hasTarget
      && this.manualTaskTargetKopecks() > 0;
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

  constructor(
    readonly auth: AuthService,
    private readonly currentUserApi: CurrentUserApi,
    private readonly systemHealthApi: SystemHealthApi,
    private readonly cabinetApi: CabinetApi,
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
        if (this.isManagerUser() && this.showManualPaymentSettings()) {
          this.loadManualPaymentSettings();
        }
        if (this.isManagerUser()) {
          this.loadManualPaymentTasks();
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
        if (this.isManagerUser()
          && this.showManualPaymentSettings()
          && !this.manualPaymentSettings()
          && !this.manualPaymentLoading()) {
          this.loadManualPaymentSettings();
        }
        if (this.isManagerUser() && !this.manualPaymentTasks().length && !this.manualTaskLoading()) {
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
    if (this.isClientUser()) {
      this.clearCabinetForClient();
      return;
    }

    this.cabinetLoading.set(true);
    this.cabinetError.set(null);

    this.cabinetApi.getProfile(this.cabinetDate(), { forceRefresh }).subscribe({
      next: (profile) => {
        this.cabinet.set(profile);
        this.cabinetLoading.set(false);
      },
      error: (err) => {
        this.cabinetError.set(this.requestWarning(
          'Личный кабинет не загрузился',
          'Зарплата, графики и профиль временно недоступны для выбранной даты.',
          err,
          'dashboard'
        ));
        this.cabinetLoading.set(false);
      }
    });
  }

  refreshCabinet(): void {
    this.loadCabinet(true);
    if (this.isManagerUser() && this.showManualPaymentSettings()) {
      this.loadManualPaymentSettings(true);
    }
    if (this.isManagerUser()) {
      this.loadManualPaymentTasks(true);
    }
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
    if (value === 'EXTERNAL_LINK' && !this.manualPaymentUrl().trim()) {
      this.manualPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
    }
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
    const manualPaymentUrl = this.manualPaymentUrl().trim() || DEFAULT_MANUAL_PAYMENT_URL;
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
    if (value === 'EXTERNAL_LINK' && !this.manualTaskPaymentUrl().trim()) {
      this.manualTaskPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
    }
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
  }

  setManualTaskComment(value: string): void {
    this.manualTaskComment.set(value ?? '');
  }

  setManualTaskEditPaymentType(value: ManualPaymentType): void {
    this.manualTaskEditPaymentType.set(value);
    if (value === 'EXTERNAL_LINK' && !this.manualTaskEditPaymentUrl().trim()) {
      this.manualTaskEditPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
    }
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
    this.manualTaskEditPaymentUrl.set(this.manualPaymentUrlOrDefault(task.manualPaymentUrl));
    this.manualTaskEditPaymentButtonLabel.set(this.manualPaymentButtonLabelOrDefault(task.manualPaymentButtonLabel));
    this.manualTaskEditAmountRubles.set(String((task.targetAmountKopecks ?? 0) / 100));
    this.manualTaskEditComment.set(task.comment ?? '');
  }

  cancelManualTaskEdit(): void {
    this.manualTaskEditingId.set(null);
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
      && this.manualTaskEditTargetKopecks() >= Math.max(1, task.reservedAmountKopecks ?? 0);
  }

  createManualPaymentTask(): void {
    if (!this.canCreateManualTask()) {
      return;
    }

    this.manualTaskSaving.set(true);
    this.manualTaskError.set(null);
    this.cabinetApi.createManagerManualPaymentTask({
      manualPaymentType: this.manualTaskPaymentType(),
      manualPhone: this.manualTaskPhone().trim(),
      manualRecipientName: this.manualTaskRecipient().trim() || DEFAULT_MANUAL_RECIPIENT_NAME,
      manualPaymentUrl: this.manualTaskPaymentUrl().trim() || DEFAULT_MANUAL_PAYMENT_URL,
      manualPaymentButtonLabel: this.manualTaskPaymentButtonLabel().trim() || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
      targetAmountKopecks: this.manualTaskTargetKopecks(),
      comment: this.manualTaskComment().trim() || null
    }).subscribe({
      next: (task) => {
        this.manualPaymentTasks.update((tasks) => [task, ...tasks]);
        this.manualTaskPaymentType.set(DEFAULT_MANUAL_PAYMENT_TYPE);
        this.manualTaskPhone.set('');
        this.manualTaskRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
        this.manualTaskPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
        this.manualTaskPaymentButtonLabel.set(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
        this.manualTaskAmountRubles.set('');
        this.manualTaskComment.set('');
        this.manualTaskSaving.set(false);
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
    this.manualTaskMutatingId.set(task.id);
    this.cabinetApi.updateManagerManualPaymentTask(task.id, {
      manualPaymentType: this.manualTaskEditPaymentType(),
      manualPhone: this.manualTaskEditPhone().trim(),
      manualRecipientName: this.manualTaskEditRecipient().trim() || DEFAULT_MANUAL_RECIPIENT_NAME,
      manualPaymentUrl: this.manualTaskEditPaymentUrl().trim() || DEFAULT_MANUAL_PAYMENT_URL,
      manualPaymentButtonLabel: this.manualTaskEditPaymentButtonLabel().trim() || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
      targetAmountKopecks: this.manualTaskEditTargetKopecks(),
      comment: this.manualTaskEditComment().trim() || null
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
      return `${this.manualPaymentUrlOrDefault(task.manualPaymentUrl)} · ${profile}`;
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
    this.manualPaymentUrl.set(this.manualPaymentUrlOrDefault(settings.manualPaymentUrl));
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

  private normalizeManualPaymentType(value?: string | null): ManualPaymentType {
    return value === 'EXTERNAL_LINK' ? 'EXTERNAL_LINK' : DEFAULT_MANUAL_PAYMENT_TYPE;
  }

  private manualPaymentUrlOrDefault(value?: string | null): string {
    const clean = (value ?? '').trim();
    return clean || DEFAULT_MANUAL_PAYMENT_URL;
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

  private money(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} руб.`;
  }

  private count(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} шт.`;
  }

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
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
