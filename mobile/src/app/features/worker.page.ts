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
  WorkerActivitySource,
  WorkerBotItem,
  WorkerOption,
  WorkerReviewItem
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileBottomPagerComponent } from '../shared/mobile-bottom-pager.component';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileOrderCardComponent, type MobileOrderCopyKind } from '../shared/mobile-order-card.component';
import { MobileRemindersComponent } from '../shared/mobile-reminders.component';
import { MobileReviewFieldEditorComponent } from '../shared/mobile-review-field-editor.component';
import { MobileReviewCardShellComponent } from '../shared/mobile-review-card-shell.component';
import { MobileSearchBarComponent } from '../shared/mobile-search-bar.component';
import { MobileStatusSliderComponent, type MobileStatusItem } from '../shared/mobile-status-slider.component';
import {
  mobilePageIndex,
  mobilePageIsFirst,
  mobilePageIsLast,
  mobilePageLabel,
  mobilePageTotal,
  mobileSortTitle
} from '../shared/mobile-board.helpers';
import { MobileMediaService } from '../shared/mobile-media.service';
import { displayPhone, normalizePhoneDigits, phoneHref } from '../shared/phone-format';
import {
  DEFAULT_WORKER_PERMISSIONS,
  DEFAULT_WORKER_SECTION,
  EMPTY_ORDER_PAGE,
  EMPTY_REVIEW_PAGE,
  ORDER_STATUS_ACTIONS,
  WORKER_SECTIONS,
  isWorkerReviewSection,
  workerDefaultSortDirection,
  workerReviewTitle,
  workerReviewToneClass,
  workerSectionIcon,
  workerSectionLabel,
  type ReviewCopyKind,
  type ReviewEditableField,
  type SideNoteField,
  type WorkerStatusAction
} from './worker/worker-board.helpers';

type PublishCredentialPreparation = {
  reviewId: number | null;
  botId?: number | null;
  loginAt?: number;
  passwordAt?: number;
  invalidated: Record<number, { botId?: number | null }>;
};
type StoredPublishCredentialPreparation = {
  reviewId: number;
  botId?: number | null;
  loginAt?: number;
  passwordAt?: number;
  updatedAt: number;
};

@Component({
  selector: 'app-worker',
  imports: [
    FormsModule,
    IonContent,
    IonRefresher,
    IonRefresherContent,
    MobileBottomPagerComponent,
    MobileHeaderComponent,
    MobileOrderCardComponent,
    MobileRemindersComponent,
    MobileReviewCardShellComponent,
    MobileReviewFieldEditorComponent,
    MobileSearchBarComponent,
    MobileStatusSliderComponent
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

          <app-mobile-status-slider
            [items]="statusItems()"
            [activeKey]="activeSection()"
            ariaLabel="Разделы специалиста"
            (select)="selectStatusSliderSection($event)"
          />

          <app-mobile-search-bar
            [value]="keyword()"
            placeholder="Компания, филиал, отзыв, аккаунт"
            [showRefresh]="false"
            [hasExtraAction]="true"
            (valueChange)="setKeyword($event)"
            (searchSubmit)="applySearch()"
          >
            @if (keyword()) {
              <button mobileSearchActions type="button" (click)="clearSearch()" aria-label="Очистить поиск">
                <span class="material-icons-sharp">close</span>
              </button>
            }
            @if (workerFilterAvailable()) {
              <button mobileSearchActions type="button" (click)="openWorkerSheet()" aria-label="Фильтр специалиста">
                <span class="material-icons-sharp">person_search</span>
              </button>
            }
            <button mobileSearchActions type="button" (click)="reload()" [disabled]="loading()" aria-label="Обновить">
              <span class="material-icons-sharp">refresh</span>
            </button>
          </app-mobile-search-bar>

          <section
            class="lead-list worker-list"
            [class.worker-list--reviews]="isReviewSection()"
            aria-label="Карточки специалиста"
          >
            @if (isOrderSection()) {
              @for (order of board()?.orders?.content ?? []; track order.id) {
                <app-mobile-order-card
                  [order]="order"
                  [statusActions]="orderStatusActions"
                  [copiedKey]="copiedKey()"
                  [mutationKey]="mutationKey()"
                  [title]="orderTitle(order)"
                  [titleHref]="order.filialUrl || orderDetailsHref(order)"
                  [toneClass]="orderLeadToneClass(order)"
                  [showWaitingBadge]="false"
                  [statusLabel]="order.waitingForClient ? 'Ждем' : (order.status || '-')"
                  [amountLabel]="orderAmountLabel(order)"
                  [badReviewSummary]="showBadReviewSummary(order) ? 'Плохие: ' + (order.badReviewTasksDone || 0) + '/' + (order.badReviewTasksTotal || 0) : ''"
                  [badReviewAmount]="showBadReviewSummary(order) ? '+' + money(order.badReviewTasksSum || 0) : ''"
                  [phoneLabel]="formatPhone(order.companyTelephone)"
                  [phoneHref]="orderChatUrl(order)"
                  [reviewHref]="order.orderDetailsId ? '/' + order.orderDetailsId : orderDetailsHref(order)"
                  [filialHref]="order.filialUrl || orderDetailsHref(order)"
                  [phoneCopyKey]="'phone-' + order.id"
                  [reviewCopyKey]="'check-' + order.id"
                  [paymentCopyKey]="'payment-' + order.id"
                  [progress]="orderProgress(order)"
                  [cityLabel]="orderCity(order)"
                  [workerLabel]="workerLabel(order)"
                  [workerTitle]="order.workerUserFio || 'Специалист не назначен'"
                  [noteBadge]="orderNoteBadge(order)"
                  [canSeePhoneAndPayment]="permissions().canSeePhoneAndPayment"
                  [canManageOrderStatuses]="permissions().canManageOrderStatuses"
                  [canManageClientWaiting]="canManageClientWaiting(order)"
                  [noteEditorId]="'workerOrderNote' + order.id"
                  [noteValue]="orderNoteValue(order)"
                  [noteReadOnly]="!permissions().canEditNotes || isMutating(orderNoteMutationKey(order))"
                  [noteShowActions]="editingOrderNoteId() === order.id"
                  [noteCancelDisabled]="isMutating(orderNoteMutationKey(order))"
                  [noteSaveDisabled]="!isOrderNoteChanged(order) || isMutating(orderNoteMutationKey(order))"
                  [noteSaveIcon]="savedOrderNoteId() === order.id ? 'check' : 'save'"
                  [noteSaved]="savedOrderNoteId() === order.id"
                  [showCompanyNoteEditor]="openedOrderCompanyNoteId() === order.id"
                  [companyNoteValue]="companyNoteValue(order)"
                  [companyNoteReadOnly]="!permissions().canEditNotes || isMutating(companyNoteMutationKey(order))"
                  [companyNoteSaveDisabled]="!isCompanyNoteChanged(order) || isMutating(companyNoteMutationKey(order))"
                  [unchangedDays]="unchangedDays(order)"
                  [unchangedAlert]="unchangedDays(order) >= 2"
                  (copyPhone)="copyPhone(order)"
                  (copyText)="copyWorkerOrderText(order, $event)"
                  (statusChange)="updateOrderStatus(order, $event)"
                  (clientWaitingToggle)="toggleOrderClientWaiting(order)"
                  (noteBadgeClick)="openOrderNoteBadge(order)"
                  (noteStart)="startOrderNoteEdit(order)"
                  (noteChange)="setOrderNoteDraft(order, $event)"
                  (noteCancel)="cancelOrderNoteEdit(order)"
                  (noteSave)="saveOrderNote(order)"
                  (companyNoteChange)="setCompanyNoteDraft(order, $event)"
                  (companyNoteCancel)="toggleOrderCompanyNote(order)"
                  (companyNoteSave)="saveOrderCompanyNote(order)"
                  (details)="openOrderDetails(order)"
                  (workerClick)="openOrderDetails(order)"
                />
              } @empty {
                <div class="empty-state compact-empty">Заказов для отображения нет.</div>
              }
            } @else {
              @for (review of board()?.reviews?.content ?? []; track reviewKey(review)) {
                <app-mobile-review-card-shell
                  class="mobile-worker-review-card"
                  [class.expanded-text]="isReviewTextExpanded(review)"
                  [title]="reviewTitle(review)"
                  [titleHref]="review.filialUrl || reviewDetailsHref(review)"
                  [idLabel]="'#' + review.id"
                  [toneClass]="reviewToneClass(review)"
                  [published]="!!review.publish"
                  [expandedText]="isReviewTextExpanded(review)"
                  [photoMode]="needsReviewPhoto(review) ? (hasReviewPhoto(review) ? 'link' : 'file') : 'none'"
                  [photoUrl]="reviewPhotoUrl(review)"
                  [photoLoading]="uploadingPhotoReviewId() === review.id"
                  [badBadge]="isBadTask(review) ? ratingTaskLabel(review) : ''"
                  [recoveryBadge]="isRecoveryTask(review) ? 'восст.' : ''"
                  [noteVisible]="hasReviewAnyNote(review) || openedReviewNotesId() === review.id"
                  [footerLeft]="reviewDate(review)"
                  [footerRight]="reviewFooterLabel(review)"
                  [footerRightTitle]="reviewFooterLabel(review)"
                  (photoAction)="pickNativeReviewPhoto(review, $event)"
                  (photoFileChange)="uploadReviewPhoto(review, $event)"
                  (noteClick)="toggleReviewNotes(review)"
                  (footerRightClick)="handleReviewFooterClick(review)"
                >

                  <app-mobile-review-field-editor
                    class="review-field review-field--text"
                    [class.editing]="isReviewFieldEditing(review, 'text')"
                    placeholder="Текст отзыва"
                    [readOnly]="!permissions().canWorkReviews || isMutating(reviewFieldMutationKey(review, 'text'))"
                    [disabled]="isMutating(reviewFieldMutationKey(review, 'text'))"
                    [value]="reviewFieldValue(review, 'text')"
                    [editing]="isReviewFieldEditing(review, 'text')"
                    [showToggle]="shouldShowReviewTextToggle(review)"
                    [expanded]="isReviewTextExpanded(review)"
                    [saveDisabled]="!isReviewFieldChanged(review, 'text') || !reviewFieldValue(review, 'text').trim() || isMutating(reviewFieldMutationKey(review, 'text'))"
                    [saveIcon]="savedReviewFieldKey() === reviewFieldKey(review, 'text') ? 'check' : 'save'"
                    (start)="startReviewFieldEdit(review, 'text')"
                    (valueChange)="setReviewFieldDraft(review, 'text', $event)"
                    (toggle)="toggleReviewText(review)"
                    (cancel)="cancelReviewFieldEdit(review, 'text')"
                    (save)="saveReviewField(review, 'text')"
                  />

                  <app-mobile-review-field-editor
                    class="review-field review-field--answer"
                    [class.editing]="isReviewFieldEditing(review, 'answer')"
                    placeholder="Ответ на отзыв или замечание"
                    [readOnly]="!permissions().canWorkReviews || isMutating(reviewFieldMutationKey(review, 'answer'))"
                    [value]="reviewFieldValue(review, 'answer')"
                    [editing]="isReviewFieldEditing(review, 'answer')"
                    [disabled]="isMutating(reviewFieldMutationKey(review, 'answer'))"
                    [saveDisabled]="!isReviewFieldChanged(review, 'answer') || isMutating(reviewFieldMutationKey(review, 'answer'))"
                    [saveIcon]="savedReviewFieldKey() === reviewFieldKey(review, 'answer') ? 'check' : 'save'"
                    (start)="startReviewFieldEdit(review, 'answer')"
                    (valueChange)="setReviewFieldDraft(review, 'answer', $event)"
                    (cancel)="cancelReviewFieldEdit(review, 'answer')"
                    (save)="saveReviewField(review, 'answer')"
                  />

                  <div class="order-city-row review-city-row" [title]="reviewCity(review)">
                    <span class="city-prefix" aria-hidden="true">город</span>
                    <span>{{ reviewCity(review) }}</span>
                  </div>

                  @if (isBadTask(review) && review.badTaskComment) {
                    <div class="bad-task-note-row" [title]="review.badTaskComment">
                      <span class="material-icons-sharp">priority_high</span>
                      <span>{{ review.badTaskComment }}</span>
                    </div>
                  }

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
                    [class.credential-locked]="publishLockedByCredentialWait(review)"
                    (click)="markReviewDone(review)"
                    [disabled]="isPublishedPublishAction(review) || publishLockedByCredentialWait(review) || isMutating(reviewDoneMutationKey(review))"
                    [title]="publishCredentialWaitTitle(review)"
                  >
                    {{ isMutating(reviewDoneMutationKey(review)) ? '...' : doneLabel(review) }}
                  </button>

                </app-mobile-review-card-shell>
              } @empty {
                <div class="empty-state compact-empty">Отзывов для отображения нет.</div>
              }
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
            @if (permissions().canManageBots && workerBots().length) {
              <button mobilePagerActions class="expand-list-button" type="button" (click)="openBotSheet()" aria-label="Аккаунты">
                <span class="material-icons-sharp">manage_accounts</span>
                <small>{{ workerBots().length }}</small>
              </button>
            }
            <button
              mobilePagerActions
              class="expand-list-button"
              type="button"
              [class.active]="sortDirection() === 'asc'"
              (click)="toggleSortDirection()"
              [attr.aria-label]="sortDirection() === 'asc' ? 'Показать сначала давно без изменений' : 'Показать сначала недавно измененные'"
            >
              <span class="material-icons-sharp">swap_vert</span>
            </button>
          </app-mobile-bottom-pager>
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

        @if (botSheetOpen()) {
          <button class="worker-section-backdrop" type="button" aria-label="Закрыть аккаунты" (click)="closeBotSheet()"></button>
          <section class="worker-section-sheet worker-filter-sheet" role="dialog" aria-modal="true" aria-label="Рабочие аккаунты">
            <header>
              <div>
                <small>Аккаунты</small>
                <h2>Рабочие аккаунты</h2>
              </div>
              <button type="button" class="icon-button" (click)="closeBotSheet()" aria-label="Закрыть">
                <span class="material-icons-sharp">close</span>
              </button>
            </header>
            <div class="worker-bot-list">
              @for (bot of workerBots(); track bot.id) {
                <article class="worker-bot-row" [class.inactive]="!bot.active">
                  <header>
                    <strong>{{ bot.fio || bot.login || ('#' + bot.id) }}</strong>
                    <small>{{ bot.city || 'город не указан' }} · {{ bot.status || 'статус' }}</small>
                  </header>
                  <footer>
                    <button type="button" (click)="copyBotValue(bot, 'login')">{{ copiedKey() === 'bot-login-' + bot.id ? 'готово' : 'логин' }}</button>
                    <button type="button" (click)="copyBotValue(bot, 'password')">{{ copiedKey() === 'bot-password-' + bot.id ? 'готово' : 'пароль' }}</button>
                    <a [href]="botBrowserUrlById(bot.id)" target="_blank" rel="noopener">вк</a>
                    <button type="button" class="danger" (click)="deleteBot(bot)" [disabled]="isMutating('bot-' + bot.id + '-delete')">
                      {{ isMutating('bot-' + bot.id + '-delete') ? '...' : 'удалить' }}
                    </button>
                  </footer>
                </article>
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
      gap: var(--otziv-page-gap, 0.46rem);
      padding: var(--otziv-page-padding-y, 0.55rem) var(--otziv-page-padding-x, 0.62rem) calc(var(--otziv-page-padding-bottom, 0.45rem) + env(safe-area-inset-bottom));
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
      align-items: stretch;
      gap: var(--otziv-list-gap, 0.56rem);
      margin-inline: calc(var(--otziv-page-padding-x, 0.62rem) * -1);
      overflow-x: auto;
      overflow-y: hidden;
      padding: 0 var(--otziv-page-padding-x, 0.62rem) 0.12rem;
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

    .bot-line {
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

    .card-actions {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 0.36rem;
    }

    .card-actions button,
    .card-actions a,
    .publish-button,
    .review-notes-panel button {
      display: inline-flex;
      min-width: 0;
      min-height: var(--otziv-card-control-height, 1.5rem);
      align-items: center;
      justify-content: center;
      overflow: hidden;
      border: 1px solid rgba(103, 116, 131, 0.24);
      border-radius: 999px;
      padding: 0 var(--otziv-card-control-padding-x, 0.42rem);
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 var(--otziv-card-control-font-size, 0.6rem)/1 var(--otziv-card-title-font);
      text-decoration: none;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .card-actions button:disabled,
    .publish-button:disabled {
      opacity: 0.58;
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

    .bad-task-note-row {
      display: inline-flex;
      min-width: 0;
      min-height: 1.72rem;
      align-items: center;
      justify-content: center;
      gap: 0.24rem;
      overflow: hidden;
      border: 1px solid rgba(236, 75, 109, 0.22);
      border-radius: 999px;
      padding: 0 0.58rem;
      color: var(--otziv-danger);
      background: linear-gradient(145deg, var(--otziv-white) 0%, rgba(236, 75, 109, 0.08) 100%);
      font: 900 0.62rem/1 var(--otziv-font-family);
    }

    .bad-task-note-row .material-icons-sharp {
      flex: 0 0 auto;
      font-size: 0.88rem;
    }

    .bad-task-note-row span:last-child {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .review-field,
    .review-notes-panel {
      position: relative;
      display: grid;
      min-width: 0;
      gap: 0.35rem;
    }

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

    .publish-button {
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

    .publish-button.credential-locked,
    .publish-button.credential-locked:disabled {
      color: rgba(103, 116, 131, 0.58) !important;
      border-color: rgba(103, 116, 131, 0.16) !important;
      background: #eef1f5 !important;
      opacity: 1;
    }

    .worker-bottom-controls {
      flex: 0 0 auto;
      justify-content: flex-start;
    }

    .worker-section-backdrop {
      position: fixed;
      inset: 0;
      z-index: 700;
      border: 0;
      background: rgba(20, 24, 32, 0.42);
    }

    .worker-section-sheet {
      position: fixed;
      left: 50%;
      top: 50%;
      z-index: 701;
      display: grid;
      grid-template-rows: auto minmax(0, 1fr);
      width: min(23rem, calc(100vw - 1.5rem));
      max-height: min(38rem, calc(100dvh - env(safe-area-inset-top) - env(safe-area-inset-bottom) - 2rem));
      transform: translate(-50%, -50%);
      gap: 0.62rem;
      overflow: hidden;
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
      min-height: 0;
      gap: 0.5rem;
      overflow-y: auto;
      overscroll-behavior: contain;
      scrollbar-width: none;
    }

    .worker-section-options::-webkit-scrollbar,
    .worker-filter-options::-webkit-scrollbar {
      display: none;
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

    .worker-bot-list {
      display: grid;
      gap: 0.5rem;
      overflow-y: auto;
      scrollbar-width: none;
    }

    .worker-bot-row {
      display: grid;
      gap: 0.46rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 0.84rem;
      padding: 0.62rem;
      background: var(--otziv-white);
    }

    .worker-bot-row.inactive {
      opacity: 0.7;
    }

    .worker-bot-row header,
    .worker-bot-row footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.42rem;
      min-width: 0;
    }

    .worker-bot-row strong,
    .worker-bot-row small {
      display: block;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .worker-bot-row strong {
      color: var(--otziv-dark);
      font-size: 0.86rem;
    }

    .worker-bot-row footer {
      flex-wrap: wrap;
    }

    .worker-bot-row footer button,
    .worker-bot-row footer a {
      display: inline-flex;
      min-height: 1.9rem;
      align-items: center;
      justify-content: center;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 999px;
      padding: 0 0.62rem;
      color: var(--otziv-primary);
      background: var(--otziv-white);
      font: 900 0.62rem/1 var(--otziv-font-family);
      text-decoration: none;
      flex: 1 1 4rem;
    }

    .worker-bot-row footer .danger {
      color: var(--otziv-danger);
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

    .overdue-sheet {
      width: min(23rem, calc(100vw - 1.5rem));
      max-height: min(34rem, calc(100dvh - env(safe-area-inset-top) - env(safe-area-inset-bottom) - 2rem));
      grid-template-rows: auto auto minmax(0, 1fr) auto;
      overflow: hidden;
    }

    .overdue-sheet header {
      min-height: 0;
    }

    .overdue-sheet header small {
      font-size: 0.58rem;
      line-height: 1;
    }

    .overdue-sheet .worker-filter-options {
      min-height: 0;
      overflow-y: auto;
      overscroll-behavior: contain;
      padding-right: 0.08rem;
    }

    .overdue-sheet .worker-filter-options button {
      min-height: 2.35rem;
      padding: 0.38rem 0.58rem;
      font-size: 0.78rem;
    }

    .overdue-sheet .worker-filter-options button small {
      grid-column: 1 / -1;
      font-size: 0.58rem;
      line-height: 1;
      text-transform: none;
    }

    .dismiss-button {
      display: flex;
      min-height: 2.2rem;
      justify-content: center;
      padding: 0.42rem 0.58rem;
      font-size: 0.68rem;
      line-height: 1.12;
      text-align: center;
    }

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

    }
  `]
})
export class WorkerPage implements OnInit, OnDestroy {
  private routeSubscription?: Subscription;
  private lastMobileNavKey = '';
  private searchTimer: ReturnType<typeof setTimeout> | null = null;
  private noticeTimer: ReturnType<typeof setTimeout> | null = null;
  private publishCredentialWaitTimer: ReturnType<typeof setInterval> | null = null;
  private readonly publishCredentialPreparationStorageKey = 'otziv-mobile-worker-publish-prep:v1';
  private readonly publishCredentialPreparationMaxAgeMs = 60 * 60 * 1000;
  private readonly publishCredentialWaitMs = 150_000;

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
  readonly botSheetOpen = signal(false);
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
  readonly publishCredentialPreparation = signal<PublishCredentialPreparation>({
    reviewId: null,
    invalidated: {}
  });
  readonly publishCredentialWaitNow = signal(Date.now());

  readonly permissions = computed(() => this.board()?.permissions ?? DEFAULT_WORKER_PERMISSIONS);
  readonly workerBots = computed(() => this.board()?.bots ?? []);
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
  readonly statusItems = computed<MobileStatusItem[]>(() =>
    this.sectionOptions().map((section) => {
      const metric = this.sectionMetric(section);
      const delta = this.metricDelta(section);
      return {
        key: section,
        title: this.sectionLabel(section),
        value: this.metricValue(section),
        icon: metric?.icon || workerSectionIcon(section),
        tone: metric?.tone || 'blue',
        badge: delta > 0 ? `+${delta}` : null,
        disabled: this.isSectionLocked(section)
      };
    })
  );

  constructor(
    private readonly api: ApiService,
    private readonly auth: AuthService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly confirm: MobileConfirmService,
    private readonly media: MobileMediaService
  ) {}

  ngOnInit(): void {
    this.restoreStoredPublishCredentialPreparation();
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
    this.clearPublishCredentialWaitTimer();
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
    this.searchTimer = setTimeout(() => void this.load(), 450);
  }

  applySearch(): void {
    this.pageNumber.set(0);
    this.clearSearchTimer();
    void this.load();
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

  openBotSheet(): void {
    this.botSheetOpen.set(true);
  }

  closeBotSheet(): void {
    this.botSheetOpen.set(false);
  }

  openAllSection(load = true): void {
    this.activeSection.set('all');
    this.pageNumber.set(0);
    this.sortDirection.set(workerDefaultSortDirection('all'));
    this.listExpanded.set(false);
    this.sectionSheetOpen.set(false);
    if (load) {
      void this.load();
    }
  }

  selectSection(section: WorkerBoardSection): void {
    if (this.isSectionLocked(section)) {
      return;
    }

    this.activeSection.set(section);
    this.pageNumber.set(0);
    this.sortDirection.set(workerDefaultSortDirection(section));
    this.listExpanded.set(false);
    this.sectionSheetOpen.set(false);
    void this.load();
  }

  selectStatusSliderSection(section: string): void {
    this.selectSection(section as WorkerBoardSection);
  }

  changeWorkerFilter(worker: WorkerOption | null): void {
    this.selectedWorkerId.set(worker?.id ?? null);
    this.pageNumber.set(0);
    this.sortDirection.set(workerDefaultSortDirection(this.activeSection()));
    this.listExpanded.set(false);
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
    this.sortDirection.set(workerDefaultSortDirection(this.activeSection()));
    void this.load();
  }

  async updateOrderStatus(order: OrderItem, action: WorkerStatusAction): Promise<void> {
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
      } else if (this.isRecoveryTask(review) && review.recoveryTaskId) {
        const recoveryText = field === 'text' ? value : (review.text ?? '');
        const recoveryAnswer = field === 'answer' ? value : (review.answer ?? '');
        await firstValueFrom(this.api.updateWorkerRecoveryTask(
          review.recoveryTaskId,
          recoveryText,
          review.recoveryTaskScheduledDate || review.publishedDate || null,
          recoveryAnswer
        ));
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

    await this.uploadReviewPhotoFile(review, file, input);
  }

  async pickNativeReviewPhoto(review: WorkerReviewItem, event: Event): Promise<void> {
    if (!this.media.nativePhotoPickerAvailable || this.uploadingPhotoReviewId()) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    const file = await this.media.pickImageFile(`review-${review.id}`);
    if (file) {
      await this.uploadReviewPhotoFile(review, file);
    }
  }

  private async uploadReviewPhotoFile(review: WorkerReviewItem, file: File, input?: HTMLInputElement | null): Promise<void> {
    this.uploadingPhotoReviewId.set(review.id);
    try {
      const preparedFile = await this.media.prepareImageFile(file, `review-${review.id}`);
      const updatedReview = await firstValueFrom(this.api.uploadManagerOrderReviewPhoto(review.orderId, review.id, preparedFile));
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

  async copyWorkerOrderText(order: OrderItem, kind: MobileOrderCopyKind): Promise<void> {
    await this.copyOrderText(order, kind === 'payment' ? 'payment' : 'check');
  }

  async copyReviewValue(review: WorkerReviewItem, kind: ReviewCopyKind): Promise<void> {
    const value = {
      url: review.filialUrl || review.url || '',
      login: review.botLogin || '',
      password: review.botPassword || '',
      text: review.text || '',
      answer: review.answer || '',
      vk: 'https://vk.com/'
    }[kind];
    if (!(await this.copyText(value, `${kind}-${review.id}`))) {
      return;
    }

    if (kind === 'login' || kind === 'password') {
      if (this.activeSection() === 'nagul') {
        this.api.logWorkerReviewCopyClick(review.id, kind, this.workerActivitySource()).subscribe({ error: () => undefined });
        return;
      }

      if (this.activeSection() !== 'publish') {
        return;
      }

      if (!(await this.logReviewCredentialCopyClick(review, kind))) {
        return;
      }
      this.markPublishCredentialCopied(review, kind);
    }
  }

  async copyBotValue(bot: WorkerBotItem, kind: 'login' | 'password'): Promise<void> {
    await this.copyText(kind === 'login' ? bot.login || '' : bot.password || '', `bot-${kind}-${bot.id}`);
  }

  private async logReviewCredentialCopyClick(review: WorkerReviewItem, kind: ReviewCopyKind): Promise<boolean> {
    if (kind !== 'login' && kind !== 'password') {
      return true;
    }

    try {
      await firstValueFrom(this.api.logWorkerReviewCopyClick(review.id, kind, this.workerActivitySource()));
      return true;
    } catch {
      this.error.set('Данные скопированы, но сервер не подтвердил действие. Нажмите кнопку еще раз.');
      return false;
    }
  }

  private markPublishCredentialCopied(review: WorkerReviewItem, kind: ReviewCopyKind): void {
    if ((kind !== 'login' && kind !== 'password') || !this.shouldUsePublishCredentialWait()) {
      return;
    }

    this.publishCredentialPreparation.update((current) => {
      const botId = review.botId ?? null;
      const sameReviewBot = current.reviewId === review.id && current.botId === botId;
      const invalidated = { ...current.invalidated };

      if (current.reviewId !== null && !sameReviewBot) {
        invalidated[current.reviewId] = { botId: current.botId ?? null };
      }
      delete invalidated[review.id];

      const base = sameReviewBot ? current : { reviewId: review.id, botId };
      return {
        ...base,
        invalidated,
        [kind === 'login' ? 'loginAt' : 'passwordAt']: Date.now()
      };
    });
    this.refreshPublishCredentialWaitTimer();
    this.storePublishCredentialPreparation();
  }

  publishCredentialWaitLeftSeconds(review: WorkerReviewItem): number {
    if (!this.shouldUsePublishCredentialWait() || review.publish) {
      return 0;
    }

    const preparation = this.publishCredentialPreparation();
    if (
      preparation.reviewId !== review.id ||
      preparation.botId !== (review.botId ?? null) ||
      !preparation.loginAt ||
      !preparation.passwordAt
    ) {
      return 0;
    }

    const readyAt = Math.max(preparation.loginAt, preparation.passwordAt) + this.publishCredentialWaitMs;
    return Math.max(0, Math.ceil((readyAt - this.publishCredentialWaitNow()) / 1000));
  }

  publishLockedByCredentialWait(review: WorkerReviewItem): boolean {
    if (!this.shouldUsePublishCredentialWait() || review.publish) {
      return false;
    }

    const preparation = this.publishCredentialPreparation();
    const botId = review.botId ?? null;
    const invalidated = preparation.invalidated[review.id];
    if (invalidated && invalidated.botId === botId) {
      return true;
    }

    if (preparation.reviewId === review.id && preparation.botId === botId) {
      return !preparation.loginAt || !preparation.passwordAt || this.publishCredentialWaitLeftSeconds(review) > 0;
    }

    return true;
  }

  publishCredentialWaitTitle(review: WorkerReviewItem): string {
    const preparation = this.publishCredentialPreparation();
    const botId = review.botId ?? null;
    const invalidated = preparation.invalidated[review.id];
    if (invalidated && invalidated.botId === botId) {
      return 'Подготовка сброшена: снова скопируйте логин и пароль.';
    }

    if (preparation.reviewId !== review.id || preparation.botId !== botId) {
      return 'Сначала скопируйте логин и пароль аккаунта.';
    }

    if (!preparation.loginAt || !preparation.passwordAt) {
      const missing = [
        !preparation.loginAt ? 'логин' : '',
        !preparation.passwordAt ? 'пароль' : ''
      ].filter(Boolean).join(', ');
      return `Скопируйте ${missing}.`;
    }

    const left = this.publishCredentialWaitLeftSeconds(review);
    return left > 0
      ? `После копирования логина и пароля подождите еще ${left} сек.`
      : 'Действие с отзывом';
  }

  private shouldUsePublishCredentialWait(): boolean {
    return this.activeSection() === 'publish' && !this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
  }

  private workerActivitySource(): WorkerActivitySource {
    return {
      sourcePage: 'worker-board',
      sourceSection: this.activeSection()
    };
  }

  botBrowserUrlById(botId: number): string {
    return `/admin/dictionaries/accounts/${botId}/browser`;
  }

  async deleteBot(bot: WorkerBotItem): Promise<void> {
    if (!bot?.id || this.isMutating(`bot-${bot.id}-delete`)) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Удалить аккаунт',
      message: `Удалить аккаунт "${bot.fio || bot.login || bot.id}"?`,
      confirmText: 'Удалить',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    await this.runMutation(
      `bot-${bot.id}-delete`,
      () => this.api.deleteWorkerBot(bot.id),
      'Не удалось удалить аккаунт.'
    );
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
    const confirmed = await this.confirm.confirm({
      title: 'Заменить аккаунт',
      message: `Заблокировать аккаунт "${review.botFio || review.botId}" и заменить в карточке?`,
      confirmText: 'Заменить',
      danger: true
    });
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
    if (this.publishLockedByCredentialWait(review)) {
      this.error.set(this.publishCredentialWaitTitle(review));
      return;
    }
    await this.runMutation(
      key,
      () => this.api.publishWorkerReview(review.id),
      'Не удалось отметить публикацию.',
      () => this.clearStoredPublishCredentialPreparation(review.id)
    );
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
    void this.router.navigate(['/tabs/orders', review.companyId, review.orderId], {
      queryParams: this.activeSection() === 'all' ? { from: 'worker-all' } : undefined
    });
  }

  handleReviewFooterClick(review: WorkerReviewItem): void {
    if (this.isRecoveryTask(review) || this.isBadTask(review)) {
      this.startReviewFieldEdit(review, 'text');
      window.setTimeout(() => {
        const selector = `article[data-review-id="${review.id}"] textarea`;
        document.querySelector<HTMLTextAreaElement>(selector)?.focus();
      });
      return;
    }

    this.openReviewDetails(review);
  }

  orderTitle(order: OrderItem): string {
    return `${order.companyTitle || 'Без компании'}${order.filialTitle ? ' - ' + order.filialTitle : ''}`;
  }

  sectionLabel(section: WorkerBoardSection): string {
    return workerSectionLabel(section);
  }

  sectionIcon(section: WorkerBoardSection): string {
    return this.sectionMetric(section)?.icon || workerSectionIcon(section);
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
    return mobilePageLabel(this.activePage(), this.pageNumber());
  }

  sortTitle(): string {
    return mobileSortTitle(this.sortDirection());
  }

  isOrderSection(section = this.activeSection()): boolean {
    return section === 'all' || section === 'new' || section === 'correct';
  }

  isReviewSection(section = this.activeSection()): boolean {
    return isWorkerReviewSection(section);
  }

  currentPageIndex(): number {
    return mobilePageIndex(this.activePage(), this.pageNumber());
  }

  currentPageTotal(): number {
    return mobilePageTotal(this.activePage());
  }

  isFirstPage(): boolean {
    return mobilePageIsFirst(this.activePage(), this.pageNumber());
  }

  isLastPage(): boolean {
    return mobilePageIsLast(this.activePage(), this.pageNumber());
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
    return workerReviewToneClass(review, this.activeSection());
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
    return workerReviewTitle(review, this.activeSection());
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
      return 'Сообщить менеджеру';
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
    if (!review.companyId || !review.orderId) {
      return '/tabs/worker';
    }
    const path = `/tabs/orders/${review.companyId}/${review.orderId}`;
    return this.activeSection() === 'all' ? `${path}?from=worker-all` : path;
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
      this.refreshPublishCredentialWaitTimer();
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
    this.sortDirection.set(workerDefaultSortDirection(DEFAULT_WORKER_SECTION));
    this.listExpanded.set(false);
    this.sectionSheetOpen.set(false);
    if (load) {
      void this.load();
    }
  }

  private mobileNavKey(params: ParamMap = this.route.snapshot.queryParamMap): string {
    return `${params.get('mobileNav') ?? ''}:${params.get('navTs') ?? ''}`;
  }

  private async runMutation<T>(
    key: string,
    request: () => { subscribe: unknown } | import('rxjs').Observable<T>,
    fallback: string,
    afterSuccess?: () => void
  ): Promise<void> {
    this.mutationKey.set(key);
    try {
      await firstValueFrom(request() as import('rxjs').Observable<T>);
      afterSuccess?.();
      await this.load();
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, fallback));
    } finally {
      this.mutationKey.set(null);
    }
  }

  private async copyText(text: string, key: string): Promise<boolean> {
    const value = text.trim();
    if (!value) {
      return false;
    }
    try {
      await navigator.clipboard.writeText(value);
      this.copiedKey.set(key);
      setTimeout(() => {
        if (this.copiedKey() === key) {
          this.copiedKey.set(null);
        }
      }, 1200);
      return true;
    } catch {
      this.error.set('Браузер не дал доступ к буферу обмена.');
      return false;
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

  private refreshPublishCredentialWaitTimer(): void {
    this.publishCredentialWaitNow.set(Date.now());
    if (!this.hasActivePublishCredentialWait()) {
      this.clearPublishCredentialWaitTimer();
      return;
    }

    if (this.publishCredentialWaitTimer) {
      return;
    }

    this.publishCredentialWaitTimer = setInterval(() => {
      this.publishCredentialWaitNow.set(Date.now());
      if (!this.hasActivePublishCredentialWait()) {
        this.clearPublishCredentialWaitTimer();
      }
    }, 1000);
  }

  private hasActivePublishCredentialWait(): boolean {
    if (!this.shouldUsePublishCredentialWait()) {
      return false;
    }

    return (this.board()?.reviews?.content ?? []).some((review) => this.publishCredentialWaitLeftSeconds(review) > 0);
  }

  private clearPublishCredentialWaitTimer(): void {
    if (!this.publishCredentialWaitTimer) {
      return;
    }

    clearInterval(this.publishCredentialWaitTimer);
    this.publishCredentialWaitTimer = null;
  }

  private restoreStoredPublishCredentialPreparation(): void {
    const stored = this.readStoredPublishCredentialPreparation();
    if (!stored) {
      return;
    }

    this.publishCredentialPreparation.set({
      reviewId: stored.reviewId,
      botId: stored.botId ?? null,
      loginAt: stored.loginAt,
      passwordAt: stored.passwordAt,
      invalidated: {}
    });
    this.refreshPublishCredentialWaitTimer();
  }

  private storePublishCredentialPreparation(): void {
    const preparation = this.publishCredentialPreparation();
    if (preparation.reviewId === null) {
      this.removeSessionStorageItem(this.publishCredentialPreparationStorageKey);
      return;
    }

    this.setSessionStorageItem(this.publishCredentialPreparationStorageKey, JSON.stringify({
      reviewId: preparation.reviewId,
      botId: preparation.botId ?? null,
      loginAt: preparation.loginAt,
      passwordAt: preparation.passwordAt,
      updatedAt: Date.now()
    } satisfies StoredPublishCredentialPreparation));
  }

  private clearStoredPublishCredentialPreparation(reviewId?: number): void {
    const current = this.publishCredentialPreparation();
    if (reviewId === undefined || current.reviewId === reviewId) {
      this.publishCredentialPreparation.set({ reviewId: null, invalidated: {} });
      this.clearPublishCredentialWaitTimer();
    }

    const stored = this.readStoredPublishCredentialPreparation(false);
    if (reviewId === undefined || stored?.reviewId === reviewId) {
      this.removeSessionStorageItem(this.publishCredentialPreparationStorageKey);
    }
  }

  private readStoredPublishCredentialPreparation(clearExpired = true): StoredPublishCredentialPreparation | null {
    const raw = this.getSessionStorageItem(this.publishCredentialPreparationStorageKey);
    if (!raw) {
      return null;
    }

    try {
      const value = JSON.parse(raw) as Partial<StoredPublishCredentialPreparation>;
      const reviewId = Number(value.reviewId);
      const updatedAt = Number(value.updatedAt);
      const loginAt = value.loginAt === undefined ? undefined : Number(value.loginAt);
      const passwordAt = value.passwordAt === undefined ? undefined : Number(value.passwordAt);
      if (
        !Number.isFinite(reviewId) ||
        reviewId <= 0 ||
        !Number.isFinite(updatedAt) ||
        (loginAt !== undefined && !Number.isFinite(loginAt)) ||
        (passwordAt !== undefined && !Number.isFinite(passwordAt))
      ) {
        this.removeSessionStorageItem(this.publishCredentialPreparationStorageKey);
        return null;
      }

      if (clearExpired && Date.now() - updatedAt > this.publishCredentialPreparationMaxAgeMs) {
        this.removeSessionStorageItem(this.publishCredentialPreparationStorageKey);
        return null;
      }

      const botId = value.botId === null || value.botId === undefined ? null : Number(value.botId);
      return {
        reviewId,
        botId: Number.isFinite(botId) ? botId : null,
        loginAt,
        passwordAt,
        updatedAt
      };
    } catch {
      this.removeSessionStorageItem(this.publishCredentialPreparationStorageKey);
      return null;
    }
  }

  private getSessionStorageItem(key: string): string | null {
    try {
      return window.sessionStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private setSessionStorageItem(key: string, value: string): void {
    try {
      window.sessionStorage.setItem(key, value);
    } catch {
      // Storage can be blocked; the current page still keeps the state in memory.
    }
  }

  private removeSessionStorageItem(key: string): void {
    try {
      window.sessionStorage.removeItem(key);
    } catch {
      // This only affects UI state restoration.
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
