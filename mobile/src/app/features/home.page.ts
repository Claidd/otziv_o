import { Component, computed, OnDestroy, OnInit, signal, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, NavigationStart, ParamMap, Router, RouterLink } from '@angular/router';
import { IonContent, IonModal, ToastController } from '@ionic/angular/standalone';
import { firstValueFrom, Subscription } from 'rxjs';
import {
  AnalyticsResponse,
  CabinetProfile,
  ContractorPaymentSummary,
  DailyWorkProgress,
  DictionarySummary,
  DictionarySummaryItem,
  ManagerManualPaymentSettings,
  ManualPaymentTaskAccountingTargetOption,
  ManualPaymentTaskResponse,
  ManualPaymentTaskStatus,
  ManualPaymentType,
  ScoreResponse,
  ScoreUser,
  TeamMember,
  TeamResponse,
  WorkerNetworkViolationDetail,
  WorkerNetworkViolationStats,
  ApiService
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import {
  cabinetDailyBarChartFrom,
  cabinetPeriodTotalFrom,
  cabinetYearlyLineChartFrom,
  type CabinetBarChart,
  type CabinetLineChart,
  type YearlyLineChartOptions
} from '../shared/cabinet-chart.helpers';
import {
  MOBILE_ACTIONS,
  MOBILE_ROLE_LABELS,
  MOBILE_ROLES,
  MOBILE_SECTIONS,
  canUseAction,
  type MobileRoleSet
} from '../core/mobile-permissions';
import { MobileDictionariesComponent } from '../shared/mobile-dictionaries.component';
import { businessDateIso, millisecondsUntilNextBusinessDay } from '../shared/business-date';
import { MobileActionSheetComponent } from '../shared/mobile-action-sheet.component';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileStatusSliderComponent, type MobileStatusItem } from '../shared/mobile-status-slider.component';
import {
  ManagerReportReviewAccessService,
  type ManagerReportReviewAccessState
} from '../core/manager-report-review-access.service';
import { MobileContractorPaymentSummaryComponent } from '../shared/mobile-contractor-payment-summary.component';
import {
  shouldShowLegacyContractorMetrics
} from '../shared/contractor-payment-summary';
import {
  mobileManualTaskRecommendedTarget,
  mobileManualTaskSelectedTarget,
  mobileManualTaskTargetEffect,
  mobileManualTaskTargetForSnapshot,
  mobileManualTaskTargetValid
} from '../shared/manual-payment-task-target';
import { MobileManualPaymentTaskOperationKeyDraft } from '../shared/manual-payment-operation-key';
import { manualPaymentTaskWorklist } from '../shared/manual-payment-task-visibility';

type HomeSectionKey = 'profile' | 'analytics' | 'team' | 'score' | 'dictionaries';
type HomeTone = 'blue' | 'green' | 'teal' | 'violet' | 'yellow';
type MetricTone = 'green' | 'blue' | 'yellow' | 'red';
type TeamKey = 'managers' | 'marketologs' | 'workers' | 'operators';
type TeamProgressMode = 'day' | 'month';

type HomeSectionLink = {
  key: HomeSectionKey;
  title: string;
  subtitle: string;
  icon: string;
  tone: HomeTone;
  roles: MobileRoleSet;
};

type Row = {
  label: string;
  value: string;
  percent?: number | null;
};

const HOME_SECTIONS: HomeSectionLink[] = [
  { key: 'profile', title: 'Личный кабинет', subtitle: 'профиль и показатели', icon: 'dashboard', tone: 'blue', roles: [] },
  { key: 'analytics', title: 'Аналитика', subtitle: 'оборот, вознаграждения и графики', icon: 'analytics', tone: 'violet', roles: MOBILE_ROLES.ownerAdmin },
  { key: 'team', title: 'Моя команда', subtitle: 'сотрудники и показатели', icon: 'badge', tone: 'green', roles: MOBILE_ROLES.manager },
  { key: 'score', title: 'Рейтинг', subtitle: 'рабочие счетчики', icon: 'leaderboard', tone: 'teal', roles: MOBILE_ROLES.score },
  { key: 'dictionaries', title: 'Справочники', subtitle: 'настройки данных', icon: 'tune', tone: 'yellow', roles: MOBILE_ROLES.manager }
];

const TEAM_SECTIONS: Array<{ key: TeamKey; title: string; icon: string }> = [
  { key: 'managers', title: 'Менеджеры', icon: 'groups' },
  { key: 'marketologs', title: 'Маркетологи', icon: 'campaign' },
  { key: 'workers', title: 'Работники', icon: 'engineering' },
  { key: 'operators', title: 'Операторы', icon: 'support_agent' }
];

const DEFAULT_MANUAL_PAYMENT_TYPE: ManualPaymentType = 'MOBILE_BANK';
const DEFAULT_MANUAL_RECIPIENT_NAME = 'Сивохин И.И.';
const DEFAULT_MANUAL_PAYMENT_URL = 'https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR';
const DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL = 'Оплатить через Альфа-Банк';

@Component({
  selector: 'app-home',
  imports: [FormsModule, IonContent, IonModal, MobileActionSheetComponent, MobileContractorPaymentSummaryComponent, MobileDictionariesComponent, MobileHeaderComponent, MobileStatusSliderComponent, RouterLink],
  template: `
    <div class="ion-page">
      <app-mobile-header [title]="sectionTitle()" />

      <ion-content class="home-content" fullscreen [scrollY]="false">
        <main class="analytics-home">
          <app-mobile-status-slider
            [items]="navStatusItems()"
            [activeKey]="activeSection()"
            ariaLabel="Разделы главной"
            (select)="selectNavStatusItem($event)"
          />

          @if (activeSection() !== 'dictionaries') {
            <section class="home-toolbar" aria-label="Управление разделом">
              <div>
                <p class="eyebrow">{{ sectionKicker() }}</p>
                <h1>{{ sectionTitle() }}</h1>
              </div>

              <label class="date-control">
                @if (activeSection() === 'team' && teamProgressMode() === 'month') {
                  <input type="month" [ngModel]="selectedMonth()" (ngModelChange)="setTeamMonth($event)">
                } @else {
                  <input type="date" [ngModel]="selectedDate()" (ngModelChange)="setDate($event)">
                }
              </label>

              <button class="icon-button" type="button" (click)="reload(true)" [disabled]="loading()" aria-label="Обновить">
                <span class="material-icons-sharp">refresh</span>
              </button>
            </section>
          }

          @if (activeSection() === 'team') {
            <section class="period-row team-progress-row" aria-label="Период команды">
              <button type="button" [class.active]="teamProgressMode() === 'day'" (click)="setTeamProgressMode('day')">Сегодня</button>
              <button type="button" [class.active]="teamProgressMode() === 'month'" (click)="setTeamProgressMode('month')">Месяц</button>
            </section>
          }

          @if (activeSection() === 'analytics') {
            <section class="period-row" aria-label="Период аналитики">
              <button type="button" [class.active]="analyticsMode() === 'lastTwoYears'" (click)="setAnalyticsMode('lastTwoYears')">2 года</button>
              <button type="button" [class.active]="analyticsMode() === 'allTime'" (click)="setAnalyticsMode('allTime')">все</button>
              <label>
                <span>с</span>
                <input type="date" [ngModel]="periodFrom()" (ngModelChange)="setPeriodFrom($event)">
              </label>
              <label>
                <span>по</span>
                <input type="date" [ngModel]="periodTo()" (ngModelChange)="setPeriodTo($event)">
              </label>
            </section>
          }

          @if (error()) {
            <button class="inline-alert" type="button" (click)="reload(true)">
              <span class="material-icons-sharp">error</span>
              <span>{{ error() }}</span>
            </button>
          }

          <section class="home-panel">
            @switch (activeSection()) {
              @case ('profile') {
                <section class="profile-view">
                  @if (reportReview.state(); as audit) {
                    @if (audit.pending) {
                      <article
                        class="report-review-card"
                        [class.restricted]="audit.restricted"
                        aria-label="Проверка персонального аудита"
                      >
                        <span class="material-icons-sharp">{{ audit.restricted ? 'lock_clock' : 'fact_check' }}</span>
                        <div>
                          <p class="eyebrow">{{ audit.restricted ? 'ДОСТУП ОГРАНИЧЕН' : 'НУЖНА ПРОВЕРКА' }}</p>
                          <h3>Изучите персональный аудит в Telegram</h3>
                          <p>{{ audit.message || 'Откройте Telegram и нажмите «Изучить отчёт».' }}</p>
                          @if (audit.questionCount > 0) {
                            <div class="report-review-progress">
                              <span [style.width.%]="reportReviewProgress(audit)"></span>
                            </div>
                            <small>Ответы: {{ audit.answeredQuestionCount }} из {{ audit.questionCount }}</small>
                          }
                          @if (audit.restrictedFrom) {
                            <small>{{ audit.restricted ? 'Срок истёк' : 'Завершить до' }}: {{ reportReviewDeadline(audit.restrictedFrom) }}</small>
                          }
                        </div>
                      </article>
                    }
                  }

                  <article class="identity-card">
                    <div>
                      <p class="eyebrow">{{ greeting() }}</p>
                      <h2>{{ displayName() }}</h2>
                      <span>{{ loginName() }}</span>
                    </div>
                    <button type="button" class="role-pill" (click)="openSectionSheet()">{{ primaryRoleLabel() }}</button>
                  </article>

                  @if (showManualPaymentSettings()) {
                    <article class="manual-payment-card">
                      <header>
                        <span class="material-icons-sharp">phone_iphone</span>
                        <div>
                          <p class="eyebrow">PAYMENT PROFILE</p>
                          <h3>Ручная оплата</h3>
                          <small>{{ manualPaymentSettings()?.profileName || 'Платежный профиль' }} · новые ссылки возьмут эту схему</small>
                        </div>
                      </header>

                      <div class="manual-mode-toggle" aria-label="Способ ручной оплаты">
                        <button type="button" [class.active]="manualPaymentType() === 'MOBILE_BANK'" (click)="setManualPaymentType('MOBILE_BANK')">
                          <span class="material-icons-sharp">phone_iphone</span>
                          Телефон
                        </button>
                        <button type="button" [class.active]="manualPaymentType() === 'EXTERNAL_LINK'" (click)="setManualPaymentType('EXTERNAL_LINK')">
                          <span class="material-icons-sharp">link</span>
                          Ссылка
                        </button>
                      </div>

                      @if (manualPaymentType() === 'EXTERNAL_LINK') {
                        <label>
                          <span>Ссылка оплаты</span>
                          <input type="url" autocomplete="url" [ngModel]="manualPaymentUrl()" (ngModelChange)="setManualPaymentUrl($event)">
                        </label>
                        <label>
                          <span>Текст кнопки</span>
                          <input type="text" [ngModel]="manualPaymentButtonLabel()" (ngModelChange)="setManualPaymentButtonLabel($event)">
                        </label>
                        <label>
                          <span>Получатель</span>
                          <input type="text" autocomplete="name" [ngModel]="manualPaymentRecipient()" (ngModelChange)="setManualPaymentRecipient($event)">
                        </label>
                      } @else {
                        <label>
                          <span>Телефон</span>
                          <input type="text" autocomplete="tel" [ngModel]="manualPaymentPhone()" (ngModelChange)="setManualPaymentPhone($event)" placeholder="+7...">
                        </label>
                        <label>
                          <span>Получатель</span>
                          <input type="text" autocomplete="name" [ngModel]="manualPaymentRecipient()" (ngModelChange)="setManualPaymentRecipient($event)" placeholder="Имя в банке">
                        </label>
                      }

                      @if (manualPaymentMessage()) {
                        <small class="manual-payment-message">{{ manualPaymentMessage() }}</small>
                      }

                      <button
                        class="manual-payment-save"
                        type="button"
                        (click)="saveManualPaymentSettings()"
                        [disabled]="manualPaymentSaving() || !manualPaymentChanged()"
                      >
                        <span class="material-icons-sharp">{{ manualPaymentSaving() ? 'hourglass_top' : 'save' }}</span>
                        {{ manualPaymentSaving() ? 'Сохраняю' : 'Сохранить реквизиты' }}
                      </button>
                    </article>
                  } @else if (manualPaymentLoading()) {
                    <article class="manual-payment-card manual-payment-card--loading">
                      <header>
                        <span class="material-icons-sharp">hourglass_top</span>
                        <div>
                          <p class="eyebrow">PAYMENT PROFILE</p>
                          <h3>Проверяю режим оплаты</h3>
                        </div>
                      </header>
                    </article>
                  }

                  @if (showManualPaymentTasks()) {
                    <article class="manual-payment-card manual-task-card">
                      <header>
                        <span class="material-icons-sharp">playlist_add_check</span>
                        <div>
                          <p class="eyebrow">PAYMENT TASKS</p>
                          <h3>Ручные задания</h3>
                          <small>Приоритетнее общего лимита профиля</small>
                        </div>
                      </header>

                      <div class="manual-mode-toggle" aria-label="Способ задания">
                        <button type="button" [class.active]="manualTaskPaymentType() === 'MOBILE_BANK'" (click)="setManualTaskPaymentType('MOBILE_BANK')">
                          <span class="material-icons-sharp">phone_iphone</span>
                          Телефон
                        </button>
                        <button type="button" [class.active]="manualTaskPaymentType() === 'EXTERNAL_LINK'" (click)="setManualTaskPaymentType('EXTERNAL_LINK')">
                          <span class="material-icons-sharp">link</span>
                          Ссылка
                        </button>
                      </div>

                      @if (manualTaskPaymentType() === 'EXTERNAL_LINK') {
                        <label><span>Ссылка оплаты</span><input type="url" [ngModel]="manualTaskPaymentUrl()" (ngModelChange)="setManualTaskPaymentUrl($event)"></label>
                        <label><span>Текст кнопки</span><input [ngModel]="manualTaskPaymentButtonLabel()" (ngModelChange)="setManualTaskPaymentButtonLabel($event)"></label>
                        <label><span>Получатель в банке</span><input [ngModel]="manualTaskRecipient()" (ngModelChange)="setManualTaskRecipient($event)"></label>
                      } @else {
                        <label><span>Телефон</span><input autocomplete="tel" [ngModel]="manualTaskPhone()" (ngModelChange)="setManualTaskPhone($event)" placeholder="+7..."></label>
                        <label><span>Получатель в банке</span><input [ngModel]="manualTaskRecipient()" (ngModelChange)="setManualTaskRecipient($event)"></label>
                      }
                      <label><span>Цель, руб.</span><input type="number" min="1" step="100" [ngModel]="manualTaskAmountRubles()" (ngModelChange)="setManualTaskAmount($event)"></label>
                      <label>
                        <span>Кому учитывать оплату</span>
                        <select [ngModel]="manualTaskAccountingTargetKey()" (ngModelChange)="setManualTaskAccountingTarget($event)" [disabled]="manualTaskAccountingTargetsLoading() || !manualTaskAmountRubles()">
                          <option value="">Выберите получателя</option>
                          @for (target of manualTaskAccountingTargets(); track target.key) {
                            <option [value]="target.key" [disabled]="!target.enabled || target.kind === 'UNRESOLVED'">{{ target.label }}</option>
                          }
                        </select>
                      </label>
                      @if (manualTaskAccountingTargetsLoading()) {
                        <small class="manual-task-accounting-note">Проверяем лимиты и резервы…</small>
                      } @else if (manualTaskAccountingTargetError()) {
                        <small class="manual-task-accounting-warning" role="alert">{{ manualTaskAccountingTargetError() }}</small>
                      } @else if (selectedManualTaskAccountingTarget(); as target) {
                        <div class="manual-task-accounting-preview">
                          <strong>{{ target.label }}</strong>
                          <small>{{ manualTaskTargetEffect(target) }}</small>
                          @if (target.currentAvailableKopecks != null) {
                            <small>Доступно сейчас: {{ formatKopecks(target.currentAvailableKopecks) }}</small>
                          }
                          @if ((target.projectedOverrunKopecks || 0) > 0) {
                            <small class="manual-task-accounting-warning">Превышение {{ formatKopecks(target.projectedOverrunKopecks) }} будет записано как аномалия. Сумма не уменьшится и не уйдёт владельцу.</small>
                            <label class="manual-task-accounting-ack">
                              <input type="checkbox" [ngModel]="manualTaskAccountingTargetAcknowledged()" (ngModelChange)="setManualTaskAccountingTargetAcknowledged($event)">
                              <span>Подтверждаю создание задания с превышением.</span>
                            </label>
                          }
                        </div>
                      }
                      <label><span>Комментарий</span><input [ngModel]="manualTaskComment()" (ngModelChange)="setManualTaskComment($event)"></label>

                      @if (manualTaskMessage()) {
                        <small class="manual-payment-message">{{ manualTaskMessage() }}</small>
                      }

                      <button class="manual-payment-save" type="button" (click)="createManualPaymentTask()" [disabled]="!canCreateManualTask()">
                        <span class="material-icons-sharp">{{ manualTaskSaving() ? 'hourglass_top' : 'add' }}</span>
                        {{ manualTaskSaving() ? 'Создаю' : 'Создать задание' }}
                      </button>
                      <button class="manual-payment-save secondary" type="button" (click)="resetManualPaymentTaskDraft()" [disabled]="manualTaskSaving()">
                        Новый черновик
                      </button>

                      <div class="manual-task-list">
                        @for (task of visibleManualPaymentTasks(); track task.id) {
                          <section class="manual-task-item" [class.inactive]="task.status !== 'ACTIVE'">
                            <header>
                              <div>
                                <strong>{{ manualTaskTitle(task) }}</strong>
                                <small>{{ manualTaskSubtitle(task) }}</small>
                                <small>Учёт: {{ task.accountingTargetResolved === false ? 'получатель не привязан' : (task.accountingTargetLabel || 'не указан') }}</small>
                              </div>
                              <b>{{ manualTaskStatusLabel(task.status) }}</b>
                            </header>
                            <small class="manual-task-accounting-note">
                              Оплачено {{ formatKopecks(task.confirmedAmountKopecks) }} · в брони {{ formatKopecks(task.pendingAmountKopecks) }} · свободно {{ formatKopecks(task.remainingAmountKopecks) }}
                            </small>
                            @if (task.accountingTargetResolved === false) {
                              <small class="manual-task-accounting-warning" role="alert">Новые счета заблокированы до привязки получателя.</small>
                            }
                            @if ((task.targetProjectedOverrunKopecks || 0) > 0) {
                              <small class="manual-task-accounting-warning" role="alert">Нужна сверка: превышение лимита {{ formatKopecks(task.targetProjectedOverrunKopecks) }}.</small>
                            }
                            @if (manualTaskEditingId() === task.id) {
                              <div class="manual-task-edit-form">
                                <div class="manual-mode-toggle" aria-label="Способ задания">
                                  <button type="button" [class.active]="manualTaskEditPaymentType() === 'MOBILE_BANK'" (click)="setManualTaskEditPaymentType('MOBILE_BANK')">
                                    <span class="material-icons-sharp">phone_iphone</span>
                                    телефон
                                  </button>
                                  <button type="button" [class.active]="manualTaskEditPaymentType() === 'EXTERNAL_LINK'" (click)="setManualTaskEditPaymentType('EXTERNAL_LINK')">
                                    <span class="material-icons-sharp">link</span>
                                    ссылка
                                  </button>
                                </div>
                                @if (manualTaskEditPaymentType() === 'EXTERNAL_LINK') {
                                  <label><span>Ссылка оплаты</span><input type="url" [ngModel]="manualTaskEditPaymentUrl()" (ngModelChange)="setManualTaskEditPaymentUrl($event)"></label>
                                  <label><span>Текст кнопки</span><input [ngModel]="manualTaskEditPaymentButtonLabel()" (ngModelChange)="setManualTaskEditPaymentButtonLabel($event)"></label>
                                  <label><span>Получатель в банке</span><input [ngModel]="manualTaskEditRecipient()" (ngModelChange)="setManualTaskEditRecipient($event)"></label>
                                } @else {
                                  <label><span>Телефон</span><input autocomplete="tel" [ngModel]="manualTaskEditPhone()" (ngModelChange)="setManualTaskEditPhone($event)" placeholder="+7..."></label>
                                  <label><span>Получатель в банке</span><input [ngModel]="manualTaskEditRecipient()" (ngModelChange)="setManualTaskEditRecipient($event)"></label>
                                }
                                <label><span>Цель, руб.</span><input type="number" min="1" step="100" [ngModel]="manualTaskEditAmountRubles()" (ngModelChange)="setManualTaskEditAmount($event)"></label>
                                <label>
                                  <span>Кому учитывать оплату</span>
                                  <select [ngModel]="manualTaskEditAccountingTargetKey()" (ngModelChange)="setManualTaskEditAccountingTarget($event)" [disabled]="manualTaskEditAccountingTargetsLoading()">
                                    <option value="">Выберите получателя</option>
                                    @for (target of manualTaskEditAccountingTargets(); track target.key) {
                                      <option [value]="target.key" [disabled]="!target.enabled || target.kind === 'UNRESOLVED'">{{ target.label }}</option>
                                    }
                                  </select>
                                </label>
                                @if (manualTaskEditAccountingTargetsLoading()) {
                                  <small class="manual-task-accounting-note">Пересчитываем лимит…</small>
                                } @else if (manualTaskEditAccountingTargetError()) {
                                  <small class="manual-task-accounting-warning" role="alert">{{ manualTaskEditAccountingTargetError() }}</small>
                                } @else if (selectedManualTaskEditAccountingTarget(); as target) {
                                  <div class="manual-task-accounting-preview">
                                    <strong>{{ target.label }}</strong>
                                    <small>{{ manualTaskTargetEffect(target) }}</small>
                                    @if ((target.projectedOverrunKopecks || 0) > 0) {
                                      <small class="manual-task-accounting-warning">Превышение {{ formatKopecks(target.projectedOverrunKopecks) }} будет сохранено как аномалия.</small>
                                      <label class="manual-task-accounting-ack">
                                        <input type="checkbox" [ngModel]="manualTaskEditAccountingTargetAcknowledged()" (ngModelChange)="setManualTaskEditAccountingTargetAcknowledged($event)">
                                        <span>Подтверждаю сохранение с превышением.</span>
                                      </label>
                                    }
                                  </div>
                                }
                                <label><span>Комментарий</span><input [ngModel]="manualTaskEditComment()" (ngModelChange)="setManualTaskEditComment($event)"></label>
                              </div>
                              <footer>
                                <small>занято {{ formatKopecks(task.reservedAmountKopecks) }}</small>
                                <button type="button" (click)="saveManualTaskEdit(task)" [disabled]="!canSaveManualTaskEdit(task)">сохранить</button>
                                <button type="button" class="danger" (click)="cancelManualTaskEdit()" [disabled]="manualTaskMutatingId() === task.id">закрыть</button>
                              </footer>
                            } @else {
                              <div class="manual-task-progress">
                                <span [style.width.%]="manualTaskProgressPercent(task)"></span>
                              </div>
                              <footer>
                                <small>{{ formatKopecks(task.reservedAmountKopecks) }} из {{ formatKopecks(task.targetAmountKopecks) }}</small>
                                @if (task.status !== 'COMPLETED' && task.status !== 'CANCELED') {
                                  <button type="button" (click)="startManualTaskEdit(task)" [disabled]="manualTaskMutatingId() === task.id">ред.</button>
                                }
                                @if (task.status === 'ACTIVE') {
                                  <button type="button" (click)="updateManualTaskStatus(task, 'PAUSED')" [disabled]="manualTaskMutatingId() === task.id">пауза</button>
                                } @else if (task.status === 'PAUSED') {
                                  <button type="button" (click)="updateManualTaskStatus(task, 'ACTIVE')" [disabled]="manualTaskMutatingId() === task.id">вкл</button>
                                }
                                @if (task.status !== 'COMPLETED' && task.status !== 'CANCELED') {
                                  <button type="button" class="danger" (click)="updateManualTaskStatus(task, 'CANCELED')" [disabled]="manualTaskMutatingId() === task.id">отмена</button>
                                }
                              </footer>
                            }
                          </section>
                        } @empty {
                          <div class="manual-task-empty">
                            <span class="material-icons-sharp">playlist_add</span>
                            <strong>{{ manualTaskLoading() ? 'Загружаю задания' : 'Заданий пока нет' }}</strong>
                          </div>
                        }
                      </div>
                    </article>
                  }

                  @if (showContractorPayments()) {
                    <app-mobile-contractor-payment-summary
                      [summaries]="contractorPayments()"
                      [loading]="contractorPaymentsLoading()"
                      [error]="contractorPaymentsError()"
                      (retry)="loadContractorPayments()"
                    />
                  }

                  <section class="metric-grid">
                    @for (row of profileRows(); track row.label) {
                      <article class="data-card">
                        <span>{{ row.label }}</span>
                        <strong>{{ row.value }}</strong>
                      </article>
                    }
                  </section>

                  <section class="profile-chart-grid" aria-label="Графики личного кабинета">
                    @if (profileSalaryDayChart(); as chart) {
                      <article class="mobile-chart-card mobile-chart-card--salary">
                        <div class="chart-head">
                          <h3>Вознаграждения по дням</h3>
                          <small>{{ profile()?.date || selectedDate() }}</small>
                        </div>
                        <div class="bar-chart-frame">
                          <div class="y-axis">
                            @for (tick of chart.ticks; track $index) {
                              <span>{{ tick }}</span>
                            }
                          </div>
                          <div class="bar-chart daily salary">
                            @for (point of chart.points; track point.label) {
                              <div class="bar-item" [title]="point.label + ': ' + moneyLabel(point.value)">
                                <span class="bar" [style.height.%]="point.height"></span>
                                <span class="bar-label">{{ point.label }}</span>
                              </div>
                            }
                          </div>
                        </div>
                      </article>
                    }

                    @if (profileSalaryMonthChart(); as chart) {
                      <article class="mobile-chart-card mobile-chart-card--salary">
                        <div class="chart-head">
                          <h3>Вознаграждения по месяцам</h3>
                          <small>все годы</small>
                        </div>
                        <div class="line-legend">
                          @for (series of chart.series; track series.label) {
                            <span><i [style.background]="series.color"></i>{{ series.label }}</span>
                          }
                        </div>
                        <div class="line-chart-frame">
                          <div class="y-axis line">
                            @for (tick of chart.ticks; track $index) {
                              <span>{{ tick }}</span>
                            }
                          </div>
                          <div class="line-chart-scroll">
                            <div class="line-chart-plot">
                              <svg class="line-chart" [attr.viewBox]="chart.viewBox" preserveAspectRatio="none" role="img" aria-label="Вознаграждения по месяцам">
                                @for (lineY of chart.gridLines; track lineY) {
                                  <line class="grid-line" [attr.x1]="chart.plotStart" [attr.x2]="chart.plotEnd" [attr.y1]="lineY" [attr.y2]="lineY"></line>
                                }
                                @for (series of chart.series; track series.label) {
                                  <polyline class="year-line" [attr.points]="series.points" [attr.stroke]="series.color"></polyline>
                                }
                              </svg>
                              @for (series of chart.series; track series.label) {
                                @for (point of series.pointsData; track point.label) {
                                  <span
                                    class="chart-dot"
                                    [style.left.%]="point.x"
                                    [style.top.%]="point.y"
                                    [style.background]="series.color"
                                    [title]="series.label + ' · ' + point.label + ': ' + moneyLabel(point.value)"
                                  ></span>
                                }
                              }
                            </div>
                            <div class="x-axis line">
                              @for (month of chart.months; track month) {
                                <span>{{ month }}</span>
                              }
                            </div>
                          </div>
                        </div>
                      </article>
                    }
                  </section>

                  <section class="profile-actions">
                    <a class="pill-button" routerLink="/tabs/profile">
                      <span class="material-icons-sharp">person</span>
                      профиль
                    </a>
                    @if (canPersonalManagerControl() && !workLocked()) {
                      <a class="pill-button" routerLink="/tabs/cabinet/manager-control">
                        <span class="material-icons-sharp">fact_check</span>
                        замечания
                      </a>
                    }
                    @if (auth.hasRealmRole('MANAGER') && !workLocked()) {
                      <a class="pill-button" routerLink="/tabs/training">
                        <span class="material-icons-sharp">school</span>
                        обучение
                      </a>
                    }
                    @if (!workLocked()) {
                      <button class="pill-button" type="button" (click)="openSectionSheet()">
                        <span class="material-icons-sharp">apps</span>
                        разделы
                      </button>
                    }
                    <button class="pill-button danger" type="button" (click)="logout()">
                      <span class="material-icons-sharp">logout</span>
                      выход
                    </button>
                  </section>
                </section>
              }

              @case ('analytics') {
                <section class="analytics-view">
                  <div class="section-caption">
                    <span>Период</span>
                    <strong>{{ analyticsPeriodLabel() }}</strong>
                  </div>

                  <section class="analytics-block analytics-block--pay">
                    <header class="analytics-block-title">
                      <span class="material-icons-sharp">payments</span>
                      <strong>Оборот</strong>
                      <small>{{ periodSubtitle() }}</small>
                    </header>

                    <section class="metric-grid analytics-metric-grid">
                      @for (row of analyticsPayRows(); track row.label) {
                        <article class="data-card tone-{{ metricTone(row) }}">
                          <span>{{ row.label }}</span>
                          <strong>{{ row.value }}</strong>
                          @if (row.percent !== null && row.percent !== undefined) {
                            <small class="metric-delta">{{ percentLabel(row.percent) }}</small>
                          }
                        </article>
                      }
                    </section>

                    @if (turnoverMonthChart(); as chart) {
                      <article class="mobile-chart-card">
                        <div class="chart-head">
                          <h3>Оборот по месяцам</h3>
                          <small>{{ periodSubtitle() }}</small>
                        </div>
                        <div class="line-legend">
                          @for (series of chart.series; track series.label) {
                            <span><i [style.background]="series.color"></i>{{ series.label }}</span>
                          }
                        </div>
                        <div class="line-chart-frame">
                          <div class="y-axis line">
                            @for (tick of chart.ticks; track $index) {
                              <span>{{ tick }}</span>
                            }
                          </div>
                          <div class="line-chart-scroll">
                            <div class="line-chart-plot">
                              <svg class="line-chart" [attr.viewBox]="chart.viewBox" preserveAspectRatio="none" role="img" aria-label="Оборот по месяцам">
                                @for (lineY of chart.gridLines; track lineY) {
                                  <line class="grid-line" [attr.x1]="chart.plotStart" [attr.x2]="chart.plotEnd" [attr.y1]="lineY" [attr.y2]="lineY"></line>
                                }
                                @for (series of chart.series; track series.label) {
                                  <polyline class="year-line" [attr.points]="series.points" [attr.stroke]="series.color"></polyline>
                                }
                              </svg>
                              @for (series of chart.series; track series.label) {
                                @for (point of series.pointsData; track point.label) {
                                  <span
                                    class="chart-dot"
                                    [style.left.%]="point.x"
                                    [style.top.%]="point.y"
                                    [style.background]="series.color"
                                    [title]="series.label + ' · ' + point.label + ': ' + moneyLabel(point.value)"
                                  ></span>
                                }
                              }
                            </div>
                            <div class="x-axis line">
                              @for (month of chart.months; track month) {
                                <span>{{ month }}</span>
                              }
                            </div>
                          </div>
                        </div>
                      </article>
                    }

                    @if (turnoverDayChart(); as chart) {
                      <article class="mobile-chart-card">
                        <div class="chart-head">
                          <h3>Оборот по дням</h3>
                          <small>{{ analytics()?.date || selectedDate() }}</small>
                        </div>
                        <div class="bar-chart-frame">
                          <div class="y-axis">
                            @for (tick of chart.ticks; track $index) {
                              <span>{{ tick }}</span>
                            }
                          </div>
                          <div class="bar-chart daily">
                            @for (point of chart.points; track point.label) {
                              <div class="bar-item" [title]="point.label + ': ' + moneyLabel(point.value)">
                                <span class="bar" [style.height.%]="point.height"></span>
                                <span class="bar-label">{{ point.label }}</span>
                              </div>
                            }
                          </div>
                        </div>
                      </article>
                    }

                    <section class="metric-grid analytics-metric-grid">
                      @for (row of analyticsPayOrderRows(); track row.label) {
                        <article class="data-card tone-{{ metricTone(row) }}">
                          <span>{{ row.label }}</span>
                          <strong>{{ row.value }}</strong>
                          @if (row.percent !== null && row.percent !== undefined) {
                            <small class="metric-delta">{{ percentLabel(row.percent) }}</small>
                          }
                        </article>
                      }
                    </section>
                  </section>

                  <section class="analytics-block analytics-block--salary">
                    <header class="analytics-block-title">
                      <span class="material-icons-sharp">account_balance_wallet</span>
                      <strong>Вознаграждения</strong>
                      <small>{{ periodSubtitle() }}</small>
                    </header>

                    <section class="metric-grid analytics-metric-grid">
                      @for (row of analyticsSalaryRows(); track row.label) {
                        <article class="data-card tone-{{ metricTone(row) }}">
                          <span>{{ row.label }}</span>
                          <strong>{{ row.value }}</strong>
                          @if (row.percent !== null && row.percent !== undefined) {
                            <small class="metric-delta">{{ percentLabel(row.percent) }}</small>
                          }
                        </article>
                      }
                    </section>

                    @if (salaryMonthChart(); as chart) {
                      <article class="mobile-chart-card mobile-chart-card--salary">
                        <div class="chart-head">
                          <h3>Вознаграждения по месяцам</h3>
                          <small>{{ periodSubtitle() }}</small>
                        </div>
                        <div class="line-legend">
                          @for (series of chart.series; track series.label) {
                            <span><i [style.background]="series.color"></i>{{ series.label }}</span>
                          }
                        </div>
                        <div class="line-chart-frame">
                          <div class="y-axis line">
                            @for (tick of chart.ticks; track $index) {
                              <span>{{ tick }}</span>
                            }
                          </div>
                          <div class="line-chart-scroll">
                            <div class="line-chart-plot">
                              <svg class="line-chart" [attr.viewBox]="chart.viewBox" preserveAspectRatio="none" role="img" aria-label="Вознаграждения по месяцам">
                                @for (lineY of chart.gridLines; track lineY) {
                                  <line class="grid-line" [attr.x1]="chart.plotStart" [attr.x2]="chart.plotEnd" [attr.y1]="lineY" [attr.y2]="lineY"></line>
                                }
                                @for (series of chart.series; track series.label) {
                                  <polyline class="year-line" [attr.points]="series.points" [attr.stroke]="series.color"></polyline>
                                }
                              </svg>
                              @for (series of chart.series; track series.label) {
                                @for (point of series.pointsData; track point.label) {
                                  <span
                                    class="chart-dot"
                                    [style.left.%]="point.x"
                                    [style.top.%]="point.y"
                                    [style.background]="series.color"
                                    [title]="series.label + ' · ' + point.label + ': ' + moneyLabel(point.value)"
                                  ></span>
                                }
                              }
                            </div>
                            <div class="x-axis line">
                              @for (month of chart.months; track month) {
                                <span>{{ month }}</span>
                              }
                            </div>
                          </div>
                        </div>
                      </article>
                    }

                    @if (salaryDayChart(); as chart) {
                      <article class="mobile-chart-card mobile-chart-card--salary">
                        <div class="chart-head">
                          <h3>Вознаграждения по дням</h3>
                          <small>{{ analytics()?.date || selectedDate() }}</small>
                        </div>
                        <div class="bar-chart-frame">
                          <div class="y-axis">
                            @for (tick of chart.ticks; track $index) {
                              <span>{{ tick }}</span>
                            }
                          </div>
                          <div class="bar-chart daily salary">
                            @for (point of chart.points; track point.label) {
                              <div class="bar-item" [title]="point.label + ': ' + moneyLabel(point.value)">
                                <span class="bar" [style.height.%]="point.height"></span>
                                <span class="bar-label">{{ point.label }}</span>
                              </div>
                            }
                          </div>
                        </div>
                      </article>
                    }

                    <section class="metric-grid analytics-metric-grid">
                      @for (row of analyticsSalaryOrderRows(); track row.label) {
                        <article class="data-card tone-{{ metricTone(row) }}">
                          <span>{{ row.label }}</span>
                          <strong>{{ row.value }}</strong>
                          @if (row.percent !== null && row.percent !== undefined) {
                            <small class="metric-delta">{{ percentLabel(row.percent) }}</small>
                          }
                        </article>
                      }
                    </section>
                  </section>
                </section>
              }

              @case ('team') {
                <section class="people-view">
                  @for (section of teamSections; track section.key) {
                    <article class="group-block">
                      <header>
                        <span class="material-icons-sharp">{{ section.icon }}</span>
                        <strong>{{ section.title }}</strong>
                        <small>{{ members(section.key).length }}</small>
                      </header>

                      <div class="people-strip">
                        @for (member of members(section.key); track member.userId) {
                          <article class="person-card">
                            @if (memberProgress(member); as progress) {
                              @if (progress.visible) {
                                <span
                                  class="team-efficiency-badge score-{{ efficiencyTone(progress) }}"
                                  [title]="efficiencyTitle(progress)"
                                >{{ memberEfficiency(progress) }}</span>
                              }
                            }
                            <img [src]="imageUrl(member.imageId)" [alt]="member.fio || member.login">
                            <div>
                              <strong>{{ member.fio || member.login }}</strong>
                              <span>{{ member.login }}</span>
                            </div>
                            @if (memberProgress(member); as progress) {
                              @if (progress.visible) {
                                <section
                                  class="team-progress-strip"
                                  [class.complete]="progress.checked"
                                  [class.empty]="(progress.total || 0) <= 0"
                                  [title]="teamProgressTitle(progress)"
                                >
                                  <span>{{ teamProgressLabel() }}</span>
                                  <i><b [style.width.%]="safeProgressPercent(progress)"></b></i>
                                  <strong>{{ progress.completed || 0 }}/{{ progress.total || 0 }}</strong>
                                  <em>{{ safeProgressPercent(progress) }}%</em>
                                  @if (progress.checked) {
                                    <span class="material-icons-sharp">check_circle</span>
                                  }
                                </section>
                                <p class="team-progress-summary">{{ teamProgressSummary(progress) }}</p>
                              }
                            }
                            <dl>
                              @for (row of teamRows(section.key, member); track row.label) {
                                <div><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></div>
                              }
                            </dl>
                            @if (section.key === 'workers' && networkViolations(member); as violations) {
                              @if (violations.visible && violations.episodeCount > 0) {
                                <details class="network-violations" [class.critical]="violations.severity === 'CRITICAL'">
                                  <summary>
                                    <span class="material-icons-sharp">wifi_off</span>
                                    <strong>Нарушения сети: {{ violations.episodeCount }}</strong>
                                  </summary>
                                  <p>
                                    {{ violations.attemptCount }} попыток
                                    @if (teamProgressMode() === 'month') {
                                      · {{ violations.daysWithViolations }} дн. с нарушениями
                                    }
                                  </p>
                                  @for (detail of violations.details; track detail.firstSeenAt + detail.reason + detail.scope) {
                                    <article>
                                      <strong>{{ networkViolationReason(detail.reason) }}</strong>
                                      <small>{{ networkViolationTime(detail) }} · {{ networkViolationScope(detail.scope) }}</small>
                                      @if (detail.provider) {
                                        <small>{{ detail.provider }}</small>
                                      }
                                      <span>{{ detail.attemptCount }} попыток · {{ detail.blocked ? 'заблокировано' : 'режим аудита' }}</span>
                                    </article>
                                  }
                                </details>
                              }
                            }
                          </article>
                        } @empty {
                          <p class="empty-note">Нет данных.</p>
                        }
                      </div>
                    </article>
                  }
                </section>
              }

              @case ('score') {
                <section class="people-view">
                  @if (score() && !score()?.financeVisible) {
                    <p class="notice">Финансовые суммы скрыты для твоей роли. Показываем рабочие счетчики.</p>
                  }

                  @for (section of teamSections; track section.key) {
                    <article class="group-block">
                      <header>
                        <span class="material-icons-sharp">{{ section.icon }}</span>
                        <strong>{{ section.title }}</strong>
                        <small>{{ scoreUsers(section.key).length }}</small>
                      </header>

                      <div class="people-strip">
                        @for (user of scoreUsers(section.key); track scoreTrack(user)) {
                          <article class="person-card score-card">
                            <b>{{ $index + 1 }}</b>
                            <img [src]="imageUrl(user.imageId)" [alt]="user.fio">
                            <div>
                              <strong>{{ user.fio }}</strong>
                              <span>{{ user.role }}</span>
                            </div>
                            <dl>
                              @for (row of scoreRows(section.key, user); track row.label) {
                                <div><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></div>
                              }
                            </dl>
                          </article>
                        } @empty {
                          <p class="empty-note">Нет данных.</p>
                        }
                      </div>
                    </article>
                  }
                </section>
              }

              @case ('dictionaries') {
                <app-mobile-dictionaries [adminMode]="canManageAllDictionaries()" />
              }
            }
          </section>
        </main>
      </ion-content>

      <ion-modal #sectionModal class="sheet-modal home-section-sheet" [isOpen]="sectionSheetOpen()" (didDismiss)="onSectionSheetDismissed()">
        <ng-template>
          <ion-content>
            <app-mobile-action-sheet kicker="Главная" title="Выберите раздел" (close)="closeSectionSheet()">
              <div class="section-choice-list">
                @for (link of navLinks(); track link.key) {
                  <button type="button" [class.active]="activeSection() === link.key" (click)="selectSection(link.key)">
                    <span class="material-icons-sharp">{{ link.icon }}</span>
                    <div>
                      <strong>{{ link.title }}</strong>
                      <small>{{ link.subtitle }}</small>
                      </div>
                    </button>
                  }
                  @if (canSeeTbank()) {
                    <button type="button" (click)="openTbankSection()">
                      <span class="material-icons-sharp">account_balance_wallet</span>
                      <div>
                        <strong>Банк</strong>
                        <small>платежи и профили</small>
                      </div>
                    </button>
                  }
                  @if (canPersonalManagerControl()) {
                    <button type="button" (click)="openManagerRemarks()">
                      <span class="material-icons-sharp">fact_check</span>
                      <div>
                        <strong>Замечания</strong>
                        <small>личный контроль дня</small>
                      </div>
                    </button>
                  }
                  @if (canSeeManagerControl()) {
                    <button type="button" (click)="openManagerControlSection()">
                      <span class="material-icons-sharp">rule</span>
                      <div>
                        <strong>Контроль</strong>
                        <small>замечания менеджеров</small>
                      </div>
                    </button>
                  }
                  @if (canSeeAdminUsers()) {
                    <button type="button" (click)="openAdminUsersSection()">
                      <span class="material-icons-sharp">admin_panel_settings</span>
                      <div>
                        <strong>Пользователи</strong>
                        <small>доступы и назначения</small>
                      </div>
                    </button>
                  }
                </div>
              </app-mobile-action-sheet>
            </ion-content>
        </ng-template>
      </ion-modal>
    </div>
  `,
  styles: [`
    .home-content {
      --background: var(--otziv-background);
      --overflow: hidden;
    }

    .analytics-home {
      display: flex;
      height: 100%;
      max-width: 44rem;
      width: 100%;
      min-height: 0;
      min-width: 0;
      margin: 0 auto;
      overflow: hidden;
      flex-direction: column;
      gap: 0.52rem;
      padding: 0.75rem 0.75rem calc(0.7rem + env(safe-area-inset-bottom));
    }

    .home-section-scroll {
      display: flex;
      gap: 0.5rem;
      flex: 0 0 auto;
      min-width: 0;
      margin-inline: -0.15rem;
      overflow-x: auto;
      padding: 0 0.15rem 0.08rem;
      scrollbar-width: none;
    }

    .home-section-scroll::-webkit-scrollbar,
    .people-strip::-webkit-scrollbar {
      display: none;
    }

    .home-section-scroll .metric-tile {
      flex: 0 0 7.3rem;
      min-height: 3.45rem;
      border: 1px solid var(--status-menu-border, rgba(108, 155, 207, 0.28));
      background: linear-gradient(155deg, var(--status-menu-surface, var(--otziv-tone-walk-surface)) 0%, var(--otziv-white) 82%);
      box-shadow: 0 0.7rem 1.45rem rgba(132, 139, 200, 0.09);
    }

    .home-section-scroll .metric-tile .material-icons-sharp {
      grid-row: span 2;
      font-size: 1.22rem;
    }

    .home-section-scroll .metric-tile strong {
      align-self: end;
      min-width: 0;
      overflow: hidden;
      font-size: 1.02rem;
      line-height: 1;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .home-section-scroll .metric-tile small {
      align-self: start;
      min-width: 0;
      overflow: hidden;
      font-size: 0.54rem;
      line-height: 1;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .tone-blue { --status-menu-border: rgba(108, 155, 207, 0.28); --status-menu-surface: #f6faff; }
    .tone-green { --status-menu-border: var(--otziv-tone-success-border); --status-menu-surface: var(--otziv-tone-success-surface); }
    .tone-teal { --status-menu-border: rgba(47, 159, 149, 0.28); --status-menu-surface: #f4fffd; }
    .tone-violet { --status-menu-border: var(--otziv-tone-publication-border); --status-menu-surface: var(--otziv-tone-publication-surface); }
    .tone-yellow { --status-menu-border: var(--otziv-tone-wait-border); --status-menu-surface: var(--otziv-tone-wait-surface); }

    .home-toolbar,
    .period-row,
    .identity-card,
    .manual-payment-card,
    .manual-task-item,
    .data-card,
    .group-block,
    .notice,
    .inline-alert {
      border: 1px solid rgba(103, 116, 131, 0.16);
      background: var(--otziv-white);
      box-shadow: 0 0.8rem 1.6rem rgba(132, 139, 200, 0.1);
      box-sizing: border-box;
      min-width: 0;
      max-width: 100%;
    }

    .home-toolbar {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 8.6rem) 2.35rem;
      align-items: center;
      gap: 0.45rem;
      flex: 0 0 auto;
      border-radius: 1rem;
      padding: 0.6rem 0.65rem;
    }

    .eyebrow {
      margin: 0;
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 900;
      text-transform: uppercase;
    }

    h1,
    h2 {
      margin: 0.12rem 0 0;
      overflow: hidden;
      color: var(--otziv-dark);
      line-height: 1.05;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    h1 { font-size: 1.18rem; }
    h2 { font-size: 1.38rem; }

    button,
    a,
    input {
      font: inherit;
      letter-spacing: 0;
    }

    .date-control input,
    .period-row input {
      width: 100%;
      min-width: 0;
      min-height: 2.25rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.78rem;
      padding: 0 0.5rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font-size: 0.72rem;
      font-weight: 900;
    }

    .date-control {
      min-width: 0;
    }

    .icon-button {
      display: grid;
      width: 2.35rem;
      height: 2.35rem;
      place-items: center;
      border: 0;
      border-radius: 0.78rem;
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }

    .period-row {
      display: grid;
      grid-template-columns: auto auto minmax(0, 1fr) minmax(0, 1fr);
      gap: 0.4rem;
      flex: 0 0 auto;
      overflow: hidden;
      border-radius: 1rem;
      padding: 0.48rem;
    }

    .period-row button,
    .period-row label {
      display: inline-flex;
      min-height: 2.15rem;
      align-items: center;
      justify-content: center;
      gap: 0.28rem;
      min-width: 0;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 999px;
      padding: 0 0.5rem;
      color: var(--otziv-info);
      background: var(--otziv-white);
      font-size: 0.64rem;
      font-weight: 900;
    }

    .period-row label input {
      width: 100%;
      min-width: 0;
      border: 0;
      padding: 0;
      background: transparent;
      font-size: 0.67rem;
      text-align: center;
    }

    .period-row button.active {
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }

    .team-progress-row {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .home-panel {
      flex: 1 1 0;
      min-height: 0;
      min-width: 0;
      max-width: 100%;
      overflow: hidden;
    }

    .profile-view,
    .analytics-view,
    .people-view {
      display: flex;
      height: 100%;
      min-height: 0;
      min-width: 0;
      max-width: 100%;
      overflow: hidden;
      flex-direction: column;
      gap: 0.65rem;
    }

    .analytics-view {
      overflow-x: hidden;
      overflow-y: auto;
      padding-bottom: 0.65rem;
    }

    .analytics-view .mobile-chart-card {
      gap: 0.48rem;
      padding: 0.58rem 0.42rem;
    }

    .profile-view {
      overflow-y: auto;
      padding-bottom: 0.35rem;
    }

    .profile-view > .identity-card { order: 10; }
    .profile-view > .report-review-card { order: 5; }
    .profile-view > .manual-payment-card { order: 15; }
    .profile-view > .metric-grid { order: 20; }
    .profile-view > .profile-chart-grid { order: 30; }
    .profile-view > .profile-actions { order: 90; }
    .profile-view > .manual-task-card { order: 100; }

    .people-view {
      overflow-y: auto;
      padding-bottom: 0.2rem;
    }

    .identity-card {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      border-radius: 1rem;
      padding: 0.82rem;
    }

    .report-review-card {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: start;
      gap: 0.68rem;
      flex: 0 0 auto;
      border: 1px solid rgba(231, 180, 52, 0.38);
      border-radius: 1rem;
      padding: 0.78rem;
      background: linear-gradient(155deg, #fffaf0 0%, var(--otziv-white) 100%);
      box-shadow: 0 0.8rem 1.45rem rgba(132, 139, 200, 0.1);
    }

    .report-review-card.restricted {
      border-color: rgba(237, 45, 91, 0.36);
      background: linear-gradient(155deg, #fff3f6 0%, var(--otziv-white) 100%);
    }

    .report-review-card > .material-icons-sharp {
      display: grid;
      width: 2.35rem;
      height: 2.35rem;
      place-items: center;
      border-radius: 0.78rem;
      color: #9b6d00;
      background: rgba(231, 180, 52, 0.16);
    }

    .report-review-card.restricted > .material-icons-sharp {
      color: var(--otziv-danger);
      background: rgba(237, 45, 91, 0.12);
    }

    .report-review-card h3,
    .report-review-card p {
      margin: 0;
    }

    .report-review-card h3 {
      margin-top: 0.1rem;
      color: var(--otziv-dark);
      font-size: 0.94rem;
      font-weight: 900;
      line-height: 1.22;
    }

    .report-review-card p:not(.eyebrow),
    .report-review-card small {
      display: block;
      margin-top: 0.28rem;
      color: var(--otziv-info);
      font-size: 0.67rem;
      font-weight: 800;
      line-height: 1.35;
    }

    .report-review-progress {
      height: 0.34rem;
      margin-top: 0.48rem;
      overflow: hidden;
      border-radius: 999px;
      background: rgba(103, 116, 131, 0.14);
    }

    .report-review-progress span {
      display: block;
      height: 100%;
      border-radius: inherit;
      background: linear-gradient(90deg, var(--otziv-primary), var(--otziv-success));
    }

    .manual-payment-card {
      display: grid;
      gap: 0.52rem;
      flex: 0 0 auto;
      border-color: rgba(27, 156, 133, 0.24);
      border-radius: 1rem;
      padding: 0.72rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, rgba(234, 250, 246, 0.96) 100%);
    }

    .manual-payment-card header {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.55rem;
      min-width: 0;
    }

    .manual-payment-card header > .material-icons-sharp {
      display: grid;
      width: 2.2rem;
      height: 2.2rem;
      place-items: center;
      border-radius: 0.78rem;
      color: var(--otziv-success);
      background: rgba(27, 156, 133, 0.14);
      font-size: 1.18rem;
    }

    .manual-payment-card h3,
    .manual-payment-card small {
      margin: 0;
      min-width: 0;
    }

    .manual-payment-card h3 {
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: 0.94rem;
      line-height: 1.08;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .manual-payment-card header small,
    .manual-payment-message {
      display: block;
      overflow: hidden;
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 850;
      line-height: 1.18;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .manual-payment-card label {
      display: grid;
      gap: 0.24rem;
      min-width: 0;
    }

    .manual-payment-card label span {
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 900;
    }

    .manual-payment-card input:not([type='checkbox']),
    .manual-payment-card select {
      width: 100%;
      min-width: 0;
      min-height: 2.25rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.78rem;
      padding: 0 0.72rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font-size: 0.76rem;
      font-weight: 900;
      box-sizing: border-box;
    }

    .manual-payment-save {
      display: inline-flex;
      min-height: 2.25rem;
      align-items: center;
      justify-content: center;
      gap: 0.32rem;
      border: 1px solid rgba(27, 156, 133, 0.32);
      border-radius: 999px;
      color: var(--otziv-success);
      background: var(--otziv-white);
      font-size: 0.72rem;
      font-weight: 900;
    }

    .manual-payment-save:disabled {
      opacity: 0.58;
    }

    .manual-payment-card--loading {
      border-color: rgba(108, 155, 207, 0.22);
      background: var(--otziv-white);
    }

    .manual-mode-toggle {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.38rem;
    }

    .manual-mode-toggle button,
    .manual-task-item footer button {
      display: inline-flex;
      min-height: 2.05rem;
      align-items: center;
      justify-content: center;
      gap: 0.25rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 999px;
      color: var(--otziv-info);
      background: var(--otziv-white);
      font-size: 0.64rem;
      font-weight: 900;
    }

    .manual-mode-toggle button.active {
      border-color: rgba(27, 156, 133, 0.32);
      color: var(--otziv-success);
      background: rgba(27, 156, 133, 0.1);
    }

    .manual-mode-toggle .material-icons-sharp {
      font-size: 1rem;
    }

    .manual-task-list {
      display: grid;
      gap: 0.45rem;
    }

    .manual-task-item {
      display: grid;
      gap: 0.42rem;
      border-radius: 0.82rem;
      padding: 0.55rem;
      background: rgba(255, 255, 255, 0.72);
    }

    .manual-task-item.inactive {
      opacity: 0.72;
    }

    .manual-task-item header,
    .manual-task-item footer {
      display: flex;
      min-width: 0;
      align-items: center;
      justify-content: space-between;
      gap: 0.42rem;
    }

    .manual-task-item header div {
      min-width: 0;
    }

    .manual-task-item strong,
    .manual-task-item small {
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .manual-task-item strong {
      color: var(--otziv-dark);
      font-size: 0.76rem;
    }

    .manual-task-item b {
      color: var(--otziv-success);
      font-size: 0.64rem;
      white-space: nowrap;
    }

    .manual-task-progress {
      overflow: hidden;
      height: 0.38rem;
      border-radius: 999px;
      background: rgba(103, 116, 131, 0.14);
    }

    .manual-task-progress span {
      display: block;
      height: 100%;
      border-radius: inherit;
      background: var(--otziv-success);
    }

    .manual-task-edit-form {
      display: grid;
      gap: 0.42rem;
    }

    .manual-task-accounting-preview {
      display: grid;
      gap: .3rem;
      border: 1px solid rgba(69, 158, 133, .25);
      border-radius: .75rem;
      padding: .7rem;
      background: rgba(69, 158, 133, .07);
    }

    .manual-task-accounting-note,
    .manual-task-accounting-preview small {
      color: var(--otziv-info);
      font-weight: 800;
    }

    .manual-task-accounting-warning {
      color: #a83f47 !important;
      font-weight: 900 !important;
    }

    .manual-payment-card .manual-task-accounting-ack {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: flex-start;
      gap: .5rem;
      color: #8f3b43;
    }

    .manual-task-accounting-ack input {
      width: 1.1rem;
      height: 1.1rem;
      min-height: 0;
      margin-top: .1rem;
    }

    .manual-task-edit-form label {
      display: grid;
      gap: 0.22rem;
    }

    .manual-task-edit-form label span {
      color: var(--otziv-info);
      font-size: 0.6rem;
      font-weight: 900;
    }

    .manual-task-edit-form input {
      width: 100%;
      min-width: 0;
      min-height: 2rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 0.7rem;
      padding: 0 0.6rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font-size: 0.72rem;
      font-weight: 900;
      box-sizing: border-box;
    }

    .manual-task-item footer {
      flex-wrap: wrap;
    }

    .manual-task-item footer small {
      flex: 1 1 8rem;
      color: var(--otziv-info);
      font-size: 0.61rem;
      font-weight: 850;
    }

    .manual-task-item footer button {
      min-height: 1.75rem;
      padding: 0 0.58rem;
    }

    .manual-task-item footer .danger {
      color: var(--otziv-danger);
    }

    .manual-task-empty {
      display: grid;
      place-items: center;
      gap: 0.35rem;
      min-height: 4rem;
      color: var(--otziv-info);
      text-align: center;
      font-size: 0.72rem;
      font-weight: 900;
    }

    .identity-card span,
    .data-card span,
    .section-caption span,
    dt {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 900;
    }

    .role-pill,
    .pill-button {
      display: inline-flex;
      min-height: 2.25rem;
      align-items: center;
      justify-content: center;
      gap: 0.3rem;
      border: 1px solid rgba(108, 155, 207, 0.24);
      border-radius: 999px;
      padding: 0 0.75rem;
      color: var(--otziv-primary);
      background: var(--otziv-light);
      font-size: 0.72rem;
      font-weight: 900;
      text-decoration: none;
    }

    .pill-button.danger {
      color: var(--otziv-danger);
      background: rgba(255, 0, 96, 0.08);
    }

    .metric-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.55rem;
      min-width: 0;
      max-width: 100%;
    }

    .profile-chart-grid {
      display: grid;
      gap: 0.65rem;
      min-width: 0;
      max-width: 100%;
    }

    .data-card {
      display: grid;
      position: relative;
      gap: 0.25rem;
      min-height: 4.6rem;
      align-content: center;
      overflow: hidden;
      border-radius: 1rem;
      padding: 0.75rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, #f6faff 100%);
    }

    .data-card.tone-green {
      border-color: rgba(27, 156, 133, 0.22);
      background: linear-gradient(155deg, var(--otziv-white) 0%, rgba(225, 247, 239, 0.92) 100%);
    }

    .data-card.tone-blue {
      border-color: rgba(108, 155, 207, 0.24);
      background: linear-gradient(155deg, var(--otziv-white) 0%, #eef6ff 100%);
    }

    .data-card.tone-yellow {
      border-color: rgba(198, 142, 30, 0.28);
      background: linear-gradient(155deg, var(--otziv-white) 0%, #fff7dc 100%);
    }

    .data-card.tone-red {
      border-color: rgba(234, 51, 98, 0.24);
      background: linear-gradient(155deg, var(--otziv-white) 0%, #fff0f5 100%);
    }

    .data-card strong {
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: 1.1rem;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .metric-delta {
      position: absolute;
      top: 0.48rem;
      right: 0.55rem;
      border-radius: 999px;
      padding: 0.12rem 0.34rem;
      color: var(--otziv-info);
      background: rgba(255, 255, 255, 0.72);
      font-size: 0.54rem;
      font-weight: 1000;
      line-height: 1;
    }

    .analytics-block {
      display: grid;
      gap: 0.6rem;
      flex: 0 0 auto;
      min-width: 0;
      max-width: 100%;
    }

    .analytics-block-title {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      align-items: center;
      gap: 0.4rem;
      color: var(--otziv-dark);
    }

    .analytics-block-title .material-icons-sharp {
      display: grid;
      width: 1.9rem;
      height: 1.9rem;
      place-items: center;
      border-radius: 0.7rem;
      color: var(--otziv-primary);
      background: var(--otziv-light);
      font-size: 1.05rem;
    }

    .analytics-block--salary .analytics-block-title .material-icons-sharp {
      color: #7b5fc1;
      background: rgba(154, 123, 217, 0.14);
    }

    .analytics-block-title small {
      color: var(--otziv-info);
      font-size: 0.66rem;
      font-weight: 800;
    }

    .analytics-metric-grid .data-card {
      min-height: 4.35rem;
      padding: 0.7rem;
    }

    .mobile-chart-card {
      display: grid;
      gap: 0.65rem;
      min-width: 0;
      max-width: 100%;
      overflow: hidden;
      border: 1px solid rgba(103, 116, 131, 0.15);
      border-radius: 1rem;
      padding: 0.75rem 0.5rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, #f8fbff 100%);
      box-shadow: 0 0.8rem 1.6rem rgba(132, 139, 200, 0.1);
    }

    .mobile-chart-card--salary {
      background: linear-gradient(155deg, var(--otziv-white) 0%, rgba(246, 241, 255, 0.88) 100%);
    }

    .chart-head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 0.6rem;
    }

    .chart-head h3 {
      margin: 0;
      color: var(--otziv-dark);
      font-size: 1rem;
      line-height: 1.08;
    }

    .chart-head small,
    .line-legend,
    .y-axis,
    .x-axis.line {
      color: var(--otziv-info);
    }

    .line-legend {
      display: flex;
      gap: 0.38rem;
      overflow-x: auto;
      padding-inline: 0.2rem;
      font-size: 0.58rem;
      font-weight: 900;
    }

    .line-legend span {
      display: inline-flex;
      flex: 0 0 auto;
      align-items: center;
      gap: 0.2rem;
      white-space: nowrap;
    }

    .line-legend i {
      display: inline-block;
      width: 0.62rem;
      height: 0.62rem;
      border-radius: 50%;
    }

    .line-chart-frame,
    .bar-chart-frame {
      position: relative;
      min-width: 0;
      max-width: 100%;
    }

    .y-axis {
      display: flex;
      position: absolute;
      z-index: 2;
      left: 0.3rem;
      width: 2.15rem;
      pointer-events: none;
      flex-direction: column;
      justify-content: space-between;
      font-size: 0.62rem;
      font-weight: 900;
      line-height: 1;
      text-align: right;
    }

    .line-chart-frame .y-axis {
      height: var(--otziv-home-line-chart-height, 11.1rem);
      padding: 0.72rem 0 0.9rem;
    }

    .bar-chart-frame .y-axis {
      height: 13.2rem;
      padding: 0.15rem 0 1.75rem;
    }

    .line-chart-scroll {
      width: 100%;
      min-width: 0;
      max-width: 100%;
      box-sizing: border-box;
      overflow-x: hidden;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.9rem;
      padding-left: 2.6rem;
      background: var(--otziv-muted-surface);
    }

    .line-chart-plot {
      position: relative;
      width: 100%;
      height: var(--otziv-home-line-chart-height, 11.1rem);
    }

    .line-chart {
      display: block;
      width: 100%;
      height: 100%;
    }

    .grid-line {
      stroke: rgba(103, 116, 131, 0.16);
      stroke-width: 1;
      vector-effect: non-scaling-stroke;
    }

    .year-line {
      fill: none;
      stroke-linecap: round;
      stroke-linejoin: round;
      stroke-width: 2.4;
      vector-effect: non-scaling-stroke;
    }

    .chart-dot {
      position: absolute;
      z-index: 2;
      width: 0.48rem;
      height: 0.48rem;
      border: 2px solid var(--otziv-white);
      border-radius: 50%;
      transform: translate(-50%, -50%);
    }

    .x-axis.line {
      display: grid;
      grid-template-columns: repeat(12, minmax(0, 1fr));
      gap: 0;
      padding: 0 0.15rem 0.5rem;
      font-size: 0.56rem;
      font-weight: 900;
      line-height: 1;
      text-align: center;
    }

    .bar-chart {
      display: grid;
      grid-auto-flow: column;
      grid-auto-columns: minmax(0, 1fr);
      max-width: 100%;
      box-sizing: border-box;
      height: 13.2rem;
      align-items: stretch;
      gap: 0.08rem;
      overflow-x: hidden;
      overflow-y: hidden;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.9rem;
      padding: 0.8rem 0.25rem 0.5rem 2.42rem;
      background: linear-gradient(180deg, transparent 0, transparent 24%, rgba(103, 116, 131, 0.08) 25%, transparent 26%, transparent 49%, rgba(103, 116, 131, 0.08) 50%, transparent 51%, transparent 74%, rgba(103, 116, 131, 0.08) 75%, transparent 76%);
    }

    .bar-item {
      display: grid;
      grid-template-rows: minmax(0, 1fr) 1.1rem;
      align-items: end;
      min-width: 0;
      gap: 0.22rem;
    }

    .bar {
      display: block;
      width: 100%;
      min-height: 0;
      border-radius: 999px 999px 0 0;
      background: var(--otziv-primary);
    }

    .bar-chart.salary .bar {
      background: #9a7bd9;
    }

    .bar-label {
      overflow: hidden;
      color: var(--otziv-info);
      font-size: 0.48rem;
      font-weight: 900;
      text-align: center;
      white-space: nowrap;
    }

    :host-context(body.otziv-dark-theme) .home-section-scroll .metric-tile {
      background: linear-gradient(155deg, rgba(37, 43, 47, 0.98) 0%, rgba(24, 29, 33, 0.96) 100%);
      box-shadow: none;
    }

    :host-context(body.otziv-dark-theme) .home-toolbar,
    :host-context(body.otziv-dark-theme) .period-row,
    :host-context(body.otziv-dark-theme) .identity-card,
    :host-context(body.otziv-dark-theme) .manual-payment-card,
    :host-context(body.otziv-dark-theme) .manual-task-item,
    :host-context(body.otziv-dark-theme) .data-card,
    :host-context(body.otziv-dark-theme) .group-block,
    :host-context(body.otziv-dark-theme) .notice,
    :host-context(body.otziv-dark-theme) .inline-alert {
      border-color: rgba(163, 189, 204, 0.18);
      background: linear-gradient(155deg, rgba(32, 37, 40, 0.98) 0%, rgba(37, 43, 47, 0.96) 100%);
      box-shadow: none;
    }

    :host-context(body.otziv-dark-theme) .data-card {
      background: linear-gradient(155deg, rgba(32, 37, 40, 0.98) 0%, rgba(38, 45, 52, 0.96) 100%);
    }

    :host-context(body.otziv-dark-theme) .data-card.tone-green {
      border-color: rgba(74, 198, 177, 0.24);
      background: linear-gradient(155deg, rgba(32, 37, 40, 0.98) 0%, rgba(28, 47, 43, 0.94) 100%);
    }

    :host-context(body.otziv-dark-theme) .data-card.tone-blue {
      border-color: rgba(122, 167, 220, 0.28);
      background: linear-gradient(155deg, rgba(32, 37, 40, 0.98) 0%, rgba(30, 43, 58, 0.94) 100%);
    }

    :host-context(body.otziv-dark-theme) .data-card.tone-yellow {
      border-color: rgba(215, 189, 120, 0.24);
      background: linear-gradient(155deg, rgba(32, 37, 40, 0.98) 0%, rgba(48, 42, 28, 0.94) 100%);
    }

    :host-context(body.otziv-dark-theme) .data-card.tone-red {
      border-color: rgba(255, 91, 143, 0.24);
      background: linear-gradient(155deg, rgba(32, 37, 40, 0.98) 0%, rgba(50, 32, 43, 0.94) 100%);
    }

    :host-context(body.otziv-dark-theme) .metric-delta {
      color: var(--otziv-info);
      background: rgba(14, 18, 22, 0.44);
    }

    :host-context(body.otziv-dark-theme) .mobile-chart-card,
    :host-context(body.otziv-dark-theme) .mobile-chart-card--salary {
      border-color: rgba(163, 189, 204, 0.18);
      background: linear-gradient(155deg, rgba(32, 37, 40, 0.98) 0%, rgba(38, 43, 50, 0.96) 100%);
      box-shadow: none;
    }

    :host-context(body.otziv-dark-theme) .line-chart-scroll {
      border-color: rgba(163, 189, 204, 0.16);
      background: rgba(21, 26, 30, 0.74);
    }

    :host-context(body.otziv-dark-theme) .bar-chart {
      border-color: rgba(163, 189, 204, 0.16);
      background:
        linear-gradient(180deg, transparent 0, transparent 24%, rgba(163, 189, 204, 0.1) 25%, transparent 26%, transparent 49%, rgba(163, 189, 204, 0.1) 50%, transparent 51%, transparent 74%, rgba(163, 189, 204, 0.1) 75%, transparent 76%),
        rgba(21, 26, 30, 0.74);
    }

    :host-context(body.otziv-dark-theme) .grid-line {
      stroke: rgba(163, 189, 204, 0.14);
    }

    :host-context(body.otziv-dark-theme) .chart-dot {
      border-color: #202528;
    }

    :host-context(body.otziv-dark-theme) .date-control input,
    :host-context(body.otziv-dark-theme) .period-row button,
    :host-context(body.otziv-dark-theme) .period-row label,
    :host-context(body.otziv-dark-theme) .manual-mode-toggle button,
    :host-context(body.otziv-dark-theme) .manual-task-item footer button,
    :host-context(body.otziv-dark-theme) .section-choice-list button,
    :host-context(body.otziv-dark-theme) .person-card dl div,
    :host-context(body.otziv-dark-theme) .manual-payment-card input,
    :host-context(body.otziv-dark-theme) .manual-task-edit-form input,
    :host-context(body.otziv-dark-theme) .manual-payment-save {
      border-color: rgba(163, 189, 204, 0.18);
      background: rgba(21, 26, 30, 0.72);
    }

    :host-context(body.otziv-dark-theme) .role-pill,
    :host-context(body.otziv-dark-theme) .pill-button,
    :host-context(body.otziv-dark-theme) .icon-button,
    :host-context(body.otziv-dark-theme) .period-row button.active,
    :host-context(body.otziv-dark-theme) .section-choice-list button.active {
      border-color: rgba(122, 167, 220, 0.32);
      background: rgba(122, 167, 220, 0.14);
      color: var(--otziv-primary);
    }

    :host-context(body.otziv-dark-theme) .person-card {
      border-color: rgba(163, 189, 204, 0.18);
      background: linear-gradient(155deg, rgba(32, 37, 40, 0.98) 0%, rgba(37, 43, 47, 0.96) 100%);
    }

    :host-context(body.otziv-dark-theme) .team-efficiency-badge {
      background: rgba(21, 26, 30, 0.94);
      box-shadow: none;
    }

    :host-context(body.otziv-dark-theme) .team-progress-strip {
      border-color: rgba(163, 189, 204, 0.2);
      background: rgba(21, 26, 30, 0.7);
    }

    :host-context(body.otziv-dark-theme) .team-progress-strip i {
      background: rgba(163, 189, 204, 0.18);
    }

    :host-context(body.otziv-compact-phone) .bar-chart,
    :host-context(body.otziv-short-phone) .bar-chart {
      height: 11.7rem;
      gap: 0.05rem;
      padding: 0.72rem 0.18rem 0.45rem 2.22rem;
    }

    :host-context(body.otziv-compact-phone) .bar-chart-frame .y-axis,
    :host-context(body.otziv-short-phone) .bar-chart-frame .y-axis {
      width: 1.95rem;
      height: 11.7rem;
      padding: 0.12rem 0 1.55rem;
      font-size: 0.54rem;
    }

    :host-context(body.otziv-compact-phone) .bar-label,
    :host-context(body.otziv-short-phone) .bar-label {
      font-size: 0.4rem;
    }

    :host-context(body.otziv-compact-phone) .analytics-view,
    :host-context(body.otziv-short-phone) .analytics-view {
      --otziv-home-line-chart-height: 9.4rem;
      gap: 0.48rem;
    }

    :host-context(body.otziv-compact-phone) .analytics-view .mobile-chart-card,
    :host-context(body.otziv-short-phone) .analytics-view .mobile-chart-card {
      gap: 0.36rem;
      padding: 0.48rem 0.36rem;
    }

    :host-context(body.otziv-compact-phone) .analytics-view .line-legend,
    :host-context(body.otziv-short-phone) .analytics-view .line-legend {
      font-size: 0.52rem;
    }

    :host-context(body.otziv-compact-phone) .analytics-view .chart-head h3,
    :host-context(body.otziv-short-phone) .analytics-view .chart-head h3 {
      font-size: 0.92rem;
    }

    :host-context(body.otziv-compact-phone) .analytics-view .line-chart-frame .y-axis,
    :host-context(body.otziv-short-phone) .analytics-view .line-chart-frame .y-axis {
      height: var(--otziv-home-line-chart-height);
      padding: 0.58rem 0 0.78rem;
      font-size: 0.54rem;
    }

    :host-context(body.otziv-compact-phone) .analytics-view .line-chart-scroll,
    :host-context(body.otziv-short-phone) .analytics-view .line-chart-scroll {
      padding-left: 2.28rem;
    }

    .section-caption,
    .profile-actions {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
    }

    .profile-actions {
      margin-top: auto;
    }

    .group-block {
      display: grid;
      gap: 0.55rem;
      flex: 0 0 auto;
      border-radius: 1rem;
      padding: 0.7rem;
    }

    .group-block header {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      align-items: center;
      gap: 0.45rem;
    }

    .group-block header strong {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .people-strip {
      display: flex;
      gap: 0.6rem;
      overflow-x: auto;
      scrollbar-width: none;
    }

    .person-card {
      position: relative;
      display: grid;
      flex: 0 0 min(16.5rem, 76vw);
      grid-template-columns: auto minmax(0, 1fr);
      gap: 0.45rem 0.6rem;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 1rem;
      padding: 0.7rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
    }

    .person-card img {
      width: 2.7rem;
      height: 2.7rem;
      border-radius: 0.8rem;
      object-fit: cover;
    }

    .person-card strong,
    .person-card span {
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .person-card span {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 800;
    }

    .team-efficiency-badge {
      position: absolute;
      top: 0.52rem;
      right: 0.55rem;
      display: grid;
      width: 2rem;
      height: 2rem;
      place-items: center;
      border: 1px solid rgba(108, 155, 207, 0.36);
      border-radius: 50%;
      color: var(--otziv-primary);
      background: rgba(255, 255, 255, 0.94);
      box-shadow: 0 0.55rem 1.1rem rgba(108, 155, 207, 0.16);
      font-size: 0.68rem;
      font-weight: 950;
      line-height: 1;
    }

    .team-efficiency-badge.score-green {
      border-color: rgba(47, 159, 149, 0.5);
      color: #238879;
      box-shadow: 0 0.55rem 1.1rem rgba(47, 159, 149, 0.16);
    }

    .team-efficiency-badge.score-yellow {
      border-color: rgba(235, 178, 58, 0.52);
      color: #b6790d;
      box-shadow: 0 0.55rem 1.1rem rgba(235, 178, 58, 0.16);
    }

    .team-efficiency-badge.score-red {
      border-color: rgba(232, 48, 103, 0.5);
      color: var(--otziv-danger);
      box-shadow: 0 0.55rem 1.1rem rgba(232, 48, 103, 0.16);
    }

    .team-progress-strip {
      display: grid;
      grid-column: 1 / -1;
      grid-template-columns: auto minmax(0, 1fr) auto auto auto;
      align-items: center;
      gap: 0.28rem;
      min-width: 0;
      min-height: 1.24rem;
      border: 1px solid rgba(108, 155, 207, 0.2);
      border-radius: 999px;
      padding: 0.16rem 0.28rem;
      background: rgba(255, 255, 255, 0.82);
    }

    .team-progress-strip span,
    .team-progress-strip strong,
    .team-progress-strip em {
      display: block;
      overflow: hidden;
      min-width: 0;
      color: var(--otziv-dark);
      font-size: 0.57rem;
      font-style: normal;
      font-weight: 950;
      line-height: 1;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .team-progress-strip > span:first-child {
      max-width: 4.4rem;
      color: var(--otziv-info);
      font-size: 0.54rem;
    }

    .team-progress-strip i {
      display: block;
      overflow: hidden;
      height: 0.34rem;
      min-width: 2.4rem;
      border-radius: 999px;
      background: rgba(136, 150, 169, 0.17);
    }

    .team-progress-strip i b {
      display: block;
      width: 0;
      height: 100%;
      border-radius: inherit;
      background: linear-gradient(90deg, #ef5f7d 0%, #f2a06a 55%, #52af91 100%);
      transition: width 180ms ease;
    }

    .team-progress-strip.complete i b {
      background: linear-gradient(90deg, #54b894 0%, #6fcba5 100%);
    }

    .team-progress-strip.empty i b {
      background: rgba(136, 150, 169, 0.24);
    }

    .team-progress-strip .material-icons-sharp {
      color: #52af91;
      font-size: 0.86rem;
      line-height: 1;
    }

    .team-progress-summary {
      grid-column: 1 / -1;
      margin: -0.16rem 0 0;
      overflow: hidden;
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 900;
      line-height: 1.18;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .person-card dl {
      display: grid;
      grid-column: 1 / -1;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.38rem;
      margin: 0;
    }

    .person-card dl div {
      border-radius: 0.7rem;
      padding: 0.42rem;
      background: var(--otziv-white);
    }

    .network-violations {
      grid-column: 1 / -1;
      border: 1px solid rgba(235, 178, 58, 0.42);
      border-radius: 0.78rem;
      padding: 0.5rem;
      background: rgba(255, 249, 232, 0.84);
    }

    .network-violations.critical {
      border-color: rgba(232, 48, 103, 0.42);
      background: rgba(255, 240, 244, 0.86);
    }

    .network-violations summary {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      color: #9a6809;
      cursor: pointer;
      list-style: none;
    }

    .network-violations.critical summary { color: var(--otziv-danger); }
    .network-violations summary::-webkit-details-marker { display: none; }
    .network-violations summary .material-icons-sharp { font-size: 1rem; }
    .network-violations summary strong { font-size: 0.66rem; }
    .network-violations > p { margin: 0.32rem 0 0; color: var(--otziv-info); font-size: 0.59rem; font-weight: 850; }
    .network-violations article { display: grid; gap: 0.12rem; margin-top: 0.42rem; border-top: 1px solid rgba(103, 116, 131, 0.14); padding-top: 0.42rem; }
    .network-violations article strong { font-size: 0.64rem; }
    .network-violations article small,
    .network-violations article span { color: var(--otziv-info); font-size: 0.57rem; font-weight: 800; white-space: normal; }

    :host-context(body.otziv-dark-theme) .network-violations {
      border-color: rgba(215, 189, 120, 0.3);
      background: linear-gradient(155deg, rgba(48, 42, 28, 0.96) 0%, rgba(32, 37, 40, 0.98) 100%);
    }

    :host-context(body.otziv-dark-theme) .network-violations.critical {
      border-color: rgba(255, 91, 143, 0.32);
      background: linear-gradient(155deg, rgba(50, 32, 43, 0.96) 0%, rgba(32, 37, 40, 0.98) 100%);
    }

    dd {
      margin: 0.12rem 0 0;
      color: var(--otziv-dark);
      font-size: 0.72rem;
      font-weight: 900;
    }

    .score-card {
      position: relative;
    }

    .score-card > b {
      position: absolute;
      top: 0.55rem;
      right: 0.65rem;
      color: var(--otziv-primary);
    }

    .notice,
    .inline-alert,
    .empty-note {
      margin: 0;
      border-radius: 1rem;
      padding: 0.7rem;
      color: var(--otziv-info);
      font-size: 0.72rem;
      font-weight: 900;
      line-height: 1.25;
    }

    .inline-alert {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.45rem;
      flex: 0 0 auto;
      border-color: rgba(255, 0, 96, 0.22);
      color: var(--otziv-danger);
      text-align: left;
    }

    .sheet-body {
      display: grid;
      grid-template-rows: auto minmax(0, 1fr);
      gap: 0.55rem;
      max-height: min(82vh, 38rem);
      padding: 0.85rem;
      overflow: hidden;
    }

    .sheet-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
    }

    .section-choice-list {
      display: grid;
      gap: 0.34rem;
      min-height: 0;
      overflow-y: auto;
      padding: 0 0.12rem 0.55rem 0;
      overscroll-behavior: contain;
      scrollbar-width: none;
    }

    .section-choice-list::-webkit-scrollbar {
      display: none;
    }

    .section-choice-list button {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.55rem;
      min-height: 2.64rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.86rem;
      padding: 0.42rem 0.56rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      text-align: left;
    }

    .section-choice-list button.active {
      border-color: rgba(108, 155, 207, 0.45);
      background: var(--otziv-light);
    }

    .section-choice-list .material-icons-sharp {
      color: var(--otziv-primary);
      font-size: 1.16rem;
    }

    .section-choice-list strong,
    .section-choice-list small {
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .section-choice-list strong {
      font-size: 0.92rem;
      line-height: 1.05;
    }

    .section-choice-list small {
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 800;
      line-height: 1.05;
    }
  `]
})
export class HomePage implements OnInit, OnDestroy {
  @ViewChild('sectionModal') private sectionModal?: IonModal;

  private routeSubscription?: Subscription;
  private querySubscription?: Subscription;
  private routerEventsSubscription?: Subscription;
  private lastMobileNavKey = '';
  private midnightRefreshTimer: ReturnType<typeof setTimeout> | null = null;
  private reloadEpoch = 0;
  private contractorPaymentsRequestEpoch = 0;

  readonly activeSection = signal<HomeSectionKey>('profile');
  readonly selectedDate = signal(this.todayIso());
  readonly selectedMonth = signal(this.currentMonthIso());
  readonly teamProgressMode = signal<TeamProgressMode>('day');
  readonly analyticsMode = signal<'lastTwoYears' | 'allTime' | 'custom'>('lastTwoYears');
  readonly periodFrom = signal(this.defaultPeriodFromIso(this.selectedDate()));
  readonly periodTo = signal(this.selectedDate());
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly sectionSheetOpen = signal(false);

  readonly profile = signal<CabinetProfile | null>(null);
  readonly contractorPayments = signal<ContractorPaymentSummary[]>([]);
  readonly contractorPaymentsLoading = signal(false);
  readonly contractorPaymentsError = signal<string | null>(null);
  readonly team = signal<TeamResponse | null>(null);
  readonly score = signal<ScoreResponse | null>(null);
  readonly analytics = signal<AnalyticsResponse | null>(null);
  readonly dictionarySummary = signal<DictionarySummary | null>(null);
  readonly manualPaymentSettings = signal<ManagerManualPaymentSettings | null>(null);
  readonly manualPaymentLoading = signal(false);
  readonly manualPaymentSaving = signal(false);
  readonly manualPaymentType = signal<ManualPaymentType>(DEFAULT_MANUAL_PAYMENT_TYPE);
  readonly manualPaymentPhone = signal('');
  readonly manualPaymentRecipient = signal(DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly manualPaymentUrl = signal(DEFAULT_MANUAL_PAYMENT_URL);
  readonly manualPaymentButtonLabel = signal(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly manualPaymentMessage = signal<string | null>(null);
  readonly manualPaymentTasks = signal<ManualPaymentTaskResponse[]>([]);
  readonly visibleManualPaymentTasks = computed(() => manualPaymentTaskWorklist(this.manualPaymentTasks()));
  readonly manualTaskLoading = signal(false);
  readonly manualTaskSaving = signal(false);
  readonly manualTaskMutatingId = signal<number | null>(null);
  readonly manualTaskEditingId = signal<number | null>(null);
  readonly manualTaskPaymentType = signal<ManualPaymentType>(DEFAULT_MANUAL_PAYMENT_TYPE);
  readonly manualTaskPhone = signal('');
  readonly manualTaskRecipient = signal(DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly manualTaskPaymentUrl = signal(DEFAULT_MANUAL_PAYMENT_URL);
  readonly manualTaskPaymentButtonLabel = signal(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly manualTaskAmountRubles = signal('');
  readonly manualTaskComment = signal('');
  readonly manualTaskAccountingTargets = signal<ManualPaymentTaskAccountingTargetOption[]>([]);
  readonly manualTaskAccountingTargetKey = signal('');
  readonly manualTaskAccountingTargetAcknowledged = signal(false);
  readonly manualTaskAccountingTargetsLoading = signal(false);
  readonly manualTaskAccountingTargetError = signal<string | null>(null);
  readonly manualTaskEditPaymentType = signal<ManualPaymentType>(DEFAULT_MANUAL_PAYMENT_TYPE);
  readonly manualTaskEditPhone = signal('');
  readonly manualTaskEditRecipient = signal(DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly manualTaskEditPaymentUrl = signal(DEFAULT_MANUAL_PAYMENT_URL);
  readonly manualTaskEditPaymentButtonLabel = signal(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly manualTaskEditAmountRubles = signal('');
  readonly manualTaskEditComment = signal('');
  readonly manualTaskEditAccountingTargets = signal<ManualPaymentTaskAccountingTargetOption[]>([]);
  readonly manualTaskEditAccountingTargetKey = signal('');
  readonly manualTaskEditAccountingTargetAcknowledged = signal(false);
  readonly manualTaskEditAccountingTargetsLoading = signal(false);
  readonly manualTaskEditAccountingTargetError = signal<string | null>(null);
  private manualTaskAccountingPreviewEpoch = 0;
  private manualTaskEditAccountingPreviewEpoch = 0;
  private readonly manualTaskOperationKey = new MobileManualPaymentTaskOperationKeyDraft();
  readonly manualTaskMessage = signal<string | null>(null);

  readonly teamSections = TEAM_SECTIONS;

  constructor(
    readonly auth: AuthService,
    private readonly api: ApiService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    readonly reportReview: ManagerReportReviewAccessService,
    private readonly toastController: ToastController
  ) {}

  ngOnInit(): void {
    this.applyRouteSection(this.route.snapshot.paramMap);
    this.applyMobileNavIntent(this.route.snapshot.queryParamMap);
    this.lastMobileNavKey = this.mobileNavKey(this.route.snapshot.queryParamMap);
    this.scheduleMidnightRefresh();
    void this.reloadWithReportReviewCheckIn();

    this.routeSubscription = this.route.paramMap.subscribe((params) => {
      const changed = this.applyRouteSection(params);
      if (changed) {
        void this.reloadWithReportReviewCheckIn();
      }
    });

    this.querySubscription = this.route.queryParamMap.subscribe((params) => {
      const key = this.mobileNavKey(params);
      if (key === this.lastMobileNavKey) {
        return;
      }
      this.lastMobileNavKey = key;
      this.applyMobileNavIntent(params);
    });

    this.routerEventsSubscription = this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) {
        this.closeSectionSheet();
      }
    });
  }

  ngOnDestroy(): void {
    this.reloadEpoch += 1;
    this.contractorPaymentsRequestEpoch += 1;
    this.closeSectionSheet();
    this.routeSubscription?.unsubscribe();
    this.querySubscription?.unsubscribe();
    this.routerEventsSubscription?.unsubscribe();
    this.clearMidnightRefresh();
  }

  navLinks(): HomeSectionLink[] {
    if (this.workLocked()) {
      return HOME_SECTIONS.filter((link) => link.key === 'profile');
    }
    return HOME_SECTIONS.filter((link) => this.canSee(link));
  }

  navStatusItems(): MobileStatusItem[] {
    return this.navLinks().map((link) => ({
      key: link.key,
      title: link.title.toLowerCase(),
      value: this.navMetric(link.key),
      icon: link.icon,
      tone: link.tone
    }));
  }

  sectionTitle(): string {
    return this.currentLink().title;
  }

  sectionKicker(): string {
    return this.activeSection() === 'profile' ? 'PERSONAL' : 'ANALYTICS';
  }

  navMetric(key: HomeSectionKey): string {
    if (key === 'profile') {
      return String(this.profile()?.user?.reviewCount ?? this.auth.user()?.roles.length ?? 0);
    }
    if (key === 'analytics') {
      return this.shortMoney(this.analytics()?.stats?.sum1MonthPay ?? 0);
    }
    if (key === 'team') {
      return String(this.totalTeamMembers());
    }
    if (key === 'score') {
      return String(this.totalScoreUsers());
    }
    return String(this.dictionaryItems().length);
  }

  async selectSection(section: HomeSectionKey): Promise<void> {
    this.closeSectionSheet();
    await this.router.navigateByUrl(`/tabs/home/${section}`);
    this.closeSectionSheet();
  }

  selectNavStatusItem(section: string): void {
    void this.selectSection(section as HomeSectionKey);
  }

  async openTbankSection(): Promise<void> {
    this.closeSectionSheet();
    await this.router.navigateByUrl('/tabs/tbank');
    this.closeSectionSheet();
  }

  async openAdminUsersSection(): Promise<void> {
    this.closeSectionSheet();
    await this.router.navigateByUrl('/tabs/users');
    this.closeSectionSheet();
  }

  async openManagerRemarks(): Promise<void> {
    this.closeSectionSheet();
    await this.router.navigateByUrl('/tabs/cabinet/manager-control');
    this.closeSectionSheet();
  }

  async openManagerControlSection(): Promise<void> {
    this.closeSectionSheet();
    await this.router.navigateByUrl('/tabs/control');
    this.closeSectionSheet();
  }

  openSectionSheet(): void {
    this.sectionSheetOpen.set(true);
  }

  closeSectionSheet(): void {
    this.sectionSheetOpen.set(false);
    void this.sectionModal?.dismiss(undefined, 'close').catch(() => undefined);
  }

  onSectionSheetDismissed(): void {
    this.sectionSheetOpen.set(false);
  }

  setDate(value: string): void {
    const nextDate = value || this.todayIso();
    this.selectedDate.set(nextDate);
    if (/^\d{4}-\d{2}-\d{2}$/.test(nextDate)) {
      this.selectedMonth.set(nextDate.slice(0, 7));
    }
    if (this.analyticsMode() === 'lastTwoYears') {
      this.periodFrom.set(this.defaultPeriodFromIso(this.selectedDate()));
      this.periodTo.set(this.selectedDate());
    }
    void this.reload();
  }

  setTeamProgressMode(mode: TeamProgressMode): void {
    this.teamProgressMode.set(mode);
  }

  setTeamMonth(value: string): void {
    this.selectedMonth.set(value || this.currentMonthIso());
    if (this.activeSection() === 'team') {
      void this.reload();
    }
  }

  setAnalyticsMode(mode: 'lastTwoYears' | 'allTime'): void {
    this.analyticsMode.set(mode);
    if (mode === 'lastTwoYears') {
      this.periodFrom.set(this.defaultPeriodFromIso(this.selectedDate()));
      this.periodTo.set(this.selectedDate());
    }
    void this.reload();
  }

  setPeriodFrom(value: string): void {
    this.periodFrom.set(value || this.periodFrom());
    this.analyticsMode.set('custom');
    void this.reload();
  }

  setPeriodTo(value: string): void {
    this.periodTo.set(value || this.periodTo());
    this.analyticsMode.set('custom');
    void this.reload();
  }

  async reload(forceRefresh = false): Promise<void> {
    const requestId = ++this.reloadEpoch;
    if (!this.auth.isAuthenticated()) {
      await this.auth.login('/tabs/home');
      return;
    }

    const section = this.activeSection();
    const selectedDate = this.selectedDate();
    this.loading.set(true);
    this.error.set(null);

    try {
      switch (section) {
        case 'profile': {
          const profile = await firstValueFrom(this.api.getCabinetProfile(selectedDate, { forceRefresh }));
          if (requestId !== this.reloadEpoch) {
            return;
          }
          this.profile.set(profile);
          await this.loadContractorPayments(requestId);
          if (requestId !== this.reloadEpoch) {
            return;
          }
          if (!this.workLocked()) {
            await this.loadManualPaymentSettings(forceRefresh, requestId);
            if (requestId !== this.reloadEpoch) {
              return;
            }
            await this.loadManualPaymentTasks(forceRefresh, requestId);
          } else {
            this.manualPaymentSettings.set(null);
            this.manualPaymentTasks.set([]);
          }
          break;
        }
        case 'team': {
          const team = await firstValueFrom(this.api.getCabinetTeam(selectedDate, {
            forceRefresh,
            month: this.teamMonthParam()
          }));
          if (requestId !== this.reloadEpoch) {
            return;
          }
          this.team.set(team);
          break;
        }
        case 'score': {
          const score = await firstValueFrom(this.api.getCabinetScore(selectedDate, { forceRefresh }));
          if (requestId !== this.reloadEpoch) {
            return;
          }
          this.score.set(score);
          break;
        }
        case 'analytics': {
          const analytics = await firstValueFrom(this.api.getCabinetAnalytics(selectedDate, this.analyticsOptions(forceRefresh)));
          if (requestId !== this.reloadEpoch) {
            return;
          }
          this.analytics.set(analytics);
          break;
        }
        case 'dictionaries': {
          const summary = await firstValueFrom(this.api.getDictionarySummary(this.canManageAllDictionaries()));
          if (requestId !== this.reloadEpoch) {
            return;
          }
          this.dictionarySummary.set(summary);
          break;
        }
      }
    } catch (error) {
      if (requestId !== this.reloadEpoch) {
        return;
      }
      this.error.set(this.errorMessage(error));
    } finally {
      if (requestId === this.reloadEpoch) {
        this.loading.set(false);
      }
    }
  }

  workLocked(): boolean {
    return this.auth.hasRealmRole('MANAGER') && this.reportReview.state()?.restricted === true;
  }

  reportReviewProgress(state: ManagerReportReviewAccessState): number {
    if (state.questionCount <= 0) {
      return 0;
    }
    return Math.max(
      0,
      Math.min(100, Math.round((state.answeredQuestionCount / state.questionCount) * 100))
    );
  }

  reportReviewDeadline(value: string): string {
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

  private async reloadWithReportReviewCheckIn(): Promise<void> {
    if (this.activeSection() === 'profile' && this.auth.hasRealmRole('MANAGER')) {
      await this.checkInReportReview();
    }
    await this.reload();
  }

  private async checkInReportReview(): Promise<void> {
    try {
      const state = await this.reportReview.checkIn();
      if (this.reportReview.shouldNotify(state)) {
        const toast = await this.toastController.create({
          header: state.restricted
            ? 'Рабочие разделы временно закрыты'
            : 'Необходимо проверить персональный аудит',
          message: state.message || 'Откройте Telegram и изучите персональный отчёт.',
          color: state.restricted ? 'danger' : 'warning',
          duration: 7000,
          position: 'top',
          buttons: [{ text: 'Понятно', role: 'cancel' }]
        });
        await toast.present();
      }
    } catch {
      // Личный кабинет остаётся доступным даже при временной ошибке проверки статуса.
    }
  }

  displayName(): string {
    return this.profile()?.workerZp?.fio
      || this.auth.user()?.name
      || this.auth.user()?.preferredUsername
      || 'Пользователь';
  }

  loginName(): string {
    return this.auth.user()?.preferredUsername || this.profile()?.user?.username || 'user';
  }

  greeting(): string {
    const hour = new Date().getHours();
    if (hour < 12) {
      return 'Доброе утро';
    }
    if (hour < 18) {
      return 'Добрый день';
    }
    return 'Добрый вечер';
  }

  primaryRoleLabel(): string {
    const role = this.auth.user()?.roles.find((value) => MOBILE_ROLE_LABELS[value]);
    return role ? this.roleLabel(role) : 'Пользователь';
  }

  roleLabel(role: string): string {
    return MOBILE_ROLE_LABELS[role] ?? role;
  }

  showContractorPayments(): boolean {
    return this.contractorPayments().length > 0
      || this.auth.hasRealmRole('MANAGER')
      || this.auth.hasRealmRole('WORKER');
  }

  profileRows(): Row[] {
    const profile = this.profile();
    const stats = profile?.workerZp;
    const identityRows: Row[] = [
      { label: 'Лиды', value: this.count(profile?.user?.leadCount ?? 0) },
      { label: 'Отзывы', value: this.count(profile?.user?.reviewCount ?? 0) }
    ];
    if (!shouldShowLegacyContractorMetrics(
      this.showContractorPayments(),
      this.contractorPaymentsError(),
      this.contractorPayments()
    )) {
      return identityRows;
    }
    return [
      ...identityRows,
      { label: 'За день', value: this.money(stats?.sum1Day ?? 0) },
      { label: 'За неделю', value: this.money(stats?.sum1Week ?? 0) },
      { label: 'За месяц', value: this.money(stats?.sum1Month ?? 0) },
      { label: 'За год', value: this.money(stats?.sum1Year ?? 0) }
    ];
  }

  showManualPaymentSettings(): boolean {
    return this.canManageManualPaymentSettings() && Boolean(this.manualPaymentSettings()?.manualPaymentEnabled);
  }

  showManualPaymentTasks(): boolean {
    return this.isManagerUser();
  }

  manualPaymentChanged(): boolean {
    const settings = this.manualPaymentSettings();
    if (!settings) {
      return false;
    }
    return this.manualPaymentType() !== this.normalizeManualPaymentType(settings.manualPaymentType)
      || this.manualPaymentPhone().trim() !== (settings.manualPhone ?? '')
      || this.manualPaymentRecipient().trim() !== this.manualRecipientOrDefault(settings.manualRecipientName)
      || this.manualPaymentUrl().trim() !== this.manualPaymentUrlFromResponse(settings.manualPaymentUrl)
      || this.manualPaymentButtonLabel().trim() !== this.manualPaymentButtonLabelOrDefault(settings.manualPaymentButtonLabel);
  }

  canCreateManualTask(): boolean {
    const hasTarget = this.manualTaskPaymentType() === 'MOBILE_BANK'
      ? Boolean(this.manualTaskPhone().trim()) && Boolean(this.manualTaskRecipient().trim())
      : Boolean(this.manualTaskPaymentUrl().trim()) && Boolean(this.manualTaskRecipient().trim());
    return !this.manualTaskSaving()
      && hasTarget
      && this.manualTaskTargetKopecks() > 0
      && !this.manualTaskAccountingTargetsLoading()
      && mobileManualTaskTargetValid(
        this.selectedManualTaskAccountingTarget(), this.manualTaskAccountingTargetAcknowledged()
      );
  }

  selectedManualTaskAccountingTarget(): ManualPaymentTaskAccountingTargetOption | null {
    return mobileManualTaskSelectedTarget(this.manualTaskAccountingTargets(), this.manualTaskAccountingTargetKey());
  }

  selectedManualTaskEditAccountingTarget(): ManualPaymentTaskAccountingTargetOption | null {
    return mobileManualTaskSelectedTarget(this.manualTaskEditAccountingTargets(), this.manualTaskEditAccountingTargetKey());
  }

  readonly manualTaskTargetEffect = mobileManualTaskTargetEffect;

  setManualPaymentType(value: ManualPaymentType): void {
    this.manualPaymentType.set(value);
    if (!this.manualPaymentRecipient().trim()) {
      this.manualPaymentRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
    }
    this.manualPaymentMessage.set(null);
  }

  setManualPaymentPhone(value: string): void {
    this.manualPaymentPhone.set(value ?? '');
    this.manualPaymentMessage.set(null);
  }

  setManualPaymentRecipient(value: string): void {
    this.manualPaymentRecipient.set(value ?? '');
    this.manualPaymentMessage.set(null);
  }

  setManualPaymentUrl(value: string): void {
    this.manualPaymentUrl.set(value ?? '');
    this.manualPaymentMessage.set(null);
  }

  setManualPaymentButtonLabel(value: string): void {
    this.manualPaymentButtonLabel.set(value ?? '');
    this.manualPaymentMessage.set(null);
  }

  async saveManualPaymentSettings(): Promise<void> {
    if (!this.showManualPaymentSettings() || this.manualPaymentSaving() || !this.manualPaymentChanged()) {
      return;
    }

    const manualPaymentType = this.manualPaymentType();
    const manualPhone = this.manualPaymentPhone().trim();
    const manualRecipientName = this.manualPaymentRecipient().trim() || DEFAULT_MANUAL_RECIPIENT_NAME;
    const manualPaymentUrl = this.manualPaymentUrl().trim();
    const manualPaymentButtonLabel = this.manualPaymentButtonLabel().trim() || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL;
    if (manualPaymentType === 'MOBILE_BANK' && (!manualPhone || !manualRecipientName)) {
      this.manualPaymentMessage.set('Заполните телефон и получателя.');
      return;
    }
    if (manualPaymentType === 'EXTERNAL_LINK' && (!manualPaymentUrl || !manualRecipientName)) {
      this.manualPaymentMessage.set('Заполните ссылку и получателя.');
      return;
    }

    this.manualPaymentSaving.set(true);
    this.manualPaymentMessage.set(null);
    try {
      const settings = await firstValueFrom(this.api.updateManagerManualPaymentSettings({
        manualPaymentType,
        manualPhone,
        manualRecipientName,
        manualPaymentUrl,
        manualPaymentButtonLabel
      }));
      this.applyManualPaymentSettings(settings);
      this.manualPaymentMessage.set('Реквизиты сохранены.');
    } catch (error) {
      const message = this.errorMessage(error);
      this.manualPaymentMessage.set(message);
      this.error.set(message);
    } finally {
      this.manualPaymentSaving.set(false);
    }
  }

  setManualTaskPaymentType(value: ManualPaymentType): void {
    this.manualTaskPaymentType.set(value);
    if (!this.manualTaskRecipient().trim()) {
      this.manualTaskRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
    }
    this.manualTaskMessage.set(null);
  }

  setManualTaskPhone(value: string): void {
    this.manualTaskPhone.set(value ?? '');
    this.manualTaskMessage.set(null);
  }

  setManualTaskRecipient(value: string): void {
    this.manualTaskRecipient.set(value ?? '');
    this.manualTaskMessage.set(null);
  }

  setManualTaskPaymentUrl(value: string): void {
    this.manualTaskPaymentUrl.set(value ?? '');
    this.manualTaskMessage.set(null);
  }

  setManualTaskPaymentButtonLabel(value: string): void {
    this.manualTaskPaymentButtonLabel.set(value ?? '');
    this.manualTaskMessage.set(null);
  }

  setManualTaskAmount(value: string | number | null): void {
    this.manualTaskAmountRubles.set(value == null ? '' : String(value));
    this.manualTaskMessage.set(null);
    void this.loadManualTaskAccountingTargets();
  }

  setManualTaskAccountingTarget(value: string | null): void {
    this.manualTaskAccountingTargetKey.set(value?.trim() ?? '');
    this.manualTaskAccountingTargetAcknowledged.set(false);
  }

  setManualTaskAccountingTargetAcknowledged(value: boolean): void {
    this.manualTaskAccountingTargetAcknowledged.set(Boolean(value));
  }

  setManualTaskComment(value: string): void {
    this.manualTaskComment.set(value ?? '');
  }

  setManualTaskEditPaymentType(value: ManualPaymentType): void {
    this.manualTaskEditPaymentType.set(value);
    if (!this.manualTaskEditRecipient().trim()) {
      this.manualTaskEditRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
    }
    this.manualTaskMessage.set(null);
  }

  setManualTaskEditPhone(value: string): void {
    this.manualTaskEditPhone.set(value ?? '');
    this.manualTaskMessage.set(null);
  }

  setManualTaskEditRecipient(value: string): void {
    this.manualTaskEditRecipient.set(value ?? '');
    this.manualTaskMessage.set(null);
  }

  setManualTaskEditPaymentUrl(value: string): void {
    this.manualTaskEditPaymentUrl.set(value ?? '');
    this.manualTaskMessage.set(null);
  }

  setManualTaskEditPaymentButtonLabel(value: string): void {
    this.manualTaskEditPaymentButtonLabel.set(value ?? '');
    this.manualTaskMessage.set(null);
  }

  setManualTaskEditAmount(value: string | number | null): void {
    this.manualTaskEditAmountRubles.set(value == null ? '' : String(value));
    this.manualTaskMessage.set(null);
    void this.loadManualTaskEditAccountingTargets();
  }

  setManualTaskEditAccountingTarget(value: string | null): void {
    this.manualTaskEditAccountingTargetKey.set(value?.trim() ?? '');
    this.manualTaskEditAccountingTargetAcknowledged.set(false);
  }

  setManualTaskEditAccountingTargetAcknowledged(value: boolean): void {
    this.manualTaskEditAccountingTargetAcknowledged.set(Boolean(value));
  }

  setManualTaskEditComment(value: string): void {
    this.manualTaskEditComment.set(value ?? '');
  }

  startManualTaskEdit(task: ManualPaymentTaskResponse): void {
    if (!task?.id || task.status === 'COMPLETED' || task.status === 'CANCELED') {
      return;
    }
    this.manualTaskEditingId.set(task.id);
    this.manualTaskEditPaymentType.set(this.normalizeManualPaymentType(task.manualPaymentType));
    this.manualTaskEditPhone.set(task.manualPhone ?? '');
    this.manualTaskEditRecipient.set(this.manualRecipientOrDefault(task.manualRecipientName));
    this.manualTaskEditPaymentUrl.set(this.manualPaymentUrlFromResponse(task.manualPaymentUrl));
    this.manualTaskEditPaymentButtonLabel.set(this.manualPaymentButtonLabelOrDefault(task.manualPaymentButtonLabel));
    this.manualTaskEditAmountRubles.set(String((task.targetAmountKopecks ?? 0) / 100));
    this.manualTaskEditComment.set(task.comment ?? '');
    this.manualTaskMessage.set(null);
    this.manualTaskEditAccountingTargetKey.set('');
    this.manualTaskEditAccountingTargetAcknowledged.set(false);
    void this.loadManualTaskEditAccountingTargets(task);
  }

  cancelManualTaskEdit(): void {
    this.manualTaskEditingId.set(null);
    this.manualTaskMessage.set(null);
    this.manualTaskEditAccountingTargets.set([]);
    this.manualTaskEditAccountingTargetKey.set('');
    this.manualTaskEditAccountingTargetAcknowledged.set(false);
    this.manualTaskEditAccountingTargetError.set(null);
    this.manualTaskEditAccountingPreviewEpoch += 1;
  }

  canSaveManualTaskEdit(task: ManualPaymentTaskResponse): boolean {
    const hasTarget = this.manualTaskEditPaymentType() === 'MOBILE_BANK'
      ? Boolean(this.manualTaskEditPhone().trim()) && Boolean(this.manualTaskEditRecipient().trim())
      : Boolean(this.manualTaskEditPaymentUrl().trim()) && Boolean(this.manualTaskEditRecipient().trim());
    return this.manualTaskEditingId() === task.id
      && this.manualTaskMutatingId() !== task.id
      && task.status !== 'COMPLETED'
      && task.status !== 'CANCELED'
      && hasTarget
      && this.manualTaskEditTargetKopecks() >= Math.max(1, task.reservedAmountKopecks ?? 0)
      && !this.manualTaskEditAccountingTargetsLoading()
      && mobileManualTaskTargetValid(
        this.selectedManualTaskEditAccountingTarget(), this.manualTaskEditAccountingTargetAcknowledged()
      );
  }

  async createManualPaymentTask(): Promise<void> {
    if (!this.canCreateManualTask()) {
      return;
    }
    const accountingTarget = this.selectedManualTaskAccountingTarget();
    if (!accountingTarget) {
      return;
    }

    this.manualTaskSaving.set(true);
    this.manualTaskMessage.set(null);
    try {
      const task = await firstValueFrom(this.api.createManagerManualPaymentTask({
        operationKey: this.manualTaskOperationKey.current(),
        manualPaymentType: this.manualTaskPaymentType(),
        manualPhone: this.manualTaskPhone().trim(),
        manualRecipientName: this.manualTaskRecipient().trim() || DEFAULT_MANUAL_RECIPIENT_NAME,
        manualPaymentUrl: this.manualTaskPaymentUrl().trim(),
        manualPaymentButtonLabel: this.manualTaskPaymentButtonLabel().trim() || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
        targetAmountKopecks: this.manualTaskTargetKopecks(),
        comment: this.manualTaskComment().trim() || null,
        accountingTargetKind: accountingTarget.kind,
        accountingTargetProfileId: accountingTarget.profileId ?? null,
        accountingTargetOverrunAcknowledged: this.manualTaskAccountingTargetAcknowledged()
      }));
      this.manualPaymentTasks.update((tasks) => [task, ...tasks.filter((item) => item.id !== task.id)]);
      this.startNewManualPaymentTaskDraft();
      this.manualTaskMessage.set('Задание создано.');
    } catch (error) {
      const message = this.errorMessage(error);
      this.manualTaskMessage.set(message);
      this.error.set(message);
    } finally {
      this.manualTaskSaving.set(false);
    }
  }

  resetManualPaymentTaskDraft(): void {
    if (!this.manualTaskSaving()) {
      this.startNewManualPaymentTaskDraft();
      this.manualTaskMessage.set(null);
    }
  }

  async updateManualTaskStatus(task: ManualPaymentTaskResponse, status: ManualPaymentTaskStatus): Promise<void> {
    if (!task?.id || this.manualTaskMutatingId()) {
      return;
    }
    this.manualTaskMutatingId.set(task.id);
    this.manualTaskMessage.set(null);
    try {
      const updated = await firstValueFrom(this.api.updateManagerManualPaymentTaskStatus(task.id, status));
      this.manualPaymentTasks.update((tasks) => tasks.map((item) => item.id === updated.id ? updated : item));
    } catch (error) {
      const message = this.errorMessage(error);
      this.manualTaskMessage.set(message);
      this.error.set(message);
    } finally {
      this.manualTaskMutatingId.set(null);
    }
  }

  async saveManualTaskEdit(task: ManualPaymentTaskResponse): Promise<void> {
    if (!task?.id || !this.canSaveManualTaskEdit(task)) {
      return;
    }
    const accountingTarget = this.selectedManualTaskEditAccountingTarget();
    if (!accountingTarget) {
      return;
    }
    this.manualTaskMutatingId.set(task.id);
    this.manualTaskMessage.set(null);
    try {
      const updated = await firstValueFrom(this.api.updateManagerManualPaymentTask(task.id, {
        manualPaymentType: this.manualTaskEditPaymentType(),
        manualPhone: this.manualTaskEditPhone().trim(),
        manualRecipientName: this.manualTaskEditRecipient().trim() || DEFAULT_MANUAL_RECIPIENT_NAME,
        manualPaymentUrl: this.manualTaskEditPaymentUrl().trim(),
        manualPaymentButtonLabel: this.manualTaskEditPaymentButtonLabel().trim() || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
        targetAmountKopecks: this.manualTaskEditTargetKopecks(),
        comment: this.manualTaskEditComment().trim() || null,
        manualPaymentUrlReplacementConfirmed: this.manualTaskEditPaymentType() === 'EXTERNAL_LINK'
          && !Boolean(task.manualPaymentUrl?.trim())
          && Boolean(this.manualTaskEditPaymentUrl().trim()),
        accountingTargetKind: accountingTarget.kind,
        accountingTargetProfileId: accountingTarget.profileId ?? null,
        accountingTargetOverrunAcknowledged: this.manualTaskEditAccountingTargetAcknowledged(),
        expectedGeneration: task.generation ?? null
      }));
      this.manualPaymentTasks.update((tasks) => tasks.map((item) => item.id === updated.id ? updated : item));
      this.manualTaskEditingId.set(null);
      this.manualTaskMessage.set('Задание сохранено.');
    } catch (error) {
      const message = this.errorMessage(error);
      this.manualTaskMessage.set(message);
      this.error.set(message);
    } finally {
      this.manualTaskMutatingId.set(null);
    }
  }

  manualTaskProgressPercent(task: ManualPaymentTaskResponse): number {
    if (!task.targetAmountKopecks) {
      return 0;
    }
    return Math.min(100, Math.round((task.reservedAmountKopecks / task.targetAmountKopecks) * 100));
  }

  manualTaskStatusLabel(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'Активно';
      case 'PAUSED':
        return 'Пауза';
      case 'COMPLETED':
        return 'Выполнено';
      case 'CANCELED':
        return 'Отменено';
      default:
        return status || 'Неизвестно';
    }
  }

  manualTaskTitle(task: ManualPaymentTaskResponse): string {
    return task.manualRecipientName || DEFAULT_MANUAL_RECIPIENT_NAME;
  }

  manualTaskSubtitle(task: ManualPaymentTaskResponse): string {
    const profile = task.paymentProfileName || 'профиль оплаты';
    if (this.normalizeManualPaymentType(task.manualPaymentType) === 'EXTERNAL_LINK') {
      return `${this.manualPaymentUrlFromResponse(task.manualPaymentUrl) || 'ссылка не настроена'} · ${profile}`;
    }
    return `${task.manualPhone || 'телефон не указан'} · ${profile}`;
  }

  formatKopecks(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    }).format((value ?? 0) / 100)} руб.`;
  }

  analyticsPayRows(): Row[] {
    const stats = this.analytics()?.stats;
    return [
      this.moneyMetric('За период', this.periodMoneyTotal(stats?.orderPayMapMonth), null),
      this.moneyMetric('За вчера', stats?.sum1DayPay ?? 0, stats?.percent1DayPay ?? null),
      this.moneyMetric('За неделю', stats?.sum1WeekPay ?? 0, stats?.percent1WeekPay ?? null),
      this.moneyMetric('За месяц', stats?.sum1MonthPay ?? 0, stats?.percent1MonthPay ?? null)
    ];
  }

  analyticsPayOrderRows(): Row[] {
    const stats = this.analytics()?.stats;
    return [
      this.moneyMetric('За год', stats?.sum1YearPay ?? 0, stats?.percent1YearPay ?? null),
      { label: 'Отклики', value: this.countWithUnit(stats?.newLeads ?? 0), percent: stats?.percent1NewLeadsPay ?? null },
      { label: 'Новые компании', value: this.countWithUnit(stats?.leadsInWork ?? 0), percent: stats?.percent2InWorkLeadsPay ?? null }
    ];
  }

  analyticsSalaryRows(): Row[] {
    const stats = this.analytics()?.stats;
    return [
      this.moneyMetric('За период', this.periodMoneyTotal(stats?.zpPayMapMonth), null),
      this.moneyMetric('За вчера', stats?.sum1Day ?? 0, stats?.percent1Day ?? null),
      this.moneyMetric('За неделю', stats?.sum1Week ?? 0, stats?.percent1Week ?? null),
      this.moneyMetric('За месяц', stats?.sum1Month ?? 0, stats?.percent1Month ?? null)
    ];
  }

  analyticsSalaryOrderRows(): Row[] {
    const stats = this.analytics()?.stats;
    return [
      this.moneyMetric('За год', stats?.sum1Year ?? 0, stats?.percent1Year ?? null),
      { label: 'Заказов месяц', value: this.countWithUnit(stats?.sumOrders1Month ?? 0), percent: stats?.percent1MonthOrders ?? null },
      { label: 'Прошлый месяц', value: this.countWithUnit(stats?.sumOrders2Month ?? 0), percent: stats?.percent2MonthOrders ?? null }
    ];
  }

  analyticsPeriodLabel(): string {
    const period = this.analytics()?.period;
    if (period?.allTime || this.analyticsMode() === 'allTime') {
      return 'все время';
    }
    return `${this.formatDate(period?.from ?? this.periodFrom())} - ${this.formatDate(period?.to ?? this.periodTo())}`;
  }

  periodSubtitle(): string {
    const period = this.analytics()?.period;
    if (period?.allTime || this.analyticsMode() === 'allTime') {
      return 'все время';
    }

    if (this.analyticsMode() === 'custom') {
      return this.analyticsPeriodLabel();
    }

    return 'последние 2 года';
  }

  turnoverMonthChart(): CabinetLineChart {
    return cabinetYearlyLineChartFrom(this.analytics()?.stats?.orderPayMapMonth, this.chartPeriodOptions());
  }

  turnoverDayChart(): CabinetBarChart {
    return cabinetDailyBarChartFrom(this.analytics()?.stats?.orderPayMap, this.selectedDate());
  }

  salaryMonthChart(): CabinetLineChart {
    return cabinetYearlyLineChartFrom(this.analytics()?.stats?.zpPayMapMonth, this.chartPeriodOptions());
  }

  salaryDayChart(): CabinetBarChart {
    return cabinetDailyBarChartFrom(this.analytics()?.stats?.zpPayMap, this.selectedDate());
  }

  profileSalaryMonthChart(): CabinetLineChart {
    return cabinetYearlyLineChartFrom(this.profile()?.workerZp?.zpPayMapMonth, { allTime: true });
  }

  profileSalaryDayChart(): CabinetBarChart {
    return cabinetDailyBarChartFrom(this.profile()?.workerZp?.zpPayMap, this.selectedDate());
  }

  metricTone(row: Row): MetricTone {
    const percent = row.percent;
    if (percent == null) {
      return 'blue';
    }
    if (percent > 25) {
      return 'green';
    }
    if (percent >= 0) {
      return 'blue';
    }
    if (percent > -25) {
      return 'yellow';
    }
    return 'red';
  }

  percentLabel(percent: number): string {
    const value = Math.round(percent);
    return `${value > 0 ? '+' : ''}${value}%`;
  }

  moneyLabel(value: number): string {
    return this.money(value);
  }

  members(key: TeamKey): TeamMember[] {
    return this.team()?.[key] ?? [];
  }

  memberProgress(member: TeamMember): DailyWorkProgress | null {
    return this.teamProgressMode() === 'month'
      ? member.monthlyProgress ?? null
      : member.dailyProgress ?? null;
  }

  networkViolations(member: TeamMember): WorkerNetworkViolationStats | null {
    return this.teamProgressMode() === 'month'
      ? member.monthlyNetworkViolations ?? null
      : member.dailyNetworkViolations ?? null;
  }

  networkViolationReason(reason: string): string {
    switch (reason) {
      case 'NON_CELLULAR_NETWORK': return 'Домашняя сеть или Wi-Fi';
      case 'VPN_PROXY_OR_DATACENTER': return 'VPN, прокси или анонимная сеть';
      case 'DESKTOP_OR_UNKNOWN_DEVICE': return 'Компьютер или неподдерживаемое устройство';
      case 'UNKNOWN_NETWORK': return 'Не удалось определить сеть';
      default: return 'Нарушение требований подключения';
    }
  }

  networkViolationScope(scope: string): string {
    switch ((scope || '').toLowerCase()) {
      case 'nagul': return 'Выгул';
      case 'publish': return 'Публикация';
      case 'recovery': return 'Восстановление';
      case 'bad': return 'Плохие';
      case 'review': return 'Отзывы';
      default: return 'Раздел специалиста';
    }
  }

  networkViolationTime(detail: WorkerNetworkViolationDetail): string {
    const start = this.formatNetworkDateTime(detail.firstSeenAt);
    const end = this.formatNetworkDateTime(detail.lastSeenAt);
    return !end || start === end ? start : `${start}–${end.split(' ').at(-1)}`;
  }

  teamProgressLabel(): string {
    return this.teamProgressMode() === 'month' ? 'Месяц' : 'Сегодня';
  }

  safeProgressPercent(progress?: DailyWorkProgress | null): number {
    if (!progress) {
      return 0;
    }
    const fallback = progress.total > 0 ? (progress.completed / progress.total) * 100 : (progress.checked ? 100 : 0);
    return this.clampPercent(progress.percent ?? fallback);
  }

  memberEfficiency(progress?: DailyWorkProgress | null): number {
    return this.clampPercent(progress?.efficiencyScore ?? 0);
  }

  efficiencyTone(progress?: DailyWorkProgress | null): MetricTone {
    const score = this.memberEfficiency(progress);
    if (score >= 85) {
      return 'green';
    }
    if (score >= 65) {
      return 'blue';
    }
    if (score >= 40) {
      return 'yellow';
    }
    return 'red';
  }

  efficiencyTitle(progress?: DailyWorkProgress | null): string {
    if (!progress) {
      return 'Эффективность пока не рассчитана.';
    }
    const parts = [
      `Эффективность ${this.memberEfficiency(progress)}/100`,
      'учитывает прогресс, скорость закрытия, нагрузку и дисциплину'
    ];
    if (progress.speedScore !== undefined) {
      parts.push(`скорость ${this.clampPercent(progress.speedScore)}`);
    }
    if (progress.disciplineScore !== undefined) {
      parts.push(`дисциплина ${this.clampPercent(progress.disciplineScore)}`);
    }
    if (progress.workloadScore !== undefined) {
      parts.push(`нагрузка ${this.clampPercent(progress.workloadScore)}`);
    }
    return parts.join(' · ');
  }

  teamProgressTitle(progress?: DailyWorkProgress | null): string {
    if (!progress) {
      return 'Прогресс пока не рассчитан.';
    }
    const period = this.teamProgressMode() === 'month' ? 'Месяц' : 'Сегодня';
    const parts = [
      `${period}: закрыто ${progress.completed || 0} из ${progress.total || 0}`,
      `${this.safeProgressPercent(progress)}%`
    ];
    if (progress.active > 0) {
      parts.push(`осталось ${progress.active}`);
    }
    if (progress.medianCloseSeconds > 0) {
      parts.push(`медиана ${this.formatDurationSeconds(progress.medianCloseSeconds)}`);
    }
    if (this.teamProgressMode() === 'month') {
      parts.push(`100% дней ${progress.reached100Days || 0}/${progress.workingDays || 0}`);
    }
    return parts.join(' · ');
  }

  teamProgressSummary(progress?: DailyWorkProgress | null): string {
    if (!progress?.visible) {
      return '';
    }

    if (this.teamProgressMode() === 'month') {
      if ((progress.workingDays || 0) <= 0) {
        return 'За месяц данных пока нет';
      }
      return `100% ${progress.reached100Days || 0}/${progress.workingDays || 0} дн. · медиана ${this.formatDurationSeconds(progress.medianCloseSeconds)}`;
    }

    if ((progress.total || 0) <= 0) {
      return 'Нет задач за день';
    }

    const parts: string[] = [];
    if (progress.checked) {
      parts.push('День закрыт');
    } else {
      parts.push(`Осталось ${progress.active || 0}`);
    }
    if (progress.reached100 && !progress.checked) {
      parts.push('100% уже был');
    }
    if ((progress.orderOverdueCount || 0) > 0) {
      parts.push(`за день просрочено заказов: ${progress.orderOverdueCount}`);
    }
    if (progress.medianCloseSeconds > 0) {
      parts.push(`медиана ${this.formatDurationSeconds(progress.medianCloseSeconds)}`);
    }
    return parts.join(' · ');
  }

  teamRows(key: TeamKey, member: TeamMember): Row[] {
    const progress = this.memberProgress(member);
    const rows: Row[] = [];

    if (key === 'managers') {
      rows.push(
        { label: 'Начислено', value: this.money(member.sum1Month) },
        { label: 'Выручка', value: this.money(member.payment1Month) },
        { label: 'Заказы', value: this.count(member.order1Month) },
        { label: 'Отзывы', value: this.count(member.review1Month) }
      );
      this.appendProgressRows(rows, progress);
      return rows;
    }

    if (key === 'workers') {
      rows.push(
        { label: 'Начислено', value: this.money(member.sum1Month) },
        { label: 'Заказы', value: this.count(member.order1Month) },
        { label: 'Отзывы', value: this.count(member.review1Month) }
      );
      if (this.teamProgressMode() === 'day') {
        rows.push({ label: 'В работе', value: this.count((member.newOrder || 0) + (member.inCorrect || 0) + (member.intVigul || 0) + (member.publish || 0)) });
      }
      this.appendProgressRows(rows, progress);
      const violations = this.networkViolations(member);
      if (violations?.visible && violations.episodeCount > 0) {
        rows.push({
          label: violations.severity === 'CRITICAL' ? 'Сеть · критично' : 'Нарушения сети',
          value: `${violations.episodeCount} / ${violations.attemptCount}`
        });
      }
      return rows;
    }

    rows.push(
      { label: 'Начислено', value: this.money(member.sum1Month) },
      { label: 'Новые', value: this.count(member.leadsNew) },
      { label: 'В работе', value: this.count(member.leadsInWork) },
      { label: 'Конверсия', value: `${member.percentInWork || 0}%` }
    );
    this.appendProgressRows(rows, progress);
    return rows;
  }

  private appendProgressRows(rows: Row[], progress?: DailyWorkProgress | null): void {
    if (!progress?.visible) {
      return;
    }

    rows.push(
      { label: 'Эффективн.', value: `${this.memberEfficiency(progress)}%` },
      { label: this.teamProgressMode() === 'month' ? 'Выполнено мес.' : 'Выполнено', value: `${progress.completed || 0}/${progress.total || 0}` }
    );

    if (this.teamProgressMode() === 'month') {
      rows.push(
        { label: 'Раб. дней', value: this.count(progress.workingDays) },
        { label: '100% дней', value: this.count(progress.reached100Days) },
        { label: 'Дней закрыто', value: this.count(progress.checkedDays) }
      );
    }

    this.addCountRow(rows, 'Восст. создано', progress.recoveryCreatedCount);
    this.addCountRow(rows, 'Восст. закрыто', progress.recoveryCompletedCount);
    this.addCountRow(rows, 'Просрочено заказов за период', progress.orderOverdueCount);
    this.addCountRow(rows, 'Просрочено карточек за период', progress.totalOverdueCount);
    this.addCountRow(rows, 'Смена бота', progress.botChangeCount);
    this.addCountRow(rows, 'Блок бота', progress.botBlockCount);

    if ((progress.activityEvents || 0) > 0) {
      rows.push({ label: 'Действий', value: this.count(progress.activityEvents) });
    }
    if ((progress.activeWorkSeconds || 0) > 0) {
      rows.push({ label: 'Активно', value: this.formatDurationSeconds(progress.activeWorkSeconds) });
    }
    if ((progress.medianCloseSeconds || 0) > 0) {
      rows.push({ label: 'Медиана', value: this.formatDurationSeconds(progress.medianCloseSeconds) });
    }
  }

  private addCountRow(rows: Row[], label: string, value?: number | null): void {
    if ((value || 0) <= 0) {
      return;
    }
    rows.push({ label, value: this.countWithUnit(value) });
  }

  scoreUsers(key: TeamKey): ScoreUser[] {
    return this.score()?.groups[key] ?? [];
  }

  scoreRows(key: TeamKey, user: ScoreUser): Row[] {
    const finance = this.score()?.financeVisible;
    const rows: Row[] = [];
    if (finance) {
      rows.push({ label: 'Начислено', value: this.money(user.salary) });
    }

    if (key === 'managers') {
      rows.push(
        { label: 'Новые компании', value: this.count(user.newCompanies) },
        { label: 'Заказы', value: this.count(user.order1Month) },
        { label: 'Отзывы', value: this.count(user.review1Month) }
      );
      if (finance) {
        rows.push({ label: 'Оборот', value: this.money(user.totalSum) });
      }
      return rows;
    }

    if (key === 'workers') {
      rows.push(
        { label: 'Заказы', value: this.count(user.order1Month) },
        { label: 'Отзывы', value: this.count(user.review1Month) },
        { label: 'Выгул', value: this.count(user.inVigul) },
        { label: 'Публикация', value: this.count(user.inPublish) }
      );
      return rows;
    }

    rows.push(
      { label: 'Новые', value: this.count(user.leadsNew) },
      { label: 'В работе', value: this.count(user.leadsInWork) },
      { label: 'Конверсия', value: `${user.percentInWork || 0}%` }
    );
    return rows;
  }

  scoreTrack(user: ScoreUser): string {
    return `${user.role}-${user.userId ?? user.fio}`;
  }

  dictionaryItems(): DictionarySummaryItem[] {
    return this.dictionarySummary()?.items ?? [];
  }

  imageUrl(imageId?: number | null): string {
    return this.api.imageUrl(imageId || 1);
  }

  logout(): void {
    void this.auth.logoutFrom('home_actions');
  }

  async loadContractorPayments(reloadRequestId = this.reloadEpoch): Promise<void> {
    if (!this.auth.hasRealmRole('MANAGER') && !this.auth.hasRealmRole('WORKER')) {
      this.contractorPaymentsRequestEpoch += 1;
      this.contractorPayments.set([]);
      this.contractorPaymentsError.set(null);
      this.contractorPaymentsLoading.set(false);
      return;
    }

    const requestId = ++this.contractorPaymentsRequestEpoch;
    this.contractorPaymentsLoading.set(true);
    this.contractorPaymentsError.set(null);
    try {
      const summaries = await firstValueFrom(this.api.getMyContractorPaymentSummaries());
      if (requestId !== this.contractorPaymentsRequestEpoch || reloadRequestId !== this.reloadEpoch) {
        return;
      }
      this.contractorPayments.set(summaries ?? []);
    } catch (error) {
      if (requestId !== this.contractorPaymentsRequestEpoch || reloadRequestId !== this.reloadEpoch) {
        return;
      }
      const status = Number((error as { status?: unknown })?.status);
      this.contractorPaymentsError.set(status === 403
        ? 'Личные расчёты недоступны (403). Обновите приложение или обратитесь к администратору.'
        : 'Расчёты по вознаграждениям временно недоступны. Старые показатели ниже продолжают работать.');
    } finally {
      if (requestId === this.contractorPaymentsRequestEpoch && reloadRequestId === this.reloadEpoch) {
        this.contractorPaymentsLoading.set(false);
      }
    }
  }

  private async loadManualPaymentSettings(forceRefresh = false, requestId = this.reloadEpoch): Promise<void> {
    if (!this.canManageManualPaymentSettings()) {
      this.clearManualPaymentSettings();
      return;
    }

    this.manualPaymentLoading.set(true);
    this.manualPaymentMessage.set(null);
    try {
      const settings = await firstValueFrom(this.api.getManagerManualPaymentSettings({ forceRefresh }));
      if (requestId !== this.reloadEpoch) {
        return;
      }
      this.applyManualPaymentSettings(settings);
    } catch (error) {
      if (requestId !== this.reloadEpoch) {
        return;
      }
      const message = this.errorMessage(error);
      this.clearManualPaymentSettings();
      this.error.set(message);
    } finally {
      if (requestId === this.reloadEpoch) {
        this.manualPaymentLoading.set(false);
      }
    }
  }

  private async loadManualPaymentTasks(forceRefresh = false, requestId = this.reloadEpoch): Promise<void> {
    if (!this.isManagerUser()) {
      this.clearManualPaymentTasks();
      return;
    }

    this.manualTaskLoading.set(true);
    this.manualTaskMessage.set(null);
    try {
      const tasks = await firstValueFrom(this.api.getManagerManualPaymentTasks({ forceRefresh }));
      if (requestId !== this.reloadEpoch) {
        return;
      }
      this.manualPaymentTasks.set(tasks ?? []);
    } catch (error) {
      if (requestId !== this.reloadEpoch) {
        return;
      }
      const message = this.errorMessage(error);
      this.manualPaymentTasks.set([]);
      this.manualTaskMessage.set(message);
    } finally {
      if (requestId === this.reloadEpoch) {
        this.manualTaskLoading.set(false);
      }
    }
  }

  private applyManualPaymentSettings(settings: ManagerManualPaymentSettings): void {
    this.manualPaymentSettings.set(settings);
    this.manualPaymentType.set(this.normalizeManualPaymentType(settings.manualPaymentType));
    this.manualPaymentPhone.set(settings.manualPhone ?? '');
    this.manualPaymentRecipient.set(this.manualRecipientOrDefault(settings.manualRecipientName));
    this.manualPaymentUrl.set(this.manualPaymentUrlFromResponse(settings.manualPaymentUrl));
    this.manualPaymentButtonLabel.set(this.manualPaymentButtonLabelOrDefault(settings.manualPaymentButtonLabel));
  }

  private clearManualPaymentSettings(): void {
    this.manualPaymentSettings.set(null);
    this.manualPaymentType.set(DEFAULT_MANUAL_PAYMENT_TYPE);
    this.manualPaymentPhone.set('');
    this.manualPaymentRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
    this.manualPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
    this.manualPaymentButtonLabel.set(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
    this.manualPaymentMessage.set(null);
    this.manualPaymentLoading.set(false);
    this.manualPaymentSaving.set(false);
    this.clearManualPaymentTasks();
  }

  private clearManualPaymentTasks(): void {
    this.manualPaymentTasks.set([]);
    this.manualTaskLoading.set(false);
    this.manualTaskSaving.set(false);
    this.manualTaskMutatingId.set(null);
    this.manualTaskPaymentType.set(DEFAULT_MANUAL_PAYMENT_TYPE);
    this.manualTaskPhone.set('');
    this.manualTaskRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
    this.manualTaskPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
    this.manualTaskPaymentButtonLabel.set(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
    this.manualTaskAmountRubles.set('');
    this.manualTaskComment.set('');
    this.manualTaskAccountingTargets.set([]);
    this.manualTaskAccountingTargetKey.set('');
    this.manualTaskAccountingTargetAcknowledged.set(false);
    this.manualTaskAccountingTargetError.set(null);
    this.manualTaskAccountingPreviewEpoch += 1;
    this.manualTaskMessage.set(null);
    this.manualTaskOperationKey.rotate();
  }

  private startNewManualPaymentTaskDraft(): void {
    this.manualTaskPaymentType.set(DEFAULT_MANUAL_PAYMENT_TYPE);
    this.manualTaskPhone.set('');
    this.manualTaskRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
    this.manualTaskPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
    this.manualTaskPaymentButtonLabel.set(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
    this.manualTaskAmountRubles.set('');
    this.manualTaskComment.set('');
    this.manualTaskAccountingTargets.set([]);
    this.manualTaskAccountingTargetKey.set('');
    this.manualTaskAccountingTargetAcknowledged.set(false);
    this.manualTaskAccountingTargetError.set(null);
    this.manualTaskAccountingPreviewEpoch += 1;
    this.manualTaskOperationKey.rotate();
  }

  private manualTaskTargetKopecks(): number {
    const value = Number(this.manualTaskAmountRubles());
    return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : 0;
  }

  private manualTaskEditTargetKopecks(): number {
    const value = Number(this.manualTaskEditAmountRubles());
    return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : 0;
  }

  private async loadManualTaskAccountingTargets(): Promise<void> {
    const amount = this.manualTaskTargetKopecks();
    const previousKey = this.manualTaskAccountingTargetKey();
    const epoch = ++this.manualTaskAccountingPreviewEpoch;
    this.manualTaskAccountingTargetAcknowledged.set(false);
    this.manualTaskAccountingTargetError.set(null);
    if (amount <= 0) {
      this.manualTaskAccountingTargets.set([]);
      this.manualTaskAccountingTargetKey.set('');
      this.manualTaskAccountingTargetsLoading.set(false);
      return;
    }
    this.manualTaskAccountingTargetsLoading.set(true);
    try {
      const options = await firstValueFrom(this.api.getManagerManualPaymentTaskAccountingTargets(amount));
      if (epoch !== this.manualTaskAccountingPreviewEpoch) return;
      const normalized = options ?? [];
      this.manualTaskAccountingTargets.set(normalized);
      const restored = normalized.find(option => option.key === previousKey)
        ?? mobileManualTaskRecommendedTarget(normalized);
      this.manualTaskAccountingTargetKey.set(restored?.key ?? '');
    } catch (error) {
      if (epoch !== this.manualTaskAccountingPreviewEpoch) return;
      this.manualTaskAccountingTargets.set([]);
      this.manualTaskAccountingTargetKey.set('');
      this.manualTaskAccountingTargetError.set(this.errorMessage(error));
    } finally {
      if (epoch === this.manualTaskAccountingPreviewEpoch) this.manualTaskAccountingTargetsLoading.set(false);
    }
  }

  private async loadManualTaskEditAccountingTargets(sourceTask?: ManualPaymentTaskResponse): Promise<void> {
    const task = sourceTask ?? this.manualPaymentTasks().find(item => item.id === this.manualTaskEditingId());
    const amount = this.manualTaskEditTargetKopecks();
    const previousKey = this.manualTaskEditAccountingTargetKey();
    const epoch = ++this.manualTaskEditAccountingPreviewEpoch;
    this.manualTaskEditAccountingTargetAcknowledged.set(false);
    this.manualTaskEditAccountingTargetError.set(null);
    if (!task || amount <= 0) {
      this.manualTaskEditAccountingTargets.set([]);
      this.manualTaskEditAccountingTargetKey.set('');
      this.manualTaskEditAccountingTargetsLoading.set(false);
      return;
    }
    this.manualTaskEditAccountingTargetsLoading.set(true);
    try {
      const options = await firstValueFrom(this.api.getManagerManualPaymentTaskAccountingTargets(amount, task.id));
      if (epoch !== this.manualTaskEditAccountingPreviewEpoch) return;
      const normalized = options ?? [];
      const restored = normalized.find(option => option.key === previousKey)
        ?? mobileManualTaskTargetForSnapshot(normalized, task);
      this.manualTaskEditAccountingTargets.set(normalized);
      this.manualTaskEditAccountingTargetKey.set(restored?.key ?? '');
    } catch (error) {
      if (epoch !== this.manualTaskEditAccountingPreviewEpoch) return;
      this.manualTaskEditAccountingTargets.set([]);
      this.manualTaskEditAccountingTargetKey.set('');
      this.manualTaskEditAccountingTargetError.set(this.errorMessage(error));
    } finally {
      if (epoch === this.manualTaskEditAccountingPreviewEpoch) this.manualTaskEditAccountingTargetsLoading.set(false);
    }
  }

  private normalizeManualPaymentType(value?: string | null): ManualPaymentType {
    return value === 'EXTERNAL_LINK' ? 'EXTERNAL_LINK' : DEFAULT_MANUAL_PAYMENT_TYPE;
  }

  private manualPaymentUrlFromResponse(value?: string | null): string {
    return (value ?? '').trim();
  }

  private manualPaymentButtonLabelOrDefault(value?: string | null): string {
    const clean = (value ?? '').trim();
    return clean || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL;
  }

  private manualRecipientOrDefault(value?: string | null): string {
    const clean = (value ?? '').trim();
    return clean || DEFAULT_MANUAL_RECIPIENT_NAME;
  }

  private isManagerUser(): boolean {
    return this.auth.hasRealmRole('MANAGER');
  }

  private canManageManualPaymentSettings(): boolean {
    return this.auth.hasAnyRealmRole(MOBILE_ROLES.ownerAdmin) && this.isManagerUser();
  }

  private applyRouteSection(params: ParamMap): boolean {
    const requested = params.get('section');
    const next = this.isHomeSection(requested) && this.canSeeSection(requested)
      ? requested
      : this.defaultSection();
    const changed = this.activeSection() !== next;
    this.activeSection.set(next);
    return changed;
  }

  private applyMobileNavIntent(params: ParamMap): void {
    if (params.get('mobileNav') === 'menu') {
      this.openSectionSheet();
    }
  }

  private mobileNavKey(params: ParamMap): string {
    return `${params.get('mobileNav') ?? ''}:${params.get('navTs') ?? ''}`;
  }

  private defaultSection(): HomeSectionKey {
    return this.auth.hasAnyRealmRole(MOBILE_ROLES.ownerAdmin) ? 'analytics' : 'profile';
  }

  private currentLink(): HomeSectionLink {
    return HOME_SECTIONS.find((link) => link.key === this.activeSection()) ?? HOME_SECTIONS[0];
  }

  private canSee(link: HomeSectionLink): boolean {
    return link.roles.length === 0 || this.auth.hasAnyRealmRole(link.roles);
  }

  private canSeeSection(section: HomeSectionKey): boolean {
    const link = HOME_SECTIONS.find((item) => item.key === section);
    return Boolean(link && this.canSee(link));
  }

  canManageAllDictionaries(): boolean {
    return canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.dictionaries, MOBILE_ACTIONS.manage);
  }

  canPersonalManagerControl(): boolean {
    return this.auth.hasRealmRole('MANAGER');
  }

  canSeeManagerControl(): boolean {
    return this.auth.hasAnyRealmRole(MOBILE_ROLES.ownerAdmin);
  }

  canSeeTbank(): boolean {
    return canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.tbank, MOBILE_ACTIONS.view);
  }

  canSeeAdminUsers(): boolean {
    return canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.adminUsers, MOBILE_ACTIONS.view);
  }

  private isHomeSection(value: unknown): value is HomeSectionKey {
    return value === 'profile'
      || value === 'analytics'
      || value === 'team'
      || value === 'score'
      || value === 'dictionaries';
  }

  private totalTeamMembers(): number {
    const team = this.team();
    return team ? team.managers.length + team.marketologs.length + team.workers.length + team.operators.length : 0;
  }

  private totalScoreUsers(): number {
    const groups = this.score()?.groups;
    return groups ? groups.managers.length + groups.marketologs.length + groups.workers.length + groups.operators.length : 0;
  }

  private analyticsOptions(forceRefresh: boolean) {
    if (this.analyticsMode() === 'allTime') {
      return { forceRefresh, allTime: true };
    }
    if (this.analyticsMode() === 'custom') {
      return { forceRefresh, from: this.periodFrom(), to: this.periodTo() };
    }
    return { forceRefresh, from: this.defaultPeriodFromIso(this.selectedDate()), to: this.selectedDate() };
  }

  private chartPeriodOptions(): YearlyLineChartOptions {
    const anchorYear = new Date(this.selectedDate()).getFullYear();
    if (this.analyticsMode() === 'allTime') {
      return { allTime: true, anchorYear };
    }

    const period = this.analytics()?.period;
    return {
      anchorYear,
      from: period?.from ?? this.periodFrom(),
      to: period?.to ?? this.periodTo()
    };
  }

  private moneyMetric(label: string, value: number, percent: number | null): Row {
    return {
      label,
      value: this.money(value),
      percent
    };
  }

  private periodMoneyTotal(map?: string | null): number {
    return cabinetPeriodTotalFrom(map, this.chartPeriodOptions());
  }

  private money(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} руб.`;
  }

  private count(value?: number | null): string {
    return new Intl.NumberFormat('ru-RU').format(value || 0);
  }

  private countWithUnit(value?: number | null): string {
    return `${this.count(value)} шт.`;
  }

  private clampPercent(value?: number | null): number {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round(numeric)));
  }

  private formatDurationSeconds(value?: number | null): string {
    const totalMinutes = Math.max(0, Math.round((value || 0) / 60));
    if (totalMinutes <= 0) {
      return '0 мин';
    }
    const days = Math.floor(totalMinutes / 1440);
    const hours = Math.floor((totalMinutes % 1440) / 60);
    const minutes = totalMinutes % 60;
    const parts: string[] = [];
    if (days > 0) {
      parts.push(`${days} д`);
    }
    if (hours > 0) {
      parts.push(`${hours} ч`);
    }
    if (minutes > 0 || parts.length === 0) {
      parts.push(`${minutes} мин`);
    }
    return parts.slice(0, 2).join(' ');
  }

  private shortMoney(value: number): string {
    const abs = Math.abs(value || 0);
    if (abs >= 1_000_000) {
      return `${Math.round(abs / 100_000) / 10}м`;
    }
    if (abs >= 1_000) {
      return `${Math.round(abs / 1_000)}к`;
    }
    return String(value || 0);
  }

  private formatDate(value: string): string {
    return value ? value.split('-').reverse().join('.') : '-';
  }

  private formatNetworkDateTime(value: string | null | undefined): string {
    if (!value) {
      return '';
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime())
      ? value
      : date.toLocaleString('ru-RU', {
          day: '2-digit',
          month: '2-digit',
          hour: '2-digit',
          minute: '2-digit'
        });
  }

  private defaultPeriodFromIso(dateIso: string): string {
    const year = Number(dateIso.slice(0, 4)) || new Date().getFullYear();
    return `${year - 1}-01-01`;
  }

  private todayIso(): string {
    return businessDateIso();
  }

  private currentMonthIso(): string {
    return this.todayIso().slice(0, 7);
  }

  private teamMonthParam(): string {
    const value = this.selectedMonth() || this.currentMonthIso();
    return /^\d{4}-\d{2}$/.test(value) ? `${value}-01` : value;
  }

  private scheduleMidnightRefresh(): void {
    this.clearMidnightRefresh();
    const previousToday = this.todayIso();
    const previousMonth = this.currentMonthIso();
    const now = new Date();
    const delay = Math.min(2_147_483_647, millisecondsUntilNextBusinessDay(now) + 2000);

    this.midnightRefreshTimer = setTimeout(() => {
      let shouldReload = false;
      const today = this.todayIso();
      const month = this.currentMonthIso();

      if (this.selectedDate() === previousToday) {
        this.selectedDate.set(today);
        shouldReload = true;
        if (this.analyticsMode() === 'lastTwoYears') {
          this.periodFrom.set(this.defaultPeriodFromIso(today));
          this.periodTo.set(today);
        }
      }

      if (this.selectedMonth() === previousMonth) {
        this.selectedMonth.set(month);
        shouldReload = true;
      }

      if (shouldReload && this.activeSection() !== 'dictionaries') {
        void this.reload(true);
      }

      this.scheduleMidnightRefresh();
    }, delay);
  }

  private clearMidnightRefresh(): void {
    if (this.midnightRefreshTimer) {
      clearTimeout(this.midnightRefreshTimer);
      this.midnightRefreshTimer = null;
    }
  }

  private errorMessage(error: unknown): string {
    const maybe = error as { error?: unknown; message?: string; status?: number };
    if (typeof maybe.error === 'object' && maybe.error !== null) {
      const body = maybe.error as { message?: string; detail?: string; error?: string };
      return body.message || body.detail || body.error || 'Раздел не загрузился.';
    }
    if (typeof maybe.error === 'string' && maybe.error.trim()) {
      return maybe.error;
    }
    return maybe.message || (maybe.status ? `Раздел не загрузился. Код: ${maybe.status}` : 'Раздел не загрузился.');
  }
}
