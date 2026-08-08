export function contractorCoverageStartLabel(trackingStartedAt?: string | null): string {
  const isoDate = trackingStartedAt?.slice(0, 10);
  const parts = isoDate?.split('-').map(Number) ?? [];
  if (parts.length !== 3 || parts.some((part) => !Number.isInteger(part))) {
    return 'даты подключения';
  }
  const [year, month, day] = parts;
  const date = new Date(Date.UTC(year, month - 1, day));
  if (
    Number.isNaN(date.getTime())
    || date.getUTCFullYear() !== year
    || date.getUTCMonth() !== month - 1
    || date.getUTCDate() !== day
  ) {
    return 'даты подключения';
  }
  return new Intl.DateTimeFormat('ru-RU', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC'
  }).format(date);
}

export function shouldShowLegacyCabinetMetrics(
  showContractorPayments: boolean,
  contractorPaymentsError: string | null,
  payments: ReadonlyArray<{ currentMonthCoverageComplete: boolean }>
): boolean {
  return !showContractorPayments
    || contractorPaymentsError !== null
    || payments.length === 0
    || payments.some((payment) => !payment.currentMonthCoverageComplete);
}

export function contractorPaymentModeLabel(summary: ContractorPaymentModeSummary): string {
  if (!summary.profileEnabled) {
    return 'Профиль отключён · реквизиты не участвуют';
  }
  if (!summary.liveEnabled) {
    return 'Реквизиты не участвуют в новых счетах';
  }
  if (summary.liveRouting) {
    return 'Реквизиты участвуют в новых счетах';
  }
  if (summary.reportingLive) {
    return 'Фактический учёт · новые маршруты остановлены';
  }
  if (summary.shadowMode) {
    return 'Тестовый расчёт';
  }
  return 'Маршрутизация отключена';
}

export function contractorPaymentModeClass(
  summary: ContractorPaymentModeSummary
): 'live' | 'shadow' | 'disabled' {
  if (!summary.profileEnabled || !summary.liveEnabled) {
    return 'disabled';
  }
  if (summary.liveRouting || summary.reportingLive) {
    return 'live';
  }
  return summary.shadowMode ? 'shadow' : 'disabled';
}
import type { ContractorPaymentSummary } from '../../core/contractor-payments.api';

type ContractorPaymentModeSummary = Pick<
  ContractorPaymentSummary,
  'profileEnabled' | 'liveEnabled' | 'liveRouting' | 'reportingLive' | 'shadowMode'
>;
