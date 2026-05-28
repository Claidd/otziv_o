import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'app-mobile-action-sheet',
  standalone: true,
  template: `
    <section class="mobile-action-sheet" role="dialog" aria-modal="true" [attr.aria-label]="title">
      <header>
        <div>
          @if (kicker) {
            <p>{{ kicker }}</p>
          }
          <h2>{{ title }}</h2>
        </div>
        <button type="button" (click)="close.emit()" aria-label="Закрыть">
          <span class="material-icons-sharp">close</span>
        </button>
      </header>

      <div class="mobile-action-sheet-body">
        <ng-content />
      </div>
    </section>
  `,
  styles: [`
    :host {
      display: block;
      height: 100%;
      min-height: 0;
    }

    .mobile-action-sheet {
      display: grid;
      grid-template-rows: auto minmax(0, 1fr);
      gap: 0.6rem;
      height: 100%;
      max-height: 100%;
      min-height: 0;
      overflow: hidden;
      padding: 0.72rem;
      color: var(--otziv-dark);
      background: var(--otziv-background);
    }

    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      min-width: 0;
    }

    header div {
      min-width: 0;
    }

    p,
    h2 {
      margin: 0;
    }

    p {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 1000;
      line-height: 1;
      text-transform: uppercase;
    }

    h2 {
      margin-top: 0.12rem;
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: 1.18rem;
      font-weight: 900;
      line-height: 1.08;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    button {
      display: grid;
      width: 2.25rem;
      height: 2.25rem;
      flex: 0 0 auto;
      place-items: center;
      border: 0;
      border-radius: 0.85rem;
      color: var(--otziv-primary);
      background: var(--otziv-light);
      font: inherit;
      font-weight: 900;
    }

    .material-icons-sharp {
      font-size: 1.22rem;
    }

    .mobile-action-sheet-body {
      display: grid;
      gap: 0.48rem;
      min-height: 0;
      overflow: hidden;
    }

    :host-context(body.otziv-dark-theme) .mobile-action-sheet {
      background: var(--otziv-background);
    }
  `]
})
export class MobileActionSheetComponent {
  @Input() kicker = '';
  @Input() title = '';
  @Output() close = new EventEmitter<void>();
}
