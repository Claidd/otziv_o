import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  ManagerApi,
  WorkerRiskIncident,
  WorkerRiskResolutionAction
} from '../../../core/manager.api';
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

const ORDER_LIST_STATUSES = new Set([
  'Все',
  'Новый',
  'В проверку',
  'На проверке',
  'Коррекция',
  'Публикация',
  'Опубликовано',
  'Ожидает общего счета',
  'Выставлен счет',
  'Напоминание',
  'Требует внимания',
  'Не оплачено',
  'Бан',
  'Оплачено',
  'Архив'
]);

@Component({
  selector: 'app-manager-control',
  imports: [AdminLayoutComponent, DatePipe, FormsModule, LoadErrorCardComponent],
  templateUrl: './manager-control.component.html',
  styleUrl: './manager-control.component.scss'
})
export class ManagerControlComponent {
  private readonly api = inject(ManagerControlApi);
  private readonly managerApi = inject(ManagerApi);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly summary = signal<ManagerControlSummary | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly updatingItemIds = signal<Set<number>>(new Set());
  readonly detail = signal<ManagerControlManagerDetail | null>(null);
  readonly detailLoading = signal(false);
  readonly detailError = signal<string | null>(null);
  readonly detailComments = signal<Record<number, string>>({});
  readonly detailConcreteComments = signal<Record<number, string>>({});
  readonly unansweredReplyDrafts = signal<Record<number, string>>({});
  readonly updatingConcreteItemIds = signal<Set<number>>(new Set());
  readonly preparedContactItemIds = signal<Set<number>>(new Set());
  readonly updatingControl = signal(false);
  readonly selectedManagerId = signal<number | null>(null);
  readonly detailPageManagerId = signal<number | null>(null);
  readonly isDetailPage = computed(() => this.detailPageManagerId() !== null);

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
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const rawManagerId = params.get('managerId');
      const managerId = rawManagerId ? Number(rawManagerId) : null;
      if (managerId && Number.isFinite(managerId)) {
        this.detailPageManagerId.set(managerId);
        this.selectedManagerId.set(managerId);
        this.loadDetails(managerId);
        return;
      }
      this.detailPageManagerId.set(null);
      this.clearDetails();
    });
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.today().subscribe({
      next: (summary) => {
        this.summary.set(summary);
        const selectedId = this.detailPageManagerId() ?? this.selectedManagerId();
        if (this.detailPageManagerId()) {
          this.selectedManagerId.set(this.detailPageManagerId());
        } else if (!selectedId || !summary.managers.some((manager) => manager.managerId === selectedId)) {
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

  otherCriticalCount(manager: ManagerControlManager): number {
    return Math.max(0, manager.criticalCount - manager.overdueOrderCount - manager.openRiskCount);
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
    if (item.group === 'ACTION') {
      return item.examples.length > 0 || item.itemStatus !== 'OPEN' || !!item.comment;
    }
    return item.itemStatus !== 'OPEN' || !!item.comment || item.examples.some((example) =>
      !!example.comment || !!example.actionType || (!!example.itemStatus && example.itemStatus !== 'OPEN')
    );
  }

  detailItemVisibleCount(item: ManagerControlItemDetail): number {
    return item.group === 'ACTION' ? item.examples.length : item.count;
  }

  selectManager(manager: ManagerControlManager): void {
    this.selectedManagerId.set(manager.managerId);
  }

  openDetails(manager: ManagerControlManager): void {
    this.selectManager(manager);
    void this.router.navigate(['/admin/manager-control', manager.managerId]);
  }

  private loadDetails(managerId: number): void {
    this.detail.set(null);
    this.detailError.set(null);
    this.detailLoading.set(true);
    this.preparedContactItemIds.set(new Set());
    this.api.managerDetails(managerId).subscribe({
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
    if (this.isDetailPage()) {
      void this.router.navigate(['/admin/manager-control']);
      return;
    }
    this.clearDetails();
  }

  private clearDetails(): void {
    this.detail.set(null);
    this.detailError.set(null);
    this.detailLoading.set(false);
    this.detailComments.set({});
    this.detailConcreteComments.set({});
    this.preparedContactItemIds.set(new Set());
    this.updatingControl.set(false);
  }

  markStage(stage: 'MORNING_DONE' | 'FINAL_CHECK'): void {
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
          this.loadDetails(currentDetail.managerId);
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
    options: { manualWorkerNotification?: boolean; comment?: string | null } = {}
  ): void {
    const itemId = example.controlEntityId;
    if (!itemId || this.isConcreteUpdating(itemId)) {
      return;
    }
    if (this.requiresPreparedContact(example, actionType)) {
      this.toast.error('Сначала подготовьте сообщение', 'Нажмите «Текст», отправьте его клиенту, затем отметьте действие');
      return;
    }
    const comment = options.comment ?? this.concreteActionComment(example, actionType);
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

  sendClientMessage(example: ManagerControlConcreteItem): void {
    const itemId = example.controlEntityId;
    if (!itemId || this.isConcreteUpdating(itemId)) {
      return;
    }
    if (!this.canSendClientMessage(example)) {
      this.toast.error('Сообщение не собрано', 'Для этой карточки нет готового текста клиенту');
      return;
    }
    this.updatingConcreteItemIds.update((ids) => new Set(ids).add(itemId));
    this.api.sendClientMessage(itemId).subscribe({
      next: (updated) => {
        this.updatingConcreteItemIds.update((ids) => {
          const next = new Set(ids);
          next.delete(itemId);
          return next;
        });
        const merged = this.patchDetailConcreteItem(example, updated);
        this.toast.success('Сообщение отправлено', 'Карточка уйдет из контроля до повторной проверки');
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
        this.toast.error('Сообщение не отправлено', apiErrorMessage(err, 'Не удалось отправить сообщение клиенту'));
      }
    });
  }

  repairConcreteItem(example: ManagerControlConcreteItem): void {
    const itemId = example.controlEntityId;
    if (!itemId || this.isConcreteUpdating(itemId)) {
      return;
    }
    this.updatingConcreteItemIds.update((ids) => new Set(ids).add(itemId));
    this.api.repairConcreteItem(itemId).subscribe({
      next: (updated) => {
        this.updatingConcreteItemIds.update((ids) => {
          const next = new Set(ids);
          next.delete(itemId);
          return next;
        });
        const merged = this.patchDetailConcreteItem(example, updated);
        this.toast.success('Карточка починена', merged.comment || 'Проблема устранена автоматически');
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
        this.toast.error('Не удалось починить', apiErrorMessage(err, 'Автоматическая починка не сработала'));
      }
    });
  }

  requestWorkerTask(example: ManagerControlConcreteItem): void {
    const itemId = example.controlEntityId;
    if (itemId && !this.detailConcreteComment(itemId)) {
      this.updateConcreteComment(itemId, this.workerTaskRequestComment(example));
    }
    this.markConcreteItem(example, 'ACTION_TAKEN');
  }

  markUnansweredAnswered(example: ManagerControlConcreteItem): void {
    this.markConcreteItem(example, 'ACTION_TAKEN', { comment: 'Ответ клиенту проверен вручную' });
  }

  markUnansweredNoResponseNeeded(example: ManagerControlConcreteItem): void {
    this.markConcreteItem(example, 'ACKNOWLEDGED', { comment: 'Сообщение клиента не требует ответа' });
  }

  unansweredReplyDraft(itemId: number | null | undefined): string {
    return itemId ? this.unansweredReplyDrafts()[itemId] ?? '' : '';
  }

  updateUnansweredReplyDraft(itemId: number | null | undefined, value: string): void {
    if (!itemId) {
      return;
    }
    this.unansweredReplyDrafts.update((drafts) => ({ ...drafts, [itemId]: value }));
  }

  sendUnansweredReply(example: ManagerControlConcreteItem): void {
    const itemId = example.controlEntityId;
    const message = this.unansweredReplyDraft(itemId).trim();
    if (!itemId || this.isConcreteUpdating(itemId)) {
      return;
    }
    if (!message) {
      this.toast.error('Введите ответ', 'Поле ответа клиенту пустое');
      return;
    }
    this.updatingConcreteItemIds.update((ids) => new Set(ids).add(itemId));
    this.api.replyToClientMessage(itemId, { message }).subscribe({
      next: (updated) => {
        this.updatingConcreteItemIds.update((ids) => {
          const next = new Set(ids);
          next.delete(itemId);
          return next;
        });
        this.unansweredReplyDrafts.update((drafts) => {
          const next = { ...drafts };
          delete next[itemId];
          return next;
        });
        const merged = this.patchDetailConcreteItem(example, updated);
        this.toast.success('Ответ отправлен', 'Карточка закрыта как отвеченная');
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
        this.toast.error('Ответ не отправлен', apiErrorMessage(err, 'Не удалось отправить ответ клиенту'));
      }
    });
  }

  resolveRisk(example: ManagerControlConcreteItem): void {
    this.updateRiskIncident(example, 'VERIFIED');
  }

  ignoreRisk(example: ManagerControlConcreteItem): void {
    this.updateRiskIncident(example, 'FALSE_POSITIVE');
  }

  requestRiskExplanation(example: ManagerControlConcreteItem): void {
    this.updateRiskIncident(example, 'EXPLANATION_REQUESTED');
  }

  confirmRiskViolation(example: ManagerControlConcreteItem): void {
    this.updateRiskIncident(example, 'VIOLATION_CONFIRMED', 1);
  }

  requestControlCardExplanation(example: ManagerControlConcreteItem): void {
    const itemId = example.controlEntityId;
    if (itemId && !this.detailConcreteComment(itemId)) {
      this.updateConcreteComment(itemId, this.workerTaskRequestComment(example));
    }
    this.markConcreteItem(example, 'ACTION_TAKEN');
  }

  markControlCardViolation(example: ManagerControlConcreteItem): void {
    const itemId = example.controlEntityId;
    if (itemId && !this.detailConcreteComment(itemId)) {
      this.updateConcreteComment(itemId, 'Нарушение / штраф. Требуется фиксация результата менеджером.');
    }
    this.markConcreteItem(example, 'ACTION_TAKEN');
  }

  contactText(example: ManagerControlConcreteItem): string {
    return (example.contactText ?? '').trim();
  }

  chatUrl(example: ManagerControlConcreteItem): string {
    return (example.chatUrl ?? '').trim();
  }

  statusChatUrl(example: ManagerControlConcreteItem): string {
    const status = (example.status ?? '').trim().toLowerCase();
    if (!['telegram', 'whatsapp', 'max'].includes(status)) {
      return '';
    }
    return this.chatUrl(example);
  }

  canContactOrder(example: ManagerControlConcreteItem): boolean {
    return example.type === 'ORDER' && !!this.contactText(example);
  }

  isUnansweredClientMessage(example: ManagerControlConcreteItem): boolean {
    return example.type === 'CLIENT_CHAT_UNANSWERED';
  }

  clientChatPlatformLabel(example: ManagerControlConcreteItem): string {
    const source = `${example.subtitle ?? ''} ${example.status ?? ''}`.toLowerCase();
    if (source.includes('whatsapp')) {
      return 'WhatsApp';
    }
    if (source.includes('telegram')) {
      return 'Telegram';
    }
    if (/\bmax\b|макс/i.test(source)) {
      return 'MAX';
    }
    return 'Чат';
  }

  canSendClientMessage(example: ManagerControlConcreteItem): boolean {
    return (example.type === 'ORDER' || example.type === 'WORKER_ORDER_NEW') && !!this.contactText(example);
  }

  canRepairAutomationIssue(example: ManagerControlConcreteItem): boolean {
    const reason = (example.reason ?? '').toLowerCase();
    if (example.type === 'COMMON_INVOICE') {
      return reason.includes('нажмите «починить»') || reason.includes('нажмите "починить"');
    }
    if (example.type === 'TELEGRAM_CHAT') {
      return true;
    }
    const repairableWaitingClient = example.type === 'WORKER_ORDER_NEW'
      && this.isWaitingForClientExample(example)
      && (
        reason.includes('client_text_reminder')
        || reason.includes('снимите статус')
        || reason.includes('автоответчик не отправляет')
      );
    const repairableOrderQueue = (example.type === 'ORDER' || example.type === 'WORKER_ORDER_CORRECT')
      && !reason.includes('не может отправить сообщение')
      && !reason.includes('не привязан')
      && (
        reason.includes('записи в очереди')
        || reason.includes('нет активного')
        || reason.includes('нет активной')
        || reason.includes('автоответчик не обработал')
        || reason.includes('автоответчик не закрыл')
      );
    return repairableWaitingClient || repairableOrderQueue;
  }

  isContactTextCopied(example: ManagerControlConcreteItem): boolean {
    const itemId = example.controlEntityId;
    return !!itemId && this.preparedContactItemIds().has(itemId);
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

  contactFollowUpHint(example: ManagerControlConcreteItem): string {
    if (example.followUpAt) {
      return `скроется до ${new Date(example.followUpAt).toLocaleString('ru-RU', {
        day: '2-digit',
        month: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      })}`;
    }
    return 'отправит и скроет до повторной проверки';
  }

  canRequestWorkerTask(example: ManagerControlConcreteItem): boolean {
    return example.type === 'BAD_REVIEW_TASK'
      || example.type === 'RECOVERY_TASK'
      || example.type === 'PUBLISH_REVIEW'
      || example.type === 'NAGUL_REVIEW'
      || example.type === 'WORKER_ORDER_NEW'
      || example.type === 'WORKER_ORDER_CORRECT';
  }

  canOpenOrderFromWorkerTask(example: ManagerControlConcreteItem): boolean {
    return this.canRequestWorkerTask(example)
      && example.type !== 'RISK'
      && this.detailExamplePrimaryUrl(example, null) !== '#';
  }

  canShowWorkerClientAction(example: ManagerControlConcreteItem): boolean {
    return example.type !== 'NAGUL_REVIEW'
      && example.type !== 'PUBLISH_REVIEW'
      && !!this.chatUrl(example);
  }

  workerTaskRequestComment(example: ManagerControlConcreteItem): string {
    return `Специалисту отправлен запрос: ${this.workerProblemLabel(example)}. Повторный контроль через 3 ч.`;
  }

  workerProblemLabel(example: ManagerControlConcreteItem): string {
    switch (example.type) {
      case 'RECOVERY_TASK':
        return 'проверьте восстановление';
      case 'BAD_REVIEW_TASK':
        return 'проверьте плохой отзыв';
      case 'PUBLISH_REVIEW':
        return 'проверьте публикацию';
      case 'NAGUL_REVIEW':
        return 'проверьте выгул';
      case 'WORKER_ORDER_NEW':
        return 'подготовьте текст нового заказа';
      case 'WORKER_ORDER_CORRECT':
        return 'проверьте коррекцию';
      case 'RISK':
        return 'проверьте открытый риск';
      default:
        return 'проверьте проблему';
    }
  }

  controlCardSpecialist(example: ManagerControlConcreteItem): string {
    const explicitName = (example.specialistName ?? '').trim();
    if (explicitName) {
      return explicitName;
    }
    if (example.type === 'COMMON_INVOICE') {
      return '-';
    }
    if (example.type === 'ORDER') {
      return '-';
    }
    return this.specialistNameFromText(example.subtitle)
      || this.specialistNameFromText(example.reason)
      || '-';
  }

  controlCardOrder(example: ManagerControlConcreteItem): string {
    return this.controlCardOrderUrl(example) ? 'Перейти' : '-';
  }

  controlCardOrderUrl(example: ManagerControlConcreteItem): string {
    if (example.type === 'COMMON_INVOICE') {
      return this.commonInvoiceOrdersUrl(example);
    }
    const orderId = this.controlCardOrderId(example);
    if (orderId) {
      return this.orderListUrlForControlCard(example, orderId);
    }
    const url = this.detailExamplePrimaryUrl(example, null);
    return url === '#' ? '' : url;
  }

  controlCardReview(example: ManagerControlConcreteItem): string {
    return this.controlCardReviewUrl(example) ? 'Перейти' : '-';
  }

  controlCardReviewUrl(example: ManagerControlConcreteItem): string {
    const url = this.detailExamplePrimaryUrl(example, null);
    return url === '#' ? '' : url;
  }

  private controlCardOrderId(example: ManagerControlConcreteItem): number | null {
    if (this.isOrderEntityType(example.type) && this.isPositiveNumber(example.entityId)) {
      return example.entityId;
    }

    const url = (example.targetUrl ?? '').trim();
    const pathMatch = url.match(/\/(?:manager\/)?orders\/\d+\/(\d+)(?:[/?#]|$)/);
    if (pathMatch) {
      return Number(pathMatch[1]);
    }

    const keyword = this.urlParam(url, 'keyword');
    if (keyword && /^\d+$/.test(keyword)) {
      return Number(keyword);
    }

    return null;
  }

  private orderListUrlForControlCard(example: ManagerControlConcreteItem, orderId: number): string {
    const params = new URLSearchParams({
      status: this.controlCardOrderStatus(example),
      keyword: String(orderId),
      pageNumber: '0',
      pageSize: '10',
      sortDirection: 'desc'
    });
    const managerId = this.urlParam(example.targetUrl, 'managerId');
    const control = this.urlParam(example.targetUrl, 'control');
    if (managerId && /^\d+$/.test(managerId)) {
      params.set('managerId', managerId);
    }
    if (control) {
      params.set('control', control);
    }
    return `/orders?${params.toString()}`;
  }

  private controlCardOrderStatus(example: ManagerControlConcreteItem): string {
    const explicitStatus = this.normalizeOrderListStatus(example.status);
    if (explicitStatus) {
      return explicitStatus;
    }
    const targetStatus = this.normalizeOrderListStatus(this.urlParam(example.targetUrl, 'status'));
    return targetStatus || 'Все';
  }

  private normalizeOrderListStatus(status: string | null | undefined): string {
    const normalized = (status ?? '').trim();
    return ORDER_LIST_STATUSES.has(normalized) ? normalized : '';
  }

  private isOrderEntityType(type: string | null | undefined): boolean {
    return type === 'ORDER'
      || type === 'WORKER_ORDER_NEW'
      || type === 'WORKER_ORDER_CORRECT';
  }

  private isPositiveNumber(value: number | null | undefined): value is number {
    return typeof value === 'number' && Number.isFinite(value) && value > 0;
  }

  private urlParam(value: string | null | undefined, name: string): string {
    const raw = (value ?? '').trim();
    if (!raw) {
      return '';
    }
    try {
      return new URL(raw, 'http://localhost').searchParams.get(name)?.trim() ?? '';
    } catch {
      return '';
    }
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
    if (example.workerNotificationAcceptedAt) {
      return `работник принял ${this.shortDateTime(example.workerNotificationAcceptedAt)}`;
    }
    if (example.workerNotificationSentAt) {
      return `запрос отправлен в группу ${this.shortDateTime(example.workerNotificationSentAt)}, ждем принятия`;
    }
    if (example.workerNotificationAttemptedAt) {
      const reason = (example.workerNotificationFailureReason ?? '').trim();
      return reason ? `запрос не доставлен: ${reason}` : 'запрос не доставлен';
    }
    return '';
  }

  workerNotificationBadgeLabel(example: ManagerControlConcreteItem): string {
    if (!this.canRequestWorkerTask(example)) {
      return '';
    }
    if (example.workerNotificationAcceptedAt) {
      return 'Принял';
    }
    if (example.workerNotificationSentAt) {
      return 'В группу';
    }
    if (example.workerNotificationAttemptedAt) {
      const reason = (example.workerNotificationFailureReason ?? '').trim();
      return reason.includes('не привязан') ? 'Группа не привязана' : 'Не доставлено';
    }
    return 'Не отправлен';
  }

  workerNotificationBadgeClass(example: ManagerControlConcreteItem): string {
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
    return this.workerNotificationNote(example) || 'Запрос в группу еще не отправлялся';
  }

  riskExplanationRequested(example: ManagerControlConcreteItem): boolean {
    return example.riskResolutionAction === 'EXPLANATION_REQUESTED'
      || example.riskResolutionAction === 'WORKER_WARNED';
  }

  riskExplanationButtonLabel(example: ManagerControlConcreteItem): string {
    if (example.workerExplanation) {
      return 'Ответ получен';
    }
    return example.workerNotificationAcceptedAt ? 'Принято, нужен комментарий' : 'Ждем пояснение';
  }

  riskExplanationButtonIcon(example: ManagerControlConcreteItem): string {
    if (example.workerExplanation) {
      return 'mark_chat_read';
    }
    return example.workerNotificationAcceptedAt ? 'edit_note' : 'hourglass_top';
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

  private specialistNameFromText(value: string | null | undefined): string {
    const text = (value ?? '').replace(/\s+/g, ' ').trim();
    if (!text) {
      return '';
    }
    const parts = text.includes('·')
      ? text.split('·')
      : text.split(/\s[-–—]\s/);
    return parts
      .map((part) => part.trim())
      .find((part) => this.isSpecialistNameCandidate(part)) ?? '';
  }

  private isSpecialistNameCandidate(value: string): boolean {
    const candidate = value.replace(/^(специалист|работник)\s*[:\-]\s*/i, '').trim();
    if (!candidate || candidate === '-') {
      return false;
    }
    const lower = candidate.toLowerCase();
    if (/^(new|warning|planned|open|closed)$/i.test(candidate)) {
      return false;
    }
    if (/^(новый|новые|коррекция|плохие|выгул|публикация|восстановление|на проверке|в проверку|отключен|отключён|общий счет|общий счёт)$/i.test(candidate)) {
      return false;
    }
    return !(
      lower.includes('общий счет')
      || lower.includes('общий счёт')
      || lower.startsWith('план ')
      || lower.startsWith('заказ ')
      || lower.startsWith('статус ')
      || lower.startsWith('сумма ')
      || lower.includes(' без изменений ')
      || lower.includes('ждет клиента')
      || lower.includes('ждёт клиента')
      || lower.includes(' руб')
      || lower.includes(' дн')
      || lower.includes('объект:')
      || lower.includes('действие:')
      || lower.includes('риск:')
      || lower.includes('review_')
      || lower.includes('manual_')
    );
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

  isWaitingForClientExample(example: ManagerControlConcreteItem): boolean {
    const text = `${example.subtitle ?? ''} ${example.reason ?? ''}`.toLowerCase();
    return text.includes('ждет клиента') || text.includes('ждёт клиента');
  }

  exampleIcon(example: ManagerControlConcreteItem): string {
    switch (example.type) {
      case 'RISK':
        return 'warning';
      case 'COMMON_INVOICE':
        return 'receipt_long';
      case 'CLIENT_CHAT_UNANSWERED':
        return 'mark_chat_unread';
      case 'BAD_REVIEW_TASK':
        return 'thumb_down';
      case 'RECOVERY_TASK':
        return 'restore';
      case 'PUBLISH_REVIEW':
        return 'rate_review';
      case 'NAGUL_REVIEW':
        return 'directions_walk';
      case 'WORKER_ORDER_NEW':
        return 'fiber_new';
      case 'WORKER_ORDER_CORRECT':
        return 'edit_note';
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
    this.loadDetails(currentDetail.managerId);
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
    return !!example.itemStatus
      && example.itemStatus !== 'OPEN'
      && (!!example.followUpAt || example.itemStatus === 'RESOLVED');
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
      if (example.workerNotificationSentAt) {
        this.toast.success('Запрос отправлен', 'Сообщение ушло в группу специалиста, ждем кнопку «Принял»');
        return;
      }
      if (example.workerNotificationAttemptedAt) {
        const reason = example.workerNotificationFailureReason?.trim() || 'Telegram не принял сообщение в группу';
        this.toast.error('Запрос не доставлен', reason);
        return;
      }
    }
    this.toast.success('Карточка обновлена', this.actionToast(actionType));
  }

  private updateRiskIncident(
    example: ManagerControlConcreteItem,
    action: WorkerRiskResolutionAction,
    penaltyPoints?: number
  ): void {
    const incidentId = example.entityId;
    const itemId = example.controlEntityId;
    if (!incidentId || !itemId || this.isConcreteUpdating(itemId)) {
      return;
    }
    this.updatingConcreteItemIds.update((ids) => new Set(ids).add(itemId));
    this.managerApi.setWorkerRiskIncidentResolution(incidentId, action, penaltyPoints).subscribe({
      next: (incident) => {
        this.updatingConcreteItemIds.update((ids) => {
          const next = new Set(ids);
          next.delete(itemId);
          return next;
        });
        const merged = this.patchDetailConcreteItem(example, this.riskConcretePatch(example, incident));
        this.toast.success(this.riskActionToast(action));
        if (incident.status !== 'OPEN') {
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
        this.toast.error('Статус риска не изменен', apiErrorMessage(err, 'Не удалось обновить риск'));
      }
    });
  }

  private riskConcretePatch(
    example: ManagerControlConcreteItem,
    incident: WorkerRiskIncident
  ): ManagerControlConcreteItem {
    return {
      ...example,
      status: incident.level,
      reason: incident.details || incident.message || example.reason,
      riskResolutionAction: incident.resolutionAction ?? null,
      workerExplanation: incident.workerExplanation ?? null,
      workerExplanationAt: incident.workerExplanationAt ?? null,
      penaltyPoints: incident.penaltyPoints,
      rollbackStatus: incident.rollbackStatus ?? null,
      rollbackMessage: incident.rollbackMessage ?? null,
      canRollback: incident.canRollback,
      itemStatus: incident.status === 'OPEN' ? 'OPEN' : 'RESOLVED',
      actionType: incident.status === 'OPEN' ? 'ACTION_TAKEN' : 'RESOLVED',
      resolvedAt: incident.resolvedAt ?? example.resolvedAt
    };
  }

  private riskActionToast(action: WorkerRiskResolutionAction): string {
    switch (action) {
      case 'FALSE_POSITIVE':
        return 'Инцидент проигнорирован';
      case 'EXPLANATION_REQUESTED':
      case 'WORKER_WARNED':
        return 'Разъяснение запрошено';
      case 'VIOLATION_CONFIRMED':
        return 'Нарушение зафиксировано';
      default:
        return 'Инцидент проверен';
    }
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

  stageLabel(stage: 'MORNING_DONE' | 'FINAL_CHECK'): string {
    switch (stage) {
      case 'MORNING_DONE':
        return 'Начало дня отмечено';
      default:
        return 'Конец дня отмечен';
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
