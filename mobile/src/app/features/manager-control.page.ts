import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
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
  ManagerControlItemStatus,
  ManagerControlItemDetail,
  ManagerControlManagerDetail,
  ManagerControlManager,
  ManagerControlOverdueStatus,
  ManagerControlProblem,
  ManagerControlSection,
  ManagerControlSummary,
  ManagerPerformanceScore,
  WorkerRiskResolutionAction
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

@Component({
  selector: 'app-manager-control-mobile',
  imports: [DatePipe, FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header [title]="pageTitle()" />

      <ion-content fullscreen [scrollY]="false">
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <main class="control-page">
          <section class="control-top">
            <div>
              <p class="eyebrow">{{ isOwnerAdminView() ? 'ADMIN / OWNER' : 'MANAGER' }}</p>
              <h1>{{ pageTitle() }}</h1>
              <small>{{ summary()?.date || todayLabel() }} · {{ attentionCount() }} к действию</small>
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

          @if (isOwnerAdminView()) {
            <section class="summary-grid" aria-label="Сводка контроля">
              <article class="summary-card red">
                <span class="material-icons-sharp">error</span>
                <strong>{{ summary()?.redCount ?? 0 }}</strong>
                <small>красных</small>
              </article>
              <article class="summary-card yellow">
                <span class="material-icons-sharp">report_problem</span>
                <strong>{{ summary()?.yellowCount ?? 0 }}</strong>
                <small>желтых</small>
              </article>
              <article class="summary-card green">
                <span class="material-icons-sharp">verified</span>
                <strong>{{ summary()?.greenCount ?? 0 }}</strong>
                <small>зеленых</small>
              </article>
              <article class="summary-card blue">
                <span class="material-icons-sharp">rule</span>
                <strong>{{ summary()?.workloadTotal ?? 0 }}</strong>
                <small>нагрузка</small>
              </article>
            </section>
          }

          @if (isSummaryPage()) {
            <section class="manager-list" aria-label="Менеджеры">
              @for (manager of managers(); track manager.managerId) {
                <article class="manager-card" [class.red]="manager.status === 'RED'" [class.yellow]="manager.status === 'YELLOW'">
                  <header>
                    <span class="material-icons-sharp">{{ manager.status === 'RED' ? 'error' : manager.status === 'YELLOW' ? 'report_problem' : 'verified' }}</span>
                    <div>
                      <h2>{{ shortName(manager.name || manager.username) }}</h2>
                      <small>{{ manager.username || 'без логина' }}</small>
                    </div>
                    <button type="button" class="details-button" (click)="openDetails(manager)">
                      <span class="material-icons-sharp">list_alt</span>
                      Детали
                    </button>
                  </header>

                  <div class="manager-card-status">
                    <strong>{{ managerStatusLabel(manager) }}</strong>
                    <span>{{ manager.totalAttentionCount }} к действию</span>
                    <small>{{ manager.openItemCount }} открыто · {{ manager.handledItemCount }} обработано</small>
                  </div>

                  <div class="manager-numbers">
                    <span class="metric urgent">
                      <b>{{ manager.overdueOrderCount }}</b>
                      <small>просрочки</small>
                    </span>
                    <span class="metric urgent">
                      <b>{{ manager.openRiskCount }}</b>
                      <small>риски</small>
                    </span>
                    <span class="metric">
                      <b>{{ manager.criticalCount }}</b>
                      <small>всего к действию</small>
                    </span>
                  </div>

                  @if (manager.managerPerformance; as performance) {
                    <div class="manager-performance-line">
                      <span [class.red]="performance.loadAdjustedPerformanceScore < 60" [class.yellow]="performance.loadAdjustedPerformanceScore >= 60 && performance.loadAdjustedPerformanceScore < 80">
                        {{ performance.grade }} · {{ performance.loadAdjustedPerformanceScore }}
                      </span>
                      <small>SLA проблем {{ percent(performance.problemSlaRate) }} · SLA клиентов {{ percent(performance.clientSlaRate) }}</small>
                    </div>
                  }

                  @if (hasActionRows(manager)) {
                    <div class="overview-chips compact">
                      @for (problem of actionProblems(manager); track problem.code) {
                        <button type="button" class="overview-chip critical" [class.handled]="isHandledStatus(problem.itemStatus)" (click)="openDetails(manager)">
                          <span class="material-icons-sharp">{{ problem.icon || 'priority_high' }}</span>
                          <strong>{{ problem.count }}</strong>
                          <small>{{ problem.label }}</small>
                        </button>
                      }
                      @for (section of actionSections(manager); track section.code) {
                        <button type="button" class="overview-chip" [class.handled]="isHandledStatus(section.itemStatus)" (click)="openDetails(manager)">
                          <span class="material-icons-sharp">assignment</span>
                          <strong>{{ section.count }}</strong>
                          <small>{{ section.label }}</small>
                        </button>
                      }
                    </div>
                  } @else {
                    <p class="clean-state">
                      <span class="material-icons-sharp">done_all</span>
                      Нет открытых действий
                    </p>
                  }
                </article>
              } @empty {
                @if (!loading()) {
                  <section class="mobile-empty-state">
                    <span class="material-icons-sharp">manage_accounts</span>
                    <p>Менеджеров для контроля нет</p>
                  </section>
                }
              }
            </section>
          }

          @if (shouldShowDetail() && selectedManager(); as manager) {
            <section class="manager-overview" [class.personal]="!isOwnerAdminView()">
              <header>
                <div>
                  <p class="eyebrow">{{ isOwnerAdminView() ? 'MANAGER SNAPSHOT' : 'PERSONAL CONTROL' }}</p>
                  <h2>{{ manager.name || manager.username }}</h2>
                  <small>{{ manager.openItemCount }} открыто · {{ manager.handledItemCount }} обработано</small>
                </div>
                @if (manager.managerPerformance; as performance) {
                  <span class="score-badge" [class.red]="performance.loadAdjustedPerformanceScore < 60" [class.yellow]="performance.loadAdjustedPerformanceScore >= 60 && performance.loadAdjustedPerformanceScore < 80">
                    {{ performance.grade }} · {{ performance.loadAdjustedPerformanceScore }}
                  </span>
                }
              </header>

              @if (manager.managerPerformance; as performance) {
                <div class="performance-grid">
                  @for (row of performanceRows(performance); track row.label) {
                    <article>
                      <strong>{{ row.value }}</strong>
                      <small>{{ row.label }}</small>
                    </article>
                  }
                </div>
              }

              <div class="overview-chips">
                @for (problem of actionProblems(manager); track problem.code) {
                  <button type="button" class="overview-chip critical" [class.handled]="isHandledStatus(problem.itemStatus)" (click)="openLink(problem.targetUrl)">
                    <span class="material-icons-sharp">{{ problem.icon || 'priority_high' }}</span>
                    <strong>{{ problem.count }}</strong>
                    <small>{{ problem.label }}</small>
                  </button>
                }
                @for (section of actionSections(manager); track section.code) {
                  <button type="button" class="overview-chip" [class.handled]="isHandledStatus(section.itemStatus)" (click)="openLink(section.targetUrl)">
                    <span class="material-icons-sharp">assignment</span>
                    <strong>{{ section.count }}</strong>
                    <small>{{ section.label }}</small>
                  </button>
                }
                @for (overdue of openOverdueStatuses(manager); track overdue.status) {
                  <button type="button" class="overview-chip warning" [class.handled]="isHandledStatus(overdue.itemStatus)" (click)="openLink(overdue.targetUrl)">
                    <span class="material-icons-sharp">schedule</span>
                    <strong>{{ overdue.count }}</strong>
                    <small>{{ overdue.status }}</small>
                  </button>
                }
              </div>
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
                @if (isDetailPage()) {
                  <button type="button" (click)="backToSummary()">
                    <span class="material-icons-sharp">arrow_back</span>
                    Назад
                  </button>
                }
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
                    <span class="count-pill">{{ detailItemVisibleCount(item) }}</span>
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
                        @if (card.contactText) {
                          <button type="button" class="muted" [class.done]="isContactTextCopied(card)" (click)="copyContactText(card)" [disabled]="!card.controlEntityId">
                            <span class="material-icons-sharp">{{ isContactTextCopied(card) ? 'done' : 'content_copy' }}</span>
                            Текст
                          </button>
                        }
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

                      @if (isRisk(card)) {
                        <div class="risk-actions">
                          <button type="button" class="success" (click)="resolveRisk(card)" [disabled]="!canUpdateRisk(card)">
                            <span class="material-icons-sharp">verified</span>
                            Риск проверен
                          </button>
                          <button type="button" class="muted" (click)="ignoreRisk(card)" [disabled]="!canUpdateRisk(card)">
                            <span class="material-icons-sharp">visibility_off</span>
                            Ложное
                          </button>
                          <button type="button" class="warning" (click)="requestRiskExplanation(card)" [disabled]="!canUpdateRisk(card)">
                            <span class="material-icons-sharp">contact_support</span>
                            Пояснение
                          </button>
                          <button type="button" class="danger" (click)="confirmRiskViolation(card)" [disabled]="!canUpdateRisk(card)">
                            <span class="material-icons-sharp">gavel</span>
                            Нарушение
                          </button>
                        </div>
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

                  <div class="item-actions">
                    <textarea
                      rows="2"
                      placeholder="Комментарий по пункту"
                      [ngModel]="itemCommentText(item)"
                      (ngModelChange)="setItemCommentText(item, $event)"
                    ></textarea>
                    <button type="button" class="warning" (click)="markItemAction(item, 'ACTION_TAKEN')" [disabled]="mutatingItemId() === item.itemId">
                      <span class="material-icons-sharp">done_all</span>
                      В работе
                    </button>
                    <button type="button" class="muted" (click)="markItemAction(item, 'DEFERRED')" [disabled]="mutatingItemId() === item.itemId || !itemCommentText(item).trim()">
                      <span class="material-icons-sharp">schedule</span>
                      Отложить
                    </button>
                    <button type="button" class="success" (click)="markItemAction(item, 'RESOLVED')" [disabled]="mutatingItemId() === item.itemId">
                      <span class="material-icons-sharp">task_alt</span>
                      Закрыть пункт
                    </button>
                  </div>
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
          } @else if (shouldShowDetail() && !loading()) {
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
    :host {
      --control-surface: var(--otziv-white);
      --control-soft-surface: var(--otziv-muted-surface);
      --control-chip-surface: var(--otziv-tone-walk-surface);
      --control-success-surface: var(--otziv-tone-success-surface);
      --control-warning-surface: var(--otziv-tone-wait-surface);
      --control-danger-surface: var(--otziv-tone-bad-surface);
      --control-success-color: var(--otziv-success);
      --control-warning-color: #9a7118;
      --control-danger-color: var(--otziv-danger);
    }
    :host-context(body.otziv-dark-theme) {
      --control-warning-color: var(--otziv-warning);
    }
    ion-content { --overflow: hidden; }
    .control-page { display:flex; height:100%; max-width:48rem; min-height:0; margin:0 auto; overflow:auto; flex-direction:column; gap:.62rem; padding:var(--otziv-page-padding-y,.58rem) var(--otziv-page-padding-x,.62rem) calc(var(--otziv-page-padding-bottom,.62rem) + env(safe-area-inset-bottom)); -webkit-overflow-scrolling:touch; }
    .control-top,.detail-head,.control-section,.control-card,.worker-stats,.manager-overview,.summary-card { min-width:0; border:1px solid rgba(103,116,131,.16); border-radius:.92rem; background:linear-gradient(155deg,var(--control-surface) 0%,var(--control-soft-surface) 100%); box-shadow:0 .8rem 1.45rem rgba(132,139,200,.1); }
    .control-top,.detail-head { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:.58rem; padding:.72rem; }
    .control-top>*,
    .detail-head>*,
    .manager-overview>*,
    .control-section>*,
    .control-card>* { min-width:0; }
    .eyebrow { margin:0 0 .12rem; color:var(--otziv-info); font-size:.58rem; font-weight:1000; letter-spacing:0; }
    h1,h2,h3 { margin:0; color:var(--otziv-dark); font-family:var(--otziv-card-title-font); line-height:1.05; letter-spacing:0; }
    h1 { font-size:1.05rem; } h2 { font-size:.98rem; } h3 { font-size:.82rem; }
    small { color:var(--otziv-info); font-size:.62rem; font-weight:800; line-height:1.25; }
    button { min-width:0; font:inherit; letter-spacing:0; overflow-wrap:anywhere; }
    .refresh-button,.detail-actions button,.manager-strip button,.action-grid button,.card-grid button,.card-grid div,.send-button,.send-message { min-height:2.15rem; border:1px solid rgba(108,155,207,.22); border-radius:999px; padding:0 .72rem; color:var(--otziv-primary); background:var(--control-surface); font-size:.66rem; font-weight:1000; }
    .refresh-button,.detail-actions button,.action-grid button,.send-button,.send-message { display:inline-flex; align-items:center; justify-content:center; gap:.32rem; }
    .refresh-button .material-icons-sharp,.detail-actions .material-icons-sharp,.action-grid .material-icons-sharp,.send-button .material-icons-sharp,.send-message .material-icons-sharp { font-size:1rem; }
    button:disabled { opacity:.48; }
    .inline-alert { display:grid; grid-template-columns:auto minmax(0,1fr); align-items:center; gap:.45rem; border:1px solid rgba(237,45,91,.28); border-radius:.85rem; padding:.62rem; color:var(--control-danger-color); background:var(--control-danger-surface); text-align:left; font-size:.68rem; font-weight:900; }
    .inline-alert.success { border-color:rgba(47,159,149,.25); color:var(--control-success-color); background:var(--control-success-surface); }
    .manager-strip { display:grid; grid-auto-columns:minmax(8.2rem,1fr); grid-auto-flow:column; gap:.45rem; overflow-x:auto; padding-bottom:.14rem; }
    .manager-strip button { display:grid; align-content:center; justify-items:start; border-radius:.82rem; color:var(--otziv-dark); }
    .manager-strip button.active { border-color:var(--otziv-primary); background:var(--otziv-light); }
    .manager-strip button.red { border-color:rgba(237,45,91,.32); }
    .manager-strip button.yellow { border-color:rgba(231,180,52,.38); }
    .manager-strip em { color:var(--otziv-primary); font-size:.56rem; font-style:normal; font-weight:1000; }
    .manager-list { display:grid; gap:.62rem; }
    .manager-card { display:grid; min-width:0; gap:.55rem; border:1px solid rgba(103,116,131,.16); border-radius:.92rem; padding:.65rem; background:linear-gradient(155deg,var(--control-surface) 0%,var(--control-soft-surface) 100%); box-shadow:0 .8rem 1.45rem rgba(132,139,200,.1); }
    .manager-card.red { border-color:rgba(237,45,91,.36); }
    .manager-card.yellow { border-color:rgba(231,180,52,.38); }
    .manager-card>header { display:grid; grid-template-columns:auto minmax(0,1fr) auto; align-items:center; gap:.48rem; min-width:0; }
    .manager-card>header>.material-icons-sharp { display:grid; place-items:center; width:2.15rem; height:2.15rem; border-radius:.75rem; color:var(--otziv-primary); background:var(--control-chip-surface); }
    .manager-card.red>header>.material-icons-sharp { color:var(--control-danger-color); background:var(--control-danger-surface); }
    .manager-card.yellow>header>.material-icons-sharp { color:var(--control-warning-color); background:var(--control-warning-surface); }
    .manager-card header div { min-width:0; }
    .manager-card h2 { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .details-button { display:inline-flex; align-items:center; justify-content:center; gap:.3rem; min-height:2.1rem; border:1px solid rgba(108,155,207,.24); border-radius:999px; padding:0 .72rem; color:var(--otziv-primary); background:var(--control-surface); font-size:.64rem; font-weight:1000; }
    .details-button .material-icons-sharp { font-size:1rem; }
    .manager-card-status { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:.18rem .45rem; border:1px solid rgba(108,155,207,.18); border-radius:.78rem; padding:.5rem; background:var(--control-chip-surface); }
    .manager-card-status strong { color:var(--otziv-dark); font-size:.72rem; font-weight:1000; }
    .manager-card-status span { color:var(--otziv-dark); font-size:.72rem; font-weight:1000; text-align:right; }
    .manager-card-status small { grid-column:1 / -1; }
    .manager-numbers { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:.38rem; }
    .metric { display:grid; min-width:0; min-height:3.15rem; align-content:center; justify-items:center; border:1px solid rgba(108,155,207,.18); border-radius:.72rem; padding:.38rem; background:var(--control-surface); text-align:center; }
    .metric.urgent { border-color:rgba(237,45,91,.25); }
    .metric b { color:var(--otziv-dark); font-size:.92rem; font-weight:1000; }
    .metric small { max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .manager-performance-line { display:grid; grid-template-columns:auto minmax(0,1fr); align-items:center; gap:.4rem; }
    .manager-performance-line span { display:inline-flex; min-height:1.75rem; align-items:center; border:1px solid rgba(47,159,149,.28); border-radius:999px; padding:0 .62rem; color:var(--control-success-color); background:var(--control-success-surface); font-size:.62rem; font-weight:1000; white-space:nowrap; }
    .manager-performance-line span.yellow { border-color:rgba(231,180,52,.32); color:var(--control-warning-color); background:var(--control-warning-surface); }
    .manager-performance-line span.red { border-color:rgba(237,45,91,.3); color:var(--control-danger-color); background:var(--control-danger-surface); }
    .manager-performance-line small { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .clean-state { display:inline-flex; align-items:center; justify-content:center; gap:.32rem; min-height:2.1rem; margin:0; border:1px dashed rgba(47,159,149,.22); border-radius:.78rem; color:var(--control-success-color); background:var(--control-success-surface); font-size:.66rem; font-weight:1000; }
    .summary-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:.42rem; }
    .summary-card { display:grid; min-height:4.3rem; align-content:center; justify-items:center; gap:.1rem; padding:.5rem; text-align:center; }
    .summary-card .material-icons-sharp { font-size:1rem; }
    .summary-card strong { color:var(--otziv-dark); font-size:1.1rem; font-weight:1000; }
    .summary-card.red { border-color:rgba(237,45,91,.28); }
    .summary-card.yellow { border-color:rgba(231,180,52,.34); }
    .summary-card.green { border-color:rgba(47,159,149,.28); }
    .summary-card.blue { border-color:rgba(108,155,207,.3); }
    .manager-overview { display:grid; gap:.52rem; padding:.65rem; }
    .manager-overview>header { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:.5rem; align-items:start; }
    .score-badge { display:inline-flex; min-height:2rem; align-items:center; border:1px solid rgba(47,159,149,.28); border-radius:999px; padding:0 .7rem; color:var(--control-success-color); background:var(--control-success-surface); font-size:.68rem; font-weight:1000; white-space:nowrap; }
    .score-badge.yellow { border-color:rgba(231,180,52,.34); color:var(--control-warning-color); background:var(--control-warning-surface); }
    .score-badge.red { border-color:rgba(237,45,91,.3); color:var(--control-danger-color); background:var(--control-danger-surface); }
    .performance-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:.38rem; }
    .performance-grid article { display:grid; min-width:0; gap:.05rem; border:1px solid rgba(108,155,207,.18); border-radius:.72rem; padding:.42rem; background:var(--control-surface); }
    .performance-grid strong { color:var(--otziv-dark); font-size:.74rem; font-weight:1000; }
    .overview-chips { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:.38rem; }
    .overview-chip { display:grid; grid-template-columns:auto auto minmax(0,1fr); align-items:center; gap:.3rem; min-height:2.25rem; border:1px solid rgba(108,155,207,.2); border-radius:.75rem; padding:.35rem .45rem; color:var(--otziv-primary); background:var(--control-surface); text-align:left; }
    .overview-chip.critical { border-color:rgba(237,45,91,.28); color:var(--otziv-danger); }
    .overview-chip.warning { border-color:rgba(231,180,52,.34); color:var(--control-warning-color); }
    .overview-chip.handled { opacity:.64; }
    .overview-chip .material-icons-sharp { font-size:.95rem; }
    .overview-chip strong { font-size:.75rem; }
    .overview-chip small { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .detail-actions { display:grid; gap:.38rem; }
    .control-note { margin:0; border:1px solid rgba(108,155,207,.22); border-radius:.85rem; padding:.62rem; color:var(--otziv-info); background:var(--control-chip-surface); font-size:.66rem; font-weight:900; }
    .warning-note { border-color:rgba(231,180,52,.3); color:var(--control-warning-color); background:var(--control-warning-surface); }
    .ready-note,.closed-note { border-color:rgba(47,159,149,.25); color:var(--control-success-color); background:var(--control-success-surface); }
    .worker-stats { display:grid; gap:.42rem; padding:.65rem; }
    .worker-stats article { display:grid; gap:.1rem; border-top:1px solid rgba(103,116,131,.11); padding-top:.4rem; }
    .worker-stats span { color:var(--otziv-info); font-size:.62rem; font-weight:800; }
    .control-list { display:grid; gap:.65rem; }
    .control-section { display:grid; gap:.52rem; padding:.65rem; }
    .control-section>header,.control-card>header { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:start; gap:.48rem; }
    .count-pill,.control-card>header span { display:inline-flex; min-height:1.55rem; align-items:center; border:1px solid rgba(47,159,149,.22); border-radius:999px; padding:0 .58rem; color:var(--control-success-color); background:var(--control-success-surface); font-size:.58rem; font-weight:1000; }
    .control-card { display:grid; gap:.5rem; padding:.62rem; }
    .control-card.danger { border-color:rgba(237,45,91,.38); }
    .control-card.resolved { opacity:.72; }
    .card-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:.38rem; }
    .card-grid button,.card-grid div { display:grid; min-width:0; align-content:center; justify-items:center; border-radius:.72rem; padding:.34rem; color:var(--otziv-dark); background:var(--control-surface); }
    .card-grid span { color:var(--otziv-info); font-size:.55rem; font-weight:800; }
    .card-grid strong { max-width:100%; overflow:hidden; font-size:.66rem; text-overflow:ellipsis; white-space:nowrap; }
    .reason-text,.comment-text,.worker-answer,.delivery-error { margin:0; min-width:0; border:1px solid rgba(103,116,131,.13); border-radius:.72rem; padding:.55rem; color:var(--otziv-dark); background:var(--control-surface); font-size:.66rem; font-weight:800; line-height:1.36; overflow-wrap:anywhere; }
    .delivery-error { display:grid; grid-template-columns:auto minmax(0,1fr); align-items:start; gap:.35rem; border-color:rgba(237,45,91,.32); color:var(--control-danger-color); background:var(--control-danger-surface); }
    .worker-answer { border-color:rgba(47,159,149,.22); background:var(--control-success-surface); }
    .worker-answer span,.worker-answer small { display:block; margin-bottom:.12rem; color:var(--control-success-color); font-size:.56rem; font-weight:1000; }
    .action-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:.38rem; }
    .action-grid .success,.send-button,.send-message,.risk-actions .success,.item-actions .success { border-color:rgba(47,159,149,.28); color:var(--control-success-color); background:var(--control-success-surface); }
    .action-grid .warning,.risk-actions .warning,.item-actions .warning { border-color:rgba(231,180,52,.3); color:var(--control-warning-color); background:var(--control-warning-surface); }
    .action-grid .muted,.risk-actions .muted,.item-actions .muted { color:var(--otziv-info); }
    .action-grid .done { border-color:rgba(47,159,149,.28); color:var(--control-success-color); background:var(--control-success-surface); }
    .risk-actions,.item-actions { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:.38rem; }
    .risk-actions button,.item-actions button { display:inline-flex; align-items:center; justify-content:center; gap:.28rem; min-height:2.15rem; border:1px solid rgba(108,155,207,.22); border-radius:999px; padding:0 .58rem; background:var(--control-surface); font-size:.62rem; font-weight:1000; }
    .risk-actions .danger { border-color:rgba(237,45,91,.3); color:var(--control-danger-color); background:var(--control-danger-surface); }
    .item-actions textarea { grid-column:1 / -1; }
    .reply-box { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:.42rem; align-items:stretch; }
    textarea { width:100%; min-width:0; border:1px solid rgba(103,116,131,.18); border-radius:.78rem; padding:.58rem; color:var(--otziv-dark); background:var(--control-surface); font:800 .68rem/1.35 var(--otziv-font-family); resize:vertical; }
    .reply-box .wide { grid-column:1 / -1; }
    .send-message { width:100%; border-radius:.82rem; }
    .card-comment { min-height:2.5rem; }
    .mobile-empty-state { display:grid; place-items:center; gap:.35rem; border:1px dashed rgba(108,155,207,.28); border-radius:.9rem; padding:1.2rem; color:var(--otziv-info); background:var(--control-soft-surface); font-size:.72rem; font-weight:900; text-align:center; }
    @media (max-width:380px) {
      .control-top,.detail-head,.manager-overview>header { grid-template-columns:1fr; }
      .manager-card>header,.overview-chips,.detail-actions,.card-grid,.action-grid,.risk-actions,.item-actions,.reply-box { grid-template-columns:1fr; }
      .summary-grid,.performance-grid { grid-template-columns:repeat(2,minmax(0,1fr)); }
      .manager-numbers { grid-template-columns:repeat(3,minmax(0,1fr)); }
      .details-button { width:100%; }
      .refresh-button { width:100%; }
    }
  `]
})
export class ManagerControlPage implements OnInit, OnDestroy {
  private routeSubscription?: Subscription;

  readonly summary = signal<ManagerControlSummary | null>(null);
  readonly detail = signal<ManagerControlManagerDetail | null>(null);
  readonly selectedManagerId = signal<number | null>(null);
  readonly loading = signal(false);
  readonly mutating = signal(false);
  readonly mutatingId = signal<number | null>(null);
  readonly mutatingItemId = signal<number | null>(null);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly comments = signal<Record<number, string>>({});
  readonly itemComments = signal<Record<number, string>>({});
  readonly replies = signal<Record<number, string>>({});
  readonly preparedContactItemIds = signal<Set<number>>(new Set());

  readonly managers = computed(() => this.summary()?.managers ?? []);
  readonly selectedManager = computed(() => {
    const managers = this.managers();
    const selectedId = this.selectedManagerId();
    return managers.find((manager) => manager.managerId === selectedId) ?? managers[0] ?? null;
  });

  constructor(
    readonly auth: AuthService,
    private readonly api: ApiService,
    private readonly externalLink: MobileExternalLinkService,
    private readonly route: ActivatedRoute,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.routeSubscription = this.route.paramMap.subscribe(() => {
      void this.load();
    });
  }

  ngOnDestroy(): void {
    this.routeSubscription?.unsubscribe();
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
      if (this.shouldShowDetail() && managerId) {
        await this.loadDetails(managerId, forceSync);
      } else {
        this.applyDetail(null);
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

  async openDetails(manager: ManagerControlManager): Promise<void> {
    this.closeTransientState();
    await this.router.navigateByUrl(`/tabs/control/${manager.managerId}`);
  }

  async backToSummary(): Promise<void> {
    this.closeTransientState();
    await this.router.navigateByUrl('/tabs/control');
  }

  visibleItems(detail: ManagerControlManagerDetail): ManagerControlItemDetail[] {
    return detail.items.filter((item) => this.shouldShowDetailItem(item));
  }

  shouldShowDetailItem(item: ManagerControlItemDetail): boolean {
    if (item.reasonCode === 'WORKER_ACTIONS') {
      return false;
    }
    if (item.group === 'ACTION') {
      return item.examples.length > 0;
    }
    return item.itemStatus !== 'OPEN' || Boolean(item.comment) || item.examples.some((example) =>
      Boolean(example.comment || example.actionType || (example.itemStatus && example.itemStatus !== 'OPEN'))
    );
  }

  detailItemVisibleCount(item: ManagerControlItemDetail): number {
    return item.group === 'ACTION' ? item.examples.length : item.count;
  }

  actionProblems(manager: ManagerControlManager): ManagerControlProblem[] {
    return manager.problems.filter((problem) => problem.group === 'ACTION' && problem.count > 0);
  }

  actionSections(manager: ManagerControlManager): ManagerControlSection[] {
    return manager.workerSections.filter((section) => section.group === 'ACTION' && section.count > 0);
  }

  openOverdueStatuses(manager: ManagerControlManager): ManagerControlOverdueStatus[] {
    return manager.overdueStatuses.filter((status) => status.count > 0);
  }

  performanceRows(performance: ManagerPerformanceScore): Array<{ label: string; value: string }> {
    return [
      { label: 'SLA проблем', value: this.percent(performance.problemSlaRate) },
      { label: 'SLA клиентов', value: this.percent(performance.clientSlaRate) },
      { label: 'Просрочки', value: this.percent(performance.overdueRate) },
      { label: 'Нагрузка', value: `${this.number(performance.workloadOrder)} / ${this.number(performance.workloadWorker)}` },
      { label: 'Ответ p50/p90', value: `${this.number(performance.clientReplyMedianMinutes)} / ${this.number(performance.clientReplyP90Minutes)} мин.` },
      { label: 'Бэклог', value: this.number(performance.backlogCount) }
    ];
  }

  managerStatusLabel(manager: ManagerControlManager): string {
    switch (manager.status) {
      case 'RED':
        return 'красный';
      case 'YELLOW':
        return 'желтый';
      default:
        return 'зеленый';
    }
  }

  isHandledStatus(status?: ManagerControlItemStatus | null): boolean {
    return Boolean(status && status !== 'OPEN');
  }

  hasActionRows(manager: ManagerControlManager): boolean {
    return this.actionProblems(manager).length > 0 || this.actionSections(manager).length > 0;
  }

  trackCard(index: number, card: ManagerControlConcreteItem): string {
    return `${card.controlEntityId ?? card.entityId ?? index}:${card.type}`;
  }

  pageTitle(): string {
    return this.isOwnerAdminView() ? 'Контроль' : 'Мои замечания';
  }

  isOwnerAdminView(): boolean {
    return !this.isPersonalControl() && this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  }

  isPersonalControl(): boolean {
    return this.route.snapshot.data['personalControl'] === true;
  }

  isDetailPage(): boolean {
    return this.routeManagerId() !== null;
  }

  isSummaryPage(): boolean {
    return this.isOwnerAdminView() && !this.isDetailPage();
  }

  shouldShowDetail(): boolean {
    return this.isPersonalControl() || this.isDetailPage();
  }

  attentionCount(): number {
    if (this.isOwnerAdminView()) {
      return this.summary()?.attentionTotal ?? 0;
    }
    const managerId = this.selectedManagerId();
    return this.managers().find((manager) => manager.managerId === managerId)?.totalAttentionCount
      ?? this.detail()?.openItemCount
      ?? 0;
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

  itemCommentText(item: ManagerControlItemDetail): string {
    return this.itemComments()[item.itemId] ?? item.comment ?? '';
  }

  setItemCommentText(item: ManagerControlItemDetail, value: string): void {
    this.itemComments.update((comments) => ({ ...comments, [item.itemId]: value ?? '' }));
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

  async markItemAction(item: ManagerControlItemDetail, actionType: ManagerControlActionPayload['actionType']): Promise<void> {
    if (!item.itemId || this.mutatingItemId() === item.itemId) {
      return;
    }
    const comment = this.itemCommentText(item).trim() || null;
    if (actionType === 'DEFERRED' && !comment) {
      this.notice.set('Для отложить нужен комментарий по пункту.');
      return;
    }
    this.mutatingItemId.set(item.itemId);
    this.error.set(null);
    try {
      await this.api.actionManagerControlItem(item.itemId, { actionType, comment }).toPromise();
      this.notice.set(this.actionLabel(actionType));
      const managerId = this.selectedManagerId();
      if (managerId) {
        await this.loadDetails(managerId);
      }
      await this.loadSummaryOnly();
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.mutatingItemId.set(null);
    }
  }

  async copyContactText(card: ManagerControlConcreteItem): Promise<void> {
    const id = card.controlEntityId;
    const text = card.contactText?.trim();
    if (!id || !text) {
      this.notice.set('Текст для клиента не собран.');
      return;
    }
    if (await this.writeClipboard(text)) {
      this.preparedContactItemIds.update((ids) => new Set(ids).add(id));
      this.notice.set('Текст скопирован. Можно отправить клиенту в чат.');
    } else {
      this.error.set('Не удалось скопировать текст.');
    }
  }

  isContactTextCopied(card: ManagerControlConcreteItem): boolean {
    const id = card.controlEntityId;
    return Boolean(id && this.preparedContactItemIds().has(id));
  }

  isRisk(card: ManagerControlConcreteItem): boolean {
    return card.type === 'RISK';
  }

  canUpdateRisk(card: ManagerControlConcreteItem): boolean {
    return this.isRisk(card)
      && this.auth.hasAnyRealmRole(['ADMIN', 'OWNER'])
      && Boolean(card.entityId)
      && this.mutatingId() !== card.controlEntityId;
  }

  async resolveRisk(card: ManagerControlConcreteItem): Promise<void> {
    await this.updateRisk(card, 'VERIFIED', undefined, 'Риск подтвержден как проверенный.', 'RESOLVED');
  }

  async ignoreRisk(card: ManagerControlConcreteItem): Promise<void> {
    await this.updateRisk(card, 'FALSE_POSITIVE', undefined, 'Риск отмечен как ложное срабатывание.', 'RESOLVED');
  }

  async requestRiskExplanation(card: ManagerControlConcreteItem): Promise<void> {
    await this.updateRisk(card, 'EXPLANATION_REQUESTED', undefined, 'Запрос пояснения специалисту отправлен.', 'ACTION_TAKEN');
  }

  async confirmRiskViolation(card: ManagerControlConcreteItem): Promise<void> {
    await this.updateRisk(card, 'VIOLATION_CONFIRMED', 1, 'Нарушение зафиксировано.', 'ACTION_TAKEN');
  }

  async openLink(url?: string | null): Promise<void> {
    const target = (url ?? '').trim();
    if (!target || target === '#') {
      return;
    }
    const internal = this.mobileInternalUrl(target);
    if (internal) {
      await this.router.navigateByUrl(internal);
      return;
    }
    await this.externalLink.open(target);
  }

  private async updateRisk(
    card: ManagerControlConcreteItem,
    action: WorkerRiskResolutionAction,
    penaltyPoints: number | undefined,
    successMessage: string,
    controlAction: ManagerControlActionPayload['actionType']
  ): Promise<void> {
    const incidentId = card.entityId;
    const itemId = card.controlEntityId;
    if (!incidentId || !itemId || this.mutatingId() === itemId) {
      return;
    }
    const comment = this.commentText(card).trim() || null;
    this.mutatingId.set(itemId);
    this.error.set(null);
    try {
      await this.api.setManagerWorkerRiskIncidentResolution(incidentId, action, penaltyPoints, comment).toPromise();
      await this.api.actionManagerControlConcreteItem(itemId, { actionType: controlAction, comment }).toPromise();
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

  private async loadDetails(managerId: number, forceSync = false): Promise<void> {
    const detail = forceSync
      ? await this.api.syncManagerControlDetails(managerId).toPromise()
      : await this.api.getManagerControlDetails(managerId).toPromise();
    if (!forceSync && detail && this.needsDetailSync(detail)) {
      const syncedDetail = await this.api.syncManagerControlDetails(managerId).toPromise();
      this.applyDetail(syncedDetail ?? detail);
      return;
    }
    this.applyDetail(detail ?? null);
  }

  private applyDetail(detail: ManagerControlManagerDetail | null): void {
    this.detail.set(detail);
    this.preparedContactItemIds.set(new Set());
    if (!detail) {
      this.itemComments.set({});
      this.comments.set({});
      return;
    }
    this.itemComments.set(Object.fromEntries(detail.items.map((item) => [item.itemId, item.comment ?? ''])));
    this.comments.set(Object.fromEntries(
      detail.items
        .flatMap((item) => item.examples)
        .filter((example) => Boolean(example.controlEntityId))
        .map((example) => [example.controlEntityId as number, example.comment ?? ''])
    ));
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
    if (!this.isOwnerAdminView()) {
      return summary.managers[0]?.managerId ?? null;
    }
    const routeManagerId = this.routeManagerId();
    if (routeManagerId && summary.managers.some((manager) => manager.managerId === routeManagerId)) {
      return routeManagerId;
    }
    if (this.isSummaryPage()) {
      return null;
    }
    const selected = this.selectedManagerId();
    if (selected && summary.managers.some((manager) => manager.managerId === selected)) {
      return selected;
    }
    return summary.managers[0]?.managerId ?? null;
  }

  private routeManagerId(): number | null {
    const raw = this.route.snapshot.paramMap.get('managerId');
    const value = raw ? Number(raw) : null;
    return value && Number.isFinite(value) ? value : null;
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
        this.applyDetail(detail);
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

  private actionLabel(actionType: ManagerControlActionPayload['actionType']): string {
    switch (actionType) {
      case 'RESOLVED':
        return 'Пункт контроля закрыт.';
      case 'DEFERRED':
        return 'Пункт контроля отложен.';
      case 'ACKNOWLEDGED':
        return 'Пункт контроля принят.';
      default:
        return 'Пункт контроля взят в работу.';
    }
  }

  private mobileInternalUrl(url: string): string | null {
    const path = this.pathWithQuery(url);
    if (!path.startsWith('/')) {
      return null;
    }
    const orderMatch = path.match(/^\/(?:manager\/)?orders\/(\d+)\/(\d+)(?:[/?#]|$)/);
    if (orderMatch) {
      return `/tabs/orders/${orderMatch[1]}/${orderMatch[2]}`;
    }
    if (path.startsWith('/orders')) {
      return '/tabs/orders';
    }
    if (path.startsWith('/companies') || path.startsWith('/manager')) {
      return '/tabs/companies';
    }
    const invoiceId = this.urlParam(path, 'invoiceId');
    if (path.includes('common-billing') && invoiceId) {
      return `/tabs/common-billing/${invoiceId}`;
    }
    if (path.includes('common-billing')) {
      return '/tabs/common-billing';
    }
    if (path.startsWith('/admin/manager-control')) {
      const managerId = path.match(/\/admin\/manager-control\/(\d+)/)?.[1];
      return managerId ? `/tabs/control/${managerId}` : '/tabs/control';
    }
    return null;
  }

  private pathWithQuery(url: string): string {
    try {
      return new URL(url, 'https://mobile.local').pathname + new URL(url, 'https://mobile.local').search;
    } catch {
      return url;
    }
  }

  private urlParam(url: string | null | undefined, name: string): string | null {
    const raw = (url ?? '').trim();
    if (!raw) {
      return null;
    }
    try {
      return new URL(raw, 'https://mobile.local').searchParams.get(name);
    } catch {
      const query = raw.split('?')[1] ?? '';
      return new URLSearchParams(query).get(name);
    }
  }

  percent(value?: number | null): string {
    return `${this.number(value ?? 0)}%`;
  }

  private number(value?: number | null): string {
    return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 }).format(value ?? 0);
  }

  private async writeClipboard(value: string): Promise<boolean> {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
        return true;
      }
    } catch {
      // Fall back to textarea copy for Android WebView and restricted browsers.
    }
    return this.writeClipboardLegacy(value);
  }

  private writeClipboardLegacy(value: string): boolean {
    const textarea = document.createElement('textarea');
    textarea.value = value;
    textarea.setAttribute('readonly', 'true');
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    let copied = false;
    try {
      copied = document.execCommand('copy');
    } catch {
      copied = false;
    } finally {
      document.body.removeChild(textarea);
    }
    return copied;
  }

  private closeTransientState(): void {
    this.notice.set(null);
    this.error.set(null);
    this.preparedContactItemIds.set(new Set());
  }

  private errorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      return error.error?.message || error.error?.error || error.message || 'Не удалось выполнить действие.';
    }
    return error instanceof Error ? error.message : 'Не удалось выполнить действие.';
  }
}
