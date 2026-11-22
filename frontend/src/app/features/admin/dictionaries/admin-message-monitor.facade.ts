import { computed, signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import type { AdminClientMessageMonitor, AdminClientMessageMaintenancePreview, AdminClientMessageMonitorQueueItem, AdminClientMessageMonitorAttempt, AdminClientMessageMonitorScenario, AdminMaintenanceApplyResponse } from '../../../core/admin-dictionaries.api';
import type { AdminMessageMonitorApi } from '../../../core/admin-message-monitor.api';
import type { ToastService } from '../../../shared/toast.service';
import { apiErrorMessage } from '../../../shared/api-error-message';

type DictionaryMetric = { label: string; value: number; icon: string; tone: 'blue' | 'green' | 'teal' | 'yellow' | 'pink'; tooltip?: string };
type MaintenanceAction = 'company-statuses' | 'payment-overdue' | 'missing-bad-tasks' | 'archive-offers' | 'publication-dates' | 'publication-completed';
/** The outcome of the last database update started on this screen. It stays until the next one, also after leaving the tab. */
export type MaintenanceResult = { tone: 'success' | 'warning' | 'error'; title: string; message: string; at: string };
type MonitorDeps = {
  api: Pick<AdminMessageMonitorApi, keyof AdminMessageMonitorApi>;
  toast: Pick<ToastService, 'success' | 'error'> & Partial<Pick<ToastService, 'warning'>>;
  isActive: () => boolean;
  isVisible: () => boolean;
  enabled: () => boolean;
  setEnabled: (enabled: boolean) => void;
};
const CLIENT_MESSAGE_MONITOR_POLLING_MS = 60000;
/** Tasks of these scenarios change the order status instead of writing to the client. */
const STATUS_SCENARIOS: ReadonlySet<string> = new Set(['BAD_REVIEW_AUTO_BAN', 'PAYMENT_OVERDUE_ESCALATION']);
/** Scenarios whose message carries payment details (a manager text or a bank link). */
const PAYMENT_SCENARIOS: ReadonlySet<string> = new Set(['PAYMENT_REMINDER', 'PAYMENT_INVOICE_RETRY', 'BAD_REVIEW_INVOICE']);

/** One instance per dictionary screen; owns only the monitor tab's state and work. */
/** Waits the queue plans on purpose: a limit, a working window, a recovery in progress, a dry run. */
const PLANNED_WAIT_CODES = new Set(['archive_waiting', 'daily_limit', 'rate_limited', 'outside_messaging_window',
  'review_recovery_active', 'review_recovery_still_open', 'payment_overdue_dry_run']);

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

  /** Only a failed read of the monitor: the errors of the switch and of row actions are reported by their own toasts. */
  readonly clientMessageMonitorError = signal<string | null>(null);

  readonly clientMessageMaintenancePreviewError = signal<string | null>(null);

  readonly monitorScenarioFilter = signal('ALL');

  readonly monitorQueueStatusFilter = signal('ALL');

  readonly monitorAttemptStatusFilter = signal('ALL');

  readonly monitorSearch = signal('');

  /** The open preview row as the old template compares it (by targetKey); isMonitorQueueExpanded() is exact. */
  readonly expandedMonitorQueueKey = signal<string | null>(null);

  /** The open preview row by task: two tasks of one order (payment and overdue payment) share their targetKey. */
  private readonly expandedMonitorQueueId = signal<number | null>(null);

  readonly clientMessageManualAction = signal<string | null>(null);

  readonly maintenanceAction = signal<string | null>(null);

  /** The last database update of this screen with its outcome, shown in the «Проверка базы» card. */
  readonly maintenanceResult = signal<MaintenanceResult | null>(null);

  /** The monitoring flag from the server's last answer on this visit of the tab: the monitor read, the switch or a row action. */
  private readonly answeredEnabled = signal<boolean | null>(null);

  /** Where the switch was moved while its request runs. */
  private readonly switchTarget = signal<boolean | null>(null);

  /**
   * The one source of the monitoring state for the switch, its caption, «Обновить», «Проверить базу» and the empty state:
   * the server's last answer on this visit, otherwise «on» when the loaded settings or snapshot say so. Null while it is
   * unknown — shown as loading, never as «выключено»: the message settings are loaded only by the autoresponder tabs.
   */
  readonly monitorEnabled = computed<boolean | null>(() => this.answeredEnabled() ?? (this.deps.enabled() ? true : null));

  /** The switch follows a click while its request runs, then shows the answer — or the old state after an error. */
  readonly monitorSwitchChecked = computed(() => this.switchTarget() ?? this.monitorEnabled() === true);

  readonly monitorSwitchLabel = computed(() => {
    if (this.clientMessageMonitorSaving()) {
      return 'Переключаю';
    }
    const enabled = this.monitorEnabled();
    if (enabled === null) {
      // Before the first answer the read is on its way; after a failed read the state is simply not known.
      return this.clientMessageMonitorError() && !this.clientMessageMonitorLoading() ? 'Состояние неизвестно' : 'Загружаю';
    }
    return enabled ? 'Наблюдение включено' : 'Наблюдение выключено';
  });

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
      if (statusFilter === 'ERROR' && !this.monitorQueueHasError(item)) {
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

    const generation = this.generation;
    this.cancelReads();
    this.clientMessageMonitorSaving.set(true);
    this.switchTarget.set(enabled);
    this.clientMessageMonitorError.set(null);
    this.deps.api.updateClientMessageMonitorSettings(enabled).subscribe({
      next: (settings) => {
        this.clientMessageMonitorSaving.set(false);
        this.switchTarget.set(null);
        if (!this.current(generation)) return;
        this.answeredEnabled.set(settings.enabled);
        this.deps.setEnabled(settings.enabled);
        if (settings.enabled) {
          this.loadClientMessageMonitor(false, true);
          this.startClientMessageMonitorPolling();
        } else {
          this.deactivate();
          this.clientMessageMonitor.set(null);
        }
        this.deps.toast.success(
          settings.enabled ? 'Наблюдение включено' : 'Наблюдение выключено',
          settings.enabled
            ? 'Для всех администраторов. Пока вкладка открыта, данные обновляются раз в минуту.'
            : 'Для всех администраторов. Отправка сообщений клиентам работает как прежде.'
        );
      },
      error: (err: unknown) => {
        this.clientMessageMonitorSaving.set(false);
        // The switch returns to the state the server still has; the monitor itself did not fail to load.
        this.switchTarget.set(null);
        if (!this.current(generation)) return;
        const message = apiErrorMessage(err, 'Не удалось переключить наблюдение');
        this.deps.toast.error('Наблюдение не переключено', message);
      }
    });
  }

  loadClientMessageMonitor(silent = false, discoverSettings = false): void {
    if (!this.current(this.generation)) return;
    if (this.clientMessageMonitorSaving() || this.clientMessageManualAction() || this.maintenanceAction()) return;
    if (silent && this.reads.get('monitor') && !this.reads.get('monitor')!.closed) return;
    // Skipped only when monitoring is known to be off; an unknown state is read to learn it.
    if (!discoverSettings && this.monitorEnabled() === false) {
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
        this.answeredEnabled.set(monitor.enabled);
        this.deps.setEnabled(monitor.enabled);
        this.clientMessageMonitorLoading.set(false);
        // The check of the base runs about a dozen heavy queries: once per entry or «Обновить» while monitoring is on,
        // never from the minute poll, never while it is off.
        if (!silent && monitor.enabled) {
          this.loadClientMessageMaintenancePreview(true);
        }
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
    if (!silent && (this.monitorEnabled() !== true || !this.clientMessageMonitor())) {
      // The result card lives inside the monitor: a check that cannot be shown is not started.
      this.notify('warning', 'Проверка базы не запущена', this.monitorEnabled() === false
        ? 'Включите наблюдение: результат проверки показывается в кабинете мониторинга.'
        : 'Дождитесь загрузки кабинета мониторинга: результат проверки показывается в нём.');
      return;
    }
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
        const message = apiErrorMessage(err, 'Не удалось проверить базу');
        this.clientMessageMaintenancePreviewError.set(message);
        this.clientMessageMaintenancePreviewLoading.set(false);
        if (!silent) {
          this.deps.toast.error('Проверка базы не выполнена', message);
        }
      }
    });
  }

  applyClientMessageMaintenance(action: MaintenanceAction, label: string): void {
    if (this.maintenanceAction() || !this.current(this.generation)) {
      return;
    }
    const generation = this.generation;
    // Reserved before the question: a second click while it is open cannot send a second update.
    this.maintenanceAction.set(action);
    if (!window.confirm(this.maintenanceQuestion(action, label)) || !this.current(generation)) {
      this.maintenanceAction.set(null);
      return;
    }

    this.cancelReads();
    this.clientMessageMaintenancePreviewError.set(null);
    this.deps.api.applyClientMessageMaintenance(action).subscribe({
      next: (response) => {
        this.maintenanceAction.set(null);
        if (this.destroyed) return;
        // The update is done on the server: its outcome and fresh check are kept even if the tab was left meanwhile.
        const result = this.maintenanceOutcome(response);
        this.maintenanceResult.set(result);
        this.clientMessageMaintenancePreview.set(response.preview);
        if (!this.current(this.generation)) return;
        this.notify(result.tone, result.title, result.message);
        this.loadClientMessageMonitor(true);
      },
      error: (err: unknown) => {
        this.maintenanceAction.set(null);
        if (this.destroyed) return;
        const message = apiErrorMessage(err, 'Не удалось выполнить актуализацию');
        this.maintenanceResult.set({ tone: 'error', title: 'Актуализация не выполнена', message, at: new Date().toISOString() });
        if (!this.current(this.generation)) return;
        this.deps.toast.error('Актуализация не выполнена', message);
      }
    });
  }

  /** The confirmation of one database update: what changes, how many records by the last check, limits and consequences. */
  private maintenanceQuestion(action: MaintenanceAction, label: string): string {
    const preview = this.clientMessageMaintenancePreview();
    if (!preview) {
      return `Применить: ${label}? Изменение сразу записывается в базу и само не отменяется.`;
    }
    const byCheck = 'по последней проверке базы';
    const perRun = (limit: number) => `За один раз обрабатывается не больше ${limit} заказов.`;
    const closing = 'Изменение сразу записывается в базу и само не отменяется. Применить?';
    let lines: string[];
    switch (action) {
      case 'company-statuses':
        lines = [
          'Статусы компаний',
          `Вернуть в «В работе»: ${preview.companyStatuses.shouldMoveToWork}. Поставить «На стопе»: ${preview.companyStatuses.workWithoutActiveOrders} (${byCheck}).`,
          'Меняются сразу все подходящие компании. Компании на стопе со временем получат предложения нового заказа.'
        ];
        break;
      case 'payment-overdue':
        lines = [
          'Старые счета',
          `Перевести в «Не оплачено» заказы, которые ждут оплату ${preview.paymentOverdueDays} дн. и дольше: ${preview.paymentStatuses.invoiceOrReminderOlderThanThreshold} (${byCheck}).`,
          `${perRun(200)} Статус меняется обычным путём: по заказам появятся плохие задачи.`
        ];
        break;
      case 'missing-bad-tasks':
        lines = [
          'Плохие задачи',
          `Создать недостающие плохие задачи для старых заказов «Не оплачено» с опубликованными отзывами: ${preview.unpaidRecovery.canCreateBadTasks} (${byCheck}).`,
          perRun(300)
        ];
        break;
      case 'archive-offers':
        lines = [
          'Архивные офферы',
          `Закрыть предложения нового заказа компаниям, у которых уже есть активный заказ или открытая заявка: ${preview.archiveOffers.blockedByActiveOrders + preview.archiveOffers.blockedByOpenNextRequest} (${byCheck}).`,
          'Закрываются сразу все подходящие предложения.'
        ];
        break;
      case 'publication-dates':
        lines = [
          'Даты публикаций',
          `Исправить пустые, слишком дальние и растянутые даты у неопубликованных отзывов. Заказов с такими датами: до ${preview.publication.farFuturePublishDate + preview.publication.longPublishSpan + preview.publication.undatedUnpublished} (${byCheck}).`,
          perRun(100)
        ];
        break;
      case 'publication-completed':
        lines = [
          'Завершить публикации',
          `Перевести в «Опубликовано» заказы в «Публикации», где все отзывы уже опубликованы: ${preview.publication.oldAllReviewsPublished} (${byCheck}).`,
          `${perRun(200)} Статус меняется обычным путём: клиентам уйдут финальные счета по обычным правилам.`
        ];
        break;
      default:
        lines = [`Применить: ${label}?`];
    }
    return [...lines, closing].join('\n\n');
  }

  /** Nothing changed or a part failed («не удалось: N» of the server message) is a warning, not a success. */
  private maintenanceOutcome(response: AdminMaintenanceApplyResponse): MaintenanceResult {
    const message = response.message || '';
    const at = new Date().toISOString();
    if (/не удалось:\s*[1-9]/i.test(message)) {
      return { tone: 'warning', title: 'Выполнено не полностью', message, at };
    }
    if (!response.changed) {
      return { tone: 'warning', title: 'Ничего не изменено', message, at };
    }
    return { tone: 'success', title: 'Актуализация выполнена', message, at };
  }

  private notify(tone: MaintenanceResult['tone'], title: string, message: string): void {
    if (tone === 'success') {
      this.deps.toast.success(title, message);
    } else if (tone === 'warning' && this.deps.toast.warning) {
      this.deps.toast.warning(title, message);
    } else {
      this.deps.toast.error(title, message);
    }
  }

  setMonitorScenarioFilter(value: string): void {
    this.monitorScenarioFilter.set(value);
    this.collapseMonitorQueueDetails();
  }

  setMonitorQueueStatusFilter(value: string): void {
    this.monitorQueueStatusFilter.set(value);
    this.collapseMonitorQueueDetails();
  }

  setMonitorAttemptStatusFilter(value: string): void {
    this.monitorAttemptStatusFilter.set(value);
  }

  setMonitorSearch(value: string): void {
    this.monitorSearch.set(value);
    this.collapseMonitorQueueDetails();
  }

  toggleMonitorQueueDetails(item: AdminClientMessageMonitorQueueItem): void {
    const open = this.expandedMonitorQueueId() === item.id;
    this.expandedMonitorQueueId.set(open ? null : item.id);
    this.expandedMonitorQueueKey.set(open ? null : item.targetKey);
  }

  isMonitorQueueExpanded(item: AdminClientMessageMonitorQueueItem): boolean {
    return this.expandedMonitorQueueId() === item.id;
  }

  private collapseMonitorQueueDetails(): void {
    this.expandedMonitorQueueId.set(null);
    this.expandedMonitorQueueKey.set(null);
  }

  retryClientMessageCandidate(item: AdminClientMessageMonitorQueueItem): void {
    this.runClientMessageManualAction(
      item,
      'retry',
      () => this.deps.api.retryClientMessageNow(item.id),
      'Задача поставлена на ближайшую попытку',
      this.monitorActionQuestion(item, 'Повторить сейчас?', this.retryConsequence(item))
    );
  }

  disableClientMessageCandidate(item: AdminClientMessageMonitorQueueItem): void {
    this.runClientMessageManualAction(
      item,
      'disable',
      () => this.deps.api.disableClientMessageCandidate(item.id),
      'Задача отключена',
      this.monitorActionQuestion(item, 'Отключить задачу?',
        'Задача отключится без отправки и без смены статуса и больше не вернётся в очередь сама.')
    );
  }

  markClientMessageCandidateDone(item: AdminClientMessageMonitorQueueItem): void {
    this.runClientMessageManualAction(
      item,
      'done',
      () => this.deps.api.markClientMessageCandidateDone(item.id),
      'Задача помечена выполненной',
      this.monitorActionQuestion(item, 'Пометить задачу выполненной?',
        'Задача закроется без отправки и без смены статуса и больше не будет обрабатываться автоматически.')
    );
  }

  monitorManualActionKey(item: AdminClientMessageMonitorQueueItem, action: string): string {
    return `${item.id}:${action}`;
  }

  /** A row action question: the scenario, the target (one order can have two tasks) and what happens. */
  private monitorActionQuestion(item: AdminClientMessageMonitorQueueItem, question: string, consequence: string): string {
    return [question, `Сценарий: «${item.scenarioLabel}»`, `Цель: ${this.monitorTargetName(item)}`, '', consequence].join('\n');
  }

  private monitorTargetName(item: AdminClientMessageMonitorQueueItem): string {
    if (item.orderId) {
      return `заказ №${item.orderId}${item.orderTitle ? ` («${item.orderTitle}»)` : ''}`;
    }
    return `компания «${item.companyTitle}»`;
  }

  /** What «Повторить сейчас» really does: the task runs on the next step of the queue, without waiting for its date. */
  private retryConsequence(item: AdminClientMessageMonitorQueueItem): string {
    switch (item.scenario) {
      case 'BAD_REVIEW_AUTO_BAN':
        return 'Заказ сразу уйдёт в «Бан», не дожидаясь срока оплаты.';
      case 'PAYMENT_OVERDUE_ESCALATION':
        return 'Если автоперевод включён, заказ сразу уйдёт в статус просрочки из настроек автоответчика, не дожидаясь срока. Если выключен — будет только запись в журнале.';
      case 'REVIEW_CHECK_AUTO_ARCHIVE':
        return 'Задача закроется без действий: автоархив теперь проверяется после семи напоминаний.';
      case 'REVIEW_CHECK_REMINDER':
      case 'CLIENT_TEXT_REMINDER':
        return 'Клиенту уйдёт внеочередное напоминание в ближайшее рабочее окно. Оно засчитается в семь напоминаний перед автоархивом.';
      case 'PAYMENT_REMINDER':
        return 'Клиенту уйдёт внеочередное напоминание об оплате в ближайшее рабочее окно.';
      case 'PAYMENT_INVOICE_RETRY':
        return 'Клиенту повторно уйдёт счёт в ближайшее рабочее окно.';
      case 'REVIEW_CHECK_DELIVERY_RETRY':
        return 'Клиенту повторно уйдёт ссылка на проверку отзывов в ближайшее рабочее окно.';
      case 'ARCHIVE_REORDER_OFFER':
        return 'Компании уйдёт предложение нового заказа в ближайшее рабочее окно.';
      default:
        return 'Задача выполнится на ближайшем шаге очереди, не дожидаясь срока: клиенту уйдёт сообщение в ближайшее рабочее окно.';
    }
  }

  private runClientMessageManualAction(
    item: AdminClientMessageMonitorQueueItem,
    action: string,
    requestFactory: () => Observable<AdminClientMessageMonitor>,
    successTitle: string,
    question: string
  ): void {
    const key = this.monitorManualActionKey(item, action);
    if (this.clientMessageManualAction() || !this.current(this.generation)) {
      return;
    }
    const generation = this.generation;
    // Reserved before the question: a second click while it is open cannot start another action.
    this.clientMessageManualAction.set(key);
    if (!window.confirm(question) || !this.current(generation)) {
      this.clientMessageManualAction.set(null);
      return;
    }
    this.cancelReads();
    this.clientMessageMonitorError.set(null);
    requestFactory().subscribe({
      next: (monitor) => {
        this.clientMessageManualAction.set(null);
        if (!this.current(generation)) return;
        this.clientMessageMonitor.set(monitor);
        this.answeredEnabled.set(monitor.enabled);
        this.deps.setEnabled(monitor.enabled);
        this.collapseMonitorQueueDetails();
        this.deps.toast.success(successTitle, `${item.scenarioLabel} · ${this.monitorTargetName(item)}`);
      },
      error: (err: unknown) => {
        this.clientMessageManualAction.set(null);
        if (!this.current(generation)) return;
        // A refused action (for example 409 «Задача уже обрабатывается») is not a failed load of the monitor.
        const message = apiErrorMessage(err, 'Не удалось выполнить действие с задачей');
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

  /**
   * A real failure of the last attempts. Planned waits — the daily limit, an interval, a review recovery, the autoarchive
   * check, a switched-off auto transfer — also fill the reason, but they are not errors.
   */
  monitorQueueHasError(item: AdminClientMessageMonitorQueueItem): boolean {
    // A postponement keeps the failure counter of an earlier attempt: a planned wait is still not an error.
    return (item.consecutiveFailures ?? 0) > 0 && !!item.lastErrorMessage
      && !PLANNED_WAIT_CODES.has(item.lastErrorCode ?? '');
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
        return 'просрочено, фоновая очередь выключена';
      case 'DRY_RUN':
        return 'просрочено, пробный режим';
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

  /** The target type of a task in words instead of the server code. */
  monitorTargetTypeLabel(targetType: string | null | undefined): string {
    switch (targetType) {
      case 'ORDER':
        return 'заказ';
      case 'ARCHIVE_COMPANY':
        return 'компания из архива';
      default:
        return targetType || 'цель не указана';
    }
  }

  /** The channel of an attempt in words: status changes are written with the channel «system». */
  monitorChannelLabel(channel: string | null | undefined): string {
    const value = (channel ?? '').trim();
    if (!value) {
      return 'канал не выбран';
    }
    if (value.toLowerCase() === 'system') {
      return 'система: смена статуса';
    }
    return value.toUpperCase() === 'ANY' ? 'любой канал' : value;
  }

  /** The «Оплата» line of the preview is shown only for scenarios that send payment details. */
  monitorScenarioHasPayment(item: AdminClientMessageMonitorQueueItem): boolean {
    return PAYMENT_SCENARIOS.has(item.scenario);
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
      REVIEW_CHECK_DELIVERY_RETRY: 'forward_to_inbox',
      REVIEW_CHECK_AUTO_ARCHIVE: 'inventory_2',
      PAYMENT_INVOICE_RETRY: 'receipt',
      PAYMENT_REMINDER: 'receipt_long',
      PAYMENT_OVERDUE_ESCALATION: 'priority_high',
      ARCHIVE_REORDER_OFFER: 'archive',
      BAD_REVIEW_INVOICE: 'request_quote',
      BAD_REVIEW_AUTO_BAN: 'gavel',
      REVIEW_RECOVERY_NOTICE: 'restore_page'
    }[scenario.scenario] ?? 'mark_chat_unread';
  }

  monitorScenarioTone(scenario: AdminClientMessageMonitorScenario): DictionaryMetric['tone'] {
    if (scenario.failedToday > 0 || this.monitorScenarioErrorIsRecent(scenario)) {
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

  /** The server keeps the last error of a scenario without a date limit: an error of an earlier day is not a current problem. */
  private monitorScenarioErrorIsRecent(scenario: AdminClientMessageMonitorScenario): boolean {
    if (!scenario.lastError) {
      return false;
    }
    const today = (this.clientMessageMonitor()?.nowIrkutsk ?? '').slice(0, 10);
    const errorDay = (scenario.lastErrorAt ?? '').slice(0, 10);
    return !today || !errorDay || errorDay >= today;
  }

  /** Scenarios that change an order status «выполняют» their task; the others «отправляют» a message. */
  monitorScenarioSentWord(scenario: AdminClientMessageMonitorScenario): string {
    return STATUS_SCENARIOS.has(scenario.scenario) ? 'выполнено' : 'отправлено';
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
      REVIEW_CHECK_DELIVERY_RETRY: 'Если заказ остался в «В проверку», повторно отправляет клиенту ссылку на проверку отзывов и переводит заказ в «На проверке».',
      REVIEW_CHECK_AUTO_ARCHIVE: 'Старый сценарий: его задачи закрываются без действий. Автоархив теперь проверяется после семи напоминаний — см. «Проверка автоархива».',
      PAYMENT_INVOICE_RETRY: 'Если заказ остался в «Опубликовано», повторно отправляет клиенту счёт и переводит заказ в «Выставлен счет».',
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
    if (!this.deps.isActive()) {
      // The answer belongs to one visit of the tab: the next visit starts from the settings and snapshot loaded by then.
      this.answeredEnabled.set(null);
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
      && this.monitorEnabled() === true
      && this.deps.isVisible();
  }

  monitorTotal(): number {
    if (this.monitorEnabled() !== true) {
      return 0;
    }
    return this.clientMessageMonitor()?.activeCandidates ?? 0;
  }

}
