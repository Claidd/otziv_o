import { Component, HostListener, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ManagerApi, OrderCardItem } from '../../core/manager.api';
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
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { phoneDigits } from '../../shared/phone-format';
import { ToastService } from '../../shared/toast.service';
import {
  DEFAULT_WORKER_PERMISSIONS,
  EMPTY_WORKER_ORDER_PAGE,
  EMPTY_WORKER_REVIEW_PAGE,
  ReviewCopyKind,
  ReviewEditableField,
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
  workerErrorMessage,
  workerLegacyUrl,
  workerReviewCopyLabel,
  workerSectionLabel
} from './worker-board.config';
import { WorkerBoardActionFacade } from './worker-board-action.facade';
import { WorkerBoardEditFacade } from './worker-board-edit.facade';
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
    LoadErrorCardComponent,
    WorkerOrderCardComponent,
    WorkerOrderEditModalComponent,
    WorkerReviewCardComponent,
    WorkerReviewEditModalComponent
  ],
  templateUrl: './worker-board.component.html',
  styleUrl: './worker-board.component.scss'
})
export class WorkerBoardComponent implements OnDestroy {
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
  readonly boardNoticeVisible = signal(false);
  private boardNoticeTimer: number | null = null;

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
  private readonly editFacade = new WorkerBoardEditFacade({
    managerApi: this.managerApi,
    toastService: this.toastService,
    loadBoard: () => this.loadBoard(),
    board: this.board,
    setBoardPatch: (board) => this.setBoardPatch(board),
    errorMessage: (err, fallback) => this.errorMessage(err, fallback),
    clearReviewEditDrafts: (reviewId) => this.noteFacade.clearReviewEditDrafts(reviewId),
    canOpenOrderEditModal: () => this.canOpenOrderEditModal(),
    canOpenReviewEditModal: () => this.canOpenReviewEditModal(),
    orderEditUrl: (order) => this.orderEditUrl(order),
    reviewEditUrl: (review) => this.reviewEditUrl(review)
  });
  private readonly actionFacade = new WorkerBoardActionFacade({
    workerApi: this.workerApi,
    toastService: this.toastService,
    activeSection: this.activeSection,
    mutationKey: this.mutationKey,
    loadBoard: () => this.loadBoard(),
    patchBoard: (updater) => this.patchBoard(updater),
    errorMessage: (err, fallback) => this.errorMessage(err, fallback)
  });
  readonly editOrder = this.editFacade.editOrder;
  readonly orderDraft = this.editFacade.orderDraft;
  readonly orderLoading = this.editFacade.orderLoading;
  readonly orderSaving = this.editFacade.orderSaving;
  readonly orderError = this.editFacade.orderError;
  readonly orderDeleting = this.editFacade.orderDeleting;
  readonly editReview = this.editFacade.editReview;
  readonly reviewEditDetails = this.editFacade.reviewEditDetails;
  readonly reviewEditDraft = this.editFacade.reviewEditDraft;
  readonly reviewEditLoading = this.editFacade.reviewEditLoading;
  readonly reviewEditSaving = this.editFacade.reviewEditSaving;
  readonly reviewEditDeleting = this.editFacade.reviewEditDeleting;
  readonly reviewEditUploading = this.editFacade.reviewEditUploading;
  readonly reviewEditError = this.editFacade.reviewEditError;
  readonly productOptions = this.editFacade.productOptions;
  readonly reviewEditBusy = this.editFacade.reviewEditBusy;
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

  ngOnDestroy(): void {
    this.clearBoardNoticeTimer();
  }

  @HostListener('window:keydown.escape')
  closeModalsFromKeyboard(): void {
    this.closeOrderEdit();
    this.closeReviewEdit();
  }

  loadBoard(): void {
    this.loading.set(true);
    this.error.set(null);
    this.hideBoardNotice();

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
          this.showBoardNotice();
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
    this.actionFacade.updateOrderStatus(order, action);
  }

  changeReviewBot(review: WorkerReviewItem): void {
    this.actionFacade.changeReviewBot(review);
  }

  deactivateReviewBot(review: WorkerReviewItem): void {
    this.actionFacade.deactivateReviewBot(review);
  }

  markReviewDone(review: WorkerReviewItem): void {
    this.actionFacade.markReviewDone(review);
  }

  deleteBot(bot: WorkerBotItem): void {
    this.actionFacade.deleteBot(bot);
  }

  openOrderEdit(order: OrderCardItem): void {
    this.editFacade.openOrderEdit(order);
  }

  closeOrderEdit(): void {
    this.editFacade.closeOrderEdit();
  }

  handleOrderEditDraftChange(change: WorkerOrderEditDraftChange): void {
    this.editFacade.handleOrderEditDraftChange(change);
  }

  saveOrderEdit(): void {
    this.editFacade.saveOrderEdit();
  }

  deleteOrderEdit(): void {
    this.editFacade.deleteOrderEdit();
  }

  openReviewEdit(review: WorkerReviewItem): void {
    this.editFacade.openReviewEdit(review);
  }

  closeReviewEdit(): void {
    this.editFacade.closeReviewEdit();
  }

  handleReviewEditDraftChange(change: WorkerReviewEditDraftChange): void {
    this.editFacade.handleReviewEditDraftChange(change);
  }

  saveReviewEdit(): void {
    this.editFacade.saveReviewEdit();
  }

  deleteReviewEdit(): void {
    this.editFacade.deleteReviewEdit();
  }

  uploadReviewPhoto(file: File): void {
    this.editFacade.uploadReviewPhoto(file);
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
    return this.actionFacade.isBadTask(review);
  }

  reviewActionTitle(review: WorkerReviewItem): string {
    return this.actionFacade.reviewActionTitle(review);
  }

  async copyPhone(phone?: string): Promise<void> {
    await this.copyText(phoneDigits(phone), 'телефон', 'Телефон скопирован');
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

  private setBoardPatch(board: WorkerBoard | null): void {
    if (board) {
      this.board.set(board);
    }
  }

  private patchBoard(updater: (board: WorkerBoard) => WorkerBoard): void {
    this.board.update((board) => board ? updater(board) : board);
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

  private showBoardNotice(): void {
    this.clearBoardNoticeTimer();
    this.boardNoticeVisible.set(true);
    this.boardNoticeTimer = window.setTimeout(() => {
      this.boardNoticeVisible.set(false);
      this.boardNoticeTimer = null;
    }, 3000);
  }

  private hideBoardNotice(): void {
    this.clearBoardNoticeTimer();
    this.boardNoticeVisible.set(false);
  }

  private clearBoardNoticeTimer(): void {
    if (this.boardNoticeTimer === null) {
      return;
    }

    window.clearTimeout(this.boardNoticeTimer);
    this.boardNoticeTimer = null;
  }
}
