import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { CabinetApi, CabinetProfile } from '../../core/cabinet.api';
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

@Component({
  selector: 'app-home',
  imports: [
    AdminLayoutComponent,
    CabinetNavigationComponent,
    FormsModule,
    LoadErrorCardComponent,
    RouterLink,
    CabinetBarChartComponent,
    CabinetLineChartComponent
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
  readonly error = signal<DashboardWarning | null>(null);
  readonly healthError = signal<DashboardWarning | null>(null);
  readonly cabinetError = signal<DashboardWarning | null>(null);

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
      label: 'Менеджер',
      description: 'Компании и заказы',
      icon: 'groups',
      roles: ['ADMIN', 'OWNER', 'MANAGER'],
      routerLink: '/manager'
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
      this.cabinetError()
    ].filter((warning): warning is DashboardWarning => warning !== null);
  });

  readonly cabinetMetrics = computed(() => {
    const workerZp = this.cabinet()?.workerZp;
    if (!workerZp) {
      return [];
    }

    return [
      { label: 'За вчера', value: this.money(workerZp.sum1Day), percent: workerZp.percent1Day },
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
  }

  selectCabinetDate(date: string): void {
    this.cabinetDate.set(date);
    this.loadCabinet(true);
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

  private currentRoles(): string[] {
    return [
      ...this.realmRoles(),
      ...(this.me()?.realmRoles ?? []),
      ...(this.me()?.authorities ?? []),
      this.cabinet()?.user?.role ?? ''
    ];
  }

  private clearCabinetForClient(): void {
    this.cabinet.set(null);
    this.cabinetLoading.set(false);
    this.cabinetError.set(null);
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
