import { clearAllSessionDrafts } from '../shared/session-draft-storage';

export const ACTIVE_DEEP_REPORT_COMPANY_KEY = 'otziv.reputationAi.activeDeepReportCompanyId';
export const ACTIVE_DEEP_REPORT_CONTEXT_KEY = 'otziv.reputationAi.activeDeepReportContext';

/** Forgets the deep-report job this browser was waiting for. */
export function clearStoredDeepReportMonitor(): void {
  try {
    const storage = typeof window === 'undefined' ? null : window.localStorage;
    storage?.removeItem(ACTIVE_DEEP_REPORT_COMPANY_KEY);
    storage?.removeItem(ACTIVE_DEEP_REPORT_CONTEXT_KEY);
  } catch {
    // Storage can be unavailable; there is nothing to clear then.
  }
}

/**
 * Browser state that belongs to the signed-in user and must not survive an
 * explicit sign-out: the next person in this browser must not inherit a
 * background report poll or unsaved form drafts.
 */
export function clearSessionScopedClientState(): void {
  clearStoredDeepReportMonitor();
  clearAllSessionDrafts();
}
