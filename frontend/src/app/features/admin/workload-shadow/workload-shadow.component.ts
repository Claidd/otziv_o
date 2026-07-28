import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, HostListener, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, forkJoin, of } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import {
  WorkloadShadowActionResult,
  WorkloadShadowApi,
  WorkloadShadowCollection,
  WorkloadShadowEvent,
  WorkloadShadowHealth,
  WorkloadShadowHealthIssue,
  WorkloadShadowHealthNode,
  WorkloadShadowMonitorSummary,
  WorkloadShadowProposal,
  WorkloadShadowSettings,
  WorkloadShadowWorker
} from '../../../core/workload-shadow.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';

type WorkloadTab = 'monitor' | 'settings';
type MonitorTone = 'blue' | 'green' | 'yellow' | 'red' | 'gray';

type MonitorMetric = {
  label: string;
  value: number | string;
  icon: string;
  tone: MonitorTone;
  hint?: string;
};

const MONITOR_POLLING_MS = 60_000;
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;

export const WORKLOAD_SHADOW_RECIPIENT_ELIGIBILITY_BASIS =
  'Допуск рассчитывается по последнему завершённому дню и истории завершённых дней с начала текущего месяца. Текущий незавершённый процент показывается только для мониторинга и не является причиной исключения.';

export function workloadShadowLastCompletedDayLabel(reached100?: boolean | null): string {
  if (reached100 == null) {
    return 'нет завершённых данных';
  }
  return reached100 ? '100%' : 'не 100%';
}

export function workloadShadowEvaluatedDays(
  worker: Pick<WorkloadShadowWorker, 'evaluatedDays' | 'hundredPercentDays' | 'failureDays'>
): number {
  const fallback = Number(worker.hundredPercentDays ?? 0) + Number(worker.failureDays ?? 0);
  return Math.max(0, Number(worker.evaluatedDays ?? fallback));
}

export type WorkloadShadowSafetyValues = Pick<
  WorkloadShadowSettings,
  | 'mode'
  | 'applyEnabled'
  | 'groupNotificationsEnabled'
  | 'notificationGroupChatId'
  | 'shiftStart'
  | 'shiftEnd'
  | 'schedulerIntervalMinutes'
  | 'nearEndIntervalMinutes'
  | 'walkMinimumMinutesPerCard'
  | 'walkMinutesPerCard'
  | 'lookbackDays'
  | 'recipientMaximumFailureDays'
  | 'fourthFailurePercent'
  | 'fifthFailurePercent'
  | 'sixthFailurePercent'
  | 'fourthFailureMaxCompanies'
  | 'fifthFailureMaxCompanies'
  | 'sixthFailureMaxCompanies'
  | 'notificationBatchSize'
  | 'notificationMaxAttempts'
  | 'notificationLeaseMinutes'
  | 'notificationRetryBaseMinutes'
  | 'maintenanceBatchSize'
>;

export function workloadShadowSettingsError(value: WorkloadShadowSafetyValues): string | null {
  if (String(value.mode).toUpperCase() === 'LIVE' || value.applyEnabled) {
    return 'Боевой режим недоступен: страница работает только в режиме наблюдения.';
  }
  if (value.notificationGroupChatId != null && value.notificationGroupChatId >= 0) {
    return 'Для уведомлений разрешён только отрицательный Telegram chat ID группы.';
  }
  if (value.groupNotificationsEnabled && value.notificationGroupChatId == null) {
    return 'Перед включением уведомлений укажите Telegram chat ID общей группы администраторов и владельцев.';
  }
  if (value.shiftStart === value.shiftEnd) {
    return 'Начало и окончание смены не могут совпадать.';
  }
  if (value.walkMinimumMinutesPerCard < 3) {
    return 'Минимальное время выгула не может быть меньше 3 минут.';
  }
  if (value.walkMinutesPerCard < value.walkMinimumMinutesPerCard) {
    return 'Оценка выгула не может быть меньше установленного минимума.';
  }
  if (value.nearEndIntervalMinutes > value.schedulerIntervalMinutes) {
    return 'Интервал у конца смены не может быть больше обычного интервала.';
  }
  if (value.recipientMaximumFailureDays < 0 || value.recipientMaximumFailureDays > 31) {
    return 'Лимит неуспешных дней получателя за месяц должен быть от 0 до 31.';
  }
  if (
    value.fourthFailurePercent > value.fifthFailurePercent
    || value.fifthFailurePercent > value.sixthFailurePercent
  ) {
    return 'Процент разгрузки должен возрастать от первого к третьему превышению порога.';
  }
  if (
    value.fourthFailureMaxCompanies > value.fifthFailureMaxCompanies
    || value.fifthFailureMaxCompanies > value.sixthFailureMaxCompanies
  ) {
    return 'Лимит компаний должен возрастать от первого к третьему превышению порога.';
  }
  if (value.notificationBatchSize < 1 || value.notificationBatchSize > 250) {
    return 'Размер сводки уведомлений должен быть от 1 до 250.';
  }
  if (value.notificationMaxAttempts < 1 || value.notificationMaxAttempts > 20) {
    return 'Количество попыток доставки должно быть от 1 до 20.';
  }
  if (value.notificationLeaseMinutes < 1 || value.notificationLeaseMinutes > 30) {
    return 'Аренда события должна быть от 1 до 30 минут.';
  }
  if (value.notificationRetryBaseMinutes < 1 || value.notificationRetryBaseMinutes > 60) {
    return 'Базовая пауза повтора должна быть от 1 до 60 минут.';
  }
  if (value.maintenanceBatchSize < 100 || value.maintenanceBatchSize > 5000) {
    return 'Пачка обслуживания должна содержать от 100 до 5000 записей.';
  }
  return null;
}

@Component({
  selector: 'app-workload-shadow',
  imports: [
    AdminLayoutComponent,
    DatePipe,
    DecimalPipe,
    FormsModule,
    ReactiveFormsModule,
    LoadErrorCardComponent
  ],
  templateUrl: './workload-shadow.component.html',
  styleUrl: './workload-shadow.component.scss'
})
export class WorkloadShadowComponent implements OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(WorkloadShadowApi);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private monitorTimerId: ReturnType<typeof window.setInterval> | null = null;

  readonly activeTab = signal<WorkloadTab>('monitor');
  readonly settings = signal<WorkloadShadowSettings | null>(null);
  readonly summary = signal<WorkloadShadowMonitorSummary | null>(null);
  readonly workers = signal<WorkloadShadowWorker[]>([]);
  readonly proposals = signal<WorkloadShadowProposal[]>([]);
  readonly events = signal<WorkloadShadowEvent[]>([]);
  readonly health = signal<WorkloadShadowHealth | null>(null);
  readonly settingsLoading = signal(false);
  readonly monitorLoading = signal(false);
  readonly saving = signal(false);
  readonly recalculating = signal(false);
  readonly repairing = signal(false);
  readonly settingsError = signal<string | null>(null);
  readonly monitorError = signal<string | null>(null);
  readonly selectedManagerId = signal<number | null>(null);
  readonly workerSearch = signal('');
  readonly proposalStatus = signal('ALL');
  readonly eventSeverity = signal('ALL');
  readonly recipientEligibilityBasis = WORKLOAD_SHADOW_RECIPIENT_ELIGIBILITY_BASIS;

  readonly canRepair = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasRealmRole('ADMIN') || this.auth.hasRealmRole('OWNER');
  });
  readonly managers = computed(() => {
    const summaryManagers = this.summary()?.managers ?? [];
    if (summaryManagers.length) {
      return summaryManagers
        .map((manager) => ({
          id: manager.managerId,
          name: manager.managerName || `Менеджер #${manager.managerId}`
        }))
        .sort((left, right) => left.name.localeCompare(right.name, 'ru'));
    }
    const unique = new Map<number, string>();
    this.workers().forEach((worker) => {
      if (worker.managerId != null) {
        unique.set(worker.managerId, worker.managerName || `Менеджер #${worker.managerId}`);
      }
    });
    return [...unique.entries()]
      .map(([id, name]) => ({ id, name }))
      .sort((left, right) => left.name.localeCompare(right.name, 'ru'));
  });
  readonly visibleManagerSummaries = computed(() => {
    const summaries = this.summary()?.managers ?? [];
    const managerId = this.selectedManagerId();
    return managerId == null
      ? summaries
      : summaries.filter((manager) => manager.managerId === managerId);
  });
  readonly filteredWorkers = computed(() => {
    const search = this.workerSearch().trim().toLocaleLowerCase('ru');
    return this.workers().filter((worker) => {
      if (!search) {
        return true;
      }
      return [
        worker.workerName,
        worker.username,
        worker.managerName,
        worker.distributionStatus,
        worker.diagnosticStatus,
        ...this.workerReasons(worker)
      ].some((value) => String(value ?? '').toLocaleLowerCase('ru').includes(search));
    });
  });
  readonly filteredProposals = computed(() => {
    const status = this.proposalStatus();
    return status === 'ALL'
      ? this.proposals()
      : this.proposals().filter((proposal) => proposal.status === status);
  });
  readonly proposalStatuses = computed(() => [
    ...new Set(this.proposals().map((proposal) => proposal.status).filter(Boolean))
  ].sort());
  readonly filteredEvents = computed(() => {
    const severity = this.eventSeverity();
    return severity === 'ALL'
      ? this.events()
      : this.events().filter((event) => (event.severity || 'INFO') === severity);
  });
  readonly eventSeverities = computed(() => [
    ...new Set(this.events().map((event) => event.severity || 'INFO'))
  ].sort());
  readonly metrics = computed<MonitorMetric[]>(() => {
    const summary = this.summary();
    const scoped = this.selectedManagerId() != null;
    const workerCount = scoped ? this.workers().length : summary?.workerCount ?? this.workers().length;
    const hundred = scoped
      ? this.workers().filter((worker) => this.workerPercent(worker) >= 100).length
      : summary?.hundredPercentWorkers
        ?? summary?.workersAt100
        ?? this.workers().filter((worker) => this.workerPercent(worker) >= 100).length;
    const risk = scoped
      ? this.workers().filter((worker) => Number(worker.failureDays) > 0).length
      : summary?.riskWorkers
        ?? summary?.atRiskWorkerCount
        ?? this.workers().filter((worker) => Number(worker.failureDays) > 0).length;
    const recipients = scoped
      ? this.workers().filter((worker) => this.workerEligible(worker)).length
      : summary?.eligibleRecipients
        ?? this.workers().filter((worker) => this.workerEligible(worker)).length;
    const proposals = scoped
      ? this.proposals().length
      : summary?.pendingProposals ?? summary?.transferCaseCount ?? this.proposals().length;
    const lateUnits = scoped
      ? this.workers().reduce(
          (total, worker) => total + Number(worker.lateUnits ?? worker.lateExcludedUnits ?? 0),
          0
        )
      : summary?.lateUnits ?? summary?.lateExcludedUnits
        ?? this.workers().reduce(
          (total, worker) => total + Number(worker.lateUnits ?? worker.lateExcludedUnits ?? 0),
          0
        );
    const blockedUnits = this.workers()
      .reduce((total, worker) => total + Number(worker.blockedUnits ?? 0), 0);
    const externalBlockedUnits = this.workers()
      .reduce((total, worker) => total + Number(worker.externalBlockedUnits ?? 0), 0);
    const clientDeferredUnits = this.workers()
      .reduce((total, worker) => total + Number(worker.clientDeferredUnits ?? 0), 0);
    const managerDeferredUnits = this.workers()
      .reduce((total, worker) => total + Number(worker.managerDeferredUnits ?? 0), 0);
    const staffingSignals = scoped
      ? this.proposals().filter((proposal) => proposal.staffingRequired).length
      : summary?.staffingSignals ?? summary?.staffingSignalCount
        ?? this.proposals().filter((proposal) => proposal.staffingRequired).length;
    return [
      { label: 'Специалистов', value: workerCount, icon: 'engineering', tone: 'blue' },
      { label: 'Сегодня 100%', value: hundred, icon: 'task_alt', tone: 'green' },
      { label: 'В зоне риска', value: risk, icon: 'warning', tone: risk ? 'red' : 'gray' },
      { label: 'В распределении', value: recipients, icon: 'playlist_add_check', tone: 'blue' },
      { label: 'Готовится передач', value: proposals, icon: 'move_up', tone: proposals ? 'yellow' : 'gray' },
      { label: 'Поздних единиц', value: lateUnits, icon: 'schedule', tone: 'yellow' },
      {
        label: 'Вне процента',
        value: blockedUnits,
        icon: 'rule',
        tone: 'gray',
        hint: `Внешние причины: ${externalBlockedUnits}; отложено клиентом: ${clientDeferredUnits}; менеджером: ${managerDeferredUnits}`
      },
      { label: 'Сигналов о найме', value: staffingSignals, icon: 'person_add', tone: staffingSignals ? 'red' : 'gray' },
      {
        label: 'Самодиагностика',
        value: this.health()?.status || summary?.healthStatus || '—',
        icon: 'monitor_heart',
        tone: this.healthTone()
      }
    ];
  });
  readonly lastUpdatedAt = computed(() =>
    this.summary()?.updatedAt || this.health()?.updatedAt || this.health()?.checkedAt || null
  );
  readonly healthNodes = computed<WorkloadShadowHealthNode[]>(() => {
    const health = this.health();
    if (!health) {
      return [];
    }
    if (health.nodes?.length) {
      return health.nodes;
    }
    const notificationProblems = Number(health.deadEvents ?? 0) + Number(health.staleProcessingEvents ?? 0);
    return [
      {
        name: 'Расчёты',
        status: Number(health.staleRunningRuns ?? 0) > 0
          || Number(health.expiredRecalculationLocks ?? 0) > 0 ? 'STALE' : 'UP',
        message: `Выполняется: ${health.runningRuns ?? 0}; зависло: ${health.staleRunningRuns ?? 0}; просроченных lock: ${health.expiredRecalculationLocks ?? 0}`,
        updatedAt: health.lastSnapshotAt ?? health.checkedAt,
        lagSeconds: health.snapshotAgeSeconds
      },
      {
        name: 'Целостность пакетов заказов',
        status: Number(health.graphErrorCases ?? 0) > 0
          ? 'DEGRADED'
          : Number(health.graphWarningCases ?? 0) > 0 ? 'WARNING' : 'UP',
        message: `Ошибок: ${health.graphErrorCases ?? 0}; предупреждений: ${health.graphWarningCases ?? 0}`,
        updatedAt: health.checkedAt
      },
      {
        name: 'Групповые уведомления',
        status: health.groupNotificationsEnabled === false
          ? 'PAUSED'
          : notificationProblems > 0 ? 'DEGRADED' : 'UP',
        message: `Ожидает: ${health.dueEvents ?? 0}; обрабатывается: ${health.processingEvents ?? 0}`,
        updatedAt: health.checkedAt,
        lagSeconds: health.oldestDueAgeSeconds
      },
      {
        name: 'Привязки групп',
        status: Number(health.missingGroupBindings ?? 0) > 0 ? 'DEGRADED' : 'UP',
        message: `Отсутствующих привязок: ${health.missingGroupBindings ?? 0}`,
        updatedAt: health.checkedAt
      }
    ];
  });
  readonly healthIssues = computed<WorkloadShadowHealthIssue[]>(() => {
    const health = this.health();
    if (!health) {
      return [];
    }
    if (health.issues?.length) {
      return health.issues;
    }
    const issues: WorkloadShadowHealthIssue[] = [];
    if (Number(health.staleRunningRuns ?? 0) > 0
      || Number(health.staleProcessingEvents ?? 0) > 0
      || Number(health.expiredRecalculationLocks ?? 0) > 0) {
      issues.push({
        code: 'STALE_WORK',
        severity: 'CRITICAL',
        component: 'Расчёты и доставка',
        message: `Зависшие запуски: ${health.staleRunningRuns ?? 0}; события: ${health.staleProcessingEvents ?? 0}; просроченные lock: ${health.expiredRecalculationLocks ?? 0}.`,
        detectedAt: health.checkedAt
      });
    }
    if (Number(health.deadEvents ?? 0) > 0) {
      issues.push({
        code: 'DEAD_EVENTS',
        severity: 'CRITICAL',
        component: 'Групповые уведомления',
        message: `После повторных попыток не доставлено событий: ${health.deadEvents}.`,
        detectedAt: health.checkedAt
      });
    }
    if (Number(health.missingGroupBindings ?? 0) > 0) {
      issues.push({
        code: 'MISSING_GROUPS',
        severity: 'WARNING',
        component: 'Привязки чатов',
        message: `Не найдены соответствующие чаты-группы: ${health.missingGroupBindings}.`,
        detectedAt: health.checkedAt
      });
    }
    if (Number(health.graphErrorCases ?? 0) > 0 || Number(health.graphWarningCases ?? 0) > 0) {
      issues.push({
        code: 'TRANSFER_GRAPH_INTEGRITY',
        severity: Number(health.graphErrorCases ?? 0) > 0 ? 'CRITICAL' : 'WARNING',
        component: 'Целостность пакетов заказов',
        message: `Пакетов с ошибками: ${health.graphErrorCases ?? 0}; с предупреждениями: ${health.graphWarningCases ?? 0}.`,
        detectedAt: health.checkedAt
      });
    }
    return issues;
  });

  readonly form = this.fb.nonNullable.group({
    mode: ['SHADOW', Validators.required],
    applyEnabled: [{ value: false, disabled: true }],
    observationEnabled: [true],
    groupNotificationsEnabled: [false],
    notificationGroupChatId: this.fb.control<number | null>(
      null,
      [Validators.max(-1)]
    ),
    schedulerIntervalMinutes: [10, [Validators.required, Validators.min(5), Validators.max(60)]],
    nearEndIntervalMinutes: [5, [Validators.required, Validators.min(5), Validators.max(60)]],
    nearEndWindowMinutes: [120, [Validators.required, Validators.min(15), Validators.max(360)]],
    businessZone: ['Asia/Irkutsk', Validators.required],
    shiftStart: ['10:00', [Validators.required, Validators.pattern(TIME_PATTERN)]],
    shiftEnd: ['23:00', [Validators.required, Validators.pattern(TIME_PATTERN)]],
    walkMinutesPerCard: [4, [Validators.required, Validators.min(3), Validators.max(30)]],
    walkMinimumMinutesPerCard: [3, [Validators.required, Validators.min(3), Validators.max(30)]],
    newMinutesPerCard: [5, [Validators.required, Validators.min(1), Validators.max(120)]],
    correctionMinutesPerOrder: [10, [Validators.required, Validators.min(1), Validators.max(240)]],
    publishMinutesPerCard: [3, [Validators.required, Validators.min(1), Validators.max(60)]],
    recoveryMinutesPerTask: [10, [Validators.required, Validators.min(1), Validators.max(240)]],
    badMinutesPerTask: [10, [Validators.required, Validators.min(1), Validators.max(240)]],
    adaptiveEstimatesEnabled: [true],
    adaptiveMinimumSamples: [30, [Validators.required, Validators.min(10), Validators.max(10000)]],
    lookbackDays: [30, [Validators.required, Validators.min(7), Validators.max(90)]],
    allowedFailureDays: [3, [Validators.required, Validators.min(0), Validators.max(15)]],
    recipientMinimumRating: [85, [Validators.required, Validators.min(0), Validators.max(100)]],
    recipientMinimumHundredPercentRate: [80, [Validators.required, Validators.min(0), Validators.max(100)]],
    recipientMaximumFailureDays: [2, [Validators.required, Validators.min(0), Validators.max(31)]],
    fourthFailurePercent: [15, [Validators.required, Validators.min(1), Validators.max(100)]],
    fourthFailureMaxCompanies: [1, [Validators.required, Validators.min(1), Validators.max(20)]],
    fifthFailurePercent: [25, [Validators.required, Validators.min(1), Validators.max(100)]],
    fifthFailureMaxCompanies: [2, [Validators.required, Validators.min(1), Validators.max(20)]],
    sixthFailurePercent: [30, [Validators.required, Validators.min(1), Validators.max(100)]],
    sixthFailureMaxCompanies: [3, [Validators.required, Validators.min(1), Validators.max(20)]],
    freezeEarnDays: [14, [Validators.required, Validators.min(1), Validators.max(60)]],
    freezeMaxCredits: [2, [Validators.required, Validators.min(0), Validators.max(10)]],
    alertCooldownMinutes: [60, [Validators.required, Validators.min(5), Validators.max(10080)]],
    notificationBatchSize: [250, [Validators.required, Validators.min(1), Validators.max(250)]],
    notificationMaxAttempts: [8, [Validators.required, Validators.min(1), Validators.max(20)]],
    notificationLeaseMinutes: [5, [Validators.required, Validators.min(1), Validators.max(30)]],
    notificationRetryBaseMinutes: [1, [Validators.required, Validators.min(1), Validators.max(60)]],
    maintenanceBatchSize: [1000, [Validators.required, Validators.min(100), Validators.max(5000)]],
    runRetentionDays: [30, [Validators.required, Validators.min(7), Validators.max(365)]],
    dailyRetentionDays: [400, [Validators.required, Validators.min(31), Validators.max(3650)]],
    eventRetentionDays: [90, [Validators.required, Validators.min(7), Validators.max(3650)]],
    decisionRetentionDays: [60, [Validators.required, Validators.min(7), Validators.max(365)]],
    staleRunMinutes: [30, [Validators.required, Validators.min(5), Validators.max(240)]],
    revision: [0, [Validators.required, Validators.min(0)]]
  });

  constructor() {
    this.loadSettings();
    this.loadMonitor();
    this.syncPolling();
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  @HostListener('document:visibilitychange')
  onVisibilityChange(): void {
    this.syncPolling();
  }

  setTab(tab: WorkloadTab): void {
    this.activeTab.set(tab);
    this.syncPolling();
    if (tab === 'monitor') {
      this.loadMonitor(true);
    }
  }

  selectManager(value: string | number | null): void {
    const parsed = Number(value);
    this.selectedManagerId.set(Number.isFinite(parsed) && parsed > 0 ? parsed : null);
    this.loadMonitor(true);
  }

  setWorkerSearch(value: string): void {
    this.workerSearch.set(value);
  }

  setProposalStatus(value: string): void {
    this.proposalStatus.set(value || 'ALL');
  }

  setEventSeverity(value: string): void {
    this.eventSeverity.set(value || 'ALL');
  }

  loadSettings(): void {
    this.settingsLoading.set(true);
    this.settingsError.set(null);
    this.api.getSettings().subscribe({
      next: (settings) => {
        this.applySettings(settings);
        this.settingsLoading.set(false);
      },
      error: (error) => {
        this.settingsError.set(apiErrorMessage(error, 'Не удалось загрузить настройки наблюдения'));
        this.settingsLoading.set(false);
      }
    });
  }

  saveSettings(): void {
    if (!this.settings()) {
      this.settingsError.set('Сначала загрузите текущую ревизию настроек с сервера.');
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.settingsError.set('Проверьте обязательные поля и допустимые диапазоны.');
      return;
    }
    const request = this.form.getRawValue();
    const validationError = workloadShadowSettingsError(request);
    if (validationError) {
      this.settingsError.set(validationError);
      return;
    }
    this.saving.set(true);
    this.settingsError.set(null);
    this.api.updateSettings(request).subscribe({
      next: (settings) => {
        this.applySettings(settings);
        this.saving.set(false);
        this.toast.success('Настройки наблюдения сохранены', `Ревизия ${settings.revision}`);
        this.loadMonitor(true);
      },
      error: (error) => {
        const message = apiErrorMessage(error, 'Не удалось сохранить настройки наблюдения');
        this.settingsError.set(message);
        this.saving.set(false);
        this.toast.error('Настройки не сохранены', message);
      }
    });
  }

  loadMonitor(background = false): void {
    if (!background) {
      this.monitorLoading.set(true);
    }
    const errors: string[] = [];
    const managerId = this.selectedManagerId();
    forkJoin({
      summary: this.api.getSummary().pipe(catchError((error) => {
        errors.push(apiErrorMessage(error, 'сводка'));
        return of(null);
      })),
      workers: this.api.getWorkers(managerId).pipe(catchError((error) => {
        errors.push(apiErrorMessage(error, 'специалисты'));
        return of(null);
      })),
      proposals: this.api.getProposals(managerId).pipe(catchError((error) => {
        errors.push(apiErrorMessage(error, 'предложения'));
        return of(null);
      })),
      events: this.api.getEvents(50).pipe(catchError((error) => {
        errors.push(apiErrorMessage(error, 'события'));
        return of(null);
      })),
      health: this.api.getHealth().pipe(catchError((error) => {
        errors.push(apiErrorMessage(error, 'самодиагностика'));
        return of(null);
      }))
    }).subscribe({
      next: ({ summary, workers, proposals, events, health }) => {
        if (summary) this.summary.set(summary);
        if (workers) this.workers.set(this.collectionItems(workers));
        if (proposals) this.proposals.set(this.collectionItems(proposals));
        if (events) this.events.set(this.collectionItems(events));
        if (health) this.health.set(health);
        this.monitorError.set(errors.length ? `Часть данных не обновилась: ${errors.join('; ')}` : null);
        this.monitorLoading.set(false);
      }
    });
  }

  recalculate(): void {
    if (this.recalculating()) {
      return;
    }
    this.recalculating.set(true);
    this.api.recalculate().subscribe({
      next: (result) => {
        this.recalculating.set(false);
        this.actionSuccess('Пересчёт запущен', result);
        this.loadMonitor(true);
      },
      error: (error) => {
        const message = apiErrorMessage(error, 'Не удалось запустить теневой пересчёт');
        this.recalculating.set(false);
        this.toast.error('Пересчёт не запущен', message);
      }
    });
  }

  repair(): void {
    if (!this.canRepair() || this.repairing()) {
      return;
    }
    if (!window.confirm(
      'Запустить безопасную самоналадку теневого контура? Рабочие компании и назначения не изменяются.'
    )) {
      return;
    }
    this.repairing.set(true);
    this.api.repair().subscribe({
      next: (result) => {
        this.repairing.set(false);
        const details = result.message || [
          `завершено зависших запусков: ${result.failedRuns ?? 0}`,
          `возвращено событий в очередь: ${result.retriedEvents ?? 0}`,
          `отменено событий: ${result.cancelledEvents ?? 0}`
        ].join('; ');
        this.toast.success('Самоналадка завершена', details);
        this.loadMonitor(true);
      },
      error: (error) => {
        const message = apiErrorMessage(error, 'Не удалось запустить самоналадку');
        this.repairing.set(false);
        this.toast.error('Самоналадка не запущена', message);
      }
    });
  }

  workerPercent(worker: WorkloadShadowWorker): number {
    return Math.max(0, Math.min(
      100,
      Number(worker.currentPercent ?? worker.progressPercent ?? worker.predictedPercent ?? 0)
    ));
  }

  workerEligibleUnits(worker: WorkloadShadowWorker): number {
    if (worker.eligibleUnits != null) {
      return Number(worker.eligibleUnits);
    }
    return Number(worker.completedUnits ?? 0)
      + Number(worker.feasibleUnits ?? worker.activeUnits ?? 0);
  }

  workerRate(worker: WorkloadShadowWorker): number {
    const calculated = worker.evaluatedDays
      ? (Number(worker.hundredPercentDays ?? 0) / worker.evaluatedDays) * 100
      : 0;
    return Math.max(0, Math.min(100, Number(worker.hundredPercentRate ?? calculated)));
  }

  workerEvaluatedDays(worker: WorkloadShadowWorker): number {
    return workloadShadowEvaluatedDays(worker);
  }

  workerLastCompletedDayLabel(worker: WorkloadShadowWorker): string {
    return workloadShadowLastCompletedDayLabel(worker.lastDayReached100);
  }

  workerEligible(worker: WorkloadShadowWorker): boolean {
    return worker.eligibleRecipient ?? worker.recipientEligible ?? false;
  }

  workerReasons(worker: WorkloadShadowWorker): string[] {
    if (worker.reasons?.length) {
      return worker.reasons;
    }
    const reasons: string[] = [];
    if (worker.acceptsCompanyTransfers === false) {
      reasons.push('Сотрудник отключил получение компаний');
    }
    if (worker.workerGroupConnected === false) {
      reasons.push('Не подключена рабочая группа');
    }
    if (worker.lastDayReached100 === false) {
      reasons.push('Последний завершённый день закрыт не на 100%');
    }
    if (worker.diagnosticStatus && !['OK', 'HEALTHY'].includes(worker.diagnosticStatus.toUpperCase())) {
      const diagnostic = worker.diagnosticStatus.toUpperCase();
      const diagnosticLabels: Record<string, string> = {
        AMBIGUOUS_MANAGER_LINK: 'Найдено несколько связей с менеджерами — распределение заблокировано',
        MISSING_MANAGER_GROUP: 'Не подключена audit-группа менеджера',
        MISSING_WORKER_GROUP: 'Не подключена рабочая группа специалиста',
      };
      reasons.push(diagnosticLabels[diagnostic] || worker.diagnosticStatus);
    }
    if (!this.workerEligible(worker) && !reasons.length) {
      reasons.push('Не проходит текущие пороги отбора');
    }
    return reasons;
  }

  workerDistributionLabel(worker: WorkloadShadowWorker): string {
    if (this.workerEligible(worker)) {
      return worker.distributionRank
        ? `#${worker.distributionRank} · кандидат`
        : 'Кандидат на получение';
    }
    if (Number(worker.transferStage ?? 0) > 0) {
      return `Готовится к передаче · этап ${worker.transferStage}`;
    }
    return worker.distributionStatus || worker.diagnosticStatus || 'Исключён';
  }

  proposalCompanyCount(proposal: WorkloadShadowProposal): number {
    return proposal.companyCount ?? proposal.companies?.length ?? (proposal.companyId != null ? 1 : 0);
  }

  proposalTargetPercent(proposal: WorkloadShadowProposal): number | string {
    return proposal.targetPercent ?? proposal.transferPercent ?? '—';
  }

  proposalRecommendedWorker(proposal: WorkloadShadowProposal): string {
    return proposal.recommendedWorkerName
      || proposal.fallbackWorkerName
      || proposal.candidates?.[0]?.workerName
      || 'Нет подходящего кандидата';
  }

  eventTimestamp(event: WorkloadShadowEvent): string | null {
    return event.createdAt ?? event.lastSeenAt ?? event.firstSeenAt ?? null;
  }

  eventCompany(event: WorkloadShadowEvent): string | null {
    return event.companyName ?? event.companyTitle ?? null;
  }

  healthLastRunAt(): string | null {
    return this.health()?.lastRunAt
      ?? this.health()?.lastSuccessfulRunAt
      ?? this.summary()?.lastRun?.finishedAt
      ?? null;
  }

  healthQueueDepth(): number {
    const health = this.health();
    return Number(health?.queueDepth ?? 0)
      || Number(health?.dueEvents ?? 0) + Number(health?.processingEvents ?? 0);
  }

  healthFailedCount(): number {
    const health = this.health();
    return Number(health?.failedRuns ?? 0)
      || Number(health?.deadEvents ?? 0)
      + Number(health?.staleProcessingEvents ?? 0)
      + Number(health?.staleRunningRuns ?? 0);
  }

  eventTone(event: WorkloadShadowEvent): string {
    return String(event.severity || 'INFO').toLowerCase();
  }

  statusLabel(value: string | null | undefined): string {
    switch (String(value || '').toUpperCase()) {
      case 'SHADOW': return 'Наблюдение';
      case 'OFF': return 'Выключено';
      case 'LIVE': return 'Боевой';
      case 'HEALTHY':
      case 'UP':
      case 'OK': return 'Исправно';
      case 'DEGRADED':
      case 'WARNING':
      case 'STALE': return 'Требует внимания';
      case 'PAUSED': return 'На паузе';
      case 'SHADOW_PENDING': return 'Теневое предложение';
      case 'BLOCKED_GRAPH': return 'Пакет заблокирован';
      case 'DOWN':
      case 'CRITICAL':
      case 'FAILED': return 'Ошибка';
      case 'PENDING': return 'Ожидает';
      case 'PROCESSING': return 'Отправляется';
      case 'RETRY': return 'Повторная попытка';
      case 'SENT': return 'Отправлено в общую группу';
      case 'SKIPPED': return 'Только мониторинг — не отправлялось';
      case 'MISSING_GROUP_BINDING': return 'Группа не настроена';
      case 'DEAD': return 'Доставка остановлена';
      case 'CANCELLED': return 'Событие закрыто до отправки';
      case 'READY': return 'Готово';
      default: return value || '—';
    }
  }

  trackWorker(_index: number, worker: WorkloadShadowWorker): number {
    return worker.workerId;
  }

  trackProposal(_index: number, proposal: WorkloadShadowProposal): number | string {
    return proposal.id;
  }

  trackEvent(_index: number, event: WorkloadShadowEvent): number | string {
    return event.id;
  }

  private applySettings(settings: WorkloadShadowSettings): void {
    this.settings.set(settings);
    this.form.reset({
      mode: settings.mode,
      applyEnabled: settings.applyEnabled,
      observationEnabled: settings.observationEnabled,
      groupNotificationsEnabled: settings.groupNotificationsEnabled,
      notificationGroupChatId: settings.notificationGroupChatId,
      schedulerIntervalMinutes: settings.schedulerIntervalMinutes,
      nearEndIntervalMinutes: settings.nearEndIntervalMinutes,
      nearEndWindowMinutes: settings.nearEndWindowMinutes,
      businessZone: settings.businessZone,
      shiftStart: settings.shiftStart,
      shiftEnd: settings.shiftEnd,
      walkMinutesPerCard: settings.walkMinutesPerCard,
      walkMinimumMinutesPerCard: settings.walkMinimumMinutesPerCard,
      newMinutesPerCard: settings.newMinutesPerCard,
      correctionMinutesPerOrder: settings.correctionMinutesPerOrder,
      publishMinutesPerCard: settings.publishMinutesPerCard,
      recoveryMinutesPerTask: settings.recoveryMinutesPerTask,
      badMinutesPerTask: settings.badMinutesPerTask,
      adaptiveEstimatesEnabled: settings.adaptiveEstimatesEnabled,
      adaptiveMinimumSamples: settings.adaptiveMinimumSamples,
      lookbackDays: settings.lookbackDays,
      allowedFailureDays: settings.allowedFailureDays,
      recipientMinimumRating: settings.recipientMinimumRating,
      recipientMinimumHundredPercentRate: settings.recipientMinimumHundredPercentRate,
      recipientMaximumFailureDays: settings.recipientMaximumFailureDays,
      fourthFailurePercent: settings.fourthFailurePercent,
      fourthFailureMaxCompanies: settings.fourthFailureMaxCompanies,
      fifthFailurePercent: settings.fifthFailurePercent,
      fifthFailureMaxCompanies: settings.fifthFailureMaxCompanies,
      sixthFailurePercent: settings.sixthFailurePercent,
      sixthFailureMaxCompanies: settings.sixthFailureMaxCompanies,
      freezeEarnDays: settings.freezeEarnDays,
      freezeMaxCredits: settings.freezeMaxCredits,
      alertCooldownMinutes: settings.alertCooldownMinutes,
      notificationBatchSize: settings.notificationBatchSize,
      notificationMaxAttempts: settings.notificationMaxAttempts,
      notificationLeaseMinutes: settings.notificationLeaseMinutes,
      notificationRetryBaseMinutes: settings.notificationRetryBaseMinutes,
      maintenanceBatchSize: settings.maintenanceBatchSize,
      runRetentionDays: settings.runRetentionDays,
      dailyRetentionDays: settings.dailyRetentionDays,
      eventRetentionDays: settings.eventRetentionDays,
      decisionRetentionDays: settings.decisionRetentionDays,
      staleRunMinutes: settings.staleRunMinutes,
      revision: settings.revision
    });
  }

  private collectionItems<T>(response: WorkloadShadowCollection<T>): T[] {
    if (Array.isArray(response)) {
      return response;
    }
    const value = response as { items?: T[]; content?: T[] };
    return value.items ?? value.content ?? [];
  }

  private healthTone(): MonitorTone {
    const status = String(this.health()?.status || this.summary()?.healthStatus || '').toUpperCase();
    if (['HEALTHY', 'UP', 'OK'].includes(status)) return 'green';
    if (['DOWN', 'CRITICAL', 'FAILED'].includes(status)) return 'red';
    if (status) return 'yellow';
    return 'gray';
  }

  private actionSuccess(title: string, result: WorkloadShadowActionResult): void {
    this.toast.success(title, result.message || 'Операция выполняется только в теневом контуре');
  }

  private syncPolling(): void {
    if (this.canPoll()) {
      this.startPolling();
    } else {
      this.stopPolling();
    }
  }

  private startPolling(): void {
    if (this.monitorTimerId != null || !this.canPoll()) {
      return;
    }
    this.monitorTimerId = window.setInterval(() => {
      if (this.canPoll()) {
        this.loadMonitor(true);
      } else {
        this.stopPolling();
      }
    }, MONITOR_POLLING_MS);
  }

  private stopPolling(): void {
    if (this.monitorTimerId == null) {
      return;
    }
    window.clearInterval(this.monitorTimerId);
    this.monitorTimerId = null;
  }

  private canPoll(): boolean {
    return this.activeTab() === 'monitor'
      && (typeof document === 'undefined' || document.visibilityState === 'visible');
  }
}
