import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthLifecycleApi, ProvisionedUserResponse, RegisterClientRequest } from '../../core/auth-lifecycle.api';
import { AuthService } from '../../core/auth.service';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';

@Component({
  selector: 'app-register-client',
  imports: [AdminLayoutComponent, LoadErrorCardComponent, ReactiveFormsModule, RouterLink],
  templateUrl: './register-client.component.html',
  styleUrl: './register-client.component.scss'
})
export class RegisterClientComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authApi = inject(AuthLifecycleApi);
  readonly auth = inject(AuthService);

  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly createdUser = signal<ProvisionedUserResponse | null>(null);

  readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required, Validators.minLength(3)]],
    email: ['', [Validators.required, Validators.email]],
    fio: [''],
    phoneNumber: [''],
    password: ['', [Validators.required, Validators.minLength(6)]],
    matchingPassword: ['', [Validators.required, Validators.minLength(6)]]
  });

  submit(): void {
    this.error.set(null);
    this.createdUser.set(null);

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    if (raw.password !== raw.matchingPassword) {
      this.error.set('Пароли не совпадают');
      return;
    }

    const request: RegisterClientRequest = {
      username: raw.username.trim(),
      email: raw.email.trim(),
      fio: raw.fio.trim() || undefined,
      phoneNumber: raw.phoneNumber.trim() || undefined,
      password: raw.password,
      matchingPassword: raw.matchingPassword
    };

    this.saving.set(true);
    this.authApi.registerClient(request).subscribe({
      next: (user) => {
        this.createdUser.set(user);
        this.saving.set(false);
        this.form.reset({
          username: '',
          email: '',
          fio: '',
          phoneNumber: '',
          password: '',
          matchingPassword: ''
        });
      },
      error: (err) => {
        this.error.set(apiErrorMessage(err, 'Не удалось зарегистрировать пользователя'));
        this.saving.set(false);
      }
    });
  }

  login(): void {
    void this.auth.login('/');
  }
}
