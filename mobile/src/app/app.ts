import { Component, inject } from '@angular/core';
import { IonApp, IonRouterOutlet } from '@ionic/angular/standalone';
import { MobileThemeService } from './shared/mobile-theme.service';

@Component({
  selector: 'app-root',
  imports: [IonApp, IonRouterOutlet],
  template: `
    <ion-app>
      <ion-router-outlet />
    </ion-app>
  `,
  styles: []
})
export class App {
  private readonly mobileTheme = inject(MobileThemeService);
}
