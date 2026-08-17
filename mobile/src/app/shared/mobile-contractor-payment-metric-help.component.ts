import { Component, Input, signal } from '@angular/core';

@Component({
  selector: 'app-mobile-contractor-payment-metric-help',
  standalone: true,
  template: `
    <button
      type="button"
      class="metric-help-trigger"
      [attr.aria-expanded]="expanded()"
      [attr.aria-controls]="descriptionId"
      (click)="toggle()"
    >
      <span>{{ label }}</span>
      <span class="metric-help-icon" aria-hidden="true">i</span>
    </button>
    <p
      class="metric-help-description"
      [id]="descriptionId"
      [hidden]="!expanded()"
    >
      {{ description }}
    </p>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
    }

    .metric-help-trigger {
      display: flex;
      width: 100%;
      min-width: 0;
      min-height: 44px;
      align-items: center;
      justify-content: space-between;
      gap: 0.45rem;
      border: 0;
      padding: 0.28rem 0;
      color: var(--otziv-info);
      background: transparent;
      font: inherit;
      font-size: 0.75rem;
      font-weight: 900;
      line-height: 1.3;
      text-align: left;
      cursor: pointer;
      -webkit-tap-highlight-color: transparent;
      touch-action: manipulation;
    }

    .metric-help-trigger > span:first-child {
      min-width: 0;
      overflow-wrap: anywhere;
    }

    .metric-help-trigger:focus-visible {
      border-radius: 0.4rem;
      outline: 2px solid var(--otziv-primary);
      outline-offset: 2px;
    }

    .metric-help-icon {
      display: inline-grid;
      flex: 0 0 auto;
      width: 1rem;
      height: 1rem;
      place-items: center;
      border: 1px solid currentColor;
      border-radius: 50%;
      font-size: 0.62rem;
      line-height: 1;
    }

    .metric-help-description {
      margin: 0.18rem 0 0;
      color: var(--otziv-dark);
      font-size: 0.75rem;
      font-weight: 700;
      line-height: 1.42;
      overflow-wrap: break-word;
      hyphens: auto;
    }
  `]
})
export class MobileContractorPaymentMetricHelpComponent {
  @Input({ required: true }) label!: string;
  @Input({ required: true }) description!: string;
  @Input({ required: true }) descriptionId!: string;

  readonly expanded = signal(false);

  toggle(): void {
    this.expanded.update((value) => !value);
  }
}
