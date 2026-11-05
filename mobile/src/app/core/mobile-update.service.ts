import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { App as CapacitorApp } from '@capacitor/app';
import { Capacitor } from '@capacitor/core';
import { firstValueFrom } from 'rxjs';
import { AppUpdater, type AppUpdateDownloadStatus } from './mobile-app-update.plugin';
import {
  type MobileUpdateRelease,
  isDownloadedRelease,
  isUpdateAvailable,
  isUpdateRequired,
  mobileUpdateReleaseUrl,
  mobileUpdateResumeAction,
  resolveUpdateUrl,
  shouldAutoInstallOnStartup
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
      const release = await firstValueFrom(this.http.get<MobileUpdateRelease>(mobileUpdateReleaseUrl(mobileEnvironment.apiBaseUrl)));
      if (!isUpdateAvailable(release, info.versionCode)) {
        this.releaseState.set(null);
        this.visible.set(false);
        this.state.set('idle');
        return;
      }
      this.releaseState.set(release);
      // A running download, the permission screen or an open installer keep
      // their own state; the check only refreshes the release information.
      if (!this.isBusy()) {
        this.state.set('available');
        this.error.set('');
      }
      this.visible.set(true);
    } catch {
      // The updater must never block normal application startup when the server is unavailable.
    }
  }

  async start(): Promise<void> {
    const release = this.releaseState();
    if (!release || this.isBusy()) {
      return;
    }
    this.error.set('');
    if (await this.isReleaseDownloaded(release)) {
      // The APK of this release is already on the device (for example the
      // user closed the installer earlier). Android re-verifies it.
      await this.installDownloaded();
      return;
    }
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
    if (this.required()) {
      return;
    }
    this.visible.set(false);
    if (this.state() === 'error' && this.releaseState()) {
      this.state.set('available');
      this.error.set('');
    }
  }

  async retry(): Promise<void> {
    await this.start();
  }

  private async restoreAndCheck(): Promise<void> {
    let status: AppUpdateDownloadStatus | null = null;
    try {
      // The native side discards an APK that is not newer than the installed
      // app (the update was installed), so a finished download here is pending.
      status = await AppUpdater.getDownloadStatus();
      if (status.state === 'running' || status.state === 'pending' || status.state === 'paused') {
        this.state.set('downloading');
        this.progress.set(status.progress ?? 0);
        this.visible.set(true);
        this.schedulePoll(500);
      }
    } catch {
      // A clean install has no pending native download.
    }
    await this.check(true);
    if (status?.state === 'successful'
      && !this.isBusy()
      && shouldAutoInstallOnStartup(status, this.releaseState(), this.installedVersionCode())) {
      await this.installDownloaded();
    }
    // An optional update waits in the dialog; "Обновить" reuses the APK.
  }

  private async handleResume(): Promise<void> {
    switch (mobileUpdateResumeAction(this.state())) {
      case 'recheck-permission':
        await this.continueAfterPermission();
        return;
      case 'installer-closed':
        this.returnFromInstaller();
        return;
      case 'poll-download':
        await this.pollDownload();
        return;
      default:
        await this.check();
    }
  }

  private async continueAfterPermission(): Promise<void> {
    try {
      const info = await AppUpdater.getInfo();
      if (info.canInstallPackages) {
        await this.installDownloaded();
        return;
      }
    } catch {
      // Treated as a missing permission below.
    }
    // Do not reopen the settings screen by itself: the user decides.
    this.fail('Установка обновлений не разрешена. Разрешите установку для «Компания О!» и нажмите «Повторить».');
  }

  private returnFromInstaller(): void {
    // A successful install replaces this process, so the user is back from a
    // cancelled or failed installer. Offer the update again instead of
    // relaunching the installer.
    if (!this.releaseState()) {
      this.state.set('idle');
      this.visible.set(false);
      return;
    }
    this.state.set('available');
    this.error.set('');
    this.visible.set(true);
  }

  private async isReleaseDownloaded(release: MobileUpdateRelease): Promise<boolean> {
    try {
      return isDownloadedRelease(await AppUpdater.getDownloadStatus(), release);
    } catch {
      return false;
    }
  }

  private isBusy(): boolean {
    const state = this.state();
    return state === 'downloading' || state === 'permission' || state === 'installing';
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
}
