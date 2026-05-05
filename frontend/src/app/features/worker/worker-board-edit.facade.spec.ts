import { of } from 'rxjs';
import type {
  OrderCardItem,
  OrderDetailsPayload,
  OrderEditPayload,
  OrderReviewItem,
  OrderUpdateRequest
} from '../../core/manager.api';
import type { WorkerReviewItem } from '../../core/worker.api';
import type { ReviewEditDraft } from './worker-board.config';
import { WorkerBoardEditFacade, type WorkerBoardEditFacadeDeps } from './worker-board-edit.facade';

function order(overrides: Partial<OrderCardItem> = {}): OrderCardItem {
  return {
    id: 20,
    companyId: 10,
    companyTitle: 'Company',
    status: 'Новые',
    ...overrides
  };
}

function orderEdit(overrides: Partial<OrderEditPayload> = {}): OrderEditPayload {
  return {
    id: 20,
    companyId: 10,
    companyTitle: 'Company',
    status: 'Новые',
    counter: 2,
    created: '2026-01-01',
    changed: '2026-01-02',
    payDay: '2026-01-03',
    orderComments: 'order note',
    commentsCompany: 'company note',
    complete: false,
    filial: { id: 1, label: 'Filial' },
    manager: { id: 2, label: 'Manager' },
    worker: { id: 3, label: 'Worker' },
    filials: [{ id: 1, label: 'Filial' }],
    managers: [{ id: 2, label: 'Manager' }],
    workers: [{ id: 3, label: 'Worker' }],
    canComplete: true,
    canDelete: true,
    ...overrides
  };
}

function workerReview(overrides: Partial<WorkerReviewItem> = {}): WorkerReviewItem {
  return {
    id: 7,
    companyId: 10,
    orderId: 20,
    text: 'worker text',
    answer: 'worker answer',
    category: '',
    subCategory: '',
    botFio: 'Bot',
    botLogin: 'login',
    botPassword: 'pass',
    botCounter: 0,
    companyTitle: 'Company',
    commentCompany: 'company note',
    orderComments: 'order note',
    filialCity: '',
    filialTitle: '',
    filialUrl: '',
    productId: 5,
    productTitle: 'Product',
    productPhoto: true,
    workerFio: 'Worker',
    created: '2026-01-01',
    changed: '2026-01-02',
    publishedDate: '',
    publish: false,
    vigul: false,
    comment: 'review note',
    url: '',
    urlPhoto: 'old-photo.jpg',
    ...overrides
  };
}

function orderReview(overrides: Partial<OrderReviewItem> = {}): OrderReviewItem {
  return {
    id: 7,
    companyId: 10,
    orderId: 20,
    text: 'details text',
    answer: 'details answer',
    category: '',
    subCategory: '',
    botFio: 'Bot',
    botLogin: 'login',
    botPassword: 'pass',
    botCounter: 0,
    companyTitle: 'Company',
    commentCompany: 'company note',
    orderComments: 'order note',
    filialCity: '',
    filialTitle: '',
    filialUrl: '',
    productId: 5,
    productTitle: 'Product',
    productPhoto: true,
    workerFio: 'Worker',
    created: '2026-01-01',
    changed: '2026-01-02',
    publishedDate: '',
    publish: false,
    vigul: false,
    comment: 'details note',
    url: '',
    urlPhoto: 'details-photo.jpg',
    ...overrides
  };
}

function orderDetails(overrides: Partial<OrderDetailsPayload> = {}): OrderDetailsPayload {
  return {
    orderId: 20,
    companyId: 10,
    title: 'Order #20',
    companyTitle: 'Company',
    productTitle: 'Product',
    status: 'Новые',
    orderComments: 'order note',
    companyComments: 'company note',
    created: '2026-01-01',
    changed: '2026-01-02',
    reviews: [orderReview()],
    badReviewTasks: [],
    products: [{ id: 5, label: 'Product', photo: true }],
    canEditReviews: true,
    canSendToCheck: true,
    canEditReviewDates: true,
    canEditReviewPublish: true,
    canEditReviewVigul: true,
    canDeleteReviews: true,
    ...overrides
  };
}

function createFacade(config: {
  orderPayload?: OrderEditPayload;
  details?: OrderDetailsPayload;
  uploadDetails?: OrderDetailsPayload;
  updateReviewDetails?: OrderDetailsPayload;
} = {}) {
  const calls: string[] = [];
  const toastMessages: string[] = [];
  const clearedDrafts: number[] = [];
  let lastOrderRequest: OrderUpdateRequest | null = null;
  let lastReviewRequest: ReviewEditDraft | null = null;
  const orderPayload = config.orderPayload ?? orderEdit();
  const details = config.details ?? orderDetails();
  const deps: WorkerBoardEditFacadeDeps = {
    managerApi: {
      getOrderEdit: (orderId: number) => {
        calls.push(`get-order:${orderId}`);
        return of(orderPayload);
      },
      updateOrder: (orderId: number, request: OrderUpdateRequest) => {
        calls.push(`update-order:${orderId}`);
        lastOrderRequest = request;
        return of(orderPayload);
      },
      deleteOrder: (orderId: number) => {
        calls.push(`delete-order:${orderId}`);
        return of(void 0);
      },
      getOrderDetails: (orderId: number) => {
        calls.push(`get-details:${orderId}`);
        return of(details);
      },
      updateOrderReview: (orderId: number, reviewId: number, request: ReviewEditDraft) => {
        calls.push(`update-review:${orderId}:${reviewId}`);
        lastReviewRequest = request;
        return of(config.updateReviewDetails ?? details);
      },
      deleteOrderReview: (orderId: number, reviewId: number) => {
        calls.push(`delete-review:${orderId}:${reviewId}`);
        return of(details);
      },
      uploadOrderReviewPhoto: (orderId: number, reviewId: number, file: File) => {
        calls.push(`upload-review:${orderId}:${reviewId}:${file.name}`);
        return of(config.uploadDetails ?? details);
      }
    },
    toastService: {
      success: (title: string, message?: string) => {
        toastMessages.push(`success:${title}:${message ?? ''}`);
        return toastMessages.length;
      },
      error: (title: string, message?: string) => {
        toastMessages.push(`error:${title}:${message ?? ''}`);
        return toastMessages.length;
      }
    },
    loadBoard: () => {
      calls.push('load-board');
    },
    errorMessage: (_err, fallback) => fallback,
    clearReviewEditDrafts: (reviewId) => {
      clearedDrafts.push(reviewId);
    },
    canOpenOrderEditModal: () => true,
    canOpenReviewEditModal: () => true,
    orderEditUrl: (item) => `/orders/${item.id}`,
    reviewEditUrl: (item) => `/reviews/${item.id}`
  };

  return {
    facade: new WorkerBoardEditFacade(deps),
    calls,
    toastMessages,
    clearedDrafts,
    get lastOrderRequest() {
      return lastOrderRequest;
    },
    get lastReviewRequest() {
      return lastReviewRequest;
    }
  };
}

describe('WorkerBoardEditFacade', () => {
  it('opens order edit and owns order draft state', () => {
    const payload = orderEdit({
      id: 30,
      counter: 4,
      filial: null,
      worker: { id: 8, label: 'Worker' }
    });
    const { facade, calls } = createFacade({ orderPayload: payload });

    facade.openOrderEdit(order({ id: 30 }));

    expect(calls).toContain('get-order:30');
    expect(facade.editOrder()?.id).toBe(30);
    expect(facade.orderDraft()).toEqual({
      filialId: null,
      workerId: 8,
      managerId: 2,
      counter: 4,
      orderComments: 'order note',
      commentsCompany: 'company note',
      complete: false
    });

    facade.closeOrderEdit();

    expect(facade.editOrder()).toBeNull();
    expect(facade.orderDraft()).toBeNull();
  });

  it('saves changed order draft and reloads the board', () => {
    const state = createFacade();

    state.facade.openOrderEdit(order());
    state.facade.handleOrderEditDraftChange({ field: 'counter', value: 9 });
    state.facade.saveOrderEdit();

    expect(state.calls).toContain('update-order:20');
    expect(state.lastOrderRequest?.counter).toBe(9);
    expect(state.facade.editOrder()).toBeNull();
    expect(state.calls).toContain('load-board');
    expect(state.toastMessages.some((message) => message.startsWith('success:Заказ сохранен'))).toBe(true);
  });

  it('opens review edit from order details and builds draft values', () => {
    const detailsReview = orderReview({
      id: 7,
      text: 'fresh details text',
      publish: true,
      url: '',
      urlPhoto: 'details-photo.jpg'
    });
    const { facade, calls } = createFacade({
      details: orderDetails({ reviews: [detailsReview] })
    });

    facade.openReviewEdit(workerReview({ id: 7, text: 'stale board text' }));

    expect(calls).toContain('get-details:20');
    expect(facade.editReview()?.text).toBe('fresh details text');
    expect(facade.reviewEditDraft()?.text).toBe('fresh details text');
    expect(facade.reviewEditDraft()?.publish).toBe(true);
    expect(facade.reviewEditDraft()?.url).toBe('details-photo.jpg');
    expect(facade.productOptions()).toEqual([{ id: 5, label: 'Product', photo: true }]);
  });

  it('validates and saves full review edit', () => {
    const state = createFacade();

    state.facade.openReviewEdit(workerReview());
    state.facade.handleReviewEditDraftChange({ field: 'text', value: '   ' });
    state.facade.saveReviewEdit();

    expect(state.lastReviewRequest).toBeNull();
    expect(state.facade.reviewEditError()).toBe('Поле отзыва не должно быть пустым');

    state.facade.handleReviewEditDraftChange({ field: 'text', value: 'new text' });
    state.facade.saveReviewEdit();

    expect(state.calls).toContain('update-review:20:7');
    expect(state.lastReviewRequest?.text).toBe('new text');
    expect(state.clearedDrafts).toEqual([7]);
    expect(state.facade.editReview()).toBeNull();
    expect(state.calls).toContain('load-board');
  });

  it('uploads review photo and refreshes draft URL from details', () => {
    const uploadedReview = orderReview({ id: 7, url: 'new-photo.jpg', urlPhoto: 'ignored-old-photo.jpg' });
    const state = createFacade({
      uploadDetails: orderDetails({ reviews: [uploadedReview] })
    });

    state.facade.openReviewEdit(workerReview());
    state.facade.uploadReviewPhoto(new File(['photo'], 'photo.jpg', { type: 'image/jpeg' }));

    expect(state.calls).toContain('upload-review:20:7:photo.jpg');
    expect(state.facade.editReview()?.url).toBe('new-photo.jpg');
    expect(state.facade.reviewEditDraft()?.url).toBe('new-photo.jpg');
    expect(state.calls).toContain('load-board');
  });
});
