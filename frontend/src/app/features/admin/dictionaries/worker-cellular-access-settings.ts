import {
  WorkerCellularAccessMode,
  WorkerCellularAccessReason
} from '../../../core/admin-dictionaries.api';

export type WorkerCellularAccessFormValue = {
  workerCellularAccessMode: WorkerCellularAccessMode;
  blockNonCellularNetwork: boolean;
  blockVpnProxyOrDatacenter: boolean;
  blockDesktopOrUnknownDevice: boolean;
  blockUnknownNetwork: boolean;
};

export function workerCellularAccessReasons(
  value: WorkerCellularAccessFormValue
): WorkerCellularAccessReason[] {
  const reasons: WorkerCellularAccessReason[] = [];
  if (value.blockNonCellularNetwork) reasons.push('NON_CELLULAR_NETWORK');
  if (value.blockVpnProxyOrDatacenter) reasons.push('VPN_PROXY_OR_DATACENTER');
  if (value.blockDesktopOrUnknownDevice) reasons.push('DESKTOP_OR_UNKNOWN_DEVICE');
  if (value.blockUnknownNetwork) reasons.push('UNKNOWN_NETWORK');
  return reasons;
}

export function requiresWorkerCellularEnforceConfirmation(
  currentMode: WorkerCellularAccessMode | null | undefined,
  nextMode: WorkerCellularAccessMode
): boolean {
  return nextMode === 'ENFORCE' && currentMode !== 'ENFORCE';
}
