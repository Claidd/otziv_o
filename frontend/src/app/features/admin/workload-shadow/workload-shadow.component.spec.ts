import { describe, expect, it } from 'vitest';
import {
  WORKLOAD_SHADOW_RECIPIENT_ELIGIBILITY_BASIS,
  WorkloadShadowSafetyValues,
  workloadShadowEvaluatedDays,
  workloadShadowLastCompletedDayLabel,
  workloadShadowSettingsError
} from './workload-shadow.component';

const validSettings: WorkloadShadowSafetyValues = {
  mode: 'SHADOW',
  applyEnabled: false,
  shiftStart: '10:00',
  shiftEnd: '23:00',
  schedulerIntervalMinutes: 10,
  nearEndIntervalMinutes: 5,
  walkMinimumMinutesPerCard: 3,
  walkMinutesPerCard: 5,
  lookbackDays: 30,
  recipientMaximumFailureDays: 2,
  fourthFailurePercent: 15,
  fifthFailurePercent: 25,
  sixthFailurePercent: 30,
  fourthFailureMaxCompanies: 1,
  fifthFailureMaxCompanies: 2,
  sixthFailureMaxCompanies: 3,
  notificationBatchSize: 10,
  notificationMaxAttempts: 8,
  notificationLeaseMinutes: 5,
  notificationRetryBaseMinutes: 1,
  maintenanceBatchSize: 1000
};

describe('workload shadow settings safety', () => {
  it('accepts a valid observation configuration', () => {
    expect(workloadShadowSettingsError(validSettings)).toBeNull();
  });

  it('rejects live mode and application of calculated decisions', () => {
    expect(workloadShadowSettingsError({ ...validSettings, mode: 'LIVE' }))
      .toContain('Боевой режим недоступен');
    expect(workloadShadowSettingsError({ ...validSettings, applyEnabled: true }))
      .toContain('Боевой режим недоступен');
  });

  it('never permits a walk estimate below three minutes per card', () => {
    expect(workloadShadowSettingsError({
      ...validSettings,
      walkMinimumMinutesPerCard: 2,
      walkMinutesPerCard: 2
    })).toContain('не может быть меньше 3 минут');
  });

  it('requires monotonic transfer percentages and company caps', () => {
    expect(workloadShadowSettingsError({
      ...validSettings,
      fifthFailurePercent: 10
    })).toBe('Процент разгрузки должен возрастать от первого к третьему превышению порога.');
    expect(workloadShadowSettingsError({
      ...validSettings,
      sixthFailureMaxCompanies: 1
    })).toBe('Лимит компаний должен возрастать от первого к третьему превышению порога.');
  });

  it('validates recipient failures against the current month', () => {
    expect(workloadShadowSettingsError({
      ...validSettings,
      lookbackDays: 90,
      recipientMaximumFailureDays: 32
    })).toContain('от 0 до 31');
  });

  it('bounds notification delivery and maintenance batches', () => {
    expect(workloadShadowSettingsError({
      ...validSettings,
      notificationBatchSize: 26
    })).toContain('от 1 до 25');
    expect(workloadShadowSettingsError({
      ...validSettings,
      notificationMaxAttempts: 21
    })).toContain('от 1 до 20');
    expect(workloadShadowSettingsError({
      ...validSettings,
      notificationLeaseMinutes: 31
    })).toContain('от 1 до 30');
    expect(workloadShadowSettingsError({
      ...validSettings,
      notificationRetryBaseMinutes: 61
    })).toContain('от 1 до 60');
    expect(workloadShadowSettingsError({
      ...validSettings,
      maintenanceBatchSize: 99
    })).toContain('от 100 до 5000');
  });
});

describe('workload shadow recipient eligibility wording', () => {
  it('makes finalized day history the eligibility basis, not unfinished current progress', () => {
    expect(WORKLOAD_SHADOW_RECIPIENT_ELIGIBILITY_BASIS)
      .toContain('последнему завершённому дню');
    expect(WORKLOAD_SHADOW_RECIPIENT_ELIGIBILITY_BASIS)
      .toContain('с начала текущего месяца');
    expect(WORKLOAD_SHADOW_RECIPIENT_ELIGIBILITY_BASIS)
      .toContain('Текущий незавершённый процент');
    expect(WORKLOAD_SHADOW_RECIPIENT_ELIGIBILITY_BASIS)
      .toContain('не является причиной исключения');
  });

  it('shows finalized month totals and does not label missing history as a failure', () => {
    expect(workloadShadowEvaluatedDays({
      evaluatedDays: 8,
      hundredPercentDays: 6,
      failureDays: 2
    })).toBe(8);
    expect(workloadShadowEvaluatedDays({
      hundredPercentDays: 6,
      failureDays: 2
    })).toBe(8);
    expect(workloadShadowLastCompletedDayLabel(true)).toBe('100%');
    expect(workloadShadowLastCompletedDayLabel(false)).toBe('не 100%');
    expect(workloadShadowLastCompletedDayLabel(undefined)).toBe('нет завершённых данных');
  });
});
