import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { App as CapacitorApp } from '@capacitor/app';
import { Capacitor } from '@capacitor/core';
import { firstValueFrom } from 'rxjs';
import { AppUpdater } from './mobile-app-update.plugin';
import {
  type MobileUpdateRelease,
  isUpdateAvailable,
  isUpdateRequired,
  resolveUpdateUrl
} from './mobile-update.helpers';
import { mobileEnvironment } from './mobile-environment';

export type MobileUpdateState =
  | 'idle'
  | 'available'
  | 'downloading'
  | 'permission'
  | 'installing'
  | 'error';

@Injectable({ providedIn: 'root' })
export class MobileUpdateService {
  private readonly http = inject(HttpClient);
  private readonly nativeAndroid = Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';
  private readonly releaseState = signal<MobileUpdateRelease | null>(null);
  private initialized = false;
  private pollingTimer?: ReturnType<typeof setTimeout>;
  private lastCheckAt = 0;

  readonly state = signal<MobileUpdateState>('idle');
  readonly progress = signal(0);
  readonly error = signal('');
  readonly visible = signal(false);
  readonly installedVersionCode = signal(0);
  readonly installedVersionName = signal('');
  readonly release = this.releaseState.asReadonly();
  readonly required = computed(() => {
    const release = this.releaseState();
    return release ? isUpdateRequired(release, this.installedVersionCode()) : false;
  });

  initialize(): void {
    if (!this.nativeAndroid || this.initialized) {
      return;
    }
    this.initialized = true;
    void this.restoreAndCheck();
    void CapacitorApp.addListener('appStateChange', ({ isActive }) => {
      if (isActive) {
        void this.handleResume();
      }
    });
  }

  async check(force = false): Promise<void> {
    if (!this.nativeAndroid || (!force && Date.now() - this.lastCheckAt < 5 * 60_000)) {
      return;
    }
    this.lastCheckAt = Date.now();
    try {
      const info = await AppUpdater.getInfo();
      this.installedVersionCode.set(info.versionCode);
      this.installedVersionName.set(info.versionName);
      const release = await firstValueFrom(this.http.get<MobileUpdateRelease>(this.apiUrl('/api/mobile-update')));
      if (!isUpdateAvailable(release, info.versionCode)) {
        this.releaseState.set(null);
        this.visible.set(false);
        this.state.set('idle');
        return;
      }
      this.releaseState.set(release);
      this.state.set('available');
      this.visible.set(true);
      this.error.set('');
    } catch {
      // The updater must never block normal application startup when the server is unavailable.
    }
  }

  async start(): Promise<void> {
    const release = this.releaseState();
    if (!release) {
      return;
    }
    this.error.set('');
    this.progress.set(0);
    this.state.set('downloading');
    this.visible.set(true);
    try {
      await AppUpdater.startDownload({
        url: resolveUpdateUrl(release.downloadUrl, mobileEnvironment.apiBaseUrl),
        sha256: release.sha256,
        versionName: release.versionName
      });
      this.schedulePoll(300);
    } catch (error) {
      this.fail(this.message(error, 'Не удалось начать загрузку обновления.'));
    }
  }

  defer(): void {
    if (!this.required()) {
      this.visible.set(false);
    }
  }

  async retry(): Promise<void> {
    await this.start();
  }

  private async restoreAndCheck(): Promise<void> {
    try {
      const status = await AppUpdater.getDownloadStatus();
      if (status.state === 'running' || status.state === 'pending' || status.state === 'paused') {
        this.state.set('downloading');
        this.progress.set(status.progress ?? 0);
        this.visible.set(true);
        this.schedulePoll(500);
      } else if (status.state === 'successful') {
        await this.installDownloaded();
      }
    } catch {
      // A clean install has no pending native download.
    }
    await this.check(true);
  }

  private async handleResume(): Promise<void> {
    if (this.state() === 'permission' || this.state() === 'installing') {
      await this.installDownloaded();
      return;
    }
    if (this.state() === 'downloading') {
      await this.pollDownload();
      return;
    }
    await this.check();
  }

  private schedulePoll(delay: number): void {
    if (this.pollingTimer) {
      clearTimeout(this.pollingTimer);
    }
    this.pollingTimer = setTimeout(() => void this.pollDownload(), delay);
  }

  private async pollDownload(): Promise<void> {
    try {
      const status = await AppUpdater.getDownloadStatus();
      this.progress.set(status.progress ?? 0);
      if (status.state === 'successful') {
        await this.installDownloaded();
        return;
      }
      if (status.state === 'failed') {
        this.fail(`Android не смог загрузить обновление (код ${status.reason ?? 'неизвестен'}).`);
        return;
      }
      this.state.set('downloading');
      this.visible.set(true);
      this.schedulePoll(status.state === 'paused' ? 2000 : 800);
    } catch (error) {
      this.fail(this.message(error, 'Не удалось проверить загрузку обновления.'));
    }
  }

  private async installDownloaded(): Promise<void> {
    try {
      const info = await AppUpdater.getInfo();
      if (!info.canInstallPackages) {
        this.state.set('permission');
        this.visible.set(true);
        await AppUpdater.openInstallPermission();
        return;
      }
      this.state.set('installing');
      this.visible.set(true);
      await AppUpdater.installDownloaded();
    } catch (error) {
      this.fail(this.message(error, 'Не удалось открыть установку обновления.'));
    }
  }

  private fail(message: string): void {
    this.error.set(message);
    this.state.set('error');
    this.visible.set(true);
  }

  private message(error: unknown, fallback: string): string {
    if (typeof error === 'object' && error && 'message' in error && typeof error.message === 'string') {
      return error.message;
    }
    return fallback;
  }

  private apiUrl(path: string): string {
    return `${mobileEnvironment.apiBaseUrl}${path}`;
  }
}
