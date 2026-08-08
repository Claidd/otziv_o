import type {
  ContractorPaymentSystemMode,
  ContractorPaymentSystemStatus
} from '../../../core/contractor-payments.api';

export const CONTRACTOR_SYSTEM_ACTIVATION_CONFIRMATION = 'ВКЛЮЧИТЬ НОВУЮ СИСТЕМУ';
export const CONTRACTOR_ROUTING_ENABLE_CONFIRMATION = 'ВКЛЮЧИТЬ РЕКВИЗИТЫ';
export const CONTRACTOR_ROUTING_PAUSE_CONFIRMATION = 'ПРИОСТАНОВИТЬ РЕКВИЗИТЫ';

const MODE_LABELS: Record<ContractorPaymentSystemMode, string> = {
  LEGACY: 'Старый режим',
  COMPLETION_ACTIVE: 'Новый учёт активен',
  ROUTING_LIVE: 'Реквизиты активны',
  ROUTING_PAUSED: 'Реквизиты приостановлены',
  CONFIGURATION_ERROR: 'Ошибка конфигурации'
};

export function contractorSystemModeLabel(mode: ContractorPaymentSystemMode): string {
  return MODE_LABELS[mode];
}

export function contractorSystemFirstDayOfMonth(todayIso: string): string {
  const match = /^(\d{4})-(\d{2})-\d{2}$/.exec(todayIso.trim());
  return match ? `${match[1]}-${match[2]}-01` : '';
}

export function contractorRoutingConfirmation(enabled: boolean): string {
  return enabled
    ? CONTRACTOR_ROUTING_ENABLE_CONFIRMATION
    : CONTRACTOR_ROUTING_PAUSE_CONFIRMATION;
}

export function canActivateContractorSystem(
  status: ContractorPaymentSystemStatus | null,
  owner: boolean
): boolean {
  return owner
    && status !== null
    && status.mode === 'LEGACY'
    && !status.systemEnabled
    && !status.irreversible
    && status.activationAvailable;
}

export function canChangeContractorRouting(
  status: ContractorPaymentSystemStatus | null,
  owner: boolean
): boolean {
  return owner
    && status !== null
    && status.systemEnabled
    && !status.legacyBehavior;
}
