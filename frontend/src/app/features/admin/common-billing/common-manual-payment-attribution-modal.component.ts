import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, Input, OnInit, Output, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import type { CommonInvoiceDetailsResponse } from '../../../core/common-billing.api';
import {
  CommonManualPaymentAttributionApi,
  type CommonManualPaymentAttributionRequest,
  type CommonManualPaymentMode,
  type CommonManualPaymentOptions,
  type CommonManualPaymentRecipientCandidate
} from '../../../core/common-manual-payment-attribution.api';
import {
  isManualPaymentTaskRecipient,
  isRetryablePaymentRouteError,
  manualPaymentRecipientEffect,
  manualPaymentRouteErrorMessage
} from '../../../shared/manual-payment-routing';
import { commonManualPaymentDraftAfterRouteRefresh } from '../../../shared/common-manual-payment-route-refresh';

export interface CommonManualPaymentDraftRow {
  rowKey: string;
  recipientKey: string;
  amountRubles: string;
}

export function commonManualPaymentRublesToKopecks(raw: string): number | null {
  const clean = (raw ?? '').trim().replace(/\s+/g, '').replace(',', '.');
  if (!/^\d+(?:\.\d{1,2})?$/.test(clean)) {
    return null;
  }
  const [rubles, fraction = ''] = clean.split('.');
  const result = Number(rubles) * 100 + Number(fraction.padEnd(2, '0'));
  return Number.isSafeInteger(result) && result > 0 ? result : null;
}

export function commonManualPaymentTotalKopecks(rows: readonly CommonManualPaymentDraftRow[]): number | null {
  let total = 0;
  for (const row of rows) {
    const amount = commonManualPaymentRublesToKopecks(row.amountRubles);
    if (amount == null) {
      return null;
    }
    total += amount;
    if (!Number.isSafeInteger(total)) {
      return null;
    }
  }
  return total;
}

@Component({
  selector: 'app-common-manual-payment-attribution-modal',
  imports: [FormsModule],
  templateUrl: './common-manual-payment-attribution-modal.component.html',
  styleUrl: './common-manual-payment-attribution-modal.component.scss'
})
export class CommonManualPaymentAttributionModalComponent implements OnInit {
  private readonly api = inject(CommonManualPaymentAttributionApi);

  @Input({ required: true }) invoiceId!: number;
  @Input({ required: true }) mode: CommonManualPaymentMode = 'STANDARD';
  @Input() invoiceTitle = '';

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly completed = new EventEmitter<CommonInvoiceDetailsResponse>();

  readonly options = signal<CommonManualPaymentOptions | null>(null);
  readonly rows = signal<CommonManualPaymentDraftRow[]>([]);
  readonly reason = signal('');
  readonly receiptUrl = signal('');
  readonly finalAcknowledged = signal(false);
  readonly paymentReceived = signal(false);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');

  private idempotencyKey = this.newKey();
  private readonly effectiveAt = this.localDateTime(new Date());
  readonly isTaskRecipient = isManualPaymentTaskRecipient;
  readonly recipientEffect = manualPaymentRecipientEffect;

  readonly title = computed(() => this.mode === 'TBANK_FALLBACK'
    ? 'Фактическое получение оплаты общего счёта'
    : 'Ручное подтверждение общего счёта');
  readonly totalKopecks = computed(() => commonManualPaymentTotalKopecks(this.rows()));
  readonly differenceKopecks = computed(() => {
    const options = this.options();
    const total = this.totalKopecks();
    return options && total != null ? total - options.remainingKopecks : null;
  });
  readonly canSubmit = computed(() => Boolean(
    this.options()
      && this.options()!.remainingKopecks > 0
      && this.rows().length
      && this.totalKopecks() === this.options()!.remainingKopecks
      && this.reason().trim()
      && this.finalAcknowledged()
      && this.paymentReceived()
      && !this.loading()
      && !this.saving()
  ));

  ngOnInit(): void {
    void this.load();
  }

  async load(preserveDraft = false): Promise<void> {
    const draftReason = this.reason();
    const draftReceiptUrl = this.receiptUrl();
    this.loading.set(true);
    this.error.set('');
    try {
      const options = await firstValueFrom(this.api.options(this.invoiceId));
      this.options.set({
        ...options,
        candidates: options.candidates ?? [],
        history: options.history ?? []
      });
      const refreshedDraft = commonManualPaymentDraftAfterRouteRefresh(
        { ...options, candidates: options.candidates ?? [] },
        preserveDraft ? draftReason : '',
        preserveDraft ? draftReceiptUrl : '',
        () => this.newKey()
      );
      this.rows.set(refreshedDraft.rows);
      this.reason.set(refreshedDraft.reason);
      this.receiptUrl.set(refreshedDraft.receiptUrl);
      this.paymentReceived.set(refreshedDraft.paymentReceived);
      this.finalAcknowledged.set(refreshedDraft.finalAcknowledged);
    } catch (error) {
      this.options.set(null);
      this.error.set(this.errorMessage(error, 'Не удалось загрузить получателей общего счёта.'));
    } finally {
      this.loading.set(false);
    }
  }

  addRow(): void {
    const options = this.options();
    if (!options || this.rows().length >= Math.min(20, options.candidates.length)) {
      return;
    }
    const used = new Set(this.rows().map(row => row.recipientKey));
    const candidate = options.candidates.find(item => !used.has(item.key));
    if (!candidate) {
      return;
    }
    this.rows.update(rows => [...rows, {
      rowKey: this.newKey(),
      recipientKey: candidate.key,
      amountRubles: ''
    }]);
  }

  removeRow(index: number): void {
    if (this.rows().length <= 1) {
      return;
    }
    this.rows.update(rows => rows.filter((_, rowIndex) => rowIndex !== index));
  }

  updateRecipient(index: number, recipientKey: string): void {
    this.rows.update(rows => rows.map((row, rowIndex) =>
      rowIndex === index ? { ...row, recipientKey } : row));
  }

  updateAmount(index: number, amountRubles: string): void {
    this.rows.update(rows => rows.map((row, rowIndex) =>
      rowIndex === index ? { ...row, amountRubles } : row));
  }

  candidate(recipientKey: string): CommonManualPaymentRecipientCandidate | null {
    return this.options()?.candidates.find(item => item.key === recipientKey) ?? null;
  }

  projectedOverrun(row: CommonManualPaymentDraftRow): number {
    const candidate = this.candidate(row.recipientKey);
    const amount = commonManualPaymentRublesToKopecks(row.amountRubles);
    if (!candidate || candidate.recipientType === 'OWNER' || candidate.availableKopecks == null || amount == null) {
      return 0;
    }
    return Math.max(0, amount - candidate.availableKopecks);
  }

  close(): void {
    if (!this.saving()) {
      this.closed.emit();
    }
  }

  async submit(): Promise<void> {
    const options = this.options();
    const reason = this.reason().trim();
    const receiptUrl = this.receiptUrl().trim();
    if (!options || !this.canSubmit()) {
      this.error.set(this.validationMessage());
      return;
    }
    if (reason.length > 500) {
      this.error.set('Основание не должно превышать 500 символов.');
      return;
    }
    if (receiptUrl.length > 1024) {
      this.error.set('Ссылка на чек не должна превышать 1024 символа.');
      return;
    }
    const selected = this.rows().map(row => ({ row, candidate: this.candidate(row.recipientKey) }));
    if (selected.some(item => !item.candidate)) {
      this.error.set('Один из выбранных получателей больше недоступен. Обновите окно.');
      return;
    }
    if (new Set(selected.map(item => item.row.recipientKey)).size !== selected.length) {
      this.error.set('Один получатель не должен повторяться в нескольких строках.');
      return;
    }
    const request: CommonManualPaymentAttributionRequest = {
      idempotencyKey: this.idempotencyKey,
      finalAccountingAcknowledged: true,
      paymentReceived: true,
      effectiveAt: this.effectiveAt,
      reason,
      receiptUrl,
      attributions: selected.map(({ row, candidate }) => ({
        rowKey: row.rowKey,
        recipientKey: candidate!.key,
        recipientType: candidate!.recipientType ?? null,
        recipientProfileId: candidate!.recipientProfileId ?? null,
        amountKopecks: commonManualPaymentRublesToKopecks(row.amountRubles)!
      }))
    };

    this.saving.set(true);
    this.error.set('');
    try {
      const details = await firstValueFrom(this.api.confirm(this.invoiceId, this.mode, request));
      this.completed.emit(details);
    } catch (error) {
      if (isRetryablePaymentRouteError(error)) {
        this.idempotencyKey = this.newKey();
        this.rows.set([]);
        this.paymentReceived.set(false);
        this.finalAcknowledged.set(false);
        await this.load(true);
        if (this.options()) {
          this.error.set(manualPaymentRouteErrorMessage(error, 'Маршрут оплаты изменился. Проверьте распределение ещё раз.'));
        }
      } else {
        this.error.set(manualPaymentRouteErrorMessage(error, 'Не удалось подтвердить фактическое поступление.'));
      }
    } finally {
      this.saving.set(false);
    }
  }

  validationMessage(): string {
    const options = this.options();
    const total = this.totalKopecks();
    if (!options) {
      return 'Данные получателей не загружены.';
    }
    if (total == null) {
      return 'Укажите положительные суммы с точностью не более двух знаков.';
    }
    if (total !== options.remainingKopecks) {
      return 'Сумма по получателям должна точно совпадать с остатком общего счёта.';
    }
    if (!this.reason().trim()) {
      return 'Укажите короткое основание ручного подтверждения.';
    }
    if (!this.finalAcknowledged() || !this.paymentReceived()) {
      return 'Подтвердите фактическое поступление и финальное изменение расчётов.';
    }
    return '';
  }

  money(kopecks: number | null | undefined): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format((kopecks ?? 0) / 100)} ₽`;
  }

  historyDate(value: string): string {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ru-RU');
  }

  private localDateTime(date: Date): string {
    const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
    return local.toISOString().slice(0, 23);
  }

  private newKey(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 14)}`;
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const payload = error.error;
      const backend = typeof payload === 'string'
        ? payload
        : payload && typeof payload === 'object'
          ? ('message' in payload && typeof payload.message === 'string'
            ? payload.message
            : 'detail' in payload && typeof payload.detail === 'string'
              ? payload.detail
              : '')
          : '';
      if (backend.trim()) {
        return backend.trim();
      }
    }
    return error instanceof Error && error.message.trim() ? error.message.trim() : fallback;
  }
}
