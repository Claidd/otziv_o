import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'app-mobile-search-bar',
  standalone: true,
  template: `
    <section class="mobile-search-bar" [class.has-extra]="hasExtraAction">
      <label>
        <span class="material-icons-sharp">search</span>
        <input
          type="search"
          [placeholder]="placeholder"
          autocomplete="off"
          autocorrect="off"
          enterkeyhint="search"
          [value]="value"
          (input)="handleInput($event)"
          (search)="searchSubmit.emit()"
          (keydown.enter)="searchSubmit.emit()"
        >
      </label>
      <ng-content select="[mobileSearchActions]" />
      @if (showRefresh) {
        <button type="button" class="mobile-search-button" (click)="refresh.emit()" [disabled]="refreshDisabled" aria-label="Обновить">
          <span class="material-icons-sharp">refresh</span>
        </button>
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
    }

    .mobile-search-bar {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      grid-auto-flow: column;
      grid-auto-columns: var(--otziv-search-action-size, 1.98rem);
      gap: 0.32rem;
      min-width: 0;
      overflow-x: auto;
      border: 1px solid rgba(135, 151, 178, 0.14);
      border-radius: 0.9rem;
      padding: 0.32rem;
      background: linear-gradient(135deg, rgba(255,255,255,0.96), rgba(244,247,252,0.9));
      box-shadow: 0 0.5rem 1.3rem rgba(31,44,71,0.055);
    }

    .mobile-search-bar.has-extra {
      grid-template-columns: minmax(0, 1fr);
    }

    label {
      display: flex;
      min-width: min(10.5rem, 54vw);
      align-items: center;
      gap: 0.28rem;
      border: 1px solid rgba(135, 151, 178, 0.18);
      border-radius: 0.72rem;
      padding: 0 0.5rem;
      background: #fff;
    }

    input {
      width: 100%;
      height: var(--otziv-search-control-height, 1.98rem);
      border: 0;
      outline: 0;
      background: transparent;
      color: var(--otziv-dark);
      font-size: 0.7rem;
      font-weight: 900;
    }

    .material-icons-sharp {
      color: var(--otziv-info);
      font-size: 0.94rem;
    }

    .mobile-search-button,
    ::ng-deep [mobileSearchActions] {
      display: grid;
      width: var(--otziv-search-action-size, 1.98rem);
      min-width: var(--otziv-search-action-size, 1.98rem);
      height: var(--otziv-search-action-size, 1.98rem);
      place-items: center;
      border: 1px solid rgba(116,154,207,0.18);
      border-radius: 0.62rem;
      background: var(--otziv-light);
      color: var(--otziv-primary);
      font: inherit;
      font-weight: 900;
    }

    :host-context(body.otziv-dark-theme) .mobile-search-bar {
      border-color: rgba(151, 169, 183, 0.18);
      background: linear-gradient(145deg, rgba(31,38,41,0.98), rgba(22,27,29,0.96));
      box-shadow: none;
    }

    :host-context(body.otziv-dark-theme) label,
    :host-context(body.otziv-dark-theme) .mobile-search-button,
    :host-context(body.otziv-dark-theme) ::ng-deep [mobileSearchActions] {
      border-color: rgba(151, 169, 183, 0.2);
      background: #151b1d;
    }
  `]
})
export class MobileSearchBarComponent {
  @Input() value = '';
  @Input() placeholder = 'Поиск';
  @Input() showRefresh = true;
  @Input() refreshDisabled = false;
  @Input() hasExtraAction = false;
  @Output() valueChange = new EventEmitter<string>();
  @Output() refresh = new EventEmitter<void>();
  @Output() searchSubmit = new EventEmitter<void>();

  handleInput(event: Event): void {
    this.valueChange.emit((event.target as HTMLInputElement | null)?.value ?? '');
  }
}
