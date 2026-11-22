import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AdminSpecialistTransfersApi,
  SpecialistTransferSource,
  SpecialistTransferAudit,
  SpecialistTransferPreview,
  SpecialistTransferRequest,
  SpecialistTransferResult,
  SpecialistTransferWarning
} from '../../../core/admin-specialist-transfers.api';
import { AdminUsersApi, AssignmentOption } from '../../../core/admin-users.api';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { ToastService } from '../../../shared/toast.service';

/** The server accepts a transfer only with this word in capitals; the field takes it in any case and sends it in capitals. */
const CONFIRMATION_TEXT = 'ПЕРЕНЕСТИ';

/** The server keeps at most this many characters of a comment, so the field stops there instead of a silent cut. */
export const TRANSFER_COMMENT_MAX_LENGTH = 1000;

/** Russian noun forms after a number: one, a few and many, as in ['компания', 'компании', 'компаний']. */
export type CountForms = readonly [one: string, few: string, many: string];

/** The noun form after a number: 1 компания, 3 компании, 11 компаний, 21 компания. */
export function countForm(value: number, forms: CountForms): string {
  const whole = Math.abs(Math.trunc(Number.isFinite(value) ? value : 0));
  const lastTwo = whole % 100;
  const last = whole % 10;
  if (last === 1 && lastTwo !== 11) {
    return forms[0];
  }
  if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) {
    return forms[1];
  }
  return forms[2];
}

/** A number with its noun: «1 запись», «3 записи», «20 записей». */
export function countLabel(value: number, forms: CountForms): string {
  return `${Number.isFinite(value) ? value : 0} ${countForm(value, forms)}`;
}

const COMPANIES: CountForms = ['компания', 'компании', 'компаний'];
const ORDERS: CountForms = ['заказ', 'заказа', 'заказов'];
const REVIEWS: CountForms = ['отзыв', 'отзыва', 'отзывов'];
const BAD_REVIEW_TASKS: CountForms = ['задача по плохим отзывам', 'задачи по плохим отзывам', 'задач по плохим отзывам'];
const RECOVERIES: CountForms = ['восстановление', 'восстановления', 'восстановлений'];
const TASKS: CountForms = ['задача', 'задачи', 'задач'];
const RECORDS: CountForms = ['запись', 'записи', 'записей'];

/** The pair of specialists a check or a transfer was sent for. */
type TransferPair = { fromWorkerId: number; toWorkerId: number };

/** One number of a journal record: the value in bold and its noun after it. */
export type TransferStat = { value: number; word: string };

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
  /** Only the answer of the latest journal request is shown: an older one may come back after it. */
  private recentRequest = 0;

  readonly commentMaxLength = TRANSFER_COMMENT_MAX_LENGTH;

  readonly workers = signal<AssignmentOption[]>([]);
  /** «От специалиста»: everyone with companies to move, deactivated specialists included and marked. */
  readonly sourceWorkers = signal<SpecialistTransferSource[]>([]);
  readonly fromWorkerId = signal<number | null>(null);
  readonly toWorkerId = signal<number | null>(null);
  readonly comment = signal('');
  readonly confirmation = signal('');
  readonly optionsLoading = signal(false);
  readonly previewLoading = signal(false);
  readonly applyLoading = signal(false);
  readonly recentLoading = signal(false);
  /** The specialists or the check did not load: shown in the first block. */
  readonly error = signal<string | null>(null);
  /** The transfer itself failed: shown next to «Перенести», where it was pressed. */
  readonly applyError = signal<string | null>(null);
  /** «Последние операции» did not load: an error with «Повторить» instead of an empty journal. */
  readonly recentError = signal<string | null>(null);
  readonly preview = signal<SpecialistTransferPreview | null>(null);
  readonly result = signal<SpecialistTransferResult | null>(null);
  readonly recentTransfers = signal<SpecialistTransferAudit[]>([]);

  readonly sortedWorkers = computed(() => [...this.workers()].sort((left, right) =>
    this.workerLabel(left).localeCompare(this.workerLabel(right), 'ru')
  ));

  /** A check or a transfer is running: the lists and the comment stay as they were sent. */
  readonly busy = computed(() => this.previewLoading() || this.applyLoading());

  /** Two different specialists are chosen. */
  private readonly selectedPair = computed((): TransferPair | null => {
    const fromWorkerId = this.fromWorkerId();
    const toWorkerId = this.toWorkerId();
    return fromWorkerId && toWorkerId && fromWorkerId !== toWorkerId ? { fromWorkerId, toWorkerId } : null;
  });

  readonly canPreview = computed(() => this.selectedPair() !== null && !this.busy());

  /** Why «Проверить» is not available yet; null when it is, and while a request runs. */
  readonly previewHint = computed((): string | null => {
    if (this.busy() || this.selectedPair()) {
      return null;
    }
    if (this.optionsLoading()) {
      return 'Загружаю список специалистов.';
    }
    const fromWorkerId = this.fromWorkerId();
    return fromWorkerId && fromWorkerId === this.toWorkerId()
      ? 'Выберите двух разных специалистов.'
      : 'Выберите, от кого и кому передать работу.';
  });

  /** The shown check is about the specialists selected now. */
  readonly previewMatchesSelection = computed(() => {
    const preview = this.preview();
    const pair = this.selectedPair();
    return preview !== null && pair !== null && this.answersPair(preview, pair);
  });

  /**
   * A transfer would change nothing: no orders, reviews or tasks to hand over, and the new specialist is
   * already in every company. The server keeps the old links, so a repeated transfer of the same pair
   * would otherwise pass as a success and write one more journal record.
   */
  readonly nothingToMove = computed(() => {
    const preview = this.preview();
    if (!preview || preview.companyCount <= 0) {
      return false;
    }
    const work = preview.activeOrderCount + preview.unpublishedReviewCount
      + preview.badReviewTaskCount + preview.reviewRecoveryTaskCount;
    return work === 0 && preview.targetAlreadyAssignedCompanyCount >= preview.companyCount;
  });

  /** Why the shown check cannot be transferred at all; null when only the word is left to type. */
  readonly applyStopReason = computed((): string | null => {
    const preview = this.preview();
    if (!preview) {
      return null;
    }
    if (!this.previewMatchesSelection()) {
      return 'Специалисты изменились: нажмите «Проверить» ещё раз.';
    }
    if (preview.companyCount <= 0) {
      return 'Нечего переносить: нет компаний для переноса.';
    }
    if (this.nothingToMove()) {
      return 'Нечего переносить: заказов, отзывов и задач нет, а новый специалист уже есть во всех компаниях.';
    }
    return null;
  });

  /** The word is typed: the case does not matter, the server gets it in capitals. */
  readonly confirmationTyped = computed(() => this.confirmation().trim().toUpperCase() === CONFIRMATION_TEXT);

  readonly canApply = computed(() =>
    this.preview() !== null && !this.busy() && this.applyStopReason() === null && this.confirmationTyped()
  );

  /** The check lists at most 20 companies: say so when the specialist has more. */
  readonly sampleNote = computed((): string | null => {
    const preview = this.preview();
    if (!preview) {
      return null;
    }
    const shown = preview.sampleCompanies.length;
    return shown > 0 && shown < preview.companyCount ? `показаны первые ${shown} из ${preview.companyCount}` : null;
  });

  readonly recentChip = computed(() => {
    if (this.recentLoading()) {
      return 'Загрузка';
    }
    if (this.recentError()) {
      return 'Ошибка';
    }
    return countLabel(this.recentTransfers().length, RECORDS);
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
    const pair = this.selectedPair();
    if (!pair || this.busy()) {
      return;
    }

    this.error.set(null);
    this.applyError.set(null);
    this.result.set(null);
    this.previewLoading.set(true);
    this.transfersApi.preview(this.request(pair)).subscribe({
      next: (preview) => {
        this.previewLoading.set(false);
        // The answer is about the pair that was sent. If the lists changed meanwhile it is dropped,
        // so the check on screen and the pair that «Перенести» sends can never differ.
        if (!this.isSelected(pair)) {
          return;
        }
        if (!this.answersPair(preview, pair)) {
          this.error.set('Проверка вернула другую пару специалистов. Нажмите «Проверить» ещё раз.');
          return;
        }
        this.preview.set(preview);
        this.confirmation.set('');
      },
      error: (err) => {
        this.previewLoading.set(false);
        if (!this.isSelected(pair)) {
          return;
        }
        const message = apiErrorMessage(err, 'Не удалось проверить перенос');
        this.error.set(message);
        this.toastService.error('Проверка не выполнена', message);
      }
    });
  }

  applyTransfer(): void {
    const preview = this.preview();
    if (!preview || !this.canApply()) {
      return;
    }

    // The transfer goes exactly to the pair of the check on screen (canApply has matched it with the lists).
    const pair: TransferPair = { fromWorkerId: preview.fromWorker.id, toWorkerId: preview.toWorker.id };
    this.error.set(null);
    this.applyError.set(null);
    this.applyLoading.set(true);
    this.transfersApi.apply({
      ...this.request(pair),
      confirmationText: CONFIRMATION_TEXT
    }).subscribe({
      next: (result) => {
        this.result.set(result);
        this.preview.set(null);
        this.confirmation.set('');
        this.applyLoading.set(false);
        this.toastService.success('Работа передана', this.resultSummary(result));
        this.loadRecent();
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Не удалось выполнить перенос');
        this.applyError.set(message);
        this.applyLoading.set(false);
        this.toastService.error('Перенос не выполнен', message);
      }
    });
  }

  swapWorkers(): void {
    if (this.busy()) {
      return;
    }
    const fromWorkerId = this.fromWorkerId();
    this.fromWorkerId.set(this.toWorkerId());
    this.toWorkerId.set(fromWorkerId);
    this.resetPreview();
  }

  /** Loads «Последние операции»; also the «Повторить» of its error. */
  loadRecent(): void {
    const request = ++this.recentRequest;
    this.recentLoading.set(true);
    this.recentError.set(null);
    this.transfersApi.recent().subscribe({
      next: (transfers) => {
        if (request !== this.recentRequest) {
          return;
        }
        this.recentTransfers.set(transfers ?? []);
        this.recentLoading.set(false);
      },
      error: (err) => {
        if (request !== this.recentRequest) {
          return;
        }
        this.recentError.set(apiErrorMessage(err, 'Не удалось загрузить последние операции'));
        this.recentLoading.set(false);
      }
    });
  }

  /** The name with the login, so that namesakes differ in the lists. */
  workerLabel(worker: AssignmentOption): string {
    const fio = worker.fio?.trim();
    const login = worker.username?.trim();
    if (fio && login && fio !== login) {
      return `${fio} (${login})`;
    }
    return fio || login || `Специалист #${worker.id}`;
  }

  transferTitle(transfer: SpecialistTransferAudit): string {
    return `${transfer.fromWorkerName} → ${transfer.toWorkerName}`;
  }

  /** The numbers of a journal record with their nouns: «1 компания», «3 заказа». */
  transferStats(transfer: SpecialistTransferAudit): TransferStat[] {
    return [
      this.stat(transfer.companyCount, COMPANIES),
      this.stat(transfer.orderCount, ORDERS),
      this.stat(transfer.reviewCount, REVIEWS),
      this.stat(transfer.badReviewTaskCount, BAD_REVIEW_TASKS),
      this.stat(transfer.reviewRecoveryTaskCount, RECOVERIES)
    ];
  }

  /** The comment given with the transfer, or null when there was none. */
  transferComment(transfer: SpecialistTransferAudit): string | null {
    return transfer.comment?.trim() || null;
  }

  /** A warning of the check with the number the check sent for it; any other warning as the server wrote it. */
  warningText(warning: SpecialistTransferWarning, preview: SpecialistTransferPreview): string {
    switch (warning.code) {
      case 'TARGET_ALREADY_ASSIGNED': {
        const count = preview.targetAlreadyAssignedCompanyCount;
        return count > 0
          ? `Новый специалист уже привязан к ${countLabel(count, ['компании', 'компаниям', 'компаниям'])}: дублей не будет.`
          : warning.message;
      }
      case 'COMPANIES_WITHOUT_ACTIVE_ORDERS': {
        const count = preview.companiesWithoutActiveOrdersCount;
        return count > 0
          ? `У ${countLabel(count, ['компании', 'компаний', 'компаний'])} нет текущих заказов: туда только добавится новый специалист.`
          : warning.message;
      }
      case 'TARGET_MISSING_MANAGER_LINKS': {
        const count = preview.targetWorkerMissingManagerLinksCount;
        return count > 0
          ? `Новый специалист не в команде ${countLabel(count, ['менеджера', 'менеджеров', 'менеджеров'])} этих компаний.`
          : warning.message;
      }
      default:
        return warning.message;
    }
  }

  /** The toast after a transfer: what actually moved. Companies stay with the old specialist too. */
  resultSummary(result: SpecialistTransferResult): string {
    const parts = [countLabel(result.activeOrderCount, ORDERS), countLabel(result.unpublishedReviewCount, REVIEWS)];
    const tasks = result.badReviewTaskCount + result.reviewRecoveryTaskCount;
    if (tasks > 0) {
      parts.push(countLabel(tasks, TASKS));
    }
    if (result.companyLinksAdded > 0) {
      parts.push(`новый специалист добавлен в ${countLabel(result.companyLinksAdded, ['компанию', 'компании', 'компаний'])}`);
    }
    return parts.join(', ');
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

  sourceLabel(source: SpecialistTransferSource): string {
    const label = source.active ? source.label : `${source.label} — отключён`;
    return `${label} · компаний ${source.companyCount}, открытых заказов ${source.openOrderCount}`;
  }

  trackSource(_index: number, source: SpecialistTransferSource): number {
    return source.workerId;
  }

  private loadWorkers(): void {
    this.transfersApi.sources().subscribe({
      next: (sources) => this.sourceWorkers.set(sources ?? []),
      // The full specialist list below still works as the source list.
      error: () => this.sourceWorkers.set([])
    });
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

  private request(pair: TransferPair): SpecialistTransferRequest {
    return {
      fromWorkerId: pair.fromWorkerId,
      toWorkerId: pair.toWorkerId,
      companyIds: null,
      comment: this.comment().trim() || undefined
    };
  }

  private stat(value: number, forms: CountForms): TransferStat {
    return { value, word: countForm(value, forms) };
  }

  private isSelected(pair: TransferPair): boolean {
    return this.fromWorkerId() === pair.fromWorkerId && this.toWorkerId() === pair.toWorkerId;
  }

  private answersPair(preview: SpecialistTransferPreview, pair: TransferPair): boolean {
    return preview.fromWorker?.id === pair.fromWorkerId && preview.toWorker?.id === pair.toWorkerId;
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
    this.applyError.set(null);
  }
}
