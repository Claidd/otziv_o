import { appEnvironment } from '../../core/app-environment';
import { apiErrorMessage } from '../../shared/api-error-message';
import {
  absoluteAppUrl,
  orderReviewCopyText,
  reviewCheckPath
} from '../../shared/order-review-copy-text';
import type {
  CompanyCardItem,
  ManagerMetric,
  ManagerOption,
  ManagerPage,
  ManagerSection,
  OrderCardItem
} from '../../core/manager.api';

export type SectionTab = {
  key: ManagerSection;
  label: string;
  icon: string;
};

export type PromoItem = {
  label: string;
  text: string;
};

export type StatusAction = {
  label: string;
  status: string;
  icon: string;
};

export type MobileNavLink = {
  label: string;
  routerLink?: string;
  href?: string;
};

export type SelectedCompany = {
  id: number;
  title: string;
};

export type ManagerHistoryView = {
  activeSection: ManagerSection;
  companyStatus: string;
  orderStatus: string;
  keyword: string;
  pageNumber: number;
  pageSize: number;
  sortDirection: 'desc' | 'asc';
  selectedCompany: SelectedCompany | null;
};

export const MANAGER_HISTORY_STATE_KEY = 'otzivManagerView';
export const PREFERRED_CREATE_ORDER_PRODUCT = 'отзыв2гис+';

export const MANAGER_SECTIONS: SectionTab[] = [
  { key: 'companies', label: 'Компании', icon: 'business' },
  { key: 'orders', label: 'Заказы', icon: 'inventory_2' }
];

export const MANAGER_COMPANY_ACTIONS: StatusAction[] = [
  { label: 'предложил', status: 'Ожидание', icon: 'outgoing_mail' },
  { label: 'разослал', status: 'К рассылке', icon: 'campaign' },
  { label: 'на стоп', status: 'На стопе', icon: 'pause_circle' },
  { label: 'бан', status: 'Бан', icon: 'block' }
];

export const MANAGER_ORDER_ACTIONS: StatusAction[] = [
  { label: 'на проверку', status: 'На проверке', icon: 'manage_search' },
  { label: 'коррекция', status: 'Коррекция', icon: 'build_circle' },
  { label: 'одобрено', status: 'Публикация', icon: 'task_alt' },
  { label: 'архив', status: 'Архив', icon: 'archive' },
  { label: 'опублик.', status: 'Опубликовано', icon: 'published_with_changes' },
  { label: 'счет', status: 'Выставлен счет', icon: 'receipt_long' },
  { label: 'напомнить', status: 'Напоминание', icon: 'notifications_active' },
  { label: 'не опл.', status: 'Не оплачено', icon: 'money_off' },
  { label: 'оплатили', status: 'Оплачено', icon: 'payments' }
];

export const MANAGER_PAGE_SIZE_OPTIONS = [5, 10, 15];

export const EMPTY_MANAGER_COMPANY_PAGE: ManagerPage<CompanyCardItem> = {
  content: [],
  number: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true
};

export const EMPTY_MANAGER_ORDER_PAGE: ManagerPage<OrderCardItem> = {
  content: [],
  number: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true
};

export const DEFAULT_MANAGER_COMPANY_STATUSES = [
  'Все',
  'Новая',
  'В работе',
  'Новый заказ',
  'К рассылке',
  'Ожидание',
  'На стопе',
  'Бан'
];

export const DEFAULT_MANAGER_ORDER_STATUSES = [
  'Все',
  'Новый',
  'В проверку',
  'На проверке',
  'Коррекция',
  'Публикация',
  'Опубликовано',
  'Выставлен счет',
  'Напоминание',
  'Не оплачено',
  'Архив',
  'Оплачено'
];

export const MANAGER_MOBILE_NAV_LINKS: MobileNavLink[] = [
  { label: 'Главная', routerLink: '/' },
  { label: 'Лиды', routerLink: '/leads' },
  { label: 'Оператор', routerLink: '/operator' },
  { label: 'Менеджер', routerLink: '/manager' },
  { label: 'Специалист', href: '/worker' },
  { label: 'Личный кабинет', routerLink: '/' }
];

export function managerLegacyUrl(path: string): string {
  return `${appEnvironment.legacyBaseUrl}${path}`;
}

export function managerReviewCheckPath(orderDetailsId: string): string {
  return reviewCheckPath(orderDetailsId);
}

export function managerAbsoluteAppUrl(path: string): string {
  return absoluteAppUrl(path);
}

export function managerSectionLabel(section: ManagerSection): string {
  return section === 'companies' ? 'Компании' : 'Заказы';
}

export function managerBoardTitle(section: ManagerSection, status: string, selectedCompany: SelectedCompany | null): string {
  if (section === 'orders' && selectedCompany) {
    return `Заказы - ${selectedCompany.title}`;
  }

  const sectionLabel = managerSectionLabel(section);
  return status === 'Все' ? sectionLabel : status;
}

export function managerLayoutTitle(section: ManagerSection, status: string, selectedCompany: SelectedCompany | null): string {
  if (section === 'orders' && selectedCompany) {
    return `Заказы - ${selectedCompany.title}`;
  }

  return `${managerSectionLabel(section)} - ${status}`;
}

export function managerPromoItems(section: ManagerSection, texts: string[]): PromoItem[] {
  if (section === 'orders') {
    return [
      { label: 'пояснение', text: texts[10] ?? '' },
      { label: 'напоминание', text: texts[5] ?? '' },
      { label: 'угроза', text: texts[6] ?? '' }
    ];
  }

  return [
    { label: 'предложение', text: texts[0] ?? '' },
    { label: 'пояснение', text: texts[10] ?? '' },
    { label: 'рассылка', text: texts[9] ?? '' }
  ];
}

export function managerStatusOptionLabel(status: string, count: number | null): string {
  return count === null ? status : `${status}: ${count}`;
}

export function managerPayableOrderSum(order: OrderCardItem): number {
  return order.totalSumWithBadReviews ?? order.sum ?? 0;
}

export function managerShowBadReviewSummary(order: OrderCardItem): boolean {
  return order.status !== 'Оплачено' && (order.badReviewTasksTotal ?? 0) > 0;
}

export function managerProgress(order: OrderCardItem): number {
  if (!order.amount || !order.counter) {
    return 0;
  }

  return Math.max(0, Math.min(100, Math.round((order.counter / order.amount) * 100)));
}

export function managerHasMeaningfulNote(value?: string | null): boolean {
  const normalized = (value ?? '').trim().toLocaleLowerCase('ru-RU');
  return Boolean(normalized) && normalized !== 'нет заметок';
}

export function managerCompanyChatUrl(company: CompanyCardItem): string {
  return company.urlChat || `tel:${company.telephone ?? ''}`;
}

export function managerOrderChatUrl(order: OrderCardItem): string {
  return order.companyUrlChat || `tel:${order.companyTelephone ?? ''}`;
}

export function managerCompanyFilialUrl(company: CompanyCardItem): string {
  return company.urlFilial || managerLegacyUrl(`/companies/editCompany/${company.id}`);
}

export function managerCompanyOrderUrl(company: CompanyCardItem): string {
  return managerLegacyUrl(`/ordersCompany/${company.id}`);
}

export function managerFilialEditUrl(filialId: number): string {
  return managerLegacyUrl(`/filial/edit/${filialId}`);
}

export function managerOrderDetailsUrl(order: OrderCardItem): string {
  return managerLegacyUrl(`/ordersCompany/ordersDetails/${order.companyId}/${order.id}`);
}

export function managerOrderInfoUrl(order: OrderCardItem): string {
  return managerLegacyUrl(`/ordersDetails/${order.companyId}/${order.id}`);
}

export function managerOrderReviewUrl(order: OrderCardItem): string {
  return order.orderDetailsId ? managerReviewCheckPath(order.orderDetailsId) : '';
}

export function managerOrderReviewCopyText(order: OrderCardItem, promoTexts: string[]): string {
  return orderReviewCopyText(order, promoTexts);
}

export function managerOptionLabel(option: ManagerOption): string {
  return option.label || `ID ${option.id}`;
}

export function managerOrderActions(order: OrderCardItem, showAllActions: boolean): StatusAction[] {
  if (showAllActions) {
    return MANAGER_ORDER_ACTIONS;
  }

  const actions: StatusAction[] = [];

  if (order.status === 'В проверку' || order.status === 'Архив') {
    actions.push(MANAGER_ORDER_ACTIONS[0]);
  }

  if (order.status === 'На проверке') {
    actions.push(MANAGER_ORDER_ACTIONS[1]);
    actions.push(MANAGER_ORDER_ACTIONS[3]);
  }

  if (order.status === 'На проверке' || order.status === 'Коррекция' || order.status === 'Архив') {
    actions.push(MANAGER_ORDER_ACTIONS[2]);
  }

  if (order.status === 'Публикация') {
    actions.push(MANAGER_ORDER_ACTIONS[4]);
  }

  if (order.status === 'Опубликовано') {
    actions.push(MANAGER_ORDER_ACTIONS[5]);
  }

  if (order.status === 'Выставлен счет') {
    actions.push(MANAGER_ORDER_ACTIONS[6]);
  }

  if (order.status === 'Выставлен счет' || order.status === 'Напоминание') {
    actions.push(MANAGER_ORDER_ACTIONS[7]);
  }

  if (order.status === 'Выставлен счет' || order.status === 'Напоминание' || order.status === 'Не оплачено') {
    actions.push(MANAGER_ORDER_ACTIONS[8]);
  }

  return actions;
}

export function managerErrorMessage(err: unknown, fallback: string): string {
  return apiErrorMessage(err, fallback);
}

export function trackManagerSection(_index: number, section: SectionTab): ManagerSection {
  return section.key;
}

export function trackManagerStatus(_index: number, status: string): string {
  return status;
}

export function trackManagerCompany(_index: number, company: CompanyCardItem): number {
  return company.id;
}

export function trackManagerOrder(_index: number, order: OrderCardItem): number {
  return order.id;
}

export function trackManagerMetric(_index: number, metric: ManagerMetric): string {
  return `${metric.section}-${metric.status}`;
}

export function trackManagerAction(_index: number, action: StatusAction): string {
  return action.status;
}

export function trackManagerOption(_index: number, option: ManagerOption): number {
  return option.id;
}

export function trackManagerProduct(_index: number, product: { id: number }): number {
  return product.id;
}
