import { computed, signal, WritableSignal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import type { Subscription } from 'rxjs';

import { apiErrorMessage } from '../../../shared/api-error-message';
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
};

export class AdminPhonesFacade {
  private readonly fb = new FormBuilder();
  private selectionGeneration = 0;
  private formRevision = 0;
  private requestedSelectionPending = true;
  private readonly formChanges: Subscription;
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);
  readonly scope: DictionaryViewScope;
  constructor(private readonly deps: Deps) {
    this.scope = new DictionaryViewScope(deps.isActive);
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
    const editor = this.captureEditor();
    this.error.set(null);
    this.scope
      .read('list', this.phonesApi.getPhones(this.deps.search()), this.loading)
      .subscribe({
        next: (response) => this.applyPhonesResponse(response, this.sameEditor(editor) && !this.phoneForm.dirty),
        error: (error) => this.failLoad(error)
      });
  }

  readonly phones = signal<OperatorPhone[]>([]);

  readonly phoneOperators = signal<PhoneOperatorOption[]>([]);

  readonly selectedPhone = signal<OperatorPhone | null>(null);

  readonly phoneForm = this.fb.nonNullable.group({
    number: ['+7', Validators.required],
    fio: [''],
    birthday: [''],
    amountAllowed: [1, Validators.required],
    amountSent: [0, Validators.required],
    blockTime: [3, Validators.required],
    timer: [''],
    googleLogin: [''],
    googlePassword: [''],
    avitoPassword: [''],
    mailLogin: [''],
    mailPassword: [''],
    fotoInstagram: [''],
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
    this.selectedId.set(phone.id);
    this.selectedPhone.set(phone);
    this.error.set(null);
    this.phoneForm.reset({
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
    this.selectedId.set(null);
    this.selectedPhone.set(null);
    this.error.set(null);
    this.phoneForm.reset({
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
    const call = phone
      ? this.phonesApi.updatePhone(phone.id, request)
      : this.phonesApi.createPhone(request);

    this.saving.set(true);
    this.error.set(null);

    this.scope.write(call, this.saving).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.patchSavedPhone(saved);
        if (!this.sameSelection(editor)) return;
        if (!this.sameEditor(editor)) {
          // Typing in the same create form must retain the new server ID without resetting the draft.
          this.selectedId.set(saved.id);
          this.selectedPhone.set(saved);
          this.toastService.success('Телефон сохранен', `ID ${saved.id}`);
          return;
        }
        this.selectPhone(saved);
        this.toastService.success('Телефон сохранен', `ID ${saved.id}`);
        this.load();
      },
      error: (err: unknown) => {
        if (!this.sameEditor(editor)) return;
        const message = this.errorMessage(err, 'Не удалось сохранить телефон');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Телефон не сохранен', message);
      }
    });
  }

  deleteSelectedPhone(): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    const phone = this.selectedPhone();
    if (!phone || this.deleting()) {
      return;
    }

    const editor = this.captureEditor();
    this.deleting.set(true);
    const confirmed = window.confirm(`Удалить телефон ${phone.number}?`);
    if (!confirmed || !this.scope.active() || !this.sameEditor(editor)) {
      this.deleting.set(false);
      return;
    }

    this.deleting.set(true);
    this.error.set(null);

    this.scope.write(this.phonesApi.deletePhone(phone.id), this.deleting).subscribe({
      next: () => {
        this.deleting.set(false);
        if (!this.sameEditor(editor)) return;
        this.toastService.success('Телефон удален', phone.number);
        this.startNewPhone();
        this.load();
      },
      error: (err: unknown) => {
        if (!this.sameEditor(editor)) return;
        const message = this.errorMessage(err, 'Не удалось удалить телефон');
        this.error.set(message);
        this.deleting.set(false);
        this.toastService.error('Телефон не удален', message);
      }
    });
  }

  deleteDeviceToken(phone: OperatorPhone, deviceToken: DeviceToken): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    if (this.deleting()) {
      return;
    }

    this.deleting.set(true);
    const confirmed = window.confirm(`Удалить токен устройства телефона ${phone.number}?`);
    if (!confirmed || !this.scope.active()) {
      this.deleting.set(false);
      return;
    }

    this.deleting.set(true);
    this.error.set(null);

    this.scope
      .write(this.phonesApi.deleteDeviceToken(phone.id, deviceToken.token), this.deleting)
      .subscribe({
        next: () => {
          this.deleting.set(false);
          this.removeDeviceToken(phone.id, deviceToken.token);
          this.toastService.success('Токен удален', phone.number);
        },
        error: (err: unknown) => {
          const message = this.errorMessage(err, 'Не удалось удалить токен устройства');
          this.error.set(message);
          this.deleting.set(false);
          this.toastService.error('Токен не удален', message);
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
    return !phone.timer || new Date(phone.timer).getTime() <= Date.now();
  }

  deviceTokenCount(phone: OperatorPhone): number {
    return phone.deviceTokens?.length ?? 0;
  }

  tokenPreview(token: string): string {
    return token.length > 18 ? `${token.slice(0, 8)}...${token.slice(-6)}` : token;
  }

  toPhoneRequest(): OperatorPhoneRequest {
    const raw = this.phoneForm.getRawValue();
    return {
      number: raw.number.trim(),
      fio: this.emptyToNull(raw.fio),
      birthday: raw.birthday || null,
      amountAllowed: Number(raw.amountAllowed || 0),
      amountSent: Number(raw.amountSent || 0),
      blockTime: Number(raw.blockTime || 0),
      timer: raw.timer || null,
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
    if (restoreSelection) this.restorePhoneSelection(response.phones);
  }

  restorePhoneSelection(phones: OperatorPhone[]): void {
    const selectedId =
      this.selectedPhone()?.id ||
      (this.requestedSelectionPending && Number.isFinite(this.deps.requestedPhoneId) ? this.deps.requestedPhoneId : null);
    const nextSelected = selectedId ? phones.find((phone) => phone.id === selectedId) : null;

    if (nextSelected) {
      this.selectPhone(nextSelected);
      return;
    }

    if (!this.selectedPhone()) {
      this.startNewPhone();
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
    this.selectionGeneration++; this.requestedSelectionPending = false;
    this.selectedId.set(null); this.error.set(null);
    this.selectedPhone.set(null);
    this.phoneForm.reset({
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
  }

}
