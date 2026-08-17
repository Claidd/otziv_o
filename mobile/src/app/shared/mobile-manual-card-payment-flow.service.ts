import { Injectable } from '@angular/core';
import { ModalController, ToastController } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../core/api.service';
import {
  MobileManualCardPaymentDialogComponent,
  type MobileManualCardPaymentCompleted
} from './mobile-manual-card-payment-dialog.component';
import {
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

  async confirm(orderId: number, defaultReason = ''): Promise<MobileManualCardPaymentCompleted | null> {
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
    const result = await modal.onWillDismiss<MobileManualCardPaymentCompleted>();
    if (result.role !== 'completed' || !result.data) {
      return null;
    }
    const toast = await this.toastController.create({
      message: `Оплата отмечена. Получатель — ${mobileManualCardRecipientLabel(result.data.recipient)}`,
      duration: 3200,
      position: 'top',
      color: 'success'
    });
    await toast.present();
    return result.data;
  }
}
