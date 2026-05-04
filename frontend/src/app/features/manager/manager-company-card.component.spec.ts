import { TestBed } from '@angular/core/testing';
import type { CompanyCardItem } from '../../core/manager.api';
import { MANAGER_COMPANY_ACTIONS } from './manager-board.config';
import { ManagerCompanyCardComponent } from './manager-company-card.component';

function company(overrides: Partial<CompanyCardItem> = {}): CompanyCardItem {
  return {
    id: 10,
    title: 'Company',
    telephone: '+7999',
    urlChat: 'https://chat',
    countFilials: 2,
    status: 'В работе',
    manager: 'Manager',
    commentsCompany: 'note',
    city: 'City',
    ...overrides
  };
}

describe('ManagerCompanyCardComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ManagerCompanyCardComponent]
    }).compileComponents();
  });

  it('renders company card data and mutation state', () => {
    const fixture = TestBed.createComponent(ManagerCompanyCardComponent);
    fixture.componentInstance.company = company();
    fixture.componentInstance.actions = MANAGER_COMPANY_ACTIONS.slice(0, 1);
    fixture.componentInstance.mutationKey = 'company-10-Ожидание';

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('header a')?.textContent?.trim()).toBe('Company');
    expect(element.textContent).toContain('+7999');
    expect(element.textContent).toContain('Филиалов:');
    expect(element.querySelector<HTMLButtonElement>('.card-actions button')?.disabled).toBe(true);
  });

  it('emits card actions', () => {
    const fixture = TestBed.createComponent(ManagerCompanyCardComponent);
    const component = fixture.componentInstance;
    let copiedPhone = '';
    let orderOpened = false;
    let status = '';
    let ordersOpened = false;
    let editOpened = false;
    component.company = company();
    component.actions = MANAGER_COMPANY_ACTIONS.slice(0, 1);
    component.phoneCopied.subscribe((phone) => {
      copiedPhone = phone ?? '';
    });
    component.orderCreateOpened.subscribe(() => {
      orderOpened = true;
    });
    component.statusUpdated.subscribe((action) => {
      status = action.status;
    });
    component.ordersOpened.subscribe(() => {
      ordersOpened = true;
    });
    component.editOpened.subscribe(() => {
      editOpened = true;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('.phone-row button')?.click();
    element.querySelector<HTMLButtonElement>('.order-create-trigger')?.click();
    element.querySelector<HTMLButtonElement>('.card-actions button')?.click();
    element.querySelector<HTMLAnchorElement>('.details-button')?.click();
    element.querySelector<HTMLAnchorElement>('footer a')?.click();

    expect(copiedPhone).toBe('+7999');
    expect(orderOpened).toBe(true);
    expect(status).toBe('Ожидание');
    expect(ordersOpened).toBe(true);
    expect(editOpened).toBe(true);
  });
});
