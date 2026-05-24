export const ORDER_STATUS_TO_CHECK = 'В проверку';
export const ORDER_STATUS_IN_CHECK = 'На проверке';

export function orderDetailsSendToCheckTargetStatus(
  currentStatus: string | null | undefined,
  onlyWorkerRole: boolean
): string {
  return isToCheckStatus(currentStatus) && !onlyWorkerRole
    ? ORDER_STATUS_IN_CHECK
    : ORDER_STATUS_TO_CHECK;
}

export function orderDetailsSendToCheckActionLabel(targetStatus: string): string {
  return targetStatus === ORDER_STATUS_IN_CHECK ? 'Отметить на проверке' : 'Отправить на проверку';
}

export function orderDetailsSendToCheckBusyLabel(targetStatus: string): string {
  return targetStatus === ORDER_STATUS_IN_CHECK ? 'Отмечаю...' : 'Отправляю...';
}

export function orderDetailsSendToCheckSuccessDetail(targetStatus: string): string {
  return targetStatus === ORDER_STATUS_IN_CHECK
    ? 'Статус изменен на "На проверке" без повторной отправки в чат'
    : 'Статус изменен на "В проверку"';
}

function normalizeStatus(value: string | null | undefined): string {
  return (value ?? '').trim().replace(/\s+/g, ' ').toLocaleLowerCase('ru-RU');
}

function isToCheckStatus(value: string | null | undefined): boolean {
  const normalized = normalizeStatus(value);
  return normalized === normalizeStatus(ORDER_STATUS_TO_CHECK) || normalized === 'в прверку';
}
