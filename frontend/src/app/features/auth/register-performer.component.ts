import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  AuthLifecycleApi,
  PerformerCityOption,
  RegisterPerformerRequest,
  RegisterPerformerResponse
} from '../../core/auth-lifecycle.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';

@Component({
  selector: 'app-register-performer',
  imports: [AdminLayoutComponent, LoadErrorCardComponent, ReactiveFormsModule, RouterLink],
  templateUrl: './register-performer.component.html',
  styleUrl: './register-performer.component.scss'
})
export class RegisterPerformerComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly authApi = inject(AuthLifecycleApi);

  readonly cities = signal<PerformerCityOption[]>([]);
  readonly loadingCities = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly created = signal<RegisterPerformerResponse | null>(null);

  readonly form = this.fb.nonNullable.group({
    fio: ['', [Validators.required, Validators.minLength(2)]],
    phoneNumber: ['', [Validators.required, Validators.minLength(10)]],
    cityId: [0, [Validators.required, Validators.min(1)]],
    gender: ['NOT_SPECIFIED'],
    telegramUsername: [''],
    registeredSource: ['SITE'],
    personalDataConsentAccepted: [false, Validators.requiredTrue],
    rulesConsentAccepted: [false, Validators.requiredTrue],
    honestReviewConsentAccepted: [false, Validators.requiredTrue]
  });

  ngOnInit(): void {
    this.loadingCities.set(true);
    this.authApi.getPerformerCities().subscribe({
      next: (cities) => {
        this.cities.set(cities);
        this.loadingCities.set(false);
      },
      error: (err) => {
        this.error.set(apiErrorMessage(err, 'Не удалось загрузить города'));
        this.loadingCities.set(false);
      }
    });
  }

  submit(): void {
    this.error.set(null);
    this.created.set(null);

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: RegisterPerformerRequest = {
      fio: raw.fio.trim(),
      phoneNumber: raw.phoneNumber.trim(),
      cityId: Number(raw.cityId),
      gender: raw.gender as RegisterPerformerRequest['gender'],
      telegramUsername: raw.telegramUsername.trim() || undefined,
      registeredSource: raw.registeredSource.trim() || 'SITE',
      personalDataConsentAccepted: raw.personalDataConsentAccepted,
      rulesConsentAccepted: raw.rulesConsentAccepted,
      honestReviewConsentAccepted: raw.honestReviewConsentAccepted
    };

    this.saving.set(true);
    this.authApi.registerPerformer(request).subscribe({
      next: (response) => {
        this.created.set(response);
        this.saving.set(false);
      },
      error: (err) => {
        this.error.set(apiErrorMessage(err, 'Не удалось зарегистрировать исполнителя'));
        this.saving.set(false);
      }
    });
  }

}
