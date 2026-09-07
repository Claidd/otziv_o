import { AdminDictionariesApi } from '../../../core/admin-dictionaries.api';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import type {
  AdminCategory,
  AdminManagerText,
  AdminProduct,
  PromoButtonSlot,
} from '../../../core/admin-dictionaries.api';
import { AuthService } from '../../../core/auth.service';
import { ReputationAiApi } from '../../../core/reputation-ai.api';
import { ToastService } from '../../../shared/toast.service';
import { AdminDictionariesComponent } from './admin-dictionaries.component';
import {
  messageSettingsReadFixture,
  messageSettingsWriteFixture,
} from './message-settings-request.fixture';

const category = (id: number): AdminCategory => ({
  id,
  title: `Category ${id}`,
  subCategoryCount: 1,
  subCategories: [{ id: id * 10, title: `Child ${id}` }],
});
const product = (id: number): AdminProduct => ({
  id,
  title: `Product ${id}`,
  price: 123.45,
  category: { id: 1, title: 'Category' },
  photo: true,
  requiresPerformer: true,
  targetPlatform: 'YANDEX',
  performerRewardPercent: 20,
  specialistRewardPercent: 10,
  managerRewardPercent: 5,
});
const manager = (id: number): AdminManagerText => ({
  managerId: id,
  managerTitle: `Manager ${id}`,
  payText: `Pay ${id}`,
  beginText: 'Begin',
  offerText: 'Offer',
  reminderText: 'Reminder',
  startText: 'Start',
});
const button: PromoButtonSlot = {
  section: 'FIRST',
  sectionTitle: 'First',
  buttonKey: 'offer',
  buttonLabel: 'Offer',
  outputPosition: 1,
  defaultPromoPosition: 1,
  defaultPromoTextId: 7,
};
const promo = () => ({
  texts: [{ id: 7, position: 1, text: 'Offer text' }],
  managers: [
    { id: 1, title: 'A' },
    { id: 2, title: 'B' },
  ],
  assignments: [],
  buttons: [button],
});

describe('catalog and communication editor lifetimes through the real dictionary component', () => {
  let http: HttpTestingController;
  function create(tab: string, admin = true) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AdminDictionariesApi,
          useFactory: () => {
            throw new Error('Dictionary features must use their owner API');
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { data: {}, queryParamMap: convertToParamMap({ tab }) } },
        },
        {
          provide: AuthService,
          useValue: {
            tokenParsed: () => null,
            hasAnyRealmRole: () => admin,
            hasRealmRole: () => admin,
          },
        },
        { provide: ReputationAiApi, useValue: {} },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
      ],
    });
    TestBed.overrideComponent(AdminDictionariesComponent, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    return TestBed.createComponent(AdminDictionariesComponent);
  }
  function flush(path: string, body: object) {
    http.expectOne((request) => request.method === 'GET' && request.url.endsWith(path)).flush(body);
  }
  function catalogReload() {
    flush('/categories', [category(1), category(2)]);
    flush('/subcategories', []);
  }
  afterEach(() => {
    http?.verify();
    vi.restoreAllMocks();
  });

  for (const aba of [false, true]) {
    it(`does not let a manager A save replace ${aba ? 'a later visit to A' : 'manager B'}`, () => {
      const fixture = create('managerTexts');
      const page = fixture.componentInstance;
      flush('/manager-texts', [manager(1), manager(2)]);
      page.selectManagerText(manager(1));
      page.managerTextForm.controls.payText.setValue('Submitted A');
      page.saveActive();
      const write = http.expectOne('/api/admin/manager-texts/1');
      expect(write.request.method).toBe('PUT');
      expect(write.request.body).toEqual({
        payText: 'Submitted A',
        beginText: 'Begin',
        offerText: 'Offer',
        reminderText: 'Reminder',
        startText: 'Start',
      });
      page.selectManagerText(manager(2));
      if (aba) page.selectManagerText(manager(1));
      page.managerTextForm.controls.payText.setValue('Current draft');
      write.flush({ ...manager(1), payText: 'Server-normalized A' });
      expect(page.selectedId()).toBe(aba ? 1 : 2);
      expect(page.managerTextForm.controls.payText.value).toBe('Current draft');
      expect(page.activeSaving()).toBe(false);
      fixture.destroy();
    });
  }

  it('preserves edits made during the same manager save while updating the saved list row', () => {
    const fixture = create('managerTexts');
    const page = fixture.componentInstance;
    flush('/manager-texts', [manager(1)]);
    page.selectManagerText(manager(1));
    page.saveActive();
    const write = http.expectOne('/api/admin/manager-texts/1');
    page.managerTextForm.controls.offerText.setValue('New unsent offer');
    write.flush({ ...manager(1), offerText: 'Previously submitted offer' });
    expect(page.managerTextForm.controls.offerText.value).toBe('New unsent offer');
    expect(page.managerTexts()[0].offerText).toBe('Previously submitted offer');
    fixture.destroy();
  });

  for (const aba of [false, true]) {
    it(`keeps a product ${aba ? 'ABA' : 'A to B'} selection while an earlier update completes`, () => {
      const fixture = create('products');
      const page = fixture.componentInstance;
      flush('/products', {
        products: [product(1), product(2)],
        categories: [{ id: 1, title: 'Category' }],
      });
      page.selectProduct(product(1));
      page.saveActive();
      const write = http.expectOne('/api/admin/products/1');
      expect(write.request.body).toEqual({
        title: 'Product 1',
        price: 123.45,
        categoryId: 1,
        photo: true,
        requiresPerformer: true,
        targetPlatform: 'YANDEX',
        performerRewardPercent: 20,
        specialistRewardPercent: 10,
        managerRewardPercent: 5,
      });
      page.selectProduct(product(2));
      if (aba) page.selectProduct(product(1));
      page.productForm.controls.title.setValue('Unsaved current product');
      write.flush(product(1));
      flush('/products', {
        products: [product(1), product(2)],
        categories: [{ id: 1, title: 'Category' }],
      });
      expect(page.selectedId()).toBe(aba ? 1 : 2);
      expect(page.productForm.controls.title.value).toBe('Unsaved current product');
      fixture.destroy();
    });
  }

  it('retains the created product id without replacing edits made during create', () => {
    const fixture = create('products');
    const page = fixture.componentInstance;
    flush('/products', { products: [], categories: [{ id: 1, title: 'Category' }] });
    page.clearSelection();
    page.productForm.controls.title.setValue('Submitted product');
    page.saveActive();
    const write = http.expectOne(
      (request) => request.method === 'POST' && request.url.endsWith('/products'),
    );
    page.productForm.controls.title.setValue('Continued draft');
    page.saveActive();
    http.expectNone((request) => request.method === 'POST');
    write.flush(product(7));
    flush('/products', { products: [product(7)], categories: [{ id: 1, title: 'Category' }] });
    expect(page.selectedId()).toBe(7);
    expect(page.productForm.controls.title.value).toBe('Continued draft');
    fixture.destroy();
  });

  it('does not clear another product editor when a delete finishes', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const fixture = create('products');
    const page = fixture.componentInstance;
    flush('/products', { products: [product(1), product(2)], categories: [] });
    page.selectProduct(product(1));
    page.deleteSelected();
    const write = http.expectOne('/api/admin/products/1');
    expect(write.request.method).toBe('DELETE');
    page.selectProduct(product(2));
    write.flush(null);
    flush('/products', { products: [product(2)], categories: [] });
    expect(page.selectedId()).toBe(2);
    expect(page.productForm.controls.title.value).toBe('Product 2');
    fixture.destroy();
  });

  for (const aba of [false, true]) {
    it(`keeps the current category modal across ${aba ? 'ABA' : 'A to B'} while save A completes`, () => {
      const fixture = create('categories');
      const page = fixture.componentInstance;
      flush('/categories', [category(1), category(2)]);
      expect(page.selectedCategorySubCategories()[0].id).toBe(10);
      page.editCategory(new Event('click'), category(1));
      page.saveCategory();
      const write = http.expectOne('/api/admin/categories/1');
      page.editCategory(new Event('click'), category(2));
      if (aba) page.editCategory(new Event('click'), category(1));
      page.categoryForm.controls.title.setValue('Current category draft');
      write.flush(category(1));
      catalogReload();
      expect(page.editingCategoryId()).toBe(aba ? 1 : 2);
      expect(page.categoryEditorOpen()).toBe(true);
      expect(page.categoryForm.controls.title.value).toBe('Current category draft');
      fixture.destroy();
    });
  }

  it('keeps a category create modal and its new draft open while retaining the saved id', () => {
    const fixture = create('categories');
    const page = fixture.componentInstance;
    flush('/categories', [category(1), category(2)]);
    page.openNewCategory();
    page.categoryForm.controls.title.setValue('Submitted');
    page.saveCategory();
    const write = http.expectOne((request) => request.method === 'POST');
    page.categoryForm.controls.title.setValue('Further editing');
    write.flush(category(7));
    flush('/categories', [category(1), category(2), category(7)]);
    flush('/subcategories', []);
    expect(page.editingCategoryId()).toBe(7);
    expect(page.categoryEditorOpen()).toBe(true);
    expect(page.categoryForm.controls.title.value).toBe('Further editing');
    fixture.destroy();
  });

  it('keeps the selected subcategory and parent when another subcategory save returns', () => {
    const fixture = create('categories');
    const page = fixture.componentInstance;
    flush('/categories', [category(1), category(2)]);
    page.selectSubCategory({ id: 10, title: 'Child A', category: { id: 1, title: 'A' } });
    page.saveSelectedSubCategory();
    const write = http.expectOne('/api/admin/subcategories/10');
    expect(write.request.body).toEqual({ title: 'Child A', categoryId: 1 });
    page.selectSubCategory({ id: 20, title: 'Child B', category: { id: 2, title: 'B' } });
    write.flush({ id: 10, title: 'Child A', category: { id: 1, title: 'A' } });
    catalogReload();
    expect(page.editingSubCategoryId()).toBe(20);
    expect(page.subCategoryForm.controls.categoryId.value).toBe(2);
    expect(page.subCategoryForm.controls.title.value).toBe('Child B');
    fixture.destroy();
  });

  it('does not grant taxonomy mutations to a manager who can read categories', () => {
    const fixture = create('categories', false);
    const page = fixture.componentInstance;
    flush('/categories', [category(1)]);
    page.editCategory(new Event('click'), category(1));
    page.saveCategory();
    http.expectNone((request) => request.method !== 'GET');
    fixture.destroy();
  });

  it('uses a composite manager/button assignment and ignores its old manager visit', () => {
    const fixture = create('promo');
    const page = fixture.componentInstance;
    flush('/promo-texts/management', promo());
    page.selectPromoManager(1);
    page.savePromoAssignment(button, '7');
    const write = http.expectOne('/api/admin/promo-text-assignments');
    expect(write.request.body).toEqual({
      managerId: 1,
      section: 'FIRST',
      buttonKey: 'offer',
      promoTextId: 7,
    });
    page.selectPromoManager(2);
    page.selectPromoManager(1);
    write.flush({ id: 4 });
    expect(page.selectedPromoManagerId()).toBe(1);
    http.expectNone(() => true);
    fixture.destroy();
  });

  it('does not reset a newer promo editor after a create response', () => {
    const fixture = create('promo');
    const page = fixture.componentInstance;
    flush('/promo-texts/management', promo());
    page.clearSelection();
    page.promoTextForm.controls.text.setValue('Created text');
    page.savePromoText();
    const write = http.expectOne('/api/admin/promo-texts');
    expect(write.request.body).toEqual({ text: 'Created text' });
    page.selectPromoText({ id: 7, position: 1, text: 'Current text' });
    write.flush({ id: 8, position: 2, text: 'Created text' });
    flush('/promo-texts/management', promo());
    expect(page.selectedId()).toBe(7);
    expect(page.promoTextForm.controls.text.value).toBe('Current text');
    fixture.destroy();
  });

  for (const tab of ['products', 'categories', 'promo', 'managerTexts', 'autoresponder']) {
    it(`${tab}: a submitted write survives tab leave and cannot affect the next tab`, () => {
      const fixture = create(tab);
      const page = fixture.componentInstance;
      if (tab === 'products') {
        flush('/products', { products: [product(1)], categories: [] });
        page.selectProduct(product(1));
      }
      if (tab === 'categories') {
        flush('/categories', [category(1)]);
        page.editCategory(new Event('click'), category(1));
      }
      if (tab === 'promo') {
        flush('/promo-texts/management', promo());
        page.selectPromoText({ id: 7, position: 1, text: 'Offer' });
      }
      if (tab === 'managerTexts') {
        flush('/manager-texts', [manager(1)]);
        page.selectManagerText(manager(1));
      }
      if (tab === 'autoresponder') flush('/settings/client-messages', messageSettingsReadFixture);
      page.saveActive();
      const write = http.expectOne((request) => request.method === 'PUT');
      page.setTab('cities');
      flush('/cities', []);
      page.selectedId.set(333);
      page.cityForm.controls.title.setValue('New city draft');
      expect(write.cancelled).toBe(false);
      write.flush(
        tab === 'managerTexts'
          ? manager(1)
          : tab === 'autoresponder'
            ? messageSettingsWriteFixture
            : { id: 1 },
      );
      expect(page.selectedId()).toBe(333);
      expect(page.cityForm.controls.title.value).toBe('New city draft');
      expect(page.activeSaving()).toBe(false);
      http.expectNone(() => true);
      fixture.destroy();
    });
  }

  it('preserves the full pre-extraction settings PUT while retaining a newer draft', () => {
    const fixture = create('autoresponder');
    const page = fixture.componentInstance;
    flush('/settings/client-messages', messageSettingsReadFixture);
    page.saveActive();
    const write = http.expectOne('/api/admin/settings/client-messages');
    expect(write.request.body).toEqual(messageSettingsWriteFixture);
    page.autoresponderForm.controls.paymentReminderText.setValue('New unsent text');
    write.flush(messageSettingsWriteFixture);
    expect(page.autoresponderForm.controls.paymentReminderText.value).toBe('New unsent text');
    expect(page.clientMessageSettings()?.paymentReminderText).toBe(
      messageSettingsWriteFixture.paymentReminderText,
    );
    fixture.destroy();
  });

  it('does not overwrite a settings edit with a late refresh or reset response', () => {
    const fixture = create('autoresponder');
    const page = fixture.componentInstance;
    flush('/settings/client-messages', messageSettingsReadFixture);
    page.loadAll();
    const read = http.expectOne('/api/admin/settings/client-messages');
    page.autoresponderForm.controls.dailyLimit.setValue(321);
    read.flush(messageSettingsReadFixture);
    expect(page.autoresponderForm.controls.dailyLimit.value).toBe(321);
    page.saveActive();
    const write = http.expectOne('/api/admin/settings/client-messages');
    page.resetAutoresponderForm();
    page.autoresponderForm.controls.dailyLimit.setValue(222);
    write.flush({ ...messageSettingsWriteFixture, dailyLimit: 321 });
    expect(page.autoresponderForm.controls.dailyLimit.value).toBe(222);
    fixture.destroy();
  });

  it('cancels a pre-commit settings refresh when the pending PUT succeeds', () => {
    const fixture = create('autoresponder');
    const page = fixture.componentInstance;
    flush('/settings/client-messages', messageSettingsReadFixture);
    page.autoresponderForm.controls.dailyLimit.setValue(321);
    page.saveActive();
    const write = http.expectOne(request => request.method === 'PUT');
    page.loadAll();
    const read = http.expectOne(request => request.method === 'GET');
    expect(read.cancelled).toBe(false);
    write.flush({ ...messageSettingsWriteFixture, dailyLimit: 321 });
    expect(read.cancelled).toBe(true);
    expect(page.clientMessageSettings()?.dailyLimit).toBe(321);
    expect(page.autoresponderForm.controls.dailyLimit.value).toBe(321);
    expect(page.activeLoading()).toBe(false);
    fixture.destroy();
  });

  it('cancels a hidden settings read and reloads without replacing a dirty draft on return', () => {
    let visibility: DocumentVisibilityState = 'visible';
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility);
    const fixture = create('autoresponder');
    const page = fixture.componentInstance;
    flush('/settings/client-messages', messageSettingsReadFixture);
    page.autoresponderForm.controls.dailyLimit.setValue(333);
    page.autoresponderForm.markAsDirty();
    page.loadAll();
    const read = http.expectOne('/api/admin/settings/client-messages');
    visibility = 'hidden';
    page.onDocumentVisibilityChange();
    expect(read.cancelled).toBe(true);
    visibility = 'visible';
    page.onDocumentVisibilityChange();
    flush('/settings/client-messages', messageSettingsReadFixture);
    expect(page.autoresponderForm.controls.dailyLimit.value).toBe(333);
    fixture.destroy();
  });
});
