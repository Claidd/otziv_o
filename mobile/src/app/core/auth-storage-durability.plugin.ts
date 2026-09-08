import { Capacitor, registerPlugin } from '@capacitor/core';

export type AuthStorageStore = 'preferences' | 'secure';

export interface AuthStorageDurabilityPlugin {
  flush(options: { store: AuthStorageStore }): Promise<void>;
}

export const AuthStorageDurability = registerPlugin<AuthStorageDurabilityPlugin>('AuthStorageDurability');

export class AuthStorageDurabilityError extends Error {
  constructor(message: string, cause?: unknown) {
    super(message, { cause });
    this.name = 'AuthStorageDurabilityError';
  }
}

/** Android apply() completion does not acknowledge disk persistence. */
export async function flushAndroidAuthStorage(store: AuthStorageStore): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') return;
  if (!Capacitor.isPluginAvailable('AuthStorageDurability')) {
    throw new AuthStorageDurabilityError('Надёжное сохранение сессии на Android недоступно.');
  }
  try {
    await AuthStorageDurability.flush({ store });
  } catch (cause) {
    throw new AuthStorageDurabilityError(cause instanceof Error ? cause.message : 'Не удалось надёжно сохранить сессию на Android.', cause);
  }
}
