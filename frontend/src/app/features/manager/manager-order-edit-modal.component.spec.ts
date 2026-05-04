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
    expect(element.textContent).toContain('Удалить');
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
    element.querySelector<HTMLButtonElement>('button.danger')?.click();
    element.querySelector<HTMLFormElement>('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));

    expect(closed).toBe(true);
    expect(deleted).toBe(true);
    expect(submitted).toBe(true);
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
});
