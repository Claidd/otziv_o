import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../core/auth.service';
import type { OrderEditPayload, OrderItem } from '../core/api.service';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileManualCardPaymentFlowService } from '../shared/mobile-manual-card-payment-flow.service';
import { MobileCommonManualPaymentFlowService } from '../shared/mobile-common-manual-payment-flow.service';
import { ManagerPage } from './manager.page';

const settle = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };
const board = { metrics: [], companies: { content: [] }, orders: { content: [] } };
const order = (id: number): OrderEditPayload => ({ id, companyId: id, companyTitle: `Company ${id}`, status: 'В работе', counter: 1, created: '', changed: '', payDay: '', orderComments: '', commentsCompany: '', complete: false, filials: [], managers: [], workers: [], canComplete: true, canDelete: true });

describe('manager page with actual Angular feature HTTP transports', () => {
  let page: ManagerPage;
  let requests: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { hasRealmRole: () => false, hasAnyRealmRole: () => true } },
      { provide: ActivatedRoute, useValue: { snapshot: { data: {}, queryParamMap: convertToParamMap({}) }, queryParamMap: of(convertToParamMap({})) } },
      { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
      { provide: MobileConfirmService, useValue: { confirm: vi.fn().mockResolvedValue(true) } },
      { provide: MobileManualCardPaymentFlowService, useValue: {} },
      { provide: MobileCommonManualPaymentFlowService, useValue: {} }
    ] });
    TestBed.overrideComponent(ManagerPage, { set: { template: '', imports: [] } });
    page = TestBed.createComponent(ManagerPage).componentInstance;
    requests = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { page.ngOnDestroy(); requests.verify(); });

  it('aborts a dispatched GET on cached leave and preserves filter/pagination on its replacement', async () => {
    page.ngOnInit();
    const old = requests.expectOne(req => req.url === '/api/manager/board');
    page.ionViewWillLeave();
    expect(old.cancelled).toBe(true); expect(page.loading()).toBe(false);
    page.pageNumber.set(2); page.ionViewWillEnter();
    const current = requests.expectOne(req => req.url === '/api/manager/board');
    expect(current.request.params.get('pageNumber')).toBe('2');
    current.flush(board); await settle();
    expect(page.loading()).toBe(false);
  });

  it('keeps a captured PUT alive and re-reads after its late settlement without replaying it or replacing a newer draft', async () => {
    page.ngOnInit(); requests.expectOne(req => req.url === '/api/manager/board').flush(board); await settle();
    page.openOrderEdit({ id: 101 } as OrderItem);
    requests.expectOne('/api/manager/orders/101/edit').flush(order(101)); await settle();
    page.setOrderEditField('orderComments', ' captured A ');
    const saving = page.saveOrderEdit();
    const write = requests.expectOne('/api/manager/orders/101');
    expect(write.request.method).toBe('PUT'); expect(write.request.body.orderComments).toBe('captured A');
    page.ionViewWillLeave(); expect(write.cancelled).toBe(false);
    page.ionViewWillEnter(); requests.expectOne(req => req.url === '/api/manager/board').flush(board); await settle();
    page.openOrderEdit({ id: 202 } as OrderItem);
    requests.expectOne('/api/manager/orders/202/edit').flush(order(202)); await settle();
    page.setOrderEditField('orderComments', 'preserve B');
    write.flush(order(101)); await saving; await settle();
    requests.expectOne(req => req.url === '/api/manager/board').flush(board); await settle();
    expect(page.orderEdit()?.id).toBe(202); expect(page.orderEditDraft()?.orderComments).toBe('preserve B');
    requests.expectNone(req => req.method !== 'GET');
  });
});
