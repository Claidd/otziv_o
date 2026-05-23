import { Component, EventEmitter, Input, Output } from '@angular/core';
import type { CompanyCardItem } from '../../core/manager.api';
import { CompanyNoteTriggerComponent } from '../../shared/company-note-trigger.component';
import { formatPhoneForDisplay, phoneDigits } from '../../shared/phone-format';
import {
  ManagerChatBotInviteKind,
  StatusAction,
  managerCompanyChatBindingWarning,
  managerCompanyChatBotInviteKind,
  managerCompanyHeaderUrl,
  managerCompanyChatUrl,
  managerCompanyFilialUrl,
  managerCompanyNeedsChatBot,
  managerCompanyOrderUrl,
  managerHasMeaningfulNote,
  trackManagerAction
} from './manager-board.config';

@Component({
  selector: 'app-manager-company-card',
  imports: [CompanyNoteTriggerComponent],
  templateUrl: './manager-company-card.component.html',
  styleUrl: './manager-card.component.scss'
})
export class ManagerCompanyCardComponent {
  @Input() company!: CompanyCardItem;
  @Input() actions: StatusAction[] = [];
  @Input() copied: string | null = null;
  @Input() mutationKey: string | null = null;

  @Output() readonly noteSaved = new EventEmitter<string>();
  @Output() readonly phoneCopied = new EventEmitter<string | undefined>();
  @Output() readonly orderCreateOpened = new EventEmitter<void>();
  @Output() readonly statusUpdated = new EventEmitter<StatusAction>();
  @Output() readonly ordersOpened = new EventEmitter<void>();
  @Output() readonly allOrdersOpened = new EventEmitter<void>();
  @Output() readonly editOpened = new EventEmitter<void>();
  @Output() readonly chatBotInviteOpened = new EventEmitter<void>();

  companyChatUrl(): string {
    return managerCompanyChatUrl(this.company);
  }

  companyHeaderUrl(): string {
    return managerCompanyHeaderUrl(this.company);
  }

  companyFilialUrl(): string {
    return managerCompanyFilialUrl(this.company);
  }

  companyOrdersUrl(): string {
    return managerCompanyOrderUrl(this.company);
  }

  hasCompanyFilialUrl(): boolean {
    return !!this.companyFilialUrl();
  }

  hasCompanyHeaderUrl(): boolean {
    return !!this.companyHeaderUrl();
  }

  needsChatBot(): boolean {
    return managerCompanyNeedsChatBot(this.company);
  }

  chatBotInviteKind(): ManagerChatBotInviteKind {
    return managerCompanyChatBotInviteKind(this.company);
  }

  chatLinkTitle(): string {
    const warning = this.chatBindingWarning();
    if (warning) {
      return warning;
    }

    const kind = this.chatBotInviteKind();
    if (kind === 'max') {
      return 'Открыть MAX-бота. Если кнопка в MAX не запускается, используйте подсказку с командой /start.';
    }

    if (kind === 'telegram') {
      return 'Добавить Telegram-бота в группу компании';
    }

    return this.company.urlChat ? 'Открыть чат компании' : 'Позвонить компании';
  }

  chatBindingWarning(): string {
    return managerCompanyChatBindingWarning(this.company);
  }

  handleChatLinkClick(): void {
    if (this.needsChatBot()) {
      this.chatBotInviteOpened.emit();
    }
  }

  companyPhoneLabel(): string {
    return formatPhoneForDisplay(this.company.telephone);
  }

  companyPhoneForCopy(): string {
    return phoneDigits(this.company.telephone);
  }

  hasMeaningfulNote(value?: string | null): boolean {
    return managerHasMeaningfulNote(value);
  }

  isMutating(action: StatusAction): boolean {
    return this.mutationKey === `company-${this.company.id}-${action.status}`;
  }

  isWaitTone(): boolean {
    return this.company.status === 'Ожидание';
  }

  isWalkTone(): boolean {
    return this.company.status === 'К рассылке';
  }

  isSuccessTone(): boolean {
    return this.company.status === 'В работе' || this.company.status === 'Новый заказ';
  }

  isBadTone(): boolean {
    return this.company.status === 'На стопе' || this.company.status === 'Бан';
  }

  hasNextOrderRequest(): boolean {
    return (this.company.nextOrderRequestsCount ?? 0) > 0;
  }

  nextOrderRequestLabel(): string {
    const count = this.company.nextOrderRequestsCount ?? 0;
    const filial = this.nextOrderRequestFilialTitle();
    if (count <= 1) {
      const label = (this.company.failedNextOrderRequestsCount ?? 0) > 0
        ? 'Автозаказ не создан'
        : 'Нужен заказ';
      return filial ? `${label}: ${filial}` : label;
    }
    return filial ? `Нужны заказы: ${count}, ${filial}` : `Нужны заказы: ${count}`;
  }

  nextOrderRequestTitle(): string {
    const filial = this.nextOrderRequestFilialTitle();
    const error = (this.company.nextOrderRequestError ?? '').trim();
    if (error) {
      return filial ? `${filial}: ${error}` : error;
    }
    return filial
      ? `Есть открытая заявка на следующий заказ: ${filial}`
      : 'Есть открытая заявка на следующий заказ';
  }

  hasFailedNextOrderRequest(): boolean {
    return (this.company.failedNextOrderRequestsCount ?? 0) > 0;
  }

  private nextOrderRequestFilialTitle(): string {
    return (this.company.nextOrderRequestFilialTitle ?? '').trim();
  }

  trackAction(index: number, action: StatusAction): string {
    return trackManagerAction(index, action);
  }
}
