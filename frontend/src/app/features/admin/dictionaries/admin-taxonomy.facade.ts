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
  /** Add and rename: administrators, the owner and managers. */
  canWrite: () => boolean;
  /** Delete: administrators and the owner only. */
  canDelete: () => boolean;
  tab: () => string;
  search: () => string;
};

/** Asked before a category or subcategory window drops text the user typed. */
const CLOSE_UNSAVED_QUESTION = 'Есть несохранённые изменения. Закрыть окно без сохранения?';

/** Russian plural form: 1 подкатегория, 2 подкатегории, 5 подкатегорий. */
function plural(count: number, one: string, few: string, many: string): string {
  const mod10 = count % 10;
  const mod100 = count % 100;
  if (mod10 === 1 && mod100 !== 11) return one;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return few;
  return many;
}

/** «категорию «Автосервис»», or just the noun when the record has no title. */
function named(noun: string, title: string | null | undefined): string {
  const text = (title ?? '').trim();
  return text ? `${noun} «${text}»` : noun;
}

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

  /** The window title names the saved record («Категория «Автосервис»»), not its internal number. */
  readonly categoryEditorTitle = computed(() => {
    const id = this.editingCategoryId();
    if (id == null) return 'Новая категория';
    const title = this.categories().find((category) => category.id === id)?.title;
    return title ? `Категория «${title}»` : 'Категория';
  });

  readonly subCategoryEditorTitle = computed(() => {
    const id = this.editingSubCategoryId();
    if (id == null) return 'Новая подкатегория';
    const title = this.subCategories().find((subCategory) => subCategory.id === id)?.title;
    return title ? `Подкатегория «${title}»` : 'Подкатегория';
  });
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

  /**
   * Managers add and rename categories but do not delete them; other roles only read. The page hides the controls a
   * role lacks; a change that still reaches the facade is refused with a message instead of silently doing nothing.
   */
  private writeAllowed(): boolean {
    if (this.deps.canWrite()) return true;
    this.deps.toast.error(
      'Только просмотр',
      'Категории и подкатегории меняют администратор, владелец и менеджеры.',
    );
    return false;
  }

  private deleteAllowed(): boolean {
    if (this.deps.canDelete()) return true;
    this.deps.toast.error(
      'Удаление недоступно',
      'Удаляют категории и подкатегории администратор и владелец.',
    );
    return false;
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
    if (!this.writeAllowed()) return;
    this.startNewCategory();
    this.categoryEditorOpen.set(true);
  }

  editCategory(event: Event, category: AdminCategory): void {
    event.preventDefault();
    event.stopPropagation();
    if (!this.writeAllowed()) return;
    this.categoryEditor.change();
    this.activeCategoryId.set(category.id);
    this.editingCategoryId.set(category.id);
    // reset, not setValue: the window opens clean, so closing it asks only about edits typed in it.
    this.categoryForm.reset({ title: category.title });
    this.categoryEditorOpen.set(true);
    this.error.set(null);
  }

  closeCategoryEditor(): void {
    if (this.saving() || this.deleting()) {
      return;
    }
    // A click on the dark background or «Закрыть» must not silently drop a typed name.
    if (this.categoryForm.dirty && !window.confirm(CLOSE_UNSAVED_QUESTION)) {
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
    // reset, not setValue: the form starts clean for this subcategory, so only new edits count as unsaved.
    this.subCategoryForm.reset({
      title: subCategory.title,
      categoryId,
    });
    this.error.set(null);
  }

  openNewSubCategory(): void {
    if (!this.writeAllowed()) return;
    if (this.activeCategoryId() == null) {
      this.activeCategoryId.set(this.defaultCategoryId());
    }
    this.startNewSubCategory();
    this.subCategoryEditorOpen.set(true);
  }

  editSubCategory(event: Event, subCategory: AdminSubCategory): void {
    event.preventDefault();
    event.stopPropagation();
    if (!this.writeAllowed()) return;
    this.selectSubCategory(subCategory);
    this.subCategoryEditorOpen.set(true);
  }

  closeSubCategoryEditor(): void {
    if (this.saving() || this.deleting()) {
      return;
    }
    if (this.subCategoryForm.dirty && !window.confirm(CLOSE_UNSAVED_QUESTION)) {
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
    if (!this.writeAllowed() || !this.canMutate()) return;
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
    if (!this.writeAllowed() || !this.canMutate()) return;
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
          // A subcategory moved to another category stays in view: the list switches to that category.
          const categoryId = saved.category?.id ?? request.categoryId;
          if (categoryId != null) this.activeCategoryId.set(categoryId);
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
    if (id == null || !this.deleteAllowed() || !this.canMutate()) return;
    const editor = this.categoryEditor.capture(id);
    if (!window.confirm(this.categoryDeleteQuestion(id))) return;
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
    if (id == null || !this.deleteAllowed() || !this.canMutate()) return;
    const editor = this.subCategoryEditor.capture(id);
    if (!window.confirm(this.subCategoryDeleteQuestion(id))) return;
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

  /**
   * Deleting a category cannot be undone: the database removes its subcategories with it and empties the field of
   * companies and reviews that had it. The question names the category and says so.
   */
  private categoryDeleteQuestion(id: number): string {
    const category = this.categories().find((item) => item.id === id);
    const count = category
      ? category.subCategoryCount
      : this.subCategories().filter((item) => item.category?.id === id).length;
    const lines = [
      `Удалить ${named('категорию', category?.title || this.categoryForm.controls.title.value)}?`,
    ];
    if (count > 0) {
      const verb = plural(count, 'удалится', 'удалятся', 'удалятся');
      const noun = plural(count, 'подкатегория', 'подкатегории', 'подкатегорий');
      lines.push(`Вместе с ней ${verb} ${count} ${noun}.`);
    } else {
      lines.push('Подкатегорий у неё нет.');
    }
    lines.push(
      'Если категорию или её подкатегории используют компании или отзывы, удалить её не получится — сначала переведите их в другую категорию.',
    );
    lines.push('Отменить нельзя.');
    return lines.join('\n');
  }

  private subCategoryDeleteQuestion(id: number): string {
    const subCategory = this.subCategories().find((item) => item.id === id);
    const title = subCategory?.title || this.subCategoryForm.controls.title.value;
    const parent = subCategory?.category?.title || this.activeCategory()?.title;
    return [
      `Удалить ${named('подкатегорию', title)}${parent ? ` из категории «${parent}»` : ''}?`,
      'Если подкатегорию используют компании или отзывы, удалить её не получится — сначала переведите их в другую подкатегорию.',
      'Отменить нельзя.',
    ].join('\n');
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
