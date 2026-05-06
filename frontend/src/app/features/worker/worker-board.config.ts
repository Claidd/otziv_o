import { appEnvironment } from '../../core/app-environment';
import type { ManagerOption, OrderCardItem, OrderReviewItem, ReviewUpdateRequest } from '../../core/manager.api';
import { apiErrorMessage } from '../../shared/api-error-message';
import type {
  WorkerBotItem,
  WorkerMetric,
  WorkerPage,
  WorkerPermissions,
  WorkerReviewItem,
  WorkerSection
} from '../../core/worker.api';

export type SectionTab = {
  key: WorkerSection;
  label: string;
  icon: string;
};

export type StatusAction = {
  label: string;
  status: string;
  icon: string;
};

export type ReviewEditableField = 'text' | 'answer';
export type SideNoteField = 'order' | 'company';
export type ReviewEditItem = WorkerReviewItem | OrderReviewItem;
export type ReviewEditDraft = ReviewUpdateRequest;
export type ReviewCopyKind = 'url' | 'login' | 'password' | 'text' | 'answer' | 'vk';

export const WORKER_SECTIONS: SectionTab[] = [
  { key: 'new', label: 'Новые', icon: 'fiber_new' },
  { key: 'correct', label: 'Коррекция', icon: 'build_circle' },
  { key: 'nagul', label: 'Выгул', icon: 'directions_walk' },
  { key: 'publish', label: 'Публикация', icon: 'published_with_changes' },
  { key: 'bad', label: 'Плохие', icon: 'money_off' },
  { key: 'all', label: 'Все', icon: 'dashboard' }
];

export const WORKER_ORDER_STATUS_ACTIONS: StatusAction[] = [
  { label: 'на проверку', status: 'На проверке', icon: 'manage_search' },
  { label: 'коррекция', status: 'Коррекция', icon: 'build_circle' },
  { label: 'архив', status: 'Архив', icon: 'archive' },
  { label: 'одобрено', status: 'Публикация', icon: 'task_alt' },
  { label: 'опублик.', status: 'Опубликовано', icon: 'published_with_changes' },
  { label: 'счет', status: 'Выставлен счет', icon: 'receipt_long' },
  { label: 'напомнить', status: 'Напоминание', icon: 'notifications_active' },
  { label: 'не опл.', status: 'Не оплачено', icon: 'money_off' },
  { label: 'оплатили', status: 'Оплачено', icon: 'payments' }
];

export const WORKER_PAGE_SIZE_OPTIONS = [5, 10, 15];

export const EMPTY_WORKER_ORDER_PAGE: WorkerPage<OrderCardItem> = {
  content: [],
  number: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true
};

export const EMPTY_WORKER_REVIEW_PAGE: WorkerPage<WorkerReviewItem> = {
  content: [],
  number: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true
};

export const DEFAULT_WORKER_PERMISSIONS: WorkerPermissions = {
  canManageOrderStatuses: false,
  canManageClientWaiting: false,
  canSeePhoneAndPayment: false,
  canManageBots: false,
  canAddBot: false,
  canSeeMoney: false,
  canWorkReviews: false,
  canEditNotes: false
};

export function workerSectionLabel(section: WorkerSection): string {
  return WORKER_SECTIONS.find((item) => item.key === section)?.label ?? 'Новые';
}

export function workerReviewCopyLabel(kind: ReviewCopyKind): string {
  return {
    url: 'Ссылка',
    login: 'Логин',
    password: 'Пароль',
    text: 'Текст',
    answer: 'Ответ',
    vk: 'VK'
  }[kind];
}

export function workerBotChangeMessage(oldBotId?: number | null, newBotId?: number | null): string {
  return `Аккаунт изменен с ID ${workerBotIdLabel(oldBotId)} на ID ${workerBotIdLabel(newBotId)}`;
}

export function workerBotIdLabel(botId?: number | null): string {
  return botId ? String(botId) : 'не назначен';
}

export function workerLegacyUrl(path: string): string {
  return `${appEnvironment.legacyBaseUrl}${path}`;
}

export function workerAbsoluteAppUrl(path: string): string {
  return new URL(path, window.location.origin).toString();
}

export function workerErrorMessage(err: unknown, fallback: string): string {
  return apiErrorMessage(err, fallback);
}

export function trackWorkerSection(_index: number, section: SectionTab): WorkerSection {
  return section.key;
}

export function trackWorkerOrder(_index: number, order: OrderCardItem): number {
  return order.id;
}

export function trackWorkerReview(_index: number, review: WorkerReviewItem): number {
  return review.id;
}

export function trackWorkerBot(_index: number, bot: WorkerBotItem): number {
  return bot.id;
}

export function trackWorkerMetric(_index: number, metric: WorkerMetric): string {
  return metric.section;
}

export function trackWorkerAction(_index: number, action: StatusAction): string {
  return action.status;
}

export function trackWorkerOption(_index: number, option: ManagerOption): number {
  return option.id;
}
