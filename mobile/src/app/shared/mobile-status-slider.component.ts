import { Component, EventEmitter, Input, Output } from '@angular/core';

export interface MobileStatusItem {
  key: string;
  title: string;
  value: string | number;
  icon: string;
  tone?: 'blue' | 'green' | 'yellow' | 'red' | 'teal' | 'violet' | 'pink' | 'gray';
  badge?: string | number | null;
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
          [attr.aria-label]="item.ariaLabel || item.title"
          (click)="select.emit(item.key)"
        >
          <span class="material-icons-sharp">{{ item.icon }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.title }}</small>
          @if (item.badge !== undefined && item.badge !== null && item.badge !== '') {
            <em>{{ item.badge }}</em>
          }
        </button>
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
    }

    .mobile-status-slider {
      display: grid;
      grid-auto-columns: minmax(var(--otziv-status-tile-min, 6.35rem), 1fr);
      grid-auto-flow: column;
      gap: var(--otziv-list-gap, 0.4rem);
      overflow-x: auto;
      padding: 0.01rem 0.02rem 0.04rem;
      scroll-padding-inline: var(--otziv-page-padding-x, 0.62rem);
      scrollbar-width: none;
    }

    .mobile-status-slider::-webkit-scrollbar {
      display: none;
    }

    .status-tile {
      display: grid;
      grid-template-columns: 1.72rem minmax(0, 1fr);
      grid-template-rows: 1fr 1fr;
      position: relative;
      min-width: 0;
      min-height: var(--otziv-status-tile-height, 2.62rem);
      align-items: center;
      column-gap: 0.36rem;
      overflow: hidden;
      border: 1px solid rgba(116,154,207,0.28);
      border-radius: 0.78rem;
      padding: 0.32rem 0.44rem;
      background: linear-gradient(145deg, #fff, rgba(246,250,255,0.96));
      color: var(--otziv-dark);
      text-align: left;
      box-shadow: 0 0.5rem 1.2rem rgba(31,44,71,0.055);
    }

    .status-tile.active {
      background: linear-gradient(145deg, #edf5ff, #fff);
      box-shadow: inset 0 0 0 1px rgba(116,154,207,0.18), 0 0.55rem 1.25rem rgba(31,44,71,0.07);
    }

    .status-tile.locked {
      opacity: 0.55;
    }

    .material-icons-sharp {
      grid-row: 1 / 3;
      display: grid;
      width: 1.62rem;
      height: 1.62rem;
      place-items: center;
      border-radius: 0.58rem;
      background: rgba(116,154,207,0.14);
      color: var(--otziv-primary);
      font-size: 0.92rem;
    }

    strong,
    small {
      min-width: 0;
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    strong {
      align-self: end;
      font-size: 0.86rem;
      font-weight: 1000;
      line-height: 0.95;
    }

    small {
      align-self: start;
      color: var(--otziv-info);
      font-size: 0.58rem;
      font-weight: 900;
      line-height: 1.05;
    }

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

    .tone-green { border-color: rgba(68,158,133,0.28); }
    .tone-yellow { border-color: rgba(209,164,52,0.32); }
    .tone-red { border-color: rgba(239,52,95,0.3); }
    .tone-teal { border-color: rgba(54,151,169,0.3); }
    .tone-violet { border-color: rgba(178,94,216,0.3); }
    .tone-pink { border-color: rgba(239,52,95,0.28); }
    .tone-gray { border-color: rgba(135,151,178,0.26); }

    .tone-green .material-icons-sharp { background: rgba(68,158,133,0.12); color: #449e85; }
    .tone-yellow .material-icons-sharp { background: rgba(209,164,52,0.13); color: #b88a19; }
    .tone-red .material-icons-sharp { background: rgba(239,52,95,0.12); color: var(--otziv-danger); }
    .tone-teal .material-icons-sharp { background: rgba(54,151,169,0.12); color: #3697a9; }
    .tone-violet .material-icons-sharp { background: rgba(178,94,216,0.12); color: #a656ce; }
    .tone-pink .material-icons-sharp { background: rgba(239,52,95,0.12); color: var(--otziv-danger); }
    .tone-gray .material-icons-sharp { background: rgba(135,151,178,0.13); color: var(--otziv-info); }

    :host-context(body.otziv-dark-theme) .status-tile {
      border-color: rgba(151,169,183,0.18);
      background: linear-gradient(145deg, rgba(31,38,41,0.98), rgba(24,30,32,0.94));
      box-shadow: none;
    }

    :host-context(body.otziv-dark-theme) .status-tile.active {
      background: linear-gradient(145deg, rgba(38,48,53,0.98), rgba(25,31,34,0.96));
      border-color: rgba(116,154,207,0.5);
    }
  `]
})
export class MobileStatusSliderComponent {
  @Input() items: MobileStatusItem[] = [];
  @Input() activeKey = '';
  @Input() ariaLabel = 'Выбор статуса';
  @Output() select = new EventEmitter<string>();
}
