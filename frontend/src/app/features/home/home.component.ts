import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { CabinetApi, CabinetProfile } from '../../core/cabinet.api';
import { CurrentUser, CurrentUserApi } from '../../core/current-user.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { SystemHealth, SystemHealthApi } from '../../core/system-health.api';
import { appEnvironment } from '../../core/app-environment';
import { ToastService } from '../../shared/toast.service';

type DashboardAction = {
  label: string;
  description: string;
  icon: string;
  roles: string[];
  routerLink?: string;
  href?: string;
};

type ChartPoint = {
  label: string;
  value: number;
  height: number;
};

type BarChart = {
  points: ChartPoint[];
  ticks: string[];
};

type LineChartPoint = {
  label: string;
  value: number;
  x: number;
  y: number;
};

type LineChartSeries = {
  label: string;
  color: string;
  points: string;
  pointsData: LineChartPoint[];
};

type LineChart = {
  series: LineChartSeries[];
  ticks: string[];
  months: string[];
  gridLines: number[];
  plotStart: number;
  plotEnd: number;
  viewBox: string;
};

type ChartScale = {
  max: number;
  ticks: string[];
  tickValues: number[];
};

const MONTH_LABELS = ['Янв', 'Фев', 'Мар', 'Апр', 'Май', 'Июн', 'Июл', 'Авг', 'Сен', 'Окт', 'Ноя', 'Дек'];
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
const YEAR_COLORS = ['#ea3362', '#4a9a86', '#f7a35c', '#6c9bcf', '#9a7bd9', '#1b9c85', '#b28405'];
const LINE_VIEWBOX_WIDTH = 100;
const LINE_VIEWBOX_HEIGHT = 100;
const LINE_CHART_TOP = 7;
const LINE_CHART_BOTTOM = 12;
const BAR_CHART_INTERVALS = 4;
const LINE_CHART_INTERVALS = 6;

@Component({
  selector: 'app-home',
  imports: [AdminLayoutComponent, FormsModule, RouterLink],
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
      label: 'Моя команда',
      description: 'Сотрудники и показатели',
      icon: 'badge',
      roles: ['ADMIN', 'OWNER', 'MANAGER'],
      routerLink: '/admin/team'
    },
    {
      label: 'Специалист',
      description: 'Аккаунты, публикации и задачи',
      icon: 'engineering',
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'],
      routerLink: '/worker'
    },
    {
      label: 'Рейтинг',
      description: 'Рабочие счетчики команды',
      icon: 'leaderboard',
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER', 'OPERATOR', 'MARKETOLOG'],
      routerLink: '/admin/score'
    },
    {
      label: 'Аналитика',
      description: 'Оборот, ЗП и графики',
      icon: 'analytics',
      roles: ['ADMIN', 'OWNER'],
      routerLink: '/admin/analyse'
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

  dailyChartFrom(map?: string | null): BarChart {
    if (!map) {
      return this.emptyBarChart();
    }

    try {
      const parsed = JSON.parse(map) as Record<string, number | string>;
      const date = new Date(this.cabinetDate());
      const daysInMonth = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();
      const points = Array.from({ length: daysInMonth }, (_, index) => {
        const day = index + 1;
        return {
          label: String(day),
          value: this.numberValue(parsed[String(day)])
        };
      });

      return this.barChart(points);
    } catch {
      return this.emptyBarChart();
    }
  }

  yearlyLineChartFrom(map?: string | null): LineChart {
    if (!map) {
      return this.emptyLineChart();
    }

    try {
      const parsed = JSON.parse(map) as Record<string, Record<string, number | string> | number | string>;
      const yearlyData = this.normalizeYearlyMap(parsed);
      const years = Object.keys(yearlyData).sort();
      const allValues = years.flatMap((year) => {
        const monthlyData = yearlyData[year] ?? {};
        return MONTH_LABELS.map((_, index) => this.numberValue(monthlyData[String(index + 1)]));
      });
      const scale = this.buildScale(allValues, LINE_CHART_INTERVALS, true);
      const plotHeight = LINE_VIEWBOX_HEIGHT - LINE_CHART_TOP - LINE_CHART_BOTTOM;
      const yFor = (value: number) => LINE_CHART_TOP + plotHeight - (value / scale.max) * plotHeight;
      const xFor = (index: number) => ((index + 0.5) * LINE_VIEWBOX_WIDTH) / MONTH_LABELS.length;

      const series = years.map((year, index) => {
        const monthlyData = yearlyData[year] ?? {};
        const pointsData = MONTH_LABELS.map((label, monthIndex) => {
          const value = this.numberValue(monthlyData[String(monthIndex + 1)]);
          return {
            label,
            value,
            x: xFor(monthIndex),
            y: yFor(value)
          };
        });

        return {
          label: `Год: ${year}`,
          color: YEAR_COLORS[index % YEAR_COLORS.length],
          points: pointsData.map((point) => `${point.x},${point.y}`).join(' '),
          pointsData
        };
      });

      return {
        series,
        ticks: scale.ticks,
        months: MONTH_LABELS,
        gridLines: scale.tickValues.map((value) => yFor(value)),
        plotStart: 0,
        plotEnd: LINE_VIEWBOX_WIDTH,
        viewBox: `0 0 ${LINE_VIEWBOX_WIDTH} ${LINE_VIEWBOX_HEIGHT}`
      };
    } catch {
      return this.emptyLineChart();
    }
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

  private barChart(points: Array<Omit<ChartPoint, 'height'>>): BarChart {
    const scale = this.buildScale(points.map((point) => point.value), BAR_CHART_INTERVALS);
    return {
      points: points.map((point) => ({
        ...point,
        height: point.value > 0 ? Math.max(4, Math.round((point.value / scale.max) * 100)) : 0
      })),
      ticks: scale.ticks
    };
  }

  private normalizeYearlyMap(
    parsed: Record<string, Record<string, number | string> | number | string>
  ): Record<string, Record<string, number | string>> {
    const yearlyEntries = Object.entries(parsed).filter((entry): entry is [string, Record<string, number | string>] =>
      this.isMonthlyRecord(entry[1])
    );

    if (yearlyEntries.length > 0) {
      return Object.fromEntries(yearlyEntries);
    }

    return {
      [new Date(this.cabinetDate()).getFullYear()]: parsed as Record<string, number | string>
    };
  }

  private isMonthlyRecord(value: unknown): value is Record<string, number | string> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
  }

  private numberValue(value: unknown): number {
    return typeof value === 'number' || typeof value === 'string' ? Number(value) || 0 : 0;
  }

  private buildScale(values: number[], intervals: number, clampToData = false): ChartScale {
    const maxValue = Math.max(...values, 0);
    if (maxValue <= 0) {
      return {
        max: 1,
        ticks: Array.from({ length: intervals + 1 }, () => '0'),
        tickValues: Array.from({ length: intervals + 1 }, () => 0)
      };
    }

    const step = this.niceStep(maxValue / intervals);
    const max = clampToData ? Math.ceil(maxValue / step) * step : step * intervals;
    const tickCount = Math.round(max / step);
    const tickValues = Array.from({ length: tickCount + 1 }, (_, index) => max - step * index);

    return {
      max,
      ticks: tickValues.map((value) => this.formatAxisValue(value)),
      tickValues
    };
  }

  private niceStep(value: number): number {
    const magnitude = 10 ** Math.floor(Math.log10(value));
    const normalized = value / magnitude;
    let niceStep = 10;

    if (normalized <= 1) {
      niceStep = 1;
    } else if (normalized <= 2) {
      niceStep = 2;
    } else if (normalized <= 2.5) {
      niceStep = 2.5;
    } else if (normalized <= 5) {
      niceStep = 5;
    }

    return niceStep * magnitude;
  }

  private formatAxisValue(value: number): string {
    if (Math.abs(value) >= 1000) {
      return `${new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 }).format(value / 1000)}к`;
    }

    return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(value);
  }

  private emptyBarChart(): BarChart {
    return {
      points: [],
      ticks: ['0', '0', '0', '0', '0']
    };
  }

  private emptyLineChart(): LineChart {
    return {
      series: [],
      ticks: ['0', '0', '0', '0', '0'],
      months: MONTH_LABELS,
      gridLines: [],
      plotStart: 0,
      plotEnd: LINE_VIEWBOX_WIDTH,
      viewBox: `0 0 ${LINE_VIEWBOX_WIDTH} ${LINE_VIEWBOX_HEIGHT}`
    };
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
