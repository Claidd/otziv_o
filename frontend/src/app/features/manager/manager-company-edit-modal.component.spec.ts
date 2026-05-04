import { TestBed } from '@angular/core/testing';
import type {
  CompanyEditPayload,
  CompanyFilialEditItem,
  CompanyUpdateRequest,
  ManagerOption
} from '../../core/manager.api';
import type { ManagerCompanyEditDraftChange } from './manager-company-edit-modal.component';
import { ManagerCompanyEditModalComponent } from './manager-company-edit-modal.component';

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

function company(overrides: Partial<CompanyEditPayload> = {}): CompanyEditPayload {
  return {
    id: 10,
    title: 'Company',
    urlChat: 'https://chat',
    telephone: '+7999',
    city: 'City',
    email: 'company@example.com',
    commentsCompany: 'note',
    active: true,
    createDate: '2026-05-01',
    updateStatus: '2026-05-02',
    dateNewTry: '2026-05-03',
    status: option(1, 'В работе'),
    category: option(2, 'Category'),
    subCategory: option(3, 'Subcategory'),
    manager: option(4, 'Manager'),
    categories: [option(2, 'Category')],
    subCategories: [option(3, 'Subcategory')],
    statuses: [option(1, 'В работе')],
    managers: [option(4, 'Manager')],
    workers: [option(5, 'Worker 5')],
    currentWorkers: [option(6, 'Worker 6')],
    filials: [filial(7)],
    cities: [option(8, 'City')],
    canChangeManager: true,
    ...overrides
  };
}

function draft(overrides: Partial<CompanyUpdateRequest> = {}): CompanyUpdateRequest {
  return {
    title: 'Company',
    urlChat: 'https://chat',
    telephone: '+7999',
    city: 'City',
    email: 'company@example.com',
    categoryId: 2,
    subCategoryId: 3,
    statusId: 1,
    managerId: 4,
    commentsCompany: 'note',
    active: true,
    newWorkerId: null,
    newFilialCityId: null,
    newFilialTitle: '',
    newFilialUrl: '',
    ...overrides
  };
}

describe('ManagerCompanyEditModalComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ManagerCompanyEditModalComponent]
    }).compileComponents();
  });

  it('renders company edit data', async () => {
    const fixture = TestBed.createComponent(ManagerCompanyEditModalComponent);
    fixture.componentInstance.company = company();
    fixture.componentInstance.draft = draft();

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('#company-edit-title')?.textContent?.trim()).toBe('Редактирование компании');
    expect(element.querySelector<HTMLInputElement>('input[name="title"]')?.value).toBe('Company');
    expect(element.textContent).toContain('Worker 6');
    expect(element.textContent).toContain('City: Filial 7');
  });

  it('emits form and delete actions', async () => {
    const fixture = TestBed.createComponent(ManagerCompanyEditModalComponent);
    const component = fixture.componentInstance;
    let closed = false;
    let submitted = false;
    let categoryId: number | null = null;
    let deletedWorkerLabel = '';
    let deletedFilialTitle = '';
    component.company = company();
    component.draft = draft();
    component.closed.subscribe(() => {
      closed = true;
    });
    component.submitted.subscribe(() => {
      submitted = true;
    });
    component.categoryChanged.subscribe((value) => {
      categoryId = value;
    });
    component.workerDeleted.subscribe((worker) => {
      deletedWorkerLabel = worker.label;
    });
    component.filialDeleted.subscribe((filial) => {
      deletedFilialTitle = filial.title;
    });

    fixture.detectChanges();
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    component.categoryChanged.emit(2);
    element.querySelector<HTMLButtonElement>('.lead-edit-close')?.click();
    element.querySelector<HTMLButtonElement>('.worker-delete')?.click();
    element.querySelector<HTMLButtonElement>('.filial-delete')?.click();
    element.querySelector<HTMLFormElement>('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));

    expect(categoryId).toBe(2);
    expect(closed).toBe(true);
    expect(deletedWorkerLabel).toBe('Worker 6');
    expect(deletedFilialTitle).toBe('Filial 7');
    expect(submitted).toBe(true);
  });

  it('emits typed draft changes', () => {
    const fixture = TestBed.createComponent(ManagerCompanyEditModalComponent);
    const component = fixture.componentInstance;
    let change: ManagerCompanyEditDraftChange | null = null;
    component.draftChange.subscribe((event) => {
      change = event;
    });

    component.setField('title', 'New company');

    expect(change).toEqual({ field: 'title', value: 'New company' });
  });
});
