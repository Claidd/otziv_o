import { Injectable, Injector, signal } from '@angular/core';
import { Router } from '@angular/router';
import { App as CapacitorApp } from '@capacitor/app';
import { AppLauncher } from '@capacitor/app-launcher';
import { Browser } from '@capacitor/browser';
import { Capacitor, CapacitorHttp } from '@capacitor/core';
import { mobileEnvironment, webRedirectUri } from './mobile-environment';
import { AuthTemporarilyUnavailableError, type AuthRefreshResult, type AuthStatus, type AuthUser, type StoredTokens, type TokenEndpointResponse } from './auth.models';
import { MobileAuthDiagnosticsService, type MobileAuthDiagnosticValue } from './mobile-auth-diagnostics.service';
import { MobileAuthStorageService } from './mobile-auth-storage.service';
import { AuthStorageDurabilityError } from './auth-storage-durability.plugin';
import { MobilePushService } from './mobile-push.service';

export type MobileLogoutSource = 'home_actions' | 'header_menu' | 'profile' | 'unknown';

class TokenEndpointHttpError extends Error {
  constructor(readonly statusCode: number) {
    super(`Keycloak token request failed: ${statusCode}`);
  }
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly isNative = Capacitor.isNativePlatform();
  private refreshTimerId: ReturnType<typeof setTimeout> | undefined;
  private refreshPromise: Promise<AuthRefreshResult> | null = null;
  private tokenStorageWork: Promise<void> = Promise.resolve();
  private sessionGeneration = 0;
  private initialized = false;
  private readonly handledNativeAuthUrls = new Set<string>();
  private lastResumeCheckAt = 0;

  readonly status = signal<AuthStatus>('initializing');
  readonly user = signal<AuthUser | null>(null);
  readonly tokens = signal<StoredTokens | null>(null);
  readonly error = signal<string | null>(null);

  constructor(
    private readonly router: Router,
    private readonly storage: MobileAuthStorageService,
    private readonly injector: Injector
  ) {}

  async init(): Promise<void> {
    if (this.initialized) {
      return;
    }

    this.initialized = true;
    const generation = this.sessionGeneration;
    const initialTokens = this.tokens();
    this.diagnostics().initialize();
    void this.recordAuthDiagnostic('auth.init_started');
    await this.registerNativeDeepLinks().catch(() => undefined);
    if (generation !== this.sessionGeneration || this.tokens() !== initialTokens) return;

    const stored = await this.storage.readTokens().catch(async () => {
      if (generation !== this.sessionGeneration || this.tokens() !== initialTokens) return null;
      void this.recordAuthDiagnostic('auth.storage_read_failed');
      const clearedGeneration = this.sessionGeneration + 1;
      await this.clearSession('anonymous', 'auth_storage_read_failed').catch(() => undefined);
      if (this.sessionGeneration !== clearedGeneration || this.tokens()) return null;
      this.error.set('Сессия была повреждена и очищена. Войдите заново.');
      return null;
    });
    if (generation !== this.sessionGeneration || this.tokens() !== initialTokens) return;
    if (!stored) {
      void this.recordAuthDiagnostic('auth.no_stored_session');
      this.clearState('anonymous');
      return;
    }

    try {
      this.tokens.set(stored);
      this.syncUser(stored.accessToken);
    } catch {
      void this.recordAuthDiagnostic('auth.stored_session_invalid');
      const clearedGeneration = this.sessionGeneration + 1;
      await this.clearSession('anonymous', 'stored_session_invalid').catch(() => undefined);
      if (this.sessionGeneration !== clearedGeneration || this.tokens()) return;
      this.error.set('Сессия была повреждена и очищена. Войдите заново.');
      return;
    }

    if (this.isTokenFresh(stored, 45)) {
      this.status.set('authenticated');
      void this.recordAuthDiagnostic('auth.session_restored', this.tokenDiagnosticDetails(stored));
      this.scheduleRefresh();
      void this.flushDiagnostics(stored.accessToken);
      return;
    }

    void this.recordAuthDiagnostic('auth.init_refresh_required', this.tokenDiagnosticDetails(stored));
    await this.refreshTokens();
  }

  isAuthenticated(): boolean {
    const tokens = this.tokens();
    return Boolean(tokens && this.isTokenFresh(tokens, 0));
  }

  async ensureAuthenticated(minValiditySeconds = 30): Promise<boolean> {
    const tokens = this.tokens();
    if (!tokens) {
      return false;
    }

    if (this.isTokenFresh(tokens, minValiditySeconds)) {
      if (this.status() !== 'authenticated') {
        this.status.set('authenticated');
      }
      return true;
    }

    const refreshed = await this.refreshTokens();
    return refreshed.status === 'ready' && this.isAuthenticated();
  }

  hasRealmRole(role: string): boolean {
    return this.user()?.roles.includes(role) ?? false;
  }

  hasAnyRealmRole(roles: readonly string[]): boolean {
    return roles.some((role) => this.hasRealmRole(role));
  }

  async login(targetUrl = '/tabs/home'): Promise<void> {
    const redirectUri = this.isNative ? mobileEnvironment.keycloak.nativeRedirectUri : webRedirectUri();
    const state = this.randomUrlSafeString(32);
    const codeVerifier = this.randomUrlSafeString(64);

    void this.recordAuthDiagnostic('auth.login_started', { target: targetUrl });
    const codeChallenge = await this.codeChallenge(codeVerifier);

    await this.storage.writePendingLogin({
      state,
      codeVerifier,
      targetUrl,
      redirectUri
    });
    void this.recordAuthDiagnostic('auth.pending_login_saved');

    const authUrl = new URL(`${this.issuerUrl()}/protocol/openid-connect/auth`, window.location.origin);
    authUrl.searchParams.set('client_id', mobileEnvironment.keycloak.clientId);
    authUrl.searchParams.set('redirect_uri', redirectUri);
    authUrl.searchParams.set('response_type', 'code');
    authUrl.searchParams.set('scope', this.isNative
      ? 'openid profile email offline_access'
      : 'openid profile email');
    authUrl.searchParams.set('state', state);
    authUrl.searchParams.set('code_challenge', codeChallenge);
    authUrl.searchParams.set('code_challenge_method', 'S256');

    if (this.isNative) {
      await this.openNativeAuthUrl(authUrl.toString());
      return;
    }

    window.location.assign(authUrl.toString());
  }

  async completeLoginFromCallback(callbackUrl: string): Promise<void> {
    const generation = this.sessionGeneration;
    try {
      const url = new URL(callbackUrl);
      const error = url.searchParams.get('error');
      void this.recordAuthDiagnostic('auth.callback_received', {
        hasCode: url.searchParams.has('code'),
        hasState: url.searchParams.has('state'),
        hasError: Boolean(error)
      });
      if (error) {
        void this.recordAuthDiagnostic('auth.callback_oidc_error', { oidcError: error });
        await this.storage.clearPendingLogin();
        if (generation !== this.sessionGeneration) return;
        this.error.set(url.searchParams.get('error_description') ?? error);
        this.status.set('error');
        await this.router.navigateByUrl('/login', { replaceUrl: true });
        return;
      }

      const code = url.searchParams.get('code');
      const state = url.searchParams.get('state');
      const pending = await this.storage.readPendingLogin();
      if (generation !== this.sessionGeneration) return;

      if (!code || !state || !pending || state !== pending.state) {
        void this.recordAuthDiagnostic('auth.callback_rejected', {
          hasCode: Boolean(code),
          hasState: Boolean(state),
          hasPendingLogin: Boolean(pending),
          stateMatched: Boolean(state && pending && state === pending.state)
        });
        await this.storage.clearPendingLogin();
        if (generation !== this.sessionGeneration) return;
        this.error.set('Не удалось подтвердить ответ Keycloak.');
        this.status.set('error');
        await this.router.navigateByUrl('/login', { replaceUrl: true });
        return;
      }

      void this.recordAuthDiagnostic('auth.code_exchange_started');
      const response = await this.requestToken({
        grant_type: 'authorization_code',
        client_id: mobileEnvironment.keycloak.clientId,
        code,
        redirect_uri: pending.redirectUri,
        code_verifier: pending.codeVerifier
      });

      if (!await this.acceptTokens(response, undefined, generation)) {
        return;
      }
      await this.recordAuthDiagnostic('auth.login_succeeded');
      if (generation !== this.sessionGeneration) return;
      const acceptedAccessToken = this.tokens()?.accessToken;
      if (acceptedAccessToken) {
        void this.flushDiagnostics(acceptedAccessToken);
      }
      await this.storage.clearPendingLogin();
      if (generation !== this.sessionGeneration) return;
      await Browser.close().catch(() => undefined);
      if (generation !== this.sessionGeneration) return;
      await this.router.navigateByUrl(pending.targetUrl || '/tabs/home', { replaceUrl: true });
    } catch (error: unknown) {
      if (generation !== this.sessionGeneration) return;
      void this.recordAuthDiagnostic('auth.login_failed', this.errorDiagnosticDetails(error));
      await Browser.close().catch(() => undefined);
      if (generation !== this.sessionGeneration) return;
      await this.storage.clearPendingLogin().catch(() => undefined);
      if (generation !== this.sessionGeneration) return;
      const clearedGeneration = this.sessionGeneration + 1;
      await this.clearSession('error', 'login_callback_failed').catch(() => undefined);
      if (this.sessionGeneration !== clearedGeneration || this.tokens()) return;
      this.error.set(this.errorMessage(error));
      await this.router.navigateByUrl('/login', { replaceUrl: true });
    }
  }

  async getAccessToken(): Promise<string | null> {
    const tokens = this.tokens();
    if (!tokens) {
      return null;
    }

    if (this.isTokenFresh(tokens, 30)) {
      return tokens.accessToken;
    }

    const refreshed = await this.refreshTokens();
    if (refreshed.status === 'temporary-unavailable') {
      throw new AuthTemporarilyUnavailableError();
    }
    return refreshed.status === 'ready' ? this.getOptionalAccessToken(0) : null;
  }

  getOptionalAccessToken(minValiditySeconds = 5): string | null {
    const tokens = this.tokens();
    if (!tokens || !this.isTokenFresh(tokens, minValiditySeconds)) {
      return null;
    }
    return tokens.accessToken;
  }

  async refreshTokens(): Promise<AuthRefreshResult> {
    const tokens = this.tokens();
    if (!tokens?.refreshToken || this.isRefreshExpired(tokens)) {
      void this.recordAuthDiagnostic('auth.refresh_unavailable', {
        hasRefreshToken: Boolean(tokens?.refreshToken),
        refreshExpired: Boolean(tokens && this.isRefreshExpired(tokens))
      });
      if (tokens) {
        const clearedGeneration = this.sessionGeneration + 1;
        await this.clearSession('anonymous', 'refresh_unavailable');
        if (this.sessionGeneration !== clearedGeneration || this.tokens()) return { status: 'superseded' };
      }
      return { status: 'session-invalid' };
    }

    if (this.refreshPromise) {
      return this.refreshPromise;
    }

    const refreshGeneration = this.sessionGeneration;
    this.status.set('refreshing');
    void this.recordAuthDiagnostic('auth.refresh_started', this.tokenDiagnosticDetails(tokens));
    const refresh: Promise<AuthRefreshResult> = this.requestToken({
      grant_type: 'refresh_token',
      client_id: mobileEnvironment.keycloak.clientId,
      refresh_token: tokens.refreshToken
    })
      .then(async (response) => {
        if (refreshGeneration !== this.sessionGeneration || this.tokens() !== tokens) {
          return { status: 'superseded' } as const;
        }
        if (!await this.acceptTokens(response, tokens.refreshToken, refreshGeneration)) {
          return { status: 'superseded' } as const;
        }
        await this.recordAuthDiagnostic('auth.refresh_succeeded');
        if (refreshGeneration !== this.sessionGeneration) {
          return { status: 'superseded' } as const;
        }
        const refreshedAccessToken = this.getOptionalAccessToken(0);
        if (refreshedAccessToken) {
          void this.flushDiagnostics(refreshedAccessToken);
        }
        if (!refreshedAccessToken) {
          this.status.set('retrying');
          return { status: 'temporary-unavailable' } as const;
        }
        return { status: 'ready', accessToken: refreshedAccessToken, refreshed: true } as const;
      })
      .catch(async (error: unknown) => {
        if (refreshGeneration !== this.sessionGeneration || this.tokens() !== tokens) {
          return { status: 'superseded' } as const;
        }
        if (!this.isTerminalRefreshError(error)) {
          void this.recordAuthDiagnostic('auth.refresh_transient_failure', this.errorDiagnosticDetails(error));
          const usableToken = this.getOptionalAccessToken(0);
          this.status.set(usableToken ? 'authenticated' : 'retrying');
          this.error.set('Не удалось обновить сессию. Проверьте соединение, приложение повторит попытку автоматически.');
          this.scheduleRefresh();
          return usableToken
            ? { status: 'ready', accessToken: usableToken, refreshed: false } as const
            : { status: 'temporary-unavailable' } as const;
        }
        await this.recordAuthDiagnostic('auth.refresh_terminal_failure', this.errorDiagnosticDetails(error));
        if (refreshGeneration !== this.sessionGeneration) {
          return { status: 'superseded' } as const;
        }
        if (this.tokens() !== tokens) return { status: 'superseded' } as const;
        const clearedGeneration = this.sessionGeneration + 1;
        await this.clearSession('anonymous', 'refresh_terminal_failure');
        if (this.sessionGeneration !== clearedGeneration || this.tokens()) return { status: 'superseded' } as const;
        this.error.set(this.errorMessage(error));
        return { status: 'session-invalid' } as const;
      })
      .finally(() => {
        if (this.refreshPromise === refresh) {
          this.refreshPromise = null;
        }
      });

    this.refreshPromise = refresh;
    return refresh;
  }

  async logout(): Promise<void> {
    return this.logoutFrom('unknown');
  }

  async logoutFrom(
    source: MobileLogoutSource,
    diagnosticDetails: Record<string, MobileAuthDiagnosticValue> = {}
  ): Promise<void> {
    const accessToken = this.tokens()?.accessToken;
    await this.recordAuthDiagnostic('auth.logout_requested', {
      ...diagnosticDetails,
      source,
      account: this.user()?.preferredUsername ?? 'unknown'
    });
    if (accessToken) {
      void this.flushDiagnostics(accessToken);
    }
    const idToken = this.tokens()?.idToken;
    this.sessionGeneration += 1;
    if (this.refreshTimerId) {
      clearTimeout(this.refreshTimerId);
      this.refreshTimerId = undefined;
    }
    await this.revokeCurrentPushTokenBestEffort();
    await this.clearSession('anonymous');
    void this.recordAuthDiagnostic('auth.logout_local_session_cleared', { ...diagnosticDetails, source });
    await this.storage.clearPendingLogin().catch(error => {
      if (error instanceof AuthStorageDurabilityError) throw error;
    });

    const logoutUrl = new URL(`${this.issuerUrl()}/protocol/openid-connect/logout`, window.location.origin);
    logoutUrl.searchParams.set('client_id', mobileEnvironment.keycloak.clientId);
    logoutUrl.searchParams.set('post_logout_redirect_uri', this.logoutRedirectUri());
    if (idToken) {
      logoutUrl.searchParams.set('id_token_hint', idToken);
    }

    if (this.isNative) {
      await this.openNativeAuthUrl(logoutUrl.toString());
      await this.router.navigateByUrl('/login?loggedOut=1', { replaceUrl: true });
      return;
    }

    window.location.assign(logoutUrl.toString());
  }

  private async revokeCurrentPushTokenBestEffort(): Promise<void> {
    try {
      await this.injector.get(MobilePushService).revokeCurrentTokenBestEffort();
    } catch {
      // Push revoke is additive and must not block logout on old/offline installs.
    }
  }

  async handleUnauthorized(tryRefresh = true, expectedAccessToken?: string | null): Promise<void> {
    if (expectedAccessToken !== undefined && (this.tokens()?.accessToken ?? null) !== expectedAccessToken) return;
    void this.recordAuthDiagnostic('auth.unauthorized_handled', { tryRefresh });
    if (tryRefresh) {
      const refreshed = await this.refreshTokens();
      if (refreshed.status !== 'session-invalid' || this.tokens()) {
        return;
      }
    }

    const clearedGeneration = this.sessionGeneration + 1;
    await this.clearSession('anonymous', 'api_unauthorized');
    if (this.sessionGeneration !== clearedGeneration || this.tokens()) return;
    await this.router.navigateByUrl('/login', { replaceUrl: true });
  }

  private async registerNativeDeepLinks(): Promise<void> {
    if (!this.isNative) {
      return;
    }

    await CapacitorApp.addListener('appUrlOpen', (event) => {
      if (event.url.startsWith(mobileEnvironment.keycloak.nativeRedirectUri)) {
        void this.handleNativeAuthCallback(event.url);
      } else if (event.url.startsWith('otziv://logout')) {
        void Browser.close();
      }
    });

    await CapacitorApp.addListener('appStateChange', ({ isActive }) => {
      if (isActive) {
        void this.resumeSession();
      }
    });

    await Browser.addListener('browserFinished', () => {
      void this.storage.readPendingLogin()
        .then((pending) => this.recordAuthDiagnostic('auth.browser_closed', {
          pendingLogin: Boolean(pending),
          authenticated: this.isAuthenticated()
        }))
        .catch(() => this.recordAuthDiagnostic('auth.browser_closed', {
          pendingLogin: 'unreadable',
          authenticated: this.isAuthenticated()
        }));
    });

    const launchUrl = await CapacitorApp.getLaunchUrl().catch(() => undefined);
    if (launchUrl?.url?.startsWith(mobileEnvironment.keycloak.nativeRedirectUri)) {
      await this.handleNativeAuthCallback(launchUrl.url);
    } else if (launchUrl?.url?.startsWith('otziv://logout')) {
      await Browser.close().catch(() => undefined);
    }
  }

  private async handleNativeAuthCallback(url: string): Promise<void> {
    if (this.handledNativeAuthUrls.has(url)) {
      void this.recordAuthDiagnostic('auth.callback_duplicate_ignored');
      return;
    }

    this.handledNativeAuthUrls.add(url);
    if (this.handledNativeAuthUrls.size > 5) {
      this.handledNativeAuthUrls.clear();
      this.handledNativeAuthUrls.add(url);
    }

    await this.completeLoginFromCallback(url);
  }

  private async resumeSession(): Promise<void> {
    const now = Date.now();
    if (now - this.lastResumeCheckAt < 1500) {
      return;
    }

    this.lastResumeCheckAt = now;
    const generation = this.sessionGeneration;
    const tokens = this.tokens();
    await this.recordAuthDiagnostic('auth.resume_check', {
      hasTokens: Boolean(tokens),
      ...this.tokenDiagnosticDetails(tokens)
    });
    if (!tokens || generation !== this.sessionGeneration || this.tokens() !== tokens) {
      return;
    }

    if (this.isTokenFresh(tokens, 45)) {
      this.status.set('authenticated');
      this.scheduleRefresh();
      void this.flushDiagnostics(tokens.accessToken);
      return;
    }

    await this.refreshTokens();
  }

  private async acceptTokens(
    response: TokenEndpointResponse,
    fallbackRefreshToken?: string,
    expectedGeneration = this.sessionGeneration
  ): Promise<boolean> {
    if (expectedGeneration !== this.sessionGeneration) {
      return false;
    }
    const previousSubject = this.user()?.subject;
    const previousTokens = this.tokens();
    const now = Date.now();
    const tokens: StoredTokens = {
      accessToken: response.access_token,
      tokenType: response.token_type ?? 'Bearer',
      expiresAt: now + response.expires_in * 1000,
      refreshToken: response.refresh_token ?? fallbackRefreshToken,
      refreshExpiresAt: response.refresh_expires_in ? now + response.refresh_expires_in * 1000 : undefined,
      idToken: response.id_token,
      scope: response.scope
    };

    const user = this.userFromToken(tokens.accessToken);
    await this.serializeTokenStorage(async () => {
      if (expectedGeneration !== this.sessionGeneration || this.tokens() !== previousTokens) return;
      await this.storage.writeTokens(tokens);
    });
    if (expectedGeneration !== this.sessionGeneration || this.tokens() !== previousTokens) {
      return false;
    }
    // Publish only a durably acknowledged session. A failed native commit must
    // not leave a new token usable or hide the error as a superseded refresh.
    this.tokens.set(tokens);
    this.user.set(user);
    if (previousSubject !== undefined && previousSubject !== user.subject) {
      this.resetPushRegistrationState();
    }
    this.status.set('authenticated');
    this.error.set(null);
    this.scheduleRefresh();
    return true;
  }

  private async requestToken(params: Record<string, string>): Promise<TokenEndpointResponse> {
    const body = new URLSearchParams(params);
    const url = `${this.issuerUrl()}/protocol/openid-connect/token`;

    if (this.isNative) {
      const response = await CapacitorHttp.post({
        url,
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/x-www-form-urlencoded'
        },
        data: body.toString(),
        responseType: 'json',
        connectTimeout: 15_000,
        readTimeout: 30_000
      });

      if (response.status < 200 || response.status >= 300) {
        throw new TokenEndpointHttpError(response.status);
      }

      return this.parseTokenResponse(response.data);
    }

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body,
      signal: AbortSignal.timeout(30_000)
    });

    if (!response.ok) {
      throw new TokenEndpointHttpError(response.status);
    }

    return response.json() as Promise<TokenEndpointResponse>;
  }

  private isTerminalRefreshError(error: unknown): boolean {
    return error instanceof TokenEndpointHttpError
      && (error.statusCode === 400 || error.statusCode === 401 || error.statusCode === 403);
  }

  private parseTokenResponse(data: unknown): TokenEndpointResponse {
    if (typeof data === 'string') {
      return JSON.parse(data) as TokenEndpointResponse;
    }
    return data as TokenEndpointResponse;
  }

  private async openNativeAuthUrl(url: string): Promise<void> {
    const flow = url.includes('/logout') ? 'logout' : 'login';
    void this.recordAuthDiagnostic('auth.browser_opening', { flow });
    try {
      await Browser.open({
        url,
        presentationStyle: 'fullscreen',
        toolbarColor: '#f6f8fc'
      });
      void this.recordAuthDiagnostic('auth.browser_opened', { flow, method: 'custom_tab' });
      return;
    } catch {
      void this.recordAuthDiagnostic('auth.browser_open_failed', { flow, method: 'custom_tab' });
      // Fall back to the default browser when Custom Tabs are unavailable.
    }

    const result = await AppLauncher.openUrl({ url });
    void this.recordAuthDiagnostic('auth.browser_opened', {
      flow,
      method: 'app_launcher',
      completed: result.completed
    });
    if (!result.completed) {
      throw new Error('Не удалось открыть страницу входа.');
    }
  }

  private scheduleRefresh(): void {
    if (this.refreshTimerId) {
      clearTimeout(this.refreshTimerId);
    }

    const tokens = this.tokens();
    if (!tokens?.refreshToken) {
      return;
    }

    const delay = Math.max(30_000, tokens.expiresAt - Date.now() - 90_000);
    this.refreshTimerId = setTimeout(() => {
      void this.refreshTokens();
    }, delay);
  }

  private syncUser(accessToken: string): void {
    this.user.set(this.userFromToken(accessToken));
  }

  private userFromToken(accessToken: string): AuthUser {
    const claims = this.parseJwt(accessToken);
    const subject = this.stringClaim(claims, 'sub');
    if (!subject) {
      throw new Error('Access token has no subject claim');
    }
    const preferredUsername = this.stringClaim(claims, 'preferred_username') || subject;

    return {
      subject,
      preferredUsername,
      email: this.stringClaim(claims, 'email'),
      name: this.stringClaim(claims, 'name'),
      roles: this.extractRoles(claims)
    };
  }

  private extractRoles(claims: Record<string, unknown>): string[] {
    const roles = new Set<string>();
    this.addStringRoles(roles, claims['roles']);

    const realmAccess = claims['realm_access'];
    if (this.isRecord(realmAccess)) {
      this.addStringRoles(roles, realmAccess['roles']);
    }

    const resourceAccess = claims['resource_access'];
    if (this.isRecord(resourceAccess)) {
      for (const value of Object.values(resourceAccess)) {
        if (this.isRecord(value)) {
          this.addStringRoles(roles, value['roles']);
        }
      }
    }

    return Array.from(roles).sort();
  }

  private addStringRoles(roles: Set<string>, value: unknown): void {
    if (!Array.isArray(value)) {
      return;
    }

    value.filter((role): role is string => typeof role === 'string').forEach((role) => roles.add(role));
  }

  private parseJwt(token: string): Record<string, unknown> {
    const payload = token.split('.')[1];
    if (!payload) {
      return {};
    }

    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized.padEnd(normalized.length + (4 - (normalized.length % 4)) % 4, '=');
    const bytes = Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
  }

  private stringClaim(claims: Record<string, unknown>, key: string): string | undefined {
    const value = claims[key];
    return typeof value === 'string' ? value : undefined;
  }

  private isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
  }

  private isTokenFresh(tokens: StoredTokens, minValiditySeconds: number): boolean {
    return tokens.expiresAt - Date.now() > minValiditySeconds * 1000;
  }

  private isRefreshExpired(tokens: StoredTokens): boolean {
    return Boolean(tokens.refreshExpiresAt && tokens.refreshExpiresAt <= Date.now());
  }

  private issuerUrl(): string {
    const baseUrl = mobileEnvironment.keycloak.url.replace(/\/$/, '');
    return `${baseUrl}/realms/${mobileEnvironment.keycloak.realm}`;
  }

  private logoutRedirectUri(): string {
    if (this.isNative) {
      return 'otziv://logout';
    }

    return `${window.location.origin}${mobileEnvironment.keycloak.logoutRedirectPath}`;
  }

  private async codeChallenge(verifier: string): Promise<string> {
    const data = new TextEncoder().encode(verifier);
    const digest = await crypto.subtle.digest('SHA-256', data);
    return this.base64Url(new Uint8Array(digest));
  }

  private randomUrlSafeString(size: number): string {
    const bytes = new Uint8Array(size);
    crypto.getRandomValues(bytes);
    return this.base64Url(bytes);
  }

  private base64Url(bytes: Uint8Array): string {
    let value = '';
    bytes.forEach((byte) => {
      value += String.fromCharCode(byte);
    });

    return btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  private clearState(status: AuthStatus): void {
    this.resetPushRegistrationState();
    this.tokens.set(null);
    this.user.set(null);
    this.status.set(status);
  }

  private resetPushRegistrationState(): void {
    try {
      this.injector.get(MobilePushService).resetRegistrationState();
    } catch {
      // Clearing local auth state must not depend on optional push support.
    }
  }

  private async clearSession(status: AuthStatus, reason = 'unspecified'): Promise<void> {
    void this.recordAuthDiagnostic('auth.session_clear_started', {
      reason,
      targetStatus: status,
      ...this.tokenDiagnosticDetails(this.tokens())
    });
    this.sessionGeneration += 1;
    if (this.refreshTimerId) {
      clearTimeout(this.refreshTimerId);
      this.refreshTimerId = undefined;
    }

    this.clearState(status);
    await this.serializeTokenStorage(() => this.storage.clearTokens());
    void this.recordAuthDiagnostic('auth.session_cleared', { reason, targetStatus: status });
  }

  private serializeTokenStorage(operation: () => Promise<void>): Promise<void> {
    const work = this.tokenStorageWork.then(operation, operation);
    this.tokenStorageWork = work.catch(() => undefined);
    return work;
  }

  private diagnostics(): MobileAuthDiagnosticsService {
    return this.injector.get(MobileAuthDiagnosticsService);
  }

  private recordAuthDiagnostic(
    type: string,
    details: Record<string, MobileAuthDiagnosticValue> = {}
  ): Promise<void> {
    try {
      return this.diagnostics().record(type, {
        authStatus: this.status(),
        ...details
      });
    } catch {
      return Promise.resolve();
    }
  }

  private flushDiagnostics(accessToken: string): Promise<void> {
    try {
      return this.diagnostics().flush(accessToken);
    } catch {
      return Promise.resolve();
    }
  }

  private tokenDiagnosticDetails(tokens: StoredTokens | null): Record<string, MobileAuthDiagnosticValue> {
    if (!tokens) {
      return {
        hasAccessToken: false,
        hasRefreshToken: false
      };
    }
    return {
      hasAccessToken: true,
      hasRefreshToken: Boolean(tokens.refreshToken),
      accessExpiresInSeconds: Math.round((tokens.expiresAt - Date.now()) / 1000),
      refreshExpiresInSeconds: tokens.refreshExpiresAt
        ? Math.round((tokens.refreshExpiresAt - Date.now()) / 1000)
        : 'unknown'
    };
  }

  private errorDiagnosticDetails(error: unknown): Record<string, MobileAuthDiagnosticValue> {
    return {
      errorClass: error instanceof Error ? error.constructor.name : typeof error,
      httpStatus: error instanceof TokenEndpointHttpError ? error.statusCode : 'none',
      terminal: this.isTerminalRefreshError(error)
    };
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : 'Ошибка авторизации';
  }
}
