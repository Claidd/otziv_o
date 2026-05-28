import { Injectable } from '@angular/core';
import { Browser } from '@capacitor/browser';
import { Capacitor } from '@capacitor/core';

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

  openScheme(url?: string | null): void {
    const target = url?.trim();
    if (!target) {
      return;
    }
    window.location.href = target;
  }
}
