import type Keycloak from 'keycloak-js';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AuthService, AuthTemporarilyUnavailableError } from './auth.service';
import { authInterceptor } from './auth.interceptor';

const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

describe('web refresh failures and session ownership through the real auth transport', () => {
  let auth: AuthService;
  let sdk: ReturnType<typeof createSdk>;
  let http: HttpClient;
  let requests: HttpTestingController;

  function createSdk() {
    return {
      authenticated: true,
      token: 'token-1' as string | undefined,
      tokenParsed: { exp: Math.floor(Date.now() / 1000) + 600 },
      timeSkew: 0,
      init: vi.fn().mockResolvedValue(true),
      login: vi.fn().mockResolvedValue(undefined),
      logout: vi.fn().mockResolvedValue(undefined),
      clearToken: vi.fn(),
      updateToken: vi.fn().mockResolvedValue(false),
      loadUserProfile: vi.fn().mockResolvedValue({}),
      hasRealmRole: vi.fn().mockReturnValue(true),
      onAuthSuccess: undefined as (() => void) | undefined,
      onAuthLogout: undefined as (() => void) | undefined,
      onAuthRefreshSuccess: undefined as (() => void) | undefined,
      onAuthRefreshError: undefined as (() => void) | undefined
    };
  }

  beforeEach(async () => {
    vi.useFakeTimers();
    window.history.replaceState({}, '', '/');
    sdk = createSdk();
    auth = new AuthService(sdk as unknown as Keycloak);
    await auth.init();
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(),
      { provide: AuthService, useValue: auth }
    ] });
    http = TestBed.inject(HttpClient);
    requests = TestBed.inject(HttpTestingController);
  });

  afterEach(async () => {
    requests.verify();
    await auth.logout();
    vi.useRealTimers();
  });

  it.each([new TypeError('Failed to fetch'), { response: { status: 503 } }])(
    'keeps a valid session after callback-before-reject and a temporary error %s', async error => {
      sdk.updateToken.mockImplementation(async () => { sdk.onAuthRefreshError?.(); throw error; });
      const response = firstValueFrom(http.get('/api/manager/board'));
      await settle();
      const request = requests.expectOne('/api/manager/board');
      expect(request.request.headers.get('Authorization')).toBe('Bearer token-1');
      request.flush({ ok: true });
      await expect(response).resolves.toEqual({ ok: true });
      expect(auth.status()).toBe('temporarily-unavailable');
      expect(auth.isAuthenticated()).toBe(true);
      expect(sdk.logout).not.toHaveBeenCalled();
      expect(sdk.clearToken).not.toHaveBeenCalled();
    }
  );

  it('does not send an expired token while offline and resumes after the network recovers', async () => {
    sdk.tokenParsed.exp = Math.floor(Date.now() / 1000) - 1;
    sdk.updateToken.mockImplementation(async () => { sdk.onAuthRefreshError?.(); throw new TypeError('offline'); });
    await expect(firstValueFrom(http.post('/api/orders', {}))).rejects.toBeInstanceOf(AuthTemporarilyUnavailableError);
    requests.expectNone('/api/orders');
    expect(sdk.logout).not.toHaveBeenCalled();
    sdk.updateToken.mockImplementation(async () => {
      sdk.token = 'token-2'; sdk.tokenParsed.exp += 600; sdk.onAuthRefreshSuccess?.(); return true;
    });
    window.dispatchEvent(new Event('online'));
    await settle();
    expect(auth.status()).toBe('authenticated');
    const response = firstValueFrom(http.post('/api/orders', {}));
    await settle();
    const request = requests.expectOne('/api/orders');
    expect(request.request.headers.get('Authorization')).toBe('Bearer token-2');
    request.flush(null);
    await response;
  });

  it('honors Keycloak clock skew when deciding whether a token can be sent', async () => {
    sdk.timeSkew = 120;
    sdk.tokenParsed.exp = Math.floor(Date.now() / 1000) - 30;
    expect(auth.getOptionalToken(0)).toBe('token-1');
    sdk.timeSkew = -120;
    expect(auth.getOptionalToken(0)).toBeNull();
  });

  it('bounds automatic retries and combines simultaneous refresh callers', async () => {
    const pending = deferred<boolean>();
    sdk.updateToken.mockImplementation(() => pending.promise);
    const first = auth.refreshToken(-1);
    const second = auth.refreshToken(-1);
    expect(sdk.updateToken).toHaveBeenCalledTimes(1);
    sdk.onAuthRefreshError?.(); pending.reject({ response: { status: 503 } });
    await Promise.all([first, second]);
    sdk.updateToken.mockRejectedValue({ response: { status: 503 } });
    await vi.advanceTimersByTimeAsync(2_000 + 5_000 + 15_000);
    expect(sdk.updateToken).toHaveBeenCalledTimes(4);
    await vi.advanceTimersByTimeAsync(30_000 + 60_000);
    expect(sdk.updateToken).toHaveBeenCalledTimes(6);
    // The backoff is capped: while Keycloak stays down a focused tab probes
    // once a minute (the 60 s refresh loop shares the same gate).
    await vi.advanceTimersByTimeAsync(600_000);
    expect(sdk.updateToken).toHaveBeenCalledTimes(16);
    expect(sdk.logout).not.toHaveBeenCalled();
    sdk.updateToken.mockResolvedValue(false);
    window.dispatchEvent(new Event('online')); await settle();
    expect(sdk.updateToken).toHaveBeenCalledTimes(17);
    expect(auth.status()).toBe('authenticated');
  });

  it('recovers by itself after a Keycloak restart without focus or network events', async () => {
    sdk.updateToken.mockRejectedValue({ response: { status: 503 } });
    await auth.refreshToken(-1);
    await vi.advanceTimersByTimeAsync(2_000 + 5_000 + 15_000 + 30_000 + 60_000);
    expect(auth.status()).toBe('temporarily-unavailable');
    const attempts = sdk.updateToken.mock.calls.length;
    sdk.updateToken.mockResolvedValue(true);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(sdk.updateToken.mock.calls.length).toBe(attempts + 1);
    expect(auth.status()).toBe('authenticated');
    expect(auth.error()).toBeNull();
    expect(sdk.logout).not.toHaveBeenCalled();
  });

  it('lets a request that needs a token retry during the backoff, at most once per 10 seconds', async () => {
    sdk.tokenParsed.exp = Math.floor(Date.now() / 1000) - 1;
    sdk.updateToken.mockRejectedValue({ response: { status: 503 } });
    await auth.refreshToken(-1);
    // Five failed attempts; the next automatic one is a minute away.
    await vi.advanceTimersByTimeAsync(2_000 + 5_000 + 15_000 + 30_000 + 10_000);
    const attempts = sdk.updateToken.mock.calls.length;
    expect(attempts).toBe(5);

    await expect(firstValueFrom(http.get('/api/manager/board'))).rejects.toBeInstanceOf(AuthTemporarilyUnavailableError);
    expect(sdk.updateToken.mock.calls.length).toBe(attempts + 1);
    // A burst of requests inside the rate limit does not reach Keycloak.
    await expect(firstValueFrom(http.get('/api/manager/board'))).rejects.toBeInstanceOf(AuthTemporarilyUnavailableError);
    expect(sdk.updateToken.mock.calls.length).toBe(attempts + 1);
    requests.expectNone('/api/manager/board');

    await vi.advanceTimersByTimeAsync(10_000);
    sdk.updateToken.mockImplementation(async () => {
      sdk.token = 'token-2'; sdk.tokenParsed.exp = Math.floor(Date.now() / 1000) + 600; return true;
    });
    const response = firstValueFrom(http.get('/api/manager/board'));
    await settle();
    expect(sdk.updateToken.mock.calls.length).toBe(attempts + 2);
    const request = requests.expectOne('/api/manager/board');
    expect(request.request.headers.get('Authorization')).toBe('Bearer token-2');
    request.flush({ ok: true });
    await expect(response).resolves.toEqual({ ok: true });
    expect(auth.status()).toBe('authenticated');
  });

  it('still expires a rejected refresh when Keycloak clears the token before the rejection', async () => {
    sdk.updateToken.mockImplementation(async () => {
      sdk.authenticated = false; sdk.token = undefined;
      sdk.onAuthLogout?.(); sdk.onAuthRefreshError?.();
      throw { response: { status: 400 } };
    });
    await auth.refreshToken(-1); await settle();
    expect(auth.status()).toBe('expired');
    expect(auth.isAuthenticated()).toBe(false);
    expect(sdk.logout).toHaveBeenCalledTimes(1);
  });

  it.each(['resolve', 'reject'] as const)('ignores a late refresh %s after logout', async outcome => {
    const pending = deferred<boolean>(); sdk.updateToken.mockReturnValue(pending.promise);
    const refresh = auth.refreshToken(-1);
    await auth.logout();
    if (outcome === 'resolve') { sdk.onAuthRefreshSuccess?.(); pending.resolve(true); }
    else { sdk.onAuthRefreshError?.(); pending.reject({ response: { status: 503 } }); }
    await refresh;
    expect(auth.status()).toBe('anonymous');
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.getOptionalToken()).toBeNull();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(sdk.updateToken).toHaveBeenCalledTimes(1);
  });

  it('ignores an old refresh error and old API 401 after a new authenticated session', async () => {
    const response = firstValueFrom(http.get('/api/manager/board')).catch(error => error);
    await settle(); const oldRequest = requests.expectOne('/api/manager/board');
    const pending = deferred<boolean>(); sdk.updateToken.mockReturnValue(pending.promise);
    const refresh = auth.refreshToken(-1);
    sdk.token = 'new-login'; sdk.onAuthSuccess?.(); await settle();
    sdk.onAuthRefreshError?.(); pending.reject({ response: { status: 401 } });
    await refresh;
    oldRequest.flush(null, { status: 401, statusText: 'Unauthorized' }); await response;
    expect(auth.status()).toBe('authenticated');
    expect(auth.getOptionalToken()).toBe('new-login');
    expect(sdk.logout).not.toHaveBeenCalled();
  });

  it('ignores a late old-token 401 after refresh and never replays a stale-token write', async () => {
    const oldResponse = firstValueFrom(http.get('/api/old')).catch(error => error);
    await settle(); const old = requests.expectOne('/api/old');
    const writeResponse = firstValueFrom(http.post('/api/orders', {})).catch(error => error);
    await settle(); const write = requests.expectOne('/api/orders');
    sdk.updateToken.mockImplementation(async () => { sdk.token = 'token-2'; return true; });
    write.flush({ code: 'AUTH_TOKEN_STALE' }, { status: 403, statusText: 'Forbidden' });
    await writeResponse;
    requests.expectNone('/api/orders');
    old.flush(null, { status: 401, statusText: 'Unauthorized' }); await oldResponse;
    expect(sdk.logout).not.toHaveBeenCalled();
    expect(auth.getOptionalToken()).toBe('token-2');
  });
});
