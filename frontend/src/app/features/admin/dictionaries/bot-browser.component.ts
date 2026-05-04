import { Component, HostListener, OnDestroy, computed, inject, signal } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { ActivatedRoute } from '@angular/router';
import { AdminBot, AdminDictionariesApi } from '../../../core/admin-dictionaries.api';

@Component({
  selector: 'app-bot-browser',
  templateUrl: './bot-browser.component.html',
  styleUrl: './bot-browser.component.scss'
})
export class BotBrowserComponent implements OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly dictionariesApi = inject(AdminDictionariesApi);
  private readonly botId = Number(this.route.snapshot.paramMap.get('botId'));
  private sessionOpen = false;
  private closing = false;

  readonly bot = signal<AdminBot | null>(null);
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

    this.loadBot();
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
    if (!silent) {
      this.status.set('Отключение...');
    }

    this.dictionariesApi.closeBotBrowser(this.botId).subscribe({
      next: () => {
        this.sessionOpen = false;
        this.status.set('Сессия закрыта');
      },
      error: () => {
        this.status.set('Сессия закрывается');
      }
    });
  }

  private loadBot(): void {
    this.dictionariesApi.getBot(this.botId).subscribe({
      next: (bot) => this.bot.set(bot),
      error: () => this.error.set('Не удалось загрузить данные аккаунта')
    });
  }

  private openSession(): void {
    this.status.set('Открываю VNC...');

    this.dictionariesApi.openBotBrowser(this.botId).subscribe({
      next: (response) => {
        this.sessionOpen = true;
        this.status.set('Подключаю VNC...');
        this.vncUrl.set(this.withVncParams(response.vncUrl));
      },
      error: (err) => {
        this.status.set('Ошибка запуска');
        this.error.set(this.errorMessage(err, 'Не удалось открыть браузер аккаунта'));
      }
    });
  }

  private withVncParams(rawUrl: string): string {
    const url = new URL(rawUrl, window.location.origin);
    url.searchParams.set('autoconnect', '1');
    url.searchParams.set('reconnect', '1');
    url.searchParams.set('resize', 'none');
    url.searchParams.set('clip', 'true');
    return url.toString();
  }

  private errorMessage(err: unknown, fallback: string): string {
    if (typeof err === 'object' && err !== null) {
      const response = err as { error?: unknown; message?: unknown };
      if (typeof response.error === 'object' && response.error !== null) {
        const body = response.error as { detail?: unknown; message?: unknown };
        if (typeof body.detail === 'string') {
          return body.detail;
        }

        if (typeof body.message === 'string') {
          return body.message;
        }
      }

      if (typeof response.error === 'string') {
        return response.error;
      }

      if (typeof response.message === 'string') {
        return response.message;
      }
    }

    return fallback;
  }
}
