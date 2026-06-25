import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  IonContent,
  IonRefresher,
  IonRefresherContent,
  RefresherCustomEvent
} from '@ionic/angular/standalone';
import {
  ApiService,
  Page,
  WorkerRiskIncident,
  WorkerRiskIncidentLevel,
  WorkerRiskIncidentStatus,
  WorkerRiskResolutionAction
} from '../core/api.service';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileRemindersComponent } from '../shared/mobile-reminders.component';

type RiskStatusTab = {
  key: WorkerRiskIncidentStatus;
  label: string;
  icon: string;
};

const EMPTY_RISK_PAGE: Page<WorkerRiskIncident> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
  number: 0,
  size: 50
};

@Component({
  selector: 'app-worker-risk-mobile',
  imports: [
    DatePipe,
    IonContent,
    IonRefresher,
    IonRefresherContent,
    MobileHeaderComponent,
    MobileRemindersComponent
  ],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Риски" />

      <ion-content fullscreen [scrollY]="false">
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <app-mobile-reminders #reminders />

        <main class="risk-page">
          <section class="risk-tabs" aria-label="Статусы инцидентов">
            @for (tab of tabs; track tab.key) {
              <button type="button" [class.active]="status() === tab.key" (click)="setStatus(tab.key)">
                <span class="material-icons-sharp">{{ tab.icon }}</span>
                <strong>{{ tab.label }}</strong>
              </button>
            }
          </section>

          <section class="risk-summary" aria-label="Сводка рисков">
            <article>
              <span>Всего</span>
              <strong>{{ page().totalElements }}</strong>
            </article>
            <article>
              <span>Высокий риск</span>
              <strong>{{ highRiskCount() }}</strong>
            </article>
            <article>
              <span>На проверку</span>
              <strong>{{ managerReviewCount() }}</strong>
            </article>
          </section>

          <section class="risk-guide" aria-label="Памятка проверки рисков">
            <article>
              <span class="material-icons-sharp">policy</span>
              <div>
                <strong>Что это</strong>
                <p>Автоматические сигналы по необычным действиям специалиста.</p>
              </div>
            </article>
            <article>
              <span class="material-icons-sharp">fact_check</span>
              <div>
                <strong>Что проверить</strong>
                <p>Откройте заказ или отзыв, сверьте аккаунт, заметки, дату и результат.</p>
              </div>
            </article>
            <article>
              <span class="material-icons-sharp">task_alt</span>
              <div>
                <strong>После проверки</strong>
                <p>Проверено, игнор, запрос разъяснения или нарушение со штрафом.</p>
              </div>
            </article>
          </section>

          @if (notice()) {
            <button class="inline-alert success" type="button" (click)="notice.set(null)">
              <span class="material-icons-sharp">task_alt</span>
              <span>{{ notice() }}</span>
            </button>
          }

          @if (error()) {
            <button class="inline-alert" type="button" (click)="load()">
              <span class="material-icons-sharp">error</span>
              <span>{{ error() }}</span>
            </button>
          }

          <section class="risk-list" aria-label="Инциденты">
            @for (incident of incidents(); track trackIncident($index, incident)) {
              <article class="risk-card" [class.high]="incident.level === 'HIGH_RISK'" [class.review]="incident.level === 'MANAGER_REVIEW'">
                <header>
                  <strong>{{ incident.title }}</strong>
                  <div class="risk-card-badges">
                    <span>{{ incident.createdAt | date:'dd.MM.yyyy HH:mm' }}</span>
                    <b>{{ riskLabel(incident.level) }} · {{ incident.score }}</b>
                  </div>
                </header>

                <div class="risk-grid">
                  <div class="risk-field">
                    <span>Специалист</span>
                    <strong>{{ incident.workerName || incident.workerUsername || '-' }}</strong>
                  </div>
                  <button class="risk-field link-field" type="button" [disabled]="!incident.orderId" (click)="openOrder(incident)">
                    <span>Заказ</span>
                    <strong>{{ incident.orderId ? '#' + incident.orderId : '-' }}</strong>
                  </button>
                  <button class="risk-field link-field" type="button" [disabled]="!incident.orderId || !incident.reviewId" (click)="openReview(incident)">
                    <span>Отзыв</span>
                    <strong>{{ incident.reviewId ? '#' + incident.reviewId : '-' }}</strong>
                  </button>
                </div>

                @if (incident.details || incident.message) {
                  <p>{{ incident.details || incident.message }}</p>
                }

                @if (incident.workerExplanation) {
                  <p class="worker-explanation">
                    <span>Пояснение специалиста</span>
                    {{ incident.workerExplanation }}
                    @if (incident.workerExplanationAt) {
                      <small>{{ incident.workerExplanationAt | date:'dd.MM.yyyy HH:mm' }}</small>
                    }
                  </p>
                }

                @if (incident.resolutionAction) {
                  <div class="risk-result">
                    <span>Итог</span>
                    <strong>{{ resolutionLabel(incident.resolutionAction) }}</strong>
                  </div>
                }

                @if (incident.penaltyPoints > 0) {
                  <div class="risk-result danger">
                    <span>Штраф</span>
                    <strong>{{ incident.penaltyPoints }} б.</strong>
                  </div>
                }

                @if (incident.rollbackMessage) {
                  <p class="rollback-note">{{ incident.rollbackMessage }}</p>
                }

                @if (status() === 'OPEN') {
                  <footer>
                    <button type="button" class="muted" (click)="ignore(incident)" [disabled]="mutatingId() === incident.id">
                      <span class="material-icons-sharp">visibility_off</span>
                      Игнорировать
                    </button>
                    @if (explanationRequested(incident)) {
                      <button type="button" class="warning" disabled>
                        <span class="material-icons-sharp">{{ incident.workerExplanation ? 'mark_chat_read' : 'hourglass_top' }}</span>
                        {{ incident.workerExplanation ? 'Ответ есть' : 'Ждем ответ' }}
                      </button>
                    } @else {
                      <button type="button" class="warning" (click)="requestExplanation(incident)" [disabled]="mutatingId() === incident.id">
                        <span class="material-icons-sharp">contact_support</span>
                        Разъяснение
                      </button>
                    }
                    <button type="button" class="danger" (click)="confirmViolation(incident)" [disabled]="mutatingId() === incident.id">
                      <span class="material-icons-sharp">gpp_bad</span>
                      Нарушение
                    </button>
                    <button type="button" class="success" (click)="resolve(incident)" [disabled]="mutatingId() === incident.id">
                      <span class="material-icons-sharp">task_alt</span>
                      Проверено
                    </button>
                  </footer>
                } @else if (status() === 'VIOLATION') {
                  <footer>
                    @if (incident.canRollback) {
                      <button type="button" class="danger wide" (click)="rollback(incident)" [disabled]="mutatingId() === incident.id">
                        <span class="material-icons-sharp">undo</span>
                        Вернуть в работу
                      </button>
                    } @else if (!incident.rollbackStatus) {
                      <span class="manual-only">Автовозврат пока не поддерживается</span>
                    }
                  </footer>
                }
              </article>
            } @empty {
              @if (!loading() && !error()) {
                <section class="mobile-empty-state">
                  <span class="material-icons-sharp">verified_user</span>
                  <p>Инцидентов нет</p>
                </section>
              }
            }
          </section>
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content { --overflow: hidden; }

    .risk-page {
      display: flex;
      height: 100%;
      max-width: 44rem;
      min-height: 0;
      margin: 0 auto;
      overflow: auto;
      flex-direction: column;
      gap: 0.55rem;
      padding: var(--otziv-page-padding-y, 0.55rem) var(--otziv-page-padding-x, 0.62rem) calc(var(--otziv-page-padding-bottom, 0.55rem) + env(safe-area-inset-bottom));
      -webkit-overflow-scrolling: touch;
    }

    .risk-tabs,
    .risk-summary,
    .risk-guide {
      display: grid;
      gap: 0.42rem;
    }

    .risk-tabs {
      grid-template-columns: repeat(4, minmax(0, 1fr));
    }

    .risk-tabs button,
    .risk-card footer button,
    .risk-field {
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 0.9rem;
      background: var(--otziv-white);
      color: var(--otziv-dark);
      font: 900 0.62rem/1 var(--otziv-font-family);
    }

    .risk-tabs button {
      display: grid;
      justify-items: center;
      gap: 0.18rem;
      min-height: 2.35rem;
      padding: 0.3rem 0.2rem;
    }

    .risk-tabs .material-icons-sharp {
      font-size: 1rem;
      color: var(--otziv-primary);
    }

    .risk-tabs button.active {
      border-color: var(--otziv-primary);
      background: var(--otziv-light);
      color: var(--otziv-primary);
    }

    .risk-summary {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    .risk-summary article,
    .risk-guide article,
    .risk-card {
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.9rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-muted-surface, #f8fafc) 100%);
      box-shadow: 0 0.8rem 1.45rem rgba(132, 139, 200, 0.1);
    }

    .risk-summary article {
      display: grid;
      justify-items: center;
      gap: 0.2rem;
      padding: 0.48rem 0.3rem;
    }

    .risk-summary span,
    .risk-field span,
    .risk-result span {
      color: var(--otziv-info);
      font-size: 0.56rem;
      font-weight: 800;
    }

    .risk-summary strong {
      color: var(--otziv-dark);
      font-size: 1rem;
      line-height: 1;
    }

    .risk-guide {
      grid-template-columns: 1fr;
    }

    .risk-guide article {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: start;
      gap: 0.48rem;
      padding: 0.55rem;
    }

    .risk-guide .material-icons-sharp {
      display: grid;
      width: 1.7rem;
      height: 1.7rem;
      place-items: center;
      border-radius: 0.62rem;
      color: var(--otziv-primary);
      background: var(--otziv-light);
      font-size: 1.08rem;
    }

    .risk-guide strong {
      color: var(--otziv-dark);
      font-size: 0.72rem;
    }

    .risk-guide p {
      margin: 0.16rem 0 0;
      color: var(--otziv-info);
      font-size: 0.62rem;
      line-height: 1.34;
    }

    .inline-alert {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.45rem;
      border: 1px solid rgba(239, 52, 95, 0.25);
      border-radius: 0.88rem;
      padding: 0.55rem 0.65rem;
      color: var(--otziv-danger);
      background: #fff5f7;
      font: 900 0.68rem/1.25 var(--otziv-font-family);
      text-align: left;
    }

    .inline-alert.success {
      border-color: rgba(68, 158, 133, 0.26);
      color: var(--otziv-success);
      background: #f2fbf6;
    }

    .risk-list {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.62rem;
      padding-bottom: 0.4rem;
    }

    .risk-card {
      display: grid;
      align-content: start;
      justify-items: center;
      gap: 0.58rem;
      padding: 0.68rem;
      text-align: center;
    }

    .risk-card.high {
      border-color: rgba(239, 52, 95, 0.36);
    }

    .risk-card.review {
      border-color: rgba(209, 164, 52, 0.34);
    }

    .risk-card header {
      display: grid;
      justify-items: center;
      gap: 0.4rem;
      width: 100%;
    }

    .risk-card header > strong {
      color: var(--otziv-dark);
      font-family: var(--otziv-card-title-font);
      font-size: 0.78rem;
      line-height: 1.22;
    }

    .risk-card-badges {
      display: flex;
      width: 100%;
      align-items: center;
      justify-content: space-between;
      gap: 0.35rem;
    }

    .risk-card-badges span,
    .risk-card-badges b {
      min-width: 0;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 999px;
      padding: 0.28rem 0.42rem;
      background: var(--otziv-white);
      color: var(--otziv-info);
      font: 900 0.54rem/1 var(--otziv-font-family);
      white-space: nowrap;
    }

    .risk-card-badges b {
      border-color: rgba(68, 158, 133, 0.34);
      color: var(--otziv-success);
      background: #f2fbf6;
    }

    .risk-card.high .risk-card-badges b {
      border-color: rgba(239, 52, 95, 0.34);
      color: var(--otziv-danger);
      background: #fff5f7;
    }

    .risk-card.review .risk-card-badges b {
      border-color: rgba(209, 164, 52, 0.38);
      color: #9b6f09;
      background: #fffaf0;
    }

    .risk-grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.42rem;
      width: 100%;
    }

    .risk-field {
      display: grid;
      justify-items: center;
      gap: 0.18rem;
      min-width: 0;
      min-height: 3rem;
      padding: 0.44rem 0.32rem;
      text-decoration: none;
    }

    button.risk-field {
      cursor: pointer;
    }

    button.risk-field:disabled {
      opacity: 0.72;
      cursor: default;
    }

    .risk-field strong,
    .risk-result strong {
      min-width: 0;
      max-width: 100%;
      overflow-wrap: anywhere;
      color: var(--otziv-dark);
      font-size: 0.66rem;
      line-height: 1.2;
    }

    .link-field strong {
      color: var(--otziv-primary);
      text-decoration: underline;
      text-underline-offset: 0.12rem;
    }

    .risk-card p,
    .risk-result {
      width: 100%;
      margin: 0;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.72rem;
      padding: 0.48rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background, #f8fafc);
      font: 800 0.62rem/1.36 var(--otziv-font-family);
      text-align: center;
    }

    .risk-result {
      display: grid;
      justify-items: center;
      gap: 0.16rem;
    }

    .risk-result.danger {
      border-color: rgba(239, 52, 95, 0.24);
      background: #fff5f7;
    }

    .rollback-note {
      border-color: rgba(68, 158, 133, 0.28) !important;
      background: #f2fbf6 !important;
    }

    .worker-explanation {
      display: grid;
      justify-items: center;
      gap: 0.22rem;
      border-color: rgba(68, 158, 133, 0.28) !important;
      background: #f2fbf6 !important;
      color: #246f5c !important;
    }

    .worker-explanation span,
    .worker-explanation small {
      color: #4f8c78;
      font-size: 0.56rem;
      font-weight: 900;
    }

    .risk-card footer {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.42rem;
      width: 100%;
    }

    .risk-card footer button {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.22rem;
      min-height: 1.88rem;
      padding: 0 0.42rem;
    }

    .risk-card footer button .material-icons-sharp {
      font-size: 1rem;
    }

    .risk-card footer button.muted {
      color: var(--otziv-info);
      background: #f8fafc;
    }

    .risk-card footer button.warning {
      border-color: rgba(209, 164, 52, 0.4);
      color: #8a650b;
      background: #fffaf0;
    }

    .risk-card footer button.danger {
      border-color: rgba(239, 52, 95, 0.34);
      color: var(--otziv-danger);
      background: #fff5f7;
    }

    .risk-card footer button.success {
      border-color: rgba(68, 158, 133, 0.34);
      color: var(--otziv-success);
      background: #f2fbf6;
    }

    .risk-card footer button.wide,
    .manual-only {
      grid-column: 1 / -1;
    }

    .manual-only {
      color: var(--otziv-info);
      font: 900 0.62rem/1.25 var(--otziv-font-family);
    }

    .mobile-empty-state {
      display: grid;
      justify-items: center;
      gap: 0.4rem;
      grid-column: 1 / -1;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.9rem;
      padding: 1.4rem;
      color: var(--otziv-info);
      background: var(--otziv-white);
      font-weight: 900;
    }

    .mobile-empty-state .material-icons-sharp {
      color: var(--otziv-success);
      font-size: 1.8rem;
    }

    @media (max-width: 920px) {
      .risk-list {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }

    @media (max-width: 640px) {
      .risk-tabs {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }

      .risk-list {
        grid-template-columns: 1fr;
      }
    }
  `]
})
export class WorkerRiskPage implements OnInit {
  readonly tabs: RiskStatusTab[] = [
    { key: 'OPEN', label: 'Открытые', icon: 'warning' },
    { key: 'RESOLVED', label: 'Проверенные', icon: 'task_alt' },
    { key: 'IGNORED', label: 'Игнор', icon: 'visibility_off' },
    { key: 'VIOLATION', label: 'Нарушения', icon: 'gpp_bad' }
  ];

  readonly status = signal<WorkerRiskIncidentStatus>('OPEN');
  readonly page = signal<Page<WorkerRiskIncident>>(EMPTY_RISK_PAGE);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly mutatingId = signal<number | null>(null);

  readonly incidents = computed(() => this.page().content ?? []);
  readonly highRiskCount = computed(() => this.incidents().filter((incident) => incident.level === 'HIGH_RISK').length);
  readonly managerReviewCount = computed(() => this.incidents().filter((incident) => incident.level === 'MANAGER_REVIEW').length);

  constructor(
    private readonly api: ApiService,
    private readonly router: Router,
    private readonly confirm: MobileConfirmService
  ) {}

  ngOnInit(): void {
    this.load();
  }

  setStatus(status: WorkerRiskIncidentStatus): void {
    if (this.status() === status) {
      return;
    }

    this.status.set(status);
    this.load();
  }

  refresh(event: RefresherCustomEvent): void {
    this.load(() => event.target.complete());
  }

  load(done?: () => void): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.getManagerWorkerRiskIncidents(this.status()).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
        done?.();
      },
      error: (error) => {
        this.error.set(this.apiErrorMessage(error, 'Риски не загрузились.'));
        this.loading.set(false);
        done?.();
      }
    });
  }

  resolve(incident: WorkerRiskIncident): void {
    this.updateIncident(incident, 'VERIFIED');
  }

  ignore(incident: WorkerRiskIncident): void {
    this.updateIncident(incident, 'FALSE_POSITIVE');
  }

  requestExplanation(incident: WorkerRiskIncident): void {
    this.updateIncident(incident, 'EXPLANATION_REQUESTED');
  }

  async confirmViolation(incident: WorkerRiskIncident): Promise<void> {
    const confirmed = await this.confirm.confirm({
      title: 'Зафиксировать нарушение',
      message: 'Отметить инцидент как нарушение и начислить штрафной балл специалисту?',
      confirmText: 'Нарушение',
      danger: true
    });
    if (!confirmed) {
      return;
    }
    this.updateIncident(incident, 'VIOLATION_CONFIRMED', 1);
  }

  async rollback(incident: WorkerRiskIncident): Promise<void> {
    const confirmed = await this.confirm.confirm({
      title: 'Вернуть в работу',
      message: 'Вернуть поддерживаемую карточку в работу по этому нарушению?',
      confirmText: 'Вернуть',
      danger: true
    });
    if (!confirmed || this.mutatingId()) {
      return;
    }

    this.mutatingId.set(incident.id);
    this.api.rollbackManagerWorkerRiskIncident(incident.id).subscribe({
      next: (updated) => {
        this.notice.set(updated.rollbackMessage || 'Действие обработано.');
        this.mutatingId.set(null);
        this.load();
      },
      error: (error) => {
        this.error.set(this.apiErrorMessage(error, 'Действие не возвращено.'));
        this.mutatingId.set(null);
      }
    });
  }

  openOrder(incident: WorkerRiskIncident): void {
    if (!incident.orderId) {
      return;
    }
    void this.router.navigate(['/tabs/orders', 0, incident.orderId]);
  }

  openReview(incident: WorkerRiskIncident): void {
    if (!incident.orderId || !incident.reviewId) {
      return;
    }
    void this.router.navigate(['/tabs/orders', 0, incident.orderId], {
      queryParams: { reviewId: incident.reviewId }
    });
  }

  riskLabel(level: WorkerRiskIncidentLevel): string {
    switch (level) {
      case 'HIGH_RISK':
        return 'Высокий';
      case 'MANAGER_REVIEW':
        return 'Проверить';
      default:
        return 'Предупреждение';
    }
  }

  resolutionLabel(action: WorkerRiskResolutionAction | null | undefined): string {
    const labels: Record<WorkerRiskResolutionAction, string> = {
      VERIFIED: 'Проверено',
      FALSE_POSITIVE: 'Игнорировать',
      NORMAL_ACCOUNT_SELECTION: 'Нормальный подбор',
      EXPLANATION_REQUESTED: 'Ожидаем разъяснение',
      VIOLATION_CONFIRMED: 'Нарушение / штраф',
      WORKER_WARNED: 'Пояснение запрошено'
    };
    return action ? labels[action] ?? action : '-';
  }

  explanationRequested(incident: WorkerRiskIncident): boolean {
    return incident.resolutionAction === 'EXPLANATION_REQUESTED' || incident.resolutionAction === 'WORKER_WARNED';
  }

  trackIncident(_: number, incident: WorkerRiskIncident): number {
    return incident.id;
  }

  private updateIncident(incident: WorkerRiskIncident, action: WorkerRiskResolutionAction, penaltyPoints?: number): void {
    if (this.mutatingId()) {
      return;
    }

    this.mutatingId.set(incident.id);
    this.error.set(null);
    this.api.setManagerWorkerRiskIncidentResolution(incident.id, action, penaltyPoints).subscribe({
      next: () => {
        this.notice.set(this.noticeFor(action));
        this.mutatingId.set(null);
        this.load();
      },
      error: (error) => {
        this.error.set(this.apiErrorMessage(error, 'Статус инцидента не изменен.'));
        this.mutatingId.set(null);
      }
    });
  }

  private noticeFor(action: WorkerRiskResolutionAction): string {
    switch (action) {
      case 'FALSE_POSITIVE':
        return 'Инцидент проигнорирован.';
      case 'EXPLANATION_REQUESTED':
      case 'WORKER_WARNED':
        return 'Разъяснение запрошено.';
      case 'VIOLATION_CONFIRMED':
        return 'Нарушение зафиксировано.';
      default:
        return 'Инцидент проверен.';
    }
  }

  private apiErrorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const message = error.error?.message || error.error?.detail || error.message;
      return message || fallback;
    }
    return fallback;
  }
}
