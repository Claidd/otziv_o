import type { ContractorPaymentSummary } from '../core/api.service';

export type ContractorPaymentModeClass = 'live' | 'shadow' | 'disabled';
export type ContractorPaymentMetricTone = 'default' | 'available' | 'credit';

export interface ContractorPaymentMetric {
  key: string;
  label: string;
  description: string;
  totalKopecks: number;
  monthKopecks?: number;
  tone: ContractorPaymentMetricTone;
}

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

export function contractorPaymentModeLabel(summary: ContractorPaymentSummary): string {
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

export function contractorPaymentModeClass(summary: ContractorPaymentSummary): ContractorPaymentModeClass {
  if (!summary.profileEnabled || !summary.liveEnabled) {
    return 'disabled';
  }
  if (summary.liveRouting || summary.reportingLive) {
    return 'live';
  }
  return summary.shadowMode ? 'shadow' : 'disabled';
}

export function contractorPaymentMetricDescriptionId(profileId: number, metricKey: string): string {
  return `mobile-contractor-payment-help-${profileId}-${metricKey}`;
}

export function shouldShowLegacyContractorMetrics(
  showContractorPayments: boolean,
  contractorPaymentsError: string | null,
  payments: ReadonlyArray<{ currentMonthCoverageComplete: boolean }>
): boolean {
  return !showContractorPayments
    || contractorPaymentsError !== null
    || payments.length === 0
    || payments.some((payment) => !payment.currentMonthCoverageComplete);
}

export function contractorPaymentMetrics(summary: ContractorPaymentSummary): ContractorPaymentMetric[] {
  const reservedTotal = Math.max(0,
    (summary.reservedKopecks ?? 0)
      + (summary.clientReportedKopecks ?? 0)
      + (summary.partiallyConfirmedOutstandingKopecks ?? 0)
  );

  return [
    {
      key: 'accrued',
      label: 'Начислено',
      description: 'Сколько вознаграждения начислено по вашим заказам плюс переходящий неоплаченный остаток, если он был задан. Ниже отдельно показано начисление за текущий месяц.',
      totalKopecks: summary.accruedTotalKopecks,
      monthKopecks: summary.accruedMonthKopecks,
      tone: 'default'
    },
    {
      key: 'reserved',
      label: 'Зарезервировано',
      description: 'Суммы, уже закреплённые за активными счетами. Включает обычный резерв, оплаты, отмеченные менеджером, и остатки частично оплаченных счетов.',
      totalKopecks: reservedTotal,
      tone: 'default'
    },
    {
      key: 'paid',
      label: 'Оплачено',
      description: summary.reportingLive
        ? 'Реально подтверждённые поступления за вычетом возвратов. Ниже отдельно показана сумма за текущий месяц.'
        : 'Тестово учтённые поступления за вычетом тестово учтённых возвратов. Это не сумма подтверждённых реальных переводов.',
      totalKopecks: summary.netReceivedTotalKopecks,
      monthKopecks: summary.netReceivedMonthKopecks,
      tone: 'default'
    },
    {
      key: 'due',
      label: 'Ожидает / к доплате',
      description: 'Начислено минус оплачено и минус уже зарезервированные суммы. Это остаток, который ещё нужно покрыть новыми счетами.',
      totalKopecks: summary.availableKopecks,
      tone: 'available'
    }
  ];
}
