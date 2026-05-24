import { Component, OnDestroy, signal } from '@angular/core';
import { CabinetApi, WhatsAppClientStatus } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';

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

  private refreshTimer: number | null = null;

  constructor(private readonly cabinetApi: CabinetApi) {
    this.loadStatus();
    this.refreshTimer = window.setInterval(() => {
      const current = this.status();
      if (!current?.ready && current?.configured !== false) {
        this.loadStatus(false);
      }
    }, 6000);
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  loadStatus(showLoading = true): void {
    if (showLoading) {
      this.loading.set(true);
    }
    this.error.set(null);

    this.cabinetApi.getWhatsAppBindingStatus({ skipAuthRedirectOn401: true }).subscribe({
      next: (status) => {
        this.status.set(status);
        this.loading.set(false);
      },
      error: (err) => {
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
