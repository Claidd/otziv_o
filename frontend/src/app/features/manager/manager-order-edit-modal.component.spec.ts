import { TestBed } from '@angular/core/testing';
import type { ManagerOption, OrderEditPayload, OrderUpdateRequest } from '../../core/manager.api';
import type { ManagerOrderEditDraftChange } from './manager-order-edit-modal.component';
import { ManagerOrderEditModalComponent } from './manager-order-edit-modal.component';

function option(id: number, label = `Option ${id}`): ManagerOption {
  return { id, label };
}

function order(overrides: Partial<OrderEditPayload> = {}): OrderEditPayload {
  return {
    id: 12,
    companyId: 4,
    companyTitle: 'Company',
    status: 'На проверке',
    sum: 1500,
    amount: 10,
    counter: 3,
    created: '2026-05-01',
    changed: '2026-05-02',
    payDay: '',
    orderComments: 'order note',
    commentsCompany: 'company note',
    complete: false,
    filials: [option(21, 'Filial')],
    managers: [option(31, 'Manager')],
    workers: [option(41, 'Worker')],
    canComplete: true,
    canDelete: true,
    canCancelPayment: false,
    ...overrides
  };
}

function draft(overrides: Partial<OrderUpdateRequest> = {}): OrderUpdateRequest {
  return {
    filialId: 21,
    workerId: 41,
    managerId: 31,
    counter: 3,
    orderComments: 'order note',
    commentsCompany: 'company note',
    complete: false,
    removePreviousWorkerFromCompany: false,
    ...overrides
  };
}

describe('ManagerOrderEditModalComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ManagerOrderEditModalComponent]
    }).compileComponents();
  });

  it('renders order edit data', () => {
    const fixture = TestBed.createComponent(ManagerOrderEditModalComponent);
    fixture.componentInstance.order = order();
    fixture.componentInstance.draft = draft();

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('#order-edit-title')?.textContent?.trim()).toBe('Редактирование заказа');
    expect(element.querySelector<HTMLInputElement>('input[readonly]')?.value).toBe('Company');
    expect(element.querySelector<HTMLButtonElement>('.lead-edit-delete.order-delete-action')?.textContent).toContain('Удалить заказ');
    expect(element.querySelector<HTMLButtonElement>('.lead-edit-close')?.getAttribute('aria-label')).toBe('Закрыть окно без сохранения');
    expect(element.querySelector<HTMLButtonElement>('.lead-edit-close .material-icons-sharp')?.textContent?.trim()).toBe('close');
    expect(element.querySelector<SVGElement>('.lead-edit-delete .order-delete-icon')).not.toBeNull();
  });

  it('emits form actions', async () => {
    const fixture = TestBed.createComponent(ManagerOrderEditModalComponent);
    const component = fixture.componentInstance;
    let closed = false;
    let submitted = false;
    let deleted = false;
    component.order = order();
    component.draft = draft();
    component.closed.subscribe(() => {
      closed = true;
    });
    component.submitted.subscribe(() => {
      submitted = true;
    });
    component.deleted.subscribe(() => {
      deleted = true;
    });

    fixture.detectChanges();
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('.lead-edit-close')?.click();
    element.querySelector<HTMLButtonElement>('.lead-edit-delete.order-delete-action')?.click();
    element.querySelector<HTMLFormElement>('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));

    expect(closed).toBe(true);
    expect(deleted).toBe(true);
    expect(submitted).toBe(true);
  });

  it('emits payment cancel action for paid orders', async () => {
    const fixture = TestBed.createComponent(ManagerOrderEditModalComponent);
    const component = fixture.componentInstance;
    let paymentCanceled = false;
    component.order = order({ status: 'Оплачено', canCancelPayment: true });
    component.draft = draft();
    component.paymentCanceled.subscribe(() => {
      paymentCanceled = true;
    });

    fixture.detectChanges();
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    const cancelButton = Array.from(element.querySelectorAll<HTMLButtonElement>('button.danger'))
      .find((button) => button.textContent?.includes('Отменить оплату'));
    cancelButton?.click();

    expect(paymentCanceled).toBe(true);
  });

  it('offers a safe route replacement only when manager access enables it', () => {
    const fixture = TestBed.createComponent(ManagerOrderEditModalComponent);
    const component = fixture.componentInstance;
    const selectedTargets: string[] = [];
    component.order = order();
    component.draft = draft();
    component.allowPaymentRouteChange = true;
    component.paymentRouteContext = {
      paymentLinkId: 81,
      currentRoute: 'Эквайринг Т-Банк',
      currentRecipient: 'Владелец',
      status: 'CREATED',
      canChange: true,
      blockReason: ''
    };
    component.paymentRouteChanged.subscribe((target) => selectedTargets.push(target));

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const routeSection = element.querySelector('.payment-route-change');
    expect(routeSection?.textContent).toContain('Эквайринг Т-Банк');
    const employeeButton = Array.from(routeSection?.querySelectorAll<HTMLButtonElement>('button') ?? [])
      .find((button) => button.textContent?.includes('Реквизиты сотрудника'));
    employeeButton?.click();
    const bankButton = Array.from(routeSection?.querySelectorAll<HTMLButtonElement>('button') ?? [])
      .find((button) => button.textContent?.includes('Банковская ссылка владельца'));
    bankButton?.click();
    expect(selectedTargets).toEqual(['EMPLOYEE_REQUISITES', 'OWNER_TBANK']);

    component.allowPaymentRouteChange = false;
    fixture.detectChanges();
    expect(element.querySelector('.payment-route-change')).toBeNull();
  });

  it('emits typed draft changes', () => {
    const fixture = TestBed.createComponent(ManagerOrderEditModalComponent);
    const component = fixture.componentInstance;
    let change: ManagerOrderEditDraftChange | null = null;
    component.draftChange.subscribe((event) => {
      change = event;
    });

    component.setField('counter', 7);

    expect(change).toEqual({ field: 'counter', value: 7 });
  });

  it('offers removing the previous company worker after selecting another worker', () => {
    const fixture = TestBed.createComponent(ManagerOrderEditModalComponent);
    const component = fixture.componentInstance;
    component.order = order({
      worker: option(41, 'Previous worker'),
      workers: [option(41, 'Previous worker'), option(42, 'New worker')]
    });
    component.draft = draft({ workerId: 42 });
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const checkbox = element.querySelector<HTMLInputElement>('.worker-transfer-option input');
    expect(checkbox).not.toBeNull();
    expect(element.querySelector('.worker-transfer-option')?.textContent).toContain('Удалить прежнего специалиста');
  });
});
