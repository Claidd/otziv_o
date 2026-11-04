import { VisibilityAwarePoller, backoffDelayMs } from './visibility-aware-poller';

describe('VisibilityAwarePoller', () => {
  let visibility: DocumentVisibilityState;

  beforeEach(() => {
    vi.useFakeTimers();
    visibility = 'visible';
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility });
  });

  afterEach(() => {
    delete (document as unknown as Record<string, unknown>)['visibilityState'];
    vi.useRealTimers();
  });

  function setVisibility(state: DocumentVisibilityState): void {
    visibility = state;
    document.dispatchEvent(new Event('visibilitychange'));
  }

  it('re-reads the delay before every run', () => {
    const delays = [1000, 5000];
    const tick = vi.fn();
    const poller = new VisibilityAwarePoller(tick, () => delays.shift() ?? 60_000);

    poller.start();
    vi.advanceTimersByTime(999);
    expect(tick).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(tick).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(4999);
    expect(tick).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(1);
    expect(tick).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('requests nothing while the page is hidden and refreshes once when it is shown again', () => {
    const tick = vi.fn();
    const poller = new VisibilityAwarePoller(tick, () => 3000);
    poller.start();

    setVisibility('hidden');
    vi.advanceTimersByTime(60_000);
    expect(tick).not.toHaveBeenCalled();

    setVisibility('visible');
    expect(tick).toHaveBeenCalledTimes(1);
    setVisibility('visible');
    expect(tick).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(3000);
    expect(tick).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('stops for good when stopped or when no further delay is wanted', () => {
    const tick = vi.fn();
    let stopAfterFirst = false;
    const poller = new VisibilityAwarePoller(() => { tick(); stopAfterFirst = true; }, () => (stopAfterFirst ? null : 1000));
    poller.start();

    vi.advanceTimersByTime(1000);
    expect(tick).toHaveBeenCalledTimes(1);
    expect(poller.running).toBe(false);
    vi.advanceTimersByTime(60_000);
    setVisibility('hidden');
    setVisibility('visible');
    expect(tick).toHaveBeenCalledTimes(1);
  });

  it('restarts the countdown on reschedule', () => {
    const tick = vi.fn();
    const poller = new VisibilityAwarePoller(tick, () => 10_000);
    poller.start();
    vi.advanceTimersByTime(9000);
    poller.reschedule();
    vi.advanceTimersByTime(9000);
    expect(tick).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1000);
    expect(tick).toHaveBeenCalledTimes(1);
    poller.stop();
  });

  it('doubles the delay per failure up to the cap', () => {
    expect(backoffDelayMs(3000, 0, 60_000)).toBe(3000);
    expect(backoffDelayMs(3000, 1, 60_000)).toBe(6000);
    expect(backoffDelayMs(3000, 4, 60_000)).toBe(48_000);
    expect(backoffDelayMs(3000, 5, 60_000)).toBe(60_000);
    expect(backoffDelayMs(3000, 500, 60_000)).toBe(60_000);
  });
});
