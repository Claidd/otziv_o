import { describe, expect, it } from 'vitest';
import type { ContractorPaymentSummary } from '../../core/contractor-payments.api';
import {
  contractorCoverageStartLabel,
  contractorPaymentModeClass,
  contractorPaymentModeLabel,
  shouldShowLegacyCabinetMetrics
} from './contractor-payment-coverage';

describe('contractor payment coverage', () => {
  it('keeps explicit partial-month DTO metadata and formats the connection date', () => {
    const coverage: Pick<
      ContractorPaymentSummary,
      'trackingStartedAt' | 'currentMonthCoverageComplete'
    > = {
      trackingStartedAt: '2026-08-07T11:30:00',
      currentMonthCoverageComplete: false
    };

    expect(coverage.currentMonthCoverageComplete).toBe(false);
    expect(contractorCoverageStartLabel(coverage.trackingStartedAt)).toContain('7 августа 2026');
  });

  it('does not invent a date when legacy data has no valid coverage timestamp', () => {
    expect(contractorCoverageStartLabel('')).toBe('даты подключения');
    expect(contractorCoverageStartLabel('2026-99-99T00:00:00')).toBe('даты подключения');
  });

  it('keeps legacy metrics for an API error, missing profiles, or partial coverage', () => {
    expect(shouldShowLegacyCabinetMetrics(true, 'Ошибка', [
      { currentMonthCoverageComplete: true }
    ])).toBe(true);
    expect(shouldShowLegacyCabinetMetrics(true, null, [])).toBe(true);
    expect(shouldShowLegacyCabinetMetrics(true, null, [
      { currentMonthCoverageComplete: true },
      { currentMonthCoverageComplete: false }
    ])).toBe(true);
  });

  it('hides legacy metrics only when every returned profile covers the full month', () => {
    expect(shouldShowLegacyCabinetMetrics(true, null, [
      { currentMonthCoverageComplete: true },
      { currentMonthCoverageComplete: true }
    ])).toBe(false);
    expect(shouldShowLegacyCabinetMetrics(false, null, [
      { currentMonthCoverageComplete: true }
    ])).toBe(true);
  });

  it('shows that personal requisites are excluded even when global routing is live', () => {
    const state = {
      profileEnabled: true,
      liveEnabled: false,
      liveRouting: true,
      reportingLive: true,
      shadowMode: false
    };

    expect(contractorPaymentModeLabel(state)).toBe('Реквизиты не участвуют в новых счетах');
    expect(contractorPaymentModeClass(state)).toBe('disabled');
  });

  it('shows effective participation only when both personal and global routing are enabled', () => {
    const state = {
      profileEnabled: true,
      liveEnabled: true,
      liveRouting: true,
      reportingLive: true,
      shadowMode: false
    };

    expect(contractorPaymentModeLabel(state)).toBe('Реквизиты участвуют в новых счетах');
    expect(contractorPaymentModeClass(state)).toBe('live');
  });
});
