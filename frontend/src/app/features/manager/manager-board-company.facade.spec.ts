import { of } from 'rxjs';
import type {
  CompanyCardItem,
  CompanyEditPayload,
  CompanyFilialEditItem,
  CompanyFilialUpdateRequest,
  CompanyUpdateRequest,
  ManagerOption
} from '../../core/manager.api';
import { ManagerBoardCompanyFacade, type ManagerBoardCompanyFacadeDeps } from './manager-board-company.facade';

function option(id: number, label = `Option ${id}`): ManagerOption {
  return { id, label };
}

function filial(id: number, title = `Filial ${id}`): CompanyFilialEditItem {
  return {
    id,
    title,
    url: `https://filial-${id}.example.test`,
    cityId: 31,
    city: 'City'
  };
}

function companyCard(overrides: Partial<CompanyCardItem> = {}): CompanyCardItem {
  return {
    id: 10,
    title: 'Company',
    countFilials: 1,
    status: 'Новая',
    ...overrides
  };
}

function companyPayload(overrides: Partial<CompanyEditPayload> = {}): CompanyEditPayload {
  return {
    id: 10,
    title: 'Company',
    urlChat: 'https://chat.example',
    telephone: '+79990000000',
    city: 'City',
    email: 'company@example.test',
    commentsCompany: 'company note',
    active: true,
    createDate: '2026-01-01',
    updateStatus: '2026-01-02',
    dateNewTry: '2026-01-03',
    status: option(1, 'Новая'),
    category: option(2, 'Category'),
    subCategory: option(3, 'Subcategory'),
    manager: option(4, 'Manager'),
    categories: [option(2, 'Category'), option(6, 'New Category')],
    subCategories: [option(3, 'Subcategory')],
    statuses: [option(1, 'Новая')],
    managers: [option(4, 'Manager')],
    workers: [option(11, 'Worker')],
    currentWorkers: [option(11, 'Worker')],
    filials: [filial(21)],
    cities: [option(31, 'City')],
    canChangeManager: true,
    ...overrides
  };
}

function createFacade(config: {
  payload?: CompanyEditPayload;
  workerDeletePayload?: CompanyEditPayload;
  filialDeletePayload?: CompanyEditPayload;
  filialUpdatePayload?: CompanyEditPayload;
} = {}) {
  const calls: string[] = [];
  const toastMessages: string[] = [];
  let lastUpdateRequest: CompanyUpdateRequest | null = null;
  let lastFilialUpdateRequest: CompanyFilialUpdateRequest | null = null;
  const sourcePayload = config.payload ?? companyPayload();
  const deps: ManagerBoardCompanyFacadeDeps = {
    managerApi: {
      getCompanyEdit: (companyId: number) => {
        calls.push(`get-company:${companyId}`);
        return of(sourcePayload);
      },
      getCompanySubcategories: (categoryId: number) => {
        calls.push(`subcategories:${categoryId}`);
        return of([option(61, 'Loaded Subcategory')]);
      },
      updateCompany: (companyId: number, request: CompanyUpdateRequest) => {
        calls.push(`update-company:${companyId}`);
        lastUpdateRequest = request;
        return of(sourcePayload);
      },
      deleteCompanyWorker: (companyId: number, workerId: number) => {
        calls.push(`delete-worker:${companyId}:${workerId}`);
        return of(config.workerDeletePayload ?? companyPayload({ currentWorkers: [] }));
      },
      deleteCompanyFilial: (companyId: number, filialId: number) => {
        calls.push(`delete-filial:${companyId}:${filialId}`);
        return of(config.filialDeletePayload ?? companyPayload({ filials: [] }));
      },
      updateCompanyFilial: (companyId: number, filialId: number, request: CompanyFilialUpdateRequest) => {
        calls.push(`update-filial:${companyId}:${filialId}`);
        lastFilialUpdateRequest = request;
        return of(config.filialUpdatePayload ?? companyPayload({
          filials: [filial(filialId, request.title)]
        }));
      }
    },
    toastService: {
      success: (title: string, message?: string) => {
        toastMessages.push(`success:${title}:${message ?? ''}`);
        return toastMessages.length;
      },
      error: (title: string, message?: string) => {
        toastMessages.push(`error:${title}:${message ?? ''}`);
        return toastMessages.length;
      }
    },
    loadBoard: () => {
      calls.push('load-board');
    },
    errorMessage: (_err, fallback) => fallback
  };

  return {
    facade: new ManagerBoardCompanyFacade(deps),
    calls,
    toastMessages,
    get lastUpdateRequest() {
      return lastUpdateRequest;
    },
    get lastFilialUpdateRequest() {
      return lastFilialUpdateRequest;
    }
  };
}

describe('ManagerBoardCompanyFacade', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('opens company edit and owns draft state', () => {
    const { facade, calls } = createFacade();

    facade.openCompanyEdit(companyCard({ id: 10 }));

    expect(calls).toContain('get-company:10');
    expect(facade.editCompany()?.title).toBe('Company');
    expect(facade.editDraft()).toEqual({
      title: 'Company',
      urlChat: 'https://chat.example',
      telephone: '+79990000000',
      city: 'City',
      email: 'company@example.test',
      categoryId: 2,
      subCategoryId: 3,
      statusId: 1,
      managerId: 4,
      commentsCompany: 'company note',
      active: true,
      newWorkerId: null,
      newFilialCityId: null,
      newFilialTitle: '',
      newFilialUrl: ''
    });

    facade.closeCompanyEdit();

    expect(facade.editCompany()).toBeNull();
    expect(facade.editDraft()).toBeNull();
  });

  it('updates draft fields and reloads subcategories when category changes', () => {
    const { facade, calls } = createFacade();

    facade.openCompanyEdit(companyCard());
    facade.handleCompanyEditDraftChange({ field: 'title', value: 'New Company' });
    facade.changeCompanyCategory(6);

    expect(calls).toContain('subcategories:6');
    expect(facade.editDraft()?.title).toBe('New Company');
    expect(facade.editDraft()?.categoryId).toBe(6);
    expect(facade.editDraft()?.subCategoryId).toBe(61);
    expect(facade.editCompany()?.subCategories).toEqual([option(61, 'Loaded Subcategory')]);
  });

  it('saves company draft and reloads the board', () => {
    const state = createFacade();

    state.facade.openCompanyEdit(companyCard());
    state.facade.handleCompanyEditDraftChange({ field: 'commentsCompany', value: 'new note' });
    state.facade.saveCompanyEdit();

    expect(state.calls).toContain('update-company:10');
    expect(state.lastUpdateRequest?.commentsCompany).toBe('new note');
    expect(state.facade.editCompany()).toBeNull();
    expect(state.calls).toContain('load-board');
    expect(state.toastMessages.some((message) => message.startsWith('success:Компания сохранена'))).toBe(true);
  });

  it('deletes company worker after confirmation and applies returned payload', () => {
    const state = createFacade();
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    state.facade.openCompanyEdit(companyCard());
    state.facade.deleteCompanyWorker(option(11, 'Worker'));

    expect(state.calls).toContain('delete-worker:10:11');
    expect(state.facade.editCompany()?.currentWorkers).toEqual([]);
    expect(state.calls).toContain('load-board');
    expect(state.toastMessages).toContain('success:Специалист удален:Worker');
  });

  it('deletes company filial after confirmation and applies returned payload', () => {
    const state = createFacade();
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    state.facade.openCompanyEdit(companyCard());
    state.facade.deleteCompanyFilial(21, 'Filial 21');

    expect(state.calls).toContain('delete-filial:10:21');
    expect(state.facade.editCompany()?.filials).toEqual([]);
    expect(state.calls).toContain('load-board');
    expect(state.toastMessages).toContain('success:Филиал удален:Filial 21');
  });

  it('updates company filial and applies returned payload', () => {
    const state = createFacade();

    state.facade.openCompanyEdit(companyCard());
    state.facade.updateCompanyFilial({
      filialId: 21,
      title: 'Updated filial',
      url: 'https://updated.example.test',
      cityId: 31
    });

    expect(state.calls).toContain('update-filial:10:21');
    expect(state.lastFilialUpdateRequest).toEqual({
      title: 'Updated filial',
      url: 'https://updated.example.test',
      cityId: 31
    });
    expect(state.facade.editCompany()?.filials[0]?.title).toBe('Updated filial');
    expect(state.calls).toContain('load-board');
    expect(state.toastMessages).toContain('success:Филиал сохранен:Updated filial');
  });
});
