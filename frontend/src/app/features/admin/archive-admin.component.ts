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
};

@Component({
  selector: 'app-archive-admin',
  imports: [AdminLayoutComponent, DatePipe, DecimalPipe, FormsModule, LoadErrorCardComponent],
  templateUrl: './archive-admin.component.html',
  styleUrl: './archive-admin.component.scss'
})
export class ArchiveAdminComponent {
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

  retentionDays = 60;
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
      { label: 'Рабочий срез', value: `${settings.boardLiveSliceRetentionDays} дн.`, icon: 'filter_alt', tone: 'blue' },
      { label: 'Кандидаты старше', value: `${settings.archiveRetentionDays} дн.`, icon: 'event_repeat', tone: 'yellow' },
      { label: 'Размер пачки', value: settings.batchSize, icon: 'inventory_2', tone: 'green' },
      { label: 'Live перенос', value: settings.applyEnabled ? 'разрешён' : 'выключен', icon: 'lock', tone: settings.applyEnabled ? 'red' : 'gray' },
      { label: 'Ежедневный запуск', value: settings.scheduleEnabled ? settings.runMode : 'выключен', icon: 'event_available', tone: settings.scheduleEnabled ? 'green' : 'gray' }
    ];
  });

  readonly resultMetrics = computed<ArchiveMetric[]>(() => {
    const result = this.result();
    if (!result) {
      return [];
    }
    const selected = result.selected;
    return [
      { label: 'Подходящих заказов', value: result.eligibleOrders, icon: 'search', tone: 'blue' },
      { label: 'Выбрано в пачку', value: selected.orders, icon: 'inventory', tone: 'green' },
      { label: 'Отзывы', value: selected.reviews, icon: 'rate_review', tone: 'yellow' },
      { label: 'Месяцев без агрегатов', value: result.missingClosedAnalyticsMonths, icon: 'analytics', tone: result.missingClosedAnalyticsMonths ? 'red' : 'gray' }
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
        tone: 'blue'
      },
      {
        label: 'В ближайшей пачке',
        value: preview?.selected.orders ?? 0,
        icon: 'inventory',
        tone: 'green'
      },
      {
        label: 'Lock',
        value: this.lockStatus()?.locked ? 'занят' : 'свободен',
        icon: this.lockStatus()?.locked ? 'lock' : 'lock_open',
        tone: this.lockStatus()?.locked ? 'red' : 'green'
      },
      {
        label: 'Последний batch',
        value: latest ? `#${latest.batchId}` : '-',
        icon: 'receipt_long',
        tone: latest?.dryRun === false ? 'yellow' : 'gray'
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
      archiveRetentionDays: this.positiveOrFallback(this.retentionDays, 60),
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
