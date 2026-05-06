import type { Signal, WritableSignal } from '@angular/core';
import type { OrderCardItem } from '../../core/manager.api';
import type {
  WorkerApi,
  WorkerBotItem,
  WorkerBoard,
  WorkerReviewItem,
  WorkerSection
} from '../../core/worker.api';
import type { ToastService } from '../../shared/toast.service';
import type { StatusAction } from './worker-board.config';
import { workerBotChangeMessage } from './worker-board.config';

type WorkerBoardActionApi = Pick<
  WorkerApi,
  | 'updateOrderStatus'
  | 'changeReviewBot'
  | 'deactivateReviewBot'
  | 'publishReview'
  | 'completeBadReviewTask'
  | 'changeBadReviewTaskBot'
  | 'deactivateBadReviewTaskBot'
  | 'nagulReview'
  | 'deleteBot'
>;

type WorkerBoardActionToast = Pick<ToastService, 'success' | 'error'>;

export type WorkerBoardActionFacadeDeps = {
  workerApi: WorkerBoardActionApi;
  toastService: WorkerBoardActionToast;
  activeSection: Signal<WorkerSection>;
  mutationKey: WritableSignal<string | null>;
  loadBoard: () => void;
  patchBoard?: (updater: (board: WorkerBoard) => WorkerBoard) => void;
  errorMessage: (err: unknown, fallback: string) => string;
};

export class WorkerBoardActionFacade {
  constructor(private readonly deps: WorkerBoardActionFacadeDeps) {}

  updateOrderStatus(order: OrderCardItem, action: StatusAction): void {
    const key = `order-${order.id}-${action.status}`;
    this.deps.mutationKey.set(key);

    this.deps.workerApi.updateOrderStatus(order.id, action.status).subscribe({
      next: () => {
        this.patchOrder(order.id, { status: action.status });
        this.deps.mutationKey.set(null);
        this.deps.toastService.success('Статус изменен', `${order.companyTitle}: ${action.status}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Статус не изменен', this.deps.errorMessage(err, 'Не удалось изменить статус заказа'));
      }
    });
  }

  changeReviewBot(review: WorkerReviewItem): void {
    const key = `review-${review.id}-change-bot`;
    const oldBotId = review.botId ?? null;
    this.deps.mutationKey.set(key);

    const request = this.isBadTask(review) && review.badTaskId
      ? this.deps.workerApi.changeBadReviewTaskBot(review.badTaskId)
      : this.deps.workerApi.changeReviewBot(review.id);

    request.subscribe({
      next: (response) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.success(
          'Аккаунт изменен',
          workerBotChangeMessage(oldBotId, response?.newBotId ?? null)
        );
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Бот не заменен', this.deps.errorMessage(err, 'Не удалось заменить бота'));
      }
    });
  }

  deactivateReviewBot(review: WorkerReviewItem): void {
    if (!review.botId) {
      return;
    }

    const confirmed = window.confirm(`Заблокировать бота "${review.botFio || review.botId}" и заменить в отзыве?`);
    if (!confirmed) {
      return;
    }

    const key = `review-${review.id}-block-bot`;
    this.deps.mutationKey.set(key);

    const request = this.isBadTask(review) && review.badTaskId
      ? this.deps.workerApi.deactivateBadReviewTaskBot(review.badTaskId, review.botId)
      : this.deps.workerApi.deactivateReviewBot(review.id, review.botId);

    request.subscribe({
      next: () => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.success('Бот заблокирован', this.reviewActionTitle(review));
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Бот не заблокирован', this.deps.errorMessage(err, 'Не удалось заблокировать бота'));
      }
    });
  }

  markReviewDone(review: WorkerReviewItem): void {
    if (this.isBadTask(review)) {
      this.markBadReviewTaskDone(review);
      return;
    }

    if (this.deps.activeSection() === 'nagul') {
      this.markReviewNagul(review);
      return;
    }

    this.markReviewPublished(review);
  }

  deleteBot(bot: WorkerBotItem): void {
    const confirmed = window.confirm(`Удалить аккаунт "${bot.fio || bot.login}"?`);
    if (!confirmed) {
      return;
    }

    const key = `bot-${bot.id}-delete`;
    this.deps.mutationKey.set(key);

    this.deps.workerApi.deleteBot(bot.id).subscribe({
      next: () => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.success('Аккаунт удален', bot.fio || `#${bot.id}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Аккаунт не удален', this.deps.errorMessage(err, 'Не удалось удалить аккаунт'));
      }
    });
  }

  isBadTask(review: WorkerReviewItem): boolean {
    return !!review.badTask;
  }

  reviewActionTitle(review: WorkerReviewItem): string {
    return this.isBadTask(review) && review.badTaskId
      ? `Плохая задача #${review.badTaskId}`
      : `Отзыв #${review.id}`;
  }

  private markReviewPublished(review: WorkerReviewItem): void {
    const key = `review-${review.id}-publish`;
    this.deps.mutationKey.set(key);

    this.deps.workerApi.publishReview(review.id).subscribe({
      next: () => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.success('Отзыв опубликован', `Отзыв #${review.id}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Отзыв не опубликован', this.deps.errorMessage(err, 'Не удалось отметить отзыв опубликованным'));
      }
    });
  }

  private markBadReviewTaskDone(review: WorkerReviewItem): void {
    if (!review.badTaskId) {
      return;
    }

    const key = `review-${review.id}-publish`;
    this.deps.mutationKey.set(key);

    this.deps.workerApi.completeBadReviewTask(review.badTaskId).subscribe({
      next: () => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.success('Оценка изменена', this.reviewActionTitle(review));
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Задача не выполнена', this.deps.errorMessage(err, 'Не удалось отметить изменение оценки'));
      }
    });
  }

  private markReviewNagul(review: WorkerReviewItem): void {
    const key = `review-${review.id}-nagul`;
    this.deps.mutationKey.set(key);

    this.deps.workerApi.nagulReview(review.id).subscribe({
      next: (response) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.success('Выгул выполнен', response.message || `Отзыв #${review.id}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Выгул не выполнен', this.deps.errorMessage(err, 'Не удалось отметить выгул'));
      }
    });
  }

  private patchOrder(orderId: number, patch: Partial<OrderCardItem>): void {
    this.deps.patchBoard?.((board) => ({
      ...board,
      orders: {
        ...board.orders,
        content: board.orders.content.map((order) => order.id === orderId
          ? { ...order, ...patch }
          : order
        )
      }
    }));
  }
}
