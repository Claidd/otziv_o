import { TestBed } from '@angular/core/testing';
import { APP_PRIMARY_NAVIGATION } from '../app-navigation';
import {
  MOBILE_NAV_DOUBLE_TAP_MS,
  MOBILE_NAV_HOLD_CLICK_SUPPRESS_MS,
  MOBILE_NAV_HOLD_MS,
  MobileBottomNavComponent,
  type MobileBottomNavRequest
} from './mobile-bottom-nav.component';
import { MobileNavIntentService } from './mobile-nav-intent.service';

function click(detail = 1): MouseEvent {
  return new MouseEvent('click', { bubbles: true, cancelable: true, detail });
}

describe('MobileBottomNavComponent gestures', () => {
  let component: MobileBottomNavComponent;
  let navIntents: MobileNavIntentService;
  let requests: MobileBottomNavRequest[];

  beforeEach(() => {
    vi.useFakeTimers();
    navIntents = new MobileNavIntentService();
    component = new MobileBottomNavComponent(navIntents);
    requests = [];
    component.navigate.subscribe((request) => requests.push(request));
  });

  afterEach(() => {
    component.ngOnDestroy();
    vi.useRealTimers();
  });

  it('turns one tap into one immediate normal navigation', () => {
    component.handleClick(click(), 'orders');
    expect(requests).toEqual([{ tab: 'orders', mode: 'all' }]);
  });

  it('turns a second tap at 400ms into submenu after immediate navigation', () => {
    component.handleClick(click(), 'companies');
    vi.advanceTimersByTime(400);
    component.handleClick(click(), 'companies');

    expect(400).toBeLessThan(MOBILE_NAV_DOUBLE_TAP_MS);
    expect(requests).toEqual([
      { tab: 'companies', mode: 'all' },
      { tab: 'companies', mode: 'menu' }
    ]);
  });

  it('keeps double-tap memory when AdminLayout recreates the bottom nav', () => {
    component.handleClick(click(), 'leads');
    vi.advanceTimersByTime(400);

    const recreated = new MobileBottomNavComponent(navIntents);
    recreated.navigate.subscribe((request) => requests.push(request));
    recreated.handleClick(click(), 'leads');

    expect(requests).toEqual([
      { tab: 'leads', mode: 'all' },
      { tab: 'leads', mode: 'menu' }
    ]);
    recreated.ngOnDestroy();
  });

  it('opens submenu after a 700ms hold and suppresses its following click', () => {
    component.startHold('worker');
    vi.advanceTimersByTime(MOBILE_NAV_HOLD_MS);

    expect(requests).toEqual([{ tab: 'worker', mode: 'menu' }]);

    component.cancelHold();
    component.handleClick(click(), 'worker');

    expect(requests).toEqual([{ tab: 'worker', mode: 'menu' }]);
  });

  it('keeps hold click suppression when navigation recreates the bottom nav', () => {
    component.startHold('orders');
    vi.advanceTimersByTime(MOBILE_NAV_HOLD_MS);

    const recreated = new MobileBottomNavComponent(navIntents);
    recreated.navigate.subscribe((request) => requests.push(request));
    recreated.handleClick(click(), 'orders');

    expect(requests).toEqual([{ tab: 'orders', mode: 'menu' }]);

    vi.advanceTimersByTime(MOBILE_NAV_HOLD_CLICK_SUPPRESS_MS + 1);
    recreated.handleClick(click(), 'orders');
    expect(requests).toEqual([
      { tab: 'orders', mode: 'menu' },
      { tab: 'orders', mode: 'all' }
    ]);
    recreated.ngOnDestroy();
  });

  it('cancels a pointer hold without emitting and leaves the next tap usable', () => {
    component.startHold('leads');
    vi.advanceTimersByTime(MOBILE_NAV_HOLD_MS - 1);
    component.cancelPointerGesture();
    vi.advanceTimersByTime(2);

    expect(requests).toEqual([]);

    component.handleClick(click(), 'leads');
    expect(requests).toEqual([{ tab: 'leads', mode: 'all' }]);
  });

  it('navigates immediately for keyboard activation', () => {
    component.handleClick(click(0), 'home');
    expect(requests).toEqual([{ tab: 'home', mode: 'all' }]);
  });
});

describe('MobileBottomNavComponent visual cascade', () => {
  it('keeps inactive tabs transparent inside the legacy admin layout', async () => {
    await TestBed.configureTestingModule({ imports: [MobileBottomNavComponent] }).compileComponents();
    const fixture = TestBed.createComponent(MobileBottomNavComponent);
    fixture.nativeElement.classList.add('admin-layout', 'mobile-viewport');
    fixture.componentInstance.items = APP_PRIMARY_NAVIGATION.slice(0, 2);
    fixture.componentInstance.activeTab = 'home';
    fixture.detectChanges();

    const buttons = fixture.nativeElement.querySelectorAll('.mobile-nav-item') as NodeListOf<HTMLButtonElement>;
    const inactiveStyle = getComputedStyle(buttons[1]);

    expect(inactiveStyle.display).toBe('flex');
    expect(inactiveStyle.paddingLeft).toBe('0px');
    expect(inactiveStyle.backgroundColor).not.toBe('rgb(108, 155, 207)');
    expect(inactiveStyle.color).not.toBe('rgb(255, 255, 255)');

    fixture.destroy();
  });
});
