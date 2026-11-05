import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type Keycloak from 'keycloak-js';
import { AuthService } from './auth.service';
import { SKIP_AUTH_REDIRECT_ON_401 } from './auth-http-context';
import { ReputationDeepReportMonitorService } from './reputation-deep-report-monitor.service';
import {
  ACTIVE_DEEP_REPORT_COMPANY_KEY,
  ACTIVE_DEEP_REPORT_CONTEXT_KEY
} from './session-client-state';
import { ToastService } from '../shared/toast.service';
import { readSessionDraft, writeSessionDraft } from '../shared/session-draft-storage';

const LATEST_JOB_URL = '/api/ai/reputation/companies/42/deep-research/jobs/latest';

describe('ReputationDeepReportMonitorService', () => {
  let auth: { isAuthenticated: ReturnType<typeof vi.fn>; hasAnyRealmRole: ReturnType<typeof vi.fn> };
  let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let requests: HttpTestingController;
  let monitor: ReputationDeepReportMonitorService;

  beforeEach(() => {
    vi.useFakeTimers();
    window.localStorage.clear();
    auth = { isAuthenticated: vi.fn().mockReturnValue(true), hasAnyRealmRole: vi.fn().mockReturnValue(true) };
    toast = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast }
      ]
    });
    requests = TestBed.inject(HttpTestingController);
    monitor = TestBed.inject(ReputationDeepReportMonitorService);
  });

  afterEach(() => {
    requests.verify();
    monitor.clear();
    window.localStorage.clear();
    vi.useRealTimers();
  });

  function storeWatchedCompany(id = '42'): void {
    window.localStorage.setItem(ACTIVE_DEEP_REPORT_COMPANY_KEY, id);
    window.localStorage.setItem(ACTIVE_DEEP_REPORT_CONTEXT_KEY, JSON.stringify({ routerLink: '/orders' }));
  }

  it('never polls the staff API for an anonymous visitor and keeps the staff key', () => {
    auth.isAuthenticated.mockReturnValue(false);
    storeWatchedCompany();

    monitor.restore();

    requests.expectNone(() => true);
    expect(window.localStorage.getItem(ACTIVE_DEEP_REPORT_COMPANY_KEY)).toBe('42');
  });

  it('forgets a stored report for an account without reputation access', () => {
    auth.hasAnyRealmRole.mockReturnValue(false);
    storeWatchedCompany();

    monitor.restore();

    requests.expectNone(() => true);
    expect(auth.hasAnyRealmRole).toHaveBeenCalledWith(['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG']);
    expect(window.localStorage.getItem(ACTIVE_DEEP_REPORT_COMPANY_KEY)).toBeNull();
    expect(window.localStorage.getItem(ACTIVE_DEEP_REPORT_CONTEXT_KEY)).toBeNull();
  });

  it('polls without the logout redirect and forgets the watch after a 401', () => {
    storeWatchedCompany();

    monitor.restore();

    const request = requests.expectOne(LATEST_JOB_URL);
    expect(request.request.context.get(SKIP_AUTH_REDIRECT_ON_401)).toBe(true);
    request.flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(window.localStorage.getItem(ACTIVE_DEEP_REPORT_COMPANY_KEY)).toBeNull();
    vi.advanceTimersByTime(10 * 60_000);
    requests.expectNone(LATEST_JOB_URL);
  });

  it('forgets the watch when the latest job is missing or not running', () => {
    storeWatchedCompany();

    monitor.restore();
    requests.expectOne(LATEST_JOB_URL).flush(null);

    expect(window.localStorage.getItem(ACTIVE_DEEP_REPORT_COMPANY_KEY)).toBeNull();
    vi.advanceTimersByTime(10 * 60_000);
    requests.expectNone(LATEST_JOB_URL);
    expect(toast.success).not.toHaveBeenCalled();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('retries a temporary outage a bounded number of times, then forgets the watch', () => {
    storeWatchedCompany();

    monitor.restore();
    requests.expectOne(LATEST_JOB_URL).flush(null, { status: 503, statusText: 'Unavailable' });
    expect(window.localStorage.getItem(ACTIVE_DEEP_REPORT_COMPANY_KEY)).toBe('42');

    for (let attempt = 0; attempt < 3; attempt += 1) {
      vi.advanceTimersByTime(90_000);
      requests.expectOne(LATEST_JOB_URL).flush(null, { status: 503, statusText: 'Unavailable' });
    }

    expect(window.localStorage.getItem(ACTIVE_DEEP_REPORT_COMPANY_KEY)).toBeNull();
    vi.advanceTimersByTime(10 * 60_000);
    requests.expectNone(LATEST_JOB_URL);
  });

  it('keeps polling a running job and notifies once it is done', () => {
    storeWatchedCompany();

    monitor.restore();
    requests.expectOne(LATEST_JOB_URL).flush({ id: 1, companyId: 42, status: 'RUNNING' });
    expect(window.localStorage.getItem(ACTIVE_DEEP_REPORT_COMPANY_KEY)).toBe('42');

    vi.advanceTimersByTime(30_000);
    requests.expectOne(LATEST_JOB_URL).flush({ id: 1, companyId: 42, companyName: 'Acme', status: 'DONE' });

    expect(toast.success).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem(ACTIVE_DEEP_REPORT_COMPANY_KEY)).toBeNull();
  });
});

describe('AuthService explicit logout', () => {
  it('drops the stored report watch and form drafts of the signed-in user', async () => {
    window.localStorage.setItem(ACTIVE_DEEP_REPORT_COMPANY_KEY, '42');
    window.localStorage.setItem(ACTIVE_DEEP_REPORT_CONTEXT_KEY, '{}');
    window.localStorage.setItem('otziv.unrelated', 'kept');
    writeSessionDraft('manager-order-edit:7', { changes: { orderComments: 'x' } });
    window.sessionStorage.setItem('otziv.unrelated', 'kept');
    const keycloak = {
      authenticated: true,
      logout: vi.fn().mockResolvedValue(undefined),
      hasRealmRole: vi.fn().mockReturnValue(true)
    };

    await new AuthService(keycloak as unknown as Keycloak).logout();

    expect(keycloak.logout).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem(ACTIVE_DEEP_REPORT_COMPANY_KEY)).toBeNull();
    expect(window.localStorage.getItem(ACTIVE_DEEP_REPORT_CONTEXT_KEY)).toBeNull();
    expect(readSessionDraft('manager-order-edit:7')).toBeNull();
    expect(window.localStorage.getItem('otziv.unrelated')).toBe('kept');
    expect(window.sessionStorage.getItem('otziv.unrelated')).toBe('kept');
    window.localStorage.removeItem('otziv.unrelated');
    window.sessionStorage.removeItem('otziv.unrelated');
  });
});
