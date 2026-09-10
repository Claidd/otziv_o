import { DeliveryStatusWatcher, type DeliveryOperation } from '@otziv/client-common/billing-payments';

describe('durable delivery status reads', () => {
  let watcher: DeliveryStatusWatcher;
  const value = (status: string, id = 'invoice-message:1') => ({
    delivery: { operationId: id, status, attempts: 1, errorCode: null } satisfies DeliveryOperation
  });
  beforeEach(() => { vi.useFakeTimers(); watcher = new DeliveryStatusWatcher(); });
  afterEach(() => { watcher.cancel(); vi.useRealTimers(); });

  it('reads until confirmation and never treats queued as sent', async () => {
    const read = vi.fn().mockResolvedValueOnce(value('SENDING')).mockResolvedValue(value('SENT'));
    const accept = vi.fn();
    watcher.watch(value('QUEUED'), read, accept, () => true);
    expect(accept).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(2000);
    expect(accept).toHaveBeenLastCalledWith(value('SENDING'));
    await vi.advanceTimersByTimeAsync(3000);
    expect(accept).toHaveBeenLastCalledWith(value('SENT'));
    await vi.advanceTimersByTimeAsync(60000);
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('discards an in-flight read after navigation or a newer operation', async () => {
    let finish!: (updated: ReturnType<typeof value>) => void;
    const accept = vi.fn();
    watcher.watch(value('QUEUED'), () => new Promise(resolve => { finish = resolve; }), accept, () => true);
    await vi.advanceTimersByTimeAsync(2000);
    watcher.cancel();
    finish(value('SENT'));
    await Promise.resolve();
    expect(accept).not.toHaveBeenCalled();
  });

  it('does not replace a newer invoice operation with an unrelated receipt', async () => {
    const accept = vi.fn();
    watcher.watch(value('QUEUED'), async () => value('SENT', 'invoice-message:2'), accept, () => true);
    await vi.advanceTimersByTimeAsync(60000);
    expect(accept).not.toHaveBeenCalled();
  });

  it.each(['UNKNOWN', 'FAILED', 'SENT', 'FUTURE'])('does not automatically retry %s', async status => {
    const read = vi.fn();
    watcher.watch(value(status), read, vi.fn(), () => true);
    await vi.advanceTimersByTimeAsync(60000);
    expect(read).not.toHaveBeenCalled();
  });

  it('a failed read preserves the last acknowledged state', async () => {
    const accept = vi.fn();
    const read = vi.fn().mockRejectedValue(new Error('offline'));
    watcher.watch(value('QUEUED'), read, accept, () => true);
    await vi.advanceTimersByTimeAsync(60000);
    expect(read).toHaveBeenCalledTimes(1);
    expect(accept).not.toHaveBeenCalled();
  });

  it('bounds polling when a provider stays unavailable', async () => {
    const read = vi.fn().mockResolvedValue(value('RETRYABLE'));
    watcher.watch(value('QUEUED'), read, vi.fn(), () => true);
    await vi.advanceTimersByTimeAsync(600000);
    expect(read).toHaveBeenCalledTimes(60);
  });
});
