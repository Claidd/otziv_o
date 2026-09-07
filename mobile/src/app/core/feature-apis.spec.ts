import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { ApiService } from './api.service';
import { WorkerApi } from './worker.api';
import { DictionariesApi } from './dictionaries.api';
import { CompaniesApi } from './companies.api';
import { CommonBillingApi } from './common-billing.api';

describe('feature HTTP clients and legacy delegates', () => {
  let requests: HttpTestingController;
  beforeEach(() => { TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] }); requests = TestBed.inject(HttpTestingController); });
  afterEach(() => requests.verify());
  it('keeps worker board filtering and pagination on the existing endpoint', async () => {
    const result = firstValueFrom(TestBed.inject(WorkerApi).getWorkerBoard({ keyword: '  company  ', pageNumber: 3, pageSize: 20, workerId: 7 }));
    const request = requests.expectOne(req => req.url === '/api/worker/board');
    expect(request.request.params.get('keyword')).toBe('company'); expect(request.request.params.get('pageNumber')).toBe('3'); expect(request.request.params.get('workerId')).toBe('7'); expect(request.request.params.get('section')).toBe('all');
    request.flush({}); await result;
  });
  it('preserves legacy worker mutation bodies without a replay', async () => {
    const result = firstValueFrom(TestBed.inject(ApiService).updateWorkerOrderStatus(202, 'Оплачено'));
    const request = requests.expectOne('/api/worker/orders/202/status'); expect(request.request.method).toBe('POST'); expect(request.request.body).toEqual({ status: 'Оплачено' }); request.flush(null); await result;
    requests.expectNone('/api/worker/orders/202/status');
  });
  it('preserves dictionary pagination, trimmed filters and encoded device tokens', async () => {
    const api = TestBed.inject(DictionariesApi); const list = firstValueFrom(api.getAdminBots('  bot  ', 2, 25));
    const query = requests.expectOne(req => req.url === '/api/admin/bots'); expect(query.request.params.get('keyword')).toBe('bot'); expect(query.request.params.get('page')).toBe('2'); expect(query.request.params.get('size')).toBe('25'); query.flush({ bots: [] }); await list;
    const deleted = firstValueFrom(TestBed.inject(ApiService).deleteOperatorPhoneDeviceToken(3, 'a/b+c')); const request = requests.expectOne('/api/admin/phones/3/device-tokens/a%2Fb%2Bc'); expect(request.request.method).toBe('DELETE'); request.flush(null); await deleted;
  });
  it('keeps the company source and captured lead/manager identifiers', async () => {
    const result = firstValueFrom(TestBed.inject(CompaniesApi).getCompanyCreatePayload('operator', 21, 7)); const request = requests.expectOne(req => req.url === '/api/companies/create-payload');
    expect(request.request.params.get('source')).toBe('operator'); expect(request.request.params.get('leadId')).toBe('21'); expect(request.request.params.get('managerId')).toBe('7'); request.flush({}); await result;
  });
  it('preserves common invoice evidence and explicit unpaid confirmation', async () => {
    const result = firstValueFrom(TestBed.inject(CommonBillingApi).changeCommonInvoicePaymentRoute(301, 'OWNER_BANK_REISSUE', 'evidence-token', 81)); const request = requests.expectOne('/api/common-billing/invoices/301/payment-route-change');
    expect(request.request.method).toBe('POST'); expect(request.request.body).toEqual({ target: 'OWNER_BANK_REISSUE', confirmedUnpaid: true, expectedPaymentEvidenceToken: 'evidence-token', expectedTargetPaymentProfileId: 81 }); request.flush({}); await result;
  });
});
