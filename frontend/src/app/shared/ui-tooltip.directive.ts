import { Directive, ElementRef, HostBinding, HostListener, Input, inject } from '@angular/core';

@Directive({
  selector: '[appTooltip]',
  standalone: true
})
export class UiTooltipDirective {
  private static activeTooltip: UiTooltipDirective | null = null;
  private readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private tooltipText = '';
  private open = false;

  @Input('appTooltip')
  set appTooltip(value: string | null | undefined) {
    this.tooltipText = (value ?? '').trim();
    if (!this.tooltipText) {
      this.open = false;
    }
  }

  @HostBinding('attr.data-tooltip')
  get dataTooltip(): string | null {
    return this.tooltipText || null;
  }

  @HostBinding('attr.aria-description')
  get ariaDescription(): string | null {
    return this.tooltipText || null;
  }

  @HostBinding('class.tooltip-open')
  get tooltipOpen(): boolean {
    return this.open && Boolean(this.tooltipText);
  }

  @HostBinding('attr.aria-expanded')
  get ariaExpanded(): string | null {
    return this.tooltipText ? String(this.open) : null;
  }

  @HostListener('click', ['$event'])
  toggleTooltip(event: MouseEvent): void {
    if (!this.tooltipText) {
      return;
    }

    if (this.isNestedInteractiveTarget(event.target)) {
      this.open = false;
      if (UiTooltipDirective.activeTooltip === this) {
        UiTooltipDirective.activeTooltip = null;
      }
      return;
    }

    const nextOpen = !this.open;
    if (nextOpen && UiTooltipDirective.activeTooltip && UiTooltipDirective.activeTooltip !== this) {
      UiTooltipDirective.activeTooltip.open = false;
    }

    this.open = nextOpen;
    UiTooltipDirective.activeTooltip = this.open ? this : null;
    event.stopPropagation();
  }

  @HostListener('document:click', ['$event'])
  closeOnDocumentClick(event: MouseEvent): void {
    if (!this.open) {
      return;
    }

    if (!this.elementRef.nativeElement.contains(event.target as Node | null)) {
      this.open = false;
      if (UiTooltipDirective.activeTooltip === this) {
        UiTooltipDirective.activeTooltip = null;
      }
    }
  }

  @HostListener('document:keydown.escape')
  closeOnEscape(): void {
    this.open = false;
    if (UiTooltipDirective.activeTooltip === this) {
      UiTooltipDirective.activeTooltip = null;
    }
  }

  private isNestedInteractiveTarget(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) {
      return false;
    }

    const interactive = target.closest('a[href], button, input, option, select, summary, textarea');
    return Boolean(interactive && interactive !== this.elementRef.nativeElement);
  }
}
