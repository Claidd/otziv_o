import { Component, EventEmitter, Input, Output } from '@angular/core';

export type MobileStatusTone = 'blue' | 'green' | 'yellow' | 'red' | 'teal' | 'violet' | 'pink' | 'gray';

export interface MobileStatusItem {
  key: string;
  title?: string;
  label?: string;
  value: string | number;
  icon: string;
  tone?: MobileStatusTone;
  badge?: string | number | null;
  description?: string;
  disabled?: boolean;
  ariaLabel?: string;
}

@Component({
  selector: 'app-mobile-status-slider',
  standalone: true,
  template: `
    <section class="mobile-status-slider" [attr.aria-label]="ariaLabel">
      @for (item of items; track item.key) {
        <button
          type="button"
          class="status-tile tone-{{ item.tone || 'blue' }}"
          [class.active]="item.key === activeKey"
          [class.locked]="item.disabled"
          [disabled]="item.disabled"
          [attr.aria-pressed]="item.key === activeKey"
          [attr.aria-label]="item.ariaLabel || item.title || item.label || item.key"
          (click)="select.emit(item.key)"
        >
          <span class="material-icons-sharp" aria-hidden="true">{{ item.icon }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.title || item.label || item.key }}</small>
          @if (item.badge !== undefined && item.badge !== null && item.badge !== '') {
            <em>{{ item.badge }}</em>
          }
        </button>
      }
    </section>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .mobile-status-slider {
      display: grid;
      grid-auto-columns: minmax(var(--otziv-status-tile-min, 6.35rem), 1fr);
      grid-auto-flow: column;
      gap: var(--otziv-list-gap, 0.4rem);
      min-width: 0;
      overflow-x: auto;
      padding: 0.01rem 0.02rem 0.04rem;
      scroll-padding-inline: var(--otziv-page-padding-x, 0.62rem);
      scroll-snap-type: x proximity;
      scrollbar-width: none;
      -webkit-overflow-scrolling: touch;
    }
    .mobile-status-slider::-webkit-scrollbar { display: none; }
    /* Keep the shared mobile tile above the desktop-wide button reset. */
    :host .status-tile.status-tile {
      display: grid;
      position: relative;
      grid-template-columns: 1.72rem minmax(0, 1fr);
      grid-template-rows: 1fr 1fr;
      min-width: 0;
      min-height: var(--otziv-status-tile-height, 2.62rem);
      align-items: center;
      column-gap: 0.36rem;
      overflow: hidden;
      border: 1px solid rgba(116, 154, 207, 0.28);
      border-radius: 0.78rem;
      padding: 0.32rem 0.44rem;
      color: var(--otziv-dark);
      background: linear-gradient(145deg, var(--otziv-white), rgba(246, 250, 255, 0.96));
      box-shadow: 0 0.5rem 1.2rem rgba(31, 44, 71, 0.055);
      font: inherit;
      text-align: left;
      scroll-snap-align: start;
      cursor: pointer;
    }
    :host .status-tile.status-tile.active {
      border-color: rgba(116, 154, 207, 0.5);
      background: linear-gradient(145deg, #edf5ff, var(--otziv-white));
      box-shadow: inset 0 0 0 1px rgba(116, 154, 207, 0.18), 0 0.55rem 1.25rem rgba(31, 44, 71, 0.07);
    }
    :host .status-tile.status-tile.locked { opacity: 0.55; cursor: default; }
    .material-icons-sharp {
      grid-row: 1 / 3;
      display: grid;
      width: 1.62rem;
      height: 1.62rem;
      place-items: center;
      border-radius: 0.58rem;
      color: var(--otziv-primary);
      background: rgba(116, 154, 207, 0.14);
      font-size: 0.92rem;
    }
    strong, small { min-width: 0; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    strong { align-self: end; font-size: 0.86rem; font-weight: 1000; line-height: 0.95; }
    small { align-self: start; color: var(--otziv-info); font-size: 0.58rem; font-weight: 900; line-height: 1.05; }
    em {
      position: absolute;
      top: 0.24rem;
      right: 0.32rem;
      display: grid;
      min-width: 1rem;
      height: 1rem;
      place-items: center;
      border-radius: 999px;
      padding: 0 0.22rem;
      color: #fff;
      background: var(--otziv-danger);
      font-size: 0.55rem;
      font-style: normal;
      font-weight: 1000;
      line-height: 1;
    }
    .tone-green { border-color: rgba(68, 158, 133, 0.3); }
    .tone-yellow { border-color: rgba(209, 164, 52, 0.34); }
    .tone-red, .tone-pink { border-color: rgba(239, 52, 95, 0.3); }
    .tone-teal { border-color: rgba(54, 151, 169, 0.3); }
    .tone-violet { border-color: rgba(178, 94, 216, 0.3); }
    .tone-gray { border-color: rgba(135, 151, 178, 0.26); }
    .tone-green .material-icons-sharp { color: #449e85; background: rgba(68, 158, 133, 0.12); }
    .tone-yellow .material-icons-sharp { color: #b88a19; background: rgba(209, 164, 52, 0.13); }
    .tone-red .material-icons-sharp, .tone-pink .material-icons-sharp { color: var(--otziv-danger); background: rgba(239, 52, 95, 0.12); }
    .tone-teal .material-icons-sharp { color: #3697a9; background: rgba(54, 151, 169, 0.12); }
    .tone-violet .material-icons-sharp { color: #a656ce; background: rgba(178, 94, 216, 0.12); }
    .tone-gray .material-icons-sharp { color: var(--otziv-info); background: rgba(135, 151, 178, 0.13); }
    :host-context(body.otziv-dark-theme) .status-tile.status-tile {
      border-color: rgba(151, 169, 183, 0.18);
      background: linear-gradient(145deg, rgba(31, 38, 41, 0.98), rgba(24, 30, 32, 0.94));
      box-shadow: none;
    }
    :host-context(body.otziv-dark-theme) .status-tile.status-tile.active {
      border-color: rgba(116, 154, 207, 0.5);
      background: linear-gradient(145deg, rgba(38, 48, 53, 0.98), rgba(25, 31, 34, 0.96));
    }
  `]
})
export class MobileStatusSliderComponent {
  @Input() items: MobileStatusItem[] = [];
  @Input() activeKey = '';
  @Input() ariaLabel = 'Выбор статуса';
  @Output() readonly select = new EventEmitter<string>();
}
