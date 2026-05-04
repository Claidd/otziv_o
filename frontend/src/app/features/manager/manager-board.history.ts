import type { ManagerSection } from '../../core/manager.api';
import {
  MANAGER_HISTORY_STATE_KEY,
  ManagerHistoryView,
  SelectedCompany
} from './manager-board.config';

export type ManagerQueryParamReader = {
  get(name: string): string | null;
};

export function managerWithHistoryState(
  existingState: unknown,
  view: ManagerHistoryView,
  stateKey = MANAGER_HISTORY_STATE_KEY
): Record<string, unknown> {
  const baseState = existingState && typeof existingState === 'object' ? existingState : {};

  return {
    ...baseState,
    [stateKey]: view
  };
}

export function managerReadHistoryView(
  state: unknown,
  stateKey = MANAGER_HISTORY_STATE_KEY
): ManagerHistoryView | null {
  if (!state || typeof state !== 'object' || !(stateKey in state)) {
    return null;
  }

  const view = (state as Record<string, unknown>)[stateKey];
  if (!view || typeof view !== 'object') {
    return null;
  }

  const raw = view as Partial<ManagerHistoryView>;
  const activeSection: ManagerSection = raw.activeSection === 'orders' ? 'orders' : 'companies';
  const selectedCompany = managerNormalizeSelectedCompany(raw.selectedCompany);

  return {
    activeSection,
    companyStatus: typeof raw.companyStatus === 'string' ? raw.companyStatus : 'Все',
    orderStatus: typeof raw.orderStatus === 'string' ? raw.orderStatus : 'Все',
    keyword: typeof raw.keyword === 'string' ? raw.keyword : '',
    pageNumber: typeof raw.pageNumber === 'number' ? Math.max(raw.pageNumber, 0) : 0,
    pageSize: typeof raw.pageSize === 'number' ? raw.pageSize : 10,
    sortDirection: raw.sortDirection === 'asc' ? 'asc' : 'desc',
    selectedCompany: activeSection === 'orders' ? selectedCompany : null
  };
}

export function managerReadQueryView(params: ManagerQueryParamReader): ManagerHistoryView | null {
  const section = params.get('section');
  if (section !== 'orders' && section !== 'companies') {
    return null;
  }

  if (section === 'companies') {
    return {
      activeSection: 'companies',
      companyStatus: params.get('status')?.trim() || 'Все',
      orderStatus: 'Все',
      keyword: params.get('keyword')?.trim() || '',
      pageNumber: managerPageNumber(params.get('pageNumber')),
      pageSize: managerPageSize(params.get('pageSize')),
      sortDirection: params.get('sortDirection') === 'asc' ? 'asc' : 'desc',
      selectedCompany: null
    };
  }

  const companyId = Number(params.get('companyId'));
  const selectedCompany = Number.isFinite(companyId) && companyId > 0
    ? {
        id: companyId,
        title: params.get('companyTitle')?.trim() || `Компания #${companyId}`
      }
    : null;

  return {
    activeSection: 'orders',
    companyStatus: 'Все',
    orderStatus: params.get('status')?.trim() || 'Все',
    keyword: params.get('keyword')?.trim() || '',
    pageNumber: managerPageNumber(params.get('pageNumber')),
    pageSize: managerPageSize(params.get('pageSize')),
    sortDirection: params.get('sortDirection') === 'asc' ? 'asc' : 'desc',
    selectedCompany
  };
}

export function managerNormalizeSelectedCompany(value: unknown): SelectedCompany | null {
  if (!value || typeof value !== 'object') {
    return null;
  }

  const selected = value as Partial<SelectedCompany>;
  if (typeof selected.id !== 'number') {
    return null;
  }

  return {
    id: selected.id,
    title: typeof selected.title === 'string' && selected.title.trim()
      ? selected.title
      : `Компания #${selected.id}`
  };
}

function managerPageNumber(value: string | null): number {
  return Math.max(Number(value) || 0, 0);
}

function managerPageSize(value: string | null): number {
  return Number(value) || 10;
}
