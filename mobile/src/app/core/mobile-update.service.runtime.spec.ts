import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { App as CapacitorApp } from '@capacitor/app';
import { AppUpdater } from './mobile-app-update.plugin';
import type { MobileUpdateRelease } from './mobile-update.helpers';
import { MobileUpdateService } from './mobile-update.service';

vi.mock('@capacitor/core', () => {
  const updater = {
    getInfo: vi.fn(),
    openInstallPermission: vi.fn(),
    startDownload: vi.fn(),
    getDownloadStatus: vi.fn(),
    installDownloaded: vi.fn()
  };
  return {
    Capacitor: { isNativePlatform: () => true, getPlatform: () => 'android', isPluginAvailable: () => true },
    registerPlugin: () => updater
  };
});
vi.mock('@capacitor/app', () => ({ App: { addListener: vi.fn() } }));

const RELEASE_URL = 'http://localhost:8088/android-new/release.json';
const release: MobileUpdateRelease = {
  enabled: true, versionCode: 54, versionName: '1.0.54', minSupportedVersionCode: 50, required: false,
  notes: '', fileSize: 1000, sha256: 'A'.repeat(64), publishedAt: '', downloadUrl: '/android-new/otziv-new.apk'
};
const updater = vi.mocked(AppUpdater);
const microtasks = async () => { for (let i = 0; i < 30; i += 1) await Promise.resolve(); };

describe('Android in-app update lifecycle', () => {
  let http: HttpTestingController;
  let service: MobileUpdateService;
  let resume: (state: { isActive: boolean }) => void;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    vi.mocked(CapacitorApp.addListener).mockImplementation((async (_name: string, listener: unknown) => {
      resume = listener as typeof resume;
      return { remove: async () => undefined };
    }) as typeof CapacitorApp.addListener);
    updater.getInfo.mockResolvedValue({ versionName: '1.0.53', versionCode: 53, canInstallPackages: true });
    updater.getDownloadStatus.mockResolvedValue({ state: 'idle', progress: 0 });
    updater.installDownloaded.mockResolvedValue(undefined);
    updater.openInstallPermission.mockResolvedValue(undefined);
    updater.startDownload.mockResolvedValue({ downloadId: 1 });
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(MobileUpdateService);
  });

  afterEach(() => {
    http.verify();
    vi.clearAllTimers();
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  async function initialize(serverRelease: MobileUpdateRelease = release): Promise<void> {
    service.initialize();
    await microtasks();
    http.expectOne(RELEASE_URL).flush(serverRelease);
    await microtasks();
  }

  it('offers a downloaded optional update on a cold start instead of opening the installer', async () => {
    updater.getDownloadStatus.mockResolvedValue({ state: 'successful', progress: 100, versionCode: 54 });

    await initialize();

    expect(updater.installDownloaded).not.toHaveBeenCalled();
    expect(service.state()).toBe('available');
    expect(service.visible()).toBe(true);
  });

  it('opens the installer on a cold start only for a required update', async () => {
    updater.getDownloadStatus.mockResolvedValue({ state: 'successful', progress: 100, versionCode: 54 });

    await initialize({ ...release, required: true });

    expect(updater.installDownloaded).toHaveBeenCalledTimes(1);
    expect(service.state()).toBe('installing');
  });

  it('does not reopen a cancelled installer when the app returns to the foreground', async () => {
    updater.getDownloadStatus.mockResolvedValue({ state: 'successful', progress: 100, versionCode: 54 });
    await initialize();

    await service.start();
    expect(updater.startDownload).not.toHaveBeenCalled();
    expect(updater.installDownloaded).toHaveBeenCalledTimes(1);
    expect(service.state()).toBe('installing');

    resume({ isActive: true });
    await microtasks();

    expect(updater.installDownloaded).toHaveBeenCalledTimes(1);
    expect(service.state()).toBe('available');
    expect(service.visible()).toBe(true);

    service.defer();
    expect(service.visible()).toBe(false);
    resume({ isActive: true });
    await microtasks();
    expect(updater.installDownloaded).toHaveBeenCalledTimes(1);
  });

  it('continues to the installer after the permission screen only when permission was granted', async () => {
    await initialize();
    updater.getInfo.mockResolvedValue({ versionName: '1.0.53', versionCode: 53, canInstallPackages: false });
    updater.getDownloadStatus.mockResolvedValue({ state: 'successful', progress: 100, versionCode: 54 });

    await service.start();
    expect(service.state()).toBe('permission');
    expect(updater.openInstallPermission).toHaveBeenCalledTimes(1);

    resume({ isActive: true });
    await microtasks();
    expect(updater.openInstallPermission).toHaveBeenCalledTimes(1);
    expect(updater.installDownloaded).not.toHaveBeenCalled();
    expect(service.state()).toBe('error');

    updater.getInfo.mockResolvedValue({ versionName: '1.0.53', versionCode: 53, canInstallPackages: true });
    await service.retry();
    expect(updater.installDownloaded).toHaveBeenCalledTimes(1);
  });

  it('downloads again when the stored APK belongs to another release', async () => {
    updater.getDownloadStatus.mockResolvedValue({ state: 'successful', progress: 100, versionCode: 53 });
    await initialize();

    await service.start();

    expect(updater.installDownloaded).not.toHaveBeenCalled();
    expect(updater.startDownload).toHaveBeenCalledTimes(1);
    expect(service.state()).toBe('downloading');
  });
});
