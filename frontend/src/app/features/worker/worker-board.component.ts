import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ManagerApi,
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
import { WorkerBoardNoteFacade } from './worker-board-note.facade';
import type { WorkerOrderEditDraftChange } from './worker-order-edit-modal.component';
import { WorkerOrderEditModalComponent } from './worker-order-edit-modal.component';
import { WorkerOrderCardComponent } from './worker-order-card.component';
import type { WorkerReviewEditDraftChange } from './worker-review-edit-modal.component';
import { WorkerReviewEditModalComponent } from './worker-review-edit-modal.component';
import { WorkerReviewCardComponent } from './worker-review-card.component';

@Component({
  selector: 'app-worker-board',
  imports: [
    AdminLayoutComponent,
    FormsModule,
    WorkerOrderCardComponent,
    WorkerOrderEditModalComponent,
    WorkerReviewCardComponent,
    WorkerReviewEditModalComponent
  ],
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
  private readonly noteFacade = new WorkerBoardNoteFacade({
    workerApi: this.workerApi,
    toastService: this.toastService,
    board: this.board,
    permissions: this.permissions,
    mutationKey: this.mutationKey,
    setBoardPatch: (board) => this.setBoardPatch(board),
    loadBoard: () => this.loadBoard(),
    errorMessage: (err, fallback) => this.errorMessage(err, fallback)
  });
  readonly editingOrderNoteId = this.noteFacade.editingOrderNoteId;
  readonly savedOrderNoteId = this.noteFacade.savedOrderNoteId;
  readonly expandedOrderNoteIds = this.noteFacade.expandedOrderNoteIds;
  readonly orderNoteDrafts = this.noteFacade.orderNoteDrafts;
  readonly editingReviewFieldKey = this.noteFacade.editingReviewFieldKey;
  readonly reviewFieldDrafts = this.noteFacade.reviewFieldDrafts;
  readonly savedReviewFieldKey = this.noteFacade.savedReviewFieldKey;
  readonly expandedReviewTextIds = this.noteFacade.expandedReviewTextIds;
  readonly editingReviewNoteId = this.noteFacade.editingReviewNoteId;
  readonly reviewNoteDrafts = this.noteFacade.reviewNoteDrafts;
  readonly savedReviewNoteId = this.noteFacade.savedReviewNoteId;
  readonly editingSideNoteKey = this.noteFacade.editingSideNoteKey;
  readonly sideNoteDrafts = this.noteFacade.sideNoteDrafts;
  readonly savedSideNoteKey = this.noteFacade.savedSideNoteKey;

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

    this.managerApi.updateOrderReview(review.orderId, review.id, draft).subscribe({
      next: (details) => {
        this.reviewEditDetails.set(details);
        this.noteFacade.clearReviewEditDrafts(review.id);
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

  uploadReviewPhoto(file: File): void {
    const review = this.editReview();

    if (!review) {
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

        this.toastService.success('Фото загружено', `Отзыв #${review.id} обновлен`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить фото');
        this.reviewEditError.set(message);
        this.reviewEditUploading.set(false);
        this.toastService.error('Фото не загружено', message);
      }
    });
  }

  startOrderNoteEdit(order: OrderCardItem): void {
    this.noteFacade.startOrderNoteEdit(order);
  }

  cancelOrderNoteEdit(order: OrderCardItem): void {
    this.noteFacade.cancelOrderNoteEdit(order);
  }

  saveOrderNote(order: OrderCardItem): void {
    this.noteFacade.saveOrderNote(order);
  }

  saveOrderCompanyNote(order: OrderCardItem, value: string): void {
    this.noteFacade.saveOrderCompanyNote(order, value);
  }

  startReviewFieldEdit(review: WorkerReviewItem, field: ReviewEditableField): void {
    this.noteFacade.startReviewFieldEdit(review, field);
  }

  setReviewFieldDraft(review: WorkerReviewItem, field: ReviewEditableField, value: string): void {
    this.noteFacade.setReviewFieldDraft(review, field, value);
  }

  cancelReviewFieldEdit(review: WorkerReviewItem, field: ReviewEditableField): void {
    this.noteFacade.cancelReviewFieldEdit(review, field);
  }

  saveReviewField(review: WorkerReviewItem, field: ReviewEditableField): void {
    this.noteFacade.saveReviewField(review, field);
  }

  reviewFieldValue(review: WorkerReviewItem, field: ReviewEditableField): string {
    return this.noteFacade.reviewFieldValue(review, field);
  }

  toggleReviewText(review: WorkerReviewItem): void {
    this.noteFacade.toggleReviewText(review);
  }

  startReviewNoteEdit(review: WorkerReviewItem): void {
    this.noteFacade.startReviewNoteEdit(review);
  }

  setReviewNoteDraft(reviewId: number, value: string): void {
    this.noteFacade.setReviewNoteDraft(reviewId, value);
  }

  cancelReviewNoteEdit(review: WorkerReviewItem): void {
    this.noteFacade.cancelReviewNoteEdit(review);
  }

  saveReviewNote(review: WorkerReviewItem): void {
    this.noteFacade.saveReviewNote(review);
  }

  reviewNoteValue(review: WorkerReviewItem): string {
    return this.noteFacade.reviewNoteValue(review);
  }

  startSideNoteEdit(review: WorkerReviewItem, field: SideNoteField): void {
    this.noteFacade.startSideNoteEdit(review, field);
  }

  setSideNoteDraft(review: WorkerReviewItem, field: SideNoteField, value: string): void {
    this.noteFacade.setSideNoteDraft(review, field, value);
  }

  cancelSideNoteEdit(review: WorkerReviewItem, field: SideNoteField): void {
    this.noteFacade.cancelSideNoteEdit(review, field);
  }

  saveSideNote(review: WorkerReviewItem, field: SideNoteField): void {
    this.noteFacade.saveSideNote(review, field);
  }

  sideNoteValue(review: WorkerReviewItem, field: SideNoteField): string {
    return this.noteFacade.sideNoteValue(review, field);
  }

  isBadTask(review: WorkerReviewItem): boolean {
    return !!review.badTask;
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

  editableOrderNoteText(order: OrderCardItem): string {
    return this.noteFacade.editableOrderNoteText(order);
  }

  orderNoteDraft(order: OrderCardItem): string {
    return this.noteFacade.orderNoteDraft(order);
  }

  setOrderNoteDraft(order: OrderCardItem, value: string): void {
    this.noteFacade.setOrderNoteDraft(order, value);
  }

  isOrderNoteEditing(order: OrderCardItem): boolean {
    return this.noteFacade.isOrderNoteEditing(order);
  }

  isOrderNoteSaved(order: OrderCardItem): boolean {
    return this.noteFacade.isOrderNoteSaved(order);
  }

  isOrderNoteChanged(order: OrderCardItem): boolean {
    return this.noteFacade.isOrderNoteChanged(order);
  }

  isOrderNoteExpanded(order: OrderCardItem): boolean {
    return this.noteFacade.isOrderNoteExpanded(order);
  }

  toggleOrderNote(event: Event, order: OrderCardItem): void {
    this.noteFacade.toggleOrderNote(event, order);
  }

  orderNoteMutationKey(order: OrderCardItem): string {
    return this.noteFacade.orderNoteMutationKey(order);
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

  orderEditUrl(order: OrderCardItem): string {
    return workerLegacyUrl(`/ordersCompany/ordersDetails/${order.companyId}/${order.id}`);
  }

  reviewEditUrl(review: WorkerReviewItem): string {
    return workerLegacyUrl(`/review/editReview/${review.sourceReviewId ?? review.id}`);
  }

  botEditUrl(bot: WorkerBotItem): string {
    return workerLegacyUrl(`/bots/edit/${bot.id}`);
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
