import type { ScoreContractorPaymentSummary } from '../../core/cabinet.api';

export function scoreMonthLabel(date: string): string {
  const match = /^(\d{4})-(\d{2})-\d{2}$/.exec(date || '');
  if (!match) {
    return 'выбранный месяц';
  }

  const year = Number(match[1]);
  const monthIndex = Number(match[2]) - 1;
  if (monthIndex < 0 || monthIndex > 11) {
    return 'выбранный месяц';
  }

  const month = new Intl.DateTimeFormat('ru-RU', {
    month: 'long',
    timeZone: 'UTC'
  }).format(new Date(Date.UTC(year, monthIndex, 1)));
  return `${month} ${year}`;
}

export function scorePaymentsForUser(
  rows: readonly ScoreContractorPaymentSummary[],
  userId: number,
  expectedRole: string | null
): ScoreContractorPaymentSummary[] {
  return rows
    .filter((row) => row.userId === userId)
    .sort((left, right) => {
      const leftExpected = left.role === expectedRole ? 1 : 0;
      const rightExpected = right.role === expectedRole ? 1 : 0;
      return rightExpected - leftExpected || left.profileId - right.profileId;
    });
}

export function scoreOrphanPayments(
  rows: readonly ScoreContractorPaymentSummary[],
  visibleUserIds: readonly number[]
): ScoreContractorPaymentSummary[] {
  const visible = new Set(visibleUserIds);
  return rows
    .filter((row) => !visible.has(row.userId))
    .sort((left, right) =>
      left.fio.localeCompare(right.fio, 'ru')
        || left.userId - right.userId
        || left.profileId - right.profileId
    );
}

export function scoreOutstandingDebtKopecks(row: ScoreContractorPaymentSummary): number {
  if (row.outstandingDebtKopecks != null) {
    return Math.max(0, row.outstandingDebtKopecks);
  }
  return Math.max(0, (row.accruedTotalKopecks || 0) - Math.max(0, row.paidTotalKopecks || 0));
}

export function scoreOutstandingReservedKopecks(row: ScoreContractorPaymentSummary): number {
  if (row.outstandingReservedKopecks != null) {
    return Math.max(0, row.outstandingReservedKopecks);
  }
  return Math.max(0, (row.reservedKopecks || 0) + (row.pendingKopecks || 0));
}
