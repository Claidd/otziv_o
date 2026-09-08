import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import current from '../../../../contracts/fixtures/client-api-current.json';
import { clientApiContractInterceptor } from './client-api-contract.interceptor';
import { ManagerApi } from './manager.api';

describe('compiled client SDK through real Angular HTTP transport', () => {
  let requests: HttpTestingController;
  let http: HttpClient;
  beforeEach(async () => {
    await import('@otziv/client-common/client-api');
    TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([
      (request, next) => next(request.clone({ setHeaders: { 'X-Contract-Transport': 'angular' } })),
      clientApiContractInterceptor
    ])), provideHttpClientTesting()] });
    requests = TestBed.inject(HttpTestingController); http = TestBed.inject(HttpClient);
  });
  afterEach(() => { requests.verify(); TestBed.resetTestingModule(); });
  const dispatched = async () => { await new Promise<void>(resolve => setTimeout(resolve, 0)); };

  it('checks manager pagination and permissions on the actual feature client response', async () => {
    const result = firstValueFrom(TestBed.inject(ManagerApi).getBoard({ section: 'orders', pageNumber: 2, pageSize: 7 }));
    await dispatched();
    const request = requests.expectOne(req => req.url === '/api/manager/board');
    expect(request.request.params.get('pageNumber')).toBe('2');
    expect(request.request.params.get('pageSize')).toBe('7');
    expect(request.request.headers.get('X-Contract-Transport')).toBe('angular');
    request.flush(current.responses.ManagerBoardResponseOutput);
    const board = await result;
    expect(board.orders?.totalElements).toBe(current.responses.ManagerBoardResponseOutput.orders.totalElements);
  });

  it('rejects a string permission before a consumer can treat it as true', async () => {
    const result = firstValueFrom(http.get('/api/manager/orders/2/edit'));
    const assertion = expect(result).rejects.toThrow('Invalid API response');
    await dispatched();
    requests.expectOne('/api/manager/orders/2/edit').flush({ ...current.responses.OrderEditResponseOutput, canComplete: 'false' });
    await assertion;
  });

  it('rejects malformed JSON input before dispatch', async () => {
    const result = firstValueFrom(http.post('/api/payments/public/contract-token/init', { email: 7 }));
    await expect(result).rejects.toThrow('Invalid API request');
    requests.expectNone('/api/payments/public/contract-token/init');
  });

  it('preserves a bodyless 204 on the actual status write and sends it once', async () => {
    const result = firstValueFrom(TestBed.inject(ManagerApi).updateOrderStatus(2, 'В работе'));
    await dispatched();
    const request = requests.expectOne('/api/manager/orders/2/status');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ status: 'В работе' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeNull();
    requests.expectNone('/api/manager/orders/2/status');
  });

  it('keeps a timeout unknown and never retries a write', async () => {
    const result = firstValueFrom(http.post('/api/payments/public/contract-token/init', { email: 'contract@example.invalid', offerConsent: true }));
    const assertion = expect(result).rejects.toMatchObject({ status: 0 });
    await dispatched();
    const request = requests.expectOne('/api/payments/public/contract-token/init');
    request.error(new ProgressEvent('timeout'));
    await assertion; await dispatched();
    requests.expectNone('/api/payments/public/contract-token/init');
  });

  it('preserves status and structured domain error without authentication or action replay', async () => {
    const result = firstValueFrom(http.post('/api/payments/public/contract-token/manual-paid', {}));
    const assertion = expect(result).rejects.toMatchObject({ status: 409, error: { code: 'PAYMENT_ROUTE_STALE' } });
    await dispatched();
    requests.expectOne('/api/payments/public/contract-token/manual-paid').flush({ message: 'Changed', code: 'PAYMENT_ROUTE_STALE' }, { status: 409, statusText: 'Conflict' });
    await assertion; requests.expectNone('/api/payments/public/contract-token/manual-paid');
  });

  it('keeps Angular GET cancellation after the lazy schema module loads', async () => {
    const subscription = http.get('/api/manager/orders/2/edit').subscribe();
    await dispatched(); const request = requests.expectOne('/api/manager/orders/2/edit');
    subscription.unsubscribe(); expect(request.cancelled).toBe(true);
  });

  it('rejects malformed pagination before request and preserves binary responses outside JSON decoding', async () => {
    await expect(firstValueFrom(http.get('/api/manager/board', { params: { pageNumber: 'bad' } }))).rejects.toThrow('Invalid API request');
    requests.expectNone(req => req.url === '/api/manager/board');
    const result = firstValueFrom(http.get('/api/admin/payments/test-receipt', { responseType: 'blob' }));
    const blob = new Blob(['receipt'], { type: 'application/pdf' });
    requests.expectOne('/api/admin/payments/test-receipt').flush(blob);
    expect(await result).toEqual(blob);
  });
});
