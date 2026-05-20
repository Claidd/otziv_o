const WORKER_CURRENT_SECTION_REQUEST_KEY = 'otziv-worker-current-section-request:v1';

export function requestWorkerCurrentSectionOpen(): void {
  try {
    window.sessionStorage.setItem(WORKER_CURRENT_SECTION_REQUEST_KEY, '1');
  } catch {
    // Session storage is optional; navigation still works without the first-entry hint.
  }
}

export function consumeWorkerCurrentSectionOpenRequest(): boolean {
  try {
    const requested = window.sessionStorage.getItem(WORKER_CURRENT_SECTION_REQUEST_KEY) === '1';
    window.sessionStorage.removeItem(WORKER_CURRENT_SECTION_REQUEST_KEY);
    return requested;
  } catch {
    return false;
  }
}
