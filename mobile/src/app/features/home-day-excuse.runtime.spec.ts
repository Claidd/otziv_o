import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { ToastController } from '@ionic/angular';
import { of, throwError } from 'rxjs';
import type { TeamMember, TeamResponse } from '../core/api.service';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ContractorAccrualHistoryApi } from '../core/contractor-accrual-history.api';
import { DictionariesApi } from '../core/dictionaries.api';
import { ManagerReportReviewAccessService } from '../core/manager-report-review-access.service';
import { ManualPaymentTasksApi } from '../core/manual-payment-tasks.api';
import { WorkerDayResultsApi, type WorkerDayResult } from '../core/worker-day-results.api';
import { businessDateIso } from '../shared/business-date';
import { HomePage } from './home.page';

function previousDay(): string {
  const [year, month, day] = businessDateIso().split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day - 1)).toISOString().slice(0, 10);
}

describe('mobile owner day excuse', () => {
  const date = previousDay();
  const worker = { id: 6, userId: 60, login: 'alfiya', fio: 'Альфия', imageId: 0 } as TeamMember;
  const team = { date, role: 'OWNER', managers: [], marketologs: [], workers: [worker], operators: [] } as unknown as TeamResponse;
  const failed: WorkerDayResult = {
    workerId: 6, date, eligibleCount: 29, completedCount: 28, progressPercent: 97, reached100: false,
    streakDays: 0, excused: false, canExcuse: true, canRevoke: false
  };
  const counted: WorkerDayResult = {
    ...failed, reached100: true, streakDays: 58, excused: true, excuseReason: 'Задача без аккаунта',
    excusedBy: 'hunt', canExcuse: false, canRevoke: true
  };

  let fixture: ComponentFixture<HomePage>;
  let page: HomePage;
  let auth: { hasAnyRealmRole: ReturnType<typeof vi.fn>; isAuthenticated: ReturnType<typeof vi.fn>; hasRealmRole: ReturnType<typeof vi.fn> };
  let getCabinetTeam: ReturnType<typeof vi.fn>;
  let dayApi: { getDayResults: ReturnType<typeof vi.fn>; excuse: ReturnType<typeof vi.fn>; revoke: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    auth = { hasAnyRealmRole: vi.fn(() => true), isAuthenticated: vi.fn(() => true), hasRealmRole: vi.fn(() => false) };
    getCabinetTeam = vi.fn(() => of(team));
    dayApi = {
      getDayResults: vi.fn(() => of({ date, editable: true, workers: [failed] })),
      excuse: vi.fn(() => of({ changed: true, message: 'День засчитан', laterDaysUpdated: 3, day: counted })),
      revoke: vi.fn(() => throwError(() => ({ status: 409, error: { message: 'Итог дня ещё не подведён' } })))
    };
    TestBed.configureTestingModule({ providers: [
      { provide: AuthService, useValue: auth },
      { provide: ApiService, useValue: { getCabinetTeam } },
      { provide: ActivatedRoute, useValue: {} },
      { provide: Router, useValue: {} },
      { provide: ManagerReportReviewAccessService, useValue: {} },
      { provide: ToastController, useValue: {} },
      { provide: ManualPaymentTasksApi, useValue: {} },
      { provide: ContractorAccrualHistoryApi, useValue: {} },
      { provide: DictionariesApi, useValue: {} },
      { provide: WorkerDayResultsApi, useValue: dayApi }
    ] });
    TestBed.overrideComponent(HomePage, { set: { template: '', imports: [] } });
    fixture = TestBed.createComponent(HomePage);
    page = fixture.componentInstance;
    page.activeSection.set('team');
  });

  afterEach(() => fixture.destroy());

  async function settle(): Promise<void> {
    for (let index = 0; index < 10; index++) await Promise.resolve();
  }

  it('loads the final result of a closed day for owners only', async () => {
    page.selectedDate.set(date);
    await page.reload();

    expect(dayApi.getDayResults).toHaveBeenCalledWith(date);
    const day = page.dayResult(worker)!;
    expect(page.dayResultTitle(day)).toBe('Итог дня: цель не выполнена');
    expect(page.dayResultSummary(day)).toBe('28 из 29 · серия 0 дней на 100%');

    auth.hasAnyRealmRole.mockReturnValue(false);
    expect(page.dayResult(worker)).toBeNull();
    page.selectedDate.set(businessDateIso());
    auth.hasAnyRealmRole.mockReturnValue(true);
    expect(page.dayResult(worker)).toBeNull();
  });

  it('requires a reason, sends the decision and refreshes the team', async () => {
    page.selectedDate.set(date);
    await page.reload();
    page.openDayExcuse(worker, page.dayResult(worker)!, 'excuse');

    page.dayExcuseReason.set('  ');
    await page.confirmDayExcuse();
    expect(dayApi.excuse).not.toHaveBeenCalled();
    expect(page.dayExcuseError()).toContain('Укажите причину');

    dayApi.getDayResults.mockReturnValue(of({ date, editable: true, workers: [counted] }));
    page.dayExcuseReason.set(' Задача без аккаунта ');
    await page.confirmDayExcuse();
    await settle();

    expect(dayApi.excuse).toHaveBeenCalledWith(6, date, 'Задача без аккаунта');
    expect(page.dayExcuseDialog()).toBeNull();
    expect(getCabinetTeam).toHaveBeenLastCalledWith(date, expect.objectContaining({ forceRefresh: true }));
    expect(page.dayResultTitle(page.dayResult(worker)!)).toBe('День засчитан владельцем');
  });

  it('keeps the sheet open with the server message when the undo fails', async () => {
    dayApi.getDayResults.mockReturnValue(of({ date, editable: true, workers: [counted] }));
    page.selectedDate.set(date);
    await page.reload();
    page.openDayExcuse(worker, page.dayResult(worker)!, 'revoke');
    page.dayExcuseReason.set('Ошиблись днём');

    await page.confirmDayExcuse();

    expect(dayApi.revoke).toHaveBeenCalledWith(6, date, 'Ошиблись днём');
    expect(page.dayExcuseDialog()).not.toBeNull();
    expect(page.dayExcuseSaving()).toBe(false);
    expect(page.dayExcuseError()).toBe('Итог дня ещё не подведён');
  });
});
