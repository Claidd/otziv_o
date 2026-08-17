import { HttpErrorResponse } from '@angular/common/http';
import { Component, Input, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  IonButton,
  IonButtons,
  IonContent,
  IonFooter,
  IonHeader,
  IonSpinner,
  IonTitle,
  IonToolbar,
  ModalController
} from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  ApiService,
  type CommonInvoiceDetailsResponse,
  type CommonManualPaymentAttributionRequest,
  type CommonManualPaymentMode,
  type CommonManualPaymentOptions,
  type CommonManualPaymentRecipientCandidate
} from '../core/api.service';
import {
  mobileIsTaskRecipient,
  mobilePaymentRouteErrorMessage,
  mobileRetryablePaymentRouteError,
  mobileTaskAwareRecipientEffect
} from './manual-payment-routing';

interface CommonManualPaymentDraftRow {
  rowKey: string;
  recipientKey: string;
  amountRubles: string;
}

export interface MobileCommonManualPaymentCompleted {
  details: CommonInvoiceDetailsResponse;
}

export function mobileCommonPaymentRublesToKopecks(raw: string): number | null {
  const clean = (raw ?? '').trim().replace(/\s+/g, '').replace(',', '.');
  if (!/^\d+(?:\.\d{1,2})?$/.test(clean)) {
    return null;
  }
  const [rubles, fraction = ''] = clean.split('.');
  const result = Number(rubles) * 100 + Number(fraction.padEnd(2, '0'));
  return Number.isSafeInteger(result) && result > 0 ? result : null;
}

@Component({
  selector: 'app-mobile-common-manual-payment-dialog',
  imports: [
    FormsModule,
    IonButton,
    IonButtons,
    IonContent,
    IonFooter,
    IonHeader,
    IonSpinner,
    IonTitle,
    IonToolbar
  ],
  templateUrl: './mobile-common-manual-payment-dialog.component.html',
  styleUrl: './mobile-common-manual-payment-dialog.component.scss'
})
export class MobileCommonManualPaymentDialogComponent implements OnInit {
  @Input({ required: true }) invoiceId!: number;
  @Input() mode: CommonManualPaymentMode = 'STANDARD';

  readonly options = signal<CommonManualPaymentOptions | null>(null);
  readonly rows = signal<CommonManualPaymentDraftRow[]>([]);
  readonly reason = signal('');
  readonly receiptUrl = signal('');
  readonly paymentReceived = signal(false);
  readonly finalAcknowledged = signal(false);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');

  private idempotencyKey = this.newKey();
  private readonly effectiveAt = this.localDateTime(new Date());
  readonly isTaskRecipient = mobileIsTaskRecipient;
  readonly recipientEffect = mobileTaskAwareRecipientEffect;

  readonly title = computed(() => this.mode === 'TBANK_FALLBACK'
    ? 'Оплата переводом вместо T‑Bank'
    : 'Фактические получатели оплаты');
  readonly totalKopecks = computed(() => {
    let total = 0;
    for (const row of this.rows()) {
      const amount = mobileCommonPaymentRublesToKopecks(row.amountRubles);
      if (amount == null) {
        return null;
      }
      total += amount;
      if (!Number.isSafeInteger(total)) {
        return null;
      }
    }
    return total;
  });
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
      && this.paymentReceived()
      && this.finalAcknowledged()
      && !this.loading()
      && !this.saving()
  ));

  constructor(
    private readonly api: ApiService,
    private readonly modalController: ModalController
  ) {}

  ngOnInit(): void {
    void this.load();
  }

  async load(preserveDraft = false): Promise<void> {
    const draftReason = this.reason();
    const draftReceiptUrl = this.receiptUrl();
    this.loading.set(true);
    this.error.set('');
    try {
      const options = await firstValueFrom(this.api.getCommonManualPaymentOptions(this.invoiceId));
      const normalized = {
        ...options,
        candidates: options.candidates ?? [],
        history: options.history ?? []
      };
      const original = normalized.candidates.find(candidate => candidate.key === normalized.defaultRecipientKey);
      if (normalized.remainingKopecks > 0 && !original) {
        throw new Error('Исходный получатель отсутствует в безопасном списке. Оплата не изменена.');
      }
      this.options.set(normalized);
      this.rows.set(original && normalized.remainingKopecks > 0 ? [{
        rowKey: this.newKey(),
        recipientKey: original.key,
        amountRubles: this.rublesInput(normalized.remainingKopecks)
      }] : []);
      this.reason.set(preserveDraft ? draftReason : '');
      this.receiptUrl.set(preserveDraft ? draftReceiptUrl : '');
      this.paymentReceived.set(false);
      this.finalAcknowledged.set(false);
    } catch (error) {
      this.options.set(null);
      this.rows.set([]);
      this.error.set(this.errorMessage(error, 'Не удалось загрузить получателей общего счёта.'));
    } finally {
      this.loading.set(false);
    }
  }

  candidate(key: string): CommonManualPaymentRecipientCandidate | null {
    return this.options()?.candidates.find(candidate => candidate.key === key) ?? null;
  }

  addRow(): void {
    const options = this.options();
    if (!options || this.rows().length >= Math.min(20, options.candidates.length)) {
      return;
    }
    const used = new Set(this.rows().map(row => row.recipientKey));
    const candidate = options.candidates.find(value => !used.has(value.key));
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
    if (this.rows().length > 1) {
      this.rows.update(rows => rows.filter((_, rowIndex) => rowIndex !== index));
    }
  }

  updateRecipient(index: number, key: string): void {
    this.rows.update(rows => rows.map((row, rowIndex) =>
      rowIndex === index ? { ...row, recipientKey: key } : row));
  }

  updateAmount(index: number, value: string): void {
    this.rows.update(rows => rows.map((row, rowIndex) =>
      rowIndex === index ? { ...row, amountRubles: value } : row));
  }

  projectedOverrun(row: CommonManualPaymentDraftRow): number {
    const candidate = this.candidate(row.recipientKey);
    const amount = mobileCommonPaymentRublesToKopecks(row.amountRubles);
    if (!candidate || candidate.recipientType === 'OWNER' || candidate.availableKopecks == null || amount == null) {
      return 0;
    }
    return Math.max(0, amount - candidate.availableKopecks);
  }

  close(): void {
    if (!this.saving()) {
      void this.modalController.dismiss(null, 'cancel');
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
      this.error.set('Пояснение не должно превышать 500 символов.');
      return;
    }
    if (!this.validReceiptUrl(receiptUrl)) {
      this.error.set('Ссылка на чек должна быть безопасной ссылкой http или https.');
      return;
    }
    const selected = this.rows().map(row => ({ row, candidate: this.candidate(row.recipientKey) }));
    if (selected.some(value => !value.candidate)) {
      this.error.set('Один из получателей больше недоступен. Обновите окно.');
      return;
    }
    if (new Set(selected.map(value => value.row.recipientKey)).size !== selected.length) {
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
        recipientType: candidate!.recipientType,
        recipientProfileId: candidate!.recipientProfileId ?? null,
        amountKopecks: mobileCommonPaymentRublesToKopecks(row.amountRubles)!
      }))
    };

    this.saving.set(true);
    this.error.set('');
    try {
      const details = await firstValueFrom(
        this.api.confirmCommonManualPayment(this.invoiceId, this.mode, request)
      );
      const result: MobileCommonManualPaymentCompleted = { details };
      await this.modalController.dismiss(result, 'completed');
    } catch (error) {
      if (mobileRetryablePaymentRouteError(error)) {
        this.idempotencyKey = this.newKey();
        await this.load(true);
        if (this.options()) {
          this.error.set(mobilePaymentRouteErrorMessage(error, 'Маршрут оплаты изменился. Проверьте распределение ещё раз.'));
        }
      } else {
        this.error.set(mobilePaymentRouteErrorMessage(error, 'Не удалось учесть фактическое поступление.'));
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
      return 'Укажите короткое пояснение к ручной оплате.';
    }
    if (!this.paymentReceived() || !this.finalAcknowledged()) {
      return 'Подтвердите поступление и финальное изменение расчётов.';
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

  private rublesInput(kopecks: number): string {
    return (kopecks / 100).toFixed(2).replace('.', ',');
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

  private validReceiptUrl(value: string): boolean {
    if (!value) {
      return true;
    }
    if (value.length > 1024) {
      return false;
    }
    try {
      const url = new URL(value);
      return (url.protocol === 'http:' || url.protocol === 'https:') && Boolean(url.hostname) && !url.username && !url.password;
    } catch {
      return false;
    }
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
