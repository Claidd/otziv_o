import { Directive, HostBinding, Input } from '@angular/core';

@Directive({
  selector: '[appTooltip]',
  standalone: true
})
export class UiTooltipDirective {
  private tooltipText = '';

  @Input('appTooltip')
  set appTooltip(value: string | null | undefined) {
    this.tooltipText = (value ?? '').trim();
  }

  @HostBinding('attr.data-tooltip')
  get dataTooltip(): string | null {
    return this.tooltipText || null;
  }

  @HostBinding('attr.aria-description')
  get ariaDescription(): string | null {
    return this.tooltipText || null;
  }
}
