import type {
  CompanyEditPayload,
  CompanyUpdateRequest,
  ManagerOption
} from '../../core/manager.api';

export function managerCompanyEditDraft(payload: CompanyEditPayload): CompanyUpdateRequest {
  return {
    title: payload.title,
    urlChat: payload.urlChat,
    telephone: payload.telephone,
    city: payload.city,
    email: payload.email,
    categoryId: payload.category?.id ?? null,
    subCategoryId: payload.subCategory?.id ?? null,
    statusId: payload.status?.id ?? null,
    managerId: payload.manager?.id ?? null,
    commentsCompany: payload.commentsCompany,
    active: payload.active,
    newWorkerId: null,
    newFilialCityId: null,
    newFilialTitle: '',
    newFilialUrl: ''
  };
}

export function managerCompanyWorkerDeleteKey(worker: ManagerOption): string {
  return `worker-${worker.id}`;
}

export function managerCompanyWorkerDeleteConfirm(worker: ManagerOption): string {
  return `Убрать специалиста "${worker.label}" из компании?`;
}

export function managerCompanyFilialDeleteKey(filialId: number): string {
  return `filial-${filialId}`;
}

export function managerCompanyFilialDeleteConfirm(filialId: number, title: string): string {
  return `Удалить филиал "${title || filialId}"?`;
}

export function managerCompanyFilialDeletedLabel(filialId: number, title: string): string {
  return title || `#${filialId}`;
}
