import { Injectable } from '@angular/core';
import { ModalController, ToastController } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../core/api.service';
import {
  MobileManualCardPaymentDialogComponent,
  type MobileManualCardPaymentOutcome
} from './mobile-manual-card-payment-dialog.component';
import {
  mobileManualCardPaymentIsCompleted,
  mobileManualCardPaymentSelectionRecipient,
  mobileManualCardRecipientLabel,
} from './mobile-manual-card-payment';

@Injectable({ providedIn: 'root' })
export class MobileManualCardPaymentFlowService {
  constructor(
    private readonly api: ApiService,
    private readonly modalController: ModalController,
    private readonly toastController: ToastController
  ) {}

  async confirm(orderId: number, defaultReason = ''): Promise<MobileManualCardPaymentOutcome | null> {
    const context = await firstValueFrom(this.api.getManagerManualCardPaymentContext(orderId));
    if (!mobileManualCardPaymentSelectionRecipient(context)) {
      throw new Error(context.recipientSelectionFrozen
        ? 'Ранее выбранный получатель отсутствует в списке доступных получателей. Повтор оплаты заблокирован.'
        : 'Исходный получатель счёта отсутствует в списке доступных получателей. Оплата не изменена.');
    }

    const modal = await this.modalController.create({
      component: MobileManualCardPaymentDialogComponent,
      componentProps: { context, defaultReason },
      backdropDismiss: false,
      handle: true,
      initialBreakpoint: 0.9,
      breakpoints: [0, 0.9, 1]
    });
    await modal.present();
    const result = await modal.onWillDismiss<MobileManualCardPaymentOutcome>();
    if (result.role !== 'submitted' || !result.data) {
      return null;
    }
    const completed = mobileManualCardPaymentIsCompleted(result.data.result);
    const toast = await this.toastController.create({
      message: completed
        ? `Оплата отмечена. Получатель — ${mobileManualCardRecipientLabel(result.data.recipient)}`
        : result.data.result.message || 'Запрос отправлен владельцу. До подтверждения заказ не считается оплаченным.',
      duration: 3200,
      position: 'top',
      color: completed ? 'success' : 'warning'
    });
    await toast.present();
    return result.data;
  }
}
