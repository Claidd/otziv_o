import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { App as CapacitorApp } from '@capacitor/app';
import { Capacitor, type PluginListenerHandle } from '@capacitor/core';
import { MobileThemeService } from '../shared/mobile-theme.service';
import { MobileNativeService } from './mobile-native.service';
import { workerAppLinkRoute } from './worker-app-link';

vi.mock('@capacitor/app', () => ({ App: { addListener: vi.fn(), getLaunchUrl: vi.fn() } }));

describe('verified worker App Links', () => {
  it.each([
    ['https://o-ogo.ru/worker', '/tabs/worker'],
    ['https://o-ogo.ru/worker?section=publish', '/tabs/worker?section=publish'],
    ['https://o-ogo.ru/worker?section=CORRECT&unused=discarded', '/tabs/worker?section=correct'],
    ['https://o-ogo.ru/worker?section=%2Fadmin', '/tabs/worker'],
    ['https://o-ogo.ru/worker?section=unknown', '/tabs/worker']
  ])('maps %s to %s', (url, expected) => {
    expect(workerAppLinkRoute(url)).toBe(expected);
  });

  it.each([
    'http://o-ogo.ru/worker',
    'https://www.o-ogo.ru/worker',
    'https://o-ogo.ru/worker/other',
    'https://o-ogo.ru/worker-risk',
    'https://o-ogo.ru:8443/worker',
    'https://other.example/worker',
    'https://user@o-ogo.ru/worker',
    'otziv://auth/callback',
    'garbage'
  ])('does not claim %s', (url) => {
    expect(workerAppLinkRoute(url)).toBeNull();
  });
});

describe('native worker App Link delivery', () => {
  let router: { navigateByUrl: ReturnType<typeof vi.fn> };
  let listeners: Map<string, (event: { url: string }) => void>;

  beforeEach(() => {
    vi.spyOn(Capacitor, 'isNativePlatform').mockReturnValue(true);
    vi.spyOn(Capacitor, 'isPluginAvailable').mockReturnValue(false);
    listeners = new Map();
    router = { navigateByUrl: vi.fn().mockResolvedValue(true) };
    vi.mocked(CapacitorApp.addListener).mockImplementation((async (name: string, listener: unknown) => {
      listeners.set(name, listener as (event: { url: string }) => void);
      return { remove: async () => undefined } as PluginListenerHandle;
    }) as typeof CapacitorApp.addListener);
    vi.mocked(CapacitorApp.getLaunchUrl).mockResolvedValue(undefined);
    TestBed.configureTestingModule({ providers: [
      MobileNativeService,
      { provide: Router, useValue: router },
      { provide: MobileThemeService, useValue: { theme: signal('light') } }
    ] });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
    vi.resetAllMocks();
  });

  async function bindNativeLinks(): Promise<void> {
    const native = TestBed.inject(MobileNativeService);
    await (native as unknown as { bindAppEvents: () => Promise<void> }).bindAppEvents();
  }

  it('routes a cold-start Telegram link after reading the launch URL', async () => {
    vi.mocked(CapacitorApp.getLaunchUrl).mockResolvedValue({ url: 'https://o-ogo.ru/worker?section=publish' });
    await bindNativeLinks();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/worker?section=publish', { replaceUrl: false });
  });

  it('routes a warm-start Telegram link from appUrlOpen', async () => {
    await bindNativeLinks();
    listeners.get('appUrlOpen')?.({ url: 'https://o-ogo.ru/worker?section=correct' });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/worker?section=correct', { replaceUrl: false });
  });

  it('leaves auth callbacks and unrelated web links to their existing handlers', async () => {
    vi.mocked(CapacitorApp.getLaunchUrl).mockResolvedValue({ url: 'otziv://auth/callback?code=sample' });
    await bindNativeLinks();
    listeners.get('appUrlOpen')?.({ url: 'https://o-ogo.ru/admin' });
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});
