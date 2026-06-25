import { DecimalPipe } from '@angular/common';
import { Component, EventEmitter, Input, OnDestroy, Output } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { ClientMessageStatus, OrderCardItem } from '../../core/manager.api';
import { CompanyNoteTriggerComponent } from '../../shared/company-note-trigger.component';
import { formatPhoneForDisplay, phoneDigits } from '../../shared/phone-format';
import {
  ManagerChatBotInviteKind,
  SelectedCompany,
  StatusAction,
  managerOrderChatBindingWarning,
  managerOrderChatBotInviteKind,
  managerHasMeaningfulNote,
  managerOrderChatUrl,
  managerOrderDetailsUrl,
  managerOrderHeaderUrl,
  managerOrderNeedsChatBot,
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
export class ManagerOrderCardComponent implements OnDestroy {
  @Input() order!: OrderCardItem;
  @Input() selectedCompany: SelectedCompany | null = null;
  @Input() actions: StatusAction[] = [];
  @Input() copied: string | null = null;
  @Input() mutationKey: string | null = null;
  @Input() paymentCopyDisabled = false;
  activeCategoryPopover: CategoryPopover | null = null;
  unchangedCityOpen = false;
  communicationPopoverOpen = false;
  titlePopoverOpen = false;
  private lastTitleTapAt = 0;
  private unchangedCityTimer: ReturnType<typeof setTimeout> | null = null;

  @Output() readonly companyNoteSaved = new EventEmitter<string>();
  @Output() readonly orderNoteSaved = new EventEmitter<string>();
  @Output() readonly phoneCopied = new EventEmitter<string | undefined>();
  @Output() readonly copyTextRequested = new EventEmitter<'review' | 'payment'>();
  @Output() readonly statusUpdated = new EventEmitter<StatusAction>();
  @Output() readonly clientWaitingToggled = new EventEmitter<void>();
  @Output() readonly editOpened = new EventEmitter<void>();
  @Output() readonly chatBotInviteOpened = new EventEmitter<void>();

  ngOnDestroy(): void {
    this.clearUnchangedCityTimer();
  }

  orderChatUrl(): string {
    return this.cleanUrl(managerOrderChatUrl(this.order));
  }

  orderHeaderUrl(): string {
    return this.cleanUrl(managerOrderHeaderUrl(this.order));
  }

  needsChatBot(): boolean {
    return managerOrderNeedsChatBot(this.order);
  }

  chatBotInviteKind(): ManagerChatBotInviteKind {
    return managerOrderChatBotInviteKind(this.order);
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

    return this.order.companyUrlChat ? 'Открыть чат компании' : 'Позвонить компании';
  }

  chatBindingWarning(): string {
    return managerOrderChatBindingWarning(this.order);
  }

  hasCommunicationIndicator(): boolean {
    return this.communicationTone() !== null;
  }

  communicationTone(): 'success' | 'wait' | 'danger' | null {
    if (this.isCommonInvoice()) {
      return this.commonInvoiceCommunicationTone();
    }

    if (this.chatBindingWarning()) {
      return 'danger';
    }

    const status = this.clientMessageStatus();
    if (!status || status.tone === 'muted') {
      return null;
    }

    return status.tone;
  }

  communicationTitle(): string {
    if (this.isCommonInvoice()) {
      return this.commonInvoiceCommunicationTitle();
    }

    const warning = this.chatBindingWarning();
    if (warning) {
      return warning;
    }

    return this.clientMessageStatus()?.label ?? 'Состояние связи';
  }

  communicationDetails(): string[] {
    if (this.isCommonInvoice()) {
      return this.commonInvoiceCommunicationDetails();
    }

    const details: string[] = [];
    const warning = this.chatBindingWarning();
    const status = this.clientMessageStatus();

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

  private commonInvoiceCommunicationTone(): 'success' | 'wait' | 'danger' | null {
    const status = this.commonInvoiceStatusLabel().toLocaleLowerCase('ru-RU');
    const sentAt = this.cleanLabel(this.order.commonInvoiceSentAt);

    if (this.cleanLabel(this.order.commonInvoiceLastError)) {
      return 'danger';
    }
    if (status.includes('требует внимания') || status === 'не оплачено' || status === 'бан') {
      return 'danger';
    }
    if (!sentAt && this.commonInvoiceReadyToSend() && status.includes('ожида')) {
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

  private commonInvoiceCommunicationTitle(): string {
    const status = this.commonInvoiceStatusLabel().toLocaleLowerCase('ru-RU');

    if (this.cleanLabel(this.order.commonInvoiceLastError)) {
      return 'Контроль: ошибка общего счета';
    }
    if (status.includes('требует внимания') || status === 'не оплачено' || status === 'бан') {
      return 'Контроль: общий счет требует внимания';
    }
    if (!this.cleanLabel(this.order.commonInvoiceSentAt) && this.commonInvoiceReadyToSend() && status.includes('ожида')) {
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

  private commonInvoiceCommunicationDetails(): string[] {
    const details: string[] = [];
    const error = this.cleanLabel(this.order.commonInvoiceLastError);
    const ready = this.order.commonInvoiceReadyOrders ?? this.order.counter ?? 0;
    const total = this.order.commonInvoiceTotalOrders ?? this.order.amount ?? 0;
    const paid = this.order.commonInvoicePaidOrders ?? 0;

    if (error) {
      details.push(`Ошибка: ${error}`);
    }
    if (this.commonInvoiceStatusLabel()) {
      details.push(`Статус: ${this.commonInvoiceStatusLabel()}`);
    }
    details.push(`Готово: ${ready}/${total}`);
    details.push(`Оплачено: ${paid}/${total}`);
    if (this.cleanLabel(this.order.commonInvoiceSentAt)) {
      details.push(`Отправлен: ${this.order.commonInvoiceSentAt}`);
    } else if (this.commonInvoiceReadyToSend()) {
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

  private commonInvoiceReadyToSend(): boolean {
    const ready = this.order.commonInvoiceReadyOrders ?? this.order.counter ?? 0;
    const total = this.order.commonInvoiceTotalOrders ?? this.order.amount ?? 0;
    return total > 0 && ready >= total;
  }

  private commonInvoiceStatusLabel(): string {
    return this.cleanLabel(this.order.commonInvoiceStatus) || this.cleanLabel(this.order.status);
  }

  toggleCommunicationPopover(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.titlePopoverOpen = false;
    this.communicationPopoverOpen = !this.communicationPopoverOpen;
  }

  closeCommunicationPopover(): void {
    this.communicationPopoverOpen = false;
  }

  orderFullTitle(): string {
    return this.orderTitleDetails().join('. ');
  }

  orderTitleDetails(): string[] {
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

  private openFilialFromTitle(): void {
    const url = this.orderFilialUrl();
    if (!url) {
      return;
    }

    window.open(url, '_blank', 'noopener');
  }

  clientMessageStatus(): ClientMessageStatus | null {
    return this.order.clientMessageStatus ?? null;
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

  handleChatLinkClick(): void {
    if (this.needsChatBot()) {
      this.chatBotInviteOpened.emit();
    }
  }

  orderDetailsUrl(): string {
    return this.cleanUrl(managerOrderDetailsUrl(this.order));
  }

  orderFilialUrl(): string {
    if (this.isCommonInvoice()) {
      return this.orderDetailsUrl();
    }

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
    if (this.isCommonInvoice()) {
      return false;
    }

    return managerShowBadReviewSummary(this.order);
  }

  hasMeaningfulNote(value?: string | null): boolean {
    return managerHasMeaningfulNote(value);
  }

  isMutating(action: StatusAction): boolean {
    return this.mutationKey === `order-${this.order.id}-${action.status}`;
  }

  canManageClientWaiting(): boolean {
    if (this.isCommonInvoice()) {
      return false;
    }

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

  paymentCopyTitle(): string {
    return this.paymentCopyDisabled
      ? 'Одиночный счет отключен: заказ входит в общий счет'
      : 'Создать или скопировать ссылку на оплату';
  }

  isClientWaitingMutating(): boolean {
    return this.mutationKey === this.clientWaitingMutationKey();
  }

  isUnchangedAlert(): boolean {
    if (this.isCommonInvoice()) {
      return Boolean((this.order.commonInvoiceLastError ?? '').trim());
    }

    return this.unchangedDays() >= 2;
  }

  unchangedDays(): number {
    return Math.max(0, this.order.dayToChangeStatusAgo ?? 0);
  }

  unchangedCityLabel(): string {
    if (this.isCommonInvoice()) {
      return this.order.commonInvoiceLastError || 'Ошибок по общему счету нет';
    }

    return this.cleanLabel(this.order.filialCity) || 'Город филиала не указан';
  }

  unchangedCityTitle(): string {
    if (this.isCommonInvoice()) {
      return this.unchangedCityLabel();
    }

    return `Город филиала: ${this.unchangedCityLabel()}`;
  }

  toggleUnchangedCity(event: Event): void {
    event.preventDefault();
    event.stopPropagation();

    if (this.unchangedCityOpen) {
      this.closeUnchangedCity();
      return;
    }

    this.showUnchangedCity();
  }

  closeUnchangedCity(): void {
    this.clearUnchangedCityTimer();
    this.unchangedCityOpen = false;
  }

  showUnchangedCity(): void {
    this.unchangedCityOpen = true;
    this.scheduleUnchangedCityClose();
  }

  showUnchangedCityFromPointer(event: PointerEvent): void {
    if (event.pointerType && event.pointerType !== 'mouse') {
      return;
    }

    this.showUnchangedCity();
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
    if (this.isCommonInvoice() && this.order.status === 'Ожидает общего счета') {
      return 'wait';
    }

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
      case 'Бан':
        return 'bad';
      default:
        return null;
    }
  }

  workerLabel(): string {
    return this.order.workerUserFio || '-';
  }

  openEdit(event?: Event): void {
    event?.preventDefault();
    event?.stopPropagation();
    this.editOpened.emit();
  }

  isCommonInvoice(): boolean {
    return Boolean(this.order.commonInvoice);
  }

  commonInvoiceSummaryLabel(): string {
    const ready = this.order.commonInvoiceReadyOrders ?? this.order.counter ?? 0;
    const total = this.order.commonInvoiceTotalOrders ?? this.order.amount ?? 0;
    const paid = this.order.commonInvoicePaidOrders ?? 0;
    return `Готово ${ready}/${total}, оплачено ${paid}/${total}`;
  }

  trackAction(index: number, action: StatusAction): string {
    return trackManagerAction(index, action);
  }

  private cleanUrl(value?: string | null): string {
    return (value ?? '').trim();
  }

  private cleanLabel(value?: string | null): string {
    return (value ?? '').trim();
  }

  private scheduleUnchangedCityClose(): void {
    this.clearUnchangedCityTimer();
    this.unchangedCityTimer = setTimeout(() => {
      this.unchangedCityOpen = false;
      this.unchangedCityTimer = null;
    }, 1000);
  }

  private clearUnchangedCityTimer(): void {
    if (!this.unchangedCityTimer) {
      return;
    }

    clearTimeout(this.unchangedCityTimer);
    this.unchangedCityTimer = null;
  }
}
