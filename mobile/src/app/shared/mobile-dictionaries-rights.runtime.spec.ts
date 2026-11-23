import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MobileDictionariesComponent } from './mobile-dictionaries.component';

const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
const category = { id: 72, title: 'Тестовая категория', subCategoryCount: 0, subCategories: [] };

describe('mobile dictionary rights and phone fields', () => {
  let fixture: ComponentFixture<MobileDictionariesComponent>;
  let page: MobileDictionariesComponent;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    fixture = TestBed.createComponent(MobileDictionariesComponent);
    page = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => fixture.destroy());

  async function categoryCardText(): Promise<string> {
    fixture.detectChanges();
    http.expectOne('/api/admin/categories').flush([category]);
    http.expectOne('/api/admin/subcategories').flush([]);
    await settle();
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('lets a manager add and rename categories but hides «удалить»', async () => {
    page.adminMode = false;
    const text = await categoryCardText();
    expect(text).toContain('Тестовая категория');
    expect(text).toContain('подкатегория');
    expect(text).not.toContain('удалить');
    expect(page.canCreateActive()).toBe(true);

    page.editorKind.set('categories');
    page.selectedId.set(72);
    expect(page.canDeleteEditor()).toBe(false);

    await page.removeCategory(category);
    http.expectNone((request) => request.method === 'DELETE');
  });

  it('keeps «удалить» for the owner and administrators', async () => {
    page.adminMode = true;
    expect(await categoryCardText()).toContain('удалить');
    page.editorKind.set('categories');
    page.selectedId.set(72);
    expect(page.canDeleteEditor()).toBe(true);
  });

  it('labels an expired device token with its date', () => {
    expect(page.deviceTokenLabel({ token: 'a', active: false, status: 'EXPIRED', expiresAt: '2026-09-03T10:00:00' }))
      .toBe('устройство: истёк 03.09');
    expect(page.deviceTokenLabel({ token: 'b', active: true, status: 'ACTIVE' })).toBe('активное устройство');
    expect(page.deviceTokenLabel({ token: 'c', active: false, status: 'DISABLED' })).toBe('старое устройство');
  });
});
