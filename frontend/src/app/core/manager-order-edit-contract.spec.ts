import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { ManagerApi } from './manager.api';

describe('manager editor shared read contract', () => {
  let api: ManagerApi;
  let requests: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(ManagerApi);
    requests = TestBed.inject(HttpTestingController);
  });
  afterEach(() => requests.verify());

  it('keeps Angular HTTP and decodes legacy optional fields with permissions denied by default', async () => {
    const result = firstValueFrom(api.getOrderEdit(202));
    const request = requests.expectOne('/api/manager/orders/202/edit');
    expect(request.request.method).toBe('GET');
    request.flush({ id: 202, companyId: null, companyTitle: '', status: 'FUTURE_STATUS', created: '', changed: '', payDay: '', orderComments: '', commentsCompany: '', complete: false, filials: [], managers: [], workers: [], canComplete: false, canDelete: false, sum: null });
    const payload = await result;
    expect(payload.companyId).toBeNull();
    expect(payload.sum).toBeUndefined();
    expect(payload.canCancelPayment).toBe(false);
    expect(payload.status).toBe('FUTURE_STATUS');
  });

  it('rejects a malformed permission before the editor can use it', async () => {
    const result = firstValueFrom(api.getOrderEdit(202));
    const assertion = expect(result).rejects.toThrow('Invalid order editor response');
    requests.expectOne('/api/manager/orders/202/edit').flush({ id: 202, canDelete: 'true' });
    await assertion;
  });
});
