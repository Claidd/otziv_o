import { Component, EventEmitter, Input, Output } from '@angular/core';
import { PaymentRecipientPickerComponent } from '../../shared/payment-recipient-picker.component';
import { CommonInvoiceGuideComponent } from '../admin/common-billing/common-invoice-guide.component';
import type { ManagerBoardOrderFacade } from './manager-board-order.facade';
import { ORDER_PAYMENT_GUIDE } from './order-payment-guide';

/**
 * Windows that belong to the payment of an ordinary order: the recipient choice for «Выдать номер карты»
 * of the «Способ оплаты» block and the manual «Счёт и оплата заказа». Every page that shows the order
 * editor renders this once.
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
        [autoHint]="facade.recipientPickerAutoHint()"
        [recipients]="facade.recipientPickerOptions()"
        [ownerCard]="facade.recipientPickerOwnerCard()"
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
        [section]="guideSection"
        heading="Счёт и оплата заказа"
        intro="Как заказ получает счёт, как отметить оплату и сменить получателя, что делать, если клиент не платит."
        (closed)="guideClosed.emit()"
      />
    }
  `
})
export class OrderPaymentRouteWindowsComponent {
  @Input({ required: true }) facade!: ManagerBoardOrderFacade;
  @Input() guideOpen = false;
  /** Section shown first: «Способ оплаты» opens its own part, the orders board opens the beginning. */
  @Input() guideSection: 'overview' | 'route' = 'route';
  @Output() readonly guideClosed = new EventEmitter<void>();

  readonly guide = ORDER_PAYMENT_GUIDE;
}
