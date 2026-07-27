import { describe, expect, it } from 'vitest';
import { ManagerControlItemDetail } from '../../../core/manager-control.api';
import {
  managerControlDetailItemStatusCount,
  managerControlDetailVisibleCount,
  shouldShowManagerControlDetailItem
} from './manager-control-detail-visibility';

function detailItem(overrides: Partial<ManagerControlItemDetail>): ManagerControlItemDetail {
  return {
    itemId: 1,
    itemKey: 'problem:WORKER_ACTIONS',
    itemType: 'PROBLEM',
    reasonCode: 'WORKER_ACTIONS',
    reasonLabel: 'Есть задачи специалистов, которые надо разобрать',
    label: 'Задачи специалистов',
    targetUrl: '/admin/manager-control/1',
    count: 8,
    severity: 'CRITICAL',
    group: 'ACTION',
    itemStatus: 'OPEN',
    examples: [],
    hiddenExampleCount: 8,
    createdAt: '2026-07-27T10:00:00',
    updatedAt: '2026-07-27T12:00:00',
    ...overrides
  };
}

describe('shouldShowManagerControlDetailItem', () => {
  it('shows WORKER_ACTIONS included in the current-action counter', () => {
    const item = detailItem({});

    expect(shouldShowManagerControlDetailItem(item)).toBe(true);
    expect(managerControlDetailVisibleCount(item)).toBe(8);
    expect(managerControlDetailItemStatusCount(item, true)).toBe(8);
  });

  it('shows any counted open action while concrete cards are still synchronizing', () => {
    expect(shouldShowManagerControlDetailItem(detailItem({
      reasonCode: 'OVERDUE_ORDERS',
      itemKey: 'problem:OVERDUE_ORDERS',
      count: 4,
      hiddenExampleCount: 4
    }))).toBe(true);
  });

  it('keeps the detail open count equal to the 12 actions shown in the summary', () => {
    const items = [
      detailItem({ count: 8, hiddenExampleCount: 8 }),
      detailItem({
        itemId: 2,
        reasonCode: 'OVERDUE_ORDERS',
        itemKey: 'problem:OVERDUE_ORDERS',
        count: 4,
        hiddenExampleCount: 4
      })
    ];

    const visibleOpenCount = items
      .filter(shouldShowManagerControlDetailItem)
      .reduce((total, item) => total + managerControlDetailItemStatusCount(item, true), 0);

    expect(visibleOpenCount).toBe(12);
  });

  it('does not show an empty open action', () => {
    expect(shouldShowManagerControlDetailItem(detailItem({
      count: 0,
      hiddenExampleCount: 0
    }))).toBe(false);
  });
});
