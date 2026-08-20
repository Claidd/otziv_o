import { describe, expect, it } from 'vitest';
import {
  manualPaymentTaskRecommendedTarget,
  manualPaymentTaskTargetEffect,
  manualPaymentTaskTargetForSnapshot,
  manualPaymentTaskTargetValid
} from './manual-payment-task-target';

describe('manual payment task accounting target', () => {
  const options = [
    { key: 'EXTERNAL_TASK', kind: 'EXTERNAL_TASK' as const, label: 'Только задание', enabled: true },
    {
      key: 'SPECIALIST:25', kind: 'SPECIALIST' as const, profileId: 25,
      label: 'Специалист · Наталья', enabled: true, projectedOverrunKopecks: 250000,
      overrunAcknowledgementRequired: true
    }
  ];

  it('restores an exact profile target without matching by name', () => {
    expect(manualPaymentTaskTargetForSnapshot(options, {
      accountingTargetKind: 'SPECIALIST', accountingTargetProfileId: 25
    })?.key).toBe('SPECIALIST:25');
    expect(manualPaymentTaskTargetForSnapshot(options, {
      accountingTargetKind: 'SPECIALIST', accountingTargetProfileId: 26
    })).toBeNull();
  });

  it('blocks an overrun until it is explicitly acknowledged', () => {
    expect(manualPaymentTaskTargetValid(options[1], false)).toBe(false);
    expect(manualPaymentTaskTargetValid(options[1], true)).toBe(true);
  });

  it('explains that external-task accounting never changes a person limit', () => {
    expect(manualPaymentTaskTargetEffect(options[0])).toMatch(/лимиты владельца и сотрудников не изменятся/i);
  });

  it('uses only explicit server recommendation as creation default', () => {
    expect(manualPaymentTaskRecommendedTarget([
      { key: 'OWNER', kind: 'OWNER', label: 'Владелец', enabled: true },
      { key: 'MANAGER:7', kind: 'MANAGER', profileId: 7, label: 'Менеджер', enabled: true }
    ])).toBeNull();
    expect(manualPaymentTaskRecommendedTarget([
      { key: 'MANAGER:7', kind: 'MANAGER', profileId: 7, label: 'Менеджер', enabled: true, recommended: true },
      { key: 'SPECIALIST:8', kind: 'SPECIALIST', profileId: 8, label: 'Специалист', enabled: true, recommended: true }
    ])?.key).toBe('MANAGER:7');
  });
});
