import { ManagerControlItemDetail } from '../../../core/manager-control.api';

export function shouldShowManagerControlDetailItem(item: ManagerControlItemDetail): boolean {
  if (item.group === 'ACTION') {
    return item.itemType !== 'ORDER_STATUS' && item.examples.length > 0;
  }
  return item.itemStatus !== 'OPEN' || !!item.comment || item.examples.some((example) =>
    !!example.comment || !!example.actionType || (!!example.itemStatus && example.itemStatus !== 'OPEN')
  );
}

export function managerControlDetailVisibleCount(item: ManagerControlItemDetail): number {
  return item.group === 'ACTION' ? item.examples.length : item.count;
}

export function managerControlDetailItemStatusCount(
  item: ManagerControlItemDetail,
  open: boolean
): number {
  if (item.group !== 'ACTION') {
    return open === (item.itemStatus === 'OPEN') ? item.count : 0;
  }
  return item.examples.filter((example) => {
    const status = example.itemStatus ?? item.itemStatus;
    return open ? status === 'OPEN' : status !== 'OPEN';
  }).length;
}
