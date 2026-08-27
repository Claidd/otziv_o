import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { ManagerOption, OrderEditPayload, OrderUpdateRequest, PaymentRouteChangeContext, PaymentRouteChangeTarget } from '../../core/manager.api';
import { managerOptionLabel, trackManagerOption } from './manager-board.config';

export type ManagerOrderEditDraftChange = {
  [K in keyof OrderUpdateRequest]: {
    field: K;
    value: OrderUpdateRequest[K];
  };
}[keyof OrderUpdateRequest];

@Component({
  selector: 'app-manager-order-edit-modal',
  imports: [FormsModule],
  templateUrl: './manager-order-edit-modal.component.html',
  styleUrl: './manager-order-mobile-modal.component.scss'
})
export class ManagerOrderEditModalComponent {
  @Input() loading = false;
  @Input() order: OrderEditPayload | null = null;
  @Input() draft: OrderUpdateRequest | null = null;
  @Input() saving = false;
  @Input() deleting = false;
  @Input() cancelingPayment = false;
  @Input() allowPaymentRouteChange = false;
  @Input() allowPaperInvoiceMode = false;
  @Input() paymentRouteContext: PaymentRouteChangeContext | null = null;
  @Input() paymentRouteContextLoading = false;
  @Input() paymentRouteChanging = false;
  @Input() error: string | null = null;

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly submitted = new EventEmitter<void>();
  @Output() readonly deleted = new EventEmitter<void>();
  @Output() readonly paymentCanceled = new EventEmitter<void>();
  @Output() readonly draftChange = new EventEmitter<ManagerOrderEditDraftChange>();
  @Output() readonly paymentRouteChangeOpened = new EventEmitter<void>();
  @Output() readonly paymentRouteChangeClosed = new EventEmitter<void>();
  @Output() readonly paymentRouteChanged = new EventEmitter<PaymentRouteChangeTarget>();
  @Output() readonly paperInvoiceIssued = new EventEmitter<void>();

  setField<K extends keyof OrderUpdateRequest>(field: K, value: OrderUpdateRequest[K]): void {
    this.draftChange.emit({ field, value } as ManagerOrderEditDraftChange);
  }

  optionLabel(option: ManagerOption): string {
    return managerOptionLabel(option);
  }

  trackOption(index: number, option: ManagerOption): number {
    return trackManagerOption(index, option);
  }
}
