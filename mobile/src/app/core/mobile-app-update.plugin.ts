import { registerPlugin } from '@capacitor/core';

export type AppUpdaterInfo = {
  versionName: string;
  versionCode: number;
  canInstallPackages: boolean;
};

export type AppUpdateDownloadStatus = {
  state: 'idle' | 'pending' | 'running' | 'paused' | 'successful' | 'failed' | 'unknown';
  progress: number;
  downloadedBytes?: number;
  totalBytes?: number;
  reason?: number;
};

export interface AppUpdaterPlugin {
  getInfo(): Promise<AppUpdaterInfo>;
  openInstallPermission(): Promise<void>;
  startDownload(options: { url: string; sha256: string; versionName: string }): Promise<{ downloadId: number }>;
  getDownloadStatus(): Promise<AppUpdateDownloadStatus>;
  installDownloaded(): Promise<void>;
}

export const AppUpdater = registerPlugin<AppUpdaterPlugin>('AppUpdater');
