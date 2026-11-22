import { computed, signal, WritableSignal } from '@angular/core';
import { FormBuilder, ValidationErrors, Validators } from '@angular/forms';
import type { Subscription } from 'rxjs';

import { apiErrorDetail, apiErrorMessage } from '../../../shared/api-error-message';
import { ToastService } from '../../../shared/toast.service';
import { businessDateIso, businessDateTimeInput } from '../../../shared/business-date';
import {
  DeviceToken,
  OperatorPhone,
  OperatorPhoneRequest,
  OperatorPhonesApi,
  OperatorPhonesResponse,
  PhoneOperatorOption
} from '../../../core/operator-phones.api';
import { DictionaryViewScope } from './dictionary-view-scope';
type Deps = {
  api: OperatorPhonesApi;
  toast: Pick<ToastService, 'success' | 'error'>;
  isActive: () => boolean;
  selectedId: WritableSignal<number | null>;
  search: () => string;
  requestedPhoneId: number;
  /**
   * Whether a phone may stay without an operator. The server allows that only to an administrator and answers
   * «Оператор не найден» to everyone else, so for them the card asks for an operator first. Not given: allowed.
   */
  operatorOptional?: () => boolean;
};

/**
 * The save body. On an edit amountSent and timer are null unless changed in the card: the server then keeps its own
 * values, which every operator send moves (the counter grows; at the limit it drops to 0 and a pause starts).
 */
type PhoneSaveRequest = Omit<OperatorPhoneRequest, 'amountSent'> & { amountSent: number | null };

/** The card fields that the operator's sends change on the server while the card is open. */
type PhoneCounters = { amountSent: number; timer: string };

type PhoneFormValue = ReturnType<AdminPhonesFacade['phoneForm']['getRawValue']>;

/** telephone_number is VARCHAR(20); a real number has at least ten digits, «+7» alone is not one. */
const NUMBER_MAX_LENGTH = 20;
const NUMBER_MIN_DIGITS = 10;
/** telephone_fio and the login columns are VARCHAR(100), telephone_foto_instagram is VARCHAR(255). */
const TEXT_MAX_LENGTH = 100;
const INSTAGRAM_MAX_LENGTH = 255;
/** The server's text for a write blocked by a foreign key: a phone deletion is blocked only by the lead import pool. */
const LINKED_RECORD_ERROR = /связана с рабочими данными/i;

export class AdminPhonesFacade {
  private readonly fb = new FormBuilder();
  private selectionGeneration = 0;
  private formRevision = 0;
  private requestedSelectionPending = true;
  /** The phone a search took out of the editor: it opens again when the new list still has it. */
  private reopenAfterLoad: number | null = null;
  /** Numbers the list reads, so that only the latest one counts. */
  private listRead = 0;
  /** A write cancelled the list read (its data could predate the write): the list is read again once the write ends. */
  private listReadCancelledByWrite = false;
  /** amountSent and timer as the server last sent them for the phone in the editor. */
  private readonly serverCounters: PhoneCounters = { amountSent: 0, timer: '' };
  private readonly formChanges: Subscription;
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);
  readonly scope: DictionaryViewScope;
  constructor(private readonly deps: Deps) {
    // A save that ends after the tab was left and opened again reads the list again (see DictionaryViewScope).
    this.scope = new DictionaryViewScope(deps.isActive, () => this.load());
    const controls = this.phoneForm.controls;
    controls.number.addValidators((control) => this.numberErrors(control.value));
    controls.operatorId.addValidators((control) =>
      control.value == null && !this.operatorOptional() ? { operatorRequired: true } : null);
    // The first problem in words, for the line above the buttons.
    this.phoneForm.addValidators(() => {
      const problem = this.phoneFormProblem();
      return problem ? { problem } : null;
    });
    controls.number.updateValueAndValidity({ emitEvent: false });
    controls.operatorId.updateValueAndValidity({ emitEvent: false });
    this.formChanges = this.phoneForm.valueChanges.subscribe(() => this.formRevision++);
  }
  private get phonesApi() {
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
    this.listReadCancelledByWrite = false;
    this.reopenAfterLoad = null;
  }
  destroy(): void {
    this.scope.destroy();
    this.listReadCancelledByWrite = false;
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
    const editor = this.captureEditor();
    const read = ++this.listRead;
    this.listReadCancelledByWrite = false;
    this.error.set(null);
    let answered = false;
    this.scope
      .read('list', this.phonesApi.getPhones(this.deps.search()), this.loading)
      .subscribe({
        next: (response) => {
          answered = true;
          this.applyPhonesResponse(response, this.sameEditor(editor) && !this.phoneForm.dirty);
        },
        error: (error) => {
          answered = true;
          this.failLoad(error);
        },
        complete: () => {
          // Ended without an answer, so it was cancelled. A write cancels it because its data could predate the
          // write: that read is repeated once the write ends. A newer read or a hidden tab needs nothing here.
          if (!answered && read === this.listRead && this.writePending()) this.listReadCancelledByWrite = true;
        }
      });
  }

  readonly phones = signal<OperatorPhone[]>([]);

  readonly phoneOperators = signal<PhoneOperatorOption[]>([]);

  readonly selectedPhone = signal<OperatorPhone | null>(null);

  readonly phoneForm = this.fb.nonNullable.group({
    number: ['+7'],
    fio: ['', Validators.maxLength(TEXT_MAX_LENGTH)],
    birthday: [''],
    amountAllowed: [1, [Validators.required, Validators.min(0)]],
    amountSent: [0, [Validators.required, Validators.min(0)]],
    blockTime: [3, [Validators.required, Validators.min(0)]],
    timer: [''],
    googleLogin: ['', Validators.maxLength(TEXT_MAX_LENGTH)],
    googlePassword: [''],
    avitoPassword: [''],
    mailLogin: ['', Validators.maxLength(TEXT_MAX_LENGTH)],
    mailPassword: [''],
    fotoInstagram: ['', Validators.maxLength(INSTAGRAM_MAX_LENGTH)],
    active: [true],
    createDate: [''],
    operatorId: this.fb.control<number | null>(null)
  });

  readonly phoneDeviceTokenTotal = computed(() =>
    this.phones().reduce((total, phone) => total + this.deviceTokenCount(phone), 0)
  );

  selectPhone(phone: OperatorPhone): void {
    this.selectionGeneration++;
    this.requestedSelectionPending = false;
    this.reopenAfterLoad = null;
    this.selectedId.set(phone.id);
    this.selectedPhone.set(phone);
    this.error.set(null);
    this.resetPhoneForm({
      number: phone.number ?? '+7',
      fio: phone.fio ?? '',
      birthday: this.toDateInput(phone.birthday),
      amountAllowed: phone.amountAllowed,
      amountSent: phone.amountSent,
      blockTime: phone.blockTime,
      timer: this.toDateTimeInput(phone.timer),
      // Provider credentials are write-only. Blank fields preserve the stored values.
      googleLogin: '',
      googlePassword: '',
      avitoPassword: '',
      mailLogin: '',
      mailPassword: '',
      fotoInstagram: phone.fotoInstagram ?? '',
      active: phone.active,
      createDate: this.toDateInput(phone.createDate),
      operatorId: phone.operator?.id ?? null
    });
  }

  startNewPhone(): void {
    this.selectionGeneration++;
    this.requestedSelectionPending = false;
    this.reopenAfterLoad = null;
    this.selectedId.set(null);
    this.selectedPhone.set(null);
    this.error.set(null);
    this.resetPhoneForm({
      number: '+7',
      fio: '',
      birthday: '',
      amountAllowed: 1,
      amountSent: 0,
      blockTime: 3,
      timer: businessDateTimeInput(),
      googleLogin: '',
      googlePassword: '',
      avitoPassword: '',
      mailLogin: '',
      mailPassword: '',
      fotoInstagram: '',
      active: true,
      createDate: businessDateIso(),
      operatorId: null
    });
  }

  savePhone(): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    if (this.phoneForm.invalid) {
      this.phoneForm.markAllAsTouched();
      return;
    }

    const phone = this.selectedPhone();
    const editor = this.captureEditor();
    const request = this.toPhoneRequest();
    // amountSent: null keeps the server value (see PhoneSaveRequest); the shared request type does not admit null yet.
    const body = request as OperatorPhoneRequest;
    const call = phone
      ? this.phonesApi.updatePhone(phone.id, body)
      : this.phonesApi.createPhone(body);
    const label = phone?.number || request.number;

    this.saving.set(true);
    this.error.set(null);

    this.scope.write(call, this.saving, (outcome) => outcome.failed
      ? this.toastService.error('Телефон не сохранен', `${label}: ${apiErrorDetail(outcome.error)}`)
      : this.toastService.success('Телефон сохранен', outcome.value ? `ID ${outcome.value.id}` : label)
    ).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.patchSavedPhone(saved);
        if (!this.sameSelection(editor)) {
          // Another phone is open by now: its card stays, the saved one is still reported.
          this.toastService.success('Телефон сохранен', `ID ${saved.id}`);
          this.repeatCancelledLoad();
          return;
        }
        if (!this.sameEditor(editor)) {
          // Typing in the same create form must retain the new server ID without resetting the draft.
          this.selectedId.set(saved.id);
          this.selectedPhone.set(saved);
          this.syncCounters(saved);
          this.toastService.success('Телефон сохранен', `ID ${saved.id}`);
          this.repeatCancelledLoad();
          return;
        }
        this.selectPhone(saved);
        this.toastService.success('Телефон сохранен', `ID ${saved.id}`);
        this.load();
      },
      error: (err: unknown) => {
        // Reported even when another phone is open by now: otherwise the failed edit would vanish without a trace.
        this.saving.set(false);
        this.repeatCancelledLoad();
        const detail = apiErrorDetail(err);
        this.error.set(`Телефон ${label} не сохранен. ${detail}`);
        this.toastService.error('Телефон не сохранен', `${label}: ${detail}`);
      }
    });
  }

  deleteSelectedPhone(): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    const phone = this.selectedPhone();
    if (!phone) {
      return;
    }

    const editor = this.captureEditor();
    this.deleting.set(true);
    const confirmed = window.confirm(this.deletePhoneQuestion(phone));
    if (!confirmed || !this.scope.active() || !this.sameEditor(editor)) {
      this.deleting.set(false);
      return;
    }

    this.error.set(null);

    this.scope.write(this.phonesApi.deletePhone(phone.id), this.deleting, (outcome) => outcome.failed
      ? this.toastService.error('Телефон не удален', `${phone.number}: ${this.deleteFailureDetail(outcome.error)}`)
      : this.toastService.success('Телефон удален', phone.number)
    ).subscribe({
      next: () => {
        this.deleting.set(false);
        // The record is gone whatever the editor shows now: a stale row would offer a phone that cannot be saved.
        this.phones.update((phones) => phones.filter((item) => item.id !== phone.id));
        if (this.sameEditor(editor) || this.selectedPhone()?.id === phone.id) {
          this.toastService.success('Телефон удален', phone.number);
          this.startNewPhone();
          this.load();
          return;
        }
        this.repeatCancelledLoad();
      },
      error: (err: unknown) => {
        this.deleting.set(false);
        this.repeatCancelledLoad();
        const detail = this.deleteFailureDetail(err);
        this.error.set(`Телефон ${phone.number} не удален. ${detail}`);
        this.toastService.error('Телефон не удален', `${phone.number}: ${detail}`);
      }
    });
  }

  deleteDeviceToken(phone: OperatorPhone, deviceToken: DeviceToken): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    this.deleting.set(true);
    const confirmed = window.confirm(
      `Отвязать устройство от телефона ${phone.number}?\n`
      + 'Оператор на этом устройстве сразу потеряет доступ. После этого можно привязать новое устройство.'
    );
    if (!confirmed || !this.scope.active()) {
      this.deleting.set(false);
      return;
    }

    this.error.set(null);

    this.scope
      .write(this.phonesApi.deleteDeviceToken(phone.id, deviceToken.token), this.deleting)
      .subscribe({
        next: () => {
          this.deleting.set(false);
          this.removeDeviceToken(phone.id, deviceToken.token);
          this.toastService.success('Устройство отвязано', phone.number);
          this.repeatCancelledLoad();
        },
        error: (err: unknown) => {
          this.deleting.set(false);
          this.repeatCancelledLoad();
          const detail = apiErrorDetail(err);
          this.error.set(`Устройство телефона ${phone.number} не отвязано. ${detail}`);
          this.toastService.error('Устройство не отвязано', `${phone.number}: ${detail}`);
        }
      });
  }

  phoneOperatorName(operator?: PhoneOperatorOption | null): string {
    return operator?.title || '-';
  }

  timerState(phone: OperatorPhone): string {
    return this.isTimerReady(phone) ? 'готов' : 'пауза';
  }

  isTimerReady(phone: OperatorPhone): boolean {
    // The server keeps the pause end as Irkutsk wall time without a zone: compare it with the Irkutsk clock, not the
    // browser's (an administrator in Moscow saw the pause five hours too long).
    return !phone.timer || phone.timer.slice(0, 16) <= businessDateTimeInput();
  }

  deviceTokenCount(phone: OperatorPhone): number {
    return phone.deviceTokens?.length ?? 0;
  }

  tokenPreview(token: string): string {
    return token.length > 18 ? `${token.slice(0, 8)}...${token.slice(-6)}` : token;
  }

  /** Why the card cannot be saved, in words for the line above the buttons; null when it can. */
  phoneFormProblem(): string | null {
    const { number, operatorId, amountAllowed, amountSent, blockTime, fio, googleLogin, mailLogin, fotoInstagram } =
      this.phoneForm.controls;
    if (number.hasError('required')) return 'Укажите номер телефона.';
    if (number.hasError('maxlength')) return `Номер длиннее ${NUMBER_MAX_LENGTH} символов.`;
    if (number.hasError('digits')) return `В номере должно быть не меньше ${NUMBER_MIN_DIGITS} цифр.`;
    if (operatorId.hasError('operatorRequired')) {
      return this.phoneOperators().length
        ? 'Выберите оператора: телефон без оператора может вести только администратор.'
        : 'Нет операторов для выбора: телефон без оператора может вести только администратор.';
    }
    if (amountAllowed.invalid || amountSent.invalid || blockTime.invalid) {
      return 'Заполните «Разрешено отправлять», «Отправлено» и «Блокировка, мин» числами от 0.';
    }
    if (fio.invalid) return `ФИО длиннее ${TEXT_MAX_LENGTH} символов.`;
    if (googleLogin.invalid) return `Google логин длиннее ${TEXT_MAX_LENGTH} символов.`;
    if (mailLogin.invalid) return `Mail логин длиннее ${TEXT_MAX_LENGTH} символов.`;
    if (fotoInstagram.invalid) return `«Фото Instagram» длиннее ${INSTAGRAM_MAX_LENGTH} символов.`;
    return null;
  }

  toPhoneRequest(): PhoneSaveRequest {
    const raw = this.phoneForm.getRawValue();
    const editing = this.selectedPhone() != null;
    return {
      number: raw.number.trim(),
      fio: this.emptyToNull(raw.fio),
      birthday: raw.birthday || null,
      amountAllowed: Number(raw.amountAllowed || 0),
      amountSent: !editing || this.counterChanged('amountSent') ? Number(raw.amountSent || 0) : null,
      blockTime: Number(raw.blockTime || 0),
      // An emptied pause field means «no pause». The server keeps its value for null, so the pause ends now instead.
      timer: !editing
        ? raw.timer || null
        : this.counterChanged('timer') ? raw.timer || businessDateTimeInput() : null,
      googleLogin: this.emptyToNull(raw.googleLogin),
      googlePassword: this.emptyToNull(raw.googlePassword),
      avitoPassword: this.emptyToNull(raw.avitoPassword),
      mailLogin: this.emptyToNull(raw.mailLogin),
      mailPassword: this.emptyToNull(raw.mailPassword),
      fotoInstagram: this.emptyToNull(raw.fotoInstagram),
      active: raw.active,
      createDate: raw.createDate || null,
      operatorId: raw.operatorId
    };
  }

  toDateInput(value?: string | null): string {
    return value ? value.slice(0, 10) : '';
  }

  toDateTimeInput(value?: string | null): string {
    return value ? value.slice(0, 16) : '';
  }

  emptyToNull(value: string): string | null {
    const trimmed = value.trim();
    return trimmed ? trimmed : null;
  }

  patchSavedPhone(phone: OperatorPhone): void {
    this.phones.update((phones) => {
      const exists = phones.some((item) => item.id === phone.id);
      return exists
        ? phones.map((item) => (item.id === phone.id ? phone : item))
        : [phone, ...phones];
    });
  }

  removeDeviceToken(phoneId: number, token: string): void {
    const removeFromPhone = (phone: OperatorPhone): OperatorPhone => ({
      ...phone,
      deviceTokens: (phone.deviceTokens ?? []).filter((item) => item.token !== token)
    });

    this.phones.update((phones) =>
      phones.map((phone) => (phone.id === phoneId ? removeFromPhone(phone) : phone))
    );

    const selectedPhone = this.selectedPhone();
    if (selectedPhone?.id === phoneId) {
      const updatedPhone = removeFromPhone(selectedPhone);
      this.selectedPhone.set(updatedPhone);
    }
  }

  applyPhonesResponse(response: OperatorPhonesResponse, restoreSelection = !this.phoneForm.dirty): void {
    this.phones.set(response.phones);
    this.phoneOperators.set(response.operators);
    const reopenId = this.reopenAfterLoad;
    this.reopenAfterLoad = null;
    if (restoreSelection) this.restorePhoneSelection(response.phones, reopenId);
    else this.refreshCounters(response.phones);
    // The operator line depends on whether there is anyone to choose.
    this.phoneForm.updateValueAndValidity({ emitEvent: false });
  }

  restorePhoneSelection(phones: OperatorPhone[], reopenId: number | null = null): void {
    const requestedId = this.requestedSelectionPending ? this.positiveId(this.deps.requestedPhoneId) : null;
    const selectedId = this.selectedPhone()?.id || reopenId || requestedId;
    const nextSelected = selectedId ? phones.find((phone) => phone.id === selectedId) : null;
    // A list refresh reopens the card but must not dismiss an error the user may not have read yet.
    const error = this.error();

    if (nextSelected) {
      this.selectPhone(nextSelected);
      this.error.set(error);
      return;
    }

    if (!this.selectedPhone()) {
      if (requestedId != null && selectedId === requestedId) {
        // A link such as the operator board's (?phoneId=…) to a phone this list lacks: deleted or hidden from this role.
        this.toastService.error('Телефон не найден',
          `Телефон #${requestedId} не найден или недоступен. Открыта карточка нового телефона.`);
      }
      this.startNewPhone();
      this.error.set(error);
    }
  }

  private captureEditor() {
    return { generation: this.selectionGeneration, revision: this.formRevision,
      id: this.selectedId(), phoneId: this.selectedPhone()?.id ?? null };
  }

  private sameEditor(editor: ReturnType<AdminPhonesFacade['captureEditor']>): boolean {
    return this.sameSelection(editor) && editor.revision === this.formRevision;
  }

  private sameSelection(editor: ReturnType<AdminPhonesFacade['captureEditor']>): boolean {
    return editor.generation === this.selectionGeneration && editor.id === this.selectedId()
      && editor.phoneId === (this.selectedPhone()?.id ?? null);
  }
  clearSelection(): void {
    // A search on this tab clears the card before the new list arrives; the phone opens again if the list still has it.
    const reopenId = this.scope.active() ? this.selectedPhone()?.id ?? null : null;
    this.selectionGeneration++; this.requestedSelectionPending = false;
    this.selectedId.set(null); this.error.set(null);
    this.selectedPhone.set(null);
    this.resetPhoneForm({
      number: '+7',
      fio: '',
      birthday: '',
      amountAllowed: 1,
      amountSent: 0,
      blockTime: 3,
      timer: '',
      googleLogin: '',
      googlePassword: '',
      avitoPassword: '',
      mailLogin: '',
      mailPassword: '',
      fotoInstagram: '',
      active: true,
      createDate: '',
      operatorId: null
    });
    this.reopenAfterLoad = reopenId;
  }

  private resetPhoneForm(value: PhoneFormValue): void {
    this.serverCounters.amountSent = value.amountSent;
    this.serverCounters.timer = value.timer;
    this.phoneForm.reset(value);
  }

  /** Changed in the card since the server value arrived: only then does an edit send it. */
  private counterChanged(name: keyof PhoneCounters): boolean {
    const control = this.phoneForm.controls[name];
    return control.dirty || control.value !== this.serverCounters[name];
  }

  /** The server now holds saved's counters: a field showing them is no longer an edit to send again. */
  private syncCounters(saved: OperatorPhone): void {
    const { amountSent, timer } = this.phoneForm.controls;
    const freshTimer = this.toDateTimeInput(saved.timer);
    // Fields the user left alone show what the server holds now: the operator's sends may have moved them meanwhile.
    if (!this.counterChanged('amountSent')) amountSent.setValue(saved.amountSent, { emitEvent: false });
    if (!this.counterChanged('timer')) timer.setValue(freshTimer, { emitEvent: false });
    this.serverCounters.amountSent = saved.amountSent;
    this.serverCounters.timer = freshTimer;
    if (amountSent.value === this.serverCounters.amountSent) amountSent.markAsPristine();
    if (timer.value === this.serverCounters.timer) timer.markAsPristine();
  }

  /**
   * A reload keeps a card with typed edits, but the operator's sends keep moving its counter and pause: the fields the
   * user has not changed take the fresh server values.
   */
  private refreshCounters(phones: OperatorPhone[]): void {
    const selected = this.selectedPhone();
    const fresh = selected ? phones.find((phone) => phone.id === selected.id) : undefined;
    if (!fresh) return;
    const { amountSent, timer } = this.phoneForm.controls;
    if (!this.counterChanged('amountSent')) {
      this.serverCounters.amountSent = fresh.amountSent;
      amountSent.setValue(fresh.amountSent, { emitEvent: false });
    }
    if (!this.counterChanged('timer')) {
      this.serverCounters.timer = this.toDateTimeInput(fresh.timer);
      timer.setValue(this.serverCounters.timer, { emitEvent: false });
    }
  }

  private numberErrors(value: string | null): ValidationErrors | null {
    const number = (value ?? '').trim();
    if (!number) return { required: true };
    if (number.length > NUMBER_MAX_LENGTH) {
      return { maxlength: { requiredLength: NUMBER_MAX_LENGTH, actualLength: number.length } };
    }
    // A stored number was accepted before: its card must stay editable even when the number is shorter.
    if (number === this.selectedPhone()?.number?.trim()) return null;
    return number.replace(/\D/g, '').length < NUMBER_MIN_DIGITS ? { digits: { requiredDigits: NUMBER_MIN_DIGITS } } : null;
  }

  private operatorOptional(): boolean {
    return this.deps.operatorOptional?.() ?? true;
  }

  private positiveId(value: number): number | null {
    return Number.isInteger(value) && value > 0 ? value : null;
  }

  private writePending(): boolean {
    return this.saving() || this.deleting();
  }

  /** Repeats the list read that a write cancelled. */
  private repeatCancelledLoad(): void {
    if (!this.listReadCancelledByWrite) return;
    this.listReadCancelledByWrite = false;
    this.load();
  }

  private deletePhoneQuestion(phone: OperatorPhone): string {
    return [
      `Удалить телефон ${phone.number}?`,
      'Лиды этого номера останутся без телефона, привязанное устройство потеряет доступ. Удаление не отменить.',
      'Чтобы убрать номер из работы на время, снимите «Телефон активен».'
    ].join('\n');
  }

  private deleteFailureDetail(err: unknown): string {
    const detail = apiErrorDetail(err);
    return LINKED_RECORD_ERROR.test(detail)
      ? 'Номер входит в пул импорта лидов менеджера, поэтому удалить его нельзя. '
        + 'Чтобы убрать номер из работы, снимите «Телефон активен» и сохраните.'
      : detail;
  }

}
