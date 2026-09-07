import { computed, signal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { forkJoin } from 'rxjs';
import type { AdminTaxonomyApi } from '../../../core/admin-taxonomy.api';
import type {
  AdminCategory,
  AdminSubCategory,
  SubCategoryRequest,
  TitleRequest,
} from '../../../core/admin-dictionaries.api';
import type { ToastService } from '../../../shared/toast.service';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { DictionaryViewScope } from './dictionary-view-scope';
import { DictionaryEditorSession } from './dictionary-editor-session';

type Deps = {
  api: AdminTaxonomyApi;
  toast: Pick<ToastService, 'success' | 'error'>;
  isActive: () => boolean;
  canWrite: () => boolean;
  tab: () => string;
  search: () => string;
};

export class AdminTaxonomyFacade {
  private readonly fb = new FormBuilder();
  private readonly scope: DictionaryViewScope;
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);
  readonly activeCategoryId = signal<number | null>(null);

  readonly editingCategoryId = signal<number | null>(null);

  readonly editingSubCategoryId = signal<number | null>(null);

  readonly categoryEditorOpen = signal(false);

  readonly subCategoryEditorOpen = signal(false);

  readonly categories = signal<AdminCategory[]>([]);

  readonly subCategories = signal<AdminSubCategory[]>([]);

  readonly categoryForm = this.fb.nonNullable.group({
    title: ['', Validators.required],
  });

  readonly subCategoryForm = this.fb.group({
    title: this.fb.nonNullable.control('', Validators.required),
    categoryId: this.fb.control<number | null>(null, Validators.required),
  });

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
    this.editingCategoryId() == null ? 'Новая категория' : `Категория #${this.editingCategoryId()}`,
  );

  readonly subCategoryEditorTitle = computed(() =>
    this.editingSubCategoryId() == null
      ? 'Новая подкатегория'
      : `Подкатегория #${this.editingSubCategoryId()}`,
  );
  private readonly categoryEditor = new DictionaryEditorSession(this.categoryForm);
  private readonly subCategoryEditor = new DictionaryEditorSession(this.subCategoryForm);
  constructor(private readonly deps: Deps) {
    this.scope = new DictionaryViewScope(deps.isActive);
  }
  deactivate(): void {
    this.categoryEditor.change();
    this.subCategoryEditor.change();
    this.scope.deactivate();
  }
  destroy(): void {
    this.categoryEditor.destroy();
    this.subCategoryEditor.destroy();
    this.scope.destroy();
  }
  private canMutate(): boolean {
    return this.scope.active() && this.deps.canWrite() && !this.saving() && !this.deleting();
  }

  load(): void {
    if (!this.scope.active()) return;
    this.error.set(null);
    const category = this.categoryEditor.capture(this.activeCategoryId());
    const subCategory = this.subCategoryEditor.capture(this.editingSubCategoryId());
    if (this.deps.tab() === 'subcategories') {
      this.scope
        .read('list', this.deps.api.getSubCategories(this.deps.search()), this.loading)
        .subscribe({
          next: (rows) => this.subCategories.set(rows),
          error: (err) =>
            this.fail(err, 'Справочник не загрузился', 'Не удалось загрузить подкатегории'),
        });
      return;
    }
    this.scope
      .read('list', this.deps.api.getCategories(this.deps.search()), this.loading)
      .subscribe({
        next: (rows) => {
          this.categories.set(rows);
          this.subCategories.set(
            rows.flatMap((parent) =>
              (parent.subCategories ?? []).map((child) => ({
                id: child.id,
                title: child.title,
                category: { id: parent.id, title: parent.title },
              })),
            ),
          );
          if (
            this.categoryEditor.sameSelection(category, this.activeCategoryId()) &&
            this.subCategoryEditor.sameDraft(subCategory, this.editingSubCategoryId())
          )
            this.ensureDefaults();
        },
        error: (err) =>
          this.fail(err, 'Справочник не загрузился', 'Не удалось загрузить категории'),
      });
  }

  private reloadAfterMutation(): void {
    const category = this.categoryEditor.capture(this.activeCategoryId());
    const subCategory = this.subCategoryEditor.capture(this.editingSubCategoryId());
    const tab = this.deps.tab();
    this.scope
      .read(
        'list',
        forkJoin({
          categories: this.deps.api.getCategories(tab === 'categories' ? this.deps.search() : ''),
          subCategories: this.deps.api.getSubCategories(
            tab === 'subcategories' ? this.deps.search() : '',
          ),
        }),
        this.loading,
      )
      .subscribe({
        next: (rows) => {
          this.categories.set(rows.categories);
          this.subCategories.set(rows.subCategories);
          if (
            this.categoryEditor.sameSelection(category, this.activeCategoryId()) &&
            this.subCategoryEditor.sameDraft(subCategory, this.editingSubCategoryId())
          )
            this.ensureDefaults();
        },
        error: (err) => this.fail(err, 'Справочник не загрузился', 'Не удалось обновить категории'),
      });
  }

  selectCategory(category: AdminCategory): void {
    this.categoryEditor.change();
    this.activeCategoryId.set(category.id);
    this.startNewSubCategory();
    this.error.set(null);
  }

  openNewCategory(): void {
    this.startNewCategory();
    this.categoryEditorOpen.set(true);
  }

  editCategory(event: Event, category: AdminCategory): void {
    this.categoryEditor.change();
    event.preventDefault();
    event.stopPropagation();
    this.activeCategoryId.set(category.id);
    this.editingCategoryId.set(category.id);
    this.categoryForm.setValue({ title: category.title });
    this.categoryEditorOpen.set(true);
    this.error.set(null);
  }

  closeCategoryEditor(): void {
    if (this.saving() || this.deleting()) {
      return;
    }
    this.categoryEditorOpen.set(false);
    this.startNewCategory();
  }

  selectSubCategory(subCategory: AdminSubCategory): void {
    this.subCategoryEditor.change();
    this.categoryEditor.change();
    const categoryId =
      subCategory.category?.id ?? this.activeCategoryId() ?? this.defaultCategoryId();
    this.editingSubCategoryId.set(subCategory.id);
    this.activeCategoryId.set(categoryId);
    this.subCategoryForm.setValue({
      title: subCategory.title,
      categoryId,
    });
    this.error.set(null);
  }

  openNewSubCategory(): void {
    if (this.activeCategoryId() == null) {
      this.activeCategoryId.set(this.defaultCategoryId());
    }
    this.startNewSubCategory();
    this.subCategoryEditorOpen.set(true);
  }

  editSubCategory(event: Event, subCategory: AdminSubCategory): void {
    event.preventDefault();
    event.stopPropagation();
    this.selectSubCategory(subCategory);
    this.subCategoryEditorOpen.set(true);
  }

  closeSubCategoryEditor(): void {
    if (this.saving() || this.deleting()) {
      return;
    }
    this.subCategoryEditorOpen.set(false);
    this.startNewSubCategory();
  }

  startNewCategory(): void {
    this.categoryEditor.change();
    this.editingCategoryId.set(null);
    this.categoryForm.reset({ title: '' });
    this.error.set(null);
  }

  startNewSubCategory(): void {
    this.subCategoryEditor.change();
    this.editingSubCategoryId.set(null);
    this.subCategoryForm.reset({
      title: '',
      categoryId: this.activeCategoryId() ?? this.defaultCategoryId(),
    });
    this.error.set(null);
  }

  saveSelectedSubCategory(): void {
    this.saveSubCategory();
  }
  clearSelection(): void {
    this.categoryEditorOpen.set(false);
    this.subCategoryEditorOpen.set(false);
    this.startNewCategory();
    this.startNewSubCategory();
  }

  saveCategory(): void {
    if (!this.canMutate()) return;
    if (this.categoryForm.invalid) {
      this.categoryForm.markAllAsTouched();
      return;
    }
    const request: TitleRequest = { title: this.categoryForm.controls.title.value.trim() };
    const selectedId = this.editingCategoryId();
    const call =
      selectedId == null
        ? this.deps.api.createCategory(request)
        : this.deps.api.updateCategory(selectedId, request);
    const editor = this.categoryEditor.capture(selectedId);
    this.saving.set(true);
    this.error.set(null);
    this.scope.write(call, this.saving).subscribe({
      next: (saved) => {
        this.saving.set(false);
        if (this.categoryEditor.sameSelection(editor, this.editingCategoryId())) {
          const unchanged = this.categoryEditor.sameDraft(editor, this.editingCategoryId());
          this.editingCategoryId.set(saved.id);
          this.activeCategoryId.set(saved.id);
          if (unchanged) this.categoryEditorOpen.set(false);
        }
        this.deps.toast.success('Категория сохранена', `ID ${saved.id}`);
        this.reloadAfterMutation();
      },
      error: (err) => {
        if (this.categoryEditor.sameSelection(editor, this.editingCategoryId()))
          this.fail(err, 'Запись не сохранена', 'Не удалось сохранить запись');
      },
    });
  }

  saveSubCategory(): void {
    if (!this.canMutate()) return;
    if (this.subCategoryForm.invalid) {
      this.subCategoryForm.markAllAsTouched();
      return;
    }
    const raw = this.subCategoryForm.getRawValue();
    const request: SubCategoryRequest = {
      title: raw.title.trim(),
      categoryId: raw.categoryId ?? this.activeCategoryId(),
    };
    const selectedId = this.editingSubCategoryId();
    const call =
      selectedId == null
        ? this.deps.api.createSubCategory(request)
        : this.deps.api.updateSubCategory(selectedId, request);
    const editor = this.subCategoryEditor.capture(selectedId);
    this.saving.set(true);
    this.error.set(null);
    this.scope.write(call, this.saving).subscribe({
      next: (saved) => {
        this.saving.set(false);
        if (this.subCategoryEditor.sameSelection(editor, this.editingSubCategoryId())) {
          const unchanged = this.subCategoryEditor.sameDraft(editor, this.editingSubCategoryId());
          this.editingSubCategoryId.set(saved.id);
          if (unchanged) this.subCategoryEditorOpen.set(false);
        }
        this.deps.toast.success('Подкатегория сохранена', `ID ${saved.id}`);
        this.reloadAfterMutation();
      },
      error: (err) => {
        if (this.subCategoryEditor.sameSelection(editor, this.editingSubCategoryId()))
          this.fail(err, 'Запись не сохранена', 'Не удалось сохранить запись');
      },
    });
  }
  deleteEditingCategory(): void {
    const id = this.editingCategoryId();
    if (id == null || !this.canMutate()) return;
    const editor = this.categoryEditor.capture(id);
    if (!window.confirm('Удалить категорию?')) return;
    if (!this.canMutate() || !this.categoryEditor.sameSelection(editor, this.editingCategoryId()))
      return;
    this.deleting.set(true);
    this.error.set(null);
    this.scope.write(this.deps.api.deleteCategory(id), this.deleting).subscribe({
      next: () => {
        this.deleting.set(false);
        if (this.categoryEditor.sameSelection(editor, this.editingCategoryId())) {
          const unchanged = this.categoryEditor.sameDraft(editor, this.editingCategoryId());
          this.editingCategoryId.set(null);
          if (unchanged) {
            this.categoryEditorOpen.set(false);
            this.startNewCategory();
          }
          if (this.activeCategoryId() === id) this.activeCategoryId.set(null);
        }
        this.deps.toast.success('Категория удалена');
        this.reloadAfterMutation();
      },
      error: (err) => {
        if (this.categoryEditor.sameSelection(editor, this.editingCategoryId()))
          this.fail(err, 'Категория не удалена', 'Не удалось удалить запись');
      },
    });
  }

  deleteEditingSubCategory(): void {
    const id = this.editingSubCategoryId();
    if (id == null || !this.canMutate()) return;
    const editor = this.subCategoryEditor.capture(id);
    if (!window.confirm('Удалить подкатегорию?')) return;
    if (
      !this.canMutate() ||
      !this.subCategoryEditor.sameSelection(editor, this.editingSubCategoryId())
    )
      return;
    this.deleting.set(true);
    this.error.set(null);
    this.scope.write(this.deps.api.deleteSubCategory(id), this.deleting).subscribe({
      next: () => {
        this.deleting.set(false);
        if (this.subCategoryEditor.sameSelection(editor, this.editingSubCategoryId())) {
          const unchanged = this.subCategoryEditor.sameDraft(editor, this.editingSubCategoryId());
          this.editingSubCategoryId.set(null);
          if (unchanged) {
            this.subCategoryEditorOpen.set(false);
            this.startNewSubCategory();
          }
        }
        this.deps.toast.success('Подкатегория удалена');
        this.reloadAfterMutation();
      },
      error: (err) => {
        if (this.subCategoryEditor.sameSelection(editor, this.editingSubCategoryId()))
          this.fail(err, 'Подкатегория не удалена', 'Не удалось удалить запись');
      },
    });
  }

  ensureDefaults(): void {
    const activeCategoryId = this.activeCategoryId();
    const hasActiveCategory =
      activeCategoryId != null &&
      this.categories().some((category) => category.id === activeCategoryId);
    if (!hasActiveCategory) {
      this.activeCategoryId.set(this.defaultCategoryId());
    }
    const subCategoryCategoryId = this.subCategoryForm.controls.categoryId.value;
    const hasSubCategoryCategory =
      subCategoryCategoryId != null &&
      this.categories().some((category) => category.id === subCategoryCategoryId);
    if (!this.subCategoryForm.dirty && (subCategoryCategoryId == null || !hasSubCategoryCategory)) {
      this.subCategoryForm.controls.categoryId.setValue(
        this.activeCategoryId() ?? this.defaultCategoryId(),
      );
    }
  }

  defaultCategoryId(): number | null {
    return this.categories()[0]?.id ?? null;
  }
  private fail(error: unknown, title: string, fallback: string): void {
    const message = apiErrorMessage(error, fallback);
    this.error.set(message);
    this.deps.toast.error(title, message);
  }
}
