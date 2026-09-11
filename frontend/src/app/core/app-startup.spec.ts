import { TestBed } from '@angular/core/testing';
import { NavigationEnd, NavigationError, NavigationStart, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { reportAppStartupError, watchAppStartup } from './app-startup';

describe('initial navigation loading screen', () => {
  let events: Subject<NavigationStart | NavigationEnd | NavigationError>;
  let ready: ReturnType<typeof vi.fn<() => void>>;
  let failed: ReturnType<typeof vi.fn<() => void>>;
  beforeEach(() => {
    events = new Subject();
    ready = vi.fn();
    failed = vi.fn();
    window.addEventListener('otziv:startup-ready', ready);
    window.addEventListener('otziv:startup-error', failed);
    TestBed.configureTestingModule({ providers: [{ provide: Router, useValue: { events } }] });
    TestBed.runInInjectionContext(watchAppStartup);
  });
  afterEach(() => {
    window.removeEventListener('otziv:startup-ready', ready);
    window.removeEventListener('otziv:startup-error', failed);
  });
  it('waits for the lazy route and only dismisses the screen once', () => {
    events.next(new NavigationStart(1, '/pay/token'));
    expect(ready).not.toHaveBeenCalled();
    events.next(new NavigationEnd(1, '/pay/token', '/pay/token'));
    expect(ready).toHaveBeenCalledTimes(1);
    events.next(new NavigationError(2, '/offer', new Error('later navigation')));
    expect(failed).not.toHaveBeenCalled();
  });
  it('shows a recoverable error if the first route cannot load', () => {
    events.next(new NavigationError(1, '/pay/token', new Error('missing chunk')));
    expect(failed).toHaveBeenCalledTimes(1);
    expect(ready).not.toHaveBeenCalled();
  });
  it('reports an application bootstrap failure', () => {
    reportAppStartupError();
    expect(failed).toHaveBeenCalledTimes(1);
  });
});
