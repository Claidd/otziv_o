import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, Input, OnInit, Output, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import {
  ManagerApi,
  type ManualCardPaymentConfirmationRequest,
  type ManualCardPaymentContext,
  type ManualCardPaymentRecipientOption,
  type OrderCardItem
} from '../../core/manager.api';
import {
  isManualPaymentTaskRecipient,
  isRetryablePaymentRouteError,
  manualPaymentRecipientEffect,
  manualPaymentRecipientKey as taskAwareRecipientKey,
  manualPaymentRecipientLabel,
  manualPaymentRouteErrorMessage
} from '../../shared/manual-payment-routing';

export interface ManagerManualCardPaymentCompleted {
  context: ManualCardPaymentContext;
  recipient: ManualCardPaymentRecipientOption;
}

export function manualCardRecipientKey(candidate: ManualCardPaymentRecipientOption): string {
  return taskAwareRecipientKey(candidate);
}

function matchingManualCardRecipient(
  candidates: readonly ManualCardPaymentRecipientOption[],
  expectedRecipient: ManualCardPaymentRecipientOption | null | undefined
): ManualCardPaymentRecipientOption | null {
  if (!expectedRecipient) {
    return null;
  }
  const expectedKey = manualCardRecipientKey(expectedRecipient);
  if (!expectedKey) {
    return null;
  }
  return candidates.find((candidate) => manualCardRecipientKey(candidate) === expectedKey) ?? null;
}

export function originalManualCardRecipient(
  candidates: readonly ManualCardPaymentRecipientOption[],
  originalRecipient: ManualCardPaymentRecipientOption
): ManualCardPaymentRecipientOption | null {
  return matchingManualCardRecipient(candidates, originalRecipient);
}

export function manualCardPaymentSelectionRecipient(
  context: ManualCardPaymentContext
): ManualCardPaymentRecipientOption | null {
  return context.recipientSelectionFrozen
    ? matchingManualCardRecipient(context.candidates ?? [], context.preparedRecipient)
    : originalManualCardRecipient(context.candidates ?? [], context.originalRecipient);
}

export function manualCardPaymentSubmission(
  context: ManualCardPaymentContext,
  selectedRecipient: ManualCardPaymentRecipientOption | null,
  reasonValue: string,
  receiptUrlValue: string
): { recipient: ManualCardPaymentRecipientOption; reason: string; receiptUrl: string | null } | null {
  const recipient = context.recipientSelectionFrozen
    ? manualCardPaymentSelectionRecipient(context)
    : selectedRecipient;
  if (!recipient) {
    return null;
  }
  return {
    recipient,
    reason: context.recipientSelectionFrozen ? (context.preparedReason ?? '') : reasonValue.trim(),
    receiptUrl: context.recipientSelectionFrozen ? context.preparedReceiptUrl : (receiptUrlValue.trim() || null)
  };
}

@Component({
  selector: 'app-manager-manual-card-payment-modal',
  imports: [FormsModule],
  templateUrl: './manager-manual-card-payment-modal.component.html',
  styleUrl: './manager-manual-card-payment-modal.component.scss'
})
export class ManagerManualCardPaymentModalComponent implements OnInit {
  private readonly managerApi = inject(ManagerApi);

  @Input({ required: true }) order!: OrderCardItem;

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly completed = new EventEmitter<ManagerManualCardPaymentCompleted>();
  readonly recipientKey = manualCardRecipientKey;
  readonly recipientEffect = manualPaymentRecipientEffect;
  readonly isTaskRecipient = isManualPaymentTaskRecipient;

  readonly context = signal<ManualCardPaymentContext | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly selectedRecipientKey = signal('');
  readonly reason = signal('');
  readonly receiptUrl = signal('');

  readonly selectedRecipient = computed(() => {
    const context = this.context();
    const key = this.selectedRecipientKey();
    return context?.candidates.find((candidate) => manualCardRecipientKey(candidate) === key) ?? null;
  });

  readonly recipientChanged = computed(() => {
    const context = this.context();
    const selected = this.selectedRecipient();
    return Boolean(context && selected
      && manualCardRecipientKey(context.originalRecipient) !== manualCardRecipientKey(selected));
  });

  readonly selectedAnomalyWarning = computed(() => {
    const context = this.context();
    const selected = this.selectedRecipient();
    if (!context || !selected) {
      return '';
    }
    if ((selected.projectedOverrunKopecks ?? 0) > 0) {
      return `Платёж всё равно будет учтён. Доступно для новых счетов станет 0 ₽, а превышение ${this.money(selected.projectedOverrunKopecks)} попадёт в аномалии для сверки.`;
    }
    if (selected.anomalyWarning?.trim()) {
      return selected.anomalyWarning.trim();
    }
    if (context.anomalyWarning?.trim()) {
      return context.anomalyWarning.trim();
    }
    if (selected.recipientType !== 'OWNER'
      && selected.availableKopecks != null
      && context.amountKopecks > selected.availableKopecks) {
      return 'Сумма превышает доступный остаток выбранного получателя. После подтверждения система сохранит превышение как аномалию для сверки.';
    }
    return '';
  });

  ngOnInit(): void {
    void this.loadContext();
  }

  async loadContext(preserveDraft = false): Promise<void> {
    const draftReason = this.reason();
    const draftReceiptUrl = this.receiptUrl();
    this.loading.set(true);
    this.error.set(null);
    try {
      const context = await firstValueFrom(this.managerApi.getManualCardPaymentContext(this.order.id));
      const candidates = context.candidates ?? [];
      if (!candidates.length) {
        throw new Error('Сервер не вернул доступных получателей оплаты.');
      }
      if (candidates.some(candidate => !manualCardRecipientKey(candidate))) {
        throw new Error('Сервер вернул получателя без безопасного ключа маршрута. Оплата не изменена.');
      }
      const normalizedContext = { ...context, candidates };
      const defaultRecipient = manualCardPaymentSelectionRecipient(normalizedContext);
      if (!defaultRecipient) {
        throw new Error(context.recipientSelectionFrozen
          ? 'Ранее выбранный получатель отсутствует в списке доступных получателей. Повтор оплаты заблокирован.'
          : 'Исходный получатель счёта отсутствует в списке доступных получателей. Оплата не изменена.');
      }
      if (context.recipientSelectionFrozen) {
        const preparedReason = context.preparedReason ?? '';
        const preparedReceiptUrl = context.preparedReceiptUrl ?? '';
        if (!preparedReason.trim() || preparedReason.length > 500 || preparedReceiptUrl.length > 1024) {
          throw new Error('Сохранённые данные повторной оплаты повреждены. Повтор оплаты заблокирован.');
        }
        this.reason.set(preparedReason);
        this.receiptUrl.set(preparedReceiptUrl);
      } else {
        this.reason.set(preserveDraft ? draftReason : '');
        this.receiptUrl.set(preserveDraft ? draftReceiptUrl : '');
      }
      this.context.set(normalizedContext);
      this.selectedRecipientKey.set(manualCardRecipientKey(defaultRecipient));
    } catch (error) {
      this.context.set(null);
      this.error.set(this.errorMessage(error, 'Не удалось загрузить данные ручной оплаты.'));
    } finally {
      this.loading.set(false);
    }
  }

  close(): void {
    if (!this.saving()) {
      this.closed.emit();
    }
  }

  async submit(): Promise<void> {
    const context = this.context();
    if (!context || this.saving()) {
      return;
    }
    const submission = manualCardPaymentSubmission(
      context,
      this.selectedRecipient(),
      this.reason(),
      this.receiptUrl()
    );
    if (!submission) {
      this.error.set('Зафиксированный получатель недоступен. Оплата не изменена.');
      return;
    }
    if (!submission.reason.trim()) {
      this.error.set('Укажите короткую причину ручной оплаты.');
      return;
    }
    if (submission.reason.length > 500) {
      this.error.set('Причина не должна превышать 500 символов.');
      return;
    }
    if ((submission.receiptUrl?.length ?? 0) > 1024) {
      this.error.set('Ссылка на чек не должна превышать 1024 символа.');
      return;
    }

    const request: ManualCardPaymentConfirmationRequest = {
      reason: submission.reason,
      receiptUrl: submission.receiptUrl,
      recipientKey: manualCardRecipientKey(submission.recipient),
      recipientType: submission.recipient.recipientType,
      recipientProfileId: submission.recipient.recipientProfileId ?? null
    };
    this.saving.set(true);
    this.error.set(null);
    try {
      await firstValueFrom(this.managerApi.confirmManualCardPayment(this.order.id, request));
      this.completed.emit({ context, recipient: submission.recipient });
    } catch (error) {
      if (isRetryablePaymentRouteError(error)) {
        await this.loadContext(true);
        if (this.context()) {
          this.error.set(manualPaymentRouteErrorMessage(error, 'Маршрут оплаты изменился. Проверьте выбор ещё раз.'));
        }
      } else {
        this.error.set(manualPaymentRouteErrorMessage(error, 'Не удалось безопасно подтвердить перевод.'));
      }
    } finally {
      this.saving.set(false);
    }
  }

  recipientLabel(candidate: ManualCardPaymentRecipientOption): string {
    return manualPaymentRecipientLabel(candidate);
  }

  money(kopecks: number | null | undefined): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format((kopecks ?? 0) / 100)} ₽`;
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const payload = error.error;
      const backendMessage = typeof payload === 'string'
        ? payload
        : payload && typeof payload === 'object'
          ? ('message' in payload && typeof payload.message === 'string'
            ? payload.message
            : 'detail' in payload && typeof payload.detail === 'string'
              ? payload.detail
              : '')
          : '';
      if (backendMessage.trim()) {
        return backendMessage.trim();
      }
    }
    return error instanceof Error && error.message.trim() ? error.message.trim() : fallback;
  }
}
