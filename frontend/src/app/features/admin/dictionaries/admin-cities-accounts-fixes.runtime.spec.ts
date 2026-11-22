import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ReactiveFormsModule } from '@angular/forms';
import { AdminAccountsApi } from '../../../core/admin-accounts.api';
import { AdminCitiesApi } from '../../../core/admin-cities.api';
import type {
  AdminBot,
  AdminCity,
  BotImportResponse,
  BotsResponse,
  CityCoordinateImportResponse
} from '../../../core/admin-dictionaries.api';
import { AdminAccountsFacade } from './admin-accounts.facade';
import { AdminCitiesFacade } from './admin-cities.facade';

/** The coordinate fields as the page renders them: Angular's number accessor and min/max validators apply. */
@Component({
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form">
      <input type="text" formControlName="title">
      <input class="latitude" type="number" step="0.000001" min="-90" max="90" formControlName="latitude">
      <input class="longitude" type="number" step="0.000001" min="-180" max="180" formControlName="longitude">
    </form>
  `
})
class CityCoordinatesHost {
  form!: AdminCitiesFacade['cityForm'];
}

describe('cities and accounts: fixes from the dictionaries audit', () => {
  let http: HttpTestingController;
  let active: boolean;
  const selectedId = signal<number | null>(null);
  const toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn() };
  const facades: { destroy(): void }[] = [];
  const coordinatesFile = new File(['18;52.3;104.3'], 'coordinates.csv', { type: 'text/csv' });
  const accountsFile = new File(['login:password'], 'accounts.txt', { type: 'text/plain' });
  const city: AdminCity = { id: 18, title: 'Иркутск', latitude: 52.3, longitude: 104.3, distanceMatrixReady: true, distanceCount: 2 };
  const coordinatesRange = 'Проверьте координаты: широта от -90 до 90, долгота от -180 до 180.';

  const workers = [{ id: 1, title: 'Анна' }, { id: 2, title: 'Борис' }];
  const statuses = [{ id: 3, title: 'Высокий' }, { id: 1, title: 'Новый' }, { id: 2, title: 'Средний' }];
  const botCities = [{ id: 10, title: 'Абакан' }, { id: 325, title: 'Шаблон' }];
  const first: AdminBot = { id: 8, login: 'first', fio: 'Первый', active: true, counter: 0, passwordPresent: true,
    worker: workers[0], city: botCities[0], status: statuses[1] };
  const second: AdminBot = { ...first, id: 9, login: 'second', fio: 'Второй' };
  const imported: BotImportResponse = { totalRows: 3, added: 2, skippedDuplicates: 1, skippedInvalid: 0, errors: [] };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    active = true;
    selectedId.set(null);
    vi.clearAllMocks();
  });
  afterEach(() => {
    facades.splice(0).forEach((facade) => facade.destroy());
    http.verify({ ignoreCancelled: true });
    vi.restoreAllMocks();
  });

  function cities(): AdminCitiesFacade {
    const facade = new AdminCitiesFacade({ api: TestBed.inject(AdminCitiesApi), toast, isActive: () => active,
      selectedId, search: () => '' });
    facades.push(facade);
    return facade;
  }

  function accounts(): AdminAccountsFacade {
    const facade = new AdminAccountsFacade({ api: TestBed.inject(AdminAccountsApi), toast, isActive: () => active,
      selectedId, search: () => '' });
    facades.push(facade);
    return facade;
  }

  function cityForm(facade: AdminCitiesFacade) {
    const fixture = TestBed.createComponent(CityCoordinatesHost);
    fixture.componentInstance.form = facade.cityForm;
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    return {
      latitude: element.querySelector<HTMLInputElement>('input.latitude')!,
      longitude: element.querySelector<HTMLInputElement>('input.longitude')!
    };
  }

  function type(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function fileEvent(file: File, value = file.name): { event: Event; input: { files: File[]; value: string } } {
    const input = { files: [file], value };
    return { event: { target: input } as unknown as Event, input };
  }

  function importCoordinates(facade: AdminCitiesFacade, result: CityCoordinateImportResponse): void {
    facade.importCityCoordinates(fileEvent(coordinatesFile).event);
    http.expectOne((request) => request.method === 'POST' && request.url.endsWith('/cities/coordinates/import'))
      .flush(result);
  }

  function citiesRead() {
    return http.expectOne((request) => request.method === 'GET' && request.url.endsWith('/cities'));
  }

  function page(bots: AdminBot[] = []): BotsResponse {
    return { bots, workers, statuses, cities: botCities, total: bots.length, page: 0, size: 50, totalPages: 1 };
  }

  function listRead() {
    return http.expectOne((request) => request.method === 'GET' && request.url.endsWith('/bots'));
  }

  function countRead() {
    return http.expectOne((request) => request.method === 'GET' && request.url.endsWith('/bots/unblocked-count'));
  }

  function open(facade: AdminAccountsFacade, bot: AdminBot): void {
    facade.selectBot({ id: bot.id } as AdminBot);
    http.expectOne((request) => request.method === 'GET' && request.url.endsWith(`/bots/${bot.id}`)).flush(bot);
  }

  function startImport(facade: AdminAccountsFacade) {
    facade.openBotImport();
    facade.selectBotImportFile(fileEvent(accountsFile).event);
    facade.uploadBotImport();
    return http.expectOne((request) => request.method === 'POST' && request.url.endsWith('/bots/import'));
  }

  describe('cities', () => {
    it('saves coordinates typed into the number fields and an erased coordinate as empty', () => {
      const facade = cities();
      const fields = cityForm(facade);
      facade.selectCity(city);
      expect(fields.latitude.value).toBe('52.3');
      type(fields.latitude, '52.4');
      type(fields.longitude, '');
      expect(facade.cityForm.controls.latitude.value).toBe(52.4);
      expect(facade.cityForm.controls.longitude.value).toBeNull();
      facade.saveCity();
      const write = http.expectOne((request) => request.method === 'PUT' && request.url.endsWith('/cities/18'));
      expect(write.request.body).toEqual({ title: 'Иркутск', latitude: 52.4, longitude: null });
      write.flush({ ...city, latitude: 52.4, longitude: null, distanceMatrixReady: false });
      expect(toast.success).toHaveBeenCalledWith('Город сохранен', '«Иркутск», ID 18');
      citiesRead().flush([city]);
    });

    it('names the coordinates when a number field is out of range instead of stopping silently', () => {
      const facade = cities();
      const fields = cityForm(facade);
      facade.selectCity(city);
      type(fields.latitude, '91');
      expect(facade.cityForm.controls.latitude.hasError('max')).toBe(true);
      facade.saveCity();
      expect(facade.error()).toBe(coordinatesRange);
      expect(toast.error).toHaveBeenCalledWith('Город не сохранен', coordinatesRange);
      http.expectNone(() => true);

      // An empty title still stops without a request and without the coordinate message.
      toast.error.mockClear();
      type(fields.latitude, '52');
      facade.cityForm.controls.title.setValue('');
      facade.saveCity();
      expect(facade.cityForm.controls.title.touched).toBe(true);
      expect(toast.error).not.toHaveBeenCalled();
      http.expectNone(() => true);
    });

    it('reports why coordinate rows were skipped and never calls a file without updates a success', () => {
      const facade = cities();
      importCoordinates(facade, { updated: 0, skipped: 5, citiesWithCoordinates: 0, citiesWithoutCoordinates: 0,
        distancesSaved: 0, errors: ['Строка 2: город #999 не найден', 'Строка 3: город #998 не найден',
          'Строка 4: нужно city_id, latitude, longitude', 'Строка 5: город #997 не найден'] });
      expect(toast.success).not.toHaveBeenCalled();
      expect(toast.warning).toHaveBeenCalledWith('Координаты не загружены',
        'Пропущено строк: 5. Строка 2: город #999 не найден; Строка 3: город #998 не найден; '
        + 'Строка 4: нужно city_id, latitude, longitude; и ещё 2.',
        { label: 'Понятно', callback: expect.any(Function) });
      citiesRead().flush([city]);

      importCoordinates(facade, { updated: 2, skipped: 1, errors: ['Строка 4: город #999 не найден'],
        citiesWithCoordinates: 2, citiesWithoutCoordinates: 0, distancesSaved: 2 });
      expect(toast.success).toHaveBeenCalledWith('Координаты загружены', 'обновлено: 2, пропущено: 1, ошибок: 1, связей: 2');
      expect(toast.warning).toHaveBeenLastCalledWith('Часть строк не загружена',
        'Пропущено строк: 1. Строка 4: город #999 не найден.', expect.anything());
      citiesRead().flush([city]);

      importCoordinates(facade, { updated: 0, skipped: 0, errors: [], citiesWithCoordinates: 0,
        citiesWithoutCoordinates: 0, distancesSaved: 0 });
      expect(toast.warning).toHaveBeenLastCalledWith('Координаты не загружены',
        'В файле нет строк с координатами. Нужны столбцы: ID города, широта, долгота.', expect.anything());
      expect(toast.success).toHaveBeenCalledTimes(1);
      citiesRead().flush([city]);
    });

    it('after a coordinate import points once to the cities below ID 150 that still need «Пересчитать город»', () => {
      const facade = cities();
      importCoordinates(facade, { updated: 3, skipped: 0, errors: [], citiesWithCoordinates: 1,
        citiesWithoutCoordinates: 0, distancesSaved: 2 });
      expect(toast.success).toHaveBeenCalledWith('Координаты загружены', 'обновлено: 3, пропущено: 0, связей: 2');
      expect(toast.warning).not.toHaveBeenCalled();
      const list: AdminCity[] = [
        { ...city, distanceMatrixReady: false },
        { ...city, id: 40, title: 'Без координат', latitude: null, longitude: null, distanceMatrixReady: false },
        { ...city, id: 200, title: 'Новый город', distanceMatrixReady: false }
      ];
      citiesRead().flush(list);
      expect(toast.warning).toHaveBeenCalledWith('Нужен пересчёт городов',
        'Городов с ID меньше 150 без пересчёта расстояний: 1. Откройте такой город и нажмите «Пересчитать город».');
      facade.load();
      citiesRead().flush(list);
      expect(toast.warning).toHaveBeenCalledTimes(1);
    });

    it('«Пересчитать город» asks to save typed coordinates first and reports a city without coordinates', () => {
      const facade = cities();
      facade.selectCity(city);
      facade.cityForm.controls.latitude.setValue('53');
      facade.cityForm.controls.latitude.markAsDirty();
      facade.rebuildSelectedCityDistances();
      http.expectNone(() => true);
      expect(toast.warning).toHaveBeenCalledWith('Сначала сохраните город', expect.stringContaining('«Сохранить»'));

      facade.selectCity({ ...city, latitude: null, longitude: null });
      // The page marks the form of a picked row as untouched.
      facade.cityForm.markAsPristine();
      facade.rebuildSelectedCityDistances();
      http.expectOne((request) => request.url.endsWith('/cities/18/distances/rebuild'))
        .flush({ citiesWithCoordinates: 0, citiesWithoutCoordinates: 1, distancesSaved: 0 });
      expect(toast.success).not.toHaveBeenCalled();
      expect(toast.warning).toHaveBeenLastCalledWith('Город не пересчитан',
        'У города нет сохранённых координат. Укажите широту и долготу и нажмите «Сохранить».');
      citiesRead().flush([]);
    });

    it('empties the coordinate file field even when the import is refused during a rebuild', () => {
      const facade = cities();
      facade.rebuildCityDistances();
      const rebuild = http.expectOne((request) => request.url.endsWith('/cities/distances/rebuild'));
      const { event, input } = fileEvent(coordinatesFile);
      facade.importCityCoordinates(event);
      expect(input.value).toBe('');
      http.expectNone((request) => request.url.endsWith('/cities/coordinates/import'));
      rebuild.flush({ citiesWithCoordinates: 2, citiesWithoutCoordinates: 0, distancesSaved: 2 });
      expect(toast.success).toHaveBeenCalledWith('Матрица расстояний пересчитана', 'городов: 2, без координат: 0, связей: 2');
      citiesRead().flush([]);
    });
  });

  describe('accounts', () => {
    it('does not delete the previous account while another one opens, and names the account it deletes', () => {
      const facade = accounts();
      open(facade, first);
      facade.selectBot({ id: 9 } as AdminBot);
      const opening = http.expectOne((request) => request.url.endsWith('/bots/9'));
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
      facade.deleteSelectedBot();
      expect(confirm).not.toHaveBeenCalled();
      expect(facade.deleting()).toBe(false);
      http.expectNone((request) => request.method === 'DELETE');
      expect(opening.cancelled).toBe(false);

      opening.flush(second);
      facade.deleteSelectedBot();
      const question = confirm.mock.calls[0][0] as string;
      expect(question).toContain('Удалить аккаунт second (ID 9)?');
      expect(question).toContain('Отзывы этого аккаунта останутся без аккаунта');
      expect(question).toContain('снимите «Аккаунт активен»');
      http.expectOne((request) => request.method === 'DELETE' && request.url.endsWith('/bots/9')).flush(null);
      expect(selectedId()).toBeNull();
      expect(toast.success).toHaveBeenCalledWith('Запись удалена', 'Аккаунты');
    });

    it('a row opening cancelled by an import does not keep saving and deleting locked', () => {
      const facade = accounts();
      open(facade, first);
      facade.selectBot({ id: 9 } as AdminBot);
      const opening = http.expectOne((request) => request.url.endsWith('/bots/9'));
      const write = startImport(facade);
      expect(opening.cancelled).toBe(true);
      expect(facade.botDetailLoadingId()).toBeNull();
      write.flush(imported);
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
      facade.deleteSelectedBot();
      expect(confirm).toHaveBeenCalledOnce();
      expect(confirm.mock.calls[0][0]).toContain('first (ID 8)');
    });

    it('guesses no owner, city or status: an opened account keeps empty ones, a new one gets «Новый» and the pool city', () => {
      const facade = accounts();
      facade.load();
      listRead().flush(page());
      countRead().flush({ cityId: 325, unblockedAccounts: 4 });
      expect(facade.botForm.getRawValue()).toMatchObject({ workerId: null, cityId: 325, statusId: 1 });

      open(facade, { ...first, worker: null, city: null, status: null });
      expect(facade.botForm.getRawValue()).toMatchObject({ workerId: null, cityId: null, statusId: null });
      facade.load();
      listRead().flush(page([first]));
      expect(facade.botForm.getRawValue()).toMatchObject({ workerId: null, cityId: null, statusId: null });
      facade.saveBot();
      expect(facade.botForm.controls.workerId.hasError('required')).toBe(true);
      http.expectNone((request) => request.method === 'PUT');

      // Without the pool city or «Новый» in the lists a new account gets no city and no status.
      facade.applyBotsResponse({ ...page(), statuses: [{ id: 3, title: 'Высокий' }], cities: [{ id: 10, title: 'Абакан' }] });
      facade.clearSelection();
      expect(facade.botForm.getRawValue()).toMatchObject({ workerId: null, cityId: null, statusId: null });
    });

    it('shows an import result that arrives while the browser tab is hidden and reads the list when the window closes', () => {
      const facade = accounts();
      facade.openBotImport();
      const { event, input } = fileEvent(accountsFile);
      facade.selectBotImportFile(event);
      expect(input.value).toBe('');
      expect(facade.importFile()).toBe(accountsFile);
      facade.uploadBotImport();
      const write = http.expectOne((request) => request.method === 'POST' && request.url.endsWith('/bots/import'));
      active = false;
      facade.deactivate();

      write.flush(imported);
      expect(facade.importResult()).toEqual(imported);
      expect(facade.importFile()).toBeNull();
      expect(facade.importing()).toBe(false);
      expect(facade.canUploadBotImport()).toBe(false);
      expect(toast.success).toHaveBeenCalledWith('Импорт аккаунтов завершен', 'Добавлено: 2, дублей пропущено: 1');
      http.expectNone((request) => request.method === 'GET');

      active = true;
      facade.closeBotImport();
      expect(facade.importModalOpen()).toBe(false);
      listRead().flush(page());
      countRead().flush({ cityId: 325, unblockedAccounts: 6 });
      expect(facade.trackedCityUnblockedAccounts()).toBe(6);
      facade.openBotImport();
      facade.closeBotImport();
      http.expectNone((request) => request.method === 'GET');
    });

    it('shows an import error that arrives while hidden, and nothing once the page is gone', () => {
      const facade = accounts();
      const write = startImport(facade);
      active = false;
      facade.deactivate();
      write.flush({ message: 'Файл не содержит аккаунтов' }, { status: 400, statusText: 'Bad Request' });
      expect(facade.importError()).toContain('Файл не содержит аккаунтов');
      expect(toast.error).toHaveBeenCalledWith('Аккаунты не импортированы', expect.stringContaining('Файл не содержит аккаунтов'));
      expect(facade.importing()).toBe(false);

      active = true;
      facade.uploadBotImport();
      const again = http.expectOne((request) => request.method === 'POST' && request.url.endsWith('/bots/import'));
      facade.destroy();
      again.flush(imported);
      expect(facade.importResult()).toBeNull();
      expect(toast.success).not.toHaveBeenCalled();
    });

    it('warns instead of a success when an import added nothing or skipped broken rows', () => {
      const facade = accounts();
      const nothingAdded: BotImportResponse = { ...imported, added: 0, skippedDuplicates: 3 };
      startImport(facade).flush(nothingAdded);
      expect(toast.warning).toHaveBeenLastCalledWith('Аккаунты не добавлены', 'Добавлено: 0, дублей пропущено: 3');
      const brokenRows: BotImportResponse = { ...imported, skippedDuplicates: 0, skippedInvalid: 1, errors: ['Строка 3: ошибка'] };
      startImport(facade).flush(brokenRows);
      expect(toast.warning).toHaveBeenLastCalledWith('Импорт завершён с ошибками', 'Добавлено: 2, строк с ошибками: 1');
      expect(toast.success).not.toHaveBeenCalled();
    });

    it('after saving empties the password field, marks the password as saved and reads the tracked city count again', () => {
      const facade = accounts();
      facade.load();
      listRead().flush(page());
      countRead().flush({ cityId: 325, unblockedAccounts: 4 });
      facade.botForm.patchValue({ login: 'new-login', password: 'secret', fio: 'Новый аккаунт', workerId: 1 });
      facade.saveBot();
      const create = http.expectOne((request) => request.method === 'POST' && request.url.endsWith('/bots'));
      expect(create.request.body).toMatchObject({ login: 'new-login', password: 'secret', workerId: 1, statusId: 1, cityId: 325 });
      create.flush({ ...first, id: 12, login: 'new-login', passwordPresent: true });
      expect(selectedId()).toBe(12);
      expect(facade.botForm.controls.password.value).toBe('');
      expect(facade.selectedBotPasswordPresent()).toBe(true);
      listRead().flush(page());
      countRead().flush({ cityId: 325, unblockedAccounts: 5 });
      expect(facade.trackedCityUnblockedAccounts()).toBe(5);

      // A save error is shown even when a field was changed while the request ran.
      facade.saveBot();
      const update = http.expectOne((request) => request.method === 'PUT' && request.url.endsWith('/bots/12'));
      expect(update.request.body.password).toBe('');
      facade.botForm.controls.fio.setValue('Правка во время запроса');
      update.flush({ message: 'Аккаунт с таким логином уже существует' }, { status: 400, statusText: 'Bad Request' });
      expect(toast.error).toHaveBeenCalledWith('Аккаунт не сохранен', expect.stringContaining('логином'));
      expect(facade.error()).toContain('логином');
    });

    it('applies a successful delete even when a field was edited during the request', () => {
      const facade = accounts();
      open(facade, first);
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      facade.deleteSelectedBot();
      const remove = http.expectOne((request) => request.method === 'DELETE' && request.url.endsWith('/bots/8'));
      facade.botForm.controls.fio.setValue('Правка во время удаления');
      remove.flush(null);
      expect(selectedId()).toBeNull();
      expect(facade.botForm.controls.fio.value).toBe('');
      expect(toast.success).toHaveBeenCalledWith('Запись удалена', 'Аккаунты');
    });

    it('stops a login over 45 or a name over 50 characters before the database does', () => {
      const facade = accounts();
      open(facade, first);
      facade.botForm.controls.login.setValue('x'.repeat(46));
      facade.saveBot();
      expect(facade.botForm.controls.login.hasError('maxlength')).toBe(true);
      facade.botForm.controls.login.setValue('x'.repeat(45));
      facade.botForm.controls.fio.setValue('я'.repeat(51));
      facade.saveBot();
      expect(facade.botForm.controls.login.valid).toBe(true);
      expect(facade.botForm.controls.fio.hasError('maxlength')).toBe(true);
      http.expectNone((request) => request.method === 'PUT');
    });
  });
});
