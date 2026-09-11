import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { AuthService } from './auth.service';
import { appEnvironment } from './app-environment';

export interface WorkerAccountActionCooldownState {
  enabled: boolean;
  durationSeconds: number;
  remainingSeconds: number;
  availableAt: string | null;
  serverNow: string;
}

export interface AccountActionAttempt {
  subject: string;
  generation: number;
  receivedState: boolean;
}

const STORAGE_PREFIX = 'otziv-worker-account-action-cooldown:v1:';
const LEGACY_CHANGED_KEY = 'otziv-worker-account-action-changed';

@Injectable({ providedIn: 'root' })
export class WorkerAccountActionCooldownService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  private subject: string | null = null;
  private generation = 0;
  private newestServerTime = -Infinity;
  private deadline = 0;
  private timer?: ReturnType<typeof setInterval>;
  private syncingGeneration: number | null = null;
  private syncAgain = false;
  private nextSyncAt = 0;
  private readonly pending = signal(false);
  readonly remainingSeconds = signal(0);
  readonly durationSeconds = signal(60);
  readonly enabled = signal(true);
  readonly isSpecialist = computed(() => {
    // Reading the token signal also reacts to changed roles after token refresh.
    this.auth.tokenParsed();
    return this.auth.authenticated()
      && this.auth.hasRealmRole('WORKER')
      && !this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
  });
  readonly locked = computed(() => this.isSpecialist()
    && (this.pending() || (this.enabled() && this.remainingSeconds() > 0)));
  readonly countdown = computed(() => {
    const seconds = this.remainingSeconds();
    return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`;
  });
  readonly title = computed(() => this.remainingSeconds() > 0
    ? `Смена и блокировка доступны через ${this.countdown()}`
    : 'Выполняется действие с аккаунтом');

  constructor() {
    effect(() => {
      const subject = this.isSpecialist() ? this.auth.tokenParsed()?.sub ?? null : null;
      untracked(() => this.useSubject(subject));
    });
    const resume = () => {
      if (document.visibilityState === 'visible') this.sync();
    };
    const storage = (event: StorageEvent) => {
      if (event.key === LEGACY_CHANGED_KEY) {
        this.sync();
        return;
      }
      if (!this.subject || event.key !== this.storageKey() || !event.newValue) return;
      this.restoreSnapshot(event.newValue);
    };
    window.addEventListener('focus', resume);
    document.addEventListener('visibilitychange', resume);
    window.addEventListener('storage', storage);
    this.destroyRef.onDestroy(() => {
      this.stopTimer();
      window.removeEventListener('focus', resume);
      document.removeEventListener('visibilitychange', resume);
      window.removeEventListener('storage', storage);
    });
  }

  /** Called before dispatch, after any confirmation; shared by every API entry point. */
  begin(): AccountActionAttempt | null {
    this.ensureSubject();
    if (!this.subject || this.locked()) return null;
    this.pending.set(true);
    if (this.enabled()) {
      this.deadline = performance.now() + this.durationSeconds() * 1000;
      this.tick();
    }
    return { subject: this.subject, generation: this.generation, receivedState: false };
  }

  receive(attempt: AccountActionAttempt, headers: HttpHeaders, body?: unknown): void {
    if (!this.isCurrent(attempt)) return;
    const serverNow = headers.get('X-Worker-Account-Action-Server-Now');
    const enabled = headers.get('X-Worker-Account-Action-Enabled');
    const duration = headers.get('X-Worker-Account-Action-Duration-Seconds');
    const state = serverNow && enabled !== null && duration !== null
      ? {
          enabled: enabled === 'true', durationSeconds: Number(duration), serverNow,
          availableAt: headers.get('X-Worker-Account-Action-Available-At') || null, remainingSeconds: 0
        }
      : body;
    if (isCooldownState(state)) {
      attempt.receivedState = true;
      this.applyState(state, true);
      try {
        localStorage.setItem(LEGACY_CHANGED_KEY, `${Date.now()}-${Math.random()}`);
      } catch { /* Legacy tabs also reconcile when they resume. */ }
    }
  }

  finish(attempt: AccountActionAttempt): void {
    if (!this.isCurrent(attempt)) return;
    this.pending.set(false);
    // Lost responses are reconciled with a read, never by repeating the mutation.
    if (!attempt.receivedState) {
      this.sync(true);
    }
  }

  blockedError(): HttpErrorResponse {
    return new HttpErrorResponse({
      status: 429,
      error: { code: 'WORKER_ACCOUNT_ACTION_COOLDOWN', message: this.title(), remainingSeconds: this.remainingSeconds() }
    });
  }

  sync(afterMutation = false): void {
    this.ensureSubject();
    if (!this.subject) return;
    const generation = this.generation;
    if (this.syncingGeneration === generation) {
      this.syncAgain ||= afterMutation;
      return;
    }
    this.syncingGeneration = generation;
    this.nextSyncAt = performance.now() + 30_000;
    this.http.get<WorkerAccountActionCooldownState>(`${appEnvironment.apiBaseUrl}/api/worker/account-action-cooldown`)
      .subscribe({
        next: (state) => {
          if (generation === this.generation && isCooldownState(state)) this.applyState(state, true);
        },
        error: () => this.finishSync(generation),
        complete: () => this.finishSync(generation)
      });
  }

  private finishSync(generation: number): void {
    if (generation !== this.generation || this.syncingGeneration !== generation) return;
    this.syncingGeneration = null;
    if (this.syncAgain) {
      this.syncAgain = false;
      this.sync();
    }
  }

  private ensureSubject(): void {
    this.useSubject(this.isSpecialist() ? this.auth.tokenParsed()?.sub ?? null : null);
  }

  private useSubject(subject: string | null): void {
    if (subject === this.subject) return;
    this.subject = subject;
    this.generation += 1;
    this.newestServerTime = -Infinity;
    this.deadline = 0;
    this.pending.set(false);
    this.enabled.set(true);
    this.durationSeconds.set(60);
    this.syncingGeneration = null;
    this.syncAgain = false;
    this.nextSyncAt = 0;
    this.tick();
    if (!subject) return;
    try {
      const saved = localStorage.getItem(this.storageKey());
      if (saved) this.restoreSnapshot(saved);
    } catch { /* The server remains authoritative if browser storage is unavailable. */ }
    this.sync();
  }

  private isCurrent(attempt: AccountActionAttempt): boolean {
    this.ensureSubject();
    return attempt.subject === this.subject && attempt.generation === this.generation;
  }

  private applyState(state: WorkerAccountActionCooldownState, broadcast: boolean, elapsedMs = 0): void {
    const serverTime = Date.parse(state.serverNow);
    // A delayed GET or response from another tab must not replace newer server state.
    if (serverTime < this.newestServerTime) return;
    this.newestServerTime = serverTime;
    this.enabled.set(state.enabled);
    this.durationSeconds.set(state.durationSeconds);
    const remaining = state.enabled && state.availableAt
      ? Math.max(0, Date.parse(state.availableAt) - serverTime - elapsedMs) : 0;
    this.deadline = performance.now() + remaining;
    this.tick();
    if (broadcast && this.subject) {
      try {
        localStorage.setItem(this.storageKey(), JSON.stringify({ state, receivedAt: Date.now() }));
      } catch { /* Cross-tab storage is optional; the server still rejects concurrent attempts. */ }
    }
  }

  private restoreSnapshot(raw: string): void {
    try {
      const snapshot = JSON.parse(raw);
      if (!isCooldownState(snapshot.state) || !Number.isFinite(snapshot.receivedAt)) return;
      const elapsed = Math.max(0, Date.now() - snapshot.receivedAt);
      this.applyState(snapshot.state, false, elapsed);
    } catch { /* Ignore old or invalid local state and fetch authoritative state. */ }
  }

  private tick(): void {
    // Monotonic elapsed time avoids countdown jumps when the computer clock changes.
    this.remainingSeconds.set(Math.max(0, Math.ceil((this.deadline - performance.now()) / 1000)));
    // A changed admin setting (including zero) reaches an already-visible tab promptly.
    if (this.subject && this.remainingSeconds() > 0 && document.visibilityState === 'visible'
      && performance.now() >= this.nextSyncAt) this.sync();
    if (this.remainingSeconds() > 0 && !this.timer) this.timer = setInterval(() => this.tick(), 250);
    else if (this.remainingSeconds() === 0) this.stopTimer();
  }

  private stopTimer(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
  }

  private storageKey(): string {
    return `${STORAGE_PREFIX}${this.subject}`;
  }
}

function isCooldownState(value: unknown): value is WorkerAccountActionCooldownState {
  if (!value || typeof value !== 'object') return false;
  const state = value as WorkerAccountActionCooldownState;
  return typeof state.enabled === 'boolean' && Number.isInteger(state.durationSeconds)
    && state.durationSeconds >= 0 && state.durationSeconds <= 3600
    && Number.isFinite(Date.parse(state.serverNow))
    && (state.availableAt === null || Number.isFinite(Date.parse(state.availableAt)));
}
