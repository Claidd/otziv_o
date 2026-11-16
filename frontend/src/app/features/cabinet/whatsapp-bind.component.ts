import { Component, DestroyRef, OnDestroy, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CabinetApi, WhatsAppClientStatus } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';
import { VisibilityAwarePoller, backoffDelayMs } from '../../shared/visibility-aware-poller';

const WHATSAPP_STATUS_POLL_MS = 6000;
const WHATSAPP_CONNECTED_POLL_MS = 30_000;
const WHATSAPP_STATUS_MAX_BACKOFF_MS = 60_000;

@Component({
  selector: 'app-whatsapp-bind',
  imports: [AdminLayoutComponent],
  templateUrl: './whatsapp-bind.component.html',
  styleUrl: './whatsapp-bind.component.scss'
})
export class WhatsAppBindComponent implements OnDestroy {
  readonly status = signal<WhatsAppClientStatus | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  private failedLoads = 0;
  private requestPending = false;
  private readonly destroyRef = inject(DestroyRef);
  private readonly poller = new VisibilityAwarePoller(() => {
    if (this.status()?.configured !== false) {
      this.loadStatus(false);
    }
  }, () => backoffDelayMs(this.status()?.ready ? WHATSAPP_CONNECTED_POLL_MS : WHATSAPP_STATUS_POLL_MS,
    this.failedLoads, WHATSAPP_STATUS_MAX_BACKOFF_MS));

  constructor(private readonly cabinetApi: CabinetApi) {
    this.loadStatus();
    this.poller.start();
  }

  ngOnDestroy(): void {
    this.poller.stop();
  }

  loadStatus(showLoading = true): void {
    if (this.requestPending) return;
    this.requestPending = true;
    if (showLoading) {
      this.loading.set(true);
    }
    this.error.set(null);

    this.cabinetApi.getWhatsAppBindingStatus({ skipAuthRedirectOn401: true }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (status) => {
        this.requestPending = false;
        this.failedLoads = 0;
        this.status.set(status);
        this.poller.reschedule();
        this.loading.set(false);
      },
      error: (err) => {
        this.requestPending = false;
        this.status.set(null);
        this.failedLoads += 1;
        this.poller.reschedule();
        this.error.set(apiErrorDetail(err, 'Не удалось получить QR-код WhatsApp.'));
        this.loading.set(false);
      }
    });
  }

  stateLabel(): string {
    const status = this.status();
    if (!status) {
      return 'Загрузка';
    }
    if (!status.configured) {
      return 'Не настроен';
    }
    if (status.ready) {
      return 'Привязан';
    }
    if (status.qrDataUrl) {
      return 'Ожидает сканирования';
    }
    if (status.lastError) {
      return 'Ошибка';
    }
    return 'Готовится';
  }

  stateTone(): string {
    const status = this.status();
    if (!status?.configured || status?.lastError) {
      return 'danger';
    }
    if (status.ready) {
      return 'success';
    }
    if (status.qrDataUrl) {
      return 'warning';
    }
    return 'neutral';
  }

  formattedDate(value?: string | null): string {
    if (!value) {
      return '-';
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }

    return date.toLocaleString('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    });
  }
}
