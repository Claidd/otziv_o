import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AuthService } from './auth.service';
import { CabinetApi } from './cabinet.api';

describe('CabinetApi freshness and request isolation', () => {
  let api: CabinetApi;
  let http: HttpTestingController;
  let token: Record<string, unknown>;
  beforeEach(() => {
    token = { sub: 'user-a', sid: 'session-a', realm_access: { roles: ['ROLE_WORKER'] } };
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { tokenParsed: () => token } }] });
    api = TestBed.inject(CabinetApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); vi.restoreAllMocks(); });

  it('shares a pending request but reloads after 30 seconds', () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000);
    api.getProfile().subscribe(); api.getProfile().subscribe();
    http.expectOne(r => r.url.endsWith('/profile')).flush({ date: '2026-09-12' });
    now.mockReturnValue(30_999);
    api.getProfile().subscribe();
    http.expectNone(r => r.url.endsWith('/profile'));
    now.mockReturnValue(31_000);
    api.getProfile().subscribe();
    http.expectOne(r => r.url.endsWith('/profile')).flush({ date: '2026-09-12' });
  });

  it('keeps a newer refresh when an older request fails', () => {
    api.getProfile().subscribe({ error: () => {} });
    const old = http.expectOne(r => r.url.endsWith('/profile'));
    api.getProfile(undefined, { forceRefresh: true }).subscribe();
    const refreshed = http.expectOne(r => r.params.get('refresh') === 'true');
    refreshed.flush({ date: '2026-09-12' });
    old.flush({}, { status: 503, statusText: 'Unavailable' });
    api.getProfile().subscribe();
    http.expectNone(r => r.url.endsWith('/profile'));
  });

  it('separates principal, login session and roles', () => {
    for (const change of [{}, { sub: 'user-b' }, { sid: 'session-b' },
      { realm_access: { roles: ['ROLE_MANAGER'] } }]) {
      token = { ...token, ...change };
      api.getProfile().subscribe();
      http.expectOne(r => r.url.endsWith('/profile')).flush({ date: '2026-09-12' });
    }
  });
});
