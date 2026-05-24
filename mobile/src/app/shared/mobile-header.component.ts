import { Component, Input, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { IonHeader, IonToolbar } from '@ionic/angular/standalone';
import { AuthService } from '../core/auth.service';
import { MobileThemeMode, MobileThemeService } from './mobile-theme.service';

interface MobileHeaderLink {
  label: string;
  icon: string;
  path: string;
  roles: string[];
}

const HEADER_LINKS: MobileHeaderLink[] = [
  { label: 'Главная', icon: 'home', path: '/tabs/home', roles: [] },
  { label: 'Лиды', icon: 'notifications_active', path: '/tabs/leads', roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG'] },
  { label: 'Компании', icon: 'business', path: '/tabs/companies', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
  { label: 'Заказы', icon: 'inventory_2', path: '/tabs/orders', roles: ['ADMIN', 'OWNER', 'MANAGER'] },
  { label: 'Специалист', icon: 'engineering', path: '/tabs/worker', roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] },
  { label: 'Оператор', icon: 'support_agent', path: '/tabs/operator', roles: ['ADMIN', 'OWNER', 'OPERATOR'] },
  { label: 'Т Банк', icon: 'account_balance_wallet', path: '/tabs/tbank', roles: ['ADMIN'] },
  { label: 'Профиль', icon: 'account_circle', path: '/tabs/profile', roles: [] }
];

const ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Админ',
  OWNER: 'Владелец',
  MANAGER: 'Менеджер',
  WORKER: 'Специалист',
  OPERATOR: 'Оператор',
  MARKETOLOG: 'Маркетолог',
  CLIENT: 'Клиент'
};

@Component({
  selector: 'app-mobile-header',
  imports: [IonHeader, IonToolbar, RouterLink],
  template: `
    <ion-header class="mobile-shell-header" translucent>
      <ion-toolbar>
        <div class="mobile-header-row">
          <a class="mobile-brand" routerLink="/tabs/home" (click)="closeMenu()">
            <span class="mobile-brand-text">Компания&nbsp;<strong>О!</strong></span>
          </a>

          <span class="mobile-page-title">{{ title }}</span>

          <div class="mobile-header-controls">
            <div class="mobile-mode-switch" aria-label="Смена темы день ночь">
              <button
                type="button"
                [class.active]="theme.theme() === 'light'"
                aria-label="Дневная тема"
                (click)="setTheme('light')"
              >
                <span class="material-icons-sharp" aria-hidden="true">light_mode</span>
              </button>
              <button
                type="button"
                [class.active]="theme.theme() === 'dark'"
                aria-label="Ночная тема"
                (click)="setTheme('dark')"
              >
                <span class="material-icons-sharp" aria-hidden="true">dark_mode</span>
              </button>
            </div>

            <button
              class="mobile-menu-trigger"
              type="button"
              [attr.aria-expanded]="menuOpen()"
              aria-label="Открыть меню"
              (click)="toggleMenu()"
            >
              <span class="material-icons-sharp" aria-hidden="true">{{ menuOpen() ? 'close' : 'menu' }}</span>
            </button>
          </div>
        </div>

        @if (menuOpen()) {
          <nav class="mobile-header-menu" aria-label="Разделы приложения">
            <div class="mobile-user-row">
              <span class="mobile-avatar">{{ initials() }}</span>
              <div>
                <strong>{{ username() }}</strong>
                <small>{{ primaryRole() }}</small>
              </div>
            </div>

            <div class="mobile-menu-links">
              @for (link of visibleLinks(); track link.path) {
                <a [routerLink]="link.path" (click)="closeMenu()">
                  <span class="material-icons-sharp" aria-hidden="true">{{ link.icon }}</span>
                  <strong>{{ link.label }}</strong>
                </a>
              }
            </div>

            <button class="mobile-menu-logout" type="button" (click)="logout()">
              <span class="material-icons-sharp" aria-hidden="true">logout</span>
              <strong>Выход</strong>
            </button>
          </nav>
        }
      </ion-toolbar>
    </ion-header>
  `,
  styles: [`
    :host {
      display: contents;
    }

    ion-toolbar {
      --min-height: 3.55rem;
      --padding-end: max(0.35rem, env(safe-area-inset-right));
      --padding-start: max(0.35rem, env(safe-area-inset-left));
    }

    .mobile-header-row {
      display: grid;
      grid-template-columns: minmax(0, auto) minmax(0, 1fr) auto;
      align-items: center;
      gap: 0.5rem;
      min-height: 3.55rem;
      padding: 0 0.45rem;
    }

    .mobile-brand {
      display: inline-flex;
      align-items: baseline;
      min-width: 0;
      color: var(--otziv-dark);
      font-family: var(--otziv-card-title-font);
      font-size: clamp(1.03rem, 4.6vw, 1.26rem);
      font-weight: 900;
      line-height: 1;
      text-decoration: none;
      white-space: nowrap;
    }

    .mobile-brand-text {
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .mobile-brand strong {
      color: var(--otziv-danger);
      font: inherit;
    }

    .mobile-page-title {
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-info);
      font-size: 0.72rem;
      font-weight: 900;
      text-align: center;
      text-overflow: ellipsis;
      text-transform: uppercase;
      white-space: nowrap;
    }

    .mobile-header-controls {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      min-width: 0;
    }

    .mobile-mode-switch {
      display: inline-flex;
      width: 3.55rem;
      height: 1.55rem;
      align-items: center;
      justify-content: space-between;
      border-radius: var(--otziv-small-radius);
      background: var(--otziv-light);
    }

    .mobile-mode-switch button {
      display: grid;
      width: 50%;
      height: 100%;
      place-items: center;
      border: 0;
      border-radius: var(--otziv-small-radius);
      padding: 0;
      color: var(--otziv-dark);
      background: transparent;
      font: inherit;
    }

    .mobile-mode-switch button.active {
      color: #ffffff;
      background: var(--otziv-primary);
    }

    .mobile-mode-switch .material-icons-sharp {
      font-size: 1rem;
    }

    .mobile-menu-trigger {
      display: grid;
      width: 2.25rem;
      height: 2.25rem;
      place-items: center;
      border: 1px solid rgba(108, 155, 207, 0.22);
      border-radius: 0.75rem;
      color: var(--otziv-primary);
      background: var(--otziv-white);
      font: inherit;
    }

    .mobile-header-menu {
      display: grid;
      gap: 0.65rem;
      margin: 0 0.45rem 0.55rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 1rem;
      padding: 0.7rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 0.95rem 1.9rem rgba(132, 139, 200, 0.16);
    }

    .mobile-user-row {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.65rem;
      min-width: 0;
    }

    .mobile-avatar {
      display: grid;
      width: 2.25rem;
      height: 2.25rem;
      place-items: center;
      border: 2px solid var(--otziv-danger);
      border-radius: 50%;
      color: var(--otziv-danger);
      background: var(--otziv-white);
      font-weight: 900;
    }

    .mobile-user-row strong,
    .mobile-user-row small {
      display: block;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .mobile-user-row strong {
      color: var(--otziv-dark);
      font-size: 0.9rem;
      font-weight: 900;
    }

    .mobile-user-row small {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 800;
    }

    .mobile-menu-links {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.45rem;
    }

    .mobile-menu-links a,
    .mobile-menu-logout {
      display: inline-flex;
      align-items: center;
      justify-content: flex-start;
      gap: 0.42rem;
      min-width: 0;
      min-height: 2.15rem;
      border: 1px solid rgba(108, 155, 207, 0.2);
      border-radius: 999px;
      padding: 0 0.65rem;
      color: var(--otziv-primary);
      background: var(--otziv-white);
      font: inherit;
      font-size: 0.72rem;
      font-weight: 900;
      line-height: 1;
      text-decoration: none;
    }

    .mobile-menu-links strong,
    .mobile-menu-logout strong {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .mobile-menu-links .material-icons-sharp,
    .mobile-menu-logout .material-icons-sharp {
      flex: 0 0 auto;
      font-size: 1.05rem;
    }

    .mobile-menu-logout {
      width: 100%;
      color: var(--otziv-danger);
    }

    @media (max-width: 360px) {
      .mobile-header-row {
        gap: 0.35rem;
        padding-inline: 0.25rem;
      }

      .mobile-brand {
        font-size: 0.98rem;
      }

      .mobile-page-title {
        font-size: 0.62rem;
      }

      .mobile-mode-switch {
        width: 3.15rem;
      }

      .mobile-menu-trigger {
        width: 2.1rem;
        height: 2.1rem;
      }
    }
  `]
})
export class MobileHeaderComponent {
  @Input() title = '';

  readonly auth = inject(AuthService);
  readonly theme = inject(MobileThemeService);
  readonly menuOpen = signal(false);

  visibleLinks(): MobileHeaderLink[] {
    return HEADER_LINKS.filter((link) => link.roles.length === 0 || this.auth.hasAnyRealmRole(link.roles));
  }

  username(): string {
    return this.auth.user()?.preferredUsername || this.auth.user()?.name || 'user';
  }

  primaryRole(): string {
    const role = this.auth.user()?.roles.find((value) => ROLE_LABELS[value]) ?? this.auth.user()?.roles[0] ?? 'USER';
    return ROLE_LABELS[role] ?? role;
  }

  initials(): string {
    return this.username().trim().slice(0, 1).toUpperCase() || 'O';
  }

  setTheme(theme: MobileThemeMode): void {
    this.theme.setTheme(theme);
  }

  toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  closeMenu(): void {
    this.menuOpen.set(false);
  }

  logout(): void {
    this.closeMenu();
    void this.auth.logout();
  }
}
