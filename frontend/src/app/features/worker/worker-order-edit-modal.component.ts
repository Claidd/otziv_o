import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { ManagerOption, OrderEditPayload, OrderUpdateRequest } from '../../core/manager.api';
import { trackWorkerOption } from './worker-board.config';

export type WorkerOrderEditDraftChange = {
  [K in keyof OrderUpdateRequest]: {
    field: K;
    value: OrderUpdateRequest[K];
  };
}[keyof OrderUpdateRequest];

@Component({
  selector: 'app-worker-order-edit-modal',
  imports: [FormsModule],
  templateUrl: '../manager/manager-order-edit-modal.component.html',
  styleUrl: '../manager/manager-order-mobile-modal.component.scss'
})
export class WorkerOrderEditModalComponent {
  @Input() loading = false;
  @Input() order: OrderEditPayload | null = null;
  @Input() draft: OrderUpdateRequest | null = null;
  @Input() saving = false;
  @Input() deleting = false;
  @Input() cancelingPayment = false;
  @Input() error: string | null = null;

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly submitted = new EventEmitter<void>();
  @Output() readonly deleted = new EventEmitter<void>();
  @Output() readonly paymentCanceled = new EventEmitter<void>();
  @Output() readonly draftChange = new EventEmitter<WorkerOrderEditDraftChange>();

  setField<K extends keyof OrderUpdateRequest>(field: K, value: OrderUpdateRequest[K]): void {
    this.draftChange.emit({ field, value } as WorkerOrderEditDraftChange);
  }

  optionLabel(option: ManagerOption): string {
    return option.label || `ID ${option.id}`;
  }

  trackOption(index: number, option: ManagerOption): number {
    return trackWorkerOption(index, option);
  }
}
