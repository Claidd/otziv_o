import { RouteEpoch } from './route-epoch';

describe('RouteEpoch', () => {
  it('rejects stale and ABA route visits', () => {
    const epoch = new RouteEpoch();
    epoch.change('pay:A');
    const abandoned = epoch.capture();
    expect(abandoned).not.toBeNull();

    epoch.change('pay:B');
    epoch.change('pay:A');

    expect(epoch.accepts(abandoned!)).toBe(false);
    expect(epoch.accepts(epoch.capture()!)).toBe(true);
  });

  it('keeps duplicate route emissions in the same epoch and rejects after destroy', () => {
    const epoch = new RouteEpoch();
    expect(epoch.change('same')).toBe(true);
    const current = epoch.capture()!;
    expect(epoch.change('same')).toBe(false);
    expect(epoch.accepts(current)).toBe(true);

    epoch.destroy();
    expect(epoch.accepts(current)).toBe(false);
    expect(epoch.capture()).toBeNull();
  });
});
