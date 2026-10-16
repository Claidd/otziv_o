import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { PerformerAdminApi, type MobilePerformerControl } from '../core/performer-admin.api';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { AdminPerformersPage } from './admin-performers.page';

const root = '/api/admin/performers';
const control: MobilePerformerControl = {
  rollout: { enabled: false, cityIds: '', productIds: '', parsedCityIds: [], parsedProductIds: [] },
  cityReports: [], assignments: [],
  performers: [{
    id: 7, userId: 70, username: 'person', fio: 'Исполнитель', phoneNumber: '79990000000',
    cityTitle: 'Москва', status: 'NEW', completedCount: 0, cancelledCount: 0,
    expiredOfferCount: 0, failedCheckCount: 0, telegramChatId: 123,
    phoneVerifiedAt: null, activationReady: true, legacyApprovedBeforeSecureLifecycle: false,
    activationWarning: ''
  }]
};

const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

describe('mobile performer administration', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AdminPerformersPage>;
  let page: AdminPerformersPage;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: MobileConfirmService, useValue: { confirm: async () => true } },
      { provide: MobileExternalLinkService, useValue: { open: async () => undefined } }
    ] });
    TestBed.overrideComponent(AdminPerformersPage, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminPerformersPage);
    page = fixture.componentInstance;
    page.ngOnInit();
    http.expectOne(`${root}/control`).flush(control);
    await settle();
  });

  afterEach(() => { fixture.destroy(); http.verify(); });

  it('does not activate without outside phone verification and a written method', async () => {
    const person = control.performers[0];
    await page.updateStatus(person, 'ACTIVE');
    http.expectNone(`${root}/${person.id}/status`);

    page.setPhoneChecked(person.id, true);
    expect(page.canActivate(person)).toBe(false);
    page.statusReason.set('Звонок по номеру в профиле');
    expect(page.canActivate(person)).toBe(true);

    const pending = page.updateStatus(person, 'ACTIVE');
    await settle();
    const request = http.expectOne((item) => item.url === `${root}/${person.id}/status`);
    expect(request.request.method).toBe('POST');
    expect(request.request.params.get('status')).toBe('ACTIVE');
    expect(request.request.params.get('phoneVerified')).toBe('true');
    expect(request.request.params.get('reason')).toBe('Звонок по номеру в профиле');
    request.flush({ ...person, status: 'ACTIVE' });
    await settle();
    http.expectOne(`${root}/control`).flush({ ...control, performers: [{ ...person, status: 'ACTIVE' }] });
    await pending;
    expect(page.phoneChecked()[person.id]).toBe(false);
  });

  it('posts rollout settings and manually creates only whole-number order assignments', async () => {
    const api = TestBed.inject(PerformerAdminApi);
    const rollout = firstValueFrom(api.updateRollout({ enabled: true, cityIds: '1, 7', productIds: '12' }));
    const rolloutRequest = http.expectOne(`${root}/rollout`);
    expect(rolloutRequest.request.method).toBe('PUT');
    expect(rolloutRequest.request.body).toEqual({ enabled: true, cityIds: '1, 7', productIds: '12' });
    rolloutRequest.flush({ ...control.rollout, enabled: true, cityIds: '1, 7', productIds: '12' });
    await rollout;

    page.setManualOrderId(1.5);
    await page.createAssignments();
    expect(page.error()).toContain('числовой ID');
    http.expectNone(`${root}/orders/1.5/assignments`);

    page.setManualOrderId(1542);
    const pending = page.createAssignments();
    await settle();
    const request = http.expectOne(`${root}/orders/1542/assignments`);
    expect(request.request.method).toBe('POST');
    request.flush({ createdAssignments: 3, expiredOffers: 0, offeredAssignments: 0, readyNotifications: 0 });
    await settle();
    http.expectOne(`${root}/control`).flush(control);
    await pending;
    expect(page.notice()).toContain('3');
  });
});
