import { Component, computed, effect, EventEmitter, inject, Input, Output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { appEnvironment } from '../core/app-environment';
import { AuthService } from '../core/auth.service';
import { CabinetApi, ManagerPerformanceScore } from '../core/cabinet.api';
import { CABINET_HOME_LINK, CABINET_SECTION_LINKS } from './cabinet-navigation';
import { PersonalRemindersComponent } from './personal-reminders.component';
import { normalizeRole, roleLabel } from './role-labels';
import { requestWorkerCurrentSectionOpen } from './worker-entry-navigation';

type ThemeMode = 'light' | 'dark';

type PerformanceRow = {
  key: string;
  label: string;
  value: string;
  hint: string;
};

type PerformanceFactor = {
  key: string;
  label: string;
  weight: number;
  score: number;
  hint: string;
};

type ShellLink = {
  label: string;
  icon: string;
  active: string;
  roles: string[];
  adminOnly?: boolean;
  exactRoleOnly?: boolean;
  routerLink?: string;
  href?: string;
  openInNewTab?: boolean;
};

@Component({
  selector: 'app-admin-layout',
  imports: [PersonalRemindersComponent, RouterLink],
  templateUrl: './admin-layout.component.html'
})
export class AdminLayoutComponent {
  private readonly auth = inject(AuthService);
  private readonly cabinetApi = inject(CabinetApi);
  private readonly themeStorageKey = 'otziv-theme';
  private readonly defaultBackendImageId = 1;
  private loadedHeaderProfileFor: string | null = null;

  @Input() title = 'Админка';
  @Input() active = '';
  @Input() hideSidebarBeforeLogin = true;
  @Input() rightPanelMode: 'default' | 'custom' = 'default';
  @Input() profileImageUrl: string | null = null;
  @Input() profileImageAlt = 'Фото профиля';
  @Input() managerPerformance: ManagerPerformanceScore | null = null;
  @Output() readonly activeLinkClicked = new EventEmitter<string>();

  readonly brandLogoUrl = '/assets/images/logo-o.png';
  readonly authenticated = this.auth.authenticated;
  readonly theme = signal<ThemeMode>(this.getInitialTheme());
  readonly headerProfileFallbackUrl = signal<string | null>(null);
  readonly activePerformanceTip = signal<string | null>(null);

  readonly headerLinks: ShellLink[] = [
    { label: 'Главная', icon: 'home', active: 'dashboard', routerLink: '/', roles: [] },
    { label: 'Лиды', icon: 'notifications_active', active: 'leads', routerLink: '/leads', roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG'] },
    { label: 'Компании', icon: 'business', active: 'companies', routerLink: '/companies', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Заказы', icon: 'inventory_2', active: 'orders', routerLink: '/orders', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Специалист', icon: 'engineering', active: 'worker', routerLink: '/worker', roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] },
    { label: 'Исполнитель', icon: 'assignment_ind', active: 'performer', routerLink: '/performer', roles: ['ADMIN', 'OWNER', 'MANAGER', 'PERFORMER'] },
    { label: 'Оператор', icon: 'support_agent', active: 'operator', routerLink: '/operator', roles: ['ADMIN', 'OWNER', 'OPERATOR'] },
    CABINET_HOME_LINK
  ];

  readonly sidebarLinks: ShellLink[] = [
    CABINET_HOME_LINK,
    { label: 'Лиды', icon: 'notifications_active', active: 'leads', routerLink: '/leads', roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG'] },
    { label: 'Компании', icon: 'business', active: 'companies', routerLink: '/companies', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Заказы', icon: 'inventory_2', active: 'orders', routerLink: '/orders', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Специалист', icon: 'engineering', active: 'worker', routerLink: '/worker', roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] },
    { label: 'Исполнитель', icon: 'assignment_ind', active: 'performer', routerLink: '/performer', roles: ['ADMIN', 'OWNER', 'MANAGER', 'PERFORMER'] },
    { label: 'Оператор', icon: 'support_agent', active: 'operator', routerLink: '/operator', roles: ['ADMIN', 'OWNER', 'OPERATOR'] },
    ...CABINET_SECTION_LINKS,
    { label: 'Обучение', icon: 'school', active: 'training', routerLink: '/training', roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] },
    { label: 'Архив', icon: 'archive', active: 'manager-archive', routerLink: '/manager/archive', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Города', icon: 'location_city', active: 'city-stats', routerLink: '/admin/cities', roles: ['ADMIN', 'OWNER'] },
    { label: 'Архиватор', icon: 'inventory_2', active: 'archive-admin', routerLink: '/admin/archive', roles: ['ADMIN', 'OWNER'] },
    { label: 'Контроль', icon: 'fact_check', active: 'manager-control', routerLink: '/admin/manager-control', roles: ['ADMIN', 'OWNER'] },
    { label: 'Исполнители', icon: 'assignment_ind', active: 'performers', routerLink: '/admin/performers', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'T-Bank', icon: 'account_balance_wallet', active: 'tbank-payments', routerLink: '/admin/tbank-payments', roles: ['ADMIN', 'OWNER'] },
    { label: 'Общие счета', icon: 'receipt_long', active: 'common-billing', routerLink: '/admin/common-billing', roles: ['ADMIN', 'OWNER'] },
    { label: 'AI-помощник', icon: 'auto_awesome', active: 'reputation-ai', routerLink: '/admin/reputation-ai', roles: ['ADMIN', 'OWNER'] },
    { label: 'Справочники', icon: 'tune', active: 'dictionaries', routerLink: '/admin/dictionaries', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Пользователи', icon: 'group_add', active: 'users', routerLink: '/admin/users', roles: ['ADMIN', 'OWNER'] },
    { label: 'Новый пользователь', icon: 'person_add', active: 'create-user', routerLink: '/admin/users/new', roles: ['ADMIN', 'OWNER'] },
    { label: 'Миграция', icon: 'sync', active: 'migration', routerLink: '/legacy-migration', roles: ['ADMIN', 'OWNER'] },
    { label: 'Метрики', icon: 'desktop_windows', active: 'metrics', href: appEnvironment.metricsBaseUrl, openInNewTab: true, roles: ['ADMIN', 'OWNER'] },
    { label: 'Выход', icon: 'logout', active: 'logout', roles: [] }
  ];

  readonly visibleHeaderLinks = computed(() => {
    if (!this.authenticated()) {
      return [];
    }

    return this.headerLinks.filter((link) => this.canSee(link));
  });

  readonly visibleSidebarLinks = computed(() => {
    if (!this.displaySidebar()) {
      return [];
    }

    return this.sidebarLinks.filter((link) => this.canSee(link));
  });

  readonly username = computed(() => {
    const profileUsername = this.auth.profile()?.username;
    const tokenUsername = this.token()?.preferred_username;

    return profileUsername || tokenUsername || 'user';
  });

  readonly primaryRole = computed(() => {
    const ignoredRoles = new Set(['default-roles-otziv', 'offline_access', 'uma_authorization']);
    return roleLabel(this.realmRoles().find((role) => !ignoredRoles.has(role)) ?? 'USER');
  });

  readonly initials = computed(() => {
    const value = this.username().trim();
    return value ? value.slice(0, 1).toUpperCase() : 'O';
  });

  constructor() {
    this.applyTheme(this.theme());
    effect(() => {
      if (!this.authenticated()) {
        this.loadedHeaderProfileFor = null;
        this.headerProfileFallbackUrl.set(null);
        return;
      }

      if (this.isClientUser()) {
        this.loadedHeaderProfileFor = null;
        this.headerProfileFallbackUrl.set(null);
        return;
      }

      this.loadHeaderProfile(this.username());
    });
  }

  displaySidebar(): boolean {
    return !this.hideSidebarBeforeLogin || this.authenticated();
  }

  isActive(link: ShellLink): boolean {
    return this.active === link.active;
  }

  routerLinkFor(link: ShellLink): string | undefined {
    if (link.label === 'Личный кабинет' && this.hasAdminAnalyticsHome()) {
      return '/admin/analyse';
    }

    return link.routerLink;
  }

  handleRouterLinkClick(link: ShellLink): void {
    if (link.active === 'worker' && !this.isActive(link)) {
      requestWorkerCurrentSectionOpen();
    }

    if (this.isActive(link)) {
      this.activeLinkClicked.emit(link.active);
    }
  }

  login(): void {
    void this.auth.login(window.location.pathname || '/');
  }

  logout(): void {
    void this.auth.logout();
  }

  headerProfileImageUrl(): string | null {
    return this.profileImageUrl || this.headerProfileFallbackUrl() || this.brandLogoUrl;
  }

  headerProfileImageAlt(): string {
    return this.profileImageUrl || this.headerProfileFallbackUrl() ? this.profileImageAlt : 'Компания О!';
  }

  setTheme(theme: ThemeMode): void {
    this.theme.set(theme);
    this.applyTheme(theme);
    localStorage.setItem(this.themeStorageKey, theme);
  }

  private canSee(link: ShellLink): boolean {
    if (this.isClientUser() && link.label === CABINET_HOME_LINK.label) {
      return false;
    }

    const requiredRoles = link.roles;
    if (requiredRoles.length === 0) {
      return true;
    }

    const roles = new Set(this.realmRoles());

    if (link.adminOnly) {
      return roles.has('ADMIN');
    }

    if (link.exactRoleOnly) {
      return requiredRoles.some((role) => roles.has(role));
    }

    if (roles.has('ADMIN') || roles.has('OWNER')) {
      return true;
    }

    return requiredRoles.some((role) => roles.has(role));
  }

  private hasAdminAnalyticsHome(): boolean {
    const roles = new Set(this.realmRoles());
    return roles.has('ADMIN') || roles.has('OWNER');
  }

  canOpenWhatsAppBinding(): boolean {
    return this.realmRoles().some((role) => normalizeRole(role) === 'MANAGER');
  }

  performanceTone(score: number | null | undefined): string {
    const value = score ?? 0;
    if (value >= 90) {
      return 'excellent';
    }
    if (value >= 75) {
      return 'good';
    }
    if (value >= 55) {
      return 'warning';
    }
    return 'risk';
  }

  workloadLevelLabel(value: string | null | undefined): string {
    switch ((value ?? '').toUpperCase()) {
      case 'LOW':
        return 'низкая';
      case 'HIGH':
        return 'высокая';
      case 'EXTREME':
        return 'предельная';
      case 'NORMAL':
        return 'нормальная';
      default:
        return 'нормальная';
    }
  }

  managerPerformanceRows(performance: ManagerPerformanceScore | null): PerformanceRow[] {
    if (!performance) {
      return [];
    }

    return [
      {
        key: 'problem-sla-rate',
        label: 'В срок проблем',
        value: this.percent(performance.problemSlaRate),
        hint: 'Доля замечаний, которые уже обработаны или пока идут без нарушения норматива 8 часов.'
      },
      {
        key: 'client-sla-rate',
        label: 'В срок клиентов',
        value: this.percent(performance.clientSlaRate),
        hint: 'Доля клиентских сообщений, на которые ответили или пока отвечают без нарушения норматива 30 минут.'
      },
      {
        key: 'overdue-rate',
        label: 'Просрочки',
        value: `${this.percent(performance.overdueRate)} · ${this.decimal(performance.avgDailyOverdue)} в день`,
        hint: 'Процент просроченных заказов и среднее число просрочек в день.'
      },
      {
        key: 'workload',
        label: 'Заказы / спец.',
        value: `${this.amount(performance.workloadOrder)} / ${this.amount(performance.workloadWorker)}`,
        hint: 'Текущая нагрузка менеджера: рабочие заказы и задачи специалистов.'
      },
      {
        key: 'client-replies',
        label: 'Ответы',
        value: performance.clientReplyMedianMinutes > 0
          ? `${this.decimal(performance.clientReplyMedianMinutes)} / ${this.decimal(performance.clientReplyP90Minutes)} мин.`
          : '-',
        hint: 'Медианное и 90-процентильное время ответа клиентам в минутах.'
      },
      {
        key: 'backlog',
        label: 'Хвосты',
        value: this.amount(performance.backlogCount),
        hint: 'Количество открытых или повторяющихся незакрытых проблем.'
      }
    ];
  }

  managerPerformanceFactors(performance: ManagerPerformanceScore | null): PerformanceFactor[] {
    if (!performance) {
      return [];
    }

    return [
      {
        key: 'problem-speed',
        label: 'Проблемы',
        weight: 25,
        score: performance.problemSpeedScore,
        hint: 'Скорость решения замечаний из дневного контроля. Открытые задачи не штрафуются жестко, пока они еще внутри SLA 8 часов.'
      },
      {
        key: 'client-response',
        label: 'Клиенты',
        weight: 20,
        score: performance.clientResponseScore,
        hint: 'Скорость ответа клиентам. Открытые сообщения считаются по текущему времени и штрафуются по мере приближения или выхода за норматив 30 минут.'
      },
      {
        key: 'overdue-control',
        label: 'Просрочки',
        weight: 20,
        score: performance.overdueControlScore,
        hint: 'Доля просроченных заказов в рабочей базе и возраст просрочек.'
      },
      {
        key: 'specialist-risk',
        label: 'Спец. и риски',
        weight: 15,
        score: performance.specialistRiskScore,
        hint: `Проблемы специалистов считаются по SLA 8 часов, риски по SLA 2 часа. Качество обработки рисков: ${performance.riskQualityScore}/100.`
      },
      {
        key: 'control-discipline',
        label: 'Контроль',
        weight: 10,
        score: performance.controlDisciplineScore,
        hint: 'Принятие контроля, закрытие дня и отсутствие формального быстрого прокликивания.'
      },
      {
        key: 'stability',
        label: 'Стабильность',
        weight: 10,
        score: performance.stabilityScore,
        hint: 'Меньше повторных проблем и отложенных задач означает более высокий балл.'
      }
    ];
  }

  performanceRowTipKey(row: PerformanceRow): string {
    return `row-${row.key}`;
  }

  performanceFactorTipKey(factor: PerformanceFactor): string {
    return `factor-${factor.key}`;
  }

  togglePerformanceRowTip(event: MouseEvent, row: PerformanceRow): void {
    event.stopPropagation();
    const key = this.performanceRowTipKey(row);
    this.activePerformanceTip.set(this.activePerformanceTip() === key ? null : key);
  }

  togglePerformanceFactorTip(event: MouseEvent, factor: PerformanceFactor): void {
    event.stopPropagation();
    const key = this.performanceFactorTipKey(factor);
    this.activePerformanceTip.set(this.activePerformanceTip() === key ? null : key);
  }

  activePerformanceHint(): string | null {
    const activeKey = this.activePerformanceTip();
    if (!activeKey) {
      return null;
    }

    const row = this.managerPerformanceRows(this.managerPerformance)
      .find((item) => this.performanceRowTipKey(item) === activeKey);
    if (row) {
      return row.hint;
    }

    return this.managerPerformanceFactors(this.managerPerformance)
      .find((item) => this.performanceFactorTipKey(item) === activeKey)
      ?.hint ?? null;
  }

  private isClientUser(): boolean {
    return this.realmRoles().some((role) => normalizeRole(role) === 'CLIENT');
  }

  private percent(value: number | null | undefined): string {
    return `${this.decimal(value ?? 0)}%`;
  }

  private decimal(value: number | null | undefined): string {
    const safeValue = value ?? 0;
    return Number.isInteger(safeValue) ? String(safeValue) : safeValue.toFixed(1);
  }

  private amount(value: number | null | undefined): string {
    return String(value ?? 0);
  }

  private loadHeaderProfile(username: string): void {
    if (!username || this.loadedHeaderProfileFor === username) {
      return;
    }

    this.loadedHeaderProfileFor = username;
    this.cabinetApi.getProfile(undefined, { skipAuthRedirectOn401: true }).subscribe({
      next: (profile) => {
        const imageId = this.customProfileImageId(profile.workerZp?.imageId, profile.user?.image);
        this.headerProfileFallbackUrl.set(imageId ? this.cabinetApi.imageUrl(imageId) : null);
      },
      error: () => {
        this.headerProfileFallbackUrl.set(null);
      }
    });
  }

  private realmRoles(): string[] {
    return this.token()?.realm_access?.roles ?? [];
  }

  private customProfileImageId(...imageIds: Array<number | null | undefined>): number | null {
    return imageIds.find((imageId): imageId is number => Boolean(imageId && imageId !== this.defaultBackendImageId)) ?? null;
  }

  private token(): { preferred_username?: string; realm_access?: { roles?: string[] } } | undefined {
    return this.auth.tokenParsed() as { preferred_username?: string; realm_access?: { roles?: string[] } } | undefined;
  }

  private getInitialTheme(): ThemeMode {
    const savedTheme = localStorage.getItem(this.themeStorageKey);

    if (savedTheme === 'light' || savedTheme === 'dark') {
      return savedTheme;
    }

    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  private applyTheme(theme: ThemeMode): void {
    document.body.classList.toggle('otziv-dark-theme', theme === 'dark');
  }
}
