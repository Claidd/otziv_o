import { signal, WritableSignal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import type { ToastService } from '../../../shared/toast.service';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { DictionaryViewScope } from './dictionary-view-scope';
import { DictionaryEditorSession } from './dictionary-editor-session';
import type { AdminProductsApi } from '../../../core/admin-products.api';
import type {
  AdminProduct,
  DictionaryOption,
  ProductRequest,
} from '../../../core/admin-dictionaries.api';

type Deps = {
  api: AdminProductsApi;
  toast: Pick<ToastService, 'success' | 'error'>;
  isActive: () => boolean;
  selectedId: WritableSignal<number | null>;
  search: () => string;
};

export class AdminProductsFacade {
  private readonly fb = new FormBuilder();
  private readonly scope: DictionaryViewScope;
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);
  readonly products = signal<AdminProduct[]>([]);
  readonly productCategories = signal<DictionaryOption[]>([]);
  readonly productForm = this.fb.group({
    title: this.fb.nonNullable.control('', Validators.required),
    price: this.fb.nonNullable.control('0', Validators.required),
    categoryId: this.fb.control<number | null>(null, Validators.required),
    photo: this.fb.nonNullable.control(false),
    requiresPerformer: this.fb.nonNullable.control(false),
    targetPlatform: this.fb.nonNullable.control<'YANDEX' | 'GOOGLE' | 'GIS' | 'OTHER'>('OTHER'),
    performerRewardPercent: this.fb.nonNullable.control(0),
    specialistRewardPercent: this.fb.nonNullable.control(0),
    managerRewardPercent: this.fb.nonNullable.control(0),
  });
  private readonly editor = new DictionaryEditorSession(this.productForm);
  constructor(private readonly deps: Deps) {
    this.scope = new DictionaryViewScope(deps.isActive);
  }
  private get selectedId() {
    return this.deps.selectedId;
  }
  deactivate(): void {
    this.editor.change();
    this.scope.deactivate();
  }
  destroy(): void {
    this.editor.destroy();
    this.scope.destroy();
  }

  load(): void {
    if (!this.scope.active()) return;
    this.error.set(null);
    const editor = this.editor.capture(this.selectedId());
    this.scope
      .read('products', this.deps.api.getProducts(this.deps.search()), this.loading)
      .subscribe({
        next: (payload) => {
          this.products.set(payload.products);
          this.productCategories.set(payload.categories);
          if (this.editor.sameDraft(editor, this.selectedId()) && !this.productForm.dirty)
            this.ensureDefaults();
        },
        error: (err) => this.fail(err, 'Справочник не загрузился', 'Не удалось загрузить продукты'),
      });
  }

  selectProduct(product: AdminProduct): void {
    this.editor.change();
    this.selectedId.set(product.id);
    this.productForm.setValue({
      title: product.title,
      price: String(product.price ?? 0),
      categoryId: product.category?.id ?? this.defaultProductCategoryId(),
      photo: product.photo,
      requiresPerformer: product.requiresPerformer,
      targetPlatform: product.targetPlatform || 'OTHER',
      performerRewardPercent: product.performerRewardPercent ?? 0,
      specialistRewardPercent: product.specialistRewardPercent ?? 0,
      managerRewardPercent: product.managerRewardPercent ?? 0,
    });
    this.error.set(null);
  }

  clearSelection(): void {
    this.editor.change();
    this.selectedId.set(null);
    this.error.set(null);
    this.productForm.reset({
      title: '',
      price: '0',
      categoryId: this.defaultProductCategoryId(),
      photo: false,
      requiresPerformer: false,
      targetPlatform: 'OTHER',
      performerRewardPercent: 0,
      specialistRewardPercent: 0,
      managerRewardPercent: 0,
    });
  }

  saveProduct(): void {
    if (!this.scope.active() || this.saving() || this.deleting()) return;
    if (this.productForm.invalid) {
      this.productForm.markAllAsTouched();
      return;
    }
    const raw = this.productForm.getRawValue();
    const request: ProductRequest = {
      title: raw.title.trim(),
      price: Number(raw.price || 0),
      categoryId: raw.categoryId,
      photo: raw.photo,
      requiresPerformer: raw.requiresPerformer,
      targetPlatform: raw.targetPlatform || 'OTHER',
      performerRewardPercent: Number(raw.performerRewardPercent || 0),
      specialistRewardPercent: Number(raw.specialistRewardPercent || 0),
      managerRewardPercent: Number(raw.managerRewardPercent || 0),
    };
    const selectedId = this.selectedId();
    const call =
      selectedId == null
        ? this.deps.api.createProduct(request)
        : this.deps.api.updateProduct(selectedId, request);
    const editor = this.editor.capture(selectedId);
    this.saving.set(true);
    this.error.set(null);
    this.scope.write(call, this.saving).subscribe({
      next: (saved) => {
        this.saving.set(false);
        if (this.editor.sameSelection(editor, this.selectedId())) this.selectedId.set(saved.id);
        this.deps.toast.success('Продукт сохранен', `ID ${saved.id}`);
        this.load();
      },
      error: (err) => {
        if (this.editor.sameSelection(editor, this.selectedId()))
          this.fail(err, 'Запись не сохранена', 'Не удалось сохранить продукт');
      },
    });
  }

  deleteSelected(): void {
    const id = this.selectedId();
    if (id == null || !this.scope.active() || this.saving() || this.deleting()) return;
    const editor = this.editor.capture(id);
    if (!window.confirm('Удалить запись из справочника "Продукты"?')) return;
    if (!this.scope.active() || !this.editor.sameSelection(editor, this.selectedId())) return;
    this.deleting.set(true);
    this.error.set(null);
    this.scope.write(this.deps.api.deleteProduct(id), this.deleting).subscribe({
      next: () => {
        this.deleting.set(false);
        if (this.editor.sameDraft(editor, this.selectedId())) this.clearSelection();
        else if (this.editor.sameSelection(editor, this.selectedId())) this.selectedId.set(null);
        this.deps.toast.success('Запись удалена', 'Продукты');
        this.load();
      },
      error: (err) => {
        if (this.editor.sameSelection(editor, this.selectedId()))
          this.fail(err, 'Запись не удалена', 'Не удалось удалить продукт');
      },
    });
  }
  ensureDefaults(): void {
    if (this.productForm.controls.categoryId.value == null && !this.productForm.dirty)
      this.productForm.controls.categoryId.setValue(this.defaultProductCategoryId());
  }
  defaultProductCategoryId(): number | null {
    return this.productCategories()[0]?.id ?? null;
  }
  private fail(error: unknown, title: string, fallback: string): void {
    const message = apiErrorMessage(error, fallback);
    this.error.set(message);
    this.deps.toast.error(title, message);
  }
}
