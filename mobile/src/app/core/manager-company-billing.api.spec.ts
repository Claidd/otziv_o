import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { ApiService } from './api.service';
import { ManagerCompanyBillingApi } from './manager-company-billing.api';

describe('company billing HTTP boundary', () => {
  let requests: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    requests = TestBed.inject(HttpTestingController);
  });
  afterEach(() => requests.verify());

  it('loads company-scoped accounts through the narrow client', async () => {
    const result = firstValueFrom(TestBed.inject(ManagerCompanyBillingApi).getCommonBillingAccountsForCompany(91));
    const request = requests.expectOne('/api/common-billing/accounts/by-company/91');
    expect(request.request.method).toBe('GET');
    request.flush([]);
    expect(await result).toEqual([]);
  });

  it('preserves the legacy create command and current-invoice detach choice', async () => {
    const legacy = TestBed.inject(ApiService);
    const command = { name: 'Общий счет', enabled: true, autoRepeatOrders: false, managerId: null, invoiceCompanyId: 91, companyIds: [91, 92] };
    const created = firstValueFrom(legacy.createCommonBillingAccount(command));
    const create = requests.expectOne('/api/common-billing/accounts');
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual(command);
    create.flush({ id: 7 }); await created;
    const detached = firstValueFrom(legacy.removeCommonBillingCompany(7, 91, true));
    const detach = requests.expectOne(request => request.url.includes('/api/common-billing/accounts/7/companies/91'));
    expect(detach.request.method).toBe('DELETE');
    expect(detach.request.params.get('detachCurrent')).toBe('true');
    detach.flush({ id: 7 }); await detached;
  });
});
