import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { archiveRestoreSpecialistRequired } from './archive-restore-specialist';
import { ManagerArchiveApi } from './manager-archive.api';

function conflict(body: unknown, status = 409): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: body });
}

describe('archive restore with a specialist choice', () => {
  let requests: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    requests = TestBed.inject(HttpTestingController);
  });
  afterEach(() => requests.verify());

  it('asks the server for the specialist list and repeats with the chosen specialist', async () => {
    const api = TestBed.inject(ManagerArchiveApi);
    const first = firstValueFrom(api.restoreManagerArchiveOrder(25948, 'Новый'));
    const ask = requests.expectOne((req) => req.url.endsWith('/api/manager/archive/orders/25948/restore'));
    expect(ask.request.method).toBe('POST');
    expect(ask.request.params.get('targetStatus')).toBe('Новый');
    expect(ask.request.params.get('confirm')).toBe('true');
    expect(ask.request.params.get('specialistChoice')).toBe('true');
    expect(ask.request.params.has('targetWorkerId')).toBe(false);
    ask.flush({ orderId: 25948 });
    await first;

    const second = firstValueFrom(api.restoreManagerArchiveOrder(25948, 'Новый', 16));
    const chosen = requests.expectOne((req) => req.url.endsWith('/api/manager/archive/orders/25948/restore'));
    expect(chosen.request.params.get('specialistChoice')).toBe('true');
    expect(chosen.request.params.get('targetWorkerId')).toBe('16');
    chosen.flush({ orderId: 25948 });
    await second;
  });

  it('reads the 409 with the suggested specialist', () => {
    const choice = archiveRestoreSpecialistRequired(conflict({
      message: 'Специалист заказа деактивирован — выберите действующего специалиста',
      code: 'SPECIALIST_REQUIRED',
      workers: [
        { id: 16, label: 'Специалист А', companyWorker: true },
        { id: 1, label: 'Специалист Б', companyWorker: false }
      ],
      suggestedWorkerId: 16
    }));
    expect(choice).toEqual({
      message: 'Специалист заказа деактивирован — выберите действующего специалиста',
      workers: [
        { id: 16, label: 'Специалист А', companyWorker: true },
        { id: 1, label: 'Специалист Б', companyWorker: false }
      ],
      suggestedWorkerId: 16
    });
  });

  it('offers no preselection when the suggestion is missing or not among the workers', () => {
    const workers = [{ id: 16, label: 'Специалист А', companyWorker: true }];
    expect(archiveRestoreSpecialistRequired(conflict({ code: 'SPECIALIST_REQUIRED', workers, suggestedWorkerId: null }))?.suggestedWorkerId)
      .toBeNull();
    expect(archiveRestoreSpecialistRequired(conflict({ code: 'SPECIALIST_REQUIRED', workers, suggestedWorkerId: 99 }))?.suggestedWorkerId)
      .toBeNull();
    expect(archiveRestoreSpecialistRequired(conflict({ code: 'SPECIALIST_REQUIRED', workers }))?.message)
      .toBe('Специалист заказа больше не работает — выберите действующего специалиста');
  });

  it('drops malformed workers and ignores every other answer', () => {
    const choice = archiveRestoreSpecialistRequired(conflict({
      code: 'specialist_required',
      workers: [{ id: 'x', label: 'Неверный' }, { id: 5, label: 'Верный' }, null]
    }));
    expect(choice?.workers).toEqual([{ id: 5, label: 'Верный' }]);
    expect(archiveRestoreSpecialistRequired(conflict({ message: 'Другое', code: 'CONFLICT' }))).toBeNull();
    expect(archiveRestoreSpecialistRequired(conflict({ code: 'SPECIALIST_REQUIRED' }, 400))).toBeNull();
    expect(archiveRestoreSpecialistRequired(conflict('Специалист'))).toBeNull();
    expect(archiveRestoreSpecialistRequired(new Error('сеть'))).toBeNull();
    expect(archiveRestoreSpecialistRequired(null)).toBeNull();
  });
});
