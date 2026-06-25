import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminSpecialistTransfersApi, SpecialistTransferAudit, SpecialistTransferPreview, SpecialistTransferResult } from '../../../core/admin-specialist-transfers.api';
import { AdminUsersApi, AssignmentOption } from '../../../core/admin-users.api';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { ToastService } from '../../../shared/toast.service';

@Component({
  selector: 'app-specialist-transfer',
  imports: [DatePipe, FormsModule],
  templateUrl: './specialist-transfer.component.html',
  styleUrl: './specialist-transfer.component.scss'
})
export class SpecialistTransferComponent {
  private readonly transfersApi = inject(AdminSpecialistTransfersApi);
  private readonly adminUsersApi = inject(AdminUsersApi);
  private readonly toastService = inject(ToastService);
  private readonly confirmationText = 'ПЕРЕНЕСТИ';

  readonly workers = signal<AssignmentOption[]>([]);
  readonly fromWorkerId = signal<number | null>(null);
  readonly toWorkerId = signal<number | null>(null);
  readonly comment = signal('');
  readonly confirmation = signal('');
  readonly optionsLoading = signal(false);
  readonly previewLoading = signal(false);
  readonly applyLoading = signal(false);
  readonly recentLoading = signal(false);
  readonly error = signal<string | null>(null);
  readonly preview = signal<SpecialistTransferPreview | null>(null);
  readonly result = signal<SpecialistTransferResult | null>(null);
  readonly recentTransfers = signal<SpecialistTransferAudit[]>([]);

  readonly sortedWorkers = computed(() => [...this.workers()].sort((left, right) =>
    this.workerLabel(left).localeCompare(this.workerLabel(right), 'ru')
  ));
  readonly canPreview = computed(() => {
    const fromWorkerId = this.fromWorkerId();
    const toWorkerId = this.toWorkerId();
    return Boolean(fromWorkerId && toWorkerId && fromWorkerId !== toWorkerId && !this.previewLoading() && !this.applyLoading());
  });
  readonly canApply = computed(() => {
    const preview = this.preview();
    return Boolean(
      preview
      && preview.companyCount > 0
      && this.confirmation().trim() === this.confirmationText
      && !this.applyLoading()
      && !this.previewLoading()
    );
  });

  constructor() {
    this.loadWorkers();
    this.loadRecent();
  }

  setFromWorkerId(value: string | number | null): void {
    this.fromWorkerId.set(this.parseId(value));
    this.resetPreview();
  }

  setToWorkerId(value: string | number | null): void {
    this.toWorkerId.set(this.parseId(value));
    this.resetPreview();
  }

  setComment(value: string): void {
    this.comment.set(value);
  }

  setConfirmation(value: string): void {
    this.confirmation.set(value);
  }

  previewTransfer(): void {
    if (!this.canPreview()) {
      return;
    }

    this.error.set(null);
    this.result.set(null);
    this.previewLoading.set(true);
    this.transfersApi.preview(this.request()).subscribe({
      next: (preview) => {
        this.preview.set(preview);
        this.confirmation.set('');
        this.previewLoading.set(false);
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Не удалось проверить перенос');
        this.error.set(message);
        this.previewLoading.set(false);
        this.toastService.error('Проверка не выполнена', message);
      }
    });
  }

  applyTransfer(): void {
    if (!this.canApply()) {
      return;
    }

    this.error.set(null);
    this.applyLoading.set(true);
    this.transfersApi.apply({
      ...this.request(),
      confirmationText: this.confirmation().trim()
    }).subscribe({
      next: (result) => {
        this.result.set(result);
        this.preview.set(null);
        this.confirmation.set('');
        this.applyLoading.set(false);
        this.toastService.success('Компании перенесены', `${result.companyCount} компаний`);
        this.loadRecent();
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Не удалось выполнить перенос');
        this.error.set(message);
        this.applyLoading.set(false);
        this.toastService.error('Перенос не выполнен', message);
      }
    });
  }

  swapWorkers(): void {
    const fromWorkerId = this.fromWorkerId();
    this.fromWorkerId.set(this.toWorkerId());
    this.toWorkerId.set(fromWorkerId);
    this.resetPreview();
  }

  workerLabel(worker: AssignmentOption): string {
    return worker.fio || worker.username || `Специалист #${worker.id}`;
  }

  transferTitle(transfer: SpecialistTransferAudit): string {
    return `${transfer.fromWorkerName} -> ${transfer.toWorkerName}`;
  }

  trackWorker(_index: number, worker: AssignmentOption): number {
    return worker.id;
  }

  trackTransfer(_index: number, transfer: SpecialistTransferAudit): number {
    return transfer.id;
  }

  trackCompany(_index: number, company: { id: number }): number {
    return company.id;
  }

  trackWarning(_index: number, warning: { code: string }): string {
    return warning.code;
  }

  private loadWorkers(): void {
    this.optionsLoading.set(true);
    this.adminUsersApi.getAssignmentOptions().subscribe({
      next: (options) => {
        this.workers.set(options.workers ?? []);
        this.optionsLoading.set(false);
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Не удалось загрузить специалистов');
        this.error.set(message);
        this.optionsLoading.set(false);
        this.toastService.error('Специалисты не загрузились', message);
      }
    });
  }

  private loadRecent(): void {
    this.recentLoading.set(true);
    this.transfersApi.recent().subscribe({
      next: (transfers) => {
        this.recentTransfers.set(transfers);
        this.recentLoading.set(false);
      },
      error: () => {
        this.recentLoading.set(false);
      }
    });
  }

  private request() {
    return {
      fromWorkerId: this.fromWorkerId(),
      toWorkerId: this.toWorkerId(),
      companyIds: null,
      comment: this.comment().trim() || undefined
    };
  }

  private parseId(value: string | number | null): number | null {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
  }

  private resetPreview(): void {
    this.preview.set(null);
    this.result.set(null);
    this.confirmation.set('');
    this.error.set(null);
  }
}
