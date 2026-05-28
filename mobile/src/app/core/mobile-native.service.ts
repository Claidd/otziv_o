import { Injectable, NgZone, computed, effect, signal } from '@angular/core';
import { Router } from '@angular/router';
import { App as CapacitorApp } from '@capacitor/app';
import { Capacitor, type PluginListenerHandle } from '@capacitor/core';
import { Keyboard, KeyboardResize, KeyboardStyle, type KeyboardInfo } from '@capacitor/keyboard';
import { Network, type ConnectionStatus } from '@capacitor/network';
import { SplashScreen } from '@capacitor/splash-screen';
import { StatusBar, Style } from '@capacitor/status-bar';
import { mobileEnvironment } from './mobile-environment';
import { mobileViewportMode } from './mobile-viewport.helpers';
import { MobileThemeService } from '../shared/mobile-theme.service';

const DEFAULT_NETWORK_STATUS: ConnectionStatus = {
  connected: typeof navigator === 'undefined' ? true : navigator.onLine,
  connectionType: 'unknown'
};

@Injectable({ providedIn: 'root' })
export class MobileNativeService {
  private initialized = false;
  private readonly listenerHandles: PluginListenerHandle[] = [];
  private viewportHeightBeforeKeyboard = typeof window === 'undefined' ? 0 : window.innerHeight;
  private lastKeyboardInfo: KeyboardInfo | null = null;

  readonly isNative = Capacitor.isNativePlatform();
  readonly networkStatus = signal<ConnectionStatus>(DEFAULT_NETWORK_STATUS);
  readonly appActive = signal(true);
  readonly keyboardVisible = signal(false);
  readonly keyboardHeight = signal(0);
  readonly ready = signal(false);
  readonly runtimeError = signal<string | null>(null);
  readonly isOffline = computed(() => !this.networkStatus().connected);

  constructor(
    private readonly router: Router,
    private readonly zone: NgZone,
    private readonly theme: MobileThemeService
  ) {
    effect(() => {
      void this.applySystemBars(this.theme.theme());
    });
  }

  initialize(): void {
    if (this.initialized) {
      return;
    }

    this.initialized = true;
    this.bindBrowserNetwork();
    this.bindViewportResize();
    this.bindRuntimeErrors();
    void this.initializeNative();
    window.setTimeout(() => void this.hideSplash(), 450);
  }

  private async initializeNative(): Promise<void> {
    if (!this.isNative) {
      this.ready.set(true);
      return;
    }

    await Promise.all([
      this.applySystemBars(this.theme.theme()),
      this.configureKeyboard(),
      this.bindNetwork(),
      this.bindAppEvents()
    ]);
    this.ready.set(true);
  }

  private bindBrowserNetwork(): void {
    const update = () => {
      this.zone.run(() => {
        this.networkStatus.set({
          connected: navigator.onLine,
          connectionType: navigator.onLine ? 'unknown' : 'none'
        });
      });
    };

    window.addEventListener('online', update);
    window.addEventListener('offline', update);
    update();
  }

  private bindRuntimeErrors(): void {
    window.addEventListener('error', (event) => {
      this.zone.run(() => {
        this.runtimeError.set(event.message || 'Ошибка выполнения мобильного приложения.');
      });
    });

    window.addEventListener('unhandledrejection', (event) => {
      this.zone.run(() => {
        const reason = event.reason;
        this.runtimeError.set(reason instanceof Error ? reason.message : 'Необработанная ошибка мобильного приложения.');
      });
    });
  }

  private async bindNetwork(): Promise<void> {
    if (!Capacitor.isPluginAvailable('Network')) {
      return;
    }

    const status = await Network.getStatus().catch(() => DEFAULT_NETWORK_STATUS);
    this.zone.run(() => this.networkStatus.set(status));

    const handle = await Network.addListener('networkStatusChange', (nextStatus) => {
      this.zone.run(() => this.networkStatus.set(nextStatus));
    });
    this.listenerHandles.push(handle);
  }

  private async bindAppEvents(): Promise<void> {
    const stateHandle = await CapacitorApp.addListener('appStateChange', ({ isActive }) => {
      this.zone.run(() => this.appActive.set(isActive));
    });
    this.listenerHandles.push(stateHandle);

    const urlHandle = await CapacitorApp.addListener('appUrlOpen', (event) => {
      this.zone.run(() => this.handleDeepLink(event.url));
    });
    this.listenerHandles.push(urlHandle);

    const backHandle = await CapacitorApp.addListener('backButton', ({ canGoBack }) => {
      this.zone.run(() => void this.handleBackButton(canGoBack));
    });
    this.listenerHandles.push(backHandle);
  }

  private async configureKeyboard(): Promise<void> {
    if (!Capacitor.isPluginAvailable('Keyboard')) {
      return;
    }

    if (Capacitor.getPlatform() === 'ios') {
      await Promise.all([
        Keyboard.setResizeMode({ mode: KeyboardResize.Ionic }).catch(() => undefined),
        Keyboard.setScroll({ isDisabled: false }).catch(() => undefined),
        Keyboard.setAccessoryBarVisible({ isVisible: true }).catch(() => undefined),
        Keyboard.setStyle({ style: KeyboardStyle.Default }).catch(() => undefined)
      ]);
    }

    const showHandle = await Keyboard.addListener('keyboardWillShow', (info) => this.setKeyboardState(true, info));
    const didShowHandle = await Keyboard.addListener('keyboardDidShow', (info) => this.setKeyboardState(true, info));
    const hideHandle = await Keyboard.addListener('keyboardWillHide', () => this.setKeyboardState(false));
    const didHideHandle = await Keyboard.addListener('keyboardDidHide', () => this.setKeyboardState(false));
    this.listenerHandles.push(showHandle, didShowHandle, hideHandle, didHideHandle);
  }

  private bindViewportResize(): void {
    const updateViewportMetrics = () => {
      const viewport = window.visualViewport;
      const width = Math.round(viewport?.width ?? window.innerWidth);
      const height = Math.round(viewport?.height ?? window.innerHeight);

      document.documentElement.style.setProperty('--otziv-viewport-width', `${width}px`);
      document.documentElement.style.setProperty('--otziv-viewport-height', `${height}px`);
      const { compactPhone, roomyPhone, shortPhone } = mobileViewportMode(width, height);

      document.body.classList.toggle('otziv-compact-phone', compactPhone);
      document.body.classList.toggle('otziv-roomy-phone', roomyPhone);
      document.body.classList.toggle('otziv-short-phone', shortPhone);
    };

    updateViewportMetrics();
    window.visualViewport?.addEventListener('resize', updateViewportMetrics);
    window.visualViewport?.addEventListener('scroll', updateViewportMetrics);

    window.addEventListener('resize', () => {
      updateViewportMetrics();
      if (!this.keyboardVisible()) {
        this.viewportHeightBeforeKeyboard = window.innerHeight;
        return;
      }
      this.setKeyboardState(true, this.lastKeyboardInfo ?? undefined, false);
    });
  }

  private setKeyboardState(visible: boolean, info?: KeyboardInfo, scheduleRecheck = true): void {
    this.zone.run(() => {
      const height = visible ? Math.max(0, info?.keyboardHeight ?? 0) : 0;
      if (visible) {
        this.lastKeyboardInfo = info ?? this.lastKeyboardInfo;
      } else {
        this.lastKeyboardInfo = null;
        this.viewportHeightBeforeKeyboard = window.innerHeight;
      }
      const viewportShrink = visible ? Math.max(0, this.viewportHeightBeforeKeyboard - window.innerHeight) : 0;
      const panelBottom = Math.max(0, height - viewportShrink);
      this.keyboardVisible.set(visible);
      this.keyboardHeight.set(height);
      document.body.classList.toggle('otziv-keyboard-open', visible);
      document.body.classList.toggle('otziv-keyboard-overlay', visible && panelBottom > 0);
      document.documentElement.style.setProperty('--otziv-keyboard-height', `${height}px`);
      document.documentElement.style.setProperty('--otziv-keyboard-panel-bottom', `${panelBottom}px`);
    });

    if (visible && scheduleRecheck) {
      window.setTimeout(() => this.setKeyboardState(true, this.lastKeyboardInfo ?? info, false), 80);
    }
  }

  private async applySystemBars(theme: 'light' | 'dark'): Promise<void> {
    if (!this.isNative || !Capacitor.isPluginAvailable('StatusBar')) {
      return;
    }

    const isDark = theme === 'dark';
    await Promise.all([
      StatusBar.setOverlaysWebView({ overlay: false }).catch(() => undefined),
      StatusBar.setStyle({ style: isDark ? Style.Dark : Style.Light }).catch(() => undefined),
      StatusBar.setBackgroundColor({ color: isDark ? '#181a1e' : '#f6f6f9' }).catch(() => undefined)
    ]);
  }

  private async hideSplash(): Promise<void> {
    if (!Capacitor.isPluginAvailable('SplashScreen')) {
      return;
    }
    await SplashScreen.hide().catch(() => undefined);
  }

  private handleDeepLink(url: string): void {
    if (url.startsWith(mobileEnvironment.keycloak.nativeRedirectUri) || url.startsWith('otziv://logout')) {
      return;
    }

    let parsed: URL;
    try {
      parsed = new URL(url);
    } catch {
      return;
    }

    if (parsed.protocol === 'otziv:' && parsed.hostname === 'app') {
      void this.router.navigateByUrl(`${parsed.pathname || '/tabs/home'}${parsed.search}`, { replaceUrl: false });
      return;
    }

    if (/^https?:$/i.test(parsed.protocol) && parsed.hostname === 'o-ogo.ru' && parsed.pathname.startsWith('/mobile/')) {
      void this.router.navigateByUrl(parsed.pathname.replace(/^\/mobile/, '') + parsed.search, { replaceUrl: false });
    }
  }

  private async handleBackButton(canGoBack: boolean): Promise<void> {
    const currentUrl = this.router.url.split('?')[0];
    if (canGoBack && currentUrl !== '/tabs/home') {
      window.history.back();
      return;
    }

    if (currentUrl !== '/tabs/home' && currentUrl !== '/tabs/home/profile') {
      await this.router.navigateByUrl('/tabs/home', { replaceUrl: true });
      return;
    }

    await CapacitorApp.minimizeApp().catch(() => undefined);
  }
}
