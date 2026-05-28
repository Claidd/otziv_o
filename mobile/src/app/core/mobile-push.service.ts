import { Injectable, signal } from '@angular/core';
import { App as CapacitorApp } from '@capacitor/app';
import { Router } from '@angular/router';
import { Capacitor } from '@capacitor/core';
import { PushNotifications, type Token } from '@capacitor/push-notifications';
import { firstValueFrom } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';
import { MobilePushApiService } from './mobile-push-api.service';

export type MobilePushStatus = 'unsupported' | 'idle' | 'granted' | 'denied' | 'registered' | 'error';

@Injectable({ providedIn: 'root' })
export class MobilePushService {
  private listenersBound = false;
  private registrationPromise: Promise<string | null> | null = null;
  private backendToken: string | null = null;

  readonly supported = mobileEnvironment.pushNotificationsEnabled
    && Capacitor.isNativePlatform()
    && Capacitor.isPluginAvailable('PushNotifications');
  readonly status = signal<MobilePushStatus>(this.supported ? 'idle' : 'unsupported');
  readonly token = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  constructor(
    private readonly router: Router,
    private readonly api: MobilePushApiService
  ) {}

  async register(): Promise<string | null> {
    if (this.registrationPromise) {
      return this.registrationPromise;
    }

    this.registrationPromise = this.registerInternal().finally(() => {
      this.registrationPromise = null;
    });
    return this.registrationPromise;
  }

  private async registerInternal(): Promise<string | null> {
    if (!this.supported) {
      this.status.set('unsupported');
      return null;
    }

    const permission = await PushNotifications.requestPermissions().catch((error: unknown) => {
      this.setError(error);
      return null;
    });

    if (!permission || permission.receive !== 'granted') {
      this.status.set('denied');
      return null;
    }

    this.status.set('granted');
    await this.bindRegistrationListeners();
    await PushNotifications.register().catch((error: unknown) => this.setError(error));
    return this.token();
  }

  private async bindRegistrationListeners(): Promise<void> {
    if (this.listenersBound) {
      return;
    }

    this.listenersBound = true;
    await PushNotifications.addListener('registration', (token: Token) => {
      this.token.set(token.value);
      this.status.set('registered');
      this.error.set(null);
      void this.sendTokenToBackend(token.value);
    });

    await PushNotifications.addListener('registrationError', (error) => {
      this.setError(error);
    });

    await PushNotifications.addListener('pushNotificationActionPerformed', (event) => {
      const route = event.notification.data?.['route'];
      if (typeof route === 'string') {
        void this.router.navigateByUrl(route.startsWith('/') ? route : `/${route}`);
      }
    });
  }

  private setError(error: unknown): void {
    this.status.set('error');
    this.error.set(error instanceof Error ? error.message : 'Не удалось подключить push-уведомления.');
  }

  private async sendTokenToBackend(token: string): Promise<void> {
    if (this.backendToken === token) {
      return;
    }

    try {
      const info = await CapacitorApp.getInfo().catch(() => null);
      await firstValueFrom(this.api.registerToken({
        token,
        platform: Capacitor.getPlatform(),
        appVersion: info ? [info.version, info.build].filter(Boolean).join(' / ') : undefined
      }));
      this.backendToken = token;
      this.error.set(null);
    } catch (error: unknown) {
      this.error.set(error instanceof Error ? error.message : 'Push-токен получен, но не сохранен на сервере.');
    }
  }
}
