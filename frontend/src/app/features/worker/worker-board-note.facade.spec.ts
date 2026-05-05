import { signal } from '@angular/core';
import { of } from 'rxjs';
import type { OrderCardItem } from '../../core/manager.api';
import type { WorkerBoard, WorkerReviewItem } from '../../core/worker.api';
import { DEFAULT_WORKER_PERMISSIONS } from './worker-board.config';
import { WorkerBoardNoteFacade, type WorkerBoardNoteFacadeDeps } from './worker-board-note.facade';

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

function createFacade(sourceBoard = board()) {
  const boardSignal = signal<WorkerBoard | null>(sourceBoard);
  const permissions = signal({
    ...DEFAULT_WORKER_PERMISSIONS,
    canEditNotes: true,
    canWorkReviews: true
  });
  const mutationKey = signal<string | null>(null);
  const calls: string[] = [];
  const toastMessages: string[] = [];
  const deps: WorkerBoardNoteFacadeDeps = {
    workerApi: {
      updateOrderNote: (id: number, value: string) => {
        calls.push(`order:${id}:${value}`);
        return of(void 0);
      },
      updateOrderCompanyNote: (id: number, value: string) => {
        calls.push(`company:${id}:${value}`);
        return of(void 0);
      },
      updateReviewText: (id: number, orderId: number, value: string) => {
        calls.push(`review-text:${id}:${orderId}:${value}`);
        return of(void 0);
      },
      updateReviewAnswer: (id: number, orderId: number, value: string) => {
        calls.push(`review-answer:${id}:${orderId}:${value}`);
        return of(void 0);
      },
      updateReviewNote: (id: number, orderId: number, value: string) => {
        calls.push(`review-note:${id}:${orderId}:${value}`);
        return of(void 0);
      }
    },
    toastService: {
      success: (title: string) => {
        toastMessages.push(`success:${title}`);
        return toastMessages.length;
      },
      error: (title: string) => {
        toastMessages.push(`error:${title}`);
        return toastMessages.length;
      }
    },
    board: boardSignal,
    permissions,
    mutationKey,
    setBoardPatch: (nextBoard) => {
      if (nextBoard) {
        boardSignal.set(nextBoard);
      }
    },
    loadBoard: () => {
      calls.push('load-board');
    },
    errorMessage: (_err, fallback) => fallback
  };

  return {
    facade: new WorkerBoardNoteFacade(deps),
    boardSignal,
    permissions,
    mutationKey,
    calls,
    toastMessages
  };
}

describe('WorkerBoardNoteFacade', () => {
  it('owns order note edit state', () => {
    const item = order({ id: 30, orderComments: 'old note' });
    const { facade } = createFacade(board([item]));

    facade.startOrderNoteEdit(item);
    facade.setOrderNoteDraft(item, 'new note');

    expect(facade.isOrderNoteEditing(item)).toBe(true);
    expect(facade.isOrderNoteExpanded(item)).toBe(true);
    expect(facade.orderNoteDraft(item)).toBe('new note');
    expect(facade.isOrderNoteChanged(item)).toBe(true);

    facade.cancelOrderNoteEdit(item);

    expect(facade.isOrderNoteEditing(item)).toBe(false);
    expect(facade.orderNoteDraft(item)).toBe('old note');
  });

  it('saves order notes and patches the board', () => {
    const item = order({ id: 30, orderComments: 'old note' });
    const { facade, boardSignal, mutationKey, calls, toastMessages } = createFacade(board([item]));

    facade.startOrderNoteEdit(item);
    facade.setOrderNoteDraft(item, 'new note');
    facade.saveOrderNote(item);

    expect(calls).toContain('order:30:new note');
    expect(boardSignal()?.orders.content[0].orderComments).toBe('new note');
    expect(mutationKey()).toBeNull();
    expect(facade.isOrderNoteSaved(item)).toBe(true);
    expect(toastMessages).toContain('success:Заметка сохранена');
  });

  it('validates review text and saves review fields', () => {
    const item = review({ id: 7, orderId: 20, text: 'old text', answer: 'old answer' });
    const { facade, boardSignal, calls, toastMessages } = createFacade(board([], [item]));

    facade.startReviewFieldEdit(item, 'text');
    facade.setReviewFieldDraft(item, 'text', '   ');
    facade.saveReviewField(item, 'text');

    expect(calls.some((call) => call.startsWith('review-text'))).toBe(false);
    expect(toastMessages).toContain('error:Текст не сохранен');

    facade.startReviewFieldEdit(item, 'answer');
    facade.setReviewFieldDraft(item, 'answer', 'new answer');
    facade.saveReviewField(item, 'answer');

    expect(calls).toContain('review-answer:7:20:new answer');
    expect(boardSignal()?.reviews.content[0].answer).toBe('new answer');
    expect(facade.savedReviewFieldKey()).toBe('7-answer');
  });

  it('saves review notes and shared side notes', () => {
    const first = review({ id: 1, orderId: 20, companyId: 10, comment: 'old own' });
    const sameOrder = review({ id: 2, orderId: 20, companyId: 11, orderComments: 'same order old' });
    const sameCompany = review({ id: 3, orderId: 30, companyId: 10, commentCompany: 'same company old' });
    const { facade, boardSignal, calls } = createFacade(board([], [first, sameOrder, sameCompany]));

    facade.startReviewNoteEdit(first);
    facade.setReviewNoteDraft(first.id, 'new own');
    facade.saveReviewNote(first);

    expect(calls).toContain('review-note:1:20:new own');
    expect(boardSignal()?.reviews.content[0].comment).toBe('new own');

    facade.startSideNoteEdit(first, 'order');
    facade.setSideNoteDraft(first, 'order', 'shared order');
    facade.saveSideNote(first, 'order');

    expect(boardSignal()?.reviews.content.map((item) => item.orderComments)).toEqual([
      'shared order',
      'shared order',
      'заметка заказа'
    ]);

    facade.startSideNoteEdit(first, 'company');
    facade.setSideNoteDraft(first, 'company', 'shared company');
    facade.saveSideNote(first, 'company');

    expect(boardSignal()?.reviews.content.map((item) => item.commentCompany)).toEqual([
      'shared company',
      'заметка компании',
      'shared company'
    ]);
  });

  it('clears inline drafts after full review edit save', () => {
    const { facade } = createFacade();

    facade.reviewFieldDrafts.set({ '5-text': 'text', '5-answer': 'answer', '9-text': 'other' });
    facade.reviewNoteDrafts.set({ 5: 'note', 9: 'other' });

    facade.clearReviewEditDrafts(5);

    expect(facade.reviewFieldDrafts()).toEqual({ '9-text': 'other' });
    expect(facade.reviewNoteDrafts()).toEqual({ 9: 'other' });
  });
});
