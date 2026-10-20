import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { AuthService } from '../core/auth.service';
import type { AuthStatus, StoredTokens } from '../core/auth.models';
import { SessionRetryPage } from './session-retry.page';

describe('mobile session recovery screen', () => {
  let fixture: ComponentFixture<SessionRetryPage>;
  let tokens: ReturnType<typeof signal<StoredTokens | null>>;
  let status: ReturnType<typeof signal<AuthStatus>>;
  let router: { navigateByUrl: ReturnType<typeof vi.fn>; navigate: ReturnType<typeof vi.fn> };
  let refreshTokens: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    tokens = signal<StoredTokens | null>({
      accessToken: 'expired', tokenType: 'Bearer', expiresAt: Date.now() - 1000,
      refreshToken: 'retained-refresh'
    });
    status = signal<AuthStatus>('retrying');
    router = { navigateByUrl: vi.fn().mockResolvedValue(true), navigate: vi.fn().mockResolvedValue(true) };
    refreshTokens = vi.fn().mockResolvedValue({ status: 'temporary-unavailable' });
    TestBed.configureTestingModule({ providers: [
      { provide: AuthService, useValue: { tokens, status, isAuthenticated: () => Boolean(tokens()?.expiresAt && tokens()!.expiresAt > Date.now()), refreshTokens } },
      { provide: Router, useValue: router },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ target: '/tabs/orders' }) } } }
    ] });
    fixture = TestBed.createComponent(SessionRetryPage);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => fixture.destroy());

  it('keeps an expired retained session on the recovery screen and retries without login', async () => {
    expect(fixture.nativeElement.textContent).toContain('Восстанавливаем соединение');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    fixture.componentInstance.retry();
    await fixture.whenStable();
    expect(refreshTokens).toHaveBeenCalledTimes(1);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('returns to the original route only after receiving a valid access token', async () => {
    tokens.set({ accessToken: 'renewed', tokenType: 'Bearer', expiresAt: Date.now() + 300_000, refreshToken: 'renewed-refresh' });
    status.set('authenticated');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/orders', { replaceUrl: true });
  });

  it('opens login after the refresh session is definitively invalidated', async () => {
    tokens.set(null);
    status.set('anonymous');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(router.navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { target: '/tabs/orders' }, replaceUrl: true
    });
  });
});
