import fixtures from '../../../../contracts/fixtures/client-api-current.json';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, type TestRequest } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { MobileManualCardPaymentFlowService } from '../shared/mobile-manual-card-payment-flow.service';
import { TbankPage } from './tbank.page';
import type { ManualPaymentTaskResponse } from '../core/api.service';

const journalUrl = '/api/admin/payments/tbank-links';
const settle = async () => { for (let i = 0; i < 16; i++) await Promise.resolve(); };
const pageResponse = (id: number, page = 0) => ({
  ...fixtures.responses.AdminPaymentLinksPageResponseOutput,
  items: [{ ...fixtures.responses.AdminPaymentLinkResponseOutput, id }],
  page, size: 10, totalElements: 33, totalPages: 4,
  summary: { ...fixtures.responses.AdminPaymentLinkSummaryResponseOutput, totalElements: 33 }
});

describe('mobile payment journal with real HttpClient and Ionic page lifetimes', () => {
  let fixture: ComponentFixture<TbankPage>;
  let page: TbankPage;
  let http: HttpTestingController;
  let initial: TestRequest;
  let bootstrap: TestRequest[];

  function flushBootstrap() {
    for (const request of bootstrap) {
      if (request.cancelled) continue;
      const url = request.request.url;
      request.flush(url.endsWith('tbank-profiles') ? { profiles: [], managers: [] }
        : url.endsWith('manual-tasks') ? [] : {});
    }
    bootstrap = [];
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: MobileConfirmService, useValue: { confirm: async () => true } },
      { provide: MobileExternalLinkService, useValue: {} },
      { provide: MobileManualCardPaymentFlowService, useValue: {} }
    ] });
    TestBed.overrideComponent(TbankPage, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TbankPage);
    page = fixture.componentInstance; page.ngOnInit();
    initial = http.expectOne(request => request.url === journalUrl);
    bootstrap = http.match(request => request.url !== journalUrl);
  });

  afterEach(() => {
    fixture.destroy(); flushBootstrap(); http.verify({ ignoreCancelled: true });
  });

  it('sends the final search during bootstrap and only applies its page and summary', async () => {
    page.setSearch('a'); const a = http.expectOne(request => request.url === journalUrl);
    page.setSearch('ab'); const ab = http.expectOne(request => request.url === journalUrl);
    expect(initial.cancelled).toBe(true); expect(a.cancelled).toBe(true);
    expect(ab.request.params.get('search')).toBe('ab');
    ab.flush(pageResponse(12)); await settle();
    expect(page.links()[0].id).toBe(12);
    expect(page.paymentSummary()?.totalElements).toBe(33);
    expect(page.journalLoading()).toBe(false);
    expect(page.loading()).toBe(true);
    flushBootstrap(); await settle();
    expect(page.loading()).toBe(false);
    expect(page.links()[0].id).toBe(12);
  });

  it('cancels obsolete filters, drops stale errors and keeps current pagination', async () => {
    initial.flush(pageResponse(1)); flushBootstrap(); await settle();
    page.setStatusFilter('paid'); const paid = http.expectOne(request => request.url === journalUrl);
    page.setStatusFilter('failed'); const failed = http.expectOne(request => request.url === journalUrl);
    expect(paid.cancelled).toBe(true);
    expect(failed.request.params.get('status')).toBe('failed');
    failed.flush(pageResponse(2)); await settle();
    expect(() => paid.flush({}, { status: 503, statusText: 'Unavailable' })).toThrow(/cancelled/);
    expect(page.journalError()).toBeNull();
    page.selectPaymentSource('ARCHIVE'); const archive = http.expectOne(request => request.url === journalUrl);
    expect(archive.request.params.get('source')).toBe('ARCHIVE');
    archive.flush(pageResponse(3)); await settle();
    page.nextPage(); const next = http.expectOne(request => request.url === journalUrl);
    expect(next.request.params.get('page')).toBe('1'); next.flush(pageResponse(4, 1)); await settle();
    expect(page.pageIndex()).toBe(1); expect(page.links()[0].id).toBe(4);
    expect(page.paymentTotalElements()).toBe(33); expect(page.loading()).toBe(false);
  });

  it('shows only the current error and lets search submit retry the final query', async () => {
    flushBootstrap(); initial.flush({}, { status: 503, statusText: 'Unavailable' }); await settle();
    expect(page.journalError()).toBeTruthy();
    page.setSearch('retry'); const cancelled = http.expectOne(request => request.url === journalUrl);
    page.submitSearch(); const retry = http.expectOne(request => request.url === journalUrl);
    expect(cancelled.cancelled).toBe(true); expect(retry.request.params.get('search')).toBe('retry');
    expect(page.journalError()).toBeNull(); expect(page.journalLoading()).toBe(true);
    retry.flush(pageResponse(5)); await settle();
    expect(page.links()[0].id).toBe(5); expect(page.loading()).toBe(false);
  });

  it('sorts the complete server journal and cancels a pending page before resetting to page one', async () => {
    initial.flush(pageResponse(1)); flushBootstrap(); await settle();
    page.nextPage(); const pendingPage = http.expectOne(request => request.url === journalUrl);
    expect(pendingPage.request.params.get('page')).toBe('1');
    expect(pendingPage.request.params.get('sortDirection')).toBe('desc');
    page.toggleSort(); const ascending = http.expectOne(request => request.url === journalUrl);
    expect(pendingPage.cancelled).toBe(true);
    expect(ascending.request.params.get('page')).toBe('0');
    expect(ascending.request.params.get('sortDirection')).toBe('asc');
    expect(page.pageIndex()).toBe(0); expect(page.links()).toEqual([]); expect(page.journalLoading()).toBe(true);
    const ordered = [{ ...fixtures.responses.AdminPaymentLinkResponseOutput, id: 21, createdAt: '2020-01-01' },
      { ...fixtures.responses.AdminPaymentLinkResponseOutput, id: 22, createdAt: '2020-01-01' }];
    ascending.flush({ ...pageResponse(21), items: ordered }); await settle();
    expect(page.pageLinks().map(link => link.id)).toEqual([21, 22]);
    expect(page.paymentTotalElements()).toBe(33); expect(page.journalLoading()).toBe(false);
    expect(() => pendingPage.flush(pageResponse(99, 1))).toThrow(/cancelled/);
    page.toggleSort(); const descending = http.expectOne(request => request.url === journalUrl);
    expect(descending.request.params.get('sortDirection')).toBe('desc');
    descending.flush({ ...pageResponse(22), items: [...ordered].reverse() }); await settle();
    expect(page.pageLinks().map(link => link.id)).toEqual([22, 21]);
    expect(page.links().map(link => link.id)).toEqual([22, 21]);
  });

  it('cancels every pending read on Ionic leave and reloads the latest query on resume', async () => {
    page.setSearch('resume'); const pending = http.expectOne(request => request.url === journalUrl);
    page.ionViewWillLeave(); await settle();
    expect(initial.cancelled).toBe(true); expect(pending.cancelled).toBe(true);
    expect(bootstrap.every(request => request.cancelled)).toBe(true);
    expect(page.loading()).toBe(false); expect(page.loadingRecipientSummary()).toBe(false);
    page.setSearch('latest'); http.expectNone(request => request.url === journalUrl);
    page.ionViewWillEnter();
    const resumed = http.expectOne(request => request.url === journalUrl);
    expect(resumed.request.params.get('search')).toBe('latest');
    bootstrap = http.match(request => request.url !== journalUrl);
    resumed.flush(pageResponse(8)); flushBootstrap(); await settle();
    expect(page.links()[0].id).toBe(8); expect(page.loading()).toBe(false);
  });

  it('does not overwrite unsaved routing assignments when a cached Ionic page resumes', async () => {
    initial.flush(pageResponse(1)); flushBootstrap(); await settle();
    page.profileAssignments.set({ 10: 22 }); page.profileAssignmentsDirty.set(true);
    page.ionViewWillLeave(); page.ionViewWillEnter();
    const resumed = http.expectOne(request => request.url === journalUrl);
    http.expectNone(request => request.url.endsWith('tbank-profiles'));
    resumed.flush(pageResponse(2)); await settle();
    expect(page.profileAssignments()).toEqual({ 10: 22 });
    expect(page.profileAssignmentsDirty()).toBe(true);
  });

  it('keeps an in-flight write single and refreshes the current filter after completion', async () => {
    initial.flush(pageResponse(5)); flushBootstrap(); await settle();
    const result = page.cancel({ ...page.links()[0], refundable: true }); await settle();
    const write = http.expectOne('/api/admin/payments/tbank-links/5/cancel');
    page.setStatusFilter('failed'); const earlier = http.expectOne(request => request.url === journalUrl);
    write.flush({ ...pageResponse(5).items[0], status: 'REFUNDED' }); await result;
    expect(earlier.cancelled).toBe(true);
    const current = http.expectOne(request => request.url === journalUrl);
    expect(current.request.params.get('status')).toBe('failed');
    current.flush(pageResponse(6)); await settle();
    http.expectNone('/api/admin/payments/tbank-links/5/cancel');
    expect(page.links()[0].id).toBe(6);
  });

  it('does not cancel or replay an in-flight write on leave, and reloads after returning', async () => {
    initial.flush(pageResponse(5)); flushBootstrap(); await settle();
    const result = page.cancel({ ...page.links()[0], refundable: true }); await settle();
    const write = http.expectOne('/api/admin/payments/tbank-links/5/cancel');
    page.ionViewWillLeave(); expect(write.cancelled).toBe(false);
    write.flush({ ...pageResponse(5).items[0], status: 'REFUNDED' }); await result;
    http.expectNone(request => request.url === journalUrl);
    http.expectNone('/api/admin/payments/tbank-links/5/cancel');
    page.ionViewWillEnter();
    const current = http.expectOne(request => request.url === journalUrl);
    bootstrap = http.match(request => request.url !== journalUrl);
    current.flush(pageResponse(7)); flushBootstrap(); await settle();
    expect(page.links()[0].id).toBe(7);
  });

  async function openTask(id: number) {
    const task: ManualPaymentTaskResponse = { ...fixtures.responses.ManualPaymentTaskResponseOutput, id,
      status: 'ACTIVE', manualPaymentType: 'MOBILE_BANK', manualPhone: '+79990000001',
      manualRecipientName: `Task ${id}`, targetAmountKopecks: 100000, reservedAmountKopecks: 0,
      accountingTargetKind: 'OWNER', accountingTargetProfileId: 77 };
    page.startManualTaskEdit(task);
    http.expectOne(request => request.url.endsWith('/manual-tasks/accounting-targets')).flush([{
      ...fixtures.responses.ManualPaymentTaskAccountingTargetOptionOutput,
      key: 'owner-77', kind: 'OWNER', profileId: 77, enabled: true, projectedOverrunKopecks: 0
    }]);
    await settle(); return task;
  }

  for (const selected of [702, 701]) {
    for (const outcome of ['success', 'error']) {
      it(`keeps task ${selected} editor after old A save ${outcome}, including ABA, and submits once`, async () => {
        initial.flush(pageResponse(1)); flushBootstrap(); await settle();
        const first = await openTask(701); page.manualTasks.set([first]);
        page.editTaskComment.set('A submitted'); const result = page.saveManualTaskEdit(first);
        const write = http.expectOne('/api/admin/payments/manual-tasks/701');
        const second = await openTask(702);
        const current = selected === 701 ? await openTask(701) : second;
        page.editTaskComment.set('Current unsaved');
        expect(page.canSaveManualTaskEdit(current)).toBe(false);
        await page.saveManualTaskEdit(current);
        http.expectNone(request => request.method === 'PUT');
        if (outcome === 'success') write.flush({ ...first, comment: 'A submitted', generation: 3 });
        else write.flush({ message: 'Old task failure' }, { status: 503, statusText: 'Unavailable' });
        await result;
        expect(page.editingTaskId()).toBe(selected); expect(page.editTaskComment()).toBe('Current unsaved');
        expect(page.mutatingTaskId()).toBeNull(); expect(page.canSaveManualTaskEdit(current)).toBe(true);
        expect(page.error()).toBeNull(); expect(write.request.body.comment).toBe('A submitted');
      });
    }
  }

  for (const outcome of ['success', 'error']) {
    it(`retains current task editor ${outcome} behavior without replaying writes`, async () => {
      initial.flush(pageResponse(1)); flushBootstrap(); await settle();
      const task = await openTask(701); page.manualTasks.set([task]);
      page.editTaskComment.set('Submitted'); const result = page.saveManualTaskEdit(task);
      const write = http.expectOne('/api/admin/payments/manual-tasks/701');
      if (outcome === 'success') write.flush({ ...task, comment: 'Submitted' });
      else write.flush({}, { status: 503, statusText: 'Unavailable' });
      await result;
      if (outcome === 'success') {
        expect(page.editingTaskId()).toBeNull(); expect(page.manualTasks()[0].comment).toBe('Submitted');
      } else {
        expect(page.editingTaskId()).toBe(701); expect(page.editTaskComment()).toBe('Submitted');
        expect(page.canSaveManualTaskEdit(task)).toBe(true); expect(page.error()).toBeTruthy();
      }
      expect(page.mutatingTaskId()).toBeNull(); http.expectNone('/api/admin/payments/manual-tasks/701');
    });
  }

  for (const outcome of ['success', 'error']) {
    it(`ignores old task ${outcome} after Ionic leave/resume without cancelling or replaying its write`, async () => {
      initial.flush(pageResponse(1)); flushBootstrap(); await settle();
      const task = await openTask(701); page.manualTasks.set([task]);
      const result = page.saveManualTaskEdit(task);
      const write = http.expectOne('/api/admin/payments/manual-tasks/701');
      page.ionViewWillLeave(); expect(write.cancelled).toBe(false);
      page.ionViewWillEnter();
      const resumed = http.expectOne(request => request.url === journalUrl);
      bootstrap = http.match(request => request.url !== journalUrl);
      resumed.flush(pageResponse(2)); flushBootstrap(); await settle();
      const current = await openTask(701); page.manualTasks.set([{ ...current, comment: 'Fresh read' }]);
      page.editTaskComment.set('Resumed unsaved');
      if (outcome === 'success') write.flush({ ...task, comment: 'Obsolete save' });
      else write.flush({}, { status: 503, statusText: 'Unavailable' });
      await result;
      expect(page.manualTasks()[0].comment).toBe('Fresh read');
      expect(page.editingTaskId()).toBe(701); expect(page.editTaskComment()).toBe('Resumed unsaved');
      expect(page.error()).toBeNull(); expect(page.mutatingTaskId()).toBeNull();
      http.expectNone('/api/admin/payments/manual-tasks/701');
    });
  }
});
