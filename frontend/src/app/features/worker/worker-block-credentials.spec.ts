import { signal } from '@angular/core';
import { describe, it, expect, vi } from 'vitest';
import { WorkerBoardComponent } from './worker-board.component';
import type { WorkerReviewItem } from '../../core/worker.api';

describe('worker block credential copy completion', () => {
  const review = { id: 197623, botId: 871819 } as WorkerReviewItem;
  function board() {
    return Object.assign(Object.create(WorkerBoardComponent.prototype), {
      accountCredentialCopies: signal({}),
      isOnlyWorkerRole: () => true,
      activeWorkerSection: () => 'nagul',
      copyDeferredText: vi.fn().mockResolvedValue(true),
      showCopySuccess: vi.fn(), markPublishCredentialCopied: vi.fn(),
      toastService: { error: vi.fn() }, errorMessage: () => 'Copy failed',
      accountActionCooldown: { locked: () => false },
      actionFacade: { deactivateReviewBot: vi.fn() }
    });
  }

  it('blocks the handler until both clipboard writes complete', async () => {
    const component = board();
    component.deactivateReviewBot(review);
    expect(component['actionFacade'].deactivateReviewBot).not.toHaveBeenCalled();
    await component.copyReviewValue(review, 'login');
    expect(component.blockLockedByCredentials(review)).toBe(true);
    await component.copyReviewValue(review, 'password');
    expect(component.blockLockedByCredentials(review)).toBe(false);
    component.deactivateReviewBot(review);
    expect(component['actionFacade'].deactivateReviewBot).toHaveBeenCalledOnce();
  });

  it.each([false, new Error('credential request failed')])('keeps block disabled on failed copy (%s)', async result => {
    const component = board();
    await component.copyReviewValue(review, 'login');
    if (result instanceof Error) component['copyDeferredText'].mockRejectedValueOnce(result);
    else component['copyDeferredText'].mockResolvedValueOnce(result);
    await component.copyReviewValue(review, 'password');
    expect(component.blockLockedByCredentials(review)).toBe(true);
  });
});
