import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { ToastController } from '@ionic/angular';
import { of } from 'rxjs';
import type { ScoreResponse } from '../core/api.service';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ContractorAccrualHistoryApi } from '../core/contractor-accrual-history.api';
import { DictionariesApi } from '../core/dictionaries.api';
import { ManagerReportReviewAccessService } from '../core/manager-report-review-access.service';
import { ManualPaymentTasksApi } from '../core/manual-payment-tasks.api';
import { HomePage } from './home.page';

const score: ScoreResponse = {
  date: '2026-10-18',
  user: { username: 'admin', role: 'ADMIN', leadCount: 0, reviewCount: 0 },
  financeVisible: true,
  contractorPayments: [
    { profileId: 10, userId: 1, role: 'SPECIALIST', profileEnabled: true },
    { profileId: 11, userId: 1, role: 'MANAGER', profileEnabled: true },
    { profileId: 12, userId: 2, role: 'SPECIALIST', profileEnabled: false },
    { profileId: 13, userId: 3, role: 'SPECIALIST', profileEnabled: true }
  ],
  groups: {
    managers: [{ userId: 1, fio: 'Мария', role: 'ROLE_MANAGER' }],
    workers: [{ userId: 2, fio: 'Елена', role: 'ROLE_WORKER' }],
    operators: [{ userId: 3, fio: 'Оператор', role: 'ROLE_OPERATOR' }],
    marketologs: []
  }
};

describe('mobile rating accrual shortcuts', () => {
  let fixture: ComponentFixture<HomePage>;
  let page: HomePage;
  let auth: { hasAnyRealmRole: ReturnType<typeof vi.fn>; isAuthenticated: ReturnType<typeof vi.fn> };
  let getCabinetScore: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    auth = { hasAnyRealmRole: vi.fn(() => true), isAuthenticated: vi.fn(() => true) };
    getCabinetScore = vi.fn(() => of(score));
    TestBed.configureTestingModule({ providers: [
      { provide: AuthService, useValue: auth },
      { provide: ApiService, useValue: { getCabinetScore } },
      { provide: ActivatedRoute, useValue: {} },
      { provide: Router, useValue: {} },
      { provide: ManagerReportReviewAccessService, useValue: {} },
      { provide: ToastController, useValue: {} },
      { provide: ManualPaymentTasksApi, useValue: {} },
      { provide: ContractorAccrualHistoryApi, useValue: {} },
      { provide: DictionariesApi, useValue: {} }
    ] });
    TestBed.overrideComponent(HomePage, { set: { template: '', imports: [] } });
    fixture = TestBed.createComponent(HomePage);
    page = fixture.componentInstance;
    page.activeSection.set('score');
    page.score.set(score);
  });

  afterEach(() => fixture.destroy());

  it('opens plus and minus on the enabled profile matching the rating role', () => {
    const manager = score.groups.managers[0];
    expect(page.eligibleScoreAccrualProfiles('managers', manager).map((item) => item.profileId)).toEqual([11, 10]);

    page.openScoreAccrual('managers', manager, 'BONUS');
    expect(page.scoreAccrualTarget()).toEqual({
      user: { id: 1, fio: 'Мария', username: 'Мария' }, kind: 'BONUS', profileId: 11
    });
    page.closeScoreAccrual();
    page.openScoreAccrual('managers', manager, 'DEDUCTION');
    expect(page.scoreAccrualTarget()?.kind).toBe('DEDUCTION');
  });

  it('hides controls without active finance permission or an enabled contractor profile', () => {
    expect(page.eligibleScoreAccrualProfiles('workers', score.groups.workers[0])).toEqual([]);
    expect(page.eligibleScoreAccrualProfiles('operators', score.groups.operators[0])).toEqual([]);
    page.openScoreAccrual('workers', score.groups.workers[0], 'BONUS');
    expect(page.scoreAccrualTarget()).toBeNull();

    page.score.set({ ...score, financeVisible: false });
    expect(page.eligibleScoreAccrualProfiles('managers', score.groups.managers[0])).toEqual([]);
    page.score.set(score);
    auth.hasAnyRealmRole.mockReturnValue(false);
    expect(page.eligibleScoreAccrualProfiles('managers', score.groups.managers[0])).toEqual([]);
  });

  it('refreshes the rating immediately after an adjustment is saved', async () => {
    page.onScoreAccrualSaved();
    for (let index = 0; index < 8; index++) await Promise.resolve();
    expect(getCabinetScore).toHaveBeenCalledWith(page.selectedDate(), { forceRefresh: true });
    expect(page.score()).toEqual(score);
  });
});
