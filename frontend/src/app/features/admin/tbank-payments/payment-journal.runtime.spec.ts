import fixtures from '../../../../../../contracts/fixtures/client-api-current.json';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, type TestRequest } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { ToastService } from '../../../shared/toast.service';
import { TbankPaymentsComponent } from './tbank-payments.component';
import type { ManualPaymentTaskResponse } from '../../../core/payments.api';

const journalUrl = '/api/admin/payments/tbank-links';
const page = (id: number, number = 0) => ({
  ...fixtures.responses.AdminPaymentLinksPageResponseOutput,
  items: [{ ...fixtures.responses.AdminPaymentLinkResponseOutput, id }],
  page: number, size: 25, totalElements: 83, totalPages: 4,
  summary: { ...fixtures.responses.AdminPaymentLinkSummaryResponseOutput, totalElements: 83 }
});

describe('payment journal query ownership through HttpClient', () => {
  let fixture: ComponentFixture<TbankPaymentsComponent>;
  let component: TbankPaymentsComponent;
  let http: HttpTestingController;
  let initial: TestRequest;
  let bootstrap: TestRequest[];
  const toast = { error: vi.fn(), success: vi.fn() };

  function flushBootstrap() {
    for (const request of bootstrap) {
      if (request.cancelled) continue;
      const url = request.request.url;
      request.flush(url.endsWith('bank-profiles') ? { profiles: [], managers: [] }
        : url.endsWith('manual-tasks') ? [] : {});
    }
    bootstrap = [];
  }

  beforeEach(() => {
    vi.useFakeTimers(); toast.error.mockClear(); toast.success.mockClear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), { provide: ToastService, useValue: toast }] });
    TestBed.overrideComponent(TbankPaymentsComponent, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TbankPaymentsComponent);
    component = fixture.componentInstance;
    initial = http.expectOne(request => request.url === journalUrl);
    bootstrap = http.match(request => request.url !== journalUrl);
  });

  afterEach(() => {
    fixture.destroy(); flushBootstrap();
    http.verify({ ignoreCancelled: true }); vi.useRealTimers();
  });

  it('cancels the bootstrap journal as soon as search changes and sends the last debounced query', async () => {
    component.setSearch('a'); component.setSearch('ab');
    expect(initial.cancelled).toBe(true);
    expect(component.links()).toEqual([]);
    await vi.advanceTimersByTimeAsync(260);
    const current = http.expectOne(request => request.url === journalUrl);
    expect(current.request.params.get('search')).toBe('ab');
    current.flush(page(21));
    expect(component.links()[0].id).toBe(21);
    expect(component.paymentSummary()?.totalElements).toBe(83);
    expect(component.journalLoading()).toBe(false);
    expect(component.loading()).toBe(true); // independent bootstrap is still pending
    flushBootstrap();
    expect(component.loading()).toBe(false);
    expect(component.links()[0].id).toBe(21);
  });

  it('only applies the last status/source/page query and suppresses stale errors', () => {
    initial.flush(page(1)); flushBootstrap();
    component.setStatusFilter('paid');
    const paid = http.expectOne(request => request.url === journalUrl);
    component.setStatusFilter('failed');
    const failed = http.expectOne(request => request.url === journalUrl);
    expect(paid.cancelled).toBe(true);
    expect(failed.request.params.get('status')).toBe('failed');
    failed.flush(page(2));
    expect(component.links()[0].id).toBe(2);
    expect(() => paid.flush({}, { status: 503, statusText: 'Unavailable' })).toThrow(/cancelled/);
    expect(toast.error).not.toHaveBeenCalled();
    component.setPaymentSource('ARCHIVE');
    const archive = http.expectOne(request => request.url === journalUrl);
    expect(archive.request.params.get('source')).toBe('ARCHIVE');
    archive.flush(page(3));
    component.setPaymentPage(2);
    const paginated = http.expectOne(request => request.url === journalUrl);
    expect(paginated.request.params.get('page')).toBe('2');
    paginated.flush(page(4, 2));
    expect(component.paymentPage()).toBe(2);
    expect(component.links()[0].id).toBe(4);
    expect(component.journalError()).toBeNull();
  });

  it('clears a current query error when retrying and leaves cancelled reads unable to change loading', () => {
    flushBootstrap(); initial.flush({}, { status: 503, statusText: 'Unavailable' });
    expect(component.journalError()).toBeTruthy();
    expect(component.journalLoading()).toBe(false);
    component.setStatusFilter('paid');
    const request = http.expectOne(candidate => candidate.url === journalUrl);
    expect(component.journalError()).toBeNull();
    expect(component.journalLoading()).toBe(true);
    request.flush(page(8));
    expect(component.loading()).toBe(false);
  });

  it('stops pending bootstrap, query and debounce work when the component is destroyed', async () => {
    component.setSearch('later'); fixture.destroy();
    expect(initial.cancelled).toBe(true);
    expect(bootstrap.filter(request => !request.request.url.includes('monthly-summary')).every(request => request.cancelled)).toBe(true);
    await vi.advanceTimersByTimeAsync(300);
    http.expectNone(request => request.url === journalUrl);
    flushBootstrap();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('does not replay a write when the filter changes and refreshes the current query after it completes', () => {
    initial.flush(page(5)); flushBootstrap();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    component.cancel({ ...component.links()[0], refundable: true, archived: false });
    const write = http.expectOne('/api/admin/payments/tbank-links/5/cancel');
    component.setStatusFilter('failed');
    const beforeWriteFinished = http.expectOne(request => request.url === journalUrl);
    write.flush({ ...page(5).items[0], status: 'REFUNDED' });
    expect(beforeWriteFinished.cancelled).toBe(true);
    const current = http.expectOne(request => request.url === journalUrl);
    expect(current.request.params.get('status')).toBe('failed');
    current.flush(page(6));
    http.expectNone('/api/admin/payments/tbank-links/5/cancel');
    expect(component.links()[0].id).toBe(6);
  });

  function openTask(id: number) {
    const task: ManualPaymentTaskResponse = { ...fixtures.responses.ManualPaymentTaskResponseOutput, id,
      status: 'ACTIVE', manualPaymentType: 'MOBILE_BANK', manualPhone: '+79990000001',
      manualRecipientName: `Task ${id}`, targetAmountKopecks: 100000, reservedAmountKopecks: 0,
      accountingTargetKind: 'OWNER', accountingTargetProfileId: 77 };
    component.startManualTaskEdit(task);
    http.expectOne(request => request.url.endsWith('/manual-tasks/accounting-targets')).flush([{
      ...fixtures.responses.ManualPaymentTaskAccountingTargetOptionOutput,
      key: 'owner-77', kind: 'OWNER', profileId: 77, enabled: true, projectedOverrunKopecks: 0
    }]);
    return task;
  }

  for (const selected of [702, 701]) {
    for (const outcome of ['success', 'error']) {
      it(`keeps task ${selected} editor after old A save ${outcome}, including ABA, and submits once`, () => {
        initial.flush(page(1)); flushBootstrap();
        const first = openTask(701); component.manualTasks.set([first]);
        component.setEditTaskComment('A submitted'); component.saveManualTaskEdit(first);
        const write = http.expectOne('/api/admin/payments/manual-tasks/701');
        const second = openTask(702);
        const current = selected === 701 ? openTask(701) : second;
        component.setEditTaskComment('Current unsaved');
        expect(component.canSaveManualTaskEdit(current)).toBe(false);
        component.saveManualTaskEdit(current);
        http.expectNone(request => request.method === 'PUT');
        if (outcome === 'success') write.flush({ ...first, comment: 'A submitted', generation: 3 });
        else write.flush({ message: 'Old task failure' }, { status: 503, statusText: 'Unavailable' });
        expect(component.editingTaskId()).toBe(selected);
        expect(component.editTaskComment()).toBe('Current unsaved');
        expect(component.mutatingTaskId()).toBeNull();
        expect(component.canSaveManualTaskEdit(current)).toBe(true);
        expect(toast.error).not.toHaveBeenCalled(); expect(toast.success).not.toHaveBeenCalled();
        expect(write.request.body.comment).toBe('A submitted');
      });
    }
  }

  for (const outcome of ['success', 'error']) {
    it(`retains current editor ${outcome} behavior and allows a deliberate retry after failure`, () => {
      initial.flush(page(1)); flushBootstrap();
      const task = openTask(701); component.manualTasks.set([task]);
      component.setEditTaskComment('Submitted'); component.saveManualTaskEdit(task);
      const write = http.expectOne('/api/admin/payments/manual-tasks/701');
      if (outcome === 'success') {
        write.flush({ ...task, comment: 'Submitted' });
        expect(component.editingTaskId()).toBeNull();
        expect(component.manualTasks()[0].comment).toBe('Submitted');
        expect(toast.success).toHaveBeenCalledOnce();
      } else {
        write.flush({}, { status: 503, statusText: 'Unavailable' });
        expect(component.editingTaskId()).toBe(701);
        expect(component.editTaskComment()).toBe('Submitted');
        expect(component.canSaveManualTaskEdit(task)).toBe(true);
        expect(toast.error).toHaveBeenCalledOnce();
      }
      expect(component.mutatingTaskId()).toBeNull();
      http.expectNone('/api/admin/payments/manual-tasks/701');
    });
  }

  it('does not cancel an issued task write on destroy or apply its late response to the disposed page', () => {
    initial.flush(page(1)); flushBootstrap();
    const task = openTask(701); component.manualTasks.set([task]);
    component.saveManualTaskEdit(task);
    const write = http.expectOne('/api/admin/payments/manual-tasks/701');
    fixture.destroy(); expect(write.cancelled).toBe(false);
    write.flush({ ...task, comment: 'Late save' });
    expect(component.manualTasks()[0].comment).toBe(task.comment);
    expect(toast.success).not.toHaveBeenCalled();
    expect(component.canSaveManualTaskEdit(task)).toBe(false);
  });
});
