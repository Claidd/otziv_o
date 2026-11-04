import { Component, signal } from '@angular/core';
import { CabinetApi, StaffTelegramLink } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';

@Component({
  selector: 'app-telegram-bind',
  imports: [AdminLayoutComponent],
  templateUrl: './telegram-bind.component.html',
  styleUrl: './whatsapp-bind.component.scss'
})
export class TelegramBindComponent {
  readonly link = signal<StaffTelegramLink | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  constructor(private readonly cabinetApi: CabinetApi) {}

  issueLink(): void {
    this.loading.set(true);
    this.error.set(null);
    this.cabinetApi.issueTelegramLink().subscribe({
      next: (link) => {
        this.link.set(link);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(apiErrorDetail(err, 'Не удалось получить ссылку для привязки Telegram.'));
        this.loading.set(false);
      }
    });
  }

  openTelegram(): void {
    const url = this.link()?.url;
    if (url && url.startsWith('https://t.me/')) {
      window.open(url, '_blank', 'noopener,noreferrer');
    }
  }

  expiresAtLabel(): string {
    const value = this.link()?.expiresAt;
    if (!value) {
      return '';
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime())
      ? ''
      : date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  }
}
