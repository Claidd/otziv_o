import { Component, computed } from '@angular/core';
import { Router } from '@angular/router';
import {
  IonButton,
  IonContent,
  IonIcon,
  IonSpinner
} from '@ionic/angular/standalone';
import { logInOutline, shieldCheckmarkOutline } from 'ionicons/icons';
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
              <ion-icon name="shield-checkmark-outline" />
            </div>
            <h1>Otziv</h1>
            <p>Мобильный кабинет команды</p>
          </section>

          @if (auth.error()) {
            <div class="error-state">{{ auth.error() }}</div>
          }

          <ion-button size="large" expand="block" (click)="login()" [disabled]="busy()">
            @if (busy()) {
              <ion-spinner name="crescent" />
            } @else {
              <ion-icon slot="start" name="log-in-outline" />
            }
            Войти через Keycloak
          </ion-button>
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
      border-radius: 8px;
      background: #e7f0ff;
      color: #246bfe;
      font-size: 1.8rem;
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
  `]
})
export class LoginPage {
  readonly busy = computed(() => this.auth.status() === 'initializing' || this.auth.status() === 'refreshing');

  constructor(
    readonly auth: AuthService,
    private readonly router: Router
  ) {
    addIcons({ logInOutline, shieldCheckmarkOutline });

    if (this.auth.isAuthenticated()) {
      void this.router.navigateByUrl('/tabs/home', { replaceUrl: true });
    }
  }

  login(): void {
    void this.auth.login('/tabs/home');
  }
}
