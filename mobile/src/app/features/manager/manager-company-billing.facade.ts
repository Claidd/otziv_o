import { computed, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { CommonBillingAccountResponse, CompanyEditPayload } from '../../core/api.service';
import type { ManagerCompanyBillingApi } from '../../core/manager-company-billing.api';
import type { EditorSession } from '../../core/editor-session';
import type { PageWriteTracker } from '../../core/page-write-tracker';
import type { MobileConfirmService } from '../../shared/mobile-confirm.service';

export type CompanyBillingDraft = { name: string; enabled: boolean; autoRepeatOrders: boolean };
type BillingDeps = {
  writes: PageWriteTracker;
  session: EditorSession;
  company: () => CompanyEditPayload | null;
  error: (message: string | null) => void;
  api: Pick<ManagerCompanyBillingApi, keyof ManagerCompanyBillingApi>;
  confirm: Pick<MobileConfirmService, 'confirm'>;
  reloadBoard: () => Promise<void>;
  errorMessage: (error: unknown, fallback: string) => string;
};

/** State and commands for the billing section of one company editor session. */
export class ManagerCompanyBillingFacade {
  constructor(private readonly deps: BillingDeps) {}

  private stopLoadingBeforeMutation(): void {
    this.deps.session.beginRead('billing');
    this.companyBillingLoading.set(false);
  }

  readonly companyBillingAccounts = signal<CommonBillingAccountResponse[]>([]);

  readonly companyBillingSelectedId = signal<number | null>(null);

  readonly companyBillingDraft = signal<CompanyBillingDraft | null>(null);

  readonly companyBillingLoading = signal(false);

  readonly companyBillingMutating = signal<string | null>(null);

  readonly selectedCompanyBillingAccount = computed(() => {
    const selectedId = this.companyBillingSelectedId();
    return this.companyBillingAccounts().find((account) => account.id === selectedId) ?? null;
  });

  setCompanyBillingDraftField<K extends keyof CompanyBillingDraft>(field: K, value: CompanyBillingDraft[K]): void {
    this.companyBillingDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  companyBillingStatusLabel(): string {
    const account = this.selectedCompanyBillingAccount();
    if (!account) {
      return 'Создайте связь, чтобы новые заказы попадали в общий счет';
    }
    return account.enabled ? 'Подключено' : 'Связь выключена';
  }

  canSaveCompanyBillingAccount(): boolean {
    const account = this.selectedCompanyBillingAccount();
    const draft = this.companyBillingDraft();
    return Boolean(
      account
      && draft
      && draft.name.trim()
      && (
        draft.name.trim() !== account.name
        || draft.enabled !== account.enabled
        || draft.autoRepeatOrders !== account.autoRepeatOrders
      )
    );
  }

  async createCompanyBillingAccount(): Promise<void> {
    const ticket = this.deps.session.capture();
    if (!ticket) { return; }
    const company = this.deps.company();
    const draft = this.companyBillingDraft();
    if (!company || company.id !== ticket.entityId || !draft || this.companyBillingMutating() || !draft.name.trim()) {
      return;
    }

    this.companyBillingMutating.set('create');
    this.stopLoadingBeforeMutation();
    this.deps.error(null);
    try {
      const account = await firstValueFrom(this.deps.writes.track(this.deps.api.createCommonBillingAccount({
        name: draft.name.trim(),
        enabled: draft.enabled,
        autoRepeatOrders: draft.autoRepeatOrders,
        managerId: company.manager?.id ?? null,
        invoiceCompanyId: company.id,
        companyIds: [company.id]
      })));
      if (!this.deps.session.accepts(ticket)) { return; }
      this.upsertCompanyBillingAccount(account);
      this.companyBillingSelectedId.set(account.id);
      this.companyBillingDraft.set(this.companyBillingDraftFromAccount(account, company));
      await this.deps.reloadBoard();
      if (!this.deps.session.accepts(ticket)) { return; }
    } catch (error) {
      if (!this.deps.session.accepts(ticket)) { return; }
      this.deps.error(this.deps.errorMessage(error, 'Не удалось создать общий счет.'));
    } finally {
      if (this.deps.session.accepts(ticket)) {
        this.companyBillingMutating.set(null);
      }
    }
  }

  async saveCompanyBillingAccount(): Promise<void> {
    const ticket = this.deps.session.capture();
    if (!ticket) { return; }
    const company = this.deps.company();
    const account = this.selectedCompanyBillingAccount();
    const draft = this.companyBillingDraft();
    if (!company || company.id !== ticket.entityId || !account || !draft || this.companyBillingMutating() || !draft.name.trim()) {
      return;
    }

    this.companyBillingMutating.set('save');
    this.stopLoadingBeforeMutation();
    this.deps.error(null);
    try {
      const accountCompanyIds = account.companies
        .filter((item) => item.enabled || item.companyId === company.id)
        .map((item) => item.companyId);
      const companyIds = Array.from(new Set([...accountCompanyIds, company.id]));
      const updated = await firstValueFrom(this.deps.writes.track(this.deps.api.updateCommonBillingAccount(account.id, {
        name: draft.name.trim(),
        enabled: draft.enabled,
        autoRepeatOrders: draft.autoRepeatOrders,
        managerId: account.managerId ?? company.manager?.id ?? null,
        invoiceCompanyId: account.invoiceCompanyId ?? company.id,
        companyIds
      })));
      if (!this.deps.session.accepts(ticket)) { return; }
      this.upsertCompanyBillingAccount(updated);
      this.companyBillingDraft.set(this.companyBillingDraftFromAccount(updated, company));
      await this.deps.reloadBoard();
      if (!this.deps.session.accepts(ticket)) { return; }
    } catch (error) {
      if (!this.deps.session.accepts(ticket)) { return; }
      this.deps.error(this.deps.errorMessage(error, 'Не удалось сохранить общий счет.'));
    } finally {
      if (this.deps.session.accepts(ticket)) {
        this.companyBillingMutating.set(null);
      }
    }
  }

  async removeCompanyFromBillingAccount(companyId: number): Promise<void> {
    const ticket = this.deps.session.capture();
    if (!ticket) { return; }
    const account = this.selectedCompanyBillingAccount();
    if (!account || this.companyBillingMutating()) {
      return;
    }

    // Reserve before confirmation: multiple taps must not create parallel prompts/commands.
    this.companyBillingMutating.set(`remove-${companyId}`);
    this.stopLoadingBeforeMutation();
    this.deps.error(null);
    try {
      const confirmed = await this.deps.confirm.confirm({
        title: 'Исключить компанию',
        message: 'Исключить компанию из будущих общих счетов?',
        confirmText: 'Исключить',
        danger: true
      });
      if (!this.deps.session.accepts(ticket)) { return; }
      if (!confirmed) {
        return;
      }

      const detachCurrent = await this.deps.confirm.confirm({
        title: 'Текущий счет',
        message: 'Отключить неоплаченные заказы этой компании из текущего общего счета?',
        confirmText: 'Отключить',
        cancelText: 'Оставить'
      });
      if (!this.deps.session.accepts(ticket)) { return; }

      const updated = await firstValueFrom(this.deps.writes.track(this.deps.api.removeCommonBillingCompany(account.id, companyId, detachCurrent)));
      if (!this.deps.session.accepts(ticket)) { return; }
      this.upsertCompanyBillingAccount(updated);
      const currentCompanyId = this.deps.company()?.id;
      if (currentCompanyId === companyId && !updated.companies.some((item) => item.companyId === companyId && item.enabled)) {
        this.companyBillingSelectedId.set(null);
        this.companyBillingDraft.set(this.companyBillingDraftFromCompany(this.deps.company()));
      } else {
        this.companyBillingDraft.set(this.companyBillingDraftFromAccount(updated, this.deps.company()));
      }
      await this.deps.reloadBoard();
      if (!this.deps.session.accepts(ticket)) { return; }
    } catch (error) {
      if (!this.deps.session.accepts(ticket)) { return; }
      this.deps.error(this.deps.errorMessage(error, 'Не удалось исключить компанию из общего счета.'));
    } finally {
      if (this.deps.session.accepts(ticket)) {
        this.companyBillingMutating.set(null);
      }
    }
  }

  async loadCompanyBilling(company: CompanyEditPayload): Promise<void> {
    const ticket = this.deps.session.beginRead('billing');
    if (!ticket || ticket.entityId !== company.id) { return; }
    this.companyBillingLoading.set(true);
    try {
      const accounts = await this.deps.session.read(ticket, this.deps.api.getCommonBillingAccountsForCompany(company.id));
      if (!accounts || !this.deps.session.accepts(ticket)) { return; }
      this.companyBillingAccounts.set(accounts);
      const selected = accounts.find((account) =>
        account.companies.some((item) => item.companyId === company.id && item.enabled)
      ) ?? accounts[0] ?? null;
      this.companyBillingSelectedId.set(selected?.id ?? null);
      this.companyBillingDraft.set(selected
        ? this.companyBillingDraftFromAccount(selected, company)
        : this.companyBillingDraftFromCompany(company)
      );
    } catch (error) {
      if (!this.deps.session.accepts(ticket)) { return; }
      this.companyBillingAccounts.set([]);
      this.companyBillingSelectedId.set(null);
      this.companyBillingDraft.set(this.companyBillingDraftFromCompany(company));
      this.deps.error(this.deps.errorMessage(error, 'Настройки общего счета не загрузились.'));
    } finally {
      if (this.deps.session.accepts(ticket)) { this.companyBillingLoading.set(false); }
    }
  }

  private companyBillingDraftFromAccount(
    account: CommonBillingAccountResponse,
    company?: CompanyEditPayload | null
  ): CompanyBillingDraft {
    return {
      name: account.name || this.defaultCompanyBillingName(company),
      enabled: account.enabled,
      autoRepeatOrders: account.autoRepeatOrders
    };
  }

  private companyBillingDraftFromCompany(company?: CompanyEditPayload | null): CompanyBillingDraft {
    return {
      name: this.defaultCompanyBillingName(company),
      enabled: true,
      autoRepeatOrders: true
    };
  }

  private defaultCompanyBillingName(company?: CompanyEditPayload | null): string {
    return company?.title ? `${company.title} - общий счет` : 'Новый общий счет';
  }

  private upsertCompanyBillingAccount(account: CommonBillingAccountResponse): void {
    this.companyBillingAccounts.update((accounts) => [
      account,
      ...accounts.filter((item) => item.id !== account.id)
    ]);
  }

}
