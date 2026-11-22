import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import type {
  AdminCategory,
  AdminProduct,
  AdminSubCategory,
} from '../../../core/admin-dictionaries.api';
import { AdminProductsApi } from '../../../core/admin-products.api';
import { AdminTaxonomyApi } from '../../../core/admin-taxonomy.api';
import { AdminProductsFacade } from './admin-products.facade';
import { AdminTaxonomyFacade } from './admin-taxonomy.facade';

const auto: AdminCategory = {
  id: 1,
  title: 'Автосервис',
  subCategoryCount: 3,
  subCategories: [
    { id: 11, title: 'Шиномонтаж' },
    { id: 12, title: 'Мойка' },
    { id: 13, title: 'Покраска' },
  ],
};
const beauty: AdminCategory = {
  id: 2,
  title: 'Красота',
  subCategoryCount: 1,
  subCategories: [{ id: 21, title: 'Маникюр' }],
};
const bare: AdminCategory = {
  id: 3,
  title: 'Без подкатегорий',
  subCategoryCount: 0,
  subCategories: [],
};
const tyres: AdminSubCategory = {
  id: 11,
  title: 'Шиномонтаж',
  category: { id: 1, title: 'Автосервис' },
};
const nails: AdminSubCategory = { id: 21, title: 'Маникюр', category: { id: 2, title: 'Красота' } };
const click = () => new Event('click');

describe('catalog fixes: categories and subcategories', () => {
  let http: HttpTestingController;
  let facade: AdminTaxonomyFacade;
  let canWrite: boolean;
  const toast = { success: vi.fn(), error: vi.fn() };

  function reload(categories: AdminCategory[], subCategories: AdminSubCategory[]) {
    http
      .expectOne((request) => request.method === 'GET' && request.url.endsWith('/categories'))
      .flush(categories);
    http
      .expectOne((request) => request.method === 'GET' && request.url.endsWith('/subcategories'))
      .flush(subCategories);
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    canWrite = true;
    vi.clearAllMocks();
    facade = new AdminTaxonomyFacade({
      api: TestBed.inject(AdminTaxonomyApi),
      toast,
      isActive: () => true,
      canWrite: () => canWrite,
      tab: () => 'categories',
      search: () => '',
    });
    facade.load();
    http
      .expectOne((request) => request.method === 'GET' && request.url.endsWith('/categories'))
      .flush([auto, beauty, bare]);
  });
  afterEach(() => {
    facade.destroy();
    http.verify({ ignoreCancelled: true });
    vi.restoreAllMocks();
  });

  it('refuses every change to a manager with a message instead of silently doing nothing', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    canWrite = false;
    facade.openNewCategory();
    facade.editCategory(click(), auto);
    facade.openNewSubCategory();
    facade.editSubCategory(click(), tyres);
    expect(facade.categoryEditorOpen()).toBe(false);
    expect(facade.subCategoryEditorOpen()).toBe(false);
    facade.saveCategory();
    facade.saveSubCategory();
    facade.editingCategoryId.set(1);
    facade.deleteEditingCategory();
    facade.editingSubCategoryId.set(11);
    facade.deleteEditingSubCategory();
    http.expectNone((request) => request.method !== 'GET');
    expect(confirm).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledTimes(8);
    expect(toast.error).toHaveBeenLastCalledWith(
      'Только просмотр',
      'Категории и подкатегории меняют администратор и владелец.',
    );
    // The list itself stays readable for the manager.
    facade.selectCategory(beauty);
    expect(facade.selectedCategorySubCategories().map((item) => item.title)).toEqual(['Маникюр']);
  });

  it('names the category, counts the subcategories deleted with it and warns about companies and reviews', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    facade.editCategory(click(), auto);
    expect(facade.categoryEditorOpen()).toBe(true);
    facade.categoryForm.controls.title.setValue('Недописанное новое имя');
    facade.deleteEditingCategory();
    expect(confirm).toHaveBeenCalledWith(
      'Удалить категорию «Автосервис»?\n' +
        'Вместе с ней удалятся 3 подкатегории.\n' +
        'У компаний и отзывов с этой категорией поля «Категория» и «Подкатегория» станут пустыми.\n' +
        'Если по категории уже есть опубликованные отзывы, удалить её не получится.\n' +
        'Отменить нельзя.',
    );
    http.expectNone((request) => request.method === 'DELETE');
    expect(facade.categoryEditorOpen()).toBe(true);
  });

  it('agrees the number of subcategories in Russian', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const cases: [number, string][] = [
      [1, 'Вместе с ней удалится 1 подкатегория.'],
      [3, 'Вместе с ней удалятся 3 подкатегории.'],
      [5, 'Вместе с ней удалятся 5 подкатегорий.'],
      [11, 'Вместе с ней удалятся 11 подкатегорий.'],
      [21, 'Вместе с ней удалится 21 подкатегория.'],
      [22, 'Вместе с ней удалятся 22 подкатегории.'],
    ];
    for (const [count, line] of cases) {
      const category: AdminCategory = {
        id: 100 + count,
        title: 'Проверка',
        subCategoryCount: count,
        subCategories: [],
      };
      facade.categories.set([category]);
      facade.editCategory(click(), category);
      facade.deleteEditingCategory();
      expect(String(confirm.mock.lastCall?.[0]).split('\n')[1]).toBe(line);
    }
    http.expectNone((request) => request.method === 'DELETE');
  });

  it('deletes a category without subcategories after the named question', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    facade.editCategory(click(), bare);
    facade.deleteEditingCategory();
    expect(confirm).toHaveBeenCalledWith(
      'Удалить категорию «Без подкатегорий»?\n' +
        'Подкатегорий у неё нет.\n' +
        'У компаний и отзывов с этой категорией поле «Категория» станет пустым.\n' +
        'Если по категории уже есть опубликованные отзывы, удалить её не получится.\n' +
        'Отменить нельзя.',
    );
    http
      .expectOne((request) => request.method === 'DELETE' && request.url.endsWith('/categories/3'))
      .flush(null);
    reload([auto, beauty], [tyres, nails]);
    expect(facade.categoryEditorOpen()).toBe(false);
    expect(toast.success).toHaveBeenCalledWith('Категория удалена');
  });

  it('names the subcategory and its category before deleting it', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    facade.editSubCategory(click(), tyres);
    expect(facade.subCategoryEditorOpen()).toBe(true);
    facade.deleteEditingSubCategory();
    expect(confirm).toHaveBeenCalledWith(
      'Удалить подкатегорию «Шиномонтаж» из категории «Автосервис»?\n' +
        'У компаний и отзывов с этой подкатегорией поле «Подкатегория» станет пустым.\n' +
        'Если по подкатегории уже есть опубликованные отзывы, удалить её не получится.\n' +
        'Отменить нельзя.',
    );
    http.expectNone((request) => request.method === 'DELETE');
  });

  it('titles the windows with the saved names instead of internal numbers', () => {
    expect(facade.categoryEditorTitle()).toBe('Новая категория');
    expect(facade.subCategoryEditorTitle()).toBe('Новая подкатегория');
    facade.editCategory(click(), auto);
    facade.categoryForm.controls.title.setValue('Черновик');
    expect(facade.categoryEditorTitle()).toBe('Категория «Автосервис»');
    facade.editSubCategory(click(), nails);
    expect(facade.subCategoryEditorTitle()).toBe('Подкатегория «Маникюр»');
    facade.editingCategoryId.set(999);
    expect(facade.categoryEditorTitle()).toBe('Категория');
  });

  it('asks before a window drops typed text, and the next window opens clean', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    facade.editCategory(click(), beauty);
    facade.closeCategoryEditor();
    expect(confirm).not.toHaveBeenCalled();
    expect(facade.categoryEditorOpen()).toBe(false);

    facade.editCategory(click(), auto);
    facade.categoryForm.controls.title.setValue('Автосервис и шины');
    facade.categoryForm.markAsDirty();
    facade.closeCategoryEditor();
    expect(confirm).toHaveBeenCalledWith(
      'Есть несохранённые изменения. Закрыть окно без сохранения?',
    );
    expect(facade.categoryEditorOpen()).toBe(true);
    expect(facade.categoryForm.controls.title.value).toBe('Автосервис и шины');

    // Saved without further typing: the window closes; the next one must not ask about the old edit.
    facade.saveCategory();
    http
      .expectOne((request) => request.method === 'PUT' && request.url.endsWith('/categories/1'))
      .flush({ ...auto, title: 'Автосервис и шины' });
    reload([{ ...auto, title: 'Автосервис и шины' }, beauty, bare], [tyres, nails]);
    expect(facade.categoryEditorOpen()).toBe(false);
    confirm.mockClear();
    facade.editCategory(click(), beauty);
    expect(facade.categoryForm.dirty).toBe(false);
    facade.closeCategoryEditor();
    expect(confirm).not.toHaveBeenCalled();
    expect(facade.categoryEditorOpen()).toBe(false);

    facade.editSubCategory(click(), nails);
    facade.subCategoryForm.controls.title.setValue('Маникюр и педикюр');
    facade.subCategoryForm.markAsDirty();
    facade.closeSubCategoryEditor();
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(facade.subCategoryEditorOpen()).toBe(true);
    confirm.mockReturnValue(true);
    facade.closeSubCategoryEditor();
    expect(facade.subCategoryEditorOpen()).toBe(false);
    expect(facade.subCategoryForm.controls.title.value).toBe('');
  });

  it('moves a subcategory to another category and keeps it in view there', () => {
    facade.selectCategory(auto);
    facade.editSubCategory(click(), tyres);
    facade.subCategoryForm.controls.categoryId.setValue(2);
    facade.saveSelectedSubCategory();
    const write = http.expectOne(
      (request) => request.method === 'PUT' && request.url.endsWith('/subcategories/11'),
    );
    expect(write.request.body).toEqual({ title: 'Шиномонтаж', categoryId: 2 });
    write.flush({ id: 11, title: 'Шиномонтаж', category: { id: 2, title: 'Красота' } });
    expect(facade.activeCategoryId()).toBe(2);
    expect(facade.subCategoryEditorOpen()).toBe(false);
    reload(
      [
        { ...auto, subCategoryCount: 2, subCategories: auto.subCategories.slice(1) },
        { ...beauty, subCategoryCount: 2, subCategories: [{ id: 11, title: 'Шиномонтаж' }, nails] },
        bare,
      ],
      [
        nails,
        { id: 11, title: 'Шиномонтаж', category: { id: 2, title: 'Красота' } },
        { id: 12, title: 'Мойка', category: { id: 1, title: 'Автосервис' } },
      ],
    );
    expect(facade.activeCategoryId()).toBe(2);
    expect(
      facade
        .selectedCategorySubCategories()
        .map((item) => item.id)
        .sort(),
    ).toEqual([11, 21]);
    expect(toast.success).toHaveBeenCalledWith('Подкатегория сохранена', 'ID 11');
  });
});

describe('catalog fixes: products', () => {
  let http: HttpTestingController;
  let facade: AdminProductsFacade;
  const selectedId = signal<number | null>(null);
  const toast = { success: vi.fn(), error: vi.fn() };
  const productCategories = [
    { id: 1, title: 'Отзывы' },
    { id: 2, title: 'Другое' },
  ];
  const product = (id: number, overrides: Partial<AdminProduct> = {}): AdminProduct => ({
    id,
    title: `Отзыв ${id}`,
    price: 500,
    category: { id: 1, title: 'Отзывы' },
    photo: false,
    requiresPerformer: false,
    targetPlatform: 'OTHER',
    performerRewardPercent: 0,
    specialistRewardPercent: 0,
    managerRewardPercent: 0,
    ...overrides,
  });

  function flushProducts(products: AdminProduct[]) {
    http
      .expectOne((request) => request.method === 'GET' && request.url.endsWith('/products'))
      .flush({ products, categories: productCategories });
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    selectedId.set(null);
    vi.clearAllMocks();
    facade = new AdminProductsFacade({
      api: TestBed.inject(AdminProductsApi),
      toast,
      isActive: () => true,
      selectedId,
      search: () => '',
    });
  });
  afterEach(() => {
    facade.destroy();
    http.verify({ ignoreCancelled: true });
    vi.restoreAllMocks();
  });

  it('refuses a name longer than 45 characters before it reaches the database', () => {
    facade.load();
    flushProducts([]);
    facade.productForm.controls.title.setValue('а'.repeat(46));
    facade.saveProduct();
    http.expectNone((request) => request.method === 'POST');
    expect(facade.productForm.controls.title.hasError('maxlength')).toBe(true);
    expect(facade.productForm.touched).toBe(true);

    facade.productForm.controls.title.setValue('а'.repeat(45));
    facade.saveProduct();
    const write = http.expectOne(
      (request) => request.method === 'POST' && request.url.endsWith('/products'),
    );
    expect(write.request.body.title).toBe('а'.repeat(45));
    expect(write.request.body.categoryId).toBe(1);
    write.flush(product(9, { title: 'а'.repeat(45) }));
    flushProducts([product(9, { title: 'а'.repeat(45) })]);
    expect(selectedId()).toBe(9);
  });

  it('keeps the category of a product without one empty instead of silently taking the first', () => {
    const orphan = product(4, { category: null });
    facade.load();
    flushProducts([orphan]);
    facade.selectProduct(orphan);
    expect(facade.productForm.controls.categoryId.value).toBeNull();
    facade.load();
    flushProducts([orphan]);
    expect(facade.productForm.controls.categoryId.value).toBeNull();

    facade.saveProduct();
    http.expectNone((request) => request.method === 'PUT');
    expect(facade.productForm.controls.categoryId.hasError('required')).toBe(true);

    facade.productForm.controls.categoryId.setValue(2);
    facade.saveProduct();
    const write = http.expectOne(
      (request) => request.method === 'PUT' && request.url.endsWith('/products/4'),
    );
    expect(write.request.body.categoryId).toBe(2);
    write.flush(product(4, { category: { id: 2, title: 'Другое' } }));
    flushProducts([product(4, { category: { id: 2, title: 'Другое' } })]);

    // A new product still starts with the first category of the list.
    facade.clearSelection();
    expect(facade.productForm.controls.categoryId.value).toBe(1);
  });

  it('names the product before deleting it and reports a refusal for a product used in orders', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const gis = product(5, { title: 'Отзыв 2ГИС' });
    facade.load();
    flushProducts([gis]);
    facade.selectProduct(gis);
    facade.productForm.controls.title.setValue('Отзыв 2ГИС, черновик');
    facade.deleteSelected();
    expect(confirm).toHaveBeenCalledWith(
      'Удалить продукт «Отзыв 2ГИС»?\n' +
        'Продукт, который уже есть в заказах, удалить не получится.\n' +
        'Отменить удаление нельзя.',
    );
    http.expectNone((request) => request.method === 'DELETE');

    confirm.mockReturnValue(true);
    facade.deleteSelected();
    http
      .expectOne((request) => request.method === 'DELETE' && request.url.endsWith('/products/5'))
      .flush(
        { message: 'Продукт используется в заказах.' },
        { status: 409, statusText: 'Conflict' },
      );
    expect(toast.error).toHaveBeenCalledWith(
      'Продукт не удалён',
      'Продукт используется в заказах.',
    );
    expect(selectedId()).toBe(5);
    expect(facade.deleting()).toBe(false);

    facade.deleteSelected();
    http
      .expectOne((request) => request.method === 'DELETE' && request.url.endsWith('/products/5'))
      .flush(null);
    expect(toast.success).toHaveBeenCalledWith('Продукт удалён', '«Отзыв 2ГИС»');
    flushProducts([]);
    expect(selectedId()).toBeNull();
  });
});
