import { Component, effect, inject } from '@angular/core';
import { IonApp, IonRouterOutlet } from '@ionic/angular/standalone';
import { AuthService } from './core/auth.service';
import { MobileNativeService } from './core/mobile-native.service';
import { MobilePushService } from './core/mobile-push.service';
import { MobileConfirmHostComponent } from './shared/mobile-confirm-host.component';
import { MobileNativeStatusComponent } from './shared/mobile-native-status.component';
import { MobileThemeService } from './shared/mobile-theme.service';

@Component({
  selector: 'app-root',
  imports: [IonApp, IonRouterOutlet, MobileConfirmHostComponent, MobileNativeStatusComponent],
  template: `
    <ion-app>
      <ion-router-outlet />
      <app-mobile-confirm-host />
      <app-mobile-native-status />
    </ion-app>
  `,
  styles: []
})
export class App {
  private readonly auth = inject(AuthService);
  private readonly mobileTheme = inject(MobileThemeService);
  private readonly mobileNative = inject(MobileNativeService);
  private readonly mobilePush = inject(MobilePushService);

  private readonly pushRegistration = effect(() => {
    if (this.auth.status() === 'authenticated') {
      void this.mobilePush.register();
    }
  });

  constructor() {
    this.mobileNative.initialize();
  }
}
