import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, Subject } from 'rxjs';
import { CommonBillingApi, type CommonInvoiceDetailsResponse } from '../../../core/common-billing.api';
import { CommonManualPaymentAttributionApi } from '../../../core/common-manual-payment-attribution.api';
import { ManagerApi, type OrderCardItem } from '../../../core/manager.api';
import { MetricSnapshotApi } from '../../../core/metric-snapshot.api';
import { PaymentsApi } from '../../../core/payments.api';
import { CompanyDeepReportLaunchService } from '../../../core/company-deep-report-launch.service';
import { AuthService } from '../../../core/auth.service';
import { MobileNavIntentService } from '../../../shared/mobile/mobile-nav-intent.service';
import { ToastService } from '../../../shared/toast.service';
import { CommonBillingComponent } from './common-billing.component';
import { ManagerBoardComponent } from '../../manager/manager-board.component';

const lastError = 'operation_unknown: [operationId=invoice-message:41:7] timeout';
const details = (error: string | null) => ({ summary: { id: 41, status: 'INVOICED', lastError: error } }) as CommonInvoiceDetailsResponse;

describe('web invoice delivery feedback', () => {
  let send: ReturnType<typeof vi.fn>;
  let toast: { success: ReturnType<typeof vi.fn>; warning: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    send = vi.fn();
    toast = { success: vi.fn(), warning: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({ providers: [
      { provide: CommonBillingApi, useValue: { sendInvoice: send, remind: send, markUnpaid: send } },
      { provide: CommonManualPaymentAttributionApi, useValue: {} },
      { provide: ManagerApi, useValue: {} },
      { provide: PaymentsApi, useValue: {} },
      { provide: MetricSnapshotApi, useValue: {} },
      { provide: CompanyDeepReportLaunchService, useValue: {} },
      { provide: AuthService, useValue: { hasAnyRealmRole: () => true, hasRealmRole: () => false } },
      { provide: ToastService, useValue: toast },
      { provide: ActivatedRoute, useValue: { snapshot: { data: {}, queryParamMap: convertToParamMap({ invoiceId: '41' }) }, queryParamMap: new Subject() } },
      { provide: Router, useValue: { url: '/manager', navigate: vi.fn(), createUrlTree: () => ({}), serializeUrl: () => '/manager' } },
      { provide: MobileNavIntentService, useValue: { intent: () => null } }
    ] });
    TestBed.overrideComponent(CommonBillingComponent, { set: { template: '', imports: [] } });
    TestBed.overrideComponent(ManagerBoardComponent, { set: { template: '', imports: [] } });
  });

  afterEach(() => vi.restoreAllMocks());

  it.each([lastError, 'telegram_send_failed'])('admin preserves INVOICED but warns on %s', error => {
    const returned = details(error); send.mockReturnValue(of(returned));
    const fixture = TestBed.createComponent(CommonBillingComponent);
    fixture.componentInstance.invoiceDetails.set(details(null));
    fixture.componentInstance.sendInvoice();
    expect(fixture.componentInstance.invoiceDetails()).toBe(returned);
    expect(send).toHaveBeenCalledExactlyOnceWith(41);
    expect(toast.success).not.toHaveBeenCalled();
    expect(toast.warning).toHaveBeenCalledWith('Отправка общего счета не подтверждена', `Отправка не подтверждена. ${error}`);
    expect(fixture.componentInstance.mutating()).toBe('');
    fixture.destroy();
  });

  it('admin reports confirmed delivery when lastError is empty', () => {
    send.mockReturnValue(of(details(null)));
    const fixture = TestBed.createComponent(CommonBillingComponent);
    fixture.componentInstance.invoiceDetails.set(details(null));
    fixture.componentInstance.sendInvoice();
    expect(toast.success).toHaveBeenCalledWith('Общий счет отправлен');
    expect(toast.warning).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it.each(['Выставлен счет', 'Напоминание'])('manager warns after %s and still reloads the board once', status => {
    const load = vi.spyOn(ManagerBoardComponent.prototype, 'loadBoard').mockImplementation(() => {});
    vi.spyOn(ManagerBoardComponent.prototype as any, 'loadDailyOverdueReminder').mockImplementation(() => {});
    send.mockReturnValue(of(details(lastError)));
    const fixture = TestBed.createComponent(ManagerBoardComponent);
    load.mockClear();
    fixture.componentInstance.updateOrderStatus({ id: -41, commonInvoice: true, commonInvoiceId: 41 } as OrderCardItem,
      { status } as Parameters<ManagerBoardComponent['updateOrderStatus']>[1]);
    expect(send).toHaveBeenCalledExactlyOnceWith(41);
    expect(toast.success).not.toHaveBeenCalled();
    expect(toast.warning).toHaveBeenCalledWith('Отправка общего счета не подтверждена', `Отправка не подтверждена. ${lastError}`);
    expect(load).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });
});
