import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { IonButton, IonContent, IonSpinner } from '@ionic/angular/standalone';
import { AuthService } from '../core/auth.service';

@Component({
  selector: 'app-auth-callback',
  imports: [IonButton, IonContent, IonSpinner],
  template: `
    <div class="ion-page">
      <ion-content class="ion-padding">
        <div class="callback">
          @if (auth.status() === 'error') {
            <div class="callback-mark">!</div>
            <h1>Войти не получилось</h1>
            <p>{{ auth.error() || 'Keycloak вернул ошибку авторизации.' }}</p>
            <div class="callback-actions">
              <ion-button type="button" (click)="retryLogin()">Повторить вход</ion-button>
              <ion-button type="button" fill="clear" (click)="goToLogin()">На вход</ion-button>
            </div>
          } @else {
            <ion-spinner name="crescent" />
            <h1>Входим в Компания О!</h1>
          }
        </div>
      </ion-content>
    </div>
  `,
  styles: [`
    .callback {
      min-height: 70vh;
      display: grid;
      place-items: center;
      align-content: center;
      gap: 14px;
      color: #172033;
      text-align: center;
    }

    .callback-mark {
      display: grid;
      width: 52px;
      height: 52px;
      place-items: center;
      border-radius: 999px;
      color: #9a2737;
      background: rgba(255, 0, 96, 0.1);
      font-size: 1.35rem;
      font-weight: 900;
    }

    h1 {
      margin: 0;
      font-size: 1.25rem;
    }

    p {
      max-width: 320px;
      margin: 0;
      color: #667085;
      font-size: 0.9rem;
      line-height: 1.35;
    }

    .callback-actions {
      display: grid;
      gap: 8px;
      width: min(100%, 260px);
    }
  `]
})
export class AuthCallbackPage implements OnInit {
  constructor(
    readonly auth: AuthService,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    void this.auth.completeLoginFromCallback(window.location.href);
  }

  retryLogin(): void {
    void this.auth.login('/tabs/home');
  }

  goToLogin(): void {
    void this.router.navigateByUrl('/login', { replaceUrl: true });
  }
}
