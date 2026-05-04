import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { ManagerOption, OrderEditPayload, OrderUpdateRequest } from '../../core/manager.api';
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
  templateUrl: './manager-order-edit-modal.component.html'
})
export class ManagerOrderEditModalComponent {
  @Input() loading = false;
  @Input() order: OrderEditPayload | null = null;
  @Input() draft: OrderUpdateRequest | null = null;
  @Input() saving = false;
  @Input() deleting = false;
  @Input() error: string | null = null;

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly submitted = new EventEmitter<void>();
  @Output() readonly deleted = new EventEmitter<void>();
  @Output() readonly draftChange = new EventEmitter<ManagerOrderEditDraftChange>();

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
