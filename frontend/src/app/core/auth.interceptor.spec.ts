import { HttpContext, HttpErrorResponse, HttpRequest, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { firstValueFrom, of, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { OPTIONAL_AUTH_TOKEN, SKIP_AUTH_REDIRECT_ON_401 } from './auth-http-context';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  it('uses an already-valid optional token without refreshing a public capability request', async () => {
    const auth = {
      getOptionalToken: vi.fn(() => 'optional-token'),
      getToken: vi.fn(),
      refreshToken: vi.fn(),
      isAuthenticated: vi.fn(() => true),
      handleUnauthorized: vi.fn()
    };
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }]
    });
    const request = new HttpRequest('GET', '/api/review-check/public-id', {
      context: new HttpContext()
        .set(OPTIONAL_AUTH_TOKEN, true)
        .set(SKIP_AUTH_REDIRECT_ON_401, true)
    });
    const next = vi.fn((current: HttpRequest<unknown>) => of(new HttpResponse({
      status: 200,
      body: current.headers.get('Authorization')
    })));

    const response = await firstValueFrom(
      TestBed.runInInjectionContext(() => authInterceptor(request, next))
    ) as HttpResponse<string>;

    expect(response.body).toBe('Bearer optional-token');
    expect(auth.getOptionalToken).toHaveBeenCalledTimes(1);
    expect(auth.getToken).not.toHaveBeenCalled();
    expect(auth.refreshToken).not.toHaveBeenCalled();
  });

  it('retries an optional public capability request anonymously after a stale token 401', async () => {
    const auth = {
      getOptionalToken: vi.fn(() => 'stale-token'),
      getToken: vi.fn(),
      refreshToken: vi.fn(),
      isAuthenticated: vi.fn(() => true),
      handleUnauthorized: vi.fn()
    };
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }]
    });
    const request = new HttpRequest('PUT', '/api/review-check/public-id', {}, {
      context: new HttpContext()
        .set(OPTIONAL_AUTH_TOKEN, true)
        .set(SKIP_AUTH_REDIRECT_ON_401, true)
    });
    const next = vi.fn((current: HttpRequest<unknown>) => current.headers.has('Authorization')
      ? throwError(() => new HttpErrorResponse({ status: 401, url: current.url }))
      : of(new HttpResponse({ status: 200, body: 'anonymous-ok' }))
    );

    const response = await firstValueFrom(
      TestBed.runInInjectionContext(() => authInterceptor(request, next))
    ) as HttpResponse<string>;

    expect(response.body).toBe('anonymous-ok');
    expect(next).toHaveBeenCalledTimes(2);
    expect(next.mock.calls[1][0].headers.has('Authorization')).toBe(false);
    expect(auth.refreshToken).not.toHaveBeenCalled();
    expect(auth.handleUnauthorized).not.toHaveBeenCalled();
  });

  it('does not duplicate an already-anonymous optional request that returns 401', async () => {
    const auth = {
      getOptionalToken: vi.fn(() => null),
      getToken: vi.fn(),
      refreshToken: vi.fn(),
      isAuthenticated: vi.fn(() => false),
      handleUnauthorized: vi.fn()
    };
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }]
    });
    const request = new HttpRequest('GET', '/api/review-check/public-id', {
      context: new HttpContext()
        .set(OPTIONAL_AUTH_TOKEN, true)
        .set(SKIP_AUTH_REDIRECT_ON_401, true)
    });
    const next = vi.fn(() => throwError(() => new HttpErrorResponse({
      status: 401,
      url: request.url
    })));

    await expect(firstValueFrom(
      TestBed.runInInjectionContext(() => authInterceptor(request, next))
    )).rejects.toMatchObject({ status: 401 });

    expect(next).toHaveBeenCalledTimes(1);
    expect(auth.refreshToken).not.toHaveBeenCalled();
    expect(auth.handleUnauthorized).not.toHaveBeenCalled();
  });

  it('refreshes the token once and retries an explicitly stale-token 403', async () => {
    const auth = {
      getToken: vi.fn()
        .mockResolvedValueOnce('old-token')
        .mockResolvedValueOnce('new-token'),
      refreshToken: vi.fn().mockResolvedValue(true),
      isAuthenticated: vi.fn(() => true),
      handleUnauthorized: vi.fn()
    };
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }]
    });
    const request = new HttpRequest('GET', '/api/manager/board');
    let attempt = 0;
    const next = vi.fn((current: HttpRequest<unknown>) => {
      attempt += 1;
      if (attempt === 1) {
        return throwError(() => new HttpErrorResponse({
          status: 403,
          error: { code: 'AUTH_TOKEN_STALE' },
          url: current.url
        }));
      }
      return of(new HttpResponse({ status: 200, body: 'ok' }));
    });

    const response = await firstValueFrom(
      TestBed.runInInjectionContext(() => authInterceptor(request, next))
    );

    expect(response).toBeInstanceOf(HttpResponse);
    expect(auth.refreshToken).toHaveBeenCalledTimes(1);
    expect(auth.refreshToken).toHaveBeenCalledWith(-1);
    expect(next).toHaveBeenCalledTimes(2);
    expect(next.mock.calls[1][0].headers.get('Authorization')).toBe('Bearer new-token');
  });

  it('does not refresh an ordinary permission-denied 403', async () => {
    const auth = {
      getToken: vi.fn().mockResolvedValue('token'),
      refreshToken: vi.fn(),
      isAuthenticated: vi.fn(() => true),
      handleUnauthorized: vi.fn()
    };
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }]
    });
    const request = new HttpRequest('GET', '/api/admin/restricted');
    const next = vi.fn(() => throwError(() => new HttpErrorResponse({
      status: 403,
      error: { code: 'ACCESS_DENIED' },
      url: request.url
    })));

    await expect(firstValueFrom(
      TestBed.runInInjectionContext(() => authInterceptor(request, next))
    )).rejects.toMatchObject({ status: 403 });

    expect(auth.refreshToken).not.toHaveBeenCalled();
    expect(auth.handleUnauthorized).not.toHaveBeenCalled();
    expect(next).toHaveBeenCalledTimes(1);
  });

  it('does not start a second refresh when the retried request is also forbidden', async () => {
    const auth = {
      getToken: vi.fn()
        .mockResolvedValueOnce('old-token')
        .mockResolvedValueOnce('new-token'),
      refreshToken: vi.fn().mockResolvedValue(true),
      isAuthenticated: vi.fn(() => true),
      handleUnauthorized: vi.fn()
    };
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }]
    });
    const request = new HttpRequest('GET', '/api/manager/board');
    const next = vi.fn((current: HttpRequest<unknown>) =>
      throwError(() => new HttpErrorResponse({
        status: 403,
        error: { code: 'AUTH_TOKEN_STALE' },
        url: current.url
      }))
    );

    await expect(firstValueFrom(
      TestBed.runInInjectionContext(() => authInterceptor(request, next))
    )).rejects.toMatchObject({ status: 403 });

    expect(auth.refreshToken).toHaveBeenCalledTimes(1);
    expect(next).toHaveBeenCalledTimes(2);
  });

  it('redirects a restricted manager to the personal cabinet after 423', async () => {
    const auth = {
      getToken: vi.fn().mockResolvedValue('token'),
      refreshToken: vi.fn(),
      isAuthenticated: vi.fn(() => true),
      handleUnauthorized: vi.fn()
    };
    const router = { navigate: vi.fn().mockResolvedValue(true) };
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router }
      ]
    });
    const request = new HttpRequest('GET', '/api/orders');
    const next = vi.fn(() => throwError(() => new HttpErrorResponse({
      status: 423,
      error: { code: 'MANAGER_REPORT_REVIEW_REQUIRED' },
      url: request.url
    })));

    await expect(firstValueFrom(
      TestBed.runInInjectionContext(() => authInterceptor(request, next))
    )).rejects.toMatchObject({ status: 423 });

    expect(router.navigate).toHaveBeenCalledWith(['/'], {
      queryParams: { reportReviewRequired: '1' }
    });
    expect(auth.refreshToken).not.toHaveBeenCalled();
  });
});
