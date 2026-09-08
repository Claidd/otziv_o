import fixtures from '../../../../contracts/fixtures/client-api-current.json';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { CommonBillingApi } from '../core/common-billing.api';
import { ApiService, type CommonInvoiceDetailsResponse } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileCommonManualPaymentFlowService } from '../shared/mobile-common-manual-payment-flow.service';
import { CommonBillingPage } from './common-billing.page';

const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const details = (status: string, lastError: string | null = null) => ({
  ...fixtures.responses.CommonInvoiceDetailsResponseOutput,
  summary: { ...fixtures.responses.CommonInvoiceSummaryResponseOutput, id: 41, status, lastError, totalOrders: 1, readyOrders: 1, paidOrders: 0, paidKopecks: 0 },
  orders: [], orderCards: []
}) as CommonInvoiceDetailsResponse;

describe('invoice delivery outcome in the mobile detail page', () => {
  it.each(['send', 'remind'] as const)('shows UNKNOWN from %s without claiming successful delivery or repeating', async (action) => {
    const lastError = 'operation_unknown: [operationId=invoice-message:41:7] timeout';
    const returned = details(action === 'send' ? 'INVOICED' : 'REMINDER', lastError);
    const send = vi.fn().mockReturnValue(of(returned));
    TestBed.configureTestingModule({ providers: [
      { provide: CommonBillingApi, useValue: {
        getCommonInvoice: () => of(details(action === 'send' ? 'READY' : 'INVOICED')),
        getCommonManualPaymentMode: () => of({ attributionRequired: false }),
        sendCommonInvoice: send, remindCommonInvoice: send
      } },
      { provide: ApiService, useValue: {} },
      { provide: AuthService, useValue: { hasAnyRealmRole: () => true } },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ invoiceId: '41' })) } },
      { provide: Router, useValue: {} },
      { provide: MobileConfirmService, useValue: { confirm: async () => true } },
      { provide: MobileCommonManualPaymentFlowService, useValue: {} }
    ] });
    TestBed.overrideComponent(CommonBillingPage, { set: { template: '', imports: [] } });
    const fixture = TestBed.createComponent(CommonBillingPage);
    const page = fixture.componentInstance;
    page.ngOnInit(); await settle();
    await page.runInvoiceAction(action);
    expect(send).toHaveBeenCalledExactlyOnceWith(41);
    expect(page.details()).toBe(returned);
    expect(page.error()).toBe(`Отправка не подтверждена. ${lastError}`);
    expect(page.mutating()).toBeNull();
    fixture.destroy();
  });
});
