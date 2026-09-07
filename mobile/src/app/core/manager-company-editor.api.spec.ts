import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { ApiService } from './api.service';
import { ManagerCompanyEditorApi } from './manager-company-editor.api';

describe('company editor HTTP boundary', () => {
  let requests: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    requests = TestBed.inject(HttpTestingController);
  });
  afterEach(() => requests.verify());

  it('preserves the company and branch IDs in the legacy adapter and exact branch update body', async () => {
    const legacy = TestBed.inject(ApiService);
    const requestBody = { title: 'Филиал', url: 'https://example.test', cityId: 17 };
    const result = firstValueFrom(legacy.updateManagerCompanyFilial(91, 5, requestBody));
    const request = requests.expectOne('/api/manager/companies/91/filials/5');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(requestBody);
    request.flush({ id: 91 }); await result;
  });

  it('reads the branch deletion preview without issuing a mutation', async () => {
    const result = firstValueFrom(TestBed.inject(ManagerCompanyEditorApi).getManagerCompanyFilialDeletionPreview(91, 5));
    const request = requests.expectOne('/api/manager/companies/91/filials/5/deletion-preview');
    expect(request.request.method).toBe('GET');
    request.flush({ willArchive: true, orderCount: 4 });
    expect((await result).orderCount).toBe(4);
  });
});
