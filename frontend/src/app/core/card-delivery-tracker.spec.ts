import { CardDeliveryTracker, managerCardDelivery, deliveryOperationMessage, type DeliveryOperation } from '@otziv/client-common/delivery-operations';

describe('manager card delivery after navigation', () => {
  let tracker: CardDeliveryTracker;
  const id = '00000000-0000-0000-0000-000000000041';
  const operation = (status: string, operationId = id): DeliveryOperation => ({ operationId, status, attempts: 1, errorCode: null });
  const settle = async () => { for (let n = 0; n < 5; n++) await Promise.resolve(); };
  beforeEach(() => { vi.useFakeTimers(); tracker = new CardDeliveryTracker(); });
  afterEach(() => { tracker.cancelAll(); vi.useRealTimers(); });

  it('restores a pending card from its persisted marker and reads until confirmed', async () => {
    const initial = managerCardDelivery({ comment: `client_reply_delivery_prepared:${id}` })!;
    expect(initial.status).toBe('UNKNOWN');
    const accept = vi.fn();
    const read = vi.fn().mockResolvedValueOnce(operation('QUEUED')).mockResolvedValue(operation('SENT'));
    tracker.track(41, initial, read, accept, () => true);
    await settle();
    expect(accept).toHaveBeenLastCalledWith(operation('QUEUED'));
    await vi.advanceTimersByTimeAsync(2000);
    expect(accept).toHaveBeenLastCalledWith(operation('SENT'));
    await vi.advanceTimersByTimeAsync(600000);
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('does one receipt lookup for UNKNOWN without polling or sending again', async () => {
    const read = vi.fn().mockResolvedValue(operation('UNKNOWN'));
    tracker.track(41, operation('UNKNOWN'), read, vi.fn(), () => true);
    await vi.advanceTimersByTimeAsync(600000);
    expect(read).toHaveBeenCalledTimes(1);
  });

  it.each(['navigation', 'replacement', 'inactive page'])('ignores a stale in-flight receipt after %s', async reason => {
    let finish!: (value: DeliveryOperation) => void;
    let active = true;
    const accept = vi.fn();
    tracker.track(41, operation('QUEUED'), () => new Promise(resolve => { finish = resolve; }), accept, () => active);
    if (reason === 'navigation') tracker.cancelAll();
    else if (reason === 'replacement') tracker.track(41, operation('QUEUED', 'new'), async () => operation('UNKNOWN', 'new'), accept, () => true);
    else active = false;
    finish(operation('SENT'));
    await settle();
    expect(accept.mock.calls.some(([value]) => value.status === 'SENT')).toBe(false);
  });

  it('preserves pending state after a failed read or a receipt for another operation', async () => {
    const accept = vi.fn();
    const read = vi.fn().mockRejectedValue(new Error('offline'));
    tracker.track(41, operation('QUEUED'), read, accept, () => true);
    await settle();
    tracker.track(42, operation('QUEUED'), async () => operation('SENT', 'different'), accept, () => true);
    await vi.advanceTimersByTimeAsync(600000);
    expect(read).toHaveBeenCalledTimes(1);
    expect(accept.mock.calls.every(([value]) => value.status === 'QUEUED')).toBe(true);
  });

  it('keeps independent cards active when another card is cancelled', async () => {
    const first = vi.fn().mockResolvedValue(operation('QUEUED'));
    const second = vi.fn().mockResolvedValue(operation('QUEUED', 'second'));
    tracker.track(41, operation('QUEUED'), first, vi.fn(), () => true);
    tracker.track(42, operation('QUEUED', 'second'), second, vi.fn(), () => true);
    await settle();
    tracker.cancel(41);
    await vi.advanceTimersByTimeAsync(2000);
    expect(first).toHaveBeenCalledTimes(1);
    expect(second).toHaveBeenCalledTimes(2);
  });

  it('does not claim that a confirmed message also closed a card needing reconciliation', () => {
    expect(deliveryOperationMessage({ ...operation('SENT'), errorCode: 'finalization_required' })).toContain('Карточка требует сверки');
    expect(managerCardDelivery({ comment: 'ordinary user comment' })).toBeNull();
  });
});
