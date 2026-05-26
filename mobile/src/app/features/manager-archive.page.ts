import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  IonContent,
  IonRefresher,
  IonRefresherContent,
  RefresherCustomEvent
} from '@ionic/angular/standalone';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  ApiService,
  ArchiveOrderDetailsPayload,
  ArchiveOrderListItem,
  ArchiveOrderMode,
  ArchivePaymentCheckItem,
  ArchiveReviewItem,
  ArchiveRestoreResult,
  ArchiveZpItem,
  Page
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MOBILE_ACTIONS, MOBILE_SECTIONS, canUseAction } from '../core/mobile-permissions';
import { MobileBottomPagerComponent } from '../shared/mobile-bottom-pager.component';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileRemindersComponent } from '../shared/mobile-reminders.component';
import { MobileSearchBarComponent } from '../shared/mobile-search-bar.component';
import { MobileStatusSliderComponent, type MobileStatusItem } from '../shared/mobile-status-slider.component';
import {
  mobilePageIndex,
  mobilePageIsFirst,
  mobilePageIsLast,
  mobilePageLabel,
  mobilePageTotal,
  mobileToneFromClass
} from '../shared/mobile-board.helpers';
import { displayPhone, normalizePhoneDigits } from '../shared/phone-format';

type ArchiveModeTab = {
  key: ArchiveOrderMode;
  label: string;
  short: string;
  icon: string;
  tone: string;
};

type ArchiveLiveStatusAction = {
  label: string;
  status: string;
};

const ARCHIVE_MODE_TABS: ArchiveModeTab[] = [
  { key: 'all', label: 'Все закрытые', short: 'все', icon: 'inventory_2', tone: 'tone-blue' },
  { key: 'archive', label: 'Архив', short: 'архив', icon: 'archive', tone: 'tone-gray' },
  { key: 'paid', label: 'Оплачено', short: 'оплачено', icon: 'payments', tone: 'tone-green' }
];

const LIVE_STATUS_ACTIONS: ArchiveLiveStatusAction[] = [
  { label: 'новый', status: 'Новый' },
  { label: 'коррекция', status: 'Коррекция' },
  { label: 'на проверке', status: 'На проверке' }
];

const RESTORE_STATUS_OPTIONS = ['Новый', 'Коррекция', 'На проверке'] as const;

const EMPTY_ARCHIVE_PAGE: Page<ArchiveOrderListItem> = {
  content: [],
  number: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true
};

@Component({
  selector: 'app-manager-archive',
  imports: [FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileBottomPagerComponent, MobileHeaderComponent, MobileRemindersComponent, MobileSearchBarComponent, MobileStatusSliderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Архив" />

      <ion-content fullscreen [scrollY]="false">
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <app-mobile-reminders #reminders />

        <main class="archive-mobile-page">
          <app-mobile-status-slider
            [items]="modeItems()"
            [activeKey]="mode()"
            ariaLabel="Разделы архива"
            (select)="selectModeItem($event)"
          />

          <app-mobile-search-bar
            [value]="keyword()"
            placeholder="Компания, телефон, заказ"
            [showRefresh]="false"
            [hasExtraAction]="true"
            (valueChange)="setKeyword($event)"
            (searchSubmit)="applySearch()"
          >
            @if (keyword() || appliedKeyword()) {
              <button mobileSearchActions type="button" (click)="clearSearch()" [disabled]="loading()" aria-label="Сбросить поиск">
                <span class="material-icons-sharp">close</span>
              </button>
            }

            <button mobileSearchActions type="button" (click)="applySearch()" [disabled]="loading()" aria-label="Найти">
              <span class="material-icons-sharp">manage_search</span>
            </button>

            <button mobileSearchActions type="button" (click)="reload()" [disabled]="loading()" aria-label="Обновить архив">
              <span class="material-icons-sharp">refresh</span>
            </button>
          </app-mobile-search-bar>

          @if (error()) {
            <button class="inline-alert" type="button" (click)="reload()">
              <span class="material-icons-sharp">error</span>
              <span>{{ error() }}</span>
            </button>
          }

          <section class="lead-list archive-list" aria-label="Архивные заказы">
            @for (order of orders(); track order.id) {
              <article [class]="'lead-card archive-order-card ' + orderTone(order)">
                <header class="lead-card-head order-card-head">
                  @if (archiveOrderFilialUrl(order); as filialUrl) {
                    <a [href]="filialUrl" target="_blank" rel="noopener" [title]="archiveOrderTitle(order)">
                      {{ archiveOrderTitle(order) }}
                    </a>
                  } @else {
                    <strong>{{ archiveOrderTitle(order) }}</strong>
                  }

                  <span>
                    <small>#{{ order.id }}</small>
                    <em [class.archive-source-live]="order.source === 'live'">{{ archiveSourceLabel(order) }}</em>
                  </span>
                </header>

                <div class="lead-meta-row order-main-meta">
                  <span>{{ order.status || '-' }}</span>
                  <span>{{ orderMoney(order.sum) }}</span>
                </div>

                <div class="lead-phone-row order-phone-row">
                  <a [href]="archiveOrderChatUrl(order)" target="_blank" rel="noopener" (click)="guardLink($event, archiveOrderChatUrl(order))">
                    {{ orderPhone(order) }}
                  </a>
                  <button type="button" (click)="copyOrderPhone(order)" aria-label="Скопировать телефон">
                    {{ copiedKey() === 'archive-phone-' + order.id ? '✓' : 'T' }}
                  </button>
                </div>

                <div class="lead-card-actions order-link-actions">
                  <button type="button" (click)="copyArchiveReviewText(order)" [disabled]="!hasArchiveReviewLink(order)">
                    {{ copiedKey() === 'archive-review-' + order.id ? '✓' : 'текст' }}
                  </button>
                  <a
                    [href]="archiveReviewUrl(order)"
                    target="_blank"
                    rel="noopener"
                    [class.disabled]="!archiveReviewUrl(order)"
                    (click)="guardLink($event, archiveReviewUrl(order))"
                  >url</a>
                  <button type="button" (click)="copyArchivePaymentText(order)">
                    {{ copiedKey() === 'archive-payment-' + order.id ? '✓' : 'счет' }}
                  </button>
                  <a [href]="archiveOrderChatUrl(order)" target="_blank" rel="noopener" (click)="guardLink($event, archiveOrderChatUrl(order))">
                    чат
                  </a>
                </div>

                <div class="order-progress" aria-label="Прогресс заказа">
                  <span [style.width.%]="orderProgress(order)">
                    <em>{{ order.counter || 0 }}</em>
                  </span>
                </div>

                <div class="lead-meta-row archive-facts-row">
                  <span>{{ order.orderDetailsCount || 0 }} деталей</span>
                  <span>{{ order.reviewsCount || 0 }} отзывов</span>
                </div>

                <div class="lead-meta-row order-category-row">
                  <span [title]="order.companyCity || 'Город не указан'">{{ order.companyCity || 'Город' }}</span>
                  <span [title]="order.managerName || 'Менеджер не указан'">{{ order.managerName || 'Менеджер' }}</span>
                </div>

                @if (canChangeLiveStatus(order)) {
                  <div class="lead-card-actions archive-live-actions" aria-label="Вернуть live-заказ в работу">
                    @for (action of liveStatusActions; track action.status) {
                      <button
                        type="button"
                        (click)="changeLiveStatus(order, action)"
                        [disabled]="!!liveStatusMutationKey()"
                      >
                        {{ isLiveStatusMutating(order, action) ? '...' : action.label }}
                      </button>
                    }
                  </div>
                }

                <button class="company-details-button order-details-button" type="button" (click)="openOrder(order)">
                  Подробнее
                </button>

                <footer class="lead-card-foot order-card-foot">
                  <button
                    class="order-unchanged"
                    type="button"
                    [class.order-unchanged--alert]="orderAgeDays(order) >= 2"
                    [title]="dateText(order.changed || order.payDay || order.created)"
                  >
                    @if (orderAgeDays(order) >= 2) {
                      <i aria-hidden="true">!</i>
                    }
                    Без изменений: {{ orderAgeDays(order) }} дн.
                  </button>
                  <button type="button" [title]="order.workerName || 'Исполнитель не назначен'">
                    {{ workerLabel(order) }}
                  </button>
                </footer>
              </article>
            } @empty {
              <div class="empty-state compact-empty">Заказов в архиве нет.</div>
            }
          </section>

          <app-mobile-bottom-pager
            class="mobile-page-bottom-pager"
            [pageIndex]="currentPageIndex() - 1"
            [totalPages]="currentPageTotal()"
            [disabled]="loading()"
            (previous)="previousPage()"
            (next)="nextPage()"
          >
            <button mobilePagerActions class="expand-list-button reminder-hero-button" type="button" (click)="reminders.open()" aria-label="Напоминания">
              <span class="material-icons-sharp">notifications_active</span>
              @if (reminders.activeReminderCount()) {
                <small>{{ reminders.activeReminderCount() }}</small>
              }
            </button>
            <button
              mobilePagerActions
              class="expand-list-button"
              type="button"
              [class.active]="sortDirection() === 'asc'"
              (click)="toggleSortDirection()"
              [attr.aria-label]="sortDirection() === 'asc' ? 'Показать сначала новые архивные' : 'Показать сначала старые архивные'"
            >
              <span class="material-icons-sharp">swap_vert</span>
            </button>
          </app-mobile-bottom-pager>
        </main>

        @if (detailsOrder(); as order) {
          <button class="archive-detail-backdrop" type="button" aria-label="Закрыть архивный заказ" (click)="closeDetails()"></button>
          <section class="archive-detail-sheet" role="dialog" aria-modal="true" aria-label="Подробности архивного заказа">
            <header>
              <div>
                <small>Архивный заказ #{{ order.id }}</small>
                <h2>{{ archiveOrderTitle(order) }}</h2>
              </div>
              <button type="button" class="icon-button" (click)="closeDetails()" [disabled]="restoring()" aria-label="Закрыть">
                <span class="material-icons-sharp">close</span>
              </button>
            </header>

            @if (detailsError()) {
              <p class="sheet-error">{{ detailsError() }}</p>
            }

            @if (detailsLoading()) {
              <div class="archive-loading">
                <span class="material-icons-sharp">hourglass_top</span>
                <p>Загружаю состав заказа</p>
              </div>
            } @else if (detailsPayload(); as payload) {
              <section class="archive-modal-actions">
                <button type="button" (click)="copyArchiveReviewText(order, payload)" [disabled]="!hasArchiveReviewLink(order, payload)">
                  {{ copiedKey() === 'archive-review-' + order.id ? '✓' : 'текст' }}
                </button>
                <a [href]="archiveOrderChatUrl(order)" target="_blank" rel="noopener">чат</a>
              </section>

              @if (canRestore(order) && !restoreResult()) {
                <section class="archive-restore-box">
                  <header>
                    <span class="material-icons-sharp">settings_backup_restore</span>
                    <div>
                      <h3>Восстановление</h3>
                      <p>Вернуть заказ в live-таблицы с выбранным статусом.</p>
                    </div>
                  </header>

                  <div class="restore-status-segment" role="radiogroup" aria-label="Статус после восстановления">
                    @for (status of restoreStatuses; track status) {
                      <button
                        type="button"
                        role="radio"
                        [attr.aria-checked]="restoreTargetStatus() === status"
                        [class.active]="restoreTargetStatus() === status"
                        (click)="restoreTargetStatus.set(status)"
                      >
                        {{ status }}
                      </button>
                    }
                  </div>

                  <button type="button" class="restore-confirm" (click)="confirmRestore()" [disabled]="restoring()">
                    <span class="material-icons-sharp">settings_backup_restore</span>
                    {{ restoring() ? 'Восстанавливаю' : 'Восстановить' }}
                  </button>
                </section>
              } @else if (paidRestoreRestricted(order)) {
                <section class="archive-readonly-note">
                  <span class="material-icons-sharp">lock</span>
                  <p>Оплаченный заказ может восстановить только администратор или владелец.</p>
                </section>
              }

              <section class="archive-summary-grid">
                <article>
                  <span>Строк всего</span>
                  <strong>{{ restoreRowsTotal(payload) }}</strong>
                </article>
                <article>
                  <span>Детали</span>
                  <strong>{{ payload.details.length }}</strong>
                </article>
                <article>
                  <span>Отзывы</span>
                  <strong>{{ payload.reviews.length }}</strong>
                </article>
                <article>
                  <span>Источник</span>
                  <strong>{{ order.source === 'archive' ? 'archive_*' : 'live' }}</strong>
                </article>
              </section>

              <section class="archive-detail-section">
                <header>
                  <h3>Детали заказа</h3>
                  <span>{{ payload.details.length }}</span>
                </header>
                <div class="archive-mini-list">
                  @for (detail of payload.details; track detail.id) {
                    <article>
                      <strong>{{ detail.productTitle || 'Без продукта' }}</strong>
                      <span>{{ detail.amount || 0 }} шт.</span>
                      <span>{{ orderMoney(detail.price) }}</span>
                      @if (detail.comment) {
                        <p>{{ detail.comment }}</p>
                      }
                    </article>
                  } @empty {
                    <p class="archive-empty-line">Деталей нет</p>
                  }
                </div>
              </section>

              <section class="archive-detail-section">
                <header>
                  <h3>Отзывы</h3>
                  <span>{{ payload.reviews.length }}</span>
                </header>
                <div class="archive-review-list">
                  @for (review of payload.reviews; track review.id) {
                    <article>
                      <header>
                        <strong>{{ review.productTitle || review.category || 'Отзыв' }}</strong>
                        <span>{{ review.publish ? 'опубликован' : 'не опубликован' }}</span>
                      </header>
                      <p>{{ review.text || 'Текст отзыва пустой' }}</p>
                      @if (review.answer) {
                        <p class="review-answer">{{ review.answer }}</p>
                      }
                      <footer>
                        <span>{{ review.publishedDate || review.changed || review.created || '-' }}</span>
                        <span>{{ review.workerFio || order.workerName || '-' }}</span>
                      </footer>
                    </article>
                  } @empty {
                    <p class="archive-empty-line">Отзывов нет</p>
                  }
                </div>
              </section>

              @if (canSeeArchiveFinance()) {
                <section class="archive-finance-grid">
                  <article>
                    <header>
                      <h3>Оплаты</h3>
                      <span>{{ payload.paymentChecks.length }}</span>
                    </header>
                    @for (payment of payload.paymentChecks; track trackPayment(payment)) {
                      <p>
                        <strong>{{ payment.title || 'Оплата' }}</strong>
                        <span>{{ orderMoney(payment.sum) }}</span>
                      </p>
                    } @empty {
                      <p class="archive-empty-line">Оплат нет</p>
                    }
                  </article>
                  <article>
                    <header>
                      <h3>ЗП</h3>
                      <span>{{ payload.zp.length }}</span>
                    </header>
                    @for (zp of payload.zp; track trackZp(zp)) {
                      <p>
                        <strong>{{ zp.fio || 'Сотрудник' }}</strong>
                        <span>{{ orderMoney(zp.sum) }}</span>
                      </p>
                    } @empty {
                      <p class="archive-empty-line">ЗП нет</p>
                    }
                  </article>
                </section>
              }

              @if (payload.orderComments) {
                <section class="archive-comments">
                  <strong>Заметка заказа</strong>
                  <p>{{ payload.orderComments }}</p>
                </section>
              }
            }

            @if (restoreResult(); as result) {
              <section class="archive-restore-result">
                <span class="material-icons-sharp">task_alt</span>
                <p>Batch #{{ result.batchId }}: заказ восстановлен в статус {{ result.targetStatus }}.</p>
              </section>
            }
          </section>
        }
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content { --overflow: hidden; }

    .archive-mobile-page {
      display: flex;
      height: 100%;
      max-width: 44rem;
      min-height: 0;
      margin: 0 auto;
      overflow: hidden;
      flex-direction: column;
      gap: 0.65rem;
      padding: 0.75rem 0.75rem calc(0.7rem + env(safe-area-inset-bottom));
    }

    .archive-mode-scroll {
      display: flex;
      gap: 0.5rem;
      flex: 0 0 auto;
      margin-inline: -0.15rem;
      overflow-x: auto;
      padding: 0.05rem 0.15rem 0.12rem;
      scrollbar-width: none;
    }

    .archive-mode-scroll::-webkit-scrollbar {
      display: none;
    }

    .archive-mode-scroll .metric-tile {
      flex: 0 0 7.3rem;
      min-height: 3.45rem;
      border: 1px solid var(--status-menu-border, rgba(108, 155, 207, 0.28));
      background: linear-gradient(155deg, var(--status-menu-surface, var(--otziv-tone-walk-surface)) 0%, var(--otziv-white) 82%);
      box-shadow: 0 0.7rem 1.45rem rgba(132, 139, 200, 0.09);
    }

    .archive-mode-scroll .metric-tile .material-icons-sharp {
      font-size: 1.35rem;
    }

    .archive-mode-scroll .metric-tile strong {
      font-size: 1.05rem;
      font-weight: 1000;
    }

    .archive-mode-scroll .metric-tile small {
      font-size: 0.58rem;
      line-height: 1;
    }

    .archive-mode-scroll .metric-tile.active {
      border-color: rgba(108, 155, 207, 0.5);
      background: linear-gradient(155deg, var(--status-menu-surface, var(--otziv-tone-walk-surface)) 0%, rgba(108, 155, 207, 0.14) 100%);
    }

    .archive-search-strip {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto auto;
      align-items: center;
      gap: 0.45rem;
      flex: 0 0 auto;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 1rem;
      padding: 0.48rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 0.8rem 1.6rem rgba(132, 139, 200, 0.1);
    }

    .archive-search-strip label {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.45rem;
      min-width: 0;
      min-height: 2.42rem;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.82rem;
      padding: 0 0.7rem;
      color: var(--otziv-info);
      background: var(--otziv-white);
    }

    .archive-search-strip input {
      min-width: 0;
      border: 0;
      outline: 0;
      color: var(--otziv-dark);
      background: transparent;
      font: 900 0.82rem/1 var(--otziv-font-family);
    }

    .archive-search-strip input::placeholder {
      color: var(--otziv-info);
      opacity: 1;
    }

    .archive-search-strip .material-icons-sharp {
      font-size: 1.12rem;
    }

    .archive-search-strip .icon-button,
    .archive-detail-sheet .icon-button {
      display: inline-grid;
      width: 2.42rem;
      height: 2.42rem;
      min-width: 2.42rem;
      place-items: center;
      border: 0;
      border-radius: 0.82rem;
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 92%);
      font: inherit;
    }

    .archive-list {
      flex: 1 1 0;
      gap: 0.72rem;
      overflow-y: hidden;
    }

    .archive-list .archive-order-card {
      scroll-snap-align: center;
      gap: clamp(0.42rem, 0.75vh, 0.6rem);
      justify-content: space-between;
    }

    .archive-order-card .lead-card-head a,
    .archive-order-card .lead-card-head strong {
      display: -webkit-box;
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: 0.98rem;
      font-weight: 1000;
      line-height: 1.06;
      text-decoration: none;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }

    .archive-order-card .lead-card-head small {
      font-size: 0.62rem;
      white-space: nowrap;
    }

    .archive-order-card .lead-card-head em {
      width: auto;
      min-width: 1.55rem;
      height: 1.24rem;
      padding: 0 0.38rem;
      color: #8a6a11;
      background: rgba(247, 208, 96, 0.24);
      font-size: 0.55rem;
      text-transform: uppercase;
    }

    .archive-order-card .lead-card-head em.archive-source-live {
      color: var(--otziv-success);
      background: rgba(27, 156, 133, 0.14);
    }

    .archive-order-card .lead-phone-row a,
    .archive-order-card .lead-phone-row button,
    .archive-order-card .lead-meta-row span,
    .archive-order-card .lead-card-actions button,
    .archive-order-card .lead-card-actions a {
      min-height: 1.62rem;
      font-size: 0.7rem;
      font-weight: 900;
    }

    .archive-order-card .lead-card-actions a,
    .archive-modal-actions a {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: 0;
      border: 1px solid rgba(103, 116, 131, 0.24);
      border-radius: 999px;
      padding: 0 0.2rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      text-decoration: none;
      text-overflow: ellipsis;
      white-space: nowrap;
      overflow: hidden;
    }

    .archive-order-card .lead-card-actions a.disabled {
      pointer-events: none;
      opacity: 0.48;
    }

    .order-progress {
      overflow: hidden;
      height: 0.45rem;
      border-radius: 999px;
      background: rgba(115, 128, 236, 0.2);
    }

    .order-progress span {
      display: grid;
      height: 100%;
      min-width: 1.4rem;
      max-width: 100%;
      place-items: center;
      border-radius: inherit;
      background: #c9d6ef;
    }

    .order-progress em {
      color: #52657e;
      font-size: 0.48rem;
      font-style: normal;
      font-weight: 1000;
      line-height: 1;
    }

    .archive-live-actions {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    .company-details-button {
      display: inline-flex;
      min-height: 1.72rem;
      align-items: center;
      justify-content: center;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 999px;
      color: var(--otziv-info);
      background: var(--otziv-white);
      font: inherit;
      font-size: 0.67rem;
      font-weight: 900;
      text-transform: uppercase;
    }

    .order-card-foot .order-unchanged {
      color: var(--otziv-danger);
      text-align: left;
    }

    .order-card-foot .order-unchanged i {
      display: inline-grid;
      width: 0.9rem;
      height: 0.9rem;
      margin-right: 0.12rem;
      place-items: center;
      border-radius: 999px;
      color: #fff;
      background: var(--otziv-danger);
      font-style: normal;
      font-size: 0.56rem;
      line-height: 1;
    }

    .archive-bottom-controls {
      border-color: var(--otziv-tone-walk-border);
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
    }

    .archive-detail-backdrop {
      position: fixed;
      inset: 0;
      z-index: 45;
      border: 0;
      padding: 0;
      background: rgba(24, 26, 30, 0.46);
      backdrop-filter: blur(2px);
    }

    .archive-detail-sheet {
      position: fixed;
      inset: auto 0 0;
      z-index: 46;
      display: grid;
      max-height: min(88vh, 48rem);
      overflow: auto;
      gap: 0.72rem;
      border-radius: 1.2rem 1.2rem 0 0;
      padding: 0.9rem 0.9rem calc(0.95rem + env(safe-area-inset-bottom));
      color: var(--otziv-dark);
      background: rgba(246, 246, 249, 0.98);
      box-shadow: 0 -1rem 2.7rem rgba(54, 57, 73, 0.22);
      font-family: var(--otziv-card-title-font);
    }

    .archive-detail-sheet > header,
    .archive-restore-box header,
    .archive-detail-section > header,
    .archive-review-list article header,
    .archive-review-list article footer,
    .archive-finance-grid header,
    .archive-finance-grid p,
    .archive-restore-result,
    .archive-readonly-note {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.65rem;
      min-width: 0;
    }

    .archive-detail-sheet small,
    .archive-summary-grid span,
    .archive-detail-section header span,
    .archive-empty-line {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 900;
      text-transform: uppercase;
    }

    .archive-detail-sheet h2,
    .archive-detail-sheet h3,
    .archive-detail-sheet p {
      margin: 0;
    }

    .archive-detail-sheet h2 {
      margin-top: 0.12rem;
      font-size: 1.25rem;
      line-height: 1.08;
      overflow-wrap: anywhere;
    }

    .archive-modal-actions {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem;
    }

    .archive-modal-actions button,
    .archive-modal-actions a,
    .restore-status-segment button,
    .restore-confirm {
      min-height: 2rem;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: inherit;
      font-size: 0.72rem;
      font-weight: 900;
    }

    .archive-restore-box,
    .archive-detail-section,
    .archive-finance-grid > article,
    .archive-comments,
    .archive-summary-grid article,
    .archive-readonly-note,
    .archive-restore-result {
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.95rem;
      padding: 0.72rem;
      background: var(--otziv-white);
      box-shadow: 0 0.65rem 1.25rem rgba(132, 139, 200, 0.1);
    }

    .archive-restore-box {
      display: grid;
      gap: 0.65rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
    }

    .archive-restore-box header {
      justify-content: flex-start;
    }

    .archive-restore-box header > span {
      display: grid;
      width: 2rem;
      height: 2rem;
      flex: 0 0 auto;
      place-items: center;
      border-radius: 999px;
      color: var(--otziv-primary);
      background: rgba(108, 155, 207, 0.14);
    }

    .archive-restore-box p,
    .archive-comments p,
    .archive-review-list p,
    .archive-mini-list p {
      color: var(--otziv-info);
      font-size: 0.76rem;
      font-weight: 900;
      line-height: 1.28;
    }

    .restore-status-segment {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.42rem;
    }

    .restore-status-segment button.active {
      border-color: rgba(108, 155, 207, 0.46);
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }

    .restore-confirm {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.35rem;
      color: #fff;
      background: var(--otziv-primary);
    }

    .archive-summary-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem;
    }

    .archive-summary-grid strong {
      display: block;
      margin-top: 0.12rem;
      color: var(--otziv-dark);
      font-size: 1rem;
    }

    .archive-detail-section,
    .archive-mini-list,
    .archive-review-list,
    .archive-finance-grid > article,
    .archive-comments {
      display: grid;
      gap: 0.55rem;
    }

    .archive-mini-list,
    .archive-review-list {
      max-height: 16rem;
      overflow: auto;
    }

    .archive-mini-list article,
    .archive-review-list article {
      display: grid;
      gap: 0.38rem;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.75rem;
      padding: 0.58rem;
      background: var(--otziv-field-background);
    }

    .archive-mini-list article {
      grid-template-columns: minmax(0, 1fr) auto auto;
      align-items: center;
    }

    .archive-mini-list strong,
    .archive-mini-list span,
    .archive-review-list strong,
    .archive-review-list span,
    .archive-finance-grid strong,
    .archive-finance-grid span {
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: 0.72rem;
      font-weight: 900;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .archive-mini-list p,
    .archive-review-list p {
      grid-column: 1 / -1;
    }

    .archive-review-list .review-answer {
      opacity: 0.72;
    }

    .archive-finance-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.55rem;
    }

    .archive-finance-grid p {
      border: 1px solid rgba(103, 116, 131, 0.12);
      border-radius: 0.75rem;
      padding: 0.48rem;
      background: var(--otziv-field-background);
    }

    .archive-loading {
      display: grid;
      min-height: 8rem;
      place-items: center;
      align-content: center;
      gap: 0.35rem;
      color: var(--otziv-info);
      font-weight: 900;
    }

    .archive-readonly-note {
      justify-content: flex-start;
      color: var(--otziv-info);
      font-size: 0.78rem;
      font-weight: 900;
    }

    .archive-restore-result {
      justify-content: flex-start;
      color: var(--otziv-success);
      font-size: 0.82rem;
      font-weight: 900;
      background: var(--otziv-tone-success-surface);
    }

    @media (min-width: 40rem) {
      .archive-detail-sheet {
        left: 50%;
        right: auto;
        bottom: 1rem;
        width: min(42rem, calc(100vw - 2rem));
        border-radius: 1.2rem;
        transform: translateX(-50%);
      }
    }
  `]
})
export class ManagerArchivePage implements OnInit, OnDestroy {
  private searchTimer: ReturnType<typeof setTimeout> | null = null;
  private loadingDetailsOrderId: number | null = null;

  readonly modeTabs = ARCHIVE_MODE_TABS;
  readonly liveStatusActions = LIVE_STATUS_ACTIONS;
  readonly restoreStatuses = RESTORE_STATUS_OPTIONS;
  readonly page = signal<Page<ArchiveOrderListItem>>(EMPTY_ARCHIVE_PAGE);
  readonly mode = signal<ArchiveOrderMode>('all');
  readonly keyword = signal('');
  readonly appliedKeyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly sortDirection = signal<'desc' | 'asc'>('desc');
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly copiedKey = signal<string | null>(null);
  readonly detailsOrder = signal<ArchiveOrderListItem | null>(null);
  readonly detailsPayload = signal<ArchiveOrderDetailsPayload | null>(null);
  readonly detailsLoading = signal(false);
  readonly detailsError = signal<string | null>(null);
  readonly restoreTargetStatus = signal<(typeof RESTORE_STATUS_OPTIONS)[number]>('Новый');
  readonly restoreResult = signal<ArchiveRestoreResult | null>(null);
  readonly restoring = signal(false);
  readonly liveStatusMutationKey = signal<string | null>(null);

  readonly orders = computed(() => this.page().content ?? []);
  readonly modeItems = computed<MobileStatusItem[]>(() =>
    this.modeTabs.map((tab) => ({
      key: tab.key,
      title: tab.short,
      value: this.modeValue(tab),
      icon: tab.icon,
      tone: this.sliderTone(tab.tone)
    }))
  );

  constructor(
    private readonly api: ApiService,
    private readonly auth: AuthService,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    void this.load();
  }

  ngOnDestroy(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
  }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    await this.load();
    event.target.complete();
  }

  reload(): void {
    void this.load();
  }

  setMode(mode: ArchiveOrderMode): void {
    if (this.mode() === mode) {
      return;
    }

    this.mode.set(mode);
    this.pageNumber.set(0);
    void this.load().finally(() => this.resetListScroll());
  }

  selectModeItem(mode: string): void {
    this.setMode(mode as ArchiveOrderMode);
  }

  setKeyword(value: string): void {
    this.keyword.set(value);
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }

    this.searchTimer = setTimeout(() => this.applySearch(), 1000);
  }

  applySearch(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
      this.searchTimer = null;
    }

    this.appliedKeyword.set(this.keyword().trim());
    this.pageNumber.set(0);
    void this.load().finally(() => this.resetListScroll());
  }

  clearSearch(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
      this.searchTimer = null;
    }

    this.keyword.set('');
    this.appliedKeyword.set('');
    this.pageNumber.set(0);
    void this.load().finally(() => this.resetListScroll());
  }

  previousPage(): void {
    if (this.isFirstPage() || this.loading()) {
      return;
    }

    this.pageNumber.set(Math.max(0, this.pageNumber() - 1));
    void this.load().finally(() => this.resetListScroll());
  }

  nextPage(): void {
    if (this.isLastPage() || this.loading()) {
      return;
    }

    this.pageNumber.set(this.pageNumber() + 1);
    void this.load().finally(() => this.resetListScroll());
  }

  toggleSortDirection(): void {
    this.sortDirection.set(this.sortDirection() === 'desc' ? 'asc' : 'desc');
    this.pageNumber.set(0);
    void this.load().finally(() => this.resetListScroll());
  }

  pageLabel(): string {
    return mobilePageLabel(this.page(), this.pageNumber());
  }

  currentPageIndex(): number {
    return mobilePageIndex(this.page(), this.pageNumber());
  }

  currentPageTotal(): number {
    return mobilePageTotal(this.page());
  }

  isFirstPage(): boolean {
    return mobilePageIsFirst(this.page(), this.pageNumber());
  }

  isLastPage(): boolean {
    return mobilePageIsLast(this.page(), this.pageNumber());
  }

  modeValue(tab: ArchiveModeTab): string | number {
    if (this.mode() === tab.key) {
      return this.page().totalElements;
    }

    return tab.key === 'all' ? 'Все' : tab.key === 'archive' ? 'Арх' : '₽';
  }

  sliderTone(toneClass: string): MobileStatusItem['tone'] {
    return mobileToneFromClass(toneClass);
  }

  openOrder(order: ArchiveOrderListItem): void {
    if (order.source === 'live' && order.companyId) {
      void this.router.navigate(['/tabs/orders', order.companyId, order.id]);
      return;
    }

    void this.openDetails(order);
  }

  async openDetails(order: ArchiveOrderListItem): Promise<void> {
    this.detailsOrder.set(order);
    this.detailsPayload.set(null);
    this.detailsError.set(null);
    this.restoreResult.set(null);
    this.restoreTargetStatus.set('Новый');
    this.detailsLoading.set(true);
    this.loadingDetailsOrderId = order.id;

    try {
      const details = await firstValueFrom(this.api.getManagerArchiveOrder(order.id));
      if (this.loadingDetailsOrderId !== order.id) {
        return;
      }
      this.detailsOrder.set(details.order);
      this.detailsPayload.set(details);
    } catch (error) {
      if (this.loadingDetailsOrderId === order.id) {
        this.detailsError.set(this.apiErrorMessage(error, 'Не удалось загрузить состав архивного заказа.'));
      }
    } finally {
      if (this.loadingDetailsOrderId === order.id) {
        this.detailsLoading.set(false);
      }
    }
  }

  closeDetails(): void {
    if (this.restoring()) {
      return;
    }

    this.loadingDetailsOrderId = null;
    this.detailsOrder.set(null);
    this.detailsPayload.set(null);
    this.detailsError.set(null);
    this.detailsLoading.set(false);
    this.restoreResult.set(null);
  }

  canRestore(order: ArchiveOrderListItem): boolean {
    if (order.source !== 'archive' || order.restoredAt) {
      return false;
    }

    return order.status !== 'Оплачено' || this.canManagePaidRestore();
  }

  paidRestoreRestricted(order: ArchiveOrderListItem): boolean {
    return order.source === 'archive' && order.status === 'Оплачено' && !this.canManagePaidRestore();
  }

  async confirmRestore(): Promise<void> {
    const order = this.detailsOrder();
    if (!order || this.restoring() || !this.canRestore(order)) {
      return;
    }

    this.restoring.set(true);
    this.detailsError.set(null);

    try {
      const result = await firstValueFrom(this.api.restoreManagerArchiveOrder(order.id, this.restoreTargetStatus()));
      this.restoreResult.set(result);
      await this.load();
    } catch (error) {
      this.detailsError.set(this.apiErrorMessage(error, 'Не удалось восстановить архивный заказ.'));
    } finally {
      this.restoring.set(false);
    }
  }

  canChangeLiveStatus(order: ArchiveOrderListItem): boolean {
    return order.source === 'live' && order.status === 'Архив';
  }

  isLiveStatusMutating(order: ArchiveOrderListItem, action: ArchiveLiveStatusAction): boolean {
    return this.liveStatusMutationKey() === this.liveStatusKey(order, action.status);
  }

  async changeLiveStatus(order: ArchiveOrderListItem, action: ArchiveLiveStatusAction): Promise<void> {
    if (!this.canChangeLiveStatus(order) || this.liveStatusMutationKey()) {
      return;
    }

    this.liveStatusMutationKey.set(this.liveStatusKey(order, action.status));
    this.error.set(null);

    try {
      await firstValueFrom(this.api.updateManagerOrderStatus(order.id, action.status));
      await this.load();
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Не удалось изменить статус архивного заказа.'));
    } finally {
      this.liveStatusMutationKey.set(null);
    }
  }

  async copyOrderPhone(order: ArchiveOrderListItem): Promise<void> {
    const phone = normalizePhoneDigits(order.companyTelephone);
    if (!phone) {
      return;
    }

    await this.copyText(phone, `archive-phone-${order.id}`, 'Не удалось скопировать телефон.');
  }

  async copyArchiveReviewText(order: ArchiveOrderListItem, details?: ArchiveOrderDetailsPayload | null): Promise<void> {
    const reviewUrl = this.archiveReviewUrl(order, details);
    const text = [
      this.archiveOrderTitle(order),
      'Здравствуйте, тексты отзывов доступны для проверки.',
      reviewUrl ? `Ссылка на проверку отзывов: ${reviewUrl}` : ''
    ].filter(Boolean).join('\n\n');

    await this.copyText(text, `archive-review-${order.id}`, 'Не удалось скопировать текст.');
  }

  async copyArchivePaymentText(order: ArchiveOrderListItem): Promise<void> {
    const text = [
      this.archiveOrderTitle(order),
      `К оплате: ${this.orderMoney(order.sum)}`
    ].join('\n\n');

    await this.copyText(text, `archive-payment-${order.id}`, 'Не удалось скопировать счет.');
  }

  guardLink(event: Event, url: string): void {
    if (url) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
  }

  archiveOrderTitle(order: ArchiveOrderListItem): string {
    return [order.companyTitle || 'Без компании', order.filialTitle || 'Без филиала']
      .filter(Boolean)
      .join(' - ');
  }

  archiveOrderFilialUrl(order: ArchiveOrderListItem): string {
    return (order.filialUrl ?? '').trim();
  }

  archiveOrderChatUrl(order: ArchiveOrderListItem): string {
    const digits = normalizePhoneDigits(order.companyTelephone);
    return (order.companyUrlChat ?? '').trim() || (digits ? `tel:+${digits}` : '');
  }

  orderPhone(order: ArchiveOrderListItem): string {
    return displayPhone(order.companyTelephone);
  }

  archiveReviewUrl(order: ArchiveOrderListItem, details?: ArchiveOrderDetailsPayload | null): string {
    const detailsId = this.archiveOrderDetailsId(order, details);
    if (!detailsId) {
      return '';
    }

    const origin = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1'
      ? 'https://o-ogo.ru'
      : window.location.origin;
    return new URL(`/${detailsId}`, origin).toString();
  }

  hasArchiveReviewLink(order: ArchiveOrderListItem, details?: ArchiveOrderDetailsPayload | null): boolean {
    return Boolean(this.archiveOrderDetailsId(order, details));
  }

  orderProgress(order: ArchiveOrderListItem): number {
    if (!order.amount || !order.counter) {
      return 0;
    }

    return Math.max(0, Math.min(100, Math.round((order.counter / order.amount) * 100)));
  }

  orderMoney(value?: number): string {
    return `${new Intl.NumberFormat('ru-RU').format(value ?? 0)} руб.`;
  }

  orderAgeDays(order: ArchiveOrderListItem): number {
    const value = order.changed || order.payDay || order.created;
    if (!value) {
      return 0;
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return 0;
    }

    return Math.max(0, Math.floor((Date.now() - date.getTime()) / 86_400_000));
  }

  workerLabel(order: ArchiveOrderListItem): string {
    const fio = (order.workerName ?? '').trim();
    if (!fio) {
      return '-';
    }

    const [first, second] = fio.split(/\s+/);
    return second ? `${first} ${second.charAt(0)}.` : first;
  }

  dateText(value?: string): string {
    return value ? value.slice(0, 10) : 'Нет даты';
  }

  archiveSourceLabel(order: ArchiveOrderListItem): string {
    return order.source === 'archive' ? 'арх' : 'live';
  }

  orderTone(order: ArchiveOrderListItem): string {
    if (order.status === 'Оплачено') {
      return 'lead-card--work';
    }
    if (order.source === 'archive') {
      return 'lead-card--send';
    }
    if (order.status === 'Архив') {
      return 'lead-card--new';
    }
    return '';
  }

  restoreRowsTotal(details: ArchiveOrderDetailsPayload): number {
    return 1
      + details.details.length
      + details.reviews.length
      + details.badReviewTasks.length
      + details.nextOrderRequests.length
      + details.zp.length
      + details.paymentChecks.length;
  }

  canSeeArchiveFinance(): boolean {
    return canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.archive, MOBILE_ACTIONS.manage);
  }

  trackPayment(payment: ArchivePaymentCheckItem): number {
    return payment.id;
  }

  trackZp(zp: ArchiveZpItem): number {
    return zp.id;
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);

    try {
      const page = await firstValueFrom(this.api.getManagerArchiveOrders({
        keyword: this.appliedKeyword(),
        mode: this.mode(),
        pageNumber: this.pageNumber(),
        pageSize: this.pageSize(),
        sortDirection: this.sortDirection()
      }));
      this.page.set(page);
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Архив не загрузился.'));
    } finally {
      this.loading.set(false);
    }
  }

  private archiveOrderDetailsId(order: ArchiveOrderListItem, details?: ArchiveOrderDetailsPayload | null): string {
    return (order.orderDetailsId ?? '').trim()
      || (details?.details[0]?.id ?? '').trim()
      || (details?.reviews.find((review: ArchiveReviewItem) => review.orderDetailsId)?.orderDetailsId ?? '').trim();
  }

  private liveStatusKey(order: ArchiveOrderListItem, status: string): string {
    return `archive-live-${order.id}-${status}`;
  }

  private canManagePaidRestore(): boolean {
    return canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.archive, MOBILE_ACTIONS.manage);
  }

  private async copyText(text: string, key: string, fallbackError: string): Promise<void> {
    const value = text.trim();
    if (!value) {
      return;
    }

    try {
      await navigator.clipboard.writeText(value);
      this.copiedKey.set(key);
      window.setTimeout(() => {
        if (this.copiedKey() === key) {
          this.copiedKey.set(null);
        }
      }, 1100);
    } catch {
      this.error.set(fallbackError);
    }
  }

  private resetListScroll(): void {
    window.setTimeout(() => {
      document.querySelector<HTMLElement>('app-manager-archive .lead-list')?.scrollTo({ left: 0, top: 0, behavior: 'auto' });
    });
  }

  private apiErrorMessage(error: unknown, fallback: string): string {
    if (error instanceof Error && error.message) {
      return error.message;
    }

    const maybe = error as { error?: unknown; message?: string; status?: number };
    const responseError = maybe.error;
    if (typeof responseError === 'string' && responseError.trim()) {
      return responseError;
    }
    if (this.isErrorRecord(responseError)) {
      if (responseError.message) {
        return responseError.message;
      }
      if (responseError.detail) {
        return responseError.detail;
      }
      if (responseError.error) {
        return responseError.error;
      }
    }
    if (maybe.message) {
      return maybe.message;
    }
    if (maybe.status) {
      return `${fallback} Код: ${maybe.status}`;
    }
    return fallback;
  }

  private isErrorRecord(value: unknown): value is { message?: string; detail?: string; error?: string } {
    return typeof value === 'object' && value !== null;
  }
}
