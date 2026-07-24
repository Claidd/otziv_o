import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  MANAGER_TEAM_PROGRESS_RULE,
  managerPerformanceFactorRows,
  managerPerformanceRows,
  managerTeamProgressDetails,
  managerTeamProgressValue
} = loadTsModule('src/app/features/manager-performance.helpers.ts');

const performance = {
  teamProgressEligibleDays: 10,
  teamProgressReached100Days: 9,
  teamProgressIncompleteDays: 1,
  teamProgressReached100Rate: 90,
  teamProgressAveragePercent: 99,
  teamProgressMissedWorkerDays: 1,
  teamCompletionScore: 93,
  problemSlaRate: 80,
  clientSlaRate: 70,
  overdueRate: 5,
  workloadOrder: 40,
  workloadWorker: 20,
  clientReplyMedianMinutes: 12,
  clientReplyP90Minutes: 30,
  backlogCount: 3,
  problemSpeedScore: 75,
  clientResponseScore: 70,
  overdueControlScore: 85,
  specialistRiskScore: 90,
  controlDisciplineScore: 80,
  stabilityScore: 95
};

test('shows team progress in manager rating rows', () => {
  assert.equal(managerTeamProgressValue(performance), '9/10 дн. · 90%');
  assert.equal(managerPerformanceRows(performance)[0].label, 'Команда 100%');
  assert.match(managerTeamProgressDetails(performance), /Незакрытых сотруднико-дней: 1/);
});

test('keeps mobile rating weights aligned with backend formula', () => {
  const factors = managerPerformanceFactorRows(performance);
  assert.equal(factors.reduce((sum, factor) => sum + factor.weight, 0), 100);
  assert.deepEqual(
    Array.from(factors, (factor) => factor.weight),
    [15, 17, 21, 21, 13, 9, 4]
  );
  assert.equal(factors[0].score, 93);
});

test('explains cutoff and exclusion of workers without tasks', () => {
  assert.match(MANAGER_TEAM_PROGRESS_RULE, /до 23:00/);
  assert.match(MANAGER_TEAM_PROGRESS_RULE, /Работники без задач.*не участвуют/);
  assert.equal(
    managerTeamProgressValue({ ...performance, teamProgressEligibleDays: 0 }),
    'Нет данных'
  );
});
