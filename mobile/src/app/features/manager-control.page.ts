import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  IonContent,
  IonRefresher,
  IonRefresherContent,
  RefresherCustomEvent
} from '@ionic/angular/standalone';
import {
  ApiService,
  ManagerControlActionPayload,
  ManagerControlConcreteItem,
  ManagerControlItemDetail,
  ManagerControlManagerDetail,
  ManagerControlSummary
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

@Component({
  selector: 'app-manager-control-mobile',
  imports: [DatePipe, FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Контроль" />

      <ion-content fullscreen [scrollY]="false">
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <main class="control-page">
          <section class="control-top">
            <div>
              <p class="eyebrow">{{ isOwnerAdmin() ? 'ADMIN / OWNER' : 'MANAGER' }}</p>
              <h1>{{ isOwnerAdmin() ? 'Контроль менеджеров' : 'Мои замечания' }}</h1>
              <small>{{ summary()?.date || todayLabel() }} · {{ summary()?.attentionTotal ?? 0 }} к действию</small>
            </div>
            <button type="button" class="refresh-button" (click)="sync()" [disabled]="loading()">
              <span class="material-icons-sharp">sync</span>
              Обновить
            </button>
          </section>

          @if (notice()) {
            <button class="inline-alert success" type="button" (click)="notice.set(null)">
              <span class="material-icons-sharp">task_alt</span>
              <span>{{ notice() }}</span>
            </button>
          }

          @if (error()) {
            <button class="inline-alert" type="button" (click)="load(true)">
              <span class="material-icons-sharp">error</span>
              <span>{{ error() }}</span>
            </button>
          }

          @if (isOwnerAdmin()) {
            <section class="manager-strip" aria-label="Менеджеры">
              @for (manager of managers(); track manager.managerId) {
                <button
                  type="button"
                  [class.active]="selectedManagerId() === manager.managerId"
                  [class.red]="manager.status === 'RED'"
                  [class.yellow]="manager.status === 'YELLOW'"
                  (click)="selectManager(manager.managerId)"
                >
                  <strong>{{ shortName(manager.name || manager.username) }}</strong>
                  <small>{{ manager.totalAttentionCount }} к действию</small>
                </button>
              }
            </section>
          }

          @if (detail(); as current) {
            <section class="detail-head">
              <div>
                <p class="eyebrow">КОНТРОЛЬ ДНЯ</p>
                <h2>{{ current.name || current.username }}</h2>
                <small>{{ current.openItemCount }} открыто · {{ current.handledItemCount }} обработано</small>
              </div>
              <div class="detail-actions">
                <button type="button" (click)="acceptControl()" [disabled]="!current.dailyControlId || mutating()">
                  <span class="material-icons-sharp">verified</span>
                  Принять
                </button>
                <button type="button" (click)="markStage('MORNING_DONE')" [disabled]="!current.dailyControlId || mutating()">
                  <span class="material-icons-sharp">flag</span>
                  Этап
                </button>
                <button type="button" (click)="closeDay()" [disabled]="!current.dailyControlId || !current.canCloseDay || mutating()">
                  <span class="material-icons-sharp">lock</span>
                  Закрыть
                </button>
              </div>
            </section>

            @if (!current.dailyControlId) {
              <p class="control-note warning-note">Контроль еще не синхронизирован. Нажмите “Обновить”, чтобы стали доступны этапы дня.</p>
            } @else {
              <p class="control-note" [class.ready-note]="current.canCloseDay && !current.closedAt" [class.closed-note]="!!current.closedAt">
                {{ controlAutoCloseStatus(current) }}
              </p>
            }

            @if (current.workerExplanationStats.length) {
              <section class="worker-stats">
                <h3>Ответы специалистов</h3>
                @for (stat of current.workerExplanationStats; track stat.workerUserId || stat.workerName) {
                  <article>
                    <strong>{{ stat.workerName }}</strong>
                    <span>{{ stat.requestCount }} запросов · {{ stat.unansweredCount }} без ответа · {{ stat.overdueCount }} просрочено</span>
                  </article>
                }
              </section>
            }

            <section class="control-list" aria-label="Замечания">
              @for (item of visibleItems(current); track item.itemId) {
                <section class="control-section">
                  <header>
                    <div>
                      <h3>{{ item.label }}</h3>
                      <small>{{ item.reasonLabel }}</small>
                    </div>
                    <span class="count-pill">{{ item.count }}</span>
                  </header>

                  @for (card of item.examples; track trackCard($index, card)) {
                    <article class="control-card" [class.resolved]="isHandled(card)" [class.danger]="card.workerNotificationFailureReason">
                      <header>
                        <strong>{{ card.title || item.label }}</strong>
                        <span>{{ card.status || statusLabel(card) }}</span>
                      </header>

                      <div class="card-grid">
                        <button type="button" (click)="openLink(card.targetUrl)" [disabled]="!card.targetUrl">
                          <span>Заказ</span>
                          <strong>Перейти</strong>
                        </button>
                        <button type="button" (click)="openLink(card.chatUrl)" [disabled]="!card.chatUrl">
                          <span>Чат</span>
                          <strong>{{ chatLabel(card) }}</strong>
                        </button>
                        <div>
                          <span>Специалист</span>
                          <strong>{{ card.specialistName || '-' }}</strong>
                        </div>
                      </div>

                      @if (card.reason) {
                        <p class="reason-text">{{ card.reason }}</p>
                      }

                      @if (card.workerNotificationFailureReason) {
                        <p class="delivery-error">
                          <span class="material-icons-sharp">error</span>
                          {{ card.workerNotificationFailureReason }}
                        </p>
                      }

                      @if (card.workerExplanation) {
                        <p class="worker-answer">
                          <span>Ответ специалиста</span>
                          {{ card.workerExplanation }}
                          @if (card.workerExplanationAt) {
                            <small>{{ card.workerExplanationAt | date:'dd.MM HH:mm' }}</small>
                          }
                        </p>
                      }

                      @if (card.comment) {
                        <p class="comment-text">{{ card.comment }}</p>
                      }

                      <div class="action-grid">
                        @if (showRepair(card)) {
                          <button type="button" class="muted" (click)="repair(card)" [disabled]="!card.controlEntityId || mutatingId() === card.controlEntityId">
                            <span class="material-icons-sharp">build</span>
                            Починить
                          </button>
                        }
                        <button type="button" class="warning" (click)="requestWorker(card)" [disabled]="!card.controlEntityId || mutatingId() === card.controlEntityId">
                          <span class="material-icons-sharp">contact_support</span>
                          {{ card.workerNotificationSentAt ? 'Напомнить' : 'Запросить' }}
                        </button>
                        <button type="button" class="success" (click)="markResolved(card)" [disabled]="!card.controlEntityId || mutatingId() === card.controlEntityId">
                          <span class="material-icons-sharp">task_alt</span>
                          Проверено
                        </button>
                        <button type="button" class="muted" (click)="defer(card)" [disabled]="!card.controlEntityId || mutatingId() === card.controlEntityId">
                          <span class="material-icons-sharp">schedule</span>
                          Отложить
                        </button>
                      </div>

                      @if (isUnanswered(card)) {
                        <div class="reply-box">
                          <textarea
                            rows="3"
                            placeholder="Ответ клиенту"
                            [ngModel]="replyText(card)"
                            (ngModelChange)="setReplyText(card, $event)"
                          ></textarea>
                          <button type="button" class="send-button" (click)="replyClient(card)" [disabled]="!canReply(card)">
                            <span class="material-icons-sharp">send</span>
                            Отправить
                          </button>
                          <button type="button" class="muted wide" (click)="markNoAnswerNeeded(card)" [disabled]="!card.controlEntityId || mutatingId() === card.controlEntityId">
                            <span class="material-icons-sharp">visibility_off</span>
                            Не требует ответа
                          </button>
                        </div>
                      } @else if (card.contactText) {
                        <button type="button" class="send-message" (click)="sendClientMessage(card)" [disabled]="!card.controlEntityId || mutatingId() === card.controlEntityId">
                          <span class="material-icons-sharp">send</span>
                          Отправить клиенту
                        </button>
                      }

                      <textarea
                        class="card-comment"
                        rows="2"
                        placeholder="Комментарий по карточке"
                        [ngModel]="commentText(card)"
                        (ngModelChange)="setCommentText(card, $event)"
                      ></textarea>
                    </article>
                  }
                </section>
              } @empty {
                @if (!loading()) {
                  <section class="mobile-empty-state">
                    <span class="material-icons-sharp">verified_user</span>
                    <p>Открытых замечаний нет</p>
                  </section>
                }
              }
            </section>
          } @else if (!loading()) {
            <section class="mobile-empty-state">
              <span class="material-icons-sharp">fact_check</span>
              <p>Контроль пока пуст</p>
            </section>
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content { --overflow: hidden; }
    .control-page { display:flex; height:100%; max-width:48rem; min-height:0; margin:0 auto; overflow:auto; flex-direction:column; gap:.62rem; padding:var(--otziv-page-padding-y,.58rem) var(--otziv-page-padding-x,.62rem) calc(var(--otziv-page-padding-bottom,.62rem) + env(safe-area-inset-bottom)); -webkit-overflow-scrolling:touch; }
    .control-top,.detail-head,.control-section,.control-card,.worker-stats { border:1px solid rgba(103,116,131,.16); border-radius:.92rem; background:linear-gradient(155deg,var(--otziv-white) 0%,var(--otziv-muted-surface,#f8fafc) 100%); box-shadow:0 .8rem 1.45rem rgba(132,139,200,.1); }
    .control-top,.detail-head { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:.58rem; padding:.72rem; }
    .eyebrow { margin:0 0 .12rem; color:var(--otziv-info); font-size:.58rem; font-weight:1000; letter-spacing:0; }
    h1,h2,h3 { margin:0; color:var(--otziv-dark); font-family:var(--otziv-card-title-font); line-height:1.05; letter-spacing:0; }
    h1 { font-size:1.05rem; } h2 { font-size:.98rem; } h3 { font-size:.82rem; }
    small { color:var(--otziv-info); font-size:.62rem; font-weight:800; line-height:1.25; }
    button { font:inherit; letter-spacing:0; }
    .refresh-button,.detail-actions button,.manager-strip button,.action-grid button,.card-grid button,.card-grid div,.send-button,.send-message { min-height:2.15rem; border:1px solid rgba(108,155,207,.22); border-radius:999px; padding:0 .72rem; color:var(--otziv-primary); background:var(--otziv-white); font-size:.66rem; font-weight:1000; }
    .refresh-button,.detail-actions button,.action-grid button,.send-button,.send-message { display:inline-flex; align-items:center; justify-content:center; gap:.32rem; }
    .refresh-button .material-icons-sharp,.detail-actions .material-icons-sharp,.action-grid .material-icons-sharp,.send-button .material-icons-sharp,.send-message .material-icons-sharp { font-size:1rem; }
    button:disabled { opacity:.48; }
    .inline-alert { display:grid; grid-template-columns:auto minmax(0,1fr); align-items:center; gap:.45rem; border:1px solid rgba(237,45,91,.28); border-radius:.85rem; padding:.62rem; color:var(--otziv-danger); background:#fff1f5; text-align:left; font-size:.68rem; font-weight:900; }
    .inline-alert.success { border-color:rgba(47,159,149,.25); color:#2f8b76; background:#eefcf7; }
    .manager-strip { display:grid; grid-auto-columns:minmax(8.2rem,1fr); grid-auto-flow:column; gap:.45rem; overflow-x:auto; padding-bottom:.14rem; }
    .manager-strip button { display:grid; align-content:center; justify-items:start; border-radius:.82rem; color:var(--otziv-dark); }
    .manager-strip button.active { border-color:var(--otziv-primary); background:var(--otziv-light); }
    .manager-strip button.red { border-color:rgba(237,45,91,.32); }
    .manager-strip button.yellow { border-color:rgba(231,180,52,.38); }
    .detail-actions { display:grid; gap:.38rem; }
    .control-note { margin:0; border:1px solid rgba(108,155,207,.22); border-radius:.85rem; padding:.62rem; color:var(--otziv-info); background:#f8fbff; font-size:.66rem; font-weight:900; }
    .warning-note { border-color:rgba(231,180,52,.3); color:#996c10; background:#fff8e7; }
    .ready-note,.closed-note { border-color:rgba(47,159,149,.25); color:#2f8b76; background:#eefcf7; }
    .worker-stats { display:grid; gap:.42rem; padding:.65rem; }
    .worker-stats article { display:grid; gap:.1rem; border-top:1px solid rgba(103,116,131,.11); padding-top:.4rem; }
    .worker-stats span { color:var(--otziv-info); font-size:.62rem; font-weight:800; }
    .control-list { display:grid; gap:.65rem; }
    .control-section { display:grid; gap:.52rem; padding:.65rem; }
    .control-section>header,.control-card>header { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:start; gap:.48rem; }
    .count-pill,.control-card>header span { display:inline-flex; min-height:1.55rem; align-items:center; border:1px solid rgba(47,159,149,.22); border-radius:999px; padding:0 .58rem; color:#2f8b76; background:#f1fff9; font-size:.58rem; font-weight:1000; }
    .control-card { display:grid; gap:.5rem; padding:.62rem; }
    .control-card.danger { border-color:rgba(237,45,91,.38); }
    .control-card.resolved { opacity:.72; }
    .card-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:.38rem; }
    .card-grid button,.card-grid div { display:grid; align-content:center; justify-items:center; border-radius:.72rem; padding:.34rem; color:var(--otziv-dark); background:#fff; }
    .card-grid span { color:var(--otziv-info); font-size:.55rem; font-weight:800; }
    .card-grid strong { max-width:100%; overflow:hidden; font-size:.66rem; text-overflow:ellipsis; white-space:nowrap; }
    .reason-text,.comment-text,.worker-answer,.delivery-error { margin:0; border:1px solid rgba(103,116,131,.13); border-radius:.72rem; padding:.55rem; color:var(--otziv-dark); background:#fff; font-size:.66rem; font-weight:800; line-height:1.36; }
    .delivery-error { display:grid; grid-template-columns:auto minmax(0,1fr); align-items:start; gap:.35rem; border-color:rgba(237,45,91,.32); color:var(--otziv-danger); background:#fff1f5; }
    .worker-answer { border-color:rgba(47,159,149,.22); background:#f1fff9; }
    .worker-answer span,.worker-answer small { display:block; margin-bottom:.12rem; color:#2f8b76; font-size:.56rem; font-weight:1000; }
    .action-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:.38rem; }
    .action-grid .success,.send-button,.send-message { border-color:rgba(47,159,149,.28); color:#2f8b76; background:#effcf7; }
    .action-grid .warning { border-color:rgba(231,180,52,.3); color:#9a7118; background:#fff8e7; }
    .action-grid .muted { color:var(--otziv-info); }
    .reply-box { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:.42rem; align-items:stretch; }
    textarea { width:100%; min-width:0; border:1px solid rgba(103,116,131,.18); border-radius:.78rem; padding:.58rem; color:var(--otziv-dark); background:#fff; font:800 .68rem/1.35 var(--otziv-font-family); resize:vertical; }
    .reply-box .wide { grid-column:1 / -1; }
    .send-message { width:100%; border-radius:.82rem; }
    .card-comment { min-height:2.5rem; }
    .mobile-empty-state { display:grid; place-items:center; gap:.35rem; border:1px dashed rgba(108,155,207,.28); border-radius:.9rem; padding:1.2rem; color:var(--otziv-info); background:rgba(255,255,255,.7); font-size:.72rem; font-weight:900; text-align:center; }
    @media (max-width:380px) { .control-top,.detail-head { grid-template-columns:1fr; } .detail-actions,.card-grid,.action-grid,.reply-box { grid-template-columns:1fr; } .refresh-button { width:100%; } }
  `]
})
export class ManagerControlPage implements OnInit {
  readonly summary = signal<ManagerControlSummary | null>(null);
  readonly detail = signal<ManagerControlManagerDetail | null>(null);
  readonly selectedManagerId = signal<number | null>(null);
  readonly loading = signal(false);
  readonly mutating = signal(false);
  readonly mutatingId = signal<number | null>(null);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly comments = signal<Record<number, string>>({});
  readonly replies = signal<Record<number, string>>({});

  readonly managers = computed(() => this.summary()?.managers ?? []);

  constructor(
    readonly auth: AuthService,
    private readonly api: ApiService,
    private readonly externalLink: MobileExternalLinkService
  ) {}

  ngOnInit(): void {
    void this.load();
  }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    try {
      await this.load(true);
    } finally {
      event.target.complete();
    }
  }

  async load(forceSync = false): Promise<void> {
    if (this.loading()) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      const summary = forceSync
        ? await this.api.syncManagerControlToday().toPromise()
        : await this.api.getManagerControlToday().toPromise();
      this.summary.set(summary ?? null);
      const managerId = this.resolveManagerId(summary ?? null);
      this.selectedManagerId.set(managerId);
      if (managerId) {
        await this.loadDetails(managerId, forceSync);
      } else {
        this.detail.set(null);
      }
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  async sync(): Promise<void> {
    await this.load(true);
    if (!this.error()) {
      this.notice.set('Контроль обновлен.');
    }
  }

  async selectManager(managerId: number): Promise<void> {
    this.selectedManagerId.set(managerId);
    await this.loadDetails(managerId);
  }

  visibleItems(detail: ManagerControlManagerDetail): ManagerControlItemDetail[] {
    return detail.items.filter((item) => item.count > 0 || item.examples.length > 0);
  }

  trackCard(index: number, card: ManagerControlConcreteItem): string {
    return `${card.controlEntityId ?? card.entityId ?? index}:${card.type}`;
  }

  isOwnerAdmin(): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  }

  todayLabel(): string {
    return new Date().toISOString().slice(0, 10);
  }

  shortName(value?: string | null): string {
    const text = (value || 'Менеджер').trim();
    const parts = text.split(/\s+/).filter(Boolean);
    return parts.length > 1 ? `${parts[0]} ${parts[1].slice(0, 1)}.` : text;
  }

  statusLabel(card: ManagerControlConcreteItem): string {
    return card.itemStatus === 'RESOLVED' ? 'закрыто' : card.itemStatus === 'DEFERRED' ? 'отложено' : 'открыто';
  }

  controlAutoCloseStatus(detail: ManagerControlManagerDetail): string {
    if (detail.closedAt) {
      return `Контроль закрыт: ${this.formatDateTime(detail.closedAt)}`;
    }
    if (detail.canCloseDay) {
      return 'Готов к автозакрытию в 20:00-05:00';
    }
    return detail.closeBlockers[0] || 'Есть открытые пункты контроля';
  }

  formatDateTime(value: string | null | undefined): string {
    if (!value) {
      return '';
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime())
      ? value
      : date.toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  chatLabel(card: ManagerControlConcreteItem): string {
    const value = `${card.chatUrl || ''} ${card.reason || ''}`.toLowerCase();
    if (value.includes('whatsapp')) {
      return 'WhatsApp';
    }
    if (value.includes('max.ru') || value.includes(' max')) {
      return 'MAX';
    }
    if (value.includes('telegram') || value.includes('t.me')) {
      return 'Telegram';
    }
    return 'Открыть';
  }

  isHandled(card: ManagerControlConcreteItem): boolean {
    return card.itemStatus === 'RESOLVED' || card.actionType === 'RESOLVED';
  }

  isUnanswered(card: ManagerControlConcreteItem): boolean {
    return card.type === 'CLIENT_CHAT_UNANSWERED' || Boolean(card.reason?.toLowerCase().includes('без ответа'));
  }

  showRepair(card: ManagerControlConcreteItem): boolean {
    const text = `${card.type} ${card.reason || ''}`.toLowerCase();
    return text.includes('invoice') || text.includes('telegram') || text.includes('автоответчик') || text.includes('очеред');
  }

  commentText(card: ManagerControlConcreteItem): string {
    const id = card.controlEntityId;
    return id ? this.comments()[id] ?? '' : '';
  }

  setCommentText(card: ManagerControlConcreteItem, value: string): void {
    const id = card.controlEntityId;
    if (!id) {
      return;
    }
    this.comments.update((comments) => ({ ...comments, [id]: value ?? '' }));
  }

  replyText(card: ManagerControlConcreteItem): string {
    const id = card.controlEntityId;
    return id ? this.replies()[id] ?? '' : '';
  }

  setReplyText(card: ManagerControlConcreteItem, value: string): void {
    const id = card.controlEntityId;
    if (!id) {
      return;
    }
    this.replies.update((replies) => ({ ...replies, [id]: value ?? '' }));
  }

  canReply(card: ManagerControlConcreteItem): boolean {
    const id = card.controlEntityId;
    return Boolean(id && this.replyText(card).trim() && this.mutatingId() !== id);
  }

  async acceptControl(): Promise<void> {
    const id = this.detail()?.dailyControlId;
    if (!id) {
      this.notice.set('Сначала обновите контроль.');
      return;
    }
    await this.runControlMutation(() => this.api.acceptManagerControl(id).toPromise(), 'Контроль принят.');
  }

  async markStage(stage: 'MORNING_DONE' | 'FINAL_CHECK'): Promise<void> {
    const id = this.detail()?.dailyControlId;
    if (!id) {
      this.notice.set('Сначала обновите контроль.');
      return;
    }
    await this.runControlMutation(
      () => this.api.markManagerControlStage(id, { stage }).toPromise(),
      'Этап отмечен.'
    );
  }

  async closeDay(): Promise<void> {
    const id = this.detail()?.dailyControlId;
    if (!id) {
      this.notice.set('Сначала обновите контроль.');
      return;
    }
    this.mutating.set(true);
    this.error.set(null);
    try {
      const result = await this.api.closeManagerControlDay(id, { comment: 'Закрыто из мобильного приложения.' }).toPromise();
      this.notice.set(result?.closed ? 'Контроль дня закрыт.' : 'Контроль пока нельзя закрыть.');
      const managerId = this.selectedManagerId();
      if (managerId) {
        await this.loadDetails(managerId);
      }
      await this.loadSummaryOnly();
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.mutating.set(false);
    }
  }

  async requestWorker(card: ManagerControlConcreteItem): Promise<void> {
    await this.actionCard(card, {
      actionType: 'ACTION_TAKEN',
      comment: this.commentText(card) || 'Запрошено пояснение специалиста.',
      manualWorkerNotification: true
    }, 'Запрос специалисту отправлен.');
  }

  async markResolved(card: ManagerControlConcreteItem): Promise<void> {
    await this.actionCard(card, {
      actionType: 'RESOLVED',
      comment: this.commentText(card) || 'Проверено в мобильном контроле.'
    }, 'Карточка отмечена как проверенная.');
  }

  async defer(card: ManagerControlConcreteItem): Promise<void> {
    const comment = this.commentText(card).trim();
    if (!comment) {
      this.notice.set('Для отложить нужен комментарий.');
      return;
    }
    await this.actionCard(card, { actionType: 'DEFERRED', comment }, 'Карточка отложена.');
  }

  async markNoAnswerNeeded(card: ManagerControlConcreteItem): Promise<void> {
    await this.actionCard(card, {
      actionType: 'ACKNOWLEDGED',
      comment: this.commentText(card) || 'Сообщение клиента не требует ответа.'
    }, 'Сообщение отмечено как не требующее ответа.');
  }

  async sendClientMessage(card: ManagerControlConcreteItem): Promise<void> {
    const id = card.controlEntityId;
    if (!id || this.mutatingId() === id) {
      return;
    }
    await this.runCardMutation(id, () => this.api.sendManagerControlClientMessage(id).toPromise(), 'Сообщение клиенту отправлено.');
  }

  async replyClient(card: ManagerControlConcreteItem): Promise<void> {
    const id = card.controlEntityId;
    const message = this.replyText(card).trim();
    if (!id || !message || this.mutatingId() === id) {
      return;
    }
    await this.runCardMutation(
      id,
      () => this.api.replyManagerControlClientMessage(id, { message }).toPromise(),
      'Ответ клиенту отправлен.'
    );
    this.replies.update((replies) => ({ ...replies, [id]: '' }));
  }

  async repair(card: ManagerControlConcreteItem): Promise<void> {
    const id = card.controlEntityId;
    if (!id || this.mutatingId() === id) {
      return;
    }
    await this.runCardMutation(id, () => this.api.repairManagerControlConcreteItem(id).toPromise(), 'Починка запущена.');
  }

  openLink(url?: string | null): void {
    if (url) {
      void this.externalLink.open(url);
    }
  }

  private async loadDetails(managerId: number, forceSync = false): Promise<void> {
    const detail = forceSync
      ? await this.api.syncManagerControlDetails(managerId).toPromise()
      : await this.api.getManagerControlDetails(managerId).toPromise();
    if (!forceSync && detail && this.needsDetailSync(detail)) {
      const syncedDetail = await this.api.syncManagerControlDetails(managerId).toPromise();
      this.detail.set(syncedDetail ?? detail);
      return;
    }
    this.detail.set(detail ?? null);
  }

  private needsDetailSync(detail: ManagerControlManagerDetail): boolean {
    return !detail.dailyControlId || detail.items.some((item) =>
      item.itemStatus === 'OPEN'
      && item.examples.some((example) => !example.controlEntityId)
    );
  }

  private resolveManagerId(summary: ManagerControlSummary | null): number | null {
    if (!summary?.managers.length) {
      return null;
    }
    const selected = this.selectedManagerId();
    if (selected && summary.managers.some((manager) => manager.managerId === selected)) {
      return selected;
    }
    if (!this.isOwnerAdmin()) {
      return summary.managers[0]?.managerId ?? null;
    }
    const red = summary.managers.find((manager) => manager.status === 'RED');
    return red?.managerId ?? summary.managers[0]?.managerId ?? null;
  }

  private async actionCard(
    card: ManagerControlConcreteItem,
    payload: ManagerControlActionPayload,
    successMessage: string
  ): Promise<void> {
    const id = card.controlEntityId;
    if (!id || this.mutatingId() === id) {
      return;
    }
    await this.runCardMutation(
      id,
      () => this.api.actionManagerControlConcreteItem(id, payload).toPromise(),
      successMessage
    );
  }

  private async runControlMutation(
    action: () => Promise<ManagerControlManagerDetail | undefined>,
    successMessage: string
  ): Promise<void> {
    this.mutating.set(true);
    this.error.set(null);
    try {
      const detail = await action();
      if (detail) {
        this.detail.set(detail);
      }
      this.notice.set(successMessage);
      await this.loadSummaryOnly();
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.mutating.set(false);
    }
  }

  private async runCardMutation(
    id: number,
    action: () => Promise<ManagerControlConcreteItem | undefined>,
    successMessage: string
  ): Promise<void> {
    this.mutatingId.set(id);
    this.error.set(null);
    try {
      await action();
      this.notice.set(successMessage);
      const managerId = this.selectedManagerId();
      if (managerId) {
        await this.loadDetails(managerId);
      }
      await this.loadSummaryOnly();
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.mutatingId.set(null);
    }
  }

  private async loadSummaryOnly(): Promise<void> {
    try {
      this.summary.set(await this.api.getManagerControlToday().toPromise() ?? null);
    } catch {
      // Details are already updated; summary can refresh on the next pull-to-refresh.
    }
  }

  private errorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      return error.error?.message || error.error?.error || error.message || 'Не удалось выполнить действие.';
    }
    return error instanceof Error ? error.message : 'Не удалось выполнить действие.';
  }
}
