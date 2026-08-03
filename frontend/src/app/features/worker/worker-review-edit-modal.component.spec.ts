import { TestBed } from '@angular/core/testing';
import type { ProductOption } from '../../core/manager.api';
import type { WorkerReviewItem } from '../../core/worker.api';
import type { ReviewEditDraft } from './worker-board.config';
import type { WorkerReviewEditDraftChange } from './worker-review-edit-modal.component';
import { WorkerReviewEditModalComponent } from './worker-review-edit-modal.component';

function product(id: number, label = `Product ${id}`, photo = false): ProductOption {
  return { id, label, photo };
}

function review(overrides: Partial<WorkerReviewItem> = {}): WorkerReviewItem {
  return {
    id: 17,
    companyId: 3,
    orderId: 5,
    text: 'Review text',
    answer: 'Answer text',
    category: 'Category',
    subCategory: 'Subcategory',
    botId: 11,
    botFio: 'Bot Name',
    botLoginPresent: true,
    botPasswordPresent: true,
    botCounter: 2,
    companyTitle: 'Company',
    commentCompany: 'Company note',
    orderComments: 'Order note',
    filialCity: 'City',
    filialTitle: 'Filial',
    filialUrl: 'https://example.test/filial',
    productId: 9,
    productTitle: 'Product',
    productPhoto: true,
    workerFio: 'Worker',
    created: '2026-05-01',
    changed: '2026-05-02',
    publishedDate: '',
    publish: false,
    vigul: false,
    comment: 'Review note',
    url: 'https://example.test/review',
    urlPhoto: 'https://example.test/photo.jpg',
    ...overrides
  };
}

function draft(overrides: Partial<ReviewEditDraft> = {}): ReviewEditDraft {
  return {
    text: 'Review text',
    answer: 'Answer text',
    comment: 'Review note',
    created: '2026-05-01',
    changed: '2026-05-02',
    publishedDate: null,
    publish: false,
    vigul: false,
    botName: 'Bot Name',
    botPassword: '',
    productId: 9,
    filialId: null,
    url: '',
    ...overrides
  };
}

describe('WorkerReviewEditModalComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WorkerReviewEditModalComponent]
    }).compileComponents();
  });

  it('renders review edit data and permissioned fields', async () => {
    const fixture = TestBed.createComponent(WorkerReviewEditModalComponent);
    fixture.componentInstance.review = review();
    fixture.componentInstance.draft = draft();
    fixture.componentInstance.productOptions = [product(9, 'Product', true)];
    fixture.componentInstance.canEditDates = true;
    fixture.componentInstance.canEditPublish = true;
    fixture.componentInstance.canEditVigul = true;
    fixture.componentInstance.canDelete = true;

    fixture.detectChanges();
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('#review-edit-title')?.textContent?.trim()).toBe('Редактирование отзыва');
    expect(element.querySelector<HTMLTextAreaElement>('textarea[name="reviewText"]')?.value).toBe('Review text');
    const passwordInput = element.querySelector<HTMLInputElement>('input[name="reviewBotPassword"]');
    expect(passwordInput?.value).toBe('');
    expect(passwordInput?.placeholder).toContain('Пароль сохранен');
    const deleteButton = element.querySelector<HTMLButtonElement>('.lead-edit-delete.review-delete-action');
    expect(deleteButton?.textContent?.trim()).toBe('delete');
    expect(deleteButton?.getAttribute('aria-label')).toBe('Удалить отзыв');
    expect(element.querySelector<HTMLButtonElement>('.lead-edit-close')?.textContent?.trim()).toBe('close');
    expect(element.querySelector<HTMLInputElement>('input[type="file"]')).not.toBeNull();
  });

  it('hides bot password when requested for a worker', async () => {
    const fixture = TestBed.createComponent(WorkerReviewEditModalComponent);
    fixture.componentInstance.review = review();
    fixture.componentInstance.draft = draft();
    fixture.componentInstance.hideBotPassword = true;

    fixture.detectChanges();
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector<HTMLInputElement>('input[name="reviewBotPassword"]')).toBeNull();
    expect(element.textContent).not.toContain('Пароль бота');
  });

  it('lets privileged users reassign a task worker inside review editing', async () => {
    const fixture = TestBed.createComponent(WorkerReviewEditModalComponent);
    const component = fixture.componentInstance;
    component.review = review({ recoveryTask: true, recoveryTaskId: 91, taskWorkerId: 101 });
    component.draft = draft();
    component.canReassignTask = true;
    component.taskWorkerId = 101;
    component.workerOptions = [
      { id: 101, label: 'Анна' },
      { id: 202, label: 'Борис' }
    ];
    let selectedWorkerId: number | null = null;
    component.taskWorkerChangeRequested.subscribe((workerId) => {
      selectedWorkerId = workerId;
    });

    fixture.detectChanges();
    await fixture.whenStable();

    const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      'select[name="reviewTaskWorker"]'
    );
    expect(select).not.toBeNull();
    expect(select?.selectedOptions.item(0)?.textContent).toBe('Анна');

    select!.selectedIndex = 1;
    select!.dispatchEvent(new Event('change', { bubbles: true }));

    expect(selectedWorkerId).toBe(202);
  });

  it('emits form actions', async () => {
    const fixture = TestBed.createComponent(WorkerReviewEditModalComponent);
    const component = fixture.componentInstance;
    let closed = false;
    let submitted = false;
    let deleted = false;
    let newAccountRequested = false;
    component.review = review();
    component.draft = draft();
    component.canDelete = true;
    component.closed.subscribe(() => {
      closed = true;
    });
    component.submitted.subscribe(() => {
      submitted = true;
    });
    component.deleted.subscribe(() => {
      deleted = true;
    });
    component.newAccountRequested.subscribe(() => {
      newAccountRequested = true;
    });

    fixture.detectChanges();
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('.lead-edit-close')?.click();
    element.querySelector<HTMLButtonElement>('button.review-new-account')?.click();
    element.querySelector<HTMLButtonElement>('.lead-edit-delete.review-delete-action')?.click();
    element.querySelector<HTMLFormElement>('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));

    expect(closed).toBe(true);
    expect(deleted).toBe(true);
    expect(newAccountRequested).toBe(true);
    expect(submitted).toBe(true);
  });

  it('emits typed draft changes and selected photo', () => {
    const fixture = TestBed.createComponent(WorkerReviewEditModalComponent);
    const component = fixture.componentInstance;
    const file = new File(['data'], 'photo.png', { type: 'image/png' });
    let change: WorkerReviewEditDraftChange | null = null;
    let selectedFile: File | null = null;
    component.draftChange.subscribe((event) => {
      change = event;
    });
    component.photoSelected.subscribe((event) => {
      selectedFile = event;
    });

    component.setField('botName', 'Bot 2');
    component.uploadPhoto({ target: { files: [file] } } as unknown as Event);

    expect(change).toEqual({ field: 'botName', value: 'Bot 2' });
    expect(selectedFile).toBe(file);
  });

  it('lets limited workers only unset vigul', async () => {
    const fixture = TestBed.createComponent(WorkerReviewEditModalComponent);
    const component = fixture.componentInstance;
    let change: WorkerReviewEditDraftChange | null = null;
    component.review = review({ vigul: true });
    component.draft = draft({ vigul: true });
    component.canEditVigul = true;
    component.canOnlyUnsetVigul = true;
    component.draftChange.subscribe((event) => {
      change = event;
    });

    fixture.detectChanges();
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    const input = element.querySelector<HTMLInputElement>('input[name="reviewVigul"]');
    expect(input).not.toBeNull();
    expect(input?.disabled).toBe(false);

    component.setField('vigul', false);
    expect(change).toEqual({ field: 'vigul', value: false });

    change = null;
    component.setField('vigul', true);
    expect(change).toBeNull();
  });
});
