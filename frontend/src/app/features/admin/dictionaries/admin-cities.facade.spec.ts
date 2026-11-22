import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminCitiesApi } from '../../../core/admin-cities.api';
import { AdminCitiesFacade } from './admin-cities.facade';

describe('city dictionary commands and view lifetime', () => {
  let http: HttpTestingController;
  let facade: AdminCitiesFacade;
  let active: boolean;
  const selectedId = signal<number | null>(null);
  const search = signal('');
  const toast = { success: vi.fn(), error: vi.fn() };
  const city = { id: 18, title: 'Иркутск', latitude: 52.3, longitude: 104.3, distanceMatrixReady: true, distanceCount: 2 };
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    active = true;
    selectedId.set(null);
    search.set('');
    vi.clearAllMocks();
    facade = new AdminCitiesFacade({ api: TestBed.inject(AdminCitiesApi), toast,
      isActive: () => active, selectedId, search });
  });
  afterEach(() => { facade.destroy(); http.verify({ ignoreCancelled: true }); vi.restoreAllMocks(); });

  it('cancels obsolete searches and the final read on destroy', () => {
    facade.load();
    const first = http.expectOne(request => request.url.endsWith('/cities'));
    search.set('  Иркутск  ');
    facade.load();
    expect(first.cancelled).toBe(true);
    const second = http.expectOne(request => request.params.get('keyword') === 'Иркутск');
    second.flush([city]);
    expect(facade.cities()).toEqual([city]);
    expect(facade.loading()).toBe(false);
    facade.load();
    const third = http.expectOne(request => request.url.endsWith('/cities'));
    facade.destroy();
    expect(third.cancelled).toBe(true);
    expect(facade.loading()).toBe(false);
  });

  it('snapshots comma coordinates, never applies save A to a reopened A editor, but reports it and rereads the list', () => {
    facade.selectCity(city);
    facade.cityForm.patchValue({ title: '  Иркутск новый  ', latitude: '52,4', longitude: '' });
    facade.saveCity();
    facade.saveCity();
    const write = http.expectOne(request => request.method === 'PUT' && request.url.endsWith('/cities/18'));
    expect(write.request.body).toEqual({ title: 'Иркутск новый', latitude: 52.4, longitude: null });
    facade.selectCity({ ...city, id: 19, title: 'Другой город' });
    facade.selectCity(city);
    facade.cityForm.controls.title.setValue('Новый черновик');
    write.flush(city);
    expect(facade.saving()).toBe(false);
    expect(selectedId()).toBe(18);
    expect(facade.cityForm.controls.title.value).toBe('Новый черновик');
    // The saved city is named, and the list is read again so its row does not keep the old coordinates.
    expect(toast.success).toHaveBeenCalledWith('Город сохранен', '«Иркутск новый», ID 18');
    http.expectOne(request => request.method === 'GET' && request.url.endsWith('/cities')).flush([city]);
    expect(facade.cityForm.controls.title.value).toBe('Новый черновик');
  });

  it('reports a failed save of a city that is no longer open', () => {
    facade.selectCity(city);
    facade.saveCity();
    const write = http.expectOne(request => request.method === 'PUT' && request.url.endsWith('/cities/18'));
    facade.selectCity({ ...city, id: 19, title: 'Другой город' });
    write.flush({ message: 'Город с таким названием уже есть' }, { status: 409, statusText: 'Conflict' });
    expect(facade.error()).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('Город не сохранен', '«Иркутск»: Город с таким названием уже есть');
  });

  it('reports a save that ends after the tab was left', () => {
    facade.selectCity(city);
    facade.saveCity();
    const write = http.expectOne(request => request.method === 'PUT' && request.url.endsWith('/cities/18'));
    active = false;
    facade.deactivate();
    write.flush({ message: 'Ошибка сервера' }, { status: 500, statusText: 'Server Error' });
    expect(toast.error).toHaveBeenCalledWith('Город не сохранен', '«Иркутск»: Ошибка сервера');
    expect(facade.saving()).toBe(false);
    http.expectNone(() => true);
  });

  it('rejects invalid coordinates without submitting a command', () => {
    facade.selectCity(city);
    for (const latitude of ['91', 'NaN', 'Infinity']) {
      facade.cityForm.controls.latitude.setValue(latitude);
      facade.saveCity();
      expect(facade.error()).toContain('широта');
    }
    expect(facade.saving()).toBe(false);
    http.expectNone(() => true);
  });

  it('reserves deletion before confirmation and captures the selected city', () => {
    facade.selectCity(city);
    const confirm = vi.spyOn(window, 'confirm').mockImplementation(() => { facade.deleteSelected(); return true; });
    facade.deleteSelected();
    const write = http.expectOne(request => request.method === 'DELETE' && request.url.endsWith('/cities/18'));
    expect(confirm).toHaveBeenCalledOnce();
    facade.selectCity({ ...city, id: 19, title: 'Новая форма' });
    write.flush(null);
    expect(selectedId()).toBe(19);
    expect(facade.cityForm.controls.title.value).toBe('Новая форма');
    expect(facade.deleting()).toBe(false);
    expect(toast.success).toHaveBeenCalledWith('Запись удалена', 'Города');
    http.expectOne(request => request.method === 'GET' && request.url.endsWith('/cities')).flush([]);
    expect(selectedId()).toBe(19);
  });

  it('keeps distance rebuild alive on navigation and does not replay it or update another view', () => {
    facade.selectCity(city);
    facade.load();
    const read = http.expectOne(request => request.method === 'GET');
    facade.rebuildSelectedCityDistances();
    const write = http.expectOne(request => request.url.endsWith('/cities/18/distances/rebuild'));
    expect(write.request.body).toEqual({});
    expect(read.cancelled).toBe(true);
    facade.rebuildSelectedCityDistances();
    active = false;
    facade.deactivate();
    expect(write.cancelled).toBe(false);
    selectedId.set(99);
    write.flush({ citiesWithCoordinates: 2, citiesWithoutCoordinates: 1, distancesSaved: 2 });
    expect(facade.rebuildingCityDistances()).toBe(false);
    expect(selectedId()).toBe(99);
    expect(toast.success).not.toHaveBeenCalled();
    http.expectNone(() => true);
  });

  it('retains the uploaded file and displays partial import counts without clearing a city draft', () => {
    facade.selectCity(city);
    const file = new File(['city,lat,lon\n18,52,104'], 'coordinates.csv', { type: 'text/csv' });
    const input = { files: [file], value: 'coordinates.csv' };
    facade.importCityCoordinates({ target: input } as unknown as Event);
    const write = http.expectOne(request => request.url.endsWith('/cities/coordinates/import'));
    expect(write.request.body instanceof FormData).toBe(true);
    expect((write.request.body as FormData).get('file')).toBe(file);
    expect(input.value).toBe('');
    facade.cityForm.controls.title.setValue('Несохраненное название');
    write.flush({ updated: 1, skipped: 2, errors: ['row 3'], citiesWithCoordinates: 1,
      citiesWithoutCoordinates: 2, distancesSaved: 5 });
    expect(toast.success).toHaveBeenCalledWith('Координаты загружены', 'обновлено: 1, пропущено: 2, ошибок: 1, связей: 5');
    http.expectOne(request => request.method === 'GET').flush([city]);
    expect(facade.cityForm.controls.title.value).toBe('Несохраненное название');
    expect(selectedId()).toBe(18);
  });

  it('preserves the global rebuild threshold', () => {
    TestBed.inject(AdminCitiesApi).rebuildCityDistances().subscribe();
    const write = http.expectOne(request => request.url.endsWith('/cities/distances/rebuild'));
    expect(write.request.params.get('minCityId')).toBe('150');
    expect(write.request.method).toBe('POST');
    expect(write.request.body).toEqual({});
    write.flush({ citiesWithCoordinates: 2, citiesWithoutCoordinates: 1, distancesSaved: 2 });
  });
});
