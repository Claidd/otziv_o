import { Component, OnDestroy, OnInit, signal, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, NavigationStart, ParamMap, Router, RouterLink } from '@angular/router';
import { IonContent, IonModal } from '@ionic/angular/standalone';
import { firstValueFrom, Subscription } from 'rxjs';
import {
  AnalyticsResponse,
  CabinetProfile,
  DictionarySummary,
  DictionarySummaryItem,
  ManagerManualPaymentSettings,
  ManualPaymentTaskResponse,
  ManualPaymentTaskStatus,
  ManualPaymentType,
  ScoreResponse,
  ScoreUser,
  TeamMember,
  TeamResponse,
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
import { MobileActionSheetComponent } from '../shared/mobile-action-sheet.component';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileStatusSliderComponent, type MobileStatusItem } from '../shared/mobile-status-slider.component';

type HomeSectionKey = 'profile' | 'analytics' | 'team' | 'score' | 'dictionaries';
type HomeTone = 'blue' | 'green' | 'teal' | 'violet' | 'yellow';
type MetricTone = 'green' | 'blue' | 'yellow' | 'red';
type TeamKey = 'managers' | 'marketologs' | 'workers' | 'operators';

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
  { key: 'analytics', title: 'Аналитика', subtitle: 'оборот, ЗП и графики', icon: 'analytics', tone: 'violet', roles: MOBILE_ROLES.ownerAdmin },
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
  imports: [FormsModule, IonContent, IonModal, MobileActionSheetComponent, MobileDictionariesComponent, MobileHeaderComponent, MobileStatusSliderComponent, RouterLink],
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
                <input type="date" [ngModel]="selectedDate()" (ngModelChange)="setDate($event)">
              </label>

              <button class="icon-button" type="button" (click)="reload(true)" [disabled]="loading()" aria-label="Обновить">
                <span class="material-icons-sharp">refresh</span>
              </button>
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
                        <label><span>Получатель</span><input [ngModel]="manualTaskRecipient()" (ngModelChange)="setManualTaskRecipient($event)"></label>
                      } @else {
                        <label><span>Телефон</span><input autocomplete="tel" [ngModel]="manualTaskPhone()" (ngModelChange)="setManualTaskPhone($event)" placeholder="+7..."></label>
                        <label><span>Получатель</span><input [ngModel]="manualTaskRecipient()" (ngModelChange)="setManualTaskRecipient($event)"></label>
                      }
                      <label><span>Цель, руб.</span><input type="number" min="1" step="100" [ngModel]="manualTaskAmountRubles()" (ngModelChange)="setManualTaskAmount($event)"></label>
                      <label><span>Комментарий</span><input [ngModel]="manualTaskComment()" (ngModelChange)="setManualTaskComment($event)"></label>

                      @if (manualTaskMessage()) {
                        <small class="manual-payment-message">{{ manualTaskMessage() }}</small>
                      }

                      <button class="manual-payment-save" type="button" (click)="createManualPaymentTask()" [disabled]="!canCreateManualTask()">
                        <span class="material-icons-sharp">{{ manualTaskSaving() ? 'hourglass_top' : 'add' }}</span>
                        {{ manualTaskSaving() ? 'Создаю' : 'Создать задание' }}
                      </button>

                      <div class="manual-task-list">
                        @for (task of manualPaymentTasks(); track task.id) {
                          <section class="manual-task-item" [class.inactive]="task.status !== 'ACTIVE'">
                            <header>
                              <div>
                                <strong>{{ manualTaskTitle(task) }}</strong>
                                <small>{{ manualTaskSubtitle(task) }}</small>
                              </div>
                              <b>{{ manualTaskStatusLabel(task.status) }}</b>
                            </header>
                            <div class="manual-task-progress">
                              <span [style.width.%]="manualTaskProgressPercent(task)"></span>
                            </div>
                            <footer>
                              <small>{{ formatKopecks(task.reservedAmountKopecks) }} из {{ formatKopecks(task.targetAmountKopecks) }}</small>
                              @if (task.status === 'ACTIVE') {
                                <button type="button" (click)="updateManualTaskStatus(task, 'PAUSED')" [disabled]="manualTaskMutatingId() === task.id">пауза</button>
                              } @else if (task.status === 'PAUSED') {
                                <button type="button" (click)="updateManualTaskStatus(task, 'ACTIVE')" [disabled]="manualTaskMutatingId() === task.id">вкл</button>
                              }
                              @if (task.status !== 'COMPLETED' && task.status !== 'CANCELED') {
                                <button type="button" class="danger" (click)="updateManualTaskStatus(task, 'CANCELED')" [disabled]="manualTaskMutatingId() === task.id">отмена</button>
                              }
                            </footer>
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

                  <section class="metric-grid">
                    @for (row of profileRows(); track row.label) {
                      <article class="data-card">
                        <span>{{ row.label }}</span>
                        <strong>{{ row.value }}</strong>
                      </article>
                    }
                  </section>

                  <section class="profile-actions">
                    <a class="pill-button" routerLink="/tabs/profile">
                      <span class="material-icons-sharp">person</span>
                      профиль
                    </a>
                    <button class="pill-button" type="button" (click)="openSectionSheet()">
                      <span class="material-icons-sharp">apps</span>
                      разделы
                    </button>
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
                      <strong>Зарплаты</strong>
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
                          <h3>Зарплаты по месяцам</h3>
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
                              <svg class="line-chart" [attr.viewBox]="chart.viewBox" preserveAspectRatio="none" role="img" aria-label="Зарплаты по месяцам">
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
                          <h3>Зарплаты по дням</h3>
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
                            <img [src]="imageUrl(member.imageId)" [alt]="member.fio || member.login">
                            <div>
                              <strong>{{ member.fio || member.login }}</strong>
                              <span>{{ member.login }}</span>
                            </div>
                            <dl>
                              @for (row of teamRows(section.key, member); track row.label) {
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
                        <strong>Т Банк</strong>
                        <small>платежи и профили</small>
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

    .profile-view {
      overflow-y: auto;
      padding-bottom: 0.35rem;
    }

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

    .manual-payment-card input {
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
      height: 14rem;
      padding: 1rem 0 1.15rem;
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
      height: 14rem;
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
      padding: 0 0.15rem 0.65rem;
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

  readonly activeSection = signal<HomeSectionKey>('profile');
  readonly selectedDate = signal(this.todayIso());
  readonly analyticsMode = signal<'lastTwoYears' | 'allTime' | 'custom'>('lastTwoYears');
  readonly periodFrom = signal(this.defaultPeriodFromIso(this.selectedDate()));
  readonly periodTo = signal(this.selectedDate());
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly sectionSheetOpen = signal(false);

  readonly profile = signal<CabinetProfile | null>(null);
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
  readonly manualTaskLoading = signal(false);
  readonly manualTaskSaving = signal(false);
  readonly manualTaskMutatingId = signal<number | null>(null);
  readonly manualTaskPaymentType = signal<ManualPaymentType>(DEFAULT_MANUAL_PAYMENT_TYPE);
  readonly manualTaskPhone = signal('');
  readonly manualTaskRecipient = signal(DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly manualTaskPaymentUrl = signal(DEFAULT_MANUAL_PAYMENT_URL);
  readonly manualTaskPaymentButtonLabel = signal(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly manualTaskAmountRubles = signal('');
  readonly manualTaskComment = signal('');
  readonly manualTaskMessage = signal<string | null>(null);

  readonly teamSections = TEAM_SECTIONS;

  constructor(
    readonly auth: AuthService,
    private readonly api: ApiService,
    private readonly route: ActivatedRoute,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.applyRouteSection(this.route.snapshot.paramMap);
    this.applyMobileNavIntent(this.route.snapshot.queryParamMap);
    this.lastMobileNavKey = this.mobileNavKey(this.route.snapshot.queryParamMap);
    void this.reload();

    this.routeSubscription = this.route.paramMap.subscribe((params) => {
      const changed = this.applyRouteSection(params);
      if (changed) {
        void this.reload();
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
    this.closeSectionSheet();
    this.routeSubscription?.unsubscribe();
    this.querySubscription?.unsubscribe();
    this.routerEventsSubscription?.unsubscribe();
  }

  navLinks(): HomeSectionLink[] {
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
    this.selectedDate.set(value || this.todayIso());
    if (this.analyticsMode() === 'lastTwoYears') {
      this.periodFrom.set(this.defaultPeriodFromIso(this.selectedDate()));
      this.periodTo.set(this.selectedDate());
    }
    void this.reload();
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
    if (!this.auth.isAuthenticated()) {
      await this.auth.login('/tabs/home');
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    try {
      switch (this.activeSection()) {
        case 'profile':
          this.profile.set(await firstValueFrom(this.api.getCabinetProfile(this.selectedDate(), { forceRefresh })));
          await this.loadManualPaymentSettings(forceRefresh);
          await this.loadManualPaymentTasks(forceRefresh);
          break;
        case 'team':
          this.team.set(await firstValueFrom(this.api.getCabinetTeam(this.selectedDate(), { forceRefresh })));
          break;
        case 'score':
          this.score.set(await firstValueFrom(this.api.getCabinetScore(this.selectedDate(), { forceRefresh })));
          break;
        case 'analytics':
          this.analytics.set(await firstValueFrom(this.api.getCabinetAnalytics(this.selectedDate(), this.analyticsOptions(forceRefresh))));
          break;
        case 'dictionaries':
          this.dictionarySummary.set(await firstValueFrom(this.api.getDictionarySummary(this.canManageAllDictionaries())));
          break;
      }
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
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

  profileRows(): Row[] {
    const profile = this.profile();
    const stats = profile?.workerZp;
    return [
      { label: 'Лиды', value: this.count(profile?.user?.leadCount ?? 0) },
      { label: 'Отзывы', value: this.count(profile?.user?.reviewCount ?? 0) },
      { label: 'За день', value: this.money(stats?.sum1Day ?? 0) },
      { label: 'За неделю', value: this.money(stats?.sum1Week ?? 0) },
      { label: 'За месяц', value: this.money(stats?.sum1Month ?? 0) },
      { label: 'За год', value: this.money(stats?.sum1Year ?? 0) }
    ];
  }

  showManualPaymentSettings(): boolean {
    return this.isManagerUser() && Boolean(this.manualPaymentSettings()?.manualPaymentEnabled);
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
      || this.manualPaymentUrl().trim() !== this.manualPaymentUrlOrDefault(settings.manualPaymentUrl)
      || this.manualPaymentButtonLabel().trim() !== this.manualPaymentButtonLabelOrDefault(settings.manualPaymentButtonLabel);
  }

  canCreateManualTask(): boolean {
    const hasTarget = this.manualTaskPaymentType() === 'MOBILE_BANK'
      ? Boolean(this.manualTaskPhone().trim()) && Boolean(this.manualTaskRecipient().trim())
      : Boolean(this.manualTaskPaymentUrl().trim()) && Boolean(this.manualTaskRecipient().trim());
    return !this.manualTaskSaving()
      && hasTarget
      && this.manualTaskTargetKopecks() > 0;
  }

  setManualPaymentType(value: ManualPaymentType): void {
    this.manualPaymentType.set(value);
    if (value === 'EXTERNAL_LINK' && !this.manualPaymentUrl().trim()) {
      this.manualPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
    }
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
    const manualPaymentUrl = this.manualPaymentUrl().trim() || DEFAULT_MANUAL_PAYMENT_URL;
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
    if (value === 'EXTERNAL_LINK' && !this.manualTaskPaymentUrl().trim()) {
      this.manualTaskPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
    }
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
  }

  setManualTaskComment(value: string): void {
    this.manualTaskComment.set(value ?? '');
  }

  async createManualPaymentTask(): Promise<void> {
    if (!this.canCreateManualTask()) {
      return;
    }

    this.manualTaskSaving.set(true);
    this.manualTaskMessage.set(null);
    try {
      const task = await firstValueFrom(this.api.createManagerManualPaymentTask({
        manualPaymentType: this.manualTaskPaymentType(),
        manualPhone: this.manualTaskPhone().trim(),
        manualRecipientName: this.manualTaskRecipient().trim() || DEFAULT_MANUAL_RECIPIENT_NAME,
        manualPaymentUrl: this.manualTaskPaymentUrl().trim() || DEFAULT_MANUAL_PAYMENT_URL,
        manualPaymentButtonLabel: this.manualTaskPaymentButtonLabel().trim() || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
        targetAmountKopecks: this.manualTaskTargetKopecks(),
        comment: this.manualTaskComment().trim() || null
      }));
      this.manualPaymentTasks.update((tasks) => [task, ...tasks]);
      this.manualTaskPaymentType.set(DEFAULT_MANUAL_PAYMENT_TYPE);
      this.manualTaskPhone.set('');
      this.manualTaskRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
      this.manualTaskPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
      this.manualTaskPaymentButtonLabel.set(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
      this.manualTaskAmountRubles.set('');
      this.manualTaskComment.set('');
      this.manualTaskMessage.set('Задание создано.');
    } catch (error) {
      const message = this.errorMessage(error);
      this.manualTaskMessage.set(message);
      this.error.set(message);
    } finally {
      this.manualTaskSaving.set(false);
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
      return `${this.manualPaymentUrlOrDefault(task.manualPaymentUrl)} · ${profile}`;
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

  teamRows(key: TeamKey, member: TeamMember): Row[] {
    if (key === 'managers') {
      return [
        { label: 'ЗП', value: this.money(member.sum1Month) },
        { label: 'Выручка', value: this.money(member.payment1Month) },
        { label: 'Заказы', value: this.count(member.order1Month) },
        { label: 'Отзывы', value: this.count(member.review1Month) }
      ];
    }

    if (key === 'workers') {
      return [
        { label: 'ЗП', value: this.money(member.sum1Month) },
        { label: 'Заказы', value: this.count(member.order1Month) },
        { label: 'Отзывы', value: this.count(member.review1Month) },
        { label: 'В работе', value: this.count((member.newOrder || 0) + (member.inCorrect || 0) + (member.intVigul || 0) + (member.publish || 0)) }
      ];
    }

    return [
      { label: 'ЗП', value: this.money(member.sum1Month) },
      { label: 'Новые', value: this.count(member.leadsNew) },
      { label: 'В работе', value: this.count(member.leadsInWork) },
      { label: 'Конверсия', value: `${member.percentInWork || 0}%` }
    ];
  }

  scoreUsers(key: TeamKey): ScoreUser[] {
    return this.score()?.groups[key] ?? [];
  }

  scoreRows(key: TeamKey, user: ScoreUser): Row[] {
    const finance = this.score()?.financeVisible;
    const rows: Row[] = [];
    if (finance) {
      rows.push({ label: 'ЗП', value: this.money(user.salary) });
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
    void this.auth.logout();
  }

  private async loadManualPaymentSettings(forceRefresh = false): Promise<void> {
    if (!this.isManagerUser()) {
      this.clearManualPaymentSettings();
      return;
    }

    this.manualPaymentLoading.set(true);
    this.manualPaymentMessage.set(null);
    try {
      const settings = await firstValueFrom(this.api.getManagerManualPaymentSettings({ forceRefresh }));
      this.applyManualPaymentSettings(settings);
    } catch (error) {
      const message = this.errorMessage(error);
      this.clearManualPaymentSettings();
      this.error.set(message);
    } finally {
      this.manualPaymentLoading.set(false);
    }
  }

  private async loadManualPaymentTasks(forceRefresh = false): Promise<void> {
    if (!this.isManagerUser()) {
      this.clearManualPaymentTasks();
      return;
    }

    this.manualTaskLoading.set(true);
    this.manualTaskMessage.set(null);
    try {
      const tasks = await firstValueFrom(this.api.getManagerManualPaymentTasks({ forceRefresh }));
      this.manualPaymentTasks.set(tasks ?? []);
    } catch (error) {
      const message = this.errorMessage(error);
      this.manualPaymentTasks.set([]);
      this.manualTaskMessage.set(message);
    } finally {
      this.manualTaskLoading.set(false);
    }
  }

  private applyManualPaymentSettings(settings: ManagerManualPaymentSettings): void {
    this.manualPaymentSettings.set(settings);
    this.manualPaymentType.set(this.normalizeManualPaymentType(settings.manualPaymentType));
    this.manualPaymentPhone.set(settings.manualPhone ?? '');
    this.manualPaymentRecipient.set(this.manualRecipientOrDefault(settings.manualRecipientName));
    this.manualPaymentUrl.set(this.manualPaymentUrlOrDefault(settings.manualPaymentUrl));
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
    this.manualTaskMessage.set(null);
  }

  private manualTaskTargetKopecks(): number {
    const value = Number(this.manualTaskAmountRubles());
    return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : 0;
  }

  private normalizeManualPaymentType(value?: string | null): ManualPaymentType {
    return value === 'EXTERNAL_LINK' ? 'EXTERNAL_LINK' : DEFAULT_MANUAL_PAYMENT_TYPE;
  }

  private manualPaymentUrlOrDefault(value?: string | null): string {
    const clean = (value ?? '').trim();
    return clean || DEFAULT_MANUAL_PAYMENT_URL;
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

  private defaultPeriodFromIso(dateIso: string): string {
    const year = Number(dateIso.slice(0, 4)) || new Date().getFullYear();
    return `${year - 1}-01-01`;
  }

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
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
