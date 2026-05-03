import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin, Observable } from 'rxjs';
import {
  AdminCategory,
  AdminCity,
  AdminDictionariesApi,
  AdminProduct,
  AdminSubCategory,
  DictionaryOption,
  ProductRequest,
  ProductsResponse,
  SubCategoryRequest,
  TitleRequest
} from '../../../core/admin-dictionaries.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { ToastService } from '../../../shared/toast.service';

type DictionaryTabKey = 'categories' | 'subcategories' | 'cities' | 'products';

type DictionaryTab = {
  key: DictionaryTabKey;
  label: string;
  icon: string;
};

type DictionaryMetric = {
  label: string;
  value: number;
  icon: string;
  tone: 'blue' | 'green' | 'teal' | 'yellow';
};

@Component({
  selector: 'app-admin-dictionaries',
  imports: [AdminLayoutComponent, ReactiveFormsModule],
  templateUrl: './admin-dictionaries.component.html',
  styleUrl: './admin-dictionaries.component.scss'
})
export class AdminDictionariesComponent {
  private readonly fb = inject(FormBuilder);
  private readonly dictionariesApi = inject(AdminDictionariesApi);
  private readonly toastService = inject(ToastService);

  readonly tabs: DictionaryTab[] = [
    { key: 'categories', label: 'Категории', icon: 'category' },
    { key: 'cities', label: 'Города', icon: 'location_city' },
    { key: 'products', label: 'Продукты', icon: 'inventory_2' }
  ];

  readonly activeTab = signal<DictionaryTabKey>('categories');
  readonly search = signal('');
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);
  readonly selectedId = signal<number | null>(null);
  readonly activeCategoryId = signal<number | null>(null);
  readonly editingCategoryId = signal<number | null>(null);
  readonly editingSubCategoryId = signal<number | null>(null);

  readonly categories = signal<AdminCategory[]>([]);
  readonly subCategories = signal<AdminSubCategory[]>([]);
  readonly cities = signal<AdminCity[]>([]);
  readonly products = signal<AdminProduct[]>([]);
  readonly productCategories = signal<DictionaryOption[]>([]);

  readonly categoryForm = this.fb.nonNullable.group({
    title: ['', Validators.required]
  });

  readonly subCategoryForm = this.fb.group({
    title: this.fb.nonNullable.control('', Validators.required),
    categoryId: this.fb.control<number | null>(null, Validators.required)
  });

  readonly cityForm = this.fb.nonNullable.group({
    title: ['', Validators.required]
  });

  readonly productForm = this.fb.group({
    title: this.fb.nonNullable.control('', Validators.required),
    price: this.fb.nonNullable.control('0', Validators.required),
    categoryId: this.fb.control<number | null>(null, Validators.required),
    photo: this.fb.nonNullable.control(false)
  });

  readonly activeLabel = computed(() => this.tabs.find((tab) => tab.key === this.activeTab())?.label ?? '');
  readonly activeCategory = computed(() => {
    const id = this.activeCategoryId();
    return this.categories().find((category) => category.id === id) ?? null;
  });
  readonly selectedCategorySubCategories = computed(() => {
    const categoryId = this.activeCategoryId();
    if (categoryId == null) {
      return [];
    }

    return this.subCategories().filter((subCategory) => subCategory.category?.id === categoryId);
  });
  readonly categoryEditorTitle = computed(() =>
    this.editingCategoryId() == null ? 'Новая категория' : `Категория #${this.editingCategoryId()}`
  );
  readonly subCategoryEditorTitle = computed(() =>
    this.editingSubCategoryId() == null ? 'Новая подкатегория' : `Подкатегория #${this.editingSubCategoryId()}`
  );
  readonly activeItemsTotal = computed(() => {
    switch (this.activeTab()) {
      case 'categories':
        return this.categories().length;
      case 'subcategories':
        return this.subCategories().length;
      case 'cities':
        return this.cities().length;
      case 'products':
        return this.products().length;
    }
  });
  readonly metrics = computed<DictionaryMetric[]>(() => [
    { label: 'Категории', value: this.categories().length, icon: 'category', tone: 'blue' },
    { label: 'Подкатегории', value: this.subCategories().length, icon: 'account_tree', tone: 'teal' },
    { label: 'Города', value: this.cities().length, icon: 'location_city', tone: 'green' },
    { label: 'Продукты', value: this.products().length, icon: 'inventory_2', tone: 'yellow' }
  ]);

  constructor() {
    this.loadAll();
  }

  setTab(tab: DictionaryTabKey): void {
    this.activeTab.set(tab);
    this.search.set('');
    this.clearSelection();
  }

  loadAll(): void {
    this.loading.set(true);
    this.error.set(null);

    forkJoin({
      categories: this.dictionariesApi.getCategories(),
      subCategories: this.dictionariesApi.getSubCategories(),
      cities: this.dictionariesApi.getCities(),
      products: this.dictionariesApi.getProducts()
    }).subscribe({
      next: ({ categories, subCategories, cities, products }) => {
        this.categories.set(categories);
        this.subCategories.set(subCategories);
        this.cities.set(cities);
        this.products.set(products.products);
        this.productCategories.set(products.categories);
        this.loading.set(false);
        this.ensureDefaults();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить справочники');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Справочники не загрузились', message);
      }
    });
  }

  searchActive(): void {
    this.loadActive();
  }

  clearSearch(): void {
    this.search.set('');
    this.loadActive();
  }

  selectCategory(category: AdminCategory): void {
    this.activeCategoryId.set(category.id);
    this.startNewSubCategory();
    this.error.set(null);
  }

  editCategory(event: Event, category: AdminCategory): void {
    event.preventDefault();
    event.stopPropagation();
    this.activeCategoryId.set(category.id);
    this.editingCategoryId.set(category.id);
    this.categoryForm.setValue({ title: category.title });
    this.error.set(null);
  }

  selectSubCategory(subCategory: AdminSubCategory): void {
    const categoryId = subCategory.category?.id ?? this.activeCategoryId() ?? this.defaultCategoryId();
    this.editingSubCategoryId.set(subCategory.id);
    this.activeCategoryId.set(categoryId);
    this.subCategoryForm.setValue({
      title: subCategory.title,
      categoryId
    });
    this.error.set(null);
  }

  selectCity(city: AdminCity): void {
    this.selectedId.set(city.id);
    this.cityForm.setValue({ title: city.title });
    this.error.set(null);
  }

  selectProduct(product: AdminProduct): void {
    this.selectedId.set(product.id);
    this.productForm.setValue({
      title: product.title,
      price: String(product.price ?? 0),
      categoryId: product.category?.id ?? this.defaultProductCategoryId(),
      photo: product.photo
    });
    this.error.set(null);
  }

  clearSelection(): void {
    this.selectedId.set(null);
    this.editingCategoryId.set(null);
    this.editingSubCategoryId.set(null);
    this.error.set(null);

    this.categoryForm.reset({ title: '' });
    this.subCategoryForm.reset({ title: '', categoryId: this.activeCategoryId() ?? this.defaultCategoryId() });
    this.cityForm.reset({ title: '' });
    this.productForm.reset({
      title: '',
      price: '0',
      categoryId: this.defaultProductCategoryId(),
      photo: false
    });
  }

  startNewCategory(): void {
    this.editingCategoryId.set(null);
    this.categoryForm.reset({ title: '' });
    this.error.set(null);
  }

  startNewSubCategory(): void {
    this.editingSubCategoryId.set(null);
    this.subCategoryForm.reset({
      title: '',
      categoryId: this.activeCategoryId() ?? this.defaultCategoryId()
    });
    this.error.set(null);
  }

  saveSelectedSubCategory(): void {
    this.saveSubCategory();
  }

  deleteEditingCategory(): void {
    const selectedId = this.editingCategoryId();
    if (selectedId == null || this.deleting()) {
      return;
    }

    const confirmed = window.confirm('Удалить категорию?');
    if (!confirmed) {
      return;
    }

    this.deleting.set(true);
    this.error.set(null);

    this.dictionariesApi.deleteCategory(selectedId).subscribe({
      next: () => {
        this.deleting.set(false);
        this.editingCategoryId.set(null);
        this.categoryForm.reset({ title: '' });

        if (this.activeCategoryId() === selectedId) {
          this.activeCategoryId.set(null);
          this.startNewSubCategory();
        }

        this.toastService.success('Категория удалена');
        this.reloadAfterMutation();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить категорию');
        this.error.set(message);
        this.deleting.set(false);
        this.toastService.error('Категория не удалена', message);
      }
    });
  }

  deleteEditingSubCategory(): void {
    const selectedId = this.editingSubCategoryId();
    if (selectedId == null || this.deleting()) {
      return;
    }

    const confirmed = window.confirm('Удалить подкатегорию?');
    if (!confirmed) {
      return;
    }

    this.deleting.set(true);
    this.error.set(null);

    this.dictionariesApi.deleteSubCategory(selectedId).subscribe({
      next: () => {
        this.deleting.set(false);
        this.startNewSubCategory();
        this.toastService.success('Подкатегория удалена');
        this.reloadAfterMutation();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить подкатегорию');
        this.error.set(message);
        this.deleting.set(false);
        this.toastService.error('Подкатегория не удалена', message);
      }
    });
  }

  saveActive(): void {
    switch (this.activeTab()) {
      case 'categories':
        this.saveCategory();
        return;
      case 'subcategories':
        this.saveSubCategory();
        return;
      case 'cities':
        this.saveCity();
        return;
      case 'products':
        this.saveProduct();
        return;
    }
  }

  deleteSelected(): void {
    const selectedId = this.selectedId();
    if (selectedId == null || this.deleting()) {
      return;
    }

    const confirmed = window.confirm(`Удалить запись из справочника "${this.activeLabel()}"?`);
    if (!confirmed) {
      return;
    }

    this.deleting.set(true);
    this.error.set(null);

    const request = {
      categories: () => this.dictionariesApi.deleteCategory(selectedId),
      subcategories: () => this.dictionariesApi.deleteSubCategory(selectedId),
      cities: () => this.dictionariesApi.deleteCity(selectedId),
      products: () => this.dictionariesApi.deleteProduct(selectedId)
    }[this.activeTab()]();

    request.subscribe({
      next: () => {
        this.deleting.set(false);
        this.clearSelection();
        this.toastService.success('Запись удалена', this.activeLabel());
        this.reloadAfterMutation();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить запись');
        this.error.set(message);
        this.deleting.set(false);
        this.toastService.error('Запись не удалена', message);
      }
    });
  }

  tabTotal(tab: DictionaryTabKey): number {
    return {
      categories: this.categories().length,
      subcategories: this.subCategories().length,
      cities: this.cities().length,
      products: this.products().length
    }[tab];
  }

  categoryTitle(category?: DictionaryOption | null): string {
    return category?.title || '-';
  }

  selectedTitle(): string {
    return this.selectedId() == null ? 'Новая запись' : `ID ${this.selectedId()}`;
  }

  trackTab(_index: number, tab: DictionaryTab): DictionaryTabKey {
    return tab.key;
  }

  trackCategory(_index: number, category: AdminCategory): number {
    return category.id;
  }

  trackSubCategory(_index: number, subCategory: AdminSubCategory): number {
    return subCategory.id;
  }

  trackCity(_index: number, city: AdminCity): number {
    return city.id;
  }

  trackProduct(_index: number, product: AdminProduct): number {
    return product.id;
  }

  trackOption(_index: number, option: DictionaryOption): number {
    return option.id;
  }

  trackMetric(_index: number, metric: DictionaryMetric): string {
    return metric.label;
  }

  private loadActive(): void {
    this.loading.set(true);
    this.error.set(null);
    this.selectedId.set(null);

    const keyword = this.search();
    let request: Observable<AdminCategory[] | AdminSubCategory[] | AdminCity[] | ProductsResponse>;
    switch (this.activeTab()) {
      case 'categories':
        request = this.dictionariesApi.getCategories(keyword);
        break;
      case 'subcategories':
        request = this.dictionariesApi.getSubCategories(keyword);
        break;
      case 'cities':
        request = this.dictionariesApi.getCities(keyword);
        break;
      case 'products':
        request = this.dictionariesApi.getProducts(keyword);
        break;
    }

    request.subscribe({
      next: (response: AdminCategory[] | AdminSubCategory[] | AdminCity[] | ProductsResponse) => {
        switch (this.activeTab()) {
          case 'categories':
            this.categories.set(response as AdminCategory[]);
            this.ensureDefaults();
            break;
          case 'subcategories':
            this.subCategories.set(response as AdminSubCategory[]);
            break;
          case 'cities':
            this.cities.set(response as AdminCity[]);
            break;
          case 'products': {
            const payload = response as ProductsResponse;
            this.products.set(payload.products);
            this.productCategories.set(payload.categories);
            break;
          }
        }

        this.loading.set(false);
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось загрузить справочник');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Справочник не загрузился', message);
      }
    });
  }

  private saveCategory(): void {
    if (this.categoryForm.invalid) {
      this.categoryForm.markAllAsTouched();
      return;
    }

    const request: TitleRequest = { title: this.categoryForm.controls.title.value.trim() };
    const selectedId = this.editingCategoryId();
    const call = selectedId == null
      ? this.dictionariesApi.createCategory(request)
      : this.dictionariesApi.updateCategory(selectedId, request);

    this.runSave(call, 'Категория сохранена', (saved) => {
      this.activeCategoryId.set(saved.id);
      this.editingCategoryId.set(saved.id);
    });
  }

  private saveSubCategory(): void {
    if (this.subCategoryForm.invalid) {
      this.subCategoryForm.markAllAsTouched();
      return;
    }

    const raw = this.subCategoryForm.getRawValue();
    const request: SubCategoryRequest = {
      title: raw.title.trim(),
      categoryId: raw.categoryId ?? this.activeCategoryId()
    };
    const selectedId = this.editingSubCategoryId();
    const call = selectedId == null
      ? this.dictionariesApi.createSubCategory(request)
      : this.dictionariesApi.updateSubCategory(selectedId, request);

    this.runSave(call, 'Подкатегория сохранена', (saved) => {
      this.editingSubCategoryId.set(saved.id);
      this.subCategoryForm.controls.categoryId.setValue(request.categoryId);
    });
  }

  private saveCity(): void {
    if (this.cityForm.invalid) {
      this.cityForm.markAllAsTouched();
      return;
    }

    const request: TitleRequest = { title: this.cityForm.controls.title.value.trim() };
    const selectedId = this.selectedId();
    const call = selectedId == null
      ? this.dictionariesApi.createCity(request)
      : this.dictionariesApi.updateCity(selectedId, request);

    this.runSave(call, 'Город сохранен');
  }

  private saveProduct(): void {
    if (this.productForm.invalid) {
      this.productForm.markAllAsTouched();
      return;
    }

    const raw = this.productForm.getRawValue();
    const request: ProductRequest = {
      title: raw.title.trim(),
      price: Number(raw.price || 0),
      categoryId: raw.categoryId,
      photo: raw.photo
    };
    const selectedId = this.selectedId();
    const call = selectedId == null
      ? this.dictionariesApi.createProduct(request)
      : this.dictionariesApi.updateProduct(selectedId, request);

    this.runSave(call, 'Продукт сохранен');
  }

  private runSave<T extends { id: number }>(
    request: Observable<T>,
    title: string,
    afterSave?: (saved: T) => void
  ): void {
    this.saving.set(true);
    this.error.set(null);

    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        if (afterSave) {
          afterSave(saved);
        } else {
          this.selectedId.set(saved.id);
        }
        this.toastService.success(title, `ID ${saved.id}`);
        this.reloadAfterMutation();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить запись');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Запись не сохранена', message);
      }
    });
  }

  private reloadAfterMutation(): void {
    if (this.activeTab() === 'categories' || this.activeTab() === 'subcategories') {
      forkJoin({
        categories: this.dictionariesApi.getCategories(this.activeTab() === 'categories' ? this.search() : ''),
        subCategories: this.dictionariesApi.getSubCategories(this.activeTab() === 'subcategories' ? this.search() : '')
      }).subscribe({
        next: ({ categories, subCategories }) => {
          this.categories.set(categories);
          this.subCategories.set(subCategories);
          this.ensureDefaults();
        }
      });
      return;
    }

    this.loadActive();
  }

  private defaultCategoryId(): number | null {
    return this.categories()[0]?.id ?? null;
  }

  private defaultProductCategoryId(): number | null {
    return this.productCategories()[0]?.id ?? null;
  }

  private ensureDefaults(): void {
    const activeCategoryId = this.activeCategoryId();
    const hasActiveCategory = activeCategoryId != null
      && this.categories().some((category) => category.id === activeCategoryId);

    if (!hasActiveCategory) {
      this.activeCategoryId.set(this.defaultCategoryId());
    }

    const subCategoryCategoryId = this.subCategoryForm.controls.categoryId.value;
    const hasSubCategoryCategory = subCategoryCategoryId != null
      && this.categories().some((category) => category.id === subCategoryCategoryId);

    if (subCategoryCategoryId == null || !hasSubCategoryCategory) {
      this.subCategoryForm.controls.categoryId.setValue(this.activeCategoryId() ?? this.defaultCategoryId());
    }

    if (this.productForm.controls.categoryId.value == null) {
      this.productForm.controls.categoryId.setValue(this.defaultProductCategoryId());
    }
  }

  private errorMessage(err: unknown, fallback: string): string {
    if (typeof err === 'object' && err !== null) {
      const response = err as { error?: unknown; message?: unknown; status?: unknown };
      if (typeof response.error === 'object' && response.error !== null) {
        const body = response.error as { detail?: unknown; message?: unknown; title?: unknown };
        if (typeof body.detail === 'string') {
          return body.detail;
        }

        if (typeof body.message === 'string') {
          return body.message;
        }

        if (typeof body.title === 'string') {
          return body.title;
        }
      }

      if (typeof response.error === 'string') {
        return response.error;
      }

      if (typeof response.message === 'string') {
        return response.message;
      }
    }

    return fallback;
  }
}
