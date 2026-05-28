import { DatePipe } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { LeadItem } from '../../core/api.service';
import { MobileNoteEditorComponent } from '../../shared/mobile-note-editor.component';
import { displayPhone } from '../../shared/phone-format';
import {
  type LeadCommentSaveState,
  type LeadContactLink,
  type LeadMutation,
  leadActionIcon,
  leadActionLabel,
  leadAddressLine,
  leadCategoryLine,
  leadContactLinks,
  leadStatusActions,
  leadTitle,
  leadTone
} from './lead-board.helpers';

@Component({
  selector: 'app-mobile-lead-card',
  standalone: true,
  imports: [DatePipe, MobileNoteEditorComponent],
  template: `
    <article [class]="'lead-card mobile-lead-card lead-card--' + tone">
      <header class="lead-card-head">
        <strong>{{ title }}</strong>
        <span>
          <small>{{ lead.companyName ? (lead.lidStatus || 'Без статуса') + ' / #' + lead.id : '#' + lead.id }}</small>
          @if (lead.offer) {
            <em>!</em>
          }
        </span>
      </header>

      <div class="lead-phone-row" [class.lead-phone-row--offer]="lead.offer">
        <button class="lead-phone-open" type="button" (click)="contact.emit(lead)">
          {{ phoneDisplay }}
        </button>
        <button type="button" (click)="copy.emit(lead)" aria-label="Скопировать телефон">
          {{ copiedLeadId === lead.id ? '✓' : 'T' }}
        </button>
      </div>

      <div class="lead-meta-row">
        <span>{{ lead.createDate ? (lead.createDate | date: 'yyyy-MM-dd') : '-' }}</span>
        <span>{{ lead.cityLead || 'Нет' }}</span>
      </div>

      @if (categoryLine || addressLine) {
        <div class="lead-business-stack">
          @if (categoryLine) {
            <p class="lead-business-line lead-business-line--category" [attr.aria-label]="categoryLine">{{ categoryLine }}</p>
          }
          @if (addressLine) {
            <p class="lead-business-line lead-business-line--address" [attr.aria-label]="addressLine">{{ addressLine }}</p>
          }
        </div>
      }

      @if (contactLinks.length) {
        <div class="lead-contact-row" aria-label="Контакты лида">
          @for (link of contactLinks; track link.key) {
            <a
              [class]="'lead-contact-link lead-contact-link--' + link.key"
              [href]="link.url"
              target="_blank"
              rel="noopener"
              [attr.aria-label]="link.title"
            >
              {{ link.label }}
            </a>
          }
        </div>
      }

      <app-mobile-note-editor
        class="lead-comment-editor"
        [name]="'leadComment' + lead.id"
        placeholder="Комментарий"
        [readOnly]="!canEdit"
        [value]="comment"
        [stateLabel]="commentStateLabel"
        [state]="commentState"
        (valueChange)="commentChange.emit($event)"
        (blurred)="commentSave.emit()"
      />

      <div class="lead-card-actions">
        @for (item of actions; track item) {
          <button type="button" (click)="action.emit(item)" [disabled]="isMutating(item)">
            <span class="material-icons-sharp">{{ actionIcon(item) }}</span>
            {{ isMutating(item) ? '...' : actionLabel(item) }}
          </button>
        }
        @if (canCreateCompany) {
          <button type="button" (click)="createCompany.emit(lead)">
            <span class="material-icons-sharp">business_center</span>
            работа
          </button>
        }
      </div>

      <footer class="lead-card-foot">
        <span>{{ cardPerson }}</span>
        <button type="button" (click)="open.emit(lead)">Подробнее</button>
      </footer>
    </article>
  `,
  styles: [':host { display: contents; }']
})
export class MobileLeadCardComponent {
  @Input({ required: true }) lead!: LeadItem;
  @Input() canEdit = false;
  @Input() canCreateCompany = false;
  @Input() copiedLeadId: number | null = null;
  @Input() comment = '';
  @Input() commentStateLabel = '';
  @Input() commentState: LeadCommentSaveState = 'idle';
  @Input() mutationKey: string | null = null;
  @Input() privilegedActions = false;

  @Output() contact = new EventEmitter<LeadItem>();
  @Output() copy = new EventEmitter<LeadItem>();
  @Output() commentChange = new EventEmitter<string>();
  @Output() commentSave = new EventEmitter<void>();
  @Output() action = new EventEmitter<LeadMutation>();
  @Output() createCompany = new EventEmitter<LeadItem>();
  @Output() open = new EventEmitter<LeadItem>();

  get title(): string {
    return leadTitle(this.lead);
  }

  get tone(): 'default' | 'new' | 'work' | 'send' {
    return leadTone(this.lead);
  }

  get phoneDisplay(): string {
    return displayPhone(this.lead.telephoneLead, '-');
  }

  get categoryLine(): string {
    return leadCategoryLine(this.lead);
  }

  get addressLine(): string {
    return leadAddressLine(this.lead);
  }

  get contactLinks(): LeadContactLink[] {
    return leadContactLinks(this.lead);
  }

  get actions(): LeadMutation[] {
    return leadStatusActions(this.lead, this.privilegedActions);
  }

  get cardPerson(): string {
    const manager = this.personName(this.lead.manager);
    return manager !== '-' ? manager : this.personName(this.lead.operator);
  }

  actionLabel(action: LeadMutation): string {
    return leadActionLabel(action);
  }

  actionIcon(action: LeadMutation): string {
    return leadActionIcon(action);
  }

  isMutating(action: LeadMutation): boolean {
    return this.mutationKey === `${this.lead.id}:${action}`;
  }

  private personName(person?: { fio?: string; username?: string; id?: number }): string {
    return person?.fio || person?.username || (person?.id ? `ID ${person.id}` : '-');
  }
}
