import { Component } from '@angular/core';
import { MobileConfirmService } from './mobile-confirm.service';

@Component({
  selector: 'app-mobile-confirm-host',
  standalone: true,
  template: `
    @if (confirm.active(); as dialog) {
      <section class="confirm-backdrop" role="presentation" (click)="confirm.close(false)">
        <article
          class="confirm-sheet"
          [class.danger]="dialog.danger"
          role="dialog"
          aria-modal="true"
          [attr.aria-label]="dialog.title"
          (click)="$event.stopPropagation()"
        >
          <header>
            <div>
              <p>Компания О!</p>
              <h2>{{ dialog.title }}</h2>
            </div>
            <button type="button" (click)="confirm.close(false)" aria-label="Закрыть">
              <span class="material-icons-sharp">close</span>
            </button>
          </header>

          <p class="confirm-message">{{ dialog.message }}</p>

          <footer>
            <button type="button" class="secondary" (click)="confirm.close(false)">
              {{ dialog.cancelText }}
            </button>
            <button type="button" class="primary" (click)="confirm.close(true)">
              {{ dialog.confirmText }}
            </button>
          </footer>
        </article>
      </section>
    }
  `,
  styles: [`
    :host {
      display: contents;
    }

    .confirm-backdrop {
      position: fixed;
      inset: 0;
      z-index: 10000;
      display: grid;
      align-items: end;
      padding: 1rem max(0.5rem, env(safe-area-inset-right)) max(0.5rem, env(safe-area-inset-bottom)) max(0.5rem, env(safe-area-inset-left));
      background: rgba(16, 20, 28, 0.38);
      backdrop-filter: blur(4px);
    }

    .confirm-sheet {
      display: grid;
      gap: 0.85rem;
      border: 1px solid rgba(135, 151, 178, 0.18);
      border-radius: 1.1rem;
      padding: 0.95rem;
      background: linear-gradient(145deg, #fff, rgba(247, 250, 255, 0.98));
      box-shadow: 0 1rem 2.4rem rgba(31, 44, 71, 0.18);
      color: var(--otziv-dark);
    }

    header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 0.75rem;
    }

    p,
    h2 {
      margin: 0;
    }

    header p {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 1000;
      text-transform: uppercase;
    }

    h2 {
      color: var(--otziv-dark);
      font-size: 1.08rem;
      font-weight: 900;
      line-height: 1.16;
    }

    header button {
      display: grid;
      flex: 0 0 auto;
      width: 2.35rem;
      height: 2.35rem;
      place-items: center;
      border: 1px solid rgba(116, 154, 207, 0.18);
      border-radius: 0.8rem;
      background: var(--otziv-light);
      color: var(--otziv-primary);
    }

    .confirm-message {
      color: var(--otziv-dark);
      font-size: 0.86rem;
      font-weight: 800;
      line-height: 1.35;
    }

    footer {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.55rem;
    }

    footer button {
      min-height: 2.55rem;
      border: 1px solid rgba(135, 151, 178, 0.22);
      border-radius: 999px;
      background: #fff;
      color: var(--otziv-primary);
      font: inherit;
      font-size: 0.75rem;
      font-weight: 900;
    }

    footer .primary {
      border-color: rgba(116, 154, 207, 0.35);
      background: var(--otziv-primary);
      color: #fff;
    }

    .confirm-sheet.danger footer .primary {
      border-color: rgba(239, 52, 95, 0.38);
      background: var(--otziv-danger);
    }

    :host-context(body.otziv-dark-theme) .confirm-sheet {
      border-color: rgba(151, 169, 183, 0.18);
      background: linear-gradient(145deg, rgba(31,38,41,0.98), rgba(22,27,29,0.96));
      box-shadow: none;
    }

    :host-context(body.otziv-dark-theme) footer button,
    :host-context(body.otziv-dark-theme) header button {
      border-color: rgba(151, 169, 183, 0.2);
      background: #151b1d;
    }
  `]
})
export class MobileConfirmHostComponent {
  constructor(readonly confirm: MobileConfirmService) {}
}
