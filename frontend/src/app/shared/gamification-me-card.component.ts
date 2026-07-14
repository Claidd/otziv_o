import { Component, Input, computed, inject, signal } from '@angular/core';
import {
  GamificationApi,
  GamificationMyBreakdown,
  GamificationMyProgress
} from '../core/gamification.api';
import { DailyWorkProgress } from '../core/daily-progress';

type ProgressDays = 1 | 7 | 30;
type GoalLine = {
  label: string;
  progress: number;
  target: number;
  percent: number;
  active: number;
  hint: string;
};

@Component({
  selector: 'app-gamification-me-card',
  imports: [],
  template: `
    @if (workGoal(); as goal) {
      <section class="gamification-me-card work-goal-card" aria-label="Цель дня">
        <header>
          <div>
            <span>Цель дня</span>
            <strong>{{ goal.progress }} / {{ goal.target }}</strong>
          </div>
          <span class="material-icons-sharp" aria-hidden="true">fact_check</span>
        </header>

        <div class="gamification-summary">
          <span>Закрыто {{ goal.progress }}</span>
          <span>осталось {{ goal.active }}</span>
        </div>

        <div
          class="gamification-goal"
          [attr.title]="goal.hint"
          [attr.aria-label]="goal.hint"
        >
          <span>Карточки за день</span>
          <strong>{{ goal.percent }}%</strong>
          <i [style.width.%]="goal.percent"></i>
        </div>
      </section>
    } @else if (!workGoalOnly && visibleProgress(); as progress) {
      <section class="gamification-me-card" aria-label="Личный прогресс">
        <header>
          <div>
            <span>Прогресс</span>
            <strong>{{ progress.totalPoints }}</strong>
          </div>
          <span class="material-icons-sharp" aria-hidden="true">emoji_events</span>
        </header>

        <div class="gamification-periods" aria-label="Период прогресса">
          @for (period of periods; track period.days) {
            <button
              type="button"
              [class.active]="days() === period.days"
              (click)="setDays(period.days)"
              [disabled]="loading()"
            >
              {{ period.label }}
            </button>
          }
        </div>

        <div class="gamification-summary">
          <span>{{ progress.totalEvents }} событий</span>
          <span>уровень {{ progress.level }}</span>
        </div>

        @if (goalLine(progress); as goal) {
          <div
            class="gamification-goal"
            [attr.title]="goal.hint"
            [attr.aria-label]="goal.hint"
          >
            <span>{{ goal.label }}</span>
            <strong>{{ goal.progress }} / {{ goal.target }}</strong>
            <i [style.width.%]="goal.percent"></i>
          </div>
        }

        <div class="gamification-breakdown">
          @for (item of progress.breakdown; track trackBreakdown($index, item)) {
            @if (item.events > 0 || item.points > 0) {
              <div>
                <span>{{ eventLabel(item.eventType) }}</span>
                <strong>{{ item.points }}</strong>
                <small>{{ item.events }}</small>
              </div>
            }
          }
        </div>
      </section>
    }
  `,
  styles: [`
    :host {
      display: block;
      width: 100%;
    }

    .gamification-me-card {
      display: grid;
      gap: 0.75rem;
      width: 100%;
      box-sizing: border-box;
      overflow: hidden;
      border: 1px solid var(--otziv-surface-border-neutral);
      border-radius: 1rem;
      padding: 0.9rem;
      color: var(--otziv-dark);
      background: linear-gradient(180deg, color-mix(in srgb, var(--otziv-white) 97%, var(--otziv-primary)) 0%, var(--otziv-white) 58%);
      box-shadow: 0 1rem 2rem rgba(132, 139, 200, 0.14);
      font-family: var(--otziv-font-family);
    }

    header {
      display: flex;
      min-width: 0;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
    }

    header span:first-child,
    .gamification-summary,
    .gamification-breakdown span,
    .gamification-breakdown small {
      color: var(--otziv-info);
      font-size: 0.75rem;
      font-weight: 900;
    }

    header strong {
      display: block;
      margin-top: 0.2rem;
      color: var(--otziv-primary);
      font-size: 1.65rem;
      font-weight: 900;
      line-height: 1;
      text-shadow: 0 0.55rem 1.4rem color-mix(in srgb, var(--otziv-primary) 28%, transparent);
    }

    header > .material-icons-sharp {
      display: grid;
      flex: 0 0 3rem;
      width: 3rem;
      height: 3rem;
      place-items: center;
      border: 1px solid color-mix(in srgb, var(--otziv-warning) 36%, transparent);
      border-radius: 50%;
      color: color-mix(in srgb, var(--otziv-warning) 55%, var(--otziv-dark));
      background:
        radial-gradient(circle at 34% 24%, rgba(255, 255, 255, 0.54), transparent 34%),
        linear-gradient(145deg, color-mix(in srgb, var(--otziv-warning) 54%, var(--otziv-white)), color-mix(in srgb, var(--otziv-warning) 24%, var(--otziv-white)));
      box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--otziv-white) 52%, transparent);
      font-size: 1.25rem;
    }

    .gamification-periods {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.35rem;
    }

    .gamification-periods button {
      min-width: 0;
      min-height: 2rem;
      border: 1px solid var(--otziv-surface-border-neutral);
      border-radius: 0.58rem;
      color: var(--otziv-dark);
      background: color-mix(in srgb, var(--otziv-field-background) 78%, transparent);
      font: inherit;
      font-size: 0.72rem;
      font-weight: 900;
      transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
    }

    .gamification-periods button:not(:disabled):hover {
      border-color: var(--otziv-surface-border-blue);
      box-shadow: 0 0.45rem 1rem color-mix(in srgb, var(--otziv-primary) 14%, transparent);
      transform: translateY(-1px);
    }

    .gamification-periods button.active {
      border-color: color-mix(in srgb, var(--otziv-primary) 45%, transparent);
      color: white;
      background: linear-gradient(135deg, var(--otziv-primary), color-mix(in srgb, var(--otziv-primary) 58%, #7c5fb8));
      box-shadow: 0 0.65rem 1.35rem color-mix(in srgb, var(--otziv-primary) 24%, transparent);
    }

    .gamification-summary {
      display: flex;
      min-width: 0;
      justify-content: space-between;
      gap: 0.5rem;
    }

    .gamification-summary span {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .gamification-summary span {
      display: inline-flex;
      align-items: center;
      border-radius: 999px;
      padding: 0.18rem 0.4rem;
      background: color-mix(in srgb, var(--otziv-field-background) 58%, transparent);
    }

    .gamification-breakdown {
      display: grid;
      gap: 0.45rem;
    }

    .gamification-goal {
      position: relative;
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 0.4rem;
      overflow: hidden;
      border: 1px solid var(--otziv-surface-border-neutral);
      border-radius: 0.72rem;
      padding: 0.52rem 0.62rem 0.7rem;
      background:
        linear-gradient(135deg, color-mix(in srgb, var(--otziv-field-background) 72%, transparent), color-mix(in srgb, var(--otziv-primary) 12%, transparent));
    }

    .gamification-goal span,
    .gamification-goal strong {
      position: relative;
      z-index: 1;
    }

    .gamification-goal::before {
      position: absolute;
      right: 0.55rem;
      bottom: 0.38rem;
      left: 0.55rem;
      height: 0.26rem;
      border-radius: 999px;
      background: var(--otziv-progress-track);
      content: '';
    }

    .gamification-goal i {
      position: absolute;
      inset: auto 0.55rem 0.38rem;
      height: 0.26rem;
      border-radius: 999px;
      background: var(--otziv-progress-fill);
      transform-origin: left center;
    }

    .gamification-breakdown div {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      align-items: center;
      gap: 0.45rem;
      min-height: 2.15rem;
      border: 1px solid var(--otziv-surface-border-neutral);
      border-radius: 0.75rem;
      padding: 0.42rem 0.55rem;
      background:
        radial-gradient(circle at 96% 50%, color-mix(in srgb, var(--otziv-primary) 18%, transparent), transparent 32%),
        linear-gradient(135deg, color-mix(in srgb, var(--otziv-field-background) 70%, transparent), color-mix(in srgb, var(--otziv-muted-surface) 72%, transparent));
    }

    .gamification-breakdown span {
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .gamification-breakdown strong {
      color: var(--otziv-success);
      font-weight: 900;
    }

    .gamification-breakdown small {
      min-width: 1.35rem;
      text-align: right;
    }
  `]
})
export class GamificationMeCardComponent {
  private readonly api = inject(GamificationApi);

  @Input() workProgress: DailyWorkProgress | null | undefined;
  @Input() workGoalOnly = false;

  readonly periods: Array<{ days: ProgressDays; label: string }> = [
    { days: 1, label: 'День' },
    { days: 7, label: '7 дней' },
    { days: 30, label: '30 дней' }
  ];
  readonly days = signal<ProgressDays>(7);
  readonly progress = signal<GamificationMyProgress | null>(null);
  readonly loading = signal(false);
  readonly visibleProgress = computed(() => {
    const progress = this.progress();
    return progress?.enabled ? progress : null;
  });

  constructor() {
    this.load();
  }

  setDays(days: ProgressDays): void {
    if (this.days() === days) {
      return;
    }
    this.days.set(days);
    this.load();
  }

  eventLabel(eventType: string): string {
    switch (eventType) {
      case 'REVIEW_PUBLISHED':
        return 'Опубликовано';
      case 'ORDER_PAID':
        return 'Оплачено';
      case 'BAD_REVIEW_TASK_DONE':
        return 'Плохие отзывы';
      case 'REVIEW_RECOVERY_TASK_DONE':
        return 'Восстановления';
      case 'WORKER_RISK_PENALTY':
        return 'Штрафы';
      default:
        return eventType || 'Событие';
    }
  }

  trackBreakdown(_index: number, item: GamificationMyBreakdown): string {
    return item.eventType;
  }

  goalLine(progress: GamificationMyProgress): GoalLine {
    if (this.workProgress?.visible) {
      return this.workGoal()!;
    }

    const dailyProgress = this.safeNumber(progress.dailyProgress);
    const dailyGoal = this.safeNumber(progress.dailyGoal);
    return {
      label: 'Цель дня',
      progress: dailyProgress,
      target: dailyGoal,
      percent: this.percent(dailyProgress, dailyGoal),
      active: Math.max(0, dailyGoal - dailyProgress),
      hint: 'Цель геймификации: события за сегодня / план действий на день.'
    };
  }

  workGoal(): GoalLine | null {
    if (!this.workProgress?.visible) {
      return null;
    }

    const completed = this.safeNumber(this.workProgress.completed);
    const active = this.safeNumber(this.workProgress.active);
    const target = Math.max(completed + active, this.safeNumber(this.workProgress.total));
    return {
      label: 'Цель дня',
      progress: completed,
      target,
      percent: target <= 0 ? 0 : this.percent(completed, target),
      active,
      hint: 'Рабочая цель дня: закрытые карточки / все карточки в расчёте за день (закрытые + активные). В конце дня расчёт начинается заново.'
    };
  }

  private load(): void {
    this.loading.set(true);
    this.api.getMyProgress(this.days()).subscribe({
      next: (progress) => {
        this.progress.set({
          ...progress,
          breakdown: progress.breakdown ?? []
        });
        this.loading.set(false);
      },
      error: () => {
        this.progress.set(null);
        this.loading.set(false);
      }
    });
  }

  private percent(value: number, total: number): number {
    if (!Number.isFinite(total) || total <= 0) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round(this.safeNumber(value) * 100 / total)));
  }

  private safeNumber(value: number | null | undefined): number {
    const raw = Number(value || 0);
    return Number.isFinite(raw) ? Math.max(0, Math.round(raw)) : 0;
  }
}
