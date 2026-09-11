import { signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { CompanyItem, CompanyEditPayload, CompanyUpdateRequest, CompanyFilialEditItem, CompanyFilialUpdateRequest, ManagerOption } from '../../core/api.service';
import { EditorSession } from '../../core/editor-session';
import type { PageWriteTracker } from '../../core/page-write-tracker';
import type { ManagerCompanyEditorApi } from '../../core/manager-company-editor.api';
import type { ManagerCompanyBillingApi } from '../../core/manager-company-billing.api';
import type { MobileConfirmService } from '../../shared/mobile-confirm.service';
import { ManagerCompanyBillingFacade } from './manager-company-billing.facade';

type CompanyFilialEditDraft = CompanyFilialUpdateRequest & { filialId: number };
type CompanyEditorDeps = {
  writes: PageWriteTracker;
  api: Pick<ManagerCompanyEditorApi, keyof ManagerCompanyEditorApi>;
  billingApi: Pick<ManagerCompanyBillingApi, keyof ManagerCompanyBillingApi>;
  confirm: Pick<MobileConfirmService, 'confirm'>;
  patchCompany: (company: CompanyEditPayload) => void;
  reloadBoard: () => Promise<void>;
  errorMessage: (error: unknown, fallback: string) => string;
  showSavedWarning: (company: CompanyEditPayload) => void;
};

/** One owner for the company modal, branch editor and its nested billing session. */
export class ManagerCompanyEditorFacade {
  private readonly session = new EditorSession();
  readonly billing: ManagerCompanyBillingFacade;
  constructor(private readonly deps: CompanyEditorDeps) {
    this.billing = new ManagerCompanyBillingFacade({
      session: this.session, company: () => this.companyEdit(), error: message => this.companyEditError.set(message),
      api: deps.billingApi, confirm: deps.confirm, reloadBoard: deps.reloadBoard, errorMessage: deps.errorMessage, writes: deps.writes
    });
  }

  readonly companyEditOpen = signal(false);

  readonly companyEdit = signal<CompanyEditPayload | null>(null);

  readonly companyEditDraft = signal<CompanyUpdateRequest | null>(null);

  readonly companyEditLoading = signal(false);

  readonly companyEditSaving = signal(false);

  readonly companyEditError = signal<string | null>(null);

  readonly companyEditDeleteKey = signal<string | null>(null);

  readonly companyFilialDraft = signal<CompanyFilialEditDraft | null>(null);

  openCompanyEdit(company: CompanyItem): void {
    if (this.companyEditSaving() || this.companyEditDeleteKey()) return;
    this.session.open(company.id);
    this.companyEditOpen.set(true);
    this.companyEdit.set(null);
    this.companyEditDraft.set(null);
    this.companyFilialDraft.set(null);
    this.billing.companyBillingAccounts.set([]);
    this.billing.companyBillingSelectedId.set(null);
    this.billing.companyBillingDraft.set(null);
    this.billing.companyBillingMutating.set(null);
    this.companyEditError.set(null);
    void this.loadCompanyEdit(company.id);
  }

  closeCompanyEdit(force = false): void {
    if (!force && (this.companyEditSaving() || this.companyEditDeleteKey())) {
      return;
    }

    this.session.close();
    this.companyEditSaving.set(false);
    this.companyEditDeleteKey.set(null);
    this.companyEditOpen.set(false);
    this.companyEdit.set(null);
    this.companyEditDraft.set(null);
    this.companyFilialDraft.set(null);
    this.billing.companyBillingAccounts.set([]);
    this.billing.companyBillingSelectedId.set(null);
    this.billing.companyBillingDraft.set(null);
    this.billing.companyBillingLoading.set(false);
    this.billing.companyBillingMutating.set(null);
    this.companyEditError.set(null);
    this.companyEditLoading.set(false);
  }

  setCompanyEditField<K extends keyof CompanyUpdateRequest>(field: K, value: CompanyUpdateRequest[K]): void {
    this.companyEditDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  changeCompanyEditCategory(categoryId: number | null): void {
    this.session.beginRead('subcategories');
    this.companyEditDraft.update((draft) => draft ? { ...draft, categoryId, subCategoryId: null } : draft);
    this.companyEdit.update((company) => company ? { ...company, subCategories: [] } : company);

    if (!categoryId) {
      return;
    }

    void this.loadCompanyEditSubCategories(categoryId);
  }

  canSaveCompanyEdit(): boolean {
    const draft = this.companyEditDraft();
    return Boolean(
      draft?.title.trim()
      && draft.telephone.trim()
      && draft.urlChat.trim()
      && draft.city.trim()
      && draft.statusId
    );
  }

  async saveCompanyEdit(): Promise<void> {
    const ticket = this.session.capture();
    if (!ticket) { return; }
    const company = this.companyEdit();
    const draft = this.companyEditDraft();
    if (!company || company.id !== ticket.entityId || !draft || !this.canSaveCompanyEdit() || this.companyEditSaving() || this.companyEditDeleteKey()) {
      this.companyEditError.set('Заполните обязательные поля компании.');
      return;
    }

    this.companyEditSaving.set(true);
    this.companyEditError.set(null);

    try {
      const updated = await firstValueFrom(this.deps.writes.track(this.deps.api.updateManagerCompany(ticket.entityId, this.normalizedCompanyEditDraft(draft))));
      if (!this.session.accepts(ticket)) { return; }
      if (updated.id !== ticket.entityId) { throw new Error('Ответ сервера относится к другой записи.'); }
      this.applyCompanyEditPayload(updated);
      this.deps.patchCompany(updated);
      await this.deps.reloadBoard();
      if (!this.session.accepts(ticket)) { return; }
      this.companyEditSaving.set(false);
      this.closeCompanyEdit();
      this.deps.showSavedWarning(updated);
    } catch (error) {
      if (!this.session.accepts(ticket)) { return; }
      this.companyEditError.set(this.deps.errorMessage(error, 'Компания не сохранена.'));
    } finally {
      if (this.session.accepts(ticket)) {
        this.companyEditSaving.set(false);
      }
    }
  }

  async deleteCompanyWorker(worker: ManagerOption): Promise<void> {
    const ticket = this.session.capture();
    if (!ticket) { return; }
    const company = this.companyEdit();
    if (!company || company.id !== ticket.entityId || this.companyEditSaving() || this.companyEditDeleteKey()) {
      return;
    }

    const key = `worker-${worker.id}`;
    this.companyEditDeleteKey.set(key);
    this.companyEditError.set(null);

    try {
      const confirmed = await this.deps.confirm.confirm({
        title: 'Убрать специалиста',
        message: `Убрать специалиста «${worker.label || `#${worker.id}`}» из компании?`,
        confirmText: 'Убрать',
        danger: true
      });
      if (!this.session.accepts(ticket)) { return; }
      if (!confirmed) {
        return;
      }
      const updated = await firstValueFrom(this.deps.writes.track(this.deps.api.deleteManagerCompanyWorker(ticket.entityId, worker.id)));
      if (!this.session.accepts(ticket)) { return; }
      if (updated.id !== ticket.entityId) { throw new Error('Ответ сервера относится к другой записи.'); }
      this.applyCompanyEditPayload(updated);
      this.deps.patchCompany(updated);
    } catch (error) {
      if (!this.session.accepts(ticket)) { return; }
      this.companyEditError.set(this.deps.errorMessage(error, 'Специалист не удален.'));
    } finally {
      if (this.session.accepts(ticket)) {
        this.companyEditDeleteKey.set(null);
      }
    }
  }

  async deleteCompanyFilial(filial: CompanyFilialEditItem): Promise<void> {
    const ticket = this.session.capture();
    if (!ticket) { return; }
    const company = this.companyEdit();
    if (!company || company.id !== ticket.entityId || this.companyEditSaving() || this.companyEditDeleteKey()) {
      return;
    }

    const key = `filial-${filial.id}`;
    this.companyEditDeleteKey.set(key);
    this.companyEditError.set(null);

    try {
      const readTicket = this.session.beginRead('filial-preview');
      if (!readTicket) { return; }
      const preview = await this.session.read(readTicket, this.deps.api.getManagerCompanyFilialDeletionPreview(ticket.entityId, filial.id));
      if (!preview) { return; }
      if (!this.session.accepts(ticket)) { return; }
      const label = filial.title || `#${filial.id}`;
      const message = preview.willArchive
        ? `Филиал "${label}" используется в ${preview.orderCount} заказах и будет отправлен в архив. Продолжить?`
        : `У филиала "${label}" нет заказов. Удалить его окончательно?`;
      const confirmed = await this.deps.confirm.confirm({
        title: preview.willArchive ? 'Архивировать филиал' : 'Удалить филиал',
        message,
        confirmText: preview.willArchive ? 'В архив' : 'Удалить',
        danger: true
      });
      if (!this.session.accepts(ticket)) { return; }
      if (!confirmed) {
        return;
      }
      const updated = await firstValueFrom(this.deps.writes.track(this.deps.api.deleteManagerCompanyFilial(ticket.entityId, filial.id)));
      if (!this.session.accepts(ticket)) { return; }
      if (updated.id !== ticket.entityId) { throw new Error('Ответ сервера относится к другой записи.'); }
      this.applyCompanyEditPayload(updated);
      this.deps.patchCompany(updated);
    } catch (error) {
      if (!this.session.accepts(ticket)) { return; }
      this.companyEditError.set(this.deps.errorMessage(error, 'Филиал не удален.'));
    } finally {
      if (this.session.accepts(ticket)) {
        this.companyEditDeleteKey.set(null);
      }
    }
  }

  async restoreCompanyFilial(filial: CompanyFilialEditItem): Promise<void> {
    const ticket = this.session.capture();
    if (!ticket) { return; }
    const company = this.companyEdit();
    if (!company || company.id !== ticket.entityId || this.companyEditSaving() || this.companyEditDeleteKey()) {
      return;
    }
    const key = `filial-restore-${filial.id}`;
    this.companyEditDeleteKey.set(key);
    this.companyEditError.set(null);
    try {
      const updated = await firstValueFrom(this.deps.writes.track(this.deps.api.restoreManagerCompanyFilial(ticket.entityId, filial.id)));
      if (!this.session.accepts(ticket)) { return; }
      if (updated.id !== ticket.entityId) { throw new Error('Ответ сервера относится к другой записи.'); }
      this.applyCompanyEditPayload(updated);
      this.deps.patchCompany(updated);
    } catch (error) {
      if (!this.session.accepts(ticket)) { return; }
      this.companyEditError.set(this.deps.errorMessage(error, 'Филиал не восстановлен.'));
    } finally {
      if (this.session.accepts(ticket)) {
        this.companyEditDeleteKey.set(null);
      }
    }
  }

  hasArchivedCompanyFilials(): boolean {
    return this.companyEdit()?.filials.some((filial) => filial.archived) ?? false;
  }

  startFilialEdit(filial: CompanyFilialEditItem): void {
    this.companyFilialDraft.set({
      filialId: filial.id,
      title: filial.title ?? '',
      url: filial.url ?? '',
      cityId: filial.cityId ?? null
    });
  }

  cancelFilialEdit(): void {
    this.companyFilialDraft.set(null);
  }

  setFilialDraftField<K extends keyof CompanyFilialUpdateRequest>(field: K, value: CompanyFilialUpdateRequest[K]): void {
    this.companyFilialDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  canSaveFilialEdit(): boolean {
    const draft = this.companyFilialDraft();
    return Boolean(
      draft
      && draft.title.trim()
      && draft.url.trim()
      && draft.cityId
      && !this.companyEditSaving()
      && !this.companyEditDeleteKey()
    );
  }

  async saveFilialEdit(): Promise<void> {
    const ticket = this.session.capture();
    if (!ticket) { return; }
    const company = this.companyEdit();
    const draft = this.companyFilialDraft();
    if (!company || company.id !== ticket.entityId || !draft || !this.canSaveFilialEdit()) {
      return;
    }

    const key = `filial-edit-${draft.filialId}`;
    this.companyEditDeleteKey.set(key);
    this.companyEditError.set(null);

    try {
      const updated = await firstValueFrom(this.deps.writes.track(this.deps.api.updateManagerCompanyFilial(ticket.entityId, draft.filialId, {
        title: draft.title.trim(),
        url: draft.url.trim(),
        cityId: draft.cityId
      })));
      if (!this.session.accepts(ticket)) { return; }
      if (updated.id !== ticket.entityId) { throw new Error('Ответ сервера относится к другой записи.'); }
      this.applyCompanyEditPayload(updated);
      this.deps.patchCompany(updated);
      this.companyFilialDraft.set(null);
    } catch (error) {
      if (!this.session.accepts(ticket)) { return; }
      this.companyEditError.set(this.deps.errorMessage(error, 'Филиал не сохранен.'));
    } finally {
      if (this.session.accepts(ticket)) {
        this.companyEditDeleteKey.set(null);
      }
    }
  }

  private async loadCompanyEdit(companyId: number): Promise<void> {
    const ticket = this.session.beginRead('payload');
    if (!ticket) { return; }
    this.companyEditLoading.set(true);
    this.companyEditError.set(null);
    try {
      const payload = await this.session.read(ticket, this.deps.api.getManagerCompanyEdit(companyId));
      if (!payload || !this.session.accepts(ticket)) { return; }
      if (payload.id !== companyId) { throw new Error('Ответ сервера относится к другой записи.'); }
      this.applyCompanyEditPayload(payload);
      await this.billing.loadCompanyBilling(payload);
    } catch (error) {
      if (this.session.accepts(ticket)) {
        this.companyEditError.set(this.deps.errorMessage(error, 'Не удалось загрузить редактор.'));
      }
    } finally {
      if (this.session.accepts(ticket)) { this.companyEditLoading.set(false); }
    }
  }

  private async loadCompanyEditSubCategories(categoryId: number): Promise<void> {
    const ticket = this.session.beginRead('subcategories');
    if (!ticket) { return; }
    try {
      const subCategories = await this.session.read(ticket, this.deps.api.getManagerCompanySubcategories(categoryId));
      if (!subCategories || !this.session.accepts(ticket) || this.companyEditDraft()?.categoryId !== categoryId) { return; }
      this.companyEdit.update((company) => company ? { ...company, subCategories } : company);
    } catch (error) {
      if (this.session.accepts(ticket) && this.companyEditDraft()?.categoryId === categoryId) {
        this.companyEditError.set(this.deps.errorMessage(error, 'Не удалось загрузить подкатегории.'));
      }
    }
  }

  private applyCompanyEditPayload(payload: CompanyEditPayload): void {
    this.companyEdit.set(payload);
    this.companyEditDraft.set(this.companyEditDraftFromPayload(payload));
  }

  private companyEditDraftFromPayload(payload: CompanyEditPayload): CompanyUpdateRequest {
    return {
      title: payload.title ?? '',
      urlChat: payload.urlChat ?? '',
      urlSite: payload.urlSite ?? '',
      telephone: payload.telephone ?? '',
      city: payload.city ?? '',
      email: payload.email ?? '',
      categoryId: payload.category?.id ?? null,
      subCategoryId: payload.subCategory?.id ?? null,
      statusId: payload.status?.id ?? null,
      managerId: payload.manager?.id ?? null,
      commentsCompany: payload.commentsCompany ?? '',
      active: payload.active,
      newWorkerId: null,
      newFilialCityId: null,
      newFilialTitle: '',
      newFilialUrl: ''
    };
  }

  private normalizedCompanyEditDraft(draft: CompanyUpdateRequest): CompanyUpdateRequest {
    return {
      ...draft,
      title: draft.title.trim(),
      urlChat: draft.urlChat.trim(),
      urlSite: draft.urlSite.trim(),
      telephone: draft.telephone.trim(),
      city: draft.city.trim(),
      email: draft.email.trim(),
      commentsCompany: draft.commentsCompany.trim(),
      newFilialTitle: draft.newFilialTitle.trim(),
      newFilialUrl: draft.newFilialUrl.trim()
    };
  }

}
