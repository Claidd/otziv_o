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
type OrderTone = 'wait' | 'walk' | 'correction' | 'publication' | 'success' | 'bad' | null;

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
  @Output() readonly clientWaitingToggled = new EventEmitter<void>();
  @Output() readonly editOpened = new EventEmitter<void>();

  orderChatUrl(): string {
    return this.cleanUrl(managerOrderChatUrl(this.order));
  }

  orderDetailsUrl(): string {
    return this.cleanUrl(managerOrderDetailsUrl(this.order));
  }

  orderFilialUrl(): string {
    return this.cleanUrl(this.order.filialUrl) || this.orderDetailsUrl();
  }

  orderReviewUrl(): string {
    return this.cleanUrl(managerOrderReviewUrl(this.order));
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

  canManageClientWaiting(): boolean {
    return this.order.status === 'Новый' || this.order.status === 'Коррекция' || !!this.order.waitingForClient;
  }

  clientWaitingMutationKey(): string {
    return `order-${this.order.id}-client-waiting`;
  }

  clientWaitingTitle(): string {
    return this.order.waitingForClient ? 'Вернуть заказ в работу специалиста' : 'Заказ ждет текст от клиента';
  }

  clientWaitingLabel(): string {
    return this.order.waitingForClient ? 'ждет клиента' : 'клиент';
  }

  isClientWaitingMutating(): boolean {
    return this.mutationKey === this.clientWaitingMutationKey();
  }

  isUnchangedAlert(): boolean {
    return this.unchangedDays() >= 2;
  }

  unchangedDays(): number {
    return Math.max(0, this.order.dayToChangeStatusAgo ?? 0);
  }

  isWaitTone(): boolean {
    return this.statusTone() === 'wait';
  }

  isWalkTone(): boolean {
    return this.statusTone() === 'walk';
  }

  isCorrectionTone(): boolean {
    return this.statusTone() === 'correction';
  }

  isPublicationTone(): boolean {
    return this.statusTone() === 'publication';
  }

  isSuccessTone(): boolean {
    return this.statusTone() === 'success';
  }

  isBadTone(): boolean {
    return this.statusTone() === 'bad';
  }

  private statusTone(): OrderTone {
    if (this.order.waitingForClient) {
      return null;
    }

    switch (this.order.status) {
      case 'В проверку':
      case 'Выставлен счет':
      case 'Архив':
        return 'walk';
      case 'На проверке':
      case 'Напоминание':
        return 'wait';
      case 'Коррекция':
        return 'correction';
      case 'Публикация':
        return 'publication';
      case 'Опубликовано':
      case 'Оплачено':
        return 'success';
      case 'Не оплачено':
        return 'bad';
      default:
        return null;
    }
  }

  workerLabel(): string {
    return this.order.workerUserFio || '-';
  }

  trackAction(index: number, action: StatusAction): string {
    return trackManagerAction(index, action);
  }

  private cleanUrl(value?: string | null): string {
    return (value ?? '').trim();
  }
}
