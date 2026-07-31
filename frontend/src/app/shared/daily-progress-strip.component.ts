import { Component, Input } from '@angular/core';
import { DailyWorkProgress } from '../core/daily-progress';

@Component({
  selector: 'app-daily-progress-strip',
  template: `
    @if (progress?.visible) {
      <section
        class="daily-progress-strip"
        [class.complete]="progress?.checked"
        [class.empty]="isEmpty()"
        [class.updating]="progress?.updating"
        [attr.aria-label]="tooltipText()"
        [attr.title]="tooltipText()"
      >
        <span class="daily-progress-label">{{ label || defaultLabel() }}</span>
        <div class="daily-progress-bar" aria-hidden="true">
          <i [style.width.%]="safePercent()"></i>
        </div>
        <strong>{{ progress?.completed || 0 }}/{{ progress?.total || 0 }}</strong>
        <em>{{ safePercent() }}%</em>
        @if (progress?.updating) {
          <span class="material-icons-sharp daily-progress-refresh" aria-label="Прогресс обновляется">sync</span>
        } @else if (progress?.checked) {
          <span class="material-icons-sharp daily-progress-check" aria-label="Выполнено">check_circle</span>
        }
      </section>
    }
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
      align-self: center;
    }

    .daily-progress-strip {
      display: grid;
      box-sizing: border-box;
      width: 100%;
      grid-template-columns: auto minmax(3.5rem, 1fr) auto auto auto;
      min-height: 1.35rem;
      align-items: center;
      gap: 0.38rem;
      border: 1px solid var(--otziv-progress-border);
      border-radius: 999px;
      padding: 0.16rem 0.38rem;
      color: var(--otziv-progress-text);
      background: var(--otziv-progress-surface);
      box-shadow: 0 0.22rem 0.65rem rgba(108, 155, 207, 0.08);
      font-family: var(--otziv-font-family);
      font-size: 0.68rem;
      font-weight: 900;
      line-height: 1;
    }

    .daily-progress-label {
      max-width: 5.5rem;
      overflow: hidden;
      color: var(--otziv-progress-label);
      line-height: 1;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .daily-progress-bar {
      position: relative;
      min-width: 0;
      height: 0.42rem;
      overflow: hidden;
      border-radius: 999px;
      background: var(--otziv-progress-track);
      box-shadow: inset 0 0.05rem 0.18rem rgba(31, 44, 71, 0.1);
    }

    .daily-progress-bar i {
      position: absolute;
      inset: 0 auto 0 0;
      min-width: 0.2rem;
      border-radius: inherit;
      background: var(--otziv-progress-fill);
      background-size: 14rem 100%;
      transition: width 0.35s ease;
    }

    .daily-progress-strip.complete .daily-progress-bar i {
      background: var(--otziv-progress-fill-complete, var(--otziv-progress-fill));
    }

    .daily-progress-strip.empty .daily-progress-bar i {
      min-width: 0;
    }

    strong,
    em {
      line-height: 1;
      white-space: nowrap;
    }

    em {
      color: var(--otziv-progress-label);
      font-style: normal;
    }

    .daily-progress-check {
      color: #43b77a;
      font-size: 0.95rem;
      line-height: 1;
    }

    .daily-progress-strip.updating {
      border-color: rgba(226, 157, 66, 0.48);
    }

    .daily-progress-refresh {
      color: #d99132;
      font-size: 0.95rem;
      line-height: 1;
      animation: daily-progress-refresh-spin 1.1s linear infinite;
    }

    @keyframes daily-progress-refresh-spin {
      to { transform: rotate(360deg); }
    }

    @media (max-width: 760px) {
      .daily-progress-strip {
        grid-template-columns: minmax(2.3rem, auto) minmax(0, 1fr) auto auto auto;
        min-height: 1.18rem;
        gap: 0.3rem;
        padding: 0.12rem 0.32rem;
        font-size: 0.62rem;
      }

      .daily-progress-label {
        max-width: 4rem;
      }

      .daily-progress-bar {
        height: 0.34rem;
      }
    }
  `]
})
export class DailyProgressStripComponent {
  @Input() progress: DailyWorkProgress | null | undefined;
  @Input() label = '';

  safePercent(): number {
    const raw = Number(this.progress?.percent || 0);
    if (!Number.isFinite(raw)) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round(raw)));
  }

  isEmpty(): boolean {
    return (this.progress?.total || 0) <= 0;
  }

  defaultLabel(): string {
    return this.progress?.roleType === 'MANAGER' ? 'Менеджер' : 'Специалист';
  }

  tooltipText(): string {
    const progress = this.progress;
    if (!progress?.visible) {
      return 'Прогресс пока недоступен.';
    }

    const completed = progress.completed || 0;
    const active = progress.active || 0;
    const total = progress.total || 0;
    const percent = this.safePercent();
    const name = this.label || this.defaultLabel();
    const base = `${completed}/${total} — ${percent}%, осталось ${active}.`;

    if (progress.updating) {
      return `Прогресс обновляется после последнего действия. Текущие данные: ${base}`;
    }

    if (progress.periodType === 'MONTH' || progress.roleType === 'WORKER_MONTH' || progress.roleType === 'WORKER_TEAM_MONTH') {
      const days = progress.workingDays || 0;
      const reached = progress.reached100Days || 0;
      return `Месячный прогресс: агрегированные закрытые задачи / месячная нагрузка. Дней в статистике: ${days}, дней с достижением 100%: ${reached}. ${base}`;
    }

    if (name === 'Команда' || progress.roleType === 'WORKER_TEAM') {
      return `Прогресс команды за сегодня: закрытые задачи специалистов / все задачи специалистов (закрытые + активные). ${base}`;
    }

    if (progress.roleType === 'MANAGER') {
      return `Прогресс менеджерского контроля: обработано / всего к действию. ${base}`;
    }

    return `Дневной прогресс специалиста: закрытые карточки / все карточки в работе. Карточки “Новые — ждёт клиента” не учитываются. ${base}`;
  }
}
