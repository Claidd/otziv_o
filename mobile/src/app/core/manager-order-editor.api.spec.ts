import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { ApiService, type OrderUpdateRequest } from './api.service';
import { ManagerOrderEditorApi } from './manager-order-editor.api';

describe('manager order editor HTTP boundary', () => {
  let api: ManagerOrderEditorApi;
  let requests: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(ManagerOrderEditorApi);
    requests = TestBed.inject(HttpTestingController);
  });
  afterEach(() => requests.verify());

  it('loads the existing editor endpoint through Angular HttpClient', async () => {
    const result = firstValueFrom(api.getEdit(202));
    const request = requests.expectOne('/api/manager/orders/202/edit');
    expect(request.request.method).toBe('GET');
    request.flush({ id: 202, companyId: 91, companyTitle: 'Company', status: 'FUTURE_STATUS', created: '', changed: '', payDay: '', orderComments: '', commentsCompany: '', complete: false, filials: [], managers: [], workers: [], canComplete: false, canDelete: false, sum: null });
    const payload = await result;
    expect(payload.id).toBe(202);
    expect(payload.sum).toBeUndefined();
    expect(payload.canCancelPayment).toBe(false);
    expect(payload.status).toBe('FUTURE_STATUS');
  });

  it('preserves the legacy API adapter and exact update command', async () => {
    const legacy = TestBed.inject(ApiService);
    const command: OrderUpdateRequest = { filialId: null, workerId: null, managerId: null, counter: 7, orderComments: 'note', commentsCompany: '', complete: false };
    const result = firstValueFrom(legacy.updateManagerOrder(202, command));
    const request = requests.expectOne('/api/manager/orders/202');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(command);
    request.flush({ id: 202 });
    await result;
  });

  it('preserves the deletion endpoint', async () => {
    const result = firstValueFrom(api.delete(202));
    const request = requests.expectOne('/api/manager/orders/202');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
    await result;
  });
});
