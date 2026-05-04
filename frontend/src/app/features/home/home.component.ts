import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { CabinetApi, CabinetProfile } from '../../core/cabinet.api';
import { CurrentUser, CurrentUserApi } from '../../core/current-user.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { CabinetNavigationComponent } from '../../shared/cabinet-navigation.component';
import { SystemHealth, SystemHealthApi } from '../../core/system-health.api';
import { appEnvironment } from '../../core/app-environment';
import { ToastService } from '../../shared/toast.service';
import {
  cabinetDailyBarChartFrom,
  cabinetYearlyLineChartFrom,
  type CabinetBarChart,
  type CabinetLineChart
} from '../cabinet/cabinet-chart.helpers';

type DashboardAction = {
  label: string;
  description: string;
  icon: string;
  roles: string[];
  routerLink?: string;
  href?: string;
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
  imports: [AdminLayoutComponent, CabinetNavigationComponent, FormsModule, RouterLink],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss'
})
export class HomeComponent {
  readonly me = signal<CurrentUser | null>(null);
  readonly health = signal<SystemHealth | null>(null);
  readonly cabinet = signal<CabinetProfile | null>(null);
  readonly cabinetDate = signal(this.todayIso());
  readonly loading = signal(false);
  readonly healthLoading = signal(false);
  readonly cabinetLoading = signal(false);
  readonly error = signal<string | null>(null);
  readonly healthError = signal<string | null>(null);
  readonly cabinetError = signal<string | null>(null);

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

  readonly visibleActions = computed(() => {
    if (!this.auth.authenticated()) {
      return [];
    }

    const roles = new Set(this.realmRoles());
    const canSeeAll = roles.has('ADMIN') || roles.has('OWNER');
    return this.actions.filter((action) => canSeeAll || action.roles.some((role) => roles.has(role)));
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
      this.loadCabinet();
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
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.message ?? 'Request failed');
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
        const message = err?.message ?? 'Health check failed';
        this.healthError.set(message);
        this.healthLoading.set(false);
        if (showToast) {
          this.toastService.error('Backend недоступен', message);
        }
      }
    });
  }

  loadCabinet(forceRefresh = false): void {
    this.cabinetLoading.set(true);
    this.cabinetError.set(null);

    this.cabinetApi.getProfile(this.cabinetDate(), { forceRefresh }).subscribe({
      next: (profile) => {
        this.cabinet.set(profile);
        this.cabinetLoading.set(false);
      },
      error: (err) => {
        this.cabinetError.set(err?.error?.message ?? err?.message ?? 'Cabinet request failed');
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
    const imageId = this.cabinet()?.workerZp?.imageId || this.cabinet()?.user?.image;
    return imageId ? this.imageUrl(imageId) : null;
  }

  private showHealthToast(health: SystemHealth): void {
    const status = health.status || 'UNKNOWN';
    const message = `Actuator health: ${status}`;

    if (status.toUpperCase() === 'UP') {
      this.toastService.success('Backend работает', message);
      return;
    }

    this.toastService.info('Backend ответил', message);
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

  moneyLabel(value: number): string {
    return this.money(value);
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
}
