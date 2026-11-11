import { Component, EventEmitter, Input, Output } from '@angular/core';
import { PaymentRecipientPickerComponent } from '../../shared/payment-recipient-picker.component';
import { CommonInvoiceGuideComponent } from '../admin/common-billing/common-invoice-guide.component';
import type { ManagerBoardOrderFacade } from './manager-board-order.facade';
import { ORDER_PAYMENT_GUIDE } from './order-payment-guide';

/**
 * Windows that belong to the «Способ оплаты» block of the order editor: the recipient choice for
 * «Выдать номер карты» and the manual. Every page that shows the order editor renders this once.
 */
@Component({
  selector: 'app-order-payment-route-windows',
  imports: [PaymentRecipientPickerComponent, CommonInvoiceGuideComponent],
  template: `
    @if (facade.recipientPicker(); as picker) {
      <app-payment-recipient-picker
        [heading]="'Заказ №' + picker.orderId"
        [amountKopecks]="facade.recipientPickerAmount()"
        [currentRecipient]="picker.currentRecipient"
        [autoHint]="picker.autoHint"
        [recipients]="facade.recipientPickerOptions()"
        [loading]="facade.recipientPickerLoading()"
        [error]="facade.recipientPickerError()"
        [saving]="facade.paymentRouteChanging()"
        [submitError]="facade.recipientPickerSubmitError()"
        (chosen)="facade.submitRecipientChoice($event)"
        (closed)="facade.closeRecipientPicker()"
        (retry)="facade.loadRecipientPickerOptions()"
      />
    }
    @if (guideOpen) {
      <app-common-invoice-guide
        [sections]="guide"
        heading="Способ оплаты заказа"
        intro="Как сменить способ оплаты, выбрать получателя и что при этом происходит с резервами."
        (closed)="guideClosed.emit()"
      />
    }
  `
})
export class OrderPaymentRouteWindowsComponent {
  @Input({ required: true }) facade!: ManagerBoardOrderFacade;
  @Input() guideOpen = false;
  @Output() readonly guideClosed = new EventEmitter<void>();

  readonly guide = ORDER_PAYMENT_GUIDE;
}
