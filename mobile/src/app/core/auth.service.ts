import { Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { App as CapacitorApp } from '@capacitor/app';
import { AppLauncher } from '@capacitor/app-launcher';
import { Browser } from '@capacitor/browser';
import { Capacitor, CapacitorHttp } from '@capacitor/core';
import { mobileEnvironment, webRedirectUri } from './mobile-environment';
import type { AuthStatus, AuthUser, StoredTokens, TokenEndpointResponse } from './auth.models';
import { MobileAuthStorageService } from './mobile-auth-storage.service';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly isNative = Capacitor.isNativePlatform();
  private refreshTimerId: ReturnType<typeof setTimeout> | undefined;
  private refreshPromise: Promise<boolean> | null = null;
  private initialized = false;
  private readonly handledNativeAuthUrls = new Set<string>();

  readonly status = signal<AuthStatus>('initializing');
  readonly user = signal<AuthUser | null>(null);
  readonly tokens = signal<StoredTokens | null>(null);
  readonly error = signal<string | null>(null);

  constructor(
    private readonly router: Router,
    private readonly storage: MobileAuthStorageService
  ) {}

  async init(): Promise<void> {
    if (this.initialized) {
      return;
    }

    this.initialized = true;
    await this.registerNativeDeepLinks().catch(() => undefined);

    const stored = await this.storage.readTokens().catch(async () => {
      await this.storage.clearTokens().catch(() => undefined);
      this.error.set('Сессия была повреждена и очищена. Войдите заново.');
      return null;
    });
    if (!stored) {
      this.clearState('anonymous');
      return;
    }

    this.tokens.set(stored);
    this.syncUser(stored.accessToken);

    if (this.isTokenFresh(stored, 45)) {
      this.status.set('authenticated');
      this.scheduleRefresh();
      return;
    }

    const refreshed = await this.refreshTokens();
    if (!refreshed) {
      await this.clearSession('anonymous');
    }
  }

  isAuthenticated(): boolean {
    const tokens = this.tokens();
    return Boolean(tokens && this.isTokenFresh(tokens, 0));
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
    const codeChallenge = await this.codeChallenge(codeVerifier);

    await this.storage.writePendingLogin({
      state,
      codeVerifier,
      targetUrl,
      redirectUri
    });

    const authUrl = new URL(`${this.issuerUrl()}/protocol/openid-connect/auth`, window.location.origin);
    authUrl.searchParams.set('client_id', mobileEnvironment.keycloak.clientId);
    authUrl.searchParams.set('redirect_uri', redirectUri);
    authUrl.searchParams.set('response_type', 'code');
    authUrl.searchParams.set('scope', 'openid profile email');
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
    try {
      const url = new URL(callbackUrl);
      const error = url.searchParams.get('error');
      if (error) {
        await this.storage.clearPendingLogin();
        this.error.set(url.searchParams.get('error_description') ?? error);
        this.status.set('error');
        await this.router.navigateByUrl('/login', { replaceUrl: true });
        return;
      }

      const code = url.searchParams.get('code');
      const state = url.searchParams.get('state');
      const pending = await this.storage.readPendingLogin();

      if (!code || !state || !pending || state !== pending.state) {
        await this.storage.clearPendingLogin();
        this.error.set('Не удалось подтвердить ответ Keycloak.');
        this.status.set('error');
        await this.router.navigateByUrl('/login', { replaceUrl: true });
        return;
      }

      const response = await this.requestToken({
        grant_type: 'authorization_code',
        client_id: mobileEnvironment.keycloak.clientId,
        code,
        redirect_uri: pending.redirectUri,
        code_verifier: pending.codeVerifier
      });

      await this.acceptTokens(response);
      await this.storage.clearPendingLogin();
      await Browser.close().catch(() => undefined);
      await this.router.navigateByUrl(pending.targetUrl || '/tabs/home', { replaceUrl: true });
    } catch (error: unknown) {
      await Browser.close().catch(() => undefined);
      await this.storage.clearPendingLogin().catch(() => undefined);
      await this.clearSession('error').catch(() => this.clearState('error'));
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
    return refreshed ? this.tokens()?.accessToken ?? null : null;
  }

  async refreshTokens(): Promise<boolean> {
    const tokens = this.tokens();
    if (!tokens?.refreshToken || this.isRefreshExpired(tokens)) {
      return false;
    }

    if (this.refreshPromise) {
      return this.refreshPromise;
    }

    this.status.set('refreshing');
    this.refreshPromise = this.requestToken({
      grant_type: 'refresh_token',
      client_id: mobileEnvironment.keycloak.clientId,
      refresh_token: tokens.refreshToken
    })
      .then(async (response) => {
        await this.acceptTokens(response, tokens.refreshToken);
        return true;
      })
      .catch(async (error: unknown) => {
        await this.clearSession('anonymous');
        this.error.set(this.errorMessage(error));
        return false;
      })
      .finally(() => {
        this.refreshPromise = null;
      });

    return this.refreshPromise;
  }

  async logout(): Promise<void> {
    const idToken = this.tokens()?.idToken;
    await this.clearSession('anonymous');

    const logoutUrl = new URL(`${this.issuerUrl()}/protocol/openid-connect/logout`, window.location.origin);
    logoutUrl.searchParams.set('client_id', mobileEnvironment.keycloak.clientId);
    logoutUrl.searchParams.set('post_logout_redirect_uri', this.logoutRedirectUri());
    if (idToken) {
      logoutUrl.searchParams.set('id_token_hint', idToken);
    }

    if (this.isNative) {
      await this.openNativeAuthUrl(logoutUrl.toString());
      await this.router.navigateByUrl('/login', { replaceUrl: true });
      return;
    }

    window.location.assign(logoutUrl.toString());
  }

  async handleUnauthorized(): Promise<void> {
    await this.clearSession('anonymous');
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

    const launchUrl = await CapacitorApp.getLaunchUrl().catch(() => undefined);
    if (launchUrl?.url?.startsWith(mobileEnvironment.keycloak.nativeRedirectUri)) {
      await this.handleNativeAuthCallback(launchUrl.url);
    } else if (launchUrl?.url?.startsWith('otziv://logout')) {
      await Browser.close().catch(() => undefined);
    }
  }

  private async handleNativeAuthCallback(url: string): Promise<void> {
    if (this.handledNativeAuthUrls.has(url)) {
      return;
    }

    this.handledNativeAuthUrls.add(url);
    if (this.handledNativeAuthUrls.size > 5) {
      this.handledNativeAuthUrls.clear();
      this.handledNativeAuthUrls.add(url);
    }

    await this.completeLoginFromCallback(url);
  }

  private async acceptTokens(response: TokenEndpointResponse, fallbackRefreshToken?: string): Promise<void> {
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

    this.tokens.set(tokens);
    this.syncUser(tokens.accessToken);
    this.status.set('authenticated');
    this.error.set(null);
    await this.storage.writeTokens(tokens);
    this.scheduleRefresh();
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
        throw new Error(`Keycloak token request failed: ${response.status}`);
      }

      return this.parseTokenResponse(response.data);
    }

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body
    });

    if (!response.ok) {
      throw new Error(`Keycloak token request failed: ${response.status}`);
    }

    return response.json() as Promise<TokenEndpointResponse>;
  }

  private parseTokenResponse(data: unknown): TokenEndpointResponse {
    if (typeof data === 'string') {
      return JSON.parse(data) as TokenEndpointResponse;
    }
    return data as TokenEndpointResponse;
  }

  private async openNativeAuthUrl(url: string): Promise<void> {
    try {
      await Browser.open({
        url,
        presentationStyle: 'fullscreen',
        toolbarColor: '#f6f8fc'
      });
      return;
    } catch {
      // Fall back to the default browser when Custom Tabs are unavailable.
    }

    const result = await AppLauncher.openUrl({ url });
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
    const claims = this.parseJwt(accessToken);
    const subject = this.stringClaim(claims, 'sub') || '';
    const preferredUsername = this.stringClaim(claims, 'preferred_username') || subject;

    this.user.set({
      subject,
      preferredUsername,
      email: this.stringClaim(claims, 'email'),
      name: this.stringClaim(claims, 'name'),
      roles: this.extractRoles(claims)
    });
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
    this.tokens.set(null);
    this.user.set(null);
    this.status.set(status);
  }

  private async clearSession(status: AuthStatus): Promise<void> {
    if (this.refreshTimerId) {
      clearTimeout(this.refreshTimerId);
      this.refreshTimerId = undefined;
    }

    this.clearState(status);
    await this.storage.clearTokens();
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : 'Ошибка авторизации';
  }
}
