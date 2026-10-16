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
