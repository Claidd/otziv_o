import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  AdminUsersApi,
  CreatedKeycloakUserResponse,
  CreateKeycloakUserRequest
} from '../../../core/admin-users.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { ToastService } from '../../../shared/toast.service';

@Component({
  selector: 'app-user-create',
  imports: [AdminLayoutComponent, ReactiveFormsModule, RouterLink],
  templateUrl: './user-create.component.html',
  styleUrl: './user-create.component.scss'
})
export class UserCreateComponent {
  private readonly fb = inject(FormBuilder);
  private readonly adminUsersApi = inject(AdminUsersApi);
  private readonly toastService = inject(ToastService);

  readonly availableRoles = ['ADMIN', 'OWNER', 'MANAGER', 'OPERATOR', 'WORKER', 'MARKETOLOG', 'CLIENT'];

  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly createdUser = signal<CreatedKeycloakUserResponse | null>(null);

  readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required, Validators.minLength(3)]],
    email: ['', [Validators.required, Validators.email]],
    fio: [''],
    phoneNumber: [''],
    password: ['Temp123!', [Validators.required, Validators.minLength(6)]],
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
        this.saving.set(false);
        this.toastService.success('Пользователь создан', user.username);
        this.form.reset({
          username: '',
          email: '',
          fio: '',
          phoneNumber: '',
          password: 'Temp123!',
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
      MARKETOLOG: 'Маркетолог',
      CLIENT: 'Клиент'
    }[role] ?? role;
  }

  trackRole(_index: number, role: string): string {
    return role;
  }

  private errorMessage(err: unknown, fallback: string): string {
    if (typeof err === 'object' && err !== null) {
      const response = err as { error?: unknown; message?: unknown };
      if (typeof response.error === 'string') {
        return response.error;
      }

      if (typeof response.error === 'object' && response.error !== null) {
        const body = response.error as { message?: unknown; detail?: unknown; title?: unknown };
        if (typeof body.message === 'string') {
          return body.message;
        }

        if (typeof body.detail === 'string') {
          return body.detail;
        }

        if (typeof body.title === 'string') {
          return body.title;
        }
      }

      if (typeof response.message === 'string') {
        return response.message;
      }
    }

    return fallback;
  }
}
