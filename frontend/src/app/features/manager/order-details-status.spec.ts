import {
  ORDER_STATUS_IN_CHECK,
  ORDER_STATUS_TO_CHECK,
  orderDetailsSendToCheckActionLabel,
  orderDetailsSendToCheckBusyLabel,
  orderDetailsSendToCheckSuccessDetail,
  orderDetailsSendToCheckTargetStatus
} from './order-details-status';

describe('order details status helpers', () => {
  it('uses automatic client send for orders that are not already waiting for manual check confirmation', () => {
    expect(orderDetailsSendToCheckTargetStatus('Новый', false)).toBe(ORDER_STATUS_TO_CHECK);
    expect(orderDetailsSendToCheckActionLabel(ORDER_STATUS_TO_CHECK)).toBe('Отправить на проверку');
    expect(orderDetailsSendToCheckBusyLabel(ORDER_STATUS_TO_CHECK)).toBe('Отправляю...');
    expect(orderDetailsSendToCheckSuccessDetail(ORDER_STATUS_TO_CHECK)).toContain('"В проверку"');
  });

  it('marks manager orders in check manually without sending to client chat again', () => {
    expect(orderDetailsSendToCheckTargetStatus(' В   проверку ', false)).toBe(ORDER_STATUS_IN_CHECK);
    expect(orderDetailsSendToCheckTargetStatus('В прверку', false)).toBe(ORDER_STATUS_IN_CHECK);
    expect(orderDetailsSendToCheckActionLabel(ORDER_STATUS_IN_CHECK)).toBe('Отметить на проверке');
    expect(orderDetailsSendToCheckBusyLabel(ORDER_STATUS_IN_CHECK)).toBe('Отмечаю...');
    expect(orderDetailsSendToCheckSuccessDetail(ORDER_STATUS_IN_CHECK)).toContain('без повторной отправки');
  });

  it('keeps worker-only users on the automatic to-check transition', () => {
    expect(orderDetailsSendToCheckTargetStatus('В проверку', true)).toBe(ORDER_STATUS_TO_CHECK);
  });
});
