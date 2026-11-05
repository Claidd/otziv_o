export type MobileUpdateRelease = {
  enabled: boolean;
  versionCode: number;
  versionName: string;
  minSupportedVersionCode: number;
  required: boolean;
  notes: string;
  fileSize: number;
  sha256: string;
  publishedAt: string;
  downloadUrl: string;
};

// A new signing certificate requires a separate release channel. Older installed
// APKs continue to read /api/mobile-update and cannot install this channel's APK.
export const MOBILE_UPDATE_RELEASE_PATH = '/android-new/release.json';

export function mobileUpdateReleaseUrl(apiBaseUrl: string): string {
  return resolveUpdateUrl(MOBILE_UPDATE_RELEASE_PATH, apiBaseUrl);
}

export function isUpdateAvailable(release: MobileUpdateRelease, installedVersionCode: number): boolean {
  return release.enabled && release.versionCode > installedVersionCode;
}

export function isUpdateRequired(release: MobileUpdateRelease, installedVersionCode: number): boolean {
  return release.required || installedVersionCode < release.minSupportedVersionCode;
}

export function resolveUpdateUrl(downloadUrl: string, apiBaseUrl: string): string {
  if (/^https?:\/\//i.test(downloadUrl)) {
    return downloadUrl;
  }
  const base = apiBaseUrl.replace(/\/+$/, '');
  const path = downloadUrl.startsWith('/') ? downloadUrl : `/${downloadUrl}`;
  return `${base}${path}`;
}

export function formatUpdateSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '';
  }
  return `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} МБ`;
}

/** What a downloaded APK is known to contain (from AppUpdater.getDownloadStatus). */
export type DownloadedUpdateStatus = {
  state: string;
  versionCode?: number;
};

/** True when the finished download is the APK of this release and can be installed without downloading again. */
export function isDownloadedRelease(status: DownloadedUpdateStatus | null | undefined, release: MobileUpdateRelease | null): boolean {
  return Boolean(release)
    && status?.state === 'successful'
    && typeof status.versionCode === 'number'
    && status.versionCode === release!.versionCode;
}

/**
 * Whether a cold start may open the Android installer for an APK downloaded
 * earlier. Only a required update does that; an optional one waits for the
 * user's "Обновить" in the dialog (a cancelled installer must not reopen on
 * every start).
 */
export function shouldAutoInstallOnStartup(
  status: DownloadedUpdateStatus | null | undefined,
  release: MobileUpdateRelease | null,
  installedVersionCode: number
): boolean {
  return isDownloadedRelease(status, release)
    && isUpdateAvailable(release!, installedVersionCode)
    && isUpdateRequired(release!, installedVersionCode);
}

export type MobileUpdateResumeAction = 'recheck-permission' | 'installer-closed' | 'poll-download' | 'check';

/**
 * What to do when the app returns to the foreground. Android does not report
 * the outcome of the system installer; a successful install replaces this
 * process, so returning to the app in the 'installing' state means the user
 * cancelled (or the install failed). Only the permission screen leads back
 * into the installer automatically.
 */
export function mobileUpdateResumeAction(state: string): MobileUpdateResumeAction {
  switch (state) {
    case 'permission':
      return 'recheck-permission';
    case 'installing':
      return 'installer-closed';
    case 'downloading':
      return 'poll-download';
    default:
      return 'check';
  }
}
