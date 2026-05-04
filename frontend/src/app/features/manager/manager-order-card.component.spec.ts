import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import type { OrderCardItem } from '../../core/manager.api';
import { MANAGER_ORDER_ACTIONS } from './manager-board.config';
import { ManagerOrderCardComponent } from './manager-order-card.component';

function order(overrides: Partial<OrderCardItem> = {}): OrderCardItem {
  return {
    id: 12,
    companyId: 10,
    orderDetailsId: 'uuid-1',
    companyTitle: 'Company',
    filialTitle: 'Filial',
    status: 'На проверке',
    sum: 1000,
    totalSumWithBadReviews: 1300,
    badReviewTasksSum: 300,
    badReviewTasksTotal: 2,
    badReviewTasksDone: 1,
    companyTelephone: '+7999',
    companyComments: 'company note',
    orderComments: 'order note',
    amount: 10,
    counter: 4,
    workerUserFio: 'Worker',
    categoryTitle: 'Category',
    subCategoryTitle: 'Subcategory',
    dayToChangeStatusAgo: 5,
    ...overrides
  };
}

describe('ManagerOrderCardComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ManagerOrderCardComponent],
      providers: [provideRouter([])]
    }).compileComponents();
  });

  it('renders order card data and totals', () => {
    const fixture = TestBed.createComponent(ManagerOrderCardComponent);
    fixture.componentInstance.order = order();
    fixture.componentInstance.actions = MANAGER_ORDER_ACTIONS.slice(0, 1);

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('header a')?.textContent?.trim()).toBe('Company - Filial');
    expect(element.textContent).toMatch(/1[\s,]300 руб\./);
    expect(element.textContent).toContain('Плохие: 1/2');
    expect(element.textContent).toContain('Worker');
  });

  it('emits order card actions', () => {
    const fixture = TestBed.createComponent(ManagerOrderCardComponent);
    const component = fixture.componentInstance;
    let copiedPhone = '';
    let copyKind: 'review' | 'payment' | null = null;
    let status = '';
    let editOpened = false;
    component.order = order();
    component.actions = MANAGER_ORDER_ACTIONS.slice(0, 1);
    component.phoneCopied.subscribe((phone) => {
      copiedPhone = phone ?? '';
    });
    component.copyTextRequested.subscribe((kind) => {
      copyKind = kind;
    });
    component.statusUpdated.subscribe((action) => {
      status = action.status;
    });
    component.editOpened.subscribe(() => {
      editOpened = true;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('.phone-row button')?.click();
    element.querySelector<HTMLButtonElement>('.order-links button')?.click();
    element.querySelector<HTMLButtonElement>('.order-status-actions button')?.click();
    element.querySelector<HTMLAnchorElement>('footer a')?.click();

    expect(copiedPhone).toBe('+7999');
    expect(copyKind).toBe('review');
    expect(status).toBe('На проверке');
    expect(editOpened).toBe(true);
  });
});
