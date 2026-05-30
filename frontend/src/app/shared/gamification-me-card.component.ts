import { Component, computed, inject, signal } from '@angular/core';
import {
  GamificationApi,
  GamificationMyBreakdown,
  GamificationMyProgress
} from '../core/gamification.api';

type ProgressDays = 1 | 7 | 30;

@Component({
  selector: 'app-gamification-me-card',
  imports: [],
  template: `
    @if (visibleProgress(); as progress) {
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

        <div class="gamification-goal">
          <span>Цель дня</span>
          <strong>{{ progress.dailyProgress }} / {{ progress.dailyGoal }}</strong>
          <i [style.width.%]="progress.dailyGoalPercent"></i>
        </div>

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
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.5rem;
      padding: 0.85rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
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
    }

    header > .material-icons-sharp {
      display: grid;
      flex: 0 0 3rem;
      width: 3rem;
      height: 3rem;
      place-items: center;
      border-radius: 50%;
      color: white;
      background: var(--otziv-warning);
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
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 0.45rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font: inherit;
      font-size: 0.72rem;
      font-weight: 900;
    }

    .gamification-periods button.active {
      border-color: transparent;
      color: white;
      background: var(--otziv-primary);
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
      border-radius: 0.45rem;
      padding: 0.45rem 0.55rem;
      background: var(--otziv-light);
    }

    .gamification-goal span,
    .gamification-goal strong {
      position: relative;
      z-index: 1;
    }

    .gamification-goal i {
      position: absolute;
      inset: auto 0 0;
      height: 0.2rem;
      background: var(--otziv-success);
    }

    .gamification-breakdown div {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      align-items: center;
      gap: 0.45rem;
      min-height: 2.15rem;
      border-radius: 0.45rem;
      padding: 0.35rem 0.5rem;
      background: var(--otziv-light);
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
      default:
        return eventType || 'Событие';
    }
  }

  trackBreakdown(_index: number, item: GamificationMyBreakdown): string {
    return item.eventType;
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
}
