import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, HostListener, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom, Observable } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { ReputationDeepReportMonitorService } from '../../core/reputation-deep-report-monitor.service';
import type { DeepCompanyResearchJob, ReputationSingleReviewDraftResult } from '../../core/reputation-ai.api';
import {
  BadReviewTaskItem,
  CompanyDeepReportState,
  ManagerApi,
  OrderDetailsPayload,
  OrderNotesUpdate,
  OrderReviewItem,
  ReviewRecoveryBatchItem,
  ReviewRecoveryTaskItem,
  ReviewUpdateRequest,
  WorkerCredentialPreparation
} from '../../core/manager.api';
import { PaymentsApi } from '../../core/payments.api';
import type { ManagerPaymentLinkResponse, TbankPaymentStatus } from '../../core/payments.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { copyTextToClipboard } from '../../shared/clipboard-copy';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { mobileKeyboardActionBottom } from '../../shared/mobile-keyboard-action-bottom';
import {
  PersonalRemindersService,
  RecoveryClientNotifiedDetail
} from '../../shared/personal-reminders.service';
import { reviewCheckPath } from '../../shared/order-review-copy-text';
import { sortReviewsById } from '../../shared/review-ordering';
import {
  readSessionDraft,
  removeSessionDraft,
  writeSessionDraft
} from '../../shared/session-draft-storage';
import { DeepResearchReportViewComponent } from '../../shared/reputation/deep-research-report-view.component';
import { deepReportErrorMessage } from '../../shared/reputation/deep-report-error-message';
import { ToastService } from '../../shared/toast.service';
import {
  orderDetailsSendToCheckActionLabel,
  orderDetailsSendToCheckBusyLabel,
  orderDetailsSendToCheckSuccessDetail,
  orderDetailsSendToCheckTargetStatus
} from './order-details-status';

type ReviewCopyKind = 'filialUrl' | 'botLogin' | 'botPassword' | 'text' | 'answer';
type BadReviewTaskCopyKind = 'botLogin' | 'botPassword';
type ReviewEditableField = 'text' | 'answer';
type SideNoteField = 'order' | 'company';
type ReviewQuickFilter = 'all' | 'unpublished' | 'missing-photo' | 'with-note';
type ReviewEditDraft = ReviewUpdateRequest;
type ReviewCredentialCopyState = { botId?: number | null; botLoginAt?: number; botPasswordAt?: number };
type StoredReviewCredentialCopyState = ReviewCredentialCopyState & { reviewId: number; updatedAt: number };
type RecoveryTaskDraft = {
  recoveryText: string;
  scheduledDate: string | null;
};
type BadReviewTaskDraft = {
  taskText: string;
  scheduledDate: string | null;
};
type OrderDetailsSessionDraft = {
  reviewFields?: Record<string, string>;
  reviewNotes?: Record<number, string>;
  sideNotes?: Partial<Record<SideNoteField, string>>;
  reviewEdit?: {
    reviewId: number;
    draft: ReviewEditDraft;
  };
};
type ActiveOrderReviewFieldEdit = {
  review: OrderReviewItem;
  field: ReviewEditableField;
  title: string;
  mutationKey: string;
};
const HIDDEN_PUBLISH_ORDER_STATUSES = new Set(['Новый', 'На проверке', 'В проверку', 'В прверку', 'Коррекция']);
const REVIEW_HELP_ORDER_STATUSES = new Set(['новый']);
const PLACEHOLDER_REVIEW_TEXT = 'текст отзыва';
const REVIEW_PUBLICATION_MAX_FUTURE_DAYS = 90;
const REVIEW_PUBLICATION_MAX_GAP_DAYS = 30;

function localDateInputValue(daysFromToday = 0): string {
  const date = new Date();
  date.setDate(date.getDate() + daysFromToday);
  return formatDateInputValue(date);
}

function formatDateInputValue(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

@Component({
  selector: 'app-order-details',
  imports: [AdminLayoutComponent, DecimalPipe, DeepResearchReportViewComponent, FormsModule, LoadErrorCardComponent, RouterLink],
  templateUrl: './order-details.component.html',
  styleUrl: './order-details.component.scss'
})
export class OrderDetailsComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly auth = inject(AuthService);
  private readonly managerApi = inject(ManagerApi);
  private readonly paymentsApi = inject(PaymentsApi);
  private readonly deepReportMonitor = inject(ReputationDeepReportMonitorService);
  private readonly toastService = inject(ToastService);
  private readonly remindersService = inject(PersonalRemindersService);
  private readonly publishCredentialWaitMs = 150_000;
  private readonly publishCredentialWaitSafetyBufferMs = 2_000;
  private readonly reviewPublishCredentialStorageKey = 'otziv-order-details-worker-all-publish-prep:v1';
  private readonly reviewPublishCredentialMaxAgeMs = 60 * 60 * 1000;
  private readonly reviewFieldDraftToastIds = new Map<string, number>();
  private restoredReviewFieldsToastId: number | null = null;
  private publishCredentialWaitTimer: number | null = null;

  readonly orderId = signal<number | null>(null);
  readonly details = signal<OrderDetailsPayload | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly copied = signal<string | null>(null);
  readonly editingReviewFieldKey = signal<string | null>(null);
  readonly reviewFieldDrafts = signal<Record<string, string>>({});
  readonly savedReviewFieldKey = signal<string | null>(null);
  readonly expandedReviewTextIds = signal<Record<number, boolean>>({});
  readonly mobilePreviewReviewTextId = signal<number | null>(null);
  readonly editingReviewNoteId = signal<number | null>(null);
  readonly reviewNoteDrafts = signal<Record<number, string>>({});
  readonly copiedReviewCredentials = signal<Record<number, ReviewCredentialCopyState>>({});
  readonly reviewPublishWaitNow = signal(Date.now());
  readonly savedReviewNoteId = signal<number | null>(null);
  readonly openReviewNotePopoverId = signal<number | null>(null);
  readonly editingSideNoteField = signal<SideNoteField | null>(null);
  readonly sideNoteDrafts = signal<Partial<Record<SideNoteField, string>>>({});
  readonly savedSideNoteField = signal<SideNoteField | null>(null);
  readonly badReviewTaskDrafts = signal<Record<number, BadReviewTaskDraft>>({});
  readonly savedBadReviewTaskId = signal<number | null>(null);
  readonly recoveryTaskDrafts = signal<Record<number, RecoveryTaskDraft>>({});
  readonly savedRecoveryTaskId = signal<number | null>(null);
  readonly activeReviewSlide = signal(0);
  readonly reviewJumpValue = signal('1');
  readonly reviewQuickFilter = signal<ReviewQuickFilter>('all');
  readonly mobileReviewLayout = signal(false);
  readonly editReview = signal<OrderReviewItem | null>(null);
  readonly reviewEditDraft = signal<ReviewEditDraft | null>(null);
  readonly reviewEditSaving = signal(false);
  readonly reviewEditDeleting = signal(false);
  readonly reviewEditUploading = signal(false);
  readonly reviewEditNewAccountSaving = signal(false);
  readonly reviewEditError = signal<string | null>(null);
  readonly companyReportState = signal<CompanyDeepReportState | null>(null);
  readonly companyReportVisible = signal(false);
  readonly companyReportLoading = signal(false);
  readonly companyReportError = signal<string | null>(null);
  readonly openedFromWorkerAll = signal(false);
  readonly tbankStatus = signal<TbankPaymentStatus | null>(null);
  readonly paymentLink = signal<ManagerPaymentLinkResponse | null>(null);
  readonly mobileReviewActionBottom = mobileKeyboardActionBottom(this.destroyRef);
  readonly browserOnline = signal(this.readBrowserOnline());
  readonly reviewPublicationGlobalDateMax = localDateInputValue(REVIEW_PUBLICATION_MAX_FUTURE_DAYS);

  readonly layoutTitle = computed(() => this.details()?.title || 'Детали заказа');
  readonly reviews = computed(() => sortReviewsById(this.details()?.reviews));
  readonly visibleReviews = computed(() => this.reviews());
  readonly showReviewFastSelect = computed(() => this.reviews().length > 20);
  readonly showReviewNavigation = computed(() => this.mobileReviewLayout() && this.reviews().length > 1);
  readonly reviewQuickFilterIndexes = computed(() => this.reviews()
    .map((review, index) => ({ review, index }))
    .filter(({ review }) => this.reviewMatchesQuickFilter(review, this.reviewQuickFilter()))
    .map(({ index }) => index));
  readonly productOptions = computed(() => this.details()?.products ?? []);
  readonly reviewFilialOptions = computed(() => this.details()?.filials ?? []);
  readonly badReviewTasks = computed(() => [...(this.details()?.badReviewTasks ?? [])]
    .sort((left, right) => (left.id ?? 0) - (right.id ?? 0)));
  readonly recoveryTasks = computed(() => [...(this.details()?.recoveryTasks ?? [])]
    .filter((task) => task.statusCode !== 'CANCELLED')
    .sort((left, right) => (left.id ?? 0) - (right.id ?? 0)));
  readonly recoveryBatchesToNotify = computed(() => {
    const batches = new Map<number, ReviewRecoveryBatchItem>();
    for (const task of this.recoveryTasks()) {
      const batch = task.batch;
      if (batch?.id && batch.statusCode === 'COMPLETED') {
        batches.set(batch.id, batch);
      }
    }
    return Array.from(batches.values());
  });
  readonly busy = computed(() => this.mutationKey() !== null);
  readonly reviewEditBusy = computed(() => this.reviewEditSaving()
    || this.reviewEditDeleting()
    || this.reviewEditUploading()
    || this.reviewEditNewAccountSaving());
  readonly companyReportJob = computed(() => this.companyReportState()?.activeJob
    ?? this.companyReportState()?.latestJob
    ?? null);
  readonly hasReadyCompanyReport = computed(() => !!this.companyReportState()?.latestJob?.report);
  readonly companyReportBusy = computed(() => this.companyReportLoading() || this.isActiveCompanyReport(this.companyReportJob()));
  readonly canShowPaymentLinkAction = computed(() => {
    const status = this.tbankStatus();
    return !!this.details()
      && !!status?.managerUiEnabled
      && !!status.paymentLinksEnabled
      && this.auth.hasRealmRole('ADMIN');
  });
  readonly activeReviewFieldEdit = computed<ActiveOrderReviewFieldEdit | null>(() => {
    const key = this.editingReviewFieldKey();
    const details = this.details();
    if (!key || !details) {
      return null;
    }

    const match = /^(\d+)-(text|answer)$/.exec(key);
    if (!match) {
      return null;
    }

    const review = this.reviews().find((item) => item.id === Number(match[1]));
    if (!review) {
      return null;
    }

    const field = match[2] as ReviewEditableField;
    return {
      review,
      field,
      title: field === 'text' ? 'Текст отзыва' : 'Замечание',
      mutationKey: this.saveFieldMutationKey(review, field)
    };
  });

  constructor() {
    this.updateMobileReviewLayout();
    this.loadTbankStatus();
    this.destroyRef.onDestroy(() => this.clearReviewPublishWaitTimer());
    effect(() => {
      const job = this.deepReportMonitor.currentJob();
      queueMicrotask(() => this.applyMonitoredCompanyReportJob(job));
    });
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        const openedFromWorkerAll = params.get('from') === 'worker-all';
        this.openedFromWorkerAll.set(openedFromWorkerAll);
        if (openedFromWorkerAll) {
          this.restoreReviewPublishCredentialPreparation();
        } else {
          this.copiedReviewCredentials.set({});
          this.clearReviewPublishWaitTimer();
        }
      });
    this.route.paramMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        const id = Number(params.get('orderId'));
        if (!Number.isFinite(id) || id <= 0) {
          this.error.set('Заказ не найден');
          return;
        }

        this.orderId.set(id);
        this.loadDetails();
      });
  }

  @HostListener('window:keydown.escape')
  closeReviewEditFromKeyboard(): void {
    this.closeReviewEdit();
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    this.updateMobileReviewLayout();
  }

  @HostListener('window:online')
  onWindowOnline(): void {
    this.browserOnline.set(true);
  }

  @HostListener('window:offline')
  onWindowOffline(): void {
    this.browserOnline.set(false);
  }

  @HostListener('window:review-recovery-client-notified', ['$event'])
  onRecoveryClientNotified(event: Event): void {
    const detail = (event as CustomEvent<RecoveryClientNotifiedDetail>).detail;
    if (!detail?.orderId || detail.orderId !== this.orderId()) {
      return;
    }

    if (this.mutationKey() === `recovery-notified-${detail.batchId}`) {
      return;
    }

    this.loadDetails();
  }

  loadDetails(): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.managerApi.getOrderDetails(orderId).subscribe({
      next: (details) => {
        this.details.set(details);
        this.restoreOrderDetailsSessionDraft(details);
        if (!this.applyReviewDeepLink()) {
          this.activeReviewSlide.set(0);
          this.syncReviewJumpValue(0);
        }
        this.loading.set(false);
        this.loadCompanyReportState(false);
        this.applyServerReviewPublishCredentialPreparation(details.credentialPreparation);
        this.refreshReviewPublishWaitTimer();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить детали заказа');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Детали не загрузились', message);
      }
    });
  }

  async copyReviewField(review: OrderReviewItem, kind: ReviewCopyKind): Promise<void> {
    const map: Record<ReviewCopyKind, { value: string; label: string }> = {
      filialUrl: { value: review.filialUrl, label: 'Ссылка скопирована' },
      botLogin: { value: review.botLogin, label: 'Логин скопирован' },
      botPassword: { value: review.botPassword, label: 'Пароль скопирован' },
      text: { value: review.text, label: 'Текст отзыва скопирован' },
      answer: { value: review.answer, label: 'Ответ скопирован' }
    };

    const item = map[kind];
    if (!(await this.copyText(item.value, `${review.id}-${kind}`, item.label))) {
      return;
    }

    if (kind === 'botLogin' || kind === 'botPassword') {
      if (!(await this.logReviewCredentialCopyClick(review, kind))) {
        return;
      }
      this.markReviewCredentialCopied(review, kind);
    }
  }

  reviewAccountActionLocked(review: OrderReviewItem): boolean {
    if (!this.openedFromWorkerAll()) {
      return false;
    }

    const copied = this.copiedReviewCredentials()[review.id];
    return !(copied?.botLoginAt && copied.botPasswordAt && copied.botId === (review.botId ?? null));
  }

  reviewAccountActionTitle(review: OrderReviewItem): string {
    return this.reviewAccountActionLocked(review)
      ? 'Сначала скопируйте логин и пароль аккаунта'
      : 'Действие с аккаунтом';
  }

  reviewPublishActionLocked(review: OrderReviewItem): boolean {
    if (!this.openedFromWorkerAll() || review.publish) {
      return false;
    }

    const copied = this.copiedReviewCredentials()[review.id];
    if (!copied?.botLoginAt || !copied.botPasswordAt || copied.botId !== (review.botId ?? null)) {
      return true;
    }

    return this.reviewPublishWaitLeftSeconds(review) > 0;
  }

  reviewPublishActionTitle(review: OrderReviewItem): string {
    if (!this.openedFromWorkerAll() || review.publish) {
      return 'Действие с отзывом';
    }

    const copied = this.copiedReviewCredentials()[review.id];
    if (!copied || copied.botId !== (review.botId ?? null)) {
      return 'Сначала скопируйте логин и пароль аккаунта.';
    }

    if (!copied.botLoginAt || !copied.botPasswordAt) {
      const missing = [
        !copied.botLoginAt ? 'логин' : '',
        !copied.botPasswordAt ? 'пароль' : ''
      ].filter(Boolean).join(', ');
      return `Скопируйте ${missing}.`;
    }

    const left = this.reviewPublishWaitLeftSeconds(review);
    return left > 0
      ? `После копирования логина и пароля подождите еще ${left} сек.`
      : 'Действие с отзывом';
  }

  hideReviewBotPasswordField(): boolean {
    return this.isOnlyWorkerRole();
  }

  startReviewFieldEdit(review: OrderReviewItem, field: ReviewEditableField): void {
    if (this.isMutating(this.saveFieldMutationKey(review, field))) {
      return;
    }

    const key = this.reviewFieldKey(review, field);
    if (this.editingReviewFieldKey() === key) {
      return;
    }

    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(key);
    if (field === 'text' && this.mobilePreviewReviewTextId() === review.id) {
      this.mobilePreviewReviewTextId.set(null);
    }
    this.focusReviewFieldInput(review, field);
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

  openCompanyReport(): void {
    const state = this.companyReportState();
    if (state?.activeJob) {
      this.companyReportVisible.set(true);
      this.watchCompanyReport(state.companyId, state.companyName);
      return;
    }

    if (state?.latestJob?.report) {
      this.companyReportVisible.set(true);
      return;
    }

    if (!state || state.canStart) {
      this.startCompanyReport();
      return;
    }

    this.companyReportVisible.set(true);
    if (state.unavailableReason) {
      this.toastService.info('Отчёт уже есть', state.unavailableReason);
    }
  }

  refreshCompanyReport(): void {
    const state = this.companyReportState();
    if (!state?.canRefresh) {
      return;
    }

    this.startCompanyReport(true);
  }

  createPaymentLink(): void {
    const orderId = this.orderId();
    if (!orderId || !this.canShowPaymentLinkAction() || this.isMutating('payment-link')) {
      return;
    }

    const existingPaymentLink = this.paymentLink();
    const existingPaymentText = existingPaymentLink?.copyText || existingPaymentLink?.url;
    if (existingPaymentText) {
      void this.copyText(existingPaymentText, 'payment-link', 'Ссылка на оплату скопирована');
      return;
    }

    this.mutationKey.set('payment-link');
    this.error.set(null);
    this.paymentsApi.createOrderPaymentLink(orderId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.paymentLink.set(response);
          this.mutationKey.set(null);
          void this.copyText(
            response.copyText || response.url,
            'payment-link',
            'Ссылка на оплату скопирована',
            'Ссылка создана. Если iPhone не дал скопировать, нажмите кнопку еще раз.'
          );
        },
        error: (err) => {
          const message = this.errorMessage(err, 'Не удалось создать ссылку на оплату');
          this.mutationKey.set(null);
          this.error.set(message);
          this.toastService.error('Ссылка не создана', message);
        }
      });
  }

  paymentLinkModeLabel(): string {
    const status = this.tbankStatus();
    if (!status) {
      return 'Проверка';
    }
    if (!status.enabled) {
      return 'Тест выключен';
    }
    return status.applyConfirmedPayments ? 'Боевой учет' : 'Тест без учета';
  }

  companyReportActionLabel(): string {
    if (this.companyReportLoading()) {
      return 'Проверяю';
    }
    if (this.isActiveCompanyReport(this.companyReportJob())) {
      return 'Готовится';
    }
    return 'О компании';
  }

  isCompanyReportMissing(): boolean {
    const state = this.companyReportState();
    return !state?.latestJob?.report && !state?.activeJob;
  }

  canRefreshCompanyReport(): boolean {
    return !!this.companyReportState()?.canRefresh && this.hasReadyCompanyReport();
  }

  isActiveCompanyReport(job: DeepCompanyResearchJob | null | undefined): boolean {
    return !!job && (job.status === 'QUEUED' || job.status === 'RUNNING');
  }

  companyReportJobErrorMessage(message: string | null | undefined): string {
    return deepReportErrorMessage(
      message,
      'Отчёт не собрался. Администратор или владелец может запустить обновление.'
    );
  }

  private loadCompanyReportState(showErrors: boolean): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    this.companyReportLoading.set(true);
    this.companyReportError.set(null);
    this.managerApi.getOrderCompanyReport(orderId).subscribe({
      next: (state) => {
        this.applyCompanyReportState(state);
        this.companyReportLoading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось проверить отчёт о компании');
        this.companyReportError.set(message);
        this.companyReportLoading.set(false);
        if (showErrors) {
          this.toastService.error('Отчёт не проверен', message);
        }
      }
    });
  }

  private loadTbankStatus(): void {
    this.paymentsApi.getTbankStatus()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (status) => this.tbankStatus.set(status),
        error: () => this.tbankStatus.set(null)
      });
  }

  private startCompanyReport(refresh = false): void {
    const orderId = this.orderId();
    if (!orderId || this.companyReportLoading()) {
      return;
    }

    this.companyReportVisible.set(true);
    this.companyReportLoading.set(true);
    this.companyReportError.set(null);

    const request = refresh
      ? this.managerApi.refreshOrderCompanyReport(orderId)
      : this.managerApi.startOrderCompanyReport(orderId);

    request.subscribe({
      next: (state) => {
        this.applyCompanyReportState(state);
        this.companyReportLoading.set(false);
        const job = state.activeJob ?? state.latestJob;
        if (job && this.isActiveCompanyReport(job)) {
          this.watchCompanyReport(state.companyId, state.companyName);
          this.toastService.info(
            refresh ? 'Обновление запущено' : 'Отчёт готовится',
            'Можно продолжать работать на сайте. Когда отчёт будет готов, появится уведомление.'
          );
        }
      },
      error: (err) => {
        const message = this.errorMessage(err, refresh ? 'Не удалось обновить отчёт' : 'Не удалось запустить отчёт');
        this.companyReportError.set(message);
        this.companyReportLoading.set(false);
        this.toastService.error('Отчёт не запущен', message);
        this.loadCompanyReportState(false);
      }
    });
  }

  private applyCompanyReportState(state: CompanyDeepReportState): void {
    this.companyReportState.set(state);
    if (state.activeJob) {
      this.watchCompanyReport(state.companyId, state.companyName);
    }
  }

  private applyMonitoredCompanyReportJob(job: DeepCompanyResearchJob | null): void {
    const details = this.details();
    if (!job || !details?.companyId || Number(job.companyId) !== Number(details.companyId)) {
      return;
    }

    const active = this.isActiveCompanyReport(job);
    this.companyReportVisible.set(true);
    this.companyReportState.update((state) => {
      const canRefresh = state?.canRefresh ?? false;
      const hasReadyReport = !!job.report || !!state?.latestJob?.report;
      const latestJob = job.report || !state?.latestJob?.report ? job : state.latestJob;
      return {
        companyId: job.companyId,
        companyName: job.companyName || state?.companyName || details.companyTitle,
        latestJob,
        activeJob: active ? job : null,
        canStart: !active && (canRefresh || !hasReadyReport),
        canRefresh,
        unavailableReason: active
          ? 'Отчёт уже готовится.'
          : hasReadyReport && !canRefresh
            ? 'Отчёт уже готов. Повторный запуск доступен только администратору или владельцу.'
            : state?.unavailableReason ?? ''
      };
    });
  }

  private watchCompanyReport(companyId: number, companyName: string): void {
    const details = this.details();
    const orderId = this.orderId();
    const routerLink = details?.companyId && orderId
      ? `/orders/${details.companyId}/${orderId}`
      : '/orders';
    const name = companyName || details?.companyTitle || `Компания #${companyId}`;
    this.deepReportMonitor.watch(companyId, {
      routerLink,
      actionLabel: 'К деталям',
      doneMessage: `Отчёт о компании "${name}" готов.`
    });
  }

  setReviewFieldDraft(review: OrderReviewItem, field: ReviewEditableField, value: string): void {
    const key = this.reviewFieldKey(review, field);
    this.reviewFieldDrafts.update((drafts) => ({
      ...drafts,
      [key]: value
    }));
    this.writeOrderDetailsSessionDraft();

    if (value !== this.reviewFieldSourceValue(review, field)) {
      this.showReviewFieldDraftToast(review, field);
    } else {
      this.dismissReviewFieldDraftToast(key);
    }
  }

  cancelReviewFieldEdit(review: OrderReviewItem, field: ReviewEditableField): void {
    if (this.isMutating(this.saveFieldMutationKey(review, field))) {
      return;
    }

    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(null);
    this.clearReviewFieldDraft(review, field);
  }

  saveReviewField(review: OrderReviewItem, field: ReviewEditableField): void {
    const value = this.reviewFieldValue(review, field);
    if (field === 'text' && !value.trim()) {
      this.toastService.error('Текст не сохранен', 'Поле отзыва не должно быть пустым');
      return;
    }

    const blockReason = this.reviewFieldSaveBlockReason();
    if (blockReason) {
      this.toastService.error(field === 'text' ? 'Текст не сохранен' : 'Ответ не сохранен', blockReason);
      return;
    }

    const key = this.saveFieldMutationKey(review, field);
    const fieldKey = this.reviewFieldKey(review, field);
    this.mutationKey.set(key);
    this.error.set(null);

    const request = field === 'text'
      ? this.managerApi.updateOrderReviewText(review.orderId, review.id, value)
      : this.managerApi.updateOrderReviewAnswer(review.orderId, review.id, value);

    request.subscribe({
      next: (updatedReview) => {
        this.applyUpdatedOrderReview(updatedReview);
        this.mutationKey.set(null);
        this.savedReviewFieldKey.set(fieldKey);
        this.dismissAllReviewFieldDraftToasts();
        this.toastService.success(
          'Сохранено в базе',
          field === 'text'
            ? `Текст отзыва #${review.id} записан в БД`
            : `Ответ отзыва #${review.id} записан в БД`
        );

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
        const message = this.reviewFieldSaveFailureMessage(err, field);
        this.mutationKey.set(null);
        this.error.set(message);
        this.toastService.error(field === 'text' ? 'Текст не сохранен' : 'Ответ не сохранен', message);
      }
    });
  }

  shouldShowReviewTextToggle(review: OrderReviewItem): boolean {
    const value = this.reviewFieldSourceValue(review, 'text');
    return value.length > 190 || value.split(/\r?\n/).length > 5;
  }

  isReviewTextExpanded(review: OrderReviewItem): boolean {
    return !!this.expandedReviewTextIds()[review.id];
  }

  isReviewTextOpen(review: OrderReviewItem): boolean {
    return this.isReviewTextExpanded(review) || this.isReviewFieldEditing(review, 'text');
  }

  toggleReviewText(review: OrderReviewItem, textarea?: HTMLTextAreaElement): void {
    if (this.isMobileReviewLayout() && !this.isReviewFieldEditing(review, 'text')) {
      if (this.isReviewTextExpanded(review)) {
        this.collapseReviewTextPreview(review, textarea);
        return;
      }

      this.activateMobileReviewTextPreview(review, textarea);
      return;
    }

    this.expandedReviewTextIds.update((items) => ({
      ...items,
      [review.id]: !items[review.id]
    }));
  }

  isMobileReviewTextPreview(review: OrderReviewItem): boolean {
    return this.mobilePreviewReviewTextId() === review.id && !this.isReviewFieldEditing(review, 'text');
  }

  onReviewTextPointerDown(event: PointerEvent, review: OrderReviewItem): void {
    if (!this.isMobileReviewLayout() || this.isReviewFieldEditing(review, 'text')) {
      return;
    }

    const textarea = event.currentTarget as HTMLTextAreaElement;
    if (!this.isMobileReviewTextPreview(review)) {
      event.preventDefault();
      this.activateMobileReviewTextPreview(review, textarea);
      textarea.blur();
      return;
    }

    this.startReviewFieldEdit(review, 'text');
    if (this.isReviewFieldEditing(review, 'text')) {
      textarea.readOnly = false;
    }
  }

  onReviewTextFocus(event: FocusEvent, review: OrderReviewItem): void {
    if (this.isMobileReviewLayout()) {
      if (!this.isReviewFieldEditing(review, 'text')) {
        (event.currentTarget as HTMLTextAreaElement).blur();
      }
      return;
    }

    this.startReviewFieldEdit(review, 'text');
  }

  isMobileReviewLayout(): boolean {
    return this.mobileReviewLayout();
  }

  onReviewTextDisplayClick(review: OrderReviewItem): void {
    if (this.isMobileReviewLayout()) {
      if (!this.isMobileReviewTextPreview(review)) {
        this.activateMobileReviewTextPreview(review);
        return;
      }

      this.startReviewFieldEdit(review, 'text');
      return;
    }

    this.startReviewFieldEdit(review, 'text');
  }

  onReviewAnswerDisplayClick(review: OrderReviewItem): void {
    this.startReviewFieldEdit(review, 'answer');
  }

  private activateMobileReviewTextPreview(review: OrderReviewItem, textarea?: HTMLTextAreaElement): void {
    this.mobilePreviewReviewTextId.set(review.id);
    this.expandedReviewTextIds.update((items) => ({
      ...items,
      [review.id]: true
    }));
    this.expandReviewTextAreaToContent(textarea);
  }

  private collapseReviewTextPreview(review: OrderReviewItem, textarea?: HTMLTextAreaElement): void {
    if (this.mobilePreviewReviewTextId() === review.id) {
      this.mobilePreviewReviewTextId.set(null);
    }

    this.expandedReviewTextIds.update((items) => ({
      ...items,
      [review.id]: false
    }));
    this.resetReviewTextAreaHeight(textarea);
  }

  private expandReviewTextAreaToContent(textarea?: HTMLTextAreaElement): void {
    if (!textarea) {
      return;
    }

    window.requestAnimationFrame(() => {
      const currentHeight = textarea.getBoundingClientRect().height;
      textarea.style.height = 'auto';
      textarea.style.height = `${Math.ceil(Math.max(currentHeight, textarea.scrollHeight))}px`;
    });
  }

  private resetReviewTextAreaHeight(textarea?: HTMLTextAreaElement): void {
    if (textarea) {
      textarea.style.height = '';
    }
  }

  private focusReviewFieldInput(review: OrderReviewItem, field: ReviewEditableField): void {
    if (typeof window === 'undefined') {
      return;
    }

    window.requestAnimationFrame(() => {
      document
        .querySelector<HTMLTextAreaElement>(`.review-card[data-review-id="${review.id}"] textarea[name="${field}-${review.id}"]`)
        ?.focus({ preventScroll: true });
    });
  }

  onReviewCarouselScroll(event: Event): void {
    const track = event.currentTarget as HTMLElement | null;
    const firstCard = track?.querySelector<HTMLElement>('.review-card');
    if (!track || !firstCard) {
      return;
    }

    const styles = window.getComputedStyle(track);
    const gap = parseFloat(styles.columnGap || styles.gap || '0') || 0;
    const step = firstCard.offsetWidth + gap;
    if (step <= 0) {
      return;
    }

    const maxIndex = Math.max(this.reviews().length - 1, 0);
    const index = Math.round(track.scrollLeft / step);
    this.setActiveReviewIndex(Math.min(maxIndex, Math.max(0, index)), false);
  }

  previousReview(): void {
    this.setActiveReviewIndex(this.activeReviewSlide() - 1);
  }

  nextReview(): void {
    this.setActiveReviewIndex(this.activeReviewSlide() + 1);
  }

  setReviewJumpValue(value: string): void {
    this.reviewJumpValue.set(value);
  }

  jumpToReview(): void {
    const value = this.reviewJumpValue().trim();
    if (!value) {
      this.syncReviewJumpValue(this.activeReviewSlide());
      return;
    }

    const numericValue = Number(value);
    const reviews = this.reviews();
    if (Number.isInteger(numericValue)) {
      const byPosition = numericValue - 1;
      if (byPosition >= 0 && byPosition < reviews.length) {
        this.setActiveReviewIndex(byPosition);
        return;
      }

      const byId = reviews.findIndex((review) => review.id === numericValue);
      if (byId >= 0) {
        this.setActiveReviewIndex(byId);
        return;
      }
    }

    this.toastService.error('Отзыв не найден', 'Введите номер в списке или ID отзыва');
    this.syncReviewJumpValue(this.activeReviewSlide());
  }

  goToReviewIndex(index: number): void {
    this.setActiveReviewIndex(index);
  }

  setReviewQuickFilter(value: string): void {
    const filter = this.isReviewQuickFilter(value) ? value : 'all';
    this.reviewQuickFilter.set(filter);

    const indexes = this.reviewQuickFilterIndexes();
    if (!indexes.length) {
      this.reviewQuickFilter.set('all');
      this.toastService.error('Отзывы не найдены', 'В выбранном фильтре нет отзывов');
      return;
    }

    if (!indexes.includes(this.activeReviewSlide())) {
      this.setActiveReviewIndex(indexes[0]);
    }
  }

  setActiveReviewFromQuickSelect(value: string): void {
    const index = Number(value);
    if (!Number.isInteger(index)) {
      return;
    }

    this.setActiveReviewIndex(index);
  }

  reviewQuickOptionLabel(index: number): string {
    const review = this.reviews()[index];
    if (!review) {
      return '';
    }

    return `${index + 1} из ${this.reviews().length} - #${review.id}`;
  }

  isActiveReview(review: OrderReviewItem): boolean {
    return this.showReviewNavigation() && this.reviews()[this.activeReviewSlide()]?.id === review.id;
  }

  private setActiveReviewIndex(index: number, scroll = true): void {
    const reviews = this.reviews();
    if (!reviews.length) {
      this.activeReviewSlide.set(0);
      this.syncReviewJumpValue(0);
      return;
    }

    const previousIndex = this.activeReviewSlide();
    const nextIndex = Math.max(0, Math.min(index, reviews.length - 1));
    this.activeReviewSlide.set(nextIndex);
    this.syncReviewJumpValue(nextIndex);

    if (scroll) {
      this.scrollReviewIntoView(reviews[nextIndex]?.id, Math.abs(nextIndex - previousIndex) <= 2);
    }
  }

  private applyReviewDeepLink(): boolean {
    const reviewId = Number(this.route.snapshot.queryParamMap.get('reviewId'));
    if (!Number.isFinite(reviewId) || reviewId <= 0) {
      return false;
    }

    const index = this.reviews().findIndex((review) => review.id === reviewId);
    if (index < 0) {
      return false;
    }

    this.setActiveReviewIndex(index, true);
    return true;
  }

  private scrollReviewIntoView(reviewId?: number, smooth = true): void {
    if (!reviewId || typeof window === 'undefined') {
      return;
    }

    window.requestAnimationFrame(() => {
      document.querySelector<HTMLElement>(`.review-card[data-review-id="${reviewId}"]`)
        ?.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'nearest', inline: 'center' });
    });
  }

  private syncReviewJumpValue(index: number): void {
    this.reviewJumpValue.set(String(index + 1));
  }

  private updateMobileReviewLayout(): void {
    if (typeof window === 'undefined') {
      return;
    }

    this.mobileReviewLayout.set(window.innerWidth <= 860);
  }

  private isReviewQuickFilter(value: string): value is ReviewQuickFilter {
    return value === 'all'
      || value === 'unpublished'
      || value === 'missing-photo'
      || value === 'with-note';
  }

  private reviewMatchesQuickFilter(review: OrderReviewItem, filter: ReviewQuickFilter): boolean {
    switch (filter) {
      case 'unpublished':
        return !review.publish && !review.publishedDate;
      case 'missing-photo':
        return this.needsReviewPhoto(review);
      case 'with-note':
        return this.hasReviewNote(review);
      default:
        return true;
    }
  }

  hasReviewNote(review: OrderReviewItem): boolean {
    return this.hasReviewOwnNote(review)
      || this.hasReviewOrderNote(review)
      || this.hasReviewCompanyNote(review);
  }

  hasReviewOwnNote(review: OrderReviewItem): boolean {
    return this.hasMeaningfulNote(review.comment);
  }

  hasReviewOrderNote(review: OrderReviewItem): boolean {
    return this.hasMeaningfulNote(review.orderComments)
      || this.hasMeaningfulNote(this.details()?.orderComments);
  }

  hasReviewCompanyNote(review: OrderReviewItem): boolean {
    return this.hasMeaningfulNote(review.commentCompany)
      || this.hasMeaningfulNote(this.details()?.companyComments);
  }

  toggleReviewNotePopover(review: OrderReviewItem): void {
    if (this.editingReviewNoteId() !== null || this.editingSideNoteField() !== null) {
      return;
    }

    this.openReviewNotePopoverId.update((id) => id === review.id ? null : review.id);
  }

  toggleReviewNotePopoverFromKeyboard(event: Event, review: OrderReviewItem): void {
    if (event.target !== event.currentTarget) {
      return;
    }

    event.preventDefault();
    this.toggleReviewNotePopover(review);
  }

  isReviewNotePopoverOpen(review: OrderReviewItem): boolean {
    return this.openReviewNotePopoverId() === review.id || this.isReviewNoteEditing(review);
  }

  startReviewNoteEdit(review: OrderReviewItem): void {
    if (this.isMutating(`save-note-${review.id}`)) {
      return;
    }

    this.openReviewNotePopoverId.set(review.id);
    this.savedReviewNoteId.set(null);
    this.editingReviewNoteId.set(review.id);
    this.reviewNoteDrafts.update((drafts) => {
      if (review.id in drafts) {
        return drafts;
      }

      return {
        ...drafts,
        [review.id]: review.comment ?? ''
      };
    });
  }

  setReviewNoteDraft(reviewId: number, value: string): void {
    this.reviewNoteDrafts.update((drafts) => ({
      ...drafts,
      [reviewId]: value
    }));
    this.writeOrderDetailsSessionDraft();
  }

  cancelReviewNoteEdit(review: OrderReviewItem): void {
    if (this.isMutating(`save-note-${review.id}`)) {
      return;
    }

    this.savedReviewNoteId.set(null);
    this.editingReviewNoteId.set(null);
    this.openReviewNotePopoverId.set(null);
    this.clearReviewNoteDraft(review.id);
  }

  saveReviewNote(review: OrderReviewItem): void {
    const value = this.reviewNoteValue(review);
    const key = `save-note-${review.id}`;
    this.mutationKey.set(key);
    this.error.set(null);

    this.managerApi.updateOrderReviewNote(review.orderId, review.id, value).subscribe({
      next: (updatedReview) => {
        this.applyUpdatedOrderReview(updatedReview);
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

          if (this.openReviewNotePopoverId() === review.id) {
            this.openReviewNotePopoverId.set(null);
          }

          this.clearReviewNoteDraft(review.id);
        }, 1000);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить заметку отзыва');
        this.mutationKey.set(null);
        this.error.set(message);
        this.toastService.error('Заметка не сохранена', message);
      }
    });
  }

  reviewNoteValue(review: OrderReviewItem): string {
    return this.reviewNoteDrafts()[review.id] ?? review.comment ?? '';
  }

  isReviewNoteEditing(review: OrderReviewItem): boolean {
    return this.editingReviewNoteId() === review.id;
  }

  isReviewNoteChanged(review: OrderReviewItem): boolean {
    return this.reviewNoteValue(review) !== (review.comment ?? '');
  }

  isReviewNoteSaved(review: OrderReviewItem): boolean {
    return this.savedReviewNoteId() === review.id;
  }

  reviewNoteTitle(review: OrderReviewItem): string {
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

  hasReviewPhoto(review: OrderReviewItem): boolean {
    return !!this.reviewPhotoUrl(review);
  }

  needsReviewPhoto(review: OrderReviewItem): boolean {
    return !!review.productPhoto && !this.hasReviewPhoto(review);
  }

  reviewPhotoUrl(review: OrderReviewItem): string {
    return (review.urlPhoto || review.url || '').trim();
  }

  startSideNoteEdit(details: OrderDetailsPayload, field: SideNoteField): void {
    if (this.isMutating(`save-side-${field}`)) {
      return;
    }

    this.savedSideNoteField.set(null);
    this.editingSideNoteField.set(field);
    this.sideNoteDrafts.update((drafts) => ({
      ...drafts,
      [field]: drafts[field] ?? this.sideNoteSourceValue(details, field)
    }));
  }

  startReviewSideNoteEdit(details: OrderDetailsPayload, review: OrderReviewItem, field: SideNoteField): void {
    if (this.isMutating(`save-side-${field}`)) {
      return;
    }

    this.openReviewNotePopoverId.set(review.id);
    this.startSideNoteEdit(details, field);
  }

  setSideNoteDraft(field: SideNoteField, value: string): void {
    this.sideNoteDrafts.update((drafts) => ({
      ...drafts,
      [field]: value
    }));
    this.writeOrderDetailsSessionDraft();
  }

  cancelSideNoteEdit(field: SideNoteField): void {
    if (this.isMutating(`save-side-${field}`)) {
      return;
    }

    this.savedSideNoteField.set(null);
    this.editingSideNoteField.set(null);
    this.openReviewNotePopoverId.set(null);
    this.clearSideNoteDraft(field);
  }

  saveSideNote(details: OrderDetailsPayload, field: SideNoteField): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    const value = this.sideNoteValue(details, field);
    const key = `save-side-${field}`;
    this.mutationKey.set(key);
    this.error.set(null);

    const request = field === 'order'
      ? this.managerApi.updateOrderNote(orderId, value)
      : this.managerApi.updateOrderCompanyNote(orderId, value);

    request.subscribe({
      next: (notes) => {
        this.applyOrderNotes(notes);
        this.mutationKey.set(null);
        this.savedSideNoteField.set(field);
        this.toastService.success(field === 'order' ? 'Заметка заказа сохранена' : 'Заметка компании сохранена');

        window.setTimeout(() => {
          if (this.savedSideNoteField() === field) {
            this.savedSideNoteField.set(null);
          }

          if (this.editingSideNoteField() === field) {
            this.editingSideNoteField.set(null);
          }

          this.openReviewNotePopoverId.set(null);

          this.clearSideNoteDraft(field);
        }, 1000);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить заметку');
        this.mutationKey.set(null);
        this.error.set(message);
        this.toastService.error('Заметка не сохранена', message);
      }
    });
  }

  sideNoteValue(details: OrderDetailsPayload, field: SideNoteField): string {
    return this.sideNoteDrafts()[field] ?? this.sideNoteSourceValue(details, field);
  }

  isSideNoteEditing(field: SideNoteField): boolean {
    return this.editingSideNoteField() === field;
  }

  isSideNoteChanged(details: OrderDetailsPayload, field: SideNoteField): boolean {
    return this.sideNoteValue(details, field) !== this.sideNoteSourceValue(details, field);
  }

  isSideNoteSaved(field: SideNoteField): boolean {
    return this.savedSideNoteField() === field;
  }

  reviewFieldValue(review: OrderReviewItem, field: ReviewEditableField): string {
    const key = this.reviewFieldKey(review, field);
    return this.reviewFieldDrafts()[key] ?? this.reviewFieldSourceValue(review, field);
  }

  isReviewFieldEditing(review: OrderReviewItem, field: ReviewEditableField): boolean {
    return this.editingReviewFieldKey() === this.reviewFieldKey(review, field);
  }

  isReviewFieldChanged(review: OrderReviewItem, field: ReviewEditableField): boolean {
    return this.reviewFieldValue(review, field) !== this.reviewFieldSourceValue(review, field);
  }

  isReviewFieldSaved(review: OrderReviewItem, field: ReviewEditableField): boolean {
    return this.savedReviewFieldKey() === this.reviewFieldKey(review, field);
  }

  changeReviewText(review: OrderReviewItem): void {
    this.runReviewMutation(
      `text-${review.id}`,
      this.managerApi.changeOrderReviewText(review.orderId, review.id),
      'Текст заменен',
      'Новый текст отзыва подтянут'
    );
  }

  changeBot(review: OrderReviewItem): void {
    if (this.reviewAccountActionLocked(review)) {
      this.toastService.info('Сначала данные аккаунта', 'Скопируйте логин и пароль перед сменой аккаунта');
      return;
    }

    const key = `bot-${review.id}`;
    const oldBotId = review.botId ?? null;

    this.mutationKey.set(key);
    this.error.set(null);

    this.managerApi.changeOrderReviewBot(review.orderId, review.id, this.orderDetailsActivitySource()).subscribe({
      next: (updatedReview) => {
        this.applyUpdatedOrderReview(updatedReview);
        this.mutationKey.set(null);
        this.toastService.success(
          'Аккаунт изменен',
          this.botChangeMessage(oldBotId, updatedReview.botId ?? null)
        );
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось заменить аккаунт');
        this.mutationKey.set(null);
        this.error.set(message);
        this.toastService.error('Аккаунт не изменен', message);
      }
    });
  }

  assignReviewNewAccount(): void {
    const review = this.editReview();

    if (!review || this.reviewEditNewAccountSaving()) {
      return;
    }

    const oldBotId = review.botId ?? null;
    this.reviewEditNewAccountSaving.set(true);
    this.reviewEditError.set(null);

    this.managerApi.assignOrderReviewNewAccount(review.orderId, review.id).subscribe({
      next: (updatedReview) => {
        this.applyUpdatedOrderReview(updatedReview);
        this.editReview.set(updatedReview);
        this.reviewEditDraft.update((draft) => draft ? {
          ...draft,
          publishedDate: updatedReview.publishedDate || null,
          vigul: !!updatedReview.vigul,
          botName: updatedReview.botFio ?? '',
          botPassword: updatedReview.botPassword ?? ''
        } : this.toReviewEditDraft(updatedReview));
        this.writeOrderDetailsSessionDraft();

        this.reviewEditNewAccountSaving.set(false);
        this.toastService.success(
          'Аккаунт назначен',
          this.botChangeMessage(oldBotId, updatedReview.botId ?? null)
        );
        this.loadDetails();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось назначить новый аккаунт');
        this.reviewEditNewAccountSaving.set(false);
        this.reviewEditError.set(message);
        this.toastService.error('Аккаунт не назначен', message);
      }
    });
  }

  deactivateBot(review: OrderReviewItem): void {
    if (this.reviewAccountActionLocked(review)) {
      this.toastService.info('Сначала данные аккаунта', 'Скопируйте логин и пароль перед блокировкой аккаунта');
      return;
    }

    if (!review.botId) {
      this.toastService.error('Аккаунт не заблокирован', 'У отзыва нет активного аккаунта');
      return;
    }

    const confirmed = window.confirm(`Заблокировать аккаунт "${this.botLabel(review)}" и заменить его?`);
    if (!confirmed) {
      return;
    }

    this.runReviewMutation(
      `block-${review.id}`,
      this.managerApi.deactivateOrderReviewBot(review.orderId, review.id, review.botId, this.orderDetailsActivitySource()),
      'Аккаунт заблокирован',
      'Назначен новый доступный аккаунт'
    );
  }

  publishReview(review: OrderReviewItem): void {
    const details = this.details();
    if (!details || !this.canShowPublishButton(details, review)) {
      return;
    }

    if (this.reviewPublishActionLocked(review)) {
      this.toastService.info('Публикация подождет', this.reviewPublishActionTitle(review));
      return;
    }

    this.runDetailsMutation(
      `publish-${review.id}`,
      this.managerApi.publishOrderReview(review.orderId, review.id, this.orderDetailsActivitySource()),
      'Отзыв опубликован',
      `Отзыв #${review.id} учтен в заказе`,
      () => this.clearReviewPublishCredentialPreparation(review.id)
    );
  }

  cancelBadReviewTask(task: BadReviewTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || !task.id) {
      return;
    }

    const confirmed = window.confirm(`Убрать плохую задачу #${task.id} из доплаты?`);
    if (!confirmed) {
      return;
    }

    this.runDetailsMutation(
      `bad-task-cancel-${task.id}`,
      this.managerApi.cancelBadReviewTask(orderId, task.id),
      'Задача убрана',
      'Она останется в истории, но не попадет в счет'
    );
  }

  completeBadReviewTask(task: BadReviewTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || !this.canCompleteBadReviewTask(task)) {
      return;
    }

    const confirmed = window.confirm(`Отметить плохую задачу #${task.id} выполненной?`);
    if (!confirmed) {
      return;
    }

    this.runDetailsMutation(
      `bad-task-complete-${task.id}`,
      this.managerApi.completeBadReviewTask(orderId, task.id),
      'Оценка изменена',
      `Задача #${task.id} закрыта`
    );
  }

  canCancelBadReviewTask(details: OrderDetailsPayload, task: BadReviewTaskItem): boolean {
    return !this.isOnlyWorkerRole() && details.status !== 'Оплачено' && task.statusCode !== 'CANCELED';
  }

  canCompleteBadReviewTask(task: BadReviewTaskItem): boolean {
    return this.isOnlyWorkerRole() && task.statusCode === 'NEW';
  }

  badReviewTaskState(details: OrderDetailsPayload, task: BadReviewTaskItem): string {
    if (task.statusCode === 'CANCELED') {
      return 'Убрана из счета';
    }

    if (details.status === 'Оплачено') {
      return 'В истории оплаты';
    }

    return task.status || 'В работе';
  }

  badReviewTaskDateLabel(task: BadReviewTaskItem): string {
    const dates = [];
    if (task.scheduledDate) {
      dates.push(`план: ${task.scheduledDate}`);
    }
    if (task.completedDate) {
      dates.push(`сменил: ${task.completedDate}`);
    }
    return dates.join(' · ') || 'дата не указана';
  }

  canEditBadReviewTask(task: BadReviewTaskItem): boolean {
    return task.statusCode === 'NEW';
  }

  badReviewTaskDateValue(task: BadReviewTaskItem): string {
    return this.badReviewTaskDraft(task).scheduledDate ?? '';
  }

  badReviewTaskTextValue(task: BadReviewTaskItem): string {
    return this.badReviewTaskDraft(task).taskText;
  }

  setBadReviewTaskDate(task: BadReviewTaskItem, value: string): void {
    this.updateBadReviewTaskDraft(task, { scheduledDate: value || null });
  }

  setBadReviewTaskText(task: BadReviewTaskItem, value: string): void {
    this.updateBadReviewTaskDraft(task, { taskText: value });
  }

  isBadReviewTaskChanged(task: BadReviewTaskItem): boolean {
    const draft = this.badReviewTaskDrafts()[task.id];
    if (!draft) {
      return false;
    }

    return draft.taskText !== (task.taskText ?? '')
      || (draft.scheduledDate ?? '') !== (task.scheduledDate ?? '');
  }

  isBadReviewTaskSaved(task: BadReviewTaskItem): boolean {
    return this.savedBadReviewTaskId() === task.id;
  }

  saveBadReviewTask(task: BadReviewTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || !this.canEditBadReviewTask(task)) {
      return;
    }

    const draft = this.badReviewTaskDraft(task);
    if (!draft.taskText.trim()) {
      this.toastService.error('Задача не сохранена', 'Заполните текст плохой задачи');
      return;
    }
    if (!draft.scheduledDate) {
      this.toastService.error('Дата не сохранена', 'Выберите плановую дату смены оценки');
      return;
    }

    this.runDetailsMutation(
      `bad-task-save-${task.id}`,
      this.managerApi.updateBadReviewTask(orderId, task.id, {
        taskText: draft.taskText,
        scheduledDate: draft.scheduledDate
      }),
      'Задача сохранена',
      `Текст и план задачи #${task.id} обновлены`,
      () => {
        this.clearBadReviewTaskDraft(task.id);
        this.savedBadReviewTaskId.set(task.id);
        window.setTimeout(() => {
          if (this.savedBadReviewTaskId() === task.id) {
            this.savedBadReviewTaskId.set(null);
          }
        }, 1400);
      }
    );
  }

  badReviewTaskCopyKey(task: BadReviewTaskItem, kind: BadReviewTaskCopyKind): string {
    return `bad-task-${task.id}-${kind}`;
  }

  async copyBadReviewTaskField(task: BadReviewTaskItem, kind: BadReviewTaskCopyKind): Promise<void> {
    const value = kind === 'botLogin' ? task.botLogin : task.botPassword;
    const label = kind === 'botLogin' ? 'Логин скопирован' : 'Пароль скопирован';
    await this.copyText(value ?? '', this.badReviewTaskCopyKey(task, kind), label);
  }

  changeBadReviewTaskBot(task: BadReviewTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || !this.canEditBadReviewTask(task)) {
      return;
    }

    this.runDetailsMutation(
      `bad-task-change-bot-${task.id}`,
      this.managerApi.changeBadReviewTaskBot(orderId, task.id),
      'Аккаунт изменен',
      `Для задачи #${task.id} назначен новый аккаунт`
    );
  }

  recoveryTaskForReview(review: OrderReviewItem): ReviewRecoveryTaskItem | null {
    return this.recoveryTasks().find((task) =>
      task.sourceReviewId === review.id && task.statusCode !== 'CANCELLED'
    ) ?? null;
  }

  recoveryActionLabel(review: OrderReviewItem): string {
    if (this.isMutating(`recovery-create-${review.id}`)) {
      return '...';
    }

    const task = this.recoveryTaskForReview(review);
    if (!task) {
      return 'восстановить';
    }

    return task.statusCode === 'DONE' ? 'восстановлен' : 'в плане';
  }

  createRecoveryTask(review: OrderReviewItem): void {
    const existingTask = this.recoveryTaskForReview(review);
    if (existingTask) {
      this.toastService.info('Уже в восстановлении', `Задача #${existingTask.id}: ${existingTask.status || 'в работе'}`);
      return;
    }

    this.runDetailsMutation(
      `recovery-create-${review.id}`,
      this.managerApi.createReviewRecoveryTask(review.orderId, review.id),
      'Восстановление создано',
      `Отзыв #${review.id} добавлен в восстановление`
    );
  }

  showReviewHelpAction(details: OrderDetailsPayload | null | undefined = this.details()): boolean {
    const status = (details?.status ?? '').trim().toLocaleLowerCase('ru-RU');
    return REVIEW_HELP_ORDER_STATUSES.has(status);
  }

  reviewHelpActionLabel(review: OrderReviewItem): string {
    return this.isMutating(`review-help-${review.id}`) ? '...' : 'помощь';
  }

  reviewHelpActionTitle(): string {
    if (this.companyReportBusy()) {
      return 'Отчёт о компании сейчас готовится';
    }

    if (!this.hasReadyCompanyReport()) {
      return 'Сначала собрать отчёт о компании, потом подготовить текст отзыва';
    }

    return 'Подготовить и сохранить текст отзыва через AI-помощник';
  }

  reviewHelpAllActionLabel(): string {
    return this.isMutating('review-help-all') ? 'Пишу...' : 'Помощь';
  }

  createAllReviewHelpDrafts(): void {
    const orderId = this.orderId();
    const details = this.details();
    if (!orderId || !details || !this.showReviewHelpAction(details)) {
      return;
    }

    if (this.busy()) {
      return;
    }

    if (this.companyReportBusy()) {
      this.toastService.info('Отчёт готовится', 'Дождитесь готового отчёта о компании');
      return;
    }

    if (!this.hasReadyCompanyReport()) {
      this.toastService.info('Нужен отчёт о компании', 'Сначала соберите отчёт, чтобы помощник взял факты о компании');
      this.openCompanyReport();
      return;
    }

    this.mutationKey.set('review-help-all');
    this.error.set(null);

    this.managerApi.createReviewHelpDrafts(orderId).subscribe({
      next: (updatedDetails) => {
        const activeReviewId = this.currentActiveReviewId();
        this.details.set(updatedDetails);
        this.restoreActiveReview(activeReviewId);
        this.mutationKey.set(null);
        this.toastService.success('Тексты сохранены', 'AI-помощник заполнил карточки разными отзывами');
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось подготовить тексты отзывов');
        this.mutationKey.set(null);
        this.toastService.error('Помощь не сработала', message);
      }
    });
  }

  createReviewHelpDraft(review: OrderReviewItem): void {
    const orderId = this.orderId();
    const details = this.details();
    if (!orderId || !details) {
      return;
    }

    if (this.busy()) {
      return;
    }

    if (this.companyReportBusy()) {
      this.toastService.info('Отчёт готовится', 'Дождитесь готового отчёта о компании');
      return;
    }

    if (!this.hasReadyCompanyReport()) {
      this.toastService.info('Нужен отчёт о компании', 'Сначала соберите отчёт, чтобы помощник взял факты о компании');
      this.openCompanyReport();
      return;
    }

    const key = `review-help-${review.id}`;
    const fieldKey = this.reviewFieldKey(review, 'text');
    this.runDetailsMutation(
      key,
      this.managerApi.createReviewHelpDraftForCard(orderId, review.id),
      'Текст сохранён',
      'AI-помощник записал текст в карточку',
      () => {
        this.clearReviewFieldDraft(review, 'text');
        this.expandedReviewTextIds.update((items) => ({
          ...items,
          [review.id]: true
        }));
        this.savedReviewFieldKey.set(fieldKey);
        window.setTimeout(() => {
          if (this.savedReviewFieldKey() === fieldKey) {
            this.savedReviewFieldKey.set(null);
          }

          if (this.editingReviewFieldKey() === fieldKey) {
            this.editingReviewFieldKey.set(null);
          }
        }, 1000);
      }
    );
  }

  canEditRecoveryTask(task: ReviewRecoveryTaskItem): boolean {
    return task.statusCode === 'PLANNED';
  }

  canDeleteRecoveryTask(task: ReviewRecoveryTaskItem): boolean {
    return this.canEditRecoveryTask(task)
      && (this.auth.hasRealmRole('ADMIN') || this.auth.hasRealmRole('OWNER') || this.auth.hasRealmRole('MANAGER'));
  }

  recoveryTaskTextValue(task: ReviewRecoveryTaskItem): string {
    return this.recoveryTaskDraft(task).recoveryText;
  }

  recoveryTaskDateValue(task: ReviewRecoveryTaskItem): string {
    return this.recoveryTaskDraft(task).scheduledDate ?? '';
  }

  setRecoveryTaskText(task: ReviewRecoveryTaskItem, value: string): void {
    this.updateRecoveryTaskDraft(task, { recoveryText: value });
  }

  setRecoveryTaskDate(task: ReviewRecoveryTaskItem, value: string): void {
    this.updateRecoveryTaskDraft(task, { scheduledDate: value || null });
  }

  isRecoveryTaskChanged(task: ReviewRecoveryTaskItem): boolean {
    const draft = this.recoveryTaskDrafts()[task.id];
    if (!draft) {
      return false;
    }

    return draft.recoveryText !== (task.recoveryText ?? '')
      || (draft.scheduledDate ?? '') !== (task.scheduledDate ?? '');
  }

  isRecoveryTaskSaved(task: ReviewRecoveryTaskItem): boolean {
    return this.savedRecoveryTaskId() === task.id;
  }

  saveRecoveryTask(task: ReviewRecoveryTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || !this.canEditRecoveryTask(task)) {
      return;
    }

    const draft = this.recoveryTaskDraft(task);
    if (!draft.recoveryText.trim()) {
      this.toastService.error('Восстановление не сохранено', 'Заполните текст восстановления');
      return;
    }

    this.runDetailsMutation(
      `recovery-save-${task.id}`,
      this.managerApi.updateReviewRecoveryTask(orderId, task.id, {
        recoveryText: draft.recoveryText,
        scheduledDate: draft.scheduledDate
      }),
      'Восстановление сохранено',
      `Текст и дата задачи #${task.id} обновлены`,
      () => {
        this.clearRecoveryTaskDraft(task.id);
        this.savedRecoveryTaskId.set(task.id);
        window.setTimeout(() => {
          if (this.savedRecoveryTaskId() === task.id) {
            this.savedRecoveryTaskId.set(null);
          }
        }, 1400);
      }
    );
  }

  completeRecoveryTask(task: ReviewRecoveryTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || task.statusCode !== 'PLANNED') {
      return;
    }

    const confirmed = window.confirm(`Отметить задачу восстановления #${task.id} выполненной?`);
    if (!confirmed) {
      return;
    }

    this.runDetailsMutation(
      `recovery-complete-${task.id}`,
      this.managerApi.completeReviewRecoveryTask(orderId, task.id),
      'Восстановление отмечено',
      `Задача #${task.id} закрыта`
    );
  }

  deleteRecoveryTask(task: ReviewRecoveryTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || !this.canDeleteRecoveryTask(task)) {
      return;
    }

    const confirmed = window.confirm(`Удалить задачу восстановления #${task.id}?`);
    if (!confirmed) {
      return;
    }

    this.runDetailsMutation(
      `recovery-delete-${task.id}`,
      this.managerApi.deleteReviewRecoveryTask(orderId, task.id),
      'Восстановление удалено',
      `Задача #${task.id} убрана из списка`,
      () => this.clearRecoveryTaskDraft(task.id)
    );
  }

  markRecoveryClientNotified(batch: ReviewRecoveryBatchItem): void {
    const orderId = this.orderId();
    if (!orderId || !batch.id) {
      return;
    }

    this.runDetailsMutation(
      `recovery-notified-${batch.id}`,
      this.managerApi.markRecoveryClientNotified(orderId, batch.id),
      'Клиент отмечен',
      'Восстановления скрыты из рабочих списков',
      () => this.remindersService.dispatchRecoveryClientNotified({ orderId, batchId: batch.id })
    );
  }

  recoveryTaskDateLabel(task: ReviewRecoveryTaskItem): string {
    const dates = [];
    if (task.scheduledDate) {
      dates.push(`план: ${task.scheduledDate}`);
    }
    if (task.completedDate) {
      dates.push(`восстановил: ${task.completedDate}`);
    }
    return dates.join(' · ') || 'дата не указана';
  }

  openReviewEdit(review: OrderReviewItem): void {
    if (!this.details()?.canEditReviews) {
      return;
    }

    const storedEdit = this.readOrderDetailsSessionDraft()?.reviewEdit;
    this.editReview.set(review);
    this.reviewEditDraft.set(storedEdit?.reviewId === review.id
      ? storedEdit.draft
      : this.toReviewEditDraft(review)
    );
    this.reviewEditError.set(null);
    this.reviewEditSaving.set(false);
    this.reviewEditDeleting.set(false);
    this.reviewEditUploading.set(false);
    this.reviewEditNewAccountSaving.set(false);
  }

  closeReviewEdit(): void {
    if (!this.editReview() || this.reviewEditBusy()) {
      return;
    }

    this.editReview.set(null);
    this.reviewEditDraft.set(null);
    this.reviewEditError.set(null);
    this.clearReviewEditSessionDraft();
  }

  setReviewEditField<K extends keyof ReviewEditDraft>(field: K, value: ReviewEditDraft[K]): void {
    if (field === 'vigul' && this.canOnlyUnsetReviewVigul() && value === true) {
      return;
    }

    this.reviewEditDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
    this.writeOrderDetailsSessionDraft();
  }

  reviewPublicationDateMaxFor(review: OrderReviewItem | null): string {
    const previousDate = this.previousReviewPublicationDate(review);
    if (!previousDate) {
      return this.reviewPublicationGlobalDateMax;
    }

    const previous = new Date(`${previousDate}T00:00:00`);
    if (Number.isNaN(previous.getTime())) {
      return this.reviewPublicationGlobalDateMax;
    }

    previous.setDate(previous.getDate() + REVIEW_PUBLICATION_MAX_GAP_DAYS);
    const sequenceMax = formatDateInputValue(previous);
    return sequenceMax < this.reviewPublicationGlobalDateMax ? sequenceMax : this.reviewPublicationGlobalDateMax;
  }

  private previousReviewPublicationDate(review: OrderReviewItem | null): string | null {
    if (!review) {
      return null;
    }

    const previous = [...this.reviews()]
      .filter((item) => item.id < review.id && !!item.publishedDate)
      .sort((left, right) => right.id - left.id)[0];

    return previous?.publishedDate || null;
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

    const request = this.reviewEditRequest(review, draft);

    this.managerApi.updateOrderReview(review.orderId, review.id, request).subscribe({
      next: (updatedReview) => {
        this.applyUpdatedOrderReview(updatedReview);
        this.clearReviewFieldDraft(review, 'text');
        this.clearReviewFieldDraft(review, 'answer');
        this.clearReviewNoteDraft(review.id);
        this.reviewEditSaving.set(false);
        this.editReview.set(null);
        this.reviewEditDraft.set(null);
        this.clearReviewEditSessionDraft();
        this.toastService.success('Отзыв сохранен', `Изменения по отзыву #${review.id} применены`);
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
        const activeReviewId = this.currentActiveReviewId();
        this.details.set(details);
        this.restoreActiveReview(activeReviewId);
        this.reviewEditDeleting.set(false);
        this.editReview.set(null);
        this.reviewEditDraft.set(null);
        this.clearReviewEditSessionDraft();
        this.toastService.success('Отзыв удален', `Отзыв #${review.id} удален из заказа`);
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
      next: (updatedReview) => {
        this.applyUpdatedOrderReview(updatedReview);
        this.reviewEditUploading.set(false);

        this.editReview.set(updatedReview);
        this.reviewEditDraft.update((draft) => draft ? {
          ...draft,
          url: updatedReview.url || updatedReview.urlPhoto || ''
        } : this.toReviewEditDraft(updatedReview));
        this.writeOrderDetailsSessionDraft();

        if (input) {
          input.value = '';
        }

        this.toastService.success('Фото загружено', `Отзыв #${review.id} обновлен`);
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
    return !!this.details()?.canEditReviewDates;
  }

  canEditReviewPublish(): boolean {
    return !!this.details()?.canEditReviewPublish;
  }

  canEditReviewVigul(): boolean {
    return !!this.details()?.canEditReviewVigul;
  }

  canShowReviewVigulControl(draft: ReviewEditDraft): boolean {
    if (!this.canEditReviewVigul()) {
      return false;
    }

    return !this.canOnlyUnsetReviewVigul() || !!this.editReview()?.vigul || !!draft.vigul;
  }

  isReviewVigulInputDisabled(draft: ReviewEditDraft): boolean {
    return this.canOnlyUnsetReviewVigul() && !draft.vigul;
  }

  canDeleteReviews(): boolean {
    return !!this.details()?.canDeleteReviews;
  }

  canShowPublishButton(details: OrderDetailsPayload, review: OrderReviewItem): boolean {
    if (review.publish) {
      return true;
    }

    return !HIDDEN_PUBLISH_ORDER_STATUSES.has((details.status ?? '').trim());
  }

  productNeedsPhoto(productId: number | null): boolean {
    if (productId == null) {
      return false;
    }

    return !!this.productOptions().find((product) => product.id === productId)?.photo;
  }

  addReview(): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    this.runDetailsMutation(
      'add-review',
      this.managerApi.addOrderReview(orderId),
      'Отзыв добавлен',
      'Карточка нового отзыва появилась в заказе'
    );
  }

  sendToCheck(): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    if (this.busy()) {
      this.toastService.info('Сохранение еще идет', 'Дождитесь завершения текущего действия');
      return;
    }

    const blockReason = this.sendToCheckBlockReason();
    if (blockReason) {
      this.toastService.error('Заказ не отправлен', blockReason);
      return;
    }

    const targetStatus = this.sendToCheckTargetStatus();
    this.mutationKey.set('send-check');
    this.managerApi.updateOrderStatus(orderId, targetStatus).subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Заказ отправлен', orderDetailsSendToCheckSuccessDetail(targetStatus));

        if (this.isOnlyWorkerRole()) {
          void this.router.navigate(['/worker']);
          return;
        }

        this.loadDetails();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Заказ не отправлен', this.sendToCheckFailureMessage(err));
      }
    });
  }

  sendToCheckTargetStatus(): string {
    return orderDetailsSendToCheckTargetStatus(this.details()?.status, this.isOnlyWorkerRole());
  }

  sendToCheckActionLabel(): string {
    return orderDetailsSendToCheckActionLabel(this.sendToCheckTargetStatus());
  }

  sendToCheckBusyLabel(): string {
    return orderDetailsSendToCheckBusyLabel(this.sendToCheckTargetStatus());
  }

  editAllReviewsRoute(): string {
    const details = this.details();
    return details?.orderDetailsId ? reviewCheckPath(details.orderDetailsId) : '/orders';
  }

  botLabel(review: OrderReviewItem): string {
    if (this.hasUnavailableBot(review)) {
      return 'нет доступных аккаунтов';
    }

    const counter = review.botCounter ? ` ${review.botCounter}` : '';
    return `${review.botFio || 'Аккаунт не назначен'}${counter}`;
  }

  hasUnavailableBot(review: OrderReviewItem): boolean {
    return (review.botFio ?? '').trim().toLocaleLowerCase('ru-RU') === 'нет доступных аккаунтов';
  }

  private botChangeMessage(oldBotId?: number | null, newBotId?: number | null): string {
    return `Аккаунт изменен с ID ${this.botIdLabel(oldBotId)} на ID ${this.botIdLabel(newBotId)}`;
  }

  private botIdLabel(botId?: number | null): string {
    return botId ? String(botId) : 'не назначен';
  }

  private isOnlyWorkerRole(): boolean {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['WORKER']) && !this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
  }

  trackReview(_index: number, review: OrderReviewItem): number {
    return review.id;
  }

  isMutating(key: string): boolean {
    return this.mutationKey() === key;
  }

  pendingReviewFieldCardNumbers(): number[] {
    const details = this.details();
    if (!details) {
      return [];
    }

    const cardNumbers: number[] = [];
    const seenCardNumbers = new Set<number>();
    const reviews = this.reviews();
    const reviewIndexes = new Map(reviews.map((review, index) => [review.id, index + 1]));

    for (const [key, value] of Object.entries(this.reviewFieldDrafts())) {
      const match = /^(\d+)-(text|answer)$/.exec(key);
      const review = match ? reviews.find((item) => item.id === Number(match[1])) : null;
      const field = match?.[2] as ReviewEditableField | undefined;
      const cardNumber = review ? reviewIndexes.get(review.id) : null;

      if (
        review
        && field
        && cardNumber
        && value !== this.reviewFieldSourceValue(review, field)
        && !seenCardNumbers.has(cardNumber)
      ) {
        cardNumbers.push(cardNumber);
        seenCardNumbers.add(cardNumber);
      }
    }

    return cardNumbers;
  }

  hasPendingReviewFieldChanges(): boolean {
    return this.pendingReviewFieldCardNumbers().length > 0;
  }

  sendToCheckBlockReason(): string | null {
    if (this.isBrowserOffline()) {
      return 'Нельзя отправить на проверку: нет подключения к интернету. Проверьте сеть или VPN, дождитесь подключения, сохраните черновики дискеткой, если они есть, и нажмите отправку еще раз.';
    }

    if (this.isAuthUnavailable()) {
      return 'Нельзя отправить на проверку: авторизация закончилась или не подтверждена. Войдите в систему заново, вернитесь к заказу, сохраните черновики дискеткой, если они есть, и повторите отправку.';
    }

    const pendingReviewFieldCardNumbers = this.pendingReviewFieldCardNumbers();
    if (pendingReviewFieldCardNumbers.length > 0) {
      const cardLabel = this.pendingReviewFieldCardLabel(pendingReviewFieldCardNumbers);
      return `Нельзя отправить на проверку: есть черновики в браузере ${cardLabel}. Нажмите дискетку, чтобы записать текст в БД.`;
    }

    const invalidReviewCount = this.invalidReviewTextCount();
    if (invalidReviewCount > 0) {
      return `Нельзя отправить на проверку: есть пустые отзывы или заготовка "${PLACEHOLDER_REVIEW_TEXT}" (${invalidReviewCount}). Заполните настоящий текст и сохраните дискеткой.`;
    }

    return null;
  }

  private invalidReviewTextCount(): number {
    const details = this.details();
    if (!details) {
      return 0;
    }

    return details.reviews
      .filter((review) => this.isInvalidReviewText(this.reviewFieldSourceValue(review, 'text')))
      .length;
  }

  private pendingReviewFieldCardLabel(cardNumbers: number[]): string {
    if (cardNumbers.length === 1) {
      return `у карточки №${cardNumbers[0]}`;
    }

    return `у карточек ${cardNumbers.map((number) => `№${number}`).join(', ')}`;
  }

  private isInvalidReviewText(value: string): boolean {
    const text = value.trim();
    return !text || text.toLocaleLowerCase('ru-RU') === PLACEHOLDER_REVIEW_TEXT;
  }

  private toReviewEditDraft(review: OrderReviewItem): ReviewEditDraft {
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
      filialId: review.filialId ?? null,
      url: review.url || review.urlPhoto || ''
    };
  }

  private canOnlyUnsetReviewVigul(): boolean {
    const details = this.details();
    return !!details?.canEditReviewVigul
      && !details.canEditReviewDates
      && !details.canEditReviewPublish;
  }

  private reviewEditRequest(review: OrderReviewItem, draft: ReviewEditDraft): ReviewEditDraft {
    if (!this.canOnlyUnsetReviewVigul()) {
      return draft;
    }

    return {
      ...draft,
      vigul: !!review.vigul && !!draft.vigul
    };
  }

  private badReviewTaskDraft(task: BadReviewTaskItem): BadReviewTaskDraft {
    return this.badReviewTaskDrafts()[task.id] ?? {
      taskText: task.taskText ?? '',
      scheduledDate: task.scheduledDate || null
    };
  }

  private updateBadReviewTaskDraft(task: BadReviewTaskItem, patch: Partial<BadReviewTaskDraft>): void {
    const current = this.badReviewTaskDraft(task);
    const next = { ...current, ...patch };
    this.badReviewTaskDrafts.update((drafts) => ({
      ...drafts,
      [task.id]: next
    }));
  }

  private clearBadReviewTaskDraft(taskId: number): void {
    this.badReviewTaskDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[taskId];
      return next;
    });
  }

  private recoveryTaskDraft(task: ReviewRecoveryTaskItem): RecoveryTaskDraft {
    return this.recoveryTaskDrafts()[task.id] ?? {
      recoveryText: task.recoveryText ?? '',
      scheduledDate: task.scheduledDate || null
    };
  }

  private updateRecoveryTaskDraft(task: ReviewRecoveryTaskItem, patch: Partial<RecoveryTaskDraft>): void {
    const current = this.recoveryTaskDraft(task);
    const next = { ...current, ...patch };
    this.recoveryTaskDrafts.update((drafts) => ({
      ...drafts,
      [task.id]: next
    }));
  }

  private clearRecoveryTaskDraft(taskId: number): void {
    this.recoveryTaskDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[taskId];
      return next;
    });
  }

  private runReviewMutation(
    key: string,
    request: Observable<OrderReviewItem>,
    toastTitle: string,
    toastMessage: string
  ): void {
    this.mutationKey.set(key);
    this.error.set(null);

    request.subscribe({
      next: (updatedReview) => {
        this.applyUpdatedOrderReview(updatedReview);
        this.mutationKey.set(null);
        this.toastService.success(toastTitle, toastMessage);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Действие не выполнено');
        this.mutationKey.set(null);
        this.error.set(message);
        this.toastService.error('Действие не выполнено', message);
      }
    });
  }

  private runDetailsMutation(
    key: string,
    request: Observable<OrderDetailsPayload>,
    toastTitle: string,
    toastMessage: string,
    afterSuccess?: (details: OrderDetailsPayload) => void
  ): void {
    this.mutationKey.set(key);
    this.error.set(null);

    request.subscribe({
      next: (details) => {
        const activeReviewId = this.currentActiveReviewId();
        this.details.set(details);
        this.restoreActiveReview(activeReviewId);
        afterSuccess?.(details);
        this.mutationKey.set(null);
        this.toastService.success(toastTitle, toastMessage);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Действие не выполнено');
        this.mutationKey.set(null);
        this.error.set(message);
        this.toastService.error('Действие не выполнено', message);
      }
    });
  }

  private saveReviewHelpDraft(
    review: OrderReviewItem,
    result: ReputationSingleReviewDraftResult
  ): void {
    const text = (result.draft ?? '').trim();
    if (!text) {
      this.mutationKey.set(null);
      this.toastService.error('Текст не получен', 'AI-помощник вернул пустой черновик');
      return;
    }

    this.startReviewFieldEdit(review, 'text');
    this.setReviewFieldDraft(review, 'text', text);
    this.expandedReviewTextIds.update((items) => ({
      ...items,
      [review.id]: true
    }));

    this.managerApi.updateOrderReviewText(review.orderId, review.id, text).subscribe({
      next: (updatedReview) => {
        this.applyUpdatedOrderReview(updatedReview);
        this.clearReviewFieldDraft(review, 'text');
        this.savedReviewFieldKey.set(this.reviewFieldKey(review, 'text'));
        this.mutationKey.set(null);
        this.toastService.success(
          'Текст сохранён',
          result.provider === 'local'
            ? 'Черновик собран локально и записан в карточку'
            : 'AI-помощник записал текст в карточку'
        );
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Черновик подготовлен, но не сохранён');
        this.mutationKey.set(null);
        this.error.set(message);
        this.toastService.error('Текст не сохранён', message);
      }
    });

  }

  private reviewHelpIdea(details: OrderDetailsPayload, review: OrderReviewItem): string {
    const product = this.cleanReviewHelpText(review.productTitle || details.productTitle);
    const current = this.cleanReviewHelpText(this.reviewFieldSourceValue(review, 'text'));
    const base = product
      ? `Новый отзыв по карточке заказа: клиент получил/заказывал "${product}".`
      : 'Новый отзыв по карточке заказа на основе отчёта о компании и данных заказа.';

    if (current && current.toLocaleLowerCase('ru-RU') !== PLACEHOLDER_REVIEW_TEXT) {
      return `${base} Не копировать старый текст, а написать новый вариант с другим началом и структурой.`;
    }

    return base;
  }

  private reviewHelpManualNotes(details: OrderDetailsPayload, review: OrderReviewItem): string {
    const notes = [
      this.cleanReviewHelpText(review.comment),
      this.cleanReviewHelpText(review.orderComments || details.orderComments),
      this.cleanReviewHelpText(review.commentCompany || details.companyComments)
    ].filter(Boolean);

    return notes.slice(0, 4).join('\n');
  }

  private reviewHelpFrontendContext(details: OrderDetailsPayload, review: OrderReviewItem): string {
    const lines = [
      `Заказ #${details.orderId}`,
      `Статус заказа: ${details.status}`,
      `Компания: ${details.companyTitle}`,
      `Категория: ${review.category || ''}`,
      `Подкатегория: ${review.subCategory || ''}`,
      `Филиал: ${review.filialTitle || ''}`,
      `Город филиала: ${review.filialCity || ''}`,
      `Товар/услуга карточки: ${review.productTitle || details.productTitle || ''}`,
      review.price != null ? `Цена карточки: ${review.price}` : '',
      details.amount != null ? `Количество в заказе: ${details.amount}` : '',
      details.sum != null ? `Сумма заказа: ${details.sum}` : ''
    ];

    return lines
      .map((line) => this.cleanReviewHelpText(line))
      .filter(Boolean)
      .join('\n');
  }

  private cleanReviewHelpText(value: string | number | null | undefined): string {
    return String(value ?? '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  private async copyText(
    text: string,
    key: string,
    toast: string,
    failureToast = 'Браузер не дал доступ к буферу обмена'
  ): Promise<boolean> {
    const value = (text ?? '').trim();
    if (!value) {
      return false;
    }

    if (await copyTextToClipboard(value)) {
      this.copied.set(key);
      this.toastService.success('Скопировано', toast);
      window.setTimeout(() => {
        if (this.copied() === key) {
          this.copied.set(null);
        }
      }, 1200);
      return true;
    }

    this.toastService.error('Не скопировано', failureToast);
    return false;
  }

  private markReviewCredentialCopied(review: OrderReviewItem, kind: ReviewCopyKind): void {
    if (kind !== 'botLogin' && kind !== 'botPassword') {
      return;
    }

    this.copiedReviewCredentials.update((current) => {
      const existing = current[review.id];
      const botId = review.botId ?? null;
      const base = existing?.botId === botId ? existing : { botId };
      return {
        [review.id]: {
          ...base,
          [kind === 'botLogin' ? 'botLoginAt' : 'botPasswordAt']: Date.now()
        }
      };
    });
    this.refreshReviewPublishWaitTimer();
    this.storeReviewPublishCredentialPreparation(review);
  }

  private reviewPublishWaitLeftSeconds(review: OrderReviewItem): number {
    if (!this.openedFromWorkerAll() || review.publish) {
      return 0;
    }

    const copied = this.copiedReviewCredentials()[review.id];
    if (!copied?.botLoginAt || !copied.botPasswordAt || copied.botId !== (review.botId ?? null)) {
      return 0;
    }

    const readyAt = Math.max(copied.botLoginAt, copied.botPasswordAt)
      + this.publishCredentialWaitMs
      + this.publishCredentialWaitSafetyBufferMs;
    return Math.max(0, Math.ceil((readyAt - this.reviewPublishWaitNow()) / 1000));
  }

  private refreshReviewPublishWaitTimer(): void {
    this.reviewPublishWaitNow.set(Date.now());
    if (!this.hasActiveReviewPublishWait()) {
      this.clearReviewPublishWaitTimer();
      return;
    }

    if (this.publishCredentialWaitTimer !== null) {
      return;
    }

    this.publishCredentialWaitTimer = window.setInterval(() => {
      this.reviewPublishWaitNow.set(Date.now());
      if (!this.hasActiveReviewPublishWait()) {
        this.clearReviewPublishWaitTimer();
      }
    }, 1000);
  }

  private hasActiveReviewPublishWait(): boolean {
    if (!this.openedFromWorkerAll()) {
      return false;
    }

    return this.reviews().some((review) => this.reviewPublishWaitLeftSeconds(review) > 0);
  }

  private clearReviewPublishWaitTimer(): void {
    if (this.publishCredentialWaitTimer === null) {
      return;
    }

    window.clearInterval(this.publishCredentialWaitTimer);
    this.publishCredentialWaitTimer = null;
  }

  private restoreReviewPublishCredentialPreparation(): void {
    const stored = this.readStoredReviewPublishCredentialPreparation();
    if (!stored) {
      return;
    }

    this.copiedReviewCredentials.set({
      [stored.reviewId]: {
        botId: stored.botId ?? null,
        botLoginAt: stored.botLoginAt,
        botPasswordAt: stored.botPasswordAt
      }
    });
    this.refreshReviewPublishWaitTimer();
  }

  private applyServerReviewPublishCredentialPreparation(preparation?: WorkerCredentialPreparation | null): void {
    if (!this.openedFromWorkerAll() || !preparation) {
      this.clearReviewPublishCredentialPreparation();
      return;
    }

    if ((preparation.scope ?? '').toUpperCase() !== 'PUBLISH') {
      this.clearReviewPublishCredentialPreparation();
      return;
    }

    const reviewId = Number(preparation.reviewId);
    const botId = preparation.botId === null || preparation.botId === undefined ? null : Number(preparation.botId);
    if (!Number.isFinite(reviewId) || reviewId <= 0 || (botId !== null && !Number.isFinite(botId))) {
      this.clearReviewPublishCredentialPreparation();
      return;
    }

    this.copiedReviewCredentials.set({
      [reviewId]: {
        botId,
        botLoginAt: this.serverTimestamp(preparation.loginCopiedAt),
        botPasswordAt: this.serverTimestamp(preparation.passwordCopiedAt)
      }
    });

    const review = this.reviews().find((item) => item.id === reviewId);
    if (review) {
      this.storeReviewPublishCredentialPreparation(review);
    }
  }

  private serverTimestamp(value?: string | null): number | undefined {
    const timestamp = value ? Date.parse(value) : NaN;
    return Number.isFinite(timestamp) ? timestamp : undefined;
  }

  private storeReviewPublishCredentialPreparation(review: OrderReviewItem): void {
    const copied = this.copiedReviewCredentials()[review.id];
    if (!copied) {
      this.removeSessionStorageItem(this.reviewPublishCredentialStorageKey);
      return;
    }

    this.setSessionStorageItem(this.reviewPublishCredentialStorageKey, JSON.stringify({
      reviewId: review.id,
      botId: copied.botId ?? null,
      botLoginAt: copied.botLoginAt,
      botPasswordAt: copied.botPasswordAt,
      updatedAt: Date.now()
    } satisfies StoredReviewCredentialCopyState));
  }

  private clearReviewPublishCredentialPreparation(reviewId?: number): void {
    const current = this.copiedReviewCredentials();
    if (reviewId === undefined || current[reviewId]) {
      this.copiedReviewCredentials.set({});
      this.clearReviewPublishWaitTimer();
    }

    const stored = this.readStoredReviewPublishCredentialPreparation(false);
    if (reviewId === undefined || stored?.reviewId === reviewId) {
      this.removeSessionStorageItem(this.reviewPublishCredentialStorageKey);
    }
  }

  private readStoredReviewPublishCredentialPreparation(clearExpired = true): StoredReviewCredentialCopyState | null {
    const raw = this.getSessionStorageItem(this.reviewPublishCredentialStorageKey);
    if (!raw) {
      return null;
    }

    try {
      const value = JSON.parse(raw) as Partial<StoredReviewCredentialCopyState>;
      const reviewId = Number(value.reviewId);
      const updatedAt = Number(value.updatedAt);
      const botLoginAt = value.botLoginAt === undefined ? undefined : Number(value.botLoginAt);
      const botPasswordAt = value.botPasswordAt === undefined ? undefined : Number(value.botPasswordAt);
      if (
        !Number.isFinite(reviewId) ||
        reviewId <= 0 ||
        !Number.isFinite(updatedAt) ||
        (botLoginAt !== undefined && !Number.isFinite(botLoginAt)) ||
        (botPasswordAt !== undefined && !Number.isFinite(botPasswordAt))
      ) {
        this.removeSessionStorageItem(this.reviewPublishCredentialStorageKey);
        return null;
      }

      if (clearExpired && Date.now() - updatedAt > this.reviewPublishCredentialMaxAgeMs) {
        this.removeSessionStorageItem(this.reviewPublishCredentialStorageKey);
        return null;
      }

      const botId = value.botId === null || value.botId === undefined ? null : Number(value.botId);
      return {
        reviewId,
        botId: Number.isFinite(botId) ? botId : null,
        botLoginAt,
        botPasswordAt,
        updatedAt
      };
    } catch {
      this.removeSessionStorageItem(this.reviewPublishCredentialStorageKey);
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

  private async logReviewCredentialCopyClick(review: OrderReviewItem, kind: ReviewCopyKind): Promise<boolean> {
    if (kind !== 'botLogin' && kind !== 'botPassword') {
      return true;
    }

    const field = kind === 'botLogin' ? 'login' : 'password';
    try {
      await firstValueFrom(this.managerApi.logOrderReviewCopyClick(review.id, field, this.orderDetailsActivitySource()));
      return true;
    } catch {
      this.toastService.error(
        'Копирование не записано',
        'Данные попали в буфер, но сервер не подтвердил действие. Нажмите кнопку еще раз.'
      );
      return false;
    }
  }

  private orderDetailsActivitySource(): { sourcePage: string; sourceEntry?: string; sourceSection?: string } {
    return {
      sourcePage: 'order-details',
      sourceEntry: this.openedFromWorkerAll() ? 'worker-all' : undefined,
      sourceSection: this.openedFromWorkerAll() ? 'all' : undefined
    };
  }

  private applyUpdatedOrderReview(updatedReview: OrderReviewItem): void {
    this.details.update((details) => details ? {
      ...details,
      reviews: details.reviews.map((review) => {
        if (review.id === updatedReview.id) {
          return { ...review, ...updatedReview };
        }

        if (updatedReview.orderDetailsId && review.orderDetailsId === updatedReview.orderDetailsId) {
          return {
            ...review,
            comment: updatedReview.comment,
            orderComments: updatedReview.orderComments,
            commentCompany: updatedReview.commentCompany
          };
        }

        return review;
      })
    } : details);
    this.writeOrderDetailsSessionDraft();
  }

  private applyOrderNotes(notes: OrderNotesUpdate): void {
    this.details.update((details) => details ? {
      ...details,
      orderComments: notes.orderComments,
      companyComments: notes.companyComments,
      reviews: details.reviews.map((review) => ({
        ...review,
        orderComments: notes.orderComments,
        commentCompany: notes.companyComments
      }))
    } : details);
  }

  private currentActiveReviewId(): number | null {
    return this.reviews()[this.activeReviewSlide()]?.id ?? null;
  }

  private restoreActiveReview(reviewId: number | null): void {
    const reviews = this.reviews();
    if (!reviews.length) {
      this.activeReviewSlide.set(0);
      this.syncReviewJumpValue(0);
      return;
    }

    const indexById = reviewId == null ? -1 : reviews.findIndex((review) => review.id === reviewId);
    const fallbackIndex = Math.min(this.activeReviewSlide(), reviews.length - 1);
    this.setActiveReviewIndex(indexById >= 0 ? indexById : fallbackIndex, false);
  }

  private orderDetailsSessionDraftKey(orderId = this.orderId()): string | null {
    return orderId ? `order-details:${orderId}` : null;
  }

  private readOrderDetailsSessionDraft(orderId = this.orderId()): OrderDetailsSessionDraft | null {
    const key = this.orderDetailsSessionDraftKey(orderId);
    return key ? readSessionDraft<OrderDetailsSessionDraft>(key) : null;
  }

  private restoreOrderDetailsSessionDraft(details: OrderDetailsPayload): void {
    const sessionDraft = this.readOrderDetailsSessionDraft(details.orderId);
    this.dismissRestoredReviewFieldsToast();

    const reviewFieldDrafts = this.filterReviewFieldDrafts(details, sessionDraft?.reviewFields);
    this.reviewFieldDrafts.set(reviewFieldDrafts);
    this.reviewNoteDrafts.set(this.filterReviewNoteDrafts(details, sessionDraft?.reviewNotes));
    this.sideNoteDrafts.set(this.filterSideNoteDrafts(details, sessionDraft?.sideNotes));

    if (Object.keys(reviewFieldDrafts).length > 0) {
      this.restoredReviewFieldsToastId = this.toastService.warning(
        'Восстановлен черновик',
        'Это текст из памяти браузера. Нажмите дискетку на карточке, чтобы записать его в БД'
      );
    }
  }

  private writeOrderDetailsSessionDraft(): void {
    const key = this.orderDetailsSessionDraftKey();
    const details = this.details();
    if (!key || !details) {
      return;
    }

    this.writeOrderDetailsSessionDraftValue(key, {
      reviewFields: this.changedReviewFieldDrafts(details, this.reviewFieldDrafts()),
      reviewNotes: this.changedReviewNoteDrafts(details, this.reviewNoteDrafts()),
      sideNotes: this.changedSideNoteDrafts(details, this.sideNoteDrafts()),
      reviewEdit: this.changedReviewEditDraft()
    });
  }

  private writeOrderDetailsSessionDraftValue(key: string, value: OrderDetailsSessionDraft): void {
    const hasReviewFields = Object.keys(value.reviewFields ?? {}).length > 0;
    const hasReviewNotes = Object.keys(value.reviewNotes ?? {}).length > 0;
    const hasSideNotes = Object.keys(value.sideNotes ?? {}).length > 0;

    if (!hasReviewFields && !hasReviewNotes && !hasSideNotes && !value.reviewEdit) {
      removeSessionDraft(key);
      return;
    }

    writeSessionDraft(key, value);
  }

  private clearReviewEditSessionDraft(): void {
    const key = this.orderDetailsSessionDraftKey();
    if (!key) {
      return;
    }

    const draft = readSessionDraft<OrderDetailsSessionDraft>(key);
    if (!draft?.reviewEdit) {
      this.writeOrderDetailsSessionDraft();
      return;
    }

    this.writeOrderDetailsSessionDraftValue(key, {
      ...draft,
      reviewEdit: undefined
    });
  }

  private changedReviewFieldDrafts(
    details: OrderDetailsPayload,
    drafts: Record<string, string>
  ): Record<string, string> {
    const reviewMap = new Map(details.reviews.map((review) => [review.id, review]));
    const next: Record<string, string> = {};

    for (const [key, value] of Object.entries(drafts)) {
      const match = /^(\d+)-(text|answer)$/.exec(key);
      const review = match ? reviewMap.get(Number(match[1])) : null;
      const field = match?.[2] as ReviewEditableField | undefined;
      if (review && field && value !== this.reviewFieldSourceValue(review, field)) {
        next[key] = value;
      }
    }

    return next;
  }

  private changedReviewNoteDrafts(
    details: OrderDetailsPayload,
    drafts: Record<number, string>
  ): Record<number, string> {
    const reviewMap = new Map(details.reviews.map((review) => [review.id, review]));
    const next: Record<number, string> = {};

    for (const [reviewId, value] of Object.entries(drafts)) {
      const review = reviewMap.get(Number(reviewId));
      if (review && value !== (review.comment ?? '')) {
        next[Number(reviewId)] = value;
      }
    }

    return next;
  }

  private changedSideNoteDrafts(
    details: OrderDetailsPayload,
    drafts: Partial<Record<SideNoteField, string>>
  ): Partial<Record<SideNoteField, string>> {
    return {
      ...(typeof drafts.order === 'string' && drafts.order !== this.sideNoteSourceValue(details, 'order')
        ? { order: drafts.order }
        : {}),
      ...(typeof drafts.company === 'string' && drafts.company !== this.sideNoteSourceValue(details, 'company')
        ? { company: drafts.company }
        : {})
    };
  }

  private changedReviewEditDraft(): OrderDetailsSessionDraft['reviewEdit'] {
    const review = this.editReview();
    const draft = this.reviewEditDraft();
    if (!review || !draft || !this.isReviewEditDraftChanged(review, draft)) {
      return undefined;
    }

    return {
      reviewId: review.id,
      draft
    };
  }

  private isReviewEditDraftChanged(review: OrderReviewItem, draft: ReviewEditDraft): boolean {
    const source = this.toReviewEditDraft(review);
    return (Object.keys(source) as (keyof ReviewEditDraft)[]).some((field) => source[field] !== draft[field]);
  }

  private filterReviewFieldDrafts(
    details: OrderDetailsPayload,
    stored?: Record<string, string>
  ): Record<string, string> {
    if (!stored) {
      return {};
    }

    return this.changedReviewFieldDrafts(details, stored);
  }

  private filterReviewNoteDrafts(
    details: OrderDetailsPayload,
    stored?: Record<number, string>
  ): Record<number, string> {
    if (!stored) {
      return {};
    }

    return this.changedReviewNoteDrafts(details, stored);
  }

  private filterSideNoteDrafts(
    details: OrderDetailsPayload,
    stored?: Partial<Record<SideNoteField, string>>
  ): Partial<Record<SideNoteField, string>> {
    if (!stored) {
      return {};
    }

    return this.changedSideNoteDrafts(details, stored);
  }

  private reviewFieldKey(review: OrderReviewItem, field: ReviewEditableField): string {
    return `${review.id}-${field}`;
  }

  private saveFieldMutationKey(review: OrderReviewItem, field: ReviewEditableField): string {
    return `save-${field}-${review.id}`;
  }

  private reviewFieldSourceValue(review: OrderReviewItem, field: ReviewEditableField): string {
    return field === 'text' ? review.text ?? '' : review.answer ?? '';
  }

  private clearReviewFieldDraft(review: OrderReviewItem, field: ReviewEditableField): void {
    const key = this.reviewFieldKey(review, field);
    this.dismissReviewFieldDraftToast(key);
    this.reviewFieldDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[key];
      return next;
    });
    this.writeOrderDetailsSessionDraft();
  }

  private showReviewFieldDraftToast(review: OrderReviewItem, field: ReviewEditableField): void {
    const key = this.reviewFieldKey(review, field);
    if (this.reviewFieldDraftToastIds.has(key)) {
      return;
    }

    const title = field === 'text' ? 'Текст сохранен в черновик' : 'Ответ сохранен в черновик';
    const id = this.toastService.warning(
      title,
      'Пока это только память браузера. Нажмите дискетку на карточке, чтобы записать в БД'
    );
    this.reviewFieldDraftToastIds.set(key, id);
  }

  private dismissReviewFieldDraftToast(key: string): void {
    const toastId = this.reviewFieldDraftToastIds.get(key);
    if (toastId) {
      this.toastService.dismiss(toastId);
    }

    this.reviewFieldDraftToastIds.delete(key);
  }

  private dismissAllReviewFieldDraftToasts(): void {
    for (const toastId of this.reviewFieldDraftToastIds.values()) {
      this.toastService.dismiss(toastId);
    }

    this.reviewFieldDraftToastIds.clear();
    this.dismissRestoredReviewFieldsToast();
  }

  private dismissRestoredReviewFieldsToast(): void {
    if (this.restoredReviewFieldsToastId) {
      this.toastService.dismiss(this.restoredReviewFieldsToastId);
      this.restoredReviewFieldsToastId = null;
    }
  }

  private clearReviewNoteDraft(reviewId: number): void {
    this.reviewNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[reviewId];
      return next;
    });
    this.writeOrderDetailsSessionDraft();
  }

  private sideNoteSourceValue(details: OrderDetailsPayload, field: SideNoteField): string {
    return field === 'order' ? details.orderComments ?? '' : details.companyComments ?? '';
  }

  private hasMeaningfulNote(value: string | null | undefined): boolean {
    const note = (value ?? '').trim();
    return !!note && note.toLowerCase() !== 'нет заметок';
  }

  private clearSideNoteDraft(field: SideNoteField): void {
    this.sideNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[field];
      return next;
    });
    this.writeOrderDetailsSessionDraft();
  }

  private reviewFieldSaveBlockReason(): string | null {
    if (this.isBrowserOffline()) {
      return 'Нет подключения к интернету. Черновик остался в браузере: проверьте сеть или VPN и нажмите дискетку еще раз.';
    }

    if (this.isAuthUnavailable()) {
      return 'Авторизация закончилась или не подтверждена. Черновик остался в браузере: войдите в систему заново и нажмите дискетку еще раз.';
    }

    return null;
  }

  private reviewFieldSaveFailureMessage(err: unknown, field: ReviewEditableField): string {
    const fieldLabel = field === 'text' ? 'текст отзыва' : 'ответ на отзыв';

    if (this.isNetworkError(err)) {
      return `Не удалось записать ${fieldLabel} в БД: нет связи с сервером или интернетом. Черновик остался в браузере; проверьте сеть или VPN и нажмите дискетку еще раз.`;
    }

    if (this.isAuthorizationError(err)) {
      return `Не удалось записать ${fieldLabel} в БД: авторизация закончилась. Черновик остался в браузере; войдите в систему заново и нажмите дискетку еще раз.`;
    }

    return this.errorMessage(err, field === 'text'
      ? 'Не удалось сохранить текст отзыва'
      : 'Не удалось сохранить ответ на отзыв'
    );
  }

  private sendToCheckFailureMessage(err: unknown): string {
    if (this.isNetworkError(err)) {
      return 'Нет связи с сервером или интернетом. Проверьте сеть или VPN, убедитесь, что черновики сохранены дискеткой, и нажмите отправку еще раз.';
    }

    if (this.isAuthorizationError(err)) {
      return 'Авторизация закончилась. Войдите в систему заново, вернитесь к заказу, убедитесь, что черновики сохранены дискеткой, и повторите отправку.';
    }

    return this.errorMessage(err, 'Не удалось отправить заказ на проверку');
  }

  private isBrowserOffline(): boolean {
    return !this.browserOnline();
  }

  private isAuthUnavailable(): boolean {
    const status = this.auth.status();
    return status === 'anonymous' || status === 'expired' || status === 'error' || !this.auth.authenticated();
  }

  private isNetworkError(err: unknown): boolean {
    return this.isBrowserOffline() || this.httpStatus(err) === 0;
  }

  private isAuthorizationError(err: unknown): boolean {
    const status = this.httpStatus(err);
    return status === 401 || (status === 403 && !this.auth.isAuthenticated()) || this.isAuthUnavailable();
  }

  private httpStatus(err: unknown): number | null {
    if (typeof err !== 'object' || err === null || !('status' in err)) {
      return null;
    }

    const status = (err as { status?: unknown }).status;
    return typeof status === 'number' ? status : null;
  }

  private readBrowserOnline(): boolean {
    return typeof navigator === 'undefined' ? true : navigator.onLine;
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }
}
