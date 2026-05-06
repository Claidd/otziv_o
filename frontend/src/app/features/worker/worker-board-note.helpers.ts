import type { OrderCardItem } from '../../core/manager.api';
import type { WorkerBoard, WorkerReviewItem } from '../../core/worker.api';
import type { ReviewEditableField, SideNoteField } from './worker-board.config';

export function workerHasMeaningfulNote(value?: string | null): boolean {
  const normalized = (value ?? '').trim().toLowerCase();
  return Boolean(normalized) && normalized !== 'нет заметок';
}

export function workerOrderNoteText(order: OrderCardItem): string {
  const value = order.orderComments;
  return workerHasMeaningfulNote(value) ? value!.trim() : 'нет заметок';
}

export function workerEditableOrderNoteText(order: OrderCardItem): string {
  const text = workerOrderNoteText(order);
  return workerHasMeaningfulNote(text) ? text : '';
}

export function workerOrderNoteMutationKey(order: OrderCardItem): string {
  return `save-order-note-${order.id}`;
}

export function workerShouldShowExpander(text?: string | null): boolean {
  return (text ?? '').trim().length > 88;
}

export function workerReviewFieldKey(review: WorkerReviewItem, field: ReviewEditableField): string {
  return `${review.id}-${field}`;
}

export function workerSaveReviewFieldMutationKey(review: WorkerReviewItem, field: ReviewEditableField): string {
  return `save-${field}-${review.id}`;
}

export function workerReviewFieldSourceValue(review: WorkerReviewItem, field: ReviewEditableField): string {
  return field === 'text' ? review.text ?? '' : review.answer ?? '';
}

export function workerReviewTextNeedsToggle(review: WorkerReviewItem): boolean {
  const value = workerReviewFieldSourceValue(review, 'text');
  return value.length > 190 || value.split(/\r?\n/).length > 5;
}

export function workerHasReviewOwnNote(review: WorkerReviewItem): boolean {
  return workerHasMeaningfulNote(review.comment);
}

export function workerHasReviewOrderNote(review: WorkerReviewItem): boolean {
  return workerHasMeaningfulNote(review.orderComments);
}

export function workerHasReviewCompanyNote(review: WorkerReviewItem): boolean {
  return workerHasMeaningfulNote(review.commentCompany);
}

export function workerHasReviewNote(review: WorkerReviewItem): boolean {
  return workerHasReviewOwnNote(review) || workerHasReviewOrderNote(review) || workerHasReviewCompanyNote(review);
}

export function workerReviewNoteMutationKey(review: WorkerReviewItem): string {
  return `save-note-${review.id}`;
}

export function workerReviewNoteTitle(review: WorkerReviewItem): string {
  const items: string[] = [];

  if (workerHasReviewOwnNote(review)) {
    items.push('Есть заметка отзыва');
  }

  if (workerHasReviewOrderNote(review)) {
    items.push('Есть заметка заказа');
  }

  if (workerHasReviewCompanyNote(review)) {
    items.push('Есть заметка компании');
  }

  return items.join('. ') || 'Заметка отзыва';
}

export function workerSideNoteKey(review: WorkerReviewItem, field: SideNoteField): string {
  return `${field}-${review.id}`;
}

export function workerSideNoteMutationKey(review: WorkerReviewItem, field: SideNoteField): string {
  return `save-side-${field}-${review.id}`;
}

export function workerSideNoteSourceValue(review: WorkerReviewItem, field: SideNoteField): string {
  return field === 'order' ? review.orderComments ?? '' : review.commentCompany ?? '';
}

export function workerRemoveRecordKey<T, K extends string | number>(
  record: Record<K, T>,
  key: K
): Record<K, T> {
  const next = { ...record };
  delete next[key];
  return next;
}

export function workerPatchOrderNote(board: WorkerBoard | null, orderId: number, value: string): WorkerBoard | null {
  if (!board) {
    return board;
  }

  const orders = {
    ...board.orders,
    content: board.orders.content.map((order) => {
      if (order.id !== orderId) {
        return order;
      }

      return { ...order, orderComments: value || 'нет заметок' };
    })
  };

  return { ...board, orders };
}

export function workerPatchOrderCompanyNote(
  board: WorkerBoard | null,
  order: OrderCardItem,
  value: string
): WorkerBoard | null {
  if (!board) {
    return board;
  }

  const orders = {
    ...board.orders,
    content: board.orders.content.map((item) => item.companyId === order.companyId
      ? { ...item, companyComments: value }
      : item
    )
  };

  const reviews = {
    ...board.reviews,
    content: board.reviews.content.map((review) => review.companyId === order.companyId
      ? { ...review, commentCompany: value }
      : review
    )
  };

  return { ...board, orders, reviews };
}

export function workerPatchReviewField(
  board: WorkerBoard | null,
  reviewId: number,
  field: ReviewEditableField,
  value: string
): WorkerBoard | null {
  if (!board) {
    return board;
  }

  const reviews = {
    ...board.reviews,
    content: board.reviews.content.map((review) => review.id === reviewId
      ? { ...review, [field]: value }
      : review
    )
  };

  return { ...board, reviews };
}

export function workerPatchReviewNote(board: WorkerBoard | null, reviewId: number, value: string): WorkerBoard | null {
  if (!board) {
    return board;
  }

  const reviews = {
    ...board.reviews,
    content: board.reviews.content.map((review) => review.id === reviewId
      ? { ...review, comment: value }
      : review
    )
  };

  return { ...board, reviews };
}

export function workerPatchReviewSideNote(
  board: WorkerBoard | null,
  review: WorkerReviewItem,
  field: SideNoteField,
  value: string
): WorkerBoard | null {
  if (!board) {
    return board;
  }

  const reviews = {
    ...board.reviews,
    content: board.reviews.content.map((item) => {
      if (field === 'order' && item.orderId === review.orderId) {
        return { ...item, orderComments: value };
      }

      if (field === 'company' && item.companyId === review.companyId) {
        return { ...item, commentCompany: value };
      }

      return item;
    })
  };

  return { ...board, reviews };
}
