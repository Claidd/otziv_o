import { PageWriteTracker } from '../../core/page-write-tracker';
import { signal } from '@angular/core';
import { of, Subject } from 'rxjs';
import { OrderReviewEditorFacade } from './order-review-editor.facade';
import { RouteEpochGuard } from '../../core/route-epoch.guard';
import type { OrderReviewItem, OrderDetailsPayload } from '../../core/api.service';

const review = (id: number) => ({ id, orderId: 10, text: `Review ${id}`, answer: '', comment: '', botFio: 'Account', botId: 3 }) as OrderReviewItem;
const settle = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };

describe('order review editor facade', () => {
  const create = () => {
    const route = new RouteEpochGuard(); route.change('order:10');
    const details = signal<OrderDetailsPayload | null>({ canEditReviews: true, canDeleteReviews: true, companyTitle: 'Company', reviews: [review(1), review(2)] } as OrderDetailsPayload);
    const api = { updateManagerOrderReviewText: vi.fn(), updateManagerOrderReviewAnswer: vi.fn(), updateManagerOrderReview: vi.fn(), deleteManagerOrderReview: vi.fn(), uploadManagerOrderReviewPhoto: vi.fn() };
    const confirm = vi.fn().mockResolvedValue(true); const prepareImage = vi.fn(); const pickImage = vi.fn(); const applyReview = vi.fn(); const focusText = vi.fn();
    const facade = new OrderReviewEditorFacade({ writes: new PageWriteTracker(), api, details, error: signal<string | null>(null), activeReviewIndex: signal(0), editingFieldKey: signal<string | null>(null), capture: () => route.capture(), accepts: ticket => route.accepts(ticket), confirm, prepareImage, pickImage, nativePhotoPickerAvailable: () => true,
      fieldValue: (item, field) => item[field] ?? '', sourceValue: (item, field) => item[field] ?? '', applyReview, clearDrafts: vi.fn(), focusText, scrollField: vi.fn(), blur: vi.fn(), errorMessage: (_, fallback) => fallback });
    return { facade, api, route, details, confirm, prepareImage, pickImage, applyReview, focusText };
  };
  afterEach(() => vi.useRealTimers());
  it('starts each form with a new blank password and independent draft', () => {
    const { facade } = create(); facade.openReviewEdit(review(1)); facade.setReviewEditField('text', 'Edited'); facade.setReviewEditField('botPassword', 'new-password');
    facade.closeReviewEdit(); facade.openReviewEdit(review(2));
    expect(facade.reviewEditDraft()?.text).toBe('Review 2'); expect(facade.reviewEditDraft()?.botPassword).toBe('');
    expect(create().facade.reviewEdit()).toBeNull();
  });
  it('does not focus a text modal after it was dismissed', () => {
    vi.useFakeTimers(); const { facade, focusText } = create(); facade.openReviewTextEdit(review(1), 'text'); facade.closeReviewTextEdit(); vi.runAllTimers(); expect(focusText).not.toHaveBeenCalled();
  });
  it('reserves deletion before confirmation and suppresses a confirmation after leave', async () => {
    const { facade, confirm, api } = create(); let resolve!: (value: boolean) => void; confirm.mockReturnValue(new Promise<boolean>(r => resolve = r));
    facade.openReviewEdit(review(1)); const first = facade.deleteReviewEdit(); const second = facade.deleteReviewEdit();
    expect(confirm).toHaveBeenCalledTimes(1); facade.deactivate(); facade.openReviewEdit(review(2)); resolve(true); await Promise.all([first, second]);
    expect(api.deleteManagerOrderReview).not.toHaveBeenCalled(); expect(facade.reviewEdit()?.id).toBe(2);
  });
  it('sends an immutable captured review draft once and keeps the write alive after leave', () => {
    const { facade, api, applyReview } = create(); const write = new Subject<OrderReviewItem>(); api.updateManagerOrderReview.mockReturnValue(write);
    facade.openReviewEdit(review(1)); facade.setReviewEditField('text', '  Updated  '); facade.saveReviewEdit(); facade.saveReviewEdit();
    expect(api.updateManagerOrderReview).toHaveBeenCalledTimes(1); expect(api.updateManagerOrderReview.mock.calls[0][0]).toBe(10); expect(api.updateManagerOrderReview.mock.calls[0][1]).toBe(1); expect(api.updateManagerOrderReview.mock.calls[0][2].text).toBe('Updated');
    facade.deactivate(); facade.openReviewEdit(review(2)); expect(write.observed).toBe(true); write.next(review(1)); write.complete();
    expect(applyReview).not.toHaveBeenCalled(); expect(facade.reviewEdit()?.id).toBe(2); expect(facade.reviewEditDraft()?.text).toBe('Review 2');
  });
  it('does not dispatch a photo upload if image preparation finishes after leave', async () => {
    const { facade, prepareImage, api } = create(); let resolve!: (file: File) => void; prepareImage.mockReturnValue(new Promise<File>(r => resolve = r));
    const file = new File(['image'], 'review.jpg', { type: 'image/jpeg' }); facade.openReviewEdit(review(1)); facade.uploadReviewPhoto({ target: { files: [file], value: 'selected' } } as unknown as Event);
    facade.deactivate(); facade.openReviewEdit(review(2)); resolve(file); await settle();
    expect(api.uploadManagerOrderReviewPhoto).not.toHaveBeenCalled(); expect(facade.reviewEdit()?.id).toBe(2);
  });
  it('does not apply a dispatched upload result to another review', async () => {
    const { facade, prepareImage, api, applyReview } = create(); const file = new File(['image'], 'review.jpg'); prepareImage.mockResolvedValue(file); const write = new Subject<OrderReviewItem>(); api.uploadManagerOrderReviewPhoto.mockReturnValue(write);
    facade.openReviewEdit(review(1)); facade.uploadReviewPhoto({ target: { files: [file], value: 'selected' } } as unknown as Event); await settle(); expect(write.observed).toBe(true);
    facade.deactivate(); facade.openReviewEdit(review(2)); write.next({ ...review(1), url: '/old.jpg' }); write.complete(); await settle();
    expect(applyReview).not.toHaveBeenCalled(); expect(facade.reviewEdit()?.id).toBe(2); expect(facade.reviewEditDraft()?.url).toBe('');
  });
});
