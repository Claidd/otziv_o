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
  const metrics: ContractorPaymentMetric[] = [
    {
      key: 'accrued',
      label: 'Начислено всего',
      description: 'Все начисленные вознаграждения плюс переходящий неоплаченный остаток, если он был задан. Ниже отдельно показано начисление за текущий месяц.',
      totalKopecks: summary.accruedTotalKopecks,
      monthKopecks: summary.accruedMonthKopecks,
      tone: 'default'
    },
    {
      key: 'reserved',
      label: 'Зарезервировано под счета',
      description: 'Сумма счетов, которым уже назначены ваши реквизиты, но клиент ещё не сообщил об оплате. Она временно уменьшает остаток для следующих счетов.',
      totalKopecks: summary.reservedKopecks,
      tone: 'default'
    },
    {
      key: 'client-reported',
      label: 'Клиент нажал «Оплатил»',
      description: 'Сумма счетов, по которым клиент сообщил об оплате. Поступление денег ещё не подтверждено по банку, поэтому сумма остаётся занятой.',
      totalKopecks: summary.clientReportedKopecks,
      tone: 'default'
    },
    {
      key: 'partially-confirmed',
      label: 'Остаток по частично оплаченным счетам',
      description: 'Часть суммы уже подтверждена как поступившая. Здесь показана оставшаяся неподтверждённая часть; она остаётся занятой до доплаты или закрытия.',
      totalKopecks: summary.partiallyConfirmedOutstandingKopecks,
      tone: 'default'
    },
    {
      key: 'confirmed',
      label: summary.reportingLive ? 'Подтверждённые поступления' : 'Тестово учтённые поступления',
      description: summary.reportingLive
        ? 'Сумма поступлений, подтверждённых сверкой, до вычета возвратов. Ниже отдельно показаны поступления за текущий месяц.'
        : 'Сумма, которую система в тестовом режиме засчитала бы как поступившую. Это не означает, что деньги фактически пришли.',
      totalKopecks: summary.grossConfirmedTotalKopecks,
      monthKopecks: summary.grossConfirmedMonthKopecks,
      tone: 'default'
    },
    {
      key: 'returned',
      label: summary.reportingLive ? 'Возвращено клиентам' : 'Тестово учтено возвратов',
      description: summary.reportingLive
        ? 'Подтверждённые возвраты клиентам. Они вычитаются из итоговой суммы поступлений.'
        : 'Расчётная сумма возвратов в тестовом режиме. Она уменьшает тестовый итог и не подтверждает фактический возврат денег.',
      totalKopecks: summary.returnedTotalKopecks,
      monthKopecks: summary.returnedMonthKopecks,
      tone: 'default'
    },
    {
      key: 'closed-unpaid',
      label: summary.reportingLive ? 'Снято с резерва без оплаты' : 'Тестово снято с резерва без оплаты',
      description: summary.reportingLive
        ? 'Неоплаченный остаток отменённых, истёкших или отмеченных неоплаченными счетов. Резерв уже освобождён и повторно из остатка не вычитается.'
        : 'Расчётная сумма отменённых, истёкших или отмеченных неоплаченными счетов в тестовом режиме. Тестовый резерв уже освобождён и повторно из остатка не вычитается.',
      totalKopecks: summary.closedWithoutPaymentTotalKopecks,
      monthKopecks: summary.closedWithoutPaymentMonthKopecks,
      tone: 'default'
    },
    {
      key: 'net-received',
      label: summary.reportingLive ? 'Фактически получено после возвратов' : 'Тестовый итог после возвратов',
      description: summary.reportingLive
        ? 'Все подтверждённые поступления за вычетом подтверждённых возвратов клиентам. Ниже отдельно показан итог за текущий месяц.'
        : 'Тестово учтённые поступления за вычетом тестово учтённых возвратов. Это не сумма подтверждённых реальных переводов.',
      totalKopecks: summary.netReceivedTotalKopecks,
      monthKopecks: summary.netReceivedMonthKopecks,
      tone: 'default'
    }
  ];

  if (summary.creditKopecks > 0) {
    metrics.push({
      key: 'credit',
      label: summary.reportingLive ? 'Аванс сверх начисленного' : 'Тестовый аванс сверх начисленного',
      description: summary.reportingLive
        ? 'Подтверждено поступлений больше, чем начислено. Разница будет учитываться при дальнейших начислениях.'
        : 'В тестовом расчёте учтено поступлений больше, чем начислено. Это не подтверждает фактический аванс.',
      totalKopecks: summary.creditKopecks,
      tone: 'credit'
    });
  } else {
    metrics.push({
      key: 'available',
      label: 'Осталось покрыть новыми счетами',
      description: 'Начислено минус итог после возвратов и минус суммы, уже занятые счетами. Если использование реквизитов выключено, это только расчётный остаток: новые счета на ваши реквизиты не направляются.',
      totalKopecks: summary.availableKopecks,
      tone: 'available'
    });
  }

  if (summary.exposureOverrunKopecks > 0) {
    metrics.push({
      key: 'exposure-overrun',
      label: 'Нужна сверка: учтено больше начисленного',
      description: summary.reportingLive
        ? 'Подтверждённые поступления и суммы активных счетов вместе превышают начисленное. Новые счета на превышение не назначаются; данные нужно проверить.'
        : 'Тестовый итог и суммы активных тестовых резервов вместе превышают начисленное. Это расчётное предупреждение; данные нужно проверить перед включением.',
      totalKopecks: summary.exposureOverrunKopecks,
      tone: 'credit'
    });
  }

  return metrics;
}
