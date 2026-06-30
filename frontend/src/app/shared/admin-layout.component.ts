import { Component, computed, effect, EventEmitter, inject, Input, Output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { appEnvironment } from '../core/app-environment';
import { AuthService } from '../core/auth.service';
import { CabinetApi } from '../core/cabinet.api';
import { CABINET_HOME_LINK, CABINET_SECTION_LINKS } from './cabinet-navigation';
import { PersonalRemindersComponent } from './personal-reminders.component';
import { normalizeRole, roleLabel } from './role-labels';
import { requestWorkerCurrentSectionOpen } from './worker-entry-navigation';

type ThemeMode = 'light' | 'dark';

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
  @Output() readonly activeLinkClicked = new EventEmitter<string>();

  readonly brandLogoUrl = '/assets/images/logo-o.png';
  readonly authenticated = this.auth.authenticated;
  readonly theme = signal<ThemeMode>(this.getInitialTheme());
  readonly headerProfileFallbackUrl = signal<string | null>(null);

  readonly headerLinks: ShellLink[] = [
    { label: 'Главная', icon: 'home', active: 'dashboard', routerLink: '/', roles: [] },
    { label: 'Лиды', icon: 'notifications_active', active: 'leads', routerLink: '/leads', roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG'] },
    { label: 'Компании', icon: 'business', active: 'companies', routerLink: '/companies', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Заказы', icon: 'inventory_2', active: 'orders', routerLink: '/orders', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Специалист', icon: 'engineering', active: 'worker', routerLink: '/worker', roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] },
    { label: 'Оператор', icon: 'support_agent', active: 'operator', routerLink: '/operator', roles: ['ADMIN', 'OWNER', 'OPERATOR'] },
    CABINET_HOME_LINK
  ];

  readonly sidebarLinks: ShellLink[] = [
    CABINET_HOME_LINK,
    { label: 'Лиды', icon: 'notifications_active', active: 'leads', routerLink: '/leads', roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG'] },
    { label: 'Компании', icon: 'business', active: 'companies', routerLink: '/companies', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Заказы', icon: 'inventory_2', active: 'orders', routerLink: '/orders', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Специалист', icon: 'engineering', active: 'worker', routerLink: '/worker', roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] },
    { label: 'Оператор', icon: 'support_agent', active: 'operator', routerLink: '/operator', roles: ['ADMIN', 'OWNER', 'OPERATOR'] },
    ...CABINET_SECTION_LINKS,
    { label: 'Обучение', icon: 'school', active: 'training', routerLink: '/training', roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] },
    { label: 'Архив', icon: 'archive', active: 'manager-archive', routerLink: '/manager/archive', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Города', icon: 'location_city', active: 'city-stats', routerLink: '/admin/cities', roles: ['ADMIN', 'OWNER'] },
    { label: 'Архиватор', icon: 'inventory_2', active: 'archive-admin', routerLink: '/admin/archive', roles: ['ADMIN', 'OWNER'] },
    { label: 'Контроль', icon: 'fact_check', active: 'manager-control', routerLink: '/admin/manager-control', roles: ['ADMIN', 'OWNER'] },
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

  private isClientUser(): boolean {
    return this.realmRoles().some((role) => normalizeRole(role) === 'CLIENT');
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
