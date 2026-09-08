import { ManagerCompanyActionsApi } from '../core/manager-company-actions.api';
import { ManagerOrdersApi } from '../core/manager-orders.api';
import { CommonBillingApi } from '../core/common-billing.api';
import { WorkerApi } from '../core/worker.api';
import { OrderReviewsApi } from '../core/order-reviews.api';
import { ManagerBoardApi } from '../core/manager-board.api';
import { CompaniesApi } from '../core/companies.api';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, Subject } from 'rxjs';
import { ApiService, type CompanyEditPayload, type CompanyItem, type CommonBillingAccountResponse, type CompanyOrderCreatePayload, type ManagerBoard, type OrderEditPayload, type OrderItem } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ManagerOrderEditorApi } from '../core/manager-order-editor.api';
import { ManagerCompanyBillingApi } from '../core/manager-company-billing.api';
import { ManagerCompanyEditorApi } from '../core/manager-company-editor.api';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileManualCardPaymentFlowService } from '../shared/mobile-manual-card-payment-flow.service';
import { MobileCommonManualPaymentFlowService } from '../shared/mobile-common-manual-payment-flow.service';
import { ManagerPage } from './manager.page';

const settle = async () => { for (let i = 0; i < 15; i += 1) { await Promise.resolve(); } };
const order = (id: number): OrderEditPayload => ({ id, companyId: id, companyTitle: `Company ${id}`, status: 'В работе', counter: 1, created: '', changed: '', payDay: '', orderComments: '', commentsCompany: '', complete: false, filials: [], managers: [], workers: [], canComplete: true, canDelete: true });
const company = (id: number): CompanyEditPayload => ({ id, title: `Company ${id}`, urlChat: 'https://example.test/chat', urlSite: '', telephone: '123', city: 'City', email: '', commentsCompany: '', active: true, createDate: '', updateStatus: '', dateNewTry: '', status: { id: 1, label: 'Новая' }, categories: [], subCategories: [], statuses: [], managers: [], workers: [], currentWorkers: [], filials: [], cities: [], canChangeManager: true });
const item = (id: number) => ({ id }) as OrderItem;
const companyItem = (id: number) => ({ id }) as CompanyItem;
const billing = (id: number): CommonBillingAccountResponse => ({ id, name: `Billing ${id}`, enabled: true, autoRepeatOrders: true, companies: [] });

describe('manager editor sessions', () => {
  let page: ManagerPage;
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let confirm: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    api = {};
    for (const name of ['getManagerOrderEdit', 'updateManagerOrder', 'deleteManagerOrder', 'getManagerCompanyEdit', 'getCommonBillingAccountsForCompany', 'getManagerCompanySubcategories', 'getManagerCompanyOrderCreate', 'createManagerCompanyOrder', 'createCommonBillingAccount', 'updateCommonBillingAccount', 'removeCommonBillingCompany', 'updateManagerCompany', 'getCompanyCreatePayload', 'getCompanySubcategories']) {
      api[name] = vi.fn();
    }
    api['getManagerBoard'] = vi.fn().mockReturnValue(of({ metrics: [], companies: { content: [] }, orders: { content: [] } }));
    api['getCommonBillingAccountsForCompany'].mockReturnValue(of([]));
    confirm = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({ providers: [
      ManagerPage,
      { provide: CompaniesApi, useValue: api },
      { provide: ManagerOrderEditorApi, useValue: { getEdit: api['getManagerOrderEdit'], update: api['updateManagerOrder'], delete: api['deleteManagerOrder'] } },
      { provide: ManagerCompanyBillingApi, useValue: { getCommonBillingAccountsForCompany: api['getCommonBillingAccountsForCompany'], createCommonBillingAccount: api['createCommonBillingAccount'], updateCommonBillingAccount: api['updateCommonBillingAccount'], removeCommonBillingCompany: api['removeCommonBillingCompany'] } },
      { provide: ManagerCompanyEditorApi, useValue: { getManagerCompanyEdit: api['getManagerCompanyEdit'], updateManagerCompany: api['updateManagerCompany'], getManagerCompanySubcategories: api['getManagerCompanySubcategories'] } },
      { provide: ApiService, useValue: api },
      { provide: ManagerCompanyActionsApi, useValue: api },
      { provide: ManagerOrdersApi, useValue: api },
      { provide: CommonBillingApi, useValue: api },
      { provide: WorkerApi, useValue: api },
      { provide: OrderReviewsApi, useValue: api },
      { provide: ManagerBoardApi, useValue: api },
      { provide: AuthService, useValue: { hasRealmRole: () => false, hasAnyRealmRole: () => true } },
      { provide: ActivatedRoute, useValue: { snapshot: { data: {}, queryParamMap: convertToParamMap({}) }, queryParamMap: of(convertToParamMap({})) } },
      { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
      { provide: MobileConfirmService, useValue: { confirm } },
      { provide: MobileManualCardPaymentFlowService, useValue: {} },
      { provide: MobileCommonManualPaymentFlowService, useValue: {} }
    ] });
    TestBed.overrideComponent(ManagerPage, { set: { template: '', imports: [] } });
    page = TestBed.createComponent(ManagerPage).componentInstance;
  });

  afterEach(() => { page.ngOnDestroy(); });

  it.each([
    ['Выставлен счет', 'sendCommonInvoice'],
    ['Напоминание', 'remindCommonInvoice']
  ])('keeps the unknown delivery warning after %s refresh, without replay', async (status, method) => {
    const lastError = 'operation_unknown: [operationId=invoice-message:41:7] timeout';
    api[method] = vi.fn().mockReturnValue(of({ summary: { id: 41, status: 'INVOICED', lastError } }));
    await page.updateOrderStatus({ id: -41, commonInvoice: true, commonInvoiceId: 41 } as OrderItem,
      { status } as Parameters<ManagerPage['updateOrderStatus']>[1]);
    expect(api[method]).toHaveBeenCalledExactlyOnceWith(41);
    expect(api['getManagerBoard']).toHaveBeenCalledTimes(1);
    expect(page.error()).toBe(`Отправка не подтверждена. ${lastError}`);
    expect(page.mutationKey()).toBeNull();
  });

  it('clears an old warning when a new invoice delivery is confirmed', async () => {
    api['sendCommonInvoice'] = vi.fn().mockReturnValue(of({ summary: { id: 41, status: 'INVOICED', lastError: null } }));
    page.error.set('old warning');
    await page.updateOrderStatus({ id: -41, commonInvoice: true, commonInvoiceId: 41 } as OrderItem,
      { status: 'Выставлен счет' } as Parameters<ManagerPage['updateOrderStatus']>[1]);
    expect(page.error()).toBeNull();
    expect(api['sendCommonInvoice']).toHaveBeenCalledTimes(1);
  });

  it('cancels A when B opens, keeps B on late delivery, and saves B', async () => {
    const a = new Subject<OrderEditPayload>();
    const b = new Subject<OrderEditPayload>();
    api['getManagerOrderEdit'].mockReturnValueOnce(a).mockReturnValueOnce(b);
    api['updateManagerOrder'].mockReturnValue(of(order(202)));
    page.openOrderEdit(item(101));
    expect(a.observed).toBe(true);
    page.closeOrderEdit();
    expect(a.observed).toBe(false);
    page.openOrderEdit(item(202));
    b.next(order(202)); await settle();
    a.next(order(101)); await settle();
    expect(page.orderEdit()?.id).toBe(202);
    expect(page.orderEditLoading()).toBe(false);
    await page.saveOrderEdit();
    expect(api['updateManagerOrder']).toHaveBeenCalledWith(202, expect.objectContaining({ counter: 1 }));
  });

  it('does not let an old rejected request clear loading or set an error in B', async () => {
    const a = new Subject<OrderEditPayload>(); const b = new Subject<OrderEditPayload>();
    api['getManagerOrderEdit'].mockReturnValueOnce(a).mockReturnValueOnce(b);
    page.openOrderEdit(item(101)); page.closeOrderEdit(); page.openOrderEdit(item(202));
    a.error(new Error('old error')); await settle();
    expect(page.orderEditLoading()).toBe(true);
    expect(page.orderEditError()).toBeNull();
    b.next(order(202)); await settle();
  });

  it('distinguishes A → B → A editor visits', async () => {
    const old = new Subject<OrderEditPayload>(); const middle = new Subject<OrderEditPayload>(); const current = new Subject<OrderEditPayload>();
    api['getManagerOrderEdit'].mockReturnValueOnce(old).mockReturnValueOnce(middle).mockReturnValueOnce(current);
    page.openOrderEdit(item(101)); page.closeOrderEdit(); page.openOrderEdit(item(202)); page.closeOrderEdit(); page.openOrderEdit(item(101));
    old.next({ ...order(101), counter: 99 }); await settle();
    expect(page.orderEdit()).toBeNull();
    expect(page.orderEditLoading()).toBe(true);
    current.next({ ...order(101), counter: 7 }); await settle();
    expect(page.orderEditDraft()?.counter).toBe(7);
  });

  it('rejects a server payload with an unexpected order identity', async () => {
    api['getManagerOrderEdit'].mockReturnValue(of(order(101)));
    page.openOrderEdit(item(202)); await settle();
    expect(page.orderEdit()).toBeNull();
    expect(page.orderEditError()).toContain('другой записи');
    expect(api['updateManagerOrder']).not.toHaveBeenCalled();
  });

  it('invalidates cached Ionic editor sessions on leave and allows a fresh visit', async () => {
    const old = new Subject<OrderEditPayload>();
    api['getManagerOrderEdit'].mockReturnValueOnce(old).mockReturnValueOnce(of(order(202)));
    page.openOrderEdit(item(101)); page.ionViewWillLeave();
    old.next(order(101)); await settle();
    expect(old.observed).toBe(false);
    expect(page.orderEditOpen()).toBe(false);
    page.ionViewWillEnter(); page.openOrderEdit(item(202)); await settle();
    expect(page.orderEdit()?.id).toBe(202);
  });

  it('allows a captured write to finish without overwriting a later editor', async () => {
    const write = new Subject<OrderEditPayload>();
    api['getManagerOrderEdit'].mockReturnValueOnce(of(order(101))).mockReturnValueOnce(of(order(202)));
    api['updateManagerOrder'].mockReturnValue(write);
    page.openOrderEdit(item(101)); await settle();
    const saving = page.saveOrderEdit();
    page.ionViewWillLeave(); page.openOrderEdit(item(202)); await settle();
    expect(write.observed).toBe(true);
    write.next(order(101)); await saving;
    expect(page.orderEdit()?.id).toBe(202);
    expect(page.orderEditOpen()).toBe(true);
    expect(page.orderEditSaving()).toBe(false);
    expect(api['updateManagerOrder']).toHaveBeenCalledTimes(1);
  });

  it('does not dispatch deletion after its confirmation belongs to a closed session', async () => {
    let answer!: (value: boolean) => void;
    confirm.mockReturnValue(new Promise<boolean>(resolve => { answer = resolve; }));
    api['getManagerOrderEdit'].mockReturnValueOnce(of(order(101))).mockReturnValueOnce(of(order(202)));
    page.openOrderEdit(item(101)); await settle();
    const deleting = page.deleteOrderEdit();
    page.closeOrderEdit(); page.openOrderEdit(item(202)); await settle();
    answer(true); await deleting;
    expect(api['deleteManagerOrder']).not.toHaveBeenCalled();
    expect(page.orderEdit()?.id).toBe(202);
  });

  it('keeps billing responses tied to the company editor which requested them', async () => {
    const a = new Subject<CommonBillingAccountResponse[]>(); const b = new Subject<CommonBillingAccountResponse[]>();
    api['getManagerCompanyEdit'].mockReturnValueOnce(of(company(101))).mockReturnValueOnce(of(company(202)));
    api['getCommonBillingAccountsForCompany'].mockReturnValueOnce(a).mockReturnValueOnce(b);
    page.openCompanyEdit(companyItem(101)); await settle();
    page.closeCompanyEdit(); page.openCompanyEdit(companyItem(202)); await settle();
    a.next([billing(101)]); await settle();
    expect(a.observed).toBe(false);
    expect(page.companyBillingLoading()).toBe(true);
    expect(page.companyBillingAccounts()).toEqual([]);
    b.next([billing(202)]); await settle();
    expect(page.companyEdit()?.id).toBe(202);
    expect(page.companyBillingSelectedId()).toBe(202);
  });

  it('cancels outdated subcategory requests including a change to no category', async () => {
    const a = new Subject<unknown[]>(); const b = new Subject<unknown[]>();
    api['getManagerCompanyEdit'].mockReturnValue(of(company(101)));
    api['getManagerCompanySubcategories'].mockReturnValueOnce(a).mockReturnValueOnce(b);
    page.openCompanyEdit(companyItem(101)); await settle();
    page.changeCompanyEditCategory(1); page.changeCompanyEditCategory(2);
    a.next([{ id: 1, label: 'wrong' }]); await settle();
    b.next([{ id: 2, label: 'right' }]); await settle();
    expect(page.companyEdit()?.subCategories).toEqual([{ id: 2, label: 'right' }]);
    const pending = new Subject<unknown[]>(); api['getManagerCompanySubcategories'].mockReturnValueOnce(pending);
    page.changeCompanyEditCategory(3); page.changeCompanyEditCategory(null);
    expect(pending.observed).toBe(false);
    pending.error(new Error('old error')); await settle();
    expect(page.companyEditError()).toBeNull();
    expect(page.companyEdit()?.subCategories).toEqual([]);
  });

  it('does not let a completed billing write mutate a new company session', async () => {
    const write = new Subject<CommonBillingAccountResponse>();
    api['getManagerCompanyEdit'].mockReturnValueOnce(of(company(101))).mockReturnValueOnce(of(company(202)));
    api['createCommonBillingAccount'].mockReturnValue(write);
    page.openCompanyEdit(companyItem(101)); await settle();
    const saving = page.createCompanyBillingAccount();
    page.closeCompanyEdit(); page.openCompanyEdit(companyItem(202)); await settle();
    expect(write.observed).toBe(true);
    write.next(billing(101)); await saving;
    expect(page.companyEdit()?.id).toBe(202);
    expect(page.companyBillingSelectedId()).toBeNull();
    expect(page.companyBillingDraft()?.name).toContain('202');
  });

  it('keeps company order creation tied to the current company', async () => {
    const a = new Subject<CompanyOrderCreatePayload>(); const b = new Subject<CompanyOrderCreatePayload>();
    api['getManagerCompanyOrderCreate'].mockReturnValueOnce(a).mockReturnValueOnce(b);
    page.openCompanyOrderCreate(companyItem(101)); page.closeCompanyOrderCreate(); page.openCompanyOrderCreate(companyItem(202));
    b.next({ companyId: 202, companyTitle: '202', products: [], amounts: [5], workers: [], filials: [] }); await settle();
    a.next({ companyId: 101, companyTitle: '101', products: [], amounts: [5], workers: [], filials: [] }); await settle();
    expect(page.orderCreatePayload()?.companyId).toBe(202);
  });

  it('keeps a submitted company save alive on page leave without closing the new company editor', async () => {
    const write = new Subject<CompanyEditPayload>();
    api['getManagerCompanyEdit'].mockReturnValueOnce(of(company(101))).mockReturnValueOnce(of(company(202)));
    api['updateManagerCompany'].mockReturnValue(write);
    page.openCompanyEdit(companyItem(101)); await settle();
    const saving = page.saveCompanyEdit();
    expect(api['updateManagerCompany']).toHaveBeenCalledWith(101, expect.objectContaining({ title: 'Company 101' }));
    page.ionViewWillLeave();
    page.openCompanyEdit(companyItem(202)); await settle();
    expect(write.observed).toBe(true);
    write.next(company(101)); await saving;
    expect(page.companyEdit()?.id).toBe(202);
    expect(page.companyEditOpen()).toBe(true);
    expect(page.companyEditSaving()).toBe(false);
  });

  it('reserves a billing removal before confirmation and does not send it after dismissal', async () => {
    let finishConfirmation!: (result: boolean) => void;
    confirm.mockReturnValueOnce(new Promise<boolean>(resolve => { finishConfirmation = resolve; }));
    api['getManagerCompanyEdit'].mockReturnValue(of(company(101)));
    api['getCommonBillingAccountsForCompany'].mockReturnValue(of([billing(7)]));
    page.openCompanyEdit(companyItem(101)); await settle();
    const removing = page.removeCompanyFromBillingAccount(101);
    await page.removeCompanyFromBillingAccount(101);
    expect(confirm).toHaveBeenCalledTimes(1);
    page.closeCompanyEdit();
    finishConfirmation(true); await removing;
    expect(api['removeCommonBillingCompany']).not.toHaveBeenCalled();
  });

  it('isolates company and billing drafts between two manager page instances', async () => {
    api['getManagerCompanyEdit'].mockReturnValueOnce(of(company(101))).mockReturnValueOnce(of(company(202)));
    const second = TestBed.createComponent(ManagerPage).componentInstance;
    page.openCompanyEdit(companyItem(101)); second.openCompanyEdit(companyItem(202)); await settle();
    page.setCompanyBillingDraftField('name', 'First screen draft');
    page.closeCompanyEdit();
    expect(second.companyEdit()?.id).toBe(202);
    expect(second.companyBillingDraft()?.name).toContain('202');
    expect(second.companyEditOpen()).toBe(true);
    second.ngOnDestroy();
  });

  it('stops a pending search read when Ionic caches the page on leave', () => {
    vi.useFakeTimers();
    try {
      page.setKeyword('Company');
      page.ionViewWillLeave();
      vi.advanceTimersByTime(1000);
      expect(api['getManagerBoard']).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('provides independent editor facades for separate cached manager pages', async () => {
    api['getManagerOrderEdit'].mockReturnValueOnce(of(order(101))).mockReturnValueOnce(of(order(202)));
    const second = TestBed.createComponent(ManagerPage).componentInstance;
    page.openOrderEdit(item(101)); second.openOrderEdit(item(202)); await settle();
    expect(page.orderEdit()?.id).toBe(101);
    expect(second.orderEdit()?.id).toBe(202);
    page.closeOrderEdit();
    expect(second.orderEditOpen()).toBe(true);
    second.ngOnDestroy();
  });

  it('cancels an already dispatched board GET on leave, clears loading and reads the same cached section on return', async () => {
    const abandoned = new Subject<ManagerBoard>();
    const returned = new Subject<ManagerBoard>();
    api['getManagerBoard'].mockReturnValueOnce(abandoned).mockReturnValueOnce(returned);
    page.ngOnInit();
    expect(abandoned.observed).toBe(true);
    expect(page.loading()).toBe(true);
    page.ionViewWillLeave();
    expect(abandoned.observed).toBe(false);
    expect(page.loading()).toBe(false);
    page.pageNumber.set(3);
    page.ionViewWillEnter();
    expect(page.pageNumber()).toBe(3);
    expect(api['getManagerBoard']).toHaveBeenCalledTimes(2);
    expect(returned.observed).toBe(true);
    abandoned.error(new Error('obsolete request'));
    expect(page.loading()).toBe(true);
    const current = { metrics: [], companies: { content: [] }, orders: { content: [] } } as unknown as ManagerBoard;
    returned.next(current); returned.complete(); await settle();
    expect(page.board()).toBe(current);
    expect(page.loading()).toBe(false);
    expect(page.error()).toBeNull();
  });

  it.each(['success', 'unknown'] as const)('reconciles a %s write that settles after returning, without replay or changing a newer editor', async outcome => {
    page.ngOnInit(); await settle();
    const pending = new Subject<OrderEditPayload>();
    api['getManagerOrderEdit'].mockReturnValueOnce(of(order(101))).mockReturnValueOnce(of(order(202)));
    api['updateManagerOrder'].mockReturnValue(pending);
    page.openOrderEdit(item(101)); await settle();
    const saving = page.saveOrderEdit();
    page.ionViewWillLeave();
    expect(pending.observed).toBe(true);
    page.ionViewWillEnter(); await settle();
    expect(api['getManagerBoard']).toHaveBeenCalledTimes(2);
    page.openOrderEdit(item(202)); await settle();
    page.setOrderEditField('orderComments', 'new editor draft');
    if (outcome === 'success') { pending.next(order(101)); pending.complete(); }
    else pending.error(new Error('response lost after possible commit'));
    await saving; await settle();
    expect(api['updateManagerOrder']).toHaveBeenCalledTimes(1);
    expect(api['getManagerBoard']).toHaveBeenCalledTimes(3);
    expect(page.orderEdit()?.id).toBe(202);
    expect(page.orderEditDraft()?.orderComments).toBe('new editor draft');
    expect(page.orderEditError()).toBeNull();
  });

  it('does not issue hidden reconciliation reads, but reads the server when a completed write is revisited', async () => {
    page.ngOnInit(); await settle();
    const pending = new Subject<OrderEditPayload>();
    api['getManagerOrderEdit'].mockReturnValue(of(order(101)));
    api['updateManagerOrder'].mockReturnValue(pending);
    page.openOrderEdit(item(101)); await settle();
    const saving = page.saveOrderEdit(); page.ionViewWillLeave();
    pending.error(new Error('unknown remote result')); await saving; await settle();
    expect(api['getManagerBoard']).toHaveBeenCalledTimes(1);
    page.ionViewWillEnter(); await settle();
    expect(api['getManagerBoard']).toHaveBeenCalledTimes(2);
    expect(api['updateManagerOrder']).toHaveBeenCalledTimes(1);
  });

  it('cancels superseded board reads and prevents a queued reconciliation from reviving a destroyed page', async () => {
    const initial = new Subject<ManagerBoard>();
    api['getManagerBoard'].mockReturnValueOnce(initial);
    page.ngOnInit();
    await page.refresh({ target: { complete: () => undefined } } as never); await settle();
    expect(initial.observed).toBe(false);
    const pending = new Subject<OrderEditPayload>();
    api['getManagerOrderEdit'].mockReturnValue(of(order(101)));
    api['updateManagerOrder'].mockReturnValue(pending);
    page.openOrderEdit(item(101)); await settle();
    const saving = page.saveOrderEdit(); page.ionViewWillLeave(); page.ionViewWillEnter(); await settle();
    const before = api['getManagerBoard'].mock.calls.length;
    pending.next(order(101)); page.ngOnDestroy(); await saving; await settle();
    expect(api['getManagerBoard']).toHaveBeenCalledTimes(before);
  });
});
