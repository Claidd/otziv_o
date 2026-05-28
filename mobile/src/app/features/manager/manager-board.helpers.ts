export const ALL_STATUS = 'Все';
export const PREFERRED_CREATE_ORDER_PRODUCT = 'отзыв2гис+';

export const DEFAULT_COMPANY_STATUSES = [
  'Все',
  'Новая',
  'В работе',
  'Новый заказ',
  'К рассылке',
  'Ожидание',
  'На стопе',
  'Бан',
  'Архив'
];

export const DEFAULT_ORDER_STATUSES = [
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
  'Бан',
  'Архив'
];

export const COMPANY_ACTIONS = [
  { label: 'предложил', status: 'Ожидание', icon: 'outgoing_mail' },
  { label: 'рассылка', status: 'К рассылке', icon: 'campaign' },
  { label: 'на стоп', status: 'На стопе', icon: 'pause_circle' },
  { label: 'бан', status: 'Бан', icon: 'block' }
] as const;

export const ORDER_ACTIONS = [
  { label: 'на проверк', status: 'На проверке', icon: 'manage_search' },
  { label: 'коррекция', status: 'Коррекция', icon: 'build_circle' },
  { label: 'одобрено', status: 'Публикация', icon: 'task_alt' },
  { label: 'архив', status: 'Архив', icon: 'archive' },
  { label: 'опубликов.', status: 'Опубликовано', icon: 'published_with_changes' },
  { label: 'счет', status: 'Выставлен счет', icon: 'receipt_long' },
  { label: 'напомин.', status: 'Напоминание', icon: 'notifications_active' },
  { label: 'не опл.', status: 'Не оплачено', icon: 'money_off' },
  { label: 'в бан', status: 'Бан', icon: 'block' },
  { label: 'оплатили', status: 'Оплачено', icon: 'payments' }
] as const;

export type CompanyStatusAction = (typeof COMPANY_ACTIONS)[number];
export type OrderStatusAction = (typeof ORDER_ACTIONS)[number];
export type ManagerNoteSaveState = 'idle' | 'saving' | 'saved' | 'error';

export type ManagerOrderActionSource = {
  status?: string | null;
  badReviewTasksTotal?: number | null;
  badReviewTasksPending?: number | null;
};

export function normalizedManagerStatus(status: string): string {
  return status === 'Рабочие' || !status ? ALL_STATUS : status;
}

export function ensureAllStatus(statuses: readonly string[]): string[] {
  const normalized = statuses.map((status) => normalizedManagerStatus(status));
  const uniqueStatuses = normalized.filter((status, index) =>
    status !== ALL_STATUS && normalized.indexOf(status) === index
  );
  return [ALL_STATUS, ...uniqueStatuses];
}

export function managerStatusLabel(status: string): string {
  return normalizedManagerStatus(status).toLowerCase();
}

export function managerOrderActionsFor(
  order: ManagerOrderActionSource,
  showAllActions = false
): readonly OrderStatusAction[] {
  if (showAllActions) {
    return ORDER_ACTIONS;
  }

  const status = order.status ?? '';
  const actions: OrderStatusAction[] = [];

  if (status === 'В проверку' || status === 'Архив') {
    actions.push(ORDER_ACTIONS[0]);
  }

  if (status === 'На проверке') {
    actions.push(ORDER_ACTIONS[1], ORDER_ACTIONS[3]);
  }

  if (status === 'На проверке' || status === 'Коррекция' || status === 'Архив') {
    actions.push(ORDER_ACTIONS[2]);
  }

  if (status === 'Публикация') {
    actions.push(ORDER_ACTIONS[4]);
  }

  if (status === 'Опубликовано') {
    actions.push(ORDER_ACTIONS[5]);
  }

  if (status === 'Выставлен счет') {
    actions.push(ORDER_ACTIONS[6]);
  }

  if (status === 'Выставлен счет' || status === 'Напоминание') {
    actions.push(ORDER_ACTIONS[7]);
  }

  if (status === 'Выставлен счет' || status === 'Напоминание' || status === 'Не оплачено' || status === 'Бан') {
    actions.push(ORDER_ACTIONS[9]);
  }

  if (
    status === 'Не оплачено'
    && (order.badReviewTasksTotal ?? 0) > 0
    && (order.badReviewTasksPending ?? 0) === 0
  ) {
    actions.push(ORDER_ACTIONS[8]);
  }

  return actions;
}
