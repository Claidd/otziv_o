import { signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { WorkerAccountActionCooldownService } from '../../core/worker-account-action-cooldown.service';
import {
  CompanyDeepReportState,
  ManagerApi,
  OrderDetailsPayload
} from '../../core/manager.api';
import { PaymentsApi, type ManagerPaymentLinkResponse } from '../../core/payments.api';
import { ReputationDeepReportMonitorService } from '../../core/reputation-deep-report-monitor.service';
import { PersonalRemindersService } from '../../shared/personal-reminders.service';
import { ToastService } from '../../shared/toast.service';
import { OrderDetailsComponent } from './order-details.component';

describe('OrderDetailsComponent route reads', () => {
  let routeParams: Subject<ReturnType<typeof convertToParamMap>>;
  let managerApi: {
    getOrderDetails: ReturnType<typeof vi.fn>;
    getOrderCompanyReport: ReturnType<typeof vi.fn>;
    addOrderReview: ReturnType<typeof vi.fn>;
  };
  let paymentsApi: { getTbankStatus: ReturnType<typeof vi.fn>; createOrderPaymentLink: ReturnType<typeof vi.fn> };
  let authService: {
    authenticated: WritableSignal<boolean>;
    hasRealmRole: ReturnType<typeof vi.fn>;
    hasAnyRealmRole: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    routeParams = new Subject();
    managerApi = {
      getOrderDetails: vi.fn(),
      getOrderCompanyReport: vi.fn(),
      addOrderReview: vi.fn()
    };
    paymentsApi = { getTbankStatus: vi.fn(() => of(null)), createOrderPaymentLink: vi.fn() };
    authService = {
      authenticated: signal(false),
      hasRealmRole: vi.fn(() => false),
      hasAnyRealmRole: vi.fn(() => false)
    };

    await TestBed.configureTestingModule({
      imports: [OrderDetailsComponent],
      providers: [
        { provide: WorkerAccountActionCooldownService, useValue: { locked: signal(false), title: signal('') } },
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: routeParams,
            queryParamMap: of(convertToParamMap({})),
            snapshot: {
              paramMap: convertToParamMap({}),
              queryParamMap: convertToParamMap({})
            }
          }
        },
        { provide: ManagerApi, useValue: managerApi },
        { provide: PaymentsApi, useValue: paymentsApi },
        {
          provide: AuthService,
          useValue: authService
        },
        {
          provide: ReputationDeepReportMonitorService,
          useValue: { currentJob: signal(null) }
        },
        {
          provide: ToastService,
          useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() }
        },
        { provide: PersonalRemindersService, useValue: {} }
      ]
    })
      .overrideComponent(OrderDetailsComponent, {
        set: { template: '', imports: [] }
      })
      .compileComponents();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('loads the ADMIN/OWNER T-Bank status only for an authenticated global administrator', () => {
    const anonymousFixture = TestBed.createComponent(OrderDetailsComponent);
    expect(paymentsApi.getTbankStatus).not.toHaveBeenCalled();
    anonymousFixture.destroy();

    authService.authenticated.set(true);
    let currentRole = 'MANAGER';
    authService.hasRealmRole.mockImplementation((role: string) => role === currentRole);
    authService.hasAnyRealmRole.mockImplementation((roles: readonly string[]) => roles.includes(currentRole));

    const managerFixture = TestBed.createComponent(OrderDetailsComponent);
    expect(paymentsApi.getTbankStatus).not.toHaveBeenCalled();
    managerFixture.destroy();

    currentRole = 'WORKER';
    const workerFixture = TestBed.createComponent(OrderDetailsComponent);
    expect(paymentsApi.getTbankStatus).not.toHaveBeenCalled();
    workerFixture.destroy();

    currentRole = 'OWNER';
    const ownerFixture = TestBed.createComponent(OrderDetailsComponent);
    expect(paymentsApi.getTbankStatus).toHaveBeenCalledTimes(1);
    ownerFixture.destroy();

    currentRole = 'ADMIN';
    const adminFixture = TestBed.createComponent(OrderDetailsComponent);
    expect(paymentsApi.getTbankStatus).toHaveBeenCalledTimes(2);
    adminFixture.destroy();
  });

  it('owns payment state per route and never copies a late link from the previous order', () => {
    authService.authenticated.set(true); authService.hasAnyRealmRole.mockReturnValue(true);
    paymentsApi.getTbankStatus.mockReturnValue(of({ managerUiEnabled: true, paymentLinksEnabled: true, runtimeMode: 'TEST' }));
    managerApi.getOrderDetails.mockImplementation((id: number) => of(orderDetails(id)));
    managerApi.getOrderCompanyReport.mockImplementation((id: number) => of(companyReport(id)));
    const first = new Subject<ManagerPaymentLinkResponse>(), second = new Subject<ManagerPaymentLinkResponse>();
    paymentsApi.createOrderPaymentLink.mockReturnValueOnce(first).mockReturnValueOnce(second);
    const fixture = TestBed.createComponent(OrderDetailsComponent), component = fixture.componentInstance;
    const copy = vi.spyOn(component as unknown as { copyText: (text: string, ...args: string[]) => Promise<boolean> }, 'copyText').mockResolvedValue(true);
    routeParams.next(convertToParamMap({ orderId: '1' })); component.createPaymentLink(); component.createPaymentLink();
    expect(paymentsApi.createOrderPaymentLink).toHaveBeenCalledTimes(1);
    routeParams.next(convertToParamMap({ orderId: '2' })); component.createPaymentLink();
    first.next({ url: 'old', copyText: 'old' } as ManagerPaymentLinkResponse);
    expect(component.paymentLink()).toBeNull(); expect(component.isMutating('payment-link')).toBe(true); expect(copy).not.toHaveBeenCalled();
    second.next({ url: 'current', copyText: 'current', orderId: 2 } as ManagerPaymentLinkResponse);
    expect(component.paymentLink()?.orderId).toBe(2); expect(component.isMutating('payment-link')).toBe(false);
    expect(copy).toHaveBeenCalledTimes(1); component.createPaymentLink();
    expect(copy).toHaveBeenCalledTimes(2); expect(paymentsApi.createOrderPaymentLink).toHaveBeenCalledTimes(2);
    fixture.destroy();
  });

  it('suppresses clipboard feedback when the payment route changes during the asynchronous copy', async () => {
    authService.authenticated.set(true); authService.hasAnyRealmRole.mockReturnValue(true);
    paymentsApi.getTbankStatus.mockReturnValue(of({ managerUiEnabled: true, paymentLinksEnabled: true }));
    managerApi.getOrderDetails.mockImplementation((id: number) => of(orderDetails(id)));
    managerApi.getOrderCompanyReport.mockImplementation((id: number) => of(companyReport(id)));
    paymentsApi.createOrderPaymentLink.mockReturnValue(of({ url: 'old', copyText: 'old' }));
    let resolveCopy!: () => void;
    const oldClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    const oldSecure = Object.getOwnPropertyDescriptor(window, 'isSecureContext');
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => new Promise<void>(resolve => { resolveCopy = resolve; }) } });
    Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
    const fixture = TestBed.createComponent(OrderDetailsComponent), component = fixture.componentInstance;
    try {
      routeParams.next(convertToParamMap({ orderId: '1' })); component.createPaymentLink();
      routeParams.next(convertToParamMap({ orderId: '2' })); resolveCopy();
      await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
      expect(component.copied()).toBeNull(); expect(component.paymentLink()).toBeNull();
      expect(TestBed.inject(ToastService).success).not.toHaveBeenCalled();
      expect(TestBed.inject(ToastService).error).not.toHaveBeenCalled();
    } finally {
      fixture.destroy();
      if (oldClipboard) Object.defineProperty(navigator, 'clipboard', oldClipboard); else Reflect.deleteProperty(navigator, 'clipboard');
      if (oldSecure) Object.defineProperty(window, 'isSecureContext', oldSecure); else Reflect.deleteProperty(window, 'isSecureContext');
    }
  });

  it('cancels stale details and dependent report GETs when the route id changes', () => {
    const firstReport = new Subject<CompanyDeepReportState>();
    const secondReport = new Subject<CompanyDeepReportState>();
    const firstReportTeardown = vi.fn();
    const secondReportTeardown = vi.fn();
    managerApi.getOrderDetails
      .mockReturnValueOnce(of(orderDetails(1)))
      .mockReturnValueOnce(of(orderDetails(2)));
    managerApi.getOrderCompanyReport
      .mockReturnValueOnce(withTeardown(firstReport, firstReportTeardown))
      .mockReturnValueOnce(withTeardown(secondReport, secondReportTeardown));
    const fixture = TestBed.createComponent(OrderDetailsComponent);
    const component = fixture.componentInstance;

    routeParams.next(convertToParamMap({ orderId: '1' }));
    routeParams.next(convertToParamMap({ orderId: '2' }));

    expect(firstReportTeardown).toHaveBeenCalledTimes(1);
    expect(secondReportTeardown).not.toHaveBeenCalled();
    expect(managerApi.getOrderDetails.mock.calls).toEqual([[1], [2]]);
    expect(managerApi.getOrderCompanyReport.mock.calls).toEqual([[1], [2]]);

    firstReport.next(companyReport(1));
    secondReport.next(companyReport(2));

    expect(component.orderId()).toBe(2);
    expect(component.details()?.orderId).toBe(2);
    expect(component.companyReportState()?.companyId).toBe(2);

    routeParams.next(convertToParamMap({ orderId: 'invalid' }));
    expect(secondReportTeardown).toHaveBeenCalledTimes(1);
    expect(component.orderId()).toBeNull();
    expect(component.details()).toBeNull();
    expect(component.companyReportState()).toBeNull();
    expect(component.error()).toBe('Заказ не найден');

    fixture.destroy();
  });

  it('aborts an older pending details GET before loading a reused route with another id', () => {
    const first = new Subject<OrderDetailsPayload>();
    const second = new Subject<OrderDetailsPayload>();
    const firstTeardown = vi.fn();
    const secondTeardown = vi.fn();
    managerApi.getOrderDetails
      .mockReturnValueOnce(withTeardown(first, firstTeardown))
      .mockReturnValueOnce(withTeardown(second, secondTeardown));
    managerApi.getOrderCompanyReport.mockReturnValue(of(companyReport(9)));
    const fixture = TestBed.createComponent(OrderDetailsComponent);
    const component = fixture.componentInstance;

    routeParams.next(convertToParamMap({ orderId: '8' }));
    routeParams.next(convertToParamMap({ orderId: '9' }));

    expect(firstTeardown).toHaveBeenCalledTimes(1);
    expect(secondTeardown).not.toHaveBeenCalled();

    first.next(orderDetails(8));
    second.next(orderDetails(9));

    expect(component.orderId()).toBe(9);
    expect(component.details()?.orderId).toBe(9);
    expect(managerApi.getOrderCompanyReport).toHaveBeenCalledOnce();
    expect(managerApi.getOrderCompanyReport).toHaveBeenCalledWith(9);

    fixture.destroy();
    expect(secondTeardown).toHaveBeenCalledTimes(1);
  });

  it('aborts a pending details GET when the component is destroyed', () => {
    const pending = new Subject<OrderDetailsPayload>();
    const teardown = vi.fn();
    managerApi.getOrderDetails.mockReturnValue(withTeardown(pending, teardown));
    const fixture = TestBed.createComponent(OrderDetailsComponent);

    routeParams.next(convertToParamMap({ orderId: '7' }));
    expect(teardown).not.toHaveBeenCalled();

    fixture.destroy();
    expect(teardown).toHaveBeenCalledTimes(1);
  });

  it('clears the previous order route state before the next details response arrives', () => {
    const nextRoute = new Subject<OrderDetailsPayload>();
    managerApi.getOrderDetails
      .mockReturnValueOnce(of(orderDetails(1)))
      .mockReturnValueOnce(nextRoute);
    managerApi.getOrderCompanyReport
      .mockReturnValueOnce(of(companyReport(1)))
      .mockReturnValueOnce(of(companyReport(2)));
    const fixture = TestBed.createComponent(OrderDetailsComponent);
    const component = fixture.componentInstance;

    routeParams.next(convertToParamMap({ orderId: '1' }));
    component.mutationKey.set('add-review');
    component.editingReviewFieldKey.set('text-17');
    expect(component.details()?.orderId).toBe(1);
    expect(component.companyReportState()?.companyId).toBe(1);

    routeParams.next(convertToParamMap({ orderId: '2' }));

    expect(component.orderId()).toBe(2);
    expect(component.details()).toBeNull();
    expect(component.companyReportState()).toBeNull();
    expect(component.mutationKey()).toBeNull();
    expect(component.editingReviewFieldKey()).toBeNull();
    expect(component.loading()).toBe(true);

    nextRoute.next(orderDetails(2));
    expect(component.details()?.orderId).toBe(2);
    expect(component.companyReportState()?.companyId).toBe(2);
  });

  it('does not cancel a mutation but ignores its late details after the order route changes', () => {
    const mutation = new Subject<OrderDetailsPayload>();
    const mutationTeardown = vi.fn();
    managerApi.getOrderDetails
      .mockReturnValueOnce(of(orderDetails(1)))
      .mockReturnValueOnce(of(orderDetails(2)));
    managerApi.getOrderCompanyReport
      .mockReturnValueOnce(of(companyReport(1)))
      .mockReturnValueOnce(of(companyReport(2)));
    managerApi.addOrderReview.mockReturnValue(withTeardown(mutation, mutationTeardown));
    const fixture = TestBed.createComponent(OrderDetailsComponent);
    const component = fixture.componentInstance;

    routeParams.next(convertToParamMap({ orderId: '1' }));
    component.addReview();
    expect(component.mutationKey()).toBe('add-review');

    routeParams.next(convertToParamMap({ orderId: '2' }));

    expect(mutationTeardown).not.toHaveBeenCalled();
    expect(component.details()?.orderId).toBe(2);
    expect(component.mutationKey()).toBeNull();

    mutation.next(orderDetails(1));

    expect(component.details()?.orderId).toBe(2);
    expect(component.companyReportState()?.companyId).toBe(2);
    expect(component.mutationKey()).toBeNull();

    mutation.complete();
    expect(mutationTeardown).toHaveBeenCalledTimes(1);
  });

  it('ignores an old mutation response after navigating A to B and back to A', () => {
    const abandonedMutation = new Subject<OrderDetailsPayload>();
    managerApi.getOrderDetails
      .mockReturnValueOnce(of(orderDetails(1, 'A, первое посещение')))
      .mockReturnValueOnce(of(orderDetails(2, 'B')))
      .mockReturnValueOnce(of(orderDetails(1, 'A, новое посещение')));
    managerApi.getOrderCompanyReport
      .mockReturnValueOnce(of(companyReport(1)))
      .mockReturnValueOnce(of(companyReport(2)))
      .mockReturnValueOnce(of(companyReport(1)));
    managerApi.addOrderReview.mockReturnValue(abandonedMutation);
    const fixture = TestBed.createComponent(OrderDetailsComponent);
    const component = fixture.componentInstance;

    routeParams.next(convertToParamMap({ orderId: '1' }));
    component.addReview();
    routeParams.next(convertToParamMap({ orderId: '2' }));
    routeParams.next(convertToParamMap({ orderId: '1' }));

    expect(component.details()?.title).toBe('A, новое посещение');
    expect(component.mutationKey()).toBeNull();

    abandonedMutation.next(orderDetails(1, 'A, устаревший ответ мутации'));

    expect(component.details()?.title).toBe('A, новое посещение');
    expect(component.mutationKey()).toBeNull();
  });
});

function orderDetails(orderId: number, title = `Заказ ${orderId}`): OrderDetailsPayload {
  return {
    orderId,
    companyId: orderId,
    title,
    companyTitle: `Компания ${orderId}`,
    productTitle: '',
    status: 'Новый',
    orderComments: '',
    companyComments: '',
    created: '',
    changed: '',
    reviews: [],
    badReviewTasks: [],
    recoveryTasks: [],
    filials: [],
    products: [],
    canEditReviews: false,
    canSendToCheck: false,
    canEditReviewDates: false,
    canEditReviewPublish: false,
    canEditReviewVigul: false,
    canDeleteReviews: false
  };
}

function companyReport(companyId: number): CompanyDeepReportState {
  return {
    companyId,
    companyName: `Компания ${companyId}`,
    latestJob: null,
    activeJob: null,
    canStart: true,
    canRefresh: false,
    unavailableReason: ''
  };
}

function withTeardown<T>(source: Observable<T>, teardown: () => void): Observable<T> {
  return new Observable<T>((subscriber) => {
    const subscription = source.subscribe(subscriber);
    return () => {
      subscription.unsubscribe();
      teardown();
    };
  });
}
