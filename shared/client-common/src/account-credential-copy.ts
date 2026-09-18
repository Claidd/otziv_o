export type AccountCredentialTarget = {
  id: number;
  botId?: number | null;
  recoveryTaskId?: number | null;
  badTaskId?: number | null;
};

export type AccountCredentialCopies = Record<string, { login?: boolean; password?: boolean }>;

function targetKey(target: AccountCredentialTarget): string {
  const card = target.recoveryTaskId ? `recovery:${target.recoveryTaskId}`
    : target.badTaskId ? `bad:${target.badTaskId}` : `review:${target.id}`;
  return `${card}:bot:${target.botId ?? 0}`;
}

/** Call only after both the credential request and clipboard write succeeded. */
export function recordAccountCredentialCopy(
  state: AccountCredentialCopies, target: AccountCredentialTarget, field: 'login' | 'password'
): AccountCredentialCopies {
  if (!target.botId) return state;
  const key = targetKey(target);
  return { ...state, [key]: { ...state[key], [field]: true } };
}

export function accountCredentialsCopied(state: AccountCredentialCopies, target: AccountCredentialTarget): boolean {
  if (!target.botId) return false;
  const fields = state[targetKey(target)];
  return !!fields?.login && !!fields?.password;
}
