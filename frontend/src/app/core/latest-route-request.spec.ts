import { Observable, Subject, of } from 'rxjs';
import { LatestRouteRequest } from './latest-route-request';

describe('LatestRouteRequest', () => {
  it('unsubscribes the previous read and delivers only the latest response', () => {
    const first = new Subject<string>();
    const second = new Subject<string>();
    const firstTeardown = vi.fn();
    const secondTeardown = vi.fn();
    const values: string[] = [];
    const request = new LatestRouteRequest<string>();

    request.start(withTeardown(first, firstTeardown), { next: (value) => values.push(value) });
    request.start(withTeardown(second, secondTeardown), { next: (value) => values.push(value) });

    first.next('stale');
    second.next('latest');

    expect(firstTeardown).toHaveBeenCalledTimes(1);
    expect(secondTeardown).not.toHaveBeenCalled();
    expect(values).toEqual(['latest']);

    request.cancel();
    expect(secondTeardown).toHaveBeenCalledTimes(1);
  });

  it('rejects stale synchronous notifications after a re-entrant replacement', () => {
    const values: string[] = [];
    const request = new LatestRouteRequest<string>();
    const source = new Observable<string>((subscriber) => {
      subscriber.next('replace');
      subscriber.next('stale-after-replacement');
      subscriber.complete();
    });

    request.start(source, {
      next: (value) => {
        values.push(value);
        if (value === 'replace') {
          request.start(of('latest'), { next: (latest) => values.push(latest) });
        }
      }
    });

    expect(values).toEqual(['replace', 'latest']);
  });

  it('does not own or cancel an independent mutation subscription', () => {
    const read = new Subject<void>();
    const mutation = new Subject<void>();
    const mutationTeardown = vi.fn();
    const request = new LatestRouteRequest<void>();
    const mutationSubscription = withTeardown(mutation, mutationTeardown).subscribe();

    request.start(read, {});
    request.cancel();

    expect(mutationTeardown).not.toHaveBeenCalled();

    mutationSubscription.unsubscribe();
    expect(mutationTeardown).toHaveBeenCalledTimes(1);
  });
});

function withTeardown<T>(source: Observable<T>, teardown: () => void): Observable<T> {
  return new Observable<T>((subscriber) => {
    const subscription = source.subscribe(subscriber);
    return () => {
      subscription.unsubscribe();
      teardown();
    };
  });
}
