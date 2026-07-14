import { Component, Input } from '@angular/core';
import { DailyWorkProgress } from '../core/daily-progress';

@Component({
  selector: 'app-daily-progress-strip',
  template: `
    @if (progress?.visible) {
      <section class="daily-progress-strip" [class.complete]="progress?.checked" [class.empty]="isEmpty()" aria-label="Дневной прогресс">
        <span class="daily-progress-label">{{ label || defaultLabel() }}</span>
        <div class="daily-progress-bar" aria-hidden="true">
          <i [style.width.%]="safePercent()"></i>
        </div>
        <strong>{{ progress?.completed || 0 }}/{{ progress?.total || 0 }}</strong>
        <em>{{ safePercent() }}%</em>
        @if (progress?.checked) {
          <span class="material-icons-sharp daily-progress-check" aria-label="Выполнено">check_circle</span>
        }
      </section>
    }
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
    }

    .daily-progress-strip {
      display: grid;
      grid-template-columns: auto minmax(2.5rem, 1fr) auto auto auto;
      min-height: 1.15rem;
      align-items: center;
      gap: 0.35rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 999px;
      padding: 0.15rem 0.38rem;
      color: var(--otziv-dark);
      background: rgba(255, 255, 255, 0.74);
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.5);
      font-family: var(--otziv-font-family);
      font-size: 0.68rem;
      font-weight: 900;
      line-height: 1;
    }

    .daily-progress-label {
      max-width: 5.5rem;
      overflow: hidden;
      color: var(--otziv-info);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .daily-progress-bar {
      position: relative;
      min-width: 0;
      height: 0.36rem;
      overflow: hidden;
      border-radius: 999px;
      background: rgba(196, 97, 220, 0.16);
    }

    .daily-progress-bar i {
      position: absolute;
      inset: 0 auto 0 0;
      min-width: 0.2rem;
      border-radius: inherit;
      background: linear-gradient(90deg, #7aa7dc, #c461dc);
      transition: width 0.35s ease;
    }

    .daily-progress-strip.complete .daily-progress-bar i {
      background: linear-gradient(90deg, #54c7a3, #70d36d);
    }

    .daily-progress-strip.empty .daily-progress-bar i {
      min-width: 0;
    }

    strong,
    em {
      white-space: nowrap;
    }

    em {
      color: var(--otziv-info);
      font-style: normal;
    }

    .daily-progress-check {
      color: #43b77a;
      font-size: 0.95rem;
      line-height: 1;
    }

    @media (max-width: 760px) {
      .daily-progress-strip {
        grid-template-columns: minmax(2.3rem, auto) minmax(0, 1fr) auto auto auto;
        min-height: 0.95rem;
        padding: 0.12rem 0.34rem;
        font-size: 0.62rem;
      }

      .daily-progress-label {
        max-width: 4rem;
      }

      .daily-progress-bar {
        height: 0.28rem;
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
}
