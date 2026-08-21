import { describe, expect, it } from 'vitest';
import {
  isRetryablePaymentRouteError,
  manualPaymentRecipientEffect,
  manualPaymentRecipientKey,
  manualPaymentRecipientLabel,
  manualPaymentRouteErrorCode,
  manualPaymentTaskOverrunAcknowledgementRequired
} from './manual-payment-routing';

describe('manual payment task routing', () => {
  const task = {
    key: 'TASK:16:3',
    cashDestinationKind: 'MANUAL_PAYMENT_TASK' as const,
    manualPaymentTaskId: 16,
    manualPaymentTaskGeneration: 3,
    taskRecipientName: 'Наталья',
    taskTargetKind: 'SPECIALIST' as const,
    accountingTargetLabel: 'Специалист · Наталья'
  };

  it('uses the frozen task key and keeps bank/accounting recipients visible', () => {
    expect(manualPaymentRecipientKey(task)).toBe('TASK:16:3');
    expect(manualPaymentRecipientLabel(task)).toBe(
      'Платёжное задание №16 · Наталья · учёт: Специалист · Наталья'
    );
    expect(manualPaymentRecipientEffect(task)).toMatch(/зачтена в платёжное задание/i);
    expect(manualPaymentRecipientEffect(task)).toMatch(/лимит и резерв обновятся/i);
  });

  it('does not substitute a contractor profile for an external task recipient', () => {
    const external = { ...task, taskTargetKind: 'EXTERNAL_TASK' as const, accountingTargetLabel: 'Только задание' };
    expect(manualPaymentRecipientEffect(external)).toMatch(/только в платёжное задание/i);
    expect(manualPaymentRecipientEffect(external)).toMatch(/не изменятся/i);
  });
  it('renders pre-cutover historical manual payment as a standalone settlement choice', () => {
    const historical = {
      key: 'LEGACY_PRE_CUTOVER_MANUAL_CARD',
      displayName: 'Историческая оплата до запуска',
      effectText: 'Заказ будет закрыт без нового учёта выплат.'
    };
    expect(manualPaymentRecipientKey(historical)).toBe('LEGACY_PRE_CUTOVER_MANUAL_CARD');
    expect(manualPaymentRecipientLabel(historical)).toBe('Историческая оплата до запуска');
    expect(manualPaymentRecipientEffect(historical)).toBe('Заказ будет закрыт без нового учёта выплат.');
  });

  it('recognises stable route errors and retries only refreshable conflicts', () => {
    expect(manualPaymentRouteErrorCode({ status: 409, error: { code: 'PAYMENT_ROUTE_STALE' } }))
      .toBe('PAYMENT_ROUTE_STALE');
    expect(manualPaymentRouteErrorCode({ error: { properties: { code: 'TASK_TARGET_UNRESOLVED' } } }))
      .toBe('TASK_TARGET_UNRESOLVED');
    expect(isRetryablePaymentRouteError({ error: { errorCode: 'ACTUAL_RECIPIENT_REQUIRED' } })).toBe(true);
    expect(isRetryablePaymentRouteError({ error: { code: 'TASK_TARGET_UNRESOLVED' } })).toBe(false);
  });

  it('requires an explicit acknowledgement for any projected target overrun', () => {
    expect(manualPaymentTaskOverrunAcknowledgementRequired({
      key: 'MANAGER:4',
      kind: 'MANAGER',
      profileId: 4,
      label: 'Менеджер · Вика',
      enabled: true,
      projectedOverrunKopecks: 1
    })).toBe(true);
    expect(manualPaymentTaskOverrunAcknowledgementRequired({
      key: 'OWNER', kind: 'OWNER', label: 'Владелец', enabled: true, projectedOverrunKopecks: 0
    })).toBe(false);
  });
});
