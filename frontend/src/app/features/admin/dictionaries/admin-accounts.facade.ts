import { computed, signal, WritableSignal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Observable, Subscription, finalize, tap } from 'rxjs';
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
  /** warning is optional: without it a partial result is reported as an error toast. */
  toast: Pick<ToastService, 'success' | 'error'> & Partial<Pick<ToastService, 'warning'>>;
  isActive: () => boolean;
  selectedId: WritableSignal<number | null>;
  search: () => string;
  /** Called after a page of accounts with its workers, statuses and cities has been applied. */
  afterLoad?: () => void;
};

/** Column sizes of bots.bot_login and bots.bot_fio: a longer value fails in the database with a technical error. */
const BOT_LOGIN_MAX_LENGTH = 45;
const BOT_FIO_MAX_LENGTH = 50;
/** The only status auto-assignment takes; the import gives it to every new account. */
const NEW_BOT_STATUS_TITLE = 'новый';

export class AdminAccountsFacade {
  private readonly fb = new FormBuilder();
  private formRevision = 0;
  private readonly formChanges: Subscription;
  private destroyed = false;
  /** The login of the opened account as the server has it, for the delete question. */
  private selectedBotLogin = '';
  /** Counter, status and activity as the open card received them; they also change in the background. */
  private loadedLifecycle: { counter: number; statusId: number | null; active: boolean } | null = null;
  /** An import finished while the page was hidden: the list has not been read again since. */
  private listStale = false;
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);
  readonly scope: DictionaryViewScope;
  constructor(private readonly deps: Deps) {
    // A save that ends after the tab was left and opened again reads the list again (see DictionaryViewScope).
    this.scope = new DictionaryViewScope(deps.isActive, () => this.load());
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
    this.destroyed = true;
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
  /** A partial result: a warning toast when the page has one, otherwise an error toast. */
  private warn(title: string, message: string): void {
    const toast = this.toastService;
    if (toast.warning) toast.warning(title, message);
    else toast.error(title, message);
  }
  load(): void {
    if (!this.scope.active()) return;
    this.listStale = false;
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
          this.deps.afterLoad?.();
        },
        error: (error) => this.failLoad(error)
      });
    if (this.trackedCityUnblockedAccounts() == null)
      this.loadTrackedCityUnblockedAccountsSnapshot();
  }
  private runSave(
    request: Observable<AdminBot>,
    title: string,
    sentPassword: string,
    sentLifecycle?: { counter: number; statusId: number | null; active: boolean }
  ): void {
    const editor = this.captureEditor();
    this.saving.set(true);
    this.error.set(null);
    this.scope.write(request, this.saving, (outcome) => outcome.failed
      ? this.toastService.error('Аккаунт не сохранен', this.errorMessage(outcome.error, 'Не удалось сохранить аккаунт'))
      : this.toastService.success(title, outcome.value ? `ID ${outcome.value.id}` : 'Аккаунты')
    ).subscribe({
      next: (saved) => {
        if (!this.sameSelection(editor)) {
          // Another account is open by now: its card stays, but the saved one is named so the result is not lost.
          this.toastService.success(title, `ID ${saved.id}`);
          this.trackedCityUnblockedAccounts.set(null);
          return;
        }
        this.deps.selectedId.set(saved.id);
        if (saved.login) this.selectedBotLogin = saved.login;
        // The server may have kept a newer counter, status or activity (a publication, «Блок»): the card shows what is
        // stored, so the next save does not send the old values back as if the user had chosen them. A field changed
        // during the request keeps the user's value and is written by the next save.
        if (sentLifecycle) this.syncSavedLifecycle(saved, sentLifecycle);
        this.loadedLifecycle = { counter: saved.counter ?? 0, statusId: saved.status?.id ?? null, active: saved.active };
        this.syncSavedPassword(saved, sentPassword);
        this.toastService.success(title, `ID ${saved.id}`);
        // A save may move the account into or out of the tracked city: its count is read again with the list.
        this.trackedCityUnblockedAccounts.set(null);
        this.load();
      },
      error: (error) => {
        // The same account is still open: the reason is shown even when a field was changed during the request.
        // Another account opened meanwhile keeps its card clean; the failure is still reported in a toast.
        const message = this.errorMessage(error, 'Не удалось сохранить аккаунт');
        if (this.sameSelection(editor)) this.error.set(message);
        this.toastService.error('Аккаунт не сохранен', message);
      }
    });
  }
  private syncSavedLifecycle(saved: AdminBot, sent: { counter: number; statusId: number | null; active: boolean }): void {
    const controls = this.botForm.controls;
    if (Number(controls.counter.value || 0) === sent.counter) controls.counter.setValue(String(saved.counter ?? 0));
    if (controls.statusId.value === sent.statusId) controls.statusId.setValue(saved.status?.id ?? null);
    if (controls.active.value === sent.active) controls.active.setValue(saved.active);
  }

  /**
   * After a save the password field is empty again, and the «сохранён» mark follows the server. A password typed
   * during the request is kept.
   */
  private syncSavedPassword(saved: AdminBot, sentPassword: string): void {
    const password = this.botForm.controls.password;
    if (sentPassword && password.value === sentPassword) password.setValue('');
    this.selectedBotPasswordPresent.set(typeof saved.passwordPresent === 'boolean'
      ? saved.passwordPresent
      : this.selectedBotPasswordPresent() || sentPassword.trim() !== '');
  }
  deleteSelectedBot(): void {
    const id = this.deps.selectedId();
    if (
      id == null ||
      !this.scope.active() ||
      this.scope.writing() ||
      this.deleting() ||
      this.saving() ||
      // Another row is opening: until it arrives the card and «Удалить» still belong to the previous account.
      this.botDetailLoadingId() != null
    )
      return;
    const editor = this.captureEditor();
    this.deleting.set(true);
    if (!window.confirm(this.deleteBotQuestion(id))
      || !this.scope.active() || !this.sameEditor(editor)) {
      this.deleting.set(false);
      return;
    }
    this.error.set(null);
    this.scope.write(this.dictionariesApi.deleteBot(id), this.deleting, (outcome) => outcome.failed
      ? this.toastService.error('Запись не удалена', this.errorMessage(outcome.error, 'Не удалось удалить запись'))
      : this.toastService.success('Запись удалена', `Аккаунт ID ${id}`)
    ).subscribe({
      next: () => {
        // The account is gone whatever was typed meanwhile; only another opened account keeps its card.
        if (!this.sameSelection(editor)) {
          this.toastService.success('Запись удалена', `Аккаунт ID ${id}`);
          this.trackedCityUnblockedAccounts.set(null);
          this.load();
          return;
        }
        this.clearSelection();
        this.toastService.success('Запись удалена', 'Аккаунты');
        this.trackedCityUnblockedAccounts.set(null);
        this.load();
      },
      error: (error) => {
        const message = this.errorMessage(error, 'Не удалось удалить запись');
        if (this.sameSelection(editor)) this.error.set(message);
        this.toastService.error('Запись не удалена', message);
      }
    });
  }
  /** Names the account and offers the safe alternative: its reviews keep no link to a deleted account. */
  private deleteBotQuestion(id: number): string {
    const login = this.selectedBotLogin || this.botForm.controls.login.value.trim();
    const name = login ? `${login} (ID ${id})` : `ID ${id}`;
    return `Удалить аккаунт ${name}?\n\n`
      + 'Отзывы этого аккаунта останутся без аккаунта. Удаление не отменить. '
      + 'Чтобы аккаунт просто не брался в работу, снимите «Аккаунт активен» и сохраните.';
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

  /** The shared pool city: imported accounts go there by default and its unblocked accounts are counted. */
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
    login: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(BOT_LOGIN_MAX_LENGTH)]),
    password: this.fb.nonNullable.control(''),
    fio: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(BOT_FIO_MAX_LENGTH)]),
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
    this.scope.read('selectBot', this.dictionariesApi.getBot(bot.id)).pipe(
      // A read cancelled by a command (an import) ends without a value: the row must not stay «loading» for good,
      // since saving and deleting wait for it.
      finalize(() => {
        if (requestId === this.botDetailLoadEpoch && this.botDetailLoadingId() === bot.id) {
          this.botDetailLoadingId.set(null);
        }
      })
    ).subscribe({
      next: (details) => {
        if (requestId !== this.botDetailLoadEpoch) {
          return;
        }
        this.selectedId.set(details.id);
        this.selectedBotLogin = details.login;
        this.loadedLifecycle = { counter: details.counter ?? 0, statusId: details.status?.id ?? null, active: details.active };
        this.selectedBotPasswordPresent.set(details.passwordPresent);
        // An empty owner, city or status stays empty: a guessed first option would be saved without notice.
        this.botForm.setValue({
          login: details.login,
          password: '',
          fio: details.fio,
          workerId: details.worker?.id ?? null,
          cityId: details.city?.id ?? null,
          statusId: details.status?.id ?? null,
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
    // An import that finished while the page was hidden has not refreshed the list yet.
    if (this.listStale) this.load();
  }

  selectBotImportFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.importFile.set(input.files?.[0] ?? null);
    // The chosen file is kept in importFile; clearing the field lets the same file be chosen again later.
    input.value = '';
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
      this.importError.set('Выберите файл TXT, CSV, TSV, XLS или XLSX.');
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

    // The result belongs to the open import window, not to the visible page: a hidden browser tab must not swallow
    // it, otherwise the same file looks unsent and is uploaded twice (every row then counts as a duplicate).
    const request = this.dictionariesApi.importBots(file, cityId).pipe(tap({
      next: (result) => this.applyImportResult(result),
      error: (err) => this.applyImportError(err)
    }));
    this.scope.write(request, this.importing).subscribe({
      // Reached only while the page is visible and on this tab; otherwise closing the window reads the list.
      next: () => this.load(),
      error: () => undefined
    });
  }

  private importWindowShown(): boolean {
    return !this.destroyed && this.importModalOpen();
  }

  private applyImportResult(result: BotImportResponse): void {
    if (!this.importWindowShown()) return;
    this.importResult.set(result);
    this.importFile.set(null);
    this.listStale = true;
    // New accounts usually land in the tracked city: its count is read again with the list.
    this.trackedCityUnblockedAccounts.set(null);
    const message = this.importResultMessage(result);
    if (result.added > 0 && !result.skippedInvalid) {
      this.toastService.success('Импорт аккаунтов завершен', message);
    } else {
      this.warn(result.added > 0 ? 'Импорт завершён с ошибками' : 'Аккаунты не добавлены', message);
    }
  }

  private applyImportError(err: unknown): void {
    if (!this.importWindowShown()) return;
    const message = this.errorMessage(err, 'Не удалось импортировать аккаунты');
    this.importError.set(message);
    this.toastService.error('Аккаунты не импортированы', message);
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
    // Publications and «Блок» move the counter, status and activity while the card is open: the server keeps their
    // current values for every field the user left as it was opened.
    if (selectedId != null && this.loadedLifecycle) {
      request.expectedCounter = this.loadedLifecycle.counter;
      request.expectedStatusId = this.loadedLifecycle.statusId;
      request.expectedActive = this.loadedLifecycle.active;
    }
    const call =
      selectedId == null
        ? this.dictionariesApi.createBot(request)
        : this.dictionariesApi.updateBot(selectedId, request);

    this.runSave(call, 'Аккаунт сохранен', raw.password,
      { counter: request.counter, statusId: request.statusId, active: request.active });
  }

  /** No owner is guessed for a new account: the first name of the list is an arbitrary choice. */
  defaultBotWorkerId(): number | null {
    return null;
  }

  /** A new account goes to the shared pool city like an imported one, when that city is listed. */
  defaultBotCityId(): number | null {
    return this.botCities().some((city) => city.id === this.trackedBotCityId) ? this.trackedBotCityId : null;
  }

  /** «Новый», the status auto-assignment takes; without it the status is chosen by hand. */
  defaultBotStatusId(): number | null {
    return this.botStatuses()
      .find((status) => (status.title ?? '').trim().toLowerCase() === NEW_BOT_STATUS_TITLE)?.id ?? null;
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
    this.selectedBotPasswordPresent.set(false); this.error.set(null); this.selectedBotLogin = ''; this.loadedLifecycle = null;
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

  /** Fills the defaults of an untouched new-account form once the lists are known; an opened account keeps its values. */
  private ensureDefaults(): void {
    if (this.botForm.dirty || this.selectedId() != null || this.botDetailLoadingId() != null) return;
    if (this.botForm.controls.statusId.value == null) this.botForm.controls.statusId.setValue(this.defaultBotStatusId());
    if (this.botForm.controls.cityId.value == null) this.botForm.controls.cityId.setValue(this.defaultBotCityId());
  }

}
