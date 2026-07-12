import { Injectable, signal } from '@angular/core';
import type { AppNavigationGroup } from '../app-navigation';

export type MobileNavMode = 'all' | 'menu';
export type MobileNavTab = AppNavigationGroup;

export type MobileNavIntent = {
  tab: MobileNavTab;
  mode: MobileNavMode;
  stamp: number;
};

@Injectable({ providedIn: 'root' })
export class MobileNavIntentService {
  private sequence = 0;
  private lastTapTab: MobileNavTab | null = null;
  private lastTapAt = 0;
  private suppressedHoldClickTab: MobileNavTab | null = null;
  private suppressedHoldClickUntil = 0;
  readonly intent = signal<MobileNavIntent | null>(null);

  request(tab: MobileNavTab, mode: MobileNavMode): MobileNavIntent {
    const intent: MobileNavIntent = { tab, mode, stamp: ++this.sequence };
    this.intent.set(intent);
    return intent;
  }

  clear(stamp?: number): void {
    if (stamp === undefined || this.intent()?.stamp === stamp) {
      this.intent.set(null);
    }
  }

  resolveTap(tab: MobileNavTab, doubleTapWindowMs: number, now = Date.now()): MobileNavMode {
    if (this.lastTapTab === tab && now - this.lastTapAt <= doubleTapWindowMs) {
      this.resetTapGesture();
      return 'menu';
    }

    this.lastTapTab = tab;
    this.lastTapAt = now;
    return 'all';
  }

  resetTapGesture(): void {
    this.lastTapTab = null;
    this.lastTapAt = 0;
  }

  suppressHoldClick(tab: MobileNavTab, durationMs: number, now = Date.now()): void {
    this.suppressedHoldClickTab = tab;
    this.suppressedHoldClickUntil = now + Math.max(0, durationMs);
  }

  consumeSuppressedHoldClick(tab: MobileNavTab, now = Date.now()): boolean {
    const shouldSuppress = this.suppressedHoldClickTab === tab && now <= this.suppressedHoldClickUntil;
    if (shouldSuppress || now > this.suppressedHoldClickUntil) {
      this.clearHoldClickSuppression();
    }
    return shouldSuppress;
  }

  clearHoldClickSuppression(): void {
    this.suppressedHoldClickTab = null;
    this.suppressedHoldClickUntil = 0;
  }
}
