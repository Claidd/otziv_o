import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { IonIcon, IonLabel, IonRouterOutlet, IonTabBar, IonTabButton, IonTabs } from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import {
  businessOutline,
  callOutline,
  gridOutline,
  homeOutline,
  readerOutline,
  receiptOutline
} from 'ionicons/icons';
import { AuthService } from '../core/auth.service';
import { MOBILE_ACTIONS, MOBILE_ROLES, MOBILE_SECTIONS, canUseAction } from '../core/mobile-permissions';

type SmartTab = 'home' | 'leads' | 'companies' | 'orders' | 'worker';
const SMART_TAB_HOLD_MS = 700;
const SMART_TAB_DOUBLE_TAP_MS = 700;

@Component({
  selector: 'app-tabs',
  imports: [IonIcon, IonLabel, IonRouterOutlet, IonTabBar, IonTabButton, IonTabs],
  template: `
    <ion-tabs>
      <ion-router-outlet />
      <ion-tab-bar slot="bottom" class="otziv-tabbar">
        <button
          type="button"
          class="smart-tab-button"
          [class.tab-selected]="isSmartTabSelected('home')"
          [attr.aria-current]="isSmartTabSelected('home') ? 'page' : null"
          (click)="handleSmartTabClick($event, 'home')"
          (dblclick)="openSmartTabMenu($event, 'home')"
          (pointerdown)="startSmartTabHold('home')"
          (pointerup)="clearSmartTabHold()"
          (pointercancel)="clearSmartTabHold()"
          (pointerleave)="clearSmartTabHold()"
          (contextmenu)="$event.preventDefault()"
        >
          <ion-icon name="home-outline" />
          <span class="tab-label">Главная</span>
        </button>

        @if (canLeads()) {
          <button
            type="button"
            class="smart-tab-button"
            [class.tab-selected]="isSmartTabSelected('leads')"
            [attr.aria-current]="isSmartTabSelected('leads') ? 'page' : null"
            (click)="handleSmartTabClick($event, 'leads')"
            (dblclick)="openSmartTabMenu($event, 'leads')"
            (pointerdown)="startSmartTabHold('leads')"
            (pointerup)="clearSmartTabHold()"
            (pointercancel)="clearSmartTabHold()"
            (pointerleave)="clearSmartTabHold()"
            (contextmenu)="$event.preventDefault()"
          >
            <ion-icon name="grid-outline" />
            <span class="tab-label">Лиды</span>
          </button>
        }

        @if (canManager()) {
          <button
            type="button"
            class="smart-tab-button"
            [class.tab-selected]="isSmartTabSelected('companies')"
            [attr.aria-current]="isSmartTabSelected('companies') ? 'page' : null"
            (click)="handleSmartTabClick($event, 'companies')"
            (dblclick)="openSmartTabMenu($event, 'companies')"
            (pointerdown)="startSmartTabHold('companies')"
            (pointerup)="clearSmartTabHold()"
            (pointercancel)="clearSmartTabHold()"
            (pointerleave)="clearSmartTabHold()"
            (contextmenu)="$event.preventDefault()"
          >
            <ion-icon name="business-outline" />
            <span class="tab-label">Компании</span>
          </button>

          <button
            type="button"
            class="smart-tab-button"
            [class.tab-selected]="isSmartTabSelected('orders')"
            [attr.aria-current]="isSmartTabSelected('orders') ? 'page' : null"
            (click)="handleSmartTabClick($event, 'orders')"
            (dblclick)="openSmartTabMenu($event, 'orders')"
            (pointerdown)="startSmartTabHold('orders')"
            (pointerup)="clearSmartTabHold()"
            (pointercancel)="clearSmartTabHold()"
            (pointerleave)="clearSmartTabHold()"
            (contextmenu)="$event.preventDefault()"
          >
            <ion-icon name="receipt-outline" />
            <span class="tab-label">Заказы</span>
          </button>
        }

        @if (canWorker()) {
          <button
            type="button"
            class="smart-tab-button"
            [class.tab-selected]="isSmartTabSelected('worker')"
            [attr.aria-current]="isSmartTabSelected('worker') ? 'page' : null"
            (click)="handleSmartTabClick($event, 'worker')"
            (dblclick)="openSmartTabMenu($event, 'worker')"
            (pointerdown)="startSmartTabHold('worker')"
            (pointerup)="clearSmartTabHold()"
            (pointercancel)="clearSmartTabHold()"
            (pointerleave)="clearSmartTabHold()"
            (contextmenu)="$event.preventDefault()"
          >
            <ion-icon name="reader-outline" />
            <span class="tab-label">Специалист</span>
          </button>
        }

        @if (canOperator()) {
          <ion-tab-button tab="operator" href="/tabs/operator">
            <ion-icon name="call-outline" />
            <ion-label>Оператор</ion-label>
          </ion-tab-button>
        }

      </ion-tab-bar>
    </ion-tabs>
  `,
  styles: [`
    .otziv-tabbar {
      --background: rgba(255, 255, 255, 0.96);
      --border: 1px solid rgba(103, 116, 131, 0.16);
      justify-content: stretch;
      height: calc(3.36rem + env(safe-area-inset-bottom));
      padding: 0.14rem max(0.24rem, env(safe-area-inset-right)) calc(0.16rem + env(safe-area-inset-bottom)) max(0.24rem, env(safe-area-inset-left));
      box-shadow: 0 -0.7rem 1.8rem rgba(132, 139, 200, 0.13);
    }

    ion-tab-button,
    .smart-tab-button {
      --color: var(--otziv-info);
      --color-selected: var(--otziv-primary);
      --padding-end: 0;
      --padding-start: 0;
      --ripple-color: var(--otziv-primary);
      display: flex;
      align-items: center;
      justify-content: center;
      flex: 1 1 0;
      flex-direction: column;
      height: 100%;
      min-height: 0;
      min-width: 0;
      border: 0;
      border-radius: 0.7rem;
      color: var(--otziv-info);
      background: transparent;
      font: inherit;
      font-weight: 800;
      letter-spacing: 0;
      touch-action: manipulation;
      user-select: none;
    }

    ion-tab-button.tab-selected,
    .smart-tab-button.tab-selected {
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }

    ion-icon {
      font-size: 1.03rem;
      pointer-events: none;
    }

    ion-label,
    .tab-label {
      width: 100%;
      max-width: 100%;
      margin-top: 0.08rem;
      overflow: hidden;
      font-size: clamp(0.47rem, 1.95vw, 0.52rem);
      line-height: 1;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    @media (max-width: 360px) {
      .otziv-tabbar {
        padding-inline: 0.12rem;
      }

      ion-label,
      .tab-label {
        font-size: 0.46rem;
      }
    }
  `]
})
export class TabsPage {
  private readonly smartTabRoutes: Record<SmartTab, string> = {
    home: '/tabs/home',
    leads: '/tabs/leads',
    companies: '/tabs/companies',
    orders: '/tabs/orders',
    worker: '/tabs/worker'
  };
  private holdTimer: ReturnType<typeof setTimeout> | null = null;
  private holdTriggered = false;
  private lastTapTab: SmartTab | null = null;
  private lastTapTime = 0;
  private suppressNextClick = false;

  constructor(readonly auth: AuthService, private readonly router: Router) {
    addIcons({
      businessOutline,
      callOutline,
      gridOutline,
      homeOutline,
      readerOutline,
      receiptOutline
    });
  }

  startSmartTabHold(tab: SmartTab): void {
    this.clearSmartTabHold();
    this.holdTriggered = false;
    this.holdTimer = setTimeout(() => {
      this.holdTriggered = true;
      this.suppressNextClick = true;
      this.lastTapTab = null;
      void this.openSmartTab(tab, 'menu');
    }, SMART_TAB_HOLD_MS);
  }

  clearSmartTabHold(): void {
    if (this.holdTimer) {
      clearTimeout(this.holdTimer);
      this.holdTimer = null;
    }
  }

  handleSmartTabClick(event: Event, tab: SmartTab): void {
    event.preventDefault();
    event.stopPropagation();
    this.clearSmartTabHold();

    if (this.holdTriggered || this.suppressNextClick) {
      this.holdTriggered = false;
      this.suppressNextClick = false;
      return;
    }

    const now = Date.now();
    if (this.lastTapTab === tab && now - this.lastTapTime < SMART_TAB_DOUBLE_TAP_MS) {
      this.lastTapTab = null;
      this.lastTapTime = 0;
      void this.openSmartTab(tab, 'menu');
      return;
    }

    this.lastTapTab = tab;
    this.lastTapTime = now;
    void this.openSmartTab(tab, 'all');
  }

  openSmartTabMenu(event: Event, tab: SmartTab): void {
    event.preventDefault();
    event.stopPropagation();
    this.clearSmartTabHold();
    this.holdTriggered = false;
    this.suppressNextClick = false;
    this.lastTapTab = null;
    this.lastTapTime = 0;
    void this.openSmartTab(tab, 'menu');
  }

  private openSmartTab(tab: SmartTab, mode: 'all' | 'menu'): Promise<boolean> {
    const route = tab === 'home' && mode === 'all'
      ? this.defaultHomeRoute()
      : this.smartTabRoutes[tab];
    return this.router.navigateByUrl(`${route}?mobileNav=${mode}&navTs=${Date.now()}`);
  }

  isSmartTabSelected(tab: SmartTab): boolean {
    const current = this.router.url.split('?')[0];
    const route = this.smartTabRoutes[tab];
    if (tab === 'home') {
      return current === route || current.startsWith(`${route}/`) || current === '/tabs/profile';
    }
    if (tab === 'companies' && current === '/tabs/archive') {
      return true;
    }
    return current === route || (tab === 'orders' && current.startsWith(`${route}/`));
  }

  private defaultHomeRoute(): string {
    return this.auth.hasAnyRealmRole(MOBILE_ROLES.ownerAdmin)
      ? '/tabs/home/analytics'
      : '/tabs/home/profile';
  }

  canManager(): boolean {
    return canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.companies, MOBILE_ACTIONS.view);
  }

  canWorker(): boolean {
    return canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.worker, MOBILE_ACTIONS.view);
  }

  canLeads(): boolean {
    return canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.leads, MOBILE_ACTIONS.view);
  }

  canOperator(): boolean {
    return canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.operator, MOBILE_ACTIONS.view);
  }
}
