import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  IonContent,
  IonModal,
  IonRefresher,
  IonRefresherContent,
  RefresherCustomEvent
} from '@ionic/angular/standalone';
import { Subscription, firstValueFrom } from 'rxjs';
import {
  ApiService,
  BadReviewTaskItem,
  CompanyDeepReportState,
  OrderDetailsPayload,
  OrderReviewItem,
  ReviewRecoveryBatchItem,
  ReviewRecoveryTaskItem,
  ReviewUpdateRequest
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileRemindersComponent } from '../shared/mobile-reminders.component';

type ReviewCopyKind = 'filialUrl' | 'botLogin' | 'botPassword' | 'text' | 'answer';
type BadReviewTaskCopyKind = 'botLogin' | 'botPassword';
type ReviewEditableField = 'text' | 'answer';
type ReviewSideNoteField = 'order' | 'company';
type BadReviewTaskDraft = {
  taskText: string;
  scheduledDate: string | null;
};
type RecoveryTaskDraft = {
  recoveryText: string;
  scheduledDate: string | null;
};

const HIDDEN_PUBLISH_ORDER_STATUSES = new Set(['Новый', 'На проверке', 'В проверку', 'В прверку', 'Коррекция']);
const REVIEW_HELP_ORDER_STATUSES = new Set(['новый']);
const PLACEHOLDER_REVIEW_TEXT = 'текст отзыва';

@Component({
  selector: 'app-order-details-mobile',
  imports: [FormsModule, IonContent, IonModal, IonRefresher, IonRefresherContent, MobileHeaderComponent, MobileRemindersComponent],
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
            @if (companyReportVisible()) {
              <section class="company-report-panel">
                <header>
                  <div>
                    <small>{{ companyReportState()?.companyName || details.companyTitle }}</small>
                    <h2>О компании</h2>
                  </div>
                  <button type="button" (click)="companyReportVisible.set(false)" aria-label="Закрыть отчет">
                    <span class="material-icons-sharp">close</span>
                  </button>
                </header>
                @if (companyReportLoading()) {
                  <p>Отчет проверяется...</p>
                } @else if (companyReportError()) {
                  <p class="error">{{ companyReportError() }}</p>
                } @else {
                  <p>{{ companyReportStatus() }}</p>
                }
              </section>
            }

            <section
              class="review-mobile-strip"
              [class.review-mobile-strip--expanded]="reviewsExpanded()"
              aria-label="Отзывы заказа"
              (scroll)="onReviewScroll($event)"
            >
              @for (review of details.reviews; track review.id; let index = $index) {
                <article
                  class="mobile-review-card"
                  [class.published]="review.publish"
                  [class.active]="activeReviewIndex() === index"
                  [attr.data-card-index]="index"
                  [attr.data-review-id]="review.id"
                >
                  <header>
                    <a [href]="review.filialUrl || '#'" target="_blank" rel="noopener" (click)="guardLink($event, review.filialUrl)">
                      {{ review.companyTitle || details.companyTitle || 'Компания' }}
                    </a>
                    <span>
                      @if (review.productPhoto) {
                        @if (hasReviewPhoto(review)) {
                          <a class="review-card-badge photo" [href]="reviewPhotoUrl(review)" target="_blank" rel="noopener" aria-label="Открыть фото отзыва">
                            <span class="material-icons-sharp">photo_camera</span>
                          </a>
                        } @else {
                          <button class="review-card-badge photo danger" type="button" (click)="openReviewEdit(review)" aria-label="Нужно фото">
                            <span class="material-icons-sharp">photo_camera</span>
                          </button>
                        }
                      } @else if (needsReviewPhoto(review)) {
                        <button class="review-card-badge photo danger" type="button" (click)="openReviewEdit(review)" aria-label="Нужно фото">
                          <span class="material-icons-sharp">photo_camera</span>
                        </button>
                      }
                      @if (hasReviewNote(review)) {
                        <button class="review-card-badge" type="button" (click)="toggleReviewNote(review)" aria-label="Заметки">
                          <span class="material-icons-sharp">priority_high</span>
                        </button>
                      }
                      <small>#{{ review.id }}</small>
                    </span>
                  </header>

                  <section
                    class="review-text-editor"
                    [class.open]="isReviewTextOpen(review)"
                    [class.editing]="isReviewFieldEditing(review, 'text')"
                  >
                    @if (isReviewFieldEditing(review, 'text')) {
                      <textarea
                        [name]="'text-' + review.id"
                        [ngModel]="reviewFieldValue(review, 'text')"
                        (ngModelChange)="setReviewFieldDraft(review, 'text', $event)"
                        placeholder="Текст отзыва"
                      ></textarea>
                      <div class="field-actions">
                        <button type="button" (click)="cancelReviewFieldEdit(review, 'text')" [disabled]="isMutating(saveFieldMutationKey(review, 'text'))">Отмена</button>
                        <button type="button" class="save" (click)="saveReviewField(review, 'text')" [disabled]="!canSaveReviewField(review, 'text')">
                          {{ isMutating(saveFieldMutationKey(review, 'text')) ? '...' : 'Сохранить' }}
                        </button>
                      </div>
                    } @else {
                      <button
                        class="review-display-field"
                        type="button"
                        [class.empty]="!reviewFieldValue(review, 'text').trim()"
                        (click)="startReviewFieldEdit(review, 'text')"
                      >
                        {{ reviewFieldValue(review, 'text').trim() || 'Текст отзыва' }}
                      </button>
                      @if (shouldShowTextToggle(review)) {
                        <button class="text-toggle" type="button" (click)="toggleReviewText(review)">
                          {{ isReviewTextOpen(review) ? 'свернуть' : 'развернуть' }}
                        </button>
                      }
                    }
                  </section>

                  <section class="review-answer-editor" [class.editing]="isReviewFieldEditing(review, 'answer')">
                    @if (isReviewFieldEditing(review, 'answer')) {
                      <textarea
                        [name]="'answer-' + review.id"
                        [ngModel]="reviewFieldValue(review, 'answer')"
                        (ngModelChange)="setReviewFieldDraft(review, 'answer', $event)"
                        placeholder="Ответ на отзыв или замечание"
                      ></textarea>
                      <div class="field-actions">
                        <button type="button" (click)="cancelReviewFieldEdit(review, 'answer')" [disabled]="isMutating(saveFieldMutationKey(review, 'answer'))">Отмена</button>
                        <button type="button" class="save" (click)="saveReviewField(review, 'answer')" [disabled]="!canSaveReviewField(review, 'answer')">
                          {{ isMutating(saveFieldMutationKey(review, 'answer')) ? '...' : 'Сохранить' }}
                        </button>
                      </div>
                    } @else {
                      <button
                        class="review-display-field review-display-field--answer"
                        type="button"
                        [class.empty]="!reviewFieldValue(review, 'answer').trim()"
                        (click)="startReviewFieldEdit(review, 'answer')"
                      >
                        {{ reviewFieldValue(review, 'answer').trim() || 'Ответ на отзыв или замечание' }}
                      </button>
                    }
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
                      <div class="field-actions">
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
                    @if (showReviewHelpAction(details)) {
                      <button class="help" type="button" (click)="createReviewHelpDraft(review)" [disabled]="busy() || companyReportLoading()">
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

                  <footer>
                    <span>{{ review.publishedDate || 'Не назначено' }}</span>
                    <button class="review-edit-link" type="button" (click)="openReviewEdit(review)" [disabled]="!details.canEditReviews">
                      {{ review.workerFio || 'специалист' }}
                    </button>
                  </footer>
                </article>
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
                    <span>+{{ money(task.price || 0) }}</span>
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
                    <span>{{ task.workerFio || 'не назначен' }} · {{ task.botFio || '-' }}</span>
                    @if (canCompleteBadReviewTask(task)) {
                      <button class="review-edit-link" type="button" (click)="completeBadReviewTask(task)" [disabled]="isMutating('bad-task-complete-' + task.id)">
                        {{ isMutating('bad-task-complete-' + task.id) ? '...' : 'сменил' }}
                      </button>
                    } @else if (canCancelBadReviewTask(details, task)) {
                      <button class="review-edit-link danger-link" type="button" (click)="cancelBadReviewTask(task)" [disabled]="isMutating('bad-task-cancel-' + task.id)">
                        {{ isMutating('bad-task-cancel-' + task.id) ? '...' : 'убрать' }}
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

                  <div class="review-actions task-actions">
                    <button type="button" class="save" (click)="saveRecoveryTask(task)" [disabled]="!canEditRecoveryTask(task) || !isRecoveryTaskChanged(task) || isMutating('recovery-save-' + task.id)">
                      {{ isRecoveryTaskSaved(task) ? 'готово' : 'сохранить' }}
                    </button>
                    <button type="button" (click)="completeRecoveryTask(task)" [disabled]="!canEditRecoveryTask(task) || isMutating('recovery-complete-' + task.id)">
                      {{ isMutating('recovery-complete-' + task.id) ? '...' : 'восстановил' }}
                    </button>
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
              <button type="button" (click)="refreshCompanyReport()" [disabled]="companyReportLoading()">
                <span class="material-icons-sharp">refresh</span>
                Обновить отчет
              </button>
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

            <section class="lead-bottom-controls order-review-bottom-controls" aria-label="Навигация по отзывам">
              <button class="expand-list-button reminder-hero-button" type="button" (click)="reminders.open()" aria-label="Напоминания">
                <span class="material-icons-sharp">notifications_active</span>
                @if (reminders.activeReminderCount()) {
                  <small>{{ reminders.activeReminderCount() }}</small>
                }
              </button>
              <button
                class="expand-list-button"
                type="button"
                [class.active]="reviewsExpanded()"
                (click)="toggleReviewsExpanded()"
                [attr.aria-label]="reviewsExpanded() ? 'Свернуть список отзывов' : 'Развернуть список отзывов'"
              >
                <span class="material-icons-sharp">{{ reviewsExpanded() ? 'close_fullscreen' : 'open_in_full' }}</span>
              </button>
              <div class="lead-pager">
                <button type="button" (click)="previousReview()" [disabled]="activeReviewIndex() === 0 || totalDetailCards() <= 1" aria-label="Предыдущая карточка">
                  <span class="material-icons-sharp">chevron_left</span>
                </button>
                <span>{{ totalDetailCards() ? activeReviewIndex() + 1 : 0 }} / {{ totalDetailCards() }}</span>
                <button type="button" (click)="nextReview()" [disabled]="activeReviewIndex() >= totalDetailCards() - 1 || totalDetailCards() <= 1" aria-label="Следующая карточка">
                  <span class="material-icons-sharp">chevron_right</span>
                </button>
              </div>
            </section>
          }
        </main>

        <ion-modal class="sheet-modal review-edit-sheet" [isOpen]="reviewEdit() !== null" (didDismiss)="closeReviewEdit()">
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
                      <span>Текст отзыва</span>
                      <textarea
                        name="reviewEditText"
                        rows="7"
                        [ngModel]="draft.text"
                        (ngModelChange)="setReviewEditField('text', $event)"
                        [disabled]="reviewEditSaving() || reviewEditDeleting()"
                      ></textarea>
                    </label>

                    <label class="sheet-field">
                      <span>Ответ или замечание</span>
                      <textarea
                        name="reviewEditAnswer"
                        rows="3"
                        [ngModel]="draft.answer"
                        (ngModelChange)="setReviewEditField('answer', $event)"
                        [disabled]="reviewEditSaving() || reviewEditDeleting()"
                      ></textarea>
                    </label>

                    <label class="sheet-field">
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
                          <label class="photo-upload-button">
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

                    <label class="sheet-field">
                      <span>Опубликован</span>
                      <input
                        name="reviewEditPublishedDate"
                        type="text"
                        [ngModel]="draft.publishedDate"
                        (ngModelChange)="setReviewEditField('publishedDate', emptyToNull($event))"
                        [readonly]="!details()?.canEditReviewDates"
                        [disabled]="reviewEditSaving() || reviewEditDeleting()"
                      >
                    </label>

                    <label class="sheet-field sheet-field--inline">
                      <span>Опубликован</span>
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
      gap: 0.58rem;
      height: 100%;
      max-width: 48rem;
      margin: 0 auto;
      overflow: hidden;
      padding: 0.68rem 0.68rem calc(0.58rem + env(safe-area-inset-bottom));
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

    .review-mobile-strip {
      display: flex;
      gap: 0.62rem;
      flex: 1 1 0;
      min-height: 0;
      margin-inline: -0.68rem;
      overflow-x: auto;
      overflow-y: hidden;
      padding: 0 0.68rem 0.15rem;
      scroll-snap-type: x mandatory;
      scrollbar-width: none;
    }

    .review-mobile-strip::-webkit-scrollbar {
      display: none;
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
      gap: 0.55rem;
      flex: 0 0 min(15.4rem, 79vw);
      min-width: 0;
      height: 100%;
      max-height: 100%;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.9rem;
      padding: 0.65rem;
      overflow: hidden;
      background: linear-gradient(180deg, var(--otziv-tone-walk-surface) 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
      box-shadow: 0 0.55rem 1.2rem rgba(132, 139, 200, 0.14);
      scroll-snap-align: start;
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
      gap: 0.42rem;
      min-width: 0;
    }

    .review-text-editor {
      min-height: 0;
      grid-template-rows: minmax(0, 1fr) auto;
    }

    .review-text-editor.editing {
      gap: 0.22rem;
      grid-template-rows: minmax(0, 1fr) auto;
    }

    .review-text-editor textarea,
    .review-answer-editor textarea,
    .review-note-panel textarea,
    .review-display-field {
      box-sizing: border-box;
      width: 100%;
      min-width: 0;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.8rem;
      padding: 0.68rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font-family: var(--otziv-font-family);
      font-size: 0.78rem;
      font-weight: 800;
      line-height: 1.34;
      text-align: left;
    }

    .review-text-editor textarea,
    .review-text-editor .review-display-field {
      display: block;
      height: 100%;
      min-height: 12.2rem;
      overflow: hidden;
      resize: none;
      white-space: pre-wrap;
    }

    .review-text-editor.open textarea,
    .review-text-editor.open .review-display-field {
      height: 100%;
      overflow: auto;
    }

    .review-text-editor.editing textarea {
      height: 12.08rem;
      min-height: 12.08rem;
      overflow: auto;
    }

    .review-answer-editor textarea,
    .review-display-field--answer {
      height: 3.05rem;
      min-height: 3.05rem;
      padding: 0.52rem 0.64rem;
      overflow: hidden;
      resize: none;
      opacity: 0.56;
      font-size: 0.63rem;
      font-weight: 800;
      line-height: 1.22;
      text-align: center;
    }

    .review-answer-editor textarea:focus {
      opacity: 1;
    }

    .review-answer-editor.editing textarea {
      height: 2.9rem;
      min-height: 2.9rem;
      overflow: auto;
    }

    .review-display-field.empty {
      color: var(--otziv-info);
      font-weight: 700;
    }

    .text-toggle {
      position: absolute;
      right: 0.7rem;
      bottom: 0.7rem;
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
      gap: 0.5rem;
      align-items: center;
    }

    .field-actions button,
    .review-actions button,
    .publish-button,
    .order-details-actions button {
      min-height: 1.92rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font: inherit;
      font-size: 0.66rem;
      font-weight: 1000;
      line-height: 1;
    }

    .review-text-editor > .field-actions button,
    .review-answer-editor > .field-actions button {
      align-self: center;
      height: 1.92rem;
      min-height: 1.92rem;
      padding: 0 0.55rem;
      font-size: 0.62rem;
    }

    .field-actions .save,
    .order-details-actions .primary {
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.2) 0%, var(--otziv-white) 100%);
    }

    .bot-line {
      display: grid;
      min-height: 2.1rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.8rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font-family: var(--otziv-font-family);
      font-size: 0.76rem;
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
      gap: 0.45rem;
    }

    .review-actions .help {
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

    :host-context(body.otziv-dark-theme) .review-actions .help {
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
      min-height: 9.4rem;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.84rem;
      padding: 0.62rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font-family: var(--otziv-font-family);
      font-size: 0.74rem;
      font-weight: 800;
      line-height: 1.3;
      resize: none;
    }

    .task-text-field[readonly] {
      color: var(--otziv-info);
    }

    .task-actions .save {
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 100%);
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
  readonly reviewsExpanded = signal(false);
  readonly reviewEdit = signal<OrderReviewItem | null>(null);
  readonly reviewEditDraft = signal<ReviewUpdateRequest | null>(null);
  readonly reviewEditSaving = signal(false);
  readonly reviewEditDeleting = signal(false);
  readonly reviewEditUploading = signal(false);
  readonly reviewEditError = signal<string | null>(null);
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
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.routeSubscription = this.route.paramMap.subscribe((params) => {
      const companyId = Number(params.get('companyId'));
      const orderId = Number(params.get('orderId'));
      this.companyId.set(Number.isFinite(companyId) && companyId > 0 ? companyId : null);
      if (!Number.isFinite(orderId) || orderId <= 0) {
        this.error.set('Заказ не найден');
        return;
      }

      this.orderId.set(orderId);
      this.loadDetails();
    });
  }

  ngOnDestroy(): void {
    this.routeSubscription?.unsubscribe();
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
        this.activeReviewIndex.set(0);
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

    const left = container.getBoundingClientRect().left;
    let bestIndex = 0;
    let bestDistance = Number.POSITIVE_INFINITY;
    cards.forEach((card, index) => {
      const distance = Math.abs(card.getBoundingClientRect().left - left);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestIndex = index;
      }
    });
    this.activeReviewIndex.set(bestIndex);
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

  cancelReviewFieldEdit(review: OrderReviewItem, field: ReviewEditableField): void {
    const key = this.reviewFieldKey(review, field);
    this.editingFieldKey.set(null);
    this.reviewFieldDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[key];
      return next;
    });
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

  openReviewEdit(review: OrderReviewItem): void {
    if (!this.details()?.canEditReviews) {
      this.error.set('Редактирование отзывов недоступно для этого заказа.');
      return;
    }

    if (this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading()) {
      return;
    }

    this.reviewEdit.set(review);
    this.reviewEditDraft.set(this.reviewEditDraftFromReview(review));
    this.reviewEditError.set(null);
    this.reviewEditUploading.set(false);
  }

  closeReviewEdit(): void {
    if (this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading()) {
      return;
    }

    this.reviewEdit.set(null);
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

  deleteReviewEdit(): void {
    const review = this.reviewEdit();
    if (!review || !this.details()?.canDeleteReviews || !window.confirm('Удалить отзыв?')) {
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

    this.reviewEditUploading.set(true);
    this.reviewEditError.set(null);
    this.api.uploadManagerOrderReviewPhoto(review.orderId, review.id, file).subscribe({
      next: (updatedReview) => {
        this.applyUpdatedReview(updatedReview);
        this.reviewEdit.set(updatedReview);
        this.reviewEditDraft.update((draft) => draft ? {
          ...draft,
          url: updatedReview.url || updatedReview.urlPhoto || ''
        } : this.reviewEditDraftFromReview(updatedReview));
        this.reviewEditUploading.set(false);
        if (input) {
          input.value = '';
        }
      },
      error: (err) => {
        this.reviewEditError.set(this.errorMessage(err, 'Фото отзыва не загрузилось.'));
        this.reviewEditUploading.set(false);
        if (input) {
          input.value = '';
        }
      }
    });
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

  deactivateBot(review: OrderReviewItem): void {
    if (!review.botId || this.isMutating(`block-${review.id}`)) {
      return;
    }

    if (!window.confirm(`Заблокировать аккаунт "${this.botLabel(review)}" и заменить его?`)) {
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

  showReviewHelpAction(details: OrderDetailsPayload | null | undefined = this.details()): boolean {
    const status = (details?.status ?? '').trim().toLocaleLowerCase('ru-RU');
    return REVIEW_HELP_ORDER_STATUSES.has(status);
  }

  reviewHelpActionLabel(review: OrderReviewItem): string {
    return this.isMutating(`review-help-${review.id}`) ? '...' : 'помощь';
  }

  createReviewHelpDraft(review: OrderReviewItem): void {
    if (this.busy()) {
      return;
    }

    this.runDetailsMutation(
      `review-help-${review.id}`,
      () => this.api.createManagerReviewHelpDraftForCard(review.orderId, review.id),
      'Не удалось подготовить подсказку'
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

  completeBadReviewTask(task: BadReviewTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || !this.canCompleteBadReviewTask(task) || !window.confirm(`Отметить плохую задачу #${task.id} выполненной?`)) {
      return;
    }

    this.runDetailsMutation(
      `bad-task-complete-${task.id}`,
      () => this.api.completeManagerBadReviewTask(orderId, task.id),
      'Не удалось закрыть плохую задачу'
    );
  }

  cancelBadReviewTask(task: BadReviewTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || !window.confirm(`Убрать плохую задачу #${task.id} из доплаты?`)) {
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

  recoveryTaskTextValue(task: ReviewRecoveryTaskItem): string {
    return this.recoveryTaskDraft(task).recoveryText;
  }

  recoveryTaskDateValue(task: ReviewRecoveryTaskItem): string {
    return this.recoveryTaskDraft(task).scheduledDate ?? '';
  }

  setRecoveryTaskText(task: ReviewRecoveryTaskItem, value: string): void {
    this.updateRecoveryTaskDraft(task, { recoveryText: value });
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

  completeRecoveryTask(task: ReviewRecoveryTaskItem): void {
    const orderId = this.orderId();
    if (!orderId || task.statusCode !== 'PLANNED' || !window.confirm(`Отметить задачу восстановления #${task.id} выполненной?`)) {
      return;
    }

    this.runDetailsMutation(
      `recovery-complete-${task.id}`,
      () => this.api.completeManagerReviewRecoveryTask(orderId, task.id),
      'Не удалось закрыть восстановление'
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
      'Не удалось отметить уведомление клиента'
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

  refreshCompanyReport(): void {
    const orderId = this.orderId();
    if (!orderId || this.companyReportLoading()) {
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
      return 'Отчет о компании готов в веб-версии. Полный мобильный просмотр отчета добавим отдельным шагом.';
    }
    return state.unavailableReason || 'Готового отчета пока нет.';
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

  private startCompanyReport(): void {
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

  private applyUpdatedReview(updatedReview: OrderReviewItem): void {
    this.details.update((details) => details ? {
      ...details,
      reviews: details.reviews.map((review) => review.id === updatedReview.id ? updatedReview : review)
    } : details);
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
