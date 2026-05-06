import { Injectable, signal } from '@angular/core';
import Keycloak, { KeycloakProfile, KeycloakTokenParsed } from 'keycloak-js';
import { apiErrorMessage } from '../shared/api-error-message';
import { appEnvironment } from './app-environment';

export type AuthStatus = 'initializing' | 'anonymous' | 'authenticated' | 'refreshing' | 'expired' | 'error';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly keycloak = new Keycloak(appEnvironment.keycloak);
  private initialized = false;
  private refreshTimerId: ReturnType<typeof setInterval> | undefined;
  private refreshPromise: Promise<boolean> | null = null;

  readonly status = signal<AuthStatus>('initializing');
  readonly error = signal<string | null>(null);
  readonly authenticated = signal(false);
  readonly profile = signal<KeycloakProfile | null>(null);
  readonly tokenParsed = signal<KeycloakTokenParsed | undefined>(undefined);
  readonly expiresAt = signal<Date | null>(null);

  async init(): Promise<void> {
    if (this.initialized) {
      return;
    }

    this.registerKeycloakCallbacks();

    try {
      const authenticated = await this.keycloak.init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        checkLoginIframe: false,
        silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`
      });

      this.initialized = true;

      if (authenticated) {
        await this.setAuthenticatedState();
      } else {
        this.clearSession('anonymous');
      }
    } catch (error) {
      this.status.set('error');
      this.error.set(this.getErrorMessage(error));
      throw error;
    }
  }

  login(targetUrl = '/'): Promise<void> {
    return this.keycloak.login({
      redirectUri: `${window.location.origin}${targetUrl}`
    });
  }

  logout(): Promise<void> {
    this.stopRefreshLoop();
    this.clearSession('anonymous');

    return this.keycloak.logout({
      redirectUri: window.location.origin
    });
  }

  async getToken(): Promise<string | null> {
    if (!this.keycloak.authenticated) {
      return null;
    }

    const refreshed = await this.refreshToken(30);
    if (!refreshed && !this.keycloak.token) {
      return null;
    }

    return this.keycloak.token ?? null;
  }

  isAuthenticated(): boolean {
    return this.keycloak.authenticated === true;
  }

  hasRealmRole(role: string): boolean {
    return this.keycloak.hasRealmRole(role);
  }

  hasAnyRealmRole(roles: readonly string[]): boolean {
    return roles.some((role) => this.hasRealmRole(role));
  }

  async refreshToken(minValiditySeconds = 60): Promise<boolean> {
    if (!this.keycloak.authenticated) {
      return false;
    }

    if (this.refreshPromise) {
      return this.refreshPromise;
    }

    this.status.set('refreshing');

    this.refreshPromise = this.keycloak.updateToken(minValiditySeconds)
      .then((refreshed) => {
        this.syncTokenState();
        this.error.set(null);
        this.status.set('authenticated');
        return refreshed;
      })
      .catch((error) => {
        this.handleRefreshFailure(error);
        return false;
      })
      .finally(() => {
        this.refreshPromise = null;
      });

    return this.refreshPromise;
  }

  private registerKeycloakCallbacks(): void {
    this.keycloak.onAuthSuccess = () => {
      void this.setAuthenticatedState();
    };

    this.keycloak.onAuthLogout = () => {
      this.clearSession('anonymous');
    };

    this.keycloak.onAuthRefreshSuccess = () => {
      this.syncTokenState();
      this.error.set(null);
      this.status.set('authenticated');
    };

    this.keycloak.onAuthRefreshError = () => {
      this.handleRefreshFailure();
    };

    this.keycloak.onTokenExpired = () => {
      void this.refreshToken(60);
    };
  }

  private async setAuthenticatedState(): Promise<void> {
    this.authenticated.set(true);
    this.syncTokenState();
    this.error.set(null);
    this.status.set('authenticated');
    this.startRefreshLoop();

    try {
      this.profile.set(await this.keycloak.loadUserProfile());
    } catch {
      this.profile.set(null);
    }
  }

  private syncTokenState(): void {
    const tokenParsed = this.keycloak.tokenParsed;

    this.authenticated.set(this.keycloak.authenticated === true);
    this.tokenParsed.set(tokenParsed);
    this.expiresAt.set(tokenParsed?.exp ? new Date(tokenParsed.exp * 1000) : null);
  }

  private handleRefreshFailure(error?: unknown): void {
    this.stopRefreshLoop();
    this.keycloak.clearToken();
    this.clearSession('expired');
    this.error.set(error ? this.getErrorMessage(error) : 'Сессия закончилась. Войдите снова.');
  }

  private clearSession(status: AuthStatus): void {
    this.authenticated.set(false);
    this.profile.set(null);
    this.tokenParsed.set(undefined);
    this.expiresAt.set(null);
    this.status.set(status);
  }

  private startRefreshLoop(): void {
    if (this.refreshTimerId) {
      return;
    }

    this.refreshTimerId = setInterval(() => {
      void this.refreshToken(90);
    }, 60_000);
  }

  private stopRefreshLoop(): void {
    if (!this.refreshTimerId) {
      return;
    }

    clearInterval(this.refreshTimerId);
    this.refreshTimerId = undefined;
  }

  private getErrorMessage(error: unknown): string {
    return apiErrorMessage(error, 'Ошибка авторизации');
  }
}
