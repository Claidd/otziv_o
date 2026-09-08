import { signal, type WritableSignal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { OrderDetailsPayload, OrderReviewItem, ReviewUpdateRequest } from '../../core/api.service';
import type { OrderReviewsApi } from '../../core/order-reviews.api';
import { EditorSession, type EditorTicket } from '../../core/editor-session';
import type { RouteEpochTicket } from '../../core/route-epoch.guard';
import type { PageWriteTracker } from '../../core/page-write-tracker';
import type { MobileConfirmService } from '../../shared/mobile-confirm.service';
import type { MobileMediaService } from '../../shared/mobile-media.service';
type ReviewEditableField = 'text' | 'answer';
type ReviewTextEditState = { review: OrderReviewItem; field: ReviewEditableField };
type ReviewEditorTicket = { route: RouteEpochTicket; editor: EditorTicket; kind: 'form' | 'text' };
type Dependencies = {
  writes: Pick<PageWriteTracker, 'track'>;
  api: Pick<OrderReviewsApi, 'updateManagerOrderReviewText' | 'updateManagerOrderReviewAnswer' | 'updateManagerOrderReview' | 'deleteManagerOrderReview' | 'uploadManagerOrderReviewPhoto'>;
  details: WritableSignal<OrderDetailsPayload | null>;
  error: WritableSignal<string | null>;
  activeReviewIndex: WritableSignal<number>;
  editingFieldKey: WritableSignal<string | null>;
  capture(): RouteEpochTicket | null;
  accepts(ticket: RouteEpochTicket): boolean;
  confirm: MobileConfirmService['confirm'];
  pickImage: MobileMediaService['pickImageFile'];
  prepareImage: MobileMediaService['prepareImageFile'];
  nativePhotoPickerAvailable(): boolean;
  fieldValue(review: OrderReviewItem, field: ReviewEditableField): string;
  sourceValue(review: OrderReviewItem, field: ReviewEditableField): string;
  applyReview(review: OrderReviewItem): void;
  clearDrafts(reviewId: number): void;
  focusText(): void;
  scrollField(field: ReviewEditableField): void;
  blur(): void;
  errorMessage(error: unknown, fallback: string): string;
};

/** Page-local form and text editors; each modal open has its own entity/generation. */
export class OrderReviewEditorFacade {
  private readonly formSession = new EditorSession();
  private readonly textSession = new EditorSession();
  readonly reviewEdit = signal<OrderReviewItem | null>(null);
  readonly reviewEditInitialField = signal<ReviewEditableField | null>(null);
  readonly reviewEditDraft = signal<ReviewUpdateRequest | null>(null);
  readonly reviewEditSaving = signal(false);
  readonly reviewEditDeleting = signal(false);
  readonly reviewEditUploading = signal(false);
  readonly reviewEditError = signal<string | null>(null);
  readonly reviewTextEdit = signal<ReviewTextEditState | null>(null);
  readonly reviewTextEditValue = signal('');
  readonly reviewTextEditSaving = signal(false);
  readonly reviewTextEditError = signal<string | null>(null);
  constructor(private readonly deps: Dependencies) {}

  deactivate(): void {
    this.formSession.close(); this.textSession.close();
    this.reviewEdit.set(null); this.reviewEditDraft.set(null); this.reviewEditInitialField.set(null);
    this.reviewEditSaving.set(false); this.reviewEditDeleting.set(false); this.reviewEditUploading.set(false);
    this.reviewEditError.set(null); this.reviewTextEdit.set(null); this.reviewTextEditValue.set('');
    this.reviewTextEditSaving.set(false); this.reviewTextEditError.set(null);
  }
  private captureEditor(kind: 'form' | 'text'): ReviewEditorTicket | null {
    const route = this.deps.capture(); const editor = (kind === 'form' ? this.formSession : this.textSession).capture();
    return route && editor ? { route, editor, kind } : null;
  }
  private accepts(ticket: ReviewEditorTicket): boolean {
    return this.deps.accepts(ticket.route) && (ticket.kind === 'form' ? this.formSession : this.textSession).accepts(ticket.editor);
  }

  openReviewTextEdit(review: OrderReviewItem, field: ReviewEditableField): void {
    if (!this.deps.details()?.canEditReviews) {
      this.deps.error.set('Редактирование отзывов недоступно для этого заказа.');
      return;
    }

    if (this.reviewTextEditSaving()) {
      return;
    }

    this.textSession.open(review.id);
    this.deps.editingFieldKey.set(null);
    this.reviewTextEdit.set({ review, field });
    this.reviewTextEditValue.set(this.deps.fieldValue(review, field));
    this.reviewTextEditError.set(null);
    const ticket = this.captureEditor('text');
    window.setTimeout(() => { if (ticket && this.accepts(ticket)) this.deps.focusText(); }, 150);
  }

  closeReviewTextEdit(): void {
    if (this.reviewTextEditSaving()) {
      return;
    }

    this.textSession.close();
    this.reviewTextEdit.set(null);
    this.reviewTextEditValue.set('');
    this.reviewTextEditError.set(null);
    this.deps.blur();
  }

  setReviewTextEditValue(value: string): void {
    this.reviewTextEditValue.set(value);
  }

  reviewTextEditTitle(): string {
    return this.reviewTextEdit()?.field === 'answer' ? 'Ответ или замечание' : 'Текст отзыва';
  }

  reviewTextEditLabel(): string {
    return this.reviewTextEdit()?.field === 'answer' ? 'Ответ на отзыв или замечание' : 'Текст отзыва';
  }

  reviewTextEditPlaceholder(): string {
    return this.reviewTextEdit()?.field === 'answer'
      ? 'Впишите ответ на отзыв или внутреннее замечание'
      : 'Впишите текст отзыва';
  }

  reviewTextEditNote(): string {
    const state = this.reviewTextEdit();
    if (!state) {
      return 'Редактор';
    }

    const title = state.review.companyTitle || this.deps.details()?.companyTitle || 'Компания';
    return `${title} · #${state.review.id}`;
  }

  canSaveReviewTextEdit(): boolean {
    const state = this.reviewTextEdit();
    if (!state || this.reviewTextEditSaving()) {
      return false;
    }

    const value = this.reviewTextEditValue();
    if (state.field === 'text' && !value.trim()) {
      return false;
    }

    return value !== this.deps.sourceValue(state.review, state.field);
  }

  saveReviewTextEdit(): void {
    const state = this.reviewTextEdit();
    if (!state) {
      return;
    }

    const value = this.reviewTextEditValue();
    if (state.field === 'text' && !value.trim()) {
      this.reviewTextEditError.set('Заполните текст отзыва.');
      return;
    }

    if (!this.canSaveReviewTextEdit()) {
      this.closeReviewTextEdit();
      return;
    }

    const routeTicket = this.captureEditor('text');
    if (!routeTicket) {
      return;
    }

    this.reviewTextEditSaving.set(true);
    this.reviewTextEditError.set(null);
    const request = state.field === 'text'
      ? this.deps.api.updateManagerOrderReviewText(state.review.orderId, state.review.id, value)
      : this.deps.api.updateManagerOrderReviewAnswer(state.review.orderId, state.review.id, value);

    this.deps.writes.track(request).subscribe({
      next: (updatedReview) => {
        if (!this.accepts(routeTicket)) {
          return;
        }
        this.deps.applyReview(updatedReview);
        this.deps.clearDrafts(updatedReview.id);
        this.reviewTextEditSaving.set(false);
        this.closeReviewTextEdit();
      },
      error: (err) => {
        if (!this.accepts(routeTicket)) {
          return;
        }
        this.reviewTextEditError.set(this.deps.errorMessage(err, 'Не удалось сохранить отзыв.'));
        this.reviewTextEditSaving.set(false);
      }
    });
  }

  openReviewEdit(review: OrderReviewItem, initialField: ReviewEditableField | null = null): void {
    if (!this.deps.details()?.canEditReviews) {
      this.deps.error.set('Редактирование отзывов недоступно для этого заказа.');
      return;
    }

    if (this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading()) {
      return;
    }

    this.formSession.open(review.id);
    this.reviewEdit.set(review);
    this.reviewEditInitialField.set(initialField);
    this.reviewEditDraft.set(this.reviewEditDraftFromReview(review));
    this.reviewEditError.set(null);
    this.reviewEditUploading.set(false);

    if (initialField) {
      const ticket = this.captureEditor('form');
      window.setTimeout(() => { if (ticket && this.accepts(ticket)) this.deps.scrollField(initialField); }, 120);
    }
  }

  closeReviewEdit(): void {
    if (this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading()) {
      return;
    }

    this.formSession.close();
    this.reviewEdit.set(null);
    this.reviewEditInitialField.set(null);
    this.reviewEditDraft.set(null);
    this.reviewEditError.set(null);
    this.reviewEditUploading.set(false);
  }

  setReviewEditField<K extends keyof ReviewUpdateRequest>(field: K, value: ReviewUpdateRequest[K]): void {
    this.reviewEditDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  emptyToNull(value: unknown): string | null {
    const text = String(value ?? '').trim();
    return text ? text : null;
  }

  canSaveReviewEdit(): boolean {
    const draft = this.reviewEditDraft();
    return Boolean(draft?.text.trim());
  }

  saveReviewEdit(): void {
    if (this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading()) return;
    const review = this.reviewEdit();
    const draft = this.reviewEditDraft();
    if (!review || !draft || !this.canSaveReviewEdit()) {
      this.reviewEditError.set('Заполните текст отзыва.');
      return;
    }

    const routeTicket = this.captureEditor('form');
    if (!routeTicket) {
      return;
    }

    this.reviewEditSaving.set(true);
    this.reviewEditError.set(null);
    this.deps.writes.track(this.deps.api.updateManagerOrderReview(review.orderId, review.id, this.normalizedReviewEditDraft(draft))).subscribe({
      next: (updatedReview) => {
        if (!this.accepts(routeTicket)) {
          return;
        }
        this.deps.applyReview(updatedReview);
        this.deps.clearDrafts(updatedReview.id);
        this.reviewEditSaving.set(false);
        this.closeReviewEdit();
      },
      error: (err) => {
        if (!this.accepts(routeTicket)) {
          return;
        }
        this.reviewEditError.set(this.deps.errorMessage(err, 'Отзыв не сохранен.'));
        this.reviewEditSaving.set(false);
      }
    });
  }

  async deleteReviewEdit(): Promise<void> {
    const review = this.reviewEdit(); const ticket = this.captureEditor('form');
    if (!review || !ticket || !this.deps.details()?.canDeleteReviews || this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading()) return;
    this.reviewEditDeleting.set(true);
    try {
      const confirmed = await this.deps.confirm({ title: 'Удалить отзыв', message: 'Удалить отзыв?', confirmText: 'Удалить', danger: true });
      if (!confirmed || !this.accepts(ticket)) return;
      this.reviewEditError.set(null);
      const details = await firstValueFrom(this.deps.writes.track(this.deps.api.deleteManagerOrderReview(review.orderId, review.id)));
      if (!this.accepts(ticket)) return;
      this.deps.details.set(details);
      this.deps.activeReviewIndex.set(Math.min(this.deps.activeReviewIndex(), Math.max(0, details.reviews.length - 1)));
      this.deps.clearDrafts(review.id); this.reviewEditDeleting.set(false); this.closeReviewEdit();
    } catch (error) {
      if (this.accepts(ticket)) this.reviewEditError.set(this.deps.errorMessage(error, 'Отзыв не удален.'));
    } finally { if (this.accepts(ticket)) this.reviewEditDeleting.set(false); }
  }

  uploadReviewPhoto(event: Event): void {
    const review = this.reviewEdit();
    const input = event.target as HTMLInputElement | null;
    const file = input?.files?.[0];
    if (!review || !file || this.reviewEditUploading()) {
      return;
    }

    void this.uploadReviewPhotoFile(review, file, input);
  }

  async pickNativeReviewPhoto(event: Event): Promise<void> {
    if (!this.deps.nativePhotoPickerAvailable() || this.reviewEditUploading()) return;
    event.preventDefault(); event.stopPropagation();
    const review = this.reviewEdit(); const ticket = this.captureEditor('form');
    if (!review || !ticket) return;
    this.reviewEditUploading.set(true);
    try {
      const file = await this.deps.pickImage(`review-${review.id}`);
      if (file && this.accepts(ticket)) await this.uploadReviewPhotoFile(review, file, null, ticket);
    } catch (error) {
      if (this.accepts(ticket)) this.reviewEditError.set(this.deps.errorMessage(error, 'Фото отзыва не загрузилось.'));
    } finally { if (this.accepts(ticket)) this.reviewEditUploading.set(false); }
  }

  async uploadReviewPhotoFile(
    review: OrderReviewItem,
    file: File,
    input?: HTMLInputElement | null,
    existingRouteTicket?: ReviewEditorTicket
  ): Promise<void> {
    const routeTicket = existingRouteTicket ?? this.captureEditor('form');
    if (!routeTicket || !this.accepts(routeTicket)) {
      return;
    }
    this.reviewEditUploading.set(true);
    this.reviewEditError.set(null);
    try {
      const preparedFile = await this.deps.prepareImage(file, `review-${review.id}`);
      if (!this.accepts(routeTicket)) return;
      const updatedReview = await firstValueFrom(this.deps.writes.track(this.deps.api.uploadManagerOrderReviewPhoto(review.orderId, review.id, preparedFile)));
      if (!this.accepts(routeTicket)) {
        return;
      }
      this.deps.applyReview(updatedReview);
      this.reviewEdit.set(updatedReview);
      this.reviewEditDraft.update((draft) => draft ? {
        ...draft,
        url: updatedReview.url || updatedReview.urlPhoto || ''
      } : this.reviewEditDraftFromReview(updatedReview));
    } catch (err) {
      if (this.accepts(routeTicket)) {
        this.reviewEditError.set(this.deps.errorMessage(err, 'Фото отзыва не загрузилось.'));
      }
    } finally {
      if (this.accepts(routeTicket)) {
        this.reviewEditUploading.set(false);
      }
      if (input && this.accepts(routeTicket)) {
        input.value = '';
      }
    }
  }

  reviewEditDraftFromReview(review: OrderReviewItem): ReviewUpdateRequest {
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
      botPassword: '',
      productId: review.productId ?? null,
      url: review.url || review.urlPhoto || ''
    };
  }

  normalizedReviewEditDraft(draft: ReviewUpdateRequest): ReviewUpdateRequest {
    return {
      ...draft,
      text: draft.text.trim(),
      answer: draft.answer.trim(),
      comment: draft.comment.trim(),
      created: this.emptyToNull(draft.created),
      changed: this.emptyToNull(draft.changed),
      publishedDate: this.emptyToNull(draft.publishedDate),
      botName: draft.botName.trim(),
      botPassword: draft.botPassword.trim(),
      url: draft.url.trim()
    };
  }
}
