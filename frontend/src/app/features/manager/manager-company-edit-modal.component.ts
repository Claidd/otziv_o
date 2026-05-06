import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type {
  CompanyEditPayload,
  CompanyFilialEditItem,
  CompanyFilialUpdateRequest,
  CompanyUpdateRequest,
  ManagerOption
} from '../../core/manager.api';
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

export type ManagerCompanyFilialUpdateRequest = CompanyFilialUpdateRequest & {
  filialId: number;
};

type CompanyFilialEditDraft = CompanyFilialUpdateRequest & {
  filialId: number;
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
  @Output() readonly filialUpdated = new EventEmitter<ManagerCompanyFilialUpdateRequest>();
  @Output() readonly draftChange = new EventEmitter<ManagerCompanyEditDraftChange>();

  filialDraft: CompanyFilialEditDraft | null = null;

  setField<K extends keyof CompanyUpdateRequest>(field: K, value: CompanyUpdateRequest[K]): void {
    this.draftChange.emit({ field, value } as ManagerCompanyEditDraftChange);
  }

  startFilialEdit(filial: CompanyFilialEditItem): void {
    this.filialDraft = {
      filialId: filial.id,
      title: filial.title ?? '',
      url: filial.url ?? '',
      cityId: filial.cityId ?? null
    };
  }

  cancelFilialEdit(): void {
    this.filialDraft = null;
  }

  setFilialDraftField<K extends keyof CompanyFilialUpdateRequest>(
    field: K,
    value: CompanyFilialUpdateRequest[K]
  ): void {
    this.filialDraft = this.filialDraft
      ? { ...this.filialDraft, [field]: value }
      : null;
  }

  submitFilialEdit(): void {
    const draft = this.filialDraft;
    if (!draft || !this.canSaveFilialEdit()) {
      return;
    }

    this.filialUpdated.emit({
      filialId: draft.filialId,
      title: draft.title.trim(),
      url: draft.url.trim(),
      cityId: draft.cityId
    });
    this.cancelFilialEdit();
  }

  isFilialEditing(filialId: number): boolean {
    return this.filialDraft?.filialId === filialId;
  }

  canSaveFilialEdit(): boolean {
    const draft = this.filialDraft;
    return !!draft
      && !!draft.title.trim()
      && !!draft.url.trim()
      && draft.cityId != null
      && draft.cityId > 0
      && !this.saving
      && !this.deleteKey;
  }

  filialEditKey(filialId: number): string {
    return `filial-edit-${filialId}`;
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
