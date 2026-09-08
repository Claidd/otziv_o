import { computed, signal, WritableSignal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Observable, Subscription } from 'rxjs';
import {
  AdminBot,
  BotImportResponse,
  BotRequest,
  BotsResponse,
  DictionaryOption
} from '../../../core/admin-dictionaries.api';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { ToastService } from '../../../shared/toast.service';
import { DictionaryViewScope } from './dictionary-view-scope';
import { AdminAccountsApi } from '../../../core/admin-accounts.api';
type BotImportMode = 'file' | 'city';
type Deps = {
  api: AdminAccountsApi;
  toast: Pick<ToastService, 'success' | 'error'>;
  isActive: () => boolean;
  selectedId: WritableSignal<number | null>;
  search: () => string;
};

export class AdminAccountsFacade {
  private readonly fb = new FormBuilder();
  private formRevision = 0;
  private readonly formChanges: Subscription;
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);
  readonly scope: DictionaryViewScope;
  constructor(private readonly deps: Deps) {
    this.scope = new DictionaryViewScope(deps.isActive);
    this.formChanges = this.botForm.valueChanges.subscribe(() => this.formRevision++);
  }
  private get dictionariesApi() {
    return this.deps.api;
  }
  private get toastService() {
    return this.deps.toast;
  }
  private get selectedId() {
    return this.deps.selectedId;
  }
  deactivate(): void {
    this.scope.deactivate();
    this.cancelDetail();
  }
  destroy(): void {
    this.scope.destroy();
    this.formChanges.unsubscribe();
  }
  private errorMessage(error: unknown, fallback: string): string {
    return apiErrorMessage(error, fallback);
  }
  private failLoad(error: unknown): void {
    const message = this.errorMessage(error, 'Не удалось загрузить справочник');
    this.error.set(message);
    this.toastService.error('Справочник не загрузился', message);
  }
  load(): void {
    if (!this.scope.active()) return;
    this.error.set(null);
    this.scope
      .read(
        'list',
        this.dictionariesApi.getBots(this.deps.search(), this.botPage(), this.botPageSize()),
        this.loading
      )
      .subscribe({
        next: (payload) => {
          const pages = Math.max(1, payload.totalPages);
          if (payload.total > 0 && payload.page >= pages) {
            this.botPage.set(pages - 1);
            this.load();
            return;
          }
          this.applyBotsResponse(payload);
          this.ensureDefaults();
        },
        error: (error) => this.failLoad(error)
      });
    if (this.trackedCityUnblockedAccounts() == null)
      this.loadTrackedCityUnblockedAccountsSnapshot();
  }
  private runSave(request: Observable<AdminBot>, title: string): void {
    const editor = this.captureEditor();
    this.saving.set(true);
    this.error.set(null);
    this.scope.write(request, this.saving).subscribe({
      next: (saved) => {
        if (!this.sameSelection(editor)) return;
        this.deps.selectedId.set(saved.id);
        this.toastService.success(title, `ID ${saved.id}`);
        this.load();
      },
      error: (error) => {
        if (!this.sameEditor(editor)) return;
        const message = this.errorMessage(error, 'Не удалось сохранить аккаунт');
        this.error.set(message);
        this.toastService.error('Аккаунт не сохранен', message);
      }
    });
  }
  deleteSelectedBot(): void {
    const id = this.deps.selectedId();
    if (
      id == null ||
      !this.scope.active() ||
      this.scope.writing() ||
      this.deleting() ||
      this.saving()
    )
      return;
    const editor = this.captureEditor();
    this.deleting.set(true);
    if (!window.confirm('Удалить запись из справочника "Аккаунты"?')
      || !this.scope.active() || !this.sameEditor(editor)) {
      this.deleting.set(false);
      return;
    }
    this.error.set(null);
    this.scope.write(this.dictionariesApi.deleteBot(id), this.deleting).subscribe({
      next: () => {
        if (!this.sameEditor(editor)) return;
        this.clearSelection();
        this.toastService.success('Запись удалена', 'Аккаунты');
        this.load();
      },
      error: (error) => {
        if (!this.sameEditor(editor)) return;
        const message = this.errorMessage(error, 'Не удалось удалить запись');
        this.error.set(message);
        this.toastService.error('Запись не удалена', message);
      }
    });
  }
  cancelDetail(): void {
    this.scope.cancel('selectBot');
    this.botDetailLoadEpoch++;
    this.botDetailLoadingId.set(null);
  }
  private captureEditor() {
    return { id: this.selectedId(), generation: this.botDetailLoadEpoch, revision: this.formRevision };
  }
  private sameEditor(editor: ReturnType<AdminAccountsFacade['captureEditor']>): boolean {
    return this.sameSelection(editor) && editor.revision === this.formRevision;
  }
  private sameSelection(editor: ReturnType<AdminAccountsFacade['captureEditor']>): boolean {
    return editor.id === this.selectedId() && editor.generation === this.botDetailLoadEpoch;
  }
  private botDetailLoadEpoch = 0;

  readonly bots = signal<AdminBot[]>([]);

  readonly botDetailLoadingId = signal<number | null>(null);

  readonly selectedBotPasswordPresent = signal(false);

  readonly botWorkers = signal<DictionaryOption[]>([]);

  readonly botStatuses = signal<DictionaryOption[]>([]);

  readonly botCities = signal<DictionaryOption[]>([]);

  readonly botPage = signal(0);

  readonly botPageSize = signal(50);

  readonly botsTotal = signal(0);

  readonly trackedBotCityId = 325;

  readonly trackedCityUnblockedAccounts = signal<number | null>(null);

  readonly botPageSizeOptions = [50, 100, 200];

  readonly botTotalPages = computed(() =>
    Math.max(1, Math.ceil(this.botsTotal() / this.botPageSize()))
  );

  readonly botPageStart = computed(() =>
    this.botsTotal() === 0 ? 0 : this.botPage() * this.botPageSize() + 1
  );

  readonly botPageEnd = computed(() =>
    Math.min(this.botsTotal(), (this.botPage() + 1) * this.botPageSize())
  );

  readonly botForm = this.fb.group({
    login: this.fb.nonNullable.control('', Validators.required),
    password: this.fb.nonNullable.control(''),
    fio: this.fb.nonNullable.control('', Validators.required),
    workerId: this.fb.control<number | null>(null, Validators.required),
    cityId: this.fb.control<number | null>(null),
    statusId: this.fb.control<number | null>(null, Validators.required),
    counter: this.fb.nonNullable.control('0', Validators.required),
    active: this.fb.nonNullable.control(true)
  });

  readonly importing = signal(false);

  readonly importError = signal<string | null>(null);

  readonly importResult = signal<BotImportResponse | null>(null);

  readonly importModalOpen = signal(false);

  readonly importFile = signal<File | null>(null);

  readonly importMode = signal<BotImportMode>('file');

  readonly importCitySearch = signal('');

  readonly importCityId = signal<number | null>(null);

  readonly selectedImportCity = computed(() => {
    const cityId = this.importCityId();
    return cityId == null ? null : (this.botCities().find((city) => city.id === cityId) ?? null);
  });

  readonly filteredImportCities = computed(() => {
    const search = this.importCitySearch().trim().toLowerCase();
    const cities = this.botCities();
    if (!search) {
      return cities.slice(0, 12);
    }

    return cities
      .filter(
        (city) => city.title.toLowerCase().includes(search) || String(city.id).includes(search)
      )
      .slice(0, 12);
  });

  readonly canUploadBotImport = computed(
    () =>
      !!this.importFile() &&
      !this.importing() &&
      (this.importMode() !== 'city' || this.importCityId() != null)
  );

  selectBot(bot: AdminBot): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;
    const requestId = ++this.botDetailLoadEpoch;
    this.botDetailLoadingId.set(bot.id);
    this.error.set(null);
    this.scope.read('selectBot', this.dictionariesApi.getBot(bot.id)).subscribe({
      next: (details) => {
        if (requestId !== this.botDetailLoadEpoch) {
          return;
        }
        this.selectedId.set(details.id);
        this.selectedBotPasswordPresent.set(details.passwordPresent);
        this.botForm.setValue({
          login: details.login,
          password: '',
          fio: details.fio,
          workerId: details.worker?.id ?? this.defaultBotWorkerId(),
          cityId: details.city?.id ?? this.defaultBotCityId(),
          statusId: details.status?.id ?? this.defaultBotStatusId(),
          counter: String(details.counter ?? 0),
          active: details.active
        });
        this.botDetailLoadingId.set(null);
      },
      error: (err) => {
        if (requestId !== this.botDetailLoadEpoch) {
          return;
        }
        const message = this.errorMessage(err, 'Не удалось загрузить данные аккаунта');
        this.botDetailLoadingId.set(null);
        this.error.set(message);
        this.toastService.error('Аккаунт не загрузился', message);
      }
    });
  }

  botBrowserUrl(bot: AdminBot): string {
    return `/admin/dictionaries/accounts/${bot.id}/browser`;
  }

  openBotImport(mode: BotImportMode = 'file'): void {
    this.importMode.set(mode);
    this.importModalOpen.set(true);
    this.importFile.set(null);
    this.importResult.set(null);
    this.importError.set(null);
    this.importCitySearch.set('');
    this.importCityId.set(mode === 'city' ? null : this.defaultBotCityId());
  }

  closeBotImport(): void {
    if (this.importing()) {
      return;
    }

    this.importModalOpen.set(false);
    this.importFile.set(null);
    this.importError.set(null);
    this.importCitySearch.set('');
    this.importCityId.set(null);
  }

  selectBotImportFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.importFile.set(input.files?.[0] ?? null);
    this.importResult.set(null);
    this.importError.set(null);
  }

  selectBotImportCity(city: DictionaryOption): void {
    this.importCityId.set(city.id);
    this.importCitySearch.set(city.title);
    this.importResult.set(null);
    this.importError.set(null);
  }

  uploadBotImport(): void {
    if (
      !this.scope.active() ||
      this.scope.writing() ||
      this.saving() ||
      this.deleting() ||
      this.importing()
    )
      return;

    const file = this.importFile();
    if (!file || this.importing()) {
      this.importError.set('Выберите CSV или Excel-файл.');
      return;
    }

    const cityId = this.importMode() === 'city' ? this.importCityId() : null;
    if (this.importMode() === 'city' && cityId == null) {
      this.importError.set('Выберите город для привязки аккаунтов.');
      return;
    }

    this.importing.set(true);
    this.importError.set(null);
    this.importResult.set(null);

    this.scope.write(this.dictionariesApi.importBots(file, cityId), this.importing).subscribe({
      next: (result) => {
        this.importing.set(false);
        this.importResult.set(result);
        this.importFile.set(null);
        this.toastService.success('Импорт аккаунтов завершен', this.importResultMessage(result));
        this.load();
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
    const duplicateText = result.skippedDuplicates
      ? `, дублей пропущено: ${result.skippedDuplicates}`
      : '';
    const invalidText = result.skippedInvalid ? `, строк с ошибками: ${result.skippedInvalid}` : '';
    return `Добавлено: ${result.added}${duplicateText}${invalidText}`;
  }

  saveBot(): void {
    if (
      !this.scope.active() ||
      this.scope.writing() ||
      this.saving() ||
      this.deleting() ||
      this.importing() || this.botDetailLoadingId() != null
    )
      return;

    if (this.selectedId() == null && !this.botForm.controls.password.value.trim()) {
      this.botForm.controls.password.setErrors({ required: true });
    }
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
    const call =
      selectedId == null
        ? this.dictionariesApi.createBot(request)
        : this.dictionariesApi.updateBot(selectedId, request);

    this.runSave(call, 'Аккаунт сохранен');
  }

  defaultBotWorkerId(): number | null {
    return this.botWorkers()[0]?.id ?? null;
  }

  defaultBotCityId(): number | null {
    return this.botCities()[0]?.id ?? null;
  }

  defaultBotStatusId(): number | null {
    return this.botStatuses()[0]?.id ?? null;
  }

  applyBotsResponse(response: BotsResponse): void {
    this.bots.set(response.bots);
    this.botWorkers.set(response.workers);
    this.botStatuses.set(response.statuses);
    this.botCities.set(response.cities);
    this.botsTotal.set(response.total);
    this.botPage.set(response.page);
    this.botPageSize.set(response.size);
  }

  loadTrackedCityUnblockedAccountsSnapshot(): void {
    this.scope
      .read(
        'loadTrackedCityUnblockedAccountsSnapshot',
        this.dictionariesApi.getBotCityUnblockedCount(this.trackedBotCityId)
      )
      .subscribe({
        next: (response) => this.trackedCityUnblockedAccounts.set(response.unblockedAccounts),
        error: () => this.trackedCityUnblockedAccounts.set(null)
      });
  }
  clearSelection(): void {
    this.cancelDetail(); this.selectedId.set(null); this.botDetailLoadingId.set(null);
    this.selectedBotPasswordPresent.set(false); this.error.set(null);
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
  }

  private ensureDefaults(): void {
    if (this.botForm.dirty) return;
    if (this.botForm.controls.workerId.value == null) this.botForm.controls.workerId.setValue(this.defaultBotWorkerId());
    if (this.botForm.controls.statusId.value == null) this.botForm.controls.statusId.setValue(this.defaultBotStatusId());
    if (this.botForm.controls.cityId.value == null) this.botForm.controls.cityId.setValue(this.defaultBotCityId());
  }

}
