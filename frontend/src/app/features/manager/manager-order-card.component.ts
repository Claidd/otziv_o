import { DecimalPipe } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { OrderCardItem } from '../../core/manager.api';
import { CompanyNoteTriggerComponent } from '../../shared/company-note-trigger.component';
import { formatPhoneForDisplay, phoneDigits } from '../../shared/phone-format';
import {
  SelectedCompany,
  StatusAction,
  managerHasMeaningfulNote,
  managerOrderChatUrl,
  managerOrderDetailsUrl,
  managerOrderReviewUrl,
  managerPayableOrderSum,
  managerProgress,
  managerShowBadReviewSummary,
  trackManagerAction
} from './manager-board.config';

type CategoryPopover = 'category' | 'subcategory';

@Component({
  selector: 'app-manager-order-card',
  imports: [CompanyNoteTriggerComponent, DecimalPipe, RouterLink],
  templateUrl: './manager-order-card.component.html',
  styleUrl: './manager-card.component.scss'
})
export class ManagerOrderCardComponent {
  @Input() order!: OrderCardItem;
  @Input() selectedCompany: SelectedCompany | null = null;
  @Input() actions: StatusAction[] = [];
  @Input() copied: string | null = null;
  @Input() mutationKey: string | null = null;
  activeCategoryPopover: CategoryPopover | null = null;

  @Output() readonly companyNoteSaved = new EventEmitter<string>();
  @Output() readonly orderNoteSaved = new EventEmitter<string>();
  @Output() readonly phoneCopied = new EventEmitter<string | undefined>();
  @Output() readonly copyTextRequested = new EventEmitter<'review' | 'payment'>();
  @Output() readonly statusUpdated = new EventEmitter<StatusAction>();
  @Output() readonly editOpened = new EventEmitter<void>();

  orderChatUrl(): string {
    return managerOrderChatUrl(this.order);
  }

  orderDetailsUrl(): string {
    return managerOrderDetailsUrl(this.order);
  }

  orderReviewUrl(): string {
    return managerOrderReviewUrl(this.order);
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

  payableOrderSum(): number {
    return managerPayableOrderSum(this.order);
  }

  progress(): number {
    return managerProgress(this.order);
  }

  showBadReviewSummary(): boolean {
    return managerShowBadReviewSummary(this.order);
  }

  hasMeaningfulNote(value?: string | null): boolean {
    return managerHasMeaningfulNote(value);
  }

  isMutating(action: StatusAction): boolean {
    return this.mutationKey === `order-${this.order.id}-${action.status}`;
  }

  trackAction(index: number, action: StatusAction): string {
    return trackManagerAction(index, action);
  }
}
