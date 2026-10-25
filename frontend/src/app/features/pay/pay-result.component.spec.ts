import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { PayResultComponent } from './pay-result.component';

@Component({ selector: 'app-admin-layout', template: '<ng-content />' })
class LayoutStub {
  title = input('');
  active = input('');
  rightPanelMode = input('');
}

describe('public bank return page', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([
        { path: 'pay/success', component: PayResultComponent, data: { result: 'success' } },
        { path: 'pay/fail', component: PayResultComponent, data: { result: 'fail' } }
      ])]
    });
    TestBed.overrideComponent(PayResultComponent, {
      remove: { imports: [AdminLayoutComponent] },
      add: { imports: [LayoutStub] }
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  for (const url of ['/pay/success', '/pay/fail', '/pay/success?Success=true&Status=CONFIRMED&PaymentId=123']) {
    it(`does not treat ${url} as verified payment evidence`, async () => {
      const harness = await RouterTestingHarness.create();
      await harness.navigateByUrl(url, PayResultComponent);
      const page = harness.routeNativeElement!;

      expect(page.querySelector('.summary-status strong')?.textContent).toBe('Статус платежа не проверен');
      expect(page.textContent).toContain('Откройте исходную ссылку на счёт');
      expect(page.textContent).toContain('Проверьте статус перед повторной оплатой');
      expect(page.textContent).toContain('Подтверждение банка может поступить с задержкой');
      expect(page.textContent).not.toMatch(/Оплачено|Не оплачено|Деньги поступили|Деньги не были приняты|Новый заказ принят|Можно повторить оплату/i);
      expect(page.querySelector('.payment-result-widget--success, .payment-result-widget--fail')).toBeNull();
      const icons = Array.from(page.querySelectorAll('.material-icons-sharp'), icon => icon.textContent?.trim());
      expect(icons).not.toContain('verified');
      expect(icons).not.toContain('task_alt');
      expect(page.querySelector<HTMLAnchorElement>('.primary-result-button')?.getAttribute('href')).toBe('/');
      expect(page.querySelector<HTMLAnchorElement>('.secondary-result-button')?.getAttribute('href')).toBe('/contacts');
    });
  }
});
