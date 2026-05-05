import { signal } from '@angular/core';
import type { Signal, WritableSignal } from '@angular/core';
import type { OrderCardItem } from '../../core/manager.api';
import type { WorkerApi, WorkerBoard, WorkerPermissions, WorkerReviewItem } from '../../core/worker.api';
import type { ToastService } from '../../shared/toast.service';
import type { ReviewEditableField, SideNoteField } from './worker-board.config';
import {
  workerEditableOrderNoteText,
  workerOrderNoteMutationKey,
  workerPatchOrderNote,
  workerPatchReviewField,
  workerPatchReviewNote,
  workerPatchReviewSideNote,
  workerRemoveRecordKey,
  workerReviewFieldKey,
  workerReviewFieldSourceValue,
  workerReviewNoteMutationKey,
  workerSaveReviewFieldMutationKey,
  workerSideNoteKey,
  workerSideNoteMutationKey,
  workerSideNoteSourceValue
} from './worker-board-note.helpers';

type WorkerBoardNoteApi = Pick<
  WorkerApi,
  | 'updateOrderNote'
  | 'updateOrderCompanyNote'
  | 'updateReviewText'
  | 'updateReviewAnswer'
  | 'updateReviewNote'
>;

type WorkerBoardNoteToast = Pick<ToastService, 'success' | 'error'>;

export type WorkerBoardNoteFacadeDeps = {
  workerApi: WorkerBoardNoteApi;
  toastService: WorkerBoardNoteToast;
  board: Signal<WorkerBoard | null>;
  permissions: Signal<WorkerPermissions>;
  mutationKey: WritableSignal<string | null>;
  setBoardPatch: (board: WorkerBoard | null) => void;
  loadBoard: () => void;
  errorMessage: (err: unknown, fallback: string) => string;
};

export class WorkerBoardNoteFacade {
  readonly editingOrderNoteId = signal<number | null>(null);
  readonly savedOrderNoteId = signal<number | null>(null);
  readonly expandedOrderNoteIds = signal<Record<number, boolean>>({});
  readonly orderNoteDrafts = signal<Record<number, string>>({});
  readonly editingReviewFieldKey = signal<string | null>(null);
  readonly reviewFieldDrafts = signal<Record<string, string>>({});
  readonly savedReviewFieldKey = signal<string | null>(null);
  readonly expandedReviewTextIds = signal<Record<number, boolean>>({});
  readonly editingReviewNoteId = signal<number | null>(null);
  readonly reviewNoteDrafts = signal<Record<number, string>>({});
  readonly savedReviewNoteId = signal<number | null>(null);
  readonly editingSideNoteKey = signal<string | null>(null);
  readonly sideNoteDrafts = signal<Record<string, string>>({});
  readonly savedSideNoteKey = signal<string | null>(null);

  constructor(private readonly deps: WorkerBoardNoteFacadeDeps) {}

  startOrderNoteEdit(order: OrderCardItem): void {
    if (!this.deps.permissions().canEditNotes || this.isMutating(this.orderNoteMutationKey(order))) {
      return;
    }

    this.savedOrderNoteId.set(null);
    this.editingOrderNoteId.set(order.id);
    this.expandedOrderNoteIds.update((expanded) => ({ ...expanded, [order.id]: true }));
    this.orderNoteDrafts.update((drafts) => ({
      ...drafts,
      [order.id]: this.editableOrderNoteText(order)
    }));
  }

  cancelOrderNoteEdit(order: OrderCardItem): void {
    if (this.isMutating(this.orderNoteMutationKey(order))) {
      return;
    }

    this.savedOrderNoteId.set(null);
    this.editingOrderNoteId.set(null);
    this.orderNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, order.id));
  }

  saveOrderNote(order: OrderCardItem): void {
    if (!this.deps.permissions().canEditNotes) {
      return;
    }

    const value = this.orderNoteDraft(order);
    const key = this.orderNoteMutationKey(order);

    this.deps.mutationKey.set(key);
    this.deps.workerApi.updateOrderNote(order.id, value).subscribe({
      next: () => {
        this.deps.setBoardPatch(workerPatchOrderNote(this.deps.board(), order.id, value));
        this.deps.mutationKey.set(null);
        this.editingOrderNoteId.set(null);
        this.savedOrderNoteId.set(order.id);
        this.orderNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, order.id));
        this.deps.toastService.success('Заметка сохранена', order.companyTitle || `Заказ #${order.id}`);
        window.setTimeout(() => {
          if (this.savedOrderNoteId() === order.id) {
            this.savedOrderNoteId.set(null);
          }
        }, 1400);
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Заметка не сохранена', this.deps.errorMessage(err, 'Не удалось сохранить заметку'));
      }
    });
  }

  saveOrderCompanyNote(order: OrderCardItem, value: string): void {
    if (!this.deps.permissions().canEditNotes) {
      return;
    }

    this.deps.workerApi.updateOrderCompanyNote(order.id, value).subscribe({
      next: () => {
        this.deps.toastService.success('Заметка компании сохранена', order.companyTitle || `Заказ #${order.id}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.toastService.error('Заметка не сохранена', this.deps.errorMessage(err, 'Не удалось сохранить заметку компании'));
      }
    });
  }

  startReviewFieldEdit(review: WorkerReviewItem, field: ReviewEditableField): void {
    if (!this.deps.permissions().canWorkReviews || this.isMutating(workerSaveReviewFieldMutationKey(review, field))) {
      return;
    }

    const key = workerReviewFieldKey(review, field);
    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(key);
    this.reviewFieldDrafts.update((drafts) => {
      if (key in drafts) {
        return drafts;
      }

      return {
        ...drafts,
        [key]: workerReviewFieldSourceValue(review, field)
      };
    });
  }

  setReviewFieldDraft(review: WorkerReviewItem, field: ReviewEditableField, value: string): void {
    const key = workerReviewFieldKey(review, field);
    this.reviewFieldDrafts.update((drafts) => ({ ...drafts, [key]: value }));
  }

  cancelReviewFieldEdit(review: WorkerReviewItem, field: ReviewEditableField): void {
    if (this.isMutating(workerSaveReviewFieldMutationKey(review, field))) {
      return;
    }

    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(null);
    this.reviewFieldDrafts.update((drafts) => workerRemoveRecordKey(drafts, workerReviewFieldKey(review, field)));
  }

  saveReviewField(review: WorkerReviewItem, field: ReviewEditableField): void {
    if (!this.deps.permissions().canWorkReviews) {
      return;
    }

    const value = this.reviewFieldValue(review, field);
    if (field === 'text' && !value.trim()) {
      this.deps.toastService.error('Текст не сохранен', 'Поле отзыва не должно быть пустым');
      return;
    }

    const key = workerSaveReviewFieldMutationKey(review, field);
    const fieldKey = workerReviewFieldKey(review, field);
    const request = field === 'text'
      ? this.deps.workerApi.updateReviewText(review.id, review.orderId, value)
      : this.deps.workerApi.updateReviewAnswer(review.id, review.orderId, value);

    this.deps.mutationKey.set(key);
    request.subscribe({
      next: () => {
        this.deps.setBoardPatch(workerPatchReviewField(this.deps.board(), review.id, field, value));
        this.deps.mutationKey.set(null);
        this.savedReviewFieldKey.set(fieldKey);
        this.deps.toastService.success(field === 'text' ? 'Текст сохранен' : 'Замечание сохранено', `Отзыв #${review.id} обновлен`);

        window.setTimeout(() => {
          if (this.savedReviewFieldKey() === fieldKey) {
            this.savedReviewFieldKey.set(null);
          }

          if (this.editingReviewFieldKey() === fieldKey) {
            this.editingReviewFieldKey.set(null);
          }

          this.reviewFieldDrafts.update((drafts) => workerRemoveRecordKey(drafts, fieldKey));
        }, 1000);
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error(
          field === 'text' ? 'Текст не сохранен' : 'Замечание не сохранено',
          this.deps.errorMessage(err, field === 'text' ? 'Не удалось сохранить текст отзыва' : 'Не удалось сохранить замечание')
        );
      }
    });
  }

  reviewFieldValue(review: WorkerReviewItem, field: ReviewEditableField): string {
    const key = workerReviewFieldKey(review, field);
    return this.reviewFieldDrafts()[key] ?? workerReviewFieldSourceValue(review, field);
  }

  toggleReviewText(review: WorkerReviewItem): void {
    this.expandedReviewTextIds.update((items) => ({ ...items, [review.id]: !items[review.id] }));
  }

  startReviewNoteEdit(review: WorkerReviewItem): void {
    if (!this.deps.permissions().canWorkReviews || this.isMutating(workerReviewNoteMutationKey(review))) {
      return;
    }

    this.savedReviewNoteId.set(null);
    this.editingReviewNoteId.set(review.id);
    this.reviewNoteDrafts.update((drafts) => {
      if (review.id in drafts) {
        return drafts;
      }

      return { ...drafts, [review.id]: review.comment ?? '' };
    });
  }

  setReviewNoteDraft(reviewId: number, value: string): void {
    this.reviewNoteDrafts.update((drafts) => ({ ...drafts, [reviewId]: value }));
  }

  cancelReviewNoteEdit(review: WorkerReviewItem): void {
    if (this.isMutating(workerReviewNoteMutationKey(review))) {
      return;
    }

    this.savedReviewNoteId.set(null);
    this.editingReviewNoteId.set(null);
    this.reviewNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, review.id));
  }

  saveReviewNote(review: WorkerReviewItem): void {
    if (!this.deps.permissions().canWorkReviews) {
      return;
    }

    const value = this.reviewNoteValue(review);
    const key = workerReviewNoteMutationKey(review);
    this.deps.mutationKey.set(key);

    this.deps.workerApi.updateReviewNote(review.id, review.orderId, value).subscribe({
      next: () => {
        this.deps.setBoardPatch(workerPatchReviewNote(this.deps.board(), review.id, value));
        this.deps.mutationKey.set(null);
        this.savedReviewNoteId.set(review.id);
        this.deps.toastService.success('Заметка сохранена', `Отзыв #${review.id} обновлен`);

        window.setTimeout(() => {
          if (this.savedReviewNoteId() === review.id) {
            this.savedReviewNoteId.set(null);
          }

          if (this.editingReviewNoteId() === review.id) {
            this.editingReviewNoteId.set(null);
          }

          this.reviewNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, review.id));
        }, 1000);
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Заметка не сохранена', this.deps.errorMessage(err, 'Не удалось сохранить заметку отзыва'));
      }
    });
  }

  reviewNoteValue(review: WorkerReviewItem): string {
    return this.reviewNoteDrafts()[review.id] ?? review.comment ?? '';
  }

  startSideNoteEdit(review: WorkerReviewItem, field: SideNoteField): void {
    if (!this.deps.permissions().canEditNotes || this.isMutating(workerSideNoteMutationKey(review, field))) {
      return;
    }

    const key = workerSideNoteKey(review, field);
    this.savedSideNoteKey.set(null);
    this.editingSideNoteKey.set(key);
    this.sideNoteDrafts.update((drafts) => ({
      ...drafts,
      [key]: drafts[key] ?? workerSideNoteSourceValue(review, field)
    }));
  }

  setSideNoteDraft(review: WorkerReviewItem, field: SideNoteField, value: string): void {
    const key = workerSideNoteKey(review, field);
    this.sideNoteDrafts.update((drafts) => ({ ...drafts, [key]: value }));
  }

  cancelSideNoteEdit(review: WorkerReviewItem, field: SideNoteField): void {
    if (this.isMutating(workerSideNoteMutationKey(review, field))) {
      return;
    }

    this.savedSideNoteKey.set(null);
    this.editingSideNoteKey.set(null);
    this.sideNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, workerSideNoteKey(review, field)));
  }

  saveSideNote(review: WorkerReviewItem, field: SideNoteField): void {
    if (!this.deps.permissions().canEditNotes) {
      return;
    }

    const value = this.sideNoteValue(review, field);
    const key = workerSideNoteMutationKey(review, field);
    const sideKey = workerSideNoteKey(review, field);
    const request = field === 'order'
      ? this.deps.workerApi.updateOrderNote(review.orderId, value)
      : this.deps.workerApi.updateOrderCompanyNote(review.orderId, value);

    this.deps.mutationKey.set(key);
    request.subscribe({
      next: () => {
        this.deps.setBoardPatch(workerPatchReviewSideNote(this.deps.board(), review, field, value));
        this.deps.mutationKey.set(null);
        this.savedSideNoteKey.set(sideKey);
        this.deps.toastService.success(field === 'order' ? 'Заметка заказа сохранена' : 'Заметка компании сохранена');

        window.setTimeout(() => {
          if (this.savedSideNoteKey() === sideKey) {
            this.savedSideNoteKey.set(null);
          }

          if (this.editingSideNoteKey() === sideKey) {
            this.editingSideNoteKey.set(null);
          }

          this.sideNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, sideKey));
        }, 1000);
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Заметка не сохранена', this.deps.errorMessage(err, 'Не удалось сохранить заметку'));
      }
    });
  }

  sideNoteValue(review: WorkerReviewItem, field: SideNoteField): string {
    return this.sideNoteDrafts()[workerSideNoteKey(review, field)] ?? workerSideNoteSourceValue(review, field);
  }

  editableOrderNoteText(order: OrderCardItem): string {
    return workerEditableOrderNoteText(order);
  }

  orderNoteDraft(order: OrderCardItem): string {
    return this.orderNoteDrafts()[order.id] ?? this.editableOrderNoteText(order);
  }

  setOrderNoteDraft(order: OrderCardItem, value: string): void {
    this.orderNoteDrafts.update((drafts) => ({ ...drafts, [order.id]: value }));
  }

  isOrderNoteEditing(order: OrderCardItem): boolean {
    return this.editingOrderNoteId() === order.id;
  }

  isOrderNoteSaved(order: OrderCardItem): boolean {
    return this.savedOrderNoteId() === order.id;
  }

  isOrderNoteChanged(order: OrderCardItem): boolean {
    return this.orderNoteDraft(order) !== this.editableOrderNoteText(order);
  }

  isOrderNoteExpanded(order: OrderCardItem): boolean {
    return Boolean(this.expandedOrderNoteIds()[order.id]) || this.isOrderNoteEditing(order);
  }

  toggleOrderNote(event: Event, order: OrderCardItem): void {
    event.preventDefault();
    event.stopPropagation();
    this.expandedOrderNoteIds.update((expanded) => ({ ...expanded, [order.id]: !expanded[order.id] }));
  }

  orderNoteMutationKey(order: OrderCardItem): string {
    return workerOrderNoteMutationKey(order);
  }

  clearReviewEditDrafts(reviewId: number): void {
    this.reviewFieldDrafts.update((drafts) => workerRemoveRecordKey(drafts, `${reviewId}-text`));
    this.reviewFieldDrafts.update((drafts) => workerRemoveRecordKey(drafts, `${reviewId}-answer`));
    this.reviewNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, reviewId));
  }

  private isMutating(key: string): boolean {
    return this.deps.mutationKey() === key;
  }
}
