import { Injectable, signal } from '@angular/core';
import { Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { DeepCompanyResearchJob, ReputationAiApi } from './reputation-ai.api';
import { ToastService } from '../shared/toast.service';

const ACTIVE_DEEP_REPORT_COMPANY_KEY = 'otziv.reputationAi.activeDeepReportCompanyId';
const DEEP_REPORT_POLL_INTERVAL_MS = 20_000;

@Injectable({ providedIn: 'root' })
export class ReputationDeepReportMonitorService {
  readonly currentJob = signal<DeepCompanyResearchJob | null>(null);

  private pollSubscription: Subscription | null = null;
  private activeCompanyId: number | null = null;

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
    this.pollSubscription = timer(0, DEEP_REPORT_POLL_INTERVAL_MS)
      .pipe(switchMap(() => this.api.latestDeepResearchJob(id)))
      .subscribe({
        next: (job) => this.handleJob(job),
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
      this.toastService.error('Глубокий отчёт не собрался', job.errorMessage || 'Попробуйте запустить ещё раз');
    }
  }

  private stopPolling(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = null;
    this.activeCompanyId = null;
  }
}
