import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { AuthService } from './auth.service';
import { appEnvironment } from './app-environment';
import type { WorkerBoard, WorkerReviewItem } from './worker.api';

@Injectable({ providedIn: 'root' })
export class WorkerMediaContextService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly attempted = new Map<string, number>();

  boardLoaded(board: WorkerBoard): void {
    if (board.warning || board.accessRestriction?.restricted) return;
    const review = board.reviews.content[0];
    const order = board.orders.content[0];
    if (['nagul', 'publish', 'recovery', 'bad'].includes(board.section) && review) {
      this.send('BOARD', this.card(review), board.section);
    } else if (['new', 'correct'].includes(board.section) && order) {
      this.send('BOARD', { entityType: 'order', entityId: order.id }, board.section);
    }
  }

  cardHint(action: 'UNSAVED_CHANGES' | 'EMPTY_TEXT', review: WorkerReviewItem): void {
    this.send(action, this.card(review));
  }

  requestFailed(error: unknown): void {
    const status = (error as { status?: number } | null)?.status;
    if (typeof status === 'number' && status >= 500) this.send('SITE_ERROR', {});
    else if (status === 403) this.send('NETWORK_BLOCKED', {});
  }

  private card(review: WorkerReviewItem): { entityType: string; entityId: number } {
    if (review.recoveryTask && review.recoveryTaskId) return { entityType: 'recovery_task', entityId: review.recoveryTaskId };
    if (review.badTask && review.badTaskId) return { entityType: 'bad_review_task', entityId: review.badTaskId };
    return { entityType: 'review', entityId: review.id };
  }

  private send(action: string, card: { entityType?: string; entityId?: number }, section?: string): void {
    if (!this.auth.hasAnyRealmRole(['WORKER']) || this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER'])) return;
    const key = `${action}:${section ?? ''}`;
    const now = Date.now();
    if (now - (this.attempted.get(key) ?? 0) < 120_000) return;
    this.attempted.set(key, now);
    this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/worker/notification-media/context`,
      { action, ...card, section }).subscribe({ error: () => { /* Optional media never interferes with work. */ } });
  }
}
