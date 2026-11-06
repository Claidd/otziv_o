import { PageWriteTracker } from '../../core/page-write-tracker';
import { signal } from '@angular/core';
import { of, Subject, throwError } from 'rxjs';
import { OrderReviewNotesFacade } from './order-review-notes.facade';
import { RouteEpochGuard } from '../../core/route-epoch.guard';
import type { OrderReviewItem, OrderDetailsPayload } from '../../core/api.service';
const review = { id: 1, orderId: 10, companyId: 91, text: 'Old', comment: '', orderComments: '', commentCompany: '' } as OrderReviewItem;
describe('review inline edits and notes', () => {
  const create = () => {
    const route = new RouteEpochGuard(); route.change('order:10'); const mutationKey = signal<string | null>(null); const error = signal<string | null>(null);
    const api = { updateManagerOrderReviewNote: vi.fn(), updateManagerOrderNote: vi.fn(() => of({ orderComments: '', companyComments: '' })), updateManagerOrderCompanyNote: vi.fn(() => of({ orderComments: '', companyComments: '' })) };
    const applyReview = vi.fn(); const patchOrderNote = vi.fn(); const patchCompanyNote = vi.fn();
    const facade = new OrderReviewNotesFacade({ writes: new PageWriteTracker(), api, details: () => ({ canEditReviews: true, reviews: [review] }) as OrderDetailsPayload, mutationKey, error, capture: () => route.capture(), accepts: ticket => route.accepts(ticket), isMutating: key => mutationKey() === key, applyReview, patchOrderNote, patchCompanyNote, errorMessage: (_, fallback) => fallback });
    return { facade, api, route, mutationKey, error, applyReview, patchOrderNote, patchCompanyNote };
  };
  it('keeps a captured note command together and ignores all late UI updates after navigation', async () => {
    const { facade, api, route, applyReview, patchOrderNote, patchCompanyNote } = create(); const first = new Subject<OrderReviewItem>(); api.updateManagerOrderReviewNote.mockReturnValue(first);
    facade.setReviewNoteDraft(review, 'Review note'); facade.setReviewSideNoteDraft(review, 'order', 'Order note'); facade.setReviewSideNoteDraft(review, 'company', 'Company note');
    const command = facade.saveAllReviewNotes(review); route.change('order:20'); facade.setReviewSideNoteDraft(review, 'order', 'New screen draft');
    expect(first.observed).toBe(true); first.next(review); first.complete(); await command;
    expect(api.updateManagerOrderReviewNote).toHaveBeenCalledWith(10, 1, 'Review note'); expect(api.updateManagerOrderNote).toHaveBeenCalledWith(10, 'Order note'); expect(api.updateManagerOrderCompanyNote).toHaveBeenCalledWith(10, 'Company note');
    expect(applyReview).not.toHaveBeenCalled(); expect(patchOrderNote).not.toHaveBeenCalled(); expect(patchCompanyNote).not.toHaveBeenCalled(); expect(facade.reviewSideNoteValue(review, 'order')).toBe('New screen draft');
  });
  it('keeps drafts after a partial note failure and does not dispatch later stages', async () => {
    const { facade, api, error, mutationKey, applyReview } = create(); api.updateManagerOrderReviewNote.mockReturnValue(of({ ...review, comment: 'Review note' })); api.updateManagerOrderNote.mockReturnValue(throwError(() => new Error('offline')));
    facade.setReviewNoteDraft(review, 'Review note'); facade.setReviewSideNoteDraft(review, 'order', 'Order note'); facade.setReviewSideNoteDraft(review, 'company', 'Company note'); await facade.saveAllReviewNotes(review);
    expect(applyReview).toHaveBeenCalledTimes(1); expect(api.updateManagerOrderCompanyNote).not.toHaveBeenCalled(); expect(error()).toContain('Не удалось'); expect(mutationKey()).toBeNull(); expect(facade.reviewSideNoteValue(review, 'order')).toBe('Order note');
  });
});
