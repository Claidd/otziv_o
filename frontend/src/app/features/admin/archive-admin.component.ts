import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { Observable } from 'rxjs';
import { ArchiveAdminApi } from '../../core/archive-admin.api';
import type {
  ArchiveBatchDetails,
  ArchiveBatchSummary,
  ArchiveCandidatesPreview,
  ArchiveExecutionResult,
  ArchiveLockStatus,
  ArchiveOrdersSettings,
  ArchiveRunMode
} from '../../core/archive-admin.api';
import type { ArchiveCandidateCounts } from '../../core/manager.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';

type ArchiveMetric = {
  label: string;
  value: number | string;
  icon: string;
  tone: 'blue' | 'green' | 'yellow' | 'gray' | 'red';
  helpKey: ArchiveHelpKey;
};

const ARCHIVE_HELP = {
  dryRun: 'Безопасная проверка. Архиватор находит кандидатов и пишет batch-журнал, но не копирует и не удаляет live-строки.',
  live: 'Физический перенос. Заказы и связанные строки копируются в archive_* и удаляются из live-таблиц. Доступен только при backend-флаге apply=true.',
  refresh: 'Перезагружает настройки, кандидатов, lock и журнал запусков с backend.',
  workingSlice: 'Сколько дней старые закрытые данные ещё учитываются как рабочий срез досок. Это не удаление и не перенос.',
  retentionMetric: 'Минимальный возраст закрытого заказа для физической архивации. При 90 днях cutoff сегодня минус 90 дней.',
  batchMetric: 'Сколько заказов архиватор возьмёт за один запуск. Связанные строки считаются отдельно и могут быть больше числа заказов.',
  applyEnabled: 'Жёсткий backend-предохранитель OTZIV_ARCHIVE_ORDERS_APPLY_ENABLED. Если выключен, live-перенос невозможен даже из админки.',
  scheduleMetric: 'Runtime-настройка ежедневного запуска. Работает только если отдельно включён scheduled worker на backend.',
  settingsPanel: 'Здесь задаются параметры ручного запуска и сохраняются runtime-настройки расписания.',
  retentionDays: 'Заказ становится кандидатом, если он закрыт и старше этого числа дней относительно даты cutoff. Сейчас держим 90 дней как осторожный запас.',
  batchLimit: 'Максимум заказов в одной пачке. Для production лучше повышать постепенно: 10, 50, 100, 200.',
  scheduleEnabled: 'Включает или ставит на паузу runtime-расписание. Сам worker всё равно должен быть разрешён backend-флагом.',
  scheduledMode: 'dry-run по расписанию только проверяет, live по расписанию переносит физически. Live требует apply=true.',
  scheduledReason: 'Текст причины, который попадёт в batch-журнал для автоматического запуска.',
  manualReason: 'Текст причины, который попадёт в batch-журнал для ручного запуска.',
  scheduleStrip: 'Показывает состояние scheduled worker, cron-выражение и таймзону. Если worker выключен, расписание не стартует.',
  saveSettings: 'Сохраняет retention, batch size, режим и причину расписания в app_settings. Live-флаг через UI не включается.',
  runButton: 'Запускает текущий выбранный режим вручную. Dry-run безопасен, live требует backend apply=true и подтверждение.',
  resultPanel: 'Показывает результат последнего запуска в текущей сессии страницы. После перезагрузки историю смотри в журнале.',
  eligibleOrders: 'Все закрытые live-заказы, которые старше cutoff и подходят под правила архивации.',
  selectedOrders: 'Сколько заказов реально взято в текущую пачку с учётом размера batch.',
  selectedReviews: 'Сколько отзывов связано с выбранными заказами. Они копируются/удаляются вместе с заказом в live-режиме.',
  missingMonths: 'Сколько закрытых месяцев аналитики не найдено перед переносом. Ноль означает, что блокирующих пропусков не обнаружено.',
  candidatesPanel: 'Предпросмотр ближайшей пачки без создания batch и без изменения данных.',
  cutoff: 'Дата границы: сегодня минус retention. Кандидаты должны быть старше этой даты.',
  candidateStatus: 'Бизнес-статус заказа. Сейчас архиватор берёт только закрытые статусы: Архив и Оплачено.',
  candidateSum: 'Сумма заказа из live-данных, используется только для проверки кандидата.',
  candidateDetails: 'Сколько order_details связано с заказом. Эти строки переносятся вместе с заказом.',
  candidateReviews: 'Сколько reviews связано с деталями заказа. Эти строки переносятся вместе с заказом.',
  candidateAge: 'Сколько дней прошло от даты-кандидата: changed, pay day или created.',
  journalPanel: 'История запусков архиватора. Dry-run хранит расчёт, live хранит фактический перенос.',
  batchId: 'Номер batch-запуска. По нему можно сверять archive_* строки и детали переноса.',
  batchMode: 'dry-run означает проверку без удаления; live означает физический перенос.',
  batchStatus: 'Состояние batch: завершён, завершён dry-run, ошибка или другой служебный статус.',
  batchStart: 'Когда запуск стартовал на backend.',
  batchRetention: 'Retention, с которым запускали конкретный batch.',
  batchOrders: 'Сколько заказов было выбрано и сколько фактически перенесено. В dry-run переноса нет.',
  batchRows: 'Сумма связанных строк: детали, отзывы, задачи плохих отзывов, заявки, ЗП и оплаты.',
  batchReason: 'Причина запуска. Нужна для аудита: ручная пачка, расписание, проверка и так далее.',
  batchDetails: 'Открывает подробности batch. Для live есть список перенесённых заказов; dry-run может хранить только counts.',
  detailMode: 'Режим выбранного batch в деталях.',
  detailStatus: 'Итоговый статус выбранного batch.',
  detailOrders: 'Количество заказов в выбранном batch.',
  detailReviews: 'Количество отзывов в выбранном batch.',
  detailPayments: 'Количество payment_check строк в выбранном batch.',
  sideCandidates: 'Текущее число подходящих кандидатов по выбранным настройкам.',
  sideBatch: 'Сколько заказов попадёт в ближайшую пачку.',
  lock: 'MySQL GET_LOCK не даёт двум архиваторам запуститься параллельно. Свободен значит запусков сейчас нет.',
  lastBatch: 'Самый свежий batch в журнале.',
  schedulePlan: 'Краткое состояние автоматического запуска: включён ли он, какой режим и cron.',
  safety: 'Главные предохранители: backend apply-флаг и lock от параллельных запусков.',
  latestRun: 'Короткая карточка последнего запуска из журнала.',
  manual: 'Полная справка по тому, как связаны live-данные, физический архив, dry-run, live и восстановление.'
} as const;

type ArchiveHelpKey = keyof typeof ARCHIVE_HELP;

type ArchiveManualSection = {
  title: string;
  points: string[];
};

const ARCHIVE_MANUAL_SECTIONS: ArchiveManualSection[] = [
  {
    title: '1. Два разных понятия: статус и место хранения',
    points: [
      'Live-таблицы — это текущие рабочие таблицы: orders, order_details, reviews, zp, payment_check и связанные данные.',
      'Архивные таблицы — это зеркала с префиксом archive_: archive_orders, archive_order_details, archive_reviews, archive_zp, archive_payment_check и так далее.',
      'Статус заказа и место хранения — разные вещи. Статус Архив или Оплачено ещё не означает, что строка уже лежит в archive_orders.',
      'Архиватор не передаёт данные в live. Live — это исходное место, где заказ уже лежит. Архиватор переносит из live в archive_*.'
    ]
  },
  {
    title: '2. Что показывает менеджерский Архив',
    points: [
      'Сначала заказ живёт в orders и связанных live-таблицах. Его видят обычные доски, менеджеры, детали, отзывы и оплаты.',
      'Когда заказ получает бизнес-статус Оплачено или Архив, он всё ещё может физически лежать в orders.',
      'Страница /manager/archive показывает два вида данных: закрытые live-заказы и уже физически перенесённые archive_* записи.',
      'У live-заказов со статусом Архив есть быстрые кнопки Новый, Коррекция и На проверке. У физически перенесённых записей появляется восстановление из archive_* обратно в live.'
    ]
  },
  {
    title: '3. Как выбираются кандидаты',
    points: [
      'Архиватор берёт только закрытые статусы: Архив и Оплачено.',
      'Заказ должен быть старше retention. Сейчас физическая архивация настроена на 90 дней: cutoff считается как текущая дата минус 90 дней.',
      'Для Оплачено главная дата-кандидат — order_pay_day, потом order_changed, потом order_created.',
      'Для Архив главная дата-кандидат — order_changed, потом order_pay_day, потом order_created.',
      'Размер пачки ограничивает только число заказов. Отзывы, детали, ЗП, оплаты и другие связанные строки подтягиваются к выбранным заказам автоматически.'
    ]
  },
  {
    title: '4. Dry-run и Live',
    points: [
      'Dry-run ничего не переносит и не удаляет. Он только считает: сколько заказов, деталей, отзывов, ЗП и оплат попало бы в пачку.',
      'Dry-run создаёт запись в archive_batches, чтобы можно было сверить расчёт и историю запуска.',
      'Live берёт кандидатов, копирует orders в archive_orders, а связанные строки — в archive_order_details, archive_reviews, archive_zp, archive_payment_check и другие archive_*-таблицы.',
      'После копирования live-режим проверяет, что в архив попало ровно столько строк, сколько было выбрано, и только потом удаляет эти строки из live-таблиц.',
      'Live защищён backend-флагом OTZIV_ARCHIVE_ORDERS_APPLY_ENABLED=true и ручным confirm. Когда флаг false, UI не сможет выполнить перенос.'
    ]
  },
  {
    title: '5. Расписание и предохранители',
    points: [
      'Команду на перенос может дать человек через кнопку запуска на этой странице или scheduled worker по cron-расписанию.',
      'Ежедневный запуск в UI — это runtime-переключатель в app_settings. Он говорит worker: можно ли запускаться и в каком режиме, dry-run или live.',
      'Scheduled worker включается отдельно флагом OTZIV_ARCHIVE_ORDERS_SCHEDULE_ENABLED=true. Если backend-флаг выключен, расписание не стартует даже при включённом переключателе в UI.',
      'Если расписание включено и режим dry-run, worker только создаёт проверочный batch. Если выбран live, worker попытается перенести данные физически, но только при OTZIV_ARCHIVE_ORDERS_APPLY_ENABLED=true.',
      'Lock защищает от параллельных запусков: второй архиватор не начнёт перенос, пока первый держит lock.'
    ]
  },
  {
    title: '6. Безопасный production-порядок',
    points: [
      'Архиватор не берёт заказ, если у него есть открытая задача плохого отзыва со статусом NEW.',
      'Архиватор не берёт заказ, если есть заявка на следующий заказ со статусом PENDING или FAILED.',
      'Перед live-переносом проверяется, что месяцы аналитики для данных пачки закрыты. Если есть незакрытый месяц, live останавливается.',
      'Безопасный порядок: сначала смотрим Ближайшие кандидаты, потом запускаем dry-run, сверяем batch details, затем делаем маленькую live-пачку и проверяем SQL/API/логи.'
    ]
  },
  {
    title: '7. Что происходит после переноса',
    points: [
      'После live-переноса заказ исчезает из orders и связанных live-таблиц, поэтому больше не грузит обычные рабочие доски.',
      'Данные не теряются: заказ лежит в archive_orders, а связанные строки — в соответствующих archive_*-таблицах.',
      'Физически перенесённый заказ продолжает отображаться в /manager/archive как archive-запись.',
      'Восстановление копирует заказ из archive_orders обратно в orders и возвращает связанные строки в live-таблицы. При восстановлении можно задать рабочий статус, например Архив, Новый, Коррекция или На проверке.',
      'Короткая схема: orders -> dry-run только считает; orders -> live переносит -> archive_orders; archive_orders -> restore возвращает -> orders.'
    ]
  }
];

@Component({
  selector: 'app-archive-admin',
  imports: [AdminLayoutComponent, DatePipe, DecimalPipe, FormsModule, LoadErrorCardComponent],
  templateUrl: './archive-admin.component.html',
  styleUrl: './archive-admin.component.scss'
})
export class ArchiveAdminComponent {
  readonly manualSections = ARCHIVE_MANUAL_SECTIONS;
  readonly settings = signal<ArchiveOrdersSettings | null>(null);
  readonly batches = signal<ArchiveBatchSummary[]>([]);
  readonly result = signal<ArchiveExecutionResult | null>(null);
  readonly candidatePreview = signal<ArchiveCandidatesPreview | null>(null);
  readonly selectedBatch = signal<ArchiveBatchDetails | null>(null);
  readonly lockStatus = signal<ArchiveLockStatus | null>(null);
  readonly error = signal<string | null>(null);
  readonly loading = signal(false);
  readonly running = signal(false);
  readonly saving = signal(false);
  readonly candidatesLoading = signal(false);
  readonly batchDetailsLoading = signal(false);
  readonly lockLoading = signal(false);
  readonly mode = signal<ArchiveRunMode>('dry-run');

  retentionDays = 90;
  batchLimit = 500;
  reason = 'manual-orders-retention-dry-run';
  scheduleEnabled = false;
  scheduledMode: ArchiveRunMode = 'dry-run';
  scheduledReason = 'scheduled-orders-retention-dry-run';

  readonly settingsMetrics = computed<ArchiveMetric[]>(() => {
    const settings = this.settings();
    if (!settings) {
      return [];
    }

    return [
      { label: 'Рабочий срез', value: `${settings.boardLiveSliceRetentionDays} дн.`, icon: 'filter_alt', tone: 'blue', helpKey: 'workingSlice' },
      { label: 'Кандидаты старше', value: `${settings.archiveRetentionDays} дн.`, icon: 'event_repeat', tone: 'yellow', helpKey: 'retentionMetric' },
      { label: 'Размер пачки', value: settings.batchSize, icon: 'inventory_2', tone: 'green', helpKey: 'batchMetric' },
      { label: 'Live перенос', value: settings.applyEnabled ? 'разрешён' : 'выключен', icon: 'lock', tone: settings.applyEnabled ? 'red' : 'gray', helpKey: 'applyEnabled' },
      { label: 'Ежедневный запуск', value: settings.scheduleEnabled ? settings.runMode : 'выключен', icon: 'event_available', tone: settings.scheduleEnabled ? 'green' : 'gray', helpKey: 'scheduleMetric' }
    ];
  });

  readonly resultMetrics = computed<ArchiveMetric[]>(() => {
    const result = this.result();
    if (!result) {
      return [];
    }
    const selected = result.selected;
    return [
      { label: 'Подходящих заказов', value: result.eligibleOrders, icon: 'search', tone: 'blue', helpKey: 'eligibleOrders' },
      { label: 'Выбрано в пачку', value: selected.orders, icon: 'inventory', tone: 'green', helpKey: 'selectedOrders' },
      { label: 'Отзывы', value: selected.reviews, icon: 'rate_review', tone: 'yellow', helpKey: 'selectedReviews' },
      { label: 'Месяцев без агрегатов', value: result.missingClosedAnalyticsMonths, icon: 'analytics', tone: result.missingClosedAnalyticsMonths ? 'red' : 'gray', helpKey: 'missingMonths' }
    ];
  });

  readonly rightMetrics = computed<ArchiveMetric[]>(() => {
    const preview = this.candidatePreview();
    const latest = this.batches()[0];
    return [
      {
        label: 'Кандидатов сейчас',
        value: preview?.eligibleOrders ?? 0,
        icon: 'inventory_2',
        tone: 'blue',
        helpKey: 'sideCandidates'
      },
      {
        label: 'В ближайшей пачке',
        value: preview?.selected.orders ?? 0,
        icon: 'inventory',
        tone: 'green',
        helpKey: 'sideBatch'
      },
      {
        label: 'Lock',
        value: this.lockStatus()?.locked ? 'занят' : 'свободен',
        icon: this.lockStatus()?.locked ? 'lock' : 'lock_open',
        tone: this.lockStatus()?.locked ? 'red' : 'green',
        helpKey: 'lock'
      },
      {
        label: 'Последний batch',
        value: latest ? `#${latest.batchId}` : '-',
        icon: 'receipt_long',
        tone: latest?.dryRun === false ? 'yellow' : 'gray',
        helpKey: 'lastBatch'
      }
    ];
  });

  readonly previewItems = computed(() => this.candidatePreview()?.items ?? []);

  constructor(private readonly archiveApi: ArchiveAdminApi) {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.archiveApi.getOrderSettings().subscribe({
      next: (settings) => {
        this.settings.set(settings);
        this.applySettings(settings);
        this.loadBatches();
        this.loadCandidatePreview();
        this.loadLockStatus();
      },
      error: (error) => {
        this.error.set(apiErrorDetail(error, 'Не удалось загрузить настройки архиватора.'));
        this.loading.set(false);
      }
    });
  }

  loadBatches(): void {
    this.archiveApi.latestBatches(30).subscribe({
      next: (batches) => {
        this.batches.set(batches);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(apiErrorDetail(error, 'Не удалось загрузить журнал архиватора.'));
        this.loading.set(false);
      }
    });
  }

  run(): void {
    const mode = this.mode();
    const settings = this.settings();
    if (mode === 'live' && !settings?.applyEnabled) {
      this.error.set('Live перенос выключен на backend. Включается только через OTZIV_ARCHIVE_ORDERS_APPLY_ENABLED=true.');
      return;
    }

    if (mode === 'live' && !window.confirm('Запустить физический перенос выбранной пачки в архив?')) {
      return;
    }

    this.running.set(true);
    this.error.set(null);
    this.result.set(null);

    const request = {
      retentionDays: this.positiveOrNull(this.retentionDays),
      batchLimit: this.positiveOrNull(this.batchLimit),
      reason: this.reason
    };

    const call: Observable<ArchiveExecutionResult> = mode === 'live'
      ? this.archiveApi.runOrders(request)
      : this.archiveApi.dryRunOrders(request);

    call.subscribe({
      next: (result: ArchiveExecutionResult) => {
        this.result.set(result);
        this.running.set(false);
        this.loadBatches();
        this.loadCandidatePreview();
        this.loadLockStatus();
      },
      error: (error: unknown) => {
        this.error.set(apiErrorDetail(error, 'Архиватор не выполнил запуск.'));
        this.running.set(false);
      }
    });
  }

  saveSettings(): void {
    if (this.scheduledMode === 'live' && !this.settings()?.applyEnabled) {
      this.error.set('Live режим можно сохранить только после включения OTZIV_ARCHIVE_ORDERS_APPLY_ENABLED=true.');
      return;
    }

    this.saving.set(true);
    this.error.set(null);

    this.archiveApi.updateOrderSettings({
      archiveRetentionDays: this.positiveOrFallback(this.retentionDays, 90),
      batchSize: this.positiveOrFallback(this.batchLimit, 500),
      scheduleEnabled: this.scheduleEnabled,
      runMode: this.scheduledMode,
      reason: this.scheduledReason
    }).subscribe({
      next: (settings) => {
        this.settings.set(settings);
        this.applySettings(settings);
        this.saving.set(false);
        this.loadCandidatePreview();
        this.loadLockStatus();
      },
      error: (error: unknown) => {
        this.error.set(apiErrorDetail(error, 'Настройки архиватора не сохранены.'));
        this.saving.set(false);
      }
    });
  }

  setMode(mode: ArchiveRunMode): void {
    this.mode.set(mode);
  }

  countTotal(counts: ArchiveCandidateCounts | null | undefined): number {
    if (!counts) {
      return 0;
    }
    return counts.orders
      + counts.orderDetails
      + counts.reviews
      + counts.badReviewTasks
      + counts.nextOrderRequests
      + counts.zp
      + counts.paymentCheck;
  }

  loadCandidatePreview(): void {
    this.candidatesLoading.set(true);
    this.archiveApi.previewOrderCandidates({
      retentionDays: this.positiveOrNull(this.retentionDays),
      batchLimit: this.positiveOrNull(this.batchLimit),
      previewLimit: 8
    }).subscribe({
      next: (preview) => {
        this.candidatePreview.set(preview);
        this.candidatesLoading.set(false);
      },
      error: (error: unknown) => {
        this.error.set(apiErrorDetail(error, 'Не удалось загрузить кандидатов архиватора.'));
        this.candidatesLoading.set(false);
      }
    });
  }

  loadLockStatus(): void {
    this.lockLoading.set(true);
    this.archiveApi.getOrderArchiveLockStatus().subscribe({
      next: (status) => {
        this.lockStatus.set(status);
        this.lockLoading.set(false);
      },
      error: (error: unknown) => {
        this.error.set(apiErrorDetail(error, 'Не удалось загрузить lock архиватора.'));
        this.lockLoading.set(false);
      }
    });
  }

  openBatchDetails(batch: ArchiveBatchSummary): void {
    this.batchDetailsLoading.set(true);
    this.archiveApi.getBatchDetails(batch.batchId).subscribe({
      next: (details) => {
        this.selectedBatch.set(details);
        this.batchDetailsLoading.set(false);
      },
      error: (error: unknown) => {
        this.error.set(apiErrorDetail(error, 'Не удалось загрузить детали batch.'));
        this.batchDetailsLoading.set(false);
      }
    });
  }

  candidateAgeDays(candidateDate?: string): number {
    if (!candidateDate) {
      return 0;
    }
    const date = new Date(candidateDate);
    if (Number.isNaN(date.getTime())) {
      return 0;
    }
    const msPerDay = 24 * 60 * 60 * 1000;
    return Math.max(0, Math.floor((Date.now() - date.getTime()) / msPerDay));
  }

  batchMode(batch: ArchiveBatchSummary): string {
    return batch.dryRun ? 'dry-run' : 'live';
  }

  trackBatch(_index: number, batch: ArchiveBatchSummary): number {
    return batch.batchId;
  }

  trackCandidate(_index: number, candidate: { id: number }): number {
    return candidate.id;
  }

  metricId(index: number, metric: ArchiveMetric): string {
    return `${index}-${metric.label}`;
  }

  help(key: ArchiveHelpKey): string {
    return ARCHIVE_HELP[key];
  }

  trackManualSection(_index: number, section: ArchiveManualSection): string {
    return section.title;
  }

  trackManualPoint(index: number, point: string): string {
    return `${index}-${point}`;
  }

  private positiveOrNull(value: number | null | undefined): number | null {
    const numeric = Number(value ?? 0);
    return Number.isFinite(numeric) && numeric > 0 ? numeric : null;
  }

  private positiveOrFallback(value: number | null | undefined, fallback: number): number {
    return this.positiveOrNull(value) ?? fallback;
  }

  private applySettings(settings: ArchiveOrdersSettings): void {
    this.retentionDays = settings.archiveRetentionDays;
    this.batchLimit = settings.batchSize;
    this.scheduleEnabled = settings.scheduleEnabled;
    this.scheduledMode = settings.runMode;
    this.scheduledReason = settings.reason;
    this.mode.set(settings.applyEnabled ? settings.runMode : 'dry-run');
    this.reason = settings.reason || 'manual-orders-retention-dry-run';
  }
}
