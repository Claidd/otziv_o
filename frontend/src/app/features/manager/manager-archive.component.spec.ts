import { Component, Input } from '@angular/core';
import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import type {
  ArchiveOrderDetailsPayload,
  ArchiveOrderListItem,
  ArchiveRestoreResult,
  ManagerPage
} from '../../core/manager.api';
import { AuthService } from '../../core/auth.service';
import { ManagerApi } from '../../core/manager.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { ToastService } from '../../shared/toast.service';
import { ManagerArchiveComponent } from './manager-archive.component';

@Component({
  selector: 'app-admin-layout',
  standalone: true,
  template: '<ng-content></ng-content><ng-content select="[admin-right-content]"></ng-content>'
})
class AdminLayoutStubComponent {
  @Input() title = '';
  @Input() active = '';
  @Input() rightPanelMode: 'default' | 'custom' = 'default';
}

function archiveOrder(overrides: Partial<ArchiveOrderListItem> = {}): ArchiveOrderListItem {
  return {
    id: 3,
    companyId: null,
    companyTitle: 'Архивная компания',
    companyTelephone: '+79086431055',
    companyCity: 'Иркутск',
    filialTitle: 'Центр',
    filialUrl: 'https://2gis.ru/irkutsk/firm/123',
    status: 'Оплачено',
    sum: 1500,
    amount: 5,
    counter: 5,
    waitingForClient: false,
    managerName: 'Менеджер',
    workerName: 'Специалист',
    created: '2026-01-01T00:00:00',
    changed: '2026-01-10T00:00:00',
    payDay: '2026-01-15T00:00:00',
    archivedAt: '2026-05-10T00:00:00',
    archiveReason: 'manual-live',
    archiveBatchId: 2,
    restoredAt: undefined,
    restoredBy: '',
    restoreBatchId: undefined,
    orderDetailsCount: 1,
    reviewsCount: 5,
    paymentCheckSum: 0,
    zpSum: 300,
    source: 'archive',
    ...overrides
  };
}

function page(content: ArchiveOrderListItem[]): ManagerPage<ArchiveOrderListItem> {
  return {
    content,
    number: 0,
    size: 10,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
    first: true,
    last: true
  };
}

function details(order: ArchiveOrderListItem): ArchiveOrderDetailsPayload {
  return {
    order,
    orderComments: 'вернуть при ошибке',
    details: [
      { id: 'detail-1', productId: 10, productTitle: 'Отзывы', amount: 5, price: 100, comment: '', publishedDate: undefined }
    ],
    reviews: [
      {
        id: 11,
        orderDetailsId: 'detail-1',
        text: 'review',
        answer: '',
        category: 'Категория',
        subCategory: 'Подкатегория',
        botId: null,
        botFio: '',
        botLogin: '',
        productId: 10,
        productTitle: 'Отзывы',
        workerFio: 'Специалист',
        filialTitle: 'Центр',
        publish: true,
        vigul: false,
        price: 100,
        url: ''
      }
    ],
    badReviewTasks: [],
    nextOrderRequests: [],
    zp: [
      { id: 21, fio: 'Специалист', sum: 300, userId: 2, professionId: 1, orderId: order.id, amount: 5, active: true }
    ],
    paymentChecks: []
  };
}

function restoreResult(orderId: number, targetStatus = 'Архив'): ArchiveRestoreResult {
  const counts = {
    orders: 1,
    orderDetails: 1,
    reviews: 1,
    badReviewTasks: 0,
    nextOrderRequests: 0,
    zp: 1,
    paymentCheck: 0
  };

  return {
    batchId: 4,
    orderId,
    restoredAt: '2026-05-10T13:52:31',
    restoredBy: 'admin',
    targetStatus,
    selected: counts,
    restored: counts,
    message: 'restore completed'
  };
}

describe('ManagerArchiveComponent', () => {
  const storedOrder = archiveOrder();
  const liveOrder = archiveOrder({
    id: 4,
    companyId: 7,
    companyTitle: 'Live компания',
    status: 'Архив',
    source: 'live',
    archiveBatchId: undefined,
    archivedAt: undefined
  });

  let managerApi: {
    getArchiveOrders: ReturnType<typeof vi.fn>;
    getArchiveOrder: ReturnType<typeof vi.fn>;
    restoreArchiveOrder: ReturnType<typeof vi.fn>;
    updateOrderStatus: ReturnType<typeof vi.fn>;
  };
  let toastService: {
    success: ReturnType<typeof vi.fn>;
    error: ReturnType<typeof vi.fn>;
  };
  let authService: {
    hasAnyRealmRole: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    managerApi = {
      getArchiveOrders: vi.fn(() => of(page([storedOrder, liveOrder]))),
      getArchiveOrder: vi.fn((orderId: number) => of(details(archiveOrder({ id: orderId })))),
      restoreArchiveOrder: vi.fn((orderId: number, targetStatus: string) => of(restoreResult(orderId, targetStatus))),
      updateOrderStatus: vi.fn(() => of(void 0))
    };
    toastService = {
      success: vi.fn(),
      error: vi.fn()
    };
    authService = {
      hasAnyRealmRole: vi.fn((roles: readonly string[]) => roles.includes('ADMIN'))
    };

    await TestBed.configureTestingModule({
      imports: [ManagerArchiveComponent],
      providers: [
        provideRouter([]),
        { provide: ManagerApi, useValue: managerApi },
        { provide: AuthService, useValue: authService },
        { provide: ToastService, useValue: toastService }
      ]
    })
      .overrideComponent(ManagerArchiveComponent, {
        remove: { imports: [AdminLayoutComponent] },
        add: { imports: [AdminLayoutStubComponent] }
      })
      .compileComponents();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('loads closed orders and only offers restore for archive-source rows', () => {
    const fixture = TestBed.createComponent(ManagerArchiveComponent);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(managerApi.getArchiveOrders).toHaveBeenCalledWith({
      keyword: '',
      mode: 'all',
      pageNumber: 0,
      pageSize: 10,
      sortDirection: 'desc'
    });
    expect(element.textContent).toContain('Архивная компания');
    expect(element.textContent).toContain('Live компания');
    expect(element.querySelector<HTMLAnchorElement>('.archive-card-title')?.href).toBe('https://2gis.ru/irkutsk/firm/123');
    expect(element.querySelectorAll('.restore-button')).toHaveLength(1);
    expect(element.querySelectorAll('.live-status-actions button')).toHaveLength(3);
    expect(fixture.componentInstance.canRestore(storedOrder)).toBe(true);
    expect(fixture.componentInstance.canRestore(liveOrder)).toBe(false);
    expect(fixture.componentInstance.canChangeLiveStatus(liveOrder)).toBe(true);
    expect(fixture.componentInstance.orderDetailsLink(liveOrder)).toEqual(['/orders', 7, 4]);
  });

  it('toggles archive sort from the pager', async () => {
    const fixture = TestBed.createComponent(ManagerArchiveComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const toggle = element.querySelector<HTMLButtonElement>('.archive-pager .sort-toggle');

    expect(component.sortDirection()).toBe('desc');

    toggle?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.sortDirection()).toBe('asc');
    expect(managerApi.getArchiveOrders).toHaveBeenLastCalledWith({
      keyword: '',
      mode: 'all',
      pageNumber: 0,
      pageSize: 10,
      sortDirection: 'asc'
    });
  });

  it('loads archive details and restores an order with the selected status', () => {
    const fixture = TestBed.createComponent(ManagerArchiveComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.openRestore(storedOrder);
    component.restoreTargetStatus.set('Новый');
    component.confirmRestore();

    expect(managerApi.getArchiveOrder).toHaveBeenCalledWith(3);
    expect(managerApi.restoreArchiveOrder).toHaveBeenCalledWith(3, 'Новый');
    expect(component.restoreResult()?.batchId).toBe(4);
    expect(toastService.success).toHaveBeenCalledWith('Заказ восстановлен', '#3 вернулся в live со статусом Новый');
    expect(managerApi.getArchiveOrders).toHaveBeenCalledTimes(2);
  });

  it('hides archive finance blocks from managers', () => {
    authService.hasAnyRealmRole.mockReturnValue(false);
    const fixture = TestBed.createComponent(ManagerArchiveComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.openRestore(storedOrder);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(component.canSeeArchiveFinance()).toBe(false);
    expect(element.querySelector('.restore-finance-grid')).toBeNull();
    expect(element.textContent).not.toContain('ЗП');
    expect(element.textContent).not.toContain('Оплаты');
  });

  it('returns a live archive order to a working status from the card', () => {
    const fixture = TestBed.createComponent(ManagerArchiveComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.changeLiveStatus(liveOrder, { label: 'коррекция', status: 'Коррекция' });

    expect(managerApi.updateOrderStatus).toHaveBeenCalledWith(4, 'Коррекция');
    expect(toastService.success).toHaveBeenCalledWith('Заказ вернулся в работу', '#4: Коррекция');
    expect(component.liveStatusMutationKey()).toBeNull();
    expect(managerApi.getArchiveOrders).toHaveBeenCalledTimes(2);
  });
});
