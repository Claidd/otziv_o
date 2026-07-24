import type { TeamMember, TeamPatternInsight, WorkerPatternAnalysis } from '../../core/cabinet.api';
import type { DailyWorkProgress } from '../../core/daily-progress';

export type TeamProgressMode = 'day' | 'month';
export type WorkerSortKey = 'default' | 'efficiency' | 'activeTime' | 'botBlocks' | 'networkViolations' | 'recoveries';
export type WorkerSortDirection = 'asc' | 'desc';

export type WorkerPatternSignal = {
  insight: TeamPatternInsight;
  metricLabel: string;
  value: number | null;
  valueSuffix: string;
  fallbackValue: string;
  eventCount: number;
  eventLabel: string;
  sortScore: number;
};

export type WorkerSortOption = {
  key: WorkerSortKey;
  label: string;
  shortLabel: string;
  icon: string;
  hint: string;
};

export const WORKER_SORT_OPTIONS: readonly WorkerSortOption[] = [
  { key: 'default', label: 'По умолчанию', shortLabel: 'По умолчанию', icon: 'drag_handle', hint: 'Порядок сотрудников из отчёта.' },
  { key: 'efficiency', label: 'По эффективности', shortLabel: 'Эффективность', icon: 'speed', hint: 'Итоговая оценка эффективности за выбранный период.' },
  { key: 'activeTime', label: 'По активному времени', shortLabel: 'Активное время', icon: 'schedule', hint: 'Примерное активное время по действиям в системе.' },
  { key: 'botBlocks', label: 'По блокировкам аккаунтов', shortLabel: 'Блокировки', icon: 'block', hint: 'За день — по количеству, за месяц — по отношению к публикациям.' },
  { key: 'networkViolations', label: 'По нарушениям сети', shortLabel: 'Нарушения сети', icon: 'wifi_off', hint: 'Количество эпизодов нарушений сети.' },
  { key: 'recoveries', label: 'По восстановлениям', shortLabel: 'Восстановления', icon: 'restore', hint: 'За день — по количеству, за месяц — по отношению к публикациям.' }
];

export function memberProgressForMode(member: TeamMember, mode: TeamProgressMode): DailyWorkProgress | null | undefined {
  return mode === 'month' ? member.monthlyProgress : member.dailyProgress;
}

export function publicationRate(count: number | null | undefined, publications: number | null | undefined): number | null {
  const numerator = finiteNonNegative(count);
  const denominator = finiteNonNegative(publications);
  if (denominator <= 0) {
    return null;
  }
  return Math.round((numerator / denominator) * 1_000) / 10;
}

export function sortWorkerMembers(
  members: readonly TeamMember[],
  key: WorkerSortKey,
  direction: WorkerSortDirection,
  mode: TeamProgressMode
): TeamMember[] {
  if (key === 'default') {
    return [...members];
  }

  const multiplier = direction === 'asc' ? 1 : -1;
  return members.map((member, index) => ({ member, index })).sort((left, right) => {
    const leftValue = workerSortValue(left.member, key, mode);
    const rightValue = workerSortValue(right.member, key, mode);
    const leftMissing = Number.isNaN(leftValue);
    const rightMissing = Number.isNaN(rightValue);
    if (leftMissing !== rightMissing) {
      return leftMissing ? 1 : -1;
    }
    const difference = leftValue - rightValue;
    if (difference !== 0) {
      return difference * multiplier;
    }
    const byName = workerName(left.member).localeCompare(workerName(right.member), 'ru');
    return byName || left.index - right.index;
  }).map(({ member }) => member);
}

export function workerSortValue(member: TeamMember, key: Exclude<WorkerSortKey, 'default'>, mode: TeamProgressMode): number {
  const progress = memberProgressForMode(member, mode);
  switch (key) {
    case 'efficiency':
      return finiteNonNegative(progress?.efficiencyScore || progress?.percent);
    case 'activeTime':
      return finiteNonNegative(progress?.activeWorkSeconds);
    case 'botBlocks':
      return mode === 'month'
        ? publicationRate(progress?.botBlockCount, progress?.publishCompletedCount) ?? Number.NaN
        : finiteNonNegative(progress?.botBlockCount);
    case 'networkViolations':
      return finiteNonNegative(mode === 'month'
        ? member.monthlyNetworkViolations?.episodeCount
        : member.dailyNetworkViolations?.episodeCount);
    case 'recoveries':
      return mode === 'month'
        ? publicationRate(progress?.recoveryCreatedCount, progress?.publishCompletedCount) ?? Number.NaN
        : finiteNonNegative(progress?.recoveryCreatedCount);
  }
}

export function primaryWorkerPatternSignal(pattern: WorkerPatternAnalysis | null | undefined): WorkerPatternSignal | null {
  const warnings = pattern?.insights.filter((candidate) => candidate.tone === 'WARNING') ?? [];
  const personalPatterns = pattern?.insights.filter((candidate) =>
    candidate.code === 'WORKER_NETWORK_BLOCK_PATTERN'
      || candidate.code === 'WORKER_NETWORK_RECOVERY_PATTERN'
  ) ?? [];
  const insight = personalPatterns.find((candidate) => candidate.tone === 'WARNING')
    ?? warnings[0]
    ?? personalPatterns.find((candidate) => candidate.tone === 'INFO')
    ?? personalPatterns[0];
  if (!pattern || !insight) {
    return null;
  }

  switch (insight.code) {
    case 'WORKER_BLOCK_RATE_HIGH':
      return rateSignal(
        insight,
        'Блокировки',
        pattern.blockRate,
        pattern.teamMedianBlockRate,
        pattern.blockedAccountCount,
        'блокировок'
      );
    case 'WORKER_NETWORK_RATE_HIGH':
      return rateSignal(
        insight,
        'Нарушения сети',
        pattern.networkRate,
        pattern.teamMedianNetworkRate,
        pattern.networkEpisodeCount,
        'сетевых эпизодов'
      );
    case 'WORKER_RECOVERY_RATE_HIGH':
      return rateSignal(
        insight,
        'Восстановления',
        pattern.recoveryRate,
        pattern.teamMedianRecoveryRate,
        pattern.recoveryCount,
        'задач восстановления'
      );
    case 'WORKER_NETWORK_BLOCK_PATTERN':
      return patternSignal(
        insight,
        'Сеть → блокировки',
        pattern.blockedAccountCount,
        'блокировок',
        3
      );
    case 'WORKER_NETWORK_RECOVERY_PATTERN':
      return patternSignal(
        insight,
        'Сеть → восстановления',
        pattern.recoveryCount,
        'задач восстановления',
        2
      );
    case 'WORKER_TEMPORAL_LINK':
      return {
        insight,
        metricLabel: 'Временная связь',
        value: null,
        valueSuffix: '',
        fallbackValue: 'По дням',
        eventCount: finiteNonNegative(pattern.networkEpisodeCount),
        eventLabel: 'сетевых эпизодов',
        sortScore: -1
      };
    default:
      return {
        insight,
        metricLabel: insight.title,
        value: null,
        valueSuffix: '',
        fallbackValue: 'Сигнал',
        eventCount: 0,
        eventLabel: 'событий',
        sortScore: -2
      };
  }
}

function patternSignal(
  insight: TeamPatternInsight,
  metricLabel: string,
  eventCount: number,
  eventLabel: string,
  priority: number
): WorkerPatternSignal {
  const warning = insight.tone === 'WARNING';
  const insufficient = insight.confidence === 'INSUFFICIENT';
  return {
    insight,
    metricLabel,
    value: null,
    valueSuffix: '',
    fallbackValue: warning ? 'Связь' : insufficient ? 'Мало данных' : 'Не выявлена',
    eventCount: finiteNonNegative(eventCount),
    eventLabel,
    sortScore: warning ? priority : insufficient ? -20 - priority : -10 - priority
  };
}

function rateSignal(
  insight: TeamPatternInsight,
  metricLabel: string,
  value: number,
  median: number,
  eventCount: number,
  eventLabel: string
): WorkerPatternSignal {
  const safeValue = finiteNonNegative(value);
  const safeMedian = finiteNonNegative(median);
  return {
    insight,
    metricLabel,
    value: safeValue,
    valueSuffix: ' / 100',
    fallbackValue: '',
    eventCount: finiteNonNegative(eventCount),
    eventLabel,
    sortScore: (safeValue - safeMedian) / Math.max(safeMedian, 10)
  };
}

function finiteNonNegative(value: number | null | undefined): number {
  const numeric = Number(value || 0);
  return Number.isFinite(numeric) ? Math.max(0, numeric) : 0;
}

function workerName(member: TeamMember): string {
  return (member.fio || member.login || '').trim();
}
