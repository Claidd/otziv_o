import type { ManagerHistoryView } from './manager-board.config';
import {
  managerNormalizeSelectedCompany,
  managerReadHistoryView,
  managerReadQueryView,
  managerWithHistoryState
} from './manager-board.history';

function view(overrides: Partial<ManagerHistoryView> = {}): ManagerHistoryView {
  return {
    activeSection: 'companies',
    companyStatus: 'Все',
    orderStatus: 'Все',
    keyword: '',
    pageNumber: 0,
    pageSize: 10,
    sortDirection: 'desc',
    selectedCompany: null,
    ...overrides
  };
}

function params(values: Record<string, string | null>) {
  return {
    get(name: string): string | null {
      return values[name] ?? null;
    }
  };
}

describe('manager-board history helpers', () => {
  it('stores manager view in history state without dropping existing state', () => {
    const stored = view({ keyword: 'needle' });
    const state = managerWithHistoryState({ navigationId: 3 }, stored, 'customKey');

    expect(state).toEqual({
      navigationId: 3,
      customKey: stored
    });
  });

  it('reads and normalizes history state', () => {
    const state = managerWithHistoryState(
      {},
      view({
        activeSection: 'orders',
        companyStatus: 'Бан',
        orderStatus: 'Оплачено',
        keyword: 'search',
        pageNumber: -5,
        pageSize: 15,
        sortDirection: 'asc',
        selectedCompany: { id: 9, title: '' }
      })
    );

    expect(managerReadHistoryView(state)).toEqual({
      activeSection: 'orders',
      companyStatus: 'Бан',
      orderStatus: 'Оплачено',
      keyword: 'search',
      pageNumber: 0,
      pageSize: 15,
      sortDirection: 'asc',
      selectedCompany: { id: 9, title: 'Компания #9' }
    });
  });

  it('drops selected company when restored section is companies', () => {
    const restored = managerReadHistoryView(managerWithHistoryState({}, view({
      activeSection: 'companies',
      selectedCompany: { id: 3, title: 'Acme' }
    })));

    expect(restored?.selectedCompany).toBeNull();
  });

  it('returns null for missing or invalid history state', () => {
    expect(managerReadHistoryView(null)).toBeNull();
    expect(managerReadHistoryView({ other: view() })).toBeNull();
    expect(managerReadHistoryView({ otzivManagerView: 'bad' })).toBeNull();
  });

  it('reads company query params with safe defaults', () => {
    const result = managerReadQueryView(params({
      section: 'companies',
      status: ' Бан ',
      keyword: ' query ',
      pageNumber: '-2',
      pageSize: '',
      sortDirection: 'asc'
    }));

    expect(result).toEqual({
      activeSection: 'companies',
      companyStatus: 'Бан',
      orderStatus: 'Все',
      keyword: 'query',
      pageNumber: 0,
      pageSize: 10,
      sortDirection: 'asc',
      selectedCompany: null
    });
  });

  it('reads order query params and selected company fallback', () => {
    const result = managerReadQueryView(params({
      section: 'orders',
      status: 'Оплачено',
      keyword: 'pay',
      pageNumber: '2',
      pageSize: '15',
      sortDirection: 'desc',
      companyId: '4',
      companyTitle: ''
    }));

    expect(result).toEqual({
      activeSection: 'orders',
      companyStatus: 'Все',
      orderStatus: 'Оплачено',
      keyword: 'pay',
      pageNumber: 2,
      pageSize: 15,
      sortDirection: 'desc',
      selectedCompany: { id: 4, title: 'Компания #4' }
    });
  });

  it('normalizes selected company objects', () => {
    expect(managerNormalizeSelectedCompany(null)).toBeNull();
    expect(managerNormalizeSelectedCompany({ id: '4', title: 'bad' })).toBeNull();
    expect(managerNormalizeSelectedCompany({ id: 4, title: ' Acme ' })).toEqual({ id: 4, title: ' Acme ' });
    expect(managerReadQueryView(params({ section: 'bad' }))).toBeNull();
  });
});
