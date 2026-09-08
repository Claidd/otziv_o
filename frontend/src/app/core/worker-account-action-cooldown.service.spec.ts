import { HttpClient, HttpHeaders, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';
import { isWorkerAccountAction, workerAccountActionCooldownInterceptor } from './worker-account-action-cooldown.interceptor';
import { WorkerAccountActionCooldownService, WorkerAccountActionCooldownState } from './worker-account-action-cooldown.service';

const STATE_URL = '/api/worker/account-action-cooldown';
const CHANGE_URL = '/api/worker/reviews/17/change-bot';
const BLOCK_URL = '/api/manager/orders/5/reviews/18/bots/12/deactivate';

function state(seconds = 0, serverNow = '2026-09-07T10:00:00Z', duration = 60): WorkerAccountActionCooldownState {
  return {
    enabled: duration > 0, durationSeconds: duration, remainingSeconds: seconds, serverNow,
    availableAt: seconds > 0 ? new Date(Date.parse(serverNow) + seconds * 1000).toISOString() : null
  };
}

function headers(value: WorkerAccountActionCooldownState): Record<string, string> {
  return {
    'X-Worker-Account-Action-Enabled': String(value.enabled),
    'X-Worker-Account-Action-Duration-Seconds': String(value.durationSeconds),
    'X-Worker-Account-Action-Server-Now': value.serverNow,
    ...(value.availableAt ? { 'X-Worker-Account-Action-Available-At': value.availableAt } : {})
  };
}

describe('Worker account action cooldown', () => {
  let service: WorkerAccountActionCooldownService;
  let http: HttpClient;
  let requests: HttpTestingController;
  let roles: string[];
  let auth: { authenticated: ReturnType<typeof signal<boolean>>; tokenParsed: ReturnType<typeof signal<{ sub: string } | undefined>> };

  beforeEach(() => {
    localStorage.clear();
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date', 'performance'] });
    roles = ['WORKER'];
    auth = { authenticated: signal(true), tokenParsed: signal<{ sub: string } | undefined>({ sub: 'worker-a' }) };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([workerAccountActionCooldownInterceptor])),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            ...auth,
            hasRealmRole: (role: string) => roles.includes(role),
            hasAnyRealmRole: (values: string[]) => values.some((role) => roles.includes(role))
          }
        }
      ]
    });
    service = TestBed.inject(WorkerAccountActionCooldownService);
    http = TestBed.inject(HttpClient);
    requests = TestBed.inject(HttpTestingController);
    TestBed.tick();
    requests.expectOne(STATE_URL).flush(state());
  });

  afterEach(() => {
    requests.verify();
    TestBed.resetTestingModule();
    vi.useRealTimers();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('locks both routes immediately and keeps a pending mutation alive when its page unsubscribes', () => {
    const subscription = http.post(CHANGE_URL, {}).subscribe();
    const change = requests.expectOne(CHANGE_URL);
    expect(service.locked()).toBe(true);
    expect(service.countdown()).toBe('01:00');
    const errors: number[] = [];
    http.post(BLOCK_URL, {}).subscribe({ error: (error) => errors.push(error.status) });
    requests.expectNone(BLOCK_URL);
    expect(errors).toEqual([429]);
    subscription.unsubscribe();
    expect(change.cancelled).toBe(false);
    change.flush({}, { headers: headers(state(60, '2026-09-07T10:00:01Z')) });
    vi.advanceTimersByTime(59_000);
    expect(service.countdown()).toBe('00:01');
    vi.advanceTimersByTime(1000);
    expect(service.locked()).toBe(false);
  });

  it('leaves other actions and page reads available throughout the timer', () => {
    http.post(CHANGE_URL, {}).subscribe();
    requests.expectOne(CHANGE_URL).flush({}, { headers: headers(state(60, '2026-09-07T10:00:01Z')) });
    http.get('/api/worker/board').subscribe();
    requests.expectOne('/api/worker/board').flush({});
    http.post('/api/worker/reviews/17/credential-reveal', {}).subscribe();
    requests.expectOne('/api/worker/reviews/17/credential-reveal').flush({});
    expect(service.locked()).toBe(true);
  });

  it('uses a configured duration and does not extend it for rejected clicks', () => {
    service.sync();
    requests.expectOne(STATE_URL).flush(state(0, '2026-09-07T10:00:01Z', 180));
    http.post(CHANGE_URL, {}).subscribe();
    requests.expectOne(CHANGE_URL).flush({}, { headers: headers(state(180, '2026-09-07T10:00:02Z', 180)) });
    vi.advanceTimersByTime(30_000);
    http.post(BLOCK_URL, {}).subscribe({ error: () => undefined });
    expect(service.countdown()).toBe('02:30');
    requests.expectNone(BLOCK_URL);
  });

  it('keeps both buttons pending until a long request completes even after its timer elapsed', () => {
    http.post(CHANGE_URL, {}).subscribe();
    const request = requests.expectOne(CHANGE_URL);
    vi.advanceTimersByTime(61_000);
    expect(service.locked()).toBe(true);
    expect(service.title()).toBe('Выполняется действие с аккаунтом');
    request.flush({}, { headers: headers(state(0, '2026-09-07T10:01:02Z')) });
    expect(service.locked()).toBe(false);
  });

  it('counts from server acceptance when a successful request takes twelve seconds', () => {
    http.post(CHANGE_URL, {}).subscribe();
    const request = requests.expectOne(CHANGE_URL);
    vi.advanceTimersByTime(12_000);
    request.flush({}, { headers: headers(state(48, '2026-09-07T10:00:13Z')) });
    expect(service.remainingSeconds()).toBe(48);
  });

  it('refreshes only every thirty seconds while visible and releases a timer disabled by an admin', () => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    http.post(CHANGE_URL, {}).subscribe();
    requests.expectOne(CHANGE_URL).flush({}, { headers: headers(state(60, '2026-09-07T10:00:01Z')) });
    vi.advanceTimersByTime(29_000);
    requests.expectNone(STATE_URL);
    vi.advanceTimersByTime(1000);
    requests.expectOne(STATE_URL).flush({ ...state(0, '2026-09-07T10:00:31Z', 60), enabled: false });
    expect(service.locked()).toBe(false);
    expect(service.durationSeconds()).toBe(60);
    vi.advanceTimersByTime(30_000);
    requests.expectNone(STATE_URL);
  });

  it('reconciles changes from a legacy page without publishing a new legacy notification', () => {
    const write = vi.spyOn(Storage.prototype, 'setItem');
    window.dispatchEvent(new StorageEvent('storage', { key: 'otziv-worker-account-action-changed', newValue: 'changed' }));
    requests.expectOne(STATE_URL).flush(state(45, '2026-09-07T10:00:16Z'));
    expect(service.remainingSeconds()).toBe(45);
    expect(write.mock.calls.some(([key]) => key === 'otziv-worker-account-action-changed')).toBe(false);
  });

  it('reads server state after a lost response without retrying the mutation', () => {
    http.post(CHANGE_URL, {}).subscribe({ error: () => undefined });
    requests.expectOne(CHANGE_URL).error(new ProgressEvent('network-error'));
    expect(service.locked()).toBe(true);
    requests.expectOne(STATE_URL).flush(state(44, '2026-09-07T10:00:17Z'));
    expect(service.remainingSeconds()).toBe(44);
    requests.expectNone(CHANGE_URL);
  });

  it('clears provisional waiting after a request fails before server acceptance', () => {
    http.post(CHANGE_URL, {}).subscribe({ error: () => undefined });
    requests.expectOne(CHANGE_URL).flush({ message: 'Invalid review' }, { status: 400, statusText: 'Bad Request' });
    requests.expectOne(STATE_URL).flush(state(0, '2026-09-07T10:00:02Z'));
    expect(service.locked()).toBe(false);
  });

  it('uses authoritative 429 state without a second request', () => {
    http.post(CHANGE_URL, {}).subscribe({ error: () => undefined });
    requests.expectOne(CHANGE_URL).flush(
      { code: 'WORKER_ACCOUNT_ACTION_COOLDOWN', ...state(36, '2026-09-07T10:00:25Z') },
      { status: 429, statusText: 'Too Many Requests' }
    );
    expect(service.remainingSeconds()).toBe(36);
    requests.expectNone(STATE_URL);
  });

  it('ignores a delayed state read that predates an accepted mutation', () => {
    service.sync();
    const oldRead = requests.expectOne(STATE_URL);
    http.post(CHANGE_URL, {}).subscribe();
    requests.expectOne(CHANGE_URL).flush({}, { headers: headers(state(60, '2026-09-07T10:00:02Z')) });
    oldRead.flush(state(0, '2026-09-07T10:00:01Z'));
    expect(service.remainingSeconds()).toBe(60);
  });

  it('applies user-scoped state from another tab without creating broadcast request loops', () => {
    const key = 'otziv-worker-account-action-cooldown:v1:worker-a';
    const snapshot = JSON.stringify({ state: state(48, '2026-09-07T10:00:13Z'), receivedAt: Date.now() });
    window.dispatchEvent(new StorageEvent('storage', { key, newValue: snapshot }));
    expect(service.remainingSeconds()).toBe(48);
    requests.expectNone(STATE_URL);
    window.dispatchEvent(new StorageEvent('storage', {
      key: 'otziv-worker-account-action-cooldown:v1:worker-b',
      newValue: JSON.stringify({ state: state(120, '2026-09-07T10:00:14Z'), receivedAt: Date.now() })
    }));
    expect(service.remainingSeconds()).toBe(48);
  });

  it('restores a user snapshot on login and ignores responses for the previous user', () => {
    http.post(CHANGE_URL, {}).subscribe();
    const oldRequest = requests.expectOne(CHANGE_URL);
    localStorage.setItem('otziv-worker-account-action-cooldown:v1:worker-b', JSON.stringify({
      state: state(28, '2026-09-07T10:00:33Z'), receivedAt: Date.now()
    }));
    auth.tokenParsed.set({ sub: 'worker-b' });
    TestBed.tick();
    expect(service.remainingSeconds()).toBe(28);
    requests.expectOne(STATE_URL).flush(state(27, '2026-09-07T10:00:34Z'));
    oldRequest.flush({}, { headers: headers(state(60, '2026-09-07T10:00:35Z')) });
    expect(service.remainingSeconds()).toBe(27);
  });

  it.each(['MANAGER', 'ADMIN', 'OWNER'])('does not limit WORKER with elevated %s role', (role) => {
    roles = ['WORKER', role];
    auth.tokenParsed.set({ sub: 'worker-a' });
    TestBed.tick();
    http.post(CHANGE_URL, {}).subscribe();
    http.post(BLOCK_URL, {}).subscribe();
    expect(service.locked()).toBe(false);
    requests.expectOne(CHANGE_URL).flush({});
    requests.expectOne(BLOCK_URL).flush({});
    requests.expectNone(STATE_URL);
  });

  it.each([0, 180])('disables the timer independently of the saved %s seconds and retains pending request protection', (duration) => {
    service.sync();
    requests.expectOne(STATE_URL).flush({ ...state(0, '2026-09-07T10:00:01Z', duration), enabled: false });
    expect(service.durationSeconds()).toBe(duration);
    http.post(CHANGE_URL, {}).subscribe();
    expect(service.remainingSeconds()).toBe(0);
    expect(service.locked()).toBe(true);
    requests.expectOne(CHANGE_URL).flush({}, {
      headers: headers({ ...state(0, '2026-09-07T10:00:02Z', duration), enabled: false })
    });
    expect(service.locked()).toBe(false);
    expect(service.enabled()).toBe(false);
    expect(service.durationSeconds()).toBe(duration);
  });

  it('starts the saved duration when the timer is enabled again', () => {
    service.sync();
    requests.expectOne(STATE_URL).flush({ ...state(0, '2026-09-07T10:00:01Z', 180), enabled: false });
    service.sync();
    requests.expectOne(STATE_URL).flush(state(0, '2026-09-07T10:00:02Z', 180));
    http.post(BLOCK_URL, {}).subscribe();
    expect(service.remainingSeconds()).toBe(180);
    requests.expectOne(BLOCK_URL).flush({}, { headers: headers(state(180, '2026-09-07T10:00:03Z', 180)) });
    expect(service.countdown()).toBe('03:00');
  });

  it('is unaffected by wall clock changes within an active tab', () => {
    const attempt = service.begin()!;
    service.receive(attempt, new HttpHeaders(headers(state(60, '2026-09-07T10:00:01Z'))));
    service.finish(attempt);
    vi.setSystemTime(Date.now() + 86_400_000);
    vi.advanceTimersByTime(10_000);
    expect(service.remainingSeconds()).toBe(50);
  });
});

describe('account action route coverage', () => {
  it.each([
    '/api/worker/reviews/17/change-bot',
    '/api/worker/reviews/17/bots/11/deactivate',
    '/api/worker/recovery-tasks/17/change-bot',
    '/api/worker/recovery-tasks/17/bots/11/deactivate',
    '/api/worker/bad-review-tasks/17/change-bot',
    '/api/worker/bad-review-tasks/17/bots/11/deactivate',
    '/api/manager/orders/5/reviews/17/change-bot',
    '/api/manager/orders/5/reviews/17/new-account',
    '/api/manager/orders/5/bad-review-tasks/17/change-bot',
    '/api/manager/orders/5/reviews/17/bots/11/deactivate'
  ])('covers %s', (url) => expect(isWorkerAccountAction('POST', url)).toBe(true));

  it('does not match unrelated or external requests', () => {
    expect(isWorkerAccountAction('GET', CHANGE_URL)).toBe(false);
    expect(isWorkerAccountAction('POST', `${CHANGE_URL}-other`)).toBe(false);
    expect(isWorkerAccountAction('POST', `https://external.test${CHANGE_URL}`)).toBe(false);
  });
});
