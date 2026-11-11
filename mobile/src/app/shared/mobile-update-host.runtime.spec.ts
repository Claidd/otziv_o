import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import type { MobileUpdateRelease } from '../core/mobile-update.helpers';
import { MobileUpdateService } from '../core/mobile-update.service';
import { MobileUpdateHostComponent } from './mobile-update-host.component';

const LONG_NOTES = 'Длинное описание выпуска. '.repeat(40).trim();
const release: MobileUpdateRelease = {
  enabled: true, versionCode: 87, versionName: '1.0.87', minSupportedVersionCode: 0, required: false,
  notes: LONG_NOTES, fileSize: 4_424_404, sha256: 'A'.repeat(64), publishedAt: '', downloadUrl: '/android-new/otziv-v1.0.87.apk'
};

/**
 * The update window covers the whole app, so its buttons must stay reachable whatever the notes are.
 * 1.0.86 was published with notes too long for a small screen: the window did not scroll, «Обновить»
 * and «Позже» were below the screen and the employees could not enter the app. The layout itself is
 * checked in a browser; this guards the structure it relies on: notes scroll, buttons do not.
 */
describe('Android update window', () => {
  const update = {
    visible: signal(true),
    release: signal<MobileUpdateRelease | null>(release),
    state: signal<string>('available'),
    progress: signal(0),
    error: signal(''),
    required: signal(false),
    start: vi.fn(),
    defer: vi.fn(),
    retry: vi.fn()
  };

  function render(): HTMLElement {
    TestBed.configureTestingModule({ providers: [{ provide: MobileUpdateService, useValue: update }] });
    const fixture = TestBed.createComponent(MobileUpdateHostComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function button(root: HTMLElement, label: string): HTMLButtonElement | undefined {
    return Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((item) => item.textContent?.trim().endsWith(label));
  }

  beforeEach(() => {
    vi.clearAllMocks();
    update.visible.set(true);
    update.release.set(release);
    update.state.set('available');
    update.progress.set(0);
    update.error.set('');
    update.required.set(false);
  });

  afterEach(() => TestBed.resetTestingModule());

  it('keeps the notes in the scrolling part and both buttons outside it', () => {
    const root = render();
    const body = root.querySelector('.update-dialog > .update-body');
    const footer = root.querySelector('.update-dialog > .update-footer');

    expect(body?.querySelector('.update-notes')?.textContent).toBe(LONG_NOTES);
    expect(body?.querySelector('h2')?.textContent).toBe('Обновление 1.0.87');
    expect(body?.querySelector('button')).toBeNull();
    expect(footer?.contains(button(root, 'Обновить') ?? null)).toBe(true);
    expect(footer?.contains(button(root, 'Позже') ?? null)).toBe(true);

    button(root, 'Обновить')?.click();
    button(root, 'Позже')?.click();
    expect(update.start).toHaveBeenCalledTimes(1);
    expect(update.defer).toHaveBeenCalledTimes(1);
  });

  it('shows the download progress and the retry of a failed download outside the scrolling part', () => {
    update.state.set('downloading');
    update.progress.set(40);
    let root = render();
    expect(root.querySelector('.update-footer .update-progress')?.getAttribute('aria-valuenow')).toBe('40');
    expect(root.querySelector('.update-body .update-progress')).toBeNull();
    TestBed.resetTestingModule();

    update.state.set('error');
    update.error.set('Не удалось скачать обновление.');
    root = render();
    expect(root.querySelector('.update-footer .update-error')?.textContent).toBe('Не удалось скачать обновление.');
    expect(root.querySelector('.update-footer')?.contains(button(root, 'Повторить') ?? null)).toBe(true);
    button(root, 'Повторить')?.click();
    expect(update.retry).toHaveBeenCalledTimes(1);
  });

  it('offers no way to postpone a required update and renders nothing while hidden', () => {
    update.required.set(true);
    let root = render();
    expect(button(root, 'Обновить')).toBeDefined();
    expect(button(root, 'Позже')).toBeUndefined();
    TestBed.resetTestingModule();

    update.visible.set(false);
    root = render();
    expect(root.querySelector('.update-backdrop')).toBeNull();
  });
});
