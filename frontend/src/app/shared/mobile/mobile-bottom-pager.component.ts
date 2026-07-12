import { Location } from '@angular/common';
import { Component, EventEmitter, inject, Input, Output } from '@angular/core';
import { Router } from '@angular/router';
import { MobileRemindersComponent } from './mobile-reminders.component';

@Component({
  selector: 'app-mobile-bottom-pager',
  standalone: true,
  imports: [MobileRemindersComponent],
  template: `
    <app-mobile-reminders #reminders />
    <section class="mobile-bottom-pager" aria-label="Пагинация">
      <div class="mobile-pager-actions">
        <button
          type="button"
          class="mobile-pager-reminders"
          (click)="reminders.open()"
          [attr.aria-label]="reminderButtonLabel(reminders.activeReminderCount())"
        >
          <span class="material-icons-sharp" aria-hidden="true">notifications_active</span>
          @if (reminders.activeReminderCount()) {
            <small>{{ reminders.activeReminderCount() }}</small>
          }
        </button>
        <ng-content select="[mobilePagerActions]" />
      </div>
      <div class="mobile-pager-nav">
        <div class="mobile-pager-turns">
          <button type="button" (click)="previous.emit()" [disabled]="disabled || pageIndex <= 0" aria-label="Предыдущая страница">
            <span class="material-icons-sharp" aria-hidden="true">chevron_left</span>
          </button>
          <span>{{ pageIndex + 1 }} / {{ safeTotalPages }}</span>
          <button type="button" (click)="next.emit()" [disabled]="disabled || pageIndex >= safeTotalPages - 1" aria-label="Следующая страница">
            <span class="material-icons-sharp" aria-hidden="true">chevron_right</span>
          </button>
        </div>
        <button class="mobile-pager-back" type="button" (click)="goBack()" [disabled]="disabled" aria-label="Вернуться назад">
          <span class="material-icons-sharp" aria-hidden="true">keyboard_backspace</span>
        </button>
      </div>
    </section>
  `,
  styles: [`
    :host { display: block; flex: 0 0 auto; width: 100%; min-width: 0; }
    .mobile-bottom-pager {
      display: grid;
      position: relative;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: var(--otziv-pager-gap, 0.26rem);
      height: var(--otziv-pager-height, 2.78rem);
      min-height: var(--otziv-pager-height, 2.78rem);
      max-width: 100%;
      overflow: hidden;
      border: 1px solid rgba(116, 154, 207, 0.18);
      border-radius: 0.86rem;
      padding: 0.25rem;
      background: rgba(255, 255, 255, 0.92);
      box-shadow: 0 0.55rem 1.35rem rgba(31, 44, 71, 0.06);
    }
    .mobile-pager-actions {
      display: inline-flex;
      position: relative;
      z-index: 2;
      flex: 0 0 auto;
      align-items: center;
      gap: var(--otziv-pager-action-gap, var(--otziv-pager-button-size, 2.08rem));
      min-width: 0;
    }
    .mobile-pager-actions > .mobile-pager-reminders.mobile-pager-reminders {
      display: grid;
      position: relative;
      min-width: var(--otziv-pager-button-size, 2.08rem);
      min-height: 0;
      width: var(--otziv-pager-button-size, 2.08rem);
      height: var(--otziv-pager-button-size, 2.08rem);
      place-items: center;
      border: 1px solid rgba(116, 154, 207, 0.22);
      border-radius: 0.62rem;
      padding: 0;
      color: var(--otziv-primary) !important;
      background: #f4f8ff !important;
      box-shadow: none;
      font: inherit;
      font-weight: 900;
      cursor: pointer;
    }
    :host .mobile-pager-actions.mobile-pager-actions ::ng-deep button {
      display: grid;
      position: relative;
      min-width: var(--otziv-pager-button-size, 2.08rem);
      min-height: 0;
      height: var(--otziv-pager-button-size, 2.08rem);
      place-items: center;
      border: 1px solid rgba(116, 154, 207, 0.22);
      border-radius: 0.62rem;
      padding: 0;
      color: var(--otziv-primary) !important;
      background: #f4f8ff !important;
      font: inherit;
      font-weight: 900;
    }
    :host .mobile-pager-actions.mobile-pager-actions ::ng-deep button.active {
      color: var(--otziv-primary) !important;
      background: var(--otziv-light) !important;
    }
    .mobile-pager-actions ::ng-deep small {
      position: absolute;
      top: -0.38rem;
      right: -0.28rem;
      display: grid;
      min-width: 1rem;
      height: 1rem;
      place-items: center;
      border-radius: 999px;
      color: #fff;
      background: var(--otziv-danger);
      font-size: 0.6rem;
      line-height: 1;
    }
    .mobile-pager-reminders > small {
      padding-inline: 0.22rem;
      font-weight: 900;
    }
    .mobile-pager-nav {
      display: grid;
      position: absolute;
      inset: 0.25rem;
      grid-template-columns: minmax(0, 1fr) var(--otziv-pager-back-width, 2.72rem);
      align-items: center;
      gap: var(--otziv-pager-gap, 0.26rem);
      min-width: 0;
      pointer-events: none;
    }
    .mobile-pager-turns {
      display: grid;
      z-index: 1;
      grid-column: 1 / -1;
      grid-row: 1;
      grid-template-columns: var(--otziv-pager-button-size, 2.08rem) 3.18rem var(--otziv-pager-button-size, 2.08rem);
      align-items: center;
      justify-self: center;
      gap: var(--otziv-pager-gap, 0.26rem);
      min-width: 0;
      pointer-events: auto;
    }
    :host .mobile-pager-nav.mobile-pager-nav button {
      display: grid;
      min-width: 0;
      min-height: 0;
      height: var(--otziv-pager-button-size, 2.08rem);
      place-items: center;
      border: 0;
      border-radius: 0.62rem;
      padding: 0;
      color: var(--otziv-primary);
      background: linear-gradient(135deg, #f9fbff, #eef4ff);
      font: inherit;
      font-weight: 900;
      cursor: pointer;
    }
    .mobile-pager-nav .mobile-pager-back {
      z-index: 2;
      grid-column: 2;
      grid-row: 1;
      width: calc(100% - 0.14rem);
      margin-right: 0.08rem;
      padding: 0;
      justify-self: end;
      color: var(--otziv-info);
      pointer-events: auto;
    }
    .mobile-pager-nav button:disabled { cursor: default; opacity: 0.42; }
    .mobile-pager-nav > .mobile-pager-back:disabled { opacity: 0.42; }
    .mobile-pager-turns > span { min-width: 3.18rem; color: var(--otziv-info); font-size: 0.66rem; font-weight: 1000; line-height: 1; text-align: center; white-space: nowrap; }
    .material-icons-sharp { font-size: 1.05rem; }
    :host-context(body.otziv-dark-theme) .mobile-bottom-pager { border-color: rgba(151, 169, 183, 0.18); background: rgba(31, 38, 41, 0.94); box-shadow: none; }
    :host-context(body.otziv-dark-theme) .mobile-pager-actions.mobile-pager-actions ::ng-deep button,
    :host-context(body.otziv-dark-theme) .mobile-pager-nav.mobile-pager-nav button { border-color: rgba(151, 169, 183, 0.18); background: #1f282c !important; }
    :host-context(body.otziv-dark-theme) .mobile-pager-actions > .mobile-pager-reminders.mobile-pager-reminders {
      border-color: rgba(151, 169, 183, 0.18);
      background: #1f282c !important;
    }
    :host-context(body.otziv-dark-theme) .mobile-pager-actions.mobile-pager-actions ::ng-deep button.active {
      background: #2b3542 !important;
    }
    @media (max-width: 360px) {
      .mobile-bottom-pager {
        --otziv-pager-gap: 0.22rem;
        --otziv-pager-action-gap: clamp(0.8rem, calc(100vw - 19.3rem), var(--otziv-pager-button-size, 2.06rem));
      }
      .mobile-pager-nav { grid-template-columns: minmax(0, 1fr) 2.54rem; }
    }
  `]
})
export class MobileBottomPagerComponent {
  private readonly location = inject(Location);
  private readonly router = inject(Router);

  @Input() pageIndex = 0;
  @Input() totalPages = 1;
  @Input() disabled = false;
  @Input() fallbackUrl = '/';
  @Output() readonly previous = new EventEmitter<void>();
  @Output() readonly next = new EventEmitter<void>();

  get safeTotalPages(): number {
    return Math.max(1, this.totalPages || 1);
  }

  reminderButtonLabel(count: number): string {
    if (!count) {
      return 'Напоминания, активных нет';
    }

    return `Напоминания, активных: ${count}`;
  }

  goBack(): void {
    if (typeof window !== 'undefined' && window.history.length > 1) {
      this.location.back();
      return;
    }

    void this.router.navigateByUrl(this.fallbackUrl || '/');
  }
}
