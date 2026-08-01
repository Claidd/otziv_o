import { Component, HostListener, OnDestroy, computed, inject, signal } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { ActivatedRoute } from '@angular/router';
import { AdminDictionariesApi, BotBrowserMetadata } from '../../../core/admin-dictionaries.api';
import { prepareBotBrowserVncUrl } from '../../../core/bot-browser-vnc-url';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';

@Component({
  selector: 'app-bot-browser',
  imports: [LoadErrorCardComponent],
  templateUrl: './bot-browser.component.html',
  styleUrl: './bot-browser.component.scss'
})
export class BotBrowserComponent implements OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly dictionariesApi = inject(AdminDictionariesApi);
  private readonly botId = Number(this.route.snapshot.paramMap.get('botId'));
  private sessionOpen = false;
  private sessionId: string | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private heartbeatInFlight = false;
  private closing = false;

  readonly bot = signal<BotBrowserMetadata | null>(null);
  readonly status = signal('Запуск браузера...');
  readonly error = signal<string | null>(null);
  readonly vncUrl = signal<string | null>(null);
  readonly frameLoaded = signal(false);
  readonly safeVncUrl = computed(() => {
    const url = this.vncUrl();
    return url ? this.sanitizer.bypassSecurityTrustResourceUrl(url) : null;
  });

  constructor() {
    if (!Number.isFinite(this.botId) || this.botId <= 0) {
      this.error.set('Аккаунт не найден');
      this.status.set('Ошибка');
      return;
    }

    this.loadMetadata();
    this.openSession();
  }

  ngOnDestroy(): void {
    this.closeSession(true);
  }

  @HostListener('window:pagehide')
  handlePageHide(): void {
    this.closeSession(true);
  }

  onFrameLoad(): void {
    this.frameLoaded.set(true);
    this.status.set('Браузер запущен');
  }

  closeSession(silent = false): void {
    if (!this.sessionOpen || this.closing) {
      return;
    }

    this.closing = true;
    this.stopHeartbeat();
    if (!silent) {
      this.status.set('Отключение...');
    }

    this.dictionariesApi.closeBotBrowser(this.botId, this.sessionId).subscribe({
      next: () => {
        this.sessionOpen = false;
        this.sessionId = null;
        this.closing = false;
        this.status.set('Сессия закрыта');
      },
      error: () => {
        this.closing = false;
        this.status.set('Сессия закрывается');
      }
    });
  }

  retry(): void {
    if (this.closing) {
      return;
    }

    this.error.set(null);
    if (this.sessionOpen) {
      this.loadMetadata();
      return;
    }
    this.frameLoaded.set(false);
    this.vncUrl.set(null);
    this.loadMetadata();
    this.openSession();
  }

  private loadMetadata(): void {
    this.dictionariesApi.getBotBrowserMetadata(this.botId).subscribe({
      next: (bot) => this.bot.set(bot),
      error: () => this.error.set('Не удалось загрузить данные аккаунта')
    });
  }

  private openSession(): void {
    if (this.sessionOpen || this.closing) {
      return;
    }

    this.status.set('Открываю VNC...');

    this.dictionariesApi.openBotBrowser(this.botId).subscribe({
      next: (response) => {
        this.sessionOpen = true;
        this.sessionId = response.sessionId?.trim() || null;
        if (this.sessionId) {
          this.startHeartbeat(response.heartbeatIntervalSeconds);
        }
        const vncUrl = prepareBotBrowserVncUrl(response.vncUrl);
        if (!vncUrl) {
          this.status.set('Ошибка запуска');
          this.error.set('Сервис браузера вернул небезопасный адрес подключения');
          this.vncUrl.set(null);
          this.closeSession(true);
          return;
        }

        this.status.set('Подключаю VNC...');
        this.vncUrl.set(vncUrl);
      },
      error: (err) => {
        this.status.set('Ошибка запуска');
        this.error.set(this.errorMessage(err, 'Не удалось открыть браузер аккаунта'));
      }
    });
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }

  private startHeartbeat(intervalSeconds?: number): void {
    this.stopHeartbeat();
    const delay = Math.max(5, Number(intervalSeconds) || 20) * 1000;
    this.heartbeatTimer = setInterval(() => this.sendHeartbeat(), delay);
  }

  private sendHeartbeat(): void {
    const sessionId = this.sessionId;
    if (!sessionId || !this.sessionOpen || this.closing || this.heartbeatInFlight) {
      return;
    }
    this.heartbeatInFlight = true;
    this.dictionariesApi.heartbeatBotBrowser(this.botId, sessionId).subscribe({
      next: () => {
        this.heartbeatInFlight = false;
      },
      error: (error: unknown) => {
        this.heartbeatInFlight = false;
        const status = error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: unknown }).status)
          : 0;
        if (status === 404 || status === 409) {
          this.stopHeartbeat();
          this.dictionariesApi.closeBotBrowser(this.botId, sessionId).subscribe({
            error: () => undefined
          });
          this.sessionOpen = false;
          this.sessionId = null;
          this.vncUrl.set(null);
          this.status.set('Сессия завершена');
          this.error.set('Доступ к браузерной сессии завершен. Откройте ее заново.');
        }
      }
    });
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    this.heartbeatInFlight = false;
  }
}
