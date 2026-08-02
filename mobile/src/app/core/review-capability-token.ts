const OPAQUE_TOKEN = /^rc1_[A-Za-z0-9_-]{43}$/;
const HISTORY_STATE_KEY = 'reviewCapabilityToken';

let capturedToken: string | null = null;

/** Capture before authentication startup and remove the secret from history. */
export function captureReviewCapabilityToken(): string | null {
  if (typeof window === 'undefined') {
    return null;
  }
  if (!window.location.pathname.replace(/\/+$/, '').endsWith('/review/c')) {
    return null;
  }

  const fragmentToken = window.location.hash.replace(/^#/, '');
  const stateToken = typeof window.history.state?.[HISTORY_STATE_KEY] === 'string'
    ? window.history.state[HISTORY_STATE_KEY]
    : '';
  const candidate = fragmentToken || stateToken || capturedToken || '';
  if (!OPAQUE_TOKEN.test(candidate)) {
    return null;
  }

  capturedToken = candidate;
  if (!fragmentToken) {
    return capturedToken;
  }
  try {
    const currentState = window.history.state && typeof window.history.state === 'object'
      ? window.history.state
      : {};
    window.history.replaceState(
      { ...currentState, [HISTORY_STATE_KEY]: capturedToken },
      document.title,
      `${window.location.pathname}${window.location.search}`
    );
  } catch {
    // Some embedded browsers do not expose mutable history.
  }
  return capturedToken;
}

export function reviewCapabilityToken(): string | null {
  return captureReviewCapabilityToken();
}
