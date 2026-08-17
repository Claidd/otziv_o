import { Injectable } from '@angular/core';
import { ModalController } from '@ionic/angular/standalone';
import type { CommonInvoiceDetailsResponse, CommonManualPaymentMode } from '../core/api.service';
import {
  MobileCommonManualPaymentDialogComponent,
  type MobileCommonManualPaymentCompleted
} from './mobile-common-manual-payment-dialog.component';

@Injectable({ providedIn: 'root' })
export class MobileCommonManualPaymentFlowService {
  constructor(private readonly modalController: ModalController) {}

  async confirm(invoiceId: number, mode: CommonManualPaymentMode): Promise<CommonInvoiceDetailsResponse | null> {
    const modal = await this.modalController.create({
      component: MobileCommonManualPaymentDialogComponent,
      componentProps: { invoiceId, mode },
      backdropDismiss: false,
      canDismiss: true
    });
    await modal.present();
    const result = await modal.onDidDismiss<MobileCommonManualPaymentCompleted>();
    return result.role === 'completed' ? result.data?.details ?? null : null;
  }
}
