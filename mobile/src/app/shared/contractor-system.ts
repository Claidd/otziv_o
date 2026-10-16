import type { ContractorPaymentSystemStatus } from '../core/contractor-system.api';

export const ACTIVATION_CONFIRMATION = 'ВКЛЮЧИТЬ НОВУЮ СИСТЕМУ';
export const ROUTING_ENABLE_CONFIRMATION = 'ВКЛЮЧИТЬ РЕКВИЗИТЫ';
export const ROUTING_PAUSE_CONFIRMATION = 'ПРИОСТАНОВИТЬ РЕКВИЗИТЫ';

export function contractorBusinessDate(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Irkutsk', year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(now);
  const value = (type: string) => parts.find((part) => part.type === type)?.value ?? '';
  return `${value('year')}-${value('month')}-${value('day')}`;
}

export function contractorSystemModeLabel(mode: ContractorPaymentSystemStatus['mode']): string {
  return ({
    LEGACY: 'Старый режим', COMPLETION_ACTIVE: 'Новый учёт активен', ROUTING_LIVE: 'Реквизиты активны',
    ROUTING_PAUSED: 'Реквизиты приостановлены', CONFIGURATION_ERROR: 'Ошибка конфигурации'
  })[mode];
}

export function canActivateContractorSystem(status: ContractorPaymentSystemStatus | null, owner: boolean): boolean {
  return owner && !!status && status.mode === 'LEGACY' && !status.systemEnabled
    && !status.irreversible && status.activationAvailable;
}

export function canChangeContractorRouting(status: ContractorPaymentSystemStatus | null, owner: boolean): boolean {
  return owner && !!status && status.systemEnabled && !status.legacyBehavior;
}

export function contractorSystemReason(value: string): string | null {
  const reason = value.trim();
  return reason.length > 0 && reason.length <= 500 ? reason : null;
}
