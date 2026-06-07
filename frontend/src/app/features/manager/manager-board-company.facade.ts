import { signal } from '@angular/core';
import type {
  CommonBillingAccountResponse,
  CommonBillingApi
} from '../../core/common-billing.api';
import type {
  CompanyCardItem,
  CompanyEditPayload,
  CompanyUpdateRequest,
  ManagerBoardQuery,
  ManagerBoard,
  ManagerApi,
  ManagerOption
} from '../../core/manager.api';
import type { ToastService } from '../../shared/toast.service';
import {
  readSessionDraft,
  removeSessionDraft,
  writeSessionDraft
} from '../../shared/session-draft-storage';
import {
  managerCompanyEditDraft,
  managerCompanyFilialDeletedLabel,
  managerCompanyFilialDeleteConfirm,
  managerCompanyFilialDeleteKey,
  managerCompanyWorkerDeleteConfirm,
  managerCompanyWorkerDeleteKey
} from './manager-board.company.helpers';
import type {
  ManagerCompanyEditDraftChange,
  ManagerCompanyBillingDraftChange,
  ManagerCompanyFilialUpdateRequest
} from './manager-company-edit-modal.component';

export type ManagerCompanyBillingDraft = {
  name: string;
  enabled: boolean;
  autoRepeatOrders: boolean;
};

type ManagerBoardCompanyApi = Pick<
  ManagerApi,
  | 'getBoard'
  | 'getCompanyEdit'
  | 'getCompanySubcategories'
  | 'updateCompany'
  | 'deleteCompanyWorker'
  | 'deleteCompanyFilial'
  | 'updateCompanyFilial'
>;

type ManagerBoardCommonBillingApi = Pick<
  CommonBillingApi,
  'accounts' | 'createAccount' | 'updateAccount' | 'addCompany' | 'removeCompany'
>;

type ManagerBoardCompanyToast = Pick<ToastService, 'success' | 'error'>;

export type ManagerBoardCompanyFacadeDeps = {
  managerApi: ManagerBoardCompanyApi;
  commonBillingApi?: ManagerBoardCommonBillingApi;
  toastService: ManagerBoardCompanyToast;
  loadBoard: () => void;
  patchBoard?: (updater: (board: ManagerBoard) => ManagerBoard) => void;
  errorMessage: (err: unknown, fallback: string) => string;
};

export class ManagerBoardCompanyFacade {
  readonly editCompany = signal<CompanyEditPayload | null>(null);
  readonly editDraft = signal<CompanyUpdateRequest | null>(null);
  readonly editLoading = signal(false);
  readonly editSaving = signal(false);
  readonly editError = signal<string | null>(null);
  readonly editDeleteKey = signal<string | null>(null);
  readonly billingAccounts = signal<CommonBillingAccountResponse[]>([]);
  readonly billingSelectedAccountId = signal<number | null>(null);
  readonly billingDraft = signal<ManagerCompanyBillingDraft | null>(null);
  readonly billingLoading = signal(false);
  readonly billingError = signal<string | null>(null);
  readonly billingMutating = signal<string | null>(null);
  readonly billingCompanySearch = signal('');
  readonly billingCompanySearchResults = signal<CompanyCardItem[]>([]);
  readonly billingCompanySearchLoading = signal(false);
  readonly billingCompanySearchError = signal<string | null>(null);

  private billingCompanySearchTimer: number | null = null;
  private billingCompanySearchRun = 0;

  constructor(private readonly deps: ManagerBoardCompanyFacadeDeps) {}

  openCompanyEdit(company: CompanyCardItem): void {
    this.editLoading.set(true);
    this.editError.set(null);
    this.editDeleteKey.set(null);

    this.deps.managerApi.getCompanyEdit(company.id).subscribe({
      next: (payload) => {
        this.applyCompanyEditPayload(payload);
        this.editLoading.set(false);
        this.loadCompanyBilling(payload.id);
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось открыть редактирование компании');
        this.editLoading.set(false);
        this.editError.set(message);
        this.deps.toastService.error('Компания не открыта', message);
      }
    });
  }

  closeCompanyEdit(): void {
    if (this.editLoading() || this.editSaving() || this.editDeleteKey()) {
      return;
    }

    this.removeCompanyEditSessionDraft();
    this.clearBillingState();
    this.editCompany.set(null);
    this.editDraft.set(null);
    this.editError.set(null);
  }

  handleCompanyEditDraftChange(change: ManagerCompanyEditDraftChange): void {
    this.setCompanyEditField(change.field, change.value);
  }

  changeCompanyCategory(categoryId: number | null): void {
    this.setCompanyEditField('categoryId', categoryId);
    this.setCompanyEditField('subCategoryId', null);

    if (!categoryId) {
      return;
    }

    this.deps.managerApi.getCompanySubcategories(categoryId).subscribe({
      next: (subCategories) => {
        this.editCompany.update((company) => company ? { ...company, subCategories } : company);
        this.editDraft.update((draft) => draft ? { ...draft, subCategoryId: subCategories[0]?.id ?? null } : draft);
        this.writeCompanyEditSessionDraft();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось загрузить подкатегории');
        this.editError.set(message);
        this.deps.toastService.error('Подкатегории не загружены', message);
      }
    });
  }

  saveCompanyEdit(): void {
    const company = this.editCompany();
    const draft = this.editDraft();

    if (!company || !draft) {
      return;
    }

    this.editSaving.set(true);
    this.editError.set(null);

    if (this.saveBillingChangesBeforeCompany(company, draft)) {
      return;
    }

    this.persistCompanyEdit(company, draft);
  }

  private persistCompanyEdit(company: CompanyEditPayload, draft: CompanyUpdateRequest): void {
    this.deps.managerApi.updateCompany(company.id, draft).subscribe({
      next: (payload) => {
        this.applyCompanyCardPatch(payload);
        this.editSaving.set(false);
        this.closeCompanyEdit();
        this.deps.toastService.success('Компания сохранена', `Изменения по компании #${company.id} применены`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось сохранить компанию');
        this.editError.set(message);
        this.editSaving.set(false);
        this.deps.toastService.error('Компания не сохранена', message);
      }
    });
  }

  private saveBillingChangesBeforeCompany(company: CompanyEditPayload, companyDraft: CompanyUpdateRequest): boolean {
    const account = this.selectedBillingAccount();
    const draft = this.billingDraft();
    const api = this.deps.commonBillingApi;
    if (!account || !draft || !api || !draft.name.trim() || !this.billingDraftChanged(account, draft)) {
      return false;
    }

    this.billingMutating.set(`billing-save-${account.id}`);
    api.updateAccount(account.id, {
      name: draft.name.trim(),
      enabled: draft.enabled,
      autoRepeatOrders: draft.autoRepeatOrders,
      managerId: account.managerId ?? null,
      invoiceCompanyId: account.invoiceCompanyId ?? company.id,
      companyIds: this.enabledBillingCompanyIds(account)
    }).subscribe({
      next: (updated) => {
        this.replaceBillingAccount(updated);
        this.applyBillingDraft();
        this.billingMutating.set(null);
        this.persistCompanyEdit(company, companyDraft);
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось сохранить общий счет');
        this.billingMutating.set(null);
        this.editSaving.set(false);
        this.editError.set(message);
        this.deps.toastService.error('Общий счет не сохранен', message);
      }
    });
    return true;
  }

  deleteCompanyWorker(worker: ManagerOption): void {
    const company = this.editCompany();

    if (!company || this.editDeleteKey()) {
      return;
    }

    const confirmed = window.confirm(managerCompanyWorkerDeleteConfirm(worker));
    if (!confirmed) {
      return;
    }

    const key = managerCompanyWorkerDeleteKey(worker);
    this.editDeleteKey.set(key);
    this.editError.set(null);

    this.deps.managerApi.deleteCompanyWorker(company.id, worker.id).subscribe({
      next: (payload) => {
        this.editDeleteKey.set(null);
        this.applyCompanyEditPayload(payload);
        this.applyCompanyCardPatch(payload);
        this.deps.toastService.success('Специалист удален', worker.label);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось удалить специалиста');
        this.editDeleteKey.set(null);
        this.editError.set(message);
        this.deps.toastService.error('Специалист не удален', message);
      }
    });
  }

  deleteCompanyFilial(filialId: number, title: string): void {
    const company = this.editCompany();

    if (!company || this.editDeleteKey()) {
      return;
    }

    const confirmed = window.confirm(managerCompanyFilialDeleteConfirm(filialId, title));
    if (!confirmed) {
      return;
    }

    const key = managerCompanyFilialDeleteKey(filialId);
    this.editDeleteKey.set(key);
    this.editError.set(null);

    this.deps.managerApi.deleteCompanyFilial(company.id, filialId).subscribe({
      next: (payload) => {
        this.editDeleteKey.set(null);
        this.applyCompanyEditPayload(payload);
        this.applyCompanyCardPatch(payload);
        this.deps.toastService.success('Филиал удален', managerCompanyFilialDeletedLabel(filialId, title));
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось удалить филиал');
        this.editDeleteKey.set(null);
        this.editError.set(message);
        this.deps.toastService.error('Филиал не удален', message);
      }
    });
  }

  updateCompanyFilial(request: ManagerCompanyFilialUpdateRequest): void {
    const company = this.editCompany();

    if (!company || this.editDeleteKey()) {
      return;
    }

    const key = `filial-edit-${request.filialId}`;
    this.editDeleteKey.set(key);
    this.editError.set(null);

    const payload = {
      title: request.title,
      url: request.url,
      cityId: request.cityId
    };

    this.deps.managerApi.updateCompanyFilial(company.id, request.filialId, payload).subscribe({
      next: (updatedCompany) => {
        this.editDeleteKey.set(null);
        this.applyCompanyEditPayload(updatedCompany);
        this.applyCompanyCardPatch(updatedCompany);
        this.deps.toastService.success('Филиал сохранен', request.title || `#${request.filialId}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось сохранить филиал');
        this.editDeleteKey.set(null);
        this.editError.set(message);
        this.deps.toastService.error('Филиал не сохранен', message);
      }
    });
  }

  selectCompanyBillingAccount(accountId: number | null): void {
    this.billingSelectedAccountId.set(accountId);
    this.applyBillingDraft();
    this.clearBillingCompanySearch();
  }

  handleCompanyBillingDraftChange(change: ManagerCompanyBillingDraftChange): void {
    this.billingDraft.update((draft) => draft ? { ...draft, [change.field]: change.value } : draft);
    if (change.field === 'enabled' && change.value === true && this.billingSelectedAccountId() === null) {
      this.createCompanyBillingAccount();
    }
  }

  createCompanyBillingAccount(): void {
    const company = this.editCompany();
    const draft = this.billingDraft();
    const api = this.deps.commonBillingApi;
    if (!company || !draft || !api || this.billingMutating() || !draft.name.trim() || !draft.enabled) {
      return;
    }

    const reusableAccount = this.reusableBillingAccountForCompany(company, draft);
    if (reusableAccount) {
      this.billingSelectedAccountId.set(reusableAccount.id);
      this.applyBillingDraft();
      if (!this.billingAccountHasCompany(reusableAccount, company.id)) {
        this.connectCurrentCompanyToBillingAccount(reusableAccount.id);
        return;
      }
      this.deps.toastService.success('Использую существующую связь общего счета', reusableAccount.name);
      return;
    }

    this.billingMutating.set('billing-create');
    api.createAccount({
      name: draft.name.trim(),
      enabled: draft.enabled,
      autoRepeatOrders: draft.autoRepeatOrders,
      managerId: company.manager?.id ?? null,
      invoiceCompanyId: company.id,
      companyIds: [company.id]
    }).subscribe({
      next: (account) => {
        this.billingAccounts.update((accounts) => [account, ...accounts.filter((item) => item.id !== account.id)]);
        this.billingSelectedAccountId.set(account.id);
        this.applyBillingDraft();
        this.billingMutating.set(null);
        this.deps.toastService.success('Общий счет создан и компания подключена', company.title || `Компания #${company.id}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        this.billingDraft.update((current) => current ? { ...current, enabled: false } : current);
        this.failBillingMutation(err, 'Не удалось подключить общий счет');
      }
    });
  }

  saveCompanyBillingAccount(): void {
    const account = this.selectedBillingAccount();
    const draft = this.billingDraft();
    const api = this.deps.commonBillingApi;
    if (!account || !draft || !api || this.billingMutating() || !draft.name.trim() || !this.billingDraftChanged(account, draft)) {
      return;
    }

    this.billingMutating.set(`billing-save-${account.id}`);
    api.updateAccount(account.id, {
      name: draft.name.trim(),
      enabled: draft.enabled,
      autoRepeatOrders: draft.autoRepeatOrders,
      managerId: account.managerId ?? null,
      invoiceCompanyId: account.invoiceCompanyId ?? this.editCompany()?.id ?? null,
      companyIds: this.enabledBillingCompanyIds(account)
    }).subscribe({
      next: (updated) => {
        this.replaceBillingAccount(updated);
        this.applyBillingDraft();
        this.billingMutating.set(null);
        this.deps.toastService.success('Настройки общего счета сохранены');
        this.deps.loadBoard();
      },
      error: (err) => this.failBillingMutation(err, 'Не удалось сохранить общий счет')
    });
  }

  connectCurrentCompanyToBillingAccount(accountId: number): void {
    const company = this.editCompany();
    const api = this.deps.commonBillingApi;
    const account = this.billingAccounts().find((item) => item.id === accountId) ?? null;
    const draft = this.billingDraft();
    if (!company || !api || this.billingMutating() || !account || !this.billingCanChangeCompanies(account, draft)) {
      return;
    }

    this.billingMutating.set(`billing-connect-${accountId}`);
    api.addCompany(accountId, company.id).subscribe({
      next: (updated) => {
        this.replaceBillingAccount(updated);
        this.billingSelectedAccountId.set(updated.id);
        this.applyBillingDraft();
        this.billingMutating.set(null);
        this.deps.toastService.success('Компания подключена к общему счету', updated.name);
        this.deps.loadBoard();
      },
      error: (err) => this.failBillingMutation(err, 'Не удалось подключить компанию к общему счету')
    });
  }

  addCompanyToBillingAccount(company: CompanyCardItem): void {
    const account = this.selectedBillingAccount();
    const draft = this.billingDraft();
    const api = this.deps.commonBillingApi;
    if (
      !account
      || !company?.id
      || !api
      || this.billingMutating()
      || !this.billingCanChangeCompanies(account, draft)
      || this.billingCompanyAlreadyLinked(company.id)
    ) {
      return;
    }

    this.billingMutating.set(`billing-add-company-${company.id}`);
    api.addCompany(account.id, company.id).subscribe({
      next: (updated) => {
        this.replaceBillingAccount(updated);
        this.applyBillingDraft();
        this.clearBillingCompanySearch();
        this.billingMutating.set(null);
        this.deps.toastService.success('Компания добавлена в общий счет', company.title || `#${company.id}`);
        this.deps.loadBoard();
      },
      error: (err) => this.failBillingMutation(err, 'Не удалось добавить компанию в общий счет')
    });
  }

  removeCompanyFromBillingAccount(companyId: number): void {
    const account = this.selectedBillingAccount();
    const api = this.deps.commonBillingApi;
    if (!account || !api || this.billingMutating()) {
      return;
    }

    const confirmed = window.confirm('Исключить компанию из будущих общих счетов?');
    if (!confirmed) {
      return;
    }
    const detachCurrent = window.confirm(
      'Отключить неоплаченные заказы этой компании из текущего общего счета? Отмена оставит текущий счет как есть.'
    );

    this.billingMutating.set(`billing-remove-company-${companyId}`);
    api.removeCompany(account.id, companyId, detachCurrent).subscribe({
      next: (updated) => {
        this.replaceBillingAccount(updated);
        const currentCompanyId = this.editCompany()?.id;
        if (currentCompanyId === companyId && !this.billingAccountHasCompany(updated, companyId)) {
          this.billingSelectedAccountId.set(null);
        }
        this.applyBillingDraft();
        this.billingMutating.set(null);
        this.deps.toastService.success(
          detachCurrent ? 'Компания отключена от общего счета' : 'Компания исключена из будущих общих счетов'
        );
        this.deps.loadBoard();
      },
      error: (err) => this.failBillingMutation(err, 'Не удалось отключить компанию от общего счета')
    });
  }

  onBillingCompanySearchChange(value: string): void {
    this.billingCompanySearch.set(value);
    this.billingCompanySearchError.set(null);
    if (this.billingCompanySearchTimer != null) {
      window.clearTimeout(this.billingCompanySearchTimer);
    }

    const query = value.trim();
    if (query.length < 2) {
      this.billingCompanySearchResults.set([]);
      this.billingCompanySearchLoading.set(false);
      return;
    }

    this.billingCompanySearchLoading.set(true);
    const run = ++this.billingCompanySearchRun;
    this.billingCompanySearchTimer = window.setTimeout(() => {
      this.deps.managerApi.getBoard(this.billingSearchQuery(query)).subscribe({
        next: (board) => {
          if (run !== this.billingCompanySearchRun) {
            return;
          }
          const currentCompanyId = this.editCompany()?.id;
          this.billingCompanySearchResults.set((board.companies.content ?? [])
            .filter((company) => company.id !== currentCompanyId));
          this.billingCompanySearchLoading.set(false);
        },
        error: (err) => {
          if (run !== this.billingCompanySearchRun) {
            return;
          }
          this.billingCompanySearchResults.set([]);
          this.billingCompanySearchError.set(this.deps.errorMessage(err, 'Поиск компаний не сработал'));
          this.billingCompanySearchLoading.set(false);
        }
      });
    }, 260);
  }

  selectedBillingAccount(): CommonBillingAccountResponse | null {
    const id = this.billingSelectedAccountId();
    return this.billingAccounts().find((account) => account.id === id) ?? null;
  }

  currentCompanyBillingAccount(): CommonBillingAccountResponse | null {
    const companyId = this.editCompany()?.id;
    if (!companyId) {
      return null;
    }
    return this.billingAccounts().find((account) => this.billingAccountHasCompany(account, companyId)) ?? null;
  }

  billingCompanyAlreadyLinked(companyId: number): boolean {
    const account = this.selectedBillingAccount();
    return account ? this.billingAccountHasCompany(account, companyId) : false;
  }

  private setCompanyEditField<K extends keyof CompanyUpdateRequest>(field: K, value: CompanyUpdateRequest[K]): void {
    this.editDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
    this.writeCompanyEditSessionDraft();
  }

  private applyCompanyEditPayload(payload: CompanyEditPayload): void {
    const sourceDraft = managerCompanyEditDraft(payload);
    const storedDraft = readSessionDraft<CompanyUpdateRequest>(this.companyEditSessionDraftKey(payload.id));
    this.editCompany.set(payload);
    this.editDraft.set(storedDraft ?? sourceDraft);
    this.writeCompanyEditSessionDraft();
  }

  private loadCompanyBilling(companyId: number): void {
    const api = this.deps.commonBillingApi;
    if (!api) {
      return;
    }

    this.billingLoading.set(true);
    this.billingError.set(null);
    api.accounts().subscribe({
      next: (accounts) => {
        this.billingAccounts.set(accounts ?? []);
        const currentAccount = this.billingAccounts().find((account) => this.billingAccountHasCompany(account, companyId));
        this.billingSelectedAccountId.set(currentAccount?.id ?? null);
        this.applyBillingDraft();
        this.billingLoading.set(false);
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось загрузить общие счета компании');
        this.billingLoading.set(false);
        this.billingError.set(message);
        this.deps.toastService.error('Общие счета не загрузились', message);
      }
    });
  }

  private applyBillingDraft(): void {
    const account = this.selectedBillingAccount();
    const company = this.editCompany();
    if (account) {
      this.billingDraft.set({
        name: account.name ?? '',
        enabled: account.enabled,
        autoRepeatOrders: account.autoRepeatOrders
      });
      return;
    }

    this.billingDraft.set({
      name: company?.title ? `${company.title} - общий счет` : 'Новый общий счет',
      enabled: false,
      autoRepeatOrders: true
    });
  }

  private replaceBillingAccount(updated: CommonBillingAccountResponse): void {
    this.billingAccounts.update((accounts) => {
      const exists = accounts.some((account) => account.id === updated.id);
      return exists
        ? accounts.map((account) => account.id === updated.id ? updated : account)
        : [updated, ...accounts];
    });
  }

  private billingDraftChanged(account: CommonBillingAccountResponse, draft: ManagerCompanyBillingDraft): boolean {
    return draft.name.trim() !== (account.name ?? '').trim()
      || draft.enabled !== account.enabled
      || draft.autoRepeatOrders !== account.autoRepeatOrders;
  }

  private billingCanChangeCompanies(
    account: CommonBillingAccountResponse,
    draft: ManagerCompanyBillingDraft | null
  ): boolean {
    return account.enabled && (!draft || !this.billingDraftChanged(account, draft));
  }

  private enabledBillingCompanyIds(account: CommonBillingAccountResponse): number[] {
    return (account.companies ?? [])
      .filter((company) => company.enabled)
      .map((company) => company.companyId);
  }

  private billingAccountHasCompany(account: CommonBillingAccountResponse, companyId: number): boolean {
    return (account.companies ?? []).some((company) => company.companyId === companyId && company.enabled);
  }

  private reusableBillingAccountForCompany(
    company: CompanyEditPayload,
    draft: ManagerCompanyBillingDraft
  ): CommonBillingAccountResponse | null {
    const draftName = this.normalizedBillingName(draft.name);
    return this.billingAccounts().find((account) => {
      if (!account.enabled) {
        return false;
      }
      if (this.billingAccountHasCompany(account, company.id)) {
        return true;
      }
      return !!draftName && this.normalizedBillingName(account.name) === draftName;
    }) ?? null;
  }

  private normalizedBillingName(value: string | null | undefined): string {
    return (value ?? '').trim().replace(/\s+/g, ' ').toLocaleLowerCase('ru-RU');
  }

  private billingSearchQuery(query: string): ManagerBoardQuery {
    return {
      section: 'companies',
      status: 'Все',
      keyword: query,
      pageNumber: 0,
      pageSize: 8,
      sortDirection: 'desc'
    };
  }

  private failBillingMutation(err: unknown, fallback: string): void {
    const message = this.deps.errorMessage(err, fallback);
    this.billingMutating.set(null);
    this.billingError.set(message);
    this.deps.toastService.error(fallback, message);
  }

  private clearBillingState(): void {
    this.billingAccounts.set([]);
    this.billingSelectedAccountId.set(null);
    this.billingDraft.set(null);
    this.billingLoading.set(false);
    this.billingError.set(null);
    this.billingMutating.set(null);
    this.clearBillingCompanySearch();
  }

  private clearBillingCompanySearch(): void {
    this.billingCompanySearch.set('');
    this.billingCompanySearchResults.set([]);
    this.billingCompanySearchLoading.set(false);
    this.billingCompanySearchError.set(null);
    if (this.billingCompanySearchTimer != null) {
      window.clearTimeout(this.billingCompanySearchTimer);
      this.billingCompanySearchTimer = null;
    }
  }

  private writeCompanyEditSessionDraft(): void {
    const company = this.editCompany();
    const draft = this.editDraft();
    if (!company || !draft) {
      return;
    }

    const key = this.companyEditSessionDraftKey(company.id);
    if (!this.isCompanyEditDraftChanged(company, draft)) {
      removeSessionDraft(key);
      return;
    }

    writeSessionDraft(key, draft);
  }

  private removeCompanyEditSessionDraft(companyId = this.editCompany()?.id): void {
    if (companyId) {
      removeSessionDraft(this.companyEditSessionDraftKey(companyId));
    }
  }

  private companyEditSessionDraftKey(companyId: number): string {
    return `manager-company-edit:${companyId}`;
  }

  private isCompanyEditDraftChanged(company: CompanyEditPayload, draft: CompanyUpdateRequest): boolean {
    const source = managerCompanyEditDraft(company);
    return (Object.keys(source) as (keyof CompanyUpdateRequest)[]).some((field) => source[field] !== draft[field]);
  }

  private applyCompanyCardPatch(payload: CompanyEditPayload): void {
    this.deps.patchBoard?.((board) => ({
      ...board,
      companies: {
        ...board.companies,
        content: board.companies.content.map((company) => company.id === payload.id
          ? {
              ...company,
              title: payload.title,
              urlChat: payload.urlChat,
              telephone: payload.telephone,
              countFilials: payload.filials.length,
              urlFilial: payload.filials[0]?.url ?? company.urlFilial,
              status: payload.status?.label ?? company.status,
              manager: payload.manager?.label ?? company.manager,
              commentsCompany: payload.commentsCompany,
              city: payload.city,
              dateNewTry: payload.dateNewTry,
              groupId: payload.groupId,
              telegramGroupChatId: payload.telegramGroupChatId,
              maxGroupChatId: payload.maxGroupChatId
            }
          : company
        )
      }
    }));
  }
}
