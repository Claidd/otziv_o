import type { ManagerPerformanceScore } from '../core/api.service';

export interface ManagerPerformanceDisplayRow {
  label: string;
  value: string;
}

export interface ManagerPerformanceFactorRow {
  key: string;
  label: string;
  weight: number;
  score: number;
}

export const MANAGER_TEAM_PROGRESS_RULE =
  'Итог фиксируется в 23:59 по задачам, поступившим до 23:00. Работники без задач в этот день не участвуют; задачи последнего часа переходят на следующий день.';

export function managerPerformanceRows(
  performance: ManagerPerformanceScore
): ManagerPerformanceDisplayRow[] {
  return [
    { label: 'Команда 100%', value: managerTeamProgressValue(performance) },
    { label: 'Средний прогресс', value: formatPercent(performance.teamProgressAveragePercent) },
    { label: 'SLA проблем', value: formatPercent(performance.problemSlaRate) },
    { label: 'SLA клиентов', value: formatPercent(performance.clientSlaRate) },
    { label: 'Просрочки', value: formatPercent(performance.overdueRate) },
    { label: 'Нагрузка', value: `${formatNumber(performance.workloadOrder)} / ${formatNumber(performance.workloadWorker)}` },
    {
      label: 'Ответ p50/p90',
      value: `${formatNumber(performance.clientReplyMedianMinutes)} / ${formatNumber(performance.clientReplyP90Minutes)} мин.`
    },
    { label: 'Бэклог', value: formatNumber(performance.backlogCount) }
  ];
}

export function managerPerformanceFactorRows(
  performance: ManagerPerformanceScore
): ManagerPerformanceFactorRow[] {
  return [
    { key: 'team', label: 'Команда 100%', weight: 15, score: safeScore(performance.teamCompletionScore) },
    { key: 'problems', label: 'Проблемы', weight: 17, score: safeScore(performance.problemSpeedScore) },
    { key: 'clients', label: 'Клиенты', weight: 21, score: safeScore(performance.clientResponseScore) },
    { key: 'overdue', label: 'Просрочки', weight: 21, score: safeScore(performance.overdueControlScore) },
    { key: 'specialists', label: 'Спец. и риски', weight: 13, score: safeScore(performance.specialistRiskScore) },
    { key: 'control', label: 'Контроль', weight: 9, score: safeScore(performance.controlDisciplineScore) },
    { key: 'stability', label: 'Стабильность', weight: 4, score: safeScore(performance.stabilityScore) }
  ];
}

export function managerTeamProgressValue(performance: ManagerPerformanceScore): string {
  const eligibleDays = safeCount(performance.teamProgressEligibleDays);
  if (eligibleDays <= 0) {
    return 'Нет данных';
  }
  return `${safeCount(performance.teamProgressReached100Days)}/${eligibleDays} дн. · ${formatPercent(performance.teamProgressReached100Rate)}`;
}

export function managerTeamProgressDetails(performance: ManagerPerformanceScore): string {
  const eligibleDays = safeCount(performance.teamProgressEligibleDays);
  if (eligibleDays <= 0) {
    return 'Завершённых командных дней с рабочими задачами пока нет.';
  }
  return `Закрыто дней: ${safeCount(performance.teamProgressReached100Days)} из ${eligibleDays}. Средний прогресс: ${formatPercent(performance.teamProgressAveragePercent)}. Незакрытых сотруднико-дней: ${safeCount(performance.teamProgressMissedWorkerDays)}.`;
}

export function managerPerformanceCompact(performance: ManagerPerformanceScore): string {
  const parts = [
    `SLA проблем ${formatPercent(performance.problemSlaRate)}`,
    `SLA клиентов ${formatPercent(performance.clientSlaRate)}`
  ];
  if (safeCount(performance.teamProgressEligibleDays) > 0) {
    parts.push(`Команда ${formatPercent(performance.teamProgressReached100Rate)}`);
  }
  return parts.join(' · ');
}

function safeScore(value?: number | null): number {
  return Math.max(0, Math.min(100, Math.round(finite(value))));
}

function safeCount(value?: number | null): number {
  return Math.max(0, Math.round(finite(value)));
}

function formatPercent(value?: number | null): string {
  return `${formatNumber(value)}%`;
}

function formatNumber(value?: number | null): string {
  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 }).format(finite(value));
}

function finite(value?: number | null): number {
  return Number.isFinite(value) ? Number(value) : 0;
}
