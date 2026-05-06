import { signal } from '@angular/core';
import type {
  CompanyCardItem,
  CompanyEditPayload,
  CompanyUpdateRequest,
  ManagerBoard,
  ManagerApi,
  ManagerOption
} from '../../core/manager.api';
import type { ToastService } from '../../shared/toast.service';
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
  ManagerCompanyFilialUpdateRequest
} from './manager-company-edit-modal.component';

type ManagerBoardCompanyApi = Pick<
  ManagerApi,
  | 'getCompanyEdit'
  | 'getCompanySubcategories'
  | 'updateCompany'
  | 'deleteCompanyWorker'
  | 'deleteCompanyFilial'
  | 'updateCompanyFilial'
>;

type ManagerBoardCompanyToast = Pick<ToastService, 'success' | 'error'>;

export type ManagerBoardCompanyFacadeDeps = {
  managerApi: ManagerBoardCompanyApi;
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

  constructor(private readonly deps: ManagerBoardCompanyFacadeDeps) {}

  openCompanyEdit(company: CompanyCardItem): void {
    this.editLoading.set(true);
    this.editError.set(null);
    this.editDeleteKey.set(null);

    this.deps.managerApi.getCompanyEdit(company.id).subscribe({
      next: (payload) => {
        this.applyCompanyEditPayload(payload);
        this.editLoading.set(false);
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

  private setCompanyEditField<K extends keyof CompanyUpdateRequest>(field: K, value: CompanyUpdateRequest[K]): void {
    this.editDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  private applyCompanyEditPayload(payload: CompanyEditPayload): void {
    this.editCompany.set(payload);
    this.editDraft.set(managerCompanyEditDraft(payload));
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
              dateNewTry: payload.dateNewTry
            }
          : company
        )
      }
    }));
  }
}
