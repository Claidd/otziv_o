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
    botLogin: 'bot-login',
    botPassword: 'bot-password',
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
    botPassword: 'bot-password',
    productId: 9,
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
    expect(element.textContent).toContain('Удалить');
    expect(element.querySelector<HTMLInputElement>('input[type="file"]')).not.toBeNull();
  });

  it('emits form actions', async () => {
    const fixture = TestBed.createComponent(WorkerReviewEditModalComponent);
    const component = fixture.componentInstance;
    let closed = false;
    let submitted = false;
    let deleted = false;
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
});
