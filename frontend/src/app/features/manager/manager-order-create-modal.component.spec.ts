import { TestBed } from '@angular/core/testing';
import type {
  CompanyFilialEditItem,
  CompanyOrderCreatePayload,
  CompanyOrderCreateRequest,
  ManagerOption,
  OrderProductOption
} from '../../core/manager.api';
import type { ManagerCreateOrderDraftChange } from './manager-order-create-modal.component';
import { ManagerOrderCreateModalComponent } from './manager-order-create-modal.component';

function option(id: number, label = `Option ${id}`): ManagerOption {
  return { id, label };
}

function filial(id: number): CompanyFilialEditItem {
  return {
    id,
    title: `Filial ${id}`,
    url: '',
    city: 'City'
  };
}

function product(id: number, label: string, price = 100): OrderProductOption {
  return {
    id,
    label,
    price,
    photo: false
  };
}

function payload(): CompanyOrderCreatePayload {
  return {
    companyId: 10,
    companyTitle: 'Company',
    products: [product(1, 'Base', 50), product(2, 'Plus', 120)],
    amounts: [5, 10],
    workers: [option(11, 'Worker')],
    filials: [filial(21)]
  };
}

function draft(overrides: Partial<CompanyOrderCreateRequest> = {}): CompanyOrderCreateRequest {
  return {
    productId: 2,
    amount: 5,
    workerId: 11,
    filialId: 21,
    ...overrides
  };
}

describe('ManagerOrderCreateModalComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ManagerOrderCreateModalComponent]
    }).compileComponents();
  });

  it('renders order-create payload and emits draft changes', () => {
    const fixture = TestBed.createComponent(ManagerOrderCreateModalComponent);
    const component = fixture.componentInstance;
    let change: ManagerCreateOrderDraftChange | null = null;
    component.payload = payload();
    component.draft = draft();
    component.selectedProduct = component.payload.products[1];
    component.total = 600;
    component.draftChange.subscribe((event) => {
      change = event;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('#order-create-title')?.textContent?.trim()).toBe('Company');
    expect(element.querySelector('.order-create-summary strong')?.textContent?.trim()).toBe('600 руб.');

    element.querySelector<HTMLButtonElement>('.order-create-product')?.click();

    expect(change).toEqual({ field: 'productId', value: 1 });
  });

  it('emits close and submit actions', async () => {
    const fixture = TestBed.createComponent(ManagerOrderCreateModalComponent);
    const component = fixture.componentInstance;
    let closed = false;
    let submitted = false;
    component.payload = payload();
    component.draft = draft();
    component.closed.subscribe(() => {
      closed = true;
    });
    component.submitted.subscribe(() => {
      submitted = true;
    });

    fixture.detectChanges();
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('.lead-edit-close')?.click();
    element.querySelector<HTMLFormElement>('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));

    expect(closed).toBe(true);
    expect(submitted).toBe(true);
  });

  it('disables submit while saving or when required draft fields are missing', () => {
    const fixture = TestBed.createComponent(ManagerOrderCreateModalComponent);
    const component = fixture.componentInstance;
    component.payload = payload();
    component.draft = draft({ workerId: null });

    fixture.detectChanges();

    const submit = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(submit.disabled).toBe(true);

    const savingFixture = TestBed.createComponent(ManagerOrderCreateModalComponent);
    savingFixture.componentInstance.payload = payload();
    savingFixture.componentInstance.draft = draft();
    savingFixture.componentInstance.saving = true;
    savingFixture.detectChanges();

    const savingSubmit = savingFixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(savingSubmit.disabled).toBe(true);
  });
});
