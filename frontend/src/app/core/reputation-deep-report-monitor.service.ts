import { Injectable, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { DeepCompanyResearchJob, ReputationAiApi } from './reputation-ai.api';
import { ToastService } from '../shared/toast.service';

const ACTIVE_DEEP_REPORT_COMPANY_KEY = 'otziv.reputationAi.activeDeepReportCompanyId';
const DEEP_REPORT_FAST_POLL_INTERVAL_MS = 30_000;
const DEEP_REPORT_SLOW_POLL_INTERVAL_MS = 90_000;
const DEEP_REPORT_FAST_POLL_WINDOW_MS = 3 * 60_000;

@Injectable({ providedIn: 'root' })
export class ReputationDeepReportMonitorService {
  readonly currentJob = signal<DeepCompanyResearchJob | null>(null);

  private pollSubscription: Subscription | null = null;
  private pollTimeoutId: number | null = null;
  private activeCompanyId: number | null = null;
  private watchStartedAtMs = 0;

  constructor(
    private readonly api: ReputationAiApi,
    private readonly toastService: ToastService
  ) {}

  restore(): void {
    const stored = Number(window.localStorage.getItem(ACTIVE_DEEP_REPORT_COMPANY_KEY));
    if (Number.isFinite(stored) && stored > 0) {
      this.watch(Math.trunc(stored));
    }
  }

  watch(companyId: number): void {
    const id = Math.trunc(Number(companyId));
    if (!Number.isFinite(id) || id <= 0) {
      return;
    }

    window.localStorage.setItem(ACTIVE_DEEP_REPORT_COMPANY_KEY, String(id));
    if (this.activeCompanyId === id && this.pollSubscription) {
      return;
    }

    this.stopPolling();
    this.activeCompanyId = id;
    this.watchStartedAtMs = Date.now();
    this.pollLatestJob(id);
  }

  private pollLatestJob(companyId: number): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = this.api.latestDeepResearchJob(companyId)
      .subscribe({
        next: (job) => {
          this.handleJob(job);
          if (this.activeCompanyId === companyId && this.isActiveJob(job)) {
            this.scheduleNextPoll(companyId);
          }
        },
        error: () => this.stopPolling()
      });
  }

  clear(): void {
    window.localStorage.removeItem(ACTIVE_DEEP_REPORT_COMPANY_KEY);
    this.stopPolling();
    this.currentJob.set(null);
  }

  private handleJob(job: DeepCompanyResearchJob): void {
    this.currentJob.set(job);
    if (job.status === 'DONE') {
      window.localStorage.removeItem(ACTIVE_DEEP_REPORT_COMPANY_KEY);
      this.stopPolling();
      this.toastService.success(
        'Глубокий отчёт готов',
        `${job.companyName || 'Компания #' + job.companyId}: можно открыть AI-помощник`,
        { label: 'Открыть', routerLink: '/admin/reputation-ai' }
      );
      return;
    }

    if (job.status === 'FAILED') {
      window.localStorage.removeItem(ACTIVE_DEEP_REPORT_COMPANY_KEY);
      this.stopPolling();
      this.toastService.error('Глубокий отчёт не собрался', this.humanizeAiError(job.errorMessage));
    }
  }

  private scheduleNextPoll(companyId: number): void {
    this.clearPollTimeout();
    this.pollTimeoutId = window.setTimeout(() => {
      this.pollTimeoutId = null;
      if (this.activeCompanyId === companyId) {
        this.pollLatestJob(companyId);
      }
    }, this.nextPollDelayMs());
  }

  private nextPollDelayMs(): number {
    const elapsedMs = Date.now() - this.watchStartedAtMs;
    return elapsedMs < DEEP_REPORT_FAST_POLL_WINDOW_MS
      ? DEEP_REPORT_FAST_POLL_INTERVAL_MS
      : DEEP_REPORT_SLOW_POLL_INTERVAL_MS;
  }

  private isActiveJob(job: DeepCompanyResearchJob): boolean {
    return job.status === 'QUEUED' || job.status === 'RUNNING';
  }

  private stopPolling(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = null;
    this.clearPollTimeout();
    this.activeCompanyId = null;
    this.watchStartedAtMs = 0;
  }

  private clearPollTimeout(): void {
    if (this.pollTimeoutId !== null) {
      window.clearTimeout(this.pollTimeoutId);
      this.pollTimeoutId = null;
    }
  }

  private humanizeAiError(message: string | null | undefined): string {
    const text = (message ?? '').trim();
    if (!text) {
      return 'Попробуйте запустить ещё раз.';
    }

    const lower = text.toLowerCase();
    if (lower.includes('rate limit reached')
      || lower.includes('tokens per min')
      || lower.includes('rate_limit_exceeded')
      || lower.includes('лимит токен')) {
      const retry = text.match(/try again in ([0-9.]+)s/i)?.[1];
      const retryHint = retry ? ` API просит повторить примерно через ${retry} с.` : '';
      return `OpenAI временно упёрся в лимит токенов в минуту.${retryHint} Подождите 1-2 минуты и запустите отчёт снова.`;
    }

    return text;
  }
}
