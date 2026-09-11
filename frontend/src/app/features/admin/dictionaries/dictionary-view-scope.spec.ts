import { signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { DictionaryViewScope } from './dictionary-view-scope';

describe('dictionary view lifetime', () => {
  it('cancels superseded/hidden GETs and clears loading', () => {
    let active = true;
    const scope = new DictionaryViewScope(() => active);
    const busy = signal(false);
    const first = new Subject<number>();
    const second = new Subject<number>();
    const apply = vi.fn();
    scope.read('list', first, busy).subscribe(apply);
    scope.read('list', second, busy).subscribe(apply);
    expect(first.observed).toBe(false);
    expect(second.observed).toBe(true);
    expect(busy()).toBe(true);
    active = false;
    scope.deactivate();
    expect(second.observed).toBe(false);
    expect(busy()).toBe(false);
    second.next(2);
    expect(apply).not.toHaveBeenCalled();
  });

  it('does not abort or replay a submitted command on hide/destroy and suppresses obsolete completion', () => {
    const scope = new DictionaryViewScope(() => true);
    const busy = signal(true);
    const transport = new Subject<number>();
    const unsubscribe = vi.fn();
    let calls = 0;
    const request = new Observable<number>(subscriber => {
      calls++;
      const sub = transport.subscribe(subscriber);
      return () => { unsubscribe(); sub.unsubscribe(); };
    });
    const next = vi.fn(), complete = vi.fn();
    scope.write(request, busy).subscribe({ next, complete });
    scope.destroy();
    expect(unsubscribe).not.toHaveBeenCalled();
    expect(transport.observed).toBe(true);
    transport.next(9);
    transport.complete();
    expect(calls).toBe(1);
    expect(busy()).toBe(false);
    expect(scope.writing()).toBe(false);
    expect(next).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
    expect(unsubscribe).toHaveBeenCalledOnce();
  });

  it('cancels pre-command reads so they cannot overwrite the submitted form', () => {
    const scope = new DictionaryViewScope(() => true);
    const read = new Subject<number>();
    const write = new Subject<number>();
    scope.read('settings', read).subscribe();
    scope.write(write, signal(true)).subscribe({ error: () => undefined });
    expect(read.observed).toBe(false);
    expect(write.observed).toBe(true);
    write.error(new Error('unknown outcome'));
  });

  it('never subscribes a hidden feature read', () => {
    const scope = new DictionaryViewScope(() => false);
    const subscribe = vi.fn();
    scope.read('list', new Observable(subscribe)).subscribe();
    expect(subscribe).not.toHaveBeenCalled();
  });

  for (const reenter of [false, true]) {
    it(`cancels reads started during a write before acknowledging it${reenter ? ' after hidden reentry' : ''}`, () => {
      let active = true;
      const scope = new DictionaryViewScope(() => active);
      const write = new Subject<number>();
      const obsolete = new Subject<number>();
      const fresh = new Subject<number>();
      const state = signal(0);
      const busy = signal(true);
      scope.write(write, busy).subscribe(value => {
        state.set(value);
        scope.read('settings', fresh).subscribe(value => state.set(value));
      });
      if (reenter) {
        active = false; scope.deactivate(); active = true;
      }
      scope.read('settings', obsolete).subscribe(value => state.set(value));
      expect(obsolete.observed).toBe(true);
      write.next(2);
      expect(obsolete.observed).toBe(false);
      obsolete.next(1);
      expect(state()).toBe(reenter ? 0 : 2);
      if (!reenter) {
        expect(fresh.observed).toBe(true);
        fresh.next(3);
        expect(state()).toBe(3);
      }
      write.complete();
      expect(busy()).toBe(false);
      scope.destroy();
    });
  }
});
