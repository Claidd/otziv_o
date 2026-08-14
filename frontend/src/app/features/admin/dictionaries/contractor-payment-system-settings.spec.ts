import { describe, expect, it } from 'vitest';
import type { ContractorPaymentSystemStatus } from '../../../core/contractor-payments.api';
import {
  CONTRACTOR_ROUTING_ENABLE_CONFIRMATION,
  CONTRACTOR_ROUTING_PAUSE_CONFIRMATION,
  CONTRACTOR_SYSTEM_ACTIVATION_CONFIRMATION,
  canActivateContractorSystem,
  canChangeContractorRouting,
  contractorRoutingConfirmation,
  contractorSystemActivationDate,
  contractorSystemModeLabel
} from './contractor-payment-system-settings';

describe('contractor payment system settings', () => {
  it('defaults activation to the current business date without backdating', () => {
    expect(contractorSystemActivationDate('2026-08-07')).toBe('2026-08-07');
    expect(contractorSystemActivationDate('invalid')).toBe('');
  });

  it('uses the exact typed confirmations required by the backend contract', () => {
    expect(CONTRACTOR_SYSTEM_ACTIVATION_CONFIRMATION).toBe('ВКЛЮЧИТЬ НОВУЮ СИСТЕМУ');
    expect(contractorRoutingConfirmation(true)).toBe(CONTRACTOR_ROUTING_ENABLE_CONFIRMATION);
    expect(contractorRoutingConfirmation(false)).toBe(CONTRACTOR_ROUTING_PAUSE_CONFIRMATION);
  });

  it('allows irreversible activation only to an owner in an available legacy state', () => {
    const status = systemStatus();

    expect(canActivateContractorSystem(status, true)).toBe(true);
    expect(canActivateContractorSystem(status, false)).toBe(false);
    expect(canActivateContractorSystem({ ...status, activationAvailable: false }, true)).toBe(false);
    expect(canActivateContractorSystem({ ...status, systemEnabled: true, irreversible: true }, true)).toBe(false);
  });

  it('keeps routing control separate from irreversible accounting activation', () => {
    const legacy = systemStatus();
    const active = {
      ...legacy,
      mode: 'COMPLETION_ACTIVE' as const,
      systemEnabled: true,
      legacyBehavior: false,
      irreversible: true
    };

    expect(canChangeContractorRouting(legacy, true)).toBe(false);
    expect(canChangeContractorRouting(active, false)).toBe(false);
    expect(canChangeContractorRouting(active, true)).toBe(true);
    expect(contractorSystemModeLabel('ROUTING_PAUSED')).toBe('Реквизиты приостановлены');
  });
});

function systemStatus(): ContractorPaymentSystemStatus {
  return {
    mode: 'LEGACY',
    systemEnabled: false,
    legacyBehavior: true,
    irreversible: false,
    routingRequested: false,
    completionAccountingEffective: false,
    liveRoutingEffective: false,
    completionBacklogReady: true,
    activationAvailable: true,
    activationBlockedReasons: [],
    attributionStartDate: null,
    revision: 1,
    liveRoutingMasterEnabled: false,
    rewardAttributionMasterEnabled: false
  };
}
