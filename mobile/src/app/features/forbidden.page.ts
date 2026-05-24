import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { IonButton, IonContent } from '@ionic/angular/standalone';

@Component({
  selector: 'app-forbidden',
  imports: [IonButton, IonContent, RouterLink],
  template: `
    <div class="ion-page">
      <ion-content fullscreen>
        <main class="mobile-page">
          <section class="error-state">
            Нет доступа к этому разделу для текущей роли.
          </section>
          <ion-button routerLink="/tabs/home">На главную</ion-button>
        </main>
      </ion-content>
    </div>
  `
})
export class ForbiddenPage {}
