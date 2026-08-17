import { Component, EventEmitter, Input, Output } from '@angular/core';
import type { ContractorPaymentSummary } from '../core/api.service';
import {
  contractorCoverageStartLabel,
  contractorPaymentMetricDescriptionId,
  contractorPaymentMetrics,
  contractorPaymentModeClass,
  contractorPaymentModeLabel
} from './contractor-payment-summary';
import { MobileContractorPaymentMetricHelpComponent } from './mobile-contractor-payment-metric-help.component';

@Component({
  selector: 'app-mobile-contractor-payment-summary',
  standalone: true,
  imports: [MobileContractorPaymentMetricHelpComponent],
  template: `
    <section class="contractor-payment-summary" aria-label="Расчёты по вознаграждениям">
      <header class="summary-title">
        <span class="material-icons-sharp" aria-hidden="true">account_balance_wallet</span>
        <div>
          <p>МОИ РАСЧЁТЫ</p>
          <h2>Вознаграждения и переводы</h2>
        </div>
      </header>

      @if (loading) {
        <p class="summary-state" role="status" aria-live="polite">Загружаю расчёты…</p>
      } @else if (error) {
        <div class="summary-state summary-state--error" role="alert">
          <span>{{ error }}</span>
          <button type="button" (click)="retry.emit()">Повторить</button>
        </div>
      } @else if (summaries.length === 0) {
        <p class="summary-state" role="status" aria-live="polite">Платёжный профиль для вашей роли ещё не создан.</p>
      } @else {
        <div class="role-list">
          @for (summary of summaries; track summary.profileId) {
            <article
              class="contractor-role"
              [attr.aria-labelledby]="roleHeadingId(summary.profileId)"
            >
              <header>
                <div>
                  <strong [id]="roleHeadingId(summary.profileId)">{{ roleLabel(summary.role) }}</strong>
                  <small>Начисления, счета и поступления</small>
                </div>
                <span
                  class="payment-mode"
                  [class.payment-mode--live]="modeClass(summary) === 'live'"
                  [class.payment-mode--shadow]="modeClass(summary) === 'shadow'"
                  [class.payment-mode--disabled]="modeClass(summary) === 'disabled'"
                  aria-live="polite"
                >
                  {{ modeLabel(summary) }}
                </span>
              </header>

              @if (!summary.currentMonthCoverageComplete) {
                <p class="coverage-note">
                  Новый расчёт по этому профилю показан с даты подключения —
                  {{ coverageStartLabel(summary) }}. Полная история за месяц остаётся
                  в прежних показателях и графиках ниже.
                </p>
              }

              <p class="metrics-hint">
                Нажмите на название показателя, чтобы показать или скрыть пояснение.
              </p>

              <div class="contractor-metric-grid">
                @for (metric of metrics(summary); track metric.key) {
                  <section
                    class="contractor-metric"
                    [class.contractor-metric--available]="metric.tone === 'available'"
                    [class.contractor-metric--credit]="metric.tone === 'credit'"
                  >
                    <app-mobile-contractor-payment-metric-help
                      [label]="metric.label"
                      [description]="metric.description"
                      [descriptionId]="descriptionId(summary.profileId, metric.key)"
                    />
                    <strong>{{ money(metric.totalKopecks) }}</strong>
                    @if (metric.monthKopecks !== undefined) {
                      <small>за месяц {{ money(metric.monthKopecks) }}</small>
                    }
                  </section>
                }
              </div>
            </article>
          }
        </div>
        <p class="history-note">
          Полная историческая статистика вознаграждений сохранена в прежних графиках.
        </p>
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
      order: 18;
      min-width: 0;
      max-width: 100%;
    }

    .contractor-payment-summary {
      display: grid;
      gap: 0.65rem;
      min-width: 0;
      max-width: 100%;
      border: 1px solid rgba(108, 155, 207, 0.24);
      border-radius: 1rem;
      padding: 0.72rem;
      background: var(--otziv-white);
      box-shadow: 0 0.8rem 1.6rem rgba(132, 139, 200, 0.1);
      box-sizing: border-box;
    }

    .summary-title {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.55rem;
    }

    .summary-title > .material-icons-sharp {
      display: grid;
      width: 2.25rem;
      height: 2.25rem;
      place-items: center;
      border-radius: 0.72rem;
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }

    .summary-title p,
    .summary-title h2,
    .contractor-role p {
      margin: 0;
    }

    .summary-title p {
      color: var(--otziv-info);
      font-size: 0.64rem;
      font-weight: 1000;
      letter-spacing: 0.02em;
    }

    .summary-title h2 {
      margin-top: 0.08rem;
      color: var(--otziv-dark);
      font-size: 1rem;
      font-weight: 1000;
      line-height: 1.18;
      overflow-wrap: anywhere;
    }

    .role-list {
      display: grid;
      gap: 0.62rem;
      min-width: 0;
    }

    .contractor-role {
      display: grid;
      gap: 0.52rem;
      min-width: 0;
      border: 1px solid rgba(108, 155, 207, 0.22);
      border-radius: 0.9rem;
      padding: 0.62rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, #f6faff 100%);
      box-sizing: border-box;
    }

    .contractor-role > header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 0.48rem;
      min-width: 0;
    }

    .contractor-role > header > div {
      display: grid;
      gap: 0.12rem;
      min-width: 0;
    }

    .contractor-role > header strong {
      color: var(--otziv-dark);
      font-size: 0.8rem;
      font-weight: 1000;
    }

    .contractor-role > header small,
    .contractor-metric small {
      color: var(--otziv-info);
      font-size: 0.64rem;
      font-weight: 800;
      line-height: 1.3;
    }

    .payment-mode {
      flex: 0 1 auto;
      max-width: 62%;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 999px;
      padding: 0.26rem 0.46rem;
      color: var(--otziv-info);
      background: rgba(103, 116, 131, 0.08);
      font-size: 0.58rem;
      font-weight: 1000;
      line-height: 1.25;
      text-align: center;
      overflow-wrap: anywhere;
    }

    .payment-mode--live {
      border-color: rgba(27, 156, 133, 0.28);
      color: var(--otziv-success);
      background: rgba(27, 156, 133, 0.1);
    }

    .payment-mode--shadow {
      border-color: rgba(198, 142, 30, 0.3);
      color: #9b6d00;
      background: rgba(231, 180, 52, 0.12);
    }

    .payment-mode--disabled {
      color: var(--otziv-info);
      background: rgba(103, 116, 131, 0.08);
    }

    .coverage-note,
    .metrics-hint,
    .history-note {
      color: var(--otziv-info);
      font-size: 0.65rem;
      font-weight: 750;
      line-height: 1.4;
      overflow-wrap: anywhere;
    }

    .coverage-note {
      border-left: 3px solid #d7ad3a;
      border-radius: 0.45rem;
      padding: 0.42rem 0.5rem;
      background: rgba(231, 180, 52, 0.1);
    }

    .metrics-hint {
      margin: 0;
    }

    .contractor-metric-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.46rem;
      min-width: 0;
    }

    .contractor-metric {
      display: grid;
      min-width: 0;
      align-content: start;
      border: 1px solid rgba(103, 116, 131, 0.15);
      border-radius: 0.78rem;
      padding: 0.42rem 0.5rem 0.52rem;
      background: var(--otziv-white);
      box-sizing: border-box;
    }

    .contractor-metric--available {
      border-color: rgba(27, 156, 133, 0.26);
      background: rgba(27, 156, 133, 0.06);
    }

    .contractor-metric--credit {
      border-color: rgba(234, 51, 98, 0.24);
      background: rgba(234, 51, 98, 0.06);
    }

    .contractor-metric > strong {
      color: var(--otziv-dark);
      font-size: 0.94rem;
      font-weight: 1000;
      line-height: 1.22;
      overflow-wrap: break-word;
      hyphens: auto;
      font-variant-numeric: tabular-nums;
    }

    .contractor-metric > small {
      margin-top: 0.14rem;
      overflow-wrap: break-word;
      hyphens: auto;
    }

    .history-note {
      margin: 0;
    }

    .summary-state {
      margin: 0;
      border-radius: 0.78rem;
      padding: 0.68rem;
      color: var(--otziv-info);
      background: var(--otziv-light);
      font-size: 0.72rem;
      font-weight: 850;
      line-height: 1.4;
    }

    .summary-state--error {
      display: grid;
      gap: 0.48rem;
      border: 1px solid rgba(234, 51, 98, 0.25);
      color: var(--otziv-danger);
      background: rgba(234, 51, 98, 0.06);
    }

    .summary-state--error button {
      width: fit-content;
      min-height: 44px;
      border: 1px solid currentColor;
      border-radius: 0.7rem;
      padding: 0 0.8rem;
      color: var(--otziv-danger);
      background: var(--otziv-white);
      font: inherit;
      font-weight: 1000;
      touch-action: manipulation;
    }

    :host-context(body.otziv-dark-theme) .contractor-payment-summary,
    :host-context(body.otziv-dark-theme) .contractor-role,
    :host-context(body.otziv-dark-theme) .contractor-metric {
      border-color: rgba(163, 189, 204, 0.18);
      background: linear-gradient(155deg, rgba(32, 37, 40, 0.98) 0%, rgba(38, 45, 52, 0.96) 100%);
      box-shadow: none;
    }

    :host-context(body.otziv-dark-theme) .payment-mode--shadow {
      border-color: rgba(231, 180, 52, 0.38);
      color: var(--otziv-warning);
      background: rgba(231, 180, 52, 0.16);
    }

    :host-context(body.otziv-dark-theme) .contractor-metric--available {
      border-color: rgba(74, 198, 177, 0.24);
      background: rgba(27, 90, 78, 0.24);
    }

    :host-context(body.otziv-dark-theme) .contractor-metric--credit,
    :host-context(body.otziv-dark-theme) .summary-state--error {
      border-color: rgba(255, 91, 143, 0.24);
      background: rgba(90, 30, 53, 0.24);
    }

    :host-context(body.otziv-dark-theme) .summary-state--error button {
      background: rgba(21, 26, 30, 0.72);
    }

    @media (max-width: 390px) {
      .contractor-role > header {
        flex-direction: column;
      }

      .payment-mode {
        max-width: 100%;
        text-align: left;
      }

      .contractor-metric-grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `]
})
export class MobileContractorPaymentSummaryComponent {
  @Input() summaries: readonly ContractorPaymentSummary[] = [];
  @Input() loading = false;
  @Input() error: string | null = null;
  @Output() readonly retry = new EventEmitter<void>();

  readonly metrics = contractorPaymentMetrics;
  readonly modeLabel = contractorPaymentModeLabel;
  readonly modeClass = contractorPaymentModeClass;
  readonly descriptionId = contractorPaymentMetricDescriptionId;

  roleLabel(role: ContractorPaymentSummary['role']): string {
    return role === 'SPECIALIST' ? 'Специалист' : 'Менеджер';
  }

  roleHeadingId(profileId: number): string {
    return `mobile-contractor-payment-role-${profileId}`;
  }

  coverageStartLabel(summary: ContractorPaymentSummary): string {
    return contractorCoverageStartLabel(summary.trackingStartedAt);
  }

  money(kopecks?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format((kopecks ?? 0) / 100)} руб.`;
  }
}
