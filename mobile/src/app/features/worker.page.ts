import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import {
  IonContent,
  IonRefresher,
  IonRefresherContent,
  RefresherCustomEvent
} from '@ionic/angular/standalone';
import { Subscription, firstValueFrom } from 'rxjs';
import {
  ApiService,
  ManagerOverdueOrders,
  ManagerOverdueStatus,
  MetricItem,
  OrderItem,
  Page,
  WorkerBoard,
  WorkerBoardSection,
  WorkerBoardSectionQuery,
  WorkerOption,
  WorkerPermissions,
  WorkerReviewItem
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileRemindersComponent } from '../shared/mobile-reminders.component';
import { displayPhone, normalizePhoneDigits, phoneHref } from '../shared/phone-format';

type StatusAction = {
  label: string;
  status: string;
  icon: string;
};

type ReviewEditableField = 'text' | 'answer';
type SideNoteField = 'order' | 'company';
type ReviewCopyKind = 'url' | 'login' | 'password' | 'text' | 'answer' | 'vk';

const DEFAULT_WORKER_SECTION: WorkerBoardSection = 'new';
const WORKER_SECTIONS: WorkerBoardSection[] = ['new', 'correct', 'nagul', 'recovery', 'publish', 'bad', 'all'];
const REVIEW_SECTIONS = new Set<WorkerBoardSection>(['nagul', 'recovery', 'publish', 'bad']);
const WORKER_SECTION_LABELS: Record<WorkerBoardSection, string> = {
  all: 'Все',
  new: 'Новые',
  correct: 'Коррекция',
  nagul: 'Выгул',
  recovery: 'Восстановление',
  publish: 'Публикация',
  bad: 'Плохие'
};
const WORKER_SECTION_ICONS: Record<WorkerBoardSection, string> = {
  all: 'dashboard',
  new: 'fiber_new',
  correct: 'build_circle',
  nagul: 'directions_walk',
  recovery: 'restore',
  publish: 'published_with_changes',
  bad: 'money_off'
};
const ORDER_STATUS_ACTIONS: StatusAction[] = [
  { label: 'на проверку', status: 'На проверке', icon: 'manage_search' },
  { label: 'коррекция', status: 'Коррекция', icon: 'build_circle' },
  { label: 'архив', status: 'Архив', icon: 'archive' },
  { label: 'одобрено', status: 'Публикация', icon: 'task_alt' },
  { label: 'опублик.', status: 'Опубликовано', icon: 'published_with_changes' },
  { label: 'счет', status: 'Выставлен счет', icon: 'receipt_long' },
  { label: 'напомнить', status: 'Напоминание', icon: 'notifications_active' },
  { label: 'не опл.', status: 'Не оплачено', icon: 'money_off' },
  { label: 'оплатили', status: 'Оплачено', icon: 'payments' }
];
const DEFAULT_WORKER_PERMISSIONS: WorkerPermissions = {
  canManageOrderStatuses: false,
  canManageClientWaiting: false,
  canSeePhoneAndPayment: false,
  canManageBots: false,
  canAddBot: false,
  canSeeMoney: false,
  canWorkReviews: false,
  canEditNotes: false
};
const EMPTY_ORDER_PAGE: Page<OrderItem> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
  number: 0,
  size: 10
};
const EMPTY_REVIEW_PAGE: Page<WorkerReviewItem> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
  number: 0,
  size: 10
};

@Component({
  selector: 'app-worker',
  imports: [
    FormsModule,
    IonContent,
    IonRefresher,
    IonRefresherContent,
    MobileHeaderComponent,
    MobileRemindersComponent
  ],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Специалист" />

      <ion-content fullscreen [scrollY]="false">
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <app-mobile-reminders #reminders />

        <main class="worker-page">
          @if (error() || boardNotice()) {
            <button class="inline-alert" [class.warning]="board()?.warning" type="button" (click)="reload()">
              <span class="material-icons-sharp">{{ board()?.warning ? 'priority_high' : 'error' }}</span>
              <span>{{ error() || boardNotice() }}</span>
            </button>
          }

          <section class="worker-status-strip" aria-label="Разделы специалиста">
            @for (section of sectionOptions(); track section) {
              <button
                type="button"
                class="metric-tile {{ sectionTone(section) }}"
                [class.active]="activeSection() === section"
                [class.locked]="isSectionLocked(section)"
                (click)="selectSection(section)"
              >
                <span class="material-icons-sharp">{{ sectionIcon(section) }}</span>
                <strong>{{ metricValue(section) }}</strong>
                <small>{{ sectionLabel(section) }}</small>
                @if (metricDelta(section) > 0) {
                  <em>+{{ metricDelta(section) }}</em>
                }
              </button>
            }
          </section>

          <section class="worker-search-line" aria-label="Поиск специалиста">
            <label>
              <span class="material-icons-sharp">search</span>
              <input
                type="search"
                placeholder="Компания, филиал, отзыв, аккаунт"
                [ngModel]="keyword()"
                (ngModelChange)="setKeyword($event)"
              >
            </label>
            @if (keyword()) {
              <button class="icon-button" type="button" (click)="clearSearch()" aria-label="Очистить поиск">
                <span class="material-icons-sharp">close</span>
              </button>
            }
            @if (workerFilterAvailable()) {
              <button class="icon-button" type="button" (click)="openWorkerSheet()" aria-label="Фильтр специалиста">
                <span class="material-icons-sharp">person_search</span>
              </button>
            }
            <button class="icon-button" type="button" (click)="reload()" [disabled]="loading()" aria-label="Обновить">
              <span class="material-icons-sharp">refresh</span>
            </button>
          </section>

          <section
            class="lead-list worker-list"
            [class.worker-list--reviews]="isReviewSection()"
            aria-label="Карточки специалиста"
          >
            @if (isOrderSection()) {
              @for (order of board()?.orders?.content ?? []; track order.id) {
                <article
                  class="lead-card manager-card order-mobile-card mobile-worker-card mobile-worker-order-card {{ orderLeadToneClass(order) }}"
                  [class.waiting-client]="order.waitingForClient"
                >
                  <header class="lead-card-head order-card-head">
                    <a [href]="order.filialUrl || orderDetailsHref(order)" target="_blank" rel="noopener">
                      {{ order.companyTitle || 'Без компании' }}{{ order.filialTitle ? ' - ' + order.filialTitle : '' }}
                    </a>
                    <span class="card-id-line">
                      <small>#{{ order.id }}</small>
                      @if (orderNoteBadge(order); as badge) {
                        <button class="order-note-badge" type="button" (click)="openOrderNoteBadge(order)" [attr.aria-label]="badge">
                          {{ badge }}
                        </button>
                      }
                    </span>
                  </header>

                  <div class="lead-meta-row order-main-meta meta-row">
                    <span [class.status-waiting]="order.waitingForClient">{{ order.waitingForClient ? 'Ждем' : (order.status || '-') }}</span>
                    <span>{{ orderAmountLabel(order) }}</span>
                  </div>

                  @if (showBadReviewSummary(order)) {
                    <div class="lead-meta-row order-bad-review-row meta-row bad-summary-row">
                      <span>Плохие: {{ order.badReviewTasksDone || 0 }}/{{ order.badReviewTasksTotal || 0 }}</span>
                      <span>+{{ money(order.badReviewTasksSum || 0) }}</span>
                    </div>
                  }

                  @if (permissions().canSeePhoneAndPayment) {
                    <div class="lead-phone-row order-phone-row phone-row">
                      <a [href]="orderChatUrl(order)" target="_blank" rel="noopener">{{ formatPhone(order.companyTelephone) }}</a>
                      <button type="button" (click)="copyPhone(order)" title="Скопировать телефон">
                        {{ copiedKey() === 'phone-' + order.id ? '✓' : 'T' }}
                      </button>
                    </div>

                    <div class="lead-card-actions card-actions order-link-actions">
                      <button type="button" (click)="copyOrderText(order, 'check')">текст</button>
                      <a [href]="order.orderDetailsId ? '/' + order.orderDetailsId : orderDetailsHref(order)" target="_blank" rel="noopener">url</a>
                      <button type="button" (click)="copyOrderText(order, 'payment')">счет</button>
                      <a [href]="order.filialUrl || orderDetailsHref(order)" target="_blank" rel="noopener">ссылка</a>
                    </div>
                  }

                  <div class="order-progress progress">
                    <span class="progress-bar" [style.width.%]="orderProgress(order)">
                      <em>{{ order.counter || 0 }}</em>
                    </span>
                  </div>

                  @if (permissions().canManageOrderStatuses || canManageClientWaiting(order)) {
                    <div class="lead-card-actions card-actions order-status-actions">
                      @if (permissions().canManageOrderStatuses) {
                        @for (action of orderStatusActions; track action.status) {
                          <button
                            type="button"
                            [disabled]="order.waitingForClient || isMutating(orderMutationKey(order, action.status))"
                            (click)="updateOrderStatus(order, action)"
                            [title]="action.status"
                          >
                            {{ action.label }}
                          </button>
                        }
                      }
                      @if (canManageClientWaiting(order)) {
                        <button
                          type="button"
                          class="client-waiting-action"
                          [class.active]="order.waitingForClient"
                          [disabled]="isMutating(clientWaitingMutationKey(order))"
                          (click)="toggleOrderClientWaiting(order)"
                        >
                          {{ order.waitingForClient ? 'ждет клиента' : 'клиент' }}
                        </button>
                      }
                    </div>
                  }

                  <div class="lead-meta-row order-category-row meta-row category-row">
                    <span [title]="order.categoryTitle || 'Категория'">{{ order.categoryTitle || 'Категория' }}</span>
                    <span [title]="order.subCategoryTitle || 'Подкатегория'">{{ order.subCategoryTitle || 'Подкатегория' }}</span>
                  </div>

                  <div class="order-city-row" [title]="orderCity(order)">
                    <span class="material-icons-sharp">location_on</span>
                    <span>{{ orderCity(order) }}</span>
                  </div>

                  <div class="lead-comment-editor order-note-editor note-editor" [class.saved]="savedOrderNoteId() === order.id">
                    <textarea
                      class="lead-card-comment"
                      [id]="'workerOrderNote' + order.id"
                      [name]="'workerOrderNote' + order.id"
                      [readonly]="!permissions().canEditNotes || isMutating(orderNoteMutationKey(order))"
                      [ngModel]="orderNoteValue(order)"
                      (focus)="startOrderNoteEdit(order)"
                      (click)="startOrderNoteEdit(order)"
                      (ngModelChange)="setOrderNoteDraft(order, $event)"
                      placeholder="нет заметок"
                    ></textarea>
                    @if (editingOrderNoteId() === order.id) {
                      <div class="note-actions">
                        <button type="button" class="cancel" (click)="cancelOrderNoteEdit(order)" [disabled]="isMutating(orderNoteMutationKey(order))">X</button>
                        <button type="button" class="save" (click)="saveOrderNote(order)" [disabled]="!isOrderNoteChanged(order) || isMutating(orderNoteMutationKey(order))">
                          <span class="material-icons-sharp">{{ savedOrderNoteId() === order.id ? 'check' : 'save' }}</span>
                        </button>
                      </div>
                    }
                  </div>

                  @if (openedOrderCompanyNoteId() === order.id) {
                    <div class="note-editor side-note-editor">
                      <small>Заметка компании</small>
                      <textarea
                        [readonly]="!permissions().canEditNotes || isMutating(companyNoteMutationKey(order))"
                        [ngModel]="companyNoteValue(order)"
                        (ngModelChange)="setCompanyNoteDraft(order, $event)"
                      ></textarea>
                      <div class="note-actions">
                        <button type="button" class="cancel" (click)="toggleOrderCompanyNote(order)">X</button>
                        <button type="button" class="save" (click)="saveOrderCompanyNote(order)" [disabled]="!isCompanyNoteChanged(order) || isMutating(companyNoteMutationKey(order))">
                          <span class="material-icons-sharp">save</span>
                        </button>
                      </div>
                    </div>
                  }

                  <button class="company-details-button order-details-button details-button" type="button" (click)="openOrderDetails(order)">Подробнее</button>

                  <footer class="lead-card-foot order-card-foot">
                    <button
                      class="order-unchanged unchanged-age"
                      type="button"
                      [class.order-unchanged--alert]="unchangedDays(order) >= 2"
                      [class.alert]="unchangedDays(order) >= 2"
                      [title]="orderCity(order)"
                    >
                      @if (unchangedDays(order) >= 2) {
                        <i>!</i>
                      }
                      Без изменений: {{ unchangedDays(order) }} дн.
                    </button>
                    <button type="button" (click)="openOrderDetails(order)" [title]="order.workerUserFio || 'Специалист не назначен'">
                      {{ workerLabel(order) }}
                    </button>
                  </footer>
                </article>
              } @empty {
                <div class="empty-state compact-empty">Заказов для отображения нет.</div>
              }
            } @else {
              @for (review of board()?.reviews?.content ?? []; track reviewKey(review)) {
                <article
                  class="mobile-worker-card mobile-worker-review-card {{ reviewToneClass(review) }}"
                  [class.published]="review.publish"
                  [class.bad-task]="isBadTask(review)"
                  [class.recovery-task]="isRecoveryTask(review)"
                  [class.expanded-text]="isReviewTextExpanded(review)"
                >
                  <header>
                    <a
                      [href]="review.filialUrl || reviewDetailsHref(review)"
                      target="_blank"
                      rel="noopener"
                      [title]="reviewTitle(review)"
                    >
                      {{ reviewTitle(review) }}
                    </a>
                    <div class="card-id-line">
                      @if (needsReviewPhoto(review)) {
                        @if (hasReviewPhoto(review)) {
                          <a class="photo-dot" [href]="reviewPhotoUrl(review)" target="_blank" rel="noopener" title="Открыть фото отзыва" aria-label="Открыть фото отзыва">
                            <span class="material-icons-sharp">photo_camera</span>
                          </a>
                        } @else {
                          <label
                            class="photo-dot missing"
                            [class.loading]="uploadingPhotoReviewId() === review.id"
                            title="Загрузить фото отзыва"
                            aria-label="Загрузить фото отзыва"
                          >
                            <span class="material-icons-sharp">{{ uploadingPhotoReviewId() === review.id ? 'hourglass_top' : 'photo_camera' }}</span>
                            <input type="file" accept="image/*" (change)="uploadReviewPhoto(review, $event)" [disabled]="uploadingPhotoReviewId() === review.id">
                          </label>
                        }
                      }
                      #{{ review.id }}
                      @if (isBadTask(review)) {
                        <em class="task-badge bad">{{ ratingTaskLabel(review) }}</em>
                      }
                      @if (isRecoveryTask(review)) {
                        <em class="task-badge recovery">восст.</em>
                      }
                      @if (hasReviewAnyNote(review) || openedReviewNotesId() === review.id) {
                        <button class="note-dot" type="button" (click)="toggleReviewNotes(review)" aria-label="Заметки отзыва">
                          <span class="material-icons-sharp">priority_high</span>
                        </button>
                      }
                    </div>
                  </header>

                  <div class="review-field review-field--text" [class.editing]="isReviewFieldEditing(review, 'text')">
                    <textarea
                      [readonly]="!permissions().canWorkReviews || isMutating(reviewFieldMutationKey(review, 'text'))"
                      [ngModel]="reviewFieldValue(review, 'text')"
                      (focus)="startReviewFieldEdit(review, 'text')"
                      (click)="startReviewFieldEdit(review, 'text')"
                      (ngModelChange)="setReviewFieldDraft(review, 'text', $event)"
                      placeholder="Текст отзыва"
                    ></textarea>
                    @if (shouldShowReviewTextToggle(review)) {
                      <button class="review-text-toggle" type="button" (click)="toggleReviewText(review)">
                        {{ isReviewTextExpanded(review) ? 'свернуть' : 'развернуть' }}
                      </button>
                    }
                    @if (isReviewFieldEditing(review, 'text')) {
                      <div class="note-actions">
                        <button type="button" class="cancel" (click)="cancelReviewFieldEdit(review, 'text')" [disabled]="isMutating(reviewFieldMutationKey(review, 'text'))">X</button>
                        <button type="button" class="save" (click)="saveReviewField(review, 'text')" [disabled]="!isReviewFieldChanged(review, 'text') || !reviewFieldValue(review, 'text').trim() || isMutating(reviewFieldMutationKey(review, 'text'))">
                          <span class="material-icons-sharp">{{ savedReviewFieldKey() === reviewFieldKey(review, 'text') ? 'check' : 'save' }}</span>
                        </button>
                      </div>
                    }
                  </div>

                  <div class="review-field review-field--answer" [class.editing]="isReviewFieldEditing(review, 'answer')">
                    <textarea
                      [readonly]="!permissions().canWorkReviews || isMutating(reviewFieldMutationKey(review, 'answer'))"
                      [ngModel]="reviewFieldValue(review, 'answer')"
                      (focus)="startReviewFieldEdit(review, 'answer')"
                      (click)="startReviewFieldEdit(review, 'answer')"
                      (ngModelChange)="setReviewFieldDraft(review, 'answer', $event)"
                      placeholder="Ответ на отзыв или замечание"
                    ></textarea>
                    @if (isReviewFieldEditing(review, 'answer')) {
                      <div class="note-actions">
                        <button type="button" class="cancel" (click)="cancelReviewFieldEdit(review, 'answer')" [disabled]="isMutating(reviewFieldMutationKey(review, 'answer'))">X</button>
                        <button type="button" class="save" (click)="saveReviewField(review, 'answer')" [disabled]="!isReviewFieldChanged(review, 'answer') || isMutating(reviewFieldMutationKey(review, 'answer'))">
                          <span class="material-icons-sharp">{{ savedReviewFieldKey() === reviewFieldKey(review, 'answer') ? 'check' : 'save' }}</span>
                        </button>
                      </div>
                    }
                  </div>

                  <div class="order-city-row review-city-row" [title]="reviewCity(review)">
                    <span class="material-icons-sharp">location_on</span>
                    <span>{{ reviewCity(review) }}</span>
                  </div>

                  @if (openedReviewNotesId() === review.id) {
                    <section class="review-notes-panel">
                      <label>
                        <span>Заметка отзыва</span>
                        <textarea
                          [readonly]="!permissions().canWorkReviews || isMutating(reviewNoteMutationKey(review))"
                          [ngModel]="reviewNoteValue(review)"
                          (ngModelChange)="setReviewNoteDraft(review, $event)"
                        ></textarea>
                        <button type="button" (click)="saveReviewNote(review)" [disabled]="!isReviewNoteChanged(review) || isMutating(reviewNoteMutationKey(review))">сохранить</button>
                      </label>
                      @if (hasMeaningfulNote(review.orderComments)) {
                        <label>
                          <span>Заметка заказа</span>
                          <textarea
                            [readonly]="!permissions().canEditNotes || isMutating(sideNoteMutationKey(review, 'order'))"
                            [ngModel]="sideNoteValue(review, 'order')"
                            (ngModelChange)="setSideNoteDraft(review, 'order', $event)"
                          ></textarea>
                          <button type="button" (click)="saveSideNote(review, 'order')" [disabled]="!isSideNoteChanged(review, 'order') || isMutating(sideNoteMutationKey(review, 'order'))">сохранить</button>
                        </label>
                      }
                      @if (hasMeaningfulNote(review.commentCompany)) {
                        <label>
                          <span>Заметка компании</span>
                          <textarea
                            [readonly]="!permissions().canEditNotes || isMutating(sideNoteMutationKey(review, 'company'))"
                            [ngModel]="sideNoteValue(review, 'company')"
                            (ngModelChange)="setSideNoteDraft(review, 'company', $event)"
                          ></textarea>
                          <button type="button" (click)="saveSideNote(review, 'company')" [disabled]="!isSideNoteChanged(review, 'company') || isMutating(sideNoteMutationKey(review, 'company'))">сохранить</button>
                        </label>
                      }
                    </section>
                  }

                  <div class="bot-line" [class.empty]="hasUnavailableBot(review)">
                    <span>{{ botLabel(review) }}</span>
                  </div>

                  <div class="card-actions review-actions">
                    <button type="button" (click)="copyReviewValue(review, 'url')">{{ copiedKey() === 'url-' + review.id ? 'готово' : 'ссылка' }}</button>
                    <button type="button" (click)="copyReviewValue(review, 'login')">{{ copiedKey() === 'login-' + review.id ? 'готово' : 'логин' }}</button>
                    <button type="button" (click)="copyReviewValue(review, 'password')">{{ copiedKey() === 'password-' + review.id ? 'готово' : 'пароль' }}</button>
                    <a [href]="botBrowserUrl(review)" target="_blank" rel="noopener">вк</a>
                    <button type="button" (click)="copyReviewValue(review, 'text')">{{ copiedKey() === 'text-' + review.id ? 'готово' : 'текст' }}</button>
                    <button type="button" (click)="copyReviewValue(review, 'answer')">{{ copiedKey() === 'answer-' + review.id ? 'готово' : 'ответ' }}</button>
                    <button type="button" (click)="changeReviewBot(review)" [disabled]="isMutating(reviewBotMutationKey(review, 'change'))">
                      {{ isMutating(reviewBotMutationKey(review, 'change')) ? '...' : 'смена' }}
                    </button>
                    <button type="button" (click)="deactivateReviewBot(review)" [disabled]="!review.botId || isMutating(reviewBotMutationKey(review, 'block'))">
                      {{ isMutating(reviewBotMutationKey(review, 'block')) ? '...' : 'блок' }}
                    </button>
                  </div>

                  <button
                    class="publish-button"
                    type="button"
                    [class.published]="isPublishedPublishAction(review)"
                    (click)="markReviewDone(review)"
                    [disabled]="isPublishedPublishAction(review) || isMutating(reviewDoneMutationKey(review))"
                  >
                    {{ isMutating(reviewDoneMutationKey(review)) ? '...' : doneLabel(review) }}
                  </button>

                  <footer>
                    <span>{{ reviewDate(review) }}</span>
                    <button type="button" (click)="openReviewDetails(review)" [title]="reviewFooterLabel(review)">
                      {{ reviewFooterLabel(review) }}
                    </button>
                  </footer>
                </article>
              } @empty {
                <div class="empty-state compact-empty">Отзывов для отображения нет.</div>
              }
            }
          </section>

          <section class="lead-bottom-controls worker-bottom-controls" aria-label="Пагинация специалиста">
            <button class="expand-list-button reminder-hero-button" type="button" (click)="reminders.open()" aria-label="Напоминания">
              <span class="material-icons-sharp">notifications_active</span>
              @if (reminders.activeReminderCount()) {
                <small>{{ reminders.activeReminderCount() }}</small>
              }
            </button>
            <button
              class="expand-list-button"
              type="button"
              [class.active]="sortDirection() === 'asc'"
              (click)="toggleSortDirection()"
              [attr.aria-label]="sortDirection() === 'asc' ? 'Показать сначала давно без изменений' : 'Показать сначала недавно измененные'"
            >
              <span class="material-icons-sharp">swap_vert</span>
            </button>
            <div class="lead-pager">
              <button type="button" (click)="previousPage()" [disabled]="isFirstPage() || loading()" aria-label="Предыдущая страница">
                <span class="material-icons-sharp">chevron_left</span>
              </button>
              <span>{{ pageLabel() }}</span>
              <button type="button" (click)="nextPage()" [disabled]="isLastPage() || loading()" aria-label="Следующая страница">
                <span class="material-icons-sharp">chevron_right</span>
              </button>
            </div>
          </section>
        </main>

        @if (sectionSheetOpen()) {
          <button class="worker-section-backdrop" type="button" aria-label="Закрыть разделы" (click)="closeSectionSheet()"></button>
          <section class="worker-section-sheet" role="dialog" aria-modal="true" aria-label="Выбор раздела специалиста">
            <header>
              <div>
                <small>Специалист</small>
                <h2>Выберите раздел</h2>
              </div>
              <button type="button" class="icon-button" (click)="closeSectionSheet()" aria-label="Закрыть">
                <span class="material-icons-sharp">close</span>
              </button>
            </header>
            <div class="worker-section-options">
              @for (section of sectionOptions(); track section) {
                <button
                  type="button"
                  class="metric-tile {{ sectionTone(section) }}"
                  [class.active]="activeSection() === section"
                  [class.locked]="isSectionLocked(section)"
                  (click)="selectSection(section)"
                >
                  <span class="material-icons-sharp">{{ sectionIcon(section) }}</span>
                  <strong>{{ metricValue(section) }}</strong>
                  <small>{{ sectionLabel(section).toLowerCase() }}</small>
                </button>
              }
            </div>
          </section>
        }

        @if (workerSheetOpen()) {
          <button class="worker-section-backdrop" type="button" aria-label="Закрыть фильтр" (click)="closeWorkerSheet()"></button>
          <section class="worker-section-sheet worker-filter-sheet" role="dialog" aria-modal="true" aria-label="Фильтр специалиста">
            <header>
              <div>
                <small>Работник</small>
                <h2>Выберите специалиста</h2>
              </div>
              <button type="button" class="icon-button" (click)="closeWorkerSheet()" aria-label="Закрыть">
                <span class="material-icons-sharp">close</span>
              </button>
            </header>
            <div class="worker-filter-options">
              <button type="button" [class.active]="!selectedWorkerId()" (click)="changeWorkerFilter(null)">Все работники</button>
              @for (worker of workerOptions(); track worker.id) {
                <button type="button" [class.active]="selectedWorkerId() === worker.id" (click)="changeWorkerFilter(worker)">
                  {{ worker.label }}
                </button>
              }
            </div>
          </section>
        }

        @if (overdueModalOpen()) {
          @if (overdueOrders(); as overdue) {
            <button class="worker-section-backdrop" type="button" aria-label="Закрыть просрочки" (click)="closeOverdueModal()"></button>
            <section class="worker-section-sheet overdue-sheet" role="dialog" aria-modal="true" aria-label="Просроченные заказы">
              <header>
                <div>
                  <small>Без изменений больше {{ overdue.thresholdDays }} дн.</small>
                  <h2>Нужна рассылка</h2>
                </div>
                <button type="button" class="icon-button" (click)="closeOverdueModal()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </header>
              <div class="overdue-summary">
                <span class="material-icons-sharp">notifications_active</span>
                <strong>{{ overdue.total }}</strong>
                <small>макс. {{ overdueMaxDays(overdue) }} дн.</small>
              </div>
              <div class="worker-filter-options">
                @for (status of overdue.statuses; track status.status) {
                  <button type="button" (click)="openOverdueStatus(status)">
                    <span>{{ status.status }}</span>
                    <strong>{{ status.count }}</strong>
                    <small>до {{ status.maxDays }} дн.</small>
                  </button>
                }
              </div>
              <button type="button" class="dismiss-button" (click)="closeOverdueModal()">
                Клянусь исправить и не допускать
              </button>
            </section>
          }
        }
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content { --overflow: hidden; }

    .worker-page {
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

    .worker-search-line {
      display: grid;
      flex: 0 0 auto;
      grid-template-columns: minmax(0, 1fr) repeat(3, auto);
      align-items: center;
      gap: 0.45rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 1rem;
      padding: 0.48rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 0.8rem 1.6rem rgba(132, 139, 200, 0.1);
    }

    .worker-search-line label {
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

    .worker-search-line input {
      min-width: 0;
      border: 0;
      outline: 0;
      color: var(--otziv-dark);
      background: transparent;
      font: 900 0.82rem/1 var(--otziv-font-family);
    }

    .worker-search-line input::placeholder {
      color: var(--otziv-info);
      opacity: 1;
    }

    .worker-search-line .material-icons-sharp {
      font-size: 1.12rem;
    }

    .worker-search-line .icon-button {
      width: 2.42rem;
      height: 2.42rem;
      min-width: 2.42rem;
      border: 0;
      border-radius: 0.82rem;
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 92%);
    }

    .worker-search-line button:disabled {
      opacity: 0.52;
    }

    .worker-status-strip {
      display: flex;
      flex: 0 0 auto;
      gap: 0.5rem;
      margin-inline: -0.15rem;
      overflow-x: auto;
      overflow-y: hidden;
      padding: 0 0.15rem 0.08rem;
      scrollbar-width: none;
      scroll-snap-type: x proximity;
    }

    .worker-status-strip::-webkit-scrollbar,
    .worker-list::-webkit-scrollbar {
      display: none;
    }

    .metric-tile {
      position: relative;
      display: grid;
      flex: 0 0 7.3rem;
      min-height: 3.45rem;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.1rem 0.5rem;
      border: 1px solid var(--status-menu-border, rgba(103, 116, 131, 0.18));
      border-radius: 0.9rem;
      padding: 0.55rem 0.65rem;
      color: var(--otziv-dark);
      background: linear-gradient(155deg, var(--status-menu-surface, var(--otziv-white)) 0%, var(--otziv-white) 72%);
      box-shadow: 0 0.7rem 1.25rem rgba(132, 139, 200, 0.09);
      font: inherit;
      text-align: left;
      scroll-snap-align: start;
    }

    .metric-tile.active {
      border-color: rgba(108, 155, 207, 0.48);
      background: linear-gradient(155deg, var(--status-menu-surface, var(--otziv-tone-walk-surface)) 0%, rgba(108, 155, 207, 0.14) 100%);
      box-shadow: 0 0.8rem 1.45rem rgba(108, 155, 207, 0.16);
    }

    .metric-tile.locked {
      opacity: 0.68;
    }

    .metric-tile .material-icons-sharp {
      grid-row: span 2;
      width: auto;
      height: auto;
      border-radius: 0;
      background: transparent;
      font-size: 1.35rem;
    }

    .metric-tile strong,
    .metric-tile small {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .metric-tile strong {
      grid-column: 2;
      align-self: end;
      font-size: 1.02rem;
      font-weight: 900;
      text-align: left;
    }

    .metric-tile small {
      grid-column: 2;
      align-self: start;
      color: var(--otziv-info);
      font-size: 0.58rem;
      font-weight: 900;
    }

    .metric-tile em {
      position: absolute;
      top: 0.38rem;
      right: 0.45rem;
      color: var(--otziv-danger);
      font-size: 0.68rem;
      font-style: normal;
      font-weight: 900;
    }

    .worker-status-strip .tone-yellow {
      --status-menu-border: var(--otziv-tone-wait-border);
      --status-menu-surface: var(--otziv-tone-wait-surface);
    }

    .worker-status-strip .tone-green {
      --status-menu-border: var(--otziv-tone-success-border);
      --status-menu-surface: var(--otziv-tone-success-surface);
    }

    .worker-status-strip .tone-teal {
      --status-menu-border: rgba(47, 159, 149, 0.28);
      --status-menu-surface: #f4fffd;
    }

    .worker-status-strip .tone-pink {
      --status-menu-border: var(--otziv-tone-publication-border);
      --status-menu-surface: var(--otziv-tone-publication-surface);
    }

    .worker-status-strip .tone-gray {
      --status-menu-border: var(--otziv-tone-walk-border);
      --status-menu-surface: var(--otziv-tone-walk-surface);
    }

    .worker-status-strip .tone-blue {
      --status-menu-border: rgba(108, 155, 207, 0.28);
      --status-menu-surface: #f6faff;
    }

    .tone-yellow .material-icons-sharp { color: #b28405; }
    .tone-pink .material-icons-sharp { color: var(--otziv-danger); }
    .tone-teal .material-icons-sharp { color: #0891b2; }
    .tone-green .material-icons-sharp { color: var(--otziv-success); }
    .tone-gray .material-icons-sharp { color: var(--otziv-info); }
    .tone-blue .material-icons-sharp { color: var(--otziv-dark); }

    .worker-list {
      display: flex;
      flex: 1 1 0;
      min-height: 0;
      gap: 0.72rem;
      margin-inline: -0.75rem;
      overflow-x: auto;
      overflow-y: hidden;
      padding: 0 0.75rem 0.2rem;
      scroll-snap-type: x mandatory;
      scrollbar-width: none;
    }

    .worker-list--expanded {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      align-content: start;
      gap: 0.62rem;
      align-items: stretch;
      overflow-x: hidden;
      overflow-y: auto;
      scroll-snap-type: none;
    }

    .worker-list--expanded .mobile-worker-card {
      flex: none;
      width: 100%;
      min-height: auto;
      scroll-snap-align: none;
    }

    .mobile-worker-card {
      display: grid;
      flex: 0 0 min(15.4rem, 76vw);
      min-width: 0;
      min-height: 100%;
      align-content: start;
      gap: clamp(0.34rem, 0.78vh, 0.56rem);
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.9rem;
      padding: 0.68rem;
      background: linear-gradient(180deg, var(--otziv-white) 0%, var(--otziv-white) 58%, var(--otziv-muted-surface) 100%);
      box-shadow: 0 0.55rem 1.2rem rgba(132, 139, 200, 0.14);
      scroll-snap-align: center;
    }

    .mobile-worker-order-card {
      align-content: space-between;
      gap: clamp(0.28rem, 0.58vh, 0.46rem);
    }

    .mobile-worker-card.tone-wait,
    .mobile-worker-card.waiting-client {
      border-color: var(--otziv-tone-wait-border);
      background: linear-gradient(180deg, var(--otziv-tone-wait-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-worker-card.tone-walk {
      border-color: var(--otziv-tone-walk-border);
      background: linear-gradient(180deg, var(--otziv-tone-walk-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-worker-card.tone-correction {
      border-color: var(--otziv-tone-correction-border);
      background: linear-gradient(180deg, var(--otziv-tone-correction-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-worker-card.tone-publication {
      border-color: var(--otziv-tone-publication-border);
      background: linear-gradient(180deg, var(--otziv-tone-publication-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-worker-card.tone-success {
      border-color: var(--otziv-tone-success-border);
      background: linear-gradient(180deg, var(--otziv-tone-success-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-worker-card.tone-bad {
      border-color: var(--otziv-tone-bad-border);
      background: linear-gradient(180deg, var(--otziv-tone-bad-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-worker-card.tone-recovery {
      border-color: rgba(244, 197, 66, 0.7);
      background: linear-gradient(180deg, #fff8d8 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-worker-card header {
      display: grid;
      grid-template-columns: minmax(0, 1fr) max-content;
      align-items: start;
      gap: 0.42rem;
    }

    .mobile-worker-card header a {
      display: -webkit-box;
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      font-family: var(--otziv-card-title-font);
      font-size: 0.98rem;
      font-weight: 1000;
      line-height: 1.06;
      text-decoration: none;
      overflow-wrap: anywhere;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }

    .card-id-line {
      display: inline-flex;
      align-items: center;
      justify-content: flex-end;
      gap: 0.24rem;
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 900;
      white-space: nowrap;
    }

    .note-dot,
    .photo-dot {
      display: inline-grid;
      width: 1.24rem;
      height: 1.24rem;
      place-items: center;
      border: 0;
      border-radius: 999px;
      color: #4d3900;
      background: #f4c542;
      font: inherit;
      text-decoration: none;
    }

    .note-dot .material-icons-sharp,
    .photo-dot .material-icons-sharp {
      font-size: 0.84rem;
    }

    .photo-dot {
      position: relative;
      color: white;
      background: var(--otziv-success);
    }

    .photo-dot.missing {
      background: var(--otziv-danger);
    }

    .photo-dot.loading {
      opacity: 0.72;
    }

    .photo-dot input {
      position: absolute;
      width: 1px;
      height: 1px;
      opacity: 0;
      pointer-events: none;
    }

    .task-badge {
      border-radius: 999px;
      padding: 0.14rem 0.35rem;
      font-size: 0.58rem;
      font-style: normal;
      font-weight: 900;
    }

    .task-badge.bad {
      color: var(--otziv-danger);
      background: rgba(255, 0, 96, 0.1);
    }

    .task-badge.recovery {
      color: #8a6400;
      background: rgba(255, 248, 216, 0.92);
    }

    .meta-row,
    .phone-row,
    .mobile-worker-card footer {
      display: flex;
      min-width: 0;
      align-items: center;
      justify-content: space-between;
      gap: 0.48rem;
    }

    .meta-row span,
    .meta-row button,
    .phone-row a,
    .phone-row button,
    .bot-line,
    .mobile-worker-card footer button {
      min-width: 0;
      min-height: 1.9rem;
      overflow: hidden;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 999px;
      padding: 0 0.55rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font: 900 0.72rem/1 var(--otziv-font-family);
      text-align: center;
      text-decoration: none;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .meta-row span,
    .meta-row button,
    .phone-row a {
      flex: 1;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }

    .phone-row a {
      color: #36708d;
      font-size: 0.95rem;
    }

    .phone-row button {
      flex: 0 0 2.25rem;
      padding: 0;
      color: #36708d;
    }

    .mobile-worker-order-card .meta-row span,
    .mobile-worker-order-card .meta-row button,
    .mobile-worker-order-card .phone-row a,
    .mobile-worker-order-card .phone-row button {
      min-height: 1.62rem;
      font-size: 0.7rem;
      font-weight: 900;
    }

    .mobile-worker-order-card .phone-row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 2rem;
    }

    .mobile-worker-order-card .phone-row a {
      color: #36708d;
      font-size: 0.82rem;
    }

    .mobile-worker-order-card .phone-row button {
      min-width: 2rem;
      padding: 0;
      font-size: 0.78rem;
    }

    .status-waiting,
    .bad-summary-row span {
      color: #8a6416 !important;
      border-color: rgba(214, 159, 43, 0.36) !important;
      background: rgba(255, 244, 214, 0.86) !important;
    }

    .progress {
      overflow: hidden;
      min-height: 0.38rem;
      border-radius: 999px;
      background: #f3ddf8;
    }

    .progress-bar {
      display: grid;
      min-width: 1.6rem;
      height: 100%;
      place-items: center;
      border-radius: inherit;
      color: var(--otziv-dark);
      background: #bdcfe5;
      font-size: 0.56rem;
      font-weight: 900;
    }

    .card-actions {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 0.36rem;
    }

    .card-actions button,
    .card-actions a,
    .details-button,
    .publish-button,
    .review-notes-panel button {
      display: inline-flex;
      min-width: 0;
      min-height: 1.95rem;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      border: 1px solid rgba(103, 116, 131, 0.24);
      border-radius: 999px;
      padding: 0 0.35rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 0.62rem/1 var(--otziv-font-family);
      text-decoration: none;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .card-actions button:disabled,
    .publish-button:disabled {
      opacity: 0.58;
    }

    .order-status-actions {
      grid-template-columns: repeat(5, minmax(0, 1fr));
    }

    .mobile-worker-order-card .order-link-actions,
    .mobile-worker-order-card .order-status-actions {
      gap: 0.28rem 0.3rem;
    }

    .mobile-worker-order-card .order-link-actions button,
    .mobile-worker-order-card .order-link-actions a,
    .mobile-worker-order-card .order-status-actions button,
    .mobile-worker-order-card .details-button {
      min-height: 1.55rem;
      padding: 0 0.18rem;
      font-size: 0.56rem;
      letter-spacing: 0;
    }

    .client-waiting-action.active {
      grid-column: 1 / -1;
      color: #8a6416;
      border-color: rgba(214, 159, 43, 0.36);
      background: rgba(255, 244, 214, 0.86);
      text-transform: uppercase;
    }

    .category-row {
      overflow: visible;
    }

    .category-row button,
    .category-row span {
      flex: 1;
      cursor: default;
    }

    .order-city-row {
      display: inline-flex;
      min-width: 0;
      min-height: 1.72rem;
      align-items: center;
      justify-content: center;
      gap: 0.26rem;
      overflow: hidden;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 999px;
      padding: 0 0.58rem;
      color: var(--otziv-info);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-tone-work-surface) 100%);
      font: 900 0.66rem/1 var(--otziv-font-family);
    }

    .order-city-row .material-icons-sharp {
      flex: 0 0 auto;
      color: var(--otziv-success);
      font-size: 0.9rem;
    }

    .order-city-row span:last-child {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .note-editor,
    .review-field,
    .review-notes-panel {
      position: relative;
      display: grid;
      min-width: 0;
      gap: 0.35rem;
    }

    .note-editor textarea,
    .review-field textarea,
    .review-notes-panel textarea {
      display: block;
      width: 100%;
      min-width: 0;
      resize: none;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.8rem;
      outline: 0;
      padding: 0.58rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font: 700 0.74rem/1.35 var(--otziv-font-family);
    }

    .note-editor textarea {
      height: 4.4rem;
    }

    .mobile-worker-order-card .note-editor textarea {
      height: clamp(3.7rem, 9.4vh, 4.7rem);
      font-size: 0.72rem;
    }

    .review-field--text textarea {
      height: 9.1rem;
      overflow: hidden;
    }

    .expanded-text .review-field--text textarea,
    .review-field--text.editing textarea {
      height: 10.2rem;
      overflow: auto;
    }

    .review-field--answer textarea {
      height: 3.6rem;
      font-size: 0.66rem;
      font-weight: 600;
      opacity: 0.66;
      text-align: center;
    }

    .note-editor textarea:focus,
    .review-field textarea:focus,
    .review-field.editing textarea {
      border-color: #f4c542;
      box-shadow: 0 0 0 0.16rem rgba(244, 197, 66, 0.22);
    }

    .review-text-toggle {
      position: absolute;
      right: 0.58rem;
      bottom: 0.52rem;
      z-index: 1;
      border: 0;
      color: var(--otziv-info);
      background: var(--otziv-field-background);
      font: 900 0.58rem/1 var(--otziv-font-family);
    }

    .review-field.editing .review-text-toggle {
      display: none;
    }

    .note-actions {
      display: flex;
      justify-content: space-between;
      gap: 0.35rem;
    }

    .note-actions button {
      display: grid;
      width: 1.85rem;
      height: 1.85rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.55rem;
      background: var(--otziv-white);
      color: var(--otziv-dark);
      font: 900 0.8rem/1 var(--otziv-font-family);
    }

    .note-actions .save {
      color: var(--otziv-success);
    }

    .note-actions .cancel {
      color: var(--otziv-danger);
    }

    .side-note-editor small,
    .review-notes-panel span {
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 900;
      text-transform: uppercase;
    }

    .review-notes-panel {
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.82rem;
      padding: 0.48rem;
      background: rgba(255, 255, 255, 0.68);
    }

    .review-notes-panel label {
      display: grid;
      gap: 0.32rem;
    }

    .review-notes-panel textarea {
      height: 4.4rem;
      font-size: 0.7rem;
      font-weight: 600;
    }

    .bot-line {
      display: flex;
      justify-content: center;
      align-items: center;
      border-radius: 0.8rem;
    }

    .bot-line span {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .bot-line.empty {
      color: var(--otziv-danger);
    }

    .publish-button,
    .details-button {
      width: 100%;
      min-height: 2.04rem;
      text-transform: uppercase;
    }

    .publish-button {
      color: #137d69 !important;
      border-color: rgba(27, 156, 133, 0.24) !important;
      background: #dff7ed !important;
      font-size: 0.56rem;
    }

    .publish-button.published,
    .publish-button.published:disabled {
      color: #c44d72 !important;
      border-color: rgba(255, 0, 96, 0.18) !important;
      background: #ffe6ef !important;
      opacity: 1;
    }

    .mobile-worker-card footer {
      margin-top: 0;
    }

    .mobile-worker-card footer span,
    .mobile-worker-card footer button {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 900;
      background: transparent;
      border: 0;
      padding: 0;
      text-align: left;
    }

    .mobile-worker-card footer button {
      text-align: right;
      text-decoration: underline;
    }

    .unchanged-age {
      display: inline-flex;
      align-items: center;
      gap: 0.22rem;
    }

    .unchanged-age.alert {
      color: var(--otziv-danger) !important;
    }

    .unchanged-age i {
      display: inline-grid;
      width: 0.92rem;
      height: 0.92rem;
      place-items: center;
      border-radius: 50%;
      color: white;
      background: var(--otziv-danger);
      font-size: 0.58rem;
      font-style: normal;
    }

    .worker-bottom-controls {
      flex: 0 0 auto;
      justify-content: flex-start;
    }

    .worker-section-backdrop {
      position: fixed;
      inset: 0;
      z-index: 30;
      border: 0;
      background: rgba(20, 24, 32, 0.48);
    }

    .worker-section-sheet {
      position: fixed;
      left: 50%;
      top: 50%;
      z-index: 31;
      display: grid;
      width: min(92vw, 23rem);
      max-height: min(78vh, 38rem);
      transform: translate(-50%, -50%);
      gap: 0.62rem;
      overflow: auto;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 1rem;
      padding: 0.72rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 1.3rem 3rem rgba(15, 23, 42, 0.24);
    }

    .worker-section-sheet header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.65rem;
    }

    .worker-section-sheet small {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 900;
      text-transform: uppercase;
    }

    .worker-section-sheet h2 {
      margin: 0;
      color: var(--otziv-dark);
      font-size: 1.04rem;
      font-weight: 900;
    }

    .worker-section-options,
    .worker-filter-options {
      display: grid;
      gap: 0.5rem;
    }

    .worker-section-options .metric-tile {
      display: grid;
      width: 100%;
      min-height: 2.72rem;
      flex-basis: auto;
      grid-template-columns: auto minmax(0, 1fr) auto;
      grid-template-rows: minmax(0, 1fr);
      align-items: center;
      gap: 0.5rem;
      border: 1px solid var(--status-menu-border, rgba(103, 116, 131, 0.18));
      border-radius: 0.8rem;
      padding: 0.42rem 0.58rem;
      background: linear-gradient(160deg, var(--status-menu-surface, var(--otziv-tone-walk-surface)) 0%, var(--otziv-white) 58%);
      box-shadow: none;
      scroll-snap-align: none;
    }

    .worker-section-options .metric-tile .material-icons-sharp {
      grid-column: 1;
      grid-row: 1;
      width: 1.85rem;
      height: 1.85rem;
      font-size: 1rem;
    }

    .worker-section-options .metric-tile strong {
      grid-column: 3;
      grid-row: 1;
      align-self: center;
      min-width: 2rem;
      font-size: 0.98rem;
      text-align: right;
    }

    .worker-section-options .metric-tile small {
      grid-column: 2;
      grid-row: 1;
      align-self: center;
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 900;
      text-transform: lowercase;
    }

    .worker-section-options .metric-tile.active {
      border-color: rgba(108, 155, 207, 0.45);
      color: var(--otziv-dark);
      background: linear-gradient(160deg, var(--status-menu-surface, var(--otziv-tone-walk-surface)) 0%, rgba(108, 155, 207, 0.16) 100%);
      box-shadow: 0 0.95rem 1.8rem rgba(108, 155, 207, 0.16);
    }

    .worker-filter-options button,
    .dismiss-button {
      display: grid;
      min-height: 2.8rem;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
      gap: 0.45rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 0.8rem;
      padding: 0 0.75rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 0.86rem/1 var(--otziv-font-family);
      text-align: left;
    }

    .worker-filter-options button.active {
      border-color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 100%);
    }

    .overdue-summary {
      display: grid;
      grid-template-columns: auto auto minmax(0, 1fr);
      align-items: center;
      gap: 0.55rem;
      border-radius: 0.82rem;
      padding: 0.66rem;
      background: linear-gradient(145deg, var(--otziv-tone-wait-surface) 0%, var(--otziv-white) 100%);
    }

    .overdue-summary .material-icons-sharp {
      color: var(--otziv-danger);
    }

    .dismiss-button {
      display: flex;
      justify-content: center;
      text-align: center;
    }

    :host-context(body.otziv-dark-theme) .mobile-worker-card,
    :host-context(body.otziv-dark-theme) .worker-search-line,
    :host-context(body.otziv-dark-theme) .worker-section-sheet {
      background: linear-gradient(180deg, rgba(32, 38, 44, 0.98) 0%, rgba(24, 29, 34, 0.98) 100%);
    }

    :host-context(body.otziv-dark-theme) .worker-status-strip .metric-tile:not(.active),
    :host-context(body.otziv-dark-theme) .worker-section-options .metric-tile:not(.active),
    :host-context(body.otziv-dark-theme) .worker-search-line label {
      background: rgba(25, 30, 36, 0.92);
    }

    :host-context(body.otziv-dark-theme) .publish-button {
      color: #7af0d0 !important;
      background: rgba(27, 156, 133, 0.18) !important;
    }

    @media (max-width: 360px) {
      .metric-tile {
        flex-basis: 7.1rem;
      }

      .mobile-worker-card {
        flex-basis: min(15.1rem, calc(100vw - 2.7rem));
      }
    }
  `]
})
export class WorkerPage implements OnInit, OnDestroy {
  private routeSubscription?: Subscription;
  private lastMobileNavKey = '';
  private searchTimer: ReturnType<typeof setTimeout> | null = null;
  private noticeTimer: ReturnType<typeof setTimeout> | null = null;

  readonly orderStatusActions = ORDER_STATUS_ACTIONS;
  readonly board = signal<WorkerBoard | null>(null);
  readonly activeSection = signal<WorkerBoardSection>(DEFAULT_WORKER_SECTION);
  readonly keyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly sortDirection = signal<'desc' | 'asc'>('desc');
  readonly error = signal<string | null>(null);
  readonly loading = signal(false);
  readonly copiedKey = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly sectionSheetOpen = signal(false);
  readonly workerSheetOpen = signal(false);
  readonly listExpanded = signal(false);
  readonly boardNotice = signal<string | null>(null);
  readonly overdueOrders = signal<ManagerOverdueOrders | null>(null);
  readonly overdueModalOpen = signal(false);
  readonly selectedWorkerId = signal<number | null>(null);
  readonly editingOrderNoteId = signal<number | null>(null);
  readonly savedOrderNoteId = signal<number | null>(null);
  readonly orderNoteDrafts = signal<Record<number, string>>({});
  readonly openedOrderCompanyNoteId = signal<number | null>(null);
  readonly companyNoteDrafts = signal<Record<number, string>>({});
  readonly editingReviewFieldKey = signal<string | null>(null);
  readonly savedReviewFieldKey = signal<string | null>(null);
  readonly reviewFieldDrafts = signal<Record<string, string>>({});
  readonly expandedReviewTextIds = signal<Record<number, boolean>>({});
  readonly openedReviewNotesId = signal<number | null>(null);
  readonly reviewNoteDrafts = signal<Record<number, string>>({});
  readonly sideNoteDrafts = signal<Record<string, string>>({});
  readonly uploadingPhotoReviewId = signal<number | null>(null);

  readonly permissions = computed(() => this.board()?.permissions ?? DEFAULT_WORKER_PERMISSIONS);
  readonly workerOptions = computed(() => this.board()?.workerOptions ?? []);
  readonly workerFilterAvailable = computed(() => {
    this.auth.user();
    return Boolean(this.board()?.workerFilterAvailable)
      && this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER'])
      && this.workerOptions().length > 0;
  });
  readonly sectionOptions = computed(() => WORKER_SECTIONS.filter((section) => {
    if (section !== 'recovery' && section !== 'bad') {
      return true;
    }
    return this.metricValue(section) > 0 || this.activeSection() === section;
  }));

  constructor(
    private readonly api: ApiService,
    private readonly auth: AuthService,
    private readonly route: ActivatedRoute,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.applyMobileNavIntent(false);
    this.lastMobileNavKey = this.mobileNavKey();
    void this.load();
    this.loadDailyOverdueReminder();
    this.routeSubscription = this.route.queryParamMap.subscribe((params) => {
      const nextKey = this.mobileNavKey(params);
      if (nextKey === this.lastMobileNavKey) {
        return;
      }
      this.lastMobileNavKey = nextKey;
      this.applyMobileNavIntent(true, params);
    });
  }

  ionViewWillEnter(): void {
    this.applyMobileNavIntent(false);
  }

  ngOnDestroy(): void {
    this.routeSubscription?.unsubscribe();
    this.clearSearchTimer();
    this.clearNoticeTimer();
  }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    await this.load();
    event.target.complete();
  }

  reload(): void {
    void this.load();
  }

  setKeyword(value: string): void {
    this.keyword.set(value);
    this.pageNumber.set(0);
    this.clearSearchTimer();
    this.searchTimer = setTimeout(() => void this.load(), 1000);
  }

  clearSearch(): void {
    this.keyword.set('');
    this.pageNumber.set(0);
    this.clearSearchTimer();
    void this.load();
  }

  openSectionSheet(): void {
    this.sectionSheetOpen.set(true);
  }

  closeSectionSheet(): void {
    this.sectionSheetOpen.set(false);
  }

  openWorkerSheet(): void {
    this.workerSheetOpen.set(true);
  }

  closeWorkerSheet(): void {
    this.workerSheetOpen.set(false);
  }

  openAllSection(load = true): void {
    this.activeSection.set('all');
    this.pageNumber.set(0);
    this.listExpanded.set(false);
    this.sectionSheetOpen.set(false);
    if (load) {
      void this.load();
    }
  }

  selectSection(section: WorkerBoardSection): void {
    this.activeSection.set(section);
    this.pageNumber.set(0);
    this.listExpanded.set(false);
    this.sectionSheetOpen.set(false);
    void this.load();
  }

  changeWorkerFilter(worker: WorkerOption | null): void {
    this.selectedWorkerId.set(worker?.id ?? null);
    this.pageNumber.set(0);
    this.workerSheetOpen.set(false);
    void this.load();
  }

  previousPage(): void {
    if (this.isFirstPage() || this.loading()) {
      return;
    }
    this.pageNumber.set(Math.max(this.pageNumber() - 1, 0));
    void this.load();
  }

  nextPage(): void {
    if (this.isLastPage() || this.loading()) {
      return;
    }
    this.pageNumber.set(this.pageNumber() + 1);
    void this.load();
  }

  toggleSortDirection(): void {
    this.sortDirection.set(this.sortDirection() === 'desc' ? 'asc' : 'desc');
    this.pageNumber.set(0);
    void this.load().finally(() => this.resetListScroll());
  }

  toggleListExpanded(): void {
    this.toggleSortDirection();
  }

  private resetListScroll(): void {
    setTimeout(() => document.querySelector<HTMLElement>('app-worker .worker-list')?.scrollTo({ left: 0, top: 0, behavior: 'auto' }));
  }

  closeOverdueModal(): void {
    this.overdueModalOpen.set(false);
  }

  openOverdueStatus(status: ManagerOverdueStatus): void {
    this.closeOverdueModal();
    this.keyword.set('');
    this.pageNumber.set(0);
    this.activeSection.set(this.workerSectionForOrderStatus(status.status));
    void this.load();
  }

  async updateOrderStatus(order: OrderItem, action: StatusAction): Promise<void> {
    const key = this.orderMutationKey(order, action.status);
    await this.runMutation(key, () => this.api.updateWorkerOrderStatus(order.id, action.status), 'Не удалось изменить статус заказа.');
  }

  async toggleOrderClientWaiting(order: OrderItem): Promise<void> {
    const waitingForClient = !order.waitingForClient;
    await this.runMutation(
      this.clientWaitingMutationKey(order),
      () => this.api.updateWorkerOrderClientWaiting(order.id, waitingForClient),
      'Не удалось изменить ожидание клиента.'
    );
  }

  startOrderNoteEdit(order: OrderItem): void {
    if (!this.permissions().canEditNotes || this.isMutating(this.orderNoteMutationKey(order))) {
      return;
    }
    this.savedOrderNoteId.set(null);
    this.editingOrderNoteId.set(order.id);
    this.orderNoteDrafts.update((drafts) => ({
      ...drafts,
      [order.id]: this.editableOrderNoteText(order)
    }));
  }

  setOrderNoteDraft(order: OrderItem, value: string): void {
    this.orderNoteDrafts.update((drafts) => ({ ...drafts, [order.id]: value }));
  }

  cancelOrderNoteEdit(order: OrderItem): void {
    if (this.isMutating(this.orderNoteMutationKey(order))) {
      return;
    }
    this.editingOrderNoteId.set(null);
    this.orderNoteDrafts.update((drafts) => this.removeKey(drafts, order.id));
  }

  async saveOrderNote(order: OrderItem): Promise<void> {
    if (!this.permissions().canEditNotes) {
      return;
    }
    const value = this.orderNoteValue(order);
    const key = this.orderNoteMutationKey(order);
    this.mutationKey.set(key);
    try {
      await firstValueFrom(this.api.updateWorkerOrderNote(order.id, value));
      this.patchOrder(order.id, { orderComments: value || 'нет заметок' });
      this.editingOrderNoteId.set(null);
      this.savedOrderNoteId.set(order.id);
      this.orderNoteDrafts.update((drafts) => this.removeKey(drafts, order.id));
      setTimeout(() => {
        if (this.savedOrderNoteId() === order.id) {
          this.savedOrderNoteId.set(null);
        }
      }, 1200);
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Заметка заказа не сохранилась.'));
    } finally {
      this.mutationKey.set(null);
    }
  }

  toggleOrderCompanyNote(order: OrderItem): void {
    this.openedOrderCompanyNoteId.update((id) => id === order.id ? null : order.id);
    this.companyNoteDrafts.update((drafts) => ({
      ...drafts,
      [order.id]: drafts[order.id] ?? (order.companyComments ?? '')
    }));
  }

  setCompanyNoteDraft(order: OrderItem, value: string): void {
    this.companyNoteDrafts.update((drafts) => ({ ...drafts, [order.id]: value }));
  }

  async saveOrderCompanyNote(order: OrderItem): Promise<void> {
    if (!this.permissions().canEditNotes) {
      return;
    }
    const value = this.companyNoteValue(order);
    const key = this.companyNoteMutationKey(order);
    this.mutationKey.set(key);
    try {
      await firstValueFrom(this.api.updateWorkerOrderCompanyNote(order.id, value));
      this.patchCompanyNote(order.companyId, value);
      this.openedOrderCompanyNoteId.set(null);
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Заметка компании не сохранилась.'));
    } finally {
      this.mutationKey.set(null);
    }
  }

  startReviewFieldEdit(review: WorkerReviewItem, field: ReviewEditableField): void {
    if (!this.permissions().canWorkReviews || this.isMutating(this.reviewFieldMutationKey(review, field))) {
      return;
    }
    const key = this.reviewFieldKey(review, field);
    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(key);
    this.reviewFieldDrafts.update((drafts) => ({
      ...drafts,
      [key]: drafts[key] ?? this.reviewFieldSourceValue(review, field)
    }));
  }

  setReviewFieldDraft(review: WorkerReviewItem, field: ReviewEditableField, value: string): void {
    this.reviewFieldDrafts.update((drafts) => ({ ...drafts, [this.reviewFieldKey(review, field)]: value }));
  }

  cancelReviewFieldEdit(review: WorkerReviewItem, field: ReviewEditableField): void {
    if (this.isMutating(this.reviewFieldMutationKey(review, field))) {
      return;
    }
    const key = this.reviewFieldKey(review, field);
    this.editingReviewFieldKey.set(null);
    this.reviewFieldDrafts.update((drafts) => this.removeKey(drafts, key));
  }

  async saveReviewField(review: WorkerReviewItem, field: ReviewEditableField): Promise<void> {
    if (!this.permissions().canWorkReviews) {
      return;
    }
    const value = this.reviewFieldValue(review, field);
    if (field === 'text' && !value.trim()) {
      this.error.set('Поле отзыва не должно быть пустым.');
      return;
    }
    const key = this.reviewFieldMutationKey(review, field);
    this.mutationKey.set(key);
    try {
      if (field === 'text' && this.isBadTask(review) && review.badTaskId) {
        await firstValueFrom(this.api.updateWorkerBadReviewTask(review.badTaskId, value, review.badTaskScheduledDate || review.publishedDate || null));
      } else if (field === 'text' && this.isRecoveryTask(review) && review.recoveryTaskId) {
        await firstValueFrom(this.api.updateWorkerRecoveryTask(review.recoveryTaskId, value, review.recoveryTaskScheduledDate || review.publishedDate || null));
      } else if (field === 'text') {
        await firstValueFrom(this.api.updateWorkerReviewText(review.id, review.orderId, value));
      } else {
        await firstValueFrom(this.api.updateWorkerReviewAnswer(review.id, review.orderId, value));
      }
      this.patchReview(review.id, { [field]: value } as Partial<WorkerReviewItem>);
      this.savedReviewFieldKey.set(this.reviewFieldKey(review, field));
      setTimeout(() => {
        const fieldKey = this.reviewFieldKey(review, field);
        if (this.savedReviewFieldKey() === fieldKey) {
          this.savedReviewFieldKey.set(null);
        }
        if (this.editingReviewFieldKey() === fieldKey) {
          this.editingReviewFieldKey.set(null);
        }
        this.reviewFieldDrafts.update((drafts) => this.removeKey(drafts, fieldKey));
      }, 1000);
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, field === 'text' ? 'Текст не сохранился.' : 'Замечание не сохранилось.'));
    } finally {
      this.mutationKey.set(null);
    }
  }

  toggleReviewText(review: WorkerReviewItem): void {
    this.expandedReviewTextIds.update((items) => ({ ...items, [review.id]: !items[review.id] }));
  }

  toggleReviewNotes(review: WorkerReviewItem): void {
    this.openedReviewNotesId.update((id) => id === review.id ? null : review.id);
    this.reviewNoteDrafts.update((drafts) => ({ ...drafts, [review.id]: drafts[review.id] ?? (review.comment ?? '') }));
    this.sideNoteDrafts.update((drafts) => ({
      ...drafts,
      [this.sideNoteKey(review, 'order')]: drafts[this.sideNoteKey(review, 'order')] ?? (review.orderComments ?? ''),
      [this.sideNoteKey(review, 'company')]: drafts[this.sideNoteKey(review, 'company')] ?? (review.commentCompany ?? '')
    }));
  }

  setReviewNoteDraft(review: WorkerReviewItem, value: string): void {
    this.reviewNoteDrafts.update((drafts) => ({ ...drafts, [review.id]: value }));
  }

  async saveReviewNote(review: WorkerReviewItem): Promise<void> {
    const value = this.reviewNoteValue(review);
    const key = this.reviewNoteMutationKey(review);
    this.mutationKey.set(key);
    try {
      await firstValueFrom(this.api.updateWorkerReviewNote(review.id, review.orderId, value));
      this.patchReview(review.id, { comment: value });
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Заметка отзыва не сохранилась.'));
    } finally {
      this.mutationKey.set(null);
    }
  }

  setSideNoteDraft(review: WorkerReviewItem, field: SideNoteField, value: string): void {
    this.sideNoteDrafts.update((drafts) => ({ ...drafts, [this.sideNoteKey(review, field)]: value }));
  }

  async saveSideNote(review: WorkerReviewItem, field: SideNoteField): Promise<void> {
    const value = this.sideNoteValue(review, field);
    const key = this.sideNoteMutationKey(review, field);
    this.mutationKey.set(key);
    try {
      if (field === 'order') {
        await firstValueFrom(this.api.updateWorkerOrderNote(review.orderId, value));
        this.patchReviewsByOrder(review.orderId, { orderComments: value });
      } else {
        await firstValueFrom(this.api.updateWorkerOrderCompanyNote(review.orderId, value));
        this.patchCompanyNote(review.companyId, value);
      }
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Заметка не сохранилась.'));
    } finally {
      this.mutationKey.set(null);
    }
  }

  async uploadReviewPhoto(review: WorkerReviewItem, event: Event): Promise<void> {
    const input = event.target as HTMLInputElement | null;
    const file = input?.files?.[0];
    if (!file || this.uploadingPhotoReviewId()) {
      return;
    }

    this.uploadingPhotoReviewId.set(review.id);
    try {
      const updatedReview = await firstValueFrom(this.api.uploadManagerOrderReviewPhoto(review.orderId, review.id, file));
      this.patchReview(review.id, {
        productPhoto: updatedReview.productPhoto,
        url: updatedReview.url,
        urlPhoto: updatedReview.urlPhoto
      });
      this.showBoardNotice('Фото отзыва загружено.');
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Фото отзыва не загрузилось.'));
    } finally {
      if (input) {
        input.value = '';
      }
      this.uploadingPhotoReviewId.set(null);
    }
  }

  async copyPhone(order: OrderItem): Promise<void> {
    await this.copyText(normalizePhoneDigits(order.companyTelephone), `phone-${order.id}`);
  }

  async copyOrderText(order: OrderItem, kind: 'check' | 'payment'): Promise<void> {
    const value = kind === 'payment'
      ? `${order.companyTitle}. ${order.filialTitle || ''}\n\nСумма к оплате: ${this.money(order.totalSumWithBadReviews ?? order.sum ?? 0)}`
      : `${order.companyTitle}. ${order.filialTitle || ''}\n\nПроверьте, пожалуйста, отзывы по заказу #${order.id}`;
    await this.copyText(value, `${kind}-${order.id}`);
  }

  async copyReviewValue(review: WorkerReviewItem, kind: ReviewCopyKind): Promise<void> {
    if (this.activeSection() === 'nagul' && (kind === 'login' || kind === 'password')) {
      this.api.logWorkerReviewCopyClick(review.id, kind).subscribe({ error: () => undefined });
    }
    const value = {
      url: review.filialUrl || review.url || '',
      login: review.botLogin || '',
      password: review.botPassword || '',
      text: review.text || '',
      answer: review.answer || '',
      vk: 'https://vk.com/'
    }[kind];
    await this.copyText(value, `${kind}-${review.id}`);
  }

  async changeReviewBot(review: WorkerReviewItem): Promise<void> {
    const key = this.reviewBotMutationKey(review, 'change');
    const request = this.isRecoveryTask(review) && review.recoveryTaskId
      ? () => this.api.changeWorkerRecoveryTaskBot(review.recoveryTaskId!)
      : this.isBadTask(review) && review.badTaskId
        ? () => this.api.changeWorkerBadReviewTaskBot(review.badTaskId!)
        : () => this.api.changeWorkerReviewBot(review.id);
    await this.runMutation(key, request, 'Не удалось заменить аккаунт.');
  }

  async deactivateReviewBot(review: WorkerReviewItem): Promise<void> {
    if (!review.botId) {
      return;
    }
    const confirmed = window.confirm(`Заблокировать аккаунт "${review.botFio || review.botId}" и заменить в карточке?`);
    if (!confirmed) {
      return;
    }
    const key = this.reviewBotMutationKey(review, 'block');
    const request = this.isRecoveryTask(review) && review.recoveryTaskId
      ? () => this.api.deactivateWorkerRecoveryTaskBot(review.recoveryTaskId!, review.botId!)
      : this.isBadTask(review) && review.badTaskId
        ? () => this.api.deactivateWorkerBadReviewTaskBot(review.badTaskId!, review.botId!)
        : () => this.api.deactivateWorkerReviewBot(review.id, review.botId!);
    await this.runMutation(key, request, 'Не удалось заблокировать аккаунт.');
  }

  async markReviewDone(review: WorkerReviewItem): Promise<void> {
    const key = this.reviewDoneMutationKey(review);
    if (this.isRecoveryTask(review) && review.recoveryTaskId) {
      await this.runMutation(key, () => this.api.completeWorkerRecoveryTask(review.recoveryTaskId!), 'Не удалось отметить восстановление.');
      return;
    }
    if (this.isBadTask(review) && review.badTaskId) {
      await this.runMutation(key, () => this.api.completeWorkerBadReviewTask(review.badTaskId!), 'Не удалось выполнить плохую задачу.');
      return;
    }
    if (this.activeSection() === 'nagul') {
      await this.runMutation(key, () => this.api.nagulWorkerReview(review.id), 'Не удалось отметить выгул.');
      return;
    }
    await this.runMutation(key, () => this.api.publishWorkerReview(review.id), 'Не удалось отметить публикацию.');
  }

  openOrderDetails(order: OrderItem): void {
    if (!order.companyId) {
      return;
    }
    void this.router.navigate(['/tabs/orders', order.companyId, order.id]);
  }

  openReviewDetails(review: WorkerReviewItem): void {
    if (!review.companyId || !review.orderId) {
      return;
    }
    void this.router.navigate(['/tabs/orders', review.companyId, review.orderId]);
  }

  sectionLabel(section: WorkerBoardSection): string {
    return WORKER_SECTION_LABELS[section];
  }

  sectionIcon(section: WorkerBoardSection): string {
    return this.sectionMetric(section)?.icon || WORKER_SECTION_ICONS[section];
  }

  sectionTone(section: WorkerBoardSection): string {
    return `tone-${this.sectionMetric(section)?.tone || 'blue'}`;
  }

  metricValue(section: WorkerBoardSection): number {
    return Number(this.sectionMetric(section)?.value ?? 0);
  }

  metricDelta(section: WorkerBoardSection): number {
    return Number(this.sectionMetric(section)?.delta ?? 0);
  }

  activeTotal(): number {
    return this.activePage().totalElements ?? 0;
  }

  pageLabel(): string {
    return `${this.currentPageIndex()} / ${this.currentPageTotal()}`;
  }

  sortTitle(): string {
    return this.sortDirection() === 'desc' ? 'Сначала новые' : 'Сначала старые';
  }

  isOrderSection(section = this.activeSection()): boolean {
    return section === 'all' || section === 'new' || section === 'correct';
  }

  isReviewSection(section = this.activeSection()): boolean {
    return REVIEW_SECTIONS.has(section);
  }

  currentPageIndex(): number {
    const page = this.activePage();
    return (page.number ?? page.pageNumber ?? this.pageNumber()) + 1;
  }

  currentPageTotal(): number {
    return this.activePage().totalPages || 1;
  }

  isFirstPage(): boolean {
    return this.activePage().first ?? this.pageNumber() <= 0;
  }

  isLastPage(): boolean {
    const page = this.activePage();
    return page.last ?? this.currentPageIndex() >= this.currentPageTotal();
  }

  isSectionLocked(section: WorkerBoardSection): boolean {
    return this.board()?.warning === true && (section === 'all' || section === 'publish');
  }

  reviewKey(review: WorkerReviewItem): string {
    return `${review.id}-${review.badTaskId ?? 0}-${review.recoveryTaskId ?? 0}`;
  }

  orderAmountLabel(order: OrderItem): string {
    return this.permissions().canSeeMoney
      ? `${this.money(order.totalSumWithBadReviews ?? order.sum ?? 0)}`
      : `${order.amount ?? 0} шт.`;
  }

  showBadReviewSummary(order: OrderItem): boolean {
    return order.status !== 'Оплачено' && (order.badReviewTasksTotal ?? 0) > 0;
  }

  orderChatUrl(order: OrderItem): string {
    return order.companyUrlChat || phoneHref(order.companyTelephone);
  }

  formatPhone(phone?: string): string {
    return displayPhone(phone, 'телефон');
  }

  orderCity(order: OrderItem): string {
    return order.filialCity?.trim() || 'Город не указан';
  }

  orderProgress(order: OrderItem): number {
    if (!order.amount || !order.counter) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round((order.counter / order.amount) * 100)));
  }

  orderToneClass(order: OrderItem): string {
    if (order.waitingForClient) {
      return 'tone-wait';
    }
    switch (order.status) {
      case 'В проверку':
      case 'Выставлен счет':
      case 'Архив':
        return 'tone-walk';
      case 'На проверке':
      case 'Напоминание':
        return 'tone-wait';
      case 'Коррекция':
        return 'tone-correction';
      case 'Публикация':
        return 'tone-publication';
      case 'Опубликовано':
      case 'Оплачено':
        return 'tone-success';
      case 'Не оплачено':
        return 'tone-bad';
      default:
        return '';
    }
  }

  orderLeadToneClass(order: OrderItem): string {
    if (order.waitingForClient) {
      return 'lead-card--new';
    }

    switch (order.status) {
      case 'Новый':
      case 'На проверке':
      case 'Напоминание':
        return 'lead-card--new';
      case 'Коррекция':
      case 'Публикация':
        return 'lead-card--send';
      case 'Опубликовано':
      case 'Оплачено':
        return 'lead-card--work';
      default:
        return '';
    }
  }

  orderNoteBadge(order: OrderItem): string {
    const hasOrder = this.hasMeaningfulNote(order.orderComments);
    const hasCompany = this.hasMeaningfulNote(order.companyComments);
    if (hasOrder && hasCompany) {
      return '2 заметки';
    }
    if (hasOrder) {
      return 'заметка';
    }
    if (hasCompany) {
      return 'комп.';
    }
    return '';
  }

  openOrderNoteBadge(order: OrderItem): void {
    if (this.hasMeaningfulNote(order.companyComments)) {
      this.toggleOrderCompanyNote(order);
      return;
    }

    this.startOrderNoteEdit(order);
    window.setTimeout(() => {
      document.getElementById(`workerOrderNote${order.id}`)?.focus();
    });
  }

  workerLabel(order: OrderItem): string {
    const fio = (order.workerUserFio ?? '').trim();
    if (!fio) {
      return '-';
    }

    const [first, second] = fio.split(/\s+/);
    return second ? `${first} ${second.charAt(0)}.` : first;
  }

  canManageClientWaiting(order: OrderItem): boolean {
    return this.permissions().canManageClientWaiting
      && (order.status === 'Новый' || order.status === 'Коррекция' || !!order.waitingForClient);
  }

  unchangedDays(order: OrderItem): number {
    return Math.max(0, order.dayToChangeStatusAgo ?? 0);
  }

  orderMutationKey(order: OrderItem, status: string): string {
    return `order-${order.id}-${status}`;
  }

  clientWaitingMutationKey(order: OrderItem): string {
    return `order-${order.id}-client-waiting`;
  }

  orderNoteMutationKey(order: OrderItem): string {
    return `save-order-note-${order.id}`;
  }

  companyNoteMutationKey(order: OrderItem): string {
    return `save-company-note-${order.id}`;
  }

  orderNoteValue(order: OrderItem): string {
    return this.orderNoteDrafts()[order.id] ?? this.editableOrderNoteText(order);
  }

  editableOrderNoteText(order: OrderItem): string {
    const value = (order.orderComments ?? '').trim();
    return this.hasMeaningfulNote(value) ? value : '';
  }

  isOrderNoteChanged(order: OrderItem): boolean {
    return this.orderNoteValue(order) !== this.editableOrderNoteText(order);
  }

  companyNoteValue(order: OrderItem): string {
    return this.companyNoteDrafts()[order.id] ?? (order.companyComments ?? '');
  }

  isCompanyNoteChanged(order: OrderItem): boolean {
    return this.companyNoteValue(order) !== (order.companyComments ?? '');
  }

  reviewToneClass(review: WorkerReviewItem): string {
    if (this.isBadTask(review) || this.activeSection() === 'bad') {
      return 'tone-bad';
    }
    if (this.isRecoveryTask(review) || this.activeSection() === 'recovery') {
      return 'tone-recovery';
    }
    if (this.activeSection() === 'nagul') {
      return 'tone-walk';
    }
    if (this.activeSection() === 'publish') {
      return 'tone-publication';
    }
    return '';
  }

  isBadTask(review: WorkerReviewItem): boolean {
    return !!review.badTask;
  }

  isRecoveryTask(review: WorkerReviewItem): boolean {
    return !!review.recoveryTask;
  }

  ratingTaskLabel(review: WorkerReviewItem): string {
    return `${review.originalRating ?? 5} -> ${review.targetRating ?? 2}`;
  }

  reviewTitle(review: WorkerReviewItem): string {
    const company = review.companyTitle?.trim() || 'Компания';
    const filial = review.filialTitle?.trim();
    return filial && (this.activeSection() === 'nagul' || this.activeSection() === 'recovery' || this.activeSection() === 'publish')
      ? `${company} - ${filial}`
      : company;
  }

  reviewFooterLabel(review: WorkerReviewItem): string {
    const workerOnly = this.auth.hasRealmRole('WORKER') && !this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
    return workerOnly ? (review.filialCity?.trim() || 'город') : (review.workerFio?.trim() || 'специалист');
  }

  reviewCity(review: WorkerReviewItem): string {
    return review.filialCity?.trim() || 'Город не указан';
  }

  reviewDate(review: WorkerReviewItem): string {
    return review.recoveryTaskScheduledDate || review.badTaskScheduledDate || review.publishedDate || 'Не назначено';
  }

  needsReviewPhoto(review: WorkerReviewItem): boolean {
    return !!review.productPhoto;
  }

  hasReviewPhoto(review: WorkerReviewItem): boolean {
    return this.needsReviewPhoto(review) && Boolean(review.urlPhoto || review.url);
  }

  reviewPhotoUrl(review: WorkerReviewItem): string {
    return (review.urlPhoto || review.url || '').trim();
  }

  hasReviewAnyNote(review: WorkerReviewItem): boolean {
    return this.hasMeaningfulNote(review.comment)
      || this.hasMeaningfulNote(review.orderComments)
      || this.hasMeaningfulNote(review.commentCompany);
  }

  reviewFieldKey(review: WorkerReviewItem, field: ReviewEditableField): string {
    return `${review.id}-${field}`;
  }

  reviewFieldMutationKey(review: WorkerReviewItem, field: ReviewEditableField): string {
    return `save-${field}-${review.id}`;
  }

  reviewFieldSourceValue(review: WorkerReviewItem, field: ReviewEditableField): string {
    return field === 'text' ? review.text ?? '' : review.answer ?? '';
  }

  reviewFieldValue(review: WorkerReviewItem, field: ReviewEditableField): string {
    const key = this.reviewFieldKey(review, field);
    return this.reviewFieldDrafts()[key] ?? this.reviewFieldSourceValue(review, field);
  }

  isReviewFieldEditing(review: WorkerReviewItem, field: ReviewEditableField): boolean {
    return this.editingReviewFieldKey() === this.reviewFieldKey(review, field);
  }

  isReviewFieldChanged(review: WorkerReviewItem, field: ReviewEditableField): boolean {
    return this.reviewFieldValue(review, field) !== this.reviewFieldSourceValue(review, field);
  }

  shouldShowReviewTextToggle(review: WorkerReviewItem): boolean {
    const value = this.reviewFieldSourceValue(review, 'text');
    return value.length > 190 || value.split(/\r?\n/).length > 5;
  }

  isReviewTextExpanded(review: WorkerReviewItem): boolean {
    return Boolean(this.expandedReviewTextIds()[review.id]);
  }

  reviewNoteMutationKey(review: WorkerReviewItem): string {
    return `save-note-${review.id}`;
  }

  reviewNoteValue(review: WorkerReviewItem): string {
    return this.reviewNoteDrafts()[review.id] ?? (review.comment ?? '');
  }

  isReviewNoteChanged(review: WorkerReviewItem): boolean {
    return this.reviewNoteValue(review) !== (review.comment ?? '');
  }

  sideNoteKey(review: WorkerReviewItem, field: SideNoteField): string {
    return `${field}-${review.id}`;
  }

  sideNoteMutationKey(review: WorkerReviewItem, field: SideNoteField): string {
    return `save-side-${field}-${review.id}`;
  }

  sideNoteValue(review: WorkerReviewItem, field: SideNoteField): string {
    return this.sideNoteDrafts()[this.sideNoteKey(review, field)]
      ?? (field === 'order' ? review.orderComments ?? '' : review.commentCompany ?? '');
  }

  isSideNoteChanged(review: WorkerReviewItem, field: SideNoteField): boolean {
    return this.sideNoteValue(review, field) !== (field === 'order' ? review.orderComments ?? '' : review.commentCompany ?? '');
  }

  botLabel(review: WorkerReviewItem): string {
    if (this.hasUnavailableBot(review)) {
      return 'нет доступных аккаунтов';
    }
    return review.botFio ? `${review.botFio} ${review.botCounter || ''}`.trim() : (review.productTitle || 'Аккаунт');
  }

  hasUnavailableBot(review: WorkerReviewItem): boolean {
    return (review.botFio ?? '').trim().toLocaleLowerCase('ru-RU') === 'нет доступных аккаунтов';
  }

  botBrowserUrl(review: WorkerReviewItem): string {
    return review.botId ? `/admin/dictionaries/accounts/${review.botId}/browser` : 'https://vk.com/';
  }

  doneLabel(review: WorkerReviewItem): string {
    if (this.isRecoveryTask(review)) {
      return 'Восстановил';
    }
    if (this.isBadTask(review)) {
      return 'Сменил';
    }
    if (this.isPublishedPublishAction(review)) {
      return 'ОПУБЛИКОВАНО';
    }
    return this.activeSection() === 'nagul' ? 'ВЫГУЛЯЛ' : 'ОПУБЛИКОВАЛ';
  }

  isPublishedPublishAction(review: WorkerReviewItem): boolean {
    return this.activeSection() === 'publish' && !this.isBadTask(review) && !!review.publish;
  }

  reviewBotMutationKey(review: WorkerReviewItem, action: 'change' | 'block'): string {
    return `review-${review.id}-${action === 'change' ? 'change-bot' : 'block-bot'}`;
  }

  reviewDoneMutationKey(review: WorkerReviewItem): string {
    return this.activeSection() === 'nagul' && !this.isBadTask(review) && !this.isRecoveryTask(review)
      ? `review-${review.id}-nagul`
      : `review-${review.id}-publish`;
  }

  orderDetailsHref(order: OrderItem): string {
    return order.companyId ? `/tabs/orders/${order.companyId}/${order.id}` : '/tabs/worker';
  }

  reviewDetailsHref(review: WorkerReviewItem): string {
    return review.companyId && review.orderId ? `/tabs/orders/${review.companyId}/${review.orderId}` : '/tabs/worker';
  }

  hasMeaningfulNote(value?: string | null): boolean {
    const normalized = (value ?? '').trim().toLowerCase();
    return Boolean(normalized) && normalized !== 'нет заметок';
  }

  money(value?: number): string {
    return `${Math.round(Number(value ?? 0)).toLocaleString('ru-RU')} руб.`;
  }

  isMutating(key: string): boolean {
    return this.mutationKey() === key;
  }

  overdueMaxDays(summary: ManagerOverdueOrders): number {
    return summary.statuses.reduce((max, status) => Math.max(max, status.maxDays), 0);
  }

  private async load(section: WorkerBoardSectionQuery = this.activeSection()): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const board = await firstValueFrom(this.api.getWorkerBoard({
        section,
        keyword: this.keyword(),
        pageNumber: this.pageNumber(),
        pageSize: this.pageSize(),
        sortDirection: this.sortDirection(),
        workerId: this.selectedWorkerId()
      }));
      this.board.set(board);
      this.selectedWorkerId.set(board.selectedWorkerId ?? null);
      if (board.section && board.section !== this.activeSection()) {
        this.activeSection.set(board.section);
        this.pageNumber.set(board.reviews?.number || board.orders?.number || 0);
      }
      if (board.message) {
        this.showBoardNotice(board.message);
      }
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Не удалось загрузить раздел специалиста.'));
    } finally {
      this.loading.set(false);
    }
  }

  private activePage(): Page<OrderItem> | Page<WorkerReviewItem> {
    const board = this.board();
    return this.isOrderSection()
      ? board?.orders ?? { ...EMPTY_ORDER_PAGE, number: this.pageNumber(), size: this.pageSize() }
      : board?.reviews ?? { ...EMPTY_REVIEW_PAGE, number: this.pageNumber(), size: this.pageSize() };
  }

  private sectionMetric(section: WorkerBoardSection): MetricItem | undefined {
    return this.board()?.metrics?.find((metric) => metric.section === section);
  }

  private applyMobileNavIntent(load: boolean, params: ParamMap = this.route.snapshot.queryParamMap): boolean {
    const intent = params.get('mobileNav');
    if (intent === 'menu') {
      this.sectionSheetOpen.set(true);
      return false;
    }
    if (intent === 'all') {
      this.selectDefaultSection(load);
      return load;
    }
    return false;
  }

  private selectDefaultSection(load = true): void {
    this.activeSection.set(DEFAULT_WORKER_SECTION);
    this.pageNumber.set(0);
    this.listExpanded.set(false);
    this.sectionSheetOpen.set(false);
    if (load) {
      void this.load();
    }
  }

  private mobileNavKey(params: ParamMap = this.route.snapshot.queryParamMap): string {
    return `${params.get('mobileNav') ?? ''}:${params.get('navTs') ?? ''}`;
  }

  private async runMutation<T>(key: string, request: () => { subscribe: unknown } | import('rxjs').Observable<T>, fallback: string): Promise<void> {
    this.mutationKey.set(key);
    try {
      await firstValueFrom(request() as import('rxjs').Observable<T>);
      await this.load();
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, fallback));
    } finally {
      this.mutationKey.set(null);
    }
  }

  private async copyText(text: string, key: string): Promise<void> {
    const value = text.trim();
    if (!value) {
      return;
    }
    try {
      await navigator.clipboard.writeText(value);
      this.copiedKey.set(key);
      setTimeout(() => {
        if (this.copiedKey() === key) {
          this.copiedKey.set(null);
        }
      }, 1200);
    } catch {
      this.error.set('Браузер не дал доступ к буферу обмена.');
    }
  }

  private patchOrder(orderId: number, patch: Partial<OrderItem>): void {
    this.board.update((board) => board ? {
      ...board,
      orders: {
        ...board.orders,
        content: board.orders.content.map((order) => order.id === orderId ? { ...order, ...patch } : order)
      }
    } : board);
  }

  private patchReview(reviewId: number, patch: Partial<WorkerReviewItem>): void {
    this.board.update((board) => board ? {
      ...board,
      reviews: {
        ...board.reviews,
        content: board.reviews.content.map((review) => review.id === reviewId ? { ...review, ...patch } : review)
      }
    } : board);
  }

  private patchReviewsByOrder(orderId: number, patch: Partial<WorkerReviewItem>): void {
    this.board.update((board) => board ? {
      ...board,
      reviews: {
        ...board.reviews,
        content: board.reviews.content.map((review) => review.orderId === orderId ? { ...review, ...patch } : review)
      }
    } : board);
  }

  private patchCompanyNote(companyId: number | undefined, value: string): void {
    if (!companyId) {
      return;
    }
    this.board.update((board) => board ? {
      ...board,
      orders: {
        ...board.orders,
        content: board.orders.content.map((order) => order.companyId === companyId ? { ...order, companyComments: value } : order)
      },
      reviews: {
        ...board.reviews,
        content: board.reviews.content.map((review) => review.companyId === companyId ? { ...review, commentCompany: value } : review)
      }
    } : board);
  }

  private removeKey<T, K extends string | number>(record: Record<K, T>, key: K): Record<K, T> {
    const next = { ...record };
    delete next[key];
    return next;
  }

  private clearSearchTimer(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
      this.searchTimer = null;
    }
  }

  private showBoardNotice(message: string): void {
    this.clearNoticeTimer();
    this.boardNotice.set(message);
    this.noticeTimer = setTimeout(() => {
      this.boardNotice.set(null);
      this.noticeTimer = null;
    }, 4200);
  }

  private clearNoticeTimer(): void {
    if (this.noticeTimer) {
      clearTimeout(this.noticeTimer);
      this.noticeTimer = null;
    }
  }

  private loadDailyOverdueReminder(): void {
    const today = this.localDateKey();
    const storageKey = this.overdueAlertStorageKey();
    if (this.readStoredDate(storageKey) === today) {
      return;
    }
    this.api.getWorkerOverdueOrders().subscribe({
      next: (summary) => {
        this.writeStoredDate(storageKey, today);
        const normalized = { ...summary, statuses: summary.statuses ?? [] };
        if (normalized.total > 0) {
          this.overdueOrders.set(normalized);
          this.overdueModalOpen.set(true);
        }
      },
      error: () => undefined
    });
  }

  private overdueAlertStorageKey(): string {
    const user = this.auth.user();
    return `otziv-worker-overdue-alert:v2:${user?.preferredUsername || user?.subject || 'user'}`;
  }

  private localDateKey(date = new Date()): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  private readStoredDate(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private writeStoredDate(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      // Storage can be unavailable; the reminder can simply try again later.
    }
  }

  private workerSectionForOrderStatus(status: string): WorkerBoardSection {
    if (status === 'Новый') {
      return 'new';
    }
    if (status === 'Коррекция') {
      return 'correct';
    }
    return 'all';
  }

  private apiErrorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const backendMessage = typeof error.error === 'object' && error.error && 'message' in error.error
        ? String((error.error as { message?: unknown }).message ?? '')
        : typeof error.error === 'string'
          ? error.error
          : '';
      const message = backendMessage.trim();
      if (message) {
        return message;
      }
      if (error.status) {
        return `${fallback} Код ${error.status}.`;
      }
    }
    return error instanceof Error ? error.message : fallback;
  }
}
