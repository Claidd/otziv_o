import { Component, EventEmitter, Input, OnDestroy, Output } from '@angular/core';
import type { AppNavigationLink } from '../app-navigation';
import { MobileNavIntentService, type MobileNavIntent, type MobileNavMode, type MobileNavTab } from './mobile-nav-intent.service';

export const MOBILE_NAV_HOLD_MS = 700;
export const MOBILE_NAV_DOUBLE_TAP_MS = 700;
export const MOBILE_NAV_HOLD_CLICK_SUPPRESS_MS = 1500;

export type MobileBottomNavRequest = Pick<MobileNavIntent, 'tab' | 'mode'>;

@Component({
  selector: 'app-mobile-bottom-nav',
  standalone: true,
  template: `
    <nav class="mobile-bottom-nav" aria-label="Основные разделы">
      @for (item of items; track item.id) {
        <button
          type="button"
          class="mobile-nav-item"
          [class.active]="item.group === activeTab"
          [attr.aria-current]="item.group === activeTab ? 'page' : null"
          [attr.aria-label]="item.label + '. Двойное нажатие или удержание открывает подразделы'"
          (click)="handleClick($event, item.group)"
          (pointerdown)="startHold(item.group)"
          (pointerup)="cancelHold()"
          (pointercancel)="cancelPointerGesture()"
          (pointerleave)="cancelPointerGesture()"
          (contextmenu)="$event.preventDefault()"
        >
          <span class="material-icons-sharp" aria-hidden="true">{{ item.mobileIcon || item.icon }}</span>
          <span>{{ item.label }}</span>
        </button>
      }
    </nav>
  `,
  styles: [`
    :host { display: block; }
    .mobile-bottom-nav {
      position: fixed;
      right: 0;
      bottom: 0;
      left: 0;
      z-index: 80;
      display: flex;
      height: calc(var(--otziv-mobile-nav-height, 2.56rem) + env(safe-area-inset-bottom));
      align-items: flex-start;
      justify-content: stretch;
      gap: 0;
      border-top: 1px solid rgba(103, 116, 131, 0.16);
      padding: 0.04rem max(0.18rem, env(safe-area-inset-right)) calc(0.08rem + env(safe-area-inset-bottom)) max(0.18rem, env(safe-area-inset-left));
      background: rgba(255, 255, 255, 0.96);
      box-shadow: 0 -0.42rem 1rem rgba(132, 139, 200, 0.1);
    }
    /* The repeated class intentionally outranks the legacy admin-layout button
       rule without making every declaration important. */
    :host .mobile-nav-item.mobile-nav-item {
      display: flex;
      flex: 1 1 0;
      width: 100%;
      min-width: 0;
      max-width: none;
      min-height: 0;
      height: 2.08rem;
      align-items: center;
      justify-content: center;
      flex-direction: column;
      gap: 0;
      border: 0;
      border-radius: 0.52rem;
      padding: 0;
      color: var(--otziv-info);
      background: transparent;
      font: inherit;
      font-weight: 800;
      line-height: 1;
      touch-action: manipulation;
      user-select: none;
      -webkit-user-select: none;
      cursor: pointer;
    }
    :host .mobile-nav-item.mobile-nav-item.active {
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }
    .mobile-nav-item .material-icons-sharp { flex: 0 0 auto; font-size: 1.03rem; pointer-events: none; }
    .mobile-nav-item > span:last-child { display: block; width: 100%; overflow: hidden; margin-top: 0.04rem; font-size: clamp(0.47rem, 1.95vw, 0.52rem); line-height: 1.12; text-align: center; text-overflow: ellipsis; white-space: nowrap; pointer-events: none; }
    :host-context(body.otziv-dark-theme) .mobile-bottom-nav { border-color: rgba(163, 189, 204, 0.14); background: rgba(32, 37, 40, 0.96); box-shadow: 0 -0.42rem 1rem rgba(0, 0, 0, 0.2); }
    @media (max-width: 370px) {
      .mobile-bottom-nav { height: calc(2.48rem + env(safe-area-inset-bottom)); padding-inline: 0.12rem; }
      :host .mobile-nav-item.mobile-nav-item { height: 2.02rem; border-radius: 0.52rem; }
      .mobile-nav-item .material-icons-sharp { font-size: 1.03rem; }
      .mobile-nav-item > span:last-child { margin-top: 0.03rem; font-size: 0.46rem; }
    }
  `]
})
export class MobileBottomNavComponent implements OnDestroy {
  private holdTimer: ReturnType<typeof setTimeout> | null = null;
  private holdTriggered = false;
  private suppressNextClick = false;

  @Input() items: readonly AppNavigationLink[] = [];
  @Input() activeTab: MobileNavTab = 'home';
  @Output() readonly navigate = new EventEmitter<MobileBottomNavRequest>();

  constructor(private readonly navIntents: MobileNavIntentService) {}

  startHold(tab: MobileNavTab): void {
    this.cancelHold();
    this.holdTriggered = false;
    this.holdTimer = setTimeout(() => {
      this.holdTimer = null;
      this.holdTriggered = true;
      this.suppressNextClick = true;
      this.navIntents.resetTapGesture();
      this.navIntents.suppressHoldClick(tab, MOBILE_NAV_HOLD_CLICK_SUPPRESS_MS);
      this.emit(tab, 'menu');
    }, MOBILE_NAV_HOLD_MS);
  }

  cancelHold(): void {
    if (this.holdTimer) {
      clearTimeout(this.holdTimer);
      this.holdTimer = null;
    }
  }

  cancelPointerGesture(): void {
    this.cancelHold();
    this.navIntents.clearHoldClickSuppression();
    if (this.holdTriggered) {
      this.holdTriggered = false;
      this.suppressNextClick = false;
    }
  }

  handleClick(event: MouseEvent, tab: MobileNavTab): void {
    event.preventDefault();
    event.stopPropagation();
    this.cancelHold();

    const serviceSuppressedClick = this.navIntents.consumeSuppressedHoldClick(tab);
    if (this.holdTriggered || this.suppressNextClick || serviceSuppressedClick) {
      this.holdTriggered = false;
      this.suppressNextClick = false;
      return;
    }

    if (event.detail === 0) {
      this.navIntents.resetTapGesture();
      this.emit(tab, 'all');
      return;
    }

    this.emit(tab, this.navIntents.resolveTap(tab, MOBILE_NAV_DOUBLE_TAP_MS));
  }

  ngOnDestroy(): void {
    this.cancelHold();
  }

  private emit(tab: MobileNavTab, mode: MobileNavMode): void {
    this.navigate.emit({ tab, mode });
  }
}
