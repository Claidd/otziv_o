import { prepareManualTransfer, type ManualTransferLine } from '@otziv/client-common/billing-payments';
import { ChangeDetectionStrategy } from '@angular/core';
import { inject } from '@angular/core';
import { ManagerManualPaymentsApi } from '../core/manager-manual-payments.api';
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
} from '@ionic/angular';
import { firstValueFrom } from 'rxjs';
import {
  type ManagerManualCardPaymentResult,
  type ManualCardPaymentConfirmationRequest,
  type ManualCardPaymentContext,
  type ManualCardPaymentRecipientOption
} from '../core/api.service';
import {
  mobileManualCardMoney,
  mobileManualCardBankLinkNotice,
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

export interface MobileManualCardPaymentOutcome {
  context: ManualCardPaymentContext;
  recipient: ManualCardPaymentRecipientOption;
  result: ManagerManualCardPaymentResult;
}

@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
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
  private readonly managerManualPaymentsApi = inject(ManagerManualPaymentsApi);
  @Input({ required: true }) context!: ManualCardPaymentContext;
  @Input() defaultReason = '';

  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly additionalOrders = signal<ManualTransferLine[]>([]);
  additionalOrderId = '';
  transferReference = '';
  transferRubles = '';

  async addTransferOrder(): Promise<void> {
    const current = this.context;
    const orderId = Number(this.additionalOrderId);
    if (!current || this.saving()) return;
    if (!Number.isSafeInteger(orderId) || orderId <= 0 || orderId === current.orderId || this.additionalOrders().some(row => row.orderId === orderId)) {
      this.error.set('Введите номер другого заказа.'); return;
    }
    this.saving.set(true);
    try {
      const other = await firstValueFrom(this.managerManualPaymentsApi.getManagerManualCardPaymentContext(orderId));
      const profile = this.selectedRecipient()?.recipientProfileId;
      if (!profile || other.recipientSelectionFrozen || !other.candidates.some(c => c.recipientProfileId === profile)) {
        throw new Error('По этому заказу выбранный получатель недоступен или уже зарегистрирован перевод.');
      }
      this.additionalOrders.update(lines => [...lines, {orderId, amountKopecks: other.amountKopecks}]);
      this.additionalOrderId = '';
      this.error.set(null);
    } catch (failure) {
      this.error.set(failure instanceof Error ? failure.message : 'Не удалось проверить заказ.');
    } finally { this.saving.set(false); }
  }

  removeTransferOrder(orderId: number): void {
    this.additionalOrders.update(lines => lines.filter(row => row.orderId !== orderId));
  }

  readonly selectedRecipientKey = signal('');
  readonly reason = signal('');
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

  constructor(private readonly modalController: ModalController
  ) {}

  /**
   * The invoice of the order is the owner's bank link: a payment by the link is marked by the bank, and
   * the owner is offered only where the bank profile lets the owner take card transfers.
   */
  bankLinkNotice(): string {
    return mobileManualCardBankLinkNotice(this.context);
  }

  ngOnInit(): void {
    const defaultRecipient = mobileManualCardPaymentSelectionRecipient(this.context);
    if (this.context.recipientSelectionFrozen && !defaultRecipient) {
      this.error.set(this.context.recipientSelectionFrozen
        ? 'Ранее выбранный получатель отсутствует в списке доступных получателей. Повтор оплаты заблокирован.'
        : 'Исходный получатель счёта отсутствует в списке доступных получателей. Оплата не изменена.');
      return;
    }
    this.selectedRecipientKey.set(this.context.recipientSelectionFrozen && defaultRecipient ? mobileManualCardRecipientKey(defaultRecipient) : '');
    if (this.context.recipientSelectionFrozen) {
      const preparedReason = this.context.preparedReason ?? '';
      const preparedReceiptUrl = this.context.preparedReceiptUrl ?? '';
      this.reason.set(preparedReason);
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
      ''
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
      const context = this.context;
      let apiResult: ManagerManualCardPaymentResult;
      if (this.additionalOrders().length) {
        const group = prepareManualTransfer([{orderId: context.orderId, amountKopecks: context.amountKopecks}, ...this.additionalOrders()],
          this.transferReference, this.transferRubles, submission.recipient.recipientProfileId, submission.reason, submission.receiptUrl);
        const results = await firstValueFrom(this.managerManualPaymentsApi.recordTransfer(group));
        const pending = results.filter(item => item.status !== 'COMPLETED');
        if (pending.length) {
          this.error.set('Перевод зарегистрирован. Требуется сверка заказов ' + pending.map(item => '№' + item.orderId).join(', '));
          return;
        }
        const current = results.find(item => item.orderId === context.orderId);
        if (!current) throw new Error('Проверьте результат распределения перевода.');
        apiResult = current;
      } else {
      apiResult = await firstValueFrom(
        this.managerManualPaymentsApi.confirmManagerManualCardPayment(this.context.orderId, request)
      );
      }
      const outcome: MobileManualCardPaymentOutcome = {
        context: this.context,
        recipient: submission.recipient,
        result: apiResult
      };
      await this.modalController.dismiss(outcome, 'submitted');
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
    try {
      const context = await firstValueFrom(this.managerManualPaymentsApi.getManagerManualCardPaymentContext(this.context.orderId));
      const candidates = context.candidates ?? [];
      if (!candidates.length || candidates.some(candidate => !mobileManualCardRecipientKey(candidate))) {
        throw new Error('Сервер не вернул безопасный список получателей. Оплата не изменена.');
      }
      this.context = { ...context, candidates };
      const defaultRecipient = mobileManualCardPaymentSelectionRecipient(this.context);
      if (this.context.recipientSelectionFrozen && !defaultRecipient) {
        throw new Error('Актуальный исходный получатель отсутствует в списке. Оплата не изменена.');
      }
      this.selectedRecipientKey.set(this.context.recipientSelectionFrozen && defaultRecipient ? mobileManualCardRecipientKey(defaultRecipient) : '');
      if (context.recipientSelectionFrozen) {
        this.reason.set(context.preparedReason ?? '');
      } else {
        this.reason.set(draftReason);
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
}
