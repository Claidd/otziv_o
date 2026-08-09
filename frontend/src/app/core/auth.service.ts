import { Inject, Injectable, InjectionToken, signal } from '@angular/core';
import Keycloak, { KeycloakProfile, KeycloakTokenParsed } from 'keycloak-js';
import { apiErrorMessage } from '../shared/api-error-message';
import { appEnvironment } from './app-environment';

export type AuthStatus = 'initializing' | 'anonymous' | 'authenticated' | 'refreshing' | 'expired' | 'error';

export const KEYCLOAK_CLIENT = new InjectionToken<Keycloak>('KEYCLOAK_CLIENT', {
  providedIn: 'root',
  factory: () => new Keycloak(appEnvironment.keycloak)
});

@Injectable({ providedIn: 'root' })
export class AuthService {
  private initialized = false;
  private refreshTimerId: ReturnType<typeof setInterval> | undefined;
  private refreshPromise: Promise<boolean> | null = null;
  private redirectingToLogin = false;
  private browserResumeHandlersRegistered = false;

  readonly status = signal<AuthStatus>('initializing');
  readonly error = signal<string | null>(null);
  readonly authenticated = signal(false);
  readonly profile = signal<KeycloakProfile | null>(null);
  readonly tokenParsed = signal<KeycloakTokenParsed | undefined>(undefined);
  readonly expiresAt = signal<Date | null>(null);

  constructor(@Inject(KEYCLOAK_CLIENT) private readonly keycloak: Keycloak) {}

  async init(): Promise<void> {
    if (this.initialized) {
      return;
    }

    const returnedFromAuthentication = hasKeycloakAuthenticationCallback(window.location.href);
    this.registerKeycloakCallbacks();
    this.registerBrowserResumeHandlers();

    try {
      const authenticated = await this.keycloak.init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        responseMode: 'fragment',
        checkLoginIframe: false,
        silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
        silentCheckSsoFallback: true
      });

      this.initialized = true;

      if (authenticated) {
        await this.setAuthenticatedState();
      } else if (returnedFromAuthentication) {
        this.error.set(
          'Сайт получил возврат после ввода пароля, но не смог сохранить сессию. '
          + 'Откройте o-ogo.ru напрямую в Safari или Chrome и повторите вход.'
        );
        this.clearSession('error');
      } else {
        this.clearSession('anonymous');
      }
    } catch (error) {
      this.initialized = true;
      this.error.set(this.getErrorMessage(error));
      this.clearSession('anonymous');
    }
  }

  login(targetUrl = '/'): Promise<void> {
    return this.keycloak.login({
      redirectUri: `${window.location.origin}${safeAuthTarget(targetUrl)}`
    });
  }

  restartLogin(targetUrl = '/'): Promise<void> {
    return this.keycloak.login({
      redirectUri: `${window.location.origin}${safeAuthTarget(targetUrl)}`,
      prompt: 'login'
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
    if (this.redirectingToLogin || !this.keycloak.authenticated) {
      return null;
    }

    const refreshed = await this.refreshToken(30);
    if (!refreshed && !this.keycloak.token) {
      return null;
    }

    return this.keycloak.token ?? null;
  }

  /**
   * Returns an already-valid token without refreshing or starting a login
   * redirect. Public capability pages use this to retain authenticated role
   * features when possible while still remaining usable anonymously.
   */
  getOptionalToken(minValiditySeconds = 5): string | null {
    if (this.redirectingToLogin || !this.keycloak.authenticated || !this.keycloak.token) {
      return null;
    }

    const expiresAtSeconds = this.keycloak.tokenParsed?.exp;
    if (!expiresAtSeconds
      || expiresAtSeconds <= Math.floor(Date.now() / 1000) + Math.max(0, minValiditySeconds)) {
      return null;
    }

    return this.keycloak.token;
  }

  isAuthenticated(): boolean {
    return !this.redirectingToLogin && this.keycloak.authenticated === true;
  }

  hasRealmRole(role: string): boolean {
    return !this.redirectingToLogin && this.keycloak.hasRealmRole(role);
  }

  hasAnyRealmRole(roles: readonly string[]): boolean {
    return roles.some((role) => this.hasRealmRole(role));
  }

  async refreshToken(minValiditySeconds = 60): Promise<boolean> {
    if (this.redirectingToLogin || !this.keycloak.authenticated) {
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

  handleUnauthorized(targetUrl = this.currentBrowserPath()): void {
    if (this.redirectingToLogin) {
      return;
    }

    this.redirectingToLogin = true;
    this.stopRefreshLoop();
    this.clearSession('expired');
    this.error.set('Сессия закончилась. Войдите снова.');

    if (this.isAuthRestartPage()) {
      // The restart component is about to open a forced credential prompt.
      // A late API 401 or refresh failure here must not start another
      // end-session round trip and recreate a logout/restart loop.
      this.keycloak.clearToken();
      return;
    }

    const restartUrl = this.authRestartUrl(targetUrl);
    void Promise.resolve()
      .then(() => this.keycloak.logout({ redirectUri: restartUrl }))
      .catch(() => {
        // If the end-session endpoint is temporarily unavailable, discard the
        // unusable local token and move to a same-origin forced-login page.
        // Its prompt=login prevents a live SSO cookie from recreating the loop.
        this.keycloak.clearToken();
        this.replaceBrowserLocation(restartUrl);
      });
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

  private registerBrowserResumeHandlers(): void {
    if (this.browserResumeHandlersRegistered) {
      return;
    }

    this.browserResumeHandlersRegistered = true;

    window.addEventListener('focus', () => {
      void this.refreshTokenAfterResume();
    });

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        void this.refreshTokenAfterResume();
      }
    });
  }

  private async refreshTokenAfterResume(): Promise<void> {
    if (this.redirectingToLogin || !this.keycloak.authenticated) {
      return;
    }

    await this.refreshToken(30);
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
    this.handleUnauthorized();
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

  private currentBrowserPath(): string {
    return `${window.location.pathname}${window.location.search}${window.location.hash}` || '/';
  }

  private authRestartUrl(targetUrl: string): string {
    const restartUrl = new URL('/auth/restart', window.location.origin);
    restartUrl.searchParams.set('target', safeAuthTarget(targetUrl));
    return restartUrl.toString();
  }

  private isAuthRestartPage(): boolean {
    const canonicalPath = canonicalAuthPath(window.location.pathname);
    return canonicalPath !== null && isPathOrDescendant(canonicalPath, '/auth/restart');
  }

  protected replaceBrowserLocation(url: string): void {
    window.location.replace(url);
  }
}

export function safeAuthTarget(value: string | null | undefined): string {
  if (!value || !value.startsWith('/')) {
    return '/';
  }

  try {
    const validationOrigin = 'https://auth-target.invalid';
    const parsed = new URL(value, validationOrigin);
    const canonicalPath = canonicalAuthPath(parsed.pathname);
    if (parsed.origin !== validationOrigin
      || canonicalPath === null
      || isPathOrDescendant(canonicalPath, '/keycloak')
      || isPathOrDescendant(canonicalPath, '/auth/restart')) {
      return '/';
    }
    // OAuth redirect_uri values cannot contain fragments. Keycloak also uses
    // the fragment for responseMode=fragment, so carrying an application or
    // stale callback hash into redirectUri would corrupt the callback.
    return `${parsed.pathname}${parsed.search}`;
  } catch {
    return '/';
  }
}

const MAX_AUTH_PATH_LENGTH = 4096;
const MAX_AUTH_PATH_DECODE_PASSES = 3;
const ENCODED_PATH_SEPARATOR = /%(?:2f|5c)/i;
const REMAINING_PERCENT_ESCAPE = /%[0-9a-f]{2}/i;
const UNSAFE_PATH_CHARACTER = /[\\\u0000-\u001f\u007f]/;

function canonicalAuthPath(pathname: string): string | null {
  if (!pathname || pathname.length > MAX_AUTH_PATH_LENGTH) {
    return null;
  }

  let canonical = pathname;
  for (let pass = 0; pass < MAX_AUTH_PATH_DECODE_PASSES; pass += 1) {
    // Encoded separators change Angular's segment boundaries after decoding.
    // Reject them at every layer rather than guessing which router view wins.
    if (ENCODED_PATH_SEPARATOR.test(canonical) || UNSAFE_PATH_CHARACTER.test(canonical)) {
      return null;
    }

    let decoded: string;
    try {
      decoded = decodeURIComponent(canonical);
    } catch {
      return null;
    }
    if (UNSAFE_PATH_CHARACTER.test(decoded)) {
      return null;
    }
    if (decoded === canonical) {
      return withoutMatrixParameters(decoded);
    }
    canonical = decoded;
  }

  // More deeply nested escapes are not valid auth navigation targets. This
  // keeps validation bounded and prevents a later decoder from seeing a
  // different reserved route than this check did.
  if (ENCODED_PATH_SEPARATOR.test(canonical)
    || REMAINING_PERCENT_ESCAPE.test(canonical)
    || UNSAFE_PATH_CHARACTER.test(canonical)) {
    return null;
  }
  return withoutMatrixParameters(canonical);
}

function withoutMatrixParameters(pathname: string): string {
  return pathname
    .split('/')
    .map((segment) => segment.split(';', 1)[0])
    .join('/');
}

function isPathOrDescendant(pathname: string, reservedPath: string): boolean {
  const normalized = pathname.toLowerCase();
  return normalized === reservedPath || normalized.startsWith(`${reservedPath}/`);
}

export function hasKeycloakAuthenticationCallback(url: string): boolean {
  try {
    const parsed = new URL(url, window.location.origin);
    return isAuthenticationCallbackParams(parsed.searchParams)
      || isAuthenticationCallbackParams(new URLSearchParams(parsed.hash.replace(/^#/, '')));
  } catch {
    return false;
  }
}

function isAuthenticationCallbackParams(params: URLSearchParams): boolean {
  const hasState = params.has('state');
  return hasState && (params.has('code') || params.has('error'));
}
