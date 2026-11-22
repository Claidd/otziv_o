import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { AdminTaxonomyApi } from '../../../core/admin-taxonomy.api';
import { AuthService } from '../../../core/auth.service';
import { ReputationAiApi } from '../../../core/reputation-ai.api';
import { ToastService } from '../../../shared/toast.service';
import { AdminDictionariesComponent } from './admin-dictionaries.component';

function botsPage(cities: { id: number; title: string }[] = [], total = 0) {
  return { bots: [], workers: [], statuses: [], cities, total, page: 0, size: 50, totalPages: 1 };
}

describe('dictionaries menu, counters and entry links', () => {
  const toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
  beforeEach(() => vi.clearAllMocks());

  function create(query: Record<string, string>, admin = true) {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { snapshot: { data: {}, queryParamMap: convertToParamMap(query) } } },
      { provide: AdminTaxonomyApi, useValue: { getCategories: () => of([]), getSubCategories: () => of([]) } },
      { provide: AuthService, useValue: { tokenParsed: () => null, hasAnyRealmRole: () => admin, hasRealmRole: () => admin } },
      { provide: ReputationAiApi, useValue: {} },
      { provide: ToastService, useValue: toast }
    ] });
    TestBed.overrideComponent(AdminDictionariesComponent, { set: { template: '', imports: [] } });
    return TestBed.createComponent(AdminDictionariesComponent);
  }

  it('groups all fourteen tabs for an administrator and only categories for a manager', () => {
    const fixture = create({ tab: 'categories' });
    const groups = fixture.componentInstance.menuGroups();
    expect(groups.map((group) => group.label)).toEqual(['Каталог', 'Тексты', 'Доступы', 'Автоответчик', 'Управление']);
    expect(groups.flatMap((group) => group.tabs.map((tab) => tab.key))).toEqual([
      'categories', 'products', 'cities', 'promo', 'managerTexts', 'messageDictionary', 'phones', 'accounts',
      'autoresponder', 'autoresponderMonitor', 'settings', 'audit', 'aiProvider', 'specialistTransfer'
    ]);
    fixture.destroy();
    TestBed.resetTestingModule();

    const managerFixture = create({ tab: 'phones' }, false);
    expect(managerFixture.componentInstance.menuGroups().flatMap((group) => group.tabs.map((tab) => tab.key))).toEqual(['categories']);
    managerFixture.destroy();
  });

  it('shows a record count only for tabs opened on this visit and never while the list loads', () => {
    const fixture = create({ tab: 'phones' });
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    const phones = http.expectOne((request) => request.url.endsWith('/phones'));
    expect(page.tabTotal('phones')).toBeNull();
    expect(page.tabTotal('cities')).toBeNull();
    phones.flush({ phones: [], operators: [] });
    expect(page.tabTotal('phones')).toBe(0);
    expect(page.tabTotal('cities')).toBeNull();
    expect(page.tabTotal('settings')).toBeNull();
    page.setTab('cities');
    http.expectOne((request) => request.url.endsWith('/cities')).flush([{ id: 1, title: 'Тестовый город', latitude: null, longitude: null, distanceMatrixReady: false, distanceCount: 0 }]);
    expect(page.tabTotal('cities')).toBe(1);
    expect(page.tabTotal('phones')).toBe(0);
    expect(page.helpFacts().map((fact) => fact.label)).toEqual(['Городов', 'С координатами', 'Близость рассчитана']);
    fixture.destroy();
    http.verify();
  });

  it('opens the accounts of a city from the «Боты» link of the cities page', () => {
    const fixture = create({ cityId: '5', section: 'bots' });
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    expect(page.activeTab()).toBe('accounts');
    const first = http.expectOne((request) => request.url.endsWith('/bots'));
    expect(first.request.params.get('keyword')).toBeNull();
    http.match((request) => request.url.endsWith('/bots/unblocked-count')).forEach((request) => request.flush({ cityId: 325, unblockedAccounts: 0 }));
    first.flush(botsPage([{ id: 5, title: 'Тестовый город' }], 10));
    expect(page.search()).toBe('Тестовый город');
    const narrowed = http.expectOne((request) => request.url.endsWith('/bots'));
    expect(narrowed.request.params.get('keyword')).toBe('Тестовый город');
    narrowed.flush(botsPage([{ id: 5, title: 'Тестовый город' }], 2));
    http.expectNone((request) => request.url.endsWith('/bots'));
    fixture.destroy();
    http.verify();
  });

  it('opens one account from a ?botId= link, narrows the list to its login and keeps a ?q= search for list tabs', () => {
    const fixture = create({ botId: '18' });
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    expect(page.activeTab()).toBe('accounts');
    expect(page.search()).toBe('');
    const list = http.expectOne((request) => request.url.endsWith('/bots'));
    expect(list.request.params.get('keyword')).toBeNull();
    list.flush(botsPage([], 40));
    http.expectOne((request) => request.url.endsWith('/bots/18')).flush({
      id: 18, login: 'login-18', fio: 'Тестовый аккаунт', worker: null, city: null, status: null,
      counter: 3, active: true, passwordPresent: true
    });
    TestBed.tick();
    expect(page.search()).toBe('login-18');
    expect(page.searchDraft()).toBe('login-18');
    expect(http.expectOne((request) => request.url.endsWith('/bots')).request.params.get('keyword')).toBe('login-18');
    fixture.destroy();
    TestBed.resetTestingModule();

    const cities = create({ tab: 'cities', q: 'Тест' });
    const citiesHttp = TestBed.inject(HttpTestingController);
    expect(cities.componentInstance.search()).toBe('Тест');
    expect(citiesHttp.expectOne((request) => request.url.endsWith('/cities')).request.params.get('keyword')).toBe('Тест');
    cities.destroy();
  });

  it('a page opened in a background browser tab loads its list once the tab becomes visible', () => {
    let visibility: DocumentVisibilityState = 'hidden';
    const spy = vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility);
    const fixture = create({ tab: 'phones' });
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    http.expectNone((request) => request.url.endsWith('/phones'));
    expect(page.tabTotal('phones')).toBeNull();
    visibility = 'visible';
    page.onDocumentVisibilityChange();
    http.expectOne((request) => request.url.endsWith('/phones')).flush({ phones: [], operators: [] });
    page.onDocumentVisibilityChange();
    http.expectNone((request) => request.url.endsWith('/phones'));
    fixture.destroy();
    http.verify();
    spy.mockRestore();
  });

  it('asks before dropping typed edits when another tab or record is opened, not while a save is pending', () => {
    const fixture = create({ tab: 'phones' });
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    const phoneA = { id: 1, number: '+79990000001', fio: 'A', amountAllowed: 1, amountSent: 0, blockTime: 3, active: true,
      googleLoginPresent: false, googlePasswordPresent: false, avitoPasswordPresent: false, mailLoginPresent: false,
      mailPasswordPresent: false, deviceTokens: [] };
    const phoneB = { ...phoneA, id: 2, number: '+79990000002', fio: 'B' };
    http.expectOne((request) => request.url.endsWith('/phones')).flush({ phones: [phoneA, phoneB], operators: [] });
    page.pickPhone(phoneA as never);
    page.phoneForm.controls.fio.setValue('A edited');
    page.phoneForm.markAsDirty();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    page.setTab('cities');
    expect(page.activeTab()).toBe('phones');
    page.pickPhone(phoneB as never);
    expect(page.selectedPhone()?.id).toBe(1);
    expect(confirm).toHaveBeenCalledTimes(2);
    page.saveActive();
    const write = http.expectOne((request) => request.method === 'PUT');
    page.pickPhone(phoneB as never);
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(page.selectedPhone()?.id).toBe(2);
    write.flush({ ...phoneA, fio: 'A edited' });
    confirm.mockReturnValue(true);
    page.phoneForm.markAsDirty();
    page.setTab('cities');
    expect(page.activeTab()).toBe('cities');
    confirm.mockRestore();
    fixture.destroy();
  });

  it('applies only a submitted search and keeps the open tab when its menu item is clicked again', () => {
    const fixture = create({ tab: 'cities' });
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne((request) => request.url.endsWith('/cities')).flush([]);
    page.searchDraft.set('Тест');
    page.loadAll();
    expect(http.expectOne((request) => request.url.endsWith('/cities')).request.params.get('keyword')).toBeNull();
    page.searchActive();
    const searched = http.expectOne((request) => request.url.endsWith('/cities'));
    expect(searched.request.params.get('keyword')).toBe('Тест');
    searched.flush([]);
    expect(page.tabTotal('cities')).toBeNull();
    expect(page.helpFacts()).toEqual([{ label: 'Найдено по запросу «Тест»', value: 0 }]);
    page.cityForm.controls.title.setValue('Черновик');
    page.cityForm.markAsDirty();
    page.setTab('cities');
    http.expectNone((request) => request.url.endsWith('/cities'));
    expect(page.cityForm.controls.title.value).toBe('Черновик');
    expect(page.search()).toBe('Тест');
    fixture.destroy();
    http.verify();
  });

  it('a save cut off by hiding the page re-reads the list on return and asks to check the record', () => {
    let visibility: DocumentVisibilityState = 'visible';
    const spy = vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility);
    const fixture = create({ tab: 'cities' });
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne((request) => request.url.endsWith('/cities')).flush([]);
    page.cityForm.controls.title.setValue('Новый город');
    page.cityForm.markAsDirty();
    page.saveActive();
    const write = http.expectOne((request) => request.method === 'POST');
    visibility = 'hidden';
    page.onDocumentVisibilityChange();
    write.flush({ id: 7, title: 'Новый город', latitude: null, longitude: null, distanceMatrixReady: false, distanceCount: 0 });
    expect(toast.warning).not.toHaveBeenCalled();
    visibility = 'visible';
    page.onDocumentVisibilityChange();
    expect(toast.warning).toHaveBeenCalledWith('Проверьте последнее действие', expect.stringContaining('Список обновлён'));
    http.expectOne((request) => request.method === 'GET' && request.url.endsWith('/cities')).flush([]);
    page.onDocumentVisibilityChange();
    expect(toast.warning).toHaveBeenCalledTimes(1);
    fixture.destroy();
    spy.mockRestore();
  });

  it('opens a focused row with Enter or Space and leaves the keys of controls inside the row alone', () => {
    const fixture = create({ tab: 'categories' });
    const page = fixture.componentInstance;
    const row = document.createElement('tr');
    const button = document.createElement('button');
    row.append(button);
    const results: boolean[] = [];
    row.addEventListener('keydown', (event) => results.push(page.rowKey(event)));
    button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    const onRow = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true });
    row.dispatchEvent(onRow);
    expect(results).toEqual([false, true]);
    expect(onRow.defaultPrevented).toBe(true);
    fixture.destroy();
  });

  it('names typed city coordinates that are not numbers or out of range', () => {
    const fixture = create({ tab: 'cities' });
    const page = fixture.componentInstance;
    page.cityForm.setValue({ title: 'Город', latitude: '52,287', longitude: '' });
    expect(page.cityCoordinatesInvalid()).toBe(false);
    page.cityForm.controls.latitude.setValue('52.2.8');
    expect(page.cityCoordinatesInvalid()).toBe(true);
    page.cityForm.controls.latitude.setValue('52.28');
    page.cityForm.controls.longitude.setValue('-181');
    expect(page.cityCoordinatesInvalid()).toBe(true);
    fixture.destroy();
  });

  it('asks before «Обновить» drops typed values only on «Рабочие настройки»', () => {
    const fixture = create({ tab: 'cities' });
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne((request) => request.url.endsWith('/cities')).flush([]);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    page.cityForm.controls.title.setValue('Черновик');
    page.cityForm.markAsDirty();
    page.loadAll();
    expect(confirm).not.toHaveBeenCalled();
    http.expectOne((request) => request.url.endsWith('/cities')).flush([]);
    expect(page.cityForm.controls.title.value).toBe('Черновик');
    fixture.destroy();
    TestBed.resetTestingModule();

    const settingsFixture = create({ tab: 'settings' });
    const settingsPage = settingsFixture.componentInstance;
    const settingsHttp = TestBed.inject(HttpTestingController);
    const reads = settingsHttp.match(() => true).length;
    settingsPage.settingsForm.markAsDirty();
    settingsPage.loadAll();
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(settingsHttp.match(() => true)).toHaveLength(0);
    expect(reads).toBeGreaterThan(0);
    confirm.mockRestore();
    settingsFixture.destroy();
  });

  it('names performer platforms in words', () => {
    const fixture = create({ tab: 'categories' });
    const page = fixture.componentInstance;
    expect(page.productPlatformLabel('YANDEX')).toBe('Яндекс');
    expect(page.productPlatformLabel('GIS')).toBe('2ГИС');
    expect(page.productPlatformLabel(null)).toBe('площадка не выбрана');
    fixture.destroy();
  });
});
