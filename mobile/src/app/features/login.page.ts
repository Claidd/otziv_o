import { Component, OnInit, computed, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import {
  IonButton,
  IonContent,
  IonIcon,
  IonSpinner
} from '@ionic/angular/standalone';
import { logInOutline } from 'ionicons/icons';
import { addIcons } from 'ionicons';
import { AuthService } from '../core/auth.service';

@Component({
  selector: 'app-login',
  imports: [IonButton, IonContent, IonIcon, IonSpinner],
  template: `
    <div class="ion-page">
      <ion-content fullscreen>
        <main class="login-screen">
          <section class="brand-panel">
            <div class="brand-mark">
              <img src="assets/brand/logo-o.png" alt="" />
            </div>
            <h1>Компания О!</h1>
            <p>Мобильный кабинет команды</p>
          </section>

          @if (auth.error()) {
            <div class="error-state">{{ auth.error() }}</div>
            <ion-button size="large" expand="block" (click)="login()" [disabled]="busy()">
              @if (busy()) {
                <ion-spinner name="crescent" />
              } @else {
                <ion-icon slot="start" name="log-in-outline" />
              }
              Повторить вход
            </ion-button>
          } @else if (redirecting()) {
            <div class="login-progress">
              <ion-spinner name="crescent" />
              <span>Открываем защищенный вход</span>
            </div>
          } @else {
            <ion-button size="large" expand="block" (click)="login()" [disabled]="busy()">
              @if (busy()) {
                <ion-spinner name="crescent" />
              } @else {
                <ion-icon slot="start" name="log-in-outline" />
              }
              Войти
            </ion-button>
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    .login-screen {
      min-height: 100%;
      display: grid;
      align-content: center;
      gap: 18px;
      padding: 28px 18px;
      max-width: 520px;
      margin: 0 auto;
    }

    .brand-panel {
      display: grid;
      gap: 10px;
      justify-items: start;
      border: 1px solid #d9e1ef;
      border-radius: 8px;
      background: #ffffff;
      padding: 22px;
    }

    .brand-mark {
      display: grid;
      width: 54px;
      height: 54px;
      place-items: center;
      border-radius: 14px;
      background: #ffffff;
      box-shadow: inset 0 0 0 1px rgba(218, 226, 238, 0.9);
    }

    .brand-mark img {
      width: 38px;
      height: 38px;
      object-fit: contain;
    }

    h1 {
      margin: 0;
      color: #172033;
      font-size: 2rem;
      line-height: 1;
    }

    p {
      margin: 0;
      color: #667085;
      font-size: 1rem;
    }

    .login-progress {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      min-height: 52px;
      border: 1px solid #d9e1ef;
      border-radius: 8px;
      background: #ffffff;
      color: #53627a;
      font-weight: 700;
    }
  `]
})
export class LoginPage implements OnInit {
  private autoLoginStarted = false;

  readonly busy = computed(() => this.auth.status() === 'initializing' || this.auth.status() === 'refreshing');
  readonly redirecting = signal(true);

  constructor(
    readonly auth: AuthService,
    private readonly router: Router,
    private readonly route: ActivatedRoute
  ) {
    addIcons({ logInOutline });

    if (this.auth.isAuthenticated()) {
      void this.router.navigateByUrl(this.targetUrl(), { replaceUrl: true });
    }
  }

  ngOnInit(): void {
    if (this.auth.isAuthenticated() || this.auth.error()) {
      this.redirecting.set(false);
      return;
    }

    setTimeout(() => this.startAutoLogin(), 250);
  }

  login(): void {
    void this.startAutoLogin();
  }

  private startAutoLogin(): void {
    if (this.autoLoginStarted || this.auth.isAuthenticated()) {
      return;
    }

    this.autoLoginStarted = true;
    this.redirecting.set(true);
    void this.auth.login(this.targetUrl()).catch((error: unknown) => {
      this.autoLoginStarted = false;
      this.redirecting.set(false);
      this.auth.error.set(error instanceof Error ? error.message : 'Не удалось открыть страницу входа.');
    });
  }

  private targetUrl(): string {
    const target = this.route.snapshot.queryParamMap.get('target');
    return target?.startsWith('/') ? target : '/tabs/home';
  }
}
