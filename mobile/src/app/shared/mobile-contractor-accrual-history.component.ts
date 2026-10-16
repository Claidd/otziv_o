import { Component, EventEmitter, Input, Output } from '@angular/core';
import type { ContractorPaymentSummary } from '../core/api.service';
import type { ContractorSelfAccrualAdjustment } from '../core/contractor-accrual-history.api';

@Component({
  selector: 'app-mobile-contractor-accrual-history',
  standalone: true,
  template: `
    <section class="accrual-history" aria-label="История премий и удержаний">
      <header>
        <span class="material-icons-sharp" aria-hidden="true">receipt_long</span>
        <div>
          <p>МОИ НАЧИСЛЕНИЯ</p>
          <h2>Премии и удержания</h2>
        </div>
      </header>

      @if (loading) {
        <p class="state" role="status">Загружаю историю…</p>
      } @else if (error) {
        <div class="state state--error" role="alert">
          <span>{{ error }}</span>
          <button type="button" (click)="retry.emit()">Повторить</button>
        </div>
      } @else if (!adjustments.length) {
        <p class="state">Премий и удержаний пока нет.</p>
      } @else {
        @for (summary of summaries; track summary.profileId) {
          @if (historyFor(summary.profileId); as history) {
            @if (history.length) {
              <details class="profile-history">
                <summary>{{ roleLabel(summary.role) }} · последние корректировки</summary>
                <ol>
                  @for (adjustment of history; track adjustment.id) {
                    <li>
                      <strong>{{ kindLabel(adjustment.kind) }} · {{ signedMoney(adjustment.signedAmountKopecks) }}</strong>
                      <small>Учтено {{ dateLabel(adjustment.effectiveOn) }} · основание за {{ adjustment.referenceMonth }}</small>
                      <span>{{ adjustment.reason }}</span>
                      @if (adjustment.reversalOfId) {
                        <small>Отмена записи №{{ adjustment.reversalOfId }}</small>
                      }
                    </li>
                  }
                </ol>
              </details>
            }
          }
        }
      }
    </section>
  `,
  styles: [`
    :host { display: block; order: 19; min-width: 0; }
    .accrual-history { display: grid; gap: .68rem; border: 1px solid rgba(108,155,207,.24); border-radius: 1rem; padding: .78rem; background: var(--otziv-white); box-shadow: 0 .8rem 1.6rem rgba(132,139,200,.1); }
    header { display: flex; align-items: center; gap: .58rem; }
    header .material-icons-sharp { display: grid; place-items: center; width: 2rem; height: 2rem; border-radius: .62rem; color: #3e82ae; background: rgba(98,173,218,.14); }
    header p { margin: 0; color: var(--otziv-info); font-size: .57rem; font-weight: 900; letter-spacing: .08em; }
    header h2 { margin: .08rem 0 0; font-size: .98rem; }
    .state { margin: 0; color: var(--otziv-info); font-size: .76rem; }
    .state--error { display: flex; align-items: center; justify-content: space-between; gap: .5rem; }
    .state button { border: 0; border-radius: .6rem; padding: .45rem .7rem; background: var(--otziv-light); color: var(--otziv-primary); font: inherit; font-weight: 800; }
    .profile-history { border: 1px solid rgba(103,116,131,.15); border-radius: .75rem; padding: .58rem .67rem; }
    summary { cursor: pointer; font-size: .78rem; font-weight: 900; }
    ol { display: grid; gap: .55rem; margin: .7rem 0 0; padding: 0; list-style: none; }
    li { display: grid; gap: .12rem; border-top: 1px solid rgba(103,116,131,.12); padding-top: .55rem; font-size: .72rem; line-height: 1.38; }
    li strong { font-size: .78rem; }
    li small { color: var(--otziv-info); font-size: .66rem; }
    :host-context(body.otziv-dark-theme) .accrual-history,
    :host-context(body.otziv-dark-theme) .profile-history { background: var(--otziv-white); }
  `]
})
export class MobileContractorAccrualHistoryComponent {
  @Input() summaries: ContractorPaymentSummary[] = [];
  @Input() adjustments: ContractorSelfAccrualAdjustment[] = [];
  @Input() loading = false;
  @Input() error: string | null = null;
  @Output() retry = new EventEmitter<void>();

  historyFor(profileId: number): ContractorSelfAccrualAdjustment[] {
    return this.adjustments.filter((item) => item.profileId === profileId).slice(0, 5);
  }

  roleLabel(role: ContractorPaymentSummary['role']): string {
    return role === 'SPECIALIST' ? 'Специалист' : 'Менеджер';
  }

  kindLabel(kind: ContractorSelfAccrualAdjustment['kind']): string {
    return kind === 'BONUS' ? 'Премия' : kind === 'DEDUCTION' ? 'Удержание' : 'Отмена корректировки';
  }

  signedMoney(kopecks: number): string {
    const amount = new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format(kopecks / 100);
    return `${kopecks > 0 ? '+' : ''}${amount} руб.`;
  }

  dateLabel(value: string): string {
    const [year, month, day] = value.split('-');
    return year && month && day ? `${day}.${month}.${year}` : value;
  }
}
