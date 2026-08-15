import { Injectable } from '@angular/core';
import { Browser } from '@capacitor/browser';
import { Capacitor } from '@capacitor/core';
import { MobileAuthDiagnosticsService } from '../core/mobile-auth-diagnostics.service';
import { safePaymentNavigationTarget, type PaymentNavigationPurpose } from './payment-navigation';
import { safeExternalSchemeUrl, safeHttpsExternalUrl } from './external-navigation';

@Injectable({ providedIn: 'root' })
export class MobileExternalLinkService {
  private readonly isNative = Capacitor.isNativePlatform();

  constructor(private readonly diagnostics: MobileAuthDiagnosticsService) {}

  async open(url?: string | null): Promise<void> {
    const target = safeHttpsExternalUrl(url);
    if (!target) {
      return;
    }

    if (this.isNative) {
      await this.diagnostics.checkpoint(`external.https:${this.safeHost(target)}`);
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
      await this.diagnostics.checkpoint(`external.payment:${purpose}`);
      await Browser.open({ url: target, presentationStyle: 'popover' });
      return true;
    }

    window.location.assign(target);
    return true;
  }

  openScheme(url?: string | null): void {
    const target = safeExternalSchemeUrl(url, ['tg:']);
    if (!target) {
      return;
    }
    this.diagnostics.breadcrumb('external.scheme:tg', true);
    window.location.href = target;
  }

  private safeHost(url: string): string {
    try {
      return new URL(url).hostname.toLowerCase().replace(/[^a-z0-9.-]+/g, '_').slice(0, 48) || 'unknown';
    } catch {
      return 'unknown';
    }
  }
}
