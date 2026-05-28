import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OrderItem } from '../core/api.service';
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
      [class.waiting-client]="order.waitingForClient"
    >
      <header class="lead-card-head order-card-head">
        <a [href]="titleHref || '#'" target="_blank" rel="noopener" (click)="guardLink($event, titleHref)">
          {{ title }}
        </a>
        <span class="card-id-line">
          <small>#{{ order.id }}</small>
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

      @if (canSeePhoneAndPayment) {
        <div class="lead-phone-row order-phone-row phone-row">
          <a [href]="phoneHref || '#'" target="_blank" rel="noopener" (click)="guardLink($event, phoneHref)">
            {{ phoneLabel }}
          </a>
          <button type="button" (click)="copyPhone.emit()" aria-label="Скопировать телефон">
            {{ copiedKey === phoneCopyKey ? '✓' : 'T' }}
          </button>
        </div>

        <div class="lead-card-actions card-actions order-link-actions">
          <button type="button" (click)="copyText.emit('review')">
            {{ copiedKey === reviewCopyKey ? '✓' : 'текст' }}
          </button>
          <a
            [href]="reviewHref || '#'"
            target="_blank"
            rel="noopener"
            [class.disabled]="!reviewHref"
            (click)="guardLink($event, reviewHref)"
          >url</a>
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
      }

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
        <span>{{ order.categoryTitle || 'Категория' }}</span>
        <span>{{ order.subCategoryTitle || 'Подкатегория' }}</span>
      </div>

      <div class="order-city-row" [attr.aria-label]="cityLabel">
        <span class="city-prefix" aria-hidden="true">город</span>
        <span>{{ cityLabel }}</span>
      </div>

      @if (showNoteEditor) {
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
          Без изменений: {{ unchangedDays }} дн.
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

    header {
      display: grid;
      grid-template-columns: minmax(0, 1fr) max-content;
      align-items: start;
      gap: 0.42rem;
    }

    header a {
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
      font-size: 0.82rem;
    }

    .phone-row button {
      min-width: 2rem;
      padding: 0;
      color: #36708d;
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
}
