import { Component, HostListener, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { IonContent } from '@ionic/angular/standalone';
import { Subscription } from 'rxjs';
import { ApiService, BotBrowserMetadata } from '../core/api.service';
import { prepareBotBrowserVncUrl } from '../core/bot-browser-vnc-url';
import { mobileEnvironment } from '../core/mobile-environment';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

@Component({
  selector: 'app-bot-browser-page',
  imports: [IonContent, MobileHeaderComponent, RouterLink],
  template: `
    <div class="ion-page bot-browser-shell">
      <app-mobile-header title="Браузер бота" />

      <ion-content fullscreen [scrollY]="false">
        <main class="bot-browser-page">
          <section class="bot-browser-top">
            <a routerLink="/tabs/home/dictionaries" aria-label="К справочникам">
              <span class="material-icons-sharp">arrow_back</span>
            </a>
            <div>
              <p>Аккаунт #{{ bot()?.botId || botId() || '' }}</p>
              <h1>{{ bot()?.fio || 'Браузер аккаунта' }}</h1>
              <small>{{ bot()?.login || status() }}</small>
            </div>
            <button type="button" (click)="retry()" [disabled]="status() === 'Открываю VNC...'">
              <span class="material-icons-sharp">refresh</span>
            </button>
            <button class="danger" type="button" (click)="closeSession()" [disabled]="!sessionOpen()">
              <span class="material-icons-sharp">logout</span>
            </button>
          </section>

          @if (error()) {
            <section class="browser-message error">
              <span class="material-icons-sharp">error</span>
              <strong>Браузер аккаунта не открылся</strong>
              <p>{{ error() }}</p>
              <button type="button" (click)="retry()">Повторить</button>
            </section>
          }

          <section class="browser-frame-wrap">
            @if (!frameLoaded() && !error()) {
              <div class="browser-loading">{{ status() }}</div>
            }

            @if (safeVncUrl(); as url) {
              <iframe
                [src]="url"
                title="Браузер аккаунта"
                scrolling="yes"
                sandbox="allow-scripts allow-forms allow-pointer-lock"
                allow="clipboard-read; clipboard-write"
                referrerpolicy="no-referrer"
                (load)="onFrameLoad()"
              ></iframe>
            }
          </section>
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content { --overflow: hidden; }
    .bot-browser-page{display:grid;grid-template-rows:auto minmax(0,1fr);gap:.55rem;height:100%;padding:.65rem;background:#0b1220;color:#e5e7eb;font-family:var(--otziv-font-family)}
    .bot-browser-top{display:grid;grid-template-columns:2.35rem minmax(0,1fr)2.35rem 2.35rem;align-items:center;gap:.5rem;border:1px solid rgba(148,163,184,.18);border-radius:1rem;padding:.52rem;background:#111827}
    .bot-browser-top a,.bot-browser-top button{display:grid;place-items:center;width:2.35rem;height:2.35rem;border:0;border-radius:.78rem;color:#e5e7eb;background:rgba(255,255,255,.08);text-decoration:none}
    .bot-browser-top button.danger{background:rgba(239,68,68,.18);color:#fecdd3}.bot-browser-top div{min-width:0}.bot-browser-top p,.bot-browser-top h1,.bot-browser-top small{overflow:hidden;margin:0;text-overflow:ellipsis;white-space:nowrap}.bot-browser-top p{color:#9ca3af;font-size:.68rem;font-weight:900;text-transform:uppercase}.bot-browser-top h1{font-size:.95rem}.bot-browser-top small{color:#cbd5e1;font-size:.72rem;font-weight:800}
    .browser-frame-wrap{position:relative;min-height:0;overflow:auto;border:1px solid rgba(148,163,184,.18);border-radius:1rem;background:#111}.browser-frame-wrap iframe{display:block;width:100%;height:100%;min-height:100%;border:0}.browser-loading,.browser-message{position:absolute;z-index:2;border-radius:.8rem;padding:.65rem .85rem;color:#e5e7eb;background:rgba(17,24,39,.88);font-size:.82rem;font-weight:900}.browser-loading{top:.8rem;left:.8rem}.browser-message{position:static;display:grid;gap:.35rem;place-items:center;text-align:center}.browser-message.error{color:#fecdd3}.browser-message p{margin:0;color:#cbd5e1}.browser-message button{min-height:2.2rem;border:0;border-radius:999px;padding:0 1rem;color:#fff;background:var(--otziv-primary);font-weight:900}
  `]
})
export class BotBrowserPage implements OnInit, OnDestroy {
  private closing = false;
  private reopenAfterClose = false;
  private sessionBotId = 0;
  private sessionId: string | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private heartbeatInFlight = false;
  private contextGeneration = 0;
  private openInFlightGeneration: number | null = null;
  private routeSubscription?: Subscription;
  private destroyed = false;
  private viewActive = true;

  readonly botId = signal(0);
  readonly bot = signal<BotBrowserMetadata | null>(null);
  readonly status = signal('Запуск браузера...');
  readonly error = signal<string | null>(null);
  readonly vncUrl = signal<string | null>(null);
  readonly frameLoaded = signal(false);
  readonly sessionOpen = signal(false);
  readonly safeVncUrl = computed(() => {
    const url = this.vncUrl();
    return url ? this.sanitizer.bypassSecurityTrustResourceUrl(url) : null;
  });

  constructor(
    private readonly route: ActivatedRoute,
    private readonly sanitizer: DomSanitizer,
    private readonly api: ApiService
  ) {}

  ngOnInit(): void {
    if (this.route.paramMap) {
      this.routeSubscription = this.route.paramMap.subscribe((params) => this.activateBot(params.get('botId')));
    } else {
      this.activateBot(this.route.snapshot.paramMap.get('botId'));
    }
  }

  ionViewWillEnter(): void {
    if (this.destroyed || this.viewActive) {
      return;
    }
    this.viewActive = true;
    this.contextGeneration += 1;
    this.frameLoaded.set(false);
    if (this.botId() > 0) {
      this.loadMetadata();
      this.openSession(true);
    }
  }

  ionViewWillLeave(): void {
    if (!this.viewActive) {
      return;
    }
    this.viewActive = false;
    this.contextGeneration += 1;
    this.closeSession(true);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.viewActive = false;
    this.contextGeneration += 1;
    this.routeSubscription?.unsubscribe();
    this.closeSession(true);
  }

  @HostListener('window:pagehide')
  handlePageHide(): void {
    this.viewActive = false;
    this.contextGeneration += 1;
    this.closeSession(true);
  }

  onFrameLoad(): void {
    if (!this.vncUrl()) {
      return;
    }
    this.frameLoaded.set(true);
    this.status.set('Браузер запущен');
  }

  closeSession(silent = false): void {
    if (!this.sessionOpen() || this.closing) {
      return;
    }

    const botId = this.sessionBotId;
    const sessionId = this.sessionId;
    const generation = this.contextGeneration;
    this.closing = true;
    this.reopenAfterClose = false;
    this.sessionOpen.set(false);
    this.sessionBotId = 0;
    this.sessionId = null;
    this.stopHeartbeat();
    this.vncUrl.set(null);
    this.frameLoaded.set(false);
    if (!silent) {
      this.status.set('Отключение...');
    }

    this.api.closeAdminBotBrowser(botId, sessionId).subscribe({
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
    if (this.sessionOpen()) {
      this.loadMetadata();
      return;
    }
    this.frameLoaded.set(false);
    this.vncUrl.set(null);
    this.openSession();
    this.loadMetadata();
  }

  private loadMetadata(): void {
    const botId = this.botId();
    const generation = this.contextGeneration;
    this.api.getBotBrowserMetadata(botId).subscribe({
      next: (bot) => {
        if (this.isCurrentContext(generation, botId)) {
          this.bot.set(bot);
        }
      },
      error: () => {
        if (this.isCurrentContext(generation, botId)) {
          this.error.set('Не удалось загрузить данные аккаунта.');
        }
      }
    });
  }

  private openSession(queueAfterClose = false): void {
    const botId = this.botId();
    const generation = this.contextGeneration;
    if (this.closing) {
      if (queueAfterClose && this.isCurrentContext(generation, botId)) {
        this.reopenAfterClose = true;
      }
      return;
    }
    if (this.sessionOpen() || this.openInFlightGeneration === generation || !this.isCurrentContext(generation, botId)) {
      return;
    }

    this.openInFlightGeneration = generation;
    this.status.set('Открываю VNC...');
    this.api.openAdminBotBrowser(botId).subscribe({
      next: (response) => {
        if (this.openInFlightGeneration === generation) {
          this.openInFlightGeneration = null;
        }
        const sessionId = response.sessionId?.trim() || null;
        if (!this.isCurrentContext(generation, botId)) {
          this.closeLateSession(botId, sessionId);
          return;
        }
        this.sessionOpen.set(true);
        this.sessionBotId = botId;
        this.sessionId = sessionId;
        if (this.sessionId) {
          this.startHeartbeat(response.heartbeatIntervalSeconds);
        }
        const vncUrl = prepareBotBrowserVncUrl(response.vncUrl, {
          allowedOrigins: mobileEnvironment.botBrowserVncAllowedOrigins
        });
        if (!vncUrl) {
          this.status.set('Ошибка запуска');
          this.error.set('Сервис браузера вернул небезопасный адрес подключения.');
          this.vncUrl.set(null);
          this.closeSession(true);
          return;
        }

        this.status.set('Подключаю VNC...');
        this.vncUrl.set(vncUrl);
      },
      error: (error) => {
        if (this.openInFlightGeneration === generation) {
          this.openInFlightGeneration = null;
        }
        if (this.isCurrentContext(generation, botId)) {
          this.status.set('Ошибка запуска');
          this.error.set(this.errorMessage(error, 'Не удалось открыть браузер аккаунта.'));
        }
      }
    });
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const body = (error as { error?: { message?: string; error?: string } | string }).error;
      if (typeof body === 'string') {
        return body;
      }
      return body?.message || body?.error || fallback;
    }
    return error instanceof Error ? error.message : fallback;
  }

  private startHeartbeat(intervalSeconds?: number): void {
    this.stopHeartbeat();
    const delay = Math.max(5, Number(intervalSeconds) || 20) * 1000;
    this.heartbeatTimer = setInterval(() => this.sendHeartbeat(), delay);
  }

  private sendHeartbeat(): void {
    const sessionId = this.sessionId;
    const botId = this.sessionBotId;
    if (!sessionId || !this.sessionOpen() || this.closing || this.heartbeatInFlight) {
      return;
    }
    this.heartbeatInFlight = true;
    this.api.heartbeatAdminBotBrowser(botId, sessionId).subscribe({
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
          this.api.closeAdminBotBrowser(botId, sessionId).subscribe({
            error: () => undefined
          });
          this.sessionOpen.set(false);
          this.sessionBotId = 0;
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

  private activateBot(rawBotId: string | null): void {
    const value = Number(rawBotId);
    const botId = Number.isSafeInteger(value) && value > 0 ? value : 0;
    if (botId === this.botId() && this.contextGeneration > 0) {
      return;
    }

    this.contextGeneration += 1;
    this.closeActiveSessionForRouteChange();
    this.botId.set(botId);
    this.bot.set(null);
    this.vncUrl.set(null);
    this.frameLoaded.set(false);
    this.error.set(null);
    if (!botId) {
      this.status.set('Ошибка');
      this.error.set('Аккаунт не найден.');
      return;
    }
    this.loadMetadata();
    this.openSession(true);
  }

  private finishClose(): void {
    this.closing = false;
    const shouldReopen = this.reopenAfterClose;
    this.reopenAfterClose = false;
    if (shouldReopen && !this.destroyed && this.viewActive && this.botId() > 0 && !this.sessionOpen()) {
      this.openSession();
    }
  }

  private closeActiveSessionForRouteChange(): void {
    if (!this.sessionOpen()) {
      this.stopHeartbeat();
      return;
    }
    const botId = this.sessionBotId;
    const sessionId = this.sessionId;
    this.sessionOpen.set(false);
    this.sessionBotId = 0;
    this.sessionId = null;
    this.stopHeartbeat();
    this.api.closeAdminBotBrowser(botId, sessionId).subscribe({ error: () => undefined });
  }

  private closeLateSession(botId: number, sessionId: string | null): void {
    this.api.closeAdminBotBrowser(botId, sessionId).subscribe({ error: () => undefined });
  }

  private isCurrentContext(generation: number, botId: number): boolean {
    return !this.destroyed
      && this.viewActive
      && generation === this.contextGeneration
      && botId === this.botId();
  }

  private isActiveSession(botId: number, sessionId: string): boolean {
    return this.sessionOpen() && botId === this.sessionBotId && sessionId === this.sessionId;
  }
}
