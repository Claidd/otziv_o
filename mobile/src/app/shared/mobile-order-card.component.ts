import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ClientMessageStatus, OrderItem } from '../core/api.service';
import { MobileNoteEditorComponent } from './mobile-note-editor.component';

export type MobileOrderStatusAction = {
  label: string;
  status: string;
  icon: string;
};

export type MobileOrderCopyKind = 'review' | 'payment';

@Component({
  selector: 'app-mobile-order-card',
  standalone: true,
  imports: [FormsModule, MobileNoteEditorComponent],
  template: `
    <article
      class="lead-card manager-card mobile-worker-order-card order-mobile-card mobile-order-card {{ toneClass }}"
      [class.order-mobile-card--company]="companyMode"
      [class.order-mobile-card--common]="isCommonInvoice"
      [class.order-mobile-card--has-communication]="hasCommunicationIndicator"
      [class.waiting-client]="order.waitingForClient"
    >
      @if (communicationTone; as tone) {
        <span class="communication-wrap order-communication">
          <button
            type="button"
            class="communication-indicator"
            [class.communication-indicator--success]="tone === 'success'"
            [class.communication-indicator--wait]="tone === 'wait'"
            [class.communication-indicator--danger]="tone === 'danger'"
            [attr.aria-label]="communicationTitle"
            [attr.aria-expanded]="communicationPopoverOpen"
            (click)="toggleCommunicationPopover($event)"
            (blur)="closeCommunicationPopover()"
          >
            <span aria-hidden="true"></span>
          </button>
          @if (communicationPopoverOpen) {
            <div class="communication-popover">
              <strong>{{ communicationTitle }}</strong>
              @for (detail of communicationDetails; track detail) {
                <span>{{ detail }}</span>
              }
            </div>
          }
        </span>
      }

      <header class="lead-card-head order-card-head">
        <span class="order-title-wrap">
          <button
            type="button"
            class="order-title-button"
            [attr.aria-label]="orderFullTitle"
            [attr.aria-expanded]="titlePopoverOpen"
            (click)="toggleTitlePopover($event)"
            (blur)="closeTitlePopover()"
          >
            {{ title }}
          </button>
          @if (titlePopoverOpen) {
            <div class="order-title-popover">
              @for (line of orderTitleDetails; track line) {
                <span>{{ line }}</span>
              }
            </div>
          }
        </span>
        <span class="card-id-line">
          <small>{{ isCommonInvoice ? 'ОС' : '#' }}{{ isCommonInvoice ? order.commonInvoiceId : order.id }}</small>
          @if (order.waitingForClient && showWaitingBadge) {
            <em aria-label="Ждет клиента">!</em>
          }
          @if (noteBadge) {
            <button class="order-note-badge" type="button" (click)="noteBadgeClick.emit()" [attr.aria-label]="noteBadge">
              <span aria-hidden="true">!</span>
            </button>
          }
        </span>
      </header>

      <div class="lead-meta-row order-main-meta meta-row">
        <span [class.status-waiting]="order.waitingForClient">{{ statusLabel }}</span>
        <span>{{ amountLabel }}</span>
      </div>

      @if (badReviewSummary) {
        <div class="lead-meta-row order-bad-review-row meta-row bad-summary-row">
          <span>{{ badReviewSummary }}</span>
          <span>{{ badReviewAmount }}</span>
        </div>
      }

      @if (isCommonInvoice) {
        <div class="lead-meta-row common-invoice-row meta-row">
          <span>{{ commonInvoiceSummaryLabel }}</span>
        </div>
      } @else if (canSeePhoneAndPayment) {
        <div class="lead-phone-row order-phone-row phone-row">
          <a [href]="phoneHref || '#'" target="_blank" rel="noopener" (click)="guardLink($event, phoneHref)">
            {{ phoneLabel }}
          </a>
          <button type="button" (click)="copyPhone.emit()" aria-label="Скопировать телефон">
            {{ copiedKey === phoneCopyKey ? '✓' : 'T' }}
          </button>
        </div>
      }

      <div class="lead-card-actions card-actions order-link-actions">
          @if (isCommonInvoice) {
            <button type="button" (click)="details.emit()">состав</button>
          } @else {
            <button type="button" (click)="copyText.emit('review')">
              {{ copiedKey === reviewCopyKey ? '✓' : 'текст' }}
            </button>
          }
          <a
            [href]="reviewHref || '#'"
            target="_blank"
            rel="noopener"
            [class.disabled]="!reviewHref"
            (click)="guardLink($event, reviewHref)"
          >{{ isCommonInvoice ? 'ссылка' : 'url' }}</a>
          <button type="button" (click)="copyText.emit('payment')">
            {{ copiedKey === paymentCopyKey ? '✓' : 'счет' }}
          </button>
          <a
            [href]="filialHref || '#'"
            target="_blank"
            rel="noopener"
            [class.disabled]="!filialHref"
            (click)="guardLink($event, filialHref)"
          >ссылка</a>
        </div>

      <div class="order-progress progress" aria-label="Прогресс заказа">
        <span class="progress-bar" [style.width.%]="progress">
          <em>{{ order.counter || 0 }}</em>
        </span>
      </div>

      @if (canManageOrderStatuses || canManageClientWaiting) {
        <div class="lead-card-actions card-actions order-status-actions">
          @if (canManageOrderStatuses) {
            @for (action of statusActions; track action.status) {
              <button
                type="button"
                [disabled]="order.waitingForClient || isStatusMutating(action)"
                (click)="statusChange.emit(action)"
                [attr.aria-label]="order.waitingForClient ? 'Сначала верните заказ в работу' : action.status"
              >
                {{ action.label }}
              </button>
            }
          }
          @if (canManageClientWaiting) {
            <button
              type="button"
              class="client-waiting-action"
              [class.active]="order.waitingForClient"
              [disabled]="isClientWaitingMutating"
              (click)="clientWaitingToggle.emit()"
            >
              {{ order.waitingForClient ? 'ждет клиента' : 'клиент' }}
            </button>
          }
        </div>
      }

      <div class="lead-meta-row order-category-row meta-row category-row">
        <span>{{ isCommonInvoice ? 'Общий счет' : (order.categoryTitle || 'Категория') }}</span>
        <span>{{ isCommonInvoice ? commonInvoiceCompaniesLabel : (order.subCategoryTitle || 'Подкатегория') }}</span>
      </div>

      @if (!isCommonInvoice) {
        <div class="order-city-row" [attr.aria-label]="cityLabel">
          <span class="city-prefix" aria-hidden="true">город</span>
          <span>{{ cityLabel }}</span>
        </div>
      }

      @if (showNoteEditor && !isCommonInvoice) {
        <app-mobile-note-editor
          class="lead-comment-editor order-note-editor note-editor"
          [class.saved]="noteSaved"
          [editorId]="noteEditorId"
          [name]="noteEditorId"
          placeholder="нет заметок"
          [readOnly]="noteReadOnly"
          [value]="noteValue"
          [stateLabel]="noteStateLabel"
          [state]="noteState"
          [showActions]="noteShowActions"
          [cancelDisabled]="noteCancelDisabled"
          [saveDisabled]="noteSaveDisabled"
          [saveIcon]="noteSaveIcon"
          (start)="noteStart.emit()"
          (valueChange)="noteChange.emit($event)"
          (blurred)="noteBlur.emit()"
          (cancel)="noteCancel.emit()"
          (save)="noteSave.emit()"
        />
      }

      @if (showCompanyNoteEditor) {
        <div class="note-editor side-note-editor">
          <small>Заметка компании</small>
          <textarea
            [readonly]="companyNoteReadOnly"
            [ngModel]="companyNoteValue"
            (ngModelChange)="companyNoteChange.emit($event)"
          ></textarea>
          <div class="note-actions mobile-keyboard-actions">
            <button type="button" class="cancel" (click)="companyNoteCancel.emit()">X</button>
            <button type="button" class="save" (click)="companyNoteSave.emit()" [disabled]="companyNoteSaveDisabled">
              <span class="material-icons-sharp">save</span>
            </button>
          </div>
        </div>
      }

      <button class="company-details-button order-details-button details-button" type="button" (click)="details.emit()">
        Подробнее
      </button>

      <footer class="lead-card-foot order-card-foot">
        <button
          class="order-unchanged unchanged-age"
          type="button"
          [class.order-unchanged--alert]="unchangedAlert"
          [class.alert]="unchangedAlert"
          [attr.aria-label]="'Без изменений: ' + unchangedDays + ' дн.'"
        >
          @if (unchangedAlert) {
            <i aria-hidden="true">!</i>
          }
          {{ isCommonInvoice && order.commonInvoiceLastError ? 'Ошибка счета' : 'Без изменений: ' + unchangedDays + ' дн.' }}
        </button>
        <button type="button" (click)="workerClick.emit()" [attr.aria-label]="workerTitle">
          {{ workerLabel }}
        </button>
      </footer>
    </article>
  `,
  styles: [`
    :host {
      display: contents;
    }

    .mobile-order-card {
      position: relative;
      display: flex;
      flex: 0 0 var(--otziv-board-card-width, min(15.4rem, 76vw));
      min-width: 0;
      min-height: 0;
      height: 100%;
      max-height: 100%;
      align-self: stretch;
      flex-direction: column;
      justify-content: flex-start;
      gap: var(--otziv-card-gap, 0.34rem);
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.9rem;
      padding: var(--otziv-card-padding, 0.68rem);
      background: linear-gradient(180deg, var(--otziv-tone-walk-surface) 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
      box-shadow: 0 0.55rem 1.2rem rgba(132, 139, 200, 0.14);
      scroll-snap-align: center;
    }

    :host-context(.manager-list--expanded) .mobile-order-card,
    :host-context(.worker-list--expanded) .mobile-order-card {
      flex: none;
      width: 100%;
      min-width: 0;
      max-width: none;
      min-height: 31.25rem;
      height: auto;
      scroll-snap-align: none;
    }

    :host-context(.manager-list--expanded) .order-mobile-card--company {
      min-height: 29.5rem;
    }

    :host-context(.manager-list--expanded) .mobile-order-card,
    :host-context(.worker-list--expanded) .mobile-order-card {
      gap: 0.42rem;
      justify-content: space-between;
      overflow: hidden;
    }

    .mobile-order-card.tone-wait,
    .mobile-order-card.lead-card--new,
    .mobile-order-card.waiting-client {
      border-color: var(--otziv-tone-wait-border);
      background: linear-gradient(180deg, var(--otziv-tone-wait-surface) 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
    }

    .mobile-order-card.tone-walk {
      border-color: var(--otziv-tone-walk-border);
      background: linear-gradient(180deg, var(--otziv-tone-walk-surface) 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
    }

    .mobile-order-card.tone-correction {
      border-color: var(--otziv-tone-correction-border);
      background: linear-gradient(180deg, var(--otziv-tone-correction-surface) 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
    }

    .mobile-order-card.tone-publication,
    .mobile-order-card.lead-card--send {
      border-color: var(--otziv-tone-publication-border);
      background: linear-gradient(180deg, var(--otziv-tone-publication-surface) 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
    }

    .mobile-order-card.tone-success,
    .mobile-order-card.lead-card--work {
      border-color: var(--otziv-tone-success-border);
      background: linear-gradient(180deg, var(--otziv-tone-success-surface) 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
    }

    .mobile-order-card.tone-bad {
      border-color: var(--otziv-tone-bad-border);
      background: linear-gradient(180deg, var(--otziv-tone-bad-surface) 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
    }

    .mobile-order-card.tone-recovery {
      border-color: rgba(244, 197, 66, 0.7);
      background: linear-gradient(180deg, #fff8d8 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
    }

    .mobile-order-card.order-mobile-card--common {
      border-style: dashed;
      border-color: rgba(214, 159, 43, 0.55);
      background: linear-gradient(180deg, #fffdf5 0%, var(--otziv-white) 48%, var(--otziv-white) 100%);
    }

    header {
      display: grid;
      grid-template-columns: minmax(0, 1fr) max-content;
      align-items: start;
      gap: 0.42rem;
      padding-left: 0;
    }

    .order-mobile-card--has-communication header {
      padding-left: 1.55rem;
    }

    header a,
    .order-title-button {
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

    .order-title-wrap {
      position: relative;
      min-width: 0;
    }

    .order-title-button {
      appearance: none;
      width: 100%;
      min-height: 0;
      border: 0;
      padding: 0;
      background: transparent;
      cursor: pointer;
      text-align: left;
    }

    .order-title-popover,
    .communication-popover {
      position: absolute;
      z-index: 28;
      display: grid;
      box-sizing: border-box;
      width: 100%;
      min-width: 0;
      gap: 0.3rem;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.55rem;
      padding: 0.62rem 0.68rem;
      color: var(--otziv-dark);
      background: rgba(255, 255, 255, 0.98);
      box-shadow: 0 0.7rem 1.45rem rgba(54, 57, 73, 0.18);
      font-family: var(--otziv-font-family);
      font-size: 0.72rem;
      font-weight: 800;
      line-height: 1.25;
      text-align: left;
      white-space: normal;
    }

    .order-title-popover {
      top: calc(100% + 0.36rem);
      left: 0;
    }

    .order-title-popover span,
    .communication-popover strong,
    .communication-popover span {
      min-width: 0;
      overflow-wrap: anywhere;
    }

    .communication-wrap {
      position: relative;
      display: inline-flex;
      flex: 0 0 auto;
    }

    .order-mobile-card > .order-communication {
      position: absolute;
      top: 0.58rem;
      left: 0.58rem;
      z-index: 24;
    }

    .communication-indicator {
      appearance: none;
      display: grid;
      width: 1.18rem;
      min-width: 1.18rem;
      height: 1.18rem;
      min-height: 1.18rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 50%;
      padding: 0;
      background: rgba(255, 255, 255, 0.94);
      box-shadow: 0 0.32rem 0.75rem rgba(132, 139, 200, 0.16);
    }

    .communication-indicator span {
      display: block;
      width: 0.58rem;
      min-width: 0.58rem;
      height: 0.58rem;
      min-height: 0.58rem;
      border-radius: 50%;
      background: #6b7280;
    }

    .communication-indicator--success span {
      background: #2f6f65;
    }

    .communication-indicator--wait span {
      background: #d69f2b;
    }

    .communication-indicator--danger span {
      background: var(--otziv-danger);
    }

    .communication-popover {
      top: calc(100% + 0.36rem);
      left: 0;
      width: min(13.6rem, calc(100vw - 1.4rem));
    }

    .communication-popover strong {
      font-size: 0.76rem;
      font-weight: 1000;
    }

    .card-id-line {
      display: inline-flex;
      align-items: center;
      justify-content: flex-end;
      gap: 0.18rem;
      color: var(--otziv-info);
      font-size: var(--otziv-unified-subtitle-size, 0.56rem);
      font-weight: 900;
      line-height: 1;
      white-space: nowrap;
    }

    .card-id-line em {
      display: grid;
      flex: 0 0 var(--otziv-note-alert-size, 1.06rem);
      width: var(--otziv-note-alert-size, 1.06rem);
      height: var(--otziv-note-alert-size, 1.06rem);
      min-width: var(--otziv-note-alert-size, 1.06rem);
      min-height: var(--otziv-note-alert-size, 1.06rem);
      place-items: center;
      align-self: center;
      border: 1px solid rgba(218, 168, 36, 0.36);
      border-radius: 999px;
      color: #6a5100;
      background: #f4c542;
      font-size: 0.64rem;
      font-style: normal;
      font-weight: 1000;
      line-height: 1;
    }

    .order-note-badge {
      display: inline-grid;
      flex: 0 0 var(--otziv-note-alert-size, 1.06rem);
      width: var(--otziv-note-alert-size, 1.06rem);
      height: var(--otziv-note-alert-size, 1.06rem);
      min-width: var(--otziv-note-alert-size, 1.06rem);
      min-height: var(--otziv-note-alert-size, 1.06rem);
      place-items: center;
      align-self: center;
      border: 1px solid rgba(218, 168, 36, 0.36);
      border-radius: 999px;
      padding: 0;
      color: #6a5100;
      background: #f4c542;
      font: 1000 0.64rem/1 var(--otziv-card-title-font);
    }

    .meta-row,
    .phone-row,
    footer {
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
    footer button {
      min-width: 0;
      min-height: 1.62rem;
      overflow: hidden;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 999px;
      padding: 0 0.55rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font: 900 0.7rem/1 var(--otziv-card-title-font);
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

    .phone-row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 2rem;
    }

    .phone-row a {
      color: #36708d;
      font-size: var(--otziv-unified-phone-font-size, 1rem);
      text-size-adjust: 100%;
      -webkit-text-size-adjust: 100%;
    }

    .phone-row button {
      min-width: 2rem;
      padding: 0;
      color: #36708d;
      font-size: 0.78rem;
    }

    .status-waiting,
    .bad-summary-row span,
    .common-invoice-row span {
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
      gap: 0.28rem 0.3rem;
    }

    .order-status-actions {
      grid-template-columns: repeat(5, minmax(0, 1fr));
    }

    .card-actions button,
    .card-actions a,
    .details-button {
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
      letter-spacing: 0;
      text-decoration: none;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .card-actions button:disabled,
    .card-actions a.disabled {
      opacity: 0.58;
      pointer-events: none;
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
      font: 900 0.66rem/1 var(--otziv-card-title-font);
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
    .side-note-editor {
      position: relative;
      display: grid;
      min-width: 0;
      gap: 0.35rem;
    }

    .order-note-editor {
      flex: 1 1 6.6rem;
      min-height: 5.25rem;
    }

    :host-context(.manager-list--expanded) .order-note-editor,
    :host-context(.worker-list--expanded) .order-note-editor {
      flex: 0 0 auto;
      height: 3.7rem;
      min-height: 3.7rem;
    }

    .side-note-editor textarea {
      display: block;
      width: 100%;
      min-width: 0;
      height: 4.4rem;
      resize: none;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.8rem;
      outline: 0;
      padding: 0.58rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font: 700 0.72rem/1.35 var(--otziv-card-title-font);
    }

    .side-note-editor small {
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 900;
      text-transform: uppercase;
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
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 0.8rem/1 var(--otziv-card-title-font);
    }

    .note-actions .save {
      color: var(--otziv-success);
    }

    .note-actions .cancel {
      color: var(--otziv-danger);
    }

    .details-button {
      width: 100%;
      min-height: 2.04rem;
      text-transform: uppercase;
    }

    footer {
      margin-top: 0;
    }

    footer button {
      border: 0;
      padding: 0;
      color: var(--otziv-info);
      background: transparent;
      font-size: 0.68rem;
      font-weight: 900;
      text-align: left;
    }

    footer button:last-child {
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
  `]
})
export class MobileOrderCardComponent {
  @Input({ required: true }) order!: OrderItem;
  @Input() statusActions: readonly MobileOrderStatusAction[] = [];
  @Input() copiedKey: string | null = null;
  @Input() mutationKey: string | null = null;
  @Input() title = 'Заказ';
  @Input() titleHref = '';
  @Input() toneClass = '';
  @Input() statusLabel = '-';
  @Input() amountLabel = '-';
  @Input() badReviewSummary = '';
  @Input() badReviewAmount = '';
  @Input() phoneLabel = '';
  @Input() phoneHref = '';
  @Input() reviewHref = '';
  @Input() filialHref = '';
  @Input() phoneCopyKey = '';
  @Input() reviewCopyKey = '';
  @Input() paymentCopyKey = '';
  @Input() cityLabel = 'Город не указан';
  @Input() workerLabel = '-';
  @Input() workerTitle = 'Специалист не назначен';
  @Input() noteBadge = '';
  @Input() unchangedDays = 0;
  @Input() unchangedAlert = false;
  @Input() progress = 0;
  @Input() canSeePhoneAndPayment = true;
  @Input() canManageOrderStatuses = true;
  @Input() canManageClientWaiting = false;
  @Input() companyMode = false;
  @Input() showWaitingBadge = true;
  @Input() showNoteEditor = true;
  @Input() noteEditorId = '';
  @Input() noteValue = '';
  @Input() noteStateLabel = '';
  @Input() noteState = 'idle';
  @Input() noteReadOnly = false;
  @Input() noteShowActions = false;
  @Input() noteSaveDisabled = false;
  @Input() noteCancelDisabled = false;
  @Input() noteSaveIcon = 'save';
  @Input() noteSaved = false;
  @Input() showCompanyNoteEditor = false;
  @Input() companyNoteValue = '';
  @Input() companyNoteReadOnly = false;
  @Input() companyNoteSaveDisabled = false;

  @Output() copyPhone = new EventEmitter<void>();
  @Output() copyText = new EventEmitter<MobileOrderCopyKind>();
  @Output() statusChange = new EventEmitter<MobileOrderStatusAction>();
  @Output() clientWaitingToggle = new EventEmitter<void>();
  @Output() noteBadgeClick = new EventEmitter<void>();
  @Output() noteStart = new EventEmitter<void>();
  @Output() noteChange = new EventEmitter<string>();
  @Output() noteBlur = new EventEmitter<void>();
  @Output() noteCancel = new EventEmitter<void>();
  @Output() noteSave = new EventEmitter<void>();
  @Output() companyNoteChange = new EventEmitter<string>();
  @Output() companyNoteCancel = new EventEmitter<void>();
  @Output() companyNoteSave = new EventEmitter<void>();
  @Output() details = new EventEmitter<void>();
  @Output() workerClick = new EventEmitter<void>();

  communicationPopoverOpen = false;
  titlePopoverOpen = false;
  private lastTitleTapAt = 0;

  get isCommonInvoice(): boolean {
    return Boolean(this.order.commonInvoice);
  }

  get hasCommunicationIndicator(): boolean {
    return this.communicationTone !== null;
  }

  get communicationTone(): 'success' | 'wait' | 'danger' | null {
    if (this.isCommonInvoice) {
      return this.commonInvoiceCommunicationTone;
    }

    if (this.chatBindingWarning) {
      return 'danger';
    }

    const status = this.clientMessageStatus;
    if (!status || status.tone === 'muted') {
      return null;
    }

    return status.tone;
  }

  get communicationTitle(): string {
    if (this.isCommonInvoice) {
      return this.commonInvoiceCommunicationTitle;
    }

    const warning = this.chatBindingWarning;
    if (warning) {
      return warning;
    }

    return this.clientMessageStatus?.label ?? 'Состояние связи';
  }

  get communicationDetails(): string[] {
    if (this.isCommonInvoice) {
      return this.commonInvoiceCommunicationDetails;
    }

    const details: string[] = [];
    const warning = this.chatBindingWarning;
    const status = this.clientMessageStatus;

    if (warning) {
      details.push(warning);
    }
    if (status) {
      if (!warning || !status.label.toLowerCase().includes(warning.toLowerCase())) {
        details.push(status.label);
      }
      if (status.scenario) {
        details.push(`Сценарий: ${this.clientMessageScenarioLabel(status.scenario)}`);
      }
      if (status.errorCode) {
        details.push(`Код: ${status.errorCode}`);
      }
      if (status.errorMessage) {
        details.push(status.errorMessage);
      }
      if (status.lastSuccessAt) {
        details.push(`Успех: ${status.lastSuccessAt}`);
      }
      if (status.lastAttemptAt) {
        details.push(`Последняя попытка: ${status.lastAttemptAt}`);
      }
      if (status.nextAttemptAt) {
        details.push(`Следующая попытка: ${status.nextAttemptAt}`);
      }
      if (status.consecutiveFailures) {
        details.push(`Ошибок подряд: ${status.consecutiveFailures}`);
      }
    }

    return details.length ? details : ['Ошибок связи не видно'];
  }

  get orderFullTitle(): string {
    return this.orderTitleDetails.join('. ');
  }

  get orderTitleDetails(): string[] {
    const details: string[] = [];
    const company = this.cleanLabel(this.order.companyTitle) || 'Без компании';
    const filial = this.cleanLabel(this.order.filialTitle) || 'Без филиала';
    const city = this.cleanLabel(this.order.filialCity);

    details.push(`Компания: ${company}`);
    details.push(`Адрес филиала: ${filial}`);
    if (city) {
      details.push(`Город: ${city}`);
    }

    return details;
  }

  toggleCommunicationPopover(event: MouseEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.titlePopoverOpen = false;
    this.communicationPopoverOpen = !this.communicationPopoverOpen;
  }

  closeCommunicationPopover(): void {
    this.communicationPopoverOpen = false;
  }

  toggleTitlePopover(event: MouseEvent): void {
    event.preventDefault();
    event.stopPropagation();

    const now = Date.now();
    const isPointerDoubleClick = event.detail >= 2;
    const isTouchDoubleTap = !isPointerDoubleClick && now - this.lastTitleTapAt <= 360;
    this.lastTitleTapAt = now;

    if (isPointerDoubleClick || isTouchDoubleTap) {
      this.lastTitleTapAt = 0;
      this.closeTitlePopover();
      this.openFilialFromTitle();
      return;
    }

    this.communicationPopoverOpen = false;
    this.titlePopoverOpen = !this.titlePopoverOpen;
  }

  closeTitlePopover(): void {
    this.titlePopoverOpen = false;
  }

  private get clientMessageStatus(): ClientMessageStatus | null {
    return this.order.clientMessageStatus ?? null;
  }

  private get chatBindingWarning(): string {
    if (this.isCommonInvoice) {
      return this.order.commonInvoiceLastError || '';
    }

    return this.chatBindingWarningForValues(
      this.order.companyUrlChat,
      this.order.groupId,
      this.order.telegramGroupChatId,
      this.order.maxGroupChatId
    );
  }

  private get commonInvoiceCommunicationTone(): 'success' | 'wait' | 'danger' | null {
    const status = this.commonInvoiceStatusLabel.toLocaleLowerCase('ru-RU');
    const sentAt = this.cleanLabel(this.order.commonInvoiceSentAt);

    if (this.cleanLabel(this.order.commonInvoiceLastError)) {
      return 'danger';
    }
    if (status.includes('требует внимания') || status === 'не оплачено' || status === 'бан') {
      return 'danger';
    }
    if (!sentAt && this.commonInvoiceReadyToSend && status.includes('ожида')) {
      return 'danger';
    }
    if (status === 'оплачено') {
      return 'success';
    }
    if (sentAt && this.cleanLabel(this.order.commonInvoiceNextReminderAt)) {
      return 'wait';
    }
    if (sentAt) {
      return 'success';
    }

    return 'wait';
  }

  private get commonInvoiceCommunicationTitle(): string {
    const status = this.commonInvoiceStatusLabel.toLocaleLowerCase('ru-RU');

    if (this.cleanLabel(this.order.commonInvoiceLastError)) {
      return 'Контроль: ошибка общего счета';
    }
    if (status.includes('требует внимания') || status === 'не оплачено' || status === 'бан') {
      return 'Контроль: общий счет требует внимания';
    }
    if (!this.cleanLabel(this.order.commonInvoiceSentAt) && this.commonInvoiceReadyToSend && status.includes('ожида')) {
      return 'Контроль: общий счет готов, но не отправлен';
    }
    if (status === 'оплачено') {
      return 'Общий счет оплачен';
    }
    if (this.cleanLabel(this.order.commonInvoiceNextReminderAt)) {
      return 'Общий счет: напоминание запланировано';
    }
    if (this.cleanLabel(this.order.commonInvoiceSentAt)) {
      return 'Общий счет отправлен';
    }

    return 'Общий счет собирается';
  }

  private get commonInvoiceCommunicationDetails(): string[] {
    const details: string[] = [];
    const error = this.cleanLabel(this.order.commonInvoiceLastError);
    const ready = this.order.commonInvoiceReadyOrders ?? this.order.counter ?? 0;
    const total = this.order.commonInvoiceTotalOrders ?? this.order.amount ?? 0;
    const paid = this.order.commonInvoicePaidOrders ?? 0;

    if (error) {
      details.push(`Ошибка: ${error}`);
    }
    if (this.commonInvoiceStatusLabel) {
      details.push(`Статус: ${this.commonInvoiceStatusLabel}`);
    }
    details.push(`Готово: ${ready}/${total}`);
    details.push(`Оплачено: ${paid}/${total}`);
    if (this.cleanLabel(this.order.commonInvoiceSentAt)) {
      details.push(`Отправлен: ${this.order.commonInvoiceSentAt}`);
    } else if (this.commonInvoiceReadyToSend) {
      details.push('Счет готов к отправке, но отправка не зафиксирована');
    } else {
      details.push('Счет еще собирается, отправка пока не должна идти');
    }
    if (this.cleanLabel(this.order.commonInvoiceLastReminderAt)) {
      details.push(`Последнее напоминание: ${this.order.commonInvoiceLastReminderAt}`);
    }
    if (this.cleanLabel(this.order.commonInvoiceNextReminderAt)) {
      details.push(`Следующее напоминание: ${this.order.commonInvoiceNextReminderAt}`);
    }
    if (this.order.commonInvoiceRemaining != null) {
      details.push(`Остаток: ${this.order.commonInvoiceRemaining} руб.`);
    }

    return details.length ? details : ['Общий счет еще не отправлен'];
  }

  private get commonInvoiceReadyToSend(): boolean {
    const ready = this.order.commonInvoiceReadyOrders ?? this.order.counter ?? 0;
    const total = this.order.commonInvoiceTotalOrders ?? this.order.amount ?? 0;
    return total > 0 && ready >= total;
  }

  private get commonInvoiceStatusLabel(): string {
    return this.cleanLabel(this.order.commonInvoiceStatus) || this.cleanLabel(this.order.status);
  }

  get commonInvoiceSummaryLabel(): string {
    const ready = this.order.commonInvoiceReadyOrders ?? this.order.counter ?? 0;
    const total = this.order.commonInvoiceTotalOrders ?? this.order.amount ?? 0;
    const paid = this.order.commonInvoicePaidOrders ?? 0;
    return `Готово ${ready}/${total}, оплачено ${paid}/${total}`;
  }

  get commonInvoiceCompaniesLabel(): string {
    const count = this.order.amount ?? this.order.commonInvoiceTotalOrders ?? 0;
    if (count === 1) {
      return '1 компания';
    }
    if (count > 1 && count < 5) {
      return `${count} компании`;
    }
    return `${count} компаний`;
  }

  isStatusMutating(action: MobileOrderStatusAction): boolean {
    return this.mutationKey === `order-${this.order.id}-${action.status}`;
  }

  get isClientWaitingMutating(): boolean {
    return this.mutationKey === `order-${this.order.id}-client-waiting`;
  }

  guardLink(event: MouseEvent, href: string): void {
    if (!href || href === '#') {
      event.preventDefault();
    }
  }

  private openFilialFromTitle(): void {
    const url = (this.filialHref || this.titleHref || '').trim();
    if (!url) {
      return;
    }

    window.open(url, '_blank', 'noopener');
  }

  private clientMessageScenarioLabel(scenario: string): string {
    switch (scenario) {
      case 'CLIENT_TEXT_REMINDER':
        return 'Ожидание текста клиента';
      case 'REVIEW_CHECK_REMINDER':
        return 'Проверка отзывов';
      case 'PAYMENT_REMINDER':
        return 'Оплата';
      case 'PAYMENT_INVOICE_RETRY':
        return 'Повтор счета';
      case 'PAYMENT_OVERDUE_ESCALATION':
        return 'Просроченная оплата';
      case 'REVIEW_CHECK_DELIVERY_RETRY':
        return 'Повтор ссылки проверки';
      default:
        return scenario;
    }
  }

  private chatBindingWarningForValues(
    chatUrl?: string | null,
    whatsappGroupId?: string | null,
    telegramGroupChatId?: number | null,
    maxGroupChatId?: number | null
  ): string {
    const platform = this.chatPlatformFromUrl(chatUrl);
    if (platform === 'unknown') {
      return (chatUrl ?? '').trim() ? 'Мессенджер по ссылке не распознан' : '';
    }

    if (platform === 'whatsapp' && !(whatsappGroupId ?? '').trim()) {
      return 'WhatsApp-группа не привязана';
    }
    if (platform === 'telegram' && telegramGroupChatId == null) {
      return 'Telegram-группа не привязана';
    }
    if (platform === 'max' && maxGroupChatId == null) {
      return 'MAX-группа не привязана';
    }

    return '';
  }

  private chatPlatformFromUrl(chatUrl?: string | null): 'whatsapp' | 'telegram' | 'max' | 'unknown' {
    const value = (chatUrl ?? '').trim().toLocaleLowerCase('en-US');
    if (!value) {
      return 'unknown';
    }
    if (value.includes('chat.whatsapp.com/')) {
      return 'whatsapp';
    }
    if (
      value.includes('t.me/')
      || value.includes('telegram.me/')
      || value.includes('telegram.dog/')
      || value.startsWith('tg://')
    ) {
      return 'telegram';
    }
    if (value.includes('max.ru/') || value.includes('web.max.ru/')) {
      return 'max';
    }

    return 'unknown';
  }

  private cleanLabel(value?: string | null): string {
    return (value ?? '').trim();
  }
}
