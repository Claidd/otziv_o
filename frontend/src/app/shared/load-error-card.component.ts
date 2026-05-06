import { Component, EventEmitter, Input, OnChanges, OnDestroy, Output } from '@angular/core';

@Component({
  selector: 'app-load-error-card',
  templateUrl: './load-error-card.component.html',
  styleUrl: './load-error-card.component.scss'
})
export class LoadErrorCardComponent implements OnChanges, OnDestroy {
  @Input() title = 'Данные не загрузились';
  @Input() message = '';
  @Input() icon = 'cloud_off';
  @Input() actionLabel = '';
  @Input() loading = false;
  @Input() disabled = false;
  @Input() autoDismissMs = 3000;

  @Output() retry = new EventEmitter<void>();

  visible = true;

  private dismissTimer: ReturnType<typeof setTimeout> | null = null;

  ngOnChanges(): void {
    this.scheduleAutoDismiss();
  }

  ngOnDestroy(): void {
    this.clearAutoDismiss();
  }

  onRetry(): void {
    this.retry.emit();
  }

  private scheduleAutoDismiss(): void {
    this.visible = true;
    this.clearAutoDismiss();

    if (this.autoDismissMs <= 0) {
      return;
    }

    this.dismissTimer = setTimeout(() => {
      this.visible = false;
      this.dismissTimer = null;
    }, this.autoDismissMs);
  }

  private clearAutoDismiss(): void {
    if (!this.dismissTimer) {
      return;
    }

    clearTimeout(this.dismissTimer);
    this.dismissTimer = null;
  }
}
