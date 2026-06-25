import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { CommonBillingAccountResponse } from '../../core/common-billing.api';
import type {
  CompanyCardItem,
  CompanyEditPayload,
  CompanyFilialEditItem,
  CompanyFilialUpdateRequest,
  CompanyUpdateRequest,
  ManagerOption
} from '../../core/manager.api';
import {
  managerChatBindingWarningForValues,
  managerOptionLabel,
  trackManagerOption
} from './manager-board.config';
import type { ManagerCompanyBillingDraft } from './manager-board-company.facade';

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

export type ManagerCompanyBillingDraftChange = {
  [K in keyof ManagerCompanyBillingDraft]: {
    field: K;
    value: ManagerCompanyBillingDraft[K];
  };
}[keyof ManagerCompanyBillingDraft];

type CompanyFilialEditDraft = CompanyFilialUpdateRequest & {
  filialId: number;
};

@Component({
  selector: 'app-manager-company-edit-modal',
  imports: [FormsModule],
  templateUrl: './manager-company-edit-modal.component.html',
  styleUrl: './manager-company-edit-modal.component.scss'
})
export class ManagerCompanyEditModalComponent {
  @Input() loading = false;
  @Input() company: CompanyEditPayload | null = null;
  @Input() draft: CompanyUpdateRequest | null = null;
  @Input() saving = false;
  @Input() deleteKey: string | null = null;
  @Input() error: string | null = null;
  @Input() billingAccounts: CommonBillingAccountResponse[] = [];
  @Input() billingSelectedAccountId: number | null = null;
  @Input() billingDraft: ManagerCompanyBillingDraft | null = null;
  @Input() billingLoading = false;
  @Input() billingError: string | null = null;
  @Input() billingMutating: string | null = null;
  @Input() billingCompanySearch = '';
  @Input() billingCompanySearchResults: CompanyCardItem[] = [];
  @Input() billingCompanySearchLoading = false;
  @Input() billingCompanySearchError: string | null = null;

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly submitted = new EventEmitter<void>();
  @Output() readonly categoryChanged = new EventEmitter<number | null>();
  @Output() readonly workerDeleted = new EventEmitter<ManagerOption>();
  @Output() readonly filialDeleted = new EventEmitter<ManagerCompanyFilialDeleteRequest>();
  @Output() readonly filialUpdated = new EventEmitter<ManagerCompanyFilialUpdateRequest>();
  @Output() readonly draftChange = new EventEmitter<ManagerCompanyEditDraftChange>();
  @Output() readonly billingAccountSelected = new EventEmitter<number | null>();
  @Output() readonly billingDraftChange = new EventEmitter<ManagerCompanyBillingDraftChange>();
  @Output() readonly billingAccountCreated = new EventEmitter<void>();
  @Output() readonly billingAccountSaved = new EventEmitter<void>();
  @Output() readonly billingCurrentCompanyConnected = new EventEmitter<number>();
  @Output() readonly billingCompanyRemoved = new EventEmitter<number>();
  @Output() readonly billingCompanySearchChanged = new EventEmitter<string>();
  @Output() readonly billingCompanyAdded = new EventEmitter<CompanyCardItem>();

  filialDraft: CompanyFilialEditDraft | null = null;

  setField<K extends keyof CompanyUpdateRequest>(field: K, value: CompanyUpdateRequest[K]): void {
    this.draftChange.emit({ field, value } as ManagerCompanyEditDraftChange);
  }

  setBillingField<K extends keyof ManagerCompanyBillingDraft>(
    field: K,
    value: ManagerCompanyBillingDraft[K]
  ): void {
    this.billingDraftChange.emit({ field, value } as ManagerCompanyBillingDraftChange);
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

  optionLabel(option: ManagerOption): string {
    return managerOptionLabel(option);
  }

  chatBindingWarning(): string {
    if (!this.company || !this.draft) {
      return '';
    }

    return managerChatBindingWarningForValues(
      this.draft.urlChat,
      this.company.groupId,
      this.company.telegramGroupChatId,
      this.company.maxGroupChatId
    );
  }

  selectedBillingAccount(): CommonBillingAccountResponse | null {
    const account = this.billingAccounts.find((item) => item.id === this.billingSelectedAccountId) ?? null;
    return account && this.billingAccountHasCurrentCompany(account) ? account : null;
  }

  currentCompanyBillingAccount(): CommonBillingAccountResponse | null {
    const companyId = this.company?.id;
    if (!companyId) {
      return null;
    }
    return this.billingAccounts.find((account) => this.billingAccountHasCompany(account, companyId)) ?? null;
  }

  billingAccountHasCurrentCompany(account: CommonBillingAccountResponse): boolean {
    const companyId = this.company?.id;
    return !!companyId && this.billingAccountHasCompany(account, companyId);
  }

  billingCompanyAlreadyLinked(companyId: number): boolean {
    const account = this.selectedBillingAccount();
    return account ? this.billingAccountHasCompany(account, companyId) : false;
  }

  billingStatusText(): string {
    const account = this.currentCompanyBillingAccount();
    if (!account) {
      return 'Не подключено';
    }
    return account.enabled ? 'Подключено' : 'Связь выключена';
  }

  billingAccountCompanyCount(account: CommonBillingAccountResponse): number {
    return (account.companies ?? []).filter((company) => company.enabled).length;
  }

  billingAccountCompanyLabel(account: CommonBillingAccountResponse): string {
    const count = this.billingAccountCompanyCount(account);
    return `${count} ${this.pluralRu(count, 'компания', 'компании', 'компаний')}`;
  }

  visibleBillingAccounts(): CommonBillingAccountResponse[] {
    return this.billingAccounts.filter((account) => this.billingAccountHasCurrentCompany(account));
  }

  visibleBillingAccountsLabel(): string {
    const count = this.visibleBillingAccounts().length;
    return `${count} ${this.pluralRu(count, 'связь', 'связи', 'связей')}`;
  }

  billingSettingsChanged(account: CommonBillingAccountResponse): boolean {
    const draft = this.billingDraft;
    if (!draft) {
      return false;
    }

    return draft.name.trim() !== (account.name ?? '').trim()
      || draft.enabled !== account.enabled
      || draft.autoRepeatOrders !== account.autoRepeatOrders;
  }

  billingAccountCanChangeCompanies(account: CommonBillingAccountResponse): boolean {
    return account.enabled && !this.billingSettingsChanged(account);
  }

  billingCompanyActionHint(account: CommonBillingAccountResponse, defaultText: string): string {
    if (!account.enabled) {
      return 'Сначала включите и сохраните общий счет';
    }
    if (this.billingSettingsChanged(account)) {
      return 'Сначала сохраните настройки связи';
    }
    return defaultText;
  }

  billingExplanation(): string {
    const account = this.selectedBillingAccount();
    const companyTitle = this.company?.title?.trim() || 'эта компания';
    if (!account) {
      if (this.billingMutating === 'billing-create') {
        return `Создаю общий счет и подключаю ${companyTitle}.`;
      }
      return this.billingDraft?.enabled
        ? `${companyTitle} подключается автоматически. Искать ее в списке не нужно.`
        : `Включите общий счет, чтобы сразу создать связь и подключить ${companyTitle}.`;
    }
    if (this.billingAccountHasCurrentCompany(account)) {
      if (this.billingSettingsChanged(account)) {
        return 'Есть несохраненные изменения общего счета. Нажмите нижнюю кнопку "Сохранить".';
      }
      return account.enabled
        ? 'Связь включена: заказы всех подключенных компаний собираются в один общий счет.'
        : 'Компания уже в связи, но общий счет выключен. Новые заказы не будут собираться, пока связь выключена.';
    }
    return `В карточке компании показываются только связи, где участвует ${companyTitle}.`;
  }

  trackBillingAccount(_index: number, account: CommonBillingAccountResponse): number {
    return account.id;
  }

  trackBillingCompany(_index: number, company: { companyId: number }): number {
    return company.companyId;
  }

  trackBillingCompanySearch(_index: number, company: CompanyCardItem): number {
    return company.id;
  }

  trackOption(index: number, option: ManagerOption): number {
    return trackManagerOption(index, option);
  }

  private billingAccountHasCompany(account: CommonBillingAccountResponse, companyId: number): boolean {
    return (account.companies ?? []).some((company) => company.companyId === companyId && company.enabled);
  }

  private pluralRu(value: number, one: string, few: string, many: string): string {
    const mod10 = Math.abs(value) % 10;
    const mod100 = Math.abs(value) % 100;
    if (mod10 === 1 && mod100 !== 11) {
      return one;
    }
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
      return few;
    }
    return many;
  }
}
