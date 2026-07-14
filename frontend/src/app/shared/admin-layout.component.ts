import { Component, computed, DestroyRef, effect, EventEmitter, HostListener, inject, Input, Output, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { CabinetApi, ManagerPerformanceScore } from '../core/cabinet.api';
import {
  APP_LOGOUT_LINK,
  APP_NAVIGATION_LINKS,
  APP_PRIMARY_NAVIGATION,
  appNavigationGroupForUrl,
  appNavigationLinksForGroup,
  appNavigationRouteForGroup,
  isAppNavigationGroupRootUrl,
  type AppNavigationLink
} from './app-navigation';
import { MobileBottomNavComponent, type MobileBottomNavRequest } from './mobile/mobile-bottom-nav.component';
import { MobileNavIntentService } from './mobile/mobile-nav-intent.service';
import { MobileStatusSheetComponent } from './mobile/mobile-status-sheet.component';
import type { MobileStatusItem } from './mobile/mobile-status-slider.component';
import { MobileViewportService } from './mobile/mobile-viewport.service';
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

type ShellLink = AppNavigationLink;

const ALL_SHELL_LINKS = [...APP_PRIMARY_NAVIGATION, ...APP_NAVIGATION_LINKS];

function shellLinksById(ids: string[]): ShellLink[] {
  return ids.map((id) => {
    const link = ALL_SHELL_LINKS.find((item) => item.id === id);
    if (!link) {
      throw new Error(`Unknown navigation link: ${id}`);
    }
    return link;
  });
}

@Component({
  selector: 'app-admin-layout',
  imports: [MobileBottomNavComponent, MobileStatusSheetComponent, PersonalRemindersComponent, RouterLink],
  templateUrl: './admin-layout.component.html'
})
export class AdminLayoutComponent {
  private readonly auth = inject(AuthService);
  private readonly cabinetApi = inject(CabinetApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);
  private readonly mobileNavIntent = inject(MobileNavIntentService);
  private readonly themeStorageKey = 'otziv-theme';
  private readonly defaultBackendImageId = 1;
  private readonly activeState = signal('');
  private activeValue = '';
  private loadedHeaderProfileFor: string | null = null;

  @Input() title = 'Админка';
  @Input()
  set active(value: string) {
    this.activeValue = value ?? '';
    this.activeState.set(this.activeValue);
  }
  get active(): string {
    return this.activeValue;
  }
  @Input() hideSidebarBeforeLogin = true;
  @Input() rightPanelMode: 'default' | 'custom' = 'default';
  @Input() profileImageUrl: string | null = null;
  @Input() profileImageAlt = 'Фото профиля';
  @Input() managerPerformance: ManagerPerformanceScore | null = null;
  @Output() readonly activeLinkClicked = new EventEmitter<string>();

  readonly brandLogoUrl = '/assets/images/logo-o.png';
  readonly authenticated = this.auth.authenticated;
  readonly viewport = inject(MobileViewportService);
  readonly theme = signal<ThemeMode>(this.getInitialTheme());
  readonly headerProfileFallbackUrl = signal<string | null>(null);
  readonly activePerformanceTip = signal<string | null>(null);
  readonly currentUrl = signal(this.router.url);
  readonly mobileMenuOpen = signal(false);
  readonly mobileSectionPickerOpen = signal(false);

  readonly headerLinks: readonly ShellLink[] = shellLinksById([
    'home', 'leads', 'companies', 'orders', 'worker', 'performer', 'operator', 'personal-cabinet'
  ]);
  readonly sidebarLinks: readonly ShellLink[] = [
    ...shellLinksById([
      'personal-cabinet', 'leads', 'companies', 'orders', 'worker', 'performer', 'operator',
      'team', 'score', 'manager-control-self', 'achievements', 'gamification-rewards', 'analytics', 'training',
      'company-archive', 'cities', 'archive-admin', 'manager-control', 'performers-admin',
      'tbank', 'common-billing', 'reputation-ai', 'dictionaries', 'users', 'new-user',
      'migration', 'metrics'
    ]),
    APP_LOGOUT_LINK
  ];

  readonly visibleHeaderLinks = computed(() => {
    if (!this.authenticated()) {
      return [];
    }

    return this.headerLinks
      .filter((link) => this.canSee(link))
      .filter((link) => !(link.id === 'worker' && this.isPerformerOnly()));
  });

  readonly visibleSidebarLinks = computed(() => {
    if (!this.displaySidebar()) {
      return [];
    }

    return this.sidebarLinks
      .filter((link) => this.canSee(link))
      .filter((link) => !(link.id === 'worker' && this.isPerformerOnly()));
  });

  readonly showMobileStaffShell = computed(() => this.authenticated() && !this.isClientUser());

  readonly mobilePrimaryLinks = computed(() => {
    if (!this.showMobileStaffShell()) {
      return [];
    }
    return APP_PRIMARY_NAVIGATION.filter((link) => this.canSee(link));
  });

  readonly mobileMenuLinks = computed(() => {
    if (!this.showMobileStaffShell()) {
      return [];
    }
    return APP_NAVIGATION_LINKS.filter((link) => this.canSee(link))
      .filter((link) => !(link.id === 'worker' && this.isPerformerOnly()));
  });

  readonly activeMobileTab = computed(() => appNavigationGroupForUrl(this.currentUrl(), this.activeState()));

  readonly mobileHomeSectionLinks = computed(() => {
    if (!this.showMobileStaffShell()) {
      return [];
    }
    return appNavigationLinksForGroup('home', this.realmRoles());
  });

  readonly mobileHomeSectionItems = computed<MobileStatusItem[]>(() => this.mobileHomeSectionLinks().map((link) => ({
    key: link.id,
    title: link.label,
    description: link.description,
    value: '',
    icon: link.icon,
    tone: this.mobileSectionTone(link.id)
  })));

  readonly activeMobileSectionKey = computed(() => {
    const currentPath = this.currentUrl().split(/[?#]/, 1)[0];
    return this.mobileHomeSectionLinks()
      .find((link) => link.routerLink === currentPath || (link.routerLink !== '/' && currentPath.startsWith(`${link.routerLink}/`)))
      ?.id ?? '';
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
    const routerEvents = this.router.events.subscribe((event) => {
      if (event instanceof NavigationEnd) {
        this.currentUrl.set(event.urlAfterRedirects);
        this.closeMobileOverlays();
      }
    });
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
    effect((onCleanup) => {
      const shouldLock = this.viewport.mobile() && (this.mobileMenuOpen() || this.mobileSectionPickerOpen());
      document.body.classList.toggle('otziv-mobile-overlay-open', shouldLock);
      onCleanup(() => document.body.classList.remove('otziv-mobile-overlay-open'));
    });
    this.destroyRef.onDestroy(() => routerEvents.unsubscribe());
  }

  displaySidebar(): boolean {
    return !this.hideSidebarBeforeLogin || this.authenticated();
  }

  isActive(link: ShellLink): boolean {
    return this.active === link.active;
  }

  routerLinkFor(link: ShellLink): string | undefined {
    if (link.id === 'worker') {
      return appNavigationRouteForGroup('worker', this.realmRoles());
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

  handleMobileNavigation(request: MobileBottomNavRequest): void {
    if (!this.showMobileStaffShell()) {
      return;
    }

    this.mobileNavIntent.request(request.tab, request.mode);
    this.mobileMenuOpen.set(false);

    if (request.mode === 'menu' && request.tab === 'home') {
      this.mobileSectionPickerOpen.set(true);
      return;
    }

    this.mobileSectionPickerOpen.set(false);
    const target = appNavigationRouteForGroup(request.tab, this.realmRoles());
    if (
      request.mode === 'all'
      || this.activeMobileTab() !== request.tab
      || !isAppNavigationGroupRootUrl(request.tab, this.currentUrl(), this.realmRoles())
    ) {
      void this.router.navigateByUrl(target);
    }
  }

  toggleMobileMenu(): void {
    if (!this.showMobileStaffShell()) {
      return;
    }
    this.mobileSectionPickerOpen.set(false);
    this.mobileMenuOpen.update((open) => !open);
  }

  closeMobileOverlays(): void {
    this.mobileMenuOpen.set(false);
    this.mobileSectionPickerOpen.set(false);
  }

  selectMobileHomeSection(key: string): void {
    const link = this.mobileHomeSectionLinks().find((item) => item.id === key);
    if (!link) {
      return;
    }

    this.mobileSectionPickerOpen.set(false);
    if (link.routerLink) {
      void this.router.navigateByUrl(this.routerLinkFor(link) ?? link.routerLink);
      return;
    }

    if (link.href && typeof window !== 'undefined') {
      if (link.openInNewTab) {
        window.open(link.href, '_blank', 'noopener');
      } else {
        window.location.assign(link.href);
      }
    }
  }

  handleMobileMenuLinkClick(link: ShellLink): void {
    this.mobileMenuOpen.set(false);
    this.handleRouterLinkClick(link);
  }

  @HostListener('document:keydown.escape')
  closeMobileOverlaysOnEscape(): void {
    if (this.viewport.mobile()) {
      this.closeMobileOverlays();
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
    if (this.isClientUser() && link.id === 'personal-cabinet') {
      return false;
    }

    const requiredRoles = link.roles;
    if (requiredRoles.length === 0) {
      return true;
    }

    const roles = new Set(this.realmRoles());

    if (link.id === 'worker' && !this.viewport.mobile() && this.isPerformerOnly()) {
      return false;
    }

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

  private mobileSectionTone(id: string): MobileStatusItem['tone'] {
    if (id === 'analytics' || id === 'score') {
      return 'blue';
    }
    if (id === 'team' || id === 'users') {
      return 'teal';
    }
    if (id === 'tbank') {
      return 'violet';
    }
    if (id.includes('control')) {
      return 'yellow';
    }
    return 'gray';
  }

  canOpenWhatsAppBinding(): boolean {
    return this.realmRoles().some((role) => normalizeRole(role) === 'MANAGER');
  }

  performanceTone(score: number | null | undefined): string {
    const value = score ?? 0;
    if (value >= 90) {
      return 'excellent';
    }
    if (value >= 80) {
      return 'good';
    }
    if (value >= 40) {
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

  private isPerformerOnly(): boolean {
    const roles = new Set(this.realmRoles());
    return roles.has('PERFORMER')
      && !['ADMIN', 'OWNER', 'MANAGER', 'WORKER'].some((role) => roles.has(role));
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
