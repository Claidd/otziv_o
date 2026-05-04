import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ManagerApi,
  ManagerOption,
  OrderCardItem,
  OrderDetailsPayload,
  OrderEditPayload,
  OrderUpdateRequest
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
import { CompanyNoteTriggerComponent } from '../../shared/company-note-trigger.component';
import { ToastService } from '../../shared/toast.service';
import {
  DEFAULT_WORKER_PERMISSIONS,
  EMPTY_WORKER_ORDER_PAGE,
  EMPTY_WORKER_REVIEW_PAGE,
  ReviewCopyKind,
  ReviewEditDraft,
  ReviewEditableField,
  ReviewEditItem,
  SectionTab,
  SideNoteField,
  StatusAction,
  WORKER_ORDER_STATUS_ACTIONS,
  WORKER_PAGE_SIZE_OPTIONS,
  WORKER_SECTIONS,
  trackWorkerAction,
  trackWorkerBot,
  trackWorkerMetric,
  trackWorkerOption,
  trackWorkerOrder,
  trackWorkerReview,
  trackWorkerSection,
  workerAbsoluteAppUrl,
  workerBotChangeMessage,
  workerErrorMessage,
  workerLegacyUrl,
  workerReviewCopyLabel,
  workerSectionLabel
} from './worker-board.config';
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

@Component({
  selector: 'app-worker-board',
  imports: [AdminLayoutComponent, CompanyNoteTriggerComponent, FormsModule, RouterLink],
  templateUrl: './worker-board.component.html',
  styleUrl: './worker-board.component.scss'
})
export class WorkerBoardComponent {
  private readonly workerApi = inject(WorkerApi);
  private readonly managerApi = inject(ManagerApi);
  private readonly toastService = inject(ToastService);

  readonly sections = WORKER_SECTIONS;
  readonly orderStatusActions = WORKER_ORDER_STATUS_ACTIONS;
  readonly pageSizeOptions = WORKER_PAGE_SIZE_OPTIONS;
  readonly addBotUrl = workerLegacyUrl('/bots/bot_add');
  readonly botListUrl = workerLegacyUrl('/worker/bot_list');

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
      return this.board()?.reviews ?? EMPTY_WORKER_REVIEW_PAGE;
    }

    return this.board()?.orders ?? EMPTY_WORKER_ORDER_PAGE;
  });
  readonly title = computed(() => `Специалист - ${this.board()?.title ?? workerSectionLabel(this.activeSection())}`);
  readonly metrics = computed(() => this.board()?.metrics ?? []);
  readonly productOptions = computed(() => this.reviewEditDetails()?.products ?? []);
  readonly reviewEditBusy = computed(() => this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading());
  readonly permissions = computed(() => this.board()?.permissions ?? DEFAULT_WORKER_PERMISSIONS);
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
          const title = board.warning ? 'Раздел закрыт - окончите Выгул!' : 'Специалист';
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
    const oldBotId = review.botId ?? null;
    this.mutationKey.set(key);

    const request = this.isBadTask(review) && review.badTaskId
      ? this.workerApi.changeBadReviewTaskBot(review.badTaskId)
      : this.workerApi.changeReviewBot(review.id);

    request.subscribe({
      next: (response) => {
        this.mutationKey.set(null);
        this.toastService.success(
          'Аккаунт изменен',
          workerBotChangeMessage(oldBotId, response?.newBotId ?? null)
        );
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

    const request = this.isBadTask(review) && review.badTaskId
      ? this.workerApi.deactivateBadReviewTaskBot(review.badTaskId, review.botId)
      : this.workerApi.deactivateReviewBot(review.id, review.botId);

    request.subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Бот заблокирован', this.reviewActionTitle(review));
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Бот не заблокирован', this.errorMessage(err, 'Не удалось заблокировать бота'));
      }
    });
  }

  markReviewDone(review: WorkerReviewItem): void {
    if (this.isBadTask(review)) {
      this.markBadReviewTaskDone(review);
      return;
    }

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
        this.reviewFieldDrafts.update((drafts) => workerRemoveRecordKey(drafts, workerReviewFieldKey(review, 'text')));
        this.reviewFieldDrafts.update((drafts) => workerRemoveRecordKey(drafts, workerReviewFieldKey(review, 'answer')));
        this.reviewNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, review.id));
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
    this.orderNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, order.id));
  }

  saveOrderNote(order: OrderCardItem): void {
    if (!this.permissions().canEditNotes) {
      return;
    }

    const value = this.orderNoteDraft(order);
    const key = this.orderNoteMutationKey(order);

    this.mutationKey.set(key);
    this.workerApi.updateOrderNote(order.id, value).subscribe({
      next: () => {
        this.setBoardPatch(workerPatchOrderNote(this.board(), order.id, value));
        this.mutationKey.set(null);
        this.editingOrderNoteId.set(null);
        this.savedOrderNoteId.set(order.id);
        this.orderNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, order.id));
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

  saveOrderCompanyNote(order: OrderCardItem, value: string): void {
    if (!this.permissions().canEditNotes) {
      return;
    }

    this.workerApi.updateOrderCompanyNote(order.id, value).subscribe({
      next: () => {
        this.toastService.success('Заметка компании сохранена', order.companyTitle || `Заказ #${order.id}`);
        this.loadBoard();
      },
      error: (err) => {
        this.toastService.error('Заметка не сохранена', this.errorMessage(err, 'Не удалось сохранить заметку компании'));
      }
    });
  }

  startReviewFieldEdit(review: WorkerReviewItem, field: ReviewEditableField): void {
    if (!this.permissions().canWorkReviews || this.isMutating(workerSaveReviewFieldMutationKey(review, field))) {
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
    if (!this.permissions().canWorkReviews) {
      return;
    }

    const value = this.reviewFieldValue(review, field);
    if (field === 'text' && !value.trim()) {
      this.toastService.error('Текст не сохранен', 'Поле отзыва не должно быть пустым');
      return;
    }

    const key = workerSaveReviewFieldMutationKey(review, field);
    const fieldKey = workerReviewFieldKey(review, field);
    const request = field === 'text'
      ? this.workerApi.updateReviewText(review.id, review.orderId, value)
      : this.workerApi.updateReviewAnswer(review.id, review.orderId, value);

    this.mutationKey.set(key);
    request.subscribe({
      next: () => {
        this.setBoardPatch(workerPatchReviewField(this.board(), review.id, field, value));
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

          this.reviewFieldDrafts.update((drafts) => workerRemoveRecordKey(drafts, fieldKey));
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
    const key = workerReviewFieldKey(review, field);
    return this.reviewFieldDrafts()[key] ?? workerReviewFieldSourceValue(review, field);
  }

  isReviewFieldEditing(review: WorkerReviewItem, field: ReviewEditableField): boolean {
    return this.editingReviewFieldKey() === workerReviewFieldKey(review, field);
  }

  isReviewFieldChanged(review: WorkerReviewItem, field: ReviewEditableField): boolean {
    return this.reviewFieldValue(review, field) !== workerReviewFieldSourceValue(review, field);
  }

  isReviewFieldSaved(review: WorkerReviewItem, field: ReviewEditableField): boolean {
    return this.savedReviewFieldKey() === workerReviewFieldKey(review, field);
  }

  shouldShowReviewTextToggle(review: WorkerReviewItem): boolean {
    return workerReviewTextNeedsToggle(review);
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
    return workerHasReviewNote(review);
  }

  hasReviewOwnNote(review: WorkerReviewItem): boolean {
    return workerHasReviewOwnNote(review);
  }

  hasReviewOrderNote(review: WorkerReviewItem): boolean {
    return workerHasReviewOrderNote(review);
  }

  hasReviewCompanyNote(review: WorkerReviewItem): boolean {
    return workerHasReviewCompanyNote(review);
  }

  startReviewNoteEdit(review: WorkerReviewItem): void {
    if (!this.permissions().canWorkReviews || this.isMutating(workerReviewNoteMutationKey(review))) {
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
    if (!this.permissions().canWorkReviews) {
      return;
    }

    const value = this.reviewNoteValue(review);
    const key = workerReviewNoteMutationKey(review);
    this.mutationKey.set(key);

    this.workerApi.updateReviewNote(review.id, review.orderId, value).subscribe({
      next: () => {
        this.setBoardPatch(workerPatchReviewNote(this.board(), review.id, value));
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

          this.reviewNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, review.id));
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
    if (!this.permissions().canEditNotes || this.isMutating(workerSideNoteMutationKey(review, field))) {
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
    if (!this.permissions().canEditNotes) {
      return;
    }

    const value = this.sideNoteValue(review, field);
    const key = workerSideNoteMutationKey(review, field);
    const sideKey = workerSideNoteKey(review, field);
    const request = field === 'order'
      ? this.workerApi.updateOrderNote(review.orderId, value)
      : this.workerApi.updateOrderCompanyNote(review.orderId, value);

    this.mutationKey.set(key);
    request.subscribe({
      next: () => {
        this.setBoardPatch(workerPatchReviewSideNote(this.board(), review, field, value));
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

          this.sideNoteDrafts.update((drafts) => workerRemoveRecordKey(drafts, sideKey));
        }, 1000);
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Заметка не сохранена', this.errorMessage(err, 'Не удалось сохранить заметку'));
      }
    });
  }

  sideNoteValue(review: WorkerReviewItem, field: SideNoteField): string {
    return this.sideNoteDrafts()[workerSideNoteKey(review, field)] ?? workerSideNoteSourceValue(review, field);
  }

  isSideNoteEditing(review: WorkerReviewItem, field: SideNoteField): boolean {
    return this.editingSideNoteKey() === workerSideNoteKey(review, field);
  }

  isSideNoteChanged(review: WorkerReviewItem, field: SideNoteField): boolean {
    return this.sideNoteValue(review, field) !== workerSideNoteSourceValue(review, field);
  }

  isSideNoteSaved(review: WorkerReviewItem, field: SideNoteField): boolean {
    return this.savedSideNoteKey() === workerSideNoteKey(review, field);
  }

  reviewNoteTitle(review: WorkerReviewItem): string {
    return workerReviewNoteTitle(review);
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
    return review.badTaskScheduledDate || review.publishedDate || review.created || '-';
  }

  isBadTask(review: WorkerReviewItem): boolean {
    return !!review.badTask;
  }

  ratingTaskLabel(review: WorkerReviewItem): string {
    if (!this.isBadTask(review)) {
      return '';
    }

    return `${review.originalRating ?? 5} -> ${review.targetRating ?? 2}`;
  }

  reviewActionTitle(review: WorkerReviewItem): string {
    return this.isBadTask(review) && review.badTaskId
      ? `Плохая задача #${review.badTaskId}`
      : `Отзыв #${review.id}`;
  }

  async copyPhone(phone?: string): Promise<void> {
    await this.copyText(phone ?? '', 'телефон', 'Телефон скопирован');
  }

  async copyOrderText(order: OrderCardItem, kind: 'check' | 'payment'): Promise<void> {
    if (kind === 'check') {
      const url = order.orderDetailsId ? workerAbsoluteAppUrl(`/review/editReviews/${order.orderDetailsId}`) : '';
      await this.copyText(
        `${this.board()?.promoTexts?.[4] ?? ''} Ссылка на проверку отзывов: ${url}`.trim(),
        `check-${order.id}`,
        'Текст проверки скопирован'
      );
      return;
    }

    const sum = order.totalSumWithBadReviews ?? order.sum ?? 0;
    await this.copyText(
      `${order.managerPayText ?? ''} К оплате: ${sum} руб. ${order.companyTitle} ${order.filialTitle ?? ''}`.trim(),
      `payment-${order.id}`,
      'Текст счета скопирован'
    );
  }

  async copyReviewValue(review: WorkerReviewItem, kind: ReviewCopyKind): Promise<void> {
    const value = {
      url: review.filialUrl,
      login: review.botLogin,
      password: review.botPassword,
      text: review.text,
      answer: review.answer,
      vk: 'https://vk.com/'
    }[kind] ?? '';

    await this.copyText(value, `${kind}-${review.id}`, `${workerReviewCopyLabel(kind)} скопирован`);
  }

  async copyBotValue(bot: WorkerBotItem, kind: 'login' | 'password'): Promise<void> {
    await this.copyText(kind === 'login' ? bot.login : bot.password, `bot-${kind}-${bot.id}`, kind === 'login' ? 'Логин скопирован' : 'Пароль скопирован');
  }

  isMutating(key: string): boolean {
    return this.mutationKey() === key;
  }

  orderNoteText(order: OrderCardItem): string {
    return workerOrderNoteText(order);
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

  shouldShowExpander(text?: string | null): boolean {
    return workerShouldShowExpander(text);
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
      return `${order.totalSumWithBadReviews ?? order.sum ?? 0} руб.`;
    }

    return `${order.amount ?? 0} шт.`;
  }

  showBadReviewSummary(order: OrderCardItem): boolean {
    return order.status !== 'Оплачено' && (order.badReviewTasksTotal ?? 0) > 0;
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
    return workerLegacyUrl(`/ordersDetails/${order.companyId}/${order.id}`);
  }

  orderEditUrl(order: OrderCardItem): string {
    return workerLegacyUrl(`/ordersCompany/ordersDetails/${order.companyId}/${order.id}`);
  }

  reviewEditUrl(review: WorkerReviewItem): string {
    return workerLegacyUrl(`/review/editReview/${review.sourceReviewId ?? review.id}`);
  }

  botEditUrl(bot: WorkerBotItem): string {
    return workerLegacyUrl(`/bots/edit/${bot.id}`);
  }

  botBrowserUrl(review: WorkerReviewItem): string {
    return review.botId ? workerLegacyUrl(`/bots/${review.botId}/browser`) : 'https://vk.com/';
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
    return trackWorkerSection(_index, section);
  }

  trackOrder(_index: number, order: OrderCardItem): number {
    return trackWorkerOrder(_index, order);
  }

  trackReview(_index: number, review: WorkerReviewItem): number {
    return trackWorkerReview(_index, review);
  }

  trackBot(_index: number, bot: WorkerBotItem): number {
    return trackWorkerBot(_index, bot);
  }

  trackMetric(_index: number, metric: WorkerMetric): string {
    return trackWorkerMetric(_index, metric);
  }

  trackAction(_index: number, action: StatusAction): string {
    return trackWorkerAction(_index, action);
  }

  trackOption(_index: number, option: ManagerOption): number {
    return trackWorkerOption(_index, option);
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

  hasMeaningfulNote(value?: string | null): boolean {
    return workerHasMeaningfulNote(value);
  }

  private setBoardPatch(board: WorkerBoard | null): void {
    if (board) {
      this.board.set(board);
    }
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

  private markBadReviewTaskDone(review: WorkerReviewItem): void {
    if (!review.badTaskId) {
      return;
    }

    const key = `review-${review.id}-publish`;
    this.mutationKey.set(key);

    this.workerApi.completeBadReviewTask(review.badTaskId).subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Оценка изменена', this.reviewActionTitle(review));
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Задача не выполнена', this.errorMessage(err, 'Не удалось отметить изменение оценки'));
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

  private errorMessage(err: unknown, fallback: string): string {
    return workerErrorMessage(err, fallback);
  }
}
