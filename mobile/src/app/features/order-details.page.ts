import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  IonContent,
  IonModal,
  IonRefresher,
  IonRefresherContent,
  RefresherCustomEvent
} from '@ionic/angular/standalone';
import { Observable, Subscription, firstValueFrom } from 'rxjs';
import {
  ApiService,
  BadReviewTaskItem,
  CompanyDeepReportState,
  ManagerPaymentLinkResponse,
  OrderDetailsPayload,
  OrderReviewItem,
  ReviewRecoveryBatchItem,
  ReviewRecoveryTaskItem,
  TbankPaymentStatus,
  ReviewUpdateRequest
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileBottomPagerComponent } from '../shared/mobile-bottom-pager.component';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileMediaService } from '../shared/mobile-media.service';
import {
  MobileRecoveryClientNotifiedDetail,
  MobileRemindersComponent,
  dispatchMobileRecoveryClientNotified
} from '../shared/mobile-reminders.component';
import { MobileReviewCardShellComponent } from '../shared/mobile-review-card-shell.component';

type ReviewCopyKind = 'filialUrl' | 'botLogin' | 'botPassword' | 'text' | 'answer';
type BadReviewTaskCopyKind = 'botLogin' | 'botPassword';
type RecoveryTaskCopyKind = 'botLogin' | 'botPassword';
type ReviewEditableField = 'text' | 'answer';
type ReviewTextEditState = {
  review: OrderReviewItem;
  field: ReviewEditableField;
};
type ReviewSideNoteField = 'order' | 'company';
type BadReviewTaskDraft = {
  taskText: string;
  scheduledDate: string | null;
};
type RecoveryTaskDraft = {
  recoveryText: string;
  recoveryAnswer: string;
  scheduledDate: string | null;
};
type CompanyReportFact = {
  label?: string | null;
  value?: string | null;
  evidence?: string | null;
  confidence?: string | null;
};
type CompanyReportSource = {
  title?: string | null;
  url?: string | null;
  note?: string | null;
  type?: string | null;
  confidence?: string | null;
};
type CompanyReportSection = {
  id: string;
  title: string;
  body: string;
  html: string;
};
type CompanyReport = {
  city?: string | null;
  provider?: string | null;
  model?: string | null;
  reportMarkdown?: string | null;
  sections?: Array<{ title?: string | null; body?: string | null }> | null;
  sources?: CompanyReportSource[] | null;
  warnings?: string[] | null;
  reviewIdeas?: string[] | null;
  factSnapshot?: {
    confirmedFacts?: CompanyReportFact[] | null;
    uncertainFacts?: CompanyReportFact[] | null;
  } | null;
  createdAt?: string | null;
};

const HIDDEN_PUBLISH_ORDER_STATUSES = new Set(['Новый', 'На проверке', 'В проверку', 'В прверку', 'Коррекция']);
const REVIEW_AI_ORDER_STATUSES = new Set(['новый', 'в проверку', 'в проврку', 'в прверку', 'на проверке', 'на провере', 'коррекция']);
const PLACEHOLDER_REVIEW_TEXT = 'текст отзыва';

@Component({
  selector: 'app-order-details-mobile',
  imports: [FormsModule, IonContent, IonModal, IonRefresher, IonRefresherContent, MobileBottomPagerComponent, MobileHeaderComponent, MobileRemindersComponent, MobileReviewCardShellComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Детали заказа" />

      <ion-content fullscreen [scrollY]="false">
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <app-mobile-reminders #reminders />

        <main class="order-details-mobile-page">
          @if (loading() && !details()) {
            <div class="mobile-empty-state">
              <span class="material-icons-sharp">hourglass_top</span>
              <p>Загружаю детали заказа...</p>
            </div>
          }

          @if (error()) {
            <button class="mobile-error-card" type="button" (click)="loadDetails()">
              <span class="material-icons-sharp">error</span>
              <strong>{{ error() }}</strong>
              <small>Нажмите, чтобы повторить</small>
            </button>
          }

          @if (details(); as details) {
            <section
              #reviewStrip
              class="review-mobile-strip"
              [class.review-mobile-strip--expanded]="reviewsExpanded()"
              aria-label="Отзывы заказа"
              (scroll)="onReviewScroll($event)"
            >
              @for (review of details.reviews; track review.id; let index = $index) {
                <app-mobile-review-card-shell
                  layout="details"
                  [title]="review.companyTitle || details.companyTitle || 'Компания'"
                  [titleHref]="review.filialUrl || ''"
                  [idLabel]="'#' + review.id"
                  [published]="review.publish"
                  [active]="activeReviewIndex() === index"
                  [cardIndex]="index"
                  [reviewId]="review.id"
                  [photoMode]="(review.productPhoto || needsReviewPhoto(review)) ? (hasReviewPhoto(review) ? 'link' : 'button') : 'none'"
                  [photoUrl]="reviewPhotoUrl(review)"
                  [noteVisible]="hasReviewNote(review)"
                  [footerLeft]="review.publishedDate || 'Не назначено'"
                  [footerRight]="review.workerFio || 'специалист'"
                  [footerRightDisabled]="!details.canEditReviews"
                  (photoAction)="openReviewEdit(review)"
                  (noteClick)="toggleReviewNote(review)"
                  (footerRightClick)="openReviewEdit(review)"
                >

                  <section
                    class="review-text-editor"
                    [class.open]="isReviewTextOpen(review)"
                    [class.editing]="isReviewFieldEditing(review, 'text')"
                  >
                    <button
                      class="review-display-field"
                      type="button"
                      [class.empty]="!reviewFieldValue(review, 'text').trim()"
                      (click)="openReviewTextEdit(review, 'text')"
                    >
                      {{ reviewFieldValue(review, 'text').trim() || 'Текст отзыва' }}
                    </button>
                    @if (shouldShowTextToggle(review)) {
                      <button class="text-toggle" type="button" (click)="toggleReviewText(review)">
                        {{ isReviewTextOpen(review) ? 'свернуть' : 'развернуть' }}
                      </button>
                    }
                  </section>

                  <section class="review-answer-editor" [class.editing]="isReviewFieldEditing(review, 'answer')">
                    <button
                      class="review-display-field review-display-field--answer"
                      type="button"
                      [class.empty]="!reviewFieldValue(review, 'answer').trim()"
                      (click)="openReviewTextEdit(review, 'answer')"
                    >
                      {{ reviewFieldValue(review, 'answer').trim() || 'Ответ на отзыв или замечание' }}
                    </button>
                  </section>

                  <div class="bot-line" [class.empty]="hasUnavailableBot(review)" [class.compact]="hasCompactBotPrompt(review)">
                    {{ botLabel(review) }}
                  </div>

                  @if (noteOpenId() === review.id) {
                    <section class="review-note-panel">
                      <label>
                        <span>Заметка отзыва</span>
                        <textarea
                          [name]="'note-' + review.id"
                          [ngModel]="reviewNoteValue(review)"
                          (ngModelChange)="setReviewNoteDraft(review, $event)"
                          placeholder="Заметка"
                        ></textarea>
                      </label>
                      <label>
                        <span>Заметка заказа</span>
                        <textarea
                          [name]="'order-note-' + review.id"
                          [ngModel]="reviewSideNoteValue(review, 'order')"
                          (ngModelChange)="setReviewSideNoteDraft(review, 'order', $event)"
                          placeholder="Заметка заказа"
                        ></textarea>
                      </label>
                      <label>
                        <span>Заметка компании</span>
                        <textarea
                          [name]="'company-note-' + review.id"
                          [ngModel]="reviewSideNoteValue(review, 'company')"
                          (ngModelChange)="setReviewSideNoteDraft(review, 'company', $event)"
                          placeholder="Заметка компании"
                        ></textarea>
                      </label>
                      <div class="field-actions mobile-keyboard-actions">
                        <button type="button" (click)="toggleReviewNote(review)" [disabled]="isMutating(reviewNotesMutationKey(review))">Закрыть</button>
                        <button type="button" class="save" (click)="saveAllReviewNotes(review)" [disabled]="!isAnyReviewNoteChanged(review) || isMutating(reviewNotesMutationKey(review))">
                          {{ isMutating(reviewNotesMutationKey(review)) ? '...' : 'Сохранить' }}
                        </button>
                      </div>
                    </section>
                  }

                  <div class="review-actions">
                    <button type="button" (click)="copyReviewField(review, 'filialUrl')">{{ copiedKey() === review.id + '-filialUrl' ? 'готово' : 'ссылка' }}</button>
                    <button type="button" (click)="copyReviewField(review, 'botLogin')">{{ copiedKey() === review.id + '-botLogin' ? 'готово' : 'логин' }}</button>
                    <button type="button" (click)="copyReviewField(review, 'botPassword')">{{ copiedKey() === review.id + '-botPassword' ? 'готово' : 'пароль' }}</button>
                    @if (showReviewAiAction(details)) {
                      <button
                        class="ai-action"
                        type="button"
                        [title]="reviewHelpActionTitle()"
                        (click)="createReviewHelpDraft(review)"
                        [disabled]="isMutating('review-help-' + review.id) || companyReportBusy()"
                      >
                        {{ reviewHelpActionLabel(review) }}
                      </button>
                    } @else {
                      <button
                        class="recovery-action"
                        type="button"
                        [class.planned]="!!recoveryTaskForReview(review)"
                        (click)="createRecoveryTask(review)"
                        [disabled]="isMutating('recovery-create-' + review.id) || !!recoveryTaskForReview(review)"
                      >
                        {{ recoveryActionLabel(review) }}
                      </button>
                    }
                    <button type="button" (click)="copyReviewField(review, 'text')">{{ copiedKey() === review.id + '-text' ? 'готово' : 'текст' }}</button>
                    <button type="button" (click)="copyReviewField(review, 'answer')">{{ copiedKey() === review.id + '-answer' ? 'готово' : 'ответ' }}</button>
                    <button type="button" (click)="changeBot(review)" [disabled]="isMutating('bot-' + review.id)">{{ isMutating('bot-' + review.id) ? '...' : 'смена' }}</button>
                    <button type="button" (click)="deactivateBot(review)" [disabled]="!review.botId || isMutating('block-' + review.id)">{{ isMutating('block-' + review.id) ? '...' : 'блок' }}</button>
                  </div>

                  @if (canShowPublishButton(details, review)) {
                    <button
                      class="publish-button"
                      type="button"
                      [class.published]="review.publish"
                      [disabled]="review.publish || isMutating('publish-' + review.id)"
                      (click)="publishReview(review)"
                    >
                      {{ review.publish ? 'ОПУБЛИКОВАНО' : isMutating('publish-' + review.id) ? 'ПУБЛИКУЮ...' : 'ОПУБЛИКОВАТЬ' }}
                    </button>
                  }

                </app-mobile-review-card-shell>
              }

              @for (task of badReviewTasks(); track task.id; let taskIndex = $index) {
                <article
                  class="mobile-review-card mobile-task-card mobile-task-card--bad"
                  [class.active]="activeReviewIndex() === badTaskCardIndex(taskIndex)"
                  [class.done]="task.statusCode === 'DONE'"
                  [class.canceled]="task.statusCode === 'CANCELED'"
                  [attr.data-card-index]="badTaskCardIndex(taskIndex)"
                >
                  <header>
                    <strong>Плохая оценка</strong>
                    <span>
                      <small>#{{ task.id }}</small>
                      <em>{{ task.originalRating || 5 }} -> {{ task.targetRating || 2 }}</em>
                    </span>
                  </header>

                  <div class="task-meta-grid">
                    <span>{{ badReviewTaskState(details, task) }}</span>
                    <span>отзыв #{{ task.sourceReviewId || '-' }}</span>
                    <span>доплата +{{ money(task.price || 0) }}</span>
                    <span>{{ badReviewTaskDateLabel(task) }}</span>
                  </div>

                  <label class="task-date-field">
                    <span>План смены</span>
                    <input
                      type="date"
                      [disabled]="!canEditBadReviewTask(task)"
                      [ngModel]="badReviewTaskDateValue(task)"
                      (ngModelChange)="setBadReviewTaskDate(task, $event)"
                    >
                  </label>

                  <textarea
                    class="task-text-field"
                    [readonly]="!canEditBadReviewTask(task)"
                    [ngModel]="badReviewTaskTextValue(task)"
                    (ngModelChange)="setBadReviewTaskText(task, $event)"
                    placeholder="Текст задачи"
                  ></textarea>

                  @if (task.comment) {
                    <div class="task-note-field" title="Комментарий плохой задачи">
                      <small>комментарий</small>
                      <span>{{ task.comment }}</span>
                    </div>
                  }

                  <div class="review-actions task-actions">
                    <button type="button" (click)="copyBadReviewTaskField(task, 'botLogin')" [disabled]="!task.botLogin">
                      {{ copiedKey() === badReviewTaskCopyKey(task, 'botLogin') ? 'готово' : 'логин' }}
                    </button>
                    <button type="button" (click)="copyBadReviewTaskField(task, 'botPassword')" [disabled]="!task.botPassword">
                      {{ copiedKey() === badReviewTaskCopyKey(task, 'botPassword') ? 'готово' : 'пароль' }}
                    </button>
                    <button type="button" (click)="changeBadReviewTaskBot(task)" [disabled]="!canEditBadReviewTask(task) || isMutating('bad-task-change-bot-' + task.id)">
                      {{ isMutating('bad-task-change-bot-' + task.id) ? '...' : 'смена' }}
                    </button>
                    <button type="button" class="save" (click)="saveBadReviewTask(task)" [disabled]="!canEditBadReviewTask(task) || !isBadReviewTaskChanged(task) || isMutating('bad-task-save-' + task.id)">
                      {{ isBadReviewTaskSaved(task) ? 'готово' : 'сохранить' }}
                    </button>
                  </div>

                  <footer>
                    <span [title]="(task.workerFio || 'не назначен') + ' · ' + (task.botFio || '-')">
                      {{ task.workerFio || 'не назначен' }} · {{ task.botFio || '-' }}
                    </span>
                    @if (canCompleteBadReviewTask(task)) {
                      <button class="review-edit-link" type="button" (click)="completeBadReviewTask(task)" [disabled]="isMutating('bad-task-complete-' + task.id)">
                        {{ isMutating('bad-task-complete-' + task.id) ? '...' : 'сменил' }}
                      </button>
                    } @else if (canCancelBadReviewTask(details, task)) {
                      <button
                        class="review-edit-link danger-link"
                        type="button"
                        title="Убрать из счета"
                        (click)="cancelBadReviewTask(task)"
                        [disabled]="isMutating('bad-task-cancel-' + task.id)"
                      >
                        {{ isMutating('bad-task-cancel-' + task.id) ? '...' : 'убрать из счета' }}
                      </button>
                    } @else {
                      <span>{{ badReviewTaskState(details, task) }}</span>
                    }
                  </footer>
                </article>
              }

              @for (batch of recoveryBatchesToNotify(); track batch.id; let batchIndex = $index) {
                <article
                  class="mobile-review-card mobile-task-card mobile-task-card--recovery"
                  [class.active]="activeReviewIndex() === recoveryBatchCardIndex(batchIndex)"
                  [attr.data-card-index]="recoveryBatchCardIndex(batchIndex)"
                >
                  <header>
                    <strong>Восстановление готово</strong>
                    <span><small>#{{ batch.id }}</small></span>
                  </header>
                  <div class="task-notice">
                    <span class="material-icons-sharp">task_alt</span>
                    <p>Все восстановления выполнены. Сообщите клиенту и отметьте уведомление.</p>
                  </div>
                  <button class="publish-button" type="button" (click)="markRecoveryClientNotified(batch)" [disabled]="isMutating('recovery-notified-' + batch.id)">
                    {{ isMutating('recovery-notified-' + batch.id) ? 'Скрываю...' : 'Клиент уведомлен' }}
                  </button>
                  <footer>
                    <span>{{ batch.completedAt || 'готово' }}</span>
                    <span>{{ batch.status || 'COMPLETED' }}</span>
                  </footer>
                </article>
              }

              @for (task of recoveryTasks(); track task.id; let taskIndex = $index) {
                <article
                  class="mobile-review-card mobile-task-card mobile-task-card--recovery"
                  [class.active]="activeReviewIndex() === recoveryTaskCardIndex(taskIndex)"
                  [class.done]="task.statusCode === 'DONE'"
                  [attr.data-card-index]="recoveryTaskCardIndex(taskIndex)"
                >
                  <header>
                    <strong>Восстановление</strong>
                    <span><small>#{{ task.id }}</small></span>
                  </header>

                  <div class="task-meta-grid">
                    <span>{{ task.status || 'В работе' }}</span>
                    <span>отзыв #{{ task.sourceReviewId || '-' }}</span>
                    <span>{{ task.workerFio || 'не назначен' }}</span>
                    <span>{{ recoveryTaskDateLabel(task) }}</span>
                  </div>

                  <label class="task-date-field">
                    <span>Дата</span>
                    <input
                      type="date"
                      [disabled]="!canEditRecoveryTask(task)"
                      [ngModel]="recoveryTaskDateValue(task)"
                      (ngModelChange)="setRecoveryTaskDate(task, $event)"
                    >
                  </label>

                  <textarea
                    class="task-text-field"
                    [readonly]="!canEditRecoveryTask(task)"
                    [ngModel]="recoveryTaskTextValue(task)"
                    (ngModelChange)="setRecoveryTaskText(task, $event)"
                    placeholder="Текст восстановления"
                  ></textarea>

                  <textarea
                    class="task-answer-field"
                    [readonly]="!canEditRecoveryTask(task)"
                    [ngModel]="recoveryTaskAnswerValue(task)"
                    (ngModelChange)="setRecoveryTaskAnswer(task, $event)"
                    placeholder="Ответ или замечание"
                  ></textarea>

                  <div class="review-actions task-actions">
                    <button type="button" (click)="copyRecoveryTaskField(task, 'botLogin')" [disabled]="!task.botLogin">
                      {{ copiedKey() === recoveryTaskCopyKey(task, 'botLogin') ? 'готово' : 'логин' }}
                    </button>
                    <button type="button" (click)="copyRecoveryTaskField(task, 'botPassword')" [disabled]="!task.botPassword">
                      {{ copiedKey() === recoveryTaskCopyKey(task, 'botPassword') ? 'готово' : 'пароль' }}
                    </button>
                    <a [href]="recoveryTaskBotBrowserUrl(task)" target="_blank" rel="noopener">вк</a>
                    <button type="button" (click)="changeRecoveryTaskBot(task)" [disabled]="!canEditRecoveryTask(task) || isMutating('recovery-change-bot-' + task.id)">
                      {{ isMutating('recovery-change-bot-' + task.id) ? '...' : 'смена' }}
                    </button>
                    <button type="button" (click)="deactivateRecoveryTaskBot(task)" [disabled]="!canEditRecoveryTask(task) || !task.botId || isMutating('recovery-block-bot-' + task.id)">
                      {{ isMutating('recovery-block-bot-' + task.id) ? '...' : 'блок' }}
                    </button>
                    <button type="button" class="save" (click)="saveRecoveryTask(task)" [disabled]="!canEditRecoveryTask(task) || !isRecoveryTaskChanged(task) || isMutating('recovery-save-' + task.id)">
                      {{ isRecoveryTaskSaved(task) ? 'готово' : 'сохранить' }}
                    </button>
                    <button type="button" (click)="completeRecoveryTask(task)" [disabled]="!canEditRecoveryTask(task) || isMutating('recovery-complete-' + task.id)">
                      {{ isMutating('recovery-complete-' + task.id) ? '...' : 'восстановил' }}
                    </button>
                    @if (canDeleteRecoveryTask(task)) {
                      <button type="button" class="danger" (click)="deleteRecoveryTask(task)" [disabled]="isMutating('recovery-delete-' + task.id)">
                        {{ isMutating('recovery-delete-' + task.id) ? '...' : 'удалить' }}
                      </button>
                    }
                  </div>

                  <footer>
                    <span>{{ task.botFio || 'аккаунт' }}</span>
                    <span>{{ task.completedDate || task.scheduledDate || '-' }}</span>
                  </footer>
                </article>
              }

              @if (totalDetailCards() === 0) {
                <div class="mobile-empty-state">
                  <span class="material-icons-sharp">rate_review</span>
                  <p>У заказа пока нет отзывов</p>
                </div>
              }
            </section>

            <footer class="order-details-actions">
              <button type="button" (click)="openCompanyReport()" [disabled]="companyReportLoading()">
                <span class="material-icons-sharp">business</span>
                О компании
              </button>
              <button type="button" (click)="createHelpDrafts()" [disabled]="busy()">
                <span class="material-icons-sharp">auto_awesome</span>
                Помощь
              </button>
              @if (canRefreshCompanyReport()) {
                <button type="button" (click)="refreshCompanyReport()" [disabled]="companyReportLoading()">
                  <span class="material-icons-sharp">refresh</span>
                  Обновить отчет
                </button>
              }
              <button type="button" class="primary" (click)="sendToCheck()" [disabled]="busy()">
                {{ isMutating('send-check') ? '...' : 'На проверку' }}
              </button>
              <button type="button" (click)="openReviewCheck()" [disabled]="!details.orderDetailsId">
                Проверка отзывов
              </button>
              <button type="button" (click)="addReview()" [disabled]="isMutating('add-review')">
                {{ isMutating('add-review') ? '...' : '+1 отзыв' }}
              </button>
            </footer>

            <app-mobile-bottom-pager
              class="mobile-page-bottom-pager"
              [pageIndex]="activeReviewIndex()"
              [totalPages]="totalDetailCards()"
              [disabled]="busy() || loading()"
              (previous)="previousReview()"
              (next)="nextReview()"
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
                [class.active]="reviewsExpanded()"
                (click)="toggleReviewsExpanded()"
                [attr.aria-label]="reviewsExpanded() ? 'Свернуть список отзывов' : 'Развернуть список отзывов'"
              >
                <span class="material-icons-sharp">{{ reviewsExpanded() ? 'close_fullscreen' : 'open_in_full' }}</span>
              </button>
            </app-mobile-bottom-pager>
          }
        </main>

        <ion-modal class="sheet-modal company-report-modal" [isOpen]="companyReportVisible()" (didDismiss)="closeCompanyReport()">
          <ng-template>
            <section class="sheet-body company-report-sheet">
              <header class="sheet-head">
                <div>
                  <p class="sheet-note">{{ companyReportState()?.companyName || details()?.companyTitle || 'Компания' }}</p>
                  <h2>О компании</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeCompanyReport()" aria-label="Закрыть отчет">
                  <span class="material-icons-sharp">close</span>
                </button>
              </header>

              <div class="company-report-content">
                @if (companyReportLoading()) {
                  <div class="company-report-state-card">
                    <span class="material-icons-sharp">hourglass_top</span>
                    <p>{{ companyReportState()?.activeJob ? 'Отчет готовится в фоне.' : 'Проверяю отчет о компании...' }}</p>
                  </div>
                } @else if (companyReportError()) {
                  <p class="sheet-error">{{ companyReportError() }}</p>
                } @else if (companyReport(); as report) {
                  <div class="company-report-meta">
                    @if (report.city) {
                      <span>{{ report.city }}</span>
                    }
                    @if (companyReportCompletedAt()) {
                      <span>{{ companyReportCompletedAt() }}</span>
                    }
                    @if (report.provider || report.model) {
                      <span>{{ report.provider || 'AI' }} {{ report.model || '' }}</span>
                    }
                  </div>

                  @if (companyReportSections(); as sections) {
                    @if (sections.length) {
                      @for (section of sections; track section.id) {
                        <article class="company-report-reader-section">
                          <h3>{{ section.title }}</h3>
                          <div class="company-report-rich" [innerHTML]="section.html"></div>
                        </article>
                      }
                    }
                  }

                  @if (companyReportConfirmedFacts().length) {
                    <article class="company-report-section">
                      <h3>Факты</h3>
                      @for (fact of companyReportConfirmedFacts(); track $index) {
                        <p><strong>{{ fact.label }}</strong>{{ fact.label ? ': ' : '' }}{{ fact.value || fact.evidence }}</p>
                      }
                    </article>
                  }

                  @if (companyReportReviewIdeas().length) {
                    <article class="company-report-section">
                      <h3>Идеи для отзывов</h3>
                      <ul>
                        @for (idea of companyReportReviewIdeas(); track idea) {
                          <li>{{ idea }}</li>
                        }
                      </ul>
                    </article>
                  }

                  @if (companyReportWarnings().length) {
                    <article class="company-report-section warning">
                      <h3>Предупреждения</h3>
                      <ul>
                        @for (warning of companyReportWarnings(); track warning) {
                          <li>{{ warning }}</li>
                        }
                      </ul>
                    </article>
                  }

                  @if (companyReportSources().length) {
                    <article class="company-report-section sources">
                      <h3>Источники</h3>
                      @for (source of companyReportSources(); track source.url || source.title) {
                        @if (source.url) {
                          <a [href]="source.url" target="_blank" rel="noopener">{{ source.title || source.url }}</a>
                        } @else {
                          <p>{{ source.title || source.note }}</p>
                        }
                      }
                    </article>
                  }
                } @else {
                  <div class="company-report-state-card">
                    <span class="material-icons-sharp">auto_awesome</span>
                    <p>{{ companyReportStatus() }}</p>
                  </div>
                }
              </div>

              <footer class="sheet-actions company-report-actions">
                @if (companyReportState()?.activeJob) {
                  <button type="button" class="secondary" (click)="openCompanyReport()" [disabled]="companyReportLoading()">
                    Проверить готовность
                  </button>
                } @else if (!companyReport()) {
                  <button type="button" (click)="startCompanyReport()" [disabled]="companyReportLoading() || !companyReportState()?.canStart">
                    Создать отчет
                  </button>
                }
                @if (canRefreshCompanyReport()) {
                  <button type="button" (click)="refreshCompanyReport()" [disabled]="companyReportLoading()">
                    Обновить
                  </button>
                }
              </footer>
            </section>
          </ng-template>
        </ion-modal>

        <ion-modal
          class="sheet-modal review-edit-sheet review-text-edit-sheet"
          [isOpen]="reviewTextEdit() !== null"
          (didDismiss)="closeReviewTextEdit()"
        >
          <ng-template>
            <form class="sheet-body sheet-form review-text-edit-form" (ngSubmit)="saveReviewTextEdit()">
              <header class="sheet-head review-text-edit-head">
                <div>
                  <p class="sheet-note">{{ reviewTextEditNote() }}</p>
                  <h2>{{ reviewTextEditTitle() }}</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeReviewTextEdit()" [disabled]="reviewTextEditSaving()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </header>

              <label class="sheet-field review-text-edit-field">
                <span>{{ reviewTextEditLabel() }}</span>
                <textarea
                  name="reviewTextFullEditor"
                  [ngModel]="reviewTextEditValue()"
                  (ngModelChange)="setReviewTextEditValue($event)"
                  [placeholder]="reviewTextEditPlaceholder()"
                  [disabled]="reviewTextEditSaving()"
                ></textarea>
              </label>

              @if (reviewTextEditError()) {
                <p class="sheet-error">{{ reviewTextEditError() }}</p>
              }

              <footer class="sheet-actions review-text-edit-actions mobile-keyboard-actions">
                <button class="secondary" type="button" (click)="closeReviewTextEdit()" [disabled]="reviewTextEditSaving()">Отмена</button>
                <button type="submit" [disabled]="!canSaveReviewTextEdit()">
                  {{ reviewTextEditSaving() ? 'Сохраняю' : 'Сохранить' }}
                </button>
              </footer>
            </form>
          </ng-template>
        </ion-modal>

        <ion-modal
          class="sheet-modal review-edit-sheet"
          [class.review-edit-sheet--text-first]="reviewEditInitialField() === 'text'"
          [class.review-edit-sheet--answer-first]="reviewEditInitialField() === 'answer'"
          [isOpen]="reviewEdit() !== null"
          (didDismiss)="closeReviewEdit()"
        >
          <ng-template>
            <form class="sheet-body sheet-form" (ngSubmit)="saveReviewEdit()">
              <div class="sheet-head">
                <div>
                  <p class="sheet-note">Отзыв #{{ reviewEdit()?.id || '' }}</p>
                  <h2>Редактор отзыва</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeReviewEdit()" [disabled]="reviewEditSaving() || reviewEditDeleting() || reviewEditUploading()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>

              <section class="sheet-form-content">
                @if (reviewEditError()) {
                  <p class="sheet-error">{{ reviewEditError() }}</p>
                }

                @if (reviewEdit(); as review) {
                  @if (reviewEditDraft(); as draft) {
                    <label class="sheet-field">
                      <span>Аккаунт</span>
                      <input
                        name="reviewEditBot"
                        type="text"
                        [ngModel]="draft.botName"
                        (ngModelChange)="setReviewEditField('botName', $event)"
                        [disabled]="reviewEditSaving() || reviewEditDeleting() || reviewEditUploading()"
                      >
                    </label>

                    <label class="sheet-field">
                      <span>Пароль</span>
                      <input
                        name="reviewEditPassword"
                        type="text"
                        [ngModel]="draft.botPassword"
                        (ngModelChange)="setReviewEditField('botPassword', $event)"
                        [disabled]="reviewEditSaving() || reviewEditDeleting() || reviewEditUploading()"
                      >
                    </label>

                    <label class="sheet-field">
                      <span>Дата публикации</span>
                      <input
                        name="reviewEditPublishedDate"
                        type="date"
                        [ngModel]="draft.publishedDate"
                        (ngModelChange)="setReviewEditField('publishedDate', emptyToNull($event))"
                        [readonly]="!details()?.canEditReviewDates"
                        [disabled]="reviewEditSaving() || reviewEditDeleting()"
                      >
                    </label>

                    <div class="sheet-utility-actions sheet-utility-actions--single">
                      <button type="button" (click)="assignReviewNewAccount(review)" [disabled]="reviewEditSaving() || reviewEditDeleting() || reviewEditUploading() || isMutating('new-account-' + review.id)">
                        <span class="material-icons-sharp">person_add</span>
                        {{ isMutating('new-account-' + review.id) ? 'Ищу' : 'Добавить новый' }}
                      </button>
                    </div>

                    <label class="sheet-field review-edit-main-text">
                      <span>Текст отзыва</span>
                      <textarea
                        name="reviewEditText"
                        rows="7"
                        [ngModel]="draft.text"
                        (ngModelChange)="setReviewEditField('text', $event)"
                        [disabled]="reviewEditSaving() || reviewEditDeleting()"
                      ></textarea>
                    </label>

                    <div class="sheet-utility-actions sheet-utility-actions--single">
                      <button
                        type="button"
                        [title]="reviewHelpActionTitle()"
                        (click)="createReviewHelpDraft(review)"
                        [disabled]="reviewEditSaving() || reviewEditDeleting() || reviewEditUploading() || isMutating('review-help-' + review.id) || companyReportBusy()"
                      >
                        <span class="material-icons-sharp">auto_awesome</span>
                        {{ isMutating('review-help-' + review.id) ? 'Пишу' : 'AI текст' }}
                      </button>
                    </div>

                    <label class="sheet-field review-edit-answer">
                      <span>Ответ или замечание</span>
                      <textarea
                        name="reviewEditAnswer"
                        rows="3"
                        [ngModel]="draft.answer"
                        (ngModelChange)="setReviewEditField('answer', $event)"
                        [disabled]="reviewEditSaving() || reviewEditDeleting()"
                      ></textarea>
                    </label>

                    <label class="sheet-field review-edit-comment">
                      <span>Заметка</span>
                      <textarea
                        name="reviewEditComment"
                        rows="3"
                        [ngModel]="draft.comment"
                        (ngModelChange)="setReviewEditField('comment', $event)"
                        [disabled]="reviewEditSaving() || reviewEditDeleting()"
                      ></textarea>
                    </label>

                    <label class="sheet-field">
                      <span>Продукт</span>
                      <select
                        name="reviewEditProduct"
                        [ngModel]="draft.productId"
                        (ngModelChange)="setReviewEditField('productId', $event)"
                        [disabled]="reviewEditSaving() || reviewEditDeleting()"
                      >
                        <option [ngValue]="null">Не выбран</option>
                        @for (product of details()?.products ?? []; track product.id) {
                          <option [ngValue]="product.id">{{ product.label }}</option>
                        }
                      </select>
                    </label>

                    <label class="sheet-field">
                      <span>Ссылка</span>
                      <input
                        name="reviewEditUrl"
                        type="text"
                        [ngModel]="draft.url"
                        (ngModelChange)="setReviewEditField('url', $event)"
                        [disabled]="reviewEditSaving() || reviewEditDeleting() || reviewEditUploading()"
                      >
                    </label>

                    @if (productNeedsPhoto(draft.productId) || draft.url.trim()) {
                      <div class="sheet-photo-field">
                        <span>Фото отзыва</span>
                        @if (draft.url.trim()) {
                          <a [href]="draft.url" target="_blank" rel="noopener">
                            <span class="material-icons-sharp">photo_camera</span>
                            Открыть фото
                          </a>
                        }
                        @if (productNeedsPhoto(draft.productId)) {
                          <label class="photo-upload-button" (click)="pickNativeReviewPhoto($event)">
                            <span class="material-icons-sharp">add_photo_alternate</span>
                            <span>{{ reviewEditUploading() ? 'Загружаю' : 'Загрузить фото' }}</span>
                            <input
                              type="file"
                              accept="image/*"
                              (change)="uploadReviewPhoto($event)"
                              [disabled]="reviewEditSaving() || reviewEditDeleting() || reviewEditUploading()"
                            >
                          </label>
                        }
                      </div>
                    }

                    <div class="review-edit-compact-grid">
                      <label class="sheet-field">
                        <span>Создан</span>
                        <input
                          name="reviewEditCreated"
                          type="text"
                          [ngModel]="draft.created"
                          (ngModelChange)="setReviewEditField('created', emptyToNull($event))"
                          [readonly]="!details()?.canEditReviewDates"
                          [disabled]="reviewEditSaving() || reviewEditDeleting()"
                        >
                      </label>

                      <label class="sheet-field">
                        <span>Изменен</span>
                        <input
                          name="reviewEditChanged"
                          type="text"
                          [ngModel]="draft.changed"
                          (ngModelChange)="setReviewEditField('changed', emptyToNull($event))"
                          [readonly]="!details()?.canEditReviewDates"
                          [disabled]="reviewEditSaving() || reviewEditDeleting()"
                        >
                      </label>

                      <label class="sheet-field sheet-field--inline">
                        <span>Статус опубликован</span>
                        <input
                          name="reviewEditPublish"
                          type="checkbox"
                          [ngModel]="draft.publish"
                          (ngModelChange)="setReviewEditField('publish', $event)"
                          [disabled]="reviewEditSaving() || reviewEditDeleting() || !details()?.canEditReviewPublish"
                        >
                      </label>

                      <label class="sheet-field sheet-field--inline">
                        <span>Выгул</span>
                        <input
                          name="reviewEditVigul"
                          type="checkbox"
                          [ngModel]="draft.vigul"
                          (ngModelChange)="setReviewEditField('vigul', $event)"
                          [disabled]="reviewEditSaving() || reviewEditDeleting() || !details()?.canEditReviewVigul"
                        >
                      </label>
                    </div>
                  }
                }
              </section>

              <div class="sheet-actions" [class.edit-actions]="!!details()?.canDeleteReviews">
                @if (details()?.canDeleteReviews) {
                  <button class="danger" type="button" (click)="deleteReviewEdit()" [disabled]="reviewEditSaving() || reviewEditDeleting() || reviewEditUploading()">
                    {{ reviewEditDeleting() ? 'Удаляю' : 'Удалить' }}
                  </button>
                }
                <button class="secondary" type="button" (click)="closeReviewEdit()" [disabled]="reviewEditSaving() || reviewEditDeleting() || reviewEditUploading()">Отмена</button>
                <button type="submit" [disabled]="reviewEditSaving() || reviewEditDeleting() || reviewEditUploading() || !canSaveReviewEdit()">
                  {{ reviewEditSaving() ? 'Сохраняю' : 'Сохранить' }}
                </button>
              </div>
            </form>
          </ng-template>
        </ion-modal>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content { --overflow: hidden; }

    .order-details-mobile-page {
      display: flex;
      flex-direction: column;
      gap: var(--otziv-page-gap, 0.58rem);
      height: 100%;
      max-width: 48rem;
      margin: 0 auto;
      overflow: hidden;
      padding: var(--otziv-page-padding-y, 0.68rem) var(--otziv-page-padding-x, 0.68rem) calc(var(--otziv-page-padding-bottom, 0.58rem) + env(safe-area-inset-bottom));
      font-family: var(--otziv-card-title-font);
    }

    .company-report-panel,
    .order-details-actions,
    .order-review-bottom-controls {
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.9rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 0.7rem 1.45rem rgba(132, 139, 200, 0.11);
    }

    .company-report-panel small {
      color: var(--otziv-info);
      font-family: var(--otziv-font-family);
      font-size: 0.68rem;
      font-weight: 900;
      line-height: 1.15;
    }

    .company-report-panel header button {
      display: grid;
      width: 2.2rem;
      height: 2.2rem;
      place-items: center;
      border: 0;
      border-radius: 0.75rem;
      color: var(--otziv-primary);
      background: rgba(108, 155, 207, 0.14);
      font: inherit;
    }

    .company-report-panel {
      display: grid;
      gap: 0.45rem;
      padding: 0.62rem;
      font-family: var(--otziv-font-family);
    }

    .company-report-panel header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
    }

    .company-report-panel h2 {
      margin: 0;
      color: var(--otziv-dark);
      font-size: 1rem;
    }

    .company-report-panel p {
      margin: 0;
      color: var(--otziv-dark);
      font-size: 0.78rem;
      font-weight: 800;
      line-height: 1.35;
    }

    .company-report-panel p.error {
      color: var(--otziv-danger);
    }

    .company-report-modal {
      --height: min(92vh, 48rem);
    }

    .company-report-sheet {
      grid-template-rows: auto minmax(0, 1fr) auto;
      height: 100%;
      overflow: hidden;
      font-family: var(--otziv-font-family);
    }

    .company-report-sheet .sheet-head h2 {
      margin: 0;
      color: var(--otziv-dark);
      font-size: 1.05rem;
      font-weight: 1000;
      line-height: 1.12;
    }

    .company-report-content {
      display: grid;
      gap: 0.78rem;
      min-height: 0;
      overflow: auto;
      padding: 0 0.1rem 0.18rem 0;
      scrollbar-width: thin;
    }

    .company-report-state-card,
    .company-report-section {
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.74rem;
      background: var(--otziv-white);
      box-shadow: 0 0.5rem 1.1rem rgba(42, 57, 82, 0.06);
    }

    .company-report-state-card {
      display: grid;
      place-items: center;
      gap: 0.45rem;
      min-height: 9rem;
      padding: 1rem;
      text-align: center;
    }

    .company-report-state-card .material-icons-sharp {
      color: var(--otziv-primary);
      font-size: 2rem;
    }

    .company-report-meta {
      display: flex;
      flex-wrap: wrap;
      gap: 0.42rem;
    }

    .company-report-meta span {
      border: 1px solid rgba(108, 155, 207, 0.28);
      border-radius: 999px;
      padding: 0.36rem 0.58rem;
      color: #526276;
      background: var(--otziv-white);
      font-size: 0.66rem;
      font-weight: 900;
      line-height: 1;
    }

    .company-report-reader-section {
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.74rem;
      background: var(--otziv-white);
      box-shadow: 0 0.5rem 1.1rem rgba(42, 57, 82, 0.06);
    }

    .company-report-reader-section {
      display: grid;
      gap: 0.55rem;
      padding: 0.72rem;
    }

    .company-report-reader-section h3 {
      margin: 0;
      padding: 0 0 0.46rem;
      border-bottom: 1px solid rgba(103, 116, 131, 0.14);
      color: var(--otziv-dark);
      font-size: 0.95rem;
      font-weight: 1000;
      line-height: 1.18;
    }

    .company-report-section {
      display: grid;
      gap: 0;
      padding: 0;
      overflow: hidden;
    }

    .company-report-section h3 {
      box-sizing: border-box;
      margin: 0;
      width: 100%;
      border-bottom: 1px solid rgba(103, 116, 131, 0.12);
      border-radius: 0;
      padding: 0.62rem 0.72rem;
      color: var(--otziv-dark);
      background: #eef4fb;
      font-family: var(--otziv-font-family);
      font-size: 0.8rem;
      font-weight: 1000;
      line-height: 1.12;
      text-align: left;
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 0.5rem;
      align-items: center;
      min-height: 2.7rem;
      height: auto;
      cursor: pointer;
      user-select: none;
    }

    .company-report-section-content {
      display: block;
      padding: 0.64rem 0.72rem 0.72rem;
    }

    .company-report-state-card p,
    .company-report-section > p,
    .company-report-section > ul li,
    .company-report-section a,
    .company-report-rich,
    :host ::ng-deep .company-report-rich p,
    :host ::ng-deep .company-report-rich li {
      margin: 0;
      color: #263244;
      font-size: 0.75rem;
      font-weight: 500;
      line-height: 1.3;
      overflow-wrap: break-word;
    }

    .company-report-rich {
      display: grid;
      gap: 0.38rem;
      text-align: left;
    }

    :host ::ng-deep .company-report-rich * {
      box-sizing: border-box;
    }

    :host ::ng-deep .company-report-rich p {
      max-width: 100%;
    }

    :host ::ng-deep .company-report-rich h4 {
      margin: 0.08rem 0 0;
      padding: 0.42rem 0.52rem;
      border-left: 0.22rem solid #6c9bcf;
      border-radius: 0.48rem;
      color: var(--otziv-dark);
      background: #f1f6fc;
      font-size: 0.82rem;
      font-weight: 1000;
      line-height: 1.18;
    }

    :host ::ng-deep .company-report-subheading {
      margin: 0.08rem 0 0;
      width: fit-content;
      max-width: 100%;
      border-radius: 999px;
      padding: 0.16rem 0.42rem;
      color: #1f2937;
      background: #fff4d8;
      font-size: 0.74rem;
      font-weight: 1000;
      line-height: 1.16;
    }

    :host ::ng-deep .company-report-rich strong {
      color: #111827;
      font-weight: 850;
    }

    :host ::ng-deep .company-report-rich code {
      border-radius: 0.36rem;
      padding: 0.08rem 0.28rem;
      color: #24364d;
      background: #eef4fb;
      font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
      font-size: 0.76rem;
      font-weight: 800;
    }

    .company-report-section ul,
    :host ::ng-deep .company-report-rich ul,
    :host ::ng-deep .company-report-rich ol {
      display: grid;
      gap: 0.24rem;
      margin: 0;
      padding-left: 1.1rem;
    }

    :host ::ng-deep .company-report-rich li::marker {
      color: #6c9bcf;
      font-weight: 1000;
    }

    :host ::ng-deep .company-report-table-stack {
      display: grid;
      gap: 0.42rem;
      max-width: 100%;
    }

    :host ::ng-deep .company-report-table-card {
      display: grid;
      gap: 0.28rem;
      margin: 0;
      padding: 0.52rem 0.56rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-left: 0.24rem solid #6c9bcf;
      border-radius: 0.62rem;
      background: #f8fbff;
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9);
    }

    :host ::ng-deep .company-report-table-card.tone-2 {
      border-left-color: #2f9e6d;
      background: #f6fbf8;
    }

    :host ::ng-deep .company-report-table-card.tone-3 {
      border-left-color: #d39a27;
      background: #fffaf0;
    }

    :host ::ng-deep .company-report-table-card.tone-4 {
      border-left-color: #8a7ccf;
      background: #fbf9ff;
    }

    :host ::ng-deep .company-report-table-card h5 {
      margin: 0;
      padding-bottom: 0.26rem;
      border-bottom: 1px solid rgba(103, 116, 131, 0.1);
      color: #1f2937;
      font-size: 0.79rem;
      font-weight: 1000;
      line-height: 1.15;
      text-align: left;
      overflow-wrap: anywhere;
    }

    :host ::ng-deep .company-report-table-field {
      display: grid;
      gap: 0.06rem;
      max-width: 100%;
      padding-top: 0.22rem;
      border-top: 1px solid rgba(103, 116, 131, 0.12);
    }

    :host ::ng-deep .company-report-table-card h5 + .company-report-table-field {
      padding-top: 0;
      border-top: 0;
    }

    :host ::ng-deep .company-report-table-label,
    :host ::ng-deep .company-report-table-value {
      min-width: 0;
      margin: 0;
      text-align: left;
      overflow-wrap: anywhere;
      word-break: break-word;
    }

    :host ::ng-deep .company-report-table-label {
      width: fit-content;
      max-width: 100%;
      border-radius: 999px;
      padding: 0.1rem 0.34rem;
      color: #66758a;
      background: rgba(108, 155, 207, 0.12);
      font-size: 0.62rem;
      font-weight: 1000;
      line-height: 1.08;
    }

    :host ::ng-deep .company-report-table-value {
      color: #263244;
      font-size: 0.73rem;
      font-weight: 500;
      line-height: 1.26;
    }

    .company-report-section.warning {
      border-color: rgba(244, 197, 66, 0.36);
      background: color-mix(in srgb, var(--otziv-white) 84%, #fff3b6 16%);
    }

    .company-report-section.sources a {
      color: var(--otziv-primary);
      font-weight: 900;
      text-decoration: none;
    }

    .company-report-actions {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .company-report-actions:empty {
      display: none;
    }

    .review-mobile-strip {
      display: flex;
      gap: var(--otziv-list-gap, 0.56rem);
      flex: 1 1 0;
      align-items: stretch;
      min-height: 0;
      margin-inline: calc(var(--otziv-page-padding-x, 0.68rem) * -1);
      overflow-x: auto;
      overflow-y: hidden;
      padding: 0 var(--otziv-page-padding-x, 0.68rem) 0.12rem;
      scroll-padding-inline: var(--otziv-page-padding-x, 0.68rem);
      scroll-snap-type: x proximity;
      scrollbar-width: none;
      overscroll-behavior-x: contain;
      touch-action: pan-y;
      -webkit-overflow-scrolling: touch;
    }

    .review-mobile-strip::-webkit-scrollbar {
      display: none;
    }

    .review-mobile-strip--dragging {
      scroll-snap-type: none !important;
      cursor: grabbing;
      user-select: none;
      -webkit-user-select: none;
    }

    .review-mobile-strip:not(.review-mobile-strip--expanded) .mobile-review-card,
    .review-mobile-strip:not(.review-mobile-strip--expanded) .mobile-review-card *,
    :host ::ng-deep .review-mobile-strip:not(.review-mobile-strip--expanded) app-mobile-review-card-shell.layout-details .mobile-review-card-shell {
      touch-action: pan-y;
    }

    .review-mobile-strip:not(.review-mobile-strip--expanded) .mobile-review-card,
    :host ::ng-deep .review-mobile-strip:not(.review-mobile-strip--expanded) app-mobile-review-card-shell.layout-details .mobile-review-card-shell {
      overflow: hidden !important;
      overscroll-behavior: contain;
      scroll-snap-stop: normal !important;
    }

    .review-mobile-strip--expanded {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      align-content: start;
      gap: 0.65rem;
      margin-inline: 0;
      overflow-x: hidden;
      overflow-y: auto;
      padding: 0 0.1rem 0.15rem;
      scroll-snap-type: none;
    }

    .mobile-review-card {
      display: grid;
      grid-template-rows: auto minmax(0, 1fr) auto auto auto auto auto;
      align-content: stretch;
      gap: var(--otziv-card-gap, 0.36rem);
      flex: 0 0 var(--otziv-detail-card-width, min(15.4rem, 79vw));
      min-width: 0;
      height: 100%;
      max-height: 100%;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.9rem;
      padding: var(--otziv-card-padding, 0.52rem);
      overflow: hidden;
      background: linear-gradient(180deg, var(--otziv-tone-walk-surface) 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
      box-shadow: 0 0.55rem 1.2rem rgba(132, 139, 200, 0.14);
      scroll-snap-align: center;
    }

    .review-mobile-strip--expanded .mobile-review-card {
      width: 100%;
      max-height: none;
      height: auto;
      flex: none;
      scroll-snap-align: none;
    }

    .mobile-review-card.active {
      border-color: rgba(244, 197, 66, 0.72);
      box-shadow: 0 0 0 0.14rem rgba(244, 197, 66, 0.18);
    }

    .mobile-review-card.published {
      border-color: var(--otziv-tone-success-border);
      background: linear-gradient(180deg, var(--otziv-tone-success-surface) 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
    }

    .mobile-review-card header,
    .mobile-review-card footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.45rem;
      min-width: 0;
    }

    .mobile-review-card header a,
    .mobile-review-card header strong {
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: 1rem;
      font-weight: 1000;
      line-height: 1.1;
      text-decoration: none;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .mobile-review-card header em {
      display: grid;
      min-width: 2.6rem;
      min-height: 1.28rem;
      place-items: center;
      border-radius: 999px;
      color: #4d3900;
      background: rgba(244, 197, 66, 0.26);
      font-family: var(--otziv-font-family);
      font-size: 0.62rem;
      font-style: normal;
      font-weight: 1000;
    }

    .mobile-review-card header span {
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
      flex: 0 0 auto;
    }

    .mobile-review-card small,
    .mobile-review-card footer span,
    .mobile-review-card footer .review-edit-link {
      color: var(--otziv-info);
      font-family: var(--otziv-font-family);
      font-size: 0.68rem;
      font-weight: 900;
    }

    .mobile-review-card footer .review-edit-link {
      min-width: 0;
      border: 0;
      padding: 0;
      overflow: hidden;
      background: transparent;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .mobile-review-card footer .review-edit-link:disabled {
      opacity: 0.72;
    }

    .review-card-badge {
      display: grid;
      width: 1.28rem;
      height: 1.28rem;
      place-items: center;
      border: 0;
      border-radius: 999px;
      color: #4d3900;
      background: #f4c542;
      font: inherit;
      text-decoration: none;
    }

    .review-card-badge.danger {
      color: #ffffff;
      background: var(--otziv-danger);
    }

    .review-card-badge.photo:not(.danger) {
      color: #ffffff;
      background: var(--otziv-success);
    }

    .review-card-badge .material-icons-sharp {
      font-size: 0.82rem;
    }

    .review-text-editor,
    .review-answer-editor,
    .review-note-panel {
      position: relative;
      display: grid;
      gap: 0.28rem;
      isolation: isolate;
      min-width: 0;
    }

    .review-text-editor {
      min-height: 0;
      grid-template-rows: minmax(0, 1fr) auto;
      overflow: hidden;
    }

    .review-text-editor.editing {
      gap: 0.34rem;
      grid-template-rows: minmax(0, 1fr) auto;
    }

    .review-text-editor textarea,
    .review-answer-editor textarea,
    .review-note-panel textarea,
    .review-display-field {
      position: relative;
      z-index: 1;
      box-sizing: border-box;
      width: 100%;
      min-width: 0;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.8rem;
      padding: 0.5rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font-family: var(--otziv-font-family);
      font-size: 0.72rem;
      font-weight: 700;
      line-height: 1.24;
      text-align: left;
    }

    .review-text-editor textarea,
    .review-text-editor .review-display-field {
      font-weight: 650;
    }

    .review-text-editor textarea,
    .review-text-editor .review-display-field {
      display: block;
      height: var(--otziv-review-text-height, 8.2rem);
      min-height: var(--otziv-review-text-height, 8.2rem);
      overflow: hidden;
      padding-bottom: 1.05rem;
      resize: none;
      white-space: pre-wrap;
    }

    .review-text-editor .review-display-field {
      display: flex;
      align-content: flex-start;
      align-items: flex-start;
      justify-content: flex-start;
    }

    .review-answer-editor {
      z-index: 2;
      min-height: var(--otziv-review-answer-height, 2.78rem);
    }

    .review-text-editor.open textarea,
    .review-text-editor.open .review-display-field {
      height: var(--otziv-review-text-height-open, 8.2rem);
      overflow: auto;
    }

    .review-text-editor.editing textarea {
      height: var(--otziv-review-text-height-open, 7.9rem);
      min-height: var(--otziv-review-text-height-open, 7.9rem);
      overflow: auto;
      padding-bottom: 0.72rem;
    }

    .review-answer-editor textarea,
    .review-display-field--answer {
      display: -webkit-box;
      height: var(--otziv-review-answer-height, 2.78rem);
      min-height: var(--otziv-review-answer-height, 2.78rem);
      padding: 0.38rem 0.5rem;
      overflow: hidden;
      resize: none;
      opacity: 1;
      color: var(--otziv-info);
      background: linear-gradient(180deg, var(--otziv-field-background) 0%, var(--otziv-muted-surface) 100%);
      border-style: dashed;
      font-size: 0.56rem;
      font-weight: 800;
      line-height: 1.12;
      text-align: center;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 3;
    }

    .review-answer-editor textarea:focus {
      opacity: 1;
    }

    .review-answer-editor.editing textarea {
      display: block;
      height: 3.1rem;
      min-height: 3.1rem;
      overflow: auto;
    }

    .review-display-field.empty {
      color: var(--otziv-info);
      font-weight: 700;
    }

    .text-toggle {
      position: absolute;
      z-index: 3;
      right: 0.55rem;
      bottom: 0.5rem;
      border: 0;
      padding: 0;
      color: var(--otziv-info);
      background: var(--otziv-field-background);
      font-family: var(--otziv-font-family);
      font-size: 0.62rem;
      font-weight: 900;
    }

    .field-actions {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: 0.44rem;
      align-items: center;
      min-height: 1.68rem;
    }

    .field-actions button,
    .review-actions button,
    .publish-button,
    .order-details-actions button {
      min-height: 1.62rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font: inherit;
      font-size: 0.58rem;
      font-weight: 1000;
      line-height: 1;
    }

    .review-text-editor > .field-actions button,
    .review-answer-editor > .field-actions button {
      align-self: center;
      height: 1.68rem;
      min-height: 1.68rem;
      padding: 0 0.42rem;
      font-size: 0.56rem;
    }

    .field-actions .save,
    .order-details-actions .primary {
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.2) 0%, var(--otziv-white) 100%);
    }

    .bot-line {
      display: grid;
      min-height: 1.58rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.68rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font-family: var(--otziv-font-family);
      font-size: 0.64rem;
      font-weight: 900;
      text-align: center;
    }

    .bot-line.empty {
      color: var(--otziv-danger);
    }

    .bot-line.compact {
      overflow: hidden;
      padding-inline: 0.35rem;
      font-size: 0.52rem;
      line-height: 1;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .review-note-panel {
      max-height: 13rem;
      overflow: auto;
      border: 1px solid rgba(244, 197, 66, 0.36);
      border-radius: 0.86rem;
      padding: 0.5rem;
      background: #fffaf0;
      scrollbar-width: thin;
    }

    .review-note-panel label {
      display: grid;
      gap: 0.28rem;
    }

    .review-note-panel label span {
      color: var(--otziv-info);
      font-family: var(--otziv-font-family);
      font-size: 0.68rem;
      font-weight: 900;
    }

    .review-note-panel textarea {
      height: 3.15rem;
      min-height: 3.15rem;
      resize: none;
      font-size: 0.68rem;
      line-height: 1.24;
    }

    .review-note-panel .field-actions {
      position: sticky;
      bottom: -0.5rem;
      padding-top: 0.18rem;
      background: #fffaf0;
    }

    .review-actions {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 0.28rem;
      align-items: stretch;
      min-width: 0;
    }

    .review-actions button,
    .review-actions a,
    .publish-button {
      min-width: 0;
      overflow: hidden;
      padding-inline: 0.22rem;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .review-actions .ai-action {
      border-color: rgba(244, 197, 66, 0.42);
      color: #4d3900;
      background: #fff8c9;
    }

    .review-actions .recovery-action {
      border-color: rgba(74, 198, 177, 0.28);
      color: #16735f;
      background: linear-gradient(145deg, rgba(74, 198, 177, 0.14) 0%, var(--otziv-white) 100%);
      font-size: 0.6rem;
    }

    .review-actions .recovery-action.planned {
      color: var(--otziv-info);
      background: var(--otziv-muted-surface);
    }

    :host-context(body.otziv-dark-theme) .review-actions .ai-action {
      border-color: rgba(248, 217, 117, 0.72);
      color: #342800;
      background: linear-gradient(145deg, #fff3b6 0%, #f8d975 100%);
      text-shadow: none;
    }

    .mobile-task-card {
      grid-template-rows: auto auto auto minmax(0, 1fr) auto auto;
      background: linear-gradient(180deg, var(--otziv-tone-wait-surface) 0%, var(--otziv-white) 52%, var(--otziv-white) 100%);
    }

    .mobile-task-card--bad {
      border-color: var(--otziv-tone-bad-border);
      background: linear-gradient(180deg, var(--otziv-tone-bad-surface) 0%, var(--otziv-white) 52%, var(--otziv-white) 100%);
    }

    .mobile-task-card--recovery {
      border-color: var(--otziv-tone-success-border);
      background: linear-gradient(180deg, var(--otziv-tone-success-surface) 0%, var(--otziv-white) 52%, var(--otziv-white) 100%);
    }

    .mobile-task-card.done {
      opacity: 0.82;
    }

    .mobile-task-card.canceled {
      border-style: dashed;
      opacity: 0.7;
    }

    .task-meta-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.35rem;
      font-family: var(--otziv-font-family);
    }

    .task-meta-grid span,
    .task-date-field {
      min-width: 0;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 999px;
      padding: 0.36rem 0.48rem;
      overflow: hidden;
      color: var(--otziv-info);
      background: var(--otziv-field-background);
      font-size: 0.58rem;
      font-weight: 900;
      line-height: 1.15;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .task-date-field {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.35rem;
      border-radius: 0.78rem;
      padding: 0.42rem 0.52rem;
    }

    .task-date-field input {
      min-width: 0;
      border: 0;
      color: var(--otziv-dark);
      background: transparent;
      font: inherit;
      font-size: 0.64rem;
      font-weight: 900;
    }

    .task-text-field {
      width: 100%;
      min-height: 8.1rem;
      max-height: 8.1rem;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.84rem;
      padding: 0.62rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font-family: var(--otziv-font-family);
      font-size: 0.74rem;
      font-weight: 800;
      line-height: 1.3;
      overflow: auto;
      resize: none;
    }

    .task-answer-field {
      width: 100%;
      min-height: 3.15rem;
      max-height: 3.15rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 0.82rem;
      padding: 0.54rem 0.6rem;
      color: var(--otziv-info);
      background: var(--otziv-field-background);
      font-family: var(--otziv-font-family);
      font-size: 0.64rem;
      font-weight: 700;
      line-height: 1.24;
      overflow: auto;
      opacity: 0.78;
      resize: none;
    }

    .task-text-field[readonly] {
      color: var(--otziv-info);
    }

    .task-note-field {
      display: grid;
      gap: 0.12rem;
      min-height: 2.25rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.78rem;
      padding: 0.42rem 0.52rem;
      color: var(--otziv-info);
      background: color-mix(in srgb, var(--otziv-field-background) 88%, var(--otziv-tone-bad-surface) 12%);
      font-family: var(--otziv-font-family);
    }

    .task-note-field small {
      color: var(--otziv-muted);
      font-size: 0.53rem;
      font-weight: 900;
      line-height: 1;
      text-transform: uppercase;
    }

    .task-note-field span {
      overflow: hidden;
      font-size: 0.62rem;
      font-weight: 800;
      line-height: 1.18;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .task-answer-field[readonly] {
      opacity: 0.62;
    }

    .task-actions .save {
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 100%);
    }

    .task-actions .danger {
      color: var(--otziv-danger);
      background: linear-gradient(145deg, rgba(239, 52, 95, 0.12) 0%, var(--otziv-white) 100%);
    }

    .mobile-review-card footer .danger-link {
      color: var(--otziv-danger);
    }

    .task-notice {
      display: grid;
      place-items: center;
      gap: 0.55rem;
      min-height: 14rem;
      border: 1px solid rgba(74, 198, 177, 0.22);
      border-radius: 0.86rem;
      padding: 0.85rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font-family: var(--otziv-font-family);
      font-weight: 900;
      line-height: 1.3;
      text-align: center;
    }

    .task-notice p {
      margin: 0;
    }

    .task-notice .material-icons-sharp {
      color: var(--otziv-success);
      font-size: 2rem;
    }

    .publish-button {
      width: 100%;
      color: var(--otziv-primary);
      text-transform: uppercase;
    }

    .publish-button.published {
      color: #137d69;
      background: #dff7ed;
    }

    .order-details-actions {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.46rem;
      padding: 0.52rem;
      font-family: var(--otziv-font-family);
      touch-action: manipulation;
    }

    .order-details-actions button {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.26rem;
      min-width: 0;
      min-height: 2.2rem;
      padding: 0 0.48rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      touch-action: manipulation;
    }

    .order-details-actions .material-icons-sharp {
      flex: 0 0 auto;
      font-size: 1rem;
    }

    .order-review-bottom-controls .expand-list-button,
    .order-review-bottom-controls .lead-pager button {
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 92%);
    }

    .mobile-empty-state,
    .mobile-error-card {
      display: grid;
      place-items: center;
      gap: 0.35rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.9rem;
      padding: 1rem;
      color: var(--otziv-info);
      background: var(--otziv-white);
      font-family: var(--otziv-font-family);
      font-weight: 900;
      text-align: center;
    }

    .mobile-error-card {
      width: 100%;
      border-color: rgba(255, 0, 96, 0.24);
      color: #9a2737;
      background: rgba(255, 0, 96, 0.06);
    }

    button:disabled {
      opacity: 0.5;
    }

  `]
})
export class OrderDetailsPage implements OnInit, OnDestroy {
  private routeSubscription?: Subscription;
  private reviewStripCleanup?: () => void;
  private reviewDrag: {
    pointerId: number;
    startX: number;
    startY: number;
    scrollLeft: number;
    moved: boolean;
  } | null = null;
  private suppressReviewClickUntil = 0;

  @ViewChild('reviewStrip')
  set reviewStripRef(ref: ElementRef<HTMLElement> | undefined) {
    this.reviewStripCleanup?.();
    this.reviewStripCleanup = undefined;
    if (ref?.nativeElement) {
      this.reviewStripCleanup = this.attachReviewStripDrag(ref.nativeElement);
    }
  }

  readonly orderId = signal<number | null>(null);
  readonly companyId = signal<number | null>(null);
  readonly details = signal<OrderDetailsPayload | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly copiedKey = signal<string | null>(null);
  readonly activeReviewIndex = signal(0);
  readonly editingFieldKey = signal<string | null>(null);
  readonly reviewFieldDrafts = signal<Record<string, string>>({});
  readonly expandedReviewIds = signal<Record<number, boolean>>({});
  readonly noteOpenId = signal<number | null>(null);
  readonly reviewNoteDrafts = signal<Record<number, string>>({});
  readonly reviewSideNoteDrafts = signal<Record<string, string>>({});
  readonly companyReportVisible = signal(false);
  readonly companyReportLoading = signal(false);
  readonly companyReportError = signal<string | null>(null);
  readonly companyReportState = signal<CompanyDeepReportState | null>(null);
  readonly tbankStatus = signal<TbankPaymentStatus | null>(null);
  readonly paymentLink = signal<ManagerPaymentLinkResponse | null>(null);
  readonly hasReadyCompanyReport = computed(() => !!this.companyReportState()?.latestJob?.report);
  readonly companyReportBusy = computed(() => this.companyReportLoading() || !!this.companyReportState()?.activeJob);
  readonly companyReport = computed(() => (this.companyReportState()?.latestJob?.report ?? null) as CompanyReport | null);
  readonly companyReportSections = computed(() => this.buildCompanyReportSections(this.companyReport()));
  readonly companyReportReviewIdeas = computed(() => this.cleanStringList(this.companyReport()?.reviewIdeas).slice(0, 12));
  readonly companyReportWarnings = computed(() => this.cleanStringList(this.companyReport()?.warnings));
  readonly companyReportSources = computed(() => (this.companyReport()?.sources ?? [])
    .filter((source): source is CompanyReportSource => !!source && Boolean(this.cleanText(source.title) || this.cleanText(source.url) || this.cleanText(source.note)))
    .slice(0, 10));
  readonly companyReportConfirmedFacts = computed(() => (this.companyReport()?.factSnapshot?.confirmedFacts ?? [])
    .filter((fact): fact is CompanyReportFact => !!fact && Boolean(this.cleanText(fact.label) || this.cleanText(fact.value) || this.cleanText(fact.evidence)))
    .slice(0, 8));
  readonly reviewsExpanded = signal(false);
  readonly reviewEdit = signal<OrderReviewItem | null>(null);
  readonly reviewEditInitialField = signal<ReviewEditableField | null>(null);
  readonly reviewEditDraft = signal<ReviewUpdateRequest | null>(null);
  readonly reviewEditSaving = signal(false);
  readonly reviewEditDeleting = signal(false);
  readonly reviewEditUploading = signal(false);
  readonly reviewEditError = signal<string | null>(null);
  readonly reviewTextEdit = signal<ReviewTextEditState | null>(null);
  readonly reviewTextEditValue = signal('');
  readonly reviewTextEditSaving = signal(false);
  readonly reviewTextEditError = signal<string | null>(null);
  readonly badReviewTaskDrafts = signal<Record<number, BadReviewTaskDraft>>({});
  readonly savedBadReviewTaskId = signal<number | null>(null);
  readonly recoveryTaskDrafts = signal<Record<number, RecoveryTaskDraft>>({});
  readonly savedRecoveryTaskId = signal<number | null>(null);
  readonly badReviewTasks = computed(() => [...(this.details()?.badReviewTasks ?? [])]
    .sort((left, right) => (left.id ?? 0) - (right.id ?? 0)));
  readonly recoveryTasks = computed(() => [...(this.details()?.recoveryTasks ?? [])]
    .sort((left, right) => (left.id ?? 0) - (right.id ?? 0)));
  readonly recoveryBatchesToNotify = computed(() => {
    const batches = new Map<number, ReviewRecoveryBatchItem>();
    for (const task of this.recoveryTasks()) {
      const batch = task.batch;
      if (batch?.id && batch.statusCode === 'COMPLETED') {
        batches.set(batch.id, batch);
      }
    }
    return Array.from(batches.values());
  });
  readonly totalDetailCards = computed(() =>
    (this.details()?.reviews.length ?? 0)
    + this.badReviewTasks().length
    + this.recoveryBatchesToNotify().length
    + this.recoveryTasks().length
  );

  constructor(
    private readonly api: ApiService,
    private readonly auth: AuthService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly confirm: MobileConfirmService,
    private readonly media: MobileMediaService
  ) {}

  @HostListener('window:review-recovery-client-notified', ['$event'])
  onRecoveryClientNotified(event: Event): void {
    const detail = (event as CustomEvent<MobileRecoveryClientNotifiedDetail>).detail;
    if (!detail?.orderId || detail.orderId !== this.orderId()) {
      return;
    }

    if (this.mutationKey() === `recovery-notified-${detail.batchId}`) {
      return;
    }

    this.loadDetails();
  }

  ngOnInit(): void {
    this.loadTbankStatus();
    this.routeSubscription = new Subscription();
    this.routeSubscription.add(this.route.paramMap.subscribe((params) => {
      const companyId = Number(params.get('companyId'));
      const orderId = Number(params.get('orderId'));
      this.companyId.set(Number.isFinite(companyId) && companyId > 0 ? companyId : null);
      if (!Number.isFinite(orderId) || orderId <= 0) {
        this.error.set('Заказ не найден');
        return;
      }

      this.orderId.set(orderId);
      this.loadDetails();
    }));
    this.routeSubscription.add(this.route.queryParamMap.subscribe(() => this.selectRequestedReview()));
  }

  ngOnDestroy(): void {
    this.routeSubscription?.unsubscribe();
    this.reviewStripCleanup?.();
    this.reviewStripCleanup = undefined;
  }

  refresh(event: RefresherCustomEvent): void {
    this.loadDetails(() => event.target.complete());
  }

  loadDetails(done?: () => void): void {
    const orderId = this.orderId();
    if (!orderId) {
      done?.();
      return;
    }

    this.loading.set(true);
    this.error.set(null);
    this.api.getManagerOrderDetails(orderId).subscribe({
      next: (details) => {
        this.details.set(details);
        this.selectRequestedReview(details);
        this.reviewFieldDrafts.set({});
        this.reviewNoteDrafts.set({});
        this.reviewSideNoteDrafts.set({});
        this.badReviewTaskDrafts.set({});
        this.recoveryTaskDrafts.set({});
        this.editingFieldKey.set(null);
        this.noteOpenId.set(null);
        this.loading.set(false);
        done?.();
      },
      error: (err) => {
        this.error.set(this.errorMessage(err, 'Не удалось загрузить детали заказа'));
        this.loading.set(false);
        done?.();
      }
    });
  }

  backToOrders(): void {
    void this.router.navigate(['/tabs/orders'], {
      queryParams: { mobileNav: 'all', navTs: Date.now() }
    });
  }

  previousReview(): void {
    this.goToReviewIndex(Math.max(0, this.activeReviewIndex() - 1));
  }

  nextReview(): void {
    const max = Math.max(0, this.totalDetailCards() - 1);
    this.goToReviewIndex(Math.min(max, this.activeReviewIndex() + 1));
  }

  private selectRequestedReview(details: OrderDetailsPayload | null = this.details()): void {
    const reviewId = Number(this.route.snapshot.queryParamMap.get('reviewId'));
    if (!details || !Number.isFinite(reviewId) || reviewId <= 0) {
      this.activeReviewIndex.set(0);
      return;
    }

    const index = details.reviews.findIndex((review) => review.id === reviewId);
    if (index < 0) {
      this.activeReviewIndex.set(0);
      return;
    }

    window.setTimeout(() => this.goToReviewIndex(index), 60);
  }

  goToReviewIndex(index: number): void {
    if (index < 0 || index >= this.totalDetailCards()) {
      return;
    }

    this.activeReviewIndex.set(index);
    window.requestAnimationFrame(() => {
      document.querySelector<HTMLElement>(`.mobile-review-card[data-card-index="${index}"]`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
    });
  }

  toggleReviewsExpanded(): void {
    this.reviewsExpanded.update((expanded) => !expanded);
    window.setTimeout(() => {
      document.querySelector<HTMLElement>('app-order-details-mobile .review-mobile-strip')
        ?.scrollTo({ left: 0, top: 0, behavior: 'auto' });
    });
  }

  onReviewScroll(event: Event): void {
    const container = event.target as HTMLElement;
    const cards = Array.from(container.querySelectorAll<HTMLElement>('.mobile-review-card'));
    if (!cards.length) {
      return;
    }

    const containerRect = container.getBoundingClientRect();
    const center = containerRect.left + containerRect.width / 2;
    let bestIndex = 0;
    let bestDistance = Number.POSITIVE_INFINITY;
    cards.forEach((card, index) => {
      const cardRect = card.getBoundingClientRect();
      const distance = Math.abs(cardRect.left + cardRect.width / 2 - center);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestIndex = index;
      }
    });
    this.activeReviewIndex.set(bestIndex);
  }

  private attachReviewStripDrag(container: HTMLElement): () => void {
    const options: AddEventListenerOptions = { capture: true, passive: false };
    const touchPointerId = -1;

    const onPointerDown = (event: PointerEvent): void => {
      if (this.reviewsExpanded() || this.reviewDrag || !event.isPrimary || event.button > 0) {
        return;
      }

      this.reviewDrag = {
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        scrollLeft: container.scrollLeft,
        moved: false
      };
      try {
        container.setPointerCapture?.(event.pointerId);
      } catch {
        // Some Android WebView builds reject capture when a nested control owns the touch target.
      }
    };

    const onPointerMove = (event: PointerEvent): void => {
      const drag = this.reviewDrag;
      if (!drag || drag.pointerId !== event.pointerId || this.reviewsExpanded()) {
        return;
      }

      const deltaX = event.clientX - drag.startX;
      const deltaY = event.clientY - drag.startY;
      if (!drag.moved && Math.abs(deltaX) < 7 && Math.abs(deltaY) < 7) {
        return;
      }

      if (Math.abs(deltaX) <= Math.abs(deltaY)) {
        return;
      }

      drag.moved = true;
      event.preventDefault();
      event.stopPropagation();
      container.classList.add('review-mobile-strip--dragging');
      container.scrollLeft = drag.scrollLeft - deltaX;
    };

    const endDrag = (event: PointerEvent): void => {
      const drag = this.reviewDrag;
      if (!drag || drag.pointerId !== event.pointerId) {
        return;
      }

      this.reviewDrag = null;
      try {
        container.releasePointerCapture?.(event.pointerId);
      } catch {
        // Ignore WebView capture-release quirks.
      }
      if (!drag.moved) {
        container.classList.remove('review-mobile-strip--dragging');
        return;
      }

      event.preventDefault();
      event.stopPropagation();
      this.suppressReviewClickUntil = Date.now() + 400;
      this.snapReviewStripToNearest(container);
    };

    const cancelDrag = (event: PointerEvent): void => {
      if (this.reviewDrag?.pointerId !== event.pointerId) {
        return;
      }
      this.reviewDrag = null;
      container.classList.remove('review-mobile-strip--dragging');
    };

    const onTouchStart = (event: TouchEvent): void => {
      if (this.reviewsExpanded() || this.reviewDrag || event.touches.length !== 1) {
        return;
      }

      const touch = event.touches[0];
      this.reviewDrag = {
        pointerId: touchPointerId,
        startX: touch.clientX,
        startY: touch.clientY,
        scrollLeft: container.scrollLeft,
        moved: false
      };
    };

    const onTouchMove = (event: TouchEvent): void => {
      const drag = this.reviewDrag;
      if (!drag || drag.pointerId !== touchPointerId || this.reviewsExpanded() || event.touches.length !== 1) {
        return;
      }

      const touch = event.touches[0];
      const deltaX = touch.clientX - drag.startX;
      const deltaY = touch.clientY - drag.startY;
      if (!drag.moved && Math.abs(deltaX) < 7 && Math.abs(deltaY) < 7) {
        return;
      }

      if (Math.abs(deltaX) <= Math.abs(deltaY)) {
        return;
      }

      drag.moved = true;
      event.preventDefault();
      event.stopPropagation();
      container.classList.add('review-mobile-strip--dragging');
      container.scrollLeft = drag.scrollLeft - deltaX;
    };

    const endTouchDrag = (event: TouchEvent): void => {
      const drag = this.reviewDrag;
      if (!drag || drag.pointerId !== touchPointerId) {
        return;
      }

      this.reviewDrag = null;
      if (!drag.moved) {
        container.classList.remove('review-mobile-strip--dragging');
        return;
      }

      event.preventDefault();
      event.stopPropagation();
      this.suppressReviewClickUntil = Date.now() + 400;
      this.snapReviewStripToNearest(container);
    };

    const onClick = (event: MouseEvent): void => {
      if (Date.now() < this.suppressReviewClickUntil) {
        event.preventDefault();
        event.stopPropagation();
      }
    };

    container.addEventListener('pointerdown', onPointerDown, options);
    container.addEventListener('pointermove', onPointerMove, options);
    container.addEventListener('pointerup', endDrag, options);
    container.addEventListener('pointercancel', cancelDrag, options);
    container.addEventListener('click', onClick, options);
    container.addEventListener('touchstart', onTouchStart, options);
    container.addEventListener('touchmove', onTouchMove, options);
    container.addEventListener('touchend', endTouchDrag, options);
    container.addEventListener('touchcancel', endTouchDrag, options);

    return () => {
      container.removeEventListener('pointerdown', onPointerDown, options);
      container.removeEventListener('pointermove', onPointerMove, options);
      container.removeEventListener('pointerup', endDrag, options);
      container.removeEventListener('pointercancel', cancelDrag, options);
      container.removeEventListener('click', onClick, options);
      container.removeEventListener('touchstart', onTouchStart, options);
      container.removeEventListener('touchmove', onTouchMove, options);
      container.removeEventListener('touchend', endTouchDrag, options);
      container.removeEventListener('touchcancel', endTouchDrag, options);
    };
  }

  private snapReviewStripToNearest(container: HTMLElement): void {
    const cards = Array.from(container.querySelectorAll<HTMLElement>('.mobile-review-card'));
    if (!cards.length) {
      container.classList.remove('review-mobile-strip--dragging');
      return;
    }

    const center = container.getBoundingClientRect().left + container.clientWidth / 2;
    let bestCard = cards[0];
    let bestIndex = 0;
    let bestDistance = Number.POSITIVE_INFINITY;
    cards.forEach((card, index) => {
      const rect = card.getBoundingClientRect();
      const distance = Math.abs(rect.left + rect.width / 2 - center);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestCard = card;
        bestIndex = index;
      }
    });

    this.activeReviewIndex.set(bestIndex);
    const target = bestCard.offsetLeft - Math.max(0, (container.clientWidth - bestCard.offsetWidth) / 2);
    const max = container.scrollWidth - container.clientWidth;
    container.scrollTo({ left: Math.max(0, Math.min(max, target)), behavior: 'smooth' });
    window.setTimeout(() => {
      container.classList.remove('review-mobile-strip--dragging');
    }, 260);
  }

  badTaskCardIndex(index: number): number {
    return (this.details()?.reviews.length ?? 0) + index;
  }

  recoveryBatchCardIndex(index: number): number {
    return (this.details()?.reviews.length ?? 0) + this.badReviewTasks().length + index;
  }

  recoveryTaskCardIndex(index: number): number {
    return (this.details()?.reviews.length ?? 0)
      + this.badReviewTasks().length
      + this.recoveryBatchesToNotify().length
      + index;
  }

  reviewFieldValue(review: OrderReviewItem, field: ReviewEditableField): string {
    const key = this.reviewFieldKey(review, field);
    return this.reviewFieldDrafts()[key] ?? this.reviewFieldSourceValue(review, field);
  }

  setReviewFieldDraft(review: OrderReviewItem, field: ReviewEditableField, value: string): void {
    const key = this.reviewFieldKey(review, field);
    this.reviewFieldDrafts.update((drafts) => ({ ...drafts, [key]: value }));
  }

  startReviewFieldEdit(review: OrderReviewItem, field: ReviewEditableField): void {
    if (!this.details()?.canEditReviews) {
      return;
    }

    const key = this.reviewFieldKey(review, field);
    this.editingFieldKey.set(key);
    this.reviewFieldDrafts.update((drafts) => key in drafts ? drafts : {
      ...drafts,
      [key]: this.reviewFieldSourceValue(review, field)
    });
  }

  startReviewTextInlineEdit(review: OrderReviewItem): void {
    this.startReviewFieldEdit(review, 'text');
    window.setTimeout(() => this.focusInlineReviewText(review.id), 40);
  }

  cancelReviewFieldEdit(review: OrderReviewItem, field: ReviewEditableField): void {
    const key = this.reviewFieldKey(review, field);
    this.editingFieldKey.set(null);
    this.reviewFieldDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[key];
      return next;
    });
    this.blurActiveControl();
  }

  saveReviewField(review: OrderReviewItem, field: ReviewEditableField): void {
    if (!this.canSaveReviewField(review, field)) {
      return;
    }

    const value = this.reviewFieldValue(review, field);
    const key = this.saveFieldMutationKey(review, field);
    this.mutationKey.set(key);
    const request = field === 'text'
      ? this.api.updateManagerOrderReviewText(review.orderId, review.id, value)
      : this.api.updateManagerOrderReviewAnswer(review.orderId, review.id, value);

    request.subscribe({
      next: (updatedReview) => {
        this.applyUpdatedReview(updatedReview);
        this.mutationKey.set(null);
        this.cancelReviewFieldEdit(updatedReview, field);
        this.blurActiveControl();
      },
      error: (err) => {
        this.error.set(this.errorMessage(err, 'Не удалось сохранить отзыв'));
        this.mutationKey.set(null);
      }
    });
  }

  canSaveReviewField(review: OrderReviewItem, field: ReviewEditableField): boolean {
    if (this.isMutating(this.saveFieldMutationKey(review, field))) {
      return false;
    }

    const value = this.reviewFieldValue(review, field);
    if (field === 'text' && !value.trim()) {
      return false;
    }

    return value !== this.reviewFieldSourceValue(review, field);
  }

  isReviewFieldEditing(review: OrderReviewItem, field: ReviewEditableField): boolean {
    return this.editingFieldKey() === this.reviewFieldKey(review, field);
  }

  saveFieldMutationKey(review: OrderReviewItem, field: ReviewEditableField): string {
    return `save-${field}-${review.id}`;
  }

  openReviewTextEdit(review: OrderReviewItem, field: ReviewEditableField): void {
    if (!this.details()?.canEditReviews) {
      this.error.set('Редактирование отзывов недоступно для этого заказа.');
      return;
    }

    if (this.reviewTextEditSaving()) {
      return;
    }

    this.editingFieldKey.set(null);
    this.reviewTextEdit.set({ review, field });
    this.reviewTextEditValue.set(this.reviewFieldValue(review, field));
    this.reviewTextEditError.set(null);
    window.setTimeout(() => this.focusReviewTextEditor(), 150);
  }

  closeReviewTextEdit(): void {
    if (this.reviewTextEditSaving()) {
      return;
    }

    this.reviewTextEdit.set(null);
    this.reviewTextEditValue.set('');
    this.reviewTextEditError.set(null);
    this.blurActiveControl();
  }

  setReviewTextEditValue(value: string): void {
    this.reviewTextEditValue.set(value);
  }

  reviewTextEditTitle(): string {
    return this.reviewTextEdit()?.field === 'answer' ? 'Ответ или замечание' : 'Текст отзыва';
  }

  reviewTextEditLabel(): string {
    return this.reviewTextEdit()?.field === 'answer' ? 'Ответ на отзыв или замечание' : 'Текст отзыва';
  }

  reviewTextEditPlaceholder(): string {
    return this.reviewTextEdit()?.field === 'answer'
      ? 'Впишите ответ на отзыв или внутреннее замечание'
      : 'Впишите текст отзыва';
  }

  reviewTextEditNote(): string {
    const state = this.reviewTextEdit();
    if (!state) {
      return 'Редактор';
    }

    const title = state.review.companyTitle || this.details()?.companyTitle || 'Компания';
    return `${title} · #${state.review.id}`;
  }

  canSaveReviewTextEdit(): boolean {
    const state = this.reviewTextEdit();
    if (!state || this.reviewTextEditSaving()) {
      return false;
    }

    const value = this.reviewTextEditValue();
    if (state.field === 'text' && !value.trim()) {
      return false;
    }

    return value !== this.reviewFieldSourceValue(state.review, state.field);
  }

  saveReviewTextEdit(): void {
    const state = this.reviewTextEdit();
    if (!state) {
      return;
    }

    const value = this.reviewTextEditValue();
    if (state.field === 'text' && !value.trim()) {
      this.reviewTextEditError.set('Заполните текст отзыва.');
      return;
    }

    if (!this.canSaveReviewTextEdit()) {
      this.closeReviewTextEdit();
      return;
    }

    this.reviewTextEditSaving.set(true);
    this.reviewTextEditError.set(null);
    const request = state.field === 'text'
      ? this.api.updateManagerOrderReviewText(state.review.orderId, state.review.id, value)
      : this.api.updateManagerOrderReviewAnswer(state.review.orderId, state.review.id, value);

    request.subscribe({
      next: (updatedReview) => {
        this.applyUpdatedReview(updatedReview);
        this.clearReviewDrafts(updatedReview.id);
        this.reviewTextEditSaving.set(false);
        this.closeReviewTextEdit();
      },
      error: (err) => {
        this.reviewTextEditError.set(this.errorMessage(err, 'Не удалось сохранить отзыв.'));
        this.reviewTextEditSaving.set(false);
      }
    });
  }

  toggleReviewText(review: OrderReviewItem): void {
    this.expandedReviewIds.update((items) => ({
      ...items,
      [review.id]: !items[review.id]
    }));
  }

  isReviewTextOpen(review: OrderReviewItem): boolean {
    return !!this.expandedReviewIds()[review.id] || this.isReviewFieldEditing(review, 'text');
  }

  shouldShowTextToggle(review: OrderReviewItem): boolean {
    return this.reviewFieldValue(review, 'text').length > 210;
  }

  toggleReviewNote(review: OrderReviewItem): void {
    this.noteOpenId.update((id) => id === review.id ? null : review.id);
    this.reviewNoteDrafts.update((drafts) => review.id in drafts ? drafts : {
      ...drafts,
      [review.id]: review.comment ?? ''
    });
    this.reviewSideNoteDrafts.update((drafts) => ({
      ...drafts,
      [this.reviewSideNoteKey(review, 'order')]: drafts[this.reviewSideNoteKey(review, 'order')] ?? (review.orderComments ?? ''),
      [this.reviewSideNoteKey(review, 'company')]: drafts[this.reviewSideNoteKey(review, 'company')] ?? (review.commentCompany ?? '')
    }));
  }

  reviewNoteValue(review: OrderReviewItem): string {
    return this.reviewNoteDrafts()[review.id] ?? review.comment ?? '';
  }

  setReviewNoteDraft(review: OrderReviewItem, value: string): void {
    this.reviewNoteDrafts.update((drafts) => ({ ...drafts, [review.id]: value }));
  }

  isReviewNoteChanged(review: OrderReviewItem): boolean {
    return this.reviewNoteValue(review) !== (review.comment ?? '');
  }

  reviewSideNoteValue(review: OrderReviewItem, field: ReviewSideNoteField): string {
    return this.reviewSideNoteDrafts()[this.reviewSideNoteKey(review, field)]
      ?? (field === 'order' ? review.orderComments ?? '' : review.commentCompany ?? '');
  }

  setReviewSideNoteDraft(review: OrderReviewItem, field: ReviewSideNoteField, value: string): void {
    this.reviewSideNoteDrafts.update((drafts) => ({
      ...drafts,
      [this.reviewSideNoteKey(review, field)]: value
    }));
  }

  isReviewSideNoteChanged(review: OrderReviewItem, field: ReviewSideNoteField): boolean {
    return this.reviewSideNoteValue(review, field) !== (field === 'order' ? review.orderComments ?? '' : review.commentCompany ?? '');
  }

  isAnyReviewNoteChanged(review: OrderReviewItem): boolean {
    return this.isReviewNoteChanged(review)
      || this.isReviewSideNoteChanged(review, 'order')
      || this.isReviewSideNoteChanged(review, 'company');
  }

  reviewNotesMutationKey(review: OrderReviewItem): string {
    return `save-notes-${review.id}`;
  }

  async saveAllReviewNotes(review: OrderReviewItem): Promise<void> {
    const key = this.reviewNotesMutationKey(review);
    if (!this.isAnyReviewNoteChanged(review) || this.isMutating(key)) {
      return;
    }

    this.mutationKey.set(key);
    try {
      if (this.isReviewNoteChanged(review)) {
        const updatedReview = await firstValueFrom(
          this.api.updateManagerOrderReviewNote(review.orderId, review.id, this.reviewNoteValue(review))
        );
        this.applyUpdatedReview(updatedReview);
      }

      if (this.isReviewSideNoteChanged(review, 'order')) {
        const orderComments = this.reviewSideNoteValue(review, 'order');
        await firstValueFrom(this.api.updateManagerOrderNote(review.orderId, orderComments));
        this.patchOrderNote(review.orderId, orderComments);
      }

      if (this.isReviewSideNoteChanged(review, 'company')) {
        const companyComments = this.reviewSideNoteValue(review, 'company');
        await firstValueFrom(this.api.updateManagerOrderCompanyNote(review.orderId, companyComments));
        this.patchCompanyNote(review.companyId, companyComments);
      }

      this.clearReviewNoteDrafts(review.id);
      this.noteOpenId.set(null);
    } catch (err) {
      this.error.set(this.errorMessage(err, 'Не удалось сохранить заметки'));
    } finally {
      this.mutationKey.set(null);
    }
  }

  openReviewEdit(review: OrderReviewItem, initialField: ReviewEditableField | null = null): void {
    if (!this.details()?.canEditReviews) {
      this.error.set('Редактирование отзывов недоступно для этого заказа.');
      return;
    }

    if (this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading()) {
      return;
    }

    this.reviewEdit.set(review);
    this.reviewEditInitialField.set(initialField);
    this.reviewEditDraft.set(this.reviewEditDraftFromReview(review));
    this.reviewEditError.set(null);
    this.reviewEditUploading.set(false);

    if (initialField) {
      window.setTimeout(() => this.scrollReviewEditFieldIntoView(initialField), 120);
    }
  }

  closeReviewEdit(): void {
    if (this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading()) {
      return;
    }

    this.reviewEdit.set(null);
    this.reviewEditInitialField.set(null);
    this.reviewEditDraft.set(null);
    this.reviewEditError.set(null);
    this.reviewEditUploading.set(false);
  }

  setReviewEditField<K extends keyof ReviewUpdateRequest>(field: K, value: ReviewUpdateRequest[K]): void {
    this.reviewEditDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  emptyToNull(value: unknown): string | null {
    const text = String(value ?? '').trim();
    return text ? text : null;
  }

  canSaveReviewEdit(): boolean {
    const draft = this.reviewEditDraft();
    return Boolean(draft?.text.trim());
  }

  saveReviewEdit(): void {
    const review = this.reviewEdit();
    const draft = this.reviewEditDraft();
    if (!review || !draft || !this.canSaveReviewEdit()) {
      this.reviewEditError.set('Заполните текст отзыва.');
      return;
    }

    this.reviewEditSaving.set(true);
    this.reviewEditError.set(null);
    this.api.updateManagerOrderReview(review.orderId, review.id, this.normalizedReviewEditDraft(draft)).subscribe({
      next: (updatedReview) => {
        this.applyUpdatedReview(updatedReview);
        this.clearReviewDrafts(updatedReview.id);
        this.reviewEditSaving.set(false);
        this.closeReviewEdit();
      },
      error: (err) => {
        this.reviewEditError.set(this.errorMessage(err, 'Отзыв не сохранен.'));
        this.reviewEditSaving.set(false);
      }
    });
  }

  async deleteReviewEdit(): Promise<void> {
    const review = this.reviewEdit();
    if (!review || !this.details()?.canDeleteReviews) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Удалить отзыв',
      message: 'Удалить отзыв?',
      confirmText: 'Удалить',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.reviewEditDeleting.set(true);
    this.reviewEditError.set(null);
    this.api.deleteManagerOrderReview(review.orderId, review.id).subscribe({
      next: (details) => {
        this.details.set(details);
        this.activeReviewIndex.set(Math.min(this.activeReviewIndex(), Math.max(0, details.reviews.length - 1)));
        this.clearReviewDrafts(review.id);
        this.reviewEditDeleting.set(false);
        this.closeReviewEdit();
      },
      error: (err) => {
        this.reviewEditError.set(this.errorMessage(err, 'Отзыв не удален.'));
        this.reviewEditDeleting.set(false);
      }
    });
  }

  uploadReviewPhoto(event: Event): void {
    const review = this.reviewEdit();
    const input = event.target as HTMLInputElement | null;
    const file = input?.files?.[0];
    if (!review || !file || this.reviewEditUploading()) {
      return;
    }

    void this.uploadReviewPhotoFile(review, file, input);
  }

  async pickNativeReviewPhoto(event: Event): Promise<void> {
    if (!this.media.nativePhotoPickerAvailable || this.reviewEditUploading()) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    const review = this.reviewEdit();
    if (!review) {
      return;
    }

    const file = await this.media.pickImageFile(`review-${review.id}`);
    if (file) {
      await this.uploadReviewPhotoFile(review, file);
    }
  }

  private async uploadReviewPhotoFile(review: OrderReviewItem, file: File, input?: HTMLInputElement | null): Promise<void> {
    this.reviewEditUploading.set(true);
    this.reviewEditError.set(null);
    try {
      const preparedFile = await this.media.prepareImageFile(file, `review-${review.id}`);
      const updatedReview = await firstValueFrom(this.api.uploadManagerOrderReviewPhoto(review.orderId, review.id, preparedFile));
      this.applyUpdatedReview(updatedReview);
      this.reviewEdit.set(updatedReview);
      this.reviewEditDraft.update((draft) => draft ? {
        ...draft,
        url: updatedReview.url || updatedReview.urlPhoto || ''
      } : this.reviewEditDraftFromReview(updatedReview));
    } catch (err) {
      this.reviewEditError.set(this.errorMessage(err, 'Фото отзыва не загрузилось.'));
    } finally {
      this.reviewEditUploading.set(false);
      if (input) {
        input.value = '';
      }
    }
  }

  hasReviewNote(review: OrderReviewItem): boolean {
    return this.hasText(review.comment) || this.hasText(review.orderComments) || this.hasText(review.commentCompany);
  }

  needsReviewPhoto(review: OrderReviewItem): boolean {
    return !!review.productPhoto && !this.hasText(review.urlPhoto || review.url);
  }

  hasReviewPhoto(review: OrderReviewItem): boolean {
    return this.hasText(review.urlPhoto || review.url);
  }

  reviewPhotoUrl(review: OrderReviewItem): string {
    return (review.urlPhoto || review.url || '').trim();
  }

  productNeedsPhoto(productId: number | null): boolean {
    if (productId == null) {
      return false;
    }
    return !!this.details()?.products.find((product) => product.id === productId)?.photo;
  }

  async copyReviewField(review: OrderReviewItem, kind: ReviewCopyKind): Promise<void> {
    const values: Record<ReviewCopyKind, string> = {
      filialUrl: review.filialUrl,
      botLogin: review.botLogin,
      botPassword: review.botPassword,
      text: this.reviewFieldValue(review, 'text'),
      answer: this.reviewFieldValue(review, 'answer')
    };

    await this.copyText(values[kind] ?? '', `${review.id}-${kind}`);
  }

  changeBot(review: OrderReviewItem): void {
    if (this.isMutating(`bot-${review.id}`)) {
      return;
    }

    this.runReviewMutation(
      `bot-${review.id}`,
      () => this.api.changeManagerOrderReviewBot(review.orderId, review.id),
      'Не удалось сменить аккаунт'
    );
  }

  changeReviewText(review: OrderReviewItem): void {
    if (this.isMutating(`ai-text-${review.id}`)) {
      return;
    }

    this.runReviewMutation(
      `ai-text-${review.id}`,
      () => this.api.changeManagerOrderReviewText(review.orderId, review.id),
      'Не удалось изменить текст отзыва'
    );
  }

  assignReviewNewAccount(review: OrderReviewItem): void {
    if (this.isMutating(`new-account-${review.id}`)) {
      return;
    }

    this.runReviewMutation(
      `new-account-${review.id}`,
      () => this.api.assignManagerOrderReviewNewAccount(review.orderId, review.id),
      'Не удалось назначить новый аккаунт'
    );
  }

  async deactivateBot(review: OrderReviewItem): Promise<void> {
    if (!review.botId || this.isMutating(`block-${review.id}`)) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Заменить аккаунт',
      message: `Заблокировать аккаунт "${this.botLabel(review)}" и заменить его?`,
      confirmText: 'Заменить',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.runReviewMutation(
      `block-${review.id}`,
      () => this.api.deactivateManagerOrderReviewBot(review.orderId, review.id, review.botId!),
      'Не удалось заблокировать аккаунт'
    );
  }

  publishReview(review: OrderReviewItem): void {
    const details = this.details();
    if (!details || !this.canShowPublishButton(details, review)) {
      return;
    }

    this.runDetailsMutation(
      `publish-${review.id}`,
      () => this.api.publishManagerOrderReview(review.orderId, review.id),
      'Не удалось опубликовать отзыв'
    );
  }

  showReviewAiAction(details: OrderDetailsPayload | null | undefined = this.details()): boolean {
    const status = (details?.status ?? '').trim().toLocaleLowerCase('ru-RU');
    return REVIEW_AI_ORDER_STATUSES.has(status);
  }

  reviewHelpActionLabel(review: OrderReviewItem): string {
    return this.isMutating(`review-help-${review.id}`) ? '...' : 'помощь';
  }

  reviewHelpActionTitle(): string {
    if (this.companyReportBusy()) {
      return 'Отчет о компании сейчас готовится';
    }

    if (!this.hasReadyCompanyReport()) {
      return 'Сначала собрать отчет о компании, потом подготовить текст отзыва';
    }

    return 'Подготовить и сохранить текст отзыва через AI-помощник';
  }

  createReviewHelpDraft(review: OrderReviewItem): void {
    const orderId = this.orderId();
    if (!orderId || this.busy()) {
      return;
    }

    if (this.companyReportBusy()) {
      this.error.set('Отчет о компании еще готовится. Дождитесь завершения и повторите AI.');
      this.companyReportVisible.set(true);
      return;
    }

    if (!this.hasReadyCompanyReport()) {
      this.error.set('Сначала нужен отчет о компании. Я открыл подготовку отчета, после готовности нажмите AI еще раз.');
      this.openCompanyReport();
      return;
    }

    const fieldKey = this.reviewFieldKey(review, 'text');
    this.runDetailsMutation(
      `review-help-${review.id}`,
      () => this.api.createManagerReviewHelpDraftForCard(orderId, review.id),
      'Не удалось подготовить AI-текст',
      () => {
        this.reviewFieldDrafts.update((drafts) => {
          const next = { ...drafts };
          delete next[fieldKey];
          return next;
        });
        if (this.editingFieldKey() === fieldKey) {
          this.editingFieldKey.set(null);
        }
        this.expandedReviewIds.update((items) => ({ ...items, [review.id]: true }));
      }
    );
  }

  recoveryTaskForReview(review: OrderReviewItem): ReviewRecoveryTaskItem | null {
    return this.recoveryTasks().find((task) =>
      task.sourceReviewId === review.id && task.statusCode !== 'CANCELLED'
    ) ?? null;
  }

  recoveryActionLabel(review: OrderReviewItem): string {
    if (this.isMutating(`recovery-create-${review.id}`)) {
      return '...';
    }

    const task = this.recoveryTaskForReview(review);
    if (!task) {
      return 'восстановить';
    }

    return task.statusCode === 'DONE' ? 'восстановлен' : 'в плане';
  }

  createRecoveryTask(review: OrderReviewItem): void {
    if (this.recoveryTaskForReview(review)) {
      return;
    }

    this.runDetailsMutation(
      `recovery-create-${review.id}`,
      () => this.api.createManagerReviewRecoveryTask(review.orderId, review.id),
      'Не удалось создать восстановление'
    );
  }

  canCancelBadReviewTask(details: OrderDetailsPayload, task: BadReviewTaskItem): boolean {
    return !this.isOnlyWorkerRole() && details.status !== 'Оплачено' && task.statusCode !== 'CANCELED';
  }

  canCompleteBadReviewTask(task: BadReviewTaskItem): boolean {
    return this.isOnlyWorkerRole() && task.statusCode === 'NEW';
  }

  badReviewTaskState(details: OrderDetailsPayload, task: BadReviewTaskItem): string {
    if (task.statusCode === 'CANCELED') {
      return 'Убрана из счета';
    }

    if (details.status === 'Оплачено') {
      return 'В истории оплаты';
    }

    return task.status || 'В работе';
  }

  badReviewTaskDateLabel(task: BadReviewTaskItem): string {
    const dates = [];
    if (task.scheduledDate) {
      dates.push(`план: ${task.scheduledDate}`);
    }
    if (task.completedDate) {
      dates.push(`сменил: ${task.completedDate}`);
    }
    return dates.join(' · ') || 'дата не указана';
  }

  canEditBadReviewTask(task: BadReviewTaskItem): boolean {
    return task.statusCode === 'NEW';
  }

  badReviewTaskDateValue(task: BadReviewTaskItem): string {
    return this.badReviewTaskDraft(task).scheduledDate ?? '';
  }

  badReviewTaskTextValue(task: BadReviewTaskItem): string {
    return this.badReviewTaskDraft(task).taskText;
  }

  setBadReviewTaskDate(task: BadReviewTaskItem, value: string): void {
    this.updateBadReviewTaskDraft(task, { scheduledDate: value || null });
  }

  setBadReviewTaskText(task: BadReviewTaskItem, value: string): void {
    this.updateBadReviewTaskDraft(task, { taskText: value });
  }

  isBadReviewTaskChanged(task: BadReviewTaskItem): boolean {
    const draft = this.badReviewTaskDrafts()[task.id];
    if (!draft) {
      return false;
    }

    return draft.taskText !== (task.taskText ?? '')
      || (draft.scheduledDate ?? '') !== (task.scheduledDate ?? '');
  }

  isBadReviewTaskSaved(task: BadReviewTaskItem): boolean {
    return this.savedBadReviewTaskId() === task.id;
  }

  saveBadReviewTask(task: BadReviewTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || !this.canEditBadReviewTask(task)) {
      return;
    }

    const draft = this.badReviewTaskDraft(task);
    if (!draft.taskText.trim() || !draft.scheduledDate) {
      this.error.set('Заполните текст и плановую дату плохой задачи.');
      return;
    }

    this.runDetailsMutation(
      `bad-task-save-${task.id}`,
      () => this.api.updateManagerBadReviewTask(orderId, task.id, {
        taskText: draft.taskText,
        scheduledDate: draft.scheduledDate
      }),
      'Не удалось сохранить плохую задачу',
      () => {
        this.clearBadReviewTaskDraft(task.id);
        this.savedBadReviewTaskId.set(task.id);
        window.setTimeout(() => {
          if (this.savedBadReviewTaskId() === task.id) {
            this.savedBadReviewTaskId.set(null);
          }
        }, 1400);
      }
    );
  }

  badReviewTaskCopyKey(task: BadReviewTaskItem, kind: BadReviewTaskCopyKind): string {
    return `bad-task-${task.id}-${kind}`;
  }

  async copyBadReviewTaskField(task: BadReviewTaskItem, kind: BadReviewTaskCopyKind): Promise<void> {
    const value = kind === 'botLogin' ? task.botLogin : task.botPassword;
    await this.copyText(value ?? '', this.badReviewTaskCopyKey(task, kind));
  }

  changeBadReviewTaskBot(task: BadReviewTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || !this.canEditBadReviewTask(task)) {
      return;
    }

    this.runDetailsMutation(
      `bad-task-change-bot-${task.id}`,
      () => this.api.changeManagerBadReviewTaskBot(orderId, task.id),
      'Не удалось сменить аккаунт плохой задачи'
    );
  }

  recoveryTaskCopyKey(task: ReviewRecoveryTaskItem, kind: RecoveryTaskCopyKind): string {
    return `recovery-task-${task.id}-${kind}`;
  }

  async copyRecoveryTaskField(task: ReviewRecoveryTaskItem, kind: RecoveryTaskCopyKind): Promise<void> {
    const value = kind === 'botLogin' ? task.botLogin : task.botPassword;
    await this.copyText(value ?? '', this.recoveryTaskCopyKey(task, kind));
  }

  recoveryTaskBotBrowserUrl(task: ReviewRecoveryTaskItem): string {
    return task.botId ? `/admin/dictionaries/accounts/${task.botId}/browser` : 'https://vk.com/';
  }

  changeRecoveryTaskBot(task: ReviewRecoveryTaskItem): void {
    if (!this.canEditRecoveryTask(task)) {
      return;
    }

    this.runRecoveryBotMutation(
      `recovery-change-bot-${task.id}`,
      () => this.api.changeWorkerRecoveryTaskBot(task.id),
      'Не удалось сменить аккаунт восстановления'
    );
  }

  async deactivateRecoveryTaskBot(task: ReviewRecoveryTaskItem): Promise<void> {
    if (!this.canEditRecoveryTask(task) || !task.botId) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Заменить аккаунт',
      message: `Заблокировать аккаунт "${task.botFio || task.botId}" и заменить в восстановлении?`,
      confirmText: 'Заменить',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.runRecoveryBotMutation(
      `recovery-block-bot-${task.id}`,
      () => this.api.deactivateWorkerRecoveryTaskBot(task.id, task.botId!),
      'Не удалось заблокировать аккаунт восстановления'
    );
  }

  async completeBadReviewTask(task: BadReviewTaskItem): Promise<void> {
    const orderId = this.orderId();
    if (!orderId || !this.canCompleteBadReviewTask(task)) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Отметить выполненной',
      message: `Плохая задача #${task.id} будет отмечена как "Сменил". После этого доплата попадет в сумму заказа, а менеджер получит уведомление для счета клиенту.`,
      confirmText: 'Сменил'
    });
    if (!confirmed) {
      return;
    }

    this.runDetailsMutation(
      `bad-task-complete-${task.id}`,
      () => this.api.completeManagerBadReviewTask(orderId, task.id),
      'Не удалось отметить плохую задачу'
    );
  }

  async cancelBadReviewTask(task: BadReviewTaskItem): Promise<void> {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Убрать из доплаты',
      message: `Убрать плохую задачу #${task.id} из доплаты?`,
      confirmText: 'Убрать',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.runDetailsMutation(
      `bad-task-cancel-${task.id}`,
      () => this.api.cancelManagerBadReviewTask(orderId, task.id),
      'Не удалось убрать плохую задачу из счета'
    );
  }

  canEditRecoveryTask(task: ReviewRecoveryTaskItem): boolean {
    return task.statusCode === 'PLANNED';
  }

  canDeleteRecoveryTask(task: ReviewRecoveryTaskItem): boolean {
    return this.canEditRecoveryTask(task)
      && (this.auth.hasRealmRole('ADMIN') || this.auth.hasRealmRole('OWNER') || this.auth.hasRealmRole('MANAGER'));
  }

  recoveryTaskTextValue(task: ReviewRecoveryTaskItem): string {
    return this.recoveryTaskDraft(task).recoveryText;
  }

  recoveryTaskAnswerValue(task: ReviewRecoveryTaskItem): string {
    return this.recoveryTaskDraft(task).recoveryAnswer;
  }

  recoveryTaskDateValue(task: ReviewRecoveryTaskItem): string {
    return this.recoveryTaskDraft(task).scheduledDate ?? '';
  }

  setRecoveryTaskText(task: ReviewRecoveryTaskItem, value: string): void {
    this.updateRecoveryTaskDraft(task, { recoveryText: value });
  }

  setRecoveryTaskAnswer(task: ReviewRecoveryTaskItem, value: string): void {
    this.updateRecoveryTaskDraft(task, { recoveryAnswer: value });
  }

  setRecoveryTaskDate(task: ReviewRecoveryTaskItem, value: string): void {
    this.updateRecoveryTaskDraft(task, { scheduledDate: value || null });
  }

  isRecoveryTaskChanged(task: ReviewRecoveryTaskItem): boolean {
    const draft = this.recoveryTaskDrafts()[task.id];
    if (!draft) {
      return false;
    }

    return draft.recoveryText !== (task.recoveryText ?? '')
      || draft.recoveryAnswer !== (task.recoveryAnswer ?? '')
      || (draft.scheduledDate ?? '') !== (task.scheduledDate ?? '');
  }

  isRecoveryTaskSaved(task: ReviewRecoveryTaskItem): boolean {
    return this.savedRecoveryTaskId() === task.id;
  }

  saveRecoveryTask(task: ReviewRecoveryTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || !this.canEditRecoveryTask(task)) {
      return;
    }

    const draft = this.recoveryTaskDraft(task);
    if (!draft.recoveryText.trim()) {
      this.error.set('Заполните текст восстановления.');
      return;
    }

    this.runDetailsMutation(
      `recovery-save-${task.id}`,
      () => this.api.updateManagerReviewRecoveryTask(orderId, task.id, {
        recoveryText: draft.recoveryText,
        recoveryAnswer: draft.recoveryAnswer,
        scheduledDate: draft.scheduledDate
      }),
      'Не удалось сохранить восстановление',
      () => {
        this.clearRecoveryTaskDraft(task.id);
        this.savedRecoveryTaskId.set(task.id);
        window.setTimeout(() => {
          if (this.savedRecoveryTaskId() === task.id) {
            this.savedRecoveryTaskId.set(null);
          }
        }, 1400);
      }
    );
  }

  async completeRecoveryTask(task: ReviewRecoveryTaskItem): Promise<void> {
    const orderId = this.orderId();
    if (!orderId || task.statusCode !== 'PLANNED') {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Отметить выполненным',
      message: `Восстановление #${task.id} будет отмечено выполненным. Если это последняя задача в пачке, менеджеру уйдет уведомление связаться с клиентом.`,
      confirmText: 'Готово'
    });
    if (!confirmed) {
      return;
    }

    this.runDetailsMutation(
      `recovery-complete-${task.id}`,
      () => this.api.completeManagerReviewRecoveryTask(orderId, task.id),
      'Не удалось отметить восстановление'
    );
  }

  async deleteRecoveryTask(task: ReviewRecoveryTaskItem): Promise<void> {
    const orderId = this.orderId();
    if (!orderId || !this.canDeleteRecoveryTask(task)) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Удалить восстановление',
      message: `Удалить задачу восстановления #${task.id}?`,
      confirmText: 'Удалить',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.runDetailsMutation(
      `recovery-delete-${task.id}`,
      () => this.api.deleteManagerReviewRecoveryTask(orderId, task.id),
      'Не удалось удалить восстановление',
      () => this.clearRecoveryTaskDraft(task.id)
    );
  }

  markRecoveryClientNotified(batch: ReviewRecoveryBatchItem): void {
    const orderId = this.orderId();
    if (!orderId || !batch.id) {
      return;
    }

    this.runDetailsMutation(
      `recovery-notified-${batch.id}`,
      () => this.api.markManagerRecoveryClientNotified(orderId, batch.id),
      'Не удалось отметить уведомление клиента',
      () => dispatchMobileRecoveryClientNotified({ orderId, batchId: batch.id })
    );
  }

  recoveryTaskDateLabel(task: ReviewRecoveryTaskItem): string {
    const dates = [];
    if (task.scheduledDate) {
      dates.push(`план: ${task.scheduledDate}`);
    }
    if (task.completedDate) {
      dates.push(`восстановил: ${task.completedDate}`);
    }
    return dates.join(' · ') || 'дата не указана';
  }

  addReview(): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    this.runDetailsMutation('add-review', () => this.api.addManagerOrderReview(orderId), 'Не удалось добавить отзыв');
  }

  createHelpDrafts(): void {
    const orderId = this.orderId();
    if (!orderId || this.busy()) {
      return;
    }

    this.runDetailsMutation('help-all', () => this.api.createManagerReviewHelpDrafts(orderId), 'Не удалось создать подсказки');
  }

  sendToCheck(): void {
    const orderId = this.orderId();
    if (!orderId || this.busy()) {
      return;
    }

    const blockReason = this.sendToCheckBlockReason();
    if (blockReason) {
      this.error.set(blockReason);
      return;
    }

    this.mutationKey.set('send-check');
    this.api.updateManagerOrderStatus(orderId, 'В проверку').subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.loadDetails();
      },
      error: (err) => {
        this.error.set(this.errorMessage(err, 'Не удалось отправить заказ на проверку'));
        this.mutationKey.set(null);
      }
    });
  }

  openReviewCheck(): void {
    const detailsId = (this.details()?.orderDetailsId ?? '').trim();
    if (!detailsId) {
      this.error.set('У заказа нет ссылки на проверку отзывов.');
      return;
    }

    void this.router.navigate(['/tabs/review-check', detailsId]);
  }

  openCompanyReport(): void {
    const orderId = this.orderId();
    if (!orderId || this.companyReportLoading()) {
      return;
    }

    this.companyReportVisible.set(true);
    this.companyReportLoading.set(true);
    this.companyReportError.set(null);
    this.api.getManagerOrderCompanyReport(orderId).subscribe({
      next: (state) => {
        this.companyReportState.set(state);
        this.companyReportLoading.set(false);
        if (!state.latestJob?.report && !state.activeJob && state.canStart) {
          this.startCompanyReport();
        }
      },
      error: (err) => {
        this.companyReportError.set(this.errorMessage(err, 'Не удалось проверить отчет о компании'));
        this.companyReportLoading.set(false);
      }
    });
  }

  closeCompanyReport(): void {
    this.companyReportVisible.set(false);
  }

  canRefreshCompanyReport(): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  }

  canShowPaymentLinkAction(): boolean {
    const status = this.tbankStatus();
    return !!this.details()
      && !!status?.managerUiEnabled
      && !!status.enabled
      && !!status.paymentLinksEnabled
      && !!status.applyConfirmedPayments
      && this.auth.hasRealmRole('ADMIN');
  }

  paymentLinkModeLabel(): string {
    const status = this.tbankStatus();
    if (!status) {
      return 'Счет';
    }
    if (!status.enabled) {
      return 'Тест-счет';
    }
    return status.applyConfirmedPayments ? 'Счет T-Bank' : 'Тест-счет';
  }

  createPaymentLink(): void {
    const orderId = this.orderId();
    if (!orderId || !this.canShowPaymentLinkAction() || this.isMutating('payment-link')) {
      return;
    }

    this.mutationKey.set('payment-link');
    this.error.set(null);
    this.api.createManagerOrderPaymentLink(orderId).subscribe({
      next: (response) => {
        this.paymentLink.set(response);
        this.mutationKey.set(null);
        void this.copyText(response.copyText || response.url, 'payment-link');
      },
      error: (err) => {
        this.error.set(this.errorMessage(err, 'Не удалось создать ссылку на оплату'));
        this.mutationKey.set(null);
      }
    });
  }

  private loadTbankStatus(): void {
    this.api.getTbankStatus().subscribe({
      next: (status) => this.tbankStatus.set(status),
      error: () => this.tbankStatus.set(null)
    });
  }

  refreshCompanyReport(): void {
    const orderId = this.orderId();
    if (!orderId || this.companyReportLoading()) {
      return;
    }

    if (!this.canRefreshCompanyReport()) {
      this.companyReportVisible.set(true);
      this.companyReportError.set('Обновлять отчет может только владелец или администратор.');
      return;
    }

    this.companyReportVisible.set(true);
    this.companyReportLoading.set(true);
    this.companyReportError.set(null);
    this.api.refreshManagerOrderCompanyReport(orderId).subscribe({
      next: (state) => {
        this.companyReportState.set(state);
        this.companyReportLoading.set(false);
      },
      error: (err) => {
        this.companyReportError.set(this.errorMessage(err, 'Не удалось обновить отчет о компании'));
        this.companyReportLoading.set(false);
      }
    });
  }

  companyReportStatus(): string {
    const state = this.companyReportState();
    if (!state) {
      return 'Отчет еще не проверялся.';
    }
    if (state.activeJob) {
      return 'Отчет готовится в фоне. Можно продолжать работать с отзывами.';
    }
    if (state.latestJob?.report) {
      return 'Отчет о компании готов.';
    }
    return state.unavailableReason || 'Готового отчета пока нет.';
  }

  companyReportCompletedAt(): string {
    const value = this.companyReportState()?.latestJob?.completedAt
      ?? this.companyReport()?.createdAt
      ?? this.companyReportState()?.latestJob?.updatedAt
      ?? '';
    if (!value) {
      return '';
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return String(value).slice(0, 16).replace('T', ' ');
    }

    return new Intl.DateTimeFormat('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      year: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    }).format(date);
  }

  canShowPublishButton(details: OrderDetailsPayload, review: OrderReviewItem): boolean {
    return review.publish || !HIDDEN_PUBLISH_ORDER_STATUSES.has((details.status ?? '').trim());
  }

  botLabel(review: OrderReviewItem): string {
    if (this.hasUnavailableBot(review)) {
      return 'нет доступных аккаунтов';
    }

    const counter = review.botCounter ? ` ${review.botCounter}` : '';
    return `${review.botFio || 'Аккаунт не назначен'}${counter}`;
  }

  hasUnavailableBot(review: OrderReviewItem): boolean {
    const bot = (review.botFio ?? '').trim().toLocaleLowerCase('ru-RU');
    return bot === 'нет доступных аккаунтов' || this.hasCompactBotPrompt(review);
  }

  hasCompactBotPrompt(review: OrderReviewItem): boolean {
    return (review.botFio ?? '').trim().toLocaleLowerCase('ru-RU') === 'добавьте аккаунты и нажмите сменить';
  }

  money(value?: number): string {
    return `${new Intl.NumberFormat('ru-RU').format(value ?? 0)} руб.`;
  }

  dateText(value?: string | null): string {
    return (value ?? '').trim() || '-';
  }

  guardLink(event: Event, url?: string | null): void {
    if ((url ?? '').trim()) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
  }

  isMutating(key: string): boolean {
    return this.mutationKey() === key;
  }

  busy(): boolean {
    return this.mutationKey() !== null;
  }

  private reviewEditDraftFromReview(review: OrderReviewItem): ReviewUpdateRequest {
    return {
      text: review.text ?? '',
      answer: review.answer ?? '',
      comment: review.comment ?? '',
      created: review.created || null,
      changed: review.changed || null,
      publishedDate: review.publishedDate || null,
      publish: !!review.publish,
      vigul: !!review.vigul,
      botName: review.botFio ?? '',
      botPassword: review.botPassword ?? '',
      productId: review.productId ?? null,
      url: review.url || review.urlPhoto || ''
    };
  }

  private scrollReviewEditFieldIntoView(field: ReviewEditableField): void {
    const fieldName = field === 'answer' ? 'reviewEditAnswer' : 'reviewEditText';
    const element = document.querySelector<HTMLElement>(`.review-edit-sheet [name="${fieldName}"]`);
    element?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  private focusInlineReviewText(reviewId: number): void {
    const element = document.querySelector<HTMLTextAreaElement>(`textarea[data-review-textarea="${reviewId}"]`);
    if (!element) {
      return;
    }

    element.focus({ preventScroll: true });
    const position = element.value.length;
    element.setSelectionRange(position, position);
    element.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
  }

  private focusReviewTextEditor(): void {
    const element = document.querySelector<HTMLTextAreaElement>('.review-text-edit-sheet textarea[name="reviewTextFullEditor"]');
    if (!element) {
      return;
    }

    element.focus({ preventScroll: true });
    const position = element.value.length;
    element.setSelectionRange(position, position);
  }

  private blurActiveControl(): void {
    const element = document.activeElement;
    if (element instanceof HTMLElement) {
      element.blur();
    }
  }

  private normalizedReviewEditDraft(draft: ReviewUpdateRequest): ReviewUpdateRequest {
    return {
      ...draft,
      text: draft.text.trim(),
      answer: draft.answer.trim(),
      comment: draft.comment.trim(),
      created: this.emptyToNull(draft.created),
      changed: this.emptyToNull(draft.changed),
      publishedDate: this.emptyToNull(draft.publishedDate),
      botName: draft.botName.trim(),
      botPassword: draft.botPassword.trim(),
      url: draft.url.trim()
    };
  }

  private clearReviewDrafts(reviewId: number): void {
    this.reviewFieldDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[`${reviewId}-text`];
      delete next[`${reviewId}-answer`];
      return next;
    });
    this.clearReviewNoteDrafts(reviewId);
    if (this.noteOpenId() === reviewId) {
      this.noteOpenId.set(null);
    }
    if (this.editingFieldKey()?.startsWith(`${reviewId}-`)) {
      this.editingFieldKey.set(null);
    }
  }

  private badReviewTaskDraft(task: BadReviewTaskItem): BadReviewTaskDraft {
    return this.badReviewTaskDrafts()[task.id] ?? {
      taskText: task.taskText ?? '',
      scheduledDate: this.dateInputValue(task.scheduledDate)
    };
  }

  private updateBadReviewTaskDraft(task: BadReviewTaskItem, patch: Partial<BadReviewTaskDraft>): void {
    const next = { ...this.badReviewTaskDraft(task), ...patch };
    this.badReviewTaskDrafts.update((drafts) => ({
      ...drafts,
      [task.id]: next
    }));
  }

  private clearBadReviewTaskDraft(taskId: number): void {
    this.badReviewTaskDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[taskId];
      return next;
    });
  }

  private recoveryTaskDraft(task: ReviewRecoveryTaskItem): RecoveryTaskDraft {
    return this.recoveryTaskDrafts()[task.id] ?? {
      recoveryText: task.recoveryText ?? '',
      recoveryAnswer: task.recoveryAnswer ?? '',
      scheduledDate: this.dateInputValue(task.scheduledDate)
    };
  }

  private updateRecoveryTaskDraft(task: ReviewRecoveryTaskItem, patch: Partial<RecoveryTaskDraft>): void {
    const next = { ...this.recoveryTaskDraft(task), ...patch };
    this.recoveryTaskDrafts.update((drafts) => ({
      ...drafts,
      [task.id]: next
    }));
  }

  private clearRecoveryTaskDraft(taskId: number): void {
    this.recoveryTaskDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[taskId];
      return next;
    });
  }

  private dateInputValue(value?: string | null): string | null {
    const text = (value ?? '').trim();
    return text ? text.slice(0, 10) : null;
  }

  private isOnlyWorkerRole(): boolean {
    return this.auth.hasAnyRealmRole(['WORKER']) && !this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
  }

  private clampActiveCardIndex(): void {
    const max = Math.max(0, this.totalDetailCards() - 1);
    if (this.activeReviewIndex() > max) {
      this.activeReviewIndex.set(max);
    }
  }

  startCompanyReport(): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    this.companyReportLoading.set(true);
    this.api.startManagerOrderCompanyReport(orderId).subscribe({
      next: (state) => {
        this.companyReportState.set(state);
        this.companyReportLoading.set(false);
      },
      error: (err) => {
        this.companyReportError.set(this.errorMessage(err, 'Не удалось запустить отчет о компании'));
        this.companyReportLoading.set(false);
      }
    });
  }

  private buildCompanyReportSections(report: CompanyReport | null): CompanyReportSection[] {
    if (!report) {
      return [];
    }

    const explicitSections = (report.sections ?? [])
      .map((section) => ({
        title: this.cleanText(section.title),
        body: this.cleanReportBody(section.body)
      }))
      .filter((section) => section.body)
      .map((section, index) => this.createCompanyReportSection(section.title || `Раздел ${index + 1}`, section.body, index));
    if (explicitSections.length) {
      return explicitSections;
    }

    return this.sectionsFromMarkdown(report.reportMarkdown);
  }

  private sectionsFromMarkdown(markdown?: string | null): CompanyReportSection[] {
    const text = this.cleanReportBody(markdown);
    if (!text) {
      return [];
    }

    const sections: CompanyReportSection[] = [];
    let title = 'Кратко';
    let body: string[] = [];
    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.trimEnd();
      const heading = line.match(/^#{1,4}\s+(.+)$/);
      if (heading) {
        this.pushCompanyReportSection(sections, title, body.join('\n'));
        title = this.cleanMarkdownText(heading[1]);
        body = [];
        continue;
      }
      body.push(line);
    }
    this.pushCompanyReportSection(sections, title, body.join('\n'));

    return sections.length ? sections : [this.createCompanyReportSection('Отчет', text, 0)];
  }

  private pushCompanyReportSection(sections: CompanyReportSection[], title: string, body: string): void {
    const cleanBody = this.cleanReportBody(body);
    if (!cleanBody) {
      return;
    }

    sections.push(this.createCompanyReportSection(this.cleanText(title) || `Раздел ${sections.length + 1}`, cleanBody, sections.length));
  }

  private createCompanyReportSection(title: string, body: string, index: number): CompanyReportSection {
    const cleanBody = this.cleanReportBody(body);
    const cleanTitle = this.cleanText(title) || 'Раздел';
    return {
      id: `company-report-section-${index}-${this.companyReportSectionSlug(cleanTitle)}`,
      title: cleanTitle,
      body: cleanBody,
      html: this.renderCompanyReportMarkdown(cleanBody)
    };
  }

  private companyReportSectionSlug(title: string): string {
    return title
      .toLowerCase()
      .replace(/\s+/g, '-')
      .replace(/[^a-zа-яё0-9-]/g, '')
      .slice(0, 40) || 'section';
  }

  private renderCompanyReportMarkdown(markdown?: string | null): string {
    const text = this.cleanReportBody(markdown);
    if (!text) {
      return '';
    }

    const html: string[] = [];
    const paragraph: string[] = [];
    const listItems: string[] = [];
    const tableRows: string[][] = [];
    let listTag: 'ul' | 'ol' | null = null;

    const flushParagraph = () => {
      if (!paragraph.length) {
        return;
      }
      html.push(`<p>${this.renderInlineReportMarkdown(paragraph.join(' '))}</p>`);
      paragraph.length = 0;
    };

    const flushList = () => {
      if (!listTag || !listItems.length) {
        return;
      }
      html.push(`<${listTag}>${listItems.map((item) => `<li>${item}</li>`).join('')}</${listTag}>`);
      listItems.length = 0;
      listTag = null;
    };

    const flushTable = () => {
      if (!tableRows.length) {
        return;
      }

      const separatorIndex = tableRows.findIndex((row) => this.isMarkdownTableSeparator(row));
      const hasHeader = separatorIndex === 1 && tableRows.length > 2;
      const headerRow = hasHeader ? tableRows[0] : null;
      const bodyRows = tableRows
        .filter((row, index) => !this.isMarkdownTableSeparator(row) && (!hasHeader || index !== 0));

      if (!headerRow && bodyRows.length < 2) {
        paragraph.push(...tableRows.map((row) => row.join(' | ')));
      } else {
        const bodyHtml = bodyRows
          .map((row, rowIndex) => {
            const title = this.cleanText(row[0]) || `Строка ${rowIndex + 1}`;
            const tone = `tone-${(rowIndex % 4) + 1}`;
            const fieldsSource = row.length > 1 ? row.slice(1) : row;
            const labelsSource = row.length > 1 ? headerRow?.slice(1) : headerRow;
            const fields = fieldsSource
              .map((cell, index) => {
                const label = labelsSource?.[index] || `Поле ${index + 1}`;
                return `<div class="company-report-table-field"><span class="company-report-table-label">${this.renderInlineReportMarkdown(label)}</span><p class="company-report-table-value">${this.renderInlineReportMarkdown(cell || '-')}</p></div>`;
              })
              .join('');
            if (!fieldsSource.length) {
              return `<article class="company-report-table-card ${tone}"><h5>${this.renderInlineReportMarkdown(title)}</h5></article>`;
            }
            return `<article class="company-report-table-card ${tone}"><h5>${this.renderInlineReportMarkdown(title)}</h5>${fields}</article>`;
          })
          .join('');
        html.push(`<div class="company-report-table-stack">${bodyHtml}</div>`);
      }

      tableRows.length = 0;
    };

    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line) {
        flushParagraph();
        flushList();
        flushTable();
        continue;
      }

      if (this.isMarkdownTableLine(line)) {
        flushParagraph();
        flushList();
        tableRows.push(this.parseMarkdownTableRow(line));
        continue;
      }

      flushTable();

      const heading = line.match(/^#{2,5}\s+(.+)$/);
      if (heading) {
        flushParagraph();
        flushList();
        html.push(`<h4>${this.renderInlineReportMarkdown(heading[1])}</h4>`);
        continue;
      }

      if (this.isReportSubheadingLine(line)) {
        flushParagraph();
        flushList();
        html.push(`<h5 class="company-report-subheading">${this.renderInlineReportMarkdown(line.replace(/:$/, ''))}</h5>`);
        continue;
      }

      const unordered = line.match(/^[-*]\s+(.+)$/);
      if (unordered) {
        flushParagraph();
        if (listTag && listTag !== 'ul') {
          flushList();
        }
        listTag = 'ul';
        listItems.push(this.renderInlineReportMarkdown(unordered[1]));
        continue;
      }

      const ordered = line.match(/^\d+[.)]\s+(.+)$/);
      if (ordered) {
        flushParagraph();
        if (listTag && listTag !== 'ol') {
          flushList();
        }
        listTag = 'ol';
        listItems.push(this.renderInlineReportMarkdown(ordered[1]));
        continue;
      }

      flushList();
      paragraph.push(line);
    }

    flushParagraph();
    flushList();
    flushTable();

    return html.join('');
  }

  private renderInlineReportMarkdown(value?: string | null): string {
    return this.escapeHtml(this.cleanText(value))
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/__(.+?)__/g, '<strong>$1</strong>')
      .replace(/`([^`]+)`/g, '<code>$1</code>');
  }

  private parseMarkdownTableRow(line: string): string[] {
    return line
      .replace(/^\|/, '')
      .replace(/\|$/, '')
      .split('|')
      .map((cell) => this.cleanText(cell));
  }

  private isMarkdownTableLine(line: string): boolean {
    const trimmed = line.trim();
    return trimmed.startsWith('|') && trimmed.endsWith('|') && trimmed.includes('|');
  }

  private isMarkdownTableSeparator(row: string[]): boolean {
    return row.length > 0 && row.every((cell) => /^:?-{3,}:?$/.test(cell.replace(/\s/g, '')));
  }

  private isReportSubheadingLine(line: string): boolean {
    const text = this.cleanMarkdownText(line);
    return text.endsWith(':') && text.length <= 90 && !text.includes('|');
  }

  private escapeHtml(value: string): string {
    return value
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  private cleanReportBody(value?: string | null): string {
    return this.cleanText(value)
      .replace(/\r\n/g, '\n')
      .replace(/\n{3,}/g, '\n\n');
  }

  private cleanMarkdownText(value?: string | null): string {
    return this.cleanText(value)
      .replace(/\*\*(.*?)\*\*/g, '$1')
      .replace(/__(.*?)__/g, '$1')
      .replace(/`([^`]+)`/g, '$1');
  }

  private cleanStringList(values?: Array<string | null | undefined> | null): string[] {
    return (values ?? [])
      .map((value) => this.cleanText(value))
      .filter((value, index, items) => !!value && items.indexOf(value) === index);
  }

  private cleanText(value?: unknown): string {
    return typeof value === 'string' ? value.trim() : '';
  }

  private sendToCheckBlockReason(): string | null {
    if (this.hasPendingReviewFieldChanges()) {
      return 'Нельзя отправить на проверку: есть несохраненные правки в карточках отзывов.';
    }

    const invalid = this.details()?.reviews
      .filter((review) => this.isInvalidReviewText(this.reviewFieldSourceValue(review, 'text')))
      .length ?? 0;
    return invalid > 0 ? `Нельзя отправить на проверку: есть пустые отзывы или заготовка "${PLACEHOLDER_REVIEW_TEXT}" (${invalid}).` : null;
  }

  private hasPendingReviewFieldChanges(): boolean {
    const details = this.details();
    if (!details) {
      return false;
    }

    return Object.entries(this.reviewFieldDrafts()).some(([key, value]) => {
      const match = /^(\d+)-(text|answer)$/.exec(key);
      const review = match ? details.reviews.find((item) => item.id === Number(match[1])) : null;
      const field = match?.[2] as ReviewEditableField | undefined;
      return !!review && !!field && value !== this.reviewFieldSourceValue(review, field);
    });
  }

  private isInvalidReviewText(value: string): boolean {
    const text = value.trim().toLocaleLowerCase('ru-RU');
    return !text || text === PLACEHOLDER_REVIEW_TEXT;
  }

  private runDetailsMutation(
    key: string,
    request: () => ReturnType<ApiService['addManagerOrderReview']>,
    fallback: string,
    afterSuccess?: (details: OrderDetailsPayload) => void
  ): void {
    if (this.isMutating(key)) {
      return;
    }

    this.mutationKey.set(key);
    this.error.set(null);
    request().subscribe({
      next: (details) => {
        this.details.set(details);
        this.clampActiveCardIndex();
        afterSuccess?.(details);
        this.mutationKey.set(null);
      },
      error: (err) => {
        this.error.set(this.errorMessage(err, fallback));
        this.mutationKey.set(null);
      }
    });
  }

  private runReviewMutation(key: string, request: () => ReturnType<ApiService['changeManagerOrderReviewBot']>, fallback: string): void {
    this.mutationKey.set(key);
    this.error.set(null);
    request().subscribe({
      next: (review) => {
        this.applyUpdatedReview(review);
        this.mutationKey.set(null);
      },
      error: (err) => {
        this.error.set(this.errorMessage(err, fallback));
        this.mutationKey.set(null);
      }
    });
  }

  private runRecoveryBotMutation(
    key: string,
    request: () => Observable<unknown>,
    fallback: string
  ): void {
    if (this.isMutating(key)) {
      return;
    }

    this.mutationKey.set(key);
    this.error.set(null);
    request().subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.loadDetails();
      },
      error: (err) => {
        this.error.set(this.errorMessage(err, fallback));
        this.mutationKey.set(null);
      }
    });
  }

  private applyUpdatedReview(updatedReview: OrderReviewItem): void {
    this.details.update((details) => details ? {
      ...details,
      reviews: details.reviews.map((review) => review.id === updatedReview.id ? updatedReview : review)
    } : details);

    if (this.reviewEdit()?.id === updatedReview.id) {
      this.reviewEdit.set(updatedReview);
      this.reviewEditDraft.set(this.reviewEditDraftFromReview(updatedReview));
    }
  }

  private patchOrderNote(orderId: number, orderComments: string): void {
    this.details.update((details) => details ? {
      ...details,
      orderComments,
      reviews: details.reviews.map((review) => review.orderId === orderId ? { ...review, orderComments } : review)
    } : details);
  }

  private patchCompanyNote(companyId: number, companyComments: string): void {
    this.details.update((details) => details ? {
      ...details,
      companyComments,
      reviews: details.reviews.map((review) => review.companyId === companyId ? { ...review, commentCompany: companyComments } : review)
    } : details);
  }

  private reviewFieldKey(review: OrderReviewItem, field: ReviewEditableField): string {
    return `${review.id}-${field}`;
  }

  private reviewSideNoteKey(review: OrderReviewItem, field: ReviewSideNoteField): string {
    return `${review.id}-${field}`;
  }

  private clearReviewNoteDrafts(reviewId: number): void {
    this.reviewNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[reviewId];
      return next;
    });
    this.reviewSideNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[`${reviewId}-order`];
      delete next[`${reviewId}-company`];
      return next;
    });
  }

  private reviewFieldSourceValue(review: OrderReviewItem, field: ReviewEditableField): string {
    return field === 'text' ? review.text ?? '' : review.answer ?? '';
  }

  private hasText(value?: string | null): boolean {
    return !!(value ?? '').trim();
  }

  private async copyText(value: string, key: string): Promise<void> {
    const text = (value ?? '').trim();
    if (!text) {
      this.error.set('Нечего копировать.');
      return;
    }

    try {
      await navigator.clipboard.writeText(text);
      this.copiedKey.set(key);
      window.setTimeout(() => {
        if (this.copiedKey() === key) {
          this.copiedKey.set(null);
        }
      }, 1200);
    } catch {
      this.error.set('Не удалось скопировать текст.');
    }
  }

  private webAppUrl(path: string): string {
    const { hostname, protocol } = window.location;
    if (hostname === 'localhost' || hostname === '127.0.0.1') {
      return `${protocol}//${hostname}:8088${path}`;
    }

    return path;
  }

  private errorMessage(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const message = typeof err.error === 'string'
        ? err.error
        : err.error?.message || err.error?.error;
      return message || fallback;
    }

    return fallback;
  }
}
