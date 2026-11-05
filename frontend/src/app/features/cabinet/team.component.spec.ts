import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import type { CabinetApi, TeamMember, TeamResponse } from '../../core/cabinet.api';
import type { WorkerDayResult, WorkerDayResultsApi } from '../../core/worker-day-results.api';
import { businessDateIso } from '../../shared/business-date';
import { TeamComponent } from './team.component';

function dayResultsApiStub(overrides: Partial<WorkerDayResultsApi> = {}): WorkerDayResultsApi {
  return {
    getDayResults: () => of({ date: '', editable: false, workers: [] }),
    excuse: () => throwError(() => new Error('not expected')),
    revoke: () => throwError(() => new Error('not expected')),
    ...overrides
  } as unknown as WorkerDayResultsApi;
}

function previousDay(): string {
  const [year, month, day] = businessDateIso().split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day - 1));
  return date.toISOString().slice(0, 10);
}

describe('TeamComponent monthly accrual display', () => {
  it('uses exact kopecks for every role and keeps old rubles as a fallback', () => {
    const response = {
      role: 'OWNER', managers: [], workers: [], operators: [], marketologs: []
    } as unknown as TeamResponse;
    const api = { getTeam: () => of(response) } as unknown as CabinetApi;
    const component = new TeamComponent(api, dayResultsApiStub());
    const member = {
      id: 1, userId: 1, login: 'user', fio: 'Сотрудник', imageId: 0,
      sum1Month: 0, sum1MonthKopecks: 45
    } as TeamMember;

    try {
      for (const role of ['manager', 'worker', 'marketolog', 'operator'] as const) {
        expect(component.statRows(role, member).find((row) => row.label === 'Начислено')?.value)
          .toBe('0,45 руб.');
      }
      expect(component.statRows('manager', { ...member, sum1Month: 9, sum1MonthKopecks: null })
        .find((row) => row.label === 'Начислено')?.value).toBe('9,00 руб.');
    } finally {
      component.ngOnDestroy();
    }
  });
});

describe('TeamComponent owner day excuse', () => {
  const worker = { id: 6, userId: 60, login: 'alfiya', fio: 'Альфия', imageId: 0 } as TeamMember;

  function failedDay(date: string, overrides: Partial<WorkerDayResult> = {}): WorkerDayResult {
    return {
      workerId: 6, date, eligibleCount: 29, completedCount: 28, progressPercent: 97,
      reached100: false, streakDays: 0, excused: false, canExcuse: true, canRevoke: false,
      ...overrides
    };
  }

  function setup(role: string, dayApi: Partial<WorkerDayResultsApi>) {
    const response = { role, managers: [], workers: [worker], operators: [], marketologs: [] } as unknown as TeamResponse;
    const getTeam = vi.fn(() => of(response));
    const clearTeamCache = vi.fn();
    const api = { getTeam, clearTeamCache } as unknown as CabinetApi;
    const component = new TeamComponent(api, dayResultsApiStub(dayApi));
    return { component, getTeam, clearTeamCache };
  }

  it('shows the final result of a closed day only to owners and admins', () => {
    const date = previousDay();
    const getDayResults = vi.fn(() => of({ date, editable: true, workers: [failedDay(date)] }));
    const owner = setup('OWNER', { getDayResults });
    const manager = setup('MANAGER', { getDayResults });
    try {
      expect(owner.component.dayResult(worker)).toBeNull();
      owner.component.selectDate(date);
      manager.component.selectDate(date);

      expect(getDayResults).toHaveBeenCalledTimes(1);
      expect(getDayResults).toHaveBeenCalledWith(date);
      const day = owner.component.dayResult(worker)!;
      expect(owner.component.dayResultTitle(day)).toBe('Итог дня: цель не выполнена');
      expect(owner.component.dayResultSummary(day)).toBe('28 из 29 · серия 0 дней на 100%');
      expect(manager.component.dayResult(worker)).toBeNull();
    } finally {
      owner.component.ngOnDestroy();
      manager.component.ngOnDestroy();
    }
  });

  it('requires a reason, sends it once and shows the counted day', () => {
    const date = previousDay();
    const counted = failedDay(date, {
      reached100: true, streakDays: 58, excused: true, excuseReason: 'Задача без аккаунта',
      excusedBy: 'hunt', canExcuse: false, canRevoke: true
    });
    const excuse = vi.fn(() => of({ changed: true, message: 'День засчитан', laterDaysUpdated: 3, day: counted }));
    const getDayResults = vi.fn(() => of({ date, editable: true, workers: [failedDay(date)] }));
    const { component, clearTeamCache, getTeam } = setup('OWNER', { getDayResults, excuse });
    try {
      component.selectDate(date);
      component.openDayExcuse(worker, component.dayResult(worker)!, 'excuse');

      component.dayExcuseReason.set('   ');
      component.confirmDayExcuse();
      expect(excuse).not.toHaveBeenCalled();
      expect(component.dayExcuseError()).toContain('Укажите причину');

      getDayResults.mockReturnValue(of({ date, editable: true, workers: [counted] }));
      component.dayExcuseReason.set('  Задача без аккаунта ');
      component.confirmDayExcuse();

      expect(excuse).toHaveBeenCalledTimes(1);
      expect(excuse).toHaveBeenCalledWith(6, date, 'Задача без аккаунта');
      expect(component.dayExcuseDialog()).toBeNull();
      expect(clearTeamCache).toHaveBeenCalled();
      expect(getTeam).toHaveBeenLastCalledWith(date, expect.objectContaining({ forceRefresh: true }));
      const day = component.dayResult(worker)!;
      expect(component.dayResultTitle(day)).toBe('День засчитан владельцем');
      expect(day.canRevoke).toBe(true);
    } finally {
      component.ngOnDestroy();
    }
  });

  it('keeps the dialog open with the server message when the decision fails', () => {
    const date = previousDay();
    const counted = failedDay(date, { reached100: true, excused: true, canExcuse: false, canRevoke: true });
    const revoke = vi.fn(() => throwError(() => ({ status: 409, error: { message: 'Итог дня ещё не подведён' } })));
    const { component } = setup('ADMIN', {
      getDayResults: () => of({ date, editable: true, workers: [counted] }),
      revoke
    });
    try {
      component.selectDate(date);
      component.openDayExcuse(worker, component.dayResult(worker)!, 'revoke');
      component.dayExcuseReason.set('Ошиблись днём');
      component.confirmDayExcuse();

      expect(revoke).toHaveBeenCalledWith(6, date, 'Ошиблись днём');
      expect(component.dayExcuseDialog()).not.toBeNull();
      expect(component.dayExcuseSaving()).toBe(false);
      expect(component.dayExcuseError()).toBeTruthy();
    } finally {
      component.ngOnDestroy();
    }
  });
});
