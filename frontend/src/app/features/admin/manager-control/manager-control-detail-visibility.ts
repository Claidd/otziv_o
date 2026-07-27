import { ManagerControlItemDetail } from '../../../core/manager-control.api';

export function shouldShowManagerControlDetailItem(item: ManagerControlItemDetail): boolean {
  if (item.group === 'ACTION') {
    return (item.itemStatus === 'OPEN' && item.count > 0) || item.examples.length > 0;
  }
  return item.itemStatus !== 'OPEN' || !!item.comment || item.examples.some((example) =>
    !!example.comment || !!example.actionType || (!!example.itemStatus && example.itemStatus !== 'OPEN')
  );
}

export function managerControlDetailVisibleCount(item: ManagerControlItemDetail): number {
  if (item.group === 'ACTION' && item.itemStatus === 'OPEN') {
    return item.count;
  }
  return item.group === 'ACTION' ? item.examples.length : item.count;
}

export function managerControlDetailItemStatusCount(
  item: ManagerControlItemDetail,
  open: boolean
): number {
  if (item.group !== 'ACTION') {
    return open === (item.itemStatus === 'OPEN') ? item.count : 0;
  }
  if (item.itemStatus === 'OPEN') {
    if (open) {
      return item.count;
    }
    return item.examples.filter((example) => {
      const status = example.itemStatus ?? item.itemStatus;
      return status !== 'OPEN';
    }).length;
  }
  return open ? 0 : item.examples.length;
}
