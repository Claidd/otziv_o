import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ManagerControlApi,
  ManagerControlActionType,
  ManagerControlConcreteItem,
  ManagerControlEvent,
  ManagerControlItemDetail,
  ManagerControlItemStatus,
  ManagerControlManagerDetail,
  ManagerControlManager,
  ManagerControlOverdueStatus,
  ManagerControlProblem,
  ManagerControlSection,
  ManagerControlSummary,
  ManagerControlStatus
} from '../../../core/manager-control.api';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';
import { copyTextToClipboard } from '../../../shared/clipboard-copy';

@Component({
  selector: 'app-manager-control',
  imports: [AdminLayoutComponent, DatePipe, FormsModule, LoadErrorCardComponent],
  templateUrl: './manager-control.component.html',
  styleUrl: './manager-control.component.scss'
})
export class ManagerControlComponent {
  private readonly api = inject(ManagerControlApi);
  private readonly toast = inject(ToastService);

  readonly summary = signal<ManagerControlSummary | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly updatingItemIds = signal<Set<number>>(new Set());
  readonly detail = signal<ManagerControlManagerDetail | null>(null);
  readonly detailLoading = signal(false);
  readonly detailError = signal<string | null>(null);
  readonly detailComments = signal<Record<number, string>>({});
  readonly detailConcreteComments = signal<Record<number, string>>({});
  readonly updatingConcreteItemIds = signal<Set<number>>(new Set());
  readonly preparedContactItemIds = signal<Set<number>>(new Set());
  readonly updatingControl = signal(false);
  readonly selectedManagerId = signal<number | null>(null);

  readonly managers = computed(() => {
    return this.summary()?.managers ?? [];
  });
  readonly topManagers = computed(() => this.managers().slice(0, 6));
  readonly selectedManager = computed(() => {
    const managers = this.managers();
    if (managers.length === 0) {
      return null;
    }
    const selectedId = this.selectedManagerId();
    return managers.find((manager) => manager.managerId === selectedId) ?? managers[0];
  });
  readonly visibleDetailItems = computed(() => {
    return (this.detail()?.items ?? []).filter((item) => this.shouldShowDetailItem(item));
  });
  readonly visibleDetailOpenCount = computed(() => {
    return this.visibleDetailItems().filter((item) => item.itemStatus === 'OPEN').length;
  });
  readonly visibleDetailHandledCount = computed(() => {
    return this.visibleDetailItems().filter((item) => item.itemStatus !== 'OPEN').length;
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.today().subscribe({
      next: (summary) => {
        this.summary.set(summary);
        const selectedId = this.selectedManagerId();
        if (!selectedId || !summary.managers.some((manager) => manager.managerId === selectedId)) {
          this.selectedManagerId.set(summary.managers[0]?.managerId ?? null);
        }
        this.loading.set(false);
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Контроль менеджеров не загрузился');
        this.error.set(message);
        this.loading.set(false);
        this.toast.error('Контроль не загружен', message);
      }
    });
  }

  statusLabel(status: ManagerControlStatus): string {
    switch (status) {
      case 'RED':
        return 'Красный';
      case 'YELLOW':
        return 'Желтый';
      default:
        return 'Зеленый';
    }
  }

  statusIcon(status: ManagerControlStatus): string {
    switch (status) {
      case 'RED':
        return 'error';
      case 'YELLOW':
        return 'report_problem';
      default:
        return 'task_alt';
    }
  }

  generatedAt(): string {
    return this.summary()?.generatedAt ?? '';
  }

  summaryDate(): string {
    return this.summary()?.date ?? '';
  }

  redCount(): number {
    return this.summary()?.redCount ?? 0;
  }

  yellowCount(): number {
    return this.summary()?.yellowCount ?? 0;
  }

  greenCount(): number {
    return this.summary()?.greenCount ?? 0;
  }

  attentionTotal(): number {
    return this.summary()?.attentionTotal ?? 0;
  }

  criticalTotal(): number {
    return this.summary()?.criticalTotal ?? 0;
  }

  workloadTotal(): number {
    return this.summary()?.workloadTotal ?? 0;
  }

  managersTotal(): number {
    return this.summary()?.managersTotal ?? 0;
  }

  actionProblems(manager: ManagerControlManager): ManagerControlProblem[] {
    return manager.problems.filter((problem) => problem.group === 'ACTION');
  }

  workloadProblems(manager: ManagerControlManager): ManagerControlProblem[] {
    return manager.problems.filter((problem) => problem.group === 'WORKLOAD');
  }

  actionSections(manager: ManagerControlManager): ManagerControlSection[] {
    return manager.workerSections.filter((section) => section.group === 'ACTION' && section.count > 0);
  }

  workloadSections(manager: ManagerControlManager): ManagerControlSection[] {
    return manager.workerSections.filter((section) => section.group === 'WORKLOAD' && section.count > 0);
  }

  workerActionCount(manager: ManagerControlManager): number {
    return this.actionSections(manager).reduce((sum, section) => sum + section.count, 0);
  }

  workloadProblemAlert(manager: ManagerControlManager, problem: ManagerControlProblem): string {
    if (problem.code === 'ORDERS_WORKLOAD' && manager.overdueOrderCount > 0) {
      return `${manager.overdueOrderCount} проср.`;
    }
    if (problem.code === 'WORKER_WORKLOAD') {
      const actionCount = this.workerActionCount(manager);
      return actionCount > 0 ? `${actionCount} к действию` : '';
    }
    return '';
  }

  hasActionRows(manager: ManagerControlManager): boolean {
    return this.actionProblems(manager).length > 0
      || manager.overdueStatuses.length > 0
      || this.actionSections(manager).length > 0;
  }

  hasWorkloadRows(manager: ManagerControlManager): boolean {
    return this.workloadProblems(manager).length > 0 || this.workloadSections(manager).length > 0;
  }

  shouldShowDetailItem(item: ManagerControlItemDetail): boolean {
    if (item.reasonCode === 'WORKER_ACTIONS') {
      return false;
    }
    if (item.itemType === 'WORKER_SECTION' && item.count > 0) {
      return true;
    }
    if (item.group === 'ACTION') {
      return true;
    }
    return item.itemStatus !== 'OPEN' || !!item.comment || item.examples.some((example) =>
      !!example.comment || !!example.actionType || (!!example.itemStatus && example.itemStatus !== 'OPEN')
    );
  }

  selectManager(manager: ManagerControlManager): void {
    this.selectedManagerId.set(manager.managerId);
  }

  openDetails(manager: ManagerControlManager): void {
    this.selectManager(manager);
    this.detail.set(null);
    this.detailError.set(null);
    this.detailLoading.set(true);
    this.preparedContactItemIds.set(new Set());
    this.api.managerDetails(manager.managerId).subscribe({
      next: (detail) => {
        this.detail.set(detail);
        this.detailComments.set(Object.fromEntries(
          detail.items.map((item) => [item.itemId, item.comment ?? ''])
        ));
        this.detailConcreteComments.set(Object.fromEntries(
          detail.items.flatMap((item) => item.examples.map((example) => [
            example.controlEntityId ?? 0,
            example.comment ?? ''
          ])).filter(([id]) => Number(id) > 0)
        ));
        this.detailLoading.set(false);
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Детализация менеджера не загрузилась');
        this.detailError.set(message);
        this.detailLoading.set(false);
        this.toast.error('Детализация не загружена', message);
      }
    });
  }

  closeDetails(): void {
    this.detail.set(null);
    this.detailError.set(null);
    this.detailLoading.set(false);
    this.detailComments.set({});
    this.detailConcreteComments.set({});
    this.preparedContactItemIds.set(new Set());
    this.updatingControl.set(false);
  }

  markStage(stage: 'MORNING_START' | 'MORNING_DONE' | 'DAY_CHECK' | 'FINAL_CHECK'): void {
    const detail = this.detail();
    if (!detail || this.updatingControl()) {
      return;
    }
    this.updatingControl.set(true);
    this.api.markStage(detail.dailyControlId, { stage }).subscribe({
      next: (updated) => {
        this.detail.set(updated);
        this.updatingControl.set(false);
        this.toast.success('Этап отмечен', this.stageLabel(stage));
        this.load();
      },
      error: (err) => {
        this.updatingControl.set(false);
        this.toast.error('Этап не сохранен', apiErrorMessage(err, 'Не удалось отметить этап контроля'));
      }
    });
  }

  closeDay(): void {
    const detail = this.detail();
    if (!detail || this.updatingControl()) {
      return;
    }
    const comment = window.prompt('Комментарий к закрытию дня', '') ?? '';
    this.updatingControl.set(true);
    this.api.closeDay(detail.dailyControlId, { comment }).subscribe({
      next: (result) => {
        this.updatingControl.set(false);
        if (result.closed) {
          this.toast.success('День закрыт', `Оценка ${result.qualityGrade ?? '-'} · ${result.qualityScore}`);
        } else {
          this.toast.error('День не закрыт', result.blockers.join('; '));
        }
        this.reloadCurrentDetails();
        this.load();
      },
      error: (err) => {
        this.updatingControl.set(false);
        this.toast.error('День не закрыт', apiErrorMessage(err, 'Не удалось закрыть контроль дня'));
      }
    });
  }

  markItem(itemId: number | null | undefined, actionType: ManagerControlActionType): void {
    this.markItemWithComment(itemId, actionType, null);
  }

  markDetailItem(item: ManagerControlItemDetail, actionType: ManagerControlActionType): void {
    this.markItemWithComment(item.itemId, actionType, this.detailComments()[item.itemId] ?? item.comment ?? null);
  }

  private markItemWithComment(itemId: number | null | undefined, actionType: ManagerControlActionType, comment: string | null): void {
    if (!itemId || this.isItemUpdating(itemId)) {
      return;
    }
    this.updatingItemIds.update((ids) => new Set(ids).add(itemId));
    this.api.actionItem(itemId, { actionType, comment }).subscribe({
      next: () => {
        this.updatingItemIds.update((ids) => {
          const next = new Set(ids);
          next.delete(itemId);
          return next;
        });
        this.toast.success('Контроль обновлен', this.actionToast(actionType));
        const currentDetail = this.detail();
        if (currentDetail) {
          const manager = this.managers().find((item) => item.managerId === currentDetail.managerId);
          if (manager) {
            this.openDetails(manager);
          }
        }
        this.load();
      },
      error: (err) => {
        this.updatingItemIds.update((ids) => {
          const next = new Set(ids);
          next.delete(itemId);
          return next;
        });
        this.toast.error('Действие не сохранено', apiErrorMessage(err, 'Не удалось обновить пункт контроля'));
      }
    });
  }

  markConcreteItem(
    example: ManagerControlConcreteItem,
    actionType: ManagerControlActionType,
    options: { manualWorkerNotification?: boolean } = {}
  ): void {
    const itemId = example.controlEntityId;
    if (!itemId || this.isConcreteUpdating(itemId)) {
      return;
    }
    if (this.requiresPreparedContact(example, actionType)) {
      this.toast.error('Сначала подготовьте сообщение', 'Нажмите «Текст», отправьте его клиенту, затем отметьте действие');
      return;
    }
    const comment = this.concreteActionComment(example, actionType);
    this.updatingConcreteItemIds.update((ids) => new Set(ids).add(itemId));
    this.api.actionConcreteItem(itemId, {
      actionType,
      comment,
      manualWorkerNotification: options.manualWorkerNotification ?? null
    }).subscribe({
      next: (updated) => {
        this.updatingConcreteItemIds.update((ids) => {
          const next = new Set(ids);
          next.delete(itemId);
          return next;
        });
        const merged = this.patchDetailConcreteItem(example, updated);
        this.showConcreteActionToast(merged, actionType, options);
        if (this.shouldHideConcreteItemAfterAction(merged)) {
          this.removeConcreteItemFromDetail(merged);
        }
        this.load();
      },
      error: (err) => {
        this.updatingConcreteItemIds.update((ids) => {
          const next = new Set(ids);
          next.delete(itemId);
          return next;
        });
        this.toast.error('Действие не сохранено', apiErrorMessage(err, 'Не удалось обновить карточку контроля'));
      }
    });
  }

  async copyContactText(example: ManagerControlConcreteItem): Promise<void> {
    const text = this.contactText(example);
    if (!text) {
      this.toast.error('Текст не собран', 'У карточки нет текста для ручной отправки');
      return;
    }
    if (await copyTextToClipboard(text)) {
      const itemId = example.controlEntityId;
      if (itemId) {
        this.preparedContactItemIds.update((ids) => new Set(ids).add(itemId));
      }
      this.toast.success('Текст скопирован', 'Можно отправить клиенту в чат');
    } else {
      this.toast.error('Не удалось скопировать', 'Скопируйте текст вручную из карточки заказа');
    }
  }

  markContactSent(example: ManagerControlConcreteItem): void {
    if (!this.canMarkContactSent(example)) {
      this.toast.error('Сначала подготовьте сообщение', 'Нажмите «Текст», отправьте его клиенту, затем отметьте «Отправлено»');
      return;
    }
    this.markConcreteItem(example, 'ACTION_TAKEN');
  }

  requestWorkerTask(example: ManagerControlConcreteItem): void {
    const itemId = example.controlEntityId;
    if (itemId && !this.detailConcreteComment(itemId)) {
      this.updateConcreteComment(itemId, this.workerTaskRequestComment(example));
    }
    this.markConcreteItem(example, 'ACTION_TAKEN');
  }

  markWorkerTaskSentManually(example: ManagerControlConcreteItem): void {
    const itemId = example.controlEntityId;
    if (itemId) {
      this.updateConcreteComment(itemId, this.workerTaskManualComment(example));
    }
    this.markConcreteItem(example, 'ACTION_TAKEN', { manualWorkerNotification: true });
  }

  contactText(example: ManagerControlConcreteItem): string {
    return (example.contactText ?? '').trim();
  }

  chatUrl(example: ManagerControlConcreteItem): string {
    return (example.chatUrl ?? '').trim();
  }

  canContactOrder(example: ManagerControlConcreteItem): boolean {
    return example.type === 'ORDER' && !!this.contactText(example);
  }

  isContactTextCopied(example: ManagerControlConcreteItem): boolean {
    const itemId = example.controlEntityId;
    return !!itemId && this.preparedContactItemIds().has(itemId);
  }

  canMarkContactSent(example: ManagerControlConcreteItem): boolean {
    return this.canContactOrder(example) && this.isContactTextCopied(example);
  }

  requiresPreparedContact(example: ManagerControlConcreteItem, actionType: ManagerControlActionType): boolean {
    return actionType === 'ACTION_TAKEN'
      && this.canContactOrder(example)
      && !this.isContactTextCopied(example);
  }

  concreteActionComment(example: ManagerControlConcreteItem, actionType: ManagerControlActionType): string | null {
    const itemId = example.controlEntityId;
    const existing = itemId ? this.detailConcreteComment(itemId).trim() : '';
    if (existing) {
      return existing;
    }
    const previous = (example.comment ?? '').trim();
    if (previous) {
      return previous;
    }
    return null;
  }

  isConcreteActionDisabled(example: ManagerControlConcreteItem, actionType: ManagerControlActionType): boolean {
    return this.isConcreteUpdating(example.controlEntityId) || this.requiresPreparedContact(example, actionType);
  }

  concreteActionTitle(example: ManagerControlConcreteItem, actionType: ManagerControlActionType, fallback: string): string {
    if (this.requiresPreparedContact(example, actionType)) {
      return 'Сначала нажмите «Текст» и отправьте сообщение клиенту';
    }
    return fallback;
  }

  contactSentDisabledTitle(example: ManagerControlConcreteItem): string {
    if (this.isConcreteUpdating(example.controlEntityId)) {
      return 'Сохраняю действие';
    }
    if (!this.canMarkContactSent(example)) {
      return 'Сначала нажмите «Текст» и отправьте сообщение клиенту';
    }
    return 'Отметить ручную отправку';
  }

  contactFollowUpHint(example: ManagerControlConcreteItem): string {
    if (!this.canMarkContactSent(example)) {
      return 'сначала скопируйте текст';
    }
    if (example.followUpAt) {
      return `скроется до ${new Date(example.followUpAt).toLocaleString('ru-RU', {
        day: '2-digit',
        month: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      })}`;
    }
    return 'скроется до повтора через 2 дня';
  }

  canRequestWorkerTask(example: ManagerControlConcreteItem): boolean {
    return example.type === 'BAD_REVIEW_TASK' || example.type === 'RECOVERY_TASK';
  }

  canMarkWorkerTaskSentManually(example: ManagerControlConcreteItem): boolean {
    return this.canRequestWorkerTask(example)
      && !example.workerNotificationSentAt
      && !example.workerNotificationAcceptedAt
      && !this.isManualWorkerRequest(example);
  }

  workerTaskRequestComment(example: ManagerControlConcreteItem): string {
    const type = example.type === 'RECOVERY_TASK' ? 'восстановлению' : 'плохому отзыву';
    return `Специалисту отправлен запрос выполнить задачу по ${type}. Повторный контроль завтра.`;
  }

  workerTaskManualComment(example: ManagerControlConcreteItem): string {
    const type = example.type === 'RECOVERY_TASK' ? 'восстановлению' : 'плохому отзыву';
    return `Специалисту отправлен запрос вручную выполнить задачу по ${type}. Повторный контроль завтра.`;
  }

  managerActionNote(example: ManagerControlConcreteItem): string {
    const parts: string[] = [];
    if (example.actionType) {
      parts.push(this.itemStatusLabel(example.itemStatus || 'OPEN'));
    }
    if (example.followUpAt && example.itemStatus && example.itemStatus !== 'OPEN') {
      parts.push(`повтор ${new Date(example.followUpAt).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })}`);
    }
    const workerNotification = this.workerNotificationNote(example);
    if (workerNotification) {
      parts.push(workerNotification);
    }
    if (example.comment) {
      parts.push(example.comment);
    }
    return parts.join(' · ');
  }

  workerNotificationNote(example: ManagerControlConcreteItem): string {
    if (!this.canRequestWorkerTask(example)) {
      return '';
    }
    if (this.isManualWorkerRequest(example)) {
      return `запрос отправлен вручную${example.followUpAt ? `, повтор ${this.shortDateTime(example.followUpAt)}` : ''}`;
    }
    if (example.workerNotificationAcceptedAt) {
      return `работник принял ${this.shortDateTime(example.workerNotificationAcceptedAt)}`;
    }
    if (example.workerNotificationSentAt) {
      return `Telegram отправлен ${this.shortDateTime(example.workerNotificationSentAt)}, ждем принятия`;
    }
    if (example.workerNotificationAttemptedAt) {
      const reason = (example.workerNotificationFailureReason ?? '').trim();
      return reason ? `Telegram не доставлен: ${reason}` : 'Telegram не доставлен';
    }
    return '';
  }

  workerNotificationBadgeLabel(example: ManagerControlConcreteItem): string {
    if (!this.canRequestWorkerTask(example)) {
      return '';
    }
    if (this.isManualWorkerRequest(example)) {
      return 'Вручную';
    }
    if (example.workerNotificationAcceptedAt) {
      return 'Принял';
    }
    if (example.workerNotificationSentAt) {
      return 'TG отправлен';
    }
    if (example.workerNotificationAttemptedAt) {
      const reason = (example.workerNotificationFailureReason ?? '').trim();
      return reason.includes('не привязан') ? 'TG не привязан' : 'Не доставлено';
    }
    return 'TG не отправлен';
  }

  workerNotificationBadgeClass(example: ManagerControlConcreteItem): string {
    if (this.isManualWorkerRequest(example)) {
      return 'sent';
    }
    if (example.workerNotificationAcceptedAt) {
      return 'accepted';
    }
    if (example.workerNotificationSentAt) {
      return 'sent';
    }
    if (example.workerNotificationAttemptedAt) {
      return 'failed';
    }
    return 'idle';
  }

  workerNotificationTitle(example: ManagerControlConcreteItem): string {
    return this.workerNotificationNote(example) || 'Telegram еще не отправлялся';
  }

  isManualWorkerRequest(example: ManagerControlConcreteItem): boolean {
    return this.canRequestWorkerTask(example)
      && example.actionType === 'ACTION_TAKEN'
      && /вручн/i.test(example.comment ?? '');
  }

  detailExamplePrimaryUrl(example: ManagerControlConcreteItem, fallbackUrl?: string | null): string {
    if (example.type === 'COMMON_INVOICE') {
      return this.commonInvoiceOrdersUrl(example);
    }
    return example.targetUrl || fallbackUrl || '#';
  }

  detailItemTargetUrl(item: ManagerControlItemDetail): string {
    if (item.reasonCode === 'COMMON_INVOICES') {
      const firstInvoice = item.examples.find((example) => example.type === 'COMMON_INVOICE');
      if (firstInvoice) {
        return this.commonInvoiceDetailUrl(firstInvoice, item.targetUrl);
      }
    }
    return item.targetUrl || '#';
  }

  detailItemTargetLabel(item: ManagerControlItemDetail): string {
    if (item.reasonCode === 'COMMON_INVOICES') {
      return 'Открыть первый счет';
    }
    return 'Открыть раздел';
  }

  commonInvoiceOrdersUrl(example: ManagerControlConcreteItem): string {
    const keyword = this.commonInvoiceSearchKeyword(example);
    const params = new URLSearchParams({
      section: 'orders',
      status: 'Все',
      keyword,
      pageNumber: '0',
      pageSize: '10',
      sortDirection: 'desc'
    });
    return `/orders?${params.toString()}`;
  }

  commonInvoiceDetailUrl(example: ManagerControlConcreteItem, fallbackUrl?: string | null): string {
    const invoiceId = example.entityId ?? this.commonInvoiceIdFromUrl(example.targetUrl) ?? this.commonInvoiceIdFromUrl(fallbackUrl);
    if (invoiceId) {
      return `/admin/common-billing?invoiceId=${invoiceId}`;
    }
    return example.targetUrl || fallbackUrl || '/admin/common-billing';
  }

  shortDateTime(value: string): string {
    return new Date(value).toLocaleString('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  exampleIcon(example: ManagerControlConcreteItem): string {
    switch (example.type) {
      case 'RISK':
        return 'warning';
      case 'COMMON_INVOICE':
        return 'receipt_long';
      case 'BAD_REVIEW_TASK':
        return 'thumb_down';
      case 'RECOVERY_TASK':
        return 'restore';
      case 'PUBLISH_REVIEW':
        return 'rate_review';
      default:
        return 'inventory_2';
    }
  }

  private commonInvoiceSearchKeyword(example: ManagerControlConcreteItem): string {
    const title = (example.title ?? '').trim();
    const subtitle = (example.subtitle ?? '').trim();
    const cleanedTitle = title
      .replace(/\s+-\s+общий счет(\s+-\s+общий счет)?\s*$/i, '')
      .trim();
    if (cleanedTitle) {
      return cleanedTitle;
    }
    const subtitleName = subtitle.split('·')[0]?.trim();
    return subtitleName || title || `ОС${example.entityId ?? ''}`;
  }

  private commonInvoiceIdFromUrl(value?: string | null): number | null {
    const raw = (value ?? '').trim();
    if (!raw) {
      return null;
    }
    const match = raw.match(/[?&]invoiceId=(\d+)/i);
    return match ? Number(match[1]) : null;
  }

  private reloadCurrentDetails(): void {
    const currentDetail = this.detail();
    if (!currentDetail) {
      return;
    }
    const manager = this.managers().find((item) => item.managerId === currentDetail.managerId);
    if (manager) {
      this.openDetails(manager);
    }
  }

  private patchDetailConcreteItem(previous: ManagerControlConcreteItem, updated: ManagerControlConcreteItem): ManagerControlConcreteItem {
    const itemId = updated.controlEntityId ?? previous.controlEntityId;
    const merged: ManagerControlConcreteItem = {
      ...previous,
      ...updated,
      controlEntityId: itemId,
      contactText: updated.contactText ?? previous.contactText
    };
    if (!itemId) {
      return merged;
    }
    this.detail.update((detail) => {
      if (!detail) {
        return detail;
      }
      return {
        ...detail,
        items: detail.items.map((item) => ({
          ...item,
          examples: item.examples.map((example) =>
            example.controlEntityId === itemId ? { ...example, ...merged } : example
          )
        }))
      };
    });
    this.detailConcreteComments.update((comments) => ({
      ...comments,
      [itemId]: merged.comment ?? ''
    }));
    return merged;
  }

  private shouldHideConcreteItemAfterAction(example: ManagerControlConcreteItem): boolean {
    return !!example.followUpAt
      && !!example.itemStatus
      && example.itemStatus !== 'OPEN';
  }

  private removeConcreteItemFromDetail(example: ManagerControlConcreteItem): void {
    const itemId = example.controlEntityId;
    if (!itemId) {
      return;
    }
    this.detail.update((detail) => {
      if (!detail) {
        return detail;
      }
      return {
        ...detail,
        items: detail.items.map((item) => {
          const examples = item.examples.filter((candidate) => candidate.controlEntityId !== itemId);
          const removed = item.examples.length - examples.length;
          return removed > 0
            ? {
                ...item,
                count: Math.max(0, item.count - removed),
                examples,
                hiddenExampleCount: Math.max(0, item.hiddenExampleCount - removed)
              }
            : item;
        })
      };
    });
  }

  private showConcreteActionToast(
    example: ManagerControlConcreteItem,
    actionType: ManagerControlActionType,
    options: { manualWorkerNotification?: boolean } = {}
  ): void {
    if (actionType === 'ACTION_TAKEN' && this.canRequestWorkerTask(example)) {
      if (options.manualWorkerNotification || this.isManualWorkerRequest(example)) {
        this.toast.success('Ручная отправка отмечена', 'Карточка уйдет из контроля до повторной проверки');
        return;
      }
      if (example.workerNotificationSentAt) {
        this.toast.success('Запрос отправлен', 'Telegram отправлен работнику, ждем кнопку «Принял»');
        return;
      }
      if (example.workerNotificationAttemptedAt) {
        const reason = example.workerNotificationFailureReason?.trim() || 'Telegram не принял сообщение';
        this.toast.error('Telegram не доставлен', reason);
        return;
      }
    }
    this.toast.success('Карточка обновлена', this.actionToast(actionType));
  }

  updateDetailComment(itemId: number, comment: string): void {
    this.detailComments.update((comments) => ({ ...comments, [itemId]: comment }));
  }

  detailComment(itemId: number): string {
    return this.detailComments()[itemId] ?? '';
  }

  updateConcreteComment(itemId: number | null | undefined, comment: string): void {
    if (!itemId) {
      return;
    }
    this.detailConcreteComments.update((comments) => ({ ...comments, [itemId]: comment }));
  }

  detailConcreteComment(itemId: number | null | undefined): string {
    return itemId ? this.detailConcreteComments()[itemId] ?? '' : '';
  }

  isItemUpdating(itemId: number | null | undefined): boolean {
    return !!itemId && this.updatingItemIds().has(itemId);
  }

  isConcreteUpdating(itemId: number | null | undefined): boolean {
    return !!itemId && this.updatingConcreteItemIds().has(itemId);
  }

  canAct(item: { itemId?: number | null; itemStatus?: ManagerControlItemStatus | null; group?: string }): boolean {
    return !!item.itemId && item.group === 'ACTION' && item.itemStatus === 'OPEN';
  }

  itemStatusLabel(status: ManagerControlItemStatus | null | undefined): string {
    switch (status) {
      case 'ACKNOWLEDGED':
        return 'проверено';
      case 'ACTION_TAKEN':
        return 'в работе';
      case 'DEFERRED':
        return 'отложено';
      case 'RESOLVED':
        return 'закрыто';
      case 'OPEN':
        return 'открыто';
      default:
        return '';
    }
  }

  itemStatusClass(status: ManagerControlItemStatus | null | undefined): string {
    return status ? status.toLowerCase().replace('_', '-') : 'open';
  }

  itemActionClass(actionType: ManagerControlActionType | null | undefined): string {
    return actionType ? `action-${actionType.toLowerCase().replace('_', '-')}` : '';
  }

  severityLabel(severity: string | null | undefined): string {
    switch (severity) {
      case 'CRITICAL':
        return 'Критично';
      case 'WARNING':
        return 'Внимание';
      default:
        return 'Инфо';
    }
  }

  groupLabel(group: string | null | undefined): string {
    return group === 'ACTION' ? 'К действию' : 'Нагрузка';
  }

  stageLabel(stage: 'MORNING_START' | 'MORNING_DONE' | 'DAY_CHECK' | 'FINAL_CHECK'): string {
    switch (stage) {
      case 'MORNING_START':
        return 'Утренний обход начат';
      case 'MORNING_DONE':
        return 'Утренний обход закрыт';
      case 'DAY_CHECK':
        return 'Дневной контроль';
      default:
        return 'Финальная проверка';
    }
  }

  stageDone(field: keyof Pick<ManagerControlManagerDetail, 'morningStartedAt' | 'morningCompletedAt' | 'dayCheckedAt' | 'finalCheckedAt'>): boolean {
    return !!this.detail()?.[field];
  }

  eventLabel(event: ManagerControlEvent): string {
    switch (event.eventType) {
      case 'CONTROL_CREATED':
        return 'Контроль создан';
      case 'ITEM_CREATED':
        return 'Пункт добавлен';
      case 'ITEM_ACTION':
        return 'Действие по пункту';
      case 'ITEM_RESOLVED':
        return 'Пункт закрыт';
      case 'CONTROL_STATUS_CHANGED':
        return 'Статус изменен';
      case 'STAGE_MARKED':
        return 'Этап отмечен';
      case 'CLOSE_ATTEMPT_BLOCKED':
        return 'Закрытие заблокировано';
      case 'CONTROL_CLOSED':
        return 'День закрыт';
      case 'QUALITY_RISK':
        return 'Риск качества';
      case 'TEST_NOTIFICATION':
        return 'Тестовое уведомление';
      default:
        return event.eventType;
    }
  }

  eventIcon(event: ManagerControlEvent): string {
    switch (event.eventType) {
      case 'CONTROL_CLOSED':
        return 'verified';
      case 'CLOSE_ATTEMPT_BLOCKED':
      case 'QUALITY_RISK':
        return 'warning';
      case 'TEST_NOTIFICATION':
        return 'notifications';
      case 'STAGE_MARKED':
        return 'flag';
      default:
        return 'history';
    }
  }

  private actionToast(actionType: ManagerControlActionType): string {
    switch (actionType) {
      case 'ACTION_TAKEN':
        return 'Действие зафиксировано';
      case 'DEFERRED':
        return 'Пункт отложен для контроля';
      case 'RESOLVED':
        return 'Пункт закрыт';
      default:
        return 'Пункт отмечен проверенным';
    }
  }

  trackManager(_: number, manager: ManagerControlManager): number {
    return manager.managerId;
  }

  trackProblem(_: number, problem: ManagerControlProblem): string {
    return problem.code;
  }

  trackSection(_: number, section: ManagerControlSection): string {
    return section.code;
  }

  trackOverdueStatus(_: number, overdue: ManagerControlOverdueStatus): string {
    return overdue.status;
  }

  trackDetailItem(_: number, item: ManagerControlItemDetail): number {
    return item.itemId;
  }
}
