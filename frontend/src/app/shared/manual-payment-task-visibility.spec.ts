import { describe, expect, it } from 'vitest';
import { manualPaymentTaskWorklist } from './manual-payment-task-visibility';

describe('manual payment task worklist', () => {
  it('hides only canceled tasks without mutating the source list', () => {
    const tasks = [
      { id: 1, status: 'ACTIVE' },
      { id: 2, status: 'PAUSED' },
      { id: 3, status: 'COMPLETED' },
      { id: 4, status: 'NEEDS_ATTENTION' },
      { id: 5, status: 'CANCELED' },
      { id: 6, status: 'FUTURE_STATUS' }
    ] as const;

    expect(manualPaymentTaskWorklist(tasks).map((task) => task.id)).toEqual([1, 2, 3, 4, 6]);
    expect(tasks.map((task) => task.id)).toEqual([1, 2, 3, 4, 5, 6]);
  });

  it('accepts an absent task list', () => {
    expect(manualPaymentTaskWorklist(undefined)).toEqual([]);
    expect(manualPaymentTaskWorklist(null)).toEqual([]);
  });
});
