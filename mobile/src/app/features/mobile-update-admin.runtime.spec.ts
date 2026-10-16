import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import type { MobilePublishedRelease } from '../core/mobile-update-admin.api';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { AdminMobileUpdatePage } from './mobile-update-admin.page';

const release: MobilePublishedRelease = {
  enabled: true, versionCode: 74, versionName: '1.0.74', minSupportedVersionCode: 70,
  required: false, notes: 'Новые функции', downloadUrl: '/api/mobile-update/download',
  fileSize: 12_000_000, sha256: 'ABCDEF', publishedAt: '2026-10-16T08:00:00Z'
};

describe('mobile administrator update status', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AdminMobileUpdatePage>;
  let page: AdminMobileUpdatePage;
  let open: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    open = vi.fn(async () => undefined);
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: MobileExternalLinkService, useValue: { open } }
    ] });
    TestBed.overrideComponent(AdminMobileUpdatePage, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminMobileUpdatePage);
    page = fixture.componentInstance;
    page.ngOnInit();
    http.expectOne('/api/mobile-update').flush(release);
    for (let i = 0; i < 8; i++) await Promise.resolve();
  });

  afterEach(() => { fixture.destroy(); http.verify(); });

  it('reads only the published release and compares it with the installed Android code', () => {
    expect(page.release()?.versionCode).toBe(74);
    page.installed.set({ versionCode: 69, versionName: '1.0.69', canInstallPackages: true });
    expect(page.updateStatus()).toContain('обязательное');
    page.installed.set({ versionCode: 73, versionName: '1.0.73', canInstallPackages: true });
    expect(page.updateStatus()).toContain('новая версия');
    page.installed.set({ versionCode: 74, versionName: '1.0.74', canInstallPackages: true });
    expect(page.updateStatus()).toContain('актуальная');
    http.expectNone('/api/admin/mobile-update');
  });

  it('links to the controlled release and signing instructions without uploading an APK', async () => {
    await page.openReleaseGuide();
    await page.openSigningGuide();
    expect(open).toHaveBeenNthCalledWith(1, 'https://github.com/Claidd/otziv_o/blob/main/docs/UNIFIED_MAIN_WORKFLOW.md');
    expect(open).toHaveBeenNthCalledWith(2, 'https://github.com/Claidd/otziv_o/blob/main/docs/ANDROID_RELEASE_SIGNING.md');
    http.expectNone((request) => request.method !== 'GET');
  });
});
