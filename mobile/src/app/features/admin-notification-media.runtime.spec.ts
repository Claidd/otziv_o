import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { MobileMediaService } from '../shared/mobile-media.service';
import type { NotificationMediaRule } from '../core/admin-notification-media.api';
import { AdminNotificationMediaPage } from './admin-notification-media.page';

const root = '/api/admin/notification-media';
const rule: NotificationMediaRule = {
  id: 11, eventCode: 'WORKER_REVIEW_READY', recipientType: 'WORKER',
  eventLabel: 'Готов отзыв', eventDescription: 'Уведомление специалисту', serious: false,
  enabled: true, imageProbabilityPercent: 100, cooldownMinutes: 0, images: [],
  createdAt: '2026-10-16T10:00:00', updatedAt: '2026-10-16T10:00:00'
};
const image = new File([new Uint8Array([137, 80, 78, 71])], 'notice.png', { type: 'image/png' });
const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

describe('mobile notification media administration', () => {
  let fixture: ComponentFixture<AdminNotificationMediaPage>;
  let page: AdminNotificationMediaPage;
  let http: HttpTestingController;
  let confirmation = true;

  beforeEach(async () => {
    confirmation = true;
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: MobileConfirmService, useValue: { confirm: async () => confirmation } },
      { provide: MobileExternalLinkService, useValue: { open: async () => undefined } },
      { provide: MobileMediaService, useValue: { nativePhotoPickerAvailable: true, pickImageFile: async () => image } }
    ] });
    TestBed.overrideComponent(AdminNotificationMediaPage, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminNotificationMediaPage);
    page = fixture.componentInstance;
    page.ngOnInit();
    http.expectOne(`${root}/events`).flush([{ code: rule.eventCode, recipientType: 'WORKER', label: rule.eventLabel, description: '', serious: false }]);
    http.expectOne(`${root}/rules`).flush([]);
    await settle();
  });

  afterEach(() => { fixture.destroy(); http.verify(); });

  it('creates one rule for an available event and saves only explicit settings', async () => {
    page.newEventCode.set(rule.eventCode);
    const creating = page.createRule();
    const create = http.expectOne(`${root}/rules`);
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual({ eventCode: rule.eventCode, enabled: true, imageProbabilityPercent: 100, cooldownMinutes: 0 });
    create.flush(rule); await creating;
    expect(page.availableEvents()).toEqual([]);

    page.setRuleEnabled(rule.id, false);
    page.setRuleNumber(rule.id, 'probability', 48);
    const saving = page.saveRule(rule);
    const update = http.expectOne(`${root}/rules/${rule.id}`);
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({ eventCode: rule.eventCode, enabled: false, imageProbabilityPercent: 48, cooldownMinutes: 0 });
    update.flush({ ...rule, enabled: false, imageProbabilityPercent: 48 });
    await saving;
    expect(page.rules()[0].enabled).toBe(false);
  });

  it('sends a Telegram test only after explicit confirmation', async () => {
    confirmation = false;
    await page.sendTest(rule);
    http.expectNone(`${root}/rules/${rule.id}/test`);

    confirmation = true;
    const sending = page.sendTest(rule);
    await settle();
    const request = http.expectOne(`${root}/rules/${rule.id}/test`);
    expect(request.request.method).toBe('POST');
    request.flush({ sent: true, message: 'Тест отправлен в личный Telegram' });
    await sending;
    expect(page.notice()).toContain('Telegram');
  });

  it('uses multipart files for gallery upload and requires confirmation before deleting S3 media', async () => {
    const uploading = page.pickAndUpload(rule);
    await settle();
    const upload = http.expectOne(`${root}/rules/${rule.id}/images`);
    expect(upload.request.method).toBe('POST');
    expect(upload.request.body instanceof FormData).toBe(true);
    expect(upload.request.body.get('files')).toBe(image);
    upload.flush({ ...rule, images: [{
      id: 20, imageUrl: 'https://example.test/notice.png', originalFilename: 'notice.png',
      contentType: 'image/png', active: true, sortOrder: 0, createdAt: rule.createdAt, updatedAt: rule.updatedAt
    }] });
    await uploading;

    confirmation = false;
    await page.deleteAsset(page.rules()[0].images[0]);
    http.expectNone(`${root}/images/20`);
    confirmation = true;
    const deleting = page.deleteAsset(page.rules()[0].images[0]);
    await settle();
    const deletion = http.expectOne(`${root}/images/20`);
    expect(deletion.request.method).toBe('DELETE');
    deletion.flush(rule);
    await deleting;
    expect(page.rules()[0].images).toEqual([]);
  });
});
