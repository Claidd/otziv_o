type SessionDraftEnvelope<T> = {
  value: T;
  updatedAt: number;
};

const DRAFT_PREFIX = 'otziv:draft:';

export function readSessionDraft<T>(key: string): T | null {
  const storage = sessionDraftStorage();
  if (!storage) {
    return null;
  }

  try {
    const raw = storage.getItem(draftKey(key));
    if (!raw) {
      return null;
    }

    const envelope = JSON.parse(raw) as Partial<SessionDraftEnvelope<T>>;
    return envelope.value ?? null;
  } catch {
    removeSessionDraft(key);
    return null;
  }
}

export function writeSessionDraft<T>(key: string, value: T): void {
  const storage = sessionDraftStorage();
  if (!storage) {
    return;
  }

  try {
    storage.setItem(draftKey(key), JSON.stringify({
      value,
      updatedAt: Date.now()
    } satisfies SessionDraftEnvelope<T>));
  } catch {
    // Storage can be unavailable or full. Failing silently is safer than breaking editing.
  }
}

export function removeSessionDraft(key: string): void {
  const storage = sessionDraftStorage();
  if (!storage) {
    return;
  }

  try {
    storage.removeItem(draftKey(key));
  } catch {
    // Ignore storage failures.
  }
}

function draftKey(key: string): string {
  return `${DRAFT_PREFIX}${key}`;
}

function sessionDraftStorage(): Storage | null {
  return typeof window === 'undefined' ? null : window.sessionStorage;
}
