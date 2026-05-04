import { DecimalPipe } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type {
  CompanyOrderCreatePayload,
  CompanyOrderCreateRequest,
  ManagerOption,
  OrderProductOption
} from '../../core/manager.api';
import {
  managerOptionLabel,
  trackManagerOption,
  trackManagerProduct
} from './manager-board.config';

export type ManagerCreateOrderDraftChange = {
  [K in keyof CompanyOrderCreateRequest]: {
    field: K;
    value: CompanyOrderCreateRequest[K];
  };
}[keyof CompanyOrderCreateRequest];

@Component({
  selector: 'app-manager-order-create-modal',
  imports: [DecimalPipe, FormsModule],
  templateUrl: './manager-order-create-modal.component.html'
})
export class ManagerOrderCreateModalComponent {
  @Input() loading = false;
  @Input() payload: CompanyOrderCreatePayload | null = null;
  @Input() draft: CompanyOrderCreateRequest | null = null;
  @Input() saving = false;
  @Input() error: string | null = null;
  @Input() selectedProduct: OrderProductOption | null = null;
  @Input() total = 0;

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly submitted = new EventEmitter<void>();
  @Output() readonly draftChange = new EventEmitter<ManagerCreateOrderDraftChange>();

  modalTitle(): string {
    return this.payload?.companyTitle || (this.payload?.companyId ? `Компания #${this.payload.companyId}` : 'Компания');
  }

  setField<K extends keyof CompanyOrderCreateRequest>(field: K, value: CompanyOrderCreateRequest[K]): void {
    this.draftChange.emit({ field, value } as ManagerCreateOrderDraftChange);
  }

  optionLabel(option: ManagerOption): string {
    return managerOptionLabel(option);
  }

  trackOption(index: number, option: ManagerOption): number {
    return trackManagerOption(index, option);
  }

  trackProduct(index: number, product: { id: number }): number {
    return trackManagerProduct(index, product);
  }
}
