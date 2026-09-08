import { Inject, Injectable, InjectionToken, signal } from '@angular/core';
import Keycloak, { KeycloakProfile, KeycloakTokenParsed } from 'keycloak-js';
import { apiErrorMessage } from '../shared/api-error-message';
import { appEnvironment } from './app-environment';

export type AuthStatus = 'initializing' | 'anonymous' | 'authenticated' | 'refreshing' | 'temporarily-unavailable' | 'expired' | 'error';

export class AuthTemporarilyUnavailableError extends Error {
  readonly status = 0;
  constructor() { super('Не удалось обновить сессию. Проверьте соединение и повторите действие.'); }
}

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
  private sessionGeneration = 0;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private retryAttempt = 0;
  private nextRetryAt = 0;

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
    this.invalidatePendingAuthentication();
    return this.keycloak.login({
      redirectUri: `${window.location.origin}${safeAuthTarget(targetUrl)}`
    });
  }

  restartLogin(targetUrl = '/'): Promise<void> {
    this.invalidatePendingAuthentication();
    return this.keycloak.login({
      redirectUri: `${window.location.origin}${safeAuthTarget(targetUrl)}`,
      prompt: 'login'
    });
  }

  logout(): Promise<void> {
    this.invalidatePendingAuthentication();
    this.redirectingToLogin = true;
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

    const generation = this.sessionGeneration;
    await this.refreshToken(30);
    if (generation !== this.sessionGeneration || this.redirectingToLogin || !this.keycloak.authenticated) {
      return null;
    }
    const token = this.getOptionalToken(0);
    if (!token) throw new AuthTemporarilyUnavailableError();
    return token;
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
      || expiresAtSeconds <= Math.floor(Date.now() / 1000) - (this.keycloak.timeSkew ?? 0) + Math.max(0, minValiditySeconds)) {
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

  captureSession(): number { return this.sessionGeneration; }

  isCurrentRequest(generation: number, token: string | null): boolean {
    return generation === this.sessionGeneration && !this.redirectingToLogin && (token === null || token === (this.keycloak.token ?? null));
  }

  async refreshToken(minValiditySeconds = 60): Promise<boolean> {
    if (this.redirectingToLogin || !this.keycloak.authenticated) {
      return false;
    }

    if (this.refreshPromise) {
      return this.refreshPromise;
    }
    if (Date.now() < this.nextRetryAt) return false;

    this.status.set('refreshing');
    const generation = this.sessionGeneration;
    const pending = this.keycloak.updateToken(minValiditySeconds)
      .then((refreshed) => {
        if (generation !== this.sessionGeneration || this.redirectingToLogin) return false;
        this.resetRefreshRetry();
        this.syncTokenState();
        this.error.set(null);
        this.status.set('authenticated');
        return refreshed;
      })
      .catch((error) => {
        if (generation === this.sessionGeneration && !this.redirectingToLogin) this.handleRefreshFailure(error);
        return false;
      })
      .finally(() => {
        if (this.refreshPromise === pending) {
          this.refreshPromise = null;
        }
      });
    this.refreshPromise = pending;
    return pending;
  }

  handleUnauthorized(targetUrl = this.currentBrowserPath()): void {
    if (this.redirectingToLogin) {
      return;
    }

    this.redirectingToLogin = true;
    this.invalidatePendingAuthentication();
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
      this.invalidatePendingAuthentication();
      this.redirectingToLogin = false;
      void this.setAuthenticatedState();
    };

    this.keycloak.onAuthLogout = () => {
      if (!this.redirectingToLogin) this.handleUnauthorized();
    };

    this.keycloak.onAuthRefreshSuccess = () => {
      // updateToken's promise owns the result and its session fence. The SDK
      // invokes this callback before resolving that promise.
    };

    this.keycloak.onAuthRefreshError = () => {
      // Keycloak invokes this without the cause before rejecting updateToken.
      // Only the rejection can distinguish a network outage from invalid_grant.
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
    window.addEventListener('online', () => { void this.refreshTokenAfterResume(); });

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

    this.resetRefreshRetry();
    await this.refreshToken(30);
  }

  private async setAuthenticatedState(): Promise<void> {
    const generation = this.sessionGeneration;
    this.authenticated.set(true);
    this.syncTokenState();
    this.error.set(null);
    this.status.set('authenticated');
    this.startRefreshLoop();

    try {
      const profile = await this.keycloak.loadUserProfile();
      if (generation === this.sessionGeneration && !this.redirectingToLogin) this.profile.set(profile);
    } catch {
      if (generation === this.sessionGeneration && !this.redirectingToLogin) this.profile.set(null);
    }
  }

  private syncTokenState(): void {
    const tokenParsed = this.keycloak.tokenParsed;

    this.authenticated.set(this.keycloak.authenticated === true);
    this.tokenParsed.set(tokenParsed);
    this.expiresAt.set(tokenParsed?.exp ? new Date(tokenParsed.exp * 1000) : null);
  }

  private handleRefreshFailure(error?: unknown): void {
    const response = (error as { response?: { status?: number }; status?: number } | undefined);
    const status = response?.response?.status ?? response?.status;
    if (!this.keycloak.authenticated || status === 400 || status === 401 || status === 403 || this.isAuthRestartPage()) {
      this.handleUnauthorized();
      return;
    }
    this.status.set('temporarily-unavailable');
    this.error.set(new AuthTemporarilyUnavailableError().message);
    const delay = [2_000, 5_000, 15_000][this.retryAttempt++];
    this.nextRetryAt = delay === undefined ? Number.POSITIVE_INFINITY : Date.now() + delay;
    if (delay !== undefined) {
      const generation = this.sessionGeneration;
      this.retryTimer = setTimeout(() => {
        this.retryTimer = undefined;
        if (generation === this.sessionGeneration && !this.redirectingToLogin) void this.refreshToken(-1);
      }, delay);
    }
  }

  private resetRefreshRetry(): void {
    if (this.retryTimer !== undefined) clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
    this.retryAttempt = 0;
    this.nextRetryAt = 0;
  }

  private invalidatePendingAuthentication(): void {
    this.sessionGeneration += 1;
    this.resetRefreshRetry();
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
