import { inject } from '@angular/core';
import { manualPaymentResultNotice } from '@otziv/client-common/billing-payments';
import { ManagerManualPaymentsApi } from '../core/manager-manual-payments.api';
import { Injectable } from '@angular/core';
import { ModalController, ToastController } from '@ionic/angular';
import { firstValueFrom } from 'rxjs';
import {
  MobileManualCardPaymentDialogComponent,
  type MobileManualCardPaymentOutcome
} from './mobile-manual-card-payment-dialog.component';
import {
  mobileManualCardPaymentSelectionRecipient,
  mobileManualCardRecipientLabel,
} from './mobile-manual-card-payment';

@Injectable({ providedIn: 'root' })
export class MobileManualCardPaymentFlowService {
  private readonly managerManualPaymentsApi = inject(ManagerManualPaymentsApi);
  constructor(private readonly modalController: ModalController,
    private readonly toastController: ToastController
  ) {}

  async confirm(orderId: number, defaultReason = ''): Promise<MobileManualCardPaymentOutcome | null> {
    const context = await firstValueFrom(this.managerManualPaymentsApi.getManagerManualCardPaymentContext(orderId));
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
    const notice = manualPaymentResultNotice(result.data.result);
    const toast = await this.toastController.create({
      message: `${notice.title}. Получатель — ${mobileManualCardRecipientLabel(result.data.recipient)}. ${notice.detail}`,
      duration: 6500,
      position: 'top',
      color: notice.tone
    });
    await toast.present();
    return result.data;
  }
}
