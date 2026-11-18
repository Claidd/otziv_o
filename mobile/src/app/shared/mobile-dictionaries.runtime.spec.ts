import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MobileDictionariesComponent } from './mobile-dictionaries.component';
import { MobileThemeService } from './mobile-theme.service';

const cities = [{ id: 1, title: 'Иркутск' }, { id: 2, title: 'Тюмень' }];
const result = { added: 1, totalRows: 1, skippedDuplicates: 0, skippedInvalid: 0, duplicates: [] };
const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

describe('mobile dictionary account import', () => {
  let fixture: ComponentFixture<MobileDictionariesComponent>;
  let page: MobileDictionariesComponent;
  let http: HttpTestingController;
  const chooseFile = () => {
    const file = new File(['mobile-test:synthetic-password'], 'accounts.txt', { type: 'text/plain' });
    page.selectBotImportFile({ target: { files: [file], value: 'accounts.txt' } } as unknown as Event);
    return file;
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    fixture = TestBed.createComponent(MobileDictionariesComponent);
    page = fixture.componentInstance;
    page.adminMode = true;
    page.activeTab.set('accounts');
    page.botCities.set(cities);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { fixture.destroy(); http.verify(); });

  it('requires an explicitly selected city, not just search text, before posting', async () => {
    page.openBotImport('city');
    chooseFile();
    page.setImportCitySearch('тюм');
    expect(page.filteredImportCities()).toEqual([cities[1]]);
    expect(page.canUploadBotImport()).toBe(false);
    await page.uploadBotImport();
    http.expectNone(r => r.method === 'POST');
    expect(page.importError()).toContain('Выберите город');
    page.selectBotImportCity(cities[1]);
    expect(page.canUploadBotImport()).toBe(true);
    page.setImportCitySearch('Иркутск');
    expect(page.selectedImportCity()).toBeNull();
    expect(page.canUploadBotImport()).toBe(false);
  });

  it('sends one import with the selected city and refreshes the account list', async () => {
    page.openBotImport('city');
    page.selectBotImportCity(cities[1]);
    const file = chooseFile();
    const pending = page.uploadBotImport();
    const request = http.expectOne('/api/admin/bots/import?cityId=2');
    expect(request.request.method).toBe('POST');
    expect(request.request.body.get('file')).toBe(file);
    await page.uploadBotImport();
    http.expectNone(r => r.method === 'POST');
    page.closeBotImport();
    page.selectBotImportCity(cities[0]);
    expect(page.importModalOpen()).toBe(true);
    expect(page.importCityId()).toBe(2);
    request.flush(result);
    await settle();
    http.expectOne(r => r.url === '/api/admin/bots').flush({ bots: [], cities, workers: [], statuses: [], total: 1, totalPages: 1, page: 0, size: 50 });
    await pending;
    expect(page.importResult()).toEqual(result);
    expect(page.botsTotal()).toBe(1);
    expect(page.importFile()).toBeNull();
    expect(page.canUploadBotImport()).toBe(false);
  });

  it('cancels without importing and clears the selected file and city on reopening', async () => {
    page.openBotImport('city');
    page.selectBotImportCity(cities[0]);
    chooseFile();
    page.closeBotImport();
    await page.uploadBotImport();
    page.openBotImport('city');
    expect(page.importFile()).toBeNull();
    expect(page.importCityId()).toBeNull();
    http.expectNone(r => r.method === 'POST');
  });

  it('keeps ordinary file import without a city and allows retry after a server error', async () => {
    page.openBotImport('file');
    chooseFile();
    const pending = page.uploadBotImport();
    const request = http.expectOne('/api/admin/bots/import');
    expect(request.request.params.has('cityId')).toBe(false);
    request.flush({ message: 'Недопустимый файл' }, { status: 400, statusText: 'Bad Request' });
    await pending;
    expect(page.importing()).toBe(false);
    expect(page.importFile()).not.toBeNull();
    expect(page.importError()).toBeTruthy();
    expect(page.canUploadBotImport()).toBe(true);
  });
});

describe('mobile dictionary theme', () => {
  let fixture: ComponentFixture<MobileDictionariesComponent>;
  let theme: MobileThemeService;
  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    fixture = TestBed.createComponent(MobileDictionariesComponent);
    theme = TestBed.inject(MobileThemeService);
    fixture.componentInstance.adminMode = true;
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/admin/categories').flush([{ id: 1, title: 'Категория', subCategoryCount: 0, subCategories: [] }]);
    http.expectOne('/api/admin/subcategories').flush([]);
    await settle();
    fixture.detectChanges();
    http.verify();
  });
  afterEach(() => { theme.setTheme('light'); fixture.destroy(); localStorage.removeItem('otziv-theme'); });

  it('uses the real theme switch for dictionary cards and restores their light background', () => {
    const card = fixture.nativeElement.querySelector('.dict-card') as HTMLElement;
    expect(card).not.toBeNull();
    theme.setTheme('light');
    const light = getComputedStyle(card).backgroundImage;
    theme.setTheme('dark');
    fixture.detectChanges();
    expect(getComputedStyle(card).backgroundImage).not.toBe(light);
    expect(getComputedStyle(card).backgroundImage).toContain('31, 38, 41');
    theme.setTheme('light');
    expect(getComputedStyle(card).backgroundImage).toBe(light);
  });
});
