import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import current from '../../../../contracts/fixtures/client-api-current.json';
import { CommonBillingApi } from './common-billing.api';
import { ManagerControlApi } from './manager-control.api';
import { clientApiContractInterceptor } from './client-api-contract.interceptor';

describe('queued delivery negotiation through the generated contract interceptor', () => {
  beforeEach(async () => { await import('@otziv/client-common/client-api'); TestBed.configureTestingModule({ providers: [
    provideHttpClient(withInterceptors([clientApiContractInterceptor])), provideHttpClientTesting()
  ] }); });
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });
  it('opts in only on the four asynchronous send commands and preserves the queued receipt', async () => {
    const billing = TestBed.inject(CommonBillingApi), manager = TestBed.inject(ManagerControlApi);
    const receipt = { operationId: 'fixture-operation', status: 'QUEUED', attempts: 0, errorCode: null };
    const cases = [
      { run: () => billing.sendCommonInvoice(7), path: '/api/common-billing/invoices/7/send',
        response: current.responses.CommonInvoiceDetailsResponseOutput },
      { run: () => billing.remindCommonInvoice(7), path: '/api/common-billing/invoices/7/remind',
        response: current.responses.CommonInvoiceDetailsResponseOutput },
      { run: () => manager.sendManagerControlClientMessage(7), path: '/api/admin/manager-control/concrete-items/7/send-client-message',
        response: current.responses.ManagerControlConcreteItemResponseOutput },
      { run: () => manager.replyManagerControlClientMessage(7, { message: 'Fixture reply' }),
        path: '/api/admin/manager-control/concrete-items/7/reply', response: current.responses.ManagerControlConcreteItemResponseOutput }
    ];
    for (const entry of cases) {
      const pending = firstValueFrom<{ delivery?: import('@otziv/client-common/delivery-operations').DeliveryOperation | null }>(entry.run());
      await new Promise(resolve => setTimeout(resolve, 0));
      const request = TestBed.inject(HttpTestingController).expectOne(req => req.url.endsWith(entry.path));
      expect(request.request.method).toBe('POST');
      expect(request.request.headers.get('X-Otziv-Delivery-Protocol')).toBe('queued-v1');
      request.flush({ ...entry.response, delivery: receipt });
      expect((await pending).delivery).toEqual(receipt);
    }
  });
});
