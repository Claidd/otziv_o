import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Capacitor, type PluginListenerHandle } from '@capacitor/core';
import { Keyboard, type KeyboardInfo } from '@capacitor/keyboard';
import { MobileThemeService } from '../shared/mobile-theme.service';
import { MobileNativeService } from './mobile-native.service';

vi.mock('@capacitor/keyboard', () => ({
  Keyboard: { addListener: vi.fn() },
  KeyboardResize: { Ionic: 'ionic' },
  KeyboardStyle: { Default: 'DEFAULT' }
}));

describe('native keyboard layout event ordering', () => {
  let native: MobileNativeService;
  let listeners: Map<string, (info?: KeyboardInfo) => void>;

  beforeEach(async () => {
    vi.useFakeTimers();
    vi.spyOn(Capacitor, 'isNativePlatform').mockReturnValue(true);
    vi.spyOn(Capacitor, 'getPlatform').mockReturnValue('android');
    vi.spyOn(Capacitor, 'isPluginAvailable').mockImplementation(name => name === 'Keyboard');
    listeners = new Map();
    vi.mocked(Keyboard.addListener).mockImplementation((async (name: string, listener: unknown) => {
      listeners.set(name, listener as (info?: KeyboardInfo) => void);
      return { remove: async () => undefined } as PluginListenerHandle;
    }) as typeof Keyboard.addListener);
    TestBed.configureTestingModule({ providers: [
      MobileNativeService,
      { provide: Router, useValue: { navigateByUrl: vi.fn() } },
      { provide: MobileThemeService, useValue: { theme: signal('light') } }
    ] });
    native = TestBed.inject(MobileNativeService);
    await (native as unknown as { configureKeyboard: () => Promise<void> }).configureKeyboard();
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.resetAllMocks();
    document.body.classList.remove('otziv-keyboard-open', 'otziv-keyboard-overlay');
    document.documentElement.style.removeProperty('--otziv-keyboard-height');
    document.documentElement.style.removeProperty('--otziv-keyboard-panel-bottom');
  });

  function show(height: number, event = 'keyboardWillShow'): void {
    listeners.get(event)!({ keyboardHeight: height });
  }

  function hide(): void {
    listeners.get('keyboardWillHide')!();
    listeners.get('keyboardDidHide')!();
  }

  function expectHidden(): void {
    expect(native.keyboardVisible()).toBe(false);
    expect(native.keyboardHeight()).toBe(0);
    expect(document.body.classList.contains('otziv-keyboard-open')).toBe(false);
    expect(document.body.classList.contains('otziv-keyboard-overlay')).toBe(false);
    expect(document.documentElement.style.getPropertyValue('--otziv-keyboard-height')).toBe('0px');
    expect(document.documentElement.style.getPropertyValue('--otziv-keyboard-panel-bottom')).toBe('0px');
    expect(vi.getTimerCount()).toBe(0);
  }

  it('does not resurrect keyboard layout when hide arrives before the delayed recheck', () => {
    show(300);
    vi.advanceTimersByTime(20);
    hide();
    vi.advanceTimersByTime(100);
    expectHidden();
  });

  it('keeps the newest keyboard height through a show-hide-show sequence', () => {
    show(300);
    vi.advanceTimersByTime(20);
    hide();
    vi.advanceTimersByTime(10);
    show(400);
    expect(vi.getTimerCount()).toBe(1);
    vi.advanceTimersByTime(100);
    expect(native.keyboardVisible()).toBe(true);
    expect(native.keyboardHeight()).toBe(400);
    expect(document.documentElement.style.getPropertyValue('--otziv-keyboard-height')).toBe('400px');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('coalesces will-show and did-show into one recheck using the latest native height', () => {
    const layoutWrites = vi.spyOn(document.documentElement.style, 'setProperty');
    show(300);
    show(320, 'keyboardDidShow');
    layoutWrites.mockClear();
    vi.advanceTimersByTime(80);
    expect(layoutWrites.mock.calls.filter(([name]) => name === '--otziv-keyboard-height'))
      .toEqual([['--otziv-keyboard-height', '320px']]);
    expect(native.keyboardVisible()).toBe(true);
    expect(native.keyboardHeight()).toBe(320);
    expect(document.documentElement.style.getPropertyValue('--otziv-keyboard-panel-bottom')).toBe('320px');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('clears the layout when hide follows an already completed recheck', () => {
    show(300);
    vi.advanceTimersByTime(80);
    expect(native.keyboardVisible()).toBe(true);
    hide();
    vi.advanceTimersByTime(160);
    expectHidden();
  });
});
