import { Component, OnDestroy, signal } from '@angular/core';
import { IonContent } from '@ionic/angular/standalone';
import { ApiService, WhatsAppClientStatus } from '../core/api.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

@Component({
  selector: 'app-whatsapp-bind-page',
  imports: [IonContent, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="WhatsApp" />
      <ion-content fullscreen>
        <main class="whatsapp-page">
          <section class="head-card">
            <div>
              <p>Аккаунт менеджера</p>
              <h1>Привязка WhatsApp</h1>
            </div>
            <button type="button" (click)="loadStatus()" [disabled]="loading()">
              <span class="material-icons-sharp">refresh</span>
            </button>
          </section>

          @if (error()) {
            <button type="button" class="status-card danger" (click)="loadStatus()">
              <span class="material-icons-sharp">error</span>
              <strong>{{ error() }}</strong>
            </button>
          }

          @if (status(); as current) {
            <section class="status-card" [class.success]="stateTone() === 'success'" [class.warning]="stateTone() === 'warning'" [class.danger]="stateTone() === 'danger'">
              <span class="material-icons-sharp">{{ current.ready ? 'check_circle' : current.qrDataUrl ? 'qr_code_2' : 'sync' }}</span>
              <div>
                <strong>{{ stateLabel() }}</strong>
                <small>{{ current.clientId }}</small>
              </div>
            </section>

            @if (current.qrDataUrl && !current.ready) {
              <section class="qr-card">
                <img [src]="current.qrDataUrl" alt="QR-код WhatsApp">
                <p>Откройте WhatsApp, выберите связанные устройства и отсканируйте код.</p>
              </section>
            } @else if (current.ready) {
              <section class="empty-card success">
                <span class="material-icons-sharp">done_all</span>
                <h2>WhatsApp подключен</h2>
                <p>Последняя готовность: {{ formattedDate(current.lastReadyAt) }}</p>
              </section>
            } @else {
              <section class="empty-card">
                <span class="material-icons-sharp">hourglass_top</span>
                <h2>QR-код готовится</h2>
                <p>{{ current.message || current.lastError || 'Страница обновится сама.' }}</p>
              </section>
            }

            <section class="meta-grid">
              <article><small>QR</small><strong>{{ formattedDate(current.lastQrAt) }}</strong></article>
              <article><small>Готовность</small><strong>{{ formattedDate(current.lastReadyAt) }}</strong></article>
            </section>
          } @else if (loading()) {
            <section class="empty-card">
              <span class="material-icons-sharp">hourglass_top</span>
              <h2>Загружаю WhatsApp</h2>
            </section>
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content{--background:#f6f8fc;--overflow:hidden}.whatsapp-page{display:grid;gap:.75rem;height:100%;max-width:42rem;margin:0 auto;padding:.75rem .85rem calc(.9rem + env(safe-area-inset-bottom));overflow-y:auto;font-family:var(--otziv-font-family)}
    .head-card,.status-card,.qr-card,.empty-card,.meta-grid article{border:1px solid rgba(103,116,131,.16);border-radius:1rem;background:linear-gradient(155deg,var(--otziv-white),var(--otziv-tone-walk-surface));box-shadow:0 .9rem 1.8rem rgba(132,139,200,.12)}
    .head-card{display:grid;grid-template-columns:minmax(0,1fr)2.5rem;align-items:center;gap:.6rem;padding:.9rem}.head-card p{margin:0;color:var(--otziv-info);font-size:.68rem;font-weight:1000;text-transform:uppercase}.head-card h1{margin:.1rem 0 0;color:var(--otziv-dark);font-size:1.45rem}.head-card button{display:grid;place-items:center;width:2.5rem;height:2.5rem;border:0;border-radius:.82rem;color:var(--otziv-primary);background:rgba(108,155,207,.13)}
    .status-card{display:grid;grid-template-columns:auto minmax(0,1fr);align-items:center;gap:.65rem;min-height:4rem;padding:.85rem;color:var(--otziv-info)}.status-card>.material-icons-sharp{font-size:2rem}.status-card strong,.status-card small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.status-card strong{color:var(--otziv-dark)}.status-card.success{color:#16735f}.status-card.warning{color:#ac7a00}.status-card.danger{color:var(--otziv-danger)}
    .qr-card{display:grid;gap:.75rem;justify-items:center;padding:1rem}.qr-card img{width:min(18rem,86vw);border-radius:.8rem;background:#fff}.qr-card p,.empty-card p{margin:0;color:var(--otziv-info);font-weight:800;text-align:center}.empty-card{display:grid;place-items:center;gap:.35rem;min-height:11rem;padding:1rem;text-align:center}.empty-card.success{color:#16735f}.empty-card h2{margin:0;color:var(--otziv-dark)}.meta-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.55rem}.meta-grid article{display:grid;gap:.2rem;padding:.75rem}.meta-grid small{color:var(--otziv-info);font-size:.66rem;font-weight:1000;text-transform:uppercase}.meta-grid strong{overflow:hidden;color:var(--otziv-dark);font-size:.82rem;text-overflow:ellipsis}
  `]
})
export class WhatsAppBindPage implements OnDestroy {
  readonly status = signal<WhatsAppClientStatus | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  private refreshTimer: ReturnType<typeof setInterval> | null = null;

  constructor(private readonly api: ApiService) {
    this.loadStatus();
    this.refreshTimer = setInterval(() => {
      const current = this.status();
      if (!current?.ready && current?.configured !== false) {
        this.loadStatus(false);
      }
    }, 6000);
  }

  ngOnDestroy(): void {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  loadStatus(showLoading = true): void {
    if (showLoading) {
      this.loading.set(true);
    }
    this.error.set(null);
    this.api.getWhatsAppBindingStatus().subscribe({
      next: (status) => {
        this.status.set(status);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(this.errorMessage(error));
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

  stateTone(): 'success' | 'warning' | 'danger' | 'neutral' {
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
    return date.toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  private errorMessage(error: unknown): string {
    if (typeof error === 'object' && error && 'error' in error) {
      const body = (error as { error?: { message?: string; detail?: string; error?: string } | string }).error;
      return typeof body === 'string' ? body : body?.message || body?.detail || body?.error || 'Не удалось получить QR-код WhatsApp.';
    }
    return 'Не удалось получить QR-код WhatsApp.';
  }
}
