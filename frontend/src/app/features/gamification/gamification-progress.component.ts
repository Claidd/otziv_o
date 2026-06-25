import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import {
  GamificationApi,
  GamificationMyBreakdown,
  GamificationMyMission,
  GamificationMyProgress
} from '../../core/gamification.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { ToastService } from '../../shared/toast.service';

type ProgressDays = 1 | 7 | 30;

@Component({
  selector: 'app-gamification-progress',
  imports: [AdminLayoutComponent, DatePipe],
  template: `
    <app-admin-layout title="Мои достижения" active="gamification-progress">
      <section class="progress-shell">
        @if (progress(); as data) {
          @if (data.enabled) {
            <header class="progress-hero">
              <div>
                <p>Личный прогресс</p>
                <h1>{{ data.actorName || 'Участник' }}</h1>
                <span>{{ roleLabel(data.actorRole) }} · {{ data.from | date:'dd.MM' }} - {{ data.to | date:'dd.MM' }}</span>
              </div>
              <div class="level-ring">
                <strong>{{ data.level }}</strong>
                <span>уровень</span>
              </div>
            </header>

            <nav class="period-switch" aria-label="Период прогресса">
              @for (period of periods; track period.days) {
                <button type="button" [class.active]="days() === period.days" (click)="setDays(period.days)" [disabled]="loading()">
                  {{ period.label }}
                </button>
              }
            </nav>

            <section class="progress-grid">
              <article class="metric-card primary">
                <span class="material-icons-sharp">stars</span>
                <div>
                  <small>Очки периода</small>
                  <strong>{{ data.totalPoints }}</strong>
                  <em>до следующего уровня: {{ data.pointsToNextLevel }}</em>
                </div>
              </article>
              <article class="metric-card">
                <span class="material-icons-sharp">flag</span>
                <div>
                  <small>Цель дня</small>
                  <strong>{{ data.dailyProgress }} / {{ data.dailyGoal }}</strong>
                  <em>{{ data.dailyGoalPercent }}% выполнено</em>
                </div>
              </article>
              <article class="metric-card">
                <span class="material-icons-sharp">verified</span>
                <div>
                  <small>В срок</small>
                  <strong>{{ data.timelinessPercent }}%</strong>
                  <em>потеряно очков: {{ data.lostPoints }}</em>
                </div>
              </article>
              <article class="metric-card">
                <span class="material-icons-sharp">local_fire_department</span>
                <div>
                  <small>Серия</small>
                  <strong>{{ data.streakDays }}</strong>
                  <em>дней без просрочек</em>
                </div>
              </article>
            </section>

            <section class="level-track" aria-label="Прогресс уровня">
              <div>
                <span>{{ data.currentLevelPoints }}</span>
                <strong>{{ data.totalPoints }}</strong>
                <span>{{ data.nextLevelPoints }}</span>
              </div>
              <i [style.width.%]="levelPercent(data)"></i>
            </section>

            <section class="missions">
              <div class="section-head">
                <div>
                  <h2>Миссии</h2>
                  <p>Каркас ежедневных и недельных целей без публичного рейтинга.</p>
                </div>
              </div>
              <div class="mission-list">
                @for (mission of data.missions; track mission.code) {
                  <article class="mission-card" [class.done]="mission.completed">
                    <span class="material-icons-sharp">{{ mission.completed ? 'task_alt' : 'radio_button_unchecked' }}</span>
                    <div>
                      <strong>{{ mission.title }}</strong>
                      <p>{{ mission.description }}</p>
                      <div class="mini-track"><i [style.width.%]="mission.percent"></i></div>
                    </div>
                    <em>{{ mission.progress }} / {{ mission.target }}</em>
                  </article>
                }
              </div>
            </section>

            <section class="breakdown">
              <div class="section-head">
                <div>
                  <h2>Из чего собралось</h2>
                  <p>Роли считаются отдельно, коэффициент срока применяется только в теневом контуре.</p>
                </div>
              </div>
              <div class="breakdown-grid">
                @for (item of visibleBreakdown(); track trackBreakdown($index, item)) {
                  <article>
                    <small>{{ eventLabel(item.eventType) }}</small>
                    <strong>{{ item.points }}</strong>
                    <span>{{ item.events }} событий</span>
                  </article>
                } @empty {
                  <p class="empty">За выбранный период действий пока нет.</p>
                }
              </div>
            </section>
          } @else {
            <section class="empty-state">
              <span class="material-icons-sharp">emoji_events</span>
              <h1>Прогресс пока скрыт</h1>
              <p>Админ может включить показ в настройках геймификации после проверки теневых данных.</p>
            </section>
          }
        } @else {
          <section class="empty-state">
            <span class="material-icons-sharp">hourglass_top</span>
            <h1>Загружаю прогресс</h1>
          </section>
        }
      </section>
    </app-admin-layout>
  `,
  styles: [`
    .progress-shell {
      display: grid;
      gap: 1rem;
      max-width: 82rem;
      margin: 0 auto;
      padding: 1rem;
    }

    .progress-hero,
    .metric-card,
    .missions,
    .breakdown,
    .empty-state {
      border: 1px solid rgba(103, 116, 131, 0.12);
      border-radius: 0.5rem;
      background: var(--otziv-white);
      box-shadow: 0 0.85rem 1.85rem rgba(132, 139, 200, 0.12);
    }

    .progress-hero {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 1.2rem;
    }

    .progress-hero p,
    .progress-hero span,
    .section-head p,
    .metric-card small,
    .metric-card em,
    .mission-card p,
    .mission-card em,
    .breakdown article span,
    .breakdown article small,
    .empty-state p {
      margin: 0;
      color: var(--otziv-info);
      font-size: 0.78rem;
      font-weight: 800;
      line-height: 1.35;
    }

    .progress-hero h1,
    .section-head h2 {
      margin: 0.1rem 0;
      color: var(--otziv-dark);
      font-size: 1.35rem;
      line-height: 1.1;
    }

    .level-ring {
      display: grid;
      flex: 0 0 6rem;
      width: 6rem;
      height: 6rem;
      place-items: center;
      border: 0.55rem solid rgba(27, 156, 133, 0.22);
      border-radius: 50%;
      color: var(--otziv-success);
    }

    .level-ring strong {
      font-size: 1.75rem;
      line-height: 1;
    }

    .level-ring span {
      margin-top: -1.2rem;
      font-size: 0.68rem;
    }

    .period-switch {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
    }

    .period-switch button {
      min-height: 2.35rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 0.5rem;
      padding: 0 1rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: inherit;
      font-weight: 900;
    }

    .period-switch button.active {
      color: var(--otziv-white);
      background: var(--otziv-primary);
    }

    .progress-grid,
    .breakdown-grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 0.75rem;
    }

    .metric-card {
      display: flex;
      gap: 0.75rem;
      align-items: flex-start;
      padding: 0.9rem;
    }

    .metric-card .material-icons-sharp {
      color: var(--otziv-info);
    }

    .metric-card.primary .material-icons-sharp {
      color: var(--otziv-warning);
    }

    .metric-card div {
      display: grid;
      min-width: 0;
    }

    .metric-card strong,
    .breakdown article strong {
      color: var(--otziv-dark);
      font-size: 1.25rem;
      line-height: 1.15;
    }

    .level-track,
    .mini-track {
      position: relative;
      overflow: hidden;
      border-radius: 999px;
      background: rgba(103, 116, 131, 0.12);
    }

    .level-track {
      min-height: 3rem;
      padding: 0.8rem;
    }

    .level-track div {
      position: relative;
      z-index: 1;
      display: flex;
      justify-content: space-between;
      color: var(--otziv-dark);
      font-weight: 900;
    }

    .level-track i,
    .mini-track i {
      position: absolute;
      inset: 0 auto 0 0;
      background: rgba(27, 156, 133, 0.32);
    }

    .missions,
    .breakdown,
    .empty-state {
      display: grid;
      gap: 0.85rem;
      padding: 1rem;
    }

    .mission-list {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.75rem;
    }

    .mission-card {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      gap: 0.7rem;
      min-width: 0;
      border: 1px solid rgba(103, 116, 131, 0.12);
      border-radius: 0.5rem;
      padding: 0.8rem;
      background: var(--otziv-field-background);
    }

    .mission-card.done {
      border-color: rgba(27, 156, 133, 0.28);
    }

    .mission-card .material-icons-sharp {
      color: var(--otziv-success);
    }

    .mission-card strong,
    .mission-card p {
      display: block;
      min-width: 0;
      overflow-wrap: anywhere;
    }

    .mini-track {
      height: 0.3rem;
      margin-top: 0.45rem;
    }

    .breakdown article {
      display: grid;
      gap: 0.2rem;
      border: 1px solid rgba(103, 116, 131, 0.12);
      border-radius: 0.5rem;
      padding: 0.8rem;
      background: var(--otziv-field-background);
    }

    .empty-state {
      min-height: 18rem;
      place-items: center;
      text-align: center;
    }

    .empty-state .material-icons-sharp {
      color: var(--otziv-warning);
      font-size: 2.5rem;
    }

    @media (max-width: 58rem) {
      .progress-grid,
      .mission-list,
      .breakdown-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }

    @media (max-width: 38rem) {
      .progress-hero {
        align-items: flex-start;
      }

      .level-ring {
        flex-basis: 4.75rem;
        width: 4.75rem;
        height: 4.75rem;
      }

      .progress-grid,
      .mission-list,
      .breakdown-grid {
        grid-template-columns: 1fr;
      }
    }
  `]
})
export class GamificationProgressComponent {
  private readonly api = inject(GamificationApi);
  private readonly toastService = inject(ToastService);

  readonly periods: Array<{ days: ProgressDays; label: string }> = [
    { days: 1, label: 'Сегодня' },
    { days: 7, label: '7 дней' },
    { days: 30, label: '30 дней' }
  ];
  readonly days = signal<ProgressDays>(7);
  readonly progress = signal<GamificationMyProgress | null>(null);
  readonly loading = signal(false);
  readonly visibleBreakdown = computed(() => this.progress()?.breakdown.filter((item) => item.events > 0 || item.points > 0) ?? []);

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

  levelPercent(progress: GamificationMyProgress): number {
    const range = Math.max(1, progress.nextLevelPoints - progress.currentLevelPoints);
    return Math.max(0, Math.min(100, Math.round((progress.totalPoints - progress.currentLevelPoints) * 100 / range)));
  }

  roleLabel(role: string | null | undefined): string {
    return {
      WORKER: 'Специалист',
      MANAGER: 'Менеджер',
      OPERATOR: 'Оператор',
      MARKETOLOG: 'Маркетолог'
    }[role ?? ''] ?? (role || 'Участник');
  }

  eventLabel(eventType: string): string {
    return {
      REVIEW_PUBLISHED: 'Отзывы опубликованы',
      ORDER_PAID: 'Заказы оплачены',
      BAD_REVIEW_TASK_DONE: 'Плохие отзывы',
      REVIEW_RECOVERY_TASK_DONE: 'Восстановления',
      WORKER_RISK_PENALTY: 'Штрафы'
    }[eventType] ?? (eventType || 'Событие');
  }

  trackBreakdown(_index: number, item: GamificationMyBreakdown): string {
    return item.eventType;
  }

  trackMission(_index: number, item: GamificationMyMission): string {
    return item.code;
  }

  private load(): void {
    this.loading.set(true);
    this.api.getMyProgress(this.days()).subscribe({
      next: (progress) => {
        this.progress.set({
          ...progress,
          missions: progress.missions ?? [],
          breakdown: progress.breakdown ?? []
        });
        this.loading.set(false);
      },
      error: (err) => {
        this.progress.set(null);
        this.loading.set(false);
        this.toastService.error('Прогресс не загружен', err?.error?.message || 'Попробуйте обновить страницу');
      }
    });
  }
}
