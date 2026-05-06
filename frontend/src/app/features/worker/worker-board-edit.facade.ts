import { computed, signal } from '@angular/core';
import type { Signal } from '@angular/core';
import type {
  ManagerApi,
  OrderCardItem,
  OrderDetailsPayload,
  OrderEditPayload,
  OrderUpdateRequest
} from '../../core/manager.api';
import type { WorkerBoard, WorkerReviewItem } from '../../core/worker.api';
import type { ToastService } from '../../shared/toast.service';
import type { ReviewEditDraft, ReviewEditItem } from './worker-board.config';
import type { WorkerOrderEditDraftChange } from './worker-order-edit-modal.component';
import type { WorkerReviewEditDraftChange } from './worker-review-edit-modal.component';

type WorkerBoardEditApi = Pick<
  ManagerApi,
  | 'getOrderEdit'
  | 'updateOrder'
  | 'deleteOrder'
  | 'getOrderDetails'
  | 'updateOrderReview'
  | 'deleteOrderReview'
  | 'uploadOrderReviewPhoto'
>;

type WorkerBoardEditToast = Pick<ToastService, 'success' | 'error'>;

export type WorkerBoardEditFacadeDeps = {
  managerApi: WorkerBoardEditApi;
  toastService: WorkerBoardEditToast;
  loadBoard: () => void;
  board?: Signal<WorkerBoard | null>;
  setBoardPatch?: (board: WorkerBoard | null) => void;
  errorMessage: (err: unknown, fallback: string) => string;
  clearReviewEditDrafts: (reviewId: number) => void;
  canOpenOrderEditModal: () => boolean;
  canOpenReviewEditModal: () => boolean;
  orderEditUrl: (order: OrderCardItem) => string;
  reviewEditUrl: (review: WorkerReviewItem) => string;
};

export class WorkerBoardEditFacade {
  readonly editOrder = signal<OrderEditPayload | null>(null);
  readonly orderDraft = signal<OrderUpdateRequest | null>(null);
  readonly orderLoading = signal(false);
  readonly orderSaving = signal(false);
  readonly orderError = signal<string | null>(null);
  readonly orderDeleting = signal(false);
  readonly editReview = signal<ReviewEditItem | null>(null);
  readonly reviewEditDetails = signal<OrderDetailsPayload | null>(null);
  readonly reviewEditDraft = signal<ReviewEditDraft | null>(null);
  readonly reviewEditLoading = signal(false);
  readonly reviewEditSaving = signal(false);
  readonly reviewEditDeleting = signal(false);
  readonly reviewEditUploading = signal(false);
  readonly reviewEditError = signal<string | null>(null);

  readonly productOptions = computed(() => this.reviewEditDetails()?.products ?? []);
  readonly reviewEditBusy = computed(() => this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading());

  constructor(private readonly deps: WorkerBoardEditFacadeDeps) {}

  openOrderEdit(order: OrderCardItem): void {
    if (!this.deps.canOpenOrderEditModal()) {
      window.location.href = this.deps.orderEditUrl(order);
      return;
    }

    this.orderLoading.set(true);
    this.orderError.set(null);
    this.orderDeleting.set(false);

    this.deps.managerApi.getOrderEdit(order.id).subscribe({
      next: (payload) => {
        this.applyOrderEditPayload(payload);
        this.orderLoading.set(false);
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось открыть редактирование заказа');
        this.orderLoading.set(false);
        this.orderError.set(message);
        this.deps.toastService.error('Заказ не открыт', message);
      }
    });
  }

  closeOrderEdit(): void {
    if (this.orderLoading() || this.orderSaving() || this.orderDeleting()) {
      return;
    }

    this.editOrder.set(null);
    this.orderDraft.set(null);
    this.orderError.set(null);
  }

  handleOrderEditDraftChange(change: WorkerOrderEditDraftChange): void {
    this.orderDraft.update((draft) => draft ? { ...draft, [change.field]: change.value } : draft);
  }

  saveOrderEdit(): void {
    const order = this.editOrder();
    const draft = this.orderDraft();

    if (!order || !draft) {
      return;
    }

    this.orderSaving.set(true);
    this.orderError.set(null);

    this.deps.managerApi.updateOrder(order.id, draft).subscribe({
      next: (payload) => {
        this.patchOrderCard(payload);
        this.orderSaving.set(false);
        this.closeOrderEdit();
        this.deps.toastService.success('Заказ сохранен', `Изменения по заказу #${order.id} применены`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось сохранить заказ');
        this.orderError.set(message);
        this.orderSaving.set(false);
        this.deps.toastService.error('Заказ не сохранен', message);
      }
    });
  }

  deleteOrderEdit(): void {
    const order = this.editOrder();

    if (!order || this.orderDeleting()) {
      return;
    }

    const confirmed = window.confirm(`Удалить заказ #${order.id}?`);
    if (!confirmed) {
      return;
    }

    this.orderDeleting.set(true);
    this.orderError.set(null);

    this.deps.managerApi.deleteOrder(order.id).subscribe({
      next: () => {
        this.removeOrderCard(order.id);
        this.orderDeleting.set(false);
        this.closeOrderEdit();
        this.deps.toastService.success('Заказ удален', `Заказ #${order.id} удален`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось удалить заказ');
        this.orderDeleting.set(false);
        this.orderError.set(message);
        this.deps.toastService.error('Заказ не удален', message);
      }
    });
  }

  openReviewEdit(review: WorkerReviewItem): void {
    if (!this.deps.canOpenReviewEditModal()) {
      window.location.href = this.deps.reviewEditUrl(review);
      return;
    }

    this.reviewEditLoading.set(true);
    this.reviewEditError.set(null);
    this.reviewEditSaving.set(false);
    this.reviewEditDeleting.set(false);
    this.reviewEditUploading.set(false);
    this.editReview.set(null);
    this.reviewEditDraft.set(null);
    this.reviewEditDetails.set(null);

    this.deps.managerApi.getOrderDetails(review.orderId).subscribe({
      next: (details) => {
        const currentReview = details.reviews.find((item) => item.id === review.id) ?? review;
        this.reviewEditDetails.set(details);
        this.editReview.set(currentReview);
        this.reviewEditDraft.set(this.toReviewEditDraft(currentReview));
        this.reviewEditLoading.set(false);
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось открыть редактирование отзыва');
        this.reviewEditLoading.set(false);
        this.reviewEditError.set(message);
        this.deps.toastService.error('Отзыв не открыт', message);
      }
    });
  }

  closeReviewEdit(): void {
    if (this.reviewEditLoading() || !this.editReview() || this.reviewEditBusy()) {
      return;
    }

    this.editReview.set(null);
    this.reviewEditDraft.set(null);
    this.reviewEditDetails.set(null);
    this.reviewEditError.set(null);
  }

  handleReviewEditDraftChange(change: WorkerReviewEditDraftChange): void {
    this.reviewEditDraft.update((draft) => draft ? { ...draft, [change.field]: change.value } : draft);
  }

  saveReviewEdit(): void {
    const review = this.editReview();
    const draft = this.reviewEditDraft();

    if (!review || !draft) {
      return;
    }

    if (!draft.text.trim()) {
      this.reviewEditError.set('Поле отзыва не должно быть пустым');
      return;
    }

    this.reviewEditSaving.set(true);
    this.reviewEditError.set(null);

    this.deps.managerApi.updateOrderReview(review.orderId, review.id, draft).subscribe({
      next: (details) => {
        this.reviewEditDetails.set(details);
        this.patchReviewsFromDetails(details);
        this.deps.clearReviewEditDrafts(review.id);
        this.reviewEditSaving.set(false);
        this.editReview.set(null);
        this.reviewEditDraft.set(null);
        this.deps.toastService.success('Отзыв сохранен', `Изменения по отзыву #${review.id} применены`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось сохранить отзыв');
        this.reviewEditError.set(message);
        this.reviewEditSaving.set(false);
        this.deps.toastService.error('Отзыв не сохранен', message);
      }
    });
  }

  deleteReviewEdit(): void {
    const review = this.editReview();

    if (!review || this.reviewEditDeleting()) {
      return;
    }

    const confirmed = window.confirm(`Удалить отзыв #${review.id}?`);
    if (!confirmed) {
      return;
    }

    this.reviewEditDeleting.set(true);
    this.reviewEditError.set(null);

    this.deps.managerApi.deleteOrderReview(review.orderId, review.id).subscribe({
      next: (details) => {
        this.reviewEditDetails.set(details);
        this.removeReviewCard(review.id);
        this.reviewEditDeleting.set(false);
        this.editReview.set(null);
        this.reviewEditDraft.set(null);
        this.deps.toastService.success('Отзыв удален', `Отзыв #${review.id} удален из заказа`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось удалить отзыв');
        this.reviewEditError.set(message);
        this.reviewEditDeleting.set(false);
        this.deps.toastService.error('Отзыв не удален', message);
      }
    });
  }

  uploadReviewPhoto(file: File): void {
    const review = this.editReview();

    if (!review) {
      return;
    }

    this.reviewEditUploading.set(true);
    this.reviewEditError.set(null);

    this.deps.managerApi.uploadOrderReviewPhoto(review.orderId, review.id, file).subscribe({
      next: (details) => {
        this.reviewEditDetails.set(details);
        this.patchReviewsFromDetails(details);
        this.reviewEditUploading.set(false);

        const updatedReview = details.reviews.find((item) => item.id === review.id);
        if (updatedReview) {
          this.editReview.set(updatedReview);
          this.reviewEditDraft.update((draft) => draft ? {
            ...draft,
            url: updatedReview.url || updatedReview.urlPhoto || ''
          } : this.toReviewEditDraft(updatedReview));
        }

        this.deps.toastService.success('Фото загружено', `Отзыв #${review.id} обновлен`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось загрузить фото');
        this.reviewEditError.set(message);
        this.reviewEditUploading.set(false);
        this.deps.toastService.error('Фото не загружено', message);
      }
    });
  }

  private applyOrderEditPayload(payload: OrderEditPayload): void {
    this.editOrder.set(payload);
    this.orderDraft.set({
      filialId: payload.filial?.id ?? null,
      workerId: payload.worker?.id ?? null,
      managerId: payload.manager?.id ?? null,
      counter: payload.counter ?? 0,
      orderComments: payload.orderComments,
      commentsCompany: payload.commentsCompany,
      complete: payload.complete
    });
  }

  private toReviewEditDraft(review: ReviewEditItem): ReviewEditDraft {
    return {
      text: review.text ?? '',
      answer: review.answer ?? '',
      comment: review.comment ?? '',
      created: review.created || null,
      changed: review.changed || null,
      publishedDate: review.publishedDate || null,
      publish: !!review.publish,
      vigul: !!review.vigul,
      botName: review.botFio ?? '',
      botPassword: review.botPassword ?? '',
      productId: review.productId ?? null,
      url: review.url || review.urlPhoto || ''
    };
  }

  private patchOrderCard(payload: OrderEditPayload): void {
    this.patchBoard((board) => ({
      ...board,
      orders: {
        ...board.orders,
        content: board.orders.content.map((order) => order.id === payload.id
          ? {
              ...order,
              filialTitle: payload.filial?.label ?? order.filialTitle,
              status: payload.status,
              sum: payload.sum,
              amount: payload.amount,
              counter: payload.counter,
              orderComments: payload.orderComments,
              companyComments: payload.commentsCompany,
              workerUserFio: payload.worker?.label ?? order.workerUserFio,
              changed: payload.changed,
              payDay: payload.payDay
            }
          : order
        )
      }
    }));
  }

  private removeOrderCard(orderId: number): void {
    this.patchBoard((board) => ({
      ...board,
      orders: {
        ...board.orders,
        content: board.orders.content.filter((order) => order.id !== orderId)
      }
    }));
  }

  private patchReviewsFromDetails(details: OrderDetailsPayload): void {
    const updatedReviews = new Map(details.reviews.map((review) => [review.id, review]));

    this.patchBoard((board) => ({
      ...board,
      reviews: {
        ...board.reviews,
        content: board.reviews.content.map((review) => {
          const updatedReview = updatedReviews.get(review.id);
          return updatedReview ? { ...review, ...updatedReview } : review;
        })
      }
    }));
  }

  private removeReviewCard(reviewId: number): void {
    this.patchBoard((board) => ({
      ...board,
      reviews: {
        ...board.reviews,
        content: board.reviews.content.filter((review) => review.id !== reviewId)
      }
    }));
  }

  private patchBoard(updater: (board: WorkerBoard) => WorkerBoard): void {
    if (!this.deps.board || !this.deps.setBoardPatch) {
      return;
    }

    const board = this.deps.board();
    this.deps.setBoardPatch(board ? updater(board) : board);
  }
}
