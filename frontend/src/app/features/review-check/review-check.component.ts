import { Component, DestroyRef, HostListener, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import {
  ReviewCheckApi,
  ReviewCheckNotes,
  ReviewCheckPayload,
  ReviewCheckReview,
  ReviewCheckUpdateRequest
} from '../../core/review-check.api';
import { AuthService } from '../../core/auth.service';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { mobileKeyboardActionBottom } from '../../shared/mobile-keyboard-action-bottom';
import {
  readSessionDraft,
  removeSessionDraft,
  writeSessionDraft
} from '../../shared/session-draft-storage';
import { ToastService } from '../../shared/toast.service';

type ReviewCheckDraftReview = {
  id: number;
  text: string;
  answer: string;
};

type ReviewCheckDraft = {
  comment: string;
  reviews: ReviewCheckDraftReview[];
};

type ReviewCheckSessionDraft = {
  draft?: ReviewCheckDraft;
  reviewNotes?: Record<number, string>;
  sideNotes?: Partial<Record<SideNoteField, string>>;
};

type ReviewCheckAction = 'save' | 'approve' | 'correction' | 'send-check' | 'pay-ok';
type ReviewEditableField = 'text' | 'answer';
type SideNoteField = 'order' | 'company';
type ReviewWindowStatus = 'approved' | 'paid' | 'correction' | 'not-approved';
type ReviewQuickFilter = 'all' | 'unpublished' | 'missing-photo' | 'with-note';
type ActiveReviewFieldEdit = {
  review: ReviewCheckReview;
  field: ReviewEditableField;
  title: string;
  mutationKey: string;
};

@Component({
  selector: 'app-review-check',
  imports: [AdminLayoutComponent, FormsModule, LoadErrorCardComponent, RouterLink],
  templateUrl: './review-check.component.html',
  styleUrl: './review-check.component.scss'
})
export class ReviewCheckComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly reviewCheckApi = inject(ReviewCheckApi);
  private readonly toastService = inject(ToastService);
  readonly auth = inject(AuthService);

  readonly orderDetailId = signal<string | null>(null);
  readonly details = signal<ReviewCheckPayload | null>(null);
  readonly draft = signal<ReviewCheckDraft | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly actionKey = signal<string | null>(null);
  readonly editingReviewFieldKey = signal<string | null>(null);
  readonly savedReviewFieldKey = signal<string | null>(null);
  readonly editingReviewNoteId = signal<number | null>(null);
  readonly reviewNoteDrafts = signal<Record<number, string>>({});
  readonly savedReviewNoteId = signal<number | null>(null);
  readonly editingSideNoteField = signal<SideNoteField | null>(null);
  readonly sideNoteDrafts = signal<Partial<Record<SideNoteField, string>>>({});
  readonly savedSideNoteField = signal<SideNoteField | null>(null);
  readonly expandedReviewId = signal<number | null>(null);
  readonly mobilePreviewReviewTextId = signal<number | null>(null);
  readonly activeReviewSlide = signal(0);
  readonly reviewJumpValue = signal('1');
  readonly reviewQuickFilter = signal<ReviewQuickFilter>('all');
  readonly mobileReviewLayout = signal(false);
  readonly mobileReviewActionBottom = mobileKeyboardActionBottom(this.destroyRef);

  readonly busy = computed(() => this.actionKey() !== null);
  readonly activeReviewFieldEdit = computed<ActiveReviewFieldEdit | null>(() => {
    const key = this.editingReviewFieldKey();
    const details = this.details();
    if (!key || !details) {
      return null;
    }

    const match = /^(\d+)-(text|answer)$/.exec(key);
    if (!match) {
      return null;
    }

    const review = details.reviews.find((item) => item.id === Number(match[1]));
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
  readonly title = computed(() => {
    const details = this.details();
    if (!details) {
      return 'Проверка отзывов';
    }

    return `${details.companyTitle || 'Компания'}${details.filialTitle ? ' - ' + details.filialTitle : ''}`;
  });
  readonly reviewCheckReviews = computed(() => this.details()?.reviews ?? []);
  readonly visibleReviews = computed(() => this.reviewCheckReviews());
  readonly showReviewFastSelect = computed(() => this.reviewCheckReviews().length > 20);
  readonly showReviewNavigation = computed(() => this.mobileReviewLayout() && this.reviewCheckReviews().length > 1);
  readonly reviewQuickFilterIndexes = computed(() => this.reviewCheckReviews()
    .map((review, index) => ({ review, index }))
    .filter(({ review }) => this.reviewMatchesQuickFilter(review, this.reviewQuickFilter()))
    .map(({ index }) => index));

  constructor() {
    this.updateMobileReviewLayout();
    this.route.paramMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        const id = params.get('orderDetailId');
        if (!id) {
          this.error.set('Ссылка на проверку некорректна');
          return;
        }

        this.orderDetailId.set(id);
        this.loadReviewCheck();
      });
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    this.updateMobileReviewLayout();
  }

  loadReviewCheck(): void {
    const orderDetailId = this.orderDetailId();
    if (!orderDetailId) {
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.reviewCheckApi.getReviewCheck(orderDetailId).subscribe({
      next: (details) => {
        this.applyDetails(details);
        this.activeReviewSlide.set(0);
        this.syncReviewJumpValue(0);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(this.errorMessage(err, 'Не удалось загрузить отзывы'));
        this.loading.set(false);
      }
    });
  }

  saveReviews(): void {
    const orderDetailId = this.orderDetailId();
    if (!orderDetailId) {
      return;
    }

    this.runAction(
      'save',
      this.reviewCheckApi.saveReviews(orderDetailId, this.buildRequest()),
      'Отзывы сохранены',
      'Правки применены'
    );
  }

  approveReviews(): void {
    const orderDetailId = this.orderDetailId();
    const details = this.details();
    if (!orderDetailId || !details?.permissions.canApprovePublication) {
      return;
    }

    this.runAction(
      'approve',
      this.reviewCheckApi.approveReviews(orderDetailId, this.buildRequest()),
      'Публикация разрешена',
      'Заказ переведен в публикацию'
    );
  }

  sendToCorrection(): void {
    const orderDetailId = this.orderDetailId();
    const details = this.details();
    if (!orderDetailId || !details?.permissions.canSendCorrection) {
      return;
    }

    this.runAction(
      'correction',
      this.reviewCheckApi.sendToCorrection(orderDetailId, this.buildRequest()),
      'Отправлено на коррекцию',
      'Замечания сохранены'
    );
  }

  sendToCheck(): void {
    const orderDetailId = this.orderDetailId();
    const details = this.details();
    if (!orderDetailId || !details?.permissions.canSendToCheck) {
      return;
    }

    this.runAction(
      'send-check',
      this.reviewCheckApi.sendToCheck(orderDetailId),
      'Отправлено на проверку',
      'Статус заказа обновлен'
    );
  }

  markPaid(): void {
    const orderDetailId = this.orderDetailId();
    const details = this.details();
    if (!orderDetailId || !details?.permissions.canMarkPaid) {
      return;
    }

    this.runAction(
      'pay-ok',
      this.reviewCheckApi.markPaid(orderDetailId),
      'Оплата отмечена',
      'Статус заказа обновлен'
    );
  }

  login(): void {
    void this.auth.login(window.location.pathname || '/');
  }

  logout(): void {
    void this.auth.logout();
  }

  expandReview(reviewId: number): void {
    this.expandedReviewId.set(reviewId);
  }

  collapseReview(): void {
    this.expandedReviewId.set(null);
  }

  isExpanded(review: ReviewCheckReview): boolean {
    return this.expandedReviewId() === review.id;
  }

  isReviewTextExpanded(review: ReviewCheckReview): boolean {
    return this.isExpanded(review);
  }

  shouldShowReviewTextToggle(review: ReviewCheckReview): boolean {
    const value = this.reviewText(review);
    return value.length > 190 || value.split(/\r?\n/).length > 5;
  }

  isReviewTextOpen(review: ReviewCheckReview): boolean {
    return this.isExpanded(review) || this.isReviewFieldEditing(review, 'text');
  }

  toggleReviewText(review: ReviewCheckReview, textarea?: HTMLTextAreaElement): void {
    if (this.isMobileReviewLayout() && !this.isReviewFieldEditing(review, 'text')) {
      if (this.isExpanded(review)) {
        this.collapseReviewTextPreview(review, textarea);
        return;
      }

      this.activateMobileReviewTextPreview(review, textarea);
      return;
    }

    if (this.isExpanded(review)) {
      this.collapseReview();
      return;
    }

    this.expandReview(review.id);
  }

  isMobileReviewTextPreview(review: ReviewCheckReview): boolean {
    return this.mobilePreviewReviewTextId() === review.id && !this.isReviewFieldEditing(review, 'text');
  }

  onReviewTextPointerDown(event: PointerEvent, review: ReviewCheckReview): void {
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

  onReviewTextFocus(event: FocusEvent, review: ReviewCheckReview): void {
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

  onReviewTextDisplayClick(review: ReviewCheckReview): void {
    if (this.isMobileReviewLayout()) {
      if (!this.isMobileReviewTextPreview(review)) {
        this.activateMobileReviewTextPreview(review);
        return;
      }

      this.startReviewFieldEdit(review, 'text');
      return;
    }

    if (this.details()?.permissions.canSave) {
      this.startReviewFieldEdit(review, 'text');
      return;
    }

    this.toggleReviewText(review);
  }

  onReviewAnswerDisplayClick(review: ReviewCheckReview): void {
    this.startReviewFieldEdit(review, 'answer');
  }

  private activateMobileReviewTextPreview(review: ReviewCheckReview, textarea?: HTMLTextAreaElement): void {
    this.mobilePreviewReviewTextId.set(review.id);
    this.expandReview(review.id);
    this.expandReviewTextAreaToContent(textarea);
  }

  private collapseReviewTextPreview(review: ReviewCheckReview, textarea?: HTMLTextAreaElement): void {
    if (this.mobilePreviewReviewTextId() === review.id) {
      this.mobilePreviewReviewTextId.set(null);
    }

    this.collapseReview();
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

  private focusReviewFieldInput(review: ReviewCheckReview, field: ReviewEditableField): void {
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

    const maxIndex = Math.max(this.reviewCheckReviews().length - 1, 0);
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
    const reviews = this.reviewCheckReviews();
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
    const review = this.reviewCheckReviews()[index];
    if (!review) {
      return '';
    }

    return `${index + 1} из ${this.reviewCheckReviews().length} - #${review.id}`;
  }

  isActiveReview(review: ReviewCheckReview): boolean {
    return this.showReviewNavigation() && this.reviewCheckReviews()[this.activeReviewSlide()]?.id === review.id;
  }

  reviewCarouselItemCount(details: ReviewCheckPayload): number {
    return details.reviews.length + (details.permissions.canApprovePublication ? 1 : 0);
  }

  reviewApproveSlideIndex(details: ReviewCheckPayload): number {
    return Math.max(this.reviewCarouselItemCount(details) - 1, 0);
  }

  reviewText(review: ReviewCheckReview): string {
    return this.findReviewDraft(review)?.text ?? review.text ?? '';
  }

  reviewAnswer(review: ReviewCheckReview): string {
    return this.findReviewDraft(review)?.answer ?? review.answer ?? '';
  }

  reviewFieldValue(review: ReviewCheckReview, field: ReviewEditableField): string {
    return field === 'text' ? this.reviewText(review) : this.reviewAnswer(review);
  }

  setReviewField(review: ReviewCheckReview, field: 'text' | 'answer', value: string): void {
    this.draft.update((draft) => {
      if (!draft) {
        return draft;
      }

      return {
        ...draft,
        reviews: draft.reviews.map((item) => item.id === review.id ? { ...item, [field]: value } : item)
      };
    });
    this.writeReviewCheckSessionDraft();
  }

  setReviewFieldDraft(review: ReviewCheckReview, field: ReviewEditableField, value: string): void {
    this.setReviewField(review, field, value);
  }

  startReviewFieldEdit(review: ReviewCheckReview, field: ReviewEditableField): void {
    if (!this.details()?.permissions.canSave || this.isMutating(this.saveFieldMutationKey(review, field))) {
      return;
    }

    const fieldKey = this.reviewFieldKey(review, field);
    if (this.editingReviewFieldKey() === fieldKey) {
      return;
    }

    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(fieldKey);
    if (field === 'text' && this.mobilePreviewReviewTextId() === review.id) {
      this.mobilePreviewReviewTextId.set(null);
    }
    this.focusReviewFieldInput(review, field);
  }

  cancelReviewFieldEdit(review: ReviewCheckReview, field: ReviewEditableField): void {
    if (this.isMutating(this.saveFieldMutationKey(review, field))) {
      return;
    }

    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(null);
    this.setReviewField(review, field, field === 'text' ? review.text ?? '' : review.answer ?? '');
  }

  saveReviewField(review: ReviewCheckReview, field: ReviewEditableField): void {
    const value = field === 'text' ? this.reviewText(review) : this.reviewAnswer(review);
    if (field === 'text' && !value.trim()) {
      this.toastService.error('Текст не сохранен', 'Поле отзыва не должно быть пустым');
      return;
    }

    const orderDetailId = this.orderDetailId();
    if (!orderDetailId) {
      return;
    }

    const key = this.saveFieldMutationKey(review, field);
    const fieldKey = this.reviewFieldKey(review, field);
    this.actionKey.set(key);
    this.error.set(null);

    const request = field === 'text'
      ? this.reviewCheckApi.updateReviewText(orderDetailId, review.id, value)
      : this.reviewCheckApi.updateReviewAnswer(orderDetailId, review.id, value);

    request.subscribe({
      next: (updatedReview) => {
        this.applyUpdatedReview(updatedReview);
        this.actionKey.set(null);
        this.savedReviewFieldKey.set(fieldKey);
        this.writeReviewCheckSessionDraft();
        this.toastService.success(
          field === 'text' ? 'Текст сохранен' : 'Замечание сохранено',
          `Отзыв #${review.id} обновлен`
        );

        window.setTimeout(() => {
          if (this.savedReviewFieldKey() === fieldKey) {
            this.savedReviewFieldKey.set(null);
          }

          if (this.editingReviewFieldKey() === fieldKey) {
            this.editingReviewFieldKey.set(null);
          }
        }, 1000);
      },
      error: (err) => {
        const message = this.errorMessage(err, field === 'text'
          ? 'Не удалось сохранить текст отзыва'
          : 'Не удалось сохранить замечание'
        );
        this.actionKey.set(null);
        this.error.set(message);
        this.toastService.error(field === 'text' ? 'Текст не сохранен' : 'Замечание не сохранено', message);
      }
    });
  }

  isReviewFieldEditing(review: ReviewCheckReview, field: ReviewEditableField): boolean {
    return this.editingReviewFieldKey() === this.reviewFieldKey(review, field);
  }

  isReviewFieldChanged(review: ReviewCheckReview, field: ReviewEditableField): boolean {
    const value = field === 'text' ? this.reviewText(review) : this.reviewAnswer(review);
    const source = field === 'text' ? review.text ?? '' : review.answer ?? '';
    return value !== source;
  }

  isReviewFieldSaved(review: ReviewCheckReview, field: ReviewEditableField): boolean {
    return this.savedReviewFieldKey() === this.reviewFieldKey(review, field);
  }

  canEditNotes(details: ReviewCheckPayload | null = this.details()): boolean {
    return !!details?.permissions.canEditNotes;
  }

  hasReviewNote(review: ReviewCheckReview): boolean {
    return this.hasReviewOwnNote(review)
      || this.hasReviewOrderNote(review)
      || this.hasReviewCompanyNote(review);
  }

  hasReviewOwnNote(review: ReviewCheckReview): boolean {
    return this.hasMeaningfulNote(review.comment);
  }

  hasReviewOrderNote(review: ReviewCheckReview): boolean {
    return this.hasMeaningfulNote(review.orderComments)
      || this.hasMeaningfulNote(this.details()?.orderComments);
  }

  hasReviewCompanyNote(review: ReviewCheckReview): boolean {
    return this.hasMeaningfulNote(review.commentCompany)
      || this.hasMeaningfulNote(this.details()?.companyComments);
  }

  startReviewNoteEdit(review: ReviewCheckReview): void {
    if (!this.canEditNotes() || this.isMutating(`save-note-${review.id}`)) {
      return;
    }

    if (this.editingReviewNoteId() === review.id) {
      return;
    }

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
    this.writeReviewCheckSessionDraft();
  }

  cancelReviewNoteEdit(review: ReviewCheckReview): void {
    if (this.isMutating(`save-note-${review.id}`)) {
      return;
    }

    this.savedReviewNoteId.set(null);
    this.editingReviewNoteId.set(null);
    this.clearReviewNoteDraft(review.id);
  }

  saveReviewNote(review: ReviewCheckReview): void {
    const orderDetailId = this.orderDetailId();
    if (!orderDetailId || !this.canEditNotes()) {
      return;
    }

    const value = this.reviewNoteValue(review);
    const key = `save-note-${review.id}`;
    this.actionKey.set(key);
    this.error.set(null);

    this.reviewCheckApi.updateReviewNote(orderDetailId, review.id, value).subscribe({
      next: (updatedReview) => {
        this.applyUpdatedReviewNote(updatedReview);
        this.actionKey.set(null);
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
        const message = this.errorMessage(err, 'Не удалось сохранить заметку отзыва');
        this.actionKey.set(null);
        this.error.set(message);
        this.toastService.error('Заметка не сохранена', message);
      }
    });
  }

  reviewNoteValue(review: ReviewCheckReview): string {
    return this.reviewNoteDrafts()[review.id] ?? review.comment ?? '';
  }

  reviewOrderNoteValue(details: ReviewCheckPayload, review: ReviewCheckReview): string {
    return this.sideNoteDrafts().order ?? review.orderComments ?? details.orderComments ?? '';
  }

  reviewCompanyNoteValue(details: ReviewCheckPayload, review: ReviewCheckReview): string {
    return this.sideNoteDrafts().company ?? review.commentCompany ?? details.companyComments ?? '';
  }

  isReviewNoteEditing(review: ReviewCheckReview): boolean {
    return this.editingReviewNoteId() === review.id;
  }

  isReviewNoteChanged(review: ReviewCheckReview): boolean {
    return this.reviewNoteValue(review) !== (review.comment ?? '');
  }

  isReviewNoteSaved(review: ReviewCheckReview): boolean {
    return this.savedReviewNoteId() === review.id;
  }

  reviewNoteTitle(review: ReviewCheckReview): string {
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

  startSideNoteEdit(details: ReviewCheckPayload, field: SideNoteField): void {
    if (!this.canEditNotes(details) || this.isMutating(`save-side-${field}`)) {
      return;
    }

    if (this.editingSideNoteField() === field) {
      return;
    }

    this.savedSideNoteField.set(null);
    this.editingSideNoteField.set(field);
    this.sideNoteDrafts.update((drafts) => ({
      ...drafts,
      [field]: drafts[field] ?? this.sideNoteSourceValue(details, field)
    }));
  }

  setSideNoteDraft(field: SideNoteField, value: string): void {
    this.sideNoteDrafts.update((drafts) => ({
      ...drafts,
      [field]: value
    }));
    this.writeReviewCheckSessionDraft();
  }

  cancelSideNoteEdit(field: SideNoteField): void {
    if (this.isMutating(`save-side-${field}`)) {
      return;
    }

    this.savedSideNoteField.set(null);
    this.editingSideNoteField.set(null);
    this.clearSideNoteDraft(field);
  }

  saveSideNote(details: ReviewCheckPayload, field: SideNoteField): void {
    const orderDetailId = this.orderDetailId();
    if (!orderDetailId || !this.canEditNotes(details)) {
      return;
    }

    const value = this.sideNoteValue(details, field);
    const key = `save-side-${field}`;
    this.actionKey.set(key);
    this.error.set(null);

    const request = field === 'order'
      ? this.reviewCheckApi.updateOrderNote(orderDetailId, value)
      : this.reviewCheckApi.updateCompanyNote(orderDetailId, value);

    request.subscribe({
      next: (notes) => {
        this.applyReviewCheckNotes(notes);
        this.actionKey.set(null);
        this.savedSideNoteField.set(field);
        this.toastService.success(field === 'order' ? 'Заметка заказа сохранена' : 'Заметка компании сохранена');

        window.setTimeout(() => {
          if (this.savedSideNoteField() === field) {
            this.savedSideNoteField.set(null);
          }

          if (this.editingSideNoteField() === field) {
            this.editingSideNoteField.set(null);
          }

          this.clearSideNoteDraft(field);
        }, 1000);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить заметку');
        this.actionKey.set(null);
        this.error.set(message);
        this.toastService.error('Заметка не сохранена', message);
      }
    });
  }

  sideNoteValue(details: ReviewCheckPayload, field: SideNoteField): string {
    return this.sideNoteDrafts()[field] ?? this.sideNoteSourceValue(details, field);
  }

  isSideNoteEditing(field: SideNoteField): boolean {
    return this.editingSideNoteField() === field;
  }

  isSideNoteChanged(details: ReviewCheckPayload, field: SideNoteField): boolean {
    return this.sideNoteValue(details, field) !== this.sideNoteSourceValue(details, field);
  }

  isSideNoteSaved(field: SideNoteField): boolean {
    return this.savedSideNoteField() === field;
  }

  setComment(value: string): void {
    this.draft.update((draft) => draft ? { ...draft, comment: value } : draft);
    this.writeReviewCheckSessionDraft();
  }

  commentValue(details: ReviewCheckPayload): string {
    return this.draft()?.comment ?? details.comment ?? '';
  }

  reviewPhotoUrl(review: ReviewCheckReview): string {
    return (review.url || '').trim();
  }

  hasReviewPhoto(review: ReviewCheckReview): boolean {
    return !!this.reviewPhotoUrl(review);
  }

  needsReviewPhoto(review: ReviewCheckReview): boolean {
    return review.productPhoto && !this.hasReviewPhoto(review);
  }

  hasInternalNotes(details: ReviewCheckPayload): boolean {
    return this.hasMeaningfulNote(details.orderComments) || this.hasMeaningfulNote(details.companyComments);
  }

  showStaffActions(details: ReviewCheckPayload): boolean {
    const permissions = details.permissions;
    return permissions.canOpenManagerLinks || permissions.canSendToCheck || permissions.canMarkPaid;
  }

  reviewWindowStatus(details: ReviewCheckPayload): ReviewWindowStatus {
    const status = (details.status || '').trim().toLowerCase();

    if (status === 'оплачено') {
      return 'paid';
    }

    if (status === 'коррекция') {
      return 'correction';
    }

    if (status === 'в проверку' || status === 'на проверке') {
      return 'not-approved';
    }

    if (details.approved || status === 'публикация' || status === 'опубликовано') {
      return 'approved';
    }

    return 'not-approved';
  }

  isReviewWindowApproved(details: ReviewCheckPayload): boolean {
    const status = this.reviewWindowStatus(details);
    return status === 'approved' || status === 'paid';
  }

  shouldShowReviewFooterState(details: ReviewCheckPayload, review: ReviewCheckReview): boolean {
    return this.isReviewWindowApproved(details) || this.isReviewPublished(review);
  }

  reviewFooterStateLabel(details: ReviewCheckPayload, review: ReviewCheckReview): string {
    if (this.reviewWindowStatus(details) === 'paid') {
      return 'оплачен';
    }

    return this.isReviewPublished(review) ? 'опубликован' : 'одобрен';
  }

  isReviewFooterStatePublished(details: ReviewCheckPayload, review: ReviewCheckReview): boolean {
    return this.reviewWindowStatus(details) !== 'paid' && this.isReviewPublished(review);
  }

  reviewWindowStatusLabel(details: ReviewCheckPayload): string {
    switch (this.reviewWindowStatus(details)) {
      case 'paid':
        return 'Оплачено';
      case 'approved':
        return 'Одобрено';
      case 'correction':
        return 'На коррекции';
      default:
        return 'Не одобрено';
    }
  }

  reviewWindowStatusIcon(details: ReviewCheckPayload): string {
    switch (this.reviewWindowStatus(details)) {
      case 'paid':
        return 'payments';
      case 'approved':
        return 'task_alt';
      case 'correction':
        return 'edit_note';
      default:
        return 'fact_check';
    }
  }

  isAction(action: ReviewCheckAction): boolean {
    return this.actionKey() === action;
  }

  isMutating(key: string): boolean {
    return this.actionKey() === key;
  }

  progress(details: ReviewCheckPayload): number {
    if (!details.amount) {
      return 0;
    }

    return Math.max(0, Math.min(100, Math.round((details.counter / details.amount) * 100)));
  }

  reviewedCount(details: ReviewCheckPayload): number {
    return details.reviews.filter((review) => this.isReviewPublished(review)).length;
  }

  reviewDate(review: ReviewCheckReview): string {
    return review.publishedDate || (review.publish ? 'ОПУБЛИКОВАНО' : '-');
  }

  private isReviewPublished(review: ReviewCheckReview): boolean {
    return review.publish || !!review.publishedDate;
  }

  managerOrderRoute(details: ReviewCheckPayload): unknown[] {
    if (!details.companyId || !details.orderId) {
      return ['/manager'];
    }

    return ['/manager/orders', details.companyId, details.orderId];
  }

  managerCompanyOrdersQuery(details: ReviewCheckPayload): Record<string, string | number> {
    if (!details.companyId) {
      return {};
    }

    return {
      section: 'orders',
      companyId: details.companyId,
      companyTitle: details.companyTitle || `Компания #${details.companyId}`,
      status: 'Все'
    };
  }

  trackReview(_index: number, review: ReviewCheckReview): number {
    return review.id;
  }

  trackReviewDot(index: number, _review: ReviewCheckReview): number {
    return index;
  }

  private runAction(
    action: ReviewCheckAction,
    request: Observable<ReviewCheckPayload>,
    toastTitle: string,
    toastMessage: string
  ): void {
    if (this.busy()) {
      return;
    }

    this.actionKey.set(action);
    this.error.set(null);

    request.subscribe({
      next: (details) => {
        const activeReviewId = this.currentActiveReviewId();
        if (action === 'save' || action === 'approve' || action === 'correction') {
          this.clearReviewCheckMainSessionDraft();
        }
        this.applyDetails(details);
        this.restoreActiveReview(activeReviewId);
        this.actionKey.set(null);
        this.toastService.success(toastTitle, toastMessage);
      },
      error: (err) => {
        const message = this.errorMessage(err, toastMessage);
        this.error.set(message);
        this.actionKey.set(null);
        this.toastService.error('Действие не выполнено', message);
      }
    });
  }

  private setActiveReviewIndex(index: number, scroll = true): void {
    const reviews = this.reviewCheckReviews();
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

  private reviewMatchesQuickFilter(review: ReviewCheckReview, filter: ReviewQuickFilter): boolean {
    switch (filter) {
      case 'unpublished':
        return !this.isReviewPublished(review);
      case 'missing-photo':
        return this.needsReviewPhoto(review);
      case 'with-note':
        return this.hasReviewNote(review);
      default:
        return true;
    }
  }

  private applyDetails(details: ReviewCheckPayload): void {
    const sessionDraft = this.readReviewCheckSessionDraft(details.orderDetailId);
    const baseDraft = this.reviewCheckDraftSource(details);

    this.details.set(details);
    this.draft.set(this.mergeReviewCheckDraft(baseDraft, sessionDraft?.draft));
    this.reviewNoteDrafts.set(this.filterReviewNoteDrafts(details, sessionDraft?.reviewNotes));
    this.sideNoteDrafts.set(this.filterSideNoteDrafts(sessionDraft?.sideNotes));
  }

  private applyUpdatedReview(updatedReview: ReviewCheckReview): void {
    this.details.update((details) => details ? {
      ...details,
      reviews: details.reviews.map((review) => review.id === updatedReview.id
        ? { ...review, ...updatedReview }
        : review)
    } : details);

    this.draft.update((draft) => draft ? {
      ...draft,
      reviews: draft.reviews.map((review) => review.id === updatedReview.id
        ? { ...review, text: updatedReview.text ?? '', answer: updatedReview.answer ?? '' }
        : review)
    } : draft);
  }

  private applyUpdatedReviewNote(updatedReview: ReviewCheckReview): void {
    this.details.update((details) => details ? {
      ...details,
      reviews: details.reviews.map((review) => review.id === updatedReview.id
        ? { ...review, ...updatedReview }
        : review)
    } : details);
  }

  private applyReviewCheckNotes(notes: ReviewCheckNotes): void {
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
    return this.reviewCheckReviews()[this.activeReviewSlide()]?.id ?? null;
  }

  private restoreActiveReview(reviewId: number | null): void {
    const reviews = this.reviewCheckReviews();
    if (!reviews.length) {
      this.activeReviewSlide.set(0);
      this.syncReviewJumpValue(0);
      return;
    }

    const indexById = reviewId == null ? -1 : reviews.findIndex((review) => review.id === reviewId);
    const fallbackIndex = Math.min(this.activeReviewSlide(), reviews.length - 1);
    this.setActiveReviewIndex(indexById >= 0 ? indexById : fallbackIndex, false);
  }

  private buildRequest(): ReviewCheckUpdateRequest {
    const details = this.details();
    const draft = this.draft();

    if (!details) {
      return { comment: '', reviews: [] };
    }

    return {
      comment: draft?.comment ?? details.comment ?? '',
      reviews: details.reviews.map((review) => {
        const reviewDraft = draft?.reviews.find((item) => item.id === review.id);
        return {
          id: review.id,
          text: reviewDraft?.text ?? review.text ?? '',
          answer: reviewDraft?.answer ?? review.answer ?? '',
          publish: !!review.publish,
          publishedDate: review.publishedDate || null,
          url: review.url || ''
        };
      })
    };
  }

  private findReviewDraft(review: ReviewCheckReview): ReviewCheckDraftReview | undefined {
    return this.draft()?.reviews.find((item) => item.id === review.id);
  }

  private reviewFieldKey(review: ReviewCheckReview, field: ReviewEditableField): string {
    return `${review.id}-${field}`;
  }

  private saveFieldMutationKey(review: ReviewCheckReview, field: ReviewEditableField): string {
    return `save-${field}-${review.id}`;
  }

  private sideNoteSourceValue(details: ReviewCheckPayload, field: SideNoteField): string {
    return field === 'order' ? details.orderComments ?? '' : details.companyComments ?? '';
  }

  private clearReviewNoteDraft(reviewId: number): void {
    this.reviewNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[reviewId];
      return next;
    });
    this.clearReviewNoteSessionDraft(reviewId);
  }

  private clearSideNoteDraft(field: SideNoteField): void {
    this.sideNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[field];
      return next;
    });
    this.clearSideNoteSessionDraft(field);
  }

  private reviewCheckSessionDraftKey(orderDetailId = this.orderDetailId()): string | null {
    return orderDetailId ? `review-check:${orderDetailId}` : null;
  }

  private readReviewCheckSessionDraft(orderDetailId = this.orderDetailId()): ReviewCheckSessionDraft | null {
    const key = this.reviewCheckSessionDraftKey(orderDetailId);
    return key ? readSessionDraft<ReviewCheckSessionDraft>(key) : null;
  }

  private writeReviewCheckSessionDraft(): void {
    const key = this.reviewCheckSessionDraftKey();
    if (!key) {
      return;
    }

    this.writeReviewCheckSessionDraftValue(key, {
      draft: this.changedReviewCheckDraft(),
      reviewNotes: this.reviewNoteDrafts(),
      sideNotes: this.sideNoteDrafts()
    });
  }

  private writeReviewCheckSessionDraftValue(key: string, value: ReviewCheckSessionDraft): void {
    const hasDraft = !!value.draft;
    const hasReviewNotes = Object.keys(value.reviewNotes ?? {}).length > 0;
    const hasSideNotes = Object.keys(value.sideNotes ?? {}).length > 0;

    if (!hasDraft && !hasReviewNotes && !hasSideNotes) {
      removeSessionDraft(key);
      return;
    }

    writeSessionDraft(key, value);
  }

  private clearReviewCheckMainSessionDraft(): void {
    this.updateReviewCheckSessionDraft((draft) => ({
      ...draft,
      draft: undefined
    }));
  }

  private clearReviewNoteSessionDraft(reviewId: number): void {
    this.updateReviewCheckSessionDraft((draft) => {
      const reviewNotes = { ...(draft.reviewNotes ?? {}) };
      delete reviewNotes[reviewId];
      return {
        ...draft,
        reviewNotes
      };
    });
  }

  private clearSideNoteSessionDraft(field: SideNoteField): void {
    this.updateReviewCheckSessionDraft((draft) => {
      const sideNotes = { ...(draft.sideNotes ?? {}) };
      delete sideNotes[field];
      return {
        ...draft,
        sideNotes
      };
    });
  }

  private updateReviewCheckSessionDraft(updater: (draft: ReviewCheckSessionDraft) => ReviewCheckSessionDraft): void {
    const key = this.reviewCheckSessionDraftKey();
    if (!key) {
      return;
    }

    this.writeReviewCheckSessionDraftValue(key, updater(readSessionDraft<ReviewCheckSessionDraft>(key) ?? {}));
  }

  private mergeReviewCheckDraft(base: ReviewCheckDraft, stored?: ReviewCheckDraft): ReviewCheckDraft {
    if (!stored) {
      return base;
    }

    return {
      comment: stored.comment ?? base.comment,
      reviews: base.reviews.map((review) => {
        const storedReview = stored.reviews.find((item) => item.id === review.id);
        return storedReview ? { ...review, ...storedReview } : review;
      })
    };
  }

  private changedReviewCheckDraft(): ReviewCheckDraft | undefined {
    const details = this.details();
    const draft = this.draft();
    if (!details || !draft) {
      return draft ?? undefined;
    }

    const source = this.reviewCheckDraftSource(details);
    return this.isReviewCheckDraftChanged(source, draft) ? draft : undefined;
  }

  private reviewCheckDraftSource(details: ReviewCheckPayload): ReviewCheckDraft {
    return {
      comment: details.comment ?? '',
      reviews: details.reviews.map((review) => ({
        id: review.id,
        text: review.text ?? '',
        answer: review.answer ?? ''
      }))
    };
  }

  private isReviewCheckDraftChanged(source: ReviewCheckDraft, draft: ReviewCheckDraft): boolean {
    if (source.comment !== draft.comment || source.reviews.length !== draft.reviews.length) {
      return true;
    }

    return source.reviews.some((review) => {
      const draftReview = draft.reviews.find((item) => item.id === review.id);
      return !draftReview || draftReview.text !== review.text || draftReview.answer !== review.answer;
    });
  }

  private filterReviewNoteDrafts(
    details: ReviewCheckPayload,
    stored?: Record<number, string>
  ): Record<number, string> {
    if (!stored) {
      return {};
    }

    const reviewIds = new Set(details.reviews.map((review) => String(review.id)));
    const next: Record<number, string> = {};
    for (const [reviewId, value] of Object.entries(stored)) {
      if (reviewIds.has(reviewId) && typeof value === 'string') {
        next[Number(reviewId)] = value;
      }
    }
    return next;
  }

  private filterSideNoteDrafts(stored?: Partial<Record<SideNoteField, string>>): Partial<Record<SideNoteField, string>> {
    return {
      ...(typeof stored?.order === 'string' ? { order: stored.order } : {}),
      ...(typeof stored?.company === 'string' ? { company: stored.company } : {})
    };
  }

  private hasMeaningfulNote(value: string | null | undefined): boolean {
    const note = (value ?? '').trim();
    return !!note && note.toLowerCase() !== 'нет заметок';
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }
}
