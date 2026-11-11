import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { WorkerApi } from './worker.api';
import { DictionariesApi } from './dictionaries.api';
import { CompaniesApi } from './companies.api';
import { CommonBillingApi } from './common-billing.api';
import { OrderPaymentApi } from './order-payment.api';

describe('feature HTTP clients', () => {
  let requests: HttpTestingController;
  beforeEach(() => { TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] }); requests = TestBed.inject(HttpTestingController); });
  afterEach(() => requests.verify());
  it('keeps worker board filtering and pagination on the existing endpoint', async () => {
    const result = firstValueFrom(TestBed.inject(WorkerApi).getWorkerBoard({ keyword: '  company  ', pageNumber: 3, pageSize: 20, workerId: 7 }));
    const request = requests.expectOne(req => req.url === '/api/worker/board');
    expect(request.request.params.get('keyword')).toBe('company'); expect(request.request.params.get('pageNumber')).toBe('3'); expect(request.request.params.get('workerId')).toBe('7'); expect(request.request.params.get('section')).toBe('all');
    request.flush({}); await result;
  });
  it('preserves worker mutation bodies without a replay', async () => {
    const result = firstValueFrom(TestBed.inject(WorkerApi).updateWorkerOrderStatus(202, 'Оплачено'));
    const request = requests.expectOne('/api/worker/orders/202/status'); expect(request.request.method).toBe('POST'); expect(request.request.body).toEqual({ status: 'Оплачено' }); request.flush(null); await result;
    requests.expectNone('/api/worker/orders/202/status');
  });
  it('preserves dictionary pagination, trimmed filters and encoded device tokens', async () => {
    const api = TestBed.inject(DictionariesApi); const list = firstValueFrom(api.getAdminBots('  bot  ', 2, 25));
    const query = requests.expectOne(req => req.url === '/api/admin/bots'); expect(query.request.params.get('keyword')).toBe('bot'); expect(query.request.params.get('page')).toBe('2'); expect(query.request.params.get('size')).toBe('25'); query.flush({ bots: [] }); await list;
    const deleted = firstValueFrom(api.deleteOperatorPhoneDeviceToken(3, 'a/b+c')); const request = requests.expectOne('/api/admin/phones/3/device-tokens/a%2Fb%2Bc'); expect(request.request.method).toBe('DELETE'); request.flush(null); await deleted;
  });
  it('keeps the company source and captured lead/manager identifiers', async () => {
    const result = firstValueFrom(TestBed.inject(CompaniesApi).getCompanyCreatePayload('operator', 21, 7)); const request = requests.expectOne(req => req.url === '/api/companies/create-payload');
    expect(request.request.params.get('source')).toBe('operator'); expect(request.request.params.get('leadId')).toBe('21'); expect(request.request.params.get('managerId')).toBe('7'); request.flush({}); await result;
  });
  it('preserves common invoice evidence and explicit unpaid confirmation', async () => {
    const result = firstValueFrom(TestBed.inject(CommonBillingApi).changeCommonInvoicePaymentRoute(301, 'OWNER_BANK_REISSUE', 'evidence-token', 81)); const request = requests.expectOne('/api/common-billing/invoices/301/payment-route-change');
    expect(request.request.method).toBe('POST'); expect(request.request.body).toEqual({ target: 'OWNER_BANK_REISSUE', confirmedUnpaid: true, expectedPaymentEvidenceToken: 'evidence-token', expectedTargetPaymentProfileId: 81 }); request.flush({}); await result;
  });
  it('reads the orders that may be returned to a common invoice and returns one through the attach endpoint', async () => {
    const billing = TestBed.inject(CommonBillingApi);
    const list = firstValueFrom(billing.getCommonInvoiceReturnableOrders(390));
    const listRequest = requests.expectOne('/api/common-billing/invoices/390/returnable-orders'); expect(listRequest.request.method).toBe('GET');
    listRequest.flush({ invoiceId: 390, canReturn: true, blockReason: null, orders: [{ orderId: 28523, companyId: 7, companyTitle: 'Denisenko', orderTitle: 'Denisenko', status: 'Новый', amountKopecks: 25000 }] });
    expect((await list).orders.map(order => order.orderId)).toEqual([28523]);
    const returned = firstValueFrom(billing.returnCommonInvoiceOrder(390, 28523));
    const attach = requests.expectOne('/api/common-billing/invoices/390/orders/28523/attach'); expect(attach.request.method).toBe('POST'); expect(attach.request.body).toEqual({});
    attach.flush({ summary: { id: 390 }, orders: [] });
    expect((await returned).summary.id).toBe(390);
  });
  it('reads the people of the card number window and names a person only on the plain route change', async () => {
    const billing = TestBed.inject(CommonBillingApi); const orders = TestBed.inject(OrderPaymentApi);
    const people = firstValueFrom(billing.getCommonInvoicePaymentRouteRecipients(301));
    const list = requests.expectOne('/api/common-billing/invoices/301/payment-route-recipients'); expect(list.request.method).toBe('GET'); list.flush({ invoiceId: 301, amountKopecks: 100, recipients: [] }); await people;
    const orderPeople = firstValueFrom(orders.getManagerOrderPaymentRouteRecipients(202));
    const orderList = requests.expectOne('/api/manager/orders/202/payment-route-recipients'); expect(orderList.request.method).toBe('GET'); orderList.flush({ orderId: 202, paymentLinkId: 71, amountKopecks: 100, recipients: [] }); await orderPeople;

    // The system's own choice of another person keeps the dedicated endpoint and sends no profile.
    const automatic = firstValueFrom(billing.changeCommonInvoicePaymentRoute(301, 'DIFFERENT_RECIPIENT', 'evidence-token'));
    const automaticRequest = requests.expectOne('/api/common-billing/invoices/301/payment-route-change/recipient');
    expect(automaticRequest.request.body.target).toBe('EMPLOYEE_REQUISITES'); expect(Object.keys(automaticRequest.request.body)).not.toContain('recipientProfileId'); automaticRequest.flush({}); await automatic;
    // A person named by the manager is explicit: the plain endpoint, whatever the route was issued from.
    const named = firstValueFrom(billing.changeCommonInvoicePaymentRoute(301, 'DIFFERENT_RECIPIENT', 'evidence-token', undefined, 44));
    const namedRequest = requests.expectOne('/api/common-billing/invoices/301/payment-route-change');
    expect(namedRequest.request.body).toEqual({ target: 'EMPLOYEE_REQUISITES', confirmedUnpaid: true, expectedPaymentEvidenceToken: 'evidence-token', recipientProfileId: 44 }); namedRequest.flush({}); await named;

    const orderAutomatic = firstValueFrom(orders.changeManagerOrderPaymentRoute(202, { expectedPaymentLinkId: 71, target: 'DIFFERENT_RECIPIENT', confirmedUnpaid: true }));
    const orderAutomaticRequest = requests.expectOne('/api/manager/orders/202/payment-route-change/recipient');
    expect(orderAutomaticRequest.request.body).toEqual({ expectedPaymentLinkId: 71, target: 'EMPLOYEE_REQUISITES', confirmedUnpaid: true }); orderAutomaticRequest.flush({}); await orderAutomatic;
    const orderNamed = firstValueFrom(orders.changeManagerOrderPaymentRoute(202, { expectedPaymentLinkId: 71, target: 'DIFFERENT_RECIPIENT', confirmedUnpaid: true, recipientProfileId: 44 }));
    const orderNamedRequest = requests.expectOne('/api/manager/orders/202/payment-route-change');
    expect(orderNamedRequest.request.body).toEqual({ expectedPaymentLinkId: 71, target: 'EMPLOYEE_REQUISITES', confirmedUnpaid: true, recipientProfileId: 44 }); orderNamedRequest.flush({}); await orderNamed;
  });
});
