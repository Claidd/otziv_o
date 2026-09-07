import { AdminTaxonomyApi } from '../../../core/admin-taxonomy.api';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, Subject } from 'rxjs';
import { AdminDictionariesApi, type AdminClientMessageMonitor } from '../../../core/admin-dictionaries.api';
import { AdminMessageMonitorApi } from '../../../core/admin-message-monitor.api';
import { AdminGamificationRewardsApi } from '../../../core/admin-gamification-rewards.api';
import { AuthService } from '../../../core/auth.service';
import { ContractorPaymentsApi } from '../../../core/contractor-payments.api';
import { OperatorPhonesApi } from '../../../core/operator-phones.api';
import { ReputationAiApi } from '../../../core/reputation-ai.api';
import { ToastService } from '../../../shared/toast.service';
import { AdminDictionariesComponent } from './admin-dictionaries.component';

describe('dictionary monitor component integration', () => {
  afterEach(() => { vi.useRealTimers(); });

  it('loads the narrow API on direct entry, polls after discovery, and cancels on tab leave', () => {
    vi.useFakeTimers();
    const initial = new Subject<AdminClientMessageMonitor>();
    const polling = new Subject<AdminClientMessageMonitor>();
    const monitorApi = {
      getClientMessageMonitor: vi.fn().mockReturnValueOnce(initial).mockReturnValue(polling),
      getClientMessageMaintenancePreview: vi.fn().mockReturnValue(of({}))
    };
    const legacyApi = { getClientMessageMonitor: vi.fn() };
    const taxonomyApi = { getCategories: vi.fn().mockReturnValue(of([])) };
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { snapshot: { data: {}, queryParamMap: convertToParamMap({ tab: 'autoresponderMonitor' }) } } },
      { provide: AdminDictionariesApi, useValue: legacyApi },
      { provide: AdminTaxonomyApi, useValue: taxonomyApi },
      { provide: AdminMessageMonitorApi, useValue: monitorApi },
      { provide: AuthService, useValue: { tokenParsed: () => null, hasAnyRealmRole: () => true, hasRealmRole: () => true } },
      { provide: ContractorPaymentsApi, useValue: {} },
      { provide: OperatorPhonesApi, useValue: {} },
      { provide: ReputationAiApi, useValue: {} },
      { provide: AdminGamificationRewardsApi, useValue: {} },
      { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } }
    ] });
    TestBed.overrideComponent(AdminDictionariesComponent, { set: { template: '', imports: [] } });
    const fixture = TestBed.createComponent(AdminDictionariesComponent);
    const page = fixture.componentInstance;
    expect(monitorApi.getClientMessageMonitor).toHaveBeenCalledTimes(1);
    expect(legacyApi.getClientMessageMonitor).not.toHaveBeenCalled();
    expect(page.activeLoading()).toBe(true);
    initial.next({ enabled: true, activeCandidates: 7, queue: [], attempts: [] } as unknown as AdminClientMessageMonitor);
    initial.complete();
    expect(page.clientMessageMonitor()?.activeCandidates).toBe(7);
    expect(page.activeLoading()).toBe(false);
    vi.advanceTimersByTime(60_000);
    expect(polling.observed).toBe(true);
    page.setTab('categories');
    expect(polling.observed).toBe(false);
    expect(taxonomyApi.getCategories).toHaveBeenCalledOnce();
    vi.advanceTimersByTime(120_000);
    expect(monitorApi.getClientMessageMonitor).toHaveBeenCalledTimes(2);
    fixture.destroy();
  });
});
