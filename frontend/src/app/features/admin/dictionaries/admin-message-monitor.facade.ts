import { computed, signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import type { AdminClientMessageMonitor, AdminClientMessageMaintenancePreview, AdminClientMessageMonitorQueueItem, AdminClientMessageMonitorAttempt, AdminClientMessageMonitorScenario } from '../../../core/admin-dictionaries.api';
import type { AdminMessageMonitorApi } from '../../../core/admin-message-monitor.api';
import type { ToastService } from '../../../shared/toast.service';
import { apiErrorMessage } from '../../../shared/api-error-message';

type DictionaryMetric = { label: string; value: number; icon: string; tone: 'blue' | 'green' | 'teal' | 'yellow' | 'pink'; tooltip?: string };
type MonitorDeps = { api: Pick<AdminMessageMonitorApi, keyof AdminMessageMonitorApi>; toast: Pick<ToastService, 'success' | 'error'>; isActive: () => boolean; isVisible: () => boolean; enabled: () => boolean; setEnabled: (enabled: boolean) => void };
const CLIENT_MESSAGE_MONITOR_POLLING_MS = 60000;

/** One instance per dictionary screen; owns only the monitor tab's state and work. */
export class AdminMessageMonitorFacade {
  private monitorTimerId: ReturnType<typeof setInterval> | null = null;
  private readonly reads = new Map<string, Subscription>();
  private readonly revisions = new Map<string, number>();
  private generation = 0;
  private destroyed = false;
  constructor(private readonly deps: MonitorDeps) {}

  destroy(): void {
    this.destroyed = true;
    this.deactivate();
  }

  private deactivate(): void {
    this.generation += 1;
    this.stopClientMessageMonitorPolling();
    this.cancelReads();
  }

  private cancelReads(): void {
    for (const read of this.reads.values()) read.unsubscribe();
    this.reads.clear();
    this.clientMessageMonitorLoading.set(false);
    this.clientMessageMaintenancePreviewLoading.set(false);
  }

  private current(generation: number): boolean {
    return !this.destroyed && generation === this.generation && this.deps.isActive() && this.deps.isVisible();
  }

  private read<T>(channel: string, request: Observable<T>, observer: { next: (value: T) => void; error: (error: unknown) => void }): void {
    this.reads.get(channel)?.unsubscribe();
    const generation = this.generation;
    const revision = (this.revisions.get(channel) ?? 0) + 1;
    this.revisions.set(channel, revision);
    const accepts = () => this.current(generation) && this.revisions.get(channel) === revision;
    const subscription = request.subscribe({
      next: value => { if (accepts()) observer.next(value); },
      error: error => { if (accepts()) observer.error(error); }
    });
    this.reads.set(channel, subscription);
  }

  readonly clientMessageMonitor = signal<AdminClientMessageMonitor | null>(null);

  readonly clientMessageMaintenancePreview = signal<AdminClientMessageMaintenancePreview | null>(null);

  readonly clientMessageMonitorLoading = signal(false);

  readonly clientMessageMaintenancePreviewLoading = signal(false);

  readonly clientMessageMonitorSaving = signal(false);

  readonly clientMessageMonitorError = signal<string | null>(null);

  readonly clientMessageMaintenancePreviewError = signal<string | null>(null);

  readonly monitorScenarioFilter = signal('ALL');

  readonly monitorQueueStatusFilter = signal('ALL');

  readonly monitorAttemptStatusFilter = signal('ALL');

  readonly monitorSearch = signal('');

  readonly expandedMonitorQueueKey = signal<string | null>(null);

  readonly clientMessageManualAction = signal<string | null>(null);

  readonly maintenanceAction = signal<string | null>(null);

  readonly monitorMetrics = computed<DictionaryMetric[]>(() => {
    const monitor = this.clientMessageMonitor();
    return [
      { label: 'Активных', value: monitor?.activeCandidates ?? 0, icon: 'playlist_add_check', tone: 'blue', tooltip: 'Все активные задачи автоответчика: и просроченные, и запланированные на будущее.' },
      { label: 'Пора проверить', value: monitor?.dueNow ?? 0, icon: 'schedule', tone: 'blue', tooltip: 'У этих задач уже наступило время следующей проверки, и они не захвачены другим worker-ом. Это еще не означает, что сообщение будет отправлено: задача может ждать окно, канал, лимит или проверку условий.' },
      { label: 'Готово к отправке', value: monitor?.readyToSendNow ?? 0, icon: 'bolt', tone: 'green', tooltip: 'Предварительно готовые задачи: worker и live-отправка включены, рабочее окно открыто, обязательный chatId/groupId найден. Перед отправкой еще применяются интервалы каналов, дневной лимит и финальные проверки.' },
      { label: 'Ждет окно', value: monitor?.waitingForWindow ?? 0, icon: 'access_time', tone: 'yellow', tooltip: 'Время проверки уже наступило, но сейчас закрыто рабочее окно. Задачи останутся в очереди до ближайшего разрешенного времени.' },
      { label: 'Нет chatId', value: monitor?.missingChannelBindings ?? 0, icon: 'link_off', tone: monitor?.missingChannelBindings ? 'pink' : 'teal', tooltip: 'Активные задачи, для которых не найден обязательный идентификатор WhatsApp, Telegram или MAX-группы. Без исправления привязки сообщение не уйдет.' },
      { label: 'Ручной контроль', value: monitor?.manualControlCandidates ?? 0, icon: 'support_agent', tone: monitor?.manualControlCandidates ? 'pink' : 'teal', tooltip: 'Задачи, где нужен человек: отсутствует или неверно настроен чат, повторилось слишком много ошибок либо ошибка остается без успешной обработки дольше допустимого времени.' },
      { label: 'Ждут retry', value: monitor?.retryWaitingCandidates ?? 0, icon: 'replay', tone: monitor?.retryWaitingCandidates ? 'yellow' : 'teal', tooltip: 'Недавние временные ошибки, для которых уже назначена автоматическая повторная попытка. Пока вмешательство не требуется.' },
      { label: 'Ждут восстановления', value: monitor?.recoveryHoldCandidates ?? 0, icon: 'healing', tone: monitor?.recoveryHoldCandidates ? 'blue' : 'teal', tooltip: 'Задачи поставлены на паузу, пока не завершится восстановление отзывов. После завершения зависимость будет снята автоматически.' },
      { label: 'Автовосстановлено', value: monitor?.autoRecoveredToday ?? 0, icon: 'auto_fix_high', tone: monitor?.autoRecoveredToday ? 'green' : 'teal', tooltip: 'Сколько некорректных состояний очереди сервис автоматически исправил сегодня без ручного вмешательства.' },
      { label: 'Отправлено сегодня', value: monitor?.sentToday ?? 0, icon: 'send', tone: 'green', tooltip: 'Успешные действия всех сценариев с начала сегодняшнего дня по иркутскому времени. Отдельное число только по архивному офферу показано ниже.' },
      { label: 'Ошибок сегодня', value: monitor?.failedToday ?? 0, icon: 'priority_high', tone: monitor?.failedToday ? 'pink' : 'teal', tooltip: 'Неуспешные попытки всех сценариев за сегодня. Часть временных ошибок будет повторена автоматически.' },
      { label: 'Пропущено', value: monitor?.skippedToday ?? 0, icon: 'pause_circle', tone: 'teal', tooltip: 'Попытки, где отправка осознанно не выполнялась: изменились условия, сработал dry-run, задача уже неактуальна или было выполнено системное действие. Это не обязательно ошибка или потерянное сообщение.' },
      { label: 'Отключено задач', value: monitor?.disabledStates ?? 0, icon: 'block', tone: monitor?.disabledStates ? 'pink' : 'blue', tooltip: 'Задачи, окончательно исключенные из автоматической обработки. Они не вернутся в очередь без отдельного восстановления.' }
    ];
  });

  readonly archiveOfferMetrics = computed<DictionaryMetric[]>(() => {
    const offer = this.clientMessageMonitor()?.archiveOfferToday;
    return [
      { label: 'Весь план на сегодня', value: offer?.plannedToday ?? 0, icon: 'today', tone: 'blue', tooltip: 'Весь объем архивного оффера на сегодня: уже отправленные плюс еще не завершенные задачи. Формула: «уже отправлено» + «осталось в плане».' },
      { label: 'Пора обрабатывать', value: offer?.queuedNow ?? 0, icon: 'outbox', tone: 'yellow', tooltip: 'Часть оставшегося плана, у которой уже наступило время проверки. Остальные задачи из плана назначены на более позднее время сегодня.' },
      { label: 'В обработке', value: offer?.processingNow ?? 0, icon: 'sync', tone: 'teal', tooltip: 'Офферы, которые worker уже захватил для проверки или отправки прямо сейчас. Обычно это число быстро возвращается к нулю.' },
      { label: 'Можно отправлять сейчас', value: offer?.readyNow ?? 0, icon: 'bolt', tone: 'green', tooltip: 'Часть показателя «пора обрабатывать», для которой включена live-отправка, открыто рабочее окно и найдена безопасная привязка чата. Это еще не отправленные сообщения.' },
      { label: 'Уже отправлено', value: offer?.sentToday ?? 0, icon: 'send', tone: 'green', tooltip: 'Только успешно отправленные сегодня сообщения сценария «Архивные компании», с начала дня по иркутскому времени.' },
      { label: 'Осталось в плане', value: offer?.remainingToday ?? 0, icon: 'pending_actions', tone: 'blue', tooltip: 'Активные офферы, назначенные не позже конца сегодняшнего дня и еще не завершенные. Часть из них может быть перенесена на следующий день.' },
      { label: 'Нет привязки чата', value: offer?.blockedByChannel ?? 0, icon: 'link_off', tone: offer?.blockedByChannel ? 'pink' : 'teal', tooltip: 'Активные задачи архивного оффера из оставшегося плана, у компаний которых нет корректного WhatsApp groupId, Telegram chatId или MAX chatId. Это количество задач, а не отдельный подсчет уникальных компаний. Перед отправкой статус компании проверяется повторно.' },
      { label: 'Остаток общего лимита', value: offer?.dailyLimitRemaining ?? 0, icon: 'speed', tone: 'yellow', tooltip: 'Сколько клиентских сообщений еще разрешает общий дневной лимит автоответчика. Этот остаток делят все сценарии, а не только архивный оффер.' }
    ];
  });

  readonly filteredMonitorQueue = computed(() => {
    const monitor = this.clientMessageMonitor();
    if (!monitor) {
      return [];
    }
    const scenarioFilter = this.monitorScenarioFilter();
    const statusFilter = this.monitorQueueStatusFilter();
    const search = this.monitorSearch().trim().toLowerCase();
    const nowMs = Date.parse(monitor.updatedAt);
    return monitor.queue.filter((item) => {
      if (scenarioFilter !== 'ALL' && item.scenario !== scenarioFilter) {
        return false;
      }
      if (statusFilter === 'DUE' && !this.isMonitorQueueDue(item, nowMs)) {
        return false;
      }
      if (statusFilter === 'ERROR' && !item.lastErrorMessage) {
        return false;
      }
      return this.matchesMonitorQueueSearch(item, search);
    });
  });

  readonly filteredMonitorAttempts = computed(() => {
    const monitor = this.clientMessageMonitor();
    if (!monitor) {
      return [];
    }
    const scenarioFilter = this.monitorScenarioFilter();
    const statusFilter = this.monitorAttemptStatusFilter();
    const search = this.monitorSearch().trim().toLowerCase();
    return monitor.attempts.filter((attempt) => {
      if (scenarioFilter !== 'ALL' && attempt.scenario !== scenarioFilter) {
        return false;
      }
      if (statusFilter !== 'ALL' && attempt.status !== statusFilter) {
        return false;
      }
      return this.matchesMonitorAttemptSearch(attempt, search);
    });
  });

  setClientMessageMonitorEnabled(enabled: boolean): void {
    if (this.clientMessageMonitorSaving() || !this.current(this.generation)) {
      return;
    }

    const previous = this.deps.enabled() ?? false;
    const generation = this.generation;
    this.cancelReads();
    this.clientMessageMonitorSaving.set(true);
    this.clientMessageMonitorError.set(null);
    this.deps.api.updateClientMessageMonitorSettings(enabled).subscribe({
      next: (settings) => {
        this.clientMessageMonitorSaving.set(false);
        if (!this.current(generation)) return;
        this.deps.setEnabled(settings.enabled);
        if (settings.enabled) {
          this.loadClientMessageMonitor(false, true);
          this.startClientMessageMonitorPolling();
        } else {
          this.deactivate();
          this.clientMessageMonitor.set(null);
        }
        this.deps.toast.success(
          settings.enabled ? 'Мониторинг включен' : 'Мониторинг выключен',
          settings.enabled ? 'Данные будут обновляться раз в минуту, пока вкладка открыта.' : 'Автоответчик продолжит работать без UI-опроса.'
        );
      },
      error: (err: unknown) => {
        this.clientMessageMonitorSaving.set(false);
        if (!this.current(generation)) return;
        const message = apiErrorMessage(err, 'Не удалось переключить мониторинг автоответчика');
        this.deps.setEnabled(previous);
        this.clientMessageMonitorError.set(message);
        this.deps.toast.error('Мониторинг не переключен', message);
      }
    });
  }

  loadClientMessageMonitor(silent = false, discoverSettings = false): void {
    if (!this.current(this.generation)) return;
    if (this.clientMessageMonitorSaving() || this.clientMessageManualAction() || this.maintenanceAction()) return;
    if (silent && this.reads.get('monitor') && !this.reads.get('monitor')!.closed) return;
    if (!discoverSettings && !this.deps.enabled()) {
      this.clientMessageMonitor.set(null);
      this.stopClientMessageMonitorPolling();
      return;
    }
    if (!silent) {
      this.clientMessageMonitorLoading.set(true);
    }
    this.clientMessageMonitorError.set(null);
    this.read('monitor', this.deps.api.getClientMessageMonitor(), {
      next: (monitor) => {
        this.clientMessageMonitor.set(monitor);
        this.deps.setEnabled(monitor.enabled);
        this.clientMessageMonitorLoading.set(false);
        this.loadClientMessageMaintenancePreview(true);
        if (monitor.enabled && this.deps.isActive()) {
          this.startClientMessageMonitorPolling();
        }
      },
      error: (err: unknown) => {
        const message = apiErrorMessage(err, 'Не удалось загрузить мониторинг автоответчика');
        this.clientMessageMonitorError.set(message);
        this.clientMessageMonitorLoading.set(false);
        if (!silent) {
          this.deps.toast.error('Мониторинг не загрузился', message);
        }
      }
    });
  }

  loadClientMessageMaintenancePreview(silent = false): void {
    if (!this.current(this.generation)) return;
    if (silent && this.reads.get('preview') && !this.reads.get('preview')!.closed) return;
    if (!silent) {
      this.clientMessageMaintenancePreviewLoading.set(true);
    }
    this.clientMessageMaintenancePreviewError.set(null);
    this.read('preview', this.deps.api.getClientMessageMaintenancePreview(), {
      next: (preview) => {
        this.clientMessageMaintenancePreview.set(preview);
        this.clientMessageMaintenancePreviewLoading.set(false);
      },
      error: (err: unknown) => {
        const message = apiErrorMessage(err, 'Не удалось загрузить dry-run актуализации');
        this.clientMessageMaintenancePreviewError.set(message);
        this.clientMessageMaintenancePreviewLoading.set(false);
        if (!silent) {
          this.deps.toast.error('Dry-run не загрузился', message);
        }
      }
    });
  }

  applyClientMessageMaintenance(
    action: 'company-statuses' | 'payment-overdue' | 'missing-bad-tasks' | 'archive-offers' | 'publication-dates' | 'publication-completed',
    label: string
  ): void {
    if (this.maintenanceAction() || !this.current(this.generation)) {
      return;
    }
    const confirmed = window.confirm(`Применить: ${label}? Перед применением лучше проверить текущий dry-run.`);
    if (!confirmed) {
      return;
    }

    this.maintenanceAction.set(action);
    const generation = this.generation;
    this.cancelReads();
    this.clientMessageMaintenancePreviewError.set(null);
    this.deps.api.applyClientMessageMaintenance(action).subscribe({
      next: (response) => {
        this.maintenanceAction.set(null);
        if (!this.current(generation)) return;
        this.clientMessageMaintenancePreview.set(response.preview);
        this.deps.toast.success('Актуализация выполнена', response.message);
        this.loadClientMessageMonitor(true);
      },
      error: (err: unknown) => {
        this.maintenanceAction.set(null);
        if (!this.current(generation)) return;
        const message = apiErrorMessage(err, 'Не удалось выполнить актуализацию');
        this.clientMessageMaintenancePreviewError.set(message);
        this.deps.toast.error('Актуализация не выполнена', message);
      }
    });
  }

  setMonitorScenarioFilter(value: string): void {
    this.monitorScenarioFilter.set(value);
    this.expandedMonitorQueueKey.set(null);
  }

  setMonitorQueueStatusFilter(value: string): void {
    this.monitorQueueStatusFilter.set(value);
    this.expandedMonitorQueueKey.set(null);
  }

  setMonitorAttemptStatusFilter(value: string): void {
    this.monitorAttemptStatusFilter.set(value);
  }

  setMonitorSearch(value: string): void {
    this.monitorSearch.set(value);
    this.expandedMonitorQueueKey.set(null);
  }

  toggleMonitorQueueDetails(item: AdminClientMessageMonitorQueueItem): void {
    this.expandedMonitorQueueKey.set(this.expandedMonitorQueueKey() === item.targetKey ? null : item.targetKey);
  }

  retryClientMessageCandidate(item: AdminClientMessageMonitorQueueItem): void {
    this.runClientMessageManualAction(
      item,
      'retry',
      () => this.deps.api.retryClientMessageNow(item.id),
      'Кандидат поставлен на ближайшую попытку'
    );
  }

  disableClientMessageCandidate(item: AdminClientMessageMonitorQueueItem): void {
    if (!window.confirm(`Отключить кандидата "${item.orderTitle || item.companyTitle}"?`)) {
      return;
    }
    this.runClientMessageManualAction(
      item,
      'disable',
      () => this.deps.api.disableClientMessageCandidate(item.id),
      'Кандидат отключен'
    );
  }

  markClientMessageCandidateDone(item: AdminClientMessageMonitorQueueItem): void {
    if (!window.confirm(`Пометить кандидата "${item.orderTitle || item.companyTitle}" выполненным?`)) {
      return;
    }
    this.runClientMessageManualAction(
      item,
      'done',
      () => this.deps.api.markClientMessageCandidateDone(item.id),
      'Кандидат помечен выполненным'
    );
  }

  monitorManualActionKey(item: AdminClientMessageMonitorQueueItem, action: string): string {
    return `${item.id}:${action}`;
  }

  private runClientMessageManualAction(
    item: AdminClientMessageMonitorQueueItem,
    action: string,
    requestFactory: () => Observable<AdminClientMessageMonitor>,
    successTitle: string
  ): void {
    const key = this.monitorManualActionKey(item, action);
    if (this.clientMessageManualAction() || !this.current(this.generation)) {
      return;
    }
    this.clientMessageManualAction.set(key);
    const generation = this.generation;
    this.cancelReads();
    this.clientMessageMonitorError.set(null);
    requestFactory().subscribe({
      next: (monitor) => {
        this.clientMessageManualAction.set(null);
        if (!this.current(generation)) return;
        this.clientMessageMonitor.set(monitor);
        this.deps.setEnabled(monitor.enabled);
        this.expandedMonitorQueueKey.set(null);
        this.deps.toast.success(successTitle, item.scenarioLabel);
      },
      error: (err: unknown) => {
        this.clientMessageManualAction.set(null);
        if (!this.current(generation)) return;
        const message = apiErrorMessage(err, 'Не удалось выполнить действие с кандидатом');
        this.clientMessageMonitorError.set(message);
        this.deps.toast.error('Действие не выполнено', message);
      }
    });
  }

  private isMonitorQueueDue(item: AdminClientMessageMonitorQueueItem, nowMs: number): boolean {
    if (!item.nextAttemptAt || Number.isNaN(nowMs)) {
      return false;
    }
    const nextMs = Date.parse(item.nextAttemptAt);
    return !Number.isNaN(nextMs) && nextMs <= nowMs;
  }

  monitorQueueTimingLabel(item: AdminClientMessageMonitorQueueItem): string | null {
    const monitor = this.clientMessageMonitor();
    if (!monitor || !item.nextAttemptAt) {
      return null;
    }
    const nowMs = Date.parse(monitor.nowIrkutsk || monitor.updatedAt);
    const nextMs = Date.parse(item.nextAttemptAt);
    if (Number.isNaN(nowMs) || Number.isNaN(nextMs)) {
      return null;
    }
    if (nextMs > nowMs) {
      return 'по расписанию';
    }

    switch (item.readiness) {
      case 'WAITING_WINDOW':
        return 'просрочено, ждет рабочее окно';
      case 'MISSING_CHANNEL':
        return 'просрочено, нужна привязка';
      case 'READY_TO_SEND':
        return 'просрочено, готово к отправке';
      case 'READY_TO_RUN':
        return 'просрочено, готово к действию';
      case 'PAUSED':
        return 'просрочено, пауза';
      case 'WORKER_DISABLED':
        return 'просрочено, worker выключен';
      case 'DRY_RUN':
        return 'просрочено, dry-run';
      case 'LOCKED':
        return 'в обработке';
      default:
        return 'просрочено';
    }
  }

  monitorQueueTimingClass(item: AdminClientMessageMonitorQueueItem): string {
    const label = this.monitorQueueTimingLabel(item);
    if (!label || label === 'по расписанию') {
      return 'status-pill off';
    }
    const code = item.readiness ?? '';
    if (code === 'MISSING_CHANNEL' || code === 'DRY_RUN' || code === 'WORKER_DISABLED') {
      return 'status-pill danger';
    }
    if (code === 'READY_TO_SEND' || code === 'READY_TO_RUN') {
      return 'status-pill';
    }
    return 'status-pill warning';
  }

  private matchesMonitorQueueSearch(item: AdminClientMessageMonitorQueueItem, search: string): boolean {
    if (!search) {
      return true;
    }
    return [
      item.scenarioLabel,
      item.companyTitle,
      item.orderTitle,
      item.statusTitle,
      item.lastErrorMessage,
      item.readinessLabel,
      item.readinessReason,
      item.expectedChannel,
      item.channelDetails,
      item.messagePreview,
      item.orderId?.toString(),
      item.companyId?.toString()
    ].some((value) => String(value ?? '').toLowerCase().includes(search));
  }

  private matchesMonitorAttemptSearch(attempt: AdminClientMessageMonitorAttempt, search: string): boolean {
    if (!search) {
      return true;
    }
    return [
      attempt.scenarioLabel,
      attempt.companyTitle,
      attempt.orderTitle,
      attempt.statusLabel,
      attempt.errorCode,
      attempt.errorMessage,
      attempt.messagePreview,
      attempt.channel,
      attempt.orderId?.toString(),
      attempt.companyId?.toString()
    ].some((value) => String(value ?? '').toLowerCase().includes(search));
  }

  monitorAttemptStatusClass(status: string): string {
    if (status === 'SENT') {
      return 'status-pill';
    }
    if (status === 'FAILED') {
      return 'status-pill danger';
    }
    return 'status-pill off';
  }

  monitorScenarioIcon(scenario: AdminClientMessageMonitorScenario): string {
    return {
      CLIENT_TEXT_REMINDER: 'edit_note',
      REVIEW_CHECK_REMINDER: 'playlist_add_check',
      PAYMENT_REMINDER: 'receipt_long',
      PAYMENT_OVERDUE_ESCALATION: 'priority_high',
      ARCHIVE_REORDER_OFFER: 'archive',
      BAD_REVIEW_INVOICE: 'request_quote',
      BAD_REVIEW_AUTO_BAN: 'gavel',
      REVIEW_RECOVERY_NOTICE: 'restore_page'
    }[scenario.scenario] ?? 'mark_chat_unread';
  }

  monitorScenarioTone(scenario: AdminClientMessageMonitorScenario): DictionaryMetric['tone'] {
    if (scenario.failedToday > 0 || scenario.lastError) {
      return 'pink';
    }
    if ((scenario.missingChannelBindings ?? 0) > 0) {
      return 'pink';
    }
    if ((scenario.readyToSendNow ?? 0) > 0) {
      return 'green';
    }
    if ((scenario.waitingForWindow ?? 0) > 0 || scenario.dueNow > 0) {
      return 'yellow';
    }
    if (scenario.activeCandidates > 0) {
      return 'green';
    }
    return 'teal';
  }

  monitorScenarioDueValue(scenario: AdminClientMessageMonitorScenario): number {
    if ((scenario.readyToSendNow ?? 0) > 0) {
      return scenario.readyToSendNow;
    }
    if ((scenario.waitingForWindow ?? 0) > 0) {
      return scenario.waitingForWindow;
    }
    return scenario.dueNow;
  }

  monitorScenarioDueLabel(scenario: AdminClientMessageMonitorScenario): string {
    if ((scenario.readyToSendNow ?? 0) > 0) {
      return 'готово к отправке';
    }
    if ((scenario.waitingForWindow ?? 0) > 0) {
      return 'ждет окно';
    }
    return 'пора проверить';
  }

  monitorReadinessClass(item: AdminClientMessageMonitorQueueItem): string {
    const code = item.readiness ?? '';
    if (code === 'READY_TO_SEND' || code === 'READY_TO_RUN') {
      return 'status-pill';
    }
    if (code === 'MISSING_CHANNEL' || code === 'DRY_RUN' || code === 'WORKER_DISABLED') {
      return 'status-pill danger';
    }
    if (code === 'WAITING_WINDOW' || code === 'SCHEDULED' || code === 'PAUSED' || code === 'LOCKED') {
      return 'status-pill warning';
    }
    return 'status-pill off';
  }

  monitorScenarioTooltip(scenario: AdminClientMessageMonitorScenario): string {
    return {
      CLIENT_TEXT_REMINDER: 'Напоминает клиенту прислать текст или пожелания, когда заказ в режиме "ждем текст от клиента".',
      REVIEW_CHECK_REMINDER: 'Напоминает клиенту проверить шаблоны отзывов и перейти по ссылке проверки.',
      PAYMENT_REMINDER: 'Напоминает клиенту об оплате заказа в статусах счета и напоминания.',
      PAYMENT_OVERDUE_ESCALATION: 'Следит за долгой просрочкой оплаты и готовит перевод в целевой статус по настройкам.',
      ARCHIVE_REORDER_OFFER: 'Предлагает новый заказ компаниям из архивного цикла, если нет активных заказов и открытой заявки.',
      BAD_REVIEW_INVOICE: 'Фиксирует отправку счета после выполненного плохого отзыва с учетом доплаты.',
      BAD_REVIEW_AUTO_BAN: 'Переводит заказ и компанию в Бан, если финальный счет после плохих не оплатили за заданный срок.',
      REVIEW_RECOVERY_NOTICE: 'Финально уведомляет клиента, что все восстановления по заказу завершены, и снимает паузу с платежных таймеров.'
    }[scenario.scenario] ?? 'Сценарий автоответчика: кандидаты, отправки, пропуски и ошибки.';
  }

  trackMonitorMetric(_index: number, metric: DictionaryMetric): string {
    return metric.label;
  }

  trackMonitorScenario(_index: number, scenario: AdminClientMessageMonitorScenario): string {
    return scenario.scenario;
  }

  trackMonitorQueueItem(_index: number, item: AdminClientMessageMonitorQueueItem): number {
    return item.id;
  }

  trackMonitorAttempt(_index: number, attempt: AdminClientMessageMonitorAttempt): number {
    return attempt.id;
  }

  syncClientMessageMonitorPolling(): void {
    if (this.canPollClientMessageMonitor()) {
      this.loadClientMessageMonitor(true);
      this.startClientMessageMonitorPolling();
      return;
    }
    this.deactivate();
  }

  private startClientMessageMonitorPolling(): void {
    if (this.monitorTimerId != null || !this.canPollClientMessageMonitor()) {
      return;
    }
    this.monitorTimerId = setInterval(() => {
      if (this.canPollClientMessageMonitor()) {
        this.loadClientMessageMonitor(true);
      } else {
        this.deactivate();
      }
    }, CLIENT_MESSAGE_MONITOR_POLLING_MS);
  }

  private stopClientMessageMonitorPolling(): void {
    if (this.monitorTimerId == null) {
      return;
    }
    clearInterval(this.monitorTimerId);
    this.monitorTimerId = null;
  }

  private canPollClientMessageMonitor(): boolean {
    return !this.destroyed && this.deps.isActive()
      && !!this.deps.enabled()
      && this.deps.isVisible();
  }

  monitorTotal(): number {
    if (!this.deps.enabled()) {
      return 0;
    }
    return this.clientMessageMonitor()?.activeCandidates ?? 0;
  }

}
