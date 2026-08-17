import { Component, Input, signal } from '@angular/core';

@Component({
  selector: 'app-contractor-payment-metric-help',
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
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
      width: 100%;
      min-height: 2.75rem;
      max-width: 100%;
      border: 0;
      padding: 0.35rem 0;
      color: var(--otziv-info);
      background: transparent;
      font: inherit;
      font-size: 0.75rem;
      font-weight: 800;
      line-height: 1.3;
      text-align: left;
      overflow-wrap: anywhere;
      cursor: pointer;
    }

    .metric-help-trigger span:first-child {
      min-width: 0;
      overflow-wrap: anywhere;
    }

    .metric-help-trigger:hover span:first-child {
      text-decoration: underline;
      text-underline-offset: 0.15rem;
    }

    .metric-help-trigger:focus-visible {
      border-radius: 0.2rem;
      outline: 2px solid var(--otziv-primary);
      outline-offset: 0.2rem;
    }

    .metric-help-icon {
      display: inline-grid;
      flex: 0 0 auto;
      place-items: center;
      width: 0.9rem;
      height: 0.9rem;
      margin-top: 0.01rem;
      border: 1px solid currentColor;
      border-radius: 50%;
      font-size: 0.58rem;
      line-height: 1;
      text-decoration: none;
    }

    .metric-help-description {
      margin: 0.3rem 0 0;
      color: var(--otziv-info);
      font-size: 0.75rem;
      font-weight: 500;
      line-height: 1.42;
      overflow-wrap: anywhere;
    }
  `]
})
export class ContractorPaymentMetricHelpComponent {
  @Input({ required: true }) label!: string;
  @Input({ required: true }) description!: string;
  @Input({ required: true }) descriptionId!: string;

  readonly expanded = signal(false);

  toggle(): void {
    this.expanded.update((value) => !value);
  }
}
