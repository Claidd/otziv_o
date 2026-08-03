import { describe, expect, it } from 'vitest';
import { AuthService, hasKeycloakAuthenticationCallback, safeAuthTarget } from './auth.service';

const keycloak = vi.hoisted(() => ({
  authenticated: true,
  token: 'access-token' as string | undefined,
  tokenParsed: { exp: 4_102_444_800 },
  init: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  clearToken: vi.fn(),
  updateToken: vi.fn(),
  hasRealmRole: vi.fn(() => true),
  loadUserProfile: vi.fn()
}));

vi.mock('keycloak-js', () => ({
  default: class MockKeycloak {
    constructor() {
      return keycloak;
    }
  }
}));

class TestAuthService extends AuthService {
  readonly fallbackUrls: string[] = [];

  protected override replaceBrowserLocation(url: string): void {
    this.fallbackUrls.push(url);
  }
}

describe('AuthService revoked-session recovery', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/');
    keycloak.authenticated = true;
    keycloak.token = 'access-token';
    keycloak.logout.mockReset().mockResolvedValue(undefined);
    keycloak.login.mockReset().mockResolvedValue(undefined);
    keycloak.clearToken.mockReset();
    keycloak.updateToken.mockReset().mockResolvedValue(false);
  });

  it('ends the Keycloak SSO session once before restarting a revoked login', async () => {
    const auth = new TestAuthService();

    auth.handleUnauthorized('/admin/analyse?period=all#summary');
    auth.handleUnauthorized('/admin/score');
    await vi.waitFor(() => expect(keycloak.logout).toHaveBeenCalledTimes(1));

    const redirectUri = keycloak.logout.mock.calls[0][0].redirectUri as string;
    const redirect = new URL(redirectUri);
    expect(redirect.origin).toBe(window.location.origin);
    expect(redirect.pathname).toBe('/auth/restart');
    expect(redirect.searchParams.get('target')).toBe('/admin/analyse?period=all');
    expect(keycloak.clearToken).not.toHaveBeenCalled();
    expect(keycloak.login).not.toHaveBeenCalled();
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.getOptionalToken()).toBeNull();
  });

  it('falls back to the same-origin restart page when Keycloak logout fails', async () => {
    keycloak.logout.mockRejectedValue(new Error('end-session unavailable'));
    const auth = new TestAuthService();

    auth.handleUnauthorized('https://evil.example/path');
    await vi.waitFor(() => expect(auth.fallbackUrls).toHaveLength(1));

    const redirect = new URL(auth.fallbackUrls[0]);
    expect(redirect.origin).toBe(window.location.origin);
    expect(redirect.pathname).toBe('/auth/restart');
    expect(redirect.searchParams.get('target')).toBe('/');
    expect(keycloak.clearToken).toHaveBeenCalledTimes(1);
    expect(keycloak.login).not.toHaveBeenCalled();
  });

  it('forces a credential prompt on the restart page', async () => {
    const auth = new TestAuthService();

    await auth.restartLogin('/admin/analyse');

    expect(keycloak.login).toHaveBeenCalledWith({
      redirectUri: `${window.location.origin}/admin/analyse`,
      prompt: 'login'
    });
  });

  it('does not start another logout or navigation from the restart page', async () => {
    window.history.replaceState({}, '', '/auth/restart?target=%2Fadmin%2Fanalyse');
    const auth = new TestAuthService();

    auth.handleUnauthorized('/admin/analyse');
    auth.handleUnauthorized('/admin/score');
    await Promise.resolve();

    expect(keycloak.logout).not.toHaveBeenCalled();
    expect(auth.fallbackUrls).toEqual([]);
    expect(keycloak.clearToken).toHaveBeenCalledTimes(1);
    expect(auth.isAuthenticated()).toBe(false);

    await auth.restartLogin('/admin/analyse');
    expect(keycloak.login).toHaveBeenCalledWith({
      redirectUri: `${window.location.origin}/admin/analyse`,
      prompt: 'login'
    });
  });

  it('does not restart logout when a token refresh fails on the restart page', async () => {
    window.history.replaceState({}, '', '/auth/restart?target=%2Fadmin%2Fanalyse');
    keycloak.updateToken.mockRejectedValue(new Error('refresh rejected'));
    const auth = new TestAuthService();

    await expect(auth.refreshToken()).resolves.toBe(false);

    expect(keycloak.logout).not.toHaveBeenCalled();
    expect(auth.fallbackUrls).toEqual([]);
    expect(keycloak.clearToken).toHaveBeenCalledTimes(1);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('recognizes an encoded restart route before handling an unauthorized response', async () => {
    window.history.replaceState({}, '', '/%61uth/restart?target=%2Fadmin%2Fanalyse');
    const auth = new TestAuthService();

    auth.handleUnauthorized('/admin/analyse');
    await Promise.resolve();

    expect(window.location.pathname).toBe('/%61uth/restart');
    expect(keycloak.logout).not.toHaveBeenCalled();
    expect(auth.fallbackUrls).toEqual([]);
    expect(keycloak.clearToken).toHaveBeenCalledTimes(1);
    expect(auth.isAuthenticated()).toBe(false);
  });
});

describe('safeAuthTarget', () => {
  it.each([
    null,
    '',
    'https://evil.example/path',
    '//evil.example/path',
    '/\\evil.example/path',
    '/keycloak/admin',
    '/%6beycloak/admin',
    '/KEYCLOAK/admin',
    '/auth/restart?target=/admin',
    '/%61uth/restart?target=/admin',
    '/%2561uth/restart?target=/admin',
    '/auth/%72estart?target=/admin',
    '/auth%2frestart?target=/admin',
    '/auth%5crestart?target=/admin'
  ])(
    'rejects an unsafe or recursive target: %s',
    (target) => {
      expect(safeAuthTarget(target)).toBe('/');
    }
  );

  it('keeps an application-local path and query but strips its anchor', () => {
    expect(safeAuthTarget('/admin/analyse?period=all#summary'))
      .toBe('/admin/analyse?period=all');
  });

  it('strips callback-like fragment parameters from a redirect target', () => {
    expect(safeAuthTarget('/admin/analyse?period=all#state=state-1&session_state=session-1&code=code-1'))
      .toBe('/admin/analyse?period=all');
  });
});

describe('hasKeycloakAuthenticationCallback', () => {
  it('recognizes the mobile-safe fragment callback', () => {
    expect(hasKeycloakAuthenticationCallback(
      'https://o-ogo.ru/#state=state-1&session_state=session-1&code=code-1'
    )).toBe(true);
  });

  it('recognizes a callback from the previous query response mode', () => {
    expect(hasKeycloakAuthenticationCallback(
      'https://o-ogo.ru/?state=state-1&session_state=session-1&code=code-1'
    )).toBe(true);
  });

  it('does not mistake an ordinary application URL for an authentication callback', () => {
    expect(hasKeycloakAuthenticationCallback(
      'https://o-ogo.ru/worker?status=publish#orders'
    )).toBe(false);
  });
});
