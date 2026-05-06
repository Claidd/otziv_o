import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  OperatorPhone,
  OperatorPhoneRequest,
  OperatorPhonesApi,
  PhoneOperatorOption
} from '../../core/operator-phones.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { ToastService } from '../../shared/toast.service';

type PhoneMetric = {
  label: string;
  value: number;
  icon: string;
  tone: 'blue' | 'green' | 'yellow' | 'pink';
};

@Component({
  selector: 'app-operator-phones',
  imports: [AdminLayoutComponent, DatePipe, LoadErrorCardComponent, ReactiveFormsModule, RouterLink],
  templateUrl: './operator-phones.component.html',
  styleUrl: './operator-phones.component.scss'
})
export class OperatorPhonesComponent {
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly phonesApi = inject(OperatorPhonesApi);
  private readonly toastService = inject(ToastService);
  private readonly requestedPhoneId = Number(this.route.snapshot.queryParamMap.get('phoneId'));

  readonly phones = signal<OperatorPhone[]>([]);
  readonly operators = signal<PhoneOperatorOption[]>([]);
  readonly selectedPhone = signal<OperatorPhone | null>(null);
  readonly search = signal('');
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);

  readonly activePhones = computed(() => this.phones().filter((phone) => phone.active).length);
  readonly pausedPhones = computed(() => this.phones().filter((phone) => !this.isTimerReady(phone)).length);
  readonly metrics = computed<PhoneMetric[]>(() => [
    { label: 'Всего', value: this.phones().length, icon: 'phone_iphone', tone: 'blue' },
    { label: 'Активные', value: this.activePhones(), icon: 'task_alt', tone: 'green' },
    { label: 'На паузе', value: this.pausedPhones(), icon: 'timer', tone: 'yellow' },
    { label: 'Операторы', value: this.operators().length, icon: 'badge', tone: 'pink' }
  ]);

  readonly form = this.fb.nonNullable.group({
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

  constructor() {
    this.loadPhones();
  }

  loadPhones(): void {
    this.loading.set(true);
    this.error.set(null);

    this.phonesApi.getPhones(this.search()).subscribe({
      next: (response) => {
        this.phones.set(response.phones);
        this.operators.set(response.operators);
        this.loading.set(false);
        this.restoreSelection(response.phones);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить телефоны');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Телефоны не загрузились', message);
      }
    });
  }

  searchPhones(): void {
    this.loadPhones();
  }

  clearSearch(): void {
    this.search.set('');
    this.loadPhones();
  }

  selectPhone(phone: OperatorPhone): void {
    this.selectedPhone.set(phone);
    this.error.set(null);
    this.form.reset({
      number: phone.number ?? '+7',
      fio: phone.fio ?? '',
      birthday: this.toDateInput(phone.birthday),
      amountAllowed: phone.amountAllowed,
      amountSent: phone.amountSent,
      blockTime: phone.blockTime,
      timer: this.toDateTimeInput(phone.timer),
      googleLogin: phone.googleLogin ?? '',
      googlePassword: phone.googlePassword ?? '',
      avitoPassword: phone.avitoPassword ?? '',
      mailLogin: phone.mailLogin ?? '',
      mailPassword: phone.mailPassword ?? '',
      fotoInstagram: phone.fotoInstagram ?? '',
      active: phone.active,
      createDate: this.toDateInput(phone.createDate),
      operatorId: phone.operator?.id ?? null
    });
  }

  startNewPhone(): void {
    this.selectedPhone.set(null);
    this.error.set(null);
    this.form.reset({
      number: '+7',
      fio: '',
      birthday: '',
      amountAllowed: 1,
      amountSent: 0,
      blockTime: 3,
      timer: this.toDateTimeInput(new Date().toISOString()),
      googleLogin: '',
      googlePassword: '',
      avitoPassword: '',
      mailLogin: '',
      mailPassword: '',
      fotoInstagram: '',
      active: true,
      createDate: this.toDateInput(new Date().toISOString()),
      operatorId: null
    });
  }

  savePhone(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const phone = this.selectedPhone();
    const request = this.toRequest();
    const call = phone
      ? this.phonesApi.updatePhone(phone.id, request)
      : this.phonesApi.createPhone(request);

    this.saving.set(true);
    this.error.set(null);

    call.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.selectedPhone.set(saved);
        this.patchSaved(saved);
        this.toastService.success('Телефон сохранен', `ID ${saved.id}`);
        this.loadPhones();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить телефон');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Телефон не сохранен', message);
      }
    });
  }

  deleteSelectedPhone(): void {
    const phone = this.selectedPhone();
    if (!phone || this.deleting()) {
      return;
    }

    const confirmed = window.confirm(`Удалить телефон ${phone.number}?`);
    if (!confirmed) {
      return;
    }

    this.deleting.set(true);
    this.error.set(null);

    this.phonesApi.deletePhone(phone.id).subscribe({
      next: () => {
        this.deleting.set(false);
        this.selectedPhone.set(null);
        this.toastService.success('Телефон удален', phone.number);
        this.startNewPhone();
        this.loadPhones();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить телефон');
        this.error.set(message);
        this.deleting.set(false);
        this.toastService.error('Телефон не удален', message);
      }
    });
  }

  operatorName(operator?: PhoneOperatorOption | null): string {
    return operator?.title || '-';
  }

  timerState(phone: OperatorPhone): string {
    return this.isTimerReady(phone) ? 'готов' : 'пауза';
  }

  isTimerReady(phone: OperatorPhone): boolean {
    return !phone.timer || new Date(phone.timer).getTime() <= Date.now();
  }

  trackPhone(_index: number, phone: OperatorPhone): number {
    return phone.id;
  }

  trackOperator(_index: number, operator: PhoneOperatorOption): number {
    return operator.id;
  }

  trackMetric(_index: number, metric: PhoneMetric): string {
    return metric.label;
  }

  private restoreSelection(phones: OperatorPhone[]): void {
    const selectedId = this.selectedPhone()?.id || (Number.isFinite(this.requestedPhoneId) ? this.requestedPhoneId : null);
    const nextSelected = selectedId ? phones.find((phone) => phone.id === selectedId) : null;

    if (nextSelected) {
      this.selectPhone(nextSelected);
      return;
    }

    if (!this.selectedPhone()) {
      this.startNewPhone();
    }
  }

  private patchSaved(phone: OperatorPhone): void {
    this.phones.update((phones) => {
      const exists = phones.some((item) => item.id === phone.id);
      return exists
        ? phones.map((item) => item.id === phone.id ? phone : item)
        : [phone, ...phones];
    });
  }

  private toRequest(): OperatorPhoneRequest {
    const raw = this.form.getRawValue();
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

  private toDateInput(value?: string | null): string {
    return value ? value.slice(0, 10) : '';
  }

  private toDateTimeInput(value?: string | null): string {
    return value ? value.slice(0, 16) : '';
  }

  private emptyToNull(value: string): string | null {
    const trimmed = value.trim();
    return trimmed ? trimmed : null;
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }
}
