import { provideHttpClient, HttpClient, HttpErrorResponse, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Injector } from '@angular/core';
import { Router, convertToParamMap, type ActivatedRouteSnapshot, type RouterStateSnapshot } from '@angular/router';
import { Capacitor } from '@capacitor/core';
import { App as CapacitorApp } from '@capacitor/app';
import { Browser } from '@capacitor/browser';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';
import { AuthTemporarilyUnavailableError, type StoredTokens } from './auth.models';
import { authInterceptor } from './auth.interceptor';
import { MobileAuthStorageService } from './mobile-auth-storage.service';
import { MobileAuthDiagnosticsService } from './mobile-auth-diagnostics.service';
import { MobilePushService } from './mobile-push.service';
import { ManagerReportReviewAccessService } from './manager-report-review-access.service';
import { roleGuard } from './role.guard';

vi.mock('@capacitor/app', () => ({ App: { addListener: vi.fn(), getLaunchUrl: vi.fn() } }));
vi.mock('@capacitor/browser', () => ({ Browser: { addListener: vi.fn(), close: vi.fn().mockResolvedValue(undefined) } }));

const jwt = (subject: string) => `e30.${btoa(JSON.stringify({ sub: subject, realm_access: { roles: ['MANAGER'] } }))}.signature`;
const microtasks = async () => { for (let i = 0; i < 20; i += 1) { await Promise.resolve(); } };

describe('mobile authentication lifecycle', () => {
  let auth: AuthService;
  let http: HttpClient;
  let requests: HttpTestingController;
  let storage: { readTokens: ReturnType<typeof vi.fn>; writeTokens: ReturnType<typeof vi.fn>; clearTokens: ReturnType<typeof vi.fn>; clearPendingLogin: ReturnType<typeof vi.fn>; readPendingLogin: ReturnType<typeof vi.fn> };
  let transport: ReturnType<typeof vi.fn>;
  let router: { navigateByUrl: ReturnType<typeof vi.fn>; createUrlTree: ReturnType<typeof vi.fn>; navigate: ReturnType<typeof vi.fn> };

  function retain(seconds = -60): StoredTokens {
    const tokens = { accessToken: jwt('old'), tokenType: 'Bearer', expiresAt: Date.now() + seconds * 1000, refreshToken: 'test-refresh' };
    auth.tokens.set(tokens);
    auth.user.set({ subject: 'old', preferredUsername: 'old', roles: ['MANAGER'] });
    auth.status.set('authenticated');
    return tokens;
  }

  function refreshedResponse() {
    return new Response(JSON.stringify({ access_token: jwt('new'), expires_in: 300, refresh_token: 'new-test-refresh' }), { status: 200 });
  }

  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(Capacitor, 'isNativePlatform').mockReturnValue(false);
    storage = { readTokens: vi.fn().mockResolvedValue(null), writeTokens: vi.fn().mockResolvedValue(undefined), clearTokens: vi.fn().mockResolvedValue(undefined), clearPendingLogin: vi.fn().mockResolvedValue(undefined), readPendingLogin: vi.fn().mockResolvedValue(null) };
    router = { navigateByUrl: vi.fn().mockResolvedValue(true), createUrlTree: vi.fn().mockReturnValue('login-tree'), navigate: vi.fn().mockResolvedValue(true) };
    transport = vi.fn();
    vi.stubGlobal('fetch', transport);
    TestBed.configureTestingModule({ providers: [
      AuthService,
      provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(),
      { provide: MobileAuthStorageService, useValue: storage },
      { provide: Router, useValue: router },
      { provide: MobileAuthDiagnosticsService, useValue: { initialize: vi.fn(), record: vi.fn().mockResolvedValue(undefined), flush: vi.fn().mockResolvedValue(undefined) } },
      { provide: MobilePushService, useValue: { resetRegistrationState: vi.fn(), revokeCurrentTokenBestEffort: vi.fn().mockResolvedValue(undefined) } },
      { provide: ManagerReportReviewAccessService, useValue: { refresh: vi.fn().mockResolvedValue({ restricted: false }) } }
    ] });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpClient);
    requests = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    requests.verify();
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('does not contact the token endpoint when the cached access token is fresh', async () => {
    const token = retain(300);
    expect(await auth.ensureAuthenticated()).toBe(true);
    expect(await auth.getAccessToken()).toBe(token.accessToken);
    expect(transport).not.toHaveBeenCalled();
  });

  it.each(['network', 503])('retains an expired session without claiming a usable token after %s', async (failure) => {
    const saved = retain();
    if (failure === 'network') { transport.mockRejectedValue(new TypeError('offline')); }
    else { transport.mockResolvedValue(new Response('', { status: 503 })); }
    expect(await auth.refreshTokens()).toEqual({ status: 'temporary-unavailable' });
    expect(await auth.ensureAuthenticated()).toBe(false);
    await expect(auth.getAccessToken()).rejects.toBeInstanceOf(AuthTemporarilyUnavailableError);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.status()).toBe('retrying');
    expect(auth.tokens()).toBe(saved);
    expect(storage.clearTokens).not.toHaveBeenCalled();
  });

  it('keeps a still-valid token usable during a proactive refresh outage', async () => {
    const saved = retain(10);
    transport.mockRejectedValue(new TypeError('offline'));
    expect(await auth.getAccessToken()).toBe(saved.accessToken);
    expect(auth.isAuthenticated()).toBe(true);
    expect(storage.clearTokens).not.toHaveBeenCalled();
  });

  it('restores an offline session at startup and retries it later', async () => {
    const saved = retain();
    storage.readTokens.mockResolvedValue(saved);
    transport.mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(refreshedResponse());
    await auth.init();
    expect(auth.status()).toBe('retrying');
    expect(storage.clearTokens).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(auth.status()).toBe('authenticated');
    expect(auth.getOptionalAccessToken()).toBe(jwt('new'));
  });

  it.each([400, 401, 403])('clears a terminally rejected refresh (%s)', async (status) => {
    retain();
    transport.mockResolvedValue(new Response('', { status }));
    expect(await auth.refreshTokens()).toEqual({ status: 'session-invalid' });
    expect(auth.tokens()).toBeNull();
    expect(storage.clearTokens).toHaveBeenCalledTimes(1);
  });

  it('shares one refresh across concurrent protected reads', async () => {
    retain();
    transport.mockResolvedValue(refreshedResponse());
    const first = firstValueFrom(http.get('/api/first'));
    const second = firstValueFrom(http.get('/api/second'));
    await microtasks();
    expect(transport).toHaveBeenCalledTimes(1);
    for (const url of ['/api/first', '/api/second']) {
      const request = requests.expectOne(url);
      expect(request.request.headers.get('Authorization')).toBe(`Bearer ${jwt('new')}`);
      request.flush({ ok: true });
    }
    await Promise.all([first, second]);
  });

  it('sends no protected request and does not logout during an expired-token outage', async () => {
    retain();
    transport.mockRejectedValue(new TypeError('offline'));
    const result = firstValueFrom(http.get('/api/first')).catch(error => error);
    expect(await result).toBeInstanceOf(AuthTemporarilyUnavailableError);
    requests.expectNone('/api/first');
    expect(storage.clearTokens).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('retries a rejected read once, then invalidates a repeatedly rejected session', async () => {
    retain(300);
    transport.mockResolvedValue(refreshedResponse());
    const result = firstValueFrom(http.get('/api/first')).catch(error => error);
    await microtasks();
    requests.expectOne('/api/first').flush({}, { status: 401, statusText: 'Unauthorized' });
    await microtasks();
    requests.expectOne('/api/first').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(await result).toBeInstanceOf(HttpErrorResponse);
    await microtasks();
    expect(transport).toHaveBeenCalledTimes(1);
    expect(auth.tokens()).toBeNull();
  });

  it('does not automatically replay a financial write after 401', async () => {
    retain(300);
    transport.mockResolvedValue(refreshedResponse());
    const result = firstValueFrom(http.post('/api/payments/order/1', { operationKey: 'same-command' })).catch(error => error);
    await microtasks();
    requests.expectOne('/api/payments/order/1').flush({}, { status: 401, statusText: 'Unauthorized' });
    await microtasks();
    expect((await result).status).toBe(401);
    requests.expectNone('/api/payments/order/1');
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('preserves a rejected but unexpired session when the refresh server is unavailable', async () => {
    const saved = retain(300);
    transport.mockResolvedValue(new Response('', { status: 503 }));
    const result = firstValueFrom(http.get('/api/first')).catch(error => error);
    await microtasks();
    requests.expectOne('/api/first').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(await result).toBeInstanceOf(AuthTemporarilyUnavailableError);
    requests.expectNone('/api/first');
    expect(auth.tokens()).toBe(saved);
    expect(storage.clearTokens).not.toHaveBeenCalled();
  });

  it('ignores a refresh response that arrives after local logout', async () => {
    retain();
    let resolve!: (response: Response) => void;
    transport.mockReturnValue(new Promise<Response>(done => { resolve = done; }));
    const refresh = auth.refreshTokens();
    await auth.handleUnauthorized(false);
    resolve(refreshedResponse());
    expect(await refresh).toEqual({ status: 'superseded' });
    expect(auth.tokens()).toBeNull();
    expect(storage.writeTokens).not.toHaveBeenCalled();
  });

  it('does not restore startup storage data after local logout', async () => {
    const saved = retain(300);
    auth.tokens.set(null);
    let resolveRead!: (tokens: StoredTokens) => void;
    storage.readTokens.mockReturnValue(new Promise<StoredTokens>(resolve => { resolveRead = resolve; }));
    const init = auth.init(); await microtasks();
    await auth.handleUnauthorized(false);
    resolveRead(saved); await init;
    expect(auth.tokens()).toBeNull();
    expect(auth.status()).toBe('anonymous');
  });

  it('does not accept a login code exchange that finishes after logout', async () => {
    storage.readPendingLogin.mockResolvedValue({ state: 'test-state', codeVerifier: 'test-verifier', redirectUri: 'https://example.test/callback', targetUrl: '/tabs/home' });
    let resolveExchange!: (response: Response) => void;
    transport.mockReturnValue(new Promise<Response>(resolve => { resolveExchange = resolve; }));
    const login = auth.completeLoginFromCallback('https://example.test/callback?state=test-state&code=test-code');
    await microtasks();
    await auth.handleUnauthorized(false);
    resolveExchange(refreshedResponse()); await login;
    expect(auth.tokens()).toBeNull();
    expect(storage.writeTokens).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalledWith('/tabs/home', expect.anything());
  });

  it('does not resume a logged-out native session after a delayed diagnostic write', async () => {
    vi.mocked(Capacitor.isNativePlatform).mockReturnValue(true);
    let resume!: (state: { isActive: boolean }) => void;
    vi.spyOn(CapacitorApp, 'addListener').mockImplementation((async (name: string, listener: unknown) => {
      if (name === 'appStateChange') resume = listener as typeof resume;
      return { remove: async () => undefined };
    }) as typeof CapacitorApp.addListener);
    vi.spyOn(CapacitorApp, 'getLaunchUrl').mockResolvedValue(undefined);
    vi.spyOn(Browser, 'addListener').mockResolvedValue({ remove: async () => undefined });
    storage.readTokens.mockResolvedValue(retain(300));
    const nativeAuth = new AuthService(router as unknown as Router, storage as unknown as MobileAuthStorageService, TestBed.inject(Injector));
    await nativeAuth.init();
    const diagnostics = TestBed.inject(MobileAuthDiagnosticsService);
    let finishDiagnostic!: () => void;
    vi.mocked(diagnostics.record).mockImplementation(event => event === 'auth.resume_check'
      ? new Promise<void>(resolve => { finishDiagnostic = resolve; }) : Promise.resolve());
    resume({ isActive: true }); await microtasks();
    await nativeAuth.handleUnauthorized(false);
    finishDiagnostic(); await microtasks();
    expect(nativeAuth.tokens()).toBeNull();
    expect(nativeAuth.status()).toBe('anonymous');
  });

  it('serializes secure writes and removal when logout overlaps persistence', async () => {
    retain();
    let completeWrite!: () => void;
    const order: string[] = [];
    storage.writeTokens.mockImplementation(() => new Promise<void>(resolve => { completeWrite = () => { order.push('write'); resolve(); }; }));
    storage.clearTokens.mockImplementation(async () => { order.push('clear'); });
    transport.mockResolvedValue(refreshedResponse());
    const refresh = auth.refreshTokens();
    await microtasks();
    const logout = auth.handleUnauthorized(false);
    await microtasks();
    completeWrite();
    await logout;
    expect(await refresh).toEqual({ status: 'superseded' });
    expect(order).toEqual(['write', 'clear']);
    expect(auth.tokens()).toBeNull();
  });

  it('routes an expired retained session to the recoverable login screen without deleting it', async () => {
    retain(); transport.mockRejectedValue(new TypeError('offline'));
    const route = { data: { roles: ['MANAGER'] }, queryParamMap: convertToParamMap({}) } as unknown as ActivatedRouteSnapshot;
    const state = { url: '/tabs/orders' } as RouterStateSnapshot;
    const result = await TestBed.runInInjectionContext(() => roleGuard(route, state));
    expect(result).toBe('login-tree');
    expect(router.createUrlTree).toHaveBeenCalledWith(['/login'], { queryParams: { target: '/tabs/orders' } });
    expect(auth.status()).toBe('retrying');
    expect(storage.clearTokens).not.toHaveBeenCalled();
  });

  it('does not overwrite a newer login with an older refresh response', async () => {
    retain(); let finishRefresh!: (response: Response) => void; transport.mockReturnValue(new Promise<Response>(resolve => finishRefresh = resolve));
    const refresh = auth.refreshTokens(); const replacement = { ...retain(300), accessToken: jwt('another-user') }; auth.tokens.set(replacement);
    finishRefresh(refreshedResponse()); expect(await refresh).toEqual({ status: 'superseded' });
    expect(auth.tokens()).toBe(replacement); expect(storage.writeTokens).not.toHaveBeenCalled();
  });

  it('ignores a late protected 401 issued under a different login', async () => {
    retain(300); const result = firstValueFrom(http.get('/api/old-session')).catch(error => error); await microtasks();
    const request = requests.expectOne('/api/old-session'); const replacement = { ...retain(300), accessToken: jwt('another-user') }; auth.tokens.set(replacement);
    request.flush({}, { status: 401, statusText: 'Unauthorized' }); await result;
    expect(transport).not.toHaveBeenCalled(); expect(storage.clearTokens).not.toHaveBeenCalled(); expect(auth.tokens()).toBe(replacement);
  });

  it('does not revoke a new login when an old retried request receives 401', async () => {
    retain(300); transport.mockResolvedValue(refreshedResponse());
    const result = firstValueFrom(http.get('/api/retried')).catch(error => error); await microtasks();
    requests.expectOne('/api/retried').flush({}, { status: 401, statusText: 'Unauthorized' }); await microtasks();
    const retry = requests.expectOne('/api/retried'); const replacement = { ...retain(300), accessToken: jwt('another-user') }; auth.tokens.set(replacement);
    retry.flush({}, { status: 401, statusText: 'Unauthorized' }); await result; await microtasks();
    expect(storage.clearTokens).not.toHaveBeenCalled(); expect(auth.tokens()).toBe(replacement); expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('does not navigate a new login away after an old secure removal finishes', async () => {
    retain(300); let finishClear!: () => void; storage.clearTokens.mockReturnValue(new Promise<void>(resolve => finishClear = resolve));
    const clearing = auth.handleUnauthorized(false); await microtasks();
    const replacement = { ...retain(300), accessToken: jwt('another-user') }; auth.tokens.set(replacement); finishClear(); await clearing;
    expect(auth.tokens()).toBe(replacement); expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('keeps public capability requests anonymous and independent from refresh', async () => {
    retain(); transport.mockRejectedValue(new TypeError('offline'));
    const result = firstValueFrom(http.get('/api/review-check/token'));
    const request = requests.expectOne('/api/review-check/token');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({ allowed: true });
    await result;
    expect(transport).not.toHaveBeenCalled();
    expect(storage.clearTokens).not.toHaveBeenCalled();
  });
});
