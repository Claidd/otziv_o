import { Component, HostListener, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import {
  ManagerApi,
  ManagerOverdueOrders,
  ManagerOverdueStatus,
  OrderCardItem
} from '../../core/manager.api';
import { MetricSnapshotApi } from '../../core/metric-snapshot.api';
import {
  WorkerApi,
  WorkerBoard,
  WorkerBoardSectionQuery,
  WorkerBotItem,
  WorkerMetric,
  WorkerOption,
  WorkerPage,
  WorkerCredentialPreparation,
  WorkerReviewItem,
  WorkerSection
} from '../../core/worker.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { copyTextToClipboard } from '../../shared/clipboard-copy';
import { GamificationMeCardComponent } from '../../shared/gamification-me-card.component';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { PersonalRemindersComponent } from '../../shared/personal-reminders.component';
import { phoneDigits } from '../../shared/phone-format';
import { ToastService } from '../../shared/toast.service';
import { consumeWorkerCurrentSectionOpenRequest } from '../../shared/worker-entry-navigation';
import {
  DEFAULT_WORKER_PERMISSIONS,
  EMPTY_WORKER_ORDER_PAGE,
  EMPTY_WORKER_REVIEW_PAGE,
  ReviewCopyKind,
  ReviewEditableField,
  SectionTab,
  SideNoteField,
  StatusAction,
  WorkerBoardTabKey,
  WORKER_ORDER_STATUS_ACTIONS,
  WORKER_PAGE_SIZE_OPTIONS,
  WORKER_SECTIONS,
  trackWorkerAction,
  trackWorkerBot,
  trackWorkerMetric,
  trackWorkerOrder,
  trackWorkerReview,
  trackWorkerSection,
  workerErrorMessage,
  workerOrderDetailsPath,
  workerOrderPaymentCopyText,
  workerReviewDetailsPath,
  workerOrderReviewCopyText,
  workerReviewCopyLabel,
  workerSectionLabel
} from './worker-board.config';
import { WorkerRiskComponent } from '../manager/worker-risk.component';
import { WorkerBoardActionFacade } from './worker-board-action.facade';
import { WorkerBoardEditFacade } from './worker-board-edit.facade';
import { WorkerBoardNoteFacade } from './worker-board-note.facade';
import type { WorkerOrderEditDraftChange } from './worker-order-edit-modal.component';
import { WorkerOrderEditModalComponent } from './worker-order-edit-modal.component';
import { WorkerOrderCardComponent } from './worker-order-card.component';
import type { WorkerReviewEditDraftChange } from './worker-review-edit-modal.component';
import { WorkerReviewEditModalComponent } from './worker-review-edit-modal.component';
import { WorkerReviewCardComponent } from './worker-review-card.component';

type PublishCredentialPreparation = {
  reviewId: number | null;
  botId?: number | null;
  loginAt?: number;
  passwordAt?: number;
  invalidated: Record<number, { botId?: number | null }>;
};
type StoredPublishCredentialPreparation = {
  reviewId: number;
  botId?: number | null;
  loginAt?: number;
  passwordAt?: number;
  updatedAt: number;
};
type CredentialWaitSection = 'publish' | 'nagul';

@Component({
  selector: 'app-worker-board',
  imports: [
    AdminLayoutComponent,
    FormsModule,
    GamificationMeCardComponent,
    LoadErrorCardComponent,
    PersonalRemindersComponent,
    WorkerOrderCardComponent,
    WorkerOrderEditModalComponent,
    WorkerReviewCardComponent,
    WorkerReviewEditModalComponent,
    WorkerRiskComponent
  ],
  templateUrl: './worker-board.component.html',
  styleUrl: './worker-board.component.scss'
})
export class WorkerBoardComponent implements OnDestroy {
  private readonly workerApi = inject(WorkerApi);
  private readonly managerApi = inject(ManagerApi);
  private readonly metricSnapshotApi = inject(MetricSnapshotApi);
  private readonly toastService = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly overdueAlertStorageKeyPrefix = 'otziv-worker-overdue-alert:v2';
  private readonly activeSectionStorageKeyPrefix = 'otziv-worker-active-section:v1';
  private readonly publishCredentialPreparationStorageKeys: Record<CredentialWaitSection, string> = {
    publish: 'otziv-worker-publish-prep:v1',
    nagul: 'otziv-worker-nagul-prep:v1'
  };
  private readonly publishCredentialPreparationMaxAgeMs = 60 * 60 * 1000;
  private readonly credentialWaitSafetyBufferMs = 2_000;
  private readonly searchDelayMs = 500;
  private readonly credentialWaitMs: Record<CredentialWaitSection, number> = {
    publish: 150_000,
    nagul: 180_000
  };

  readonly sections = WORKER_SECTIONS;
  readonly orderStatusActions = WORKER_ORDER_STATUS_ACTIONS;
  readonly pageSizeOptions = WORKER_PAGE_SIZE_OPTIONS;

  readonly board = signal<WorkerBoard | null>(null);
  readonly activeSection = signal<WorkerBoardTabKey>('new');
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
  readonly overdueOrders = signal<ManagerOverdueOrders | null>(null);
  readonly overdueModalOpen = signal(false);
  readonly selectedWorkerId = signal<number | null>(null);
  readonly publishCredentialPreparation = signal<PublishCredentialPreparation>({
    reviewId: null,
    invalidated: {}
  });
  readonly publishCredentialWaitNow = signal(Date.now());
  private boardNoticeTimer: number | null = null;
  private searchTimer: number | null = null;
  private publishCredentialWaitTimer: number | null = null;

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
  readonly visibleSections = computed(() => this.sections.filter((section) => this.shouldShowWorkerSection(section.key)));
  readonly visibleMetrics = computed(() => this.metrics().filter((metric) => this.shouldShowWorkerSection(metric.section, metric.value)));
  readonly workerOptions = computed(() => this.board()?.workerOptions ?? []);
  readonly workerFilterAvailable = computed(() => {
    this.auth.tokenParsed();
    return (this.board()?.workerFilterAvailable ?? false)
      && this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER'])
      && this.workerOptions().length > 0;
  });
  readonly selectedWorkerLabel = computed(() => {
    const selectedId = this.selectedWorkerId();
    if (!selectedId) {
      return 'Все работники';
    }

    return this.workerOptions().find((worker) => worker.id === selectedId)?.label ?? 'Все работники';
  });
  readonly permissions = computed(() => this.board()?.permissions ?? DEFAULT_WORKER_PERMISSIONS);
  readonly showReviewFooterCity = computed(() => {
    return this.isOnlyWorkerRole();
  });
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
    errorMessage: (err, fallback) => this.errorMessage(err, fallback),
    reviewActionSource: () => this.workerActivitySource(),
    onReviewPublished: (reviewId) => this.clearStoredPublishCredentialPreparation(reviewId),
    onReviewNagul: (reviewId) => this.clearStoredPublishCredentialPreparation(reviewId)
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
  readonly reviewEditNewAccountSaving = this.editFacade.reviewEditNewAccountSaving;
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
    this.restoreStoredPublishCredentialPreparation();
    this.loadInitialBoard();
    this.loadDailyOverdueReminder();
  }

  ngOnDestroy(): void {
    this.clearSearchTimer();
    this.clearBoardNoticeTimer();
    this.clearPublishCredentialWaitTimer();
  }

  @HostListener('window:keydown.escape')
  closeModalsFromKeyboard(): void {
    this.closeOrderEdit();
    this.closeReviewEdit();
  }

  loadBoard(section: WorkerBoardSectionQuery = this.boardSectionForLoad()): void {
    this.loading.set(true);
    this.error.set(null);
    this.hideBoardNotice();

    this.workerApi.getBoard({
      section,
      keyword: this.keyword(),
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize(),
      sortDirection: this.sortDirection(),
      workerId: this.selectedWorkerId()
    }).subscribe({
      next: (board) => {
        this.board.set(board);
        this.selectedWorkerId.set(board.selectedWorkerId ?? null);
        this.loading.set(false);

        if (!this.isRiskSection() && board.section !== this.activeSection()) {
          this.activeSection.set(board.section);
          this.pageNumber.set(board.reviews.number || board.orders.number || 0);
        }
        this.storeActiveSection(this.activeSection());
        this.applyServerCredentialPreparation(board.credentialPreparation);
        this.refreshPublishCredentialWaitTimer();

        if (board.message) {
          this.showBoardNotice();
          const title = board.warning ? 'Раздел закрыт' : 'Специалист';
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

  setSection(section: WorkerBoardTabKey): void {
    const metric = this.findMetric(section);
    this.activeSection.set(section);
    this.storeActiveSection(section);
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    if (this.isRiskSection(section)) {
      this.loadBoard('all');
      return;
    }
    this.loadBoardAfterMetricSeen(metric);
  }

  handleSectionMenu(section: WorkerBoardTabKey | ''): void {
    if (!section) {
      return;
    }

    this.setSection(section);
  }

  handleActiveShellLink(active: string): void {
    if (active !== 'worker') {
      return;
    }

    this.loadBoard();
  }

  search(): void {
    this.clearSearchTimer();
    this.pageNumber.set(0);
    this.loadBoard();
  }

  onKeywordChange(value: string): void {
    this.keyword.set(value);
    this.scheduleSearch();
  }

  clearSearch(): void {
    this.keyword.set('');
    this.search();
  }

  private scheduleSearch(): void {
    this.clearSearchTimer();
    this.searchTimer = window.setTimeout(() => {
      this.searchTimer = null;
      this.search();
    }, this.searchDelayMs);
  }

  private clearSearchTimer(): void {
    if (this.searchTimer === null) {
      return;
    }
    window.clearTimeout(this.searchTimer);
    this.searchTimer = null;
  }

  changePageSize(value: string | number): void {
    this.pageSize.set(Number(value));
    this.pageNumber.set(0);
    this.loadBoard();
  }

  changeWorkerFilter(value: string | number | null): void {
    const workerId = value === null || value === '' ? null : Number(value);
    this.selectedWorkerId.set(Number.isFinite(workerId as number) && (workerId as number) > 0 ? workerId as number : null);
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

  toggleOrderClientWaiting(order: OrderCardItem): void {
    this.actionFacade.toggleOrderClientWaiting(order);
  }

  closeOverdueModal(): void {
    this.overdueModalOpen.set(false);
  }

  openOverdueStatus(status: string): void {
    const section = this.workerSectionForOrderStatus(status);
    this.closeOverdueModal();
    this.activeSection.set(section);
    this.storeActiveSection(section);
    this.keyword.set('');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.loadBoardAfterMetricSeen(this.findMetric(section));
  }

  changeReviewBot(review: WorkerReviewItem): void {
    this.actionFacade.changeReviewBot(review);
  }

  deactivateReviewBot(review: WorkerReviewItem): void {
    this.actionFacade.deactivateReviewBot(review);
  }

  markReviewDone(review: WorkerReviewItem): void {
    if (this.publishLockedByCredentialWait(review)) {
      this.toastService.info(this.activeWorkerSection() === 'nagul' ? 'Выгул подождет' : 'Публикация подождет', this.publishCredentialWaitTitle(review));
      return;
    }

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

  assignReviewNewAccount(): void {
    this.editFacade.assignReviewNewAccount();
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
      await this.copyText(
        workerOrderReviewCopyText(order, this.board()?.promoTexts ?? []),
        `check-${order.id}`,
        'Текст проверки скопирован'
      );
      return;
    }

    const sum = order.totalSumWithBadReviews ?? order.sum ?? 0;
    await this.copyText(
      workerOrderPaymentCopyText(order, sum),
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

    if (!(await this.copyText(value, `${kind}-${review.id}`, `${workerReviewCopyLabel(kind)} скопирован`))) {
      return;
    }

    if (kind === 'login' || kind === 'password') {
      if (!(await this.logReviewCredentialCopyClick(review, kind))) {
        return;
      }
      this.markPublishCredentialCopied(review, kind);
    }
  }

  async copyReviewTitle(review: WorkerReviewItem, title: string): Promise<void> {
    await this.copyText(title, `title-${review.id}`, 'Название скопировано');
  }

  private async logReviewCredentialCopyClick(review: WorkerReviewItem, kind: ReviewCopyKind): Promise<boolean> {
    if (kind !== 'login' && kind !== 'password') {
      return true;
    }

    try {
      await firstValueFrom(this.workerApi.logReviewCopyClick(review.id, kind, this.workerActivitySource()));
      return true;
    } catch {
      this.toastService.error(
        'Копирование не записано',
        'Данные попали в буфер, но сервер не подтвердил действие. Нажмите кнопку еще раз.'
      );
      return false;
    }
  }

  private markPublishCredentialCopied(review: WorkerReviewItem, kind: ReviewCopyKind): void {
    if (kind !== 'login' && kind !== 'password') {
      return;
    }

    if (!this.shouldUsePublishCredentialWait()) {
      return;
    }

    this.publishCredentialPreparation.update((current) => {
      const botId = review.botId ?? null;
      const sameReviewBot = current.reviewId === review.id && current.botId === botId;
      const invalidated = { ...current.invalidated };

      if (current.reviewId !== null && !sameReviewBot) {
        invalidated[current.reviewId] = { botId: current.botId ?? null };
      }
      delete invalidated[review.id];

      const base = sameReviewBot
        ? current
        : { reviewId: review.id, botId };

      return {
        ...base,
        invalidated,
        [kind === 'login' ? 'loginAt' : 'passwordAt']: Date.now()
      };
    });
    this.refreshPublishCredentialWaitTimer();
    this.storePublishCredentialPreparation();
  }

  publishCredentialWaitLeftSeconds(review: WorkerReviewItem): number {
    if (!this.shouldUsePublishCredentialWait() || this.isPublishedCredentialWaitDone(review)) {
      return 0;
    }

    const preparation = this.publishCredentialPreparation();
    if (
      preparation.reviewId !== review.id ||
      preparation.botId !== (review.botId ?? null) ||
      !preparation.loginAt ||
      !preparation.passwordAt
    ) {
      return 0;
    }

    const readyAt = Math.max(preparation.loginAt, preparation.passwordAt)
      + this.activeCredentialWaitMs()
      + this.credentialWaitSafetyBufferMs;
    return Math.max(0, Math.ceil((readyAt - this.publishCredentialWaitNow()) / 1000));
  }

  publishLockedByCredentialWait(review: WorkerReviewItem): boolean {
    if (!this.shouldUsePublishCredentialWait() || this.isPublishedCredentialWaitDone(review)) {
      return false;
    }

    const preparation = this.publishCredentialPreparation();
    const botId = review.botId ?? null;
    const invalidated = preparation.invalidated[review.id];
    if (invalidated && invalidated.botId === botId) {
      return true;
    }

    if (preparation.reviewId === review.id && preparation.botId === botId) {
      return !preparation.loginAt || !preparation.passwordAt || this.publishCredentialWaitLeftSeconds(review) > 0;
    }

    return true;
  }

  publishCredentialWaitTitle(review: WorkerReviewItem): string {
    const preparation = this.publishCredentialPreparation();
    const botId = review.botId ?? null;
    const invalidated = preparation.invalidated[review.id];
    if (invalidated && invalidated.botId === botId) {
      return 'Подготовка сброшена: снова скопируйте логин и пароль.';
    }

    if (preparation.reviewId !== review.id || preparation.botId !== botId) {
      return 'Сначала скопируйте логин и пароль аккаунта.';
    }

    if (!preparation.loginAt || !preparation.passwordAt) {
      const missing = [
        !preparation.loginAt ? 'логин' : '',
        !preparation.passwordAt ? 'пароль' : ''
      ].filter(Boolean).join(', ');
      return `Скопируйте ${missing}.`;
    }

    const left = this.publishCredentialWaitLeftSeconds(review);
    if (left <= 0) {
      return 'Действие с отзывом';
    }

    return `После копирования логина и пароля подождите еще ${left} сек.`;
  }

  private shouldUsePublishCredentialWait(): boolean {
    return this.activeCredentialWaitSection() !== null;
  }

  private activeCredentialWaitSection(): CredentialWaitSection | null {
    if (!this.isOnlyWorkerRole()) {
      return null;
    }

    const section = this.activeWorkerSection();
    return section === 'publish' || section === 'nagul' ? section : null;
  }

  private activeCredentialWaitMs(): number {
    const section = this.activeCredentialWaitSection();
    return section ? this.credentialWaitMs[section] : 0;
  }

  private isPublishedCredentialWaitDone(review: WorkerReviewItem): boolean {
    return this.activeCredentialWaitSection() === 'publish' && Boolean(review.publish);
  }

  private currentCredentialPreparationStorageKey(): string | null {
    const section = this.activeCredentialWaitSection();
    return section ? this.publishCredentialPreparationStorageKeys[section] : null;
  }

  private refreshPublishCredentialWaitTimer(): void {
    this.publishCredentialWaitNow.set(Date.now());
    if (!this.hasActivePublishCredentialWait()) {
      this.clearPublishCredentialWaitTimer();
      return;
    }

    if (this.publishCredentialWaitTimer !== null) {
      return;
    }

    this.publishCredentialWaitTimer = window.setInterval(() => {
      this.publishCredentialWaitNow.set(Date.now());
      if (!this.hasActivePublishCredentialWait()) {
        this.clearPublishCredentialWaitTimer();
      }
    }, 1000);
  }

  private hasActivePublishCredentialWait(): boolean {
    if (!this.shouldUsePublishCredentialWait()) {
      return false;
    }

    return this.currentReviews().some((review) => this.publishCredentialWaitLeftSeconds(review) > 0);
  }

  private clearPublishCredentialWaitTimer(): void {
    if (this.publishCredentialWaitTimer === null) {
      return;
    }

    window.clearInterval(this.publishCredentialWaitTimer);
    this.publishCredentialWaitTimer = null;
  }

  private restoreStoredPublishCredentialPreparation(): void {
    const stored = this.readStoredPublishCredentialPreparation();
    if (!stored) {
      this.publishCredentialPreparation.set({
        reviewId: null,
        invalidated: {}
      });
      return;
    }

    this.publishCredentialPreparation.set({
      reviewId: stored.reviewId,
      botId: stored.botId ?? null,
      loginAt: stored.loginAt,
      passwordAt: stored.passwordAt,
      invalidated: {}
    });
    this.refreshPublishCredentialWaitTimer();
  }

  private applyServerCredentialPreparation(preparation?: WorkerCredentialPreparation | null): void {
    if (!this.shouldUsePublishCredentialWait() || !preparation) {
      this.clearStoredPublishCredentialPreparation();
      return;
    }

    const section = this.activeCredentialWaitSection();
    const expectedScope = section === 'nagul' ? 'NAGUL' : 'PUBLISH';
    if ((preparation.scope ?? '').toUpperCase() !== expectedScope) {
      this.clearStoredPublishCredentialPreparation();
      return;
    }

    const reviewId = Number(preparation.reviewId);
    const botId = preparation.botId === null || preparation.botId === undefined ? null : Number(preparation.botId);
    if (!Number.isFinite(reviewId) || reviewId <= 0 || (botId !== null && !Number.isFinite(botId))) {
      this.clearStoredPublishCredentialPreparation();
      return;
    }

    this.publishCredentialPreparation.set({
      reviewId,
      botId,
      loginAt: this.serverTimestamp(preparation.loginCopiedAt),
      passwordAt: this.serverTimestamp(preparation.passwordCopiedAt),
      invalidated: {}
    });
    this.storePublishCredentialPreparation();
  }

  private serverTimestamp(value?: string | null): number | undefined {
    const timestamp = value ? Date.parse(value) : NaN;
    return Number.isFinite(timestamp) ? timestamp : undefined;
  }

  private storePublishCredentialPreparation(): void {
    const storageKey = this.currentCredentialPreparationStorageKey();
    if (!storageKey) {
      return;
    }

    const preparation = this.publishCredentialPreparation();
    if (preparation.reviewId === null) {
      this.removeSessionStorageItem(storageKey);
      return;
    }

    this.setSessionStorageItem(storageKey, JSON.stringify({
      reviewId: preparation.reviewId,
      botId: preparation.botId ?? null,
      loginAt: preparation.loginAt,
      passwordAt: preparation.passwordAt,
      updatedAt: Date.now()
    } satisfies StoredPublishCredentialPreparation));
  }

  private clearStoredPublishCredentialPreparation(reviewId?: number): void {
    const storageKey = this.currentCredentialPreparationStorageKey();
    const current = this.publishCredentialPreparation();
    if (reviewId === undefined || current.reviewId === reviewId) {
      this.publishCredentialPreparation.set({ reviewId: null, invalidated: {} });
      this.clearPublishCredentialWaitTimer();
    }

    const stored = this.readStoredPublishCredentialPreparation(false);
    if (reviewId === undefined || stored?.reviewId === reviewId) {
      if (storageKey) {
        this.removeSessionStorageItem(storageKey);
      }
    }
  }

  private readStoredPublishCredentialPreparation(clearExpired = true): StoredPublishCredentialPreparation | null {
    const storageKey = this.currentCredentialPreparationStorageKey();
    if (!storageKey) {
      return null;
    }

    const raw = this.getSessionStorageItem(storageKey);
    if (!raw) {
      return null;
    }

    try {
      const value = JSON.parse(raw) as Partial<StoredPublishCredentialPreparation>;
      const reviewId = Number(value.reviewId);
      const updatedAt = Number(value.updatedAt);
      const loginAt = value.loginAt === undefined ? undefined : Number(value.loginAt);
      const passwordAt = value.passwordAt === undefined ? undefined : Number(value.passwordAt);
      if (
        !Number.isFinite(reviewId) ||
        reviewId <= 0 ||
        !Number.isFinite(updatedAt) ||
        (loginAt !== undefined && !Number.isFinite(loginAt)) ||
        (passwordAt !== undefined && !Number.isFinite(passwordAt))
      ) {
        this.removeSessionStorageItem(storageKey);
        return null;
      }

      if (clearExpired && Date.now() - updatedAt > this.publishCredentialPreparationMaxAgeMs) {
        this.removeSessionStorageItem(storageKey);
        return null;
      }

      const botId = value.botId === null || value.botId === undefined ? null : Number(value.botId);
      return {
        reviewId,
        botId: Number.isFinite(botId) ? botId : null,
        loginAt,
        passwordAt,
        updatedAt
      };
    } catch {
      this.removeSessionStorageItem(storageKey);
      return null;
    }
  }

  private getSessionStorageItem(key: string): string | null {
    try {
      return window.sessionStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private setSessionStorageItem(key: string, value: string): void {
    try {
      window.sessionStorage.setItem(key, value);
    } catch {
      // В приватном режиме браузер может запретить sessionStorage; рабочая логика останется в памяти страницы.
    }
  }

  private removeSessionStorageItem(key: string): void {
    try {
      window.sessionStorage.removeItem(key);
    } catch {
      // Игнорируем: это только восстановление UI-состояния.
    }
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

  isRiskSection(section = this.activeSection()): boolean {
    return section === 'risk';
  }

  activeWorkerSection(): WorkerSection {
    return this.isRiskSection() ? 'all' : this.activeSection() as WorkerSection;
  }

  isOrderSection(section = this.activeSection()): boolean {
    return section === 'new' || section === 'correct' || section === 'all';
  }

  isReviewSection(section = this.activeSection()): boolean {
    return section === 'nagul' || section === 'recovery' || section === 'publish' || section === 'bad';
  }

  canOpenOrderEditModal(): boolean {
    return true;
  }

  canOpenReviewEditModal(): boolean {
    return this.permissions().canWorkReviews;
  }

  canOpenReviewTitleLink(): boolean {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
  }

  isOnlyWorkerRole(): boolean {
    this.auth.tokenParsed();
    return this.auth.hasRealmRole('WORKER') && !this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
  }

  canOnlyUnsetReviewVigul(): boolean {
    return this.editFacade.canOnlyUnsetReviewVigul();
  }

  orderEditUrl(order: OrderCardItem): string {
    return workerOrderDetailsPath(order);
  }

  reviewEditUrl(review: WorkerReviewItem): string {
    return workerReviewDetailsPath(review);
  }

  botEditUrl(bot: WorkerBotItem): string {
    return `/admin/dictionaries?botId=${bot.id}`;
  }

  trackSection(_index: number, section: SectionTab): WorkerBoardTabKey {
    return trackWorkerSection(_index, section);
  }

  sectionOptionLabel(section: SectionTab): string {
    if (section.key === 'risk') {
      return section.label;
    }

    const metric = this.findMetric(section.key);
    const label = metric ? `${section.label}: ${metric.value}` : section.label;
    return (metric?.delta ?? 0) > 0 ? `${label} +${metric?.delta}` : label;
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

  trackWorkerOption(_index: number, option: WorkerOption): number {
    return option.id;
  }

  trackAction(_index: number, action: StatusAction): string {
    return trackWorkerAction(_index, action);
  }

  trackOverdueStatus(_index: number, status: ManagerOverdueStatus): string {
    return status.status;
  }

  overdueMaxDays(summary: ManagerOverdueOrders): number {
    return summary.statuses.reduce((max, status) => Math.max(max, status.maxDays), 0);
  }

  private findMetric(section: WorkerBoardTabKey): WorkerMetric | undefined {
    if (section === 'risk') {
      return undefined;
    }

    return this.board()?.metrics.find((item) => item.section === section);
  }

  private shouldShowWorkerSection(section: WorkerBoardTabKey, metricValue = this.findMetric(section)?.value ?? 0): boolean {
    if (section === 'risk') {
      return this.canSeeRiskTab();
    }

    if (section !== 'recovery' && section !== 'bad') {
      return true;
    }

    return metricValue > 0 || this.activeSection() === section;
  }

  private loadInitialBoard(): void {
    if (this.route.snapshot.data['workerTab'] === 'risk' && this.canSeeRiskTab()) {
      this.activeSection.set('risk');
      this.loadBoard('all');
      return;
    }

    if (consumeWorkerCurrentSectionOpenRequest()) {
      this.openCurrentWorkSection();
      return;
    }

    const querySection = this.normalizeStoredSection(this.route.snapshot.queryParamMap.get('section'));
    if (querySection === 'risk' && this.canSeeRiskTab()) {
      this.activeSection.set('risk');
      this.loadBoard('all');
      return;
    }
    if (querySection !== 'new' || this.route.snapshot.queryParamMap.has('section')) {
      this.activeSection.set(querySection);
      this.loadBoard(this.boardSectionForLoad());
      return;
    }

    const storedSection = this.readStoredActiveSection();
    this.activeSection.set(storedSection);
    this.loadBoard(this.boardSectionForLoad());
  }

  private openCurrentWorkSection(): void {
    this.keyword.set('');
    this.activeSection.set('new');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.loadBoard('current');
  }

  private loadBoardAfterMetricSeen(metric?: WorkerMetric): void {
    if (!metric || this.selectedWorkerId()) {
      this.loadBoard();
      return;
    }

    this.metricSnapshotApi.markSeen({
      page: 'worker',
      section: metric.section,
      status: metric.section,
      value: metric.value
    }).subscribe({
      next: () => {
        this.clearMetricDelta(metric);
        this.loadBoard();
      },
      error: () => this.loadBoard()
    });
  }

  private clearMetricDelta(metric: WorkerMetric): void {
    this.patchBoard((board) => ({
      ...board,
      metrics: board.metrics.map((item) => item.section === metric.section
        ? { ...item, delta: 0 }
        : item
      )
    }));
  }

  private setBoardPatch(board: WorkerBoard | null): void {
    if (board) {
      this.board.set(board);
    }
  }

  private patchBoard(updater: (board: WorkerBoard) => WorkerBoard): void {
    this.board.update((board) => board ? updater(board) : board);
  }

  private async copyText(text: string, copiedKey: string, toast: string): Promise<boolean> {
    const value = text.trim();

    if (!value) {
      return false;
    }

    if (await copyTextToClipboard(value)) {
      this.copied.set(copiedKey);
      this.toastService.success('Скопировано', toast);
      window.setTimeout(() => {
        if (this.copied() === copiedKey) {
          this.copied.set(null);
        }
      }, 1200);
      return true;
    }

    this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
    return false;
  }

  private workerActivitySource(): { sourcePage: string; sourceSection: string } {
    return {
      sourcePage: 'worker-board',
      sourceSection: this.activeWorkerSection()
    };
  }

  private errorMessage(err: unknown, fallback: string): string {
    return workerErrorMessage(err, fallback);
  }

  private loadDailyOverdueReminder(): void {
    const today = this.localDateKey();
    const storageKey = this.overdueAlertStorageKey();

    if (this.readStoredDate(storageKey) === today) {
      return;
    }

    this.workerApi.getOverdueOrders().subscribe({
      next: (summary) => {
        this.writeStoredDate(storageKey, today);
        const normalizedSummary = {
          ...summary,
          statuses: summary.statuses ?? []
        };

        if (normalizedSummary.total > 0) {
          this.overdueOrders.set(normalizedSummary);
          this.overdueModalOpen.set(true);
        }
      },
      error: () => {
        // The reminder is helpful, but the board itself should not fail because of it.
      }
    });
  }

  private overdueAlertStorageKey(): string {
    const token = this.auth.tokenParsed() as { preferred_username?: string; sub?: string } | undefined;
    const userKey = token?.preferred_username || token?.sub || 'user';
    return `${this.overdueAlertStorageKeyPrefix}:${userKey}`;
  }

  private activeSectionStorageKey(): string {
    const token = this.auth.tokenParsed() as { preferred_username?: string; sub?: string } | undefined;
    const userKey = token?.preferred_username || token?.sub || 'user';
    return `${this.activeSectionStorageKeyPrefix}:${userKey}`;
  }

  private readStoredActiveSection(): WorkerBoardTabKey {
    return this.normalizeStoredSection(this.readSessionValue(this.activeSectionStorageKey()));
  }

  private storeActiveSection(section: WorkerBoardTabKey): void {
    this.writeSessionValue(this.activeSectionStorageKey(), section);
  }

  private normalizeStoredSection(section: string | null): WorkerBoardTabKey {
    if (section === 'risk') {
      return this.canSeeRiskTab() ? 'risk' : 'new';
    }

    return this.sections.some((item) => item.key === section && item.key !== 'risk')
      ? section as WorkerSection
      : 'new';
  }

  private boardSectionForLoad(): WorkerBoardSectionQuery {
    return this.isRiskSection() ? 'all' : this.activeSection() as WorkerSection;
  }

  private canSeeRiskTab(): boolean {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
  }

  private localDateKey(date = new Date()): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  private readStoredDate(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private writeStoredDate(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      // Storage can be blocked in private mode; the reminder will simply try again later.
    }
  }

  private readSessionValue(key: string): string | null {
    try {
      return sessionStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private writeSessionValue(key: string, value: string): void {
    try {
      sessionStorage.setItem(key, value);
    } catch {
      // If session storage is blocked, the current in-memory tab still keeps its section.
    }
  }

  private workerSectionForOrderStatus(status: string): WorkerSection {
    switch (status) {
      case 'Новый':
        return 'new';
      case 'Коррекция':
        return 'correct';
      default:
        return 'all';
    }
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
