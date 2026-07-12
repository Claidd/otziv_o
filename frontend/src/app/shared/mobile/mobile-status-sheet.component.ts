import {
  Component,
  ElementRef,
  EventEmitter,
  HostListener,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild
} from '@angular/core';
import type { MobileStatusItem } from './mobile-status-slider.component';

@Component({
  selector: 'app-mobile-status-sheet',
  standalone: true,
  template: `
    @if (open) {
      <div class="mobile-sheet-backdrop" (pointerdown)="onBackdropPointerDown($event)">
        <section
          class="mobile-status-sheet"
          role="dialog"
          aria-modal="true"
          [attr.aria-labelledby]="titleId"
          (pointerdown)="$event.stopPropagation()"
        >
          <header>
            <div>
              @if (kicker) { <small>{{ kicker }}</small> }
              <h2 [id]="titleId">{{ title }}</h2>
            </div>
            <button #closeButton type="button" class="close-button" aria-label="Закрыть" (click)="closed.emit()">
              <span class="material-icons-sharp" aria-hidden="true">close</span>
            </button>
          </header>

          <div class="mobile-status-options">
            @for (item of items; track item.key) {
              <button
                type="button"
                class="status-option tone-{{ item.tone || 'blue' }}"
                [class.active]="item.key === activeKey"
                [disabled]="item.disabled"
                [attr.aria-current]="item.key === activeKey ? 'true' : null"
                [attr.aria-label]="item.ariaLabel || item.title || item.label || item.key"
                (click)="selected.emit(item.key)"
              >
                <span class="material-icons-sharp option-icon" aria-hidden="true">{{ item.icon }}</span>
                <span class="option-copy">
                  <strong>{{ item.title || item.label || item.key }}</strong>
                  @if (item.description) { <small>{{ item.description }}</small> }
                </span>
                @if (item.badge !== undefined && item.badge !== null && item.badge !== '') {
                  <em>{{ item.badge }}</em>
                }
                @if (item.value !== '' && item.value !== null && item.value !== undefined) {
                  <b>{{ item.value }}</b>
                }
              </button>
            }
          </div>
        </section>
      </div>
    }
  `,
  styles: [`
    :host { display: contents; }
    .mobile-sheet-backdrop {
      position: fixed;
      z-index: 110;
      inset: 0;
      display: grid;
      place-items: center;
      overflow: hidden;
      padding: max(1rem, env(safe-area-inset-top)) max(0.75rem, env(safe-area-inset-right)) max(1rem, env(safe-area-inset-bottom)) max(0.75rem, env(safe-area-inset-left));
      background: rgba(24, 26, 30, 0.42);
    }
    .mobile-status-sheet {
      display: grid;
      width: min(23rem, calc(100vw - 1.5rem));
      max-width: 100%;
      max-height: min(38rem, calc(100dvh - env(safe-area-inset-top) - env(safe-area-inset-bottom) - 2rem));
      grid-template-rows: auto minmax(0, 1fr);
      gap: 0.75rem;
      overflow: hidden;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 1.1rem;
      padding: 0.78rem;
      color: var(--otziv-dark);
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 1.3rem 3rem rgba(15, 23, 42, 0.24);
    }
    header { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 0.75rem; padding: 0; }
    header > div { min-width: 0; }
    header small { display: block; color: var(--otziv-info); font-size: 0.68rem; font-weight: 900; text-transform: uppercase; }
    header h2 { margin: 0.08rem 0 0; color: var(--otziv-dark); font-family: var(--otziv-font-family); font-size: 1.08rem; font-weight: 1000; line-height: 1.08; }
    .close-button.close-button {
      display: grid;
      flex: 0 0 auto;
      width: 2.08rem;
      min-width: 2.08rem;
      min-height: 0;
      height: 2.08rem;
      place-items: center;
      border: 0;
      border-radius: 0.75rem;
      padding: 0;
      color: var(--otziv-dark);
      background: var(--otziv-light);
      font: inherit;
      cursor: pointer;
    }
    .close-button .material-icons-sharp { font-size: 1.35rem; }
    .mobile-status-options {
      display: grid;
      align-content: start;
      gap: 0.45rem;
      width: 100%;
      max-width: 100%;
      min-height: 0;
      overflow-y: auto;
      overflow-x: hidden;
      overscroll-behavior: contain;
      padding: 0 0.05rem 0 0;
      scrollbar-width: none;
    }
    .mobile-status-options::-webkit-scrollbar { display: none; }
    .status-option.status-option {
      --status-option-border: rgba(103, 116, 131, 0.18);
      --status-option-surface: var(--otziv-tone-walk-surface);
      display: grid;
      position: relative;
      grid-template-columns: 1.85rem minmax(0, 1fr) auto auto;
      width: 100%;
      max-width: 100%;
      min-width: 0;
      min-height: 2.72rem;
      align-items: center;
      gap: 0.05rem 0.5rem;
      overflow: hidden;
      border: 1px solid var(--status-option-border);
      border-radius: 0.9rem;
      padding: 0.42rem 0.62rem;
      color: var(--otziv-dark);
      background: linear-gradient(160deg, var(--status-option-surface) 0%, var(--otziv-white) 58%);
      box-shadow: none;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
    .status-option.status-option.active { border-color: rgba(108, 155, 207, 0.45); background: linear-gradient(160deg, var(--status-option-surface) 0%, rgba(108, 155, 207, 0.16) 100%); box-shadow: 0 0.95rem 1.8rem rgba(108, 155, 207, 0.16); }
    .status-option.status-option:disabled { opacity: 0.5; cursor: default; }
    .option-icon { display: grid; width: 1.85rem; height: 1.85rem; place-items: center; color: var(--otziv-primary); font-size: 1rem; }
    .option-copy { min-width: 0; }
    .option-copy strong, .option-copy small { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
    .option-copy strong { color: var(--otziv-info); font-size: 0.68rem; font-weight: 900; line-height: 1.12; }
    .option-copy small { margin-top: 0.1rem; color: var(--otziv-info); font-size: 0.58rem; font-weight: 800; line-height: 1.08; }
    .status-option b { min-width: 2rem; overflow: hidden; font-size: 0.98rem; font-weight: 1000; text-align: right; text-overflow: ellipsis; white-space: nowrap; }
    .status-option em { min-width: 1.1rem; border-radius: 999px; padding: 0.22rem 0.3rem; color: #fff; background: var(--otziv-danger); font-size: 0.55rem; font-style: normal; font-weight: 1000; text-align: center; }
    .tone-green { --status-option-border: var(--otziv-tone-success-border); --status-option-surface: var(--otziv-tone-success-surface); }
    .tone-yellow { --status-option-border: var(--otziv-tone-wait-border); --status-option-surface: var(--otziv-tone-wait-surface); }
    .tone-red, .tone-pink { --status-option-border: var(--otziv-tone-correction-border); --status-option-surface: var(--otziv-tone-correction-surface); }
    .tone-teal { --status-option-border: rgba(47, 159, 149, 0.28); --status-option-surface: #f4fffd; }
    .tone-violet { --status-option-border: var(--otziv-tone-publication-border); --status-option-surface: var(--otziv-tone-publication-surface); }
    .tone-gray { --status-option-border: var(--otziv-tone-walk-border); --status-option-surface: var(--otziv-tone-walk-surface); }
    .tone-blue { --status-option-border: rgba(108, 155, 207, 0.28); --status-option-surface: #f6faff; }
    .tone-green .option-icon { color: #449e85; }
    .tone-yellow .option-icon { color: #b88a19; }
    .tone-red .option-icon, .tone-pink .option-icon { color: var(--otziv-danger); }
    .tone-teal .option-icon { color: #3697a9; }
    .tone-violet .option-icon { color: #a656ce; }
    .tone-gray .option-icon { color: var(--otziv-info); }
    :host-context(body.otziv-dark-theme) .mobile-status-sheet { border-color: rgba(159, 184, 215, 0.18); background: linear-gradient(180deg, rgba(32, 38, 44, 0.98) 0%, rgba(24, 29, 34, 0.98) 100%); }
    :host-context(body.otziv-dark-theme) .status-option.status-option { border-color: rgba(159, 184, 215, 0.2); background: #242b35; box-shadow: none; }
    :host-context(body.otziv-dark-theme) .status-option.status-option.active { border-color: rgba(116, 154, 207, 0.58); background: #2b3542; }
    @media (max-width: 390px) {
      .mobile-sheet-backdrop { padding-inline: 0.65rem; }
      .mobile-status-sheet { border-radius: 1.1rem; padding: 0.62rem; }
      .status-option.status-option { min-height: 2.72rem; padding-inline: 0.55rem; }
    }
  `]
})
export class MobileStatusSheetComponent implements OnChanges, OnDestroy {
  private static nextId = 0;

  @Input() open = false;
  @Input() kicker = '';
  @Input() title = 'Выберите статус';
  @Input() items: MobileStatusItem[] = [];
  @Input() activeKey = '';
  @Output() readonly selected = new EventEmitter<string>();
  @Output() readonly closed = new EventEmitter<void>();
  @ViewChild('closeButton') private closeButton?: ElementRef<HTMLButtonElement>;

  readonly titleId = `mobile-status-sheet-title-${++MobileStatusSheetComponent.nextId}`;

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['open']) {
      return;
    }

    document.body.classList.toggle('otziv-mobile-overlay-open', this.open);
    if (this.open) {
      queueMicrotask(() => this.closeButton?.nativeElement.focus());
    }
  }

  ngOnDestroy(): void {
    if (this.open) {
      document.body.classList.remove('otziv-mobile-overlay-open');
    }
  }

  @HostListener('document:keydown.escape')
  closeOnEscape(): void {
    if (this.open) {
      this.closed.emit();
    }
  }

  onBackdropPointerDown(event: PointerEvent): void {
    if (event.target === event.currentTarget) {
      this.closed.emit();
    }
  }
}
