import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin, Observable } from 'rxjs';
import {
  AdminBot,
  AdminCategory,
  AdminCity,
  AdminDictionariesApi,
  AdminManagerText,
  AdminProduct,
  AdminPromoText,
  AdminSubCategory,
  BotImportResponse,
  BotRequest,
  BotsResponse,
  DictionaryOption,
  ManagerTextRequest,
  PromoButtonSlot,
  PromoTextAssignment,
  PromoTextAssignmentRequest,
  PromoTextManagementResponse,
  ProductRequest,
  ProductsResponse,
  PromoTextRequest,
  SubCategoryRequest,
  TitleRequest
} from '../../../core/admin-dictionaries.api';
import { AuthService } from '../../../core/auth.service';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';

type DictionaryTabKey = 'categories' | 'subcategories' | 'cities' | 'products' | 'accounts' | 'promo' | 'managerTexts';

type DictionaryTab = {
  key: DictionaryTabKey;
  label: string;
  icon: string;
};

type DictionaryMetric = {
  label: string;
  value: number;
  icon: string;
  tone: 'blue' | 'green' | 'teal' | 'yellow' | 'pink';
};

const PROMO_TEXT_LABELS: Record<number, string> = {
  1: 'предложение',
  2: 'напоминание',
  3: 'данные',
  4: 'ответы',
  5: 'ссылка на проверку',
  6: 'напоминание заказа',
  7: 'угроза',
  10: 'рассылка',
  11: 'пояснение'
};

@Component({
  selector: 'app-admin-dictionaries',
  imports: [AdminLayoutComponent, LoadErrorCardComponent, ReactiveFormsModule],
  templateUrl: './admin-dictionaries.component.html',
  styleUrl: './admin-dictionaries.component.scss'
})
export class AdminDictionariesComponent {
  private readonly fb = inject(FormBuilder);
  private readonly dictionariesApi = inject(AdminDictionariesApi);
  private readonly auth = inject(AuthService);
  private readonly toastService = inject(ToastService);

  private readonly allTabs: DictionaryTab[] = [
    { key: 'categories', label: 'Категории', icon: 'category' },
    { key: 'cities', label: 'Города', icon: 'location_city' },
    { key: 'products', label: 'Продукты', icon: 'inventory_2' },
    { key: 'accounts', label: 'Аккаунты', icon: 'manage_accounts' },
    { key: 'promo', label: 'Промо', icon: 'smart_button' },
    { key: 'managerTexts', label: 'Тексты менеджеров', icon: 'article' }
  ];
  private readonly managerTabs: DictionaryTab[] = [
    { key: 'categories', label: 'Категории', icon: 'category' }
  ];

  readonly activeTab = signal<DictionaryTabKey>('categories');
  readonly search = signal('');
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly importing = signal(false);
  readonly error = signal<string | null>(null);
  readonly importError = signal<string | null>(null);
  readonly importResult = signal<BotImportResponse | null>(null);
  readonly importModalOpen = signal(false);
  readonly importFile = signal<File | null>(null);
  readonly selectedId = signal<number | null>(null);
  readonly activeCategoryId = signal<number | null>(null);
  readonly editingCategoryId = signal<number | null>(null);
  readonly editingSubCategoryId = signal<number | null>(null);

  readonly categories = signal<AdminCategory[]>([]);
  readonly subCategories = signal<AdminSubCategory[]>([]);
  readonly cities = signal<AdminCity[]>([]);
  readonly products = signal<AdminProduct[]>([]);
  readonly bots = signal<AdminBot[]>([]);
  readonly promoTexts = signal<AdminPromoText[]>([]);
  readonly managerTexts = signal<AdminManagerText[]>([]);
  readonly promoManagers = signal<DictionaryOption[]>([]);
  readonly promoAssignments = signal<PromoTextAssignment[]>([]);
  readonly promoButtons = signal<PromoButtonSlot[]>([]);
  readonly selectedPromoManagerId = signal<number | null>(null);
  readonly productCategories = signal<DictionaryOption[]>([]);
  readonly botWorkers = signal<DictionaryOption[]>([]);
  readonly botStatuses = signal<DictionaryOption[]>([]);
  readonly botCities = signal<DictionaryOption[]>([]);
  readonly canManageAllDictionaries = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  });
  readonly tabs = computed<DictionaryTab[]>(() => this.canManageAllDictionaries() ? this.allTabs : this.managerTabs);

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

  readonly botForm = this.fb.group({
    login: this.fb.nonNullable.control('', Validators.required),
    password: this.fb.nonNullable.control('', Validators.required),
    fio: this.fb.nonNullable.control('', Validators.required),
    workerId: this.fb.control<number | null>(null, Validators.required),
    cityId: this.fb.control<number | null>(null),
    statusId: this.fb.control<number | null>(null, Validators.required),
    counter: this.fb.nonNullable.control('0', Validators.required),
    active: this.fb.nonNullable.control(true)
  });

  readonly promoTextForm = this.fb.nonNullable.group({
    text: ['', Validators.required]
  });

  readonly managerTextForm = this.fb.nonNullable.group({
    payText: [''],
    beginText: [''],
    offerText: [''],
    reminderText: [''],
    startText: ['']
  });

  readonly activeLabel = computed(() => this.tabs().find((tab) => tab.key === this.activeTab())?.label ?? '');
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
  readonly selectedManagerText = computed(() => {
    const managerId = this.selectedId();
    return managerId == null
      ? null
      : this.managerTexts().find((managerText) => managerText.managerId === managerId) ?? null;
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
      case 'accounts':
        return this.bots().length;
      case 'promo':
        return this.promoTexts().length;
      case 'managerTexts':
        return this.managerTexts().length;
    }
  });
  readonly metrics = computed<DictionaryMetric[]>(() => {
    const categoryMetrics: DictionaryMetric[] = [
      { label: 'Категории', value: this.categories().length, icon: 'category', tone: 'blue' },
      { label: 'Подкатегории', value: this.subCategories().length, icon: 'account_tree', tone: 'teal' }
    ];

    if (!this.canManageAllDictionaries()) {
      return categoryMetrics;
    }

    return [
      ...categoryMetrics,
      { label: 'Города', value: this.cities().length, icon: 'location_city', tone: 'green' },
      { label: 'Продукты', value: this.products().length, icon: 'inventory_2', tone: 'yellow' },
      { label: 'Аккаунты', value: this.bots().length, icon: 'manage_accounts', tone: 'pink' },
      { label: 'Промо', value: this.promoTexts().length, icon: 'smart_button', tone: 'blue' },
      { label: 'Тексты менеджеров', value: this.managerTexts().length, icon: 'article', tone: 'green' }
    ];
  });

  constructor() {
    this.loadAll();
  }

  setTab(tab: DictionaryTabKey): void {
    if (!this.tabs().some((item) => item.key === tab)) {
      return;
    }

    this.activeTab.set(tab);
    this.search.set('');
    this.clearSelection();
  }

  loadAll(): void {
    this.loading.set(true);
    this.error.set(null);

    if (this.canManageAllDictionaries()) {
      forkJoin({
        categories: this.dictionariesApi.getCategories(),
        subCategories: this.dictionariesApi.getSubCategories(),
        cities: this.dictionariesApi.getCities(),
        products: this.dictionariesApi.getProducts(),
        bots: this.dictionariesApi.getBots(),
        promoTexts: this.dictionariesApi.getPromoTextManagement(),
        managerTexts: this.dictionariesApi.getManagerTexts()
      }).subscribe({
        next: ({ categories, subCategories, cities, products, bots, promoTexts, managerTexts }) => {
          this.categories.set(categories);
          this.subCategories.set(subCategories);
          this.cities.set(cities);
          this.products.set(products.products);
          this.productCategories.set(products.categories);
          this.applyBotsResponse(bots);
          this.applyPromoManagement(promoTexts);
          this.managerTexts.set(managerTexts);
          this.loading.set(false);
          this.ensureDefaults();
        },
        error: (err) => this.handleLoadAllError(err)
      });
      return;
    }

    forkJoin({
        categories: this.dictionariesApi.getCategories(),
        subCategories: this.dictionariesApi.getSubCategories()
    }).subscribe({
      next: ({ categories, subCategories }) => {
        this.categories.set(categories);
        this.subCategories.set(subCategories);
        this.cities.set([]);
        this.products.set([]);
        this.productCategories.set([]);
        this.bots.set([]);
        this.promoTexts.set([]);
        this.managerTexts.set([]);
        this.promoManagers.set([]);
        this.promoAssignments.set([]);
        this.promoButtons.set([]);
        this.selectedPromoManagerId.set(null);
        this.loading.set(false);
        this.ensureDefaults();
      },
      error: (err) => this.handleLoadAllError(err)
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

  selectBot(bot: AdminBot): void {
    this.selectedId.set(bot.id);
    this.botForm.setValue({
      login: bot.login,
      password: bot.password,
      fio: bot.fio,
      workerId: bot.worker?.id ?? this.defaultBotWorkerId(),
      cityId: bot.city?.id ?? this.defaultBotCityId(),
      statusId: bot.status?.id ?? this.defaultBotStatusId(),
      counter: String(bot.counter ?? 0),
      active: bot.active
    });
    this.error.set(null);
  }

  selectPromoText(promoText: AdminPromoText): void {
    this.selectedId.set(promoText.id);
    this.promoTextForm.setValue({ text: promoText.text });
    this.error.set(null);
  }

  selectManagerText(managerText: AdminManagerText): void {
    this.selectedId.set(managerText.managerId);
    this.managerTextForm.setValue({
      payText: managerText.payText,
      beginText: managerText.beginText,
      offerText: managerText.offerText,
      reminderText: managerText.reminderText,
      startText: managerText.startText
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
    this.botForm.reset({
      login: '',
      password: '',
      fio: '',
      workerId: this.defaultBotWorkerId(),
      cityId: this.defaultBotCityId(),
      statusId: this.defaultBotStatusId(),
      counter: '0',
      active: true
    });
    this.promoTextForm.reset({ text: '' });
    this.managerTextForm.reset({
      payText: '',
      beginText: '',
      offerText: '',
      reminderText: '',
      startText: ''
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
      case 'accounts':
        this.saveBot();
        return;
      case 'promo':
        this.savePromoText();
        return;
      case 'managerTexts':
        this.saveManagerText();
        return;
    }
  }

  deleteSelected(): void {
    const activeTab = this.activeTab();
    if (activeTab === 'promo' || activeTab === 'managerTexts') {
      return;
    }

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
      products: () => this.dictionariesApi.deleteProduct(selectedId),
      accounts: () => this.dictionariesApi.deleteBot(selectedId),
      promo: () => this.dictionariesApi.deleteBot(selectedId)
    }[activeTab]();

    request.subscribe({
      next: () => {
        this.deleting.set(false);
        this.clearSelection();
        this.toastService.success('Запись удалена', this.activeLabel());
        this.reloadAfterMutation();
      },
      error: (err: unknown) => {
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
      products: this.products().length,
      accounts: this.bots().length,
      promo: this.promoTexts().length,
      managerTexts: this.managerTexts().length
    }[tab];
  }

  categoryTitle(category?: DictionaryOption | null): string {
    return category?.title || '-';
  }

  selectedTitle(): string {
    if (this.activeTab() === 'managerTexts') {
      return this.selectedManagerText()?.managerTitle ?? 'Выберите менеджера';
    }

    if (this.activeTab() === 'promo' && this.selectedId() == null) {
      return 'Новый промо-текст';
    }

    return this.selectedId() == null ? 'Новая запись' : `ID ${this.selectedId()}`;
  }

  promoTextLabel(promoText: AdminPromoText): string {
    return PROMO_TEXT_LABELS[promoText.position] ?? `Текст #${promoText.position}`;
  }

  promoTextMeta(promoText: AdminPromoText): string {
    return `ID ${promoText.id} · позиция ${promoText.position}`;
  }

  promoTextPreview(value: string): string {
    const normalized = value.replace(/\s+/g, ' ').trim();
    return normalized.length > 110 ? `${normalized.slice(0, 110)}...` : normalized;
  }

  promoUsageSummary(promoText: AdminPromoText): string {
    const usages = this.promoAssignments()
      .filter((assignment) => assignment.promoTextId === promoText.id)
      .map((assignment) => `${assignment.managerTitle}: ${assignment.buttonLabel}`);

    if (usages.length) {
      return usages.slice(0, 2).join(', ') + (usages.length > 2 ? ` +${usages.length - 2}` : '');
    }

    const defaultButtons = this.promoButtons()
      .filter((button) => button.defaultPromoTextId === promoText.id)
      .map((button) => `${button.sectionTitle}: ${button.buttonLabel}`);

    return defaultButtons.length ? `по умолчанию: ${defaultButtons.slice(0, 2).join(', ')}` : 'не назначен';
  }

  managerTextSummary(managerText: AdminManagerText): string {
    const fields = [
      ['оплата', managerText.payText],
      ['начало', managerText.beginText],
      ['оффер', managerText.offerText],
      ['напоминание', managerText.reminderText],
      ['старт', managerText.startText]
    ];
    const filled = fields.filter(([, value]) => value.trim().length > 0).map(([label]) => label);
    return filled.length ? filled.join(', ') : 'тексты не заполнены';
  }

  managerTextPreview(managerText: AdminManagerText): string {
    const value = [
      managerText.payText,
      managerText.beginText,
      managerText.offerText,
      managerText.reminderText,
      managerText.startText
    ].find((text) => text.trim().length > 0) ?? '';
    return this.promoTextPreview(value);
  }

  selectedPromoManagerTitle(): string {
    const managerId = this.selectedPromoManagerId();
    return this.promoManagers().find((manager) => manager.id === managerId)?.title ?? 'Выберите менеджера';
  }

  selectPromoManager(value: string | number): void {
    const managerId = Number(value);
    this.selectedPromoManagerId.set(Number.isFinite(managerId) && managerId > 0 ? managerId : null);
    this.error.set(null);
  }

  promoAssignmentValue(button: PromoButtonSlot): number | '' {
    return this.promoAssignmentFor(button)?.promoTextId ?? '';
  }

  promoDefaultTextLabel(button: PromoButtonSlot): string {
    const defaultText = this.promoTexts().find((text) => text.id === button.defaultPromoTextId);
    return defaultText ? this.promoTextLabel(defaultText) : `позиция ${button.defaultPromoPosition}`;
  }

  promoAssignedTextLabel(button: PromoButtonSlot): string {
    const assignment = this.promoAssignmentFor(button);
    if (!assignment?.promoTextId) {
      return `по умолчанию: ${this.promoDefaultTextLabel(button)}`;
    }

    const text = this.promoTexts().find((promoText) => promoText.id === assignment.promoTextId);
    return text ? this.promoTextLabel(text) : assignment.promoTextLabel;
  }

  savePromoAssignment(button: PromoButtonSlot, value: string): void {
    const managerId = this.selectedPromoManagerId();
    if (managerId == null) {
      this.error.set('Выберите менеджера для назначения промо-текста.');
      return;
    }

    this.saving.set(true);
    this.error.set(null);

    const request: Observable<PromoTextAssignment | void> = value
      ? this.dictionariesApi.savePromoTextAssignment({
          managerId,
          section: button.section,
          buttonKey: button.buttonKey,
          promoTextId: Number(value)
        } satisfies PromoTextAssignmentRequest)
      : this.dictionariesApi.resetPromoTextAssignment(managerId, button.section, button.buttonKey);

    request.subscribe({
      next: () => {
        this.saving.set(false);
        this.toastService.success('Назначение сохранено', `${button.sectionTitle}: ${button.buttonLabel}`);
        this.reloadPromoManagement();
      },
      error: (err: unknown) => {
        const message = this.errorMessage(err, 'Не удалось сохранить назначение');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Назначение не сохранено', message);
      }
    });
  }

  botBrowserUrl(bot: AdminBot): string {
    return `/admin/dictionaries/accounts/${bot.id}/browser`;
  }

  openBotImport(): void {
    this.importModalOpen.set(true);
    this.importFile.set(null);
    this.importResult.set(null);
    this.importError.set(null);
  }

  closeBotImport(): void {
    if (this.importing()) {
      return;
    }

    this.importModalOpen.set(false);
    this.importFile.set(null);
    this.importError.set(null);
  }

  selectBotImportFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.importFile.set(input.files?.[0] ?? null);
    this.importResult.set(null);
    this.importError.set(null);
  }

  uploadBotImport(): void {
    const file = this.importFile();
    if (!file || this.importing()) {
      this.importError.set('Выберите CSV или Excel-файл.');
      return;
    }

    this.importing.set(true);
    this.importError.set(null);
    this.importResult.set(null);

    this.dictionariesApi.importBots(file).subscribe({
      next: (result) => {
        this.importing.set(false);
        this.importResult.set(result);
        this.importFile.set(null);
        this.toastService.success('Импорт аккаунтов завершен', this.importResultMessage(result));
        this.reloadAfterMutation();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось импортировать аккаунты');
        this.importError.set(message);
        this.importing.set(false);
        this.toastService.error('Аккаунты не импортированы', message);
      }
    });
  }

  importResultMessage(result: BotImportResponse): string {
    const duplicateText = result.skippedDuplicates ? `, дублей пропущено: ${result.skippedDuplicates}` : '';
    const invalidText = result.skippedInvalid ? `, строк с ошибками: ${result.skippedInvalid}` : '';
    return `Добавлено: ${result.added}${duplicateText}${invalidText}`;
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

  trackBot(_index: number, bot: AdminBot): number {
    return bot.id;
  }

  trackPromoText(_index: number, promoText: AdminPromoText): number {
    return promoText.id;
  }

  trackManagerText(_index: number, managerText: AdminManagerText): number {
    return managerText.managerId;
  }

  trackPromoButton(_index: number, button: PromoButtonSlot): string {
    return `${button.section}:${button.buttonKey}`;
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
    let request: Observable<AdminCategory[] | AdminSubCategory[] | AdminCity[] | ProductsResponse | BotsResponse | PromoTextManagementResponse | AdminManagerText[]>;
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
      case 'accounts':
        request = this.dictionariesApi.getBots(keyword);
        break;
      case 'promo':
        request = this.dictionariesApi.getPromoTextManagement(keyword);
        break;
      case 'managerTexts':
        request = this.dictionariesApi.getManagerTexts(keyword);
        break;
    }

    request.subscribe({
      next: (response: AdminCategory[] | AdminSubCategory[] | AdminCity[] | ProductsResponse | BotsResponse | PromoTextManagementResponse | AdminManagerText[]) => {
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
          case 'accounts':
            this.applyBotsResponse(response as BotsResponse);
            this.ensureDefaults();
            break;
          case 'promo':
            this.applyPromoManagement(response as PromoTextManagementResponse);
            break;
          case 'managerTexts':
            this.managerTexts.set(response as AdminManagerText[]);
            break;
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

  private saveBot(): void {
    if (this.botForm.invalid) {
      this.botForm.markAllAsTouched();
      return;
    }

    const raw = this.botForm.getRawValue();
    const request: BotRequest = {
      login: raw.login.trim(),
      password: raw.password.trim(),
      fio: raw.fio.trim(),
      workerId: raw.workerId,
      cityId: raw.cityId,
      statusId: raw.statusId,
      counter: Number(raw.counter || 0),
      active: raw.active
    };
    const selectedId = this.selectedId();
    const call = selectedId == null
      ? this.dictionariesApi.createBot(request)
      : this.dictionariesApi.updateBot(selectedId, request);

    this.runSave(call, 'Аккаунт сохранен');
  }

  private savePromoText(): void {
    if (this.promoTextForm.invalid) {
      this.promoTextForm.markAllAsTouched();
      return;
    }

    const request: PromoTextRequest = {
      text: this.promoTextForm.controls.text.value.trim()
    };

    const selectedId = this.selectedId();
    const call = selectedId == null
      ? this.dictionariesApi.createPromoText(request)
      : this.dictionariesApi.updatePromoText(selectedId, request);

    this.runSave(call, 'Промо-текст сохранен');
  }

  private saveManagerText(): void {
    const managerId = this.selectedId();
    if (managerId == null) {
      this.error.set('Выберите менеджера для редактирования текстов.');
      return;
    }

    const raw = this.managerTextForm.getRawValue();
    const request: ManagerTextRequest = {
      payText: raw.payText,
      beginText: raw.beginText,
      offerText: raw.offerText,
      reminderText: raw.reminderText,
      startText: raw.startText
    };

    this.saving.set(true);
    this.error.set(null);

    this.dictionariesApi.updateManagerText(managerId, request).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.selectedId.set(saved.managerId);
        this.managerTexts.update((items) =>
          items.map((item) => item.managerId === saved.managerId ? saved : item)
        );
        this.managerTextForm.setValue({
          payText: saved.payText,
          beginText: saved.beginText,
          offerText: saved.offerText,
          reminderText: saved.reminderText,
          startText: saved.startText
        });
        this.toastService.success('Тексты менеджера сохранены', saved.managerTitle);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить тексты менеджера');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Тексты не сохранены', message);
      }
    });
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
    if (this.activeTab() === 'promo') {
      this.reloadPromoManagement();
      return;
    }

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

  private defaultBotWorkerId(): number | null {
    return this.botWorkers()[0]?.id ?? null;
  }

  private defaultBotStatusId(): number | null {
    return this.botStatuses()[0]?.id ?? null;
  }

  private defaultBotCityId(): number | null {
    return this.botCities()[0]?.id ?? null;
  }

  private applyBotsResponse(response: BotsResponse): void {
    this.bots.set(response.bots);
    this.botWorkers.set(response.workers);
    this.botStatuses.set(response.statuses);
    this.botCities.set(response.cities);
  }

  private applyPromoManagement(response: PromoTextManagementResponse): void {
    this.promoTexts.set(response.texts);
    this.promoManagers.set(response.managers);
    this.promoAssignments.set(response.assignments);
    this.promoButtons.set(response.buttons);

    const selectedManagerId = this.selectedPromoManagerId();
    const hasSelectedManager = selectedManagerId != null
      && response.managers.some((manager) => manager.id === selectedManagerId);
    if (!hasSelectedManager) {
      this.selectedPromoManagerId.set(response.managers[0]?.id ?? null);
    }
  }

  private reloadPromoManagement(): void {
    this.dictionariesApi.getPromoTextManagement(this.search()).subscribe({
      next: (response) => this.applyPromoManagement(response),
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось обновить промо-тексты');
        this.error.set(message);
        this.toastService.error('Промо не обновилось', message);
      }
    });
  }

  private promoAssignmentFor(button: PromoButtonSlot): PromoTextAssignment | null {
    const managerId = this.selectedPromoManagerId();
    if (managerId == null) {
      return null;
    }

    return this.promoAssignments().find((assignment) =>
      assignment.managerId === managerId
      && assignment.section === button.section
      && assignment.buttonKey === button.buttonKey
    ) ?? null;
  }

  private handleLoadAllError(err: unknown): void {
    const message = this.errorMessage(err, 'Не удалось загрузить справочники');
    this.error.set(message);
    this.loading.set(false);
    this.toastService.error('Справочники не загрузились', message);
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

    if (this.botForm.controls.workerId.value == null) {
      this.botForm.controls.workerId.setValue(this.defaultBotWorkerId());
    }

    if (this.botForm.controls.statusId.value == null) {
      this.botForm.controls.statusId.setValue(this.defaultBotStatusId());
    }

    if (this.botForm.controls.cityId.value == null) {
      this.botForm.controls.cityId.setValue(this.defaultBotCityId());
    }
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }
}
