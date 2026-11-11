import { Injectable } from '@angular/core';
import { ModalController } from '@ionic/angular';
import { MobilePaymentRecipientPickerComponent } from './mobile-payment-recipient-picker.component';
import type {
  MobilePaymentRecipientIssueOutcome,
  MobilePaymentRecipientPickerRequest,
  MobilePaymentRecipientPickerResult
} from './payment-recipient-picker';

@Injectable({ providedIn: 'root' })
export class MobilePaymentRecipientPickerFlowService {
  constructor(private readonly modalController: ModalController) {}

  /** Opens the «Выдать номер карты» window and resolves once it is closed. */
  async open(request: MobilePaymentRecipientPickerRequest): Promise<MobilePaymentRecipientPickerResult | null> {
    const { signal, ...componentProps } = request;
    if (signal?.aborted) {
      return null;
    }
    const modal = await this.modalController.create({
      component: MobilePaymentRecipientPickerComponent,
      componentProps,
      backdropDismiss: false,
      canDismiss: true,
      htmlAttributes: { 'aria-label': 'Выдать номер карты' }
    });
    // The page that opened the window was left: the window must not stay over another screen.
    const leave = () => { void modal.dismiss(null, 'cancel'); };
    signal?.addEventListener('abort', leave, { once: true });
    try {
      await modal.present();
      if (signal?.aborted) {
        leave();
      }
      const result = await modal.onDidDismiss<MobilePaymentRecipientIssueOutcome>();
      const outcome = result.role === 'issued' ? result.data : null;
      return outcome?.status === 'issued' || outcome?.status === 'accepted' ? outcome : null;
    } finally {
      signal?.removeEventListener('abort', leave);
    }
  }
}
