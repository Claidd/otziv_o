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
  it('shows an action only when it has a concrete card', () => {
    const item = detailItem({
      examples: [{
        type: 'ORDER',
        entityId: 22840,
        title: 'Компания',
        subtitle: 'Заказ',
        reason: 'Нужно действие',
        targetUrl: '/admin/orders/22840',
        itemStatus: 'OPEN'
      }]
    });

    expect(shouldShowManagerControlDetailItem(item)).toBe(true);
    expect(managerControlDetailVisibleCount(item)).toBe(1);
    expect(managerControlDetailItemStatusCount(item, true)).toBe(1);
  });

  it('hides a counted action when it has no concrete cards', () => {
    expect(shouldShowManagerControlDetailItem(detailItem({
      reasonCode: 'OVERDUE_ORDERS',
      itemKey: 'problem:OVERDUE_ORDERS',
      count: 4,
      hiddenExampleCount: 4
    }))).toBe(false);
  });

  it('hides the technical ORDER_STATUS breakdown even if it contains a card', () => {
    expect(shouldShowManagerControlDetailItem(detailItem({
      itemType: 'ORDER_STATUS',
      reasonCode: 'На проверке',
      examples: [{
        type: 'ORDER',
        entityId: 22841,
        title: 'Компания',
        subtitle: 'Заказ',
        reason: 'Просрочен',
        targetUrl: '/admin/orders/22841',
        itemStatus: 'OPEN'
      }]
    }))).toBe(false);
  });

  it('counts open and handled concrete cards instead of the stale parent count', () => {
    const item = detailItem({
      count: 8,
      examples: [
        {
          type: 'ORDER',
          entityId: 22840,
          title: 'Первая',
          subtitle: 'Заказ',
          reason: 'Нужно действие',
          targetUrl: '/admin/orders/22840',
          itemStatus: 'OPEN'
        },
        {
          type: 'ORDER',
          entityId: 22839,
          title: 'Вторая',
          subtitle: 'Заказ',
          reason: 'Обработан',
          targetUrl: '/admin/orders/22839',
          itemStatus: 'ACTION_TAKEN'
        }
      ]
    });

    expect(managerControlDetailVisibleCount(item)).toBe(2);
    expect(managerControlDetailItemStatusCount(item, true)).toBe(1);
    expect(managerControlDetailItemStatusCount(item, false)).toBe(1);
  });
});
