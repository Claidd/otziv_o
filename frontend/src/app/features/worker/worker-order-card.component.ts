import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import type { OrderCardItem } from '../../core/manager.api';
import type { WorkerPermissions, WorkerSection } from '../../core/worker.api';
import { CompanyNoteTriggerComponent } from '../../shared/company-note-trigger.component';
import { formatPhoneForDisplay, phoneDigits } from '../../shared/phone-format';
import {
  DEFAULT_WORKER_PERMISSIONS,
  StatusAction,
  trackWorkerAction,
  workerLegacyUrl
} from './worker-board.config';
import {
  workerHasMeaningfulNote,
  workerOrderNoteMutationKey,
  workerOrderNoteText,
  workerShouldShowExpander
} from './worker-board-note.helpers';

type CategoryPopover = 'category' | 'subcategory';

@Component({
  selector: 'app-worker-order-card',
  imports: [CompanyNoteTriggerComponent, FormsModule, RouterLink],
  templateUrl: './worker-order-card.component.html',
  styleUrl: './worker-card.component.scss'
})
export class WorkerOrderCardComponent {
  @Input() order!: OrderCardItem;
  @Input() permissions: WorkerPermissions = DEFAULT_WORKER_PERMISSIONS;
  @Input() actions: StatusAction[] = [];
  @Input() activeSection: WorkerSection = 'new';
  @Input() copied: string | null = null;
  @Input() mutationKey: string | null = null;
  @Input() noteDraft = '';
  @Input() noteEditing = false;
  @Input() noteSaved = false;
  @Input() noteChanged = false;
  @Input() noteExpanded = false;
  @Input() canOpenEditModal = true;
  activeCategoryPopover: CategoryPopover | null = null;

  @Output() readonly companyNoteSaved = new EventEmitter<string>();
  @Output() readonly phoneCopied = new EventEmitter<string | undefined>();
  @Output() readonly copyTextRequested = new EventEmitter<'check' | 'payment'>();
  @Output() readonly statusUpdated = new EventEmitter<StatusAction>();
  @Output() readonly noteEditStarted = new EventEmitter<void>();
  @Output() readonly noteDraftChanged = new EventEmitter<string>();
  @Output() readonly noteEditCanceled = new EventEmitter<void>();
  @Output() readonly noteSaveRequested = new EventEmitter<void>();
  @Output() readonly noteToggled = new EventEmitter<Event>();
  @Output() readonly editOpened = new EventEmitter<void>();

  hasMeaningfulNote(value?: string | null): boolean {
    return workerHasMeaningfulNote(value);
  }

  orderAmountLabel(): string {
    if (this.permissions.canSeeMoney) {
      return `${this.order.totalSumWithBadReviews ?? this.order.sum ?? 0} руб.`;
    }

    return `${this.order.amount ?? 0} шт.`;
  }

  showBadReviewSummary(): boolean {
    return this.order.status !== 'Оплачено' && (this.order.badReviewTasksTotal ?? 0) > 0;
  }

  orderChatUrl(): string {
    return this.order.companyUrlChat || `tel:${this.order.companyTelephone ?? ''}`;
  }

  orderPhoneLabel(): string {
    return formatPhoneForDisplay(this.order.companyTelephone);
  }

  orderPhoneForCopy(): string {
    return phoneDigits(this.order.companyTelephone);
  }

  categoryLabel(): string {
    return this.order.categoryTitle || 'Категория';
  }

  subCategoryLabel(): string {
    return this.order.subCategoryTitle || 'Подкатегория';
  }

  toggleCategoryPopover(field: CategoryPopover): void {
    this.activeCategoryPopover = this.activeCategoryPopover === field ? null : field;
  }

  categoryPopoverText(): string | null {
    if (this.activeCategoryPopover === 'category') {
      return this.categoryLabel();
    }

    if (this.activeCategoryPopover === 'subcategory') {
      return this.subCategoryLabel();
    }

    return null;
  }

  orderDetailsUrl(): string {
    return workerLegacyUrl(`/ordersDetails/${this.order.companyId}/${this.order.id}`);
  }

  orderEditUrl(): string {
    return workerLegacyUrl(`/ordersCompany/ordersDetails/${this.order.companyId}/${this.order.id}`);
  }

  progress(): number {
    if (!this.order.amount || !this.order.counter) {
      return 0;
    }

    return Math.max(0, Math.min(100, Math.round((this.order.counter / this.order.amount) * 100)));
  }

  orderNoteText(): string {
    return workerOrderNoteText(this.order);
  }

  orderNoteMutationKey(): string {
    return workerOrderNoteMutationKey(this.order);
  }

  shouldShowExpander(text?: string | null): boolean {
    return workerShouldShowExpander(text);
  }

  isMutating(key: string): boolean {
    return this.mutationKey === key;
  }

  canOpenInlineEdit(): boolean {
    return (this.activeSection === 'new' || this.activeSection === 'correct') && this.canOpenEditModal;
  }

  trackAction(index: number, action: StatusAction): string {
    return trackWorkerAction(index, action);
  }
}
