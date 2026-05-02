import { Component, computed, effect, inject, Input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { appEnvironment } from '../core/app-environment';
import { CabinetApi } from '../core/cabinet.api';

type ThemeMode = 'light' | 'dark';

type ShellLink = {
  label: string;
  icon: string;
  active: string;
  roles: string[];
  routerLink?: string;
  href?: string;
};

@Component({
  selector: 'app-admin-layout',
  imports: [RouterLink],
  templateUrl: './admin-layout.component.html'
})
export class AdminLayoutComponent {
  private readonly auth = inject(AuthService);
  private readonly cabinetApi = inject(CabinetApi);
  private readonly themeStorageKey = 'otziv-theme';
  private loadedHeaderProfileFor: string | null = null;

  @Input() title = 'Админка';
  @Input() active = '';
  @Input() hideSidebarBeforeLogin = true;
  @Input() rightPanelMode: 'default' | 'custom' = 'default';
  @Input() profileImageUrl: string | null = null;
  @Input() profileImageAlt = 'Фото профиля';

  readonly brandLogoUrl = `${appEnvironment.legacyBaseUrl}/images/image/logo-o.png`;
  readonly authenticated = this.auth.authenticated;
  readonly theme = signal<ThemeMode>(this.getInitialTheme());
  readonly headerProfileFallbackUrl = signal<string | null>(null);

  readonly headerLinks: ShellLink[] = [
    { label: 'Главная', icon: 'home', active: 'dashboard', routerLink: '/', roles: [] },
    { label: 'Лиды', icon: 'notifications_active', active: 'leads', routerLink: '/leads', roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG'] },
    { label: 'Оператор', icon: 'support_agent', active: 'operator', href: `${appEnvironment.legacyBaseUrl}/operators`, roles: ['ADMIN', 'OWNER', 'OPERATOR', 'MARKETOLOG'] },
    { label: 'Маркетолог', icon: 'campaign', active: 'marketolog', href: `${appEnvironment.legacyBaseUrl}/admin/analyse`, roles: ['ADMIN', 'OWNER', 'MARKETOLOG'] },
    { label: 'Менеджер', icon: 'groups', active: 'manager', routerLink: '/manager', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Специалист', icon: 'engineering', active: 'worker', routerLink: '/worker', roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] },
    { label: 'Личный кабинет', icon: 'dashboard', active: 'dashboard', routerLink: '/', roles: [] }
  ];

  readonly sidebarLinks: ShellLink[] = [
    { label: 'Личный кабинет', icon: 'dashboard', active: 'dashboard', routerLink: '/', roles: [] },
    { label: 'Лиды', icon: 'notifications_active', active: 'leads', routerLink: '/leads', roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG'] },
    { label: 'Менеджер', icon: 'groups', active: 'manager', routerLink: '/manager', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Специалист', icon: 'engineering', active: 'worker', routerLink: '/worker', roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] },
    { label: 'Моя команда', icon: 'badge', active: 'team', routerLink: '/admin/team', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
    { label: 'Рейтинг', icon: 'leaderboard', active: 'score', routerLink: '/admin/score', roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER', 'OPERATOR', 'MARKETOLOG'] },
    { label: 'Аналитика', icon: 'analytics', active: 'analytics', routerLink: '/admin/analyse', roles: ['ADMIN', 'OWNER'] },
    { label: 'Пользователи', icon: 'group_add', active: 'users', routerLink: '/admin/users', roles: ['ADMIN', 'OWNER'] },
    { label: 'Новый пользователь', icon: 'person_add', active: 'create-user', routerLink: '/admin/users/new', roles: ['ADMIN', 'OWNER'] },
    { label: 'Миграция', icon: 'sync', active: 'migration', routerLink: '/legacy-migration', roles: ['ADMIN', 'OWNER'] },
    { label: 'Метрики', icon: 'desktop_windows', active: 'metrics', href: appEnvironment.metricsBaseUrl, roles: ['ADMIN', 'OWNER'] },
    { label: 'Назад', icon: 'logout', active: 'back', routerLink: '/', roles: [] }
  ];

  readonly visibleHeaderLinks = computed(() => {
    if (!this.authenticated()) {
      return [];
    }

    return this.headerLinks.filter((link) => this.canSee(link.roles));
  });

  readonly visibleSidebarLinks = computed(() => {
    if (!this.displaySidebar()) {
      return [];
    }

    return this.sidebarLinks.filter((link) => this.canSee(link.roles));
  });

  readonly username = computed(() => {
    const profileUsername = this.auth.profile()?.username;
    const tokenUsername = this.token()?.preferred_username;

    return profileUsername || tokenUsername || 'user';
  });

  readonly primaryRole = computed(() => {
    const ignoredRoles = new Set(['default-roles-otziv', 'offline_access', 'uma_authorization']);
    return this.realmRoles().find((role) => !ignoredRoles.has(role)) ?? 'USER';
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

  login(): void {
    void this.auth.login(window.location.pathname || '/');
  }

  logout(): void {
    void this.auth.logout();
  }

  headerProfileImageUrl(): string | null {
    return this.profileImageUrl || this.headerProfileFallbackUrl();
  }

  headerProfileImageAlt(): string {
    return this.profileImageUrl ? this.profileImageAlt : 'Фото профиля';
  }

  setTheme(theme: ThemeMode): void {
    this.theme.set(theme);
    this.applyTheme(theme);
    localStorage.setItem(this.themeStorageKey, theme);
  }

  private canSee(requiredRoles: string[]): boolean {
    if (requiredRoles.length === 0) {
      return true;
    }

    const roles = new Set(this.realmRoles());

    if (roles.has('ADMIN') || roles.has('OWNER')) {
      return true;
    }

    return requiredRoles.some((role) => roles.has(role));
  }

  private hasAdminAnalyticsHome(): boolean {
    const roles = new Set(this.realmRoles());
    return roles.has('ADMIN') || roles.has('OWNER');
  }

  private loadHeaderProfile(username: string): void {
    if (!username || this.loadedHeaderProfileFor === username) {
      return;
    }

    this.loadedHeaderProfileFor = username;
    this.cabinetApi.getProfile().subscribe({
      next: (profile) => {
        const imageId = profile.workerZp?.imageId || profile.user?.image;
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
