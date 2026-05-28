import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CompanyItem } from '../../core/api.service';
import { MobileNoteEditorComponent } from '../../shared/mobile-note-editor.component';
import { displayPhone, phoneHref } from '../../shared/phone-format';
import { type CompanyStatusAction, type ManagerNoteSaveState } from './manager-board.helpers';

@Component({
  selector: 'app-mobile-company-card',
  standalone: true,
  imports: [MobileNoteEditorComponent],
  template: `
    <article [class]="'lead-card manager-card company-mobile-card lead-card--' + tone">
      <header class="lead-card-head">
        <a [href]="chatUrl" target="_blank" rel="noopener">
          {{ company.title || 'Без названия' }}
        </a>
        <span>
          <small>{{ company.status || '-' }} / #{{ company.id }}</small>
          @if (noteBadge) {
            <button class="order-note-badge" type="button" (click)="focusNote.emit(company)" [attr.aria-label]="noteBadge">
              <span aria-hidden="true">!</span>
            </button>
          }
        </span>
      </header>

      <div class="lead-phone-row">
        <a [href]="chatUrl" target="_blank" rel="noopener">
          {{ phone }}
        </a>
        <button type="button" (click)="copyPhone.emit(company)" aria-label="Скопировать телефон">
          {{ copiedKey === 'company-phone-' + company.id ? '✓' : 'T' }}
        </button>
      </div>

      <div class="lead-meta-row">
        <span>{{ company.status || '-' }}</span>
        <button type="button" (click)="createOrder.emit(company)">Заказ</button>
      </div>

      @if (hasNextOrderRequest) {
        <p class="company-next-order" [class.company-next-order--failed]="hasFailedNextOrderRequest">
          <span class="material-icons-sharp">{{ hasFailedNextOrderRequest ? 'error' : 'assignment_add' }}</span>
          {{ nextOrderRequestLabel }}
        </p>
      }

      <app-mobile-note-editor
        class="lead-comment-editor company-note-editor"
        [editorId]="'companyNote' + company.id"
        [name]="'companyNote' + company.id"
        placeholder="Комментарий"
        [value]="note"
        [stateLabel]="noteStateLabel"
        [state]="noteState"
        (valueChange)="noteChange.emit($event)"
        (blurred)="noteSave.emit()"
      />

      <div class="lead-card-actions company-card-actions">
        @for (action of actions; track action.status) {
          <button
            type="button"
            (click)="statusChange.emit(action)"
            [disabled]="isMutating(action)"
          >
            <span class="material-icons-sharp">{{ action.icon }}</span>
            {{ isMutating(action) ? '...' : action.label }}
          </button>
        }
      </div>

      <div class="company-filial-count">
        <span>Филиалов:</span>
        <strong>{{ company.countFilials || 0 }}</strong>
      </div>

      <button class="company-details-button" type="button" (click)="openOrders.emit(company)">Подробнее</button>

      <footer class="lead-card-foot">
        <button type="button" (click)="showAllOrders.emit()" aria-label="Показать все заказы менеджера">
          {{ company.manager || '-' }}
        </button>
        <button type="button" (click)="edit.emit(company)">компания - {{ company.city || 'город' }}</button>
      </footer>
    </article>
  `,
  styles: [`
    :host {
      display: contents;
    }

    .company-mobile-card {
      display: flex;
      flex: 0 0 min(15.4rem, 76vw);
      min-width: 0;
      min-height: 100%;
      height: 100%;
      flex-direction: column;
      gap: clamp(0.28rem, 0.58vh, 0.46rem);
      justify-content: space-between;
      overflow: hidden;
      scroll-snap-align: center;
    }

    .company-mobile-card .lead-card-head a {
      display: block;
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      font-family: var(--otziv-card-title-font);
      font-size: 0.98rem;
      font-weight: 1000;
      line-height: 1.08;
      text-decoration: none;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .order-note-badge {
      display: inline-grid;
      width: 1.24rem;
      height: 1.24rem;
      min-width: 1.24rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 999px;
      padding: 0;
      color: #4d3900;
      background: #f4c542;
      font: 1000 0.78rem/1 var(--otziv-font-family);
    }

    .company-note-editor {
      flex: 0 0 clamp(4.05rem, 10.8vh, 4.8rem);
      min-height: 4.05rem;
      max-height: 4.8rem;
    }

    .company-mobile-card .lead-meta-row {
      gap: 0.55rem;
    }

    .company-mobile-card .lead-meta-row span,
    .company-mobile-card .lead-meta-row button {
      min-height: 1.62rem;
      padding: 0 0.56rem;
      color: var(--otziv-dark);
      font-size: 0.7rem;
      font-weight: 900;
      line-height: 1;
    }

    .company-mobile-card .lead-meta-row button {
      display: inline-flex;
      min-width: 0;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 999px;
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font: inherit;
      text-decoration: none;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .company-next-order {
      display: inline-flex;
      align-items: center;
      gap: 0.28rem;
      min-height: 1.8rem;
      border: 1px solid rgba(214, 159, 43, 0.26);
      border-radius: 999px;
      margin: 0;
      padding: 0 0.58rem;
      overflow: hidden;
      color: #8a6a11;
      background: var(--otziv-tone-wait-surface);
      font-size: 0.66rem;
      font-weight: 900;
      line-height: 1;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .company-next-order--failed {
      border-color: rgba(255, 0, 96, 0.24);
      color: var(--otziv-danger);
      background: var(--otziv-tone-correction-surface);
    }

    .company-next-order .material-icons-sharp {
      flex: 0 0 auto;
      font-size: 1rem;
    }

    .company-card-actions {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem 0.56rem;
      flex: 0 0 auto;
    }

    .company-card-actions button {
      min-height: 1.55rem;
      border-radius: 999px;
      padding-inline: 0.18rem;
      font-size: 0.56rem;
      font-weight: 900;
      line-height: 1;
    }

    .company-filial-count {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: 0.55rem;
      flex: 0 0 auto;
    }

    .company-filial-count span,
    .company-filial-count strong {
      display: grid;
      min-height: 1.62rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font-size: 0.7rem;
      font-weight: 900;
    }

    .company-details-button {
      display: grid;
      flex: 0 0 auto;
      width: 100%;
      min-height: 1.55rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 999px;
      color: var(--otziv-info);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font-size: 0.56rem;
      font-weight: 1000;
      line-height: 1;
      text-transform: uppercase;
    }

    .company-mobile-card .lead-card-foot button {
      min-width: 0;
      overflow: hidden;
      border: 0;
      padding: 0;
      color: var(--otziv-info);
      background: transparent;
      font: inherit;
      font-size: 0.64rem;
      font-weight: 900;
      text-decoration: none;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .company-mobile-card .lead-card-foot button:last-child {
      text-align: right;
      text-decoration: underline;
    }
  `]
})
export class MobileCompanyCardComponent {
  @Input({ required: true }) company!: CompanyItem;
  @Input() actions: readonly CompanyStatusAction[] = [];
  @Input() copiedKey: string | null = null;
  @Input() mutationKey: string | null = null;
  @Input() note = '';
  @Input() noteStateLabel = '';
  @Input() noteState: ManagerNoteSaveState = 'idle';

  @Output() copyPhone = new EventEmitter<CompanyItem>();
  @Output() createOrder = new EventEmitter<CompanyItem>();
  @Output() focusNote = new EventEmitter<CompanyItem>();
  @Output() noteChange = new EventEmitter<string>();
  @Output() noteSave = new EventEmitter<void>();
  @Output() statusChange = new EventEmitter<CompanyStatusAction>();
  @Output() openOrders = new EventEmitter<CompanyItem>();
  @Output() showAllOrders = new EventEmitter<void>();
  @Output() edit = new EventEmitter<CompanyItem>();

  get tone(): 'default' | 'new' | 'work' | 'send' {
    const status = (this.company.status ?? '').trim().toLocaleLowerCase('ru-RU');
    if (status.includes('нов')) {
      return 'new';
    }
    if (status.includes('работ')) {
      return 'work';
    }
    if (status.includes('рассыл') || status.includes('ожидан')) {
      return 'send';
    }
    return 'default';
  }

  get chatUrl(): string {
    return (this.company.telegramBotInviteUrl ?? '').trim()
      || (this.company.maxBotInviteUrl ?? '').trim()
      || (this.company.urlChat ?? '').trim()
      || phoneHref(this.company.telephone);
  }

  get phone(): string {
    return displayPhone(this.company.telephone);
  }

  get noteBadge(): string {
    const hasNote = Boolean((this.company.commentsCompany ?? '').trim());
    const hasOrderRequest = this.hasNextOrderRequest;
    if (hasNote && hasOrderRequest) {
      return '2 метки';
    }
    if (hasNote) {
      return 'заметка';
    }
    if (hasOrderRequest) {
      return this.hasFailedNextOrderRequest ? 'ошибка' : 'заказ';
    }
    return '';
  }

  get hasNextOrderRequest(): boolean {
    return (this.company.nextOrderRequestsCount ?? 0) > 0;
  }

  get hasFailedNextOrderRequest(): boolean {
    return (this.company.failedNextOrderRequestsCount ?? 0) > 0;
  }

  get nextOrderRequestLabel(): string {
    const count = this.company.nextOrderRequestsCount ?? 0;
    const filial = (this.company.nextOrderRequestFilialTitle ?? '').trim();
    if (count <= 1) {
      const label = this.hasFailedNextOrderRequest ? 'Автозаказ не создан' : 'Нужен заказ';
      return filial ? `${label}: ${filial}` : label;
    }
    return filial ? `Нужны заказы: ${count}, ${filial}` : `Нужны заказы: ${count}`;
  }

  isMutating(action: CompanyStatusAction): boolean {
    return this.mutationKey === `company-${this.company.id}-${action.status}`;
  }
}
