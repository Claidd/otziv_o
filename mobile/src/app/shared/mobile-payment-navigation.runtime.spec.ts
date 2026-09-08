import { TestBed } from '@angular/core/testing';
import { Browser } from '@capacitor/browser';
import { Capacitor } from '@capacitor/core';
import { MobileAuthDiagnosticsService } from '../core/mobile-auth-diagnostics.service';
import { MobileExternalLinkService } from './mobile-external-link.service';

vi.mock('@capacitor/browser', () => ({ Browser: { open: vi.fn().mockResolvedValue(undefined) } }));

describe('native payment navigation after asynchronous diagnostics', () => {
  afterEach(() => vi.restoreAllMocks());

  for (const activeAfterCheckpoint of [false, true]) {
    it(`${activeAfterCheckpoint ? 'opens for' : 'does not open for'} a ${activeAfterCheckpoint ? 'current' : 'departed'} payment visit`, async () => {
      let finishCheckpoint!: () => void;
      const checkpoint = vi.fn(() => new Promise<void>(resolve => { finishCheckpoint = resolve; }));
      vi.spyOn(Capacitor, 'isNativePlatform').mockReturnValue(true);
      const browserOpen = vi.mocked(Browser.open);
      browserOpen.mockClear();
      TestBed.configureTestingModule({ providers: [
        { provide: MobileAuthDiagnosticsService, useValue: { checkpoint } }
      ] });
      const service = TestBed.inject(MobileExternalLinkService);
      let active = true;
      const opened = service.openPayment('https://securepay.tinkoff.ru/fixture', 'payment', () => active);
      expect(checkpoint).toHaveBeenCalledOnce();
      expect(browserOpen).not.toHaveBeenCalled();
      active = activeAfterCheckpoint;
      finishCheckpoint();

      expect(await opened).toBe(activeAfterCheckpoint);
      expect(browserOpen).toHaveBeenCalledTimes(activeAfterCheckpoint ? 1 : 0);
    });
  }
});
