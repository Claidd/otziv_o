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
  type ManualCardPaymentConfirmationRequest,
  type ManualCardPaymentContext,
  type ManualCardPaymentRecipientOption
} from '../core/api.service';
import {
  mobileManualCardMoney,
  mobileManualCardPaymentSelectionRecipient,
  mobileManualCardPaymentSubmission,
  mobileManualCardRecipientKey,
  mobileManualCardRecipientLabel
} from './mobile-manual-card-payment';
import {
  mobileIsTaskRecipient,
  mobilePaymentRouteErrorMessage,
  mobileRetryablePaymentRouteError,
  mobileTaskAwareRecipientEffect
} from './manual-payment-routing';

export interface MobileManualCardPaymentCompleted {
  context: ManualCardPaymentContext;
  recipient: ManualCardPaymentRecipientOption;
}

@Component({
  selector: 'app-mobile-manual-card-payment-dialog',
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
  templateUrl: './mobile-manual-card-payment-dialog.component.html',
  styleUrl: './mobile-manual-card-payment-dialog.component.scss'
})
export class MobileManualCardPaymentDialogComponent implements OnInit {
  @Input({ required: true }) context!: ManualCardPaymentContext;
  @Input() defaultReason = '';

  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly selectedRecipientKey = signal('');
  readonly reason = signal('');
  readonly receiptUrl = signal('');
  readonly recipientKey = mobileManualCardRecipientKey;
  readonly recipientLabel = mobileManualCardRecipientLabel;
  readonly money = mobileManualCardMoney;
  readonly isTaskRecipient = mobileIsTaskRecipient;
  readonly recipientEffect = mobileTaskAwareRecipientEffect;

  readonly selectedRecipient = computed(() => {
    const key = this.selectedRecipientKey();
    return (this.context?.candidates ?? []).find(
      (candidate) => mobileManualCardRecipientKey(candidate) === key
    ) ?? null;
  });

  readonly recipientChanged = computed(() => {
    const selected = this.selectedRecipient();
    return Boolean(selected && this.context?.originalRecipient
      && mobileManualCardRecipientKey(selected) !== mobileManualCardRecipientKey(this.context.originalRecipient));
  });

  readonly selectedAnomalyWarning = computed(() => {
    const selected = this.selectedRecipient();
    if (!selected) {
      return '';
    }
    const projectedOverrun = selected.projectedOverrunKopecks ?? 0;
    if (projectedOverrun > 0) {
      return `Платёж всё равно будет учтён. Доступно для новых счетов станет 0 ₽, а превышение ${this.money(projectedOverrun)} попадёт в аномалии для сверки.`;
    }
    return selected.anomalyWarning?.trim() || this.context.anomalyWarning?.trim() || '';
  });

  constructor(
    private readonly api: ApiService,
    private readonly modalController: ModalController
  ) {}

  ngOnInit(): void {
    const defaultRecipient = mobileManualCardPaymentSelectionRecipient(this.context);
    if (!defaultRecipient) {
      this.error.set(this.context.recipientSelectionFrozen
        ? 'Ранее выбранный получатель отсутствует в списке доступных получателей. Повтор оплаты заблокирован.'
        : 'Исходный получатель счёта отсутствует в списке доступных получателей. Оплата не изменена.');
      return;
    }
    this.selectedRecipientKey.set(mobileManualCardRecipientKey(defaultRecipient));
    if (this.context.recipientSelectionFrozen) {
      const preparedReason = this.context.preparedReason ?? '';
      const preparedReceiptUrl = this.context.preparedReceiptUrl ?? '';
      this.reason.set(preparedReason);
      this.receiptUrl.set(preparedReceiptUrl);
      if (!preparedReason.trim() || preparedReason.length > 500 || preparedReceiptUrl.length > 1024) {
        this.error.set('Сохранённые данные повторной оплаты повреждены. Повтор оплаты заблокирован.');
      }
      return;
    }
    this.reason.set(this.defaultReason.trim().slice(0, 500));
  }

  close(): void {
    if (!this.saving()) {
      void this.modalController.dismiss(null, 'cancel');
    }
  }

  async submit(): Promise<void> {
    if (this.saving()) {
      return;
    }
    const submission = mobileManualCardPaymentSubmission(
      this.context,
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
      recipientKey: mobileManualCardRecipientKey(submission.recipient),
      recipientType: submission.recipient.recipientType,
      recipientProfileId: submission.recipient.recipientProfileId ?? null
    };
    this.saving.set(true);
    this.error.set(null);
    try {
      await firstValueFrom(this.api.confirmManagerManualCardPayment(this.context.orderId, request));
      const result: MobileManualCardPaymentCompleted = { context: this.context, recipient: submission.recipient };
      await this.modalController.dismiss(result, 'completed');
    } catch (error) {
      if (mobileRetryablePaymentRouteError(error)) {
        await this.reloadContextAfterConflict(error);
      } else {
        this.error.set(mobilePaymentRouteErrorMessage(error, 'Не удалось безопасно подтвердить перевод.'));
      }
    } finally {
      this.saving.set(false);
    }
  }

  private async reloadContextAfterConflict(sourceError: unknown): Promise<void> {
    const draftReason = this.reason();
    const draftReceiptUrl = this.receiptUrl();
    try {
      const context = await firstValueFrom(this.api.getManagerManualCardPaymentContext(this.context.orderId));
      const candidates = context.candidates ?? [];
      if (!candidates.length || candidates.some(candidate => !mobileManualCardRecipientKey(candidate))) {
        throw new Error('Сервер не вернул безопасный список получателей. Оплата не изменена.');
      }
      this.context = { ...context, candidates };
      const defaultRecipient = mobileManualCardPaymentSelectionRecipient(this.context);
      if (!defaultRecipient) {
        throw new Error('Актуальный исходный получатель отсутствует в списке. Оплата не изменена.');
      }
      this.selectedRecipientKey.set(mobileManualCardRecipientKey(defaultRecipient));
      if (context.recipientSelectionFrozen) {
        this.reason.set(context.preparedReason ?? '');
        this.receiptUrl.set(context.preparedReceiptUrl ?? '');
      } else {
        this.reason.set(draftReason);
        this.receiptUrl.set(draftReceiptUrl);
      }
      this.error.set(mobilePaymentRouteErrorMessage(
        sourceError,
        'Маршрут оплаты изменился. Проверьте выбор и подтвердите ещё раз.'
      ));
    } catch (reloadError) {
      this.error.set(mobilePaymentRouteErrorMessage(
        reloadError,
        'Маршрут оплаты изменился, но актуальный список не загрузился. Закройте окно и повторите.'
      ));
    }
  }

  private apiErrorMessage(error: unknown): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const payload = error.error;
      if (typeof payload === 'string' && payload.trim()) {
        return payload.trim();
      }
      if (payload && typeof payload === 'object') {
        for (const key of ['message', 'detail', 'error']) {
          const value = (payload as Record<string, unknown>)[key];
          if (typeof value === 'string' && value.trim()) {
            return value.trim();
          }
        }
      }
    }
    return error instanceof Error && error.message.trim()
      ? error.message.trim()
      : 'Не удалось безопасно подтвердить перевод.';
  }
}
