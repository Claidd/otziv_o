import { Component, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { IonContent } from '@ionic/angular/standalone';
import {
  ApiService,
  PerformerCityOption,
  ProvisionedUserResponse,
  RegisterPerformerResponse
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { safeHttpsExternalUrl } from '../shared/external-navigation';

type RegisterMode = 'client' | 'performer' | 'legacy';

const STRONG_PASSWORD_PATTERN = /^(?=.*\p{Ll})(?=.*\p{Lu})(?=.*\p{N})(?=.*[^\p{L}\p{N}\s])[^\r\n]{12,128}$/u;

@Component({
  selector: 'app-public-register-page',
  imports: [FormsModule, IonContent, RouterLink],
  template: `
    <div class="ion-page">
      <ion-content fullscreen>
        <main class="register-page">
          <header class="register-hero">
            <a routerLink="/" class="brand">Компания <strong>О!</strong></a>
            <p>{{ kicker() }}</p>
            <h1>{{ title() }}</h1>
          </header>

          @if (error()) {
            <button class="state-card error" type="button" (click)="error.set(null)">{{ error() }}</button>
          }
          @if (createdUser(); as user) {
            <section class="state-card ok">
              <span class="material-icons-sharp">task_alt</span>
              <strong>Пользователь {{ user.username }} создан.</strong>
              <button type="button" (click)="login()">Войти</button>
            </section>
          }
          @if (createdPerformer(); as performer) {
            <section class="state-card ok">
              <span class="material-icons-sharp">task_alt</span>
              <strong>Заявка исполнителя принята.</strong>
              <small>Учётная запись отключена до ручной проверки телефона администратором. После проверки вам выдадут новые данные для входа.</small>
              @if (safeExternalUrl(performer.telegramLinkUrl); as telegramLinkUrl) {
                <a [href]="telegramLinkUrl" target="_blank" rel="noopener">Привязать Telegram</a>
              }
            </section>
          }

          @if (mode() === 'client') {
            <form class="register-form" (ngSubmit)="submitClient()">
              <label><span>Логин</span><input name="username" required [ngModel]="client.username" (ngModelChange)="client.username = $event"></label>
              <label><span>Email</span><input name="email" type="email" required [ngModel]="client.email" (ngModelChange)="client.email = $event"></label>
              <label><span>ФИО</span><input name="fio" [ngModel]="client.fio" (ngModelChange)="client.fio = $event"></label>
              <label><span>Телефон</span><input name="phone" type="tel" [ngModel]="client.phoneNumber" (ngModelChange)="client.phoneNumber = $event"></label>
              <label><span>Пароль</span><input name="password" type="password" required minlength="12" maxlength="128" [ngModel]="client.password" (ngModelChange)="client.password = $event"></label>
              <label><span>Повтор пароля</span><input name="matchingPassword" type="password" required minlength="12" maxlength="128" [ngModel]="client.matchingPassword" (ngModelChange)="client.matchingPassword = $event"></label>
              <button class="primary" type="submit" [disabled]="saving()">{{ saving() ? 'Создаем...' : 'Зарегистрироваться' }}</button>
            </form>
          } @else if (mode() === 'performer') {
            <form class="register-form" (ngSubmit)="submitPerformer()">
              <label><span>ФИО</span><input name="fio" required [ngModel]="performer.fio" (ngModelChange)="performer.fio = $event"></label>
              <label><span>Телефон</span><input name="phoneNumber" type="tel" required [ngModel]="performer.phoneNumber" (ngModelChange)="performer.phoneNumber = $event"></label>
              <label><span>Город</span><select name="cityId" required [ngModel]="performer.cityId" (ngModelChange)="performer.cityId = numberValue($event)"><option [ngValue]="0">Выберите город</option>@for (city of cities(); track city.id) {<option [ngValue]="city.id">{{ city.cityTitle }}</option>}</select></label>
              <label><span>Пол</span><select name="gender" [ngModel]="performer.gender" (ngModelChange)="performer.gender = $event"><option value="NOT_SPECIFIED">Не указан</option><option value="MALE">Мужской</option><option value="FEMALE">Женский</option><option value="OTHER">Другой</option></select></label>
              <label><span>Telegram</span><input name="telegram" [ngModel]="performer.telegramUsername" (ngModelChange)="performer.telegramUsername = $event"></label>
              <fieldset class="consents">
                <legend>Обязательные подтверждения · редакция 03.08.2026</legend>
                <label class="check-row"><input type="checkbox" name="personalDataConsent" [(ngModel)]="performer.personalDataConsentAccepted"><span>Согласен на обработку данных по <a routerLink="/privacy">политике</a>.</span></label>
                <label class="check-row"><input type="checkbox" name="rulesConsent" [(ngModel)]="performer.rulesConsentAccepted"><span>Принимаю <a routerLink="/offer">правила работы исполнителя</a>.</span></label>
                <label class="check-row"><input type="checkbox" name="honestReviewConsent" [(ngModel)]="performer.honestReviewConsentAccepted"><span>Буду публиковать только честные отзывы на основании собственного опыта.</span></label>
              </fieldset>
              <button class="primary" type="submit" [disabled]="saving() || loadingCities()">{{ saving() ? 'Создаем...' : 'Зарегистрироваться' }}</button>
            </form>
          } @else {
            <form class="register-form" (ngSubmit)="submitLegacy()">
              <label><span>Старый логин</span><input name="legacyUsername" required [ngModel]="legacy.username" (ngModelChange)="legacy.username = $event"></label>
              <label><span>Старый пароль</span><input name="legacyPassword" type="password" required [ngModel]="legacy.password" (ngModelChange)="legacy.password = $event"></label>
              <button class="primary" type="submit" [disabled]="saving()">{{ saving() ? 'Переносим...' : 'Перенести аккаунт' }}</button>
            </form>
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content{--background:#f6f8fc}.register-page{display:grid;gap:.8rem;max-width:38rem;margin:0 auto;padding:calc(1rem + env(safe-area-inset-top)) .85rem calc(1.2rem + env(safe-area-inset-bottom));font-family:var(--otziv-font-family)}
    .register-hero,.register-form,.state-card{border:1px solid rgba(103,116,131,.16);border-radius:1rem;background:linear-gradient(155deg,var(--otziv-white),var(--otziv-tone-walk-surface));box-shadow:0 .9rem 1.8rem rgba(132,139,200,.12)}.register-hero{display:grid;gap:.45rem;padding:1rem}.brand{color:var(--otziv-dark);font:900 1.1rem/1 var(--otziv-card-title-font);text-decoration:none}.brand strong{color:var(--otziv-danger)}.register-hero p{margin:0;color:var(--otziv-info);font-size:.7rem;font-weight:1000;text-transform:uppercase}.register-hero h1{margin:0;color:var(--otziv-dark);font-size:1.75rem}
    .register-form{display:grid;gap:.62rem;padding:.85rem}.register-form label{display:grid;gap:.3rem}.register-form span{color:var(--otziv-info);font-size:.66rem;font-weight:1000;text-transform:uppercase}.register-form input,.register-form select{min-height:2.5rem;border:1px solid rgba(103,116,131,.18);border-radius:.75rem;padding:0 .75rem;color:var(--otziv-dark);background:var(--otziv-white);font:900 .9rem/1 var(--otziv-font-family)}
    .consents{display:grid;gap:.65rem;margin:0;border:1px solid rgba(103,116,131,.18);border-radius:.8rem;padding:.8rem}.consents legend{padding:0 .25rem;color:var(--otziv-info);font-size:.66rem;font-weight:1000}.register-form .check-row{grid-template-columns:1.2rem 1fr;align-items:start}.register-form .check-row input{min-height:1.1rem;margin:.1rem 0 0;padding:0}.register-form .check-row span{text-transform:none;line-height:1.35}.check-row a{color:var(--otziv-primary)}
    button,.state-card a{display:inline-flex;align-items:center;justify-content:center;gap:.35rem;min-height:2.55rem;border:1px solid rgba(108,155,207,.25);border-radius:.82rem;padding:0 .9rem;color:var(--otziv-primary);background:var(--otziv-white);font:1000 .82rem/1 var(--otziv-font-family);text-decoration:none}button.primary{color:#fff;background:var(--otziv-primary)}button:disabled{opacity:.55}.state-card{display:grid;place-items:center;gap:.35rem;min-height:5.5rem;padding:1rem;text-align:center}.state-card.error{color:var(--otziv-danger)}.state-card.ok{color:#16735f}.state-card small{color:var(--otziv-info);font-weight:900}
  `]
})
export class PublicRegisterPage implements OnInit {
  readonly mode = computed<RegisterMode>(() => {
    const mode = this.route.snapshot.data['mode'];
    return mode === 'performer' || mode === 'legacy' ? mode : 'client';
  });
  readonly saving = signal(false);
  readonly loadingCities = signal(false);
  readonly error = signal<string | null>(null);
  readonly createdUser = signal<ProvisionedUserResponse | null>(null);
  readonly createdPerformer = signal<RegisterPerformerResponse | null>(null);
  readonly cities = signal<PerformerCityOption[]>([]);

  readonly client = { username: '', email: '', fio: '', phoneNumber: '', password: '', matchingPassword: '' };
  readonly performer = {
    fio: '',
    phoneNumber: '',
    cityId: 0,
    gender: 'NOT_SPECIFIED' as const,
    telegramUsername: '',
    registeredSource: 'SITE',
    personalDataConsentAccepted: false,
    rulesConsentAccepted: false,
    honestReviewConsentAccepted: false
  };
  readonly legacy = { username: '', password: '' };

  readonly title = computed(() => ({
    client: 'Регистрация клиента',
    performer: 'Регистрация исполнителя',
    legacy: 'Перенос аккаунта'
  })[this.mode()]);
  readonly kicker = computed(() => this.mode() === 'legacy' ? 'Миграция' : 'Публичная форма');

  safeExternalUrl(value: unknown): string {
    return safeHttpsExternalUrl(value) ?? '';
  }

  constructor(
    private readonly api: ApiService,
    private readonly auth: AuthService,
    private readonly route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    if (this.mode() === 'performer') {
      this.loadingCities.set(true);
      this.api.getPerformerCities().subscribe({
        next: (cities) => {
          this.cities.set(cities ?? []);
          this.loadingCities.set(false);
        },
        error: (error) => {
          this.error.set(this.errorMessage(error, 'Не удалось загрузить города.'));
          this.loadingCities.set(false);
        }
      });
    }
  }

  submitClient(): void {
    this.error.set(null);
    this.createdUser.set(null);
    if (!this.client.username.trim() || !this.client.email.includes('@')) {
      this.error.set('Заполните логин и корректный e-mail.');
      return;
    }
    if (!STRONG_PASSWORD_PATTERN.test(this.client.password)) {
      this.error.set('Пароль: 12–128 символов, заглавная и строчная буквы, цифра и специальный символ.');
      return;
    }
    if (this.client.password !== this.client.matchingPassword) {
      this.error.set('Пароли не совпадают.');
      return;
    }
    this.saving.set(true);
    this.api.registerClient({ ...this.client, username: this.client.username.trim(), email: this.client.email.trim() }).subscribe({
      next: (user) => {
        this.createdUser.set(user);
        this.saving.set(false);
      },
      error: (error) => {
        this.error.set(this.errorMessage(error, 'Не удалось зарегистрировать пользователя.'));
        this.saving.set(false);
      }
    });
  }

  submitPerformer(): void {
    this.error.set(null);
    this.createdPerformer.set(null);
    if (!this.performer.fio.trim() || !this.performer.phoneNumber.trim() || !this.performer.cityId) {
      this.error.set('Заполните ФИО, телефон и город.');
      return;
    }
    if (!this.performer.personalDataConsentAccepted
      || !this.performer.rulesConsentAccepted
      || !this.performer.honestReviewConsentAccepted) {
      this.error.set('Подтвердите все обязательные условия регистрации.');
      return;
    }
    this.saving.set(true);
    this.api.registerPerformer({
      ...this.performer,
      fio: this.performer.fio.trim(),
      phoneNumber: this.performer.phoneNumber.trim(),
      telegramUsername: this.performer.telegramUsername.trim() || undefined
    }).subscribe({
      next: (created) => {
        this.createdPerformer.set(created);
        this.saving.set(false);
      },
      error: (error) => {
        this.error.set(this.errorMessage(error, 'Не удалось зарегистрировать исполнителя.'));
        this.saving.set(false);
      }
    });
  }

  submitLegacy(): void {
    this.error.set(null);
    this.createdUser.set(null);
    if (!this.legacy.username.trim() || !this.legacy.password) {
      this.error.set('Введите старый логин и пароль.');
      return;
    }
    this.saving.set(true);
    this.api.migrateLegacyUser({ username: this.legacy.username.trim(), password: this.legacy.password }).subscribe({
      next: (user) => {
        this.createdUser.set(user);
        this.saving.set(false);
      },
      error: (error) => {
        this.error.set(this.errorMessage(error, 'Не удалось перенести пользователя.'));
        this.saving.set(false);
      }
    });
  }

  login(): void {
    void this.auth.login('/tabs/home');
  }

  numberValue(value: unknown): number {
    return Number(value) || 0;
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (typeof error === 'object' && error && 'error' in error) {
      const body = (error as { error?: { message?: string; detail?: string; error?: string } | string }).error;
      return typeof body === 'string' ? body : body?.message || body?.detail || body?.error || fallback;
    }
    return fallback;
  }
}
