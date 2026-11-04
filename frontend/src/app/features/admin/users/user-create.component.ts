import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  AdminUsersApi,
  CreatedKeycloakUserResponse,
  CreateKeycloakUserRequest
} from '../../../core/admin-users.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';

const TEMPORARY_PASSWORD_LENGTH = 14;
const TEMPORARY_PASSWORD_GROUPS = ['ABCDEFGHJKLMNPQRSTUVWXYZ', 'abcdefghijkmnopqrstuvwxyz', '23456789'];

/**
 * A fresh random temporary password for each new account. A fixed default (it used to be
 * shipped in this public repository) let anyone sign in to a new account before its owner
 * and replace the temporary password with their own.
 */
export function generateTemporaryPassword(length = TEMPORARY_PASSWORD_LENGTH): string {
  const alphabet = TEMPORARY_PASSWORD_GROUPS.join('');
  const random = new Uint32Array(length);
  for (;;) {
    crypto.getRandomValues(random);
    const password = Array.from(random, (value) => alphabet[value % alphabet.length]).join('');
    if (TEMPORARY_PASSWORD_GROUPS.every((group) => [...password].some((char) => group.includes(char)))) {
      return password;
    }
  }
}

@Component({
  selector: 'app-user-create',
  imports: [AdminLayoutComponent, LoadErrorCardComponent, ReactiveFormsModule, RouterLink],
  templateUrl: './user-create.component.html',
  styleUrl: './user-create.component.scss'
})
export class UserCreateComponent {
  private readonly fb = inject(FormBuilder);
  private readonly adminUsersApi = inject(AdminUsersApi);
  private readonly toastService = inject(ToastService);

  readonly availableRoles = ['ADMIN', 'OWNER', 'MANAGER', 'OPERATOR', 'WORKER', 'PERFORMER', 'MARKETOLOG', 'CLIENT'];

  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly createdUser = signal<CreatedKeycloakUserResponse | null>(null);
  readonly createdPassword = signal<string | null>(null);

  regeneratePassword(): void {
    this.form.controls.password.setValue(generateTemporaryPassword());
    this.form.controls.password.markAsDirty();
  }

  readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required, Validators.minLength(3)]],
    email: ['', [Validators.required, Validators.email]],
    fio: [''],
    phoneNumber: [''],
    password: [generateTemporaryPassword(), [Validators.required, Validators.minLength(8)]],
    temporaryPassword: [true],
    enabled: [true],
    emailVerified: [false],
    coefficient: ['0.05'],
    roles: this.fb.nonNullable.control<string[]>(['CLIENT'], [Validators.required])
  });

  toggleRole(role: string, checked: boolean): void {
    const roles = new Set(this.form.controls.roles.value);

    if (checked) {
      roles.add(role);
    } else {
      roles.delete(role);
    }

    this.form.controls.roles.setValue([...roles]);
    this.form.controls.roles.markAsDirty();
  }

  isRoleSelected(role: string): boolean {
    return this.form.controls.roles.value.includes(role);
  }

  submit(): void {
    this.error.set(null);
    this.createdUser.set(null);

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.saving.set(true);
    const raw = this.form.getRawValue();
    const coefficient = raw.coefficient.trim();

    const request: CreateKeycloakUserRequest = {
      username: raw.username.trim(),
      email: raw.email.trim(),
      fio: raw.fio.trim() || undefined,
      phoneNumber: raw.phoneNumber.trim() || undefined,
      password: raw.password,
      temporaryPassword: raw.temporaryPassword,
      enabled: raw.enabled,
      emailVerified: raw.emailVerified,
      coefficient: coefficient ? Number(coefficient) : undefined,
      roles: raw.roles
    };

    this.adminUsersApi.createUser(request).subscribe({
      next: (user) => {
        this.createdUser.set(user);
        this.createdPassword.set(request.password ?? null);
        this.saving.set(false);
        this.toastService.success('Пользователь создан', user.username);
        this.form.reset({
          username: '',
          email: '',
          fio: '',
          phoneNumber: '',
          password: generateTemporaryPassword(),
          temporaryPassword: true,
          enabled: true,
          emailVerified: false,
          coefficient: '0.05',
          roles: ['CLIENT']
        });
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось создать пользователя');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Пользователь не создан', message);
      }
    });
  }

  roleLabel(role: string): string {
    return {
      ADMIN: 'Админ',
      OWNER: 'Владелец',
      MANAGER: 'Менеджер',
      OPERATOR: 'Оператор',
      WORKER: 'Специалист',
      PERFORMER: 'Исполнитель',
      MARKETOLOG: 'Маркетолог',
      CLIENT: 'Клиент'
    }[role] ?? role;
  }

  trackRole(_index: number, role: string): string {
    return role;
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }
}
