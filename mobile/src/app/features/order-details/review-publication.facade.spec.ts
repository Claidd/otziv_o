import { ReviewPublicationFacade } from './review-publication.facade';
import type { OrderDetailsPayload, OrderReviewItem, WorkerCredentialPreparation } from '../../core/api.service';

const review = { id: 1, botId: 3, publish: false } as OrderReviewItem;
const create = () => new ReviewPublicationFacade({ openedFromWorkerAll: () => true, details: () => ({ reviews: [review] }) as OrderDetailsPayload, needsRepair: () => false, repairTitle: () => 'Repair', isMutating: () => false, hasTemplateBot: () => false });

describe('review publication preparation', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-07T00:00:00Z')); window.sessionStorage.clear(); });
  afterEach(() => { vi.useRealTimers(); window.sessionStorage.clear(); });
  it('uses server preparation and the safety buffer without storing credential values', () => {
    const facade = create(); facade.applyServerReviewPublishCredentialPreparation({ scope: 'PUBLISH', reviewId: 1, botId: 3, loginCopied: true, passwordCopied: true, remainingSeconds: 3, ready: false } as WorkerCredentialPreparation); facade.refreshReviewPublishWaitTimer();
    expect(facade.reviewPublishActionLocked(review)).toBe(true); vi.advanceTimersByTime(5000); expect(facade.reviewPublishActionLocked(review)).toBe(false);
    const cached = window.sessionStorage.getItem('otziv-mobile-order-details-worker-all-publish-prep:v1')!; expect(Object.keys(JSON.parse(cached)).sort()).toEqual(['botId', 'botLoginAt', 'botPasswordAt', 'reviewId', 'updatedAt']);
    facade.clearReviewPublishWaitTimer();
  });
  it('invalidates preparation when scope or bot changes and drops expired restoration', () => {
    const facade = create(); facade.applyServerReviewPublishCredentialPreparation({ scope: 'PUBLISH', reviewId: 1, botId: 3, loginCopied: true, passwordCopied: true, ready: true } as WorkerCredentialPreparation);
    expect(facade.reviewPublishActionLocked({ ...review, botId: 4 })).toBe(true);
    vi.advanceTimersByTime(3600001); const restored = create(); restored.restoreReviewPublishCredentialPreparation(); expect(restored.copiedReviewCredentials()).toEqual({});
    facade.applyServerReviewPublishCredentialPreparation({ scope: 'EDIT', reviewId: 1, botId: 3 } as WorkerCredentialPreparation); expect(facade.copiedReviewCredentials()).toEqual({});
  });
  it('stops the countdown on leave without changing another facade', () => {
    const facade = create(); facade.applyServerReviewPublishCredentialPreparation({ scope: 'PUBLISH', reviewId: 1, botId: 3, loginCopied: true, passwordCopied: true, remainingSeconds: 150 } as WorkerCredentialPreparation); facade.refreshReviewPublishWaitTimer(); facade.clearReviewPublishWaitTimer();
    const stopped = facade.reviewPublishWaitNow(); vi.advanceTimersByTime(5000); expect(facade.reviewPublishWaitNow()).toBe(stopped); expect(create().copiedReviewCredentials()).toEqual({});
  });
});
