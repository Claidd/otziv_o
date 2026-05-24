import { Component, OnInit, signal } from '@angular/core';
import {
  IonButton,
  IonContent,
  IonIcon,
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { logOutOutline, refreshOutline } from 'ionicons/icons';
import { firstValueFrom } from 'rxjs';
import { ApiService, CabinetProfile } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

@Component({
  selector: 'app-profile',
  imports: [IonButton, IonContent, IonIcon, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Профиль" />

      <ion-content fullscreen>
        <main class="mobile-page">
          <section class="profile-card">
            <h1>{{ auth.user()?.name || auth.user()?.preferredUsername || 'Пользователь' }}</h1>
            <p>{{ auth.user()?.email || 'email не указан' }}</p>
            <div class="pill-row">
              @for (role of auth.user()?.roles ?? []; track role) {
                <span class="role-pill">{{ role }}</span>
              }
            </div>
          </section>

          @if (profile()) {
            <section class="metric-grid">
              <article class="metric">
                <strong>{{ profile()?.user?.leadCount ?? 0 }}</strong>
                <span>Лиды</span>
              </article>
              <article class="metric">
                <strong>{{ profile()?.user?.reviewCount ?? 0 }}</strong>
                <span>Отзывы</span>
              </article>
            </section>
          }

          @if (error()) {
            <div class="error-state">{{ error() }}</div>
          }

          <ion-button expand="block" fill="outline" (click)="load()">
            <ion-icon slot="start" name="refresh-outline" />
            Обновить
          </ion-button>

          <ion-button expand="block" color="danger" (click)="logout()">
            <ion-icon slot="start" name="log-out-outline" />
            Выйти
          </ion-button>
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    .profile-card {
      border: 1px solid #d9e1ef;
      border-radius: 8px;
      background: #ffffff;
      padding: 18px;
    }

    h1 {
      margin: 0;
      color: #172033;
      font-size: 1.35rem;
    }

    p {
      margin: 7px 0 14px;
      color: #667085;
    }

    .role-pill {
      border-radius: 999px;
      background: #eaf7f5;
      color: #0f766e;
      padding: 6px 10px;
      font-size: 0.78rem;
      font-weight: 800;
    }
  `]
})
export class ProfilePage implements OnInit {
  readonly profile = signal<CabinetProfile | null>(null);
  readonly error = signal<string | null>(null);

  constructor(
    readonly auth: AuthService,
    private readonly api: ApiService
  ) {
    addIcons({ logOutOutline, refreshOutline });
  }

  ngOnInit(): void {
    void this.load();
  }

  async load(): Promise<void> {
    try {
      this.profile.set(await firstValueFrom(this.api.getCabinetProfile()));
      this.error.set(null);
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : 'Не удалось загрузить профиль.');
    }
  }

  logout(): void {
    void this.auth.logout();
  }
}
