import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  CompanyCreateApi,
  CompanyCreateOption,
  CompanyCreatePayload,
  CompanyCreateRequest,
  CompanyCreateResult,
  CompanyCreateSource
} from '../core/company-create.api';
import { apiErrorMessage } from './api-error-message';
import { LoadErrorCardComponent } from './load-error-card.component';

type CompanyCreateDraft = {
  source: CompanyCreateSource;
  leadId: number | null;
  managerId: number | null;
  title: string;
  urlChat: string;
  telephone: string;
  city: string;
  email: string;
  commentsCompany: string;
  categoryId: number | null;
  subCategoryId: number | null;
  workerId: number | null;
  filialCityId: number | null;
  filialTitle: string;
  filialUrl: string;
};

type PreservedDraftFields = Pick<
  CompanyCreateDraft,
  'title' | 'urlChat' | 'telephone' | 'city' | 'email' | 'commentsCompany' | 'categoryId' | 'subCategoryId' | 'workerId' | 'filialCityId' | 'filialTitle' | 'filialUrl'
>;

@Component({
  selector: 'app-company-create-modal',
  imports: [FormsModule, LoadErrorCardComponent],
  template: `
    <div class="lead-edit-backdrop" role="presentation" (click)="close()">
      <section
        class="lead-edit-modal company-create-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="company-create-title"
        (click)="$event.stopPropagation()"
      >
        <header class="lead-edit-head">
          <div>
            <small>{{ leadId ? 'Лид #' + leadId : 'Без лида' }}</small>
            <h2 id="company-create-title">Создание компании</h2>
          </div>
          <button type="button" class="lead-edit-close" (click)="close()" [disabled]="saving()" title="Закрыть">
            <span class="material-icons-sharp">close</span>
          </button>
        </header>

        @if (loading()) {
          <p class="error compact">Загружаю данные компании...</p>
        }

        @if (error()) {
          <app-load-error-card title="Компания не сохранена" [message]="error()!" />
        }

        @if (draft(); as draft) {
          <form class="lead-edit-form" #companyForm="ngForm" (ngSubmit)="createCompany(companyForm.invalid)">
            <div class="lead-edit-grid">
              @if (payload()?.canChangeManager) {
                <label class="lead-edit-field lead-edit-field--wide">
                  <span>Менеджер</span>
                  <select
                    name="companyManager"
                    required
                    [disabled]="loading() || saving()"
                    [ngModel]="draft.managerId"
                    (ngModelChange)="changeManager($event)"
                  >
                    @if (managerOptions().length === 0) {
                      <option [ngValue]="null">Нет доступных менеджеров</option>
                    } @else {
                      @for (manager of managerOptions(); track trackOption($index, manager)) {
                        <option [ngValue]="manager.id">{{ manager.label }}</option>
                      }
                    }
                  </select>
                </label>
              }

              <label class="lead-edit-field">
                <span>Название</span>
                <input
                  name="companyTitle"
                  type="text"
                  required
                  [disabled]="saving()"
                  [ngModel]="draft.title"
                  (ngModelChange)="setField('title', $event)"
                >
              </label>

              <label class="lead-edit-field">
                <span>Телефон</span>
                <input
                  name="companyTelephone"
                  type="text"
                  required
                  autocomplete="tel"
                  [readonly]="source !== 'manual'"
                  [disabled]="saving()"
                  [ngModel]="draft.telephone"
                  (ngModelChange)="setField('telephone', $event)"
                >
              </label>

              <label class="lead-edit-field">
                <span>Город компании</span>
                <input
                  name="companyCity"
                  type="text"
                  required
                  [readonly]="source !== 'manual'"
                  [disabled]="saving()"
                  [ngModel]="draft.city"
                  (ngModelChange)="setField('city', $event)"
                >
              </label>

              <label class="lead-edit-field">
                <span>Ссылка на чат</span>
                <input
                  name="companyUrlChat"
                  type="text"
                  required
                  [disabled]="saving()"
                  [ngModel]="draft.urlChat"
                  (ngModelChange)="setField('urlChat', $event)"
                >
              </label>

              <label class="lead-edit-field">
                <span>Email</span>
                <input
                  name="companyEmail"
                  type="email"
                  [disabled]="saving()"
                  [ngModel]="draft.email"
                  (ngModelChange)="setField('email', $event)"
                >
              </label>

              <label class="lead-edit-field">
                <span>Категория</span>
                <select
                  name="companyCategory"
                  required
                  [disabled]="saving()"
                  [ngModel]="draft.categoryId"
                  (ngModelChange)="changeCategory($event)"
                >
                  <option [ngValue]="null">Не выбрана</option>
                  @for (category of categoryOptions(); track trackOption($index, category)) {
                    <option [ngValue]="category.id">{{ category.label }}</option>
                  }
                </select>
              </label>

              <label class="lead-edit-field">
                <span>Подкатегория</span>
                <select
                  name="companySubCategory"
                  required
                  [disabled]="saving() || !draft.categoryId"
                  [ngModel]="draft.subCategoryId"
                  (ngModelChange)="setField('subCategoryId', $event)"
                >
                  <option [ngValue]="null">Не выбрана</option>
                  @for (subCategory of subCategoryOptions(); track trackOption($index, subCategory)) {
                    <option [ngValue]="subCategory.id">{{ subCategory.label }}</option>
                  }
                </select>
              </label>

              <label class="lead-edit-field">
                <span>Специалист</span>
                <select
                  name="companyWorker"
                  required
                  [disabled]="saving()"
                  [ngModel]="draft.workerId"
                  (ngModelChange)="setField('workerId', $event)"
                >
                  <option [ngValue]="null">Не выбран</option>
                  @for (worker of workerOptions(); track trackOption($index, worker)) {
                    <option [ngValue]="worker.id">{{ worker.label }}</option>
                  }
                </select>
              </label>

              <label class="lead-edit-field">
                <span>Город филиала</span>
                <select
                  name="companyFilialCity"
                  required
                  [disabled]="saving()"
                  [ngModel]="draft.filialCityId"
                  (ngModelChange)="setField('filialCityId', $event)"
                >
                  <option [ngValue]="null">Не выбран</option>
                  @for (city of cityOptions(); track trackOption($index, city)) {
                    <option [ngValue]="city.id">{{ city.label }}</option>
                  }
                </select>
              </label>

              <label class="lead-edit-field">
                <span>Название филиала</span>
                <input
                  name="companyFilialTitle"
                  type="text"
                  required
                  [disabled]="saving()"
                  [ngModel]="draft.filialTitle"
                  (ngModelChange)="setField('filialTitle', $event)"
                >
              </label>

              <label class="lead-edit-field">
                <span>Ссылка филиала</span>
                <input
                  name="companyFilialUrl"
                  type="text"
                  required
                  [disabled]="saving()"
                  [ngModel]="draft.filialUrl"
                  (ngModelChange)="setField('filialUrl', $event)"
                >
              </label>

              <label class="lead-edit-field lead-edit-field--wide">
                <span>Комментарий</span>
                <textarea
                  name="companyComments"
                  rows="3"
                  [disabled]="saving()"
                  [ngModel]="draft.commentsCompany"
                  (ngModelChange)="setField('commentsCompany', $event)"
                ></textarea>
              </label>
            </div>

            <div class="lead-edit-actions">
              <button type="button" class="secondary" (click)="close()" [disabled]="saving()">Отмена</button>
              <button type="submit" [disabled]="loading() || saving() || companyForm.invalid">
                {{ saving() ? 'Создаю...' : 'Создать компанию' }}
              </button>
            </div>
          </form>
        }
      </section>
    </div>
  `
})
export class CompanyCreateModalComponent implements OnChanges {
  private readonly companyCreateApi = inject(CompanyCreateApi);

  @Input({ required: true }) source!: CompanyCreateSource;
  @Input() leadId: number | null = null;

  @Output() closed = new EventEmitter<void>();
  @Output() created = new EventEmitter<CompanyCreateResult>();

  readonly payload = signal<CompanyCreatePayload | null>(null);
  readonly draft = signal<CompanyCreateDraft | null>(null);
  readonly subCategories = signal<CompanyCreateOption[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.source) {
      return;
    }

    if (changes['source'] || changes['leadId']) {
      this.loadPayload();
    }
  }

  managerOptions(): CompanyCreateOption[] {
    return this.payload()?.managers ?? [];
  }

  workerOptions(): CompanyCreateOption[] {
    return this.payload()?.workers ?? [];
  }

  categoryOptions(): CompanyCreateOption[] {
    return this.payload()?.categories ?? [];
  }

  subCategoryOptions(): CompanyCreateOption[] {
    return this.subCategories();
  }

  cityOptions(): CompanyCreateOption[] {
    return this.payload()?.cities ?? [];
  }

  setField<K extends keyof CompanyCreateDraft>(field: K, value: CompanyCreateDraft[K]): void {
    this.draft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  changeManager(managerId: number | null): void {
    const preserved = this.draft() ? this.preservedFields(this.draft()!) : undefined;
    this.loadPayload(managerId, preserved);
  }

  changeCategory(categoryId: number | null): void {
    this.draft.update((draft) => draft ? { ...draft, categoryId, subCategoryId: null } : draft);
    this.subCategories.set([]);

    if (!categoryId) {
      return;
    }

    this.companyCreateApi.getSubcategories(categoryId).subscribe({
      next: (subCategories) => this.subCategories.set(subCategories),
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить подкатегории');
        this.error.set(message);
      }
    });
  }

  createCompany(formInvalid: boolean | null): void {
    const draft = this.draft();
    if (!draft || formInvalid || this.saving()) {
      return;
    }

    this.saving.set(true);
    this.error.set(null);

    this.companyCreateApi.createCompany(this.requestFromDraft(draft)).subscribe({
      next: (result) => {
        this.saving.set(false);
        this.created.emit(result);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось создать компанию');
        this.error.set(message);
        this.saving.set(false);
      }
    });
  }

  close(): void {
    if (this.saving()) {
      return;
    }

    this.closed.emit();
  }

  trackOption(_index: number, option: CompanyCreateOption): number {
    return option.id;
  }

  private loadPayload(managerId?: number | null, preserved?: PreservedDraftFields): void {
    this.loading.set(true);
    this.error.set(null);

    this.companyCreateApi.getPayload(this.source, this.leadId, managerId).subscribe({
      next: (payload) => {
        this.payload.set(payload);
        this.subCategories.set(payload.subCategories);
        this.draft.set({
          ...this.draftFromPayload(payload, managerId),
          ...preserved
        });
        this.loading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить данные для компании');
        this.error.set(message);
        this.loading.set(false);
      }
    });
  }

  private draftFromPayload(payload: CompanyCreatePayload, managerId?: number | null): CompanyCreateDraft {
    return {
      source: payload.source,
      leadId: payload.leadId ?? this.leadId ?? null,
      managerId: payload.manager?.id ?? managerId ?? null,
      title: payload.title ?? '',
      urlChat: payload.urlChat ?? '',
      telephone: payload.telephone ?? '',
      city: payload.city ?? '',
      email: payload.email ?? '',
      commentsCompany: payload.commentsCompany ?? '',
      categoryId: payload.category?.id ?? null,
      subCategoryId: payload.subCategory?.id ?? null,
      workerId: payload.worker?.id ?? null,
      filialCityId: payload.filialCity?.id ?? null,
      filialTitle: payload.filialTitle ?? '',
      filialUrl: payload.filialUrl ?? ''
    };
  }

  private preservedFields(draft: CompanyCreateDraft): PreservedDraftFields {
    return {
      title: draft.title,
      urlChat: draft.urlChat,
      telephone: draft.telephone,
      city: draft.city,
      email: draft.email,
      commentsCompany: draft.commentsCompany,
      categoryId: draft.categoryId,
      subCategoryId: draft.subCategoryId,
      workerId: draft.workerId,
      filialCityId: draft.filialCityId,
      filialTitle: draft.filialTitle,
      filialUrl: draft.filialUrl
    };
  }

  private requestFromDraft(draft: CompanyCreateDraft): CompanyCreateRequest {
    return {
      source: draft.source,
      leadId: draft.leadId,
      managerId: draft.managerId,
      title: draft.title.trim(),
      urlChat: draft.urlChat.trim(),
      telephone: draft.telephone.trim(),
      city: draft.city.trim(),
      email: draft.email.trim(),
      commentsCompany: draft.commentsCompany.trim(),
      categoryId: draft.categoryId,
      subCategoryId: draft.subCategoryId,
      workerId: draft.workerId,
      filialCityId: draft.filialCityId,
      filialTitle: draft.filialTitle.trim(),
      filialUrl: draft.filialUrl.trim()
    };
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }
}
