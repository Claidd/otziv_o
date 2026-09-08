import { Injector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Browser } from '@capacitor/browser';
import { Capacitor, CapacitorHttp } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
import { SecureStorage } from '@aparajita/capacitor-secure-storage';
import { AuthService } from './auth.service';
import { MobileAuthStorageService } from './mobile-auth-storage.service';
import { MobileAuthDiagnosticsService } from './mobile-auth-diagnostics.service';
import { MobilePushService } from './mobile-push.service';
import { TOKEN_REVOCATION_MARKER } from './auth-token-revocation';
import { AuthStorageDurability, type AuthStorageStore } from './auth-storage-durability.plugin';
import type { PendingLogin, StoredTokens } from './auth.models';

vi.mock('@capacitor/core', () => ({
  Capacitor: { getPlatform: vi.fn(), isNativePlatform: vi.fn(), isPluginAvailable: vi.fn() },
  CapacitorHttp: { post: vi.fn() },
  registerPlugin: () => ({ flush: vi.fn() })
}));
vi.mock('@capacitor/preferences', () => ({ Preferences: { get: vi.fn(), set: vi.fn(), remove: vi.fn() } }));
vi.mock('@aparajita/capacitor-secure-storage', () => ({ SecureStorage: { get: vi.fn(), set: vi.fn(), remove: vi.fn() } }));
vi.mock('@capacitor/browser', () => ({ Browser: { open: vi.fn(), close: vi.fn() } }));

const TOKEN = 'otziv.mobile.tokens';
const MARKER = 'otziv.mobile.tokens.revoked';
const PENDING = 'otziv.mobile.pendingLogin';
const jwt = (subject: string) => `e30.${btoa(JSON.stringify({ sub: subject, realm_access: { roles: ['MANAGER'] } }))}.signature`;
const tokens = (subject = 'old', seconds = 300): StoredTokens => ({
  accessToken: jwt(subject), tokenType: 'Bearer', expiresAt: Date.now() + seconds * 1000, refreshToken: `${subject}-refresh`
});
const pending: PendingLogin = {
  state: '0123456789abcdef', codeVerifier: '0123456789abcdef0123456789abcdef', targetUrl: '/tabs/home', redirectUri: 'otziv://auth/callback'
};
const bridge = { flush: vi.mocked(AuthStorageDurability.flush), post: vi.mocked(CapacitorHttp.post) };
const microtasks = async () => { for (let i = 0; i < 80; i += 1) await Promise.resolve(); };

describe('native auth storage durability through the real services', () => {
  let storage: MobileAuthStorageService;
  let memory: Record<AuthStorageStore, Map<string, unknown>>;
  let disk: Record<AuthStorageStore, Map<string, unknown>>;
  let events: string[];
  let router: { navigateByUrl: ReturnType<typeof vi.fn> };

  function commit(store: AuthStorageStore) {
    disk[store] = new Map([...memory[store]].map(([key, value]) => [key, structuredClone(value)]));
  }

  function seed(key: string, value: unknown, store: AuthStorageStore = 'secure') {
    memory[store].set(key, value);
    commit(store);
  }

  function restart(): MobileAuthStorageService {
    memory = { secure: new Map(disk.secure), preferences: new Map(disk.preferences) };
    return new MobileAuthStorageService();
  }

  function failFlush(position: number) {
    let count = 0;
    bridge.flush.mockImplementation(async ({ store }: { store: AuthStorageStore }) => {
      events.push(`flush:${store}`);
      if (++count === position) throw new Error('fixture commit failed');
      commit(store);
    });
  }

  function holdNextFlush(storeToHold: AuthStorageStore) {
    let release!: () => void;
    let held = false;
    bridge.flush.mockImplementation(async ({ store }: { store: AuthStorageStore }) => {
      events.push(`flush:${store}`);
      if (store === storeToHold && !held) {
        held = true;
        await new Promise<void>(resolve => { release = resolve; });
      }
      commit(store);
    });
    return () => release();
  }

  function createAuth(saved?: StoredTokens) {
    TestBed.configureTestingModule({ providers: [
      { provide: MobileAuthStorageService, useValue: storage },
      { provide: Router, useValue: router },
      { provide: MobileAuthDiagnosticsService, useValue: {
        initialize: vi.fn(), record: vi.fn().mockResolvedValue(undefined), flush: vi.fn().mockResolvedValue(undefined)
      } },
      { provide: MobilePushService, useValue: {
        resetRegistrationState: vi.fn(), revokeCurrentTokenBestEffort: vi.fn().mockResolvedValue(undefined)
      } }
    ] });
    const auth = new AuthService(router as unknown as Router, storage, TestBed.inject(Injector));
    if (saved) {
      auth.tokens.set(saved);
      auth.user.set({ subject: 'old', preferredUsername: 'old', roles: ['MANAGER'] });
      auth.status.set('authenticated');
    }
    return auth;
  }

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    vi.mocked(Capacitor.getPlatform).mockReturnValue('android');
    vi.mocked(Capacitor.isNativePlatform).mockReturnValue(true);
    vi.mocked(Capacitor.isPluginAvailable).mockReturnValue(true);
    memory = { secure: new Map(), preferences: new Map() };
    disk = { secure: new Map(), preferences: new Map() };
    events = [];
    bridge.flush.mockImplementation(async ({ store }: { store: AuthStorageStore }) => { events.push(`flush:${store}`); commit(store); });
    bridge.post.mockResolvedValue({ status: 200, headers: {}, url: 'http://localhost/token', data: { access_token: jwt('new'), expires_in: 300, refresh_token: 'new-refresh' } });
    vi.mocked(Preferences.get).mockImplementation(async ({ key }) => ({ value: memory.preferences.get(key) as string ?? null }));
    vi.mocked(Preferences.set).mockImplementation(async ({ key, value }) => { events.push(`preferences:set:${key}`); memory.preferences.set(key, value); });
    vi.mocked(Preferences.remove).mockImplementation(async ({ key }) => { events.push(`preferences:remove:${key}`); memory.preferences.delete(key); });
    vi.mocked(SecureStorage.get).mockImplementation(async (key) => memory.secure.get(key) as Awaited<ReturnType<typeof SecureStorage.get>> ?? null);
    vi.mocked(SecureStorage.set).mockImplementation(async (key, value) => { events.push(`secure:set:${key}`); memory.secure.set(key, value); });
    vi.mocked(SecureStorage.remove).mockImplementation(async (key) => { events.push(`secure:remove:${key}`); return memory.secure.delete(key); });
    vi.mocked(Browser.open).mockResolvedValue(undefined);
    vi.mocked(Browser.close).mockResolvedValue(undefined);
    router = { navigateByUrl: vi.fn().mockResolvedValue(true) };
    window.sessionStorage.clear();
    storage = new MobileAuthStorageService();
  });

  afterEach(() => {
    vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals();
    TestBed.resetTestingModule();
  });

  it('acknowledges a token replacement only after marker, secure value and plaintext cleanup are durable', async () => {
    const replacement = tokens('new');
    seed(TOKEN, tokens()); seed(TOKEN, JSON.stringify(tokens()), 'preferences');
    await storage.writeTokens(replacement);
    expect(events).toEqual([
      `preferences:set:${MARKER}`, 'flush:preferences', `secure:set:${TOKEN}`, 'flush:secure',
      `preferences:remove:${TOKEN}`, 'flush:preferences', `preferences:remove:${MARKER}`, 'flush:preferences'
    ]);
    expect(disk.secure.get(TOKEN)).toEqual(replacement);
    expect(disk.preferences.has(TOKEN)).toBe(false);
    expect(disk.preferences.has(MARKER)).toBe(false);
    expect(await restart().readTokens()).toEqual(replacement);
  });

  it('holds PKCE save and parallel deletion until the secure commit acknowledges completion', async () => {
    const release = holdNextFlush('secure');
    let saved = false;
    const writing = storage.writePendingLogin(pending).then(() => { saved = true; });
    const clearing = storage.clearPendingLogin();
    await microtasks();
    expect(saved).toBe(false);
    expect(events).toEqual([`secure:set:${PENDING}`, 'flush:secure']);
    release(); await Promise.all([writing, clearing]);
    expect(events).toEqual([
      `secure:set:${PENDING}`, 'flush:secure', `preferences:remove:${PENDING}`, 'flush:preferences',
      `secure:remove:${PENDING}`, 'flush:secure', `preferences:remove:${PENDING}`, 'flush:preferences'
    ]);
    expect(await restart().readPendingLogin()).toBeNull();
  });

  it('fails closed when the Android durability plugin is absent', async () => {
    vi.mocked(Capacitor.isPluginAvailable).mockImplementation(name => name !== 'AuthStorageDurability');
    await expect(storage.writeTokens(tokens('new'))).rejects.toThrow('Надёжное сохранение');
    expect(SecureStorage.set).not.toHaveBeenCalled();
    expect(bridge.flush).not.toHaveBeenCalled();
    expect(await storage.readTokens()).toBeNull();
  });

  it.each([1, 2, 3, 4])('rejects token save at failed flush %s without publishing storage success', async (position) => {
    seed(TOKEN, tokens());
    failFlush(position);
    await expect(storage.writeTokens(tokens('new'))).rejects.toThrow('fixture commit failed');
    expect(await storage.readTokens()).toBeNull();
    if (position !== 1) {
      expect(disk.preferences.get(MARKER)).toBe(TOKEN_REVOCATION_MARKER);
      expect(await restart().readTokens()).toBeNull();
    }
  });

  it.each([1, 2, 3, 4, 5])('rejects logout flush %s and retains a revocation barrier across restart', async (position) => {
    seed(TOKEN, tokens()); seed(TOKEN, JSON.stringify(tokens()), 'preferences');
    failFlush(position);
    await expect(storage.clearTokens()).rejects.toThrow('fixture commit failed');
    expect(await storage.readTokens()).toBeNull();
    expect(disk.preferences.get(MARKER)).toBe(TOKEN_REVOCATION_MARKER);
    expect(await restart().readTokens()).toBeNull();
  });

  it('rejects when both revocation barriers fail and retains the in-process read fence', async () => {
    seed(TOKEN, tokens());
    bridge.flush.mockRejectedValue(new Error('fixture disk unavailable'));
    await expect(storage.clearTokens()).rejects.toThrow('Не удалось надёжно отозвать');
    expect(await storage.readTokens()).toBeNull();
    expect(Preferences.remove).not.toHaveBeenCalled();
  });

  it('keeps the legacy source when migration secure commit fails and does not misclassify a flush failure as corrupt data', async () => {
    seed(TOKEN, JSON.stringify(tokens()), 'preferences');
    failFlush(1);
    await expect(storage.readTokens()).rejects.toThrow('fixture commit failed');
    expect(disk.preferences.has(TOKEN)).toBe(true);
    expect(Preferences.remove).not.toHaveBeenCalled();
    expect(SecureStorage.remove).not.toHaveBeenCalled();
  });

  it('propagates secure read cleanup failure without deleting valid encrypted tokens', async () => {
    seed(TOKEN, tokens()); failFlush(2);
    await expect(storage.readTokens()).rejects.toThrow('fixture commit failed');
    expect(SecureStorage.remove).not.toHaveBeenCalled();
    expect(disk.secure.get(TOKEN)).toEqual(tokens());
  });

  it('uses unqueued cleanup for invalid persisted values instead of deadlocking the storage queue', async () => {
    seed(TOKEN, { invalid: true }); seed(PENDING, { invalid: true });
    expect(await storage.readTokens()).toBeNull();
    expect(await storage.readPendingLogin()).toBeNull();
    expect(disk.secure.size).toBe(0);
  });

  it('keeps a queued logout authoritative over an earlier token save and allows an explicit subsequent login', async () => {
    const release = holdNextFlush('secure');
    const writing = storage.writeTokens(tokens('new'));
    await microtasks();
    const clearing = storage.clearTokens();
    const reading = storage.readTokens();
    await microtasks();
    expect(SecureStorage.remove).not.toHaveBeenCalled();
    release(); await Promise.all([writing, clearing]);
    expect(await reading).toBeNull();
    expect(await restart().readTokens()).toBeNull();
    await storage.writeTokens(tokens('next'));
    expect(await storage.readTokens()).toEqual(tokens('next'));
  });

  it('keeps iOS storage behavior without calling the Android bridge', async () => {
    vi.mocked(Capacitor.getPlatform).mockReturnValue('ios'); storage = new MobileAuthStorageService();
    await storage.writeTokens(tokens()); await storage.writePendingLogin(pending); await storage.clearTokens();
    expect(bridge.flush).not.toHaveBeenCalled();
    expect(await storage.readTokens()).toBeNull();
    expect(await storage.readPendingLogin()).toEqual(pending);
  });

  it('keeps web tokens in memory and PKCE in sessionStorage without either native plugin', async () => {
    vi.mocked(Capacitor.getPlatform).mockReturnValue('web');
    vi.mocked(Capacitor.isNativePlatform).mockReturnValue(false); storage = new MobileAuthStorageService();
    await storage.writeTokens(tokens()); await storage.writePendingLogin(pending);
    expect(await storage.readTokens()).toBeNull();
    expect(await storage.readPendingLogin()).toEqual(pending);
    await storage.clearPendingLogin();
    expect(window.sessionStorage.length).toBe(0);
    expect(SecureStorage.set).not.toHaveBeenCalled();
    expect(bridge.flush).not.toHaveBeenCalled();
  });

  it('does not open the login browser when the actual PKCE storage commit fails', async () => {
    const auth = createAuth();
    vi.stubGlobal('crypto', {
      getRandomValues: (value: Uint8Array) => value.fill(42),
      subtle: { digest: async () => new ArrayBuffer(32) }
    });
    failFlush(1);
    await expect(auth.login('/tabs/home')).rejects.toThrow('fixture commit failed');
    expect(Browser.open).not.toHaveBeenCalled();
    expect(bridge.post).not.toHaveBeenCalled();
  });

  it('publishes neither callback login nor navigation before the actual storage commit', async () => {
    seed(PENDING, pending);
    const auth = createAuth();
    // Pending read first flushes secure+preferences; hold the token write's secure flush.
    let secureFlushes = 0; let release!: () => void;
    bridge.flush.mockImplementation(async ({ store }: { store: AuthStorageStore }) => {
      if (store === 'secure' && ++secureFlushes === 2) await new Promise<void>(resolve => { release = resolve; });
      commit(store);
    });
    const login = auth.completeLoginFromCallback(`otziv://auth/callback?state=${pending.state}&code=fixture`);
    await microtasks();
    expect(auth.tokens()).toBeNull();
    expect(auth.isAuthenticated()).toBe(false);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    release(); await login;
    expect(auth.tokens()?.accessToken).toBe(jwt('new'));
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/home', { replaceUrl: true });
    expect(await restart().readTokens()).toEqual(auth.tokens());
  });

  it('clears callback login state after a real token storage flush failure', async () => {
    seed(PENDING, pending);
    const auth = createAuth(); failFlush(4);
    await auth.completeLoginFromCallback(`otziv://auth/callback?state=${pending.state}&code=fixture`);
    expect(auth.tokens()).toBeNull();
    expect(auth.status()).toBe('error');
    expect(router.navigateByUrl).not.toHaveBeenCalledWith('/tabs/home', expect.anything());
    expect(await restart().readTokens()).toBeNull();
  });

  it('retains the prior memory session and reports refresh unavailable after a real commit failure', async () => {
    const saved = tokens('old', -60); seed(TOKEN, saved);
    const auth = createAuth(saved); failFlush(2);
    expect(await auth.refreshTokens()).toEqual({ status: 'temporary-unavailable' });
    expect(auth.tokens()).toBe(saved);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.status()).toBe('retrying');
    expect(disk.preferences.get(MARKER)).toBe(TOKEN_REVOCATION_MARKER);
    expect(await restart().readTokens()).toBeNull();
  });

  it('preserves logout memory fences while a real refresh secure flush is in flight', async () => {
    const saved = tokens('old', -60); seed(TOKEN, saved);
    const auth = createAuth(saved); const release = holdNextFlush('secure');
    const refresh = auth.refreshTokens(); await microtasks();
    expect(auth.tokens()).toBe(saved);
    const logout = auth.logout(); await microtasks();
    expect(auth.tokens()).toBeNull();
    expect(Browser.open).not.toHaveBeenCalled();
    release(); await logout;
    expect(await refresh).toEqual({ status: 'superseded' });
    expect(auth.tokens()).toBeNull();
    expect(await restart().readTokens()).toBeNull();
  });

  it('rejects the actual logout on commit failure while keeping memory anonymous and the marker durable', async () => {
    const saved = tokens(); seed(TOKEN, saved);
    const auth = createAuth(saved); failFlush(4);
    await expect(auth.logout()).rejects.toThrow('fixture commit failed');
    expect(auth.tokens()).toBeNull();
    expect(auth.status()).toBe('anonymous');
    expect(Browser.open).not.toHaveBeenCalled();
    expect(disk.preferences.get(MARKER)).toBe(TOKEN_REVOCATION_MARKER);
    expect(await restart().readTokens()).toBeNull();
  });

  it('does not claim logout completion when pending-login removal fails its disk commit', async () => {
    const saved = tokens(); seed(TOKEN, saved); seed(PENDING, pending);
    const auth = createAuth(saved); failFlush(6);
    await expect(auth.logout()).rejects.toThrow('fixture commit failed');
    expect(auth.tokens()).toBeNull();
    expect(auth.status()).toBe('anonymous');
    expect(Browser.open).not.toHaveBeenCalled();
    expect(disk.secure.has(TOKEN)).toBe(false);
  });
});
