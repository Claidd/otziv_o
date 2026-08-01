import { Injectable } from '@angular/core';
import { Browser } from '@capacitor/browser';
import { Capacitor } from '@capacitor/core';
import { safePaymentNavigationTarget, type PaymentNavigationPurpose } from './payment-navigation';

@Injectable({ providedIn: 'root' })
export class MobileExternalLinkService {
  private readonly isNative = Capacitor.isNativePlatform();

  async open(url?: string | null): Promise<void> {
    const target = url?.trim();
    if (!target) {
      return;
    }

    if (this.isNative && /^https?:\/\//i.test(target)) {
      await Browser.open({ url: target, presentationStyle: 'popover' });
      return;
    }

    window.open(target, '_blank', 'noopener');
  }

  async openPayment(
    url: unknown,
    purpose: PaymentNavigationPurpose
  ): Promise<boolean> {
    const target = safePaymentNavigationTarget(url, purpose);
    if (!target) {
      return false;
    }

    if (this.isNative && /^https?:\/\//i.test(target)) {
      await Browser.open({ url: target, presentationStyle: 'popover' });
      return true;
    }

    window.location.assign(target);
    return true;
  }

  openScheme(url?: string | null): void {
    const target = url?.trim();
    if (!target) {
      return;
    }
    window.location.href = target;
  }
}
