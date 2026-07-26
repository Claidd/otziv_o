import { HttpErrorResponse, HttpRequest, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { firstValueFrom, of, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  it('refreshes the token once and retries an API request after 403', async () => {
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
        return throwError(() => new HttpErrorResponse({ status: 403, url: current.url }));
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
      throwError(() => new HttpErrorResponse({ status: 403, url: current.url }))
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
