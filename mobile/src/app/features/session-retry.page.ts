import { ChangeDetectionStrategy, Component, effect } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { IonButton, IonContent, IonSpinner } from '@ionic/angular';
import { AuthService } from '../core/auth.service';

@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
  selector: 'app-session-retry',
  imports: [IonButton, IonContent, IonSpinner],
  template: `
    <div class="ion-page">
      <ion-content fullscreen>
        <main class="session-retry">
          <section class="retry-card" role="status">
            <h1>Восстанавливаем соединение</h1>
            <p>Ваша сессия сохранена. После подключения вы вернётесь к работе.</p>
            <ion-button expand="block" (click)="retry()" [disabled]="auth.status() === 'refreshing'">
              @if (auth.status() === 'refreshing') { <ion-spinner name="crescent" /> }
              Повторить подключение
            </ion-button>
          </section>
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    .session-retry {
      min-height: 100%;
      display: grid;
      place-items: center;
      padding: 24px;
    }
    .retry-card {
      width: min(100%, 420px);
      padding: 24px;
      border-radius: 20px;
      background: var(--ion-card-background, var(--ion-background-color));
      color: var(--ion-text-color);
      box-shadow: 0 12px 36px rgba(25, 33, 49, 0.12);
    }
    h1 { margin: 0 0 12px; font-size: 1.4rem; }
    p { margin: 0 0 22px; line-height: 1.5; }
  `]
})
export class SessionRetryPage {
  constructor(
    readonly auth: AuthService,
    private readonly router: Router,
    private readonly route: ActivatedRoute
  ) {
    effect(() => {
      if (this.auth.isAuthenticated()) {
        void this.router.navigateByUrl(this.targetUrl(), { replaceUrl: true });
      } else if (!this.auth.tokens() && this.auth.status() !== 'initializing') {
        void this.router.navigate(['/login'], { queryParams: { target: this.targetUrl() }, replaceUrl: true });
      }
    });
  }

  retry(): void {
    void this.auth.refreshTokens();
  }

  private targetUrl(): string {
    const target = this.route.snapshot.queryParamMap.get('target');
    return target?.startsWith('/') && !target.startsWith('//') ? target : '/tabs/home';
  }
}
