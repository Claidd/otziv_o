import {
  requiresWorkerCellularEnforceConfirmation,
  workerCellularAccessReasons
} from './worker-cellular-access-settings';

describe('worker cellular access settings', () => {
  it('maps selected UI restrictions to backend reason codes', () => {
    expect(workerCellularAccessReasons({
      workerCellularAccessMode: 'ENFORCE',
      blockNonCellularNetwork: true,
      blockVpnProxyOrDatacenter: true,
      blockDesktopOrUnknownDevice: false,
      blockUnknownNetwork: false
    })).toEqual(['NON_CELLULAR_NETWORK', 'VPN_PROXY_OR_DATACENTER']);
  });

  it('asks for confirmation only when entering enforce mode', () => {
    expect(requiresWorkerCellularEnforceConfirmation('AUDIT', 'ENFORCE')).toBe(true);
    expect(requiresWorkerCellularEnforceConfirmation('ENFORCE', 'ENFORCE')).toBe(false);
    expect(requiresWorkerCellularEnforceConfirmation('ENFORCE', 'AUDIT')).toBe(false);
  });
});
