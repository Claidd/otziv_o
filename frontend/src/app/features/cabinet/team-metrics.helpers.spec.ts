import { primaryWorkerPatternSignal, publicationRate, sortWorkerMembers, workerSortValue } from './team-metrics.helpers';

describe('team metrics helpers', () => {
  const workers = [
    {
      id: 1,
      userId: 1,
      login: 'anna',
      fio: 'Анна',
      imageId: 1,
      monthlyProgress: {
        visible: true,
        efficiencyScore: 92,
        activeWorkSeconds: 7_200,
        botBlockCount: 2,
        recoveryCreatedCount: 5,
        publishCompletedCount: 2
      },
      monthlyNetworkViolations: { visible: true, episodeCount: 1 }
    },
    {
      id: 2,
      userId: 2,
      login: 'boris',
      fio: 'Борис',
      imageId: 1,
      monthlyProgress: {
        visible: true,
        efficiencyScore: 73,
        activeWorkSeconds: 10_800,
        botBlockCount: 7,
        recoveryCreatedCount: 1,
        publishCompletedCount: 14
      },
      monthlyNetworkViolations: { visible: true, episodeCount: 4 }
    }
  ] as any;

  it('calculates block and recovery percentages from publications', () => {
    expect(publicationRate(13, 74)).toBe(17.6);
    expect(publicationRate(10, 74)).toBe(13.5);
    expect(publicationRate(0, 74)).toBe(0);
    expect(publicationRate(3, 0)).toBeNull();
  });

  it('sorts workers by selected period metrics in both directions', () => {
    expect(sortWorkerMembers(workers, 'efficiency', 'desc', 'month').map((worker) => worker.login))
      .toEqual(['anna', 'boris']);
    expect(sortWorkerMembers(workers, 'botBlocks', 'desc', 'month').map((worker) => worker.login))
      .toEqual(['anna', 'boris']);
    expect(sortWorkerMembers(workers, 'activeTime', 'asc', 'month').map((worker) => worker.login))
      .toEqual(['anna', 'boris']);
    expect(sortWorkerMembers(workers, 'networkViolations', 'desc', 'month').map((worker) => worker.login))
      .toEqual(['boris', 'anna']);
  });

  it('uses publication rates for monthly block and recovery sorting', () => {
    expect(workerSortValue(workers[0], 'botBlocks', 'month')).toBe(100);
    expect(workerSortValue(workers[1], 'botBlocks', 'month')).toBe(50);
    expect(workerSortValue(workers[0], 'recoveries', 'month')).toBe(250);
    expect(workerSortValue(workers[1], 'recoveries', 'month')).toBe(7.1);
  });

  it('keeps absolute counts for daily block and recovery sorting', () => {
    expect(workerSortValue(workers[0], 'botBlocks', 'day')).toBe(0);
    expect(workerSortValue(workers[0], 'recoveries', 'day')).toBe(0);
  });

  it('keeps workers without publications at the end in either monthly direction', () => {
    const withoutPublications = {
      ...workers[0],
      id: 3,
      login: 'none',
      fio: 'Нет публикаций',
      monthlyProgress: { ...workers[0].monthlyProgress, publishCompletedCount: 0 }
    } as any;

    expect(sortWorkerMembers([...workers, withoutPublications], 'botBlocks', 'desc', 'month').at(-1)?.login)
      .toBe('none');
    expect(sortWorkerMembers([...workers, withoutPublications], 'botBlocks', 'asc', 'month').at(-1)?.login)
      .toBe('none');
  });

  it('shows the metric that actually produced the worker warning', () => {
    const blockSignal = primaryWorkerPatternSignal({
      blockRate: 75,
      teamMedianBlockRate: 50,
      networkRate: 2,
      teamMedianNetworkRate: 2,
      insights: [{ code: 'WORKER_BLOCK_RATE_HIGH', tone: 'WARNING', confidence: 'MODERATE', title: 'Блокировки', message: '' }]
    } as any);
    const networkSignal = primaryWorkerPatternSignal({
      blockRate: 45.7,
      teamMedianBlockRate: 50.8,
      networkRate: 24.5,
      teamMedianNetworkRate: 11.8,
      insights: [{ code: 'WORKER_NETWORK_RATE_HIGH', tone: 'WARNING', confidence: 'MODERATE', title: 'Сеть', message: '' }]
    } as any);

    expect(blockSignal).toEqual(expect.objectContaining({
      metricLabel: 'Блокировки', value: 75, valueSuffix: ' / 100', eventLabel: 'блокировок'
    }));
    expect(networkSignal).toEqual(expect.objectContaining({
      metricLabel: 'Нарушения сети', value: 24.5, valueSuffix: ' / 100', eventLabel: 'сетевых эпизодов'
    }));
    expect(networkSignal!.sortScore).toBeGreaterThan(0);
  });

  it('prioritizes a personal temporal pattern over a simple elevated rate', () => {
    const signal = primaryWorkerPatternSignal({
      blockedAccountCount: 7,
      recoveryCount: 3,
      networkEpisodeCount: 12,
      blockRate: 20,
      recoveryRate: 8,
      networkRate: 30,
      teamMedianBlockRate: 10,
      teamMedianRecoveryRate: 4,
      teamMedianNetworkRate: 10,
      insights: [
        { code: 'WORKER_NETWORK_RATE_HIGH', tone: 'WARNING', confidence: 'LIMITED', title: 'Сеть', message: '' },
        { code: 'WORKER_NETWORK_BLOCK_PATTERN', tone: 'WARNING', confidence: 'LIMITED', title: 'Связь', message: '' }
      ]
    } as any);

    expect(signal).toEqual(expect.objectContaining({
      metricLabel: 'Сеть → блокировки',
      fallbackValue: 'Связь',
      eventCount: 7,
      eventLabel: 'блокировок'
    }));
  });

  it('shows when a personal comparison does not have enough data yet', () => {
    const signal = primaryWorkerPatternSignal({
      blockedAccountCount: 1,
      recoveryCount: 0,
      networkEpisodeCount: 2,
      insights: [{
        code: 'WORKER_NETWORK_BLOCK_PATTERN',
        tone: 'NEUTRAL',
        confidence: 'INSUFFICIENT',
        title: 'Нарушения сети и блокировки',
        message: 'Недостаточно данных'
      }]
    } as any);

    expect(signal).toEqual(expect.objectContaining({
      metricLabel: 'Сеть → блокировки',
      fallbackValue: 'Мало данных'
    }));
  });
});
