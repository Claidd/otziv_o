import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import type { MobileArchiveLockStatus, MobileArchivePreview, MobileArchiveSettings } from '../core/archive-admin.api';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { ArchiveAdminPage } from './archive-admin.page';

const root = '/api/admin/archive';
const settings: MobileArchiveSettings = {
  boardLiveSliceRetentionDays: 14, archiveRetentionDays: 90, batchSize: 10, maxBatchSize: 500,
  applyEnabled: false, scheduleWorkerEnabled: false, scheduleEnabled: false, runMode: 'dry-run',
  reason: 'scheduled-orders-retention-dry-run', scheduleTime: '04:15', scheduleCron: '0 15 4 * * *',
  scheduleZone: 'Asia/Irkutsk'
};
const preview: MobileArchivePreview = {
  cutoffDate: '2026-07-18', retentionDays: 90, batchLimit: 10, eligibleOrders: 3,
  selected: { orders: 3, orderDetails: 5, reviews: 5, badReviewTasks: 0, nextOrderRequests: 0, zp: 2, paymentCheck: 2 },
  missingClosedAnalyticsMonths: 0, items: []
};
const lock: MobileArchiveLockStatus = { name: 'archive-orders', locked: false, ownerConnectionId: null, heldByCurrentConnection: false };
const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

describe('mobile archive administration', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ArchiveAdminPage>;
  let page: ArchiveAdminPage;
  let confirm: { confirm: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    confirm = { confirm: vi.fn(async () => true) };
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), { provide: MobileConfirmService, useValue: confirm }] });
    TestBed.overrideComponent(ArchiveAdminPage, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ArchiveAdminPage);
    page = fixture.componentInstance;
    page.ngOnInit();
    http.expectOne(`${root}/orders/settings`).flush(settings);
    await settle();
    http.expectOne((request) => request.url === `${root}/orders/candidates`).flush(preview);
    http.expectOne(`${root}/orders/lock`).flush(lock);
    http.expectOne(`${root}/batches?limit=30`).flush([]);
    await settle();
  });

  afterEach(() => { fixture.destroy(); http.verify(); });

  it('requires saved live enablement, matching preview, closed analytics and a free lock before physical transfer', async () => {
    page.mode.set('live');
    await page.run();
    http.expectNone((request) => request.url.endsWith('/orders/run'));
    expect(page.canRunLive()).toBe(false);

    page.settings.set({ ...settings, applyEnabled: true });
    page.preview.set({ ...preview, missingClosedAnalyticsMonths: 1 });
    expect(page.canRunLive()).toBe(false);
    page.preview.set(preview);
    page.lock.set({ ...lock, locked: true });
    expect(page.canRunLive()).toBe(false);
    page.lock.set(lock);
    page.setBatchSize(20);
    expect(page.canRunLive()).toBe(false);
    page.setBatchSize(10);
    page.preview.set(preview);
    expect(page.canRunLive()).toBe(true);

    const pending = page.run();
    await settle();
    expect(confirm.confirm).toHaveBeenCalledWith(expect.objectContaining({ danger: true, confirmText: 'Запустить live' }));
    const request = http.expectOne((item) => item.url === `${root}/orders/run`);
    expect(request.request.method).toBe('POST');
    expect(request.request.params.get('confirm')).toBe('true');
    expect(request.request.params.get('retentionDays')).toBe('90');
    expect(request.request.params.get('batchLimit')).toBe('10');
    request.flush({ ...preview, batchId: 41, dryRun: false, message: 'Готово', archived: preview.selected, deleted: preview.selected });
    await settle();
    http.expectOne(`${root}/batches?limit=30`).flush([]);
    http.expectOne((item) => item.url === `${root}/orders/candidates`).flush(preview);
    http.expectOne(`${root}/orders/lock`).flush(lock);
    await pending;
    expect(page.notice()).toContain('#41');
  });

  it('confirms enabling scheduled live transfer and sends the current settings without running a batch', async () => {
    page.draft.applyEnabled = true;
    page.draft.scheduleWorkerEnabled = true;
    page.draft.scheduleEnabled = true;
    page.draft.runMode = 'live';
    const pending = page.saveSettings();
    await settle();
    expect(confirm.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: 'Разрешить live по расписанию', danger: true }));
    const request = http.expectOne(`${root}/orders/settings`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(expect.objectContaining({ applyEnabled: true, scheduleWorkerEnabled: true, scheduleEnabled: true, runMode: 'live' }));
    request.flush({ ...settings, ...request.request.body });
    await settle();
    http.expectOne((item) => item.url === `${root}/orders/candidates`).flush(preview);
    http.expectOne(`${root}/orders/lock`).flush(lock);
    await pending;
    http.expectNone((item) => item.url.endsWith('/orders/run'));
  });

  it('dry-run writes a journal batch without a live confirmation parameter', async () => {
    const pending = page.run();
    const request = http.expectOne((item) => item.url === `${root}/orders/dry-run`);
    expect(request.request.method).toBe('POST');
    expect(request.request.params.has('confirm')).toBe(false);
    request.flush({ ...preview, batchId: 9, dryRun: true, message: 'Проверено' });
    await settle();
    http.expectOne(`${root}/batches?limit=30`).flush([]);
    http.expectOne((item) => item.url === `${root}/orders/candidates`).flush(preview);
    http.expectOne(`${root}/orders/lock`).flush(lock);
    await pending;
    expect(page.result()?.dryRun).toBe(true);
    expect(confirm.confirm).not.toHaveBeenCalled();
  });
});
