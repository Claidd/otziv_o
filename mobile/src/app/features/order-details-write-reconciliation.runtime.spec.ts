import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ToastController } from '@ionic/angular/standalone';
import { BehaviorSubject, Observable, of } from 'rxjs';
import type { OrderDetailsPayload, OrderReviewItem, ReviewRecoveryTaskItem } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileMediaService } from '../shared/mobile-media.service';
import { OrderDetailsPage } from './order-details.page';

const settle = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };
const review = (id = 1, orderId = 101) => ({ id, orderId, companyId: 91, text: `Review ${id}`, answer: '', comment: '', orderComments: '', commentCompany: '', botId: 3 }) as OrderReviewItem;
const details = (orderId = 101, title = 'Before commit') => ({ orderId, companyId: 91, companyTitle: title,
  reviews: [review(1, orderId), review(2, orderId)], badReviewTasks: [], recoveryTasks: [], products: [],
  canEditReviews: true, canDeleteReviews: true, orderComments: '', companyComments: '' }) as unknown as OrderDetailsPayload;
const root = '/api/manager/orders/101';

describe('order details late write reconciliation through actual Angular HTTP feature clients', () => {
  let fixture: ComponentFixture<OrderDetailsPage>;
  let page: OrderDetailsPage;
  let http: HttpTestingController;
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let route: { paramMap: typeof params; queryParamMap: Observable<ReturnType<typeof convertToParamMap>>; snapshot: { paramMap: ReturnType<typeof convertToParamMap>; queryParamMap: ReturnType<typeof convertToParamMap> } };
  let toast: { create: ReturnType<typeof vi.fn> };
  let destroyed: boolean;

  beforeEach(() => {
    destroyed = false;
    params = new BehaviorSubject(convertToParamMap({ companyId: '91', orderId: '101' }));
    route = { paramMap: params, queryParamMap: of(convertToParamMap({})), snapshot: { paramMap: params.value, queryParamMap: convertToParamMap({}) } };
    toast = { create: vi.fn().mockResolvedValue({ present: vi.fn().mockResolvedValue(undefined) }) };
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { hasAnyRealmRole: () => true, hasRealmRole: () => true } },
      { provide: ActivatedRoute, useValue: route }, { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
      { provide: MobileConfirmService, useValue: { confirm: vi.fn().mockResolvedValue(true) } },
      { provide: MobileMediaService, useValue: { nativePhotoPickerAvailable: false } },
      { provide: ToastController, useValue: toast }
    ] });
    TestBed.overrideComponent(OrderDetailsPage, { set: { template: '', imports: [] } });
    fixture = TestBed.createComponent(OrderDetailsPage); page = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController); fixture.detectChanges();
    status(); read();
  });
  afterEach(() => { if (!destroyed) fixture.destroy(); http.verify(); });

  function status(): void { http.expectOne('/api/admin/payments/tbank-status').flush({ managerUiEnabled: true, paymentLinksEnabled: true }); }
  function read(orderId = 101, title = 'Before commit'): void { http.expectOne(`/api/manager/orders/${orderId}/details`).flush(details(orderId, title)); }
  function returnToA(): void { page.ionViewWillLeave(); page.ionViewWillEnter(); status(); read(); }
  function navigate(orderId: number): void {
    route.snapshot.paramMap = convertToParamMap({ companyId: '91', orderId: String(orderId) });
    params.next(route.snapshot.paramMap); read(orderId);
  }
  function newerDrafts(): void {
    page.openReviewEdit(review(2)); page.setReviewEditField('text', 'New form draft');
    page.setReviewNoteDraft(review(), 'New note draft'); page.setReviewSideNoteDraft(review(), 'order', 'New order draft');
    page.error.set('Current screen feedback'); page.mutationKey.set('current-operation');
  }
  function assertNewerDrafts(): void {
    expect(page.reviewEdit()?.id).toBe(2); expect(page.reviewEditDraft()?.text).toBe('New form draft');
    expect(page.reviewNoteValue(review())).toBe('New note draft');
    expect(page.reviewSideNoteValue(review(), 'order')).toBe('New order draft');
    expect(page.error()).toBe('Current screen feedback'); expect(page.mutationKey()).toBe('current-operation');
  }

  async function start(kind: string): Promise<{ write: TestRequest; completion: Promise<void> }> {
    let completion = Promise.resolve(); let url: string;
    switch (kind) {
      case 'details': page.addReview(); url = `${root}/reviews`; break;
      case 'review': page.changeReviewText(review()); url = `${root}/reviews/1/change-text`; break;
      case 'recovery': page.changeRecoveryTaskBot({ id: 77, statusCode: 'PLANNED' } as ReviewRecoveryTaskItem); url = '/api/worker/recovery-tasks/77/change-bot'; break;
      case 'form': page.openReviewEdit(review()); page.setReviewEditField('text', 'Captured form'); page.saveReviewEdit(); url = `${root}/reviews/1`; break;
      case 'text': page.openReviewTextEdit(review(), 'text'); page.setReviewTextEditValue('Captured text'); page.saveReviewTextEdit(); url = `${root}/reviews/1/text`; break;
      case 'delete': page.openReviewEdit(review()); completion = page.deleteReviewEdit(); url = `${root}/reviews/1`; break;
      case 'route':
      case 'issued': {
        const opening = page.openPaymentRouteChange();
        http.expectOne(`${root}/payment-route-change-context`).flush({ canChange: true, configuredMode: 'OWNER_PAPER_INVOICE', paymentLinkId: 444, paperInvoiceIssued: false });
        await opening;
        completion = kind === 'route' ? page.changePaymentRoute('EMPLOYEE_REQUISITES') : page.markPaperInvoiceIssued();
        url = kind === 'route' ? `${root}/payment-route-change` : `${root}/paper-invoice/issued`; break;
      }
      case 'link': page.createPaymentLink(); url = `${root}/payment-link`; break;
      default: throw new Error(`Unknown test scenario ${kind}`);
    }
    await settle();
    const write = http.expectOne(url!); expect(write.request.method).not.toBe('GET');
    return { write, completion };
  }

  for (const kind of ['details', 'review', 'recovery', 'form', 'text', 'delete', 'route', 'issued', 'link']) {
    for (const outcome of ['success', 'error']) {
      it(`${kind}: re-reads after late ${outcome}, without replay, old UI effects or overwriting the returned-page draft`, async () => {
        const { write, completion } = await start(kind);
        returnToA(); expect(write.cancelled).toBe(false); newerDrafts();
        if (outcome === 'success') write.flush({ ...details(101, 'Stale write payload'), ...review() });
        else write.flush({ message: 'Ambiguous transport outcome' }, { status: 503, statusText: 'Unavailable' });
        await completion; await settle();
        assertNewerDrafts(); expect(page.details()?.companyTitle).toBe('Before commit');
        read(101, 'Authoritative state after commit'); await settle();
        expect(page.details()?.companyTitle).toBe('Authoritative state after commit'); assertNewerDrafts();
        expect(toast.create).not.toHaveBeenCalled(); http.expectNone(request => request.method !== 'GET');
      });
    }
  }

  for (const outcome of ['success', 'partial-error']) {
    it(`tracks the captured three-note command through its final ${outcome}, not just its first HTTP request`, async () => {
      page.setReviewNoteDraft(review(), 'Captured review'); page.setReviewSideNoteDraft(review(), 'order', 'Captured order');
      page.setReviewSideNoteDraft(review(), 'company', 'Captured company');
      const command = page.saveAllReviewNotes(review()); const first = http.expectOne(`${root}/reviews/1/note`);
      expect(first.request.body).toEqual({ comment: 'Captured review' });
      returnToA(); newerDrafts(); first.flush({ ...review(), comment: 'Captured review' }); await settle();
      http.expectNone(`${root}/details`);
      const second = http.expectOne(`${root}/note`); expect(second.request.body).toEqual({ orderComments: 'Captured order' });
      if (outcome === 'partial-error') second.flush({}, { status: 503, statusText: 'Unavailable' });
      else {
        second.flush({}); await settle(); http.expectNone(`${root}/details`);
        const third = http.expectOne(`${root}/company-note`); expect(third.request.body).toEqual({ companyComments: 'Captured company' }); third.flush({});
      }
      await command; await settle(); read(101, 'After note command'); assertNewerDrafts();
      http.expectNone(request => request.method !== 'GET');
    });
  }

  it('does not refresh B when A settles, and does not apply A to B', async () => {
    const { write } = await start('details'); navigate(202); write.flush(details(101, 'Old A')); await settle();
    expect(page.details()?.orderId).toBe(202); http.expectNone(() => true);
  });
  it('reconciles A after A→B→A even when the component instance never leaves', async () => {
    const { write } = await start('details'); navigate(202); navigate(101); newerDrafts();
    write.flush(details(101, 'Old A')); await settle(); read(101, 'Committed A'); assertNewerDrafts();
  });
  it('does not read while hidden; its next enter reads the settled state', async () => {
    const { write } = await start('details'); page.ionViewWillLeave(); write.flush(details()); await settle(); http.expectNone(() => true);
    page.ionViewWillEnter(); status(); read(101, 'Committed while hidden'); expect(page.details()?.companyTitle).toBe('Committed while hidden');
  });
  it('keeps a dispatched write alive after destruction without any reconciliation or UI effects', async () => {
    const { write } = await start('details'); fixture.destroy(); destroyed = true; expect(write.cancelled).toBe(false);
    write.flush(details(101, 'Old A')); await settle(); http.expectNone(() => true);
  });
  it('does not replace current-page feedback when the reconciliation GET also fails', async () => {
    const { write } = await start('review'); returnToA(); newerDrafts();
    write.flush({}, { status: 503, statusText: 'Unavailable' }); await settle();
    http.expectOne(`${root}/details`).flush({}, { status: 503, statusText: 'Unavailable' });
    assertNewerDrafts(); expect(page.loading()).toBe(false); http.expectNone(() => true);
  });
  it('reconciles an open company report with a GET after its old job settles, without replaying even when no job is found', async () => {
    page.openCompanyReport();
    http.expectOne(`${root}/company-report`).flush({ canStart: true, latestJob: null, activeJob: null });
    const write = http.expectOne(request => request.url === `${root}/company-report` && request.method === 'POST');
    returnToA(); page.openCompanyReport();
    http.expectOne(`${root}/company-report`).flush({ canStart: true, latestJob: null, activeJob: null });
    http.expectNone(request => request.method !== 'GET');
    write.flush({}, { status: 503, statusText: 'Unavailable' }); await settle();
    read(); http.expectOne(`${root}/company-report`).flush({ canStart: true, latestJob: null, activeJob: null });
    expect(page.companyReportVisible()).toBe(true); expect(page.companyReportLoading()).toBe(false);
    http.expectNone(request => request.method !== 'GET');
  });
  it('does not dispatch route reads from the cached hidden page and enters the latest route once', () => {
    page.ionViewWillLeave();
    route.snapshot.paramMap = convertToParamMap({ companyId: '91', orderId: '202' });
    params.next(route.snapshot.paramMap); http.expectNone(() => true);
    page.ionViewWillEnter(); status(); read(202); expect(page.details()?.orderId).toBe(202);
  });
});
