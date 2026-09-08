import { computed, signal } from '@angular/core';
import { finalize, Subscription } from 'rxjs';
import type { CompanyDeepReportState } from '../../core/api.service';
import type { OrderCompanyReportApi } from '../../core/order-company-report.api';
import type { RouteEpochTicket } from '../../core/route-epoch.guard';
import type { PageWriteTracker } from '../../core/page-write-tracker';
import { safeHttpsExternalUrl } from '../../shared/external-navigation';
import { CompanyReportPresentation, type CompanyReport, type CompanyReportFact, type CompanyReportSource } from './company-report.presentation';

type Dependencies = {
  writes: Pick<PageWriteTracker, 'track'>;
  api: Pick<OrderCompanyReportApi, 'getManagerOrderCompanyReport' | 'startManagerOrderCompanyReport' | 'refreshManagerOrderCompanyReport'>;
  orderId(): number | null;
  capture(): RouteEpochTicket | null;
  accepts(ticket: RouteEpochTicket): boolean;
  canRefresh(): boolean;
  errorMessage(error: unknown, fallback: string): string;
};
type ReportTicket = { route: RouteEpochTicket; generation: number; orderId: number };

/** A single screen owns its report session. Reads may be canceled; a dispatched report job must settle. */
export class OrderCompanyReportFacade {
  private readonly presentation = new CompanyReportPresentation();
  private generation = 0;
  private read?: Subscription;
  private readonly commands = new Set<number>();
  readonly companyReportVisible = signal(false);
  readonly companyReportLoading = signal(false);
  readonly companyReportError = signal<string | null>(null);
  readonly companyReportState = signal<CompanyDeepReportState | null>(null);
  readonly hasReadyCompanyReport = computed(() => !!this.companyReportState()?.latestJob?.report);
  readonly companyReportBusy = computed(() => this.companyReportLoading() || !!this.companyReportState()?.activeJob);
  readonly companyReport = computed(() => (this.companyReportState()?.latestJob?.report ?? null) as CompanyReport | null);
  readonly companyReportSections = computed(() => this.presentation.buildCompanyReportSections(this.companyReport()));
  readonly companyReportReviewIdeas = computed(() => this.presentation.cleanStringList(this.companyReport()?.reviewIdeas).slice(0, 12));
  readonly companyReportWarnings = computed(() => this.presentation.cleanStringList(this.companyReport()?.warnings));
  readonly companyReportSources = computed(() => (this.companyReport()?.sources ?? [])

    .filter((source): source is CompanyReportSource => !!source && Boolean(this.presentation.cleanText(source.title) || this.presentation.cleanText(source.url) || this.presentation.cleanText(source.note)))

    .map((source) => ({ ...source, url: safeHttpsExternalUrl(source.url) ?? '' }))

    .slice(0, 10));
  readonly companyReportConfirmedFacts = computed(() => (this.companyReport()?.factSnapshot?.confirmedFacts ?? [])

    .filter((fact): fact is CompanyReportFact => !!fact && Boolean(this.presentation.cleanText(fact.label) || this.presentation.cleanText(fact.value) || this.presentation.cleanText(fact.evidence)))

    .slice(0, 8));

  constructor(private readonly deps: Dependencies) {}

  openCompanyReport(): void { this.readCompanyReport(true); }

  /** Reconcile a visible report after an old command, never start another job. */
  reconcileIfVisible(): void {
    if (this.companyReportVisible()) this.readCompanyReport(false);
  }

  private readCompanyReport(allowStart: boolean): void {
    const orderId = this.deps.orderId();
    const route = this.deps.capture();
    if (!orderId || !route || (allowStart && this.companyReportLoading())) return;
    this.cancelRead();
    const ticket = { orderId, route, generation: this.generation };
    this.companyReportVisible.set(true);
    this.companyReportLoading.set(true);
    this.companyReportError.set(null);
    const read = this.deps.api.getManagerOrderCompanyReport(orderId).pipe(finalize(() => {
      if (this.accepts(ticket)) { this.read = undefined; this.companyReportLoading.set(false); }
    })).subscribe({
      next: state => {
        if (!this.accepts(ticket)) return;
        this.companyReportState.set(state);
        this.companyReportLoading.set(false);
        if (allowStart && !state.latestJob?.report && !state.activeJob && state.canStart) this.startCompanyReport();
      },
      error: error => {
        if (this.accepts(ticket)) this.companyReportError.set(this.deps.errorMessage(error, 'Не удалось проверить отчет о компании'));
      }
    });
    if (!read.closed && this.accepts(ticket)) this.read = read;
  }

  closeCompanyReport(): void {
    this.cancelRead();
    this.companyReportVisible.set(false);
    this.companyReportLoading.set(false);
  }

  canRefreshCompanyReport(): boolean { return this.deps.canRefresh(); }
  startCompanyReport(): void { this.start(false); }
  refreshCompanyReport(): void {
    if (!this.canRefreshCompanyReport()) {
      this.companyReportVisible.set(true);
      this.companyReportError.set('Обновлять отчет может только владелец или администратор.');
      return;
    }
    this.start(true);
  }

  private start(refresh: boolean): void {
    const orderId = this.deps.orderId();
    const route = this.deps.capture();
    if (!orderId || !route || this.commands.has(orderId) || this.companyReportLoading()) return;
    this.cancelRead();
    const ticket = { orderId, route, generation: this.generation };
    this.commands.add(orderId);
    this.companyReportVisible.set(true);
    this.companyReportLoading.set(true);
    this.companyReportError.set(null);
    const request = refresh ? this.deps.api.refreshManagerOrderCompanyReport(orderId) : this.deps.api.startManagerOrderCompanyReport(orderId);
    this.deps.writes.track(request).pipe(finalize(() => {
      this.commands.delete(orderId);
      if (this.accepts(ticket)) this.companyReportLoading.set(false);
    })).subscribe({
      next: state => { if (this.accepts(ticket)) this.companyReportState.set(state); },
      error: error => {
        if (this.accepts(ticket)) this.companyReportError.set(this.deps.errorMessage(error, refresh ? 'Не удалось обновить отчет о компании' : 'Не удалось запустить отчет о компании'));
      }
    });
  }

  private cancelRead(): void {
    this.generation += 1;
    const read = this.read;
    this.read = undefined;
    read?.unsubscribe();
  }
  private accepts(ticket: ReportTicket): boolean {
    return ticket.generation === this.generation && ticket.orderId === this.deps.orderId() && this.deps.accepts(ticket.route);
  }

  companyReportStatus(): string {

    const state = this.companyReportState();

    if (!state) {

      return 'Отчет еще не проверялся.';

    }

    if (state.activeJob) {

      return 'Отчет готовится в фоне. Можно продолжать работать с отзывами.';

    }

    if (state.latestJob?.report) {

      return 'Отчет о компании готов.';

    }

    return state.unavailableReason || 'Готового отчета пока нет.';

  }

  companyReportCompletedAt(): string {

    const value = this.companyReportState()?.latestJob?.completedAt

      ?? this.companyReport()?.createdAt

      ?? this.companyReportState()?.latestJob?.updatedAt

      ?? '';

    if (!value) {

      return '';

    }



    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {

      return String(value).slice(0, 16).replace('T', ' ');

    }



    return new Intl.DateTimeFormat('ru-RU', {

      day: '2-digit',

      month: '2-digit',

      year: '2-digit',

      hour: '2-digit',

      minute: '2-digit'

    }).format(date);

  }
}
