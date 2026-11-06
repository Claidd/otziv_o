import { signal, type WritableSignal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { OrderDetailsPayload, OrderReviewItem } from '../../core/api.service';
import type { OrderReviewsApi } from '../../core/order-reviews.api';
import type { RouteEpochTicket } from '../../core/route-epoch.guard';
import type { PageWriteTracker } from '../../core/page-write-tracker';
type ReviewEditableField = 'text' | 'answer';
type ReviewSideNoteField = 'order' | 'company';
type Dependencies = {
  writes: Pick<PageWriteTracker, 'begin'>;
  api: Pick<OrderReviewsApi, 'updateManagerOrderReviewNote' | 'updateManagerOrderNote' | 'updateManagerOrderCompanyNote'>;
  details(): OrderDetailsPayload | null;
  mutationKey: WritableSignal<string | null>; error: WritableSignal<string | null>;
  capture(): RouteEpochTicket | null; accepts(ticket: RouteEpochTicket): boolean;
  isMutating(key: string): boolean; applyReview(review: OrderReviewItem): void;
  patchOrderNote(orderId: number, note: string): void; patchCompanyNote(companyId: number, note: string): void;
  errorMessage(error: unknown, fallback: string): string;
};
/** Owns inline review drafts and the captured sequential note-save command. */
export class OrderReviewNotesFacade {
  readonly editingFieldKey = signal<string | null>(null);
  readonly reviewFieldDrafts = signal<Record<string, string>>({});
  readonly noteOpenId = signal<number | null>(null);
  readonly reviewNoteDrafts = signal<Record<number, string>>({});
  readonly reviewSideNoteDrafts = signal<Record<string, string>>({});
  constructor(private readonly deps: Dependencies) {}
  reviewFieldValue(review: OrderReviewItem, field: ReviewEditableField): string {
    const key = this.reviewFieldKey(review, field);
    return this.reviewFieldDrafts()[key] ?? this.reviewFieldSourceValue(review, field);
  }

  isReviewFieldEditing(review: OrderReviewItem, field: ReviewEditableField): boolean {
    return this.editingFieldKey() === this.reviewFieldKey(review, field);
  }

  toggleReviewNote(review: OrderReviewItem): void {
    this.noteOpenId.update((id) => id === review.id ? null : review.id);
    this.reviewNoteDrafts.update((drafts) => review.id in drafts ? drafts : {
      ...drafts,
      [review.id]: review.comment ?? ''
    });
    this.reviewSideNoteDrafts.update((drafts) => ({
      ...drafts,
      [this.reviewSideNoteKey(review, 'order')]: drafts[this.reviewSideNoteKey(review, 'order')] ?? (review.orderComments ?? ''),
      [this.reviewSideNoteKey(review, 'company')]: drafts[this.reviewSideNoteKey(review, 'company')] ?? (review.commentCompany ?? '')
    }));
  }

  reviewNoteValue(review: OrderReviewItem): string {
    return this.reviewNoteDrafts()[review.id] ?? review.comment ?? '';
  }

  setReviewNoteDraft(review: OrderReviewItem, value: string): void {
    this.reviewNoteDrafts.update((drafts) => ({ ...drafts, [review.id]: value }));
  }

  isReviewNoteChanged(review: OrderReviewItem): boolean {
    return this.reviewNoteValue(review) !== (review.comment ?? '');
  }

  reviewSideNoteValue(review: OrderReviewItem, field: ReviewSideNoteField): string {
    return this.reviewSideNoteDrafts()[this.reviewSideNoteKey(review, field)]
      ?? (field === 'order' ? review.orderComments ?? '' : review.commentCompany ?? '');
  }

  setReviewSideNoteDraft(review: OrderReviewItem, field: ReviewSideNoteField, value: string): void {
    this.reviewSideNoteDrafts.update((drafts) => ({
      ...drafts,
      [this.reviewSideNoteKey(review, field)]: value
    }));
  }

  isReviewSideNoteChanged(review: OrderReviewItem, field: ReviewSideNoteField): boolean {
    return this.reviewSideNoteValue(review, field) !== (field === 'order' ? review.orderComments ?? '' : review.commentCompany ?? '');
  }

  isAnyReviewNoteChanged(review: OrderReviewItem): boolean {
    return this.isReviewNoteChanged(review)
      || this.isReviewSideNoteChanged(review, 'order')
      || this.isReviewSideNoteChanged(review, 'company');
  }

  reviewNotesMutationKey(review: OrderReviewItem): string {
    return `save-notes-${review.id}`;
  }

  async saveAllReviewNotes(review: OrderReviewItem): Promise<void> {
    const key = this.reviewNotesMutationKey(review);
    if (!this.isAnyReviewNoteChanged(review) || this.deps.isMutating(key)) {
      return;
    }

    const routeTicket = this.deps.capture();
    if (!routeTicket) {
      return;
    }
    const updateReviewNote = this.isReviewNoteChanged(review);
    const updateOrderNote = this.isReviewSideNoteChanged(review, 'order');
    const updateCompanyNote = this.isReviewSideNoteChanged(review, 'company');
    const reviewComment = this.reviewNoteValue(review);
    const orderComments = this.reviewSideNoteValue(review, 'order');
    const companyComments = this.reviewSideNoteValue(review, 'company');

    this.deps.mutationKey.set(key);
    const settled = this.deps.writes.begin();
    try {
      if (updateReviewNote) {
        const updatedReview = await firstValueFrom(
          this.deps.api.updateManagerOrderReviewNote(review.orderId, review.id, reviewComment)
        );
        if (this.deps.accepts(routeTicket)) {
          this.deps.applyReview(updatedReview);
        }
      }

      if (updateOrderNote) {
        await firstValueFrom(this.deps.api.updateManagerOrderNote(review.orderId, orderComments));
        if (this.deps.accepts(routeTicket)) {
          this.deps.patchOrderNote(review.orderId, orderComments);
        }
      }

      if (updateCompanyNote) {
        await firstValueFrom(this.deps.api.updateManagerOrderCompanyNote(review.orderId, companyComments));
        if (this.deps.accepts(routeTicket)) {
          this.deps.patchCompanyNote(review.companyId, companyComments);
        }
      }

      if (this.deps.accepts(routeTicket)) {
        this.clearReviewNoteDrafts(review.id);
        this.noteOpenId.set(null);
      }
    } catch (err) {
      if (this.deps.accepts(routeTicket)) {
        this.deps.error.set(this.deps.errorMessage(err, 'Не удалось сохранить заметки'));
      }
    } finally {
      if (this.deps.accepts(routeTicket) && this.deps.mutationKey() === key) {
        this.deps.mutationKey.set(null);
      }
      settled();
    }
  }

  clearReviewDrafts(reviewId: number): void {
    this.reviewFieldDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[`${reviewId}-text`];
      delete next[`${reviewId}-answer`];
      return next;
    });
    this.clearReviewNoteDrafts(reviewId);
    if (this.noteOpenId() === reviewId) {
      this.noteOpenId.set(null);
    }
    if (this.editingFieldKey()?.startsWith(`${reviewId}-`)) {
      this.editingFieldKey.set(null);
    }
  }

  reviewFieldKey(review: OrderReviewItem, field: ReviewEditableField): string {
    return `${review.id}-${field}`;
  }

  reviewSideNoteKey(review: OrderReviewItem, field: ReviewSideNoteField): string {
    return `${review.id}-${field}`;
  }

  clearReviewNoteDrafts(reviewId: number): void {
    this.reviewNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[reviewId];
      return next;
    });
    this.reviewSideNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[`${reviewId}-order`];
      delete next[`${reviewId}-company`];
      return next;
    });
  }

  reviewFieldSourceValue(review: OrderReviewItem, field: ReviewEditableField): string {
    return field === 'text' ? review.text ?? '' : review.answer ?? '';
  }

  hasPendingReviewFieldChanges(): boolean {
    const details = this.deps.details();
    if (!details) {
      return false;
    }

    return Object.entries(this.reviewFieldDrafts()).some(([key, value]) => {
      const match = /^(\d+)-(text|answer)$/.exec(key);
      const review = match ? details.reviews.find((item) => item.id === Number(match[1])) : null;
      const field = match?.[2] as ReviewEditableField | undefined;
      return !!review && !!field && value !== this.reviewFieldSourceValue(review, field);
    });
  }
}
