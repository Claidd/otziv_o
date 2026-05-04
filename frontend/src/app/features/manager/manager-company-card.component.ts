import { Component, EventEmitter, Input, Output } from '@angular/core';
import type { CompanyCardItem } from '../../core/manager.api';
import { CompanyNoteTriggerComponent } from '../../shared/company-note-trigger.component';
import {
  StatusAction,
  managerCompanyChatUrl,
  managerCompanyFilialUrl,
  managerHasMeaningfulNote,
  trackManagerAction
} from './manager-board.config';

@Component({
  selector: 'app-manager-company-card',
  imports: [CompanyNoteTriggerComponent],
  templateUrl: './manager-company-card.component.html',
  styleUrl: './manager-card.component.scss'
})
export class ManagerCompanyCardComponent {
  @Input() company!: CompanyCardItem;
  @Input() actions: StatusAction[] = [];
  @Input() copied: string | null = null;
  @Input() mutationKey: string | null = null;

  @Output() readonly noteSaved = new EventEmitter<string>();
  @Output() readonly phoneCopied = new EventEmitter<string | undefined>();
  @Output() readonly orderCreateOpened = new EventEmitter<void>();
  @Output() readonly statusUpdated = new EventEmitter<StatusAction>();
  @Output() readonly ordersOpened = new EventEmitter<void>();
  @Output() readonly editOpened = new EventEmitter<void>();

  companyChatUrl(): string {
    return managerCompanyChatUrl(this.company);
  }

  companyFilialUrl(): string {
    return managerCompanyFilialUrl(this.company);
  }

  hasMeaningfulNote(value?: string | null): boolean {
    return managerHasMeaningfulNote(value);
  }

  isMutating(action: StatusAction): boolean {
    return this.mutationKey === `company-${this.company.id}-${action.status}`;
  }

  trackAction(index: number, action: StatusAction): string {
    return trackManagerAction(index, action);
  }
}
