import {
  ManualPaymentTaskOperationKeyDraft,
  newManualPaymentTaskOperationKey
} from './manual-payment-operation-key';

describe('manual payment task create operation key', () => {
  it('keeps the exact key until a confirmed success or explicit draft reset rotates it', () => {
    const keys = ['draft-one', 'draft-two'];
    const draft = new ManualPaymentTaskOperationKeyDraft(() => keys.shift()!);

    expect(draft.current()).toBe('draft-one');
    expect(draft.current()).toBe('draft-one');
    expect(draft.rotate()).toBe('draft-two');
    expect(draft.current()).toBe('draft-two');
  });

  it('creates a non-empty backend-safe key', () => {
    const key = newManualPaymentTaskOperationKey();

    expect(key.trim()).toBe(key);
    expect(key.length).toBeGreaterThan(0);
    expect(key.length).toBeLessThanOrEqual(160);
  });
});
