import type {
  OrderItem,
  Page,
  WorkerBoardSection,
  WorkerPermissions,
  WorkerReviewItem
} from '../../core/api.service';

export type WorkerStatusAction = {
  label: string;
  status: string;
  icon: string;
};

export type ReviewEditableField = 'text' | 'answer';
export type SideNoteField = 'order' | 'company';
export type ReviewCopyKind = 'url' | 'login' | 'password' | 'text' | 'answer' | 'vk';

export const DEFAULT_WORKER_SECTION: WorkerBoardSection = 'new';
export const WORKER_SECTIONS: readonly WorkerBoardSection[] = ['new', 'correct', 'nagul', 'recovery', 'publish', 'bad', 'all'];
export const REVIEW_SECTIONS = new Set<WorkerBoardSection>(['nagul', 'recovery', 'publish', 'bad']);
export const MOBILE_WORKER_ACTIVITY_SOURCE_PAGE = 'mobile-worker-board';

export const WORKER_SECTION_LABELS: Record<WorkerBoardSection, string> = {
  all: 'Все',
  new: 'Новые',
  correct: 'Коррекция',
  nagul: 'Выгул',
  recovery: 'Восстановление',
  publish: 'Публикация',
  bad: 'Плохие'
};

export const WORKER_SECTION_ICONS: Record<WorkerBoardSection, string> = {
  all: 'dashboard',
  new: 'fiber_new',
  correct: 'build_circle',
  nagul: 'directions_walk',
  recovery: 'restore',
  publish: 'published_with_changes',
  bad: 'money_off'
};

export const ORDER_STATUS_ACTIONS: readonly WorkerStatusAction[] = [
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

export function workerOrderTargetStatus(
  order: Pick<OrderItem, 'waitingForClient'>,
  action: WorkerStatusAction
): string {
  return order.waitingForClient && action.status === 'На проверке'
    ? 'В проверку'
    : action.status;
}

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

export const EMPTY_ORDER_PAGE: Page<OrderItem> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
  number: 0,
  size: 10
};

export const EMPTY_REVIEW_PAGE: Page<WorkerReviewItem> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
  number: 0,
  size: 10
};

export function workerSectionLabel(section: WorkerBoardSection): string {
  return WORKER_SECTION_LABELS[section];
}

export function workerSectionIcon(section: WorkerBoardSection): string {
  return WORKER_SECTION_ICONS[section];
}

export function workerSectionFromRouteParam(value: string | null | undefined): WorkerBoardSection | null {
  const normalized = value?.trim().toLowerCase();
  return WORKER_SECTIONS.includes(normalized as WorkerBoardSection)
    ? normalized as WorkerBoardSection
    : null;
}

export function isWorkerReviewSection(section: WorkerBoardSection): boolean {
  return REVIEW_SECTIONS.has(section);
}

export function workerDefaultSortDirection(_section: WorkerBoardSection): 'desc' {
  return 'desc';
}

export function workerReviewToneClass(review: WorkerReviewItem, section: WorkerBoardSection): string {
  if (review.badTask || section === 'bad') {
    return 'tone-bad';
  }
  if (review.recoveryTask || section === 'recovery') {
    return 'tone-recovery';
  }
  if (section === 'nagul') {
    return 'tone-walk';
  }
  if (section === 'publish') {
    return 'tone-publication';
  }
  return '';
}

export function workerReviewTitle(review: WorkerReviewItem, section: WorkerBoardSection): string {
  const company = review.companyTitle?.trim() || 'Компания';
  const filial = review.filialTitle?.trim();
  return filial && (section === 'nagul' || section === 'recovery' || section === 'publish')
    ? `${company} - ${filial}`
    : company;
}

export function formatCredentialWaitSeconds(seconds: number): string {
  const normalized = Math.max(0, Math.ceil(Number(seconds) || 0));
  const minutes = Math.floor(normalized / 60);
  const remainder = normalized % 60;
  return `${minutes}:${String(remainder).padStart(2, '0')}`;
}
