import { registerPlugin } from '@capacitor/core';

export type PreviousProcessExit = {
  timestamp: number;
  reason: string;
  reasonCode: number;
  status: number;
  importance: string;
  importanceCode: number;
  pssKb: number;
  rssKb: number;
  description: string;
  stateSummary: string;
  stateSource: 'android_exit_info' | 'local_fallback' | 'none';
  androidStateSummaryRejected: boolean;
};

export type PreviousProcessExitReport = {
  androidApi: number;
  supported: boolean;
  lowMemoryKillReportSupported: boolean;
  previousStateSummary: string;
  previousStateUpdatedAt: number;
  exits: PreviousProcessExit[];
};

export interface AppDiagnosticsPlugin {
  getPreviousExits(): Promise<PreviousProcessExitReport>;
  acknowledgePreviousExits(options: { throughTimestamp: number }): Promise<void>;
  setProcessStateSummary(options: { summary: string }): Promise<void>;
}

export const AppDiagnostics = registerPlugin<AppDiagnosticsPlugin>('AppDiagnostics');
