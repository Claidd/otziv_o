import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import {
  ReviewCheckApi,
  ReviewCheckPayload,
  ReviewCheckReview,
  ReviewCheckUpdateRequest
} from '../../core/review-check.api';
import { AuthService } from '../../core/auth.service';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
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

type ReviewCheckAction = 'save' | 'approve' | 'correction' | 'send-check' | 'pay-ok';
type ReviewEditableField = 'text' | 'answer';
type SideNoteField = 'order' | 'company';

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
  readonly activeReviewSlide = signal(0);

  readonly busy = computed(() => this.actionKey() !== null);
  readonly title = computed(() => {
    const details = this.details();
    if (!details) {
      return 'Проверка отзывов';
    }

    return `${details.companyTitle || 'Компания'}${details.filialTitle ? ' - ' + details.filialTitle : ''}`;
  });

  constructor() {
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

  toggleReviewText(review: ReviewCheckReview): void {
    if (this.isExpanded(review)) {
      this.collapseReview();
      return;
    }

    this.expandReview(review.id);
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

    const maxIndex = Math.max((this.details()?.reviews.length ?? 1) - 1, 0);
    const index = Math.round(track.scrollLeft / step);
    this.activeReviewSlide.set(Math.min(maxIndex, Math.max(0, index)));
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
  }

  setReviewFieldDraft(review: ReviewCheckReview, field: ReviewEditableField, value: string): void {
    this.setReviewField(review, field, value);
  }

  startReviewFieldEdit(review: ReviewCheckReview, field: ReviewEditableField): void {
    if (this.isMutating(this.saveFieldMutationKey(review, field))) {
      return;
    }

    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(this.reviewFieldKey(review, field));
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

    this.reviewCheckApi.saveReviews(orderDetailId, this.buildRequest()).subscribe({
      next: (details) => {
        this.applyDetails(details);
        this.actionKey.set(null);
        this.savedReviewFieldKey.set(fieldKey);
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
      next: (details) => {
        this.applyDetails(details);
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
      next: (updatedDetails) => {
        this.applyDetails(updatedDetails);
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
    return details.reviews.filter((review) => review.publish || !!review.publishedDate).length;
  }

  reviewDate(review: ReviewCheckReview): string {
    return review.publishedDate || (review.publish ? 'ОПУБЛИКОВАНО' : '-');
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
        this.applyDetails(details);
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

  private applyDetails(details: ReviewCheckPayload): void {
    this.details.set(details);
    this.draft.set({
      comment: details.comment ?? '',
      reviews: details.reviews.map((review) => ({
        id: review.id,
        text: review.text ?? '',
        answer: review.answer ?? ''
      }))
    });
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
  }

  private clearSideNoteDraft(field: SideNoteField): void {
    this.sideNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[field];
      return next;
    });
  }

  private hasMeaningfulNote(value: string | null | undefined): boolean {
    const note = (value ?? '').trim();
    return !!note && note.toLowerCase() !== 'нет заметок';
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }
}
