import { Component, HostListener, OnDestroy, computed, inject, signal } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';
import { AdminDictionariesApi, BotBrowserMetadata } from '../../../core/admin-dictionaries.api';
import { appEnvironment } from '../../../core/app-environment';
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
  private botId = 0;
  private sessionBotId = 0;
  private sessionOpen = false;
  private sessionId: string | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private heartbeatInFlight = false;
  private closing = false;
  private reopenAfterClose = false;
  private contextGeneration = 0;
  private openInFlightGeneration: number | null = null;
  private destroyed = false;
  private pageActive = true;
  private routeSubscription?: Subscription;

  readonly bot = signal<BotBrowserMetadata | null>(null);
  readonly status = signal('Запуск браузера...');
  readonly error = signal<string | null>(null);
  readonly vncUrl = signal<string | null>(null);
  readonly vncPassword = signal<string | null>(null);
  readonly frameLoaded = signal(false);
  readonly safeVncUrl = computed(() => {
    const url = this.vncUrl();
    return url ? this.sanitizer.bypassSecurityTrustResourceUrl(url) : null;
  });

  constructor() {
    if (this.route.paramMap) {
      this.routeSubscription = this.route.paramMap.subscribe((params) => this.activateBot(params.get('botId')));
    } else {
      this.activateBot(this.route.snapshot.paramMap.get('botId'));
    }
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.pageActive = false;
    this.contextGeneration += 1;
    this.routeSubscription?.unsubscribe();
    this.closeSession(true);
  }

  @HostListener('window:pagehide')
  handlePageHide(): void {
    this.pageActive = false;
    this.contextGeneration += 1;
    this.closeSession(true);
  }

  @HostListener('window:pageshow')
  handlePageShow(): void {
    if (this.destroyed || this.pageActive) {
      return;
    }
    this.pageActive = true;
    this.contextGeneration += 1;
    this.frameLoaded.set(false);
    this.loadMetadata();
    this.openSession(true);
  }

  onFrameLoad(): void {
    if (!this.vncUrl()) {
      return;
    }
    this.frameLoaded.set(true);
    this.status.set('Браузер запущен');
  }

  async copyVncPassword(): Promise<void> {
    const password = this.vncPassword();
    if (!password || !navigator.clipboard) {
      this.status.set('Не удалось скопировать пароль');
      return;
    }
    try {
      await navigator.clipboard.writeText(password);
      this.status.set('Пароль VNC скопирован');
    } catch {
      this.status.set('Не удалось скопировать пароль');
    }
  }

  closeSession(silent = false): void {
    if (!this.sessionOpen || this.closing) {
      return;
    }

    const botId = this.sessionBotId;
    const sessionId = this.sessionId;
    const generation = this.contextGeneration;
    this.closing = true;
    this.reopenAfterClose = false;
    this.sessionOpen = false;
    this.sessionBotId = 0;
    this.sessionId = null;
    this.stopHeartbeat();
    this.vncUrl.set(null);
    this.vncPassword.set(null);
    this.frameLoaded.set(false);
    if (!silent) {
      this.status.set('Отключение...');
    }

    this.dictionariesApi.closeBotBrowser(botId, sessionId).subscribe({
      next: () => {
        this.finishClose();
        if (this.isCurrentContext(generation, botId)) {
          this.status.set('Сессия закрыта');
        }
      },
      error: () => {
        this.finishClose();
        if (this.isCurrentContext(generation, botId)) {
          this.status.set('Сессия закрывается');
        }
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
    this.vncPassword.set(null);
    this.loadMetadata();
    this.openSession();
  }

  private loadMetadata(): void {
    const botId = this.botId;
    const generation = this.contextGeneration;
    this.dictionariesApi.getBotBrowserMetadata(botId).subscribe({
      next: (bot) => {
        if (this.isCurrentContext(generation, botId)) {
          this.bot.set(bot);
        }
      },
      error: () => {
        if (this.isCurrentContext(generation, botId)) {
          this.error.set('Не удалось загрузить данные аккаунта');
        }
      }
    });
  }

  private openSession(queueAfterClose = false): void {
    const botId = this.botId;
    const generation = this.contextGeneration;
    if (this.closing) {
      if (queueAfterClose && this.isCurrentContext(generation, botId)) {
        this.reopenAfterClose = true;
      }
      return;
    }
    if (this.sessionOpen || this.openInFlightGeneration === generation || !this.isCurrentContext(generation, botId)) {
      return;
    }

    this.openInFlightGeneration = generation;
    this.status.set('Открываю VNC...');

    this.dictionariesApi.openBotBrowser(botId).subscribe({
      next: (response) => {
        if (this.openInFlightGeneration === generation) {
          this.openInFlightGeneration = null;
        }
        const sessionId = response.sessionId?.trim() || null;
        if (!this.isCurrentContext(generation, botId)) {
          this.closeLateSession(botId, sessionId);
          return;
        }
        this.sessionOpen = true;
        this.sessionBotId = botId;
        this.sessionId = sessionId;
        if (this.sessionId) {
          this.startHeartbeat(response.heartbeatIntervalSeconds);
        }
        const vncUrl = prepareBotBrowserVncUrl(response.vncUrl, {
          allowedOrigins: appEnvironment.botBrowserVncAllowedOrigins
        });
        const vncPassword = this.validVncPassword(response.vncPassword);
        if (!vncUrl || !vncPassword) {
          this.status.set('Ошибка запуска');
          this.error.set('Сервис браузера вернул небезопасный адрес подключения');
          this.vncUrl.set(null);
          this.closeSession(true);
          return;
        }

        this.status.set('Подключаю VNC...');
        this.vncUrl.set(vncUrl);
        this.vncPassword.set(vncPassword);
      },
      error: (err) => {
        if (this.openInFlightGeneration === generation) {
          this.openInFlightGeneration = null;
        }
        if (this.isCurrentContext(generation, botId)) {
          this.status.set('Ошибка запуска');
          this.error.set(this.errorMessage(err, 'Не удалось открыть браузер аккаунта'));
        }
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
    const botId = this.sessionBotId;
    if (!sessionId || !this.sessionOpen || this.closing || this.heartbeatInFlight) {
      return;
    }
    this.heartbeatInFlight = true;
    this.dictionariesApi.heartbeatBotBrowser(botId, sessionId).subscribe({
      next: () => {
        if (this.isActiveSession(botId, sessionId)) {
          this.heartbeatInFlight = false;
        }
      },
      error: (error: unknown) => {
        if (!this.isActiveSession(botId, sessionId)) {
          return;
        }
        this.heartbeatInFlight = false;
        const status = error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: unknown }).status)
          : 0;
        if (status === 404 || status === 409) {
          this.stopHeartbeat();
          this.dictionariesApi.closeBotBrowser(botId, sessionId).subscribe({
            error: () => undefined
          });
          this.sessionOpen = false;
          this.sessionBotId = 0;
          this.sessionId = null;
          this.vncUrl.set(null);
          this.vncPassword.set(null);
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

  private activateBot(rawBotId: string | null): void {
    const value = Number(rawBotId);
    const botId = Number.isSafeInteger(value) && value > 0 ? value : 0;
    if (botId === this.botId && this.contextGeneration > 0) {
      return;
    }

    this.contextGeneration += 1;
    this.closeActiveSessionForRouteChange();
    this.botId = botId;
    this.bot.set(null);
    this.vncUrl.set(null);
    this.vncPassword.set(null);
    this.frameLoaded.set(false);
    this.error.set(null);
    if (!botId) {
      this.error.set('Аккаунт не найден');
      this.status.set('Ошибка');
      return;
    }
    this.loadMetadata();
    this.openSession(true);
  }

  private finishClose(): void {
    this.closing = false;
    const shouldReopen = this.reopenAfterClose;
    this.reopenAfterClose = false;
    if (shouldReopen && !this.destroyed && this.pageActive && this.botId > 0 && !this.sessionOpen) {
      this.openSession();
    }
  }

  private closeActiveSessionForRouteChange(): void {
    if (!this.sessionOpen) {
      this.stopHeartbeat();
      return;
    }
    const botId = this.sessionBotId;
    const sessionId = this.sessionId;
    this.sessionOpen = false;
    this.sessionBotId = 0;
    this.sessionId = null;
    this.stopHeartbeat();
    this.dictionariesApi.closeBotBrowser(botId, sessionId).subscribe({ error: () => undefined });
  }

  private closeLateSession(botId: number, sessionId: string | null): void {
    this.dictionariesApi.closeBotBrowser(botId, sessionId).subscribe({ error: () => undefined });
  }

  private isCurrentContext(generation: number, botId: number): boolean {
    return !this.destroyed
      && this.pageActive
      && generation === this.contextGeneration
      && botId === this.botId;
  }

  private isActiveSession(botId: number, sessionId: string): boolean {
    return this.sessionOpen && botId === this.sessionBotId && sessionId === this.sessionId;
  }

  private validVncPassword(rawValue: unknown): string | null {
    if (typeof rawValue !== 'string' || !/^[A-Za-z0-9_-]{8,128}$/.test(rawValue)) {
      return null;
    }
    return rawValue;
  }
}
