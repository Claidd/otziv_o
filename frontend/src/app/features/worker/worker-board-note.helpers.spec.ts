import type { OrderCardItem } from '../../core/manager.api';
import type { WorkerBoard, WorkerReviewItem } from '../../core/worker.api';
import { DEFAULT_WORKER_PERMISSIONS } from './worker-board.config';
import {
  workerEditableOrderNoteText,
  workerHasMeaningfulNote,
  workerHasReviewCompanyNote,
  workerHasReviewNote,
  workerHasReviewOrderNote,
  workerHasReviewOwnNote,
  workerOrderNoteMutationKey,
  workerOrderNoteText,
  workerPatchOrderNote,
  workerPatchReviewField,
  workerPatchReviewNote,
  workerPatchReviewSideNote,
  workerRemoveRecordKey,
  workerReviewFieldKey,
  workerReviewFieldSourceValue,
  workerReviewNoteMutationKey,
  workerReviewNoteTitle,
  workerReviewTextNeedsToggle,
  workerSaveReviewFieldMutationKey,
  workerShouldShowExpander,
  workerSideNoteKey,
  workerSideNoteMutationKey,
  workerSideNoteSourceValue
} from './worker-board-note.helpers';

function page<T>(content: T[]) {
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

function order(overrides: Partial<OrderCardItem> = {}): OrderCardItem {
  return {
    id: 20,
    companyId: 10,
    companyTitle: 'Company',
    status: 'Новые',
    orderComments: 'старая заметка',
    ...overrides
  };
}

function review(overrides: Partial<WorkerReviewItem> = {}): WorkerReviewItem {
  return {
    id: 1,
    companyId: 10,
    orderId: 20,
    text: 'текст',
    answer: 'ответ',
    category: '',
    subCategory: '',
    botFio: '',
    botLogin: '',
    botPassword: '',
    botCounter: 0,
    companyTitle: 'Company',
    commentCompany: 'заметка компании',
    orderComments: 'заметка заказа',
    filialCity: '',
    filialTitle: '',
    filialUrl: '',
    productTitle: '',
    productPhoto: false,
    workerFio: '',
    created: '',
    changed: '',
    publishedDate: '',
    publish: false,
    vigul: false,
    comment: 'заметка отзыва',
    url: '',
    urlPhoto: '',
    ...overrides
  };
}

function board(orders: OrderCardItem[] = [], reviews: WorkerReviewItem[] = []): WorkerBoard {
  return {
    section: 'new',
    title: 'Новые',
    orders: page(orders),
    reviews: page(reviews),
    bots: [],
    metrics: [],
    promoTexts: [],
    permissions: DEFAULT_WORKER_PERMISSIONS,
    message: '',
    warning: false
  };
}

describe('worker-board note helpers', () => {
  it('normalizes order note text and keys', () => {
    expect(workerHasMeaningfulNote(null)).toBe(false);
    expect(workerHasMeaningfulNote('  НЕТ ЗАМЕТОК  ')).toBe(false);
    expect(workerHasMeaningfulNote(' важное ')).toBe(true);
    expect(workerOrderNoteText(order({ orderComments: '' }))).toBe('нет заметок');
    expect(workerEditableOrderNoteText(order({ orderComments: 'нет заметок' }))).toBe('');
    expect(workerOrderNoteMutationKey(order({ id: 41 }))).toBe('save-order-note-41');
    expect(workerShouldShowExpander('x'.repeat(88))).toBe(false);
    expect(workerShouldShowExpander('x'.repeat(89))).toBe(true);
  });

  it('builds review field keys and source values', () => {
    const item = review({ id: 7, text: 'source text', answer: 'source answer' });

    expect(workerReviewFieldKey(item, 'text')).toBe('7-text');
    expect(workerSaveReviewFieldMutationKey(item, 'answer')).toBe('save-answer-7');
    expect(workerReviewFieldSourceValue(item, 'text')).toBe('source text');
    expect(workerReviewFieldSourceValue(item, 'answer')).toBe('source answer');
    expect(workerReviewTextNeedsToggle(item)).toBe(false);
    expect(workerReviewTextNeedsToggle(review({ text: 'line\nline\nline\nline\nline\nline' }))).toBe(true);
  });

  it('keeps review note labels and side note keys stable', () => {
    const item = review({ id: 12, comment: 'своя', orderComments: 'нет заметок', commentCompany: 'компания' });

    expect(workerHasReviewNote(item)).toBe(true);
    expect(workerHasReviewOwnNote(item)).toBe(true);
    expect(workerHasReviewOrderNote(item)).toBe(false);
    expect(workerHasReviewCompanyNote(item)).toBe(true);
    expect(workerReviewNoteTitle(item)).toBe('Есть заметка отзыва. Есть заметка компании');
    expect(workerReviewNoteTitle(review({ comment: '', orderComments: '', commentCompany: '' }))).toBe('Заметка отзыва');
    expect(workerReviewNoteMutationKey(item)).toBe('save-note-12');
    expect(workerSideNoteKey(item, 'company')).toBe('company-12');
    expect(workerSideNoteMutationKey(item, 'order')).toBe('save-side-order-12');
    expect(workerSideNoteSourceValue(item, 'company')).toBe('компания');
  });

  it('removes draft keys without mutating the original record', () => {
    const drafts = { a: 'one', b: 'two' };
    const next = workerRemoveRecordKey(drafts, 'a');

    expect(next).toEqual({ b: 'two' });
    expect(drafts).toEqual({ a: 'one', b: 'two' });
  });

  it('patches board notes immutably', () => {
    const source = board([order({ id: 20, orderComments: 'old' }), order({ id: 30, orderComments: 'other' })]);
    const patched = workerPatchOrderNote(source, 20, 'new')!;
    const emptyPatched = workerPatchOrderNote(source, 20, '')!;

    expect(patched).not.toBe(source);
    expect(patched.orders.content[0].orderComments).toBe('new');
    expect(patched.orders.content[1].orderComments).toBe('other');
    expect(emptyPatched.orders.content[0].orderComments).toBe('нет заметок');
    expect(source.orders.content[0].orderComments).toBe('old');
  });

  it('patches review fields and shared side notes immutably', () => {
    const first = review({ id: 1, orderId: 20, companyId: 10, text: 'old text', comment: 'old note' });
    const sameOrder = review({ id: 2, orderId: 20, companyId: 11, orderComments: 'same order old' });
    const sameCompany = review({ id: 3, orderId: 30, companyId: 10, commentCompany: 'same company old' });
    const source = board([], [first, sameOrder, sameCompany]);

    const fieldPatched = workerPatchReviewField(source, 1, 'text', 'new text')!;
    const notePatched = workerPatchReviewNote(source, 1, 'new note')!;
    const orderSidePatched = workerPatchReviewSideNote(source, first, 'order', 'shared order')!;
    const companySidePatched = workerPatchReviewSideNote(source, first, 'company', 'shared company')!;

    expect(fieldPatched.reviews.content[0].text).toBe('new text');
    expect(notePatched.reviews.content[0].comment).toBe('new note');
    expect(orderSidePatched.reviews.content.map((item) => item.orderComments)).toEqual([
      'shared order',
      'shared order',
      'заметка заказа'
    ]);
    expect(companySidePatched.reviews.content.map((item) => item.commentCompany)).toEqual([
      'shared company',
      'заметка компании',
      'shared company'
    ]);
    expect(source.reviews.content[0].text).toBe('old text');
  });
});
