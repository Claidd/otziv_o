import type { CompanyEditPayload, ManagerOption } from '../../core/manager.api';
import {
  managerCompanyEditDraft,
  managerCompanyFilialDeletedLabel,
  managerCompanyFilialDeleteConfirm,
  managerCompanyFilialDeleteKey,
  managerCompanyWorkerDeleteConfirm,
  managerCompanyWorkerDeleteKey
} from './manager-board.company.helpers';

function option(id: number, label = `Option ${id}`): ManagerOption {
  return { id, label };
}

function payload(overrides: Partial<CompanyEditPayload> = {}): CompanyEditPayload {
  return {
    id: 9,
    title: 'Company',
    urlChat: 'https://chat',
    telephone: '+7000',
    city: 'City',
    email: 'mail@example.test',
    commentsCompany: 'note',
    active: true,
    createDate: '',
    updateStatus: '',
    dateNewTry: '',
    status: option(1, 'Новая'),
    category: option(2, 'Category'),
    subCategory: option(3, 'Subcategory'),
    manager: option(4, 'Manager'),
    categories: [],
    subCategories: [],
    statuses: [],
    managers: [],
    workers: [],
    currentWorkers: [],
    filials: [],
    cities: [],
    canChangeManager: true,
    ...overrides
  };
}

describe('manager-board company helpers', () => {
  it('builds company edit draft from payload', () => {
    expect(managerCompanyEditDraft(payload())).toEqual({
      title: 'Company',
      urlChat: 'https://chat',
      telephone: '+7000',
      city: 'City',
      email: 'mail@example.test',
      categoryId: 2,
      subCategoryId: 3,
      statusId: 1,
      managerId: 4,
      commentsCompany: 'note',
      active: true,
      newWorkerId: null,
      newFilialCityId: null,
      newFilialTitle: '',
      newFilialUrl: ''
    });
  });

  it('handles missing optional company relations', () => {
    expect(managerCompanyEditDraft(payload({
      status: null,
      category: null,
      subCategory: null,
      manager: null,
      active: false
    }))).toEqual(expect.objectContaining({
      categoryId: null,
      subCategoryId: null,
      statusId: null,
      managerId: null,
      active: false
    }));
  });

  it('keeps delete keys and messages stable', () => {
    const worker = option(12, 'Иван');

    expect(managerCompanyWorkerDeleteKey(worker)).toBe('worker-12');
    expect(managerCompanyWorkerDeleteConfirm(worker)).toBe('Убрать специалиста "Иван" из компании?');
    expect(managerCompanyFilialDeleteKey(7)).toBe('filial-7');
    expect(managerCompanyFilialDeleteConfirm(7, 'Филиал')).toBe('Удалить филиал "Филиал"?');
    expect(managerCompanyFilialDeleteConfirm(7, '')).toBe('Удалить филиал "7"?');
    expect(managerCompanyFilialDeletedLabel(7, 'Филиал')).toBe('Филиал');
    expect(managerCompanyFilialDeletedLabel(7, '')).toBe('#7');
  });
});
