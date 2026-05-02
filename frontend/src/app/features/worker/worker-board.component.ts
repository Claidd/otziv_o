import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { appEnvironment } from '../../core/app-environment';
import {
  ManagerApi,
  ManagerOption,
  OrderCardItem,
  OrderDetailsPayload,
  OrderEditPayload,
  OrderReviewItem,
  OrderUpdateRequest,
  ReviewUpdateRequest
} from '../../core/manager.api';
import {
  WorkerApi,
  WorkerBoard,
  WorkerBotItem,
  WorkerMetric,
  WorkerPage,
  WorkerReviewItem,
  WorkerSection
} from '../../core/worker.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { ToastService } from '../../shared/toast.service';

type SectionTab = {
  key: WorkerSection;
  label: string;
  icon: string;
};

type StatusAction = {
  label: string;
  status: string;
  icon: string;
};

type ReviewEditableField = 'text' | 'answer';
type SideNoteField = 'order' | 'company';
type ReviewEditItem = WorkerReviewItem | OrderReviewItem;
type ReviewEditDraft = ReviewUpdateRequest;

@Component({
  selector: 'app-worker-board',
  imports: [AdminLayoutComponent, FormsModule, RouterLink],
  templateUrl: './worker-board.component.html',
  styleUrl: './worker-board.component.scss'
})
export class WorkerBoardComponent {
  private readonly workerApi = inject(WorkerApi);
  private readonly managerApi = inject(ManagerApi);
  private readonly toastService = inject(ToastService);
  private readonly emptyOrderPage: WorkerPage<OrderCardItem> = {
    content: [],
    number: 0,
    size: 10,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true
  };
  private readonly emptyReviewPage: WorkerPage<WorkerReviewItem> = {
    content: [],
    number: 0,
    size: 10,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true
  };

  readonly sections: SectionTab[] = [
    { key: 'new', label: 'Новые', icon: 'fiber_new' },
    { key: 'correct', label: 'Коррекция', icon: 'build_circle' },
    { key: 'nagul', label: 'Выгул', icon: 'directions_walk' },
    { key: 'publish', label: 'Публикация', icon: 'published_with_changes' },
    { key: 'bad', label: 'Плохие', icon: 'money_off' },
    { key: 'all', label: 'Все', icon: 'dashboard' }
  ];

  readonly orderStatusActions: StatusAction[] = [
    { label: 'на проверку', status: 'На проверке', icon: 'manage_search' },
    { label: 'коррекция', status: 'Коррекция', icon: 'build_circle' },
    { label: 'архив', status: 'Архив', icon: 'archive' },
    { label: 'одобрено', status: 'Публикация', icon: 'task_alt' },
    { label: 'опублик.', status: 'Опубликовано', icon: 'published_with_changes' },
    { label: 'счет', status: 'Выставлен счет', icon: 'receipt_long' },
    { label: 'напомнить', status: 'Напоминание', icon: 'notifications_active' },
    { label: 'не опл.', status: 'Не оплачено', icon: 'money_off' },
    { label: 'оплатили', status: 'Оплачено', icon: 'payments' }
  ];

  readonly pageSizeOptions = [5, 10, 15];
  readonly addBotUrl = this.legacyUrl('/bots/bot_add');
  readonly botListUrl = this.legacyUrl('/worker/bot_list');

  readonly board = signal<WorkerBoard | null>(null);
  readonly activeSection = signal<WorkerSection>('new');
  readonly keyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly sortDirection = signal<'desc' | 'asc'>('desc');
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly copied = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly mobileMenuOpen = signal(false);
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

  readonly currentOrders = computed(() => this.board()?.orders.content ?? []);
  readonly currentReviews = computed(() => this.board()?.reviews.content ?? []);
  readonly currentBots = computed(() => this.board()?.bots ?? []);
  readonly currentPage = computed<WorkerPage<OrderCardItem | WorkerReviewItem>>(() => {
    if (this.isReviewSection(this.activeSection())) {
      return this.board()?.reviews ?? this.emptyReviewPage;
    }

    return this.board()?.orders ?? this.emptyOrderPage;
  });
  readonly title = computed(() => `Специалист - ${this.board()?.title ?? this.sectionLabel(this.activeSection())}`);
  readonly metrics = computed(() => this.board()?.metrics ?? []);
  readonly productOptions = computed(() => this.reviewEditDetails()?.products ?? []);
  readonly reviewEditBusy = computed(() => this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading());
  readonly permissions = computed(() => this.board()?.permissions ?? {
    canManageOrderStatuses: false,
    canSeePhoneAndPayment: false,
    canManageBots: false,
    canAddBot: false,
    canSeeMoney: false,
    canWorkReviews: false,
    canEditNotes: false
  });
  constructor() {
    this.loadBoard();
  }

  @HostListener('window:keydown.escape')
  closeModalsFromKeyboard(): void {
    this.closeOrderEdit();
    this.closeReviewEdit();
  }

  loadBoard(): void {
    this.loading.set(true);
    this.error.set(null);

    this.workerApi.getBoard({
      section: this.activeSection(),
      keyword: this.keyword(),
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize(),
      sortDirection: this.sortDirection()
    }).subscribe({
      next: (board) => {
        this.board.set(board);
        this.loading.set(false);

        if (board.section !== this.activeSection()) {
          this.activeSection.set(board.section);
          this.pageNumber.set(board.reviews.number || board.orders.number || 0);
        }

        if (board.message) {
          const title = board.warning ? 'Публикация закрыта' : 'Специалист';
          board.warning ? this.toastService.error(title, board.message) : this.toastService.success(title, board.message);
        }
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить раздел специалиста');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Специалист не загрузился', message);
      }
    });
  }

  setSection(section: WorkerSection): void {
    this.activeSection.set(section);
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.loadBoard();
  }

  handleSectionMenu(section: WorkerSection | ''): void {
    if (!section) {
      return;
    }

    this.setSection(section);
  }

  search(): void {
    this.pageNumber.set(0);
    this.loadBoard();
  }

  clearSearch(): void {
    this.keyword.set('');
    this.search();
  }

  changePageSize(value: string | number): void {
    this.pageSize.set(Number(value));
    this.pageNumber.set(0);
    this.loadBoard();
  }

  toggleSortDirection(): void {
    this.sortDirection.update((direction) => direction === 'desc' ? 'asc' : 'desc');
    this.pageNumber.set(0);
    this.loadBoard();
  }

  previousPage(): void {
    if (this.currentPage().first) {
      return;
    }

    this.pageNumber.update((page) => Math.max(page - 1, 0));
    this.loadBoard();
  }

  nextPage(): void {
    if (this.currentPage().last) {
      return;
    }

    this.pageNumber.update((page) => page + 1);
    this.loadBoard();
  }

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((open) => !open);
  }

  openMetric(metric: WorkerMetric): void {
    this.setSection(metric.section);
  }

  updateOrderStatus(order: OrderCardItem, action: StatusAction): void {
    const key = `order-${order.id}-${action.status}`;
    this.mutationKey.set(key);

    this.workerApi.updateOrderStatus(order.id, action.status).subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Статус изменен', `${order.companyTitle}: ${action.status}`);
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Статус не изменен', this.errorMessage(err, 'Не удалось изменить статус заказа'));
      }
    });
  }

  changeReviewBot(review: WorkerReviewItem): void {
    const key = `review-${review.id}-change-bot`;
    this.mutationKey.set(key);

    this.workerApi.changeReviewBot(review.id).subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Бот заменен', `Отзыв #${review.id}`);
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Бот не заменен', this.errorMessage(err, 'Не удалось заменить бота'));
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
    this.mutationKey.set(key);

    this.workerApi.deactivateReviewBot(review.id, review.botId).subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Бот заблокирован', `Отзыв #${review.id}`);
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Бот не заблокирован', this.errorMessage(err, 'Не удалось заблокировать бота'));
      }
    });
  }

  markReviewDone(review: WorkerReviewItem): void {
    if (this.activeSection() === 'nagul') {
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
    this.mutationKey.set(key);

    this.workerApi.deleteBot(bot.id).subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Аккаунт удален', bot.fio || `#${bot.id}`);
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Аккаунт не удален', this.errorMessage(err, 'Не удалось удалить аккаунт'));
      }
    });
  }

  openOrderEdit(order: OrderCardItem): void {
    if (!this.canOpenOrderEditModal()) {
      window.location.href = this.orderEditUrl(order);
      return;
    }

    this.orderLoading.set(true);
    this.orderError.set(null);
    this.orderDeleting.set(false);

    this.managerApi.getOrderEdit(order.id).subscribe({
      next: (payload) => {
        this.applyOrderEditPayload(payload);
        this.orderLoading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось открыть редактирование заказа');
        this.orderLoading.set(false);
        this.orderError.set(message);
        this.toastService.error('Заказ не открыт', message);
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

  setOrderEditField<K extends keyof OrderUpdateRequest>(field: K, value: OrderUpdateRequest[K]): void {
    this.orderDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  saveOrderEdit(): void {
    const order = this.editOrder();
    const draft = this.orderDraft();

    if (!order || !draft) {
      return;
    }

    this.orderSaving.set(true);
    this.orderError.set(null);

    this.managerApi.updateOrder(order.id, draft).subscribe({
      next: () => {
        this.orderSaving.set(false);
        this.closeOrderEdit();
        this.toastService.success('Заказ сохранен', `Изменения по заказу #${order.id} применены`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить заказ');
        this.orderError.set(message);
        this.orderSaving.set(false);
        this.toastService.error('Заказ не сохранен', message);
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

    this.managerApi.deleteOrder(order.id).subscribe({
      next: () => {
        this.orderDeleting.set(false);
        this.closeOrderEdit();
        this.toastService.success('Заказ удален', `Заказ #${order.id} удален`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить заказ');
        this.orderDeleting.set(false);
        this.orderError.set(message);
        this.toastService.error('Заказ не удален', message);
      }
    });
  }

  openReviewEdit(review: WorkerReviewItem): void {
    if (!this.canOpenReviewEditModal()) {
      window.location.href = this.reviewEditUrl(review);
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

    this.managerApi.getOrderDetails(review.orderId).subscribe({
      next: (details) => {
        const currentReview = details.reviews.find((item) => item.id === review.id) ?? review;
        this.reviewEditDetails.set(details);
        this.editReview.set(currentReview);
        this.reviewEditDraft.set(this.toReviewEditDraft(currentReview));
        this.reviewEditLoading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось открыть редактирование отзыва');
        this.reviewEditLoading.set(false);
        this.reviewEditError.set(message);
        this.toastService.error('Отзыв не открыт', message);
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

  setReviewEditField<K extends keyof ReviewEditDraft>(field: K, value: ReviewEditDraft[K]): void {
    this.reviewEditDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
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

    this.managerApi.updateOrderReview(review.orderId, review.id, draft).subscribe({
      next: (details) => {
        this.reviewEditDetails.set(details);
        this.clearReviewFieldDraft(review, 'text');
        this.clearReviewFieldDraft(review, 'answer');
        this.clearReviewNoteDraft(review.id);
        this.reviewEditSaving.set(false);
        this.editReview.set(null);
        this.reviewEditDraft.set(null);
        this.toastService.success('Отзыв сохранен', `Изменения по отзыву #${review.id} применены`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить отзыв');
        this.reviewEditError.set(message);
        this.reviewEditSaving.set(false);
        this.toastService.error('Отзыв не сохранен', message);
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

    this.managerApi.deleteOrderReview(review.orderId, review.id).subscribe({
      next: (details) => {
        this.reviewEditDetails.set(details);
        this.reviewEditDeleting.set(false);
        this.editReview.set(null);
        this.reviewEditDraft.set(null);
        this.toastService.success('Отзыв удален', `Отзыв #${review.id} удален из заказа`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить отзыв');
        this.reviewEditError.set(message);
        this.reviewEditDeleting.set(false);
        this.toastService.error('Отзыв не удален', message);
      }
    });
  }

  uploadReviewPhoto(event: Event): void {
    const review = this.editReview();
    const input = event.target as HTMLInputElement | null;
    const file = input?.files?.[0];

    if (!review || !file) {
      return;
    }

    this.reviewEditUploading.set(true);
    this.reviewEditError.set(null);

    this.managerApi.uploadOrderReviewPhoto(review.orderId, review.id, file).subscribe({
      next: (details) => {
        this.reviewEditDetails.set(details);
        this.reviewEditUploading.set(false);

        const updatedReview = details.reviews.find((item) => item.id === review.id);
        if (updatedReview) {
          this.editReview.set(updatedReview);
          this.reviewEditDraft.update((draft) => draft ? {
            ...draft,
            url: updatedReview.url || updatedReview.urlPhoto || ''
          } : this.toReviewEditDraft(updatedReview));
        }

        if (input) {
          input.value = '';
        }

        this.toastService.success('Фото загружено', `Отзыв #${review.id} обновлен`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить фото');
        this.reviewEditError.set(message);
        this.reviewEditUploading.set(false);
        if (input) {
          input.value = '';
        }
        this.toastService.error('Фото не загружено', message);
      }
    });
  }

  canEditReviewDates(): boolean {
    return !!this.reviewEditDetails()?.canEditReviewDates;
  }

  canEditReviewPublish(): boolean {
    return !!this.reviewEditDetails()?.canEditReviewPublish;
  }

  canEditReviewVigul(): boolean {
    return !!this.reviewEditDetails()?.canEditReviewVigul;
  }

  canDeleteReviews(): boolean {
    return !!this.reviewEditDetails()?.canDeleteReviews;
  }

  productNeedsPhoto(productId: number | null): boolean {
    if (productId == null) {
      return false;
    }

    return !!this.productOptions().find((product) => product.id === productId)?.photo;
  }

  startOrderNoteEdit(order: OrderCardItem): void {
    if (!this.permissions().canEditNotes || this.isMutating(this.orderNoteMutationKey(order))) {
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
    this.orderNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[order.id];
      return next;
    });
  }

  saveOrderNote(order: OrderCardItem): void {
    if (!this.permissions().canEditNotes) {
      return;
    }

    const value = this.orderNoteDraft(order);
    const field = this.orderNoteField(order);
    const key = this.orderNoteMutationKey(order);
    const request = field === 'company'
      ? this.workerApi.updateOrderCompanyNote(order.id, value)
      : this.workerApi.updateOrderNote(order.id, value);

    this.mutationKey.set(key);
    request.subscribe({
      next: () => {
        this.patchOrderNote(order.id, field, value);
        this.mutationKey.set(null);
        this.editingOrderNoteId.set(null);
        this.savedOrderNoteId.set(order.id);
        this.orderNoteDrafts.update((drafts) => {
          const next = { ...drafts };
          delete next[order.id];
          return next;
        });
        this.toastService.success('Заметка сохранена', order.companyTitle || `Заказ #${order.id}`);
        window.setTimeout(() => {
          if (this.savedOrderNoteId() === order.id) {
            this.savedOrderNoteId.set(null);
          }
        }, 1400);
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Заметка не сохранена', this.errorMessage(err, 'Не удалось сохранить заметку'));
      }
    });
  }

  startReviewFieldEdit(review: WorkerReviewItem, field: ReviewEditableField): void {
    if (!this.permissions().canWorkReviews || this.isMutating(this.saveReviewFieldMutationKey(review, field))) {
      return;
    }

    const key = this.reviewFieldKey(review, field);
    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(key);
    this.reviewFieldDrafts.update((drafts) => {
      if (key in drafts) {
        return drafts;
      }

      return {
        ...drafts,
        [key]: this.reviewFieldSourceValue(review, field)
      };
    });
  }

  setReviewFieldDraft(review: WorkerReviewItem, field: ReviewEditableField, value: string): void {
    const key = this.reviewFieldKey(review, field);
    this.reviewFieldDrafts.update((drafts) => ({ ...drafts, [key]: value }));
  }

  cancelReviewFieldEdit(review: WorkerReviewItem, field: ReviewEditableField): void {
    if (this.isMutating(this.saveReviewFieldMutationKey(review, field))) {
      return;
    }

    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(null);
    this.clearReviewFieldDraft(review, field);
  }

  saveReviewField(review: WorkerReviewItem, field: ReviewEditableField): void {
    if (!this.permissions().canWorkReviews) {
      return;
    }

    const value = this.reviewFieldValue(review, field);
    if (field === 'text' && !value.trim()) {
      this.toastService.error('Текст не сохранен', 'Поле отзыва не должно быть пустым');
      return;
    }

    const key = this.saveReviewFieldMutationKey(review, field);
    const fieldKey = this.reviewFieldKey(review, field);
    const request = field === 'text'
      ? this.workerApi.updateReviewText(review.id, review.orderId, value)
      : this.workerApi.updateReviewAnswer(review.id, review.orderId, value);

    this.mutationKey.set(key);
    request.subscribe({
      next: () => {
        this.patchReviewField(review.id, field, value);
        this.mutationKey.set(null);
        this.savedReviewFieldKey.set(fieldKey);
        this.toastService.success(field === 'text' ? 'Текст сохранен' : 'Замечание сохранено', `Отзыв #${review.id} обновлен`);

        window.setTimeout(() => {
          if (this.savedReviewFieldKey() === fieldKey) {
            this.savedReviewFieldKey.set(null);
          }

          if (this.editingReviewFieldKey() === fieldKey) {
            this.editingReviewFieldKey.set(null);
          }

          this.clearReviewFieldDraft(review, field);
        }, 1000);
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error(
          field === 'text' ? 'Текст не сохранен' : 'Замечание не сохранено',
          this.errorMessage(err, field === 'text' ? 'Не удалось сохранить текст отзыва' : 'Не удалось сохранить замечание')
        );
      }
    });
  }

  reviewFieldValue(review: WorkerReviewItem, field: ReviewEditableField): string {
    const key = this.reviewFieldKey(review, field);
    return this.reviewFieldDrafts()[key] ?? this.reviewFieldSourceValue(review, field);
  }

  isReviewFieldEditing(review: WorkerReviewItem, field: ReviewEditableField): boolean {
    return this.editingReviewFieldKey() === this.reviewFieldKey(review, field);
  }

  isReviewFieldChanged(review: WorkerReviewItem, field: ReviewEditableField): boolean {
    return this.reviewFieldValue(review, field) !== this.reviewFieldSourceValue(review, field);
  }

  isReviewFieldSaved(review: WorkerReviewItem, field: ReviewEditableField): boolean {
    return this.savedReviewFieldKey() === this.reviewFieldKey(review, field);
  }

  shouldShowReviewTextToggle(review: WorkerReviewItem): boolean {
    const value = this.reviewFieldSourceValue(review, 'text');
    return value.length > 190 || value.split(/\r?\n/).length > 5;
  }

  isReviewTextExpanded(review: WorkerReviewItem): boolean {
    return Boolean(this.expandedReviewTextIds()[review.id]);
  }

  isReviewTextOpen(review: WorkerReviewItem): boolean {
    return this.isReviewTextExpanded(review) || this.isReviewFieldEditing(review, 'text');
  }

  toggleReviewText(review: WorkerReviewItem): void {
    this.expandedReviewTextIds.update((items) => ({ ...items, [review.id]: !items[review.id] }));
  }

  hasReviewNote(review: WorkerReviewItem): boolean {
    return this.hasReviewOwnNote(review) || this.hasReviewOrderNote(review) || this.hasReviewCompanyNote(review);
  }

  hasReviewOwnNote(review: WorkerReviewItem): boolean {
    return this.hasMeaningfulNote(review.comment);
  }

  hasReviewOrderNote(review: WorkerReviewItem): boolean {
    return this.hasMeaningfulNote(review.orderComments);
  }

  hasReviewCompanyNote(review: WorkerReviewItem): boolean {
    return this.hasMeaningfulNote(review.commentCompany);
  }

  startReviewNoteEdit(review: WorkerReviewItem): void {
    if (!this.permissions().canWorkReviews || this.isMutating(`save-note-${review.id}`)) {
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
    if (this.isMutating(`save-note-${review.id}`)) {
      return;
    }

    this.savedReviewNoteId.set(null);
    this.editingReviewNoteId.set(null);
    this.clearReviewNoteDraft(review.id);
  }

  saveReviewNote(review: WorkerReviewItem): void {
    if (!this.permissions().canWorkReviews) {
      return;
    }

    const value = this.reviewNoteValue(review);
    const key = `save-note-${review.id}`;
    this.mutationKey.set(key);

    this.workerApi.updateReviewNote(review.id, review.orderId, value).subscribe({
      next: () => {
        this.patchReviewNote(review.id, value);
        this.mutationKey.set(null);
        this.savedReviewNoteId.set(review.id);
        this.toastService.success('Заметка сохранена', `Отзыв #${review.id} обновлен`);

        window.setTimeout(() => {
          if (this.savedReviewNoteId() === review.id) {
            this.savedReviewNoteId.set(null);
          }

          if (this.editingReviewNoteId() === review.id) {
            this.editingReviewNoteId.set(null);
          }

          this.clearReviewNoteDraft(review.id);
        }, 1000);
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Заметка не сохранена', this.errorMessage(err, 'Не удалось сохранить заметку отзыва'));
      }
    });
  }

  reviewNoteValue(review: WorkerReviewItem): string {
    return this.reviewNoteDrafts()[review.id] ?? review.comment ?? '';
  }

  isReviewNoteEditing(review: WorkerReviewItem): boolean {
    return this.editingReviewNoteId() === review.id;
  }

  isReviewNoteChanged(review: WorkerReviewItem): boolean {
    return this.reviewNoteValue(review) !== (review.comment ?? '');
  }

  isReviewNoteSaved(review: WorkerReviewItem): boolean {
    return this.savedReviewNoteId() === review.id;
  }

  startSideNoteEdit(review: WorkerReviewItem, field: SideNoteField): void {
    if (!this.permissions().canEditNotes || this.isMutating(this.sideNoteMutationKey(review, field))) {
      return;
    }

    const key = this.sideNoteKey(review, field);
    this.savedSideNoteKey.set(null);
    this.editingSideNoteKey.set(key);
    this.sideNoteDrafts.update((drafts) => ({
      ...drafts,
      [key]: drafts[key] ?? this.sideNoteSourceValue(review, field)
    }));
  }

  setSideNoteDraft(review: WorkerReviewItem, field: SideNoteField, value: string): void {
    const key = this.sideNoteKey(review, field);
    this.sideNoteDrafts.update((drafts) => ({ ...drafts, [key]: value }));
  }

  cancelSideNoteEdit(review: WorkerReviewItem, field: SideNoteField): void {
    if (this.isMutating(this.sideNoteMutationKey(review, field))) {
      return;
    }

    this.savedSideNoteKey.set(null);
    this.editingSideNoteKey.set(null);
    this.clearSideNoteDraft(review, field);
  }

  saveSideNote(review: WorkerReviewItem, field: SideNoteField): void {
    if (!this.permissions().canEditNotes) {
      return;
    }

    const value = this.sideNoteValue(review, field);
    const key = this.sideNoteMutationKey(review, field);
    const sideKey = this.sideNoteKey(review, field);
    const request = field === 'order'
      ? this.workerApi.updateOrderNote(review.orderId, value)
      : this.workerApi.updateOrderCompanyNote(review.orderId, value);

    this.mutationKey.set(key);
    request.subscribe({
      next: () => {
        this.patchReviewSideNote(review, field, value);
        this.mutationKey.set(null);
        this.savedSideNoteKey.set(sideKey);
        this.toastService.success(field === 'order' ? 'Заметка заказа сохранена' : 'Заметка компании сохранена');

        window.setTimeout(() => {
          if (this.savedSideNoteKey() === sideKey) {
            this.savedSideNoteKey.set(null);
          }

          if (this.editingSideNoteKey() === sideKey) {
            this.editingSideNoteKey.set(null);
          }

          this.clearSideNoteDraft(review, field);
        }, 1000);
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Заметка не сохранена', this.errorMessage(err, 'Не удалось сохранить заметку'));
      }
    });
  }

  sideNoteValue(review: WorkerReviewItem, field: SideNoteField): string {
    return this.sideNoteDrafts()[this.sideNoteKey(review, field)] ?? this.sideNoteSourceValue(review, field);
  }

  isSideNoteEditing(review: WorkerReviewItem, field: SideNoteField): boolean {
    return this.editingSideNoteKey() === this.sideNoteKey(review, field);
  }

  isSideNoteChanged(review: WorkerReviewItem, field: SideNoteField): boolean {
    return this.sideNoteValue(review, field) !== this.sideNoteSourceValue(review, field);
  }

  isSideNoteSaved(review: WorkerReviewItem, field: SideNoteField): boolean {
    return this.savedSideNoteKey() === this.sideNoteKey(review, field);
  }

  reviewNoteTitle(review: WorkerReviewItem): string {
    const items: string[] = [];

    if (this.hasReviewOwnNote(review)) {
      items.push('Есть заметка отзыва');
    }

    if (this.hasReviewOrderNote(review)) {
      items.push('Есть заметка заказа');
    }

    if (this.hasReviewCompanyNote(review)) {
      items.push('Есть заметка компании');
    }

    return items.join('. ') || 'Заметка отзыва';
  }

  botLabel(review: WorkerReviewItem): string {
    if (this.hasUnavailableBot(review)) {
      return 'нет доступных аккаунтов';
    }

    if (review.botFio) {
      return `${review.botFio} ${review.botCounter || ''}`.trim();
    }

    return review.productTitle || 'Аккаунт';
  }

  hasUnavailableBot(review: WorkerReviewItem): boolean {
    return (review.botFio ?? '').trim().toLocaleLowerCase('ru-RU') === 'нет доступных аккаунтов';
  }

  reviewDate(review: WorkerReviewItem): string {
    return review.publishedDate || review.created || '-';
  }

  async copyPhone(phone?: string): Promise<void> {
    await this.copyText(phone ?? '', 'телефон', 'Телефон скопирован');
  }

  async copyOrderText(order: OrderCardItem, kind: 'check' | 'payment'): Promise<void> {
    if (kind === 'check') {
      const url = order.orderDetailsId ? this.absoluteAppUrl(`/review/editReviews/${order.orderDetailsId}`) : '';
      await this.copyText(
        `${this.board()?.promoTexts?.[4] ?? ''} Ссылка на проверку отзывов: ${url}`.trim(),
        `check-${order.id}`,
        'Текст проверки скопирован'
      );
      return;
    }

    await this.copyText(
      `${order.managerPayText ?? ''} К оплате: ${order.sum ?? 0} руб. ${order.companyTitle} ${order.filialTitle ?? ''}`.trim(),
      `payment-${order.id}`,
      'Текст счета скопирован'
    );
  }

  async copyReviewValue(review: WorkerReviewItem, kind: 'url' | 'login' | 'password' | 'text' | 'answer' | 'vk'): Promise<void> {
    const value = {
      url: review.filialUrl,
      login: review.botLogin,
      password: review.botPassword,
      text: review.text,
      answer: review.answer,
      vk: 'https://vk.com/'
    }[kind] ?? '';

    await this.copyText(value, `${kind}-${review.id}`, `${this.reviewCopyLabel(kind)} скопирован`);
  }

  async copyBotValue(bot: WorkerBotItem, kind: 'login' | 'password'): Promise<void> {
    await this.copyText(kind === 'login' ? bot.login : bot.password, `bot-${kind}-${bot.id}`, kind === 'login' ? 'Логин скопирован' : 'Пароль скопирован');
  }

  isMutating(key: string): boolean {
    return this.mutationKey() === key;
  }

  orderNoteText(order: OrderCardItem): string {
    const value = this.orderNoteField(order) === 'company' ? order.companyComments : order.orderComments;
    return this.hasMeaningfulNote(value) ? value!.trim() : 'нет заметок';
  }

  editableOrderNoteText(order: OrderCardItem): string {
    const text = this.orderNoteText(order);
    return this.hasMeaningfulNote(text) ? text : '';
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
    return `save-order-note-${order.id}`;
  }

  shouldShowExpander(text?: string | null): boolean {
    return (text ?? '').trim().length > 88;
  }

  isOrderSection(section = this.activeSection()): boolean {
    return section === 'new' || section === 'correct' || section === 'all';
  }

  isReviewSection(section = this.activeSection()): boolean {
    return section === 'nagul' || section === 'publish' || section === 'bad';
  }

  canOpenOrderEditModal(): boolean {
    return true;
  }

  canOpenReviewEditModal(): boolean {
    return this.permissions().canWorkReviews;
  }

  orderAmountLabel(order: OrderCardItem): string {
    if (this.permissions().canSeeMoney) {
      return `${order.sum ?? 0} руб.`;
    }

    return `${order.amount ?? 0} шт.`;
  }

  progress(order: OrderCardItem): number {
    if (!order.amount || !order.counter) {
      return 0;
    }

    return Math.max(0, Math.min(100, Math.round((order.counter / order.amount) * 100)));
  }

  orderChatUrl(order: OrderCardItem): string {
    return order.companyUrlChat || `tel:${order.companyTelephone ?? ''}`;
  }

  orderDetailsUrl(order: OrderCardItem): string {
    return this.legacyUrl(`/ordersDetails/${order.companyId}/${order.id}`);
  }

  orderEditUrl(order: OrderCardItem): string {
    return this.legacyUrl(`/ordersCompany/ordersDetails/${order.companyId}/${order.id}`);
  }

  reviewEditUrl(review: WorkerReviewItem): string {
    return this.legacyUrl(`/review/editReview/${review.id}`);
  }

  botEditUrl(bot: WorkerBotItem): string {
    return this.legacyUrl(`/bots/edit/${bot.id}`);
  }

  botBrowserUrl(review: WorkerReviewItem): string {
    return review.botId ? this.legacyUrl(`/bots/${review.botId}/browser`) : 'https://vk.com/';
  }

  reviewPhotoUrl(review: WorkerReviewItem): string {
    return review.urlPhoto || review.url || '';
  }

  hasReviewPhoto(review: WorkerReviewItem): boolean {
    return Boolean(this.needsReviewPhoto(review) && this.reviewPhotoUrl(review));
  }

  needsReviewPhoto(review: WorkerReviewItem): boolean {
    return Boolean(review.productPhoto);
  }

  trackSection(_index: number, section: SectionTab): WorkerSection {
    return section.key;
  }

  trackOrder(_index: number, order: OrderCardItem): number {
    return order.id;
  }

  trackReview(_index: number, review: WorkerReviewItem): number {
    return review.id;
  }

  trackBot(_index: number, bot: WorkerBotItem): number {
    return bot.id;
  }

  trackMetric(_index: number, metric: WorkerMetric): string {
    return metric.section;
  }

  trackAction(_index: number, action: StatusAction): string {
    return action.status;
  }

  trackOption(_index: number, option: ManagerOption): number {
    return option.id;
  }

  optionLabel(option: ManagerOption): string {
    return option.label || `ID ${option.id}`;
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

  private orderNoteField(order: OrderCardItem): 'company' | 'order' {
    return this.hasMeaningfulNote(order.companyComments) ? 'company' : 'order';
  }

  private hasMeaningfulNote(value?: string | null): boolean {
    const normalized = (value ?? '').trim().toLowerCase();
    return Boolean(normalized) && normalized !== 'нет заметок';
  }

  private patchOrderNote(orderId: number, field: 'company' | 'order', value: string): void {
    const board = this.board();
    if (!board) {
      return;
    }

    const orders = {
      ...board.orders,
      content: board.orders.content.map((order) => {
        if (order.id !== orderId) {
          return order;
        }

        return field === 'company'
          ? { ...order, companyComments: value }
          : { ...order, orderComments: value || 'нет заметок' };
      })
    };

    this.board.set({ ...board, orders });
  }

  private reviewFieldKey(review: WorkerReviewItem, field: ReviewEditableField): string {
    return `${review.id}-${field}`;
  }

  private saveReviewFieldMutationKey(review: WorkerReviewItem, field: ReviewEditableField): string {
    return `save-${field}-${review.id}`;
  }

  private reviewFieldSourceValue(review: WorkerReviewItem, field: ReviewEditableField): string {
    return field === 'text' ? review.text ?? '' : review.answer ?? '';
  }

  private clearReviewFieldDraft(review: WorkerReviewItem, field: ReviewEditableField): void {
    const key = this.reviewFieldKey(review, field);
    this.reviewFieldDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[key];
      return next;
    });
  }

  private clearReviewNoteDraft(reviewId: number): void {
    this.reviewNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[reviewId];
      return next;
    });
  }

  private patchReviewField(reviewId: number, field: ReviewEditableField, value: string): void {
    const board = this.board();
    if (!board) {
      return;
    }

    const reviews = {
      ...board.reviews,
      content: board.reviews.content.map((review) => review.id === reviewId
        ? { ...review, [field]: value }
        : review
      )
    };

    this.board.set({ ...board, reviews });
  }

  private patchReviewNote(reviewId: number, value: string): void {
    const board = this.board();
    if (!board) {
      return;
    }

    const reviews = {
      ...board.reviews,
      content: board.reviews.content.map((review) => review.id === reviewId
        ? { ...review, comment: value }
        : review
      )
    };

    this.board.set({ ...board, reviews });
  }

  private sideNoteKey(review: WorkerReviewItem, field: SideNoteField): string {
    return `${field}-${review.id}`;
  }

  private sideNoteMutationKey(review: WorkerReviewItem, field: SideNoteField): string {
    return `save-side-${field}-${review.id}`;
  }

  private sideNoteSourceValue(review: WorkerReviewItem, field: SideNoteField): string {
    return field === 'order' ? review.orderComments ?? '' : review.commentCompany ?? '';
  }

  private clearSideNoteDraft(review: WorkerReviewItem, field: SideNoteField): void {
    const key = this.sideNoteKey(review, field);
    this.sideNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[key];
      return next;
    });
  }

  private patchReviewSideNote(review: WorkerReviewItem, field: SideNoteField, value: string): void {
    const board = this.board();
    if (!board) {
      return;
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

    this.board.set({ ...board, reviews });
  }

  private markReviewPublished(review: WorkerReviewItem): void {
    const key = `review-${review.id}-publish`;
    this.mutationKey.set(key);

    this.workerApi.publishReview(review.id).subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Отзыв опубликован', `Отзыв #${review.id}`);
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Отзыв не опубликован', this.errorMessage(err, 'Не удалось отметить отзыв опубликованным'));
      }
    });
  }

  private markReviewNagul(review: WorkerReviewItem): void {
    const key = `review-${review.id}-nagul`;
    this.mutationKey.set(key);

    this.workerApi.nagulReview(review.id).subscribe({
      next: (response) => {
        this.mutationKey.set(null);
        this.toastService.success('Выгул выполнен', response.message || `Отзыв #${review.id}`);
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Выгул не выполнен', this.errorMessage(err, 'Не удалось отметить выгул'));
      }
    });
  }

  private sectionLabel(section: WorkerSection): string {
    return this.sections.find((item) => item.key === section)?.label ?? 'Новые';
  }

  private reviewCopyLabel(kind: 'url' | 'login' | 'password' | 'text' | 'answer' | 'vk'): string {
    return {
      url: 'Ссылка',
      login: 'Логин',
      password: 'Пароль',
      text: 'Текст',
      answer: 'Ответ',
      vk: 'VK'
    }[kind];
  }

  private async copyText(text: string, copiedKey: string, toast: string): Promise<void> {
    const value = text.trim();

    if (!value) {
      return;
    }

    try {
      await navigator.clipboard.writeText(value);
      this.copied.set(copiedKey);
      this.toastService.success('Скопировано', toast);
      window.setTimeout(() => {
        if (this.copied() === copiedKey) {
          this.copied.set(null);
        }
      }, 1200);
    } catch {
      this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
    }
  }

  private legacyUrl(path: string): string {
    return `${appEnvironment.legacyBaseUrl}${path}`;
  }

  private absoluteAppUrl(path: string): string {
    return new URL(path, window.location.origin).toString();
  }

  private errorMessage(err: unknown, fallback: string): string {
    if (err && typeof err === 'object' && 'error' in err) {
      const body = (err as { error?: { message?: string } | string }).error;

      if (typeof body === 'string' && body.trim()) {
        return body;
      }

      if (body && typeof body === 'object' && 'message' in body && body.message) {
        return body.message;
      }
    }

    if (err && typeof err === 'object' && 'message' in err) {
      return String((err as { message?: string }).message ?? fallback);
    }

    return fallback;
  }
}
