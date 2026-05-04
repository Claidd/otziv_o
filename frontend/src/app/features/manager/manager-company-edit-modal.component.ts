import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { CompanyEditPayload, CompanyUpdateRequest, ManagerOption } from '../../core/manager.api';
import {
  managerFilialEditUrl,
  managerOptionLabel,
  trackManagerOption
} from './manager-board.config';

export type ManagerCompanyEditDraftChange = {
  [K in keyof CompanyUpdateRequest]: {
    field: K;
    value: CompanyUpdateRequest[K];
  };
}[keyof CompanyUpdateRequest];

export type ManagerCompanyFilialDeleteRequest = {
  filialId: number;
  title: string;
};

@Component({
  selector: 'app-manager-company-edit-modal',
  imports: [FormsModule],
  templateUrl: './manager-company-edit-modal.component.html'
})
export class ManagerCompanyEditModalComponent {
  @Input() loading = false;
  @Input() company: CompanyEditPayload | null = null;
  @Input() draft: CompanyUpdateRequest | null = null;
  @Input() saving = false;
  @Input() deleteKey: string | null = null;
  @Input() error: string | null = null;

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly submitted = new EventEmitter<void>();
  @Output() readonly categoryChanged = new EventEmitter<number | null>();
  @Output() readonly workerDeleted = new EventEmitter<ManagerOption>();
  @Output() readonly filialDeleted = new EventEmitter<ManagerCompanyFilialDeleteRequest>();
  @Output() readonly draftChange = new EventEmitter<ManagerCompanyEditDraftChange>();

  setField<K extends keyof CompanyUpdateRequest>(field: K, value: CompanyUpdateRequest[K]): void {
    this.draftChange.emit({ field, value } as ManagerCompanyEditDraftChange);
  }

  filialEditUrl(filialId: number): string {
    return managerFilialEditUrl(filialId);
  }

  optionLabel(option: ManagerOption): string {
    return managerOptionLabel(option);
  }

  trackOption(index: number, option: ManagerOption): number {
    return trackManagerOption(index, option);
  }
}
