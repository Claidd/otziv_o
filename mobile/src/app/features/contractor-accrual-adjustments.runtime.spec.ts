import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import type { AdminUser, ContractorAccrualAdjustment, ContractorPaymentProfile } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { ContractorAccrualAdjustmentsComponent } from './contractor-accrual-adjustments.component';

const root = '/api/admin/users/14/contractor-payment-profiles';
const profileUrl = `${root}/27/accrual-adjustments`;
const settle = async () => { for (let i = 0; i < 16; i++) await Promise.resolve(); };
const user = { id: 14, username: 'worker', fio: 'Сотрудник', roles: ['WORKER'] } as AdminUser;
const profile = {
  id: 27, userId: 14, role: 'SPECIALIST', enabled: true, liveEnabled: true,
  workAccruedMonthKopecks: 40_000, bonusMonthKopecks: 0, deductionMonthKopecks: 0,
  accruedMonthKopecks: 40_000, reservedKopecks: 0, netReceivedTotalKopecks: 0,
  availableKopecks: 40_000, exposureOverrunKopecks: 0
} as ContractorPaymentProfile;
const preview = {
  workMonthKopecks: 40_000, bonusMonthKopecks: 0, deductionMonthKopecks: 0,
  accruedMonthBeforeKopecks: 40_000, accruedMonthAfterKopecks: 45_000,
  freeForNewPaymentsBeforeKopecks: 40_000, freeForNewPaymentsAfterKopecks: 45_000,
  paidTotalKopecks: 0, outstandingKopecks: 0, manualTaskCommitmentKopecks: 0
};
const entry = {
  id: 55, profileId: 27, kind: 'BONUS', signedAmountKopecks: 5_000,
  effectiveOn: '2026-10-16', referenceMonth: '2026-10', reason: 'Помощь команде',
  createdBy: 'admin', createdAt: '2026-10-16T10:00:00', reversalOfId: null
} as ContractorAccrualAdjustment;

describe('mobile contractor accrual adjustments', () => {
  let fixture: ComponentFixture<ContractorAccrualAdjustmentsComponent>;
  let page: ContractorAccrualAdjustmentsComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { hasAnyRealmRole: () => true } },
      { provide: MobileConfirmService, useValue: { confirm: async () => true } }
    ] });
    TestBed.overrideComponent(ContractorAccrualAdjustmentsComponent, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ContractorAccrualAdjustmentsComponent);
    page = fixture.componentInstance;
    page.user = user;
    page.ngOnInit();
    http.expectOne(root).flush([profile]);
    await settle();
    http.expectOne(`${profileUrl}/pages?page=0`).flush({ items: [], hasMore: false });
    await settle();
  });

  afterEach(() => {
    fixture.destroy();
    http.verify();
  });

  function fillDraft() {
    page.updateDraft('amountRubles', '50,00');
    page.updateDraft('reason', 'Помощь команде');
    page.updateDraft('referenceMonth', '2026-10');
  }

  it('requires a current preview and uses the same idempotency key when posting the reviewed amount', async () => {
    const saved = vi.fn();
    page.adjustmentSaved.subscribe(saved);
    fillDraft();
    const unreviewed = page.createAdjustment();
    await unreviewed;
    expect(page.actionError()).toContain('Сначала проверьте');
    http.expectNone(profileUrl);

    const pendingPreview = page.previewAdjustment();
    const previewRequest = http.expectOne(`${profileUrl}/preview`);
    expect(previewRequest.request.body).toEqual({
      kind: 'BONUS', amountKopecks: 5_000, reason: 'Помощь команде',
      referenceMonth: '2026-10', evidenceReference: undefined,
      idempotencyKey: page.draft().idempotencyKey
    });
    previewRequest.flush(preview); await pendingPreview;
    expect(page.previewKey()).toBe(page.draft().idempotencyKey);

    const pendingCreate = page.createAdjustment();
    const createRequest = http.expectOne(profileUrl);
    expect(createRequest.request.body.idempotencyKey).toBe(previewRequest.request.body.idempotencyKey);
    expect(createRequest.request.body.amountKopecks).toBe(5_000);
    createRequest.flush(entry); await settle();
    http.expectOne(root).flush([{ ...profile, bonusMonthKopecks: 5_000, accruedMonthKopecks: 45_000 }]);
    http.expectOne(`${profileUrl}/pages?page=0`).flush({ items: [entry], hasMore: false });
    await pendingCreate;
    expect(page.history()[0].id).toBe(55);
    expect(page.activeProfile()?.accruedMonthKopecks).toBe(45_000);
    expect(page.preview()).toBeNull();
    expect(page.draft().idempotencyKey).not.toBe(createRequest.request.body.idempotencyKey);
    expect(saved).toHaveBeenCalledOnce();
  });

  it('drops an outdated preview after the draft changes and reloads profiles after a conflict', async () => {
    const saved = vi.fn();
    page.adjustmentSaved.subscribe(saved);
    fillDraft();
    const first = page.previewAdjustment();
    const staleRequest = http.expectOne(`${profileUrl}/preview`);
    page.updateDraft('amountRubles', '60,00');
    staleRequest.flush(preview); await first;
    expect(page.preview()).toBeNull();
    expect(page.previewLoading()).toBe(false);

    const fresh = page.previewAdjustment();
    http.expectOne(`${profileUrl}/preview`).flush(preview); await fresh;
    const pendingCreate = page.createAdjustment();
    http.expectOne(profileUrl).flush({ detail: 'Остаток зарезервирован' }, { status: 409, statusText: 'Conflict' });
    await pendingCreate;
    expect(page.preview()).toBeNull();
    expect(page.actionError()).toContain('Остаток зарезервирован');
    expect(saved).not.toHaveBeenCalled();
    http.expectOne(root).flush([profile]);
    await settle();
  });

  it('starts the rating shortcut as a deduction on the chosen enabled profile', async () => {
    const managerProfile = { ...profile, id: 28, role: 'MANAGER' as const };
    page.selectProfile(null);
    page.initialKind = 'DEDUCTION';
    page.initialProfileId = managerProfile.id;
    page.onlyEnabledProfiles = true;
    page.ngOnInit();
    http.expectOne(root).flush([{ ...profile, enabled: false }, managerProfile]);
    await settle();
    http.expectOne(`${root}/28/accrual-adjustments/pages?page=0`).flush({ items: [], hasMore: false });
    await settle();

    expect(page.profiles().map((item) => item.id)).toEqual([28]);
    expect(page.selectedProfileId()).toBe(28);
    expect(page.draft().kind).toBe('DEDUCTION');
    page.updateDraft('amountRubles', '25');
    page.updateDraft('reason', 'Исправление начисления');
    const pendingPreview = page.previewAdjustment();
    const request = http.expectOne(`${root}/28/accrual-adjustments/preview`);
    expect(request.request.body.kind).toBe('DEDUCTION');
    request.flush({ ...preview, accruedMonthAfterKopecks: 37_500 });
    await pendingPreview;
  });

  it('paginates history and retries a reversal with the original operation key', async () => {
    const firstPage = page.loadHistory(0);
    http.expectOne(`${profileUrl}/pages?page=0`).flush({ items: [entry], hasMore: true });
    await firstPage;
    page.loadMoreHistory();
    http.expectOne(`${profileUrl}/pages?page=1`).flush({ items: [], hasMore: false });
    await settle();
    expect(page.history()).toHaveLength(1);
    expect(page.nextHistoryPage()).toBeNull();

    page.updateReversalReason(entry.id, 'Ошибка начисления');
    const firstAttempt = page.reverseAdjustment(entry);
    await settle();
    const firstRequest = http.expectOne(`${profileUrl}/${entry.id}/reversals`);
    const originalKey = firstRequest.request.body.idempotencyKey;
    expect(firstRequest.request.body.reason).toBe('Ошибка начисления');
    firstRequest.flush({}, { status: 503, statusText: 'Unavailable' });
    await firstAttempt;
    expect(page.reversalKeys()[entry.id]).toBe(originalKey);

    const retry = page.reverseAdjustment(entry);
    await settle();
    const retryRequest = http.expectOne(`${profileUrl}/${entry.id}/reversals`);
    expect(retryRequest.request.body.idempotencyKey).toBe(originalKey);
    retryRequest.flush({ ...entry, id: 56, kind: 'REVERSAL', reversalOfId: 55, signedAmountKopecks: -5_000 });
    await settle();
    http.expectOne(root).flush([profile]);
    http.expectOne(`${profileUrl}/pages?page=0`).flush({
      items: [{ ...entry, id: 56, kind: 'REVERSAL', reversalOfId: 55, signedAmountKopecks: -5_000 }, entry],
      hasMore: false
    });
    await retry;
    expect(page.canReverse(entry)).toBe(false);
    expect(page.actionSuccess()).toContain('отменена');
  });
});
