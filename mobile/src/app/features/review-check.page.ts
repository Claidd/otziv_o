import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  IonContent,
  IonRefresher,
  IonRefresherContent,
  RefresherCustomEvent
} from '@ionic/angular/standalone';
import { Subscription, firstValueFrom } from 'rxjs';
import {
  ApiService,
  ReviewCheckNotes,
  ReviewCheckPayload,
  ReviewCheckReview,
  ReviewCheckUpdateRequest
} from '../core/api.service';
import { MobileBottomPagerComponent } from '../shared/mobile-bottom-pager.component';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileRemindersComponent } from '../shared/mobile-reminders.component';

type ReviewEditableField = 'text' | 'answer';
type ReviewCheckAction = 'load' | 'save' | 'approve' | 'correction' | 'send-check' | 'pay-ok';
type ReviewDraft = {
  text: string;
  answer: string;
  comment: string;
};

@Component({
  selector: 'app-review-check-mobile',
  imports: [FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileBottomPagerComponent, MobileHeaderComponent, MobileRemindersComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Проверка отзывов" />

      <ion-content fullscreen [scrollY]="false">
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <app-mobile-reminders #reminders />

        <main class="review-check-page">
          @if (loading() && !details()) {
            <div class="mobile-empty-state">
              <span class="material-icons-sharp">hourglass_top</span>
              <p>Загружаю проверку отзывов...</p>
            </div>
          }

          @if (error()) {
            <button class="mobile-error-card" type="button" (click)="loadReviewCheck()">
              <span class="material-icons-sharp">error</span>
              <strong>{{ error() }}</strong>
              <small>Нажмите, чтобы повторить</small>
            </button>
          }

          @if (statusMessage()) {
            <div class="mobile-status-card">
              <span class="material-icons-sharp">task_alt</span>
              <strong>{{ statusMessage() }}</strong>
            </div>
          }

          @if (details(); as details) {
            <section class="review-check-actions">
              @if (details.permissions.canOpenManagerLinks) {
                <button type="button" (click)="openCompany(details)">
                  <span class="material-icons-sharp">business</span>
                  О компании
                </button>
                <button type="button" (click)="openOrder(details)">
                  <span class="material-icons-sharp">inventory_2</span>
                  Заказ
                </button>
              }
              @if (details.permissions.canSendToCheck) {
                <button type="button" (click)="sendToCheck()" [disabled]="busy()">
                  <span class="material-icons-sharp">manage_search</span>
                  {{ isAction('send-check') ? '...' : 'На проверку' }}
                </button>
              }
              @if (details.permissions.canMarkPaid) {
                <button type="button" (click)="markPaid()" [disabled]="busy()">
                  <span class="material-icons-sharp">payments</span>
                  {{ isAction('pay-ok') ? '...' : 'Оплатили' }}
                </button>
              }
            </section>

            <section
              class="review-check-list"
              [class.review-check-list--expanded]="listExpanded()"
              aria-label="Отзывы на проверке"
              (scroll)="onReviewScroll($event)"
            >
              @for (review of details.reviews; track review.id; let index = $index) {
                <article
                  class="review-check-card"
                  [class.active]="activeReviewIndex() === index"
                  [class.published]="review.publish"
                  [attr.data-card-index]="index"
                >
                  <header>
                    <strong>{{ details.companyTitle || 'Компания' }}</strong>
                    <span>
                      @if (review.productPhoto) {
                        @if (reviewPhotoUrl(review)) {
                          <a class="review-card-badge photo" [href]="reviewPhotoUrl(review)" target="_blank" rel="noopener" aria-label="Открыть фото">
                            <span class="material-icons-sharp">photo_camera</span>
                          </a>
                        } @else {
                          <span class="review-card-badge photo missing" aria-label="Фото требуется">
                            <span class="material-icons-sharp">photo_camera</span>
                          </span>
                        }
                      }
                      @if (hasReviewNote(review, details)) {
                        <button class="review-card-badge note" type="button" (click)="toggleReviewNotes(review)" aria-label="Заметки">
                          <span aria-hidden="true">!</span>
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
                      <div class="field-actions mobile-keyboard-actions">
                        <button type="button" (click)="cancelReviewFieldEdit(review, 'text')" [disabled]="isMutating(fieldMutationKey(review, 'text'))">Отмена</button>
                        <button type="button" class="save" (click)="saveReviewField(review, 'text')" [disabled]="!canSaveReviewField(review, 'text')">
                          {{ isMutating(fieldMutationKey(review, 'text')) ? '...' : 'Сохранить' }}
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
                      <div class="field-actions mobile-keyboard-actions">
                        <button type="button" (click)="cancelReviewFieldEdit(review, 'answer')" [disabled]="isMutating(fieldMutationKey(review, 'answer'))">Отмена</button>
                        <button type="button" class="save" (click)="saveReviewField(review, 'answer')" [disabled]="!canSaveReviewField(review, 'answer')">
                          {{ isMutating(fieldMutationKey(review, 'answer')) ? '...' : 'Сохранить' }}
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

                  <div class="bot-line">
                    {{ botOrProductLabel(details, review) }}
                  </div>

                  @if (noteOpenId() === review.id) {
                    <section class="review-note-panel">
                      <label>
                        <span>Заметка отзыва</span>
                        <textarea
                          [ngModel]="reviewNoteValue(review)"
                          (ngModelChange)="setReviewNoteDraft(review, $event)"
                          [readonly]="!canEditNotes(details)"
                          placeholder="Заметка отзыва"
                        ></textarea>
                      </label>
                      <label>
                        <span>Заметка заказа</span>
                        <textarea
                          [ngModel]="orderNoteValue(details)"
                          (ngModelChange)="setOrderNoteDraft($event)"
                          [readonly]="!canEditNotes(details)"
                          placeholder="Заметка заказа"
                        ></textarea>
                      </label>
                      <label>
                        <span>Заметка компании</span>
                        <textarea
                          [ngModel]="companyNoteValue(details)"
                          (ngModelChange)="setCompanyNoteDraft($event)"
                          [readonly]="!canEditNotes(details)"
                          placeholder="Заметка компании"
                        ></textarea>
                      </label>
                      <div class="field-actions mobile-keyboard-actions">
                        <button type="button" (click)="toggleReviewNotes(review)" [disabled]="isMutating(notesMutationKey(review))">Закрыть</button>
                        <button type="button" class="save" (click)="saveReviewNotes(review)" [disabled]="!canEditNotes(details) || !hasChangedNotes(details, review) || isMutating(notesMutationKey(review))">
                          {{ isMutating(notesMutationKey(review)) ? '...' : 'Сохранить' }}
                        </button>
                      </div>
                    </section>
                  }

                  <footer>
                    <span>{{ reviewDate(review, details) }}</span>
                    <span>{{ reviewFooterStateLabel(review, details) }}</span>
                  </footer>
                </article>
              }

              @if (details.permissions.canApprovePublication) {
                <article
                  class="review-check-card review-approve-card"
                  [class.active]="isApproveCardActive(details)"
                  [class.approved]="isReviewWindowApproved(details)"
                  [attr.data-card-index]="approveCardIndex(details)"
                  aria-label="Разрешить публикацию"
                >
                  <button
                    type="button"
                    class="approve-publication-button"
                    [class.approved]="isReviewWindowApproved(details)"
                    (click)="approveReviews()"
                    [disabled]="busy() || isReviewWindowApproved(details)"
                    [attr.aria-label]="isReviewWindowApproved(details) ? 'Публикация разрешена' : 'Разрешить публикацию'"
                  >
                    @if (isReviewWindowApproved(details)) {
                      <span class="approve-card-title">Разрешена</span>
                      <span class="material-icons-sharp approve-card-icon">check_circle</span>
                      <span class="approve-card-footer">Публикация</span>
                    } @else {
                      <span class="approve-card-title">{{ isAction('approve') ? 'Разрешаю' : 'Нажмите' }}</span>
                      <span class="material-icons-sharp approve-card-icon">add_circle</span>
                      <span class="approve-card-helper">Чтобы</span>
                      <span class="approve-card-footer">Разрешить публикацию</span>
                    }
                  </button>
                </article>
              }

              @if (!details.reviews.length) {
                <div class="mobile-empty-state">
                  <span class="material-icons-sharp">rate_review</span>
                  <p>Отзывы для проверки не найдены.</p>
                </div>
              }
            </section>

            <section class="review-check-decision">
              @if (details.permissions.canApprovePublication) {
                <button
                  class="approve-button"
                  type="button"
                  [class.approved]="isReviewWindowApproved(details)"
                  (click)="approveReviews()"
                  [disabled]="busy() || isReviewWindowApproved(details)"
                >
                  {{ isReviewWindowApproved(details) ? 'Публикация разрешена' : isAction('approve') ? 'Разрешаю...' : 'Разрешить публикацию' }}
                </button>
              }

              <label>
                <span>На коррекцию</span>
                <textarea
                  rows="2"
                  [ngModel]="commentValue(details)"
                  (ngModelChange)="setComment($event)"
                  placeholder="Общие замечания по отзывам"
                ></textarea>
              </label>

              @if (details.permissions.canSendCorrection) {
                <button type="button" (click)="sendToCorrection()" [disabled]="busy()">
                  {{ isAction('correction') ? 'Отправляю...' : 'На коррекцию' }}
                </button>
              }
            </section>

            <app-mobile-bottom-pager
              class="mobile-page-bottom-pager"
              [pageIndex]="activeReviewIndex()"
              [totalPages]="reviewCardCount(details)"
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
                [class.active]="listExpanded()"
                (click)="toggleListExpanded()"
                [attr.aria-label]="listExpanded() ? 'Свернуть список отзывов' : 'Развернуть список отзывов'"
              >
                <span class="material-icons-sharp">{{ listExpanded() ? 'close_fullscreen' : 'open_in_full' }}</span>
              </button>
            </app-mobile-bottom-pager>
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content { --overflow: hidden; }

    .review-check-page {
      display: flex;
      flex-direction: column;
      gap: var(--otziv-page-gap, 0.46rem);
      height: 100%;
      max-width: 48rem;
      margin: 0 auto;
      overflow: hidden;
      padding: var(--otziv-page-padding-y, 0.55rem) var(--otziv-page-padding-x, 0.62rem) calc(var(--otziv-page-padding-bottom, 0.45rem) + env(safe-area-inset-bottom));
      font-family: var(--otziv-card-title-font);
    }

    .mobile-empty-state,
    .mobile-error-card,
    .mobile-status-card,
    .review-check-actions,
    .review-check-decision,
    .review-check-bottom-controls {
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.9rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 0.7rem 1.45rem rgba(132, 139, 200, 0.11);
    }

    .mobile-empty-state,
    .mobile-error-card,
    .mobile-status-card {
      display: grid;
      gap: 0.25rem;
      place-items: center;
      min-height: 7rem;
      padding: 1rem;
      color: var(--otziv-dark);
      text-align: center;
      font-family: var(--otziv-font-family);
    }

    .mobile-error-card {
      width: 100%;
      border-color: rgba(239, 68, 68, 0.32);
      color: var(--otziv-danger);
      background: rgba(239, 68, 68, 0.07);
      font: inherit;
    }

    .mobile-status-card {
      grid-template-columns: auto minmax(0, 1fr);
      min-height: 2.5rem;
      padding: 0.55rem 0.75rem;
      color: var(--otziv-success);
      text-align: left;
    }

    .review-check-actions {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--otziv-unified-card-gap, 0.32rem);
      padding: 0.4rem;
    }

    .review-check-actions button,
    .review-check-decision button,
    .field-actions button {
      display: inline-flex;
      min-width: 0;
      min-height: var(--otziv-card-control-height, 1.5rem);
      align-items: center;
      justify-content: center;
      gap: 0.22rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font: inherit;
      font-size: var(--otziv-card-control-font-size, 0.6rem);
      font-weight: 1000;
      line-height: 1;
      text-align: center;
    }

    .review-check-actions .material-icons-sharp {
      font-size: 0.92rem;
    }

    .review-check-decision label span,
    .review-note-panel label span {
      overflow: hidden;
      color: var(--otziv-info);
      font-family: var(--otziv-font-family);
      font-size: 0.62rem;
      font-weight: 900;
      line-height: 1.1;
      text-overflow: ellipsis;
      text-transform: uppercase;
      white-space: nowrap;
    }

    .review-check-list {
      display: flex;
      gap: var(--otziv-list-gap, 0.56rem);
      flex: 1 1 0;
      min-height: 0;
      margin-inline: calc(var(--otziv-page-padding-x, 0.62rem) * -1);
      overflow-x: auto;
      overflow-y: hidden;
      padding: 0 var(--otziv-page-padding-x, 0.62rem) 0.12rem;
      scroll-snap-type: x mandatory;
      scrollbar-width: none;
    }

    .review-check-list::-webkit-scrollbar {
      display: none;
    }

    .review-check-list--expanded {
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

    .review-check-card {
      display: flex;
      flex-direction: column;
      justify-content: flex-start;
      gap: var(--otziv-unified-card-gap, 0.3rem);
      flex: 0 0 var(--otziv-board-card-width, min(14.75rem, 78vw));
      min-width: 0;
      height: 100%;
      max-height: 100%;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.9rem;
      padding: var(--otziv-unified-card-padding, 0.46rem);
      overflow: hidden;
      background: linear-gradient(180deg, var(--otziv-tone-walk-surface) 0%, var(--otziv-white) 44%, var(--otziv-white) 100%);
      box-shadow: 0 0.55rem 1.2rem rgba(132, 139, 200, 0.14);
      scroll-snap-align: start;
    }

    .review-check-list--expanded .review-check-card {
      width: 100%;
      height: auto;
      max-height: none;
      flex: none;
      scroll-snap-align: none;
    }

    .review-check-card.active {
      border-color: rgba(244, 197, 66, 0.72);
      box-shadow: 0 0 0 0.14rem rgba(244, 197, 66, 0.18);
    }

    .review-check-card.published {
      border-color: var(--otziv-tone-success-border);
      background: linear-gradient(180deg, var(--otziv-tone-success-surface) 0%, var(--otziv-white) 44%, var(--otziv-white) 100%);
    }

    .review-approve-card {
      grid-template-rows: minmax(0, 1fr);
      align-items: stretch;
      gap: 0;
      border-color: rgba(240, 180, 41, 0.46);
      padding: 0;
      background:
        linear-gradient(180deg, rgba(255, 226, 117, 0.62) 0%, rgba(255, 253, 242, 0.94) 25%, var(--otziv-white) 50%, rgba(255, 253, 242, 0.94) 75%, rgba(255, 226, 117, 0.62) 100%);
      box-shadow: 0 0.85rem 1.7rem rgba(132, 139, 200, 0.2);
    }

    .review-approve-card.approved {
      border-color: rgba(17, 170, 78, 0.42);
      background:
        linear-gradient(180deg, rgba(61, 210, 99, 0.58) 0%, rgba(250, 255, 250, 0.95) 30%, var(--otziv-white) 50%, rgba(250, 255, 250, 0.95) 70%, rgba(61, 210, 99, 0.58) 100%);
    }

    .approve-publication-button {
      display: grid;
      width: 100%;
      height: 100%;
      min-height: 100%;
      align-content: center;
      justify-items: center;
      gap: 0.95rem;
      border: 0;
      border-radius: inherit;
      padding: 1.25rem 0.75rem;
      color: #111;
      background: transparent;
      box-shadow: none;
      font: inherit;
      line-height: 1.05;
      text-align: center;
      text-transform: uppercase;
      white-space: normal;
    }

    .approve-publication-button:disabled {
      opacity: 1;
    }

    .approve-card-title,
    .approve-card-footer {
      max-width: 13.5rem;
      overflow-wrap: anywhere;
      color: #111;
      font-size: clamp(2.2rem, 9vw, 2.75rem);
      font-weight: 400;
    }

    .approve-card-footer {
      max-width: 14.2rem;
      font-size: clamp(2.28rem, 9.35vw, 2.9rem);
    }

    .approve-card-helper {
      color: #111;
      font-size: clamp(1.3rem, 5.4vw, 1.72rem);
      font-weight: 400;
    }

    .approve-card-icon {
      color: #c6dc39;
      font-family: 'Material Icons Sharp';
      font-size: clamp(8.2rem, 36vw, 10.4rem);
      font-weight: 400;
      line-height: 0.86;
      filter: drop-shadow(0 0.15rem 0 rgba(16, 22, 18, 0.5));
    }

    .review-approve-card.approved .approve-card-icon {
      color: #0caf50;
      filter: none;
    }

    .review-check-card header,
    .review-check-card footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.32rem;
      flex: 0 0 auto;
      min-width: 0;
      min-height: 1.05rem;
    }

    .review-check-card header strong {
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: var(--otziv-unified-title-size, 0.9rem);
      font-weight: 1000;
      line-height: 1.04;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .review-check-card header span {
      display: inline-flex;
      align-items: center;
      gap: 0.28rem;
      flex: 0 0 auto;
    }

    .review-check-card small,
    .review-check-card footer span {
      color: var(--otziv-info);
      font-family: var(--otziv-font-family);
      font-size: var(--otziv-unified-subtitle-size, 0.56rem);
      font-weight: 900;
      line-height: 1;
    }

    .review-card-badge {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: var(--otziv-note-alert-size, 1.06rem);
      height: var(--otziv-note-alert-size, 1.06rem);
      min-width: var(--otziv-note-alert-size, 1.06rem);
      border: 0;
      border-radius: 999px;
      color: #4d3900;
      background: #f4c542;
      font: inherit;
      text-decoration: none;
    }

    .review-card-badge.note {
      background: #f4c542;
    }

    .review-card-badge.photo {
      width: var(--otziv-note-alert-size, 1.06rem);
      height: var(--otziv-note-alert-size, 1.06rem);
      min-width: var(--otziv-note-alert-size, 1.06rem);
      color: white;
      background: var(--otziv-success);
    }

    .review-card-badge.missing {
      color: white;
      background: var(--otziv-danger);
    }

    .review-card-badge .material-icons-sharp {
      font-size: 0.82rem;
      line-height: 1;
    }

    .review-card-badge.photo .material-icons-sharp {
      font-size: 0.76rem;
    }

    .review-text-editor,
    .review-answer-editor,
    .review-note-panel {
      position: relative;
      display: grid;
      gap: var(--otziv-unified-card-gap, 0.3rem);
      min-width: 0;
    }

    .review-text-editor {
      flex: 0 0 auto;
      min-height: 0;
      grid-template-rows: auto auto;
    }

    .review-text-editor.editing {
      gap: 0.34rem;
      grid-template-rows: minmax(0, 1fr) auto;
    }

    .review-text-editor textarea,
    .review-answer-editor textarea,
    .review-note-panel textarea,
    .review-display-field,
    .review-check-decision textarea {
      box-sizing: border-box;
      width: 100%;
      min-width: 0;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.74rem;
      padding: 0.44rem 0.52rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font-family: var(--otziv-font-family);
      font-size: var(--otziv-unified-field-font-size, 0.6rem);
      font-weight: 700;
      line-height: 1.18;
      text-align: left;
    }

    .review-text-editor textarea,
    .review-text-editor .review-display-field {
      font-weight: 650;
    }

    .review-text-editor textarea,
    .review-text-editor .review-display-field {
      display: -webkit-box;
      height: 6.15rem;
      min-height: 6.15rem;
      max-height: 6.15rem;
      overflow: hidden;
      padding-bottom: 0.46rem;
      resize: none;
      white-space: pre-wrap;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 5;
    }

    .review-text-editor.open textarea,
    .review-text-editor.open .review-display-field {
      height: 8.2rem;
      min-height: 8.2rem;
      max-height: 8.2rem;
      overflow: auto;
      -webkit-line-clamp: initial;
    }

    .review-text-editor.editing textarea {
      display: block;
      height: 7rem;
      min-height: 7rem;
      max-height: 7rem;
      overflow: auto;
      padding-bottom: 0.72rem;
    }

    .review-answer-editor textarea,
    .review-display-field--answer {
      display: -webkit-box;
      height: 2.28rem;
      min-height: 2.28rem;
      max-height: 2.28rem;
      padding: 0.34rem 0.48rem;
      overflow: hidden;
      resize: none;
      opacity: 0.78;
      font-size: 0.56rem;
      font-weight: 800;
      line-height: 1.12;
      text-align: center;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }

    .review-answer-editor.editing textarea {
      display: block;
      height: 3.1rem;
      min-height: 3.1rem;
      overflow: auto;
      opacity: 1;
    }

    .review-display-field.empty {
      color: var(--otziv-info);
      font-weight: 700;
    }

    .text-toggle {
      position: static;
      justify-self: end;
      min-height: 0;
      border: 0;
      color: var(--otziv-info);
      border-radius: 999px;
      margin-top: -0.16rem;
      padding: 0.05rem 0.28rem;
      background: rgba(255, 255, 255, 0.88);
      font-family: var(--otziv-font-family);
      font-size: 0.56rem;
      font-weight: 900;
      line-height: 1;
    }

    .field-actions {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: var(--otziv-unified-card-gap, 0.32rem);
      align-items: center;
      min-height: var(--otziv-card-control-height, 1.5rem);
    }

    .review-text-editor > .field-actions button,
    .review-answer-editor > .field-actions button {
      height: var(--otziv-card-control-height, 1.5rem);
      min-height: var(--otziv-card-control-height, 1.5rem);
      padding: 0 0.42rem;
      font-size: var(--otziv-card-control-font-size, 0.6rem);
    }

    .field-actions .save {
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.2) 0%, var(--otziv-white) 100%);
    }

    .bot-line {
      display: grid;
      flex: 0 0 auto;
      min-height: var(--otziv-card-control-height, 1.5rem);
      height: var(--otziv-card-control-height, 1.5rem);
      max-height: var(--otziv-card-control-height, 1.5rem);
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 999px;
      padding: 0 0.42rem;
      overflow: hidden;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font-family: var(--otziv-font-family);
      font-size: var(--otziv-card-control-font-size, 0.6rem);
      font-weight: 900;
      text-align: center;
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

    .review-note-panel label,
    .review-check-decision label {
      display: grid;
      gap: 0.28rem;
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

    .review-check-decision {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: var(--otziv-unified-card-gap, 0.32rem);
      padding: 0.48rem;
      font-family: var(--otziv-font-family);
    }

    .review-check-decision textarea {
      height: 2.45rem;
      min-height: 2.45rem;
      resize: none;
      font-size: 0.64rem;
    }

    .review-check-decision .approve-button {
      min-height: 1.9rem;
      border-color: #f0b429;
      color: #4f3700;
      background: #ffe17a;
      font-size: 0.78rem;
    }

    .review-check-decision .approve-button.approved {
      border-color: transparent;
      color: white;
      background: var(--otziv-success);
      opacity: 1;
    }

    .review-check-bottom-controls {
      display: flex;
      align-items: center;
      gap: 0.45rem;
      flex: 0 0 auto;
      min-height: 3rem;
      border-color: var(--otziv-tone-walk-border);
      border-radius: 0.9rem;
      padding: 0.48rem 0.62rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
    }

    .review-check-bottom-controls .expand-list-button,
    .review-check-bottom-controls .lead-pager button {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.15rem;
      min-width: 2.15rem;
      min-height: 2.15rem;
      border: 0;
      border-radius: 0.75rem;
      padding: 0 0.45rem;
      color: var(--otziv-primary);
      font: inherit;
      font-size: 0.68rem;
      font-weight: 900;
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 92%);
    }

    .review-check-bottom-controls .expand-list-button {
      flex: 0 0 2.5rem;
      min-width: 2.5rem;
      border: 1px solid rgba(108, 155, 207, 0.28);
      color: var(--otziv-dark);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 92%);
    }

    .review-check-bottom-controls .expand-list-button.active {
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }

    .review-check-bottom-controls .reminder-hero-button {
      position: relative;
    }

    .review-check-bottom-controls .reminder-hero-button small {
      position: absolute;
      top: -0.3rem;
      right: -0.22rem;
      display: grid;
      min-width: 1rem;
      height: 1rem;
      place-items: center;
      border-radius: 999px;
      padding: 0 0.25rem;
      color: var(--otziv-white);
      background: var(--otziv-danger);
      font-size: 0.56rem;
      font-weight: 900;
      line-height: 1;
    }

    .review-check-bottom-controls .lead-pager {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 0.28rem;
      flex: 1 1 auto;
      min-width: 0;
    }

    .review-check-bottom-controls .lead-pager > span {
      min-width: 3.2rem;
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 1000;
      text-align: center;
      white-space: nowrap;
    }

    .review-check-bottom-controls .lead-pager .material-icons-sharp {
      font-size: 1.05rem;
    }

    .review-check-bottom-controls .lead-pager button:disabled,
    .review-check-actions button:disabled,
    .review-check-decision button:disabled,
    .field-actions button:disabled {
      opacity: 0.48;
    }

    :host-context(body.otziv-dark-theme) .review-note-panel,
    :host-context(body.otziv-dark-theme) .review-note-panel .field-actions {
      background: #2b281d;
    }

    @media (max-width: 370px) {
      .review-check-actions {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }

      .review-check-card {
        flex-basis: min(14.35rem, 82vw);
      }

      .review-text-editor textarea,
      .review-text-editor .review-display-field {
        height: 5.7rem;
        min-height: 5.7rem;
        max-height: 5.7rem;
      }

      .review-text-editor.open textarea,
      .review-text-editor.open .review-display-field {
        height: 7.5rem;
        min-height: 7.5rem;
        max-height: 7.5rem;
      }
    }
  `]
})
export class ReviewCheckPage implements OnInit, OnDestroy {
  readonly orderDetailId = signal<string | null>(null);
  readonly details = signal<ReviewCheckPayload | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly statusMessage = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly drafts = signal<Record<number, ReviewDraft>>({});
  readonly commentDraft = signal('');
  readonly orderNoteDraft = signal('');
  readonly companyNoteDraft = signal('');
  readonly editingFieldKey = signal<string | null>(null);
  readonly expandedReviewId = signal<number | null>(null);
  readonly noteOpenId = signal<number | null>(null);
  readonly listExpanded = signal(false);
  readonly activeReviewIndex = signal(0);
  readonly busy = computed(() => this.mutationKey() !== null);

  private routeSubscription?: Subscription;

  constructor(
    private readonly api: ApiService,
    private readonly route: ActivatedRoute,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.routeSubscription = this.route.paramMap.subscribe((params) => {
      const id = params.get('orderDetailId');
      if (!id) {
        this.error.set('Ссылка на проверку некорректна.');
        return;
      }

      this.orderDetailId.set(id);
      this.loadReviewCheck();
    });
  }

  ngOnDestroy(): void {
    this.routeSubscription?.unsubscribe();
  }

  refresh(event: RefresherCustomEvent): void {
    this.loadReviewCheck(() => event.target.complete());
  }

  loadReviewCheck(done?: () => void): void {
    const orderDetailId = this.orderDetailId();
    if (!orderDetailId) {
      done?.();
      return;
    }

    this.loading.set(true);
    this.error.set(null);
    this.statusMessage.set(null);

    this.api.getReviewCheck(orderDetailId).subscribe({
      next: (details) => {
        this.applyDetails(details);
        this.activeReviewIndex.set(0);
        this.loading.set(false);
        done?.();
      },
      error: (err) => {
        this.error.set(this.errorMessage(err, 'Не удалось загрузить проверку отзывов.'));
        this.loading.set(false);
        done?.();
      }
    });
  }

  openCompany(details: ReviewCheckPayload): void {
    if (!details.companyId) {
      return;
    }

    void this.router.navigate(['/tabs/companies']);
  }

  openOrder(details: ReviewCheckPayload): void {
    if (!details.companyId || !details.orderId) {
      return;
    }

    void this.router.navigate(['/tabs/orders', details.companyId, details.orderId]);
  }

  approveReviews(): void {
    this.runAction('approve', 'Публикация разрешена', () =>
      this.api.approveReviewCheck(this.requiredOrderDetailId(), this.buildRequest())
    );
  }

  sendToCorrection(): void {
    this.runAction('correction', 'Отзывы отправлены на коррекцию', () =>
      this.api.sendReviewCheckToCorrection(this.requiredOrderDetailId(), this.buildRequest())
    );
  }

  sendToCheck(): void {
    this.runAction('send-check', 'Заказ отправлен на проверку', () =>
      this.api.sendReviewCheckToCheck(this.requiredOrderDetailId(), this.buildRequest())
    );
  }

  markPaid(): void {
    this.runAction('pay-ok', 'Оплата отмечена', () =>
      this.api.markReviewCheckPaid(this.requiredOrderDetailId())
    );
  }

  previousReview(): void {
    this.goToReview(this.activeReviewIndex() - 1);
  }

  nextReview(): void {
    this.goToReview(this.activeReviewIndex() + 1);
  }

  toggleListExpanded(): void {
    this.listExpanded.update((value) => !value);
    this.scrollActiveReview();
  }

  onReviewScroll(event: Event): void {
    if (this.listExpanded()) {
      return;
    }

    const container = event.currentTarget as HTMLElement | null;
    const card = container?.querySelector<HTMLElement>('.review-check-card');
    if (!container || !card) {
      return;
    }

    const styles = window.getComputedStyle(container);
    const gap = parseFloat(styles.columnGap || styles.gap || '0') || 0;
    const step = card.offsetWidth + gap;
    if (step <= 0) {
      return;
    }

    const details = this.details();
    const count = details ? this.reviewCardCount(details) : 0;
    const index = Math.max(0, Math.min(count - 1, Math.round(container.scrollLeft / step)));
    this.activeReviewIndex.set(index);
  }

  toggleReviewText(review: ReviewCheckReview): void {
    this.expandedReviewId.update((id) => id === review.id ? null : review.id);
  }

  isReviewTextOpen(review: ReviewCheckReview): boolean {
    return this.expandedReviewId() === review.id || this.isReviewFieldEditing(review, 'text');
  }

  shouldShowTextToggle(review: ReviewCheckReview): boolean {
    const text = this.reviewFieldValue(review, 'text');
    return text.length > 180 || text.split(/\r?\n/).length > 5;
  }

  startReviewFieldEdit(review: ReviewCheckReview, field: ReviewEditableField): void {
    if (!this.details()?.permissions.canSave || this.isMutating(this.fieldMutationKey(review, field))) {
      if (field === 'text') {
        this.toggleReviewText(review);
      }
      return;
    }

    this.editingFieldKey.set(this.reviewFieldKey(review, field));
    if (field === 'text') {
      this.expandedReviewId.set(review.id);
    }
  }

  cancelReviewFieldEdit(review: ReviewCheckReview, field: ReviewEditableField): void {
    if (this.isMutating(this.fieldMutationKey(review, field))) {
      return;
    }

    this.editingFieldKey.set(null);
    this.patchDraft(review.id, {
      [field]: field === 'text' ? review.text ?? '' : review.answer ?? ''
    });
  }

  saveReviewField(review: ReviewCheckReview, field: ReviewEditableField): void {
    const orderDetailId = this.orderDetailId();
    if (!orderDetailId || !this.canSaveReviewField(review, field)) {
      return;
    }

    const value = this.reviewFieldValue(review, field);
    const key = this.fieldMutationKey(review, field);
    this.mutationKey.set(key);
    this.error.set(null);
    this.statusMessage.set(null);

    const request = field === 'text'
      ? this.api.updateReviewCheckText(orderDetailId, review.id, value)
      : this.api.updateReviewCheckAnswer(orderDetailId, review.id, value);

    request.subscribe({
      next: (updatedReview) => {
        this.applyUpdatedReview(updatedReview);
        this.editingFieldKey.set(null);
        this.mutationKey.set(null);
        this.statusMessage.set(field === 'text' ? 'Текст отзыва сохранен' : 'Замечание сохранено');
      },
      error: (err) => {
        this.error.set(this.errorMessage(err, 'Не удалось сохранить отзыв.'));
        this.mutationKey.set(null);
      }
    });
  }

  isReviewFieldEditing(review: ReviewCheckReview, field: ReviewEditableField): boolean {
    return this.editingFieldKey() === this.reviewFieldKey(review, field);
  }

  canSaveReviewField(review: ReviewCheckReview, field: ReviewEditableField): boolean {
    if (!this.details()?.permissions.canSave || this.isMutating(this.fieldMutationKey(review, field))) {
      return false;
    }

    if (field === 'text' && !this.reviewFieldValue(review, field).trim()) {
      return false;
    }

    return this.reviewFieldValue(review, field) !== (field === 'text' ? review.text ?? '' : review.answer ?? '');
  }

  setReviewFieldDraft(review: ReviewCheckReview, field: ReviewEditableField, value: string): void {
    this.patchDraft(review.id, { [field]: value });
  }

  reviewFieldValue(review: ReviewCheckReview, field: ReviewEditableField): string {
    return this.reviewDraft(review)[field];
  }

  toggleReviewNotes(review: ReviewCheckReview): void {
    this.noteOpenId.update((id) => id === review.id ? null : review.id);
  }

  setReviewNoteDraft(review: ReviewCheckReview, value: string): void {
    this.patchDraft(review.id, { comment: value });
  }

  setOrderNoteDraft(value: string): void {
    this.orderNoteDraft.set(value);
  }

  setCompanyNoteDraft(value: string): void {
    this.companyNoteDraft.set(value);
  }

  async saveReviewNotes(review: ReviewCheckReview): Promise<void> {
    const details = this.details();
    const orderDetailId = this.orderDetailId();
    if (!details || !orderDetailId || !this.canEditNotes(details) || !this.hasChangedNotes(details, review)) {
      return;
    }

    const key = this.notesMutationKey(review);
    this.mutationKey.set(key);
    this.error.set(null);
    this.statusMessage.set(null);

    try {
      let updatedReview: ReviewCheckReview | null = null;
      let updatedNotes: ReviewCheckNotes | null = null;

      if (this.reviewNoteValue(review) !== (review.comment ?? '')) {
        updatedReview = await firstValueFrom(this.api.updateReviewCheckNote(orderDetailId, review.id, this.reviewNoteValue(review)));
      }

      if (this.orderNoteValue(details) !== (details.orderComments ?? '')) {
        updatedNotes = await firstValueFrom(this.api.updateReviewCheckOrderNote(orderDetailId, this.orderNoteValue(details)));
      }

      if (this.companyNoteValue(details) !== (details.companyComments ?? '')) {
        updatedNotes = await firstValueFrom(this.api.updateReviewCheckCompanyNote(orderDetailId, this.companyNoteValue(details)));
      }

      if (updatedReview) {
        this.applyUpdatedReview(updatedReview);
      }
      if (updatedNotes) {
        this.applyUpdatedNotes(updatedNotes);
      }

      this.noteOpenId.set(null);
      this.statusMessage.set('Заметки сохранены');
    } catch (err) {
      this.error.set(this.errorMessage(err, 'Не удалось сохранить заметки.'));
    } finally {
      this.mutationKey.set(null);
    }
  }

  reviewNoteValue(review: ReviewCheckReview): string {
    return this.reviewDraft(review).comment;
  }

  orderNoteValue(details: ReviewCheckPayload): string {
    return this.orderNoteDraft();
  }

  companyNoteValue(details: ReviewCheckPayload): string {
    return this.companyNoteDraft();
  }

  hasChangedNotes(details: ReviewCheckPayload, review: ReviewCheckReview): boolean {
    return this.reviewNoteValue(review) !== (review.comment ?? '')
      || this.orderNoteValue(details) !== (details.orderComments ?? '')
      || this.companyNoteValue(details) !== (details.companyComments ?? '');
  }

  setComment(value: string): void {
    this.commentDraft.set(value);
  }

  commentValue(details: ReviewCheckPayload): string {
    return this.commentDraft();
  }

  canEditNotes(details: ReviewCheckPayload): boolean {
    return details.permissions.canEditNotes;
  }

  hasReviewNote(review: ReviewCheckReview, details: ReviewCheckPayload): boolean {
    return Boolean(
      (review.comment ?? '').trim()
      || (review.orderComments ?? '').trim()
      || (review.commentCompany ?? '').trim()
      || (details.orderComments ?? '').trim()
      || (details.companyComments ?? '').trim()
      || details.permissions.canEditNotes
    );
  }

  reviewPhotoUrl(review: ReviewCheckReview): string {
    return (review.url ?? '').trim();
  }

  botOrProductLabel(details: ReviewCheckPayload, review: ReviewCheckReview): string {
    if (details.permissions.canSeeBot && review.botName) {
      return review.botName;
    }

    return review.productTitle || 'Отзыв';
  }

  reviewedCount(details: ReviewCheckPayload): number {
    return details.reviews.filter((review) => this.isReviewPublished(review)).length;
  }

  reviewCardCount(details: ReviewCheckPayload): number {
    return details.reviews.length + (details.permissions.canApprovePublication ? 1 : 0);
  }

  approveCardIndex(details: ReviewCheckPayload): number {
    return details.reviews.length;
  }

  isApproveCardActive(details: ReviewCheckPayload): boolean {
    return this.activeReviewIndex() === this.approveCardIndex(details);
  }

  reviewDate(review: ReviewCheckReview, details: ReviewCheckPayload): string {
    if (this.reviewWindowStatus(details) === 'paid') {
      return 'оплачен';
    }

    return review.publishedDate || 'Не назначено';
  }

  reviewFooterStateLabel(review: ReviewCheckReview, details: ReviewCheckPayload): string {
    if (this.reviewWindowStatus(details) === 'paid') {
      return 'оплачен';
    }

    if (this.isReviewPublished(review)) {
      return 'опубликован';
    }

    if (this.isReviewWindowApproved(details)) {
      return 'одобрен';
    }

    return 'не опубликован';
  }

  reviewWindowStatus(details: ReviewCheckPayload): 'approved' | 'paid' | 'correction' | 'not-approved' {
    const status = (details.status || '').trim().toLowerCase();
    if (status === 'оплачено') {
      return 'paid';
    }
    if (status === 'коррекция') {
      return 'correction';
    }
    if (details.approved || status === 'публикация' || status === 'опубликовано') {
      return 'approved';
    }
    return 'not-approved';
  }

  reviewWindowStatusLabel(details: ReviewCheckPayload): string {
    switch (this.reviewWindowStatus(details)) {
      case 'paid':
        return 'Оплачено';
      case 'approved':
        return 'Одобрено';
      case 'correction':
        return 'Коррекция';
      default:
        return 'Не одобрено';
    }
  }

  isReviewWindowApproved(details: ReviewCheckPayload): boolean {
    const status = this.reviewWindowStatus(details);
    return status === 'approved' || status === 'paid';
  }

  isAction(action: ReviewCheckAction): boolean {
    return this.mutationKey() === action;
  }

  isMutating(key: string): boolean {
    return this.mutationKey() === key;
  }

  fieldMutationKey(review: ReviewCheckReview, field: ReviewEditableField): string {
    return `field-${review.id}-${field}`;
  }

  notesMutationKey(review: ReviewCheckReview): string {
    return `notes-${review.id}`;
  }

  private applyDetails(details: ReviewCheckPayload): void {
    this.details.set(details);
    this.commentDraft.set(details.comment ?? '');
    this.orderNoteDraft.set(details.orderComments ?? '');
    this.companyNoteDraft.set(details.companyComments ?? '');
    this.drafts.set(details.reviews.reduce<Record<number, ReviewDraft>>((drafts, review) => {
      drafts[review.id] = {
        text: review.text ?? '',
        answer: review.answer ?? '',
        comment: review.comment ?? ''
      };
      return drafts;
    }, {}));
  }

  private applyUpdatedReview(updatedReview: ReviewCheckReview): void {
    this.details.update((details) => details ? {
      ...details,
      reviews: details.reviews.map((review) => review.id === updatedReview.id ? { ...review, ...updatedReview } : review)
    } : details);
    this.patchDraft(updatedReview.id, {
      text: updatedReview.text ?? '',
      answer: updatedReview.answer ?? '',
      comment: updatedReview.comment ?? ''
    });
  }

  private applyUpdatedNotes(notes: ReviewCheckNotes): void {
    this.details.update((details) => details ? {
      ...details,
      orderComments: notes.orderComments ?? details.orderComments,
      companyComments: notes.companyComments ?? details.companyComments,
      reviews: details.reviews.map((review) => ({
        ...review,
        orderComments: notes.orderComments ?? review.orderComments,
        commentCompany: notes.companyComments ?? review.commentCompany
      }))
    } : details);
    this.orderNoteDraft.set(notes.orderComments ?? '');
    this.companyNoteDraft.set(notes.companyComments ?? '');
  }

  private runAction(
    key: ReviewCheckAction,
    successMessage: string,
    requestFactory: () => ReturnType<ApiService['approveReviewCheck']>
  ): void {
    if (this.busy()) {
      return;
    }

    this.mutationKey.set(key);
    this.error.set(null);
    this.statusMessage.set(null);
    const activeIndex = this.activeReviewIndex();
    const activeId = this.details()?.reviews[activeIndex]?.id;

    requestFactory().subscribe({
      next: (details) => {
        this.applyDetails(details);
        if (activeId) {
          this.restoreActiveReview(activeId);
        } else {
          this.activeReviewIndex.set(Math.max(0, Math.min(activeIndex, this.reviewCardCount(details) - 1)));
          this.scrollActiveReview();
        }
        this.statusMessage.set(successMessage);
        this.mutationKey.set(null);
      },
      error: (err) => {
        this.error.set(this.errorMessage(err, 'Действие не выполнено.'));
        this.mutationKey.set(null);
      }
    });
  }

  private buildRequest(): ReviewCheckUpdateRequest {
    const details = this.details();
    return {
      comment: details ? this.commentValue(details) : this.commentDraft(),
      reviews: (details?.reviews ?? []).map((review) => ({
        id: review.id,
        text: this.reviewFieldValue(review, 'text'),
        answer: this.reviewFieldValue(review, 'answer'),
        publish: review.publish,
        publishedDate: review.publishedDate || null,
        url: review.url || ''
      }))
    };
  }

  private reviewDraft(review: ReviewCheckReview): ReviewDraft {
    return this.drafts()[review.id] ?? {
      text: review.text ?? '',
      answer: review.answer ?? '',
      comment: review.comment ?? ''
    };
  }

  private patchDraft(reviewId: number, patch: Partial<ReviewDraft>): void {
    this.drafts.update((drafts) => ({
      ...drafts,
      [reviewId]: {
        ...(drafts[reviewId] ?? { text: '', answer: '', comment: '' }),
        ...patch
      }
    }));
  }

  private reviewFieldKey(review: ReviewCheckReview, field: ReviewEditableField): string {
    return `${review.id}-${field}`;
  }

  private isReviewPublished(review: ReviewCheckReview): boolean {
    return review.publish || Boolean(review.publishedDate);
  }

  private requiredOrderDetailId(): string {
    return this.orderDetailId() ?? '';
  }

  private goToReview(index: number): void {
    const details = this.details();
    const total = details ? this.reviewCardCount(details) : 0;
    if (!total) {
      this.activeReviewIndex.set(0);
      return;
    }

    this.activeReviewIndex.set(Math.max(0, Math.min(total - 1, index)));
    this.scrollActiveReview();
  }

  private restoreActiveReview(reviewId?: number): void {
    if (!reviewId) {
      this.activeReviewIndex.set(0);
      return;
    }

    const index = this.details()?.reviews.findIndex((review) => review.id === reviewId) ?? -1;
    this.activeReviewIndex.set(index >= 0 ? index : 0);
    this.scrollActiveReview();
  }

  private scrollActiveReview(): void {
    if (typeof window === 'undefined') {
      return;
    }

    const index = this.activeReviewIndex();
    window.requestAnimationFrame(() => {
      document
        .querySelector<HTMLElement>(`.review-check-card[data-card-index="${index}"]`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
    });
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
