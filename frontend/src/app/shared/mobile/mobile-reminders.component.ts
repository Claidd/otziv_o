import { Component, HostListener, OnInit, ViewChild, computed, effect, inject, signal } from '@angular/core';
import { PersonalRemindersComponent } from '../personal-reminders.component';
import { PersonalRemindersService } from '../personal-reminders.service';

@Component({
  selector: 'app-mobile-reminders',
  standalone: true,
  imports: [PersonalRemindersComponent],
  template: `
    @if (sheetOpen()) {
      <div class="mobile-reminders-backdrop" (pointerdown)="close()">
        <section
          class="mobile-reminders-sheet"
          role="dialog"
          aria-modal="true"
          aria-labelledby="mobile-reminders-title"
          (pointerdown)="$event.stopPropagation()"
        >
          <header class="mobile-reminders-head">
            <div>
              <p>Личные дела</p>
              <h2 id="mobile-reminders-title">Напоминания</h2>
            </div>

            <div class="mobile-reminders-head-actions">
              <button type="button" (click)="openCreate()" aria-label="Создать напоминание">
                <span class="material-icons-sharp" aria-hidden="true">add</span>
              </button>
              <button type="button" (click)="close()" aria-label="Закрыть напоминания">
                <span class="material-icons-sharp" aria-hidden="true">close</span>
              </button>
            </div>
          </header>

          <div class="mobile-reminders-content">
            <app-personal-reminders mode="list" />
          </div>
        </section>
      </div>
    }
  `,
  styles: [`
    :host {
      display: contents;
    }

    .mobile-reminders-backdrop {
      --mobile-reminders-nav-offset: calc(var(--otziv-mobile-nav-height, 2.56rem) + env(safe-area-inset-bottom));
      position: fixed;
      z-index: 190;
      inset: 0;
      bottom: var(--mobile-reminders-nav-offset);
      display: grid;
      align-items: end;
      justify-items: center;
      overflow: hidden;
      padding: max(0.5rem, env(safe-area-inset-top)) max(0.45rem, env(safe-area-inset-right)) max(0.45rem, env(safe-area-inset-bottom)) max(0.45rem, env(safe-area-inset-left));
      background: rgba(31, 36, 48, 0.46);
      backdrop-filter: blur(3px);
      animation: mobile-reminders-fade-in 140ms ease-out both;
    }

    .mobile-reminders-sheet {
      display: grid;
      width: min(100%, 35rem);
      height: min(86dvh, 38rem);
      min-height: min(25rem, calc(100dvh - 1rem));
      grid-template-rows: auto minmax(0, 1fr);
      overflow: hidden;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 1.45rem;
      color: var(--otziv-dark);
      background: var(--otziv-background);
      box-shadow: 0 1.35rem 3rem rgba(31, 44, 71, 0.24);
      animation: mobile-reminders-slide-in 180ms cubic-bezier(0.2, 0.82, 0.28, 1) both;
    }

    .mobile-reminders-head {
      display: flex;
      min-width: 0;
      min-height: 4.1rem;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      border-bottom: 1px solid rgba(103, 116, 131, 0.12);
      padding: 0.74rem 0.82rem 0.66rem 0.98rem;
      background: var(--otziv-white);
    }

    .mobile-reminders-head > div:first-child {
      min-width: 0;
    }

    .mobile-reminders-head p,
    .mobile-reminders-head h2 {
      margin: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .mobile-reminders-head p {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 900;
      letter-spacing: 0.02em;
      text-transform: uppercase;
    }

    .mobile-reminders-head h2 {
      margin-top: 0.08rem;
      color: var(--otziv-dark);
      font-size: 1.28rem;
      font-weight: 1000;
      line-height: 1.05;
    }

    .mobile-reminders-head-actions {
      display: inline-flex;
      flex: 0 0 auto;
      align-items: center;
      gap: 0.42rem;
    }

    .mobile-reminders-head-actions.mobile-reminders-head-actions button {
      display: grid;
      width: 2.35rem;
      min-width: 2.35rem;
      min-height: 0;
      height: 2.35rem;
      place-items: center;
      border: 1px solid rgba(108, 155, 207, 0.2);
      border-radius: 0.78rem;
      padding: 0;
      color: var(--otziv-primary) !important;
      background: #f4f8ff !important;
      box-shadow: none;
      font: inherit;
      cursor: pointer;
    }

    .mobile-reminders-head-actions button:last-child {
      color: var(--otziv-dark) !important;
      background: var(--otziv-white) !important;
    }

    .mobile-reminders-head-actions .material-icons-sharp {
      font-size: 1.24rem;
    }

    .mobile-reminders-content {
      min-height: 0;
      overflow-y: auto;
      overscroll-behavior: contain;
      padding: 0.72rem 0.72rem calc(0.82rem + env(safe-area-inset-bottom));
      scrollbar-width: none;
    }

    .mobile-reminders-content::-webkit-scrollbar {
      display: none;
    }

    .mobile-reminders-content ::ng-deep app-personal-reminders {
      min-width: 0;
      font-family: var(--otziv-font-family);
    }

    .mobile-reminders-content ::ng-deep .personal-reminders {
      gap: 0.72rem;
      border-radius: 0;
      padding: 0;
      background: transparent;
      box-shadow: none;
    }

    .mobile-reminders-content ::ng-deep .personal-reminders__head {
      display: none;
    }

    .mobile-reminders-content ::ng-deep .reminder-form {
      gap: 0.7rem;
      border: 1px solid var(--otziv-tone-walk-border);
      border-radius: 0.95rem;
      padding: 0.75rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
    }

    .mobile-reminders-content ::ng-deep .reminder-form input,
    .mobile-reminders-content ::ng-deep .reminder-form textarea {
      min-height: 2.45rem;
      border-radius: 0.75rem;
      background: var(--otziv-white);
    }

    .mobile-reminders-content ::ng-deep .reminder-mode {
      gap: 0.45rem;
    }

    .mobile-reminders-content ::ng-deep .reminder-mode button.reminder-mode button,
    .mobile-reminders-content ::ng-deep .reminder-mode button {
      min-height: 2.15rem !important;
      border: 1px solid rgba(108, 155, 207, 0.18) !important;
      border-radius: 0.75rem;
      color: var(--otziv-info) !important;
      background: var(--otziv-white) !important;
      box-shadow: none;
    }

    .mobile-reminders-content ::ng-deep .reminder-mode button.active {
      border-color: rgba(108, 155, 207, 0.36) !important;
      color: #fff !important;
      background: var(--otziv-primary) !important;
    }

    .mobile-reminders-content ::ng-deep .reminder-form__actions {
      gap: 0.45rem;
    }

    .mobile-reminders-content ::ng-deep button.reminder-action-button.reminder-action-button {
      min-height: 2.1rem !important;
    }

    .mobile-reminders-content ::ng-deep .personal-reminders__list {
      display: grid;
      align-content: start;
      gap: 0.55rem;
      max-height: none !important;
      overflow: visible !important;
      padding: 0;
    }

    .mobile-reminders-content ::ng-deep .personal-reminder {
      --reminder-card-padding: 0.68rem;
      position: relative;
      display: grid;
      grid-template-columns: 1.9rem minmax(0, 1fr) auto;
      align-items: start;
      gap: 0.55rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.95rem;
      padding: var(--reminder-card-padding);
      color: var(--otziv-dark);
      background: linear-gradient(160deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
    }

    .mobile-reminders-content ::ng-deep .personal-reminder + .personal-reminder {
      border-top: 1px solid rgba(103, 116, 131, 0.16);
    }

    .mobile-reminders-content ::ng-deep .personal-reminder.due {
      border-color: rgba(255, 0, 96, 0.22);
      background: linear-gradient(160deg, var(--otziv-tone-correction-surface) 0%, var(--otziv-white) 72%);
    }

    .mobile-reminders-content ::ng-deep .personal-reminder__leading {
      width: 1.9rem;
      min-width: 1.9rem;
      gap: 0.02rem;
    }

    .mobile-reminders-content ::ng-deep .personal-reminder__icon {
      display: grid;
      width: 1.9rem;
      height: 1.9rem;
      place-items: center;
      border-radius: 0.68rem;
      padding: 0;
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 100%);
      font-size: 1.05rem;
    }

    .mobile-reminders-content ::ng-deep .personal-reminder.due .personal-reminder__icon {
      color: var(--otziv-danger);
      background: rgba(255, 0, 96, 0.1);
    }

    .mobile-reminders-content ::ng-deep .personal-reminder__body {
      gap: 0.12rem;
      border: 0;
      padding: 0;
      color: inherit;
      background: transparent;
      box-shadow: none;
    }

    .mobile-reminders-content ::ng-deep .personal-reminder__actions {
      align-self: center;
      gap: 0.28rem;
      padding: 0;
    }

    .mobile-reminders-content ::ng-deep button.reminder-icon-button.reminder-icon-button {
      width: 1.9rem;
      min-width: 1.9rem;
      min-height: 1.9rem !important;
      height: 1.9rem;
      border: 0;
      border-radius: 999px;
      color: var(--otziv-success) !important;
      background: rgba(27, 156, 133, 0.12) !important;
    }

    .mobile-reminders-content ::ng-deep button.reminder-delete-button.reminder-delete-button {
      width: 1.15rem;
      min-width: 1.15rem;
      min-height: 1.15rem !important;
      height: 1.15rem;
      margin-top: 0.06rem;
      color: var(--otziv-danger) !important;
      background: transparent !important;
    }

    .mobile-reminders-content ::ng-deep .personal-reminders__empty {
      border-radius: 0.9rem;
      padding: 0.85rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
    }

    :host-context(body.otziv-dark-theme) .mobile-reminders-sheet,
    :host-context(body.otziv-dark-theme) .mobile-reminders-head {
      border-color: rgba(159, 184, 215, 0.17);
      background: #1b2027;
    }

    :host-context(body.otziv-dark-theme) .mobile-reminders-head-actions button:last-child {
      color: var(--otziv-dark) !important;
      background: #242b35 !important;
    }

    @keyframes mobile-reminders-fade-in {
      from { opacity: 0; }
      to { opacity: 1; }
    }

    @keyframes mobile-reminders-slide-in {
      from { opacity: 0; transform: translateY(1.2rem) scale(0.985); }
      to { opacity: 1; transform: translateY(0) scale(1); }
    }

    @media (max-height: 650px) {
      .mobile-reminders-sheet {
        height: calc(100dvh - var(--mobile-reminders-nav-offset) - max(0.6rem, env(safe-area-inset-top)) - 0.45rem);
        min-height: 0;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .mobile-reminders-backdrop,
      .mobile-reminders-sheet {
        animation: none;
      }
    }
  `]
})
export class MobileRemindersComponent implements OnInit {
  private readonly remindersService = inject(PersonalRemindersService);

  @ViewChild(PersonalRemindersComponent) private editor?: PersonalRemindersComponent;

  readonly sheetOpen = signal(false);
  readonly activeReminderCount = computed(() => this.remindersService.activeReminders().length);
  readonly dueReminderCount = computed(() => this.remindersService.dueReminders().length);

  constructor() {
    effect((onCleanup) => {
      if (typeof document === 'undefined' || !this.sheetOpen()) {
        return;
      }

      const previousOverflow = document.body.style.overflow;
      const previousOverscrollBehavior = document.body.style.overscrollBehavior;
      document.body.classList.add('otziv-mobile-reminders-open');
      document.body.style.overflow = 'hidden';
      document.body.style.overscrollBehavior = 'none';
      onCleanup(() => {
        document.body.classList.remove('otziv-mobile-reminders-open');
        document.body.style.overflow = previousOverflow;
        document.body.style.overscrollBehavior = previousOverscrollBehavior;
      });
    });
  }

  ngOnInit(): void {
    this.remindersService.load();
  }

  open(): void {
    this.sheetOpen.set(true);
    this.remindersService.load(true);
  }

  close(): void {
    if (this.editor?.saving()) {
      return;
    }

    this.editor?.cancelEdit();
    this.sheetOpen.set(false);
  }

  openCreate(): void {
    this.editor?.openCreate();
  }

  @HostListener('document:keydown.escape')
  closeOnEscape(): void {
    if (this.sheetOpen()) {
      this.close();
    }
  }
}
