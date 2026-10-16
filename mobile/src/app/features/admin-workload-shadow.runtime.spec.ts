import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AdminWorkloadShadowPage } from './admin-workload-shadow.page';

const root = '/api/admin/workload-shadow';
const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };

function flushBatch(requests: TestRequest[], workerName: string): void {
  expect(requests).toHaveLength(10);
  const responses: Record<string, object> = {
    '/settings': { mode: 'SHADOW', observationEnabled: true, applyEnabled: false, groupNotificationsEnabled: false, shiftStart: '09:00', shiftEnd: '18:00', businessZone: 'Asia/Irkutsk', revision: 1 },
    '/live/settings': { mode: 'SHADOW', applyEnabled: false, revision: 1, historyStartDate: '2026-10-01', minFinalizedDays: 7, stableHours: 24, canaryManagerIds: [] },
    '/monitor/summary': { updatedAt: '2026-10-16T12:00:00', mode: 'SHADOW', observationEnabled: true, applyEnabled: false, workerCount: 1, lateExcludedUnits: 7, atRiskWorkerCount: 1, managers: [{ managerId: 4, managerName: 'Менеджер', workerCount: 1 }] },
    '/monitor/health': { status: 'UP', checkedAt: '2026-10-16T12:00:00' },
    '/monitor/workers': [{ workerId: 3, workerName, managerId: 4, recipientEligible: true, transferStage: 1, lateExcludedUnits: 7 }],
    '/monitor/proposals': [],
    '/monitor/events': [],
    '/live/workflows': [],
    '/live/executions': [],
    '/live/emergency-assignments': []
  };
  for (const request of requests) {
    expect(request.request.method).toBe('GET');
    const suffix = request.request.url.slice(root.length);
    expect(Object.prototype.hasOwnProperty.call(responses, suffix)).toBe(true);
    request.flush(responses[suffix]);
  }
}

describe('read-only mobile workload monitor', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AdminWorkloadShadowPage>;
  let page: AdminWorkloadShadowPage;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.overrideComponent(AdminWorkloadShadowPage, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminWorkloadShadowPage);
    page = fixture.componentInstance;
  });

  afterEach(() => { fixture.destroy(); http.verify(); });

  it('loads monitoring and LIVE readiness using GET without a mutating request', async () => {
    page.ngOnInit();
    flushBatch(http.match((request) => request.url.startsWith(root) && !request.url.endsWith('/live/readiness')), 'Специалист');
    await settle();
    const readiness = http.expectOne((request) => request.url === `${root}/live/readiness`);
    expect(readiness.request.method).toBe('GET');
    expect(readiness.request.params.get('targetMode')).toBe('CANARY');
    readiness.flush({ ready: false, targetMode: 'CANARY', checkedAt: '2026-10-16T12:00:00', checks: [{ code: 'HISTORY', status: 'FAIL', message: 'Мало истории' }] });
    await settle();
    expect(page.workers()[0].workerName).toBe('Специалист');
    expect(page.eligibleWorkers()).toBe(1);
    expect(page.riskWorkers()).toBe(1);
    expect(page.lateUnits()).toBe(7);
    expect(page.readiness()?.ready).toBe(false);
    http.expectNone((request) => request.method !== 'GET');
  });

  it('filters by manager and ignores an older response that finishes last', async () => {
    page.ngOnInit();
    const oldBatch = http.match((request) => request.url.startsWith(root));
    page.selectManager(4);
    const newBatch = http.match((request) => request.url.startsWith(root));
    const scoped = newBatch.filter((request) => /\/(workers|proposals|workflows|executions|emergency-assignments)$/.test(request.request.url));
    expect(scoped).toHaveLength(5);
    for (const request of scoped) expect(request.request.params.get('managerId')).toBe('4');
    flushBatch(newBatch, 'Новый ответ');
    await settle();
    const readiness = http.expectOne(`${root}/live/readiness?targetMode=CANARY`);
    readiness.flush({ ready: true, targetMode: 'CANARY', checkedAt: '2026-10-16T12:00:00', checks: [] });
    await settle();
    flushBatch(oldBatch, 'Устаревший ответ');
    await settle();
    expect(page.workers()[0].workerName).toBe('Новый ответ');
    expect(page.managerId()).toBe(4);
  });
});
