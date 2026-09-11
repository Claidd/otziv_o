import { signal } from '@angular/core';
import type { OrderDetailsPayload, OrderReviewItem, WorkerCredentialPreparation } from '../../core/api.service';
type ReviewCredentialCopyState = { botId?: number | null; botLoginAt?: number; botPasswordAt?: number };
type StoredReviewCredentialCopyState = ReviewCredentialCopyState & { reviewId: number; updatedAt: number };
type Dependencies = {
  openedFromWorkerAll(): boolean;
  details(): OrderDetailsPayload | null;
  needsRepair(review: OrderReviewItem): boolean;
  repairTitle(review: OrderReviewItem): string;
  isMutating(key: string): boolean;
  hasTemplateBot(review: OrderReviewItem): boolean;
};
/** Owns publication preparation and its bounded UI cache; credential values are never stored. */
export class ReviewPublicationFacade {
  private publishCredentialWaitTimer: ReturnType<typeof setInterval> | null = null;
  private readonly reviewPublishCredentialStorageKey = 'otziv-mobile-order-details-worker-all-publish-prep:v1';
  private readonly reviewPublishCredentialMaxAgeMs = 60 * 60 * 1000;
  private readonly publishCredentialWaitMs = 150_000;
  private readonly publishCredentialWaitSafetyBufferMs = 2_000;
  readonly copiedReviewCredentials = signal<Record<number, ReviewCredentialCopyState>>({});
  readonly reviewPublishWaitNow = signal(Date.now());
  constructor(private readonly deps: Dependencies) {}
  reviewPublishActionLocked(review: OrderReviewItem): boolean {
    if (!this.deps.openedFromWorkerAll() || review.publish) {
      return false;
    }

    if (this.deps.needsRepair(review)) {
      return false;
    }

    const copied = this.copiedReviewCredentials()[review.id];
    if (!copied?.botLoginAt || !copied.botPasswordAt || copied.botId !== (review.botId ?? null)) {
      return true;
    }

    return this.reviewPublishWaitLeftSeconds(review) > 0;
  }

  reviewPublishActionTitle(review: OrderReviewItem): string {
    if (!review.publish && this.deps.needsRepair(review)) {
      return this.deps.repairTitle(review);
    }

    if (!this.deps.openedFromWorkerAll() || review.publish) {
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

  reviewPublishActionLabel(review: OrderReviewItem): string {
    if (review.publish) {
      return 'ОПУБЛИКОВАНО';
    }

    if (this.deps.isMutating('publish-' + review.id)) {
      return 'ПУБЛИКУЮ...';
    }

    if (!this.deps.needsRepair(review)) {
      return 'ОПУБЛИКОВАТЬ';
    }

    return this.deps.hasTemplateBot(review) ? 'НУЖЕН ВЫГУЛ' : 'СМЕНИТЕ АККАУНТ';
  }

  reviewPublishWaitLeftSeconds(review: OrderReviewItem): number {
    if (!this.deps.openedFromWorkerAll() || review.publish) {
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

  refreshReviewPublishWaitTimer(): void {
    this.reviewPublishWaitNow.set(Date.now());
    if (!this.hasActiveReviewPublishWait()) {
      this.clearReviewPublishWaitTimer();
      return;
    }

    if (this.publishCredentialWaitTimer) {
      return;
    }

    this.publishCredentialWaitTimer = setInterval(() => {
      this.reviewPublishWaitNow.set(Date.now());
      if (!this.hasActiveReviewPublishWait()) {
        this.clearReviewPublishWaitTimer();
      }
    }, 1000);
  }

  hasActiveReviewPublishWait(): boolean {
    if (!this.deps.openedFromWorkerAll()) {
      return false;
    }

    return (this.deps.details()?.reviews ?? []).some((review) => this.reviewPublishWaitLeftSeconds(review) > 0);
  }

  clearReviewPublishWaitTimer(): void {
    if (!this.publishCredentialWaitTimer) {
      return;
    }

    clearInterval(this.publishCredentialWaitTimer);
    this.publishCredentialWaitTimer = null;
  }

  restoreReviewPublishCredentialPreparation(): void {
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

  applyServerReviewPublishCredentialPreparation(preparation?: WorkerCredentialPreparation | null): void {
    if (!this.deps.openedFromWorkerAll() || !preparation) {
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
        ...this.serverReviewCredentialCopyState(preparation)
      }
    });

    const review = this.deps.details()?.reviews?.find((item) => item.id === reviewId);
    if (review) {
      this.storeReviewPublishCredentialPreparation(review);
    }
  }

  serverReviewCredentialCopyState(preparation: WorkerCredentialPreparation): Omit<ReviewCredentialCopyState, 'botId'> {
    const loginCopied = typeof preparation.loginCopied === 'boolean'
      ? preparation.loginCopied
      : Boolean(preparation.loginCopiedAt);
    const passwordCopied = typeof preparation.passwordCopied === 'boolean'
      ? preparation.passwordCopied
      : Boolean(preparation.passwordCopiedAt);

    if (!loginCopied && !passwordCopied) {
      return {};
    }

    const now = Date.now();
    if (loginCopied && passwordCopied) {
      const remainingSeconds = Math.max(0, Number(preparation.remainingSeconds ?? 0));
      const lastCopyAt = preparation.ready === true
        ? now - this.publishCredentialWaitMs - this.publishCredentialWaitSafetyBufferMs
        : now - this.publishCredentialWaitMs + remainingSeconds * 1000;
      return {
        botLoginAt: lastCopyAt,
        botPasswordAt: lastCopyAt
      };
    }

    return {
      botLoginAt: loginCopied ? now : undefined,
      botPasswordAt: passwordCopied ? now : undefined
    };
  }

  storeReviewPublishCredentialPreparation(review: OrderReviewItem): void {
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

  clearReviewPublishCredentialPreparation(reviewId?: number): void {
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

  readStoredReviewPublishCredentialPreparation(clearExpired = true): StoredReviewCredentialCopyState | null {
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

  getSessionStorageItem(key: string): string | null {
    try {
      return window.sessionStorage.getItem(key);
    } catch {
      return null;
    }
  }

  setSessionStorageItem(key: string, value: string): void {
    try {
      window.sessionStorage.setItem(key, value);
    } catch {
      // Storage can be blocked; the current page still keeps the state in memory.
    }
  }

  removeSessionStorageItem(key: string): void {
    try {
      window.sessionStorage.removeItem(key);
    } catch {
      // This only affects UI state restoration.
    }
  }
}
