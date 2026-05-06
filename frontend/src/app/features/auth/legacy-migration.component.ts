import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthLifecycleApi, LegacyUserMigrationRequest, ProvisionedUserResponse } from '../../core/auth-lifecycle.api';
import { AuthService } from '../../core/auth.service';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';

@Component({
  selector: 'app-legacy-migration',
  imports: [AdminLayoutComponent, LoadErrorCardComponent, ReactiveFormsModule, RouterLink],
  templateUrl: './legacy-migration.component.html',
  styleUrl: './legacy-migration.component.scss'
})
export class LegacyMigrationComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authApi = inject(AuthLifecycleApi);
  readonly auth = inject(AuthService);

  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly migratedUser = signal<ProvisionedUserResponse | null>(null);

  readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required]],
    password: ['', [Validators.required]]
  });

  submit(): void {
    this.error.set(null);
    this.migratedUser.set(null);

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: LegacyUserMigrationRequest = {
      username: raw.username.trim(),
      password: raw.password
    };

    this.saving.set(true);
    this.authApi.migrateLegacyUser(request).subscribe({
      next: (user) => {
        this.migratedUser.set(user);
        this.saving.set(false);
        this.form.reset({
          username: '',
          password: ''
        });
      },
      error: (err) => {
        this.error.set(apiErrorMessage(err, 'Не удалось перенести пользователя'));
        this.saving.set(false);
      }
    });
  }

  login(): void {
    void this.auth.login('/');
  }
}
